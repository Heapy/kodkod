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
    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun config(monitorAll: Boolean = true, cleanup: Boolean = true): Config =
        Config.fromEnv(
            mapOf(
                "KODKOD_UPDATE_MONITOR_ALL" to monitorAll.toString(),
                "KODKOD_UPDATE_CLEANUP" to cleanup.toString(),
            )::get,
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

        Updater(docker, config(), selfId = null).runOnce()

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

        Updater(docker, config(), selfId = null).runOnce()

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

        Updater(docker, config(), selfId = null).runOnce()

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

        Updater(docker, config(), selfId = null).runOnce()

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

        Updater(docker, config(), selfId = null).runOnce()

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

        Updater(docker, config(), selfId = null).runOnce()

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

        Updater(docker, config(), selfId = null).runOnce()

        val body = docker.created.single().second
        assertNull(body.obj("Labels").label("com.docker.compose.image"), "kodkod must not fabricate compose metadata: $body")
    }

    // --- rollback -------------------------------------------------------------------------

    @Test
    fun a_failed_create_rolls_back_to_the_original_container() {
        val docker = FakeDockerClient()
        docker.container(id = "web", imageRef = "nginx:1.27", currentImageId = "sha256:old")
        docker.images["nginx:1.27"] = json("""{"Id":"sha256:new","Config":{},"RepoDigests":[]}""")
        docker.failCreate += "web"

        Updater(docker, config(), selfId = null).runOnce() // runOnce logs and swallows the recreate failure

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

        Updater(docker, config(), selfId = null).runOnce()

        assertOrder(docker.ops, "create:web", "start!:new-web-0", "remove:new-web-0", "rename:web->web", "start:web")
        assertFalse(docker.ops.contains("start:new-web-0"), "the start failed and must not read as done: ${docker.ops}")
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
    configMacAddress: String? = null,
) {
    val repoDigests = currentRepoDigests.joinToString(",", "[", "]") { "\"$it\"" }
    val mac = configMacAddress?.let { ",\"MacAddress\":\"$it\"" } ?: ""
    listed += Json.parseToJsonElement("""{"Id":"$id","Labels":$labels}""").jsonObject
    containers[id] = Json.parseToJsonElement(
        """{"Name":"/$name","Image":"$currentImageId",""" +
            """"Config":{"Image":"$imageRef","Labels":$labels$mac},""" +
            """"HostConfig":$hostConfig,"NetworkSettings":{"Networks":$networks}}""",
    ).jsonObject
    images[currentImageId] = Json.parseToJsonElement(
        """{"Id":"$currentImageId","Config":{},"RepoDigests":$repoDigests}""",
    ).jsonObject
}