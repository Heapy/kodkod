package io.heapy.kodkod

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises [Updater.runOnce] end-to-end against a [FakeDockerClient]. These cover the staleness
 * decision (`markStale` — digest-pinned / registry-digest / pull paths) and the recreate + rollback
 * machinery, all of which used to be reachable only from the Docker-backed e2e suite.
 */
class UpdaterTest {
    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun config(monitorAll: Boolean = true, cleanup: Boolean = true): Config =
        Config.fromEnv(
            mapOf(
                "KODKOD_UPDATE_MONITOR_ALL" to monitorAll.toString(),
                "KODKOD_UPDATE_CLEANUP" to cleanup.toString(),
            )::get,
        )

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

        Updater(docker, config(), selfId = null).runOnce()

        assertTrue(docker.ops.isEmpty(), "digest-pinned container must not be pulled or recreated: ${docker.ops}")
    }

    @Test
    fun unlabelled_containers_are_ignored_when_not_monitoring_all() {
        val docker = FakeDockerClient()
        docker.container(id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old") // no enable label
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")

        Updater(docker, config(monitorAll = false), selfId = null).runOnce()

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

        Updater(docker, config(), selfId = null).runOnce()

        assertTrue(docker.ops.isEmpty(), "running image already carries the registry digest — no work expected: ${docker.ops}")
    }

    @Test
    fun image_unchanged_after_pull_does_not_recreate() {
        val docker = FakeDockerClient()
        docker.container(id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old")
        // No distribution entry -> fall back to a pull; the pulled image id matches the running one.
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:old","Config":{},"RepoDigests":[]}""")

        Updater(docker, config(), selfId = null).runOnce()

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

        Updater(docker, config(), selfId = null).runOnce()

        assertFalse(docker.ops.any { it.startsWith("pull:") }, "should reuse the present local image, not pull: ${docker.ops}")
        assertTrue(docker.ops.contains("create:web"), "stale container should be recreated: ${docker.ops}")
    }

    @Test
    fun pulls_and_recreates_when_pulled_image_differs() {
        val docker = FakeDockerClient()
        docker.container(id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old")
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")

        Updater(docker, config(), selfId = null).runOnce()

        assertOrder(docker.ops, "pull:nginx:1.27", "create:web")
    }

    // --- recreate -------------------------------------------------------------------------

    @Test
    fun recreate_replaces_the_container_and_prunes_the_old_image() {
        val docker = FakeDockerClient()
        docker.container(id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old")
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")

        Updater(docker, config(cleanup = true), selfId = null).runOnce()

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

        Updater(docker, config(cleanup = false), selfId = null).runOnce()

        assertTrue(docker.ops.contains("create:web"))
        assertFalse(docker.ops.any { it.startsWith("removeImage:") }, "cleanup disabled must not prune the old image: ${docker.ops}")
    }

    @Test
    fun recreate_puts_the_first_network_in_the_create_body_and_connects_the_rest() {
        val docker = FakeDockerClient()
        docker.container(
            id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old",
            networks = """{"frontend":{},"backend":{}}""",
        )
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")

        Updater(docker, config(), selfId = null).runOnce()

        val (_, body) = docker.created.single()
        val endpoints = body.obj("NetworkingConfig")?.obj("EndpointsConfig")
        assertEquals(setOf("frontend"), endpoints?.keys, "only the first network belongs in the create body")
        assertTrue(docker.ops.contains("connect:backend:new-web-0"), "the rest are connected after create: ${docker.ops}")
    }

    // --- rollback -------------------------------------------------------------------------

    @Test
    fun a_failed_create_rolls_back_to_the_original_container() {
        val docker = FakeDockerClient()
        docker.container(id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old")
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")
        docker.failCreate += "web"

        Updater(docker, config(), selfId = null).runOnce() // runOnce logs and swallows the recreate failure

        assertOrder(docker.ops, "rename:web->web_kodkod_old_web", "create:web", "rename:web->web", "start:web")
        assertFalse(docker.ops.contains("remove:web"), "the original container must survive a failed create: ${docker.ops}")
        assertTrue(docker.created.isEmpty(), "no replacement should have been recorded: ${docker.created}")
    }

    @Test
    fun a_failed_start_removes_the_replacement_and_rolls_back() {
        val docker = FakeDockerClient()
        docker.container(id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old")
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")
        docker.failStart += "new-web-0" // the freshly-created container fails to start

        Updater(docker, config(), selfId = null).runOnce()

        assertOrder(docker.ops, "create:web", "start:new-web-0", "remove:new-web-0", "rename:web->web", "start:web")
        assertFalse(docker.ops.contains("remove:web"), "the original container must survive a failed start: ${docker.ops}")
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

        Updater(docker, config(), selfId = null).runOnce()

        assertOrder(docker.ops, "stop:web", "stop:db", "create:db", "start:web")
        assertFalse(docker.ops.contains("create:web"), "web only depends on db; it is restarted, not recreated: ${docker.ops}")
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
) {
    val repoDigests = currentRepoDigests.joinToString(",", "[", "]") { "\"$it\"" }
    listed += Json.parseToJsonElement("""{"Id":"$id","Labels":$labels}""").jsonObject
    containers[id] = Json.parseToJsonElement(
        """{"Name":"/$name","Image":"$currentImageId",""" +
            """"Config":{"Image":"$imageRef","Labels":$labels},""" +
            """"HostConfig":$hostConfig,"NetworkSettings":{"Networks":$networks}}""",
    ).jsonObject
    images[currentImageId] = Json.parseToJsonElement(
        """{"Id":"$currentImageId","Config":{},"RepoDigests":$repoDigests}""",
    ).jsonObject
}