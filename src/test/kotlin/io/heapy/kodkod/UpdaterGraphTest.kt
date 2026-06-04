package io.heapy.kodkod

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdaterGraphTest {
    private fun jsonObj(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun target(
        id: String,
        name: String,
        inspect: String = "{}",
        project: String? = null,
        service: String? = null,
        labels: String = "{}",
    ): Target = Target(
        id = id,
        name = name,
        inspect = jsonObj(inspect),
        imageRef = "img:latest",
        currentImageId = "sha256:$id",
        composeLabels = jsonObj(labels),
        composeProject = project,
        composeService = service,
    )

    @Test
    fun splitImageRef_cases() {
        assertEquals("nginx" to "1.27", splitImageRef("nginx:1.27"))
        assertEquals("nginx" to "latest", splitImageRef("nginx"))
        assertEquals("registry:5000/repo" to "latest", splitImageRef("registry:5000/repo"))
        assertEquals("registry:5000/repo" to "tag", splitImageRef("registry:5000/repo:tag"))
    }

    @Test
    fun distributionDigest_reads_descriptor_digest() {
        val digest = jsonObj(
            """{"Descriptor":{"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"sha256:abc123","size":123}}""",
        ).distributionDigest()
        assertEquals("sha256:abc123", digest)
    }

    @Test
    fun repoDigests_extracts_digest_values() {
        val digests = jsonObj(
            """{"RepoDigests":["registry:5000/repo@sha256:abc123","docker.io/library/nginx@sha256:def456"]}""",
        ).repoDigests()
        assertEquals(setOf("sha256:abc123", "sha256:def456"), digests)
    }

    @Test
    fun resolveLinks_prefers_compose_depends_on() {
        val db = target("db", "proj-db", project = "proj", service = "db")
        val web = target(
            "web", "proj-web", project = "proj", service = "web",
            labels = """{"com.docker.compose.depends_on":"db:service_started:true"}""",
        )
        resolveLinks(listOf(web, db), "kodkod")
        assertEquals(setOf("db"), web.deps)
        assertTrue(web.createTimeDeps.isEmpty())
        assertTrue(db.deps.isEmpty())
    }

    @Test
    fun resolveLinks_falls_back_to_label_and_links() {
        val a = target("a", "a")
        val b = target("b", "b", labels = """{"kodkod.depends-on":"a"}""")
        val c = target("c", "c", inspect = """{"HostConfig":{"Links":["/a:/c/a"]}}""")
        resolveLinks(listOf(a, b, c), "kodkod")
        assertEquals(setOf("a"), b.deps)
        assertEquals(setOf("a"), c.deps)
        assertTrue(b.createTimeDeps.isEmpty())
        assertEquals(setOf("a"), c.createTimeDeps)
    }

    @Test
    fun resolveLinks_handles_container_network_mode() {
        val a = target("a1b2c3d4e5f6", "a")
        val b = target("b", "b", inspect = """{"HostConfig":{"NetworkMode":"container:a1b2c3d4e5f6"}}""")
        resolveLinks(listOf(a, b), "kodkod")
        assertEquals(setOf("a1b2c3d4e5f6"), b.deps)
        assertEquals(setOf("a1b2c3d4e5f6"), b.createTimeDeps)
        assertEquals("a", b.networkModeContainerName)
    }

    @Test
    fun resolveLinks_resolves_external_container_network_mode_before_update() {
        val b = target("b", "b", inspect = """{"HostConfig":{"NetworkMode":"container:abc123"}}""")
        resolveLinks(listOf(b), "kodkod") { ref ->
            assertEquals("abc123", ref)
            "provider"
        }
        assertTrue(b.deps.isEmpty())
        assertTrue(b.createTimeDeps.isEmpty())
        assertEquals("provider", b.networkModeContainerName)
    }

    @Test
    fun topoSort_orders_dependencies_first() {
        val a = target("a", "a"); val b = target("b", "b"); val c = target("c", "c")
        b.deps = setOf("a"); c.deps = setOf("b")
        val ordered = topoSort(listOf(c, b, a)).map { it.id }
        assertTrue(ordered.indexOf("a") < ordered.indexOf("b"))
        assertTrue(ordered.indexOf("b") < ordered.indexOf("c"))
    }

    @Test
    fun topoSort_tolerates_a_cycle() {
        val a = target("a", "a"); val b = target("b", "b")
        a.deps = setOf("b"); b.deps = setOf("a")
        val ordered = topoSort(listOf(a, b)).map { it.id }
        assertEquals(setOf("a", "b"), ordered.toSet())
        assertEquals(2, ordered.size) // every node appears exactly once
    }

    @Test
    fun propagateLinkedRestart_is_transitive() {
        val a = target("a", "a"); val b = target("b", "b"); val c = target("c", "c")
        b.deps = setOf("a"); c.deps = setOf("b")
        a.stale = true
        propagateLinkedRestart(listOf(a, b, c))
        assertTrue(b.toRestart)
        assertTrue(c.toRestart)
        assertFalse(b.toRecreate)
        assertFalse(c.toRecreate)
    }

    @Test
    fun propagateLinkedRestart_leaves_unrelated_alone() {
        val a = target("a", "a"); val b = target("b", "b")
        a.stale = true
        propagateLinkedRestart(listOf(a, b))
        assertFalse(b.toRestart)
    }

    @Test
    fun propagateLinkedRestart_recreates_create_time_dependents() {
        val a = target("a", "a"); val b = target("b", "b")
        b.deps = setOf("a")
        b.createTimeDeps = setOf("a")
        a.stale = true

        propagateLinkedRestart(listOf(a, b))

        assertTrue(b.toRestart)
        assertTrue(b.toRecreate)
        assertTrue(b.linkedToRecreate)
    }

    @Test
    fun propagateLinkedRestart_recreate_is_transitive_for_create_time_dependencies() {
        val a = target("a", "a"); val b = target("b", "b"); val c = target("c", "c")
        b.deps = setOf("a")
        b.createTimeDeps = setOf("a")
        c.deps = setOf("b")
        c.createTimeDeps = setOf("b")
        a.stale = true

        propagateLinkedRestart(listOf(a, b, c))

        assertTrue(b.toRecreate)
        assertTrue(c.toRecreate)
    }
}
