package io.heapy.kodkod

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Exercises [Updater.runOnce] end-to-end against a [FakeDockerClient]. These cover the staleness
 * decision (`markStale` — digest-pinned / registry-digest / pull paths) and the recreate + rollback
 * machinery, all of which used to be reachable only from the Docker-backed e2e suite.
 */
class UpdaterTest {
    /**
     * Fake time for every updater under test: the liveness gate after a `start` polls on a real
     * interval, and no unit test may spend it. [FakeClock.sleeps] doubles as the assertion that the
     * gate exited early instead of burning its whole window.
     */
    private val clock = FakeClock()

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun updater(docker: DockerClient, config: Config = config()): Updater =
        Updater(docker, config, selfId = null, clock, clock)

    /**
     * [Log] writes to stdout, and a couple of behaviours here have no other output than the line they
     * log — "the rollback did not restore the service" is exactly the state an operator only learns
     * about from the log, so the log is what those tests assert on.
     */
    private fun captureLog(block: () -> Unit): String {
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer, true))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString()
    }

    private fun config(
        monitorAll: Boolean = true,
        cleanup: Boolean = true,
        stopTimeout: String? = null,
        verifySeconds: String? = null,
        verifyHealth: Boolean? = null,
        failureCooldown: String? = null,
        dependencyHealthTimeout: String? = null,
        respectDependsOnRestart: Boolean? = null,
    ): Config =
        Config.fromEnv(
            buildMap {
                put("KODKOD_UPDATE_MONITOR_ALL", monitorAll.toString())
                put("KODKOD_UPDATE_CLEANUP", cleanup.toString())
                stopTimeout?.let { put("KODKOD_STOP_TIMEOUT", it) }
                verifySeconds?.let { put("KODKOD_UPDATE_VERIFY_SECONDS", it) }
                verifyHealth?.let { put("KODKOD_UPDATE_VERIFY_HEALTH", it.toString()) }
                failureCooldown?.let { put("KODKOD_UPDATE_FAILURE_COOLDOWN", it) }
                dependencyHealthTimeout?.let { put("KODKOD_DEPENDENCY_HEALTH_TIMEOUT", it) }
                respectDependsOnRestart?.let { put("KODKOD_RESPECT_DEPENDS_ON_RESTART", it.toString()) }
            }::get,
        )

    /** The endpoint config the create body asks for on [network]. */
    private fun JsonObject.endpoint(network: String): JsonObject =
        obj("NetworkingConfig")?.obj("EndpointsConfig")?.obj(network) ?: error("no endpoint for '$network' in $this")

    /** Whether the daemon reports [id] as running right now. */
    private fun running(docker: FakeDockerClient, id: String): Boolean =
        docker.inspectContainer(id).obj("State")?.str("Running") == "true"

    /** Assert each op is present and that the listed ops occur in this relative order (first occurrence). */
    private fun assertOrder(ops: List<String>, vararg expected: String) {
        var last = -1
        for (op in expected) {
            val at = ops.indexOf(op)
            assertTrue(at >= 0, "expected op '$op' in $ops")
            assertTrue(at > last, "op '$op' (index $at) is out of order in $ops")
            last = at
        }
    }

    // --- markStale: when NOT to act -------------------------------------------------------

    @Test
    fun digest_pinned_images_are_never_checked_or_recreated() {
        val docker = FakeDockerClient()
        docker.container(id = "web", imageRef = "nginx@sha256:abc123")

        updater(docker).runOnce()

        assertTrue(docker.ops.isEmpty(), "digest-pinned container must not be pulled or recreated: ${docker.ops}")
    }

    @Test
    fun unlabelled_containers_are_ignored_when_not_monitoring_all() {
        val docker = FakeDockerClient()
        docker.container(id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old") // no enable label
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")

        updater(docker, config(monitorAll = false)).runOnce()

        assertTrue(docker.ops.isEmpty(), "without the enable label and monitorAll=false nothing should happen: ${docker.ops}")
    }

    @Test
    fun registry_digest_already_present_skips_the_pull() {
        val docker = FakeDockerClient()
        docker.container(
            id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old",
            currentRepoDigests = listOf("nginx@sha256:remote"),
        )
        docker.distribution["nginx:1.27"] = "sha256:remote"

        updater(docker).runOnce()

        assertTrue(docker.ops.isEmpty(), "running image already carries the registry digest — no work expected: ${docker.ops}")
    }

    @Test
    fun image_unchanged_after_pull_does_not_recreate() {
        val docker = FakeDockerClient()
        docker.container(id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old")
        // No distribution entry -> fall back to a pull; the pulled image id matches the running one.
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:old","Config":{},"RepoDigests":[]}""")

        updater(docker).runOnce()

        assertEquals(listOf("pull:nginx:1.27"), docker.ops, "a no-op update pulls to check, then stops")
    }

    // --- markStale: when TO act -----------------------------------------------------------

    @Test
    fun registry_digest_matching_a_newer_local_image_recreates_without_pulling() {
        val docker = FakeDockerClient()
        docker.container(id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old")
        docker.distribution["nginx:1.27"] = "sha256:remote"
        // The local repo:tag already resolves to a newer image than the running container.
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":["nginx@sha256:remote"]}""")

        updater(docker).runOnce()

        assertFalse(docker.ops.any { it.startsWith("pull:") }, "should reuse the present local image, not pull: ${docker.ops}")
        assertTrue(docker.ops.contains("create:web"), "stale container should be recreated: ${docker.ops}")
    }

    @Test
    fun pulls_and_recreates_when_pulled_image_differs() {
        val docker = FakeDockerClient()
        docker.container(id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old")
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")

        updater(docker).runOnce()

        assertOrder(docker.ops, "pull:nginx:1.27", "create:web")
    }

    // --- recreate -------------------------------------------------------------------------

    @Test
    fun recreate_replaces_the_container_and_prunes_the_old_image() {
        val docker = FakeDockerClient()
        docker.container(id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old")
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")

        updater(docker, config(cleanup = true)).runOnce()

        assertOrder(
            docker.ops,
            "rename:web->web_kodkod_old_web",
            "create:web",
            "start:new-web-0",
            "remove:web",
            "removeImage:sha256:old",
        )
        val (name, body) = docker.created.single()
        assertEquals("web", name)
        assertEquals("nginx:1.27", body.str("Image"), "the replacement is created against the new image ref")
    }

    @Test
    fun recreate_keeps_the_old_image_when_cleanup_is_disabled() {
        val docker = FakeDockerClient()
        docker.container(id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old")
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")

        updater(docker, config(cleanup = false)).runOnce()

        assertTrue(docker.ops.contains("create:web"))
        assertFalse(docker.ops.any { it.startsWith("removeImage:") }, "cleanup disabled must not prune the old image: ${docker.ops}")
    }

    // --- image cleanup --------------------------------------------------------------------

    /** A stale `app:latest` whose old image id still answers inspect with [repoTags]. */
    private fun staleApp(docker: FakeDockerClient, repoTags: String) {
        docker.container(id = "web", imageRef = "app:latest", currentImageId = "sha256:old")
        docker.images["sha256:old"] = json("""{"Id":"sha256:old","Config":{},"RepoDigests":[],"RepoTags":$repoTags}""")
        docker.images["app:latest"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[],"RepoTags":["app:latest"]}""")
    }

    @Test
    fun a_single_remaining_user_tag_saves_the_old_image_from_the_prune() {
        val docker = FakeDockerClient()
        // `app:latest` moved to the new image, but the operator's pinned rollback tag stayed behind.
        staleApp(docker, """["app:1.26"]""")

        updater(docker, config(cleanup = true)).runOnce()

        assertTrue(docker.ops.contains("create:web"), "the update itself must still happen: ${docker.ops}")
        assertTrue(
            docker.removedImages.isEmpty(),
            "deleting an image that carries exactly one foreign tag succeeds and silently untags the " +
                "operator's rollback image — two tags would already have been refused with 409: ${docker.removedImages}",
        )
    }

    @Test
    fun only_the_updated_tag_left_on_the_old_image_still_prunes() {
        val docker = FakeDockerClient()
        staleApp(docker, """["app:latest"]""")

        updater(docker, config(cleanup = true)).runOnce()

        assertEquals(listOf("sha256:old"), docker.removedImages, "nobody else references it: ${docker.removedImages}")
    }

    @Test
    fun a_dangling_old_image_is_pruned() {
        val docker = FakeDockerClient()
        staleApp(docker, """["<none>:<none>"]""")

        updater(docker, config(cleanup = true)).runOnce()

        assertEquals(listOf("sha256:old"), docker.removedImages, "an untagged image is ours to reclaim: ${docker.removedImages}")
    }

    /**
     * `RepoTags` is what the daemon normalised the tag to (`nginx:1.27`), while the ref being updated
     * is whatever the container's `Config.Image` says. Compared raw, the one tag on the image never
     * matches the ref it belongs to, every prune stands down, and `KODKOD_UPDATE_CLEANUP=true` does
     * nothing at all.
     */
    @Test
    fun a_hub_reference_matches_the_tag_the_daemon_recorded_for_it() {
        val docker = FakeDockerClient()
        docker.container(id = "web", imageRef = "docker.io/library/nginx:1.27", currentImageId = "sha256:old")
        docker.images["sha256:old"] =
            json("""{"Id":"sha256:old","Config":{},"RepoDigests":[],"RepoTags":["nginx:1.27"]}""")
        docker.images["docker.io/library/nginx:1.27"] =
            json("""{"Id":"sha256:new","Config":{},"RepoDigests":[],"RepoTags":["nginx:1.27"]}""")

        updater(docker, config(cleanup = true)).runOnce()

        assertEquals(
            listOf("sha256:old"), docker.removedImages,
            "the only tag left on the old image is the one that moved to the new one: ${docker.removedImages}",
        )
    }

    @Test
    fun an_unreadable_old_image_is_left_alone() {
        val docker = FakeDockerClient()
        staleApp(docker, """[]""")
        docker.images.remove("sha256:old") // inspect of the old image now fails, as it would mid-prune

        updater(docker, config(cleanup = true)).runOnce()

        assertTrue(docker.ops.contains("create:web"), "the update itself must still happen: ${docker.ops}")
        assertTrue(
            docker.removedImages.isEmpty(),
            "without an answer about its tags, deleting the image is a guess: ${docker.removedImages}",
        )
    }

    /**
     * [resolveMounts] itself is covered in `ImageDefaultsTest`; what is covered here is that `recreate`
     * feeds it the container's own arrays. Handing it the wrong ones would keep every one of those
     * tests green while the replacement silently came up with fresh, empty volumes.
     */
    @Test
    fun the_volumes_the_container_is_running_with_are_carried_into_the_create_body() {
        val docker = FakeDockerClient()
        docker.container(
            id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old",
            mounts = """[{"Type":"volume","Name":"vol0123456789","Destination":"/data","RW":true}]""",
        )
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")

        updater(docker).runOnce()

        val mount = docker.created.single().second.obj("HostConfig")?.arr("Mounts")?.single()?.jsonObject
        assertEquals("vol0123456789", mount?.str("Source"), "the replacement must re-attach the same volume")
        assertEquals("/data", mount?.str("Target"), "at the destination the data is expected at")
    }

    @Test
    fun recreate_puts_the_first_network_in_the_create_body_and_connects_the_rest() {
        val docker = FakeDockerClient()
        docker.container(
            id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old",
            networks = """{"frontend":{},"backend":{}}""",
        )
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")

        updater(docker).runOnce()

        val (_, body) = docker.created.single()
        val endpoints = body.obj("NetworkingConfig")?.obj("EndpointsConfig")
        assertEquals(setOf("frontend"), endpoints?.keys, "only the first network belongs in the create body")
        assertTrue(docker.ops.contains("connect:backend:new-web-0"), "the rest are connected after create: ${docker.ops}")
    }

    // --- endpoint fidelity ----------------------------------------------------------------

    @Test
    fun recreate_keeps_an_explicit_mac_address_and_the_gateway_priority() {
        val docker = FakeDockerClient()
        docker.container(
            id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old",
            configMacAddress = "02:42:ac:11:00:99", // what `--mac-address` / compose `mac_address:` sets
            networks = """{"frontend":{"MacAddress":"02:42:ac:11:00:99","GwPriority":100}}""",
        )
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")

        updater(docker).runOnce()

        val endpoint = docker.created.single().second.endpoint("frontend")
        assertEquals(
            "02:42:ac:11:00:99", endpoint.str("MacAddress"),
            "an explicitly requested MAC is configuration and must survive recreate: $endpoint",
        )
        assertEquals(
            "100", endpoint.str("GwPriority"),
            "the gateway priority decides which network provides the default route: $endpoint",
        )
    }

    @Test
    fun recreate_does_not_pin_a_daemon_generated_mac_address() {
        val docker = FakeDockerClient()
        docker.container(
            id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old",
            // No `Config.MacAddress`, so the endpoint MAC below was generated by the daemon.
            networks = """{"frontend":{"MacAddress":"82:b1:9a:7b:ef:36","GwPriority":0}}""",
        )
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")

        updater(docker).runOnce()

        val endpoint = docker.created.single().second.endpoint("frontend")
        assertNull(
            endpoint.str("MacAddress"),
            "copying a random MAC into the replacement invents configuration nobody asked for: $endpoint",
        )
        assertEquals("0", endpoint.str("GwPriority"), "the gateway priority is copied regardless of the MAC: $endpoint")
    }

    @Test
    fun an_empty_config_mac_address_counts_as_unset() {
        val docker = FakeDockerClient()
        docker.container(
            id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old",
            configMacAddress = "", // how a container without `--mac-address` reports it
            networks = """{"frontend":{"MacAddress":"82:b1:9a:7b:ef:36"}}""",
        )
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")

        updater(docker).runOnce()

        val endpoint = docker.created.single().second.endpoint("frontend")
        assertNull(endpoint.str("MacAddress"), "an empty `Config.MacAddress` is not an explicit MAC: $endpoint")
    }

    /**
     * `host` and `none` are reported as pseudo-networks in `NetworkSettings.Networks` (with a real
     * `EndpointID` and a `GwPriority`, verified against Docker 29.6.2), so copying endpoints blindly
     * would put `EndpointsConfig={"host":{"GwPriority":0}}` next to `NetworkMode=host` in the create
     * body — a combination the daemon refuses. `HostConfig.NetworkMode` alone is authoritative here.
     */
    @Test
    fun a_host_network_container_is_recreated_without_any_endpoint() {
        val docker = FakeDockerClient()
        docker.container(
            id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old",
            hostConfig = """{"NetworkMode":"host"}""",
            networks = """{"host":{"GwPriority":0,"EndpointID":"dc17b7805866"}}""",
        )
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")

        updater(docker).runOnce()

        val body = docker.created.single().second
        assertNull(body["NetworkingConfig"], "a host-mode container has no endpoint to attach: $body")
        assertEquals(
            "host", body.obj("HostConfig")?.str("NetworkMode"),
            "the network mode is the whole networking configuration of such a container: $body",
        )
        assertTrue(docker.ops.none { it.startsWith("connect:") }, "nothing to connect afterwards: ${docker.ops}")
    }

    @Test
    fun a_none_network_container_is_recreated_without_any_endpoint() {
        val docker = FakeDockerClient()
        docker.container(
            id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old",
            hostConfig = """{"NetworkMode":"none"}""",
            networks = """{"none":{"GwPriority":0,"EndpointID":"859033cefdd1"}}""",
        )
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")

        updater(docker).runOnce()

        val body = docker.created.single().second
        assertNull(body["NetworkingConfig"], "`none` means the replacement stays off every network: $body")
        assertEquals("none", body.obj("HostConfig")?.str("NetworkMode"), "the mode itself must survive: $body")
        assertTrue(docker.ops.none { it.startsWith("connect:") }, "nothing to connect afterwards: ${docker.ops}")
    }

    // --- com.docker.compose.image ---------------------------------------------------------

    @Test
    fun recreate_stamps_the_new_image_id_into_the_compose_image_label() {
        val docker = FakeDockerClient()
        docker.container(
            id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old",
            labels = """{"com.docker.compose.image":"sha256:old","com.docker.compose.config-hash":"h1"}""",
        )
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")

        updater(docker).runOnce()

        val labels = docker.created.single().second.obj("Labels")!!
        assertEquals(
            "sha256:new", labels.label("com.docker.compose.image"),
            "a stale label makes the next `compose up` recreate the container kodkod just updated",
        )
        assertEquals("h1", labels.label("com.docker.compose.config-hash"), "the config hash describes the compose file we did not touch")
    }

    @Test
    fun the_compose_image_label_is_stamped_on_the_registry_digest_path_too() {
        val docker = FakeDockerClient()
        docker.container(
            id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old",
            labels = """{"com.docker.compose.image":"sha256:old"}""",
        )
        docker.distribution["nginx:1.27"] = "sha256:remote"
        // The local repo:tag already resolves to a newer image, so this path never pulls.
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":["nginx@sha256:remote"]}""")

        updater(docker).runOnce()

        val labels = docker.created.single().second.obj("Labels")!!
        assertEquals("sha256:new", labels.label("com.docker.compose.image"))
    }

    @Test
    fun a_container_recreated_for_a_dependency_keeps_its_compose_image_label() {
        val docker = FakeDockerClient()
        // db's image moved; web is up to date but --link'ed to db, so it is recreated, not updated.
        docker.container(id = "db", imageRef = "db:1", currentImageId = "sha256:db-old")
        docker.container(
            id = "web", imageRef = "web:1", currentImageId = "sha256:web-old",
            currentRepoDigests = listOf("web@sha256:web-remote"),
            labels = """{"com.docker.compose.image":"sha256:web-old"}""",
            hostConfig = """{"Links":["/db:/web/db"]}""",
        )
        docker.distribution["db:1"] = "sha256:db-remote"
        docker.images["db:1"] = json("""{"Id":"sha256:db-new","Config":{},"RepoDigests":["db@sha256:db-remote"]}""")
        docker.distribution["web:1"] = "sha256:web-remote"
        // The tag still names the image web is running, so the recreate really is faithful.
        docker.images["web:1"] = json("""{"Id":"sha256:web-old","Config":{},"RepoDigests":[]}""")

        updater(docker).runOnce()

        val web = docker.created.single { (name, _) -> name == "web" }.second
        assertEquals(
            "sha256:web-old", web.obj("Labels").label("com.docker.compose.image"),
            "web's own image did not change — the label must be copied verbatim",
        )
    }

    /**
     * The other half: a dependency-driven recreate creates from the image *ref* too, so a tag that
     * moved under the container (someone else's `docker pull`, a sibling service on the same tag)
     * makes the replacement run a different image after all. Leaving `com.docker.compose.image` naming
     * the old one is what makes the next `docker compose up` recreate the container all over again.
     */
    @Test
    fun a_dependency_driven_recreate_onto_a_moved_tag_restamps_the_label_and_says_so() {
        val docker = FakeDockerClient()
        docker.container(id = "db", imageRef = "db:1", currentImageId = "sha256:db-old")
        docker.container(
            id = "web", imageRef = "web:1", currentImageId = "sha256:web-old",
            currentRepoDigests = listOf("web@sha256:web-remote"),
            labels = """{"com.docker.compose.image":"sha256:web-old"}""",
            hostConfig = """{"Links":["/db:/web/db"]}""",
        )
        docker.distribution["db:1"] = "sha256:db-remote"
        docker.images["db:1"] = json("""{"Id":"sha256:db-new","Config":{},"RepoDigests":["db@sha256:db-remote"]}""")
        // web is up to date as far as the registry goes, but the local tag has moved on anyway.
        docker.distribution["web:1"] = "sha256:web-remote"
        docker.images["web:1"] = json("""{"Id":"sha256:web-moved","Config":{},"RepoDigests":[]}""")

        val log = captureLog { updater(docker).runOnce() }

        val web = docker.created.single { (name, _) -> name == "web" }.second
        assertEquals(
            "sha256:web-moved", web.obj("Labels").label("com.docker.compose.image"),
            "the label has to name the image the replacement actually runs: $web",
        )
        assertEquals("web:1", web.str("Image"), "and the ref stays a ref — an id there has no tag to follow")
        assertTrue(log.contains("has moved to"), "a recreate that silently changes the image must not be silent: $log")
    }

    @Test
    fun a_container_without_compose_labels_does_not_get_one_invented() {
        val docker = FakeDockerClient()
        docker.container(id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old")
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")

        updater(docker).runOnce()

        val body = docker.created.single().second
        assertNull(body.obj("Labels").label("com.docker.compose.image"), "kodkod must not fabricate compose metadata: $body")
    }

    // --- platform -------------------------------------------------------------------------

    @Test
    fun the_running_image_platform_is_pinned_on_both_pull_and_create() {
        val docker = FakeDockerClient()
        docker.container(
            id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old",
            imageManifestPlatform = """{"architecture":"amd64","os":"linux"}""",
        )
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")

        updater(docker).runOnce()

        assertOrder(docker.ops, "pull:nginx:1.27", "create:web")
        assertEquals(
            listOf("linux/amd64", "linux/amd64"), docker.platforms,
            "an amd64 container on an arm64 host must be updated with, and recreated from, amd64: ${docker.platforms}",
        )
    }

    @Test
    fun a_container_without_an_image_manifest_descriptor_sends_no_platform() {
        val docker = FakeDockerClient()
        docker.container(id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old") // pre-descriptor engine
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")

        updater(docker).runOnce()

        assertEquals(
            listOf(null, null), docker.platforms,
            "with nothing to read, the daemon must keep choosing its own default: ${docker.platforms}",
        )
    }

    @Test
    fun the_manifest_variant_is_not_pinned() {
        val docker = FakeDockerClient()
        docker.container(
            id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old",
            // The OLD image resolved to the v8 variant; the new one may not publish that variant at all.
            imageManifestPlatform = """{"architecture":"arm64","os":"linux","variant":"v8"}""",
        )
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")

        updater(docker).runOnce()

        assertEquals(
            listOf("linux/arm64", "linux/arm64"), docker.platforms,
            "pinning the old image's variant risks 'no matching manifest' on the new one: ${docker.platforms}",
        )
        assertTrue(docker.ops.contains("create:web"), "the update must still go through: ${docker.ops}")
    }

    // --- rollback -------------------------------------------------------------------------

    @Test
    fun a_failed_create_rolls_back_to_the_original_container() {
        val docker = FakeDockerClient()
        docker.container(id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old")
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")
        docker.failCreate += "web"

        updater(docker).runOnce() // runOnce logs and swallows the recreate failure

        // `create!:` is an attempted create — the fake never lets a failed mutation read as a done one.
        assertOrder(docker.ops, "rename:web->web_kodkod_old_web", "create!:web", "rename:web->web", "start:web")
        assertFalse(docker.ops.contains("create:web"), "the create failed and must not read as done: ${docker.ops}")
        assertFalse(docker.ops.contains("remove:web"), "the original container must survive a failed create: ${docker.ops}")
        assertTrue(docker.created.isEmpty(), "no replacement should have been recorded: ${docker.created}")
    }

    @Test
    fun a_failed_start_removes_the_replacement_and_rolls_back() {
        val docker = FakeDockerClient()
        docker.container(id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old")
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")
        docker.failStart += "new-web-0" // the freshly-created container fails to start

        updater(docker).runOnce()

        assertOrder(docker.ops, "create:web", "start!:new-web-0", "remove:new-web-0", "rename:web->web", "start:web")
        assertFalse(docker.ops.contains("start:new-web-0"), "the start failed and must not read as done: ${docker.ops}")
        assertFalse(docker.ops.contains("remove:web"), "the original container must survive a failed start: ${docker.ops}")
        assertTrue(running(docker, "web"), "the service must be serving again after the rollback")
    }

    @Test
    fun a_replacement_that_cannot_be_removed_is_moved_off_the_service_name() {
        val docker = FakeDockerClient()
        staleWeb(docker)
        docker.failStart += "new-web-0" // the replacement never comes up...
        docker.failRemove += "new-web-0" // ...and cannot be deleted either, so it still holds "web"

        updater(docker).runOnce()

        assertOrder(
            docker.ops,
            "remove!:new-web-0",
            "rename!:web->web", // 409: the corpse owns the name
            // Parked, but stopped first: a replacement that failed the gate may still be *running*, and
            // a running blocker keeps this service's published ports — the `start:web` below would then
            // fail on a port conflict and the rollback would end in ROLLBACK INCOMPLETE.
            "stop:new-web-0",
            "rename:new-web-0->web_kodkod_failed_new-web-0",
            "rename:web->web",
            "start:web",
        )
        assertEquals(
            "/web", docker.inspectContainer("web").str("Name"),
            "leaving the service under its _kodkod_old_ backup name while a dead container owns the real " +
                "one is the outage this rollback exists to prevent: ${docker.ops}",
        )
        assertTrue(running(docker, "web"), "and it has to be running, not merely named right")
    }

    @Test
    fun a_rollback_that_cannot_restore_the_name_says_so_instead_of_reporting_success() {
        val docker = FakeDockerClient()
        staleWeb(docker)
        docker.failStart += "new-web-0"
        docker.failRename += "web" // the daemon refuses the name no matter what is cleared out of the way

        val log = captureLog { updater(docker).runOnce() }

        assertTrue(
            log.contains("[web] ROLLBACK INCOMPLETE"),
            "a rollback whose rename never landed must be reported, not verified by hope: $log",
        )
        assertTrue(log.contains("web_kodkod_old_web"), "the log has to name where the container actually is: $log")
        assertFalse(docker.ops.contains("remove:web"), "and it must still not destroy the old container: ${docker.ops}")
    }

    /**
     * The rollback runs on the worker thread, and a shutdown interrupts exactly that thread. Every
     * Docker call goes through a NIO channel, which refuses to do anything at all while the calling
     * thread's interrupt flag is set — so a rollback that does not clear it first cannot rename the
     * container back or start it, and the service stays down under its backup name with both failures
     * merely logged.
     */
    @Test
    fun a_rollback_on_an_interrupted_thread_still_restores_the_service() {
        val docker = FakeDockerClient()
        staleWeb(docker)
        try {
            updater(InterruptedMidRecreate(docker)).runOnce()

            assertOrder(docker.ops, "rename:web->web_kodkod_old_web", "rename:web->web", "start:web")
            assertEquals("/web", docker.inspectContainer("web").str("Name"), "the service must have its name back")
            assertTrue(running(docker, "web"), "and it has to be running, not merely named right")
            assertTrue(
                Thread.currentThread().isInterrupted,
                "the interrupt belongs to whoever asked for the shutdown and must be handed back",
            )
        } finally {
            Thread.interrupted() // never leak the flag into the next test on this thread
        }
    }

    // --- liveness gate --------------------------------------------------------------------

    @Test
    fun a_replacement_that_starts_and_exits_is_rolled_back_and_destroys_nothing() {
        val docker = FakeDockerClient()
        staleWeb(docker)
        docker.startedThenExits += "new-web-0" // `POST /start` succeeds, the process dies anyway

        updater(docker).runOnce()

        assertOrder(docker.ops, "start:new-web-0", "remove:new-web-0", "rename:web->web", "start:web")
        assertFalse(
            docker.ops.contains("remove:web"),
            "the old container is the only way back and must survive a replacement that died: ${docker.ops}",
        )
        assertTrue(
            docker.removedImages.isEmpty(),
            "the old image is what a rollback runs on — it must not be pruned either: ${docker.removedImages}",
        )
    }

    @Test
    fun a_live_replacement_ends_the_wait_early() {
        val docker = FakeDockerClient()
        staleWeb(docker)

        updater(docker).runOnce()

        assertOrder(docker.ops, "start:new-web-0", "remove:web", "removeImage:sha256:old")
        assertEquals(
            listOf(500L, 500L), clock.sleeps,
            "three good probes end the wait; the default 15s window would be 30 sleeps: ${clock.sleeps}",
        )
    }

    @Test
    fun a_restart_looping_replacement_is_rolled_back() {
        val docker = FakeDockerClient()
        staleWeb(docker)
        // A container with a restart policy that keeps crashing: the daemon reports it as restarting.
        docker.containers["new-web-0"] = json("""{"Name":"/web","State":{"Restarting":true}}""")

        updater(docker).runOnce()

        assertOrder(docker.ops, "start:new-web-0", "remove:new-web-0", "rename:web->web", "start:web")
        assertFalse(docker.ops.contains("remove:web"), "a crash loop is a failed update: ${docker.ops}")
    }

    @Test
    fun an_unhealthy_replacement_is_rolled_back() {
        val docker = FakeDockerClient()
        staleWeb(docker)
        docker.health["new-web-0"] = "unhealthy" // the healthcheck already failed its retries

        updater(docker).runOnce()

        assertOrder(docker.ops, "start:new-web-0", "remove:new-web-0", "rename:web->web", "start:web")
        assertFalse(docker.ops.contains("remove:web"), "a container that fails its own healthcheck is not up: ${docker.ops}")
    }

    @Test
    fun an_unhealthy_replacement_is_accepted_when_health_verification_is_off() {
        val docker = FakeDockerClient()
        staleWeb(docker)
        docker.health["new-web-0"] = "unhealthy"

        updater(docker, config(verifyHealth = false)).runOnce()

        assertTrue(
            docker.ops.contains("remove:web"),
            "with KODKOD_UPDATE_VERIFY_HEALTH=false only a dead process blocks the update: ${docker.ops}",
        )
    }

    /**
     * The gate used to pass on zero evidence: an unreadable probe counted as "not settled yet", and the
     * end of the window returned normally whether or not anything had ever answered — after which the
     * old container is force-removed and its image pruned. "We could not look" is not "it is fine".
     */
    @Test
    fun a_replacement_that_could_never_be_inspected_fails_the_gate() {
        val docker = FakeDockerClient()
        staleWeb(docker)
        docker.failInspect += "new-web-0" // the daemon answers nothing about the replacement, ever

        val log = captureLog { updater(docker, config(verifySeconds = "2")).runOnce() }

        assertOrder(docker.ops, "start:new-web-0", "remove:new-web-0", "rename:web->web", "start:web")
        assertFalse(
            docker.ops.contains("remove:web"),
            "destroying the only way back on a window that never got an answer: ${docker.ops}",
        )
        assertTrue(docker.removedImages.isEmpty(), "and the old image goes with it: ${docker.removedImages}")
        assertEquals(
            1, log.lines().count { it.contains("could not probe the replacement") },
            "one line per unreadable window, not one per probe: $log",
        )
    }

    /**
     * ...and it says nothing about the *image*. The blame flag is raised before `start`, so a window
     * that failed for want of an answer used to buy the image a `KODKOD_UPDATE_FAILURE_COOLDOWN` — six
     * hours of a service left on its old image because the socket blipped for a second. Worst with the
     * documented `KODKOD_UPDATE_VERIFY_SECONDS=0`, where one unreadable inspect *is* the whole window.
     */
    @Test
    fun a_gate_that_never_got_an_answer_is_not_held_against_the_image() {
        val docker = FakeDockerClient()
        staleWeb(docker)
        docker.failInspect += "new-web-0"
        val updater = updater(docker, config(verifySeconds = "0"))

        updater.runOnce()
        val afterFirstCycle = docker.ops.size
        docker.failInspect.clear() // the blip is over; the next replacement answers like any other

        val log = captureLog { updater.runOnce() }

        assertTrue(
            docker.ops.drop(afterFirstCycle).contains("create:web"),
            "the image was never seen failing — holding the update back for six hours over an unread " +
                "probe is a self-inflicted outage of its own: ${docker.ops}",
        )
        assertFalse(log.contains("skipping this update"), "nothing was learned about the image: $log")
    }

    /**
     * A `404` is the one probe error that is an answer — and the worst one. An `AutoRemove` inherited
     * from the old container (or somebody's `docker rm`) makes the replacement disappear the moment it
     * exits, which without this reads as "still starting" for the whole window and then as success.
     */
    @Test
    fun a_replacement_the_daemon_has_forgotten_is_a_failed_update() {
        val docker = FakeDockerClient()
        staleWeb(docker)
        docker.vanishesAfterStart += "new-web-0"

        updater(docker).runOnce()

        assertOrder(docker.ops, "start:new-web-0", "rename:web->web", "start:web")
        assertFalse(docker.ops.contains("remove:web"), "the old container is the only copy left: ${docker.ops}")
        assertEquals(
            emptyList<Long>(), clock.sleeps,
            "a container the daemon does not know is not a container that needs more time: ${clock.sleeps}",
        )
    }

    @Test
    fun a_replacement_still_inside_its_start_period_is_accepted_at_the_end_of_the_window() {
        val docker = FakeDockerClient()
        staleWeb(docker)
        docker.health["new-web-0"] = "starting" // never becomes healthy within the window

        updater(docker, config(verifySeconds = "2")).runOnce()

        assertTrue(
            docker.ops.contains("remove:web"),
            "start_period is the image author's own startup budget — rolling back on it would " +
                "revert healthy updates of every slow-starting service: ${docker.ops}",
        )
        assertEquals(
            4, clock.sleeps.size,
            "a container that never settles must be waited out to the end of the 2s window: ${clock.sleeps}",
        )
    }

    /**
     * Elapsed time is not wall time. Every window used to be `currentTimeMillis() + n`, so an NTP
     * correction landing inside the liveness gate moved the finish line: backwards, and the gate waits
     * the whole correction out *while `apply` holds the cycle lock*, blocking autoheal with it;
     * forwards, and the window ends early and accepts a replacement that is still dying — moments before
     * the container and image it replaced are destroyed.
     */
    @Test
    fun the_liveness_window_is_measured_in_elapsed_time_not_wall_time() {
        val docker = FakeDockerClient()
        staleWeb(docker)
        docker.health["new-web-0"] = "starting" // never settles, so the window has to run out on its own
        val corrected = CorrectedClock(clock, stepMs = -400)

        Updater(docker, config(verifySeconds = "2"), selfId = null, corrected, corrected).runOnce()

        assertEquals(
            4, clock.sleeps.size,
            "a 2s window is 2s of elapsed time; measured against a wall clock walking backwards under it, " +
                "the same window takes as long as the correction says: ${clock.sleeps}",
        )
    }

    // --- memory of an image that cannot start ---------------------------------------------

    /** A stale `web` whose replacement starts and dies, so the first cycle ends in a rollback. */
    private fun poisonedWeb(docker: FakeDockerClient) {
        staleWeb(docker)
        docker.startedThenExits += "new-web-0"
    }

    /** The default `KODKOD_UPDATE_FAILURE_COOLDOWN`, in milliseconds. */
    private val cooldown = 6 * 60 * 60 * 1000L

    /**
     * The defect this memory exists for: [Updater] kept nothing between cycles, so an image that cannot
     * start made *every* cycle stop the healthy container, rename it, create a replacement, fail and
     * roll back — a self-inflicted outage once per `KODKOD_UPDATE_INTERVAL`, forever.
     */
    @Test
    fun an_image_that_could_not_come_up_is_not_tried_again_next_cycle() {
        val docker = FakeDockerClient()
        poisonedWeb(docker)
        val updater = updater(docker)

        updater.runOnce()
        val afterFirstCycle = docker.ops.size
        val log = captureLog { updater.runOnce() }

        assertEquals(
            listOf("pull:nginx:1.27"), docker.ops.drop(afterFirstCycle),
            "checking the registry again is fine; stopping and renaming a healthy container to repeat a " +
                "failure kodkod already caused is the outage itself: ${docker.ops}",
        )
        assertTrue(running(docker, "web"), "the container that survived the first attempt keeps serving")
        assertTrue(log.contains("skipping this update"), "a skipped update must not be silent: $log")
        assertTrue(log.contains("next attempt no earlier than"), "and it has to say for how long: $log")
    }

    @Test
    fun the_cooldown_running_out_lets_the_update_be_tried_again() {
        val docker = FakeDockerClient()
        poisonedWeb(docker)
        val updater = updater(docker)

        updater.runOnce()
        val afterFirstCycle = docker.ops.size
        clock.advance(cooldown)

        updater.runOnce()

        assertTrue(
            docker.ops.drop(afterFirstCycle).contains("create:web"),
            "this is a cooldown, not a blacklist — an image fixed in place has to be picked up: ${docker.ops}",
        )
    }

    @Test
    fun a_tag_moving_to_another_image_clears_the_memory_at_once() {
        val docker = FakeDockerClient()
        poisonedWeb(docker)
        val updater = updater(docker)

        updater.runOnce()
        val afterFirstCycle = docker.ops.size
        // The operator pushed a fix: the tag no longer resolves to the image that failed.
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:fixed","Config":{},"RepoDigests":[]}""")

        updater.runOnce()

        assertTrue(
            docker.ops.drop(afterFirstCycle).contains("create:web"),
            "only the exact image that failed is suppressed; making the fix for an outage wait out the " +
                "cooldown of the outage would be worse than not remembering at all: ${docker.ops}",
        )
    }

    @Test
    fun an_update_that_finally_worked_is_not_remembered_as_a_failure() {
        val docker = FakeDockerClient()
        poisonedWeb(docker)
        val updater = updater(docker)

        updater.runOnce()
        clock.advance(cooldown)
        updater.runOnce() // the retry comes up this time and the update completes
        val afterSuccess = docker.ops.size
        // A third image is published right away — well inside the window the failure had bought.
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:newer","Config":{},"RepoDigests":[]}""")

        updater.runOnce()

        assertTrue(docker.ops.contains("remove:web"), "the retry has to have actually gone through: ${docker.ops}")
        assertTrue(
            docker.ops.drop(afterSuccess).any { it.startsWith("create:") },
            "the memory records failures, not attempts: a service whose update went through carries no " +
                "cooldown into later cycles: ${docker.ops}",
        )
    }

    /**
     * `web` is joined to `db`'s network namespace by *id*, the way compose writes `network_mode:
     * service:db`, and its own update is inside the cooldown a failed attempt bought. Replacing `db`
     * would force `web` through a recreate built from `web:1`, which by now names exactly the image
     * that failed here — a recreate that is certain to fail, and whose rollback cannot work either:
     * `db`'s old container is force-removed the moment its replacement is accepted, so the `web` that
     * would be started back is joined to a namespace that no longer exists.
     *
     * Neither half of that may happen, and leaving `web` out of the cycle is no better — it would go
     * on reporting `Running` with no interfaces. So `db` is the one that waits.
     */
    @Test
    fun a_provider_waits_while_its_netns_consumer_is_held_back_by_a_cooldown() {
        val docker = FakeDockerClient()
        // web shares db's network namespace, so an update of db normally recreates web too.
        docker.container(
            id = "db1234567890abc", name = "db", imageRef = "db:1", currentImageId = "sha256:db-old",
            currentRepoDigests = listOf("db@sha256:db-1"),
        )
        docker.container(
            id = "web", imageRef = "web:1", currentImageId = "sha256:web-old",
            hostConfig = """{"NetworkMode":"container:db1234567890abc"}""",
        )
        docker.distribution["db:1"] = "sha256:db-1" // db is up to date in the first cycle
        docker.images["web:1"] = json("""{"Id":"sha256:web-bad","Config":{},"RepoDigests":[]}""")
        docker.startedThenExits += "new-web-0" // web's own update cannot come up
        val updater = updater(docker)

        updater.runOnce()
        val afterFirstCycle = docker.ops.size
        // Now db's image moves. web is still inside the cooldown its failed update bought.
        docker.distribution["db:1"] = "sha256:db-2"
        docker.images["db:1"] = json("""{"Id":"sha256:db-new","Config":{},"RepoDigests":["db@sha256:db-2"]}""")

        val log = captureLog { updater.runOnce() }

        val second = docker.ops.drop(afterFirstCycle)
        assertTrue(
            second.none { it.startsWith("create:") || it.startsWith("stop:") },
            "nothing may move: updating db forces a recreate of web that cannot be undone: $second",
        )
        assertTrue(running(docker, "db1234567890abc"), "db keeps serving on its old image: ${docker.ops}")
        assertTrue(running(docker, "web"), "and web keeps serving, which is the whole point: ${docker.ops}")
        assertTrue(log.contains("not restarting it this cycle"), "the delay has to be announced: $log")
        assertTrue(log.contains("skipping this update"), "web's own update is still held back: $log")
    }

    /**
     * And the hold-back reaches exactly as far as the reason for it. `web` is an ordinary `depends_on`
     * dependent, so replacing `db` costs it a restart and nothing else — the container it would be
     * rolled back to is the one that is running, and it does not care which `db` process it was
     * started against. Holding `db` back here would trade a real update for no risk at all.
     */
    @Test
    fun a_plain_dependent_inside_its_cooldown_does_not_keep_its_dependency_back() {
        val docker = FakeDockerClient()
        docker.container(
            id = "db", imageRef = "db:1", currentImageId = "sha256:db-old",
            currentRepoDigests = listOf("db@sha256:db-1"),
            labels = """{"com.docker.compose.project":"proj","com.docker.compose.service":"db"}""",
        )
        docker.container(
            id = "web", imageRef = "web:1", currentImageId = "sha256:web-old",
            labels = """{"com.docker.compose.project":"proj","com.docker.compose.service":"web",""" +
                """"com.docker.compose.depends_on":"db:service_started:true"}""",
        )
        docker.distribution["db:1"] = "sha256:db-1"
        docker.images["web:1"] = json("""{"Id":"sha256:web-bad","Config":{},"RepoDigests":[]}""")
        docker.startedThenExits += "new-web-0"
        val updater = updater(docker)

        updater.runOnce()
        val afterFirstCycle = docker.ops.size
        docker.distribution["db:1"] = "sha256:db-2"
        docker.images["db:1"] = json("""{"Id":"sha256:db-new","Config":{},"RepoDigests":["db@sha256:db-2"]}""")

        updater.runOnce()

        val second = docker.ops.drop(afterFirstCycle)
        assertTrue(second.contains("create:db"), "db's update has nothing to wait for: $second")
        assertOrder(second, "stop:web", "create:db", "start:web")
        assertTrue(
            second.none { it == "create:web" },
            "web is restarted, not recreated — its update is still suppressed: $second",
        )
    }

    /**
     * The same hold-back without any cooldown involved: both containers have an update pending and
     * `web` shares `db`'s namespace. Doing them in one cycle means `web`'s replacement is built from a
     * moved tag *after* `db`'s old container is gone — so if the new `web` image is bad, the liveness
     * gate has nothing to roll back to. Split across two cycles, each half can be undone.
     */
    @Test
    fun a_netns_consumer_with_an_update_of_its_own_is_updated_a_cycle_before_its_provider() {
        val docker = FakeDockerClient()
        docker.container(
            id = "db1234567890abc", name = "db", imageRef = "db:1", currentImageId = "sha256:db-old",
        )
        docker.container(
            id = "web", imageRef = "web:1", currentImageId = "sha256:web-old",
            hostConfig = """{"NetworkMode":"container:db1234567890abc"}""",
        )
        docker.images["db:1"] = json("""{"Id":"sha256:db-new","Config":{},"RepoDigests":[]}""")
        docker.images["web:1"] = json("""{"Id":"sha256:web-new","Config":{},"RepoDigests":[]}""")
        val updater = updater(docker)

        val log = captureLog { updater.runOnce() }

        assertTrue(docker.ops.contains("create:web"), "web's own update goes ahead: ${docker.ops}")
        assertTrue(
            docker.ops.none { it == "create:db" || it == "stop:db1234567890abc" },
            "db waits a cycle so web's update has something to roll back to: ${docker.ops}",
        )
        assertTrue(log.contains("not restarting it this cycle"), "and the delay is announced: $log")
        assertEquals(
            "container:db", docker.created.single().second.obj("HostConfig")?.str("NetworkMode"),
            "web's replacement still joins the (unchanged) provider by name: ${docker.created}",
        )

        val afterFirstCycle = docker.ops.size
        updater.runOnce()

        val second = docker.ops.drop(afterFirstCycle)
        assertTrue(second.contains("create:db"), "and db follows the next cycle: $second")
        assertTrue(
            second.contains("create:web"),
            "which recreates web against the replacement — now from a ref that names what it runs: $second",
        )
    }

    /**
     * A hold-back follows the create-time edges of the container it lands on, or it holds nothing back
     * at all. `c` shares `b`'s namespace and `b` shares `a`'s; only `c` has an update pending, so `c`
     * keeps `b` out of the cycle — and `b` has to keep `a` out for the same reason, since replacing `a`
     * is what would drag `b` along.
     *
     * Stopping at the first link is not a smaller version of the same behaviour, it is the failure the
     * hold-back exists to prevent: `a` is replaced, `b` is recreated by the daemon-wide pass to follow
     * it, and the `c` this cycle has just built — which the pass counts as handled — is left joined to
     * the namespace `b`'s recreate destroyed. `Running`, no interfaces, and not one line about it.
     */
    @Test
    fun a_hold_back_follows_the_namespace_chain_past_the_container_it_lands_on() {
        val docker = FakeDockerClient()
        docker.container(
            id = A_ID, name = "a", imageRef = "a:1", currentImageId = "sha256:a-old",
            labels = """{"kodkod.update.enable":"true"}""",
        )
        docker.images["a:1"] = json("""{"Id":"sha256:a-new","Config":{},"RepoDigests":[]}""")
        // Up to date, and joined to a's namespace: nothing but a's update can put it in motion.
        docker.container(
            id = B_ID, name = "b", imageRef = "b:1", currentImageId = "sha256:b-cur",
            labels = """{"kodkod.update.enable":"true"}""",
            hostConfig = """{"NetworkMode":"container:$A_ID"}""",
        )
        docker.images["b:1"] = json("""{"Id":"sha256:b-cur","Config":{},"RepoDigests":[]}""")
        docker.container(
            id = C_ID, name = "c", imageRef = "c:1", currentImageId = "sha256:c-old",
            labels = """{"kodkod.update.enable":"true"}""",
            hostConfig = """{"NetworkMode":"container:$B_ID"}""",
        )
        docker.images["c:1"] = json("""{"Id":"sha256:c-new","Config":{},"RepoDigests":[]}""")

        updater(docker, config(monitorAll = false)).runOnce()

        assertTrue(docker.ops.contains("create:c"), "c's own update is the one thing that is safe: ${docker.ops}")
        assertTrue(
            docker.ops.none { it == "create:a" || it == "stop:$A_ID" },
            "a is two links away from the update that is happening, and replacing it takes b — and " +
                "therefore c's brand-new container — down with it: ${docker.ops}",
        )
        assertTrue(
            docker.ops.none { it == "create:b" || it == "restart:$B_ID" },
            "and b, whose namespace c is joined to, must not be rebuilt behind c's back: ${docker.ops}",
        )
    }

    /**
     * The memory is about the *image*, and only a replacement that was actually asked to run says
     * anything about it. A name conflict, a refused stop or a create the daemon rejected happen with
     * any image — remembering those would freeze a perfectly good update for six hours over a blip.
     */
    @Test
    fun a_failure_before_the_image_ever_ran_is_not_held_against_it() {
        val docker = FakeDockerClient()
        staleWeb(docker)
        docker.failCreate += "web"
        val updater = updater(docker)

        updater.runOnce()
        val afterFirstCycle = docker.ops.size

        val log = captureLog { updater.runOnce() }

        assertTrue(
            docker.ops.drop(afterFirstCycle).contains("create!:web"),
            "the update has to be attempted again next cycle: ${docker.ops}",
        )
        assertFalse(log.contains("skipping this update"), "nothing was learned about the image: $log")
    }

    @Test
    fun a_zero_cooldown_gives_back_the_retry_every_cycle_behaviour() {
        val docker = FakeDockerClient()
        poisonedWeb(docker)
        val updater = updater(docker, config(failureCooldown = "0"))

        updater.runOnce()
        val afterFirstCycle = docker.ops.size

        updater.runOnce()

        assertTrue(
            docker.ops.drop(afterFirstCycle).contains("create:web"),
            "0 is the documented off switch for the memory: ${docker.ops}",
        )
    }

    // --- ordering across a dependency edge ------------------------------------------------

    @Test
    fun dependents_stop_first_and_come_back_after_their_dependency() {
        val docker = FakeDockerClient()
        // web depends on db; db's image is updated, web's is not -> web is restarted as a dependent.
        docker.container(id = "db", imageRef = "db:1", currentImageId = "sha256:db-old")
        docker.container(
            id = "web", imageRef = "web:1", currentImageId = "sha256:web-old",
            labels = """{"kodkod.depends-on":"db"}""",
        )
        // db: registry digest resolves to a newer local image (stale, no pull).
        docker.distribution["db:1"] = "sha256:db-remote"
        docker.images["db:1"] = json("""{"Id":"sha256:db-new","Config":{},"RepoDigests":["db@sha256:db-remote"]}""")
        // web: running image already carries the registry digest (up to date).
        docker.distribution["web:1"] = "sha256:web-remote"
        docker.images["sha256:web-old"] = json("""{"Id":"sha256:web-old","Config":{},"RepoDigests":["web@sha256:web-remote"]}""")

        updater(docker).runOnce()

        assertOrder(docker.ops, "stop:web", "stop:db", "create:db", "start:web")
        assertFalse(docker.ops.contains("create:web"), "web only depends on db; it is restarted, not recreated: ${docker.ops}")
    }

    // --- compose depends_on: condition and restart ----------------------------------------

    /**
     * db + web wired the way a compose stack is wired: **no** `kodkod.depends-on` fallback label, so the
     * only thing that can produce the edge is the `<service>:<condition>:<restart>` entry compose stamps
     * into `com.docker.compose.depends_on`. db's image moves, web's does not.
     */
    private fun composeDependentWeb(docker: FakeDockerClient, dependsOn: String, webHostConfig: String = "{}") {
        docker.container(
            id = "db", imageRef = "db:1", currentImageId = "sha256:db-old",
            labels = """{"com.docker.compose.project":"proj","com.docker.compose.service":"db"}""",
        )
        docker.container(
            id = "web", imageRef = "web:1", currentImageId = "sha256:web-old",
            labels = """{"com.docker.compose.project":"proj","com.docker.compose.service":"web",""" +
                """"com.docker.compose.depends_on":"$dependsOn"}""",
            hostConfig = webHostConfig,
        )
        docker.distribution["db:1"] = "sha256:db-remote"
        docker.images["db:1"] = json("""{"Id":"sha256:db-new","Config":{},"RepoDigests":["db@sha256:db-remote"]}""")
        docker.distribution["web:1"] = "sha256:web-remote"
        docker.images["sha256:web-old"] =
            json("""{"Id":"sha256:web-old","Config":{},"RepoDigests":["web@sha256:web-remote"]}""")
    }

    @Test
    fun a_dependency_with_condition_service_healthy_is_awaited_before_its_dependent_starts() {
        val docker = FakeDockerClient()
        composeDependentWeb(docker, dependsOn = "db:service_healthy:true")
        // The replacement's healthcheck only passes after a while — `starting` until then.
        val daemon = HealthFlip(docker, target = "new-db-0", startingProbes = 6)

        updater(daemon, config(verifyHealth = false)).runOnce()

        assertEquals(
            "healthy", daemon.healthWhenStarted["web"],
            "condition: service_healthy means web may not be started while db is still starting",
        )
        assertTrue(docker.ops.contains("start:web"), "web still has to come back: ${docker.ops}")
    }

    @Test
    fun condition_service_started_is_satisfied_by_the_dependency_being_started() {
        val docker = FakeDockerClient()
        composeDependentWeb(docker, dependsOn = "db:service_started:false")
        docker.health["new-db-0"] = "starting" // never becomes healthy, and nothing may wait for it

        val log = captureLog { updater(docker, config(verifyHealth = false)).runOnce() }

        assertFalse(
            log.contains("waiting for db"),
            "service_started asks for a started dependency, not a healthy one: $log",
        )
        assertTrue(docker.ops.contains("start:web"), "web has to come back right away: ${docker.ops}")
    }

    @Test
    fun a_dependency_that_never_becomes_healthy_only_delays_its_dependent() {
        val docker = FakeDockerClient()
        composeDependentWeb(docker, dependsOn = "db:service_healthy:false")
        docker.health["new-db-0"] = "starting" // the healthcheck never passes

        val log = captureLog {
            updater(docker, config(verifyHealth = false, dependencyHealthTimeout = "5")).runOnce()
        }

        assertTrue(log.contains("did not become healthy within 5s"), "the bound has to be visible: $log")
        assertTrue(
            docker.ops.contains("start:web"),
            "the wait is a bound on ordering, not a licence to abandon the dependent: ${docker.ops}",
        )
        assertTrue(
            clock.sleeps.count { it == 500L } >= 10,
            "5s of waiting at a 500ms probe interval is 10 sleeps: ${clock.sleeps}",
        )
    }

    @Test
    fun a_health_gated_dependency_without_a_healthcheck_is_not_waited_for() {
        val docker = FakeDockerClient()
        composeDependentWeb(docker, dependsOn = "db:service_healthy:false")
        // No `health` entry at all: the shape of a container whose image declares no healthcheck.

        val log = captureLog { updater(docker, config(verifyHealth = false)).runOnce() }

        assertTrue(log.contains("declares no healthcheck"), "waiting 120s for a health that never comes: $log")
        assertTrue(docker.ops.contains("start:web"), "web has to come back: ${docker.ops}")
    }

    @Test
    fun by_default_a_restart_false_edge_still_restarts_the_dependent() {
        val docker = FakeDockerClient()
        composeDependentWeb(docker, dependsOn = "db:service_started:false")

        updater(docker).runOnce()

        assertOrder(docker.ops, "stop:web", "stop:db", "create:db", "start:web")
    }

    @Test
    fun with_the_restart_flag_respected_a_restart_false_edge_leaves_the_dependent_alone() {
        val docker = FakeDockerClient()
        composeDependentWeb(docker, dependsOn = "db:service_started:false")

        updater(docker, config(respectDependsOnRestart = true)).runOnce()

        assertTrue(docker.ops.contains("create:db"), "db itself still has to be updated: ${docker.ops}")
        assertTrue(
            docker.ops.none { it.endsWith(":web") },
            "compose said restart: false and the operator asked kodkod to obey it: ${docker.ops}",
        )
    }

    @Test
    fun the_restart_flag_never_suppresses_a_netns_dependent() {
        val docker = FakeDockerClient()
        // web shares db's network namespace: a restart is not enough, it has to be recreated against
        // the replacement — leaving it alone would leave it on a dead namespace.
        composeDependentWeb(
            docker, dependsOn = "db:service_started:false",
            webHostConfig = """{"NetworkMode":"container:db"}""",
        )

        updater(docker, config(respectDependsOnRestart = true)).runOnce()

        assertTrue(
            docker.ops.contains("create:web"),
            "restart: false may only suppress a restart, never a create-time edge: ${docker.ops}",
        )
    }

    // --- bringing dependents back ---------------------------------------------------------

    /** db's image moved; web is up to date but depends on db, so web is stopped and started again. */
    private fun dependentWeb(docker: FakeDockerClient) {
        docker.container(id = "db", imageRef = "db:1", currentImageId = "sha256:db-old")
        docker.container(
            id = "web", imageRef = "web:1", currentImageId = "sha256:web-old",
            labels = """{"kodkod.depends-on":"db"}""",
        )
        docker.distribution["db:1"] = "sha256:db-remote"
        docker.images["db:1"] = json("""{"Id":"sha256:db-new","Config":{},"RepoDigests":["db@sha256:db-remote"]}""")
        docker.distribution["web:1"] = "sha256:web-remote"
        docker.images["sha256:web-old"] = json("""{"Id":"sha256:web-old","Config":{},"RepoDigests":["web@sha256:web-remote"]}""")
    }

    @Test
    fun a_dependent_whose_start_is_refused_is_retried_until_it_comes_up() {
        val docker = FakeDockerClient()
        dependentWeb(docker)
        // The port the previous process held is not free yet — a state the daemon leaves behind briefly.
        val flaky = FlakyStart(docker, target = "web", times = 2)

        updater(flaky).runOnce()

        assertEquals(
            listOf("new-db-0", "web", "web", "web"), flaky.attempts,
            "a transient refusal must be retried, not accepted as the verdict: ${flaky.attempts}",
        )
        assertTrue(docker.ops.contains("start:web"), "the third attempt has to actually start it: ${docker.ops}")
        assertTrue(running(docker, "web"))
        assertEquals(2, clock.sleeps.count { it == 1000L }, "with a pause before each retry: ${clock.sleeps}")
    }

    @Test
    fun a_dependent_that_never_starts_is_reported_as_left_stopped() {
        val docker = FakeDockerClient()
        dependentWeb(docker)
        docker.failStart += "web"

        val log = captureLog { updater(docker).runOnce() }

        assertEquals(3, docker.ops.count { it == "start!:web" }, "three attempts before giving up: ${docker.ops}")
        assertEquals(2, clock.sleeps.count { it == 1000L }, "with a pause between them: ${clock.sleeps}")
        assertTrue(
            log.contains("[web] could not be started after 3 attempts") && log.contains("LEFT STOPPED"),
            "discovery only lists running containers, so a container abandoned here is invisible to every " +
                "later cycle — that has to be an ERROR, not a shrug: $log",
        )
        assertFalse(running(docker, "web"), "the test is worthless if the fake started it anyway")
    }

    // --- stop timeout ---------------------------------------------------------------------

    /** Make `web` stale so a full stop -> recreate -> stop cycle runs and records its timeouts. */
    private fun staleWeb(docker: FakeDockerClient, labels: String = "{}", configStopTimeout: Int? = null) {
        docker.container(
            id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old",
            labels = labels, configStopTimeout = configStopTimeout,
        )
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")
    }

    @Test
    fun without_an_override_the_container_decides_its_own_stop_timeout() {
        val docker = FakeDockerClient()
        staleWeb(docker, configStopTimeout = 30)

        updater(docker).runOnce()

        assertEquals(
            listOf<Int?>(null, null), docker.stopTimeouts,
            "with no label and no KODKOD_STOP_TIMEOUT nothing may be sent — the daemon applies Config.StopTimeout",
        )
        assertEquals(
            listOf<Int?>(30, 30), docker.stopExpected,
            "but the wait we signed up for is the container's own 30s, and a read timeout sized for 10s " +
                "would report a perfectly good stop as a failure: ${docker.stopExpected}",
        )
    }

    @Test
    fun the_label_wins_over_the_containers_own_stop_timeout() {
        val docker = FakeDockerClient()
        staleWeb(docker, labels = """{"kodkod.stop.timeout":"45"}""", configStopTimeout = 30)

        updater(docker).runOnce()

        assertEquals(listOf<Int?>(45, 45), docker.stopTimeouts, "an explicit label is an override: ${docker.stopTimeouts}")
        assertEquals(
            listOf<Int?>(45, 45), docker.stopExpected,
            "and the override is what the wait is sized for too: ${docker.stopExpected}",
        )
    }

    // --- opt-out and failure branches -------------------------------------------------------

    /**
     * `KODKOD_LABEL_NAMESPACE` is read once into a field and then interpolated into half a dozen label
     * lookups; nothing but a cycle run proves that the *same* namespace reaches all of them.
     */
    @Test
    fun a_custom_label_namespace_drives_every_label_kodkod_reads() {
        val docker = FakeDockerClient()
        staleWeb(docker, labels = """{"acme.update.enable":"true","acme.stop.timeout":"45"}""")
        val renamed = Config.fromEnv(
            mapOf("KODKOD_LABEL_NAMESPACE" to "acme", "KODKOD_UPDATE_MONITOR_ALL" to "false")::get,
        )

        updater(docker, renamed).runOnce()

        assertTrue(docker.ops.contains("create:web"), "the opt-in label under the custom namespace has to count: ${docker.ops}")
        assertEquals(
            listOf<Int?>(45, 45), docker.stopTimeouts,
            "and so does the stop-timeout label next to it: ${docker.stopTimeouts}",
        )
    }

    @Test
    fun an_explicit_enable_false_opts_out_even_when_monitoring_everything() {
        val docker = FakeDockerClient()
        staleWeb(docker, labels = """{"kodkod.update.enable":"false"}""")

        updater(docker, config(monitorAll = true)).runOnce()

        assertTrue(
            docker.ops.isEmpty(),
            "KODKOD_UPDATE_MONITOR_ALL is a default, not an override — an explicit opt-out is the only " +
                "way to keep one container out of it: ${docker.ops}",
        )
    }

    /**
     * A stop the daemon refuses must not take the rest of the cycle with it: the dependent is one of
     * several containers, and its dependency is still owed its update.
     */
    @Test
    fun a_dependent_whose_stop_is_refused_is_reported_and_the_cycle_carries_on() {
        val docker = FakeDockerClient()
        dependentWeb(docker)
        docker.failStop += "web"

        val log = captureLog { updater(docker).runOnce() }

        assertTrue(log.contains("[web] stop failed"), "a refused stop must be reported: $log")
        assertOrder(docker.ops, "stop!:web", "stop:db", "create:db", "start:web")
    }

    /**
     * The same refusal on the container being recreated is a different story: the recreate is abandoned
     * before anything is renamed, so the rollback has nothing to rename back — and must not ask the
     * daemon to rename the container onto the name it already holds, which is refused and would report
     * two ERRORs about a container that never moved.
     */
    @Test
    fun a_recreate_whose_stop_is_refused_rolls_back_without_touching_the_name() {
        val docker = FakeDockerClient()
        staleWeb(docker)
        docker.failStop += "web"

        val log = captureLog { updater(docker).runOnce() }

        assertTrue(docker.ops.none { it.startsWith("rename:") || it.startsWith("rename!:") }, "the name never moved: ${docker.ops}")
        assertTrue(docker.ops.none { it.startsWith("create:") }, "nothing may be created after a failed stop: ${docker.ops}")
        assertFalse(log.contains("could not rename"), "there was nothing to rename back: $log")
        assertFalse(log.contains("ROLLBACK INCOMPLETE"), "the container is exactly where it was: $log")
        assertTrue(log.contains("recreate failed — rolling back"), "the abandoned update still has to be reported: $log")
    }

    @Test
    fun the_env_default_wins_over_the_containers_own_stop_timeout() {
        val docker = FakeDockerClient()
        staleWeb(docker, configStopTimeout = 30)

        updater(docker, config(stopTimeout = "25")).runOnce()

        assertEquals(
            listOf<Int?>(25, 25), docker.stopTimeouts,
            "an explicitly set KODKOD_STOP_TIMEOUT is an override too: ${docker.stopTimeouts}",
        )
    }

    // --- create-time dependents outside the monitored set -----------------------------------

    /**
     * A labelled, stale `app` plus a sidecar joined to its network namespace the way compose writes it:
     * `network_mode: service:app` resolved to `container:<app's id>`. The provider's id and name differ
     * on purpose — an id is the one spelling of the reference that cannot survive a replacement.
     *
     * The sidecar is up to date (its registry digest is already on its running image) so nothing about
     * it *but* the dependency can put it in motion, and it carries the compose project label without a
     * kodkod one: the shape of every sidecar a compose stack has.
     */
    private fun staleProviderWithSidecar(
        docker: FakeDockerClient,
        sidecarLabels: String = """{"com.docker.compose.project":"proj"}""",
        sidecarHostConfig: String = """{"NetworkMode":"container:$PROVIDER_ID"}""",
        sidecarNetworks: String = "{}",
        sidecarState: String = "running",
    ) {
        docker.container(
            id = PROVIDER_ID, name = "app", imageRef = "app:1", currentImageId = "sha256:app-old",
            labels = """{"kodkod.update.enable":"true","com.docker.compose.project":"proj",""" +
                """"com.docker.compose.service":"app"}""",
        )
        docker.distribution["app:1"] = "sha256:app-remote"
        docker.images["app:1"] = json("""{"Id":"sha256:app-new","Config":{},"RepoDigests":["app@sha256:app-remote"]}""")
        docker.container(
            id = "side", imageRef = "busybox:1", currentImageId = "sha256:side",
            currentRepoDigests = listOf("busybox@sha256:side-remote"),
            labels = sidecarLabels, hostConfig = sidecarHostConfig, networks = sidecarNetworks,
            state = sidecarState,
        )
        docker.distribution["busybox:1"] = "sha256:side-remote"
    }

    @Test
    fun an_unlabelled_netns_sidecar_is_recreated_when_its_provider_is_replaced() {
        val docker = FakeDockerClient()
        staleProviderWithSidecar(docker)

        updater(docker, config(monitorAll = false)).runOnce()

        assertTrue(docker.ops.contains("create:app"), "the provider itself has to be updated: ${docker.ops}")
        assertTrue(
            docker.ops.contains("create:side"),
            "a sidecar joined to the provider's namespace by id cannot survive the provider being " +
                "replaced, and nothing else is looking at it: ${docker.ops}",
        )
        val body = docker.created.single { (name, _) -> name == "side" }.second
        assertEquals(
            "container:app", body.obj("HostConfig")?.str("NetworkMode"),
            "the replacement must join the namespace by the name the new provider holds: $body",
        )
        assertTrue(running(docker, "new-side-1"), "the sidecar must come back up: ${docker.ops}")
    }

    @Test
    fun a_monitored_netns_sidecar_is_still_recreated_exactly_once() {
        val docker = FakeDockerClient()
        staleProviderWithSidecar(
            docker,
            sidecarLabels = """{"kodkod.update.enable":"true","com.docker.compose.project":"proj"}""",
        )

        updater(docker, config(monitorAll = false)).runOnce()

        assertEquals(
            1, docker.ops.count { it == "create:side" },
            "the dependency graph already recreates a monitored consumer; the daemon-wide scan must not " +
                "recreate the replacement it just made: ${docker.ops}",
        )
        assertTrue(
            docker.ops.none { it.endsWith(":new-side-1") && it.startsWith("restart:") },
            "and the replacement — which the scan does find, joined to the provider's namespace by name — " +
                "must not be restarted on top of the recreate it just came out of: ${docker.ops}",
        )
    }

    /**
     * The bound on the daemon-wide scan is on the *depth* of a namespace chain, and cannot be spent on
     * anything else. It used to count every provider popped off the queue — which is seeded with one
     * entry per container the cycle brought back — so on a stack of more than 32 updated services the
     * sidecars of everything past the 32nd were never looked for at all, for a reason that has nothing
     * to do with a chain.
     */
    @Test
    fun a_cycle_bigger_than_the_chain_bound_still_looks_for_every_sidecar() {
        val docker = FakeDockerClient()
        repeat(40) { i ->
            docker.container(
                id = "app$i", imageRef = "app:1", currentImageId = "sha256:app-old",
                labels = """{"kodkod.update.enable":"true"}""",
            )
        }
        docker.images["app:1"] = json("""{"Id":"sha256:app-new","Config":{},"RepoDigests":[]}""")
        // Joined to the *last* of them, and unlabelled: only the daemon-wide scan can find it.
        docker.container(
            id = "side", imageRef = "busybox:1", currentImageId = "sha256:side",
            currentRepoDigests = listOf("busybox@sha256:side-remote"),
            hostConfig = """{"NetworkMode":"container:app39"}""",
        )
        docker.distribution["busybox:1"] = "sha256:side-remote"

        updater(docker, config(monitorAll = false)).runOnce()

        assertEquals(
            40, docker.ops.count { it.startsWith("create:app") },
            "the test is worthless unless every one of them was actually replaced: ${docker.ops}",
        )
        assertTrue(
            docker.ops.contains("restart:side"),
            "the namespace of the 40th container is just as dead as the namespace of the first: ${docker.ops}",
        )
    }

    /**
     * Namespaces chain: `second` is joined to `side`, which is joined to `app`. Recreating `side` tears
     * its namespace down exactly as replacing `app` tore down `app`'s, so a pass that stops at the
     * first link leaves `second` reporting `Running` with no interfaces at all.
     */
    @Test
    fun a_chain_of_shared_namespaces_is_followed_past_the_first_link() {
        val docker = FakeDockerClient()
        staleProviderWithSidecar(docker)
        docker.container(
            id = "second0000000", name = "second", imageRef = "busybox:1", currentImageId = "sha256:second",
            labels = """{"com.docker.compose.project":"proj"}""",
            hostConfig = """{"NetworkMode":"container:side"}""",
        )

        updater(docker, config(monitorAll = false)).runOnce()

        assertTrue(docker.ops.contains("create:side"), "the first link is recreated as before: ${docker.ops}")
        assertTrue(
            docker.ops.contains("restart:second0000000"),
            "and whatever was joined to *that* one has to be refreshed too: ${docker.ops}",
        )
    }

    @Test
    fun a_legacy_link_dependent_outside_the_monitored_set_is_restarted() {
        val docker = FakeDockerClient()
        // `--link app:db`, which the daemon records by name — the replacement takes that name over, so
        // a restart is all it takes to resolve the address again.
        staleProviderWithSidecar(
            docker,
            sidecarHostConfig = "{}",
            sidecarNetworks = """{"bridge":{"Links":["app:db"]}}""",
        )

        updater(docker, config(monitorAll = false)).runOnce()

        assertTrue(docker.ops.contains("restart:side"), "a --link'ed dependent keeps a stale address: ${docker.ops}")
        assertTrue(
            docker.ops.none { it == "create:side" },
            "the link names the provider and the name still resolves — recreating is not needed: ${docker.ops}",
        )
    }

    @Test
    fun a_stopped_sidecar_outside_the_monitored_set_is_reported_rather_than_started() {
        val docker = FakeDockerClient()
        staleProviderWithSidecar(docker, sidecarState = "exited")

        val log = captureLog { updater(docker, config(monitorAll = false)).runOnce() }

        assertTrue(
            docker.ops.none { it.endsWith(":side") },
            "a container somebody else stopped is not this cycle's to start: ${docker.ops}",
        )
        assertTrue(
            log.contains("refuse to start until it is recreated"),
            "a dependent left pointing at a container that is gone must be said out loud: $log",
        )
    }

    /**
     * A sidecar whose recreate fails cannot be rolled back at all, and saying otherwise is the more
     * dangerous half of this test. Its original container is joined to the namespace of the provider
     * this cycle *replaced* — force-removed the moment the replacement passed the liveness gate — so
     * the daemon refuses to start it ("No such container"). What the rollback leaves behind is a
     * container stopped under its own name, which discovery (`status=running`) and the reconcile pass
     * (`_kodkod_old_*`) both walk past: nothing would ever look at it again.
     *
     * So kodkod remembers it instead and rebuilds it against the provider on the next cycle, which is
     * also when the reason for the first failure has usually gone away.
     */
    @Test
    fun a_sidecar_that_cannot_be_recreated_is_reported_and_rebuilt_on_the_next_cycle() {
        val docker = FakeDockerClient()
        staleProviderWithSidecar(docker)
        docker.failCreate += "side"
        val updater = updater(docker, config(monitorAll = false))

        val log = captureLog { updater.runOnce() }

        assertTrue(
            log.contains("may be left without a working network"),
            "a dependent kodkod found and could not fix is exactly what must not be silent: $log",
        )
        assertTrue(
            log.contains("no `start` can bring back"),
            "and the reason it cannot simply be started again has to be said, not implied: $log",
        )
        assertTrue(
            log.contains("docker rm side"),
            "the retry lives in this process only — a kodkod that restarts first will never look at " +
                "this container again, so the operator has to be given the command that fixes it: $log",
        )
        assertFalse(running(docker, "side"), "there is no way back for it this cycle: ${docker.ops}")

        val afterFirstCycle = docker.ops.size
        docker.failCreate -= "side"
        val second = captureLog { updater.runOnce() }

        assertTrue(
            docker.ops.drop(afterFirstCycle).contains("create:side"),
            "the sidecar has to be rebuilt against the provider, by the only pass that can see it: ${docker.ops}",
        )
        assertEquals(
            "container:app", docker.created.last { (name, _) -> name == "side" }.second
                .obj("HostConfig")?.str("NetworkMode"),
            "against the container holding the provider's name now: ${docker.created}",
        )
        val rebuilt = docker.ops.last { it.startsWith("start:new-side-") }.removePrefix("start:")
        assertTrue(running(docker, rebuilt), "and it has to be up again: ${docker.ops}")
        assertTrue(second.contains("is serving again"), "which is the one thing the operator waits for: $second")
    }

    /** A rebuild that keeps failing keeps being retried: the container is down either way. */
    @Test
    fun a_sidecar_that_cannot_be_rebuilt_is_tried_again_every_cycle() {
        val docker = FakeDockerClient()
        staleProviderWithSidecar(docker)
        docker.failCreate += "side"
        val updater = updater(docker, config(monitorAll = false))

        updater.runOnce()
        val afterFirstCycle = docker.ops.size
        updater.runOnce()
        val afterSecondCycle = docker.ops.size
        updater.runOnce()

        assertTrue(
            docker.ops.drop(afterFirstCycle).take(afterSecondCycle - afterFirstCycle).contains("create!:side"),
            "the second cycle has to try: ${docker.ops}",
        )
        assertTrue(
            docker.ops.drop(afterSecondCycle).contains("create!:side"),
            "and so does the third — nothing else in the system is looking at it: ${docker.ops}",
        )
    }

    /** Somebody who removes the stranded container by hand owns the decision; kodkod stops chasing it. */
    @Test
    fun a_stranded_sidecar_that_was_removed_by_hand_is_forgotten() {
        val docker = FakeDockerClient()
        staleProviderWithSidecar(docker)
        docker.failCreate += "side"
        val updater = updater(docker, config(monitorAll = false))

        updater.runOnce()
        docker.remove("side", force = true)
        val afterRemoval = docker.ops.size
        val log = captureLog { updater.runOnce() }
        updater.runOnce()

        assertTrue(
            docker.ops.drop(afterRemoval).none { it.contains(":side") },
            "a container that is gone is nobody's to rebuild: ${docker.ops}",
        )
        assertTrue(
            log.contains("stops trying to rebuild it"),
            "and it has to be dropped rather than chased for the life of the process: $log",
        )
    }

    /**
     * A netns consumer whose provider `holdBackUnsafeProviders` deliberately kept out of the cycle is
     * recreated **alone**, against a namespace that is still there — that is the entire point of holding
     * the provider back. If that solo recreate fails and the rollback does not land either (a port the
     * previous process has not released is enough), what is left is a stopped container under its own
     * name whose namespace is alive: one `docker start` away.
     *
     * So a `start` is what it gets. Rebuilding it instead means kodkod stops, renames, creates and
     * verifies it from the image ref that has just failed here — every cycle, uncapped — and announces
     * it with an ERROR saying its network namespace is gone, which is not true of this container at all.
     */
    private fun consumerHeldBackFromItsProvider(docker: FakeDockerClient) {
        docker.container(
            id = PROVIDER_ID, name = "app", imageRef = "app:1", currentImageId = "sha256:app-old",
            labels = """{"kodkod.update.enable":"true"}""",
        )
        docker.images["app:1"] = json("""{"Id":"sha256:app-new","Config":{},"RepoDigests":[]}""")
        // Stale as well, which is what keeps the provider out of this cycle: the recreate the provider
        // would force on it would be built from an image ref that has moved on.
        docker.container(
            id = "side", imageRef = "busybox:1", currentImageId = "sha256:side-old",
            labels = """{"kodkod.update.enable":"true"}""",
            hostConfig = """{"NetworkMode":"container:$PROVIDER_ID"}""",
        )
        docker.images["busybox:1"] = json("""{"Id":"sha256:side-new","Config":{},"RepoDigests":[]}""")
        docker.failCreate += "side" // the solo recreate fails...
        docker.failStart += "side" // ...and the rollback's start does not land either
    }

    @Test
    fun a_consumer_whose_provider_never_moved_is_started_again_rather_than_rebuilt() {
        val docker = FakeDockerClient()
        consumerHeldBackFromItsProvider(docker)
        val updater = updater(docker, config(monitorAll = false))

        val log = captureLog { updater.runOnce() }

        assertTrue(
            docker.ops.none { it.startsWith("create:app") },
            "the test is worthless unless the provider really was held back: ${docker.ops}",
        )
        assertTrue(
            log.contains("a `start` is all it needs"),
            "the namespace it is joined to is still there, and that is what decides what it needs: $log",
        )
        assertFalse(
            log.contains("no `start` can bring back"),
            "the provider was never touched, so saying it cannot be started is a falsehood: $log",
        )

        val afterFirstCycle = docker.ops.size
        val second = captureLog { updater.runOnce() }

        assertEquals(
            listOf("start!:side"), docker.ops.drop(afterFirstCycle).filter { it.endsWith(":side") },
            "a container a `docker start` would fix must be started, not destroyed and rebuilt from an " +
                "image ref that has just failed here: ${docker.ops}",
        )
        assertTrue(
            second.contains("starting it again failed"),
            "and the retry that did not land is the operator's only sign it is still down: $second",
        )
    }

    /**
     * The consequence of leaving that container stopped, and the reason it is remembered anyway: the
     * hold-back only protects a consumer while it is still a *target*. This one is stopped, so the next
     * cycle's discovery (`status=running`) does not see it, nothing keeps the provider back any more,
     * and the update that follows force-removes the very container this one names by id.
     *
     * From that moment a `start` is refused by the daemon and only a rebuild can bring it back — which
     * is exactly what nothing else in the system would ever attempt.
     */
    @Test
    fun a_consumer_left_stopped_is_rebuilt_once_its_provider_moves_after_all() {
        val docker = FakeDockerClient()
        consumerHeldBackFromItsProvider(docker)
        val updater = updater(docker, config(monitorAll = false))

        updater.runOnce()
        updater.runOnce() // the consumer is not a target any more, so the provider is updated here
        val afterProviderMoved = docker.ops.size

        assertTrue(
            docker.ops.contains("create:app") && docker.ops.contains("remove:$PROVIDER_ID"),
            "the test is worthless unless the namespace the consumer names really died: ${docker.ops}",
        )
        docker.failCreate -= "side" // whatever refused the create the first time is over
        val log = captureLog { updater.runOnce() }

        assertTrue(
            docker.ops.drop(afterProviderMoved).contains("create:side"),
            "a `start` cannot join a namespace that no longer exists — the container has to be rebuilt, " +
                "and only kodkod's own memory of it is left to do that: ${docker.ops}",
        )
        assertEquals(
            "container:app", docker.created.last { (name, _) -> name == "side" }.second
                .obj("HostConfig")?.str("NetworkMode"),
            "against the container holding the provider's name now: ${docker.created}",
        )
        val rebuilt = docker.ops.last { it.startsWith("start:new-side-") }.removePrefix("start:")
        assertTrue(running(docker, rebuilt), "and it has to be serving again: ${docker.ops}")
        assertTrue(log.contains("is serving again"), "which is the one thing the operator waits for: $log")
    }

    /**
     * "The container it names still exists" is not the same question as "its namespace is still the
     * provider's". A recreate whose final `remove` the daemon refused leaves the old provider behind,
     * stopped, under its `_kodkod_old_` backup name — so the consumer's reference still resolves, to a
     * corpse. A `start` against that is refused by the daemon for as long as the corpse is there, and
     * offering one every cycle would leave the service down while the one thing that fixes it (a rebuild
     * against the container actually serving the name) is never attempted.
     */
    @Test
    fun a_consumer_joined_to_a_corpse_the_daemon_would_not_delete_is_rebuilt() {
        val docker = FakeDockerClient()
        staleProviderWithSidecar(docker)
        docker.failRemove += PROVIDER_ID // the old provider survives its own replacement, renamed
        docker.failCreate += "side"
        val updater = updater(docker, config(monitorAll = false))

        val log = captureLog { updater.runOnce() }

        assertTrue(
            docker.ops.contains("rename:$PROVIDER_ID->app_kodkod_old_${PROVIDER_ID.take(12)}"),
            "the test is worthless unless the old provider is still there under another name: ${docker.ops}",
        )
        assertTrue(
            log.contains("no `start` can bring back"),
            "the reference resolves, but not to the provider — a `start` the daemon refuses every cycle " +
                "is not a recovery: $log",
        )

        val afterFirstCycle = docker.ops.size
        docker.failCreate -= "side"
        val second = captureLog { updater.runOnce() }

        assertTrue(
            docker.ops.drop(afterFirstCycle).contains("create:side"),
            "and the rebuild against the container serving the name is what brings it back: ${docker.ops}",
        )
        assertTrue(second.contains("is serving again"), "which is the outcome the operator waits for: $second")
    }

    // --- reconcile: backups orphaned by a kodkod that died mid-recreate ---------------------

    @Test
    fun an_orphaned_backup_with_nothing_serving_the_name_is_restored() {
        val docker = FakeDockerClient()
        docker.orphanedBackup(id = "web-old", name = "web")

        // Through the cycle, not the reconcile call: an orphan is a stopped container, which the
        // monitored set (status=running) never contains, so only a deliberate all=true look finds it.
        updater(docker).runOnce()

        assertEquals(listOf("rename:web-old->web", "start:web-old"), docker.ops)
        assertEquals("/web", docker.inspectContainer("web-old").str("Name"))
        assertTrue(running(docker, "web-old"), "restoring the name without starting it leaves the service down")
    }

    @Test
    fun an_orphaned_backup_is_removed_once_its_replacement_is_running() {
        val docker = FakeDockerClient()
        docker.orphanedBackup(id = "web-old", name = "web")
        docker.holder(id = "new-web", name = "web", state = "running")

        updater(docker).reconcileOrphanedBackups()

        assertEquals(listOf("remove:web-old"), docker.ops)
        assertEquals(
            "/web", docker.inspectContainer("new-web").str("Name"),
            "the running replacement keeps the name it already serves: ${docker.ops}",
        )
    }

    @Test
    fun an_orphaned_backup_is_restored_over_a_replacement_that_never_started() {
        val docker = FakeDockerClient()
        docker.orphanedBackup(id = "web-old", name = "web")
        docker.holder(id = "new-web", name = "web", state = "created")

        updater(docker).reconcileOrphanedBackups()

        assertOrder(docker.ops, "remove:new-web", "rename:web-old->web", "start:web-old")
        assertTrue(running(docker, "web-old"), "the known-good container is the one that has to serve: ${docker.ops}")
    }

    /**
     * The one shape that must NOT be resolved by force. A recreate that got all the way through and
     * only failed to delete the backup leaves the *replacement* holding the name; an operator who then
     * runs `docker compose stop app` leaves it stopped, hours later. Destroying it and starting the
     * pre-update container in its place would silently undo a completed update — and destroy a container
     * kodkod did not create, on nothing but the guess that it was ours.
     */
    @Test
    fun a_stopped_holder_that_served_for_hours_is_not_destroyed_for_the_backup() {
        val docker = FakeDockerClient()
        docker.orphanedBackup(id = "web-old", name = "web")
        docker.holder(
            id = "new-web", name = "web", state = "exited",
            startedAt = "2026-07-01T10:00:00.000000000Z", finishedAt = "2026-07-01T14:00:00.000000000Z",
        )

        val log = captureLog { updater(docker).reconcileOrphanedBackups() }

        assertTrue(docker.ops.isEmpty(), "neither container may be touched on a guess: ${docker.ops}")
        assertTrue(log.contains("stopped on purpose"), "and the operator has to be told what was found: $log")
    }

    /**
     * And the shape that must be: with the liveness gate in place kodkod sits in `verifyStarted` for
     * seconds after every `start`, so a SIGKILL there leaves the *crashing replacement* holding the name
     * and the known-good container parked as a backup. It is `exited`, not `created` — and discovery
     * lists running containers only, so a pass that walks away from it leaves the service down for good.
     */
    @Test
    fun a_replacement_that_died_inside_the_liveness_window_gives_the_name_back() {
        val docker = FakeDockerClient()
        docker.orphanedBackup(id = "web-old", name = "web")
        docker.holder(
            id = "new-web", name = "web", state = "exited",
            startedAt = "2026-07-01T10:00:00.000000000Z", finishedAt = "2026-07-01T10:00:02.000000000Z",
        )

        updater(docker).reconcileOrphanedBackups()

        assertOrder(docker.ops, "remove:new-web", "rename:web-old->web", "start:web-old")
        assertTrue(running(docker, "web-old"), "the known-good container is the one that has to serve: ${docker.ops}")
    }

    /**
     * The window a replacement has to survive is configurable, down to and including `0` — and a
     * verdict about a container that is *already stopped* cannot be read off a threshold of zero: every
     * container that ever started would count as proven, including one that crash-looped for ten
     * seconds and took the service down with it. So the comparison has its own floor, which is what
     * decides everything between a second and a minute of uptime.
     */
    @Test
    fun a_holder_that_ran_seconds_proves_nothing_however_short_the_verify_window_is() {
        for (verifySeconds in listOf(null, "0")) {
            val docker = FakeDockerClient()
            docker.orphanedBackup(id = "web-old", name = "web")
            docker.holder(
                id = "new-web", name = "web", state = "exited",
                startedAt = "2026-07-01T10:00:00.000000000Z", finishedAt = "2026-07-01T10:00:20.000000000Z",
            )

            updater(docker, config(verifySeconds = verifySeconds)).reconcileOrphanedBackups()

            assertOrder(docker.ops, "remove:new-web", "rename:web-old->web", "start:web-old")
            assertTrue(
                running(docker, "web-old"),
                "20s of uptime is a crash loop, not an update somebody stopped on purpose " +
                    "(KODKOD_UPDATE_VERIFY_SECONDS=${verifySeconds ?: "default"}): ${docker.ops}",
            )
        }
    }

    /**
     * `0001-01-01T00:00:00Z` is not a time, it is the daemon's way of writing "this never happened" —
     * and it is what `FinishedAt` says for a container that is paused, or that has never stopped. Read
     * as a timestamp it makes the container's run look infinitely long ago and *negative*, which turns
     * "the daemon does not say" into "it never stayed up" — and this pass answers that by destroying
     * the container holding the name.
     */
    @Test
    fun a_holder_whose_timestamps_carry_the_never_happened_sentinel_is_left_alone() {
        val docker = FakeDockerClient()
        docker.orphanedBackup(id = "web-old", name = "web")
        docker.holder(
            id = "new-web", name = "web", state = "exited",
            startedAt = "2026-07-01T10:00:00.000000000Z", finishedAt = "0001-01-01T00:00:00Z",
        )

        val log = captureLog { updater(docker).reconcileOrphanedBackups() }

        assertTrue(docker.ops.isEmpty(), "a sentinel is not evidence, and this pass destroys containers: ${docker.ops}")
        assertTrue(log.contains("does not say how long it ran"), "and the operator has to be told: $log")
    }

    /** Neither verdict can be reached without the daemon's own timestamps, so nothing is done by force. */
    @Test
    fun a_stopped_holder_the_daemon_gives_no_timestamps_for_is_left_alone() {
        val docker = FakeDockerClient()
        docker.orphanedBackup(id = "web-old", name = "web")
        docker.holder(id = "new-web", name = "web", state = "exited", startedAt = "2026-07-01T10:00:00.000000000Z")

        val log = captureLog { updater(docker).reconcileOrphanedBackups() }

        assertTrue(docker.ops.isEmpty(), "a guess is not a reason to destroy a container: ${docker.ops}")
        assertTrue(log.contains("does not say how long it ran"), "and the operator has to be told: $log")
    }

    @Test
    fun a_backup_suffix_carrying_someone_elses_id_is_left_alone() {
        val docker = FakeDockerClient()
        // Looks exactly like a backup of `web` — except the short id in the suffix is not its own.
        docker.holder(id = "impostor", name = "web_kodkod_old_deadbeef1234", state = "exited")
        docker.holder(id = "web", name = "web", state = "running")

        updater(docker).reconcileOrphanedBackups()

        assertTrue(docker.ops.isEmpty(), "a name that merely looks like a backup is somebody else's: ${docker.ops}")
    }

    /**
     * Reconcile is not part of the update feature: with `KODKOD_UPDATE_ENABLED=false` no cycle ever
     * runs, so if recovery were gated on it an orphan left by the previous process would stay down
     * forever. `main` therefore calls it at startup regardless of the flag.
     */
    @Test
    fun reconcile_does_not_depend_on_the_updater_being_enabled() {
        val docker = FakeDockerClient()
        docker.orphanedBackup(id = "web-old", name = "web")
        val disabled = Config.fromEnv(mapOf("KODKOD_UPDATE_ENABLED" to "false")::get)
        assertFalse(disabled.updateEnabled, "the test is worthless if the updater is on")

        updater(docker, disabled).reconcileOrphanedBackups()

        assertEquals(listOf("rename:web-old->web", "start:web-old"), docker.ops)
    }

    // --- read phase / mutate phase ----------------------------------------------------------

    /**
     * The defect the split exists for: planning used to run under the same lock as the mutations, so a
     * pull — ten minutes of permitted idle time per image, unbounded in practice behind a stalled
     * registry — kept autoheal from restarting anything for the whole cycle. `pull` itself stays in the
     * read phase (it only adds an image to the local store), so it is the *one* op allowed to appear
     * here; anything else in `ops` is a container kodkod touched before taking the lock.
     */
    @Test
    fun planning_pulls_but_changes_nothing() {
        val docker = FakeDockerClient()
        staleWeb(docker)
        // A leftover the reconcile would restore — a mutation the read phase must not make either.
        docker.orphanedBackup(id = "api-old", name = "api")

        val plan = updater(docker).plan()

        assertTrue(plan.hasWork, "the plan has to have found the update, or the assertions below are vacuous")
        assertEquals(
            listOf("pull:nginx:1.27"), docker.ops,
            "the read phase may only pull; every other op is a mutation taken outside the cycle lock",
        )
    }

    /** The mutations, and nothing else, happen in the second half — including the reconcile. */
    @Test
    fun applying_the_plan_is_what_recreates_the_container() {
        val docker = FakeDockerClient()
        staleWeb(docker)
        val kodkod = updater(docker)

        kodkod.apply(kodkod.plan())

        assertOrder(
            docker.ops,
            "pull:nginx:1.27", "stop:web", "rename:web->web_kodkod_old_web", "create:web",
            "start:new-web-0", "remove:web",
        )
    }

    /**
     * State can move while an image is downloading, and the plan carries container ids the mutations
     * aim at: a container that is gone means `remove` would be pointed at whatever answers now.
     */
    @Test
    fun a_plan_whose_container_disappeared_is_dropped() {
        val docker = FakeDockerClient()
        staleWeb(docker)
        val kodkod = updater(docker)
        val plan = kodkod.plan()

        docker.containers.remove("web") // an operator (or compose) removed it during the pull
        val log = captureLog { kodkod.apply(plan) }

        assertEquals(
            listOf("pull:nginx:1.27"), docker.ops,
            "nothing may be mutated on a plan that no longer describes the daemon: ${docker.ops}",
        )
        assertTrue(log.contains("dropping this cycle's plan"), "a dropped plan must say so: $log")
    }

    /**
     * Same for the image: somebody else recreating the container during the pull leaves the plan aimed
     * at an image id that is no longer the one running, and the cleanup would prune a live image.
     */
    @Test
    fun a_plan_whose_image_moved_under_it_is_dropped() {
        val docker = FakeDockerClient()
        staleWeb(docker)
        val kodkod = updater(docker)
        val plan = kodkod.plan()

        docker.container(id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:new")
        val log = captureLog { kodkod.apply(plan) }

        assertEquals(
            listOf("pull:nginx:1.27"), docker.ops,
            "the container already runs the new image — recreating it now is an outage for nothing: ${docker.ops}",
        )
        assertTrue(log.contains("dropping this cycle's plan"), "a dropped plan must say so: $log")
    }

    /**
     * Discovery only ever lists running containers, so every target was running when the plan was
     * made. One that is not any more was stopped by somebody else during the pull — and stopping,
     * renaming, recreating and *starting* it would put it back up behind whoever stopped it.
     */
    @Test
    fun a_plan_whose_container_was_stopped_meanwhile_is_dropped() {
        val docker = FakeDockerClient()
        staleWeb(docker)
        val kodkod = updater(docker)
        val plan = kodkod.plan()

        docker.stop("web", timeout = null) // an operator took it down while the image was downloading
        val before = docker.ops.size
        val log = captureLog { kodkod.apply(plan) }

        assertTrue(docker.ops.drop(before).isEmpty(), "a container somebody stopped stays stopped: ${docker.ops}")
        assertTrue(log.contains("no longer running"), "and the reason has to be in the log: $log")
    }

    /**
     * Minutes of image download sit between the two halves, and the create body is built from what the
     * container looked like. Building it from the *plan's* snapshot silently reverts everything done in
     * between — a `docker update`, a `docker network connect`, a label edit.
     */
    @Test
    fun the_replacement_is_built_from_the_container_as_it_is_at_apply_time() {
        val docker = FakeDockerClient()
        staleWeb(docker)
        val kodkod = updater(docker)
        val plan = kodkod.plan()

        // An operator raises the memory limit while the new image downloads.
        docker.containers["web"] = json(
            """{"Name":"/web","Image":"sha256:old","Config":{"Image":"nginx:1.27","Labels":{}},
               "HostConfig":{"Memory":536870912},"NetworkSettings":{"Networks":{}}}""",
        )
        kodkod.apply(plan)

        assertEquals(
            "536870912", docker.created.single().second.obj("HostConfig")?.str("Memory"),
            "the replacement must carry the configuration the container actually had: ${docker.created}",
        )
    }

    /**
     * The reconcile is a mutation, so it moved into the second half with the others — but it is not part
     * of the plan and must survive one being dropped: an orphaned backup is a service that is down right
     * now, and "the update plan went stale" is no reason to leave it parked for another interval.
     */
    @Test
    fun a_dropped_plan_does_not_take_the_reconcile_with_it() {
        val docker = FakeDockerClient()
        staleWeb(docker)
        docker.orphanedBackup(id = "api-old", name = "api")
        val kodkod = updater(docker)
        val plan = kodkod.plan()

        docker.containers.remove("web")
        kodkod.apply(plan)

        assertEquals(
            listOf("pull:nginx:1.27", "rename:api-old->api", "start:api-old"), docker.ops,
            "the orphan has to be restored even though the plan was dropped: ${docker.ops}",
        )
    }
}

/**
 * A [FakeDockerClient] that models a shutdown landing in the middle of a recreate: [create] fails the
 * way an interrupted socket write does — leaving the thread's interrupt flag set — and every call
 * after it keeps failing for as long as that flag is set, exactly as a NIO channel does.
 */
private class InterruptedMidRecreate(private val delegate: FakeDockerClient) : DockerClient by delegate {
    override fun create(name: String, body: JsonObject, platform: String?): String {
        Thread.currentThread().interrupt()
        throw DockerException(-1, "fake: interrupted while creating '$name'")
    }

    override fun rename(id: String, name: String) = interruptible { delegate.rename(id, name) }

    override fun start(id: String) = interruptible { delegate.start(id) }

    override fun stop(id: String, timeout: Int?, expectedStopSeconds: Int?) =
        interruptible { delegate.stop(id, timeout, expectedStopSeconds) }

    override fun remove(id: String, force: Boolean) = interruptible { delegate.remove(id, force) }

    override fun inspectContainer(id: String): JsonObject = interruptible { delegate.inspectContainer(id) }

    private fun <T> interruptible(call: () -> T): T {
        if (Thread.currentThread().isInterrupted) {
            throw DockerException(-1, "fake: closed by interrupt")
        }
        return call()
    }
}

/**
 * A [FakeDockerClient] whose [start] refuses [target] the first [times] calls and then behaves — the
 * shape of a transient daemon state, which [FakeDockerClient.failStart] (a permanent verdict) cannot
 * model. Every attempted id lands in [attempts], including the refused ones.
 */
private class FlakyStart(
    private val delegate: FakeDockerClient,
    private val target: String,
    private var times: Int,
) : DockerClient by delegate {
    val attempts = mutableListOf<String>()

    override fun start(id: String) {
        attempts += id
        if (id == target && times > 0) {
            times--
            throw DockerException(500, "fake: transient start failure for '$id'")
        }
        delegate.start(id)
    }
}

/**
 * A [FakeDockerClient] whose [target] reports `starting` for its first [startingProbes] inspects and
 * `healthy` from then on — a container whose healthcheck takes a while to pass, which the static
 * [FakeDockerClient.health] map cannot express. [healthWhenStarted] records what [target] reported at
 * the moment each container was started, which is exactly what "waited for it" comes down to.
 */
private class HealthFlip(
    private val delegate: FakeDockerClient,
    private val target: String,
    private var startingProbes: Int,
) : DockerClient by delegate {
    val healthWhenStarted = mutableMapOf<String, String?>()

    override fun inspectContainer(id: String): JsonObject {
        if (id == target) delegate.health[target] = if (startingProbes-- > 0) "starting" else "healthy"
        return delegate.inspectContainer(id)
    }

    override fun start(id: String) {
        healthWhenStarted[id] = delegate.health[target]
        delegate.start(id)
    }
}

/**
 * A [WallClock] whose *wall* reading is corrected by [stepMs] on every wait — an NTP step landing in the
 * middle of a polling loop — while the monotonic reading of [delegate] keeps counting the sleeps like
 * any other. Only code that measures a duration with the wall clock notices, which is the point.
 */
private class CorrectedClock(private val delegate: FakeClock, private val stepMs: Long) : WallClock, Sleeper {
    private var drift = 0L

    override fun millis(): Long = delegate.millis() + drift

    override fun nanos(): Long = delegate.nanos()

    override fun sleep(millis: Long) {
        delegate.sleep(millis)
        drift += stepMs
    }
}

/**
 * Id of the netns provider in the create-time-dependent tests. Deliberately unlike its name ("app"):
 * a reference spelled as an id is the one that dies with the container, and a fake whose ids double as
 * names could not tell the two cases apart.
 */
private const val PROVIDER_ID = "app1234567890abcdef"

/** Ids of a three-link namespace chain (`c` -> `b` -> `a`), spelled unlike their names for the same reason. */
private const val A_ID = "a1234567890abcdef00"
private const val B_ID = "b1234567890abcdef00"
private const val C_ID = "c1234567890abcdef00"

/**
 * Register a container the daemon knows under [name], with no kodkod labels of its own — the shape of
 * a bystander a reconcile pass has to reason about (who holds a name, and is it alive) rather than of
 * an update target.
 */
private fun FakeDockerClient.holder(
    id: String,
    name: String,
    state: String,
    /** `State.StartedAt` as the daemon spells it; absent is how a container that never ran reads. */
    startedAt: String? = null,
    /** `State.FinishedAt`; absent for a container that never ran, or never stopped. */
    finishedAt: String? = null,
) {
    val running = state == "running"
    val times = listOfNotNull(startedAt?.let { "StartedAt" to it }, finishedAt?.let { "FinishedAt" to it })
        .joinToString("") { (key, value) -> ""","$key":"$value"""" }
    listed += Json.parseToJsonElement("""{"Id":"$id","Names":["/$name"],"State":"$state","Labels":{}}""").jsonObject
    containers[id] = Json.parseToJsonElement(
        """{"Name":"/$name","Config":{},"HostConfig":{},"NetworkSettings":{"Networks":{}},""" +
            """"State":{"Running":$running$times}}""",
    ).jsonObject
}

/**
 * Register a container parked under its own `_kodkod_old_<short id>` backup name and stopped: exactly
 * what a kodkod killed between `rename(old -> backup)` and the replacement's `start` leaves behind.
 */
private fun FakeDockerClient.orphanedBackup(id: String, name: String) {
    holder(id = id, name = "${name}_kodkod_old_${id.take(12)}", state = "exited")
}

/**
 * Register a running, update-eligible container plus its current image. Test bodies then layer on
 * `distribution`/`images`/`onPull` entries to drive the specific staleness path under test.
 */
private fun FakeDockerClient.container(
    id: String,
    name: String = id,
    imageRef: String = "img:1",
    currentImageId: String = "sha256:$id-old",
    currentRepoDigests: List<String> = emptyList(),
    labels: String = "{}",
    hostConfig: String = "{}",
    networks: String = "{}",
    /** Top-level `Mounts[]`, the only place an anonymous volume's generated name appears. */
    mounts: String = "[]",
    configMacAddress: String? = null,
    configStopTimeout: Int? = null,
    imageManifestPlatform: String? = null,
    state: String = "running",
) {
    val repoDigests = currentRepoDigests.joinToString(",", "[", "]") { "\"$it\"" }
    val mac = configMacAddress?.let { ",\"MacAddress\":\"$it\"" } ?: ""
    // `docker run --stop-timeout` / compose `stop_grace_period`, as the daemon records it.
    val stopTimeout = configStopTimeout?.let { ",\"StopTimeout\":$it" } ?: ""
    // Engines that report it put the resolved manifest (and its platform) on the container inspect.
    val manifest = imageManifestPlatform?.let { ""","ImageManifestDescriptor":{"platform":$it}""" } ?: ""
    // The listing carries names, state, `HostConfig.NetworkMode` and the endpoints' `Links` as the
    // daemon does — that is all a create-time dependency of another container can be recognised from.
    listed += Json.parseToJsonElement(
        """{"Id":"$id","Names":["/$name"],"State":"$state","Labels":$labels,""" +
            """"HostConfig":$hostConfig,"NetworkSettings":{"Networks":$networks}}""",
    ).jsonObject
    containers[id] = Json.parseToJsonElement(
        """{"Name":"/$name","Image":"$currentImageId","Mounts":$mounts,""" +
            """"Config":{"Image":"$imageRef","Labels":$labels$mac$stopTimeout},""" +
            """"HostConfig":$hostConfig,"NetworkSettings":{"Networks":$networks}$manifest}""",
    ).jsonObject
    images[currentImageId] = Json.parseToJsonElement(
        """{"Id":"$currentImageId","Config":{},"RepoDigests":$repoDigests}""",
    ).jsonObject
}