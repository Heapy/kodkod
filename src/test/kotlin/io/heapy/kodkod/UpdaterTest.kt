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

        updater.runOnce()

        assertTrue(docker.ops.contains("remove:web"), "the retry has to have actually gone through: ${docker.ops}")
        assertTrue(
            docker.ops.drop(afterSuccess).any { it.startsWith("create:") },
            "the memory records failures, not attempts: a container whose update went through carries no " +
                "cooldown into later cycles: ${docker.ops}",
        )
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

    @Test
    fun a_sidecar_that_cannot_be_recreated_is_rolled_back_and_reported() {
        val docker = FakeDockerClient()
        staleProviderWithSidecar(docker)
        docker.failCreate += "side"

        val log = captureLog { updater(docker, config(monitorAll = false)).runOnce() }

        assertTrue(
            log.contains("may be left without a working network"),
            "a dependent kodkod found and could not fix is exactly what must not be silent: $log",
        )
        assertTrue(running(docker, "side"), "the rollback has to put the sidecar back: ${docker.ops}")
        assertEquals("/side", docker.inspectContainer("side").str("Name"), "under its own name: ${docker.ops}")
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
        docker.holder(id = "new-web", name = "web", running = true)

        updater(docker).reconcileOrphanedBackups()

        assertEquals(listOf("remove:web-old"), docker.ops)
        assertEquals(
            "/web", docker.inspectContainer("new-web").str("Name"),
            "the running replacement keeps the name it already serves: ${docker.ops}",
        )
    }

    @Test
    fun an_orphaned_backup_is_restored_over_a_replacement_that_is_not_running() {
        val docker = FakeDockerClient()
        docker.orphanedBackup(id = "web-old", name = "web")
        docker.holder(id = "new-web", name = "web", running = false)

        updater(docker).reconcileOrphanedBackups()

        assertOrder(docker.ops, "remove:new-web", "rename:web-old->web", "start:web-old")
        assertTrue(running(docker, "web-old"), "the known-good container is the one that has to serve: ${docker.ops}")
    }

    @Test
    fun a_backup_suffix_carrying_someone_elses_id_is_left_alone() {
        val docker = FakeDockerClient()
        // Looks exactly like a backup of `web` — except the short id in the suffix is not its own.
        docker.holder(id = "impostor", name = "web_kodkod_old_deadbeef1234", running = false)
        docker.holder(id = "web", name = "web", running = true)

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
 * Id of the netns provider in the create-time-dependent tests. Deliberately unlike its name ("app"):
 * a reference spelled as an id is the one that dies with the container, and a fake whose ids double as
 * names could not tell the two cases apart.
 */
private const val PROVIDER_ID = "app1234567890abcdef"

/**
 * Register a container the daemon knows under [name], with no kodkod labels of its own — the shape of
 * a bystander a reconcile pass has to reason about (who holds a name, and is it alive) rather than of
 * an update target.
 */
private fun FakeDockerClient.holder(id: String, name: String, running: Boolean) {
    val state = if (running) "running" else "exited"
    listed += Json.parseToJsonElement("""{"Id":"$id","Names":["/$name"],"State":"$state","Labels":{}}""").jsonObject
    containers[id] = Json.parseToJsonElement(
        """{"Name":"/$name","Config":{},"HostConfig":{},"NetworkSettings":{"Networks":{}},"State":{"Running":$running}}""",
    ).jsonObject
}

/**
 * Register a container parked under its own `_kodkod_old_<short id>` backup name and stopped: exactly
 * what a kodkod killed between `rename(old -> backup)` and the replacement's `start` leaves behind.
 */
private fun FakeDockerClient.orphanedBackup(id: String, name: String) {
    holder(id = id, name = "${name}_kodkod_old_${id.take(12)}", running = false)
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
        """{"Name":"/$name","Image":"$currentImageId",""" +
            """"Config":{"Image":"$imageRef","Labels":$labels$mac$stopTimeout},""" +
            """"HostConfig":$hostConfig,"NetworkSettings":{"Networks":$networks}$manifest}""",
    ).jsonObject
    images[currentImageId] = Json.parseToJsonElement(
        """{"Id":"$currentImageId","Config":{},"RepoDigests":$repoDigests}""",
    ).jsonObject
}