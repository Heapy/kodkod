package io.heapy.kodkod

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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

    private fun updater(docker: FakeDockerClient, config: Config = config()): Updater =
        Updater(docker, config, selfId = null, clock, clock)

    private fun config(
        monitorAll: Boolean = true,
        cleanup: Boolean = true,
        stopTimeout: String? = null,
        verifySeconds: String? = null,
        verifyHealth: Boolean? = null,
    ): Config =
        Config.fromEnv(
            buildMap {
                put("KODKOD_UPDATE_MONITOR_ALL", monitorAll.toString())
                put("KODKOD_UPDATE_CLEANUP", cleanup.toString())
                stopTimeout?.let { put("KODKOD_STOP_TIMEOUT", it) }
                verifySeconds?.let { put("KODKOD_UPDATE_VERIFY_SECONDS", it) }
                verifyHealth?.let { put("KODKOD_UPDATE_VERIFY_HEALTH", it.toString()) }
            }::get,
        )

    /** The endpoint config the create body asks for on [network]. */
    private fun JsonObject.endpoint(network: String): JsonObject =
        obj("NetworkingConfig")?.obj("EndpointsConfig")?.obj(network) ?: error("no endpoint for '$network' in $this")

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

        updater(docker).runOnce()

        val web = docker.created.single { (name, _) -> name == "web" }.second
        assertEquals(
            "sha256:web-old", web.obj("Labels").label("com.docker.compose.image"),
            "web's own image did not change — the label must be copied verbatim",
        )
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
    }

    @Test
    fun the_label_wins_over_the_containers_own_stop_timeout() {
        val docker = FakeDockerClient()
        staleWeb(docker, labels = """{"kodkod.stop.timeout":"45"}""", configStopTimeout = 30)

        updater(docker).runOnce()

        assertEquals(listOf<Int?>(45, 45), docker.stopTimeouts, "an explicit label is an override: ${docker.stopTimeouts}")
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
    configMacAddress: String? = null,
    configStopTimeout: Int? = null,
    imageManifestPlatform: String? = null,
) {
    val repoDigests = currentRepoDigests.joinToString(",", "[", "]") { "\"$it\"" }
    val mac = configMacAddress?.let { ",\"MacAddress\":\"$it\"" } ?: ""
    // `docker run --stop-timeout` / compose `stop_grace_period`, as the daemon records it.
    val stopTimeout = configStopTimeout?.let { ",\"StopTimeout\":$it" } ?: ""
    // Engines that report it put the resolved manifest (and its platform) on the container inspect.
    val manifest = imageManifestPlatform?.let { ""","ImageManifestDescriptor":{"platform":$it}""" } ?: ""
    listed += Json.parseToJsonElement("""{"Id":"$id","Labels":$labels}""").jsonObject
    containers[id] = Json.parseToJsonElement(
        """{"Name":"/$name","Image":"$currentImageId",""" +
            """"Config":{"Image":"$imageRef","Labels":$labels$mac$stopTimeout},""" +
            """"HostConfig":$hostConfig,"NetworkSettings":{"Networks":$networks}$manifest}""",
    ).jsonObject
    images[currentImageId] = Json.parseToJsonElement(
        """{"Id":"$currentImageId","Config":{},"RepoDigests":$repoDigests}""",
    ).jsonObject
}