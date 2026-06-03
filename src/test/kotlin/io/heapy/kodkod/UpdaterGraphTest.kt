package io.heapy.kodkod

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun resolveLinks_prefers_compose_depends_on() {
        val db = target("db", "proj-db", project = "proj", service = "db")
        val web = target(
            "web", "proj-web", project = "proj", service = "web",
            labels = """{"com.docker.compose.depends_on":"db:service_started:true"}""",
        )
        resolveLinks(listOf(web, db), "kodkod")
        assertEquals(setOf("db"), web.deps)
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
    }

    @Test
    fun resolveLinks_handles_container_network_mode() {
        val a = target("a1b2c3d4e5f6", "a")
        val b = target("b", "b", inspect = """{"HostConfig":{"NetworkMode":"container:a1b2c3d4e5f6"}}""")
        resolveLinks(listOf(a, b), "kodkod")
        assertEquals(setOf("a1b2c3d4e5f6"), b.deps)
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
    }

    @Test
    fun propagateLinkedRestart_leaves_unrelated_alone() {
        val a = target("a", "a"); val b = target("b", "b")
        a.stale = true
        propagateLinkedRestart(listOf(a, b))
        assertFalse(b.toRestart)
    }
}
