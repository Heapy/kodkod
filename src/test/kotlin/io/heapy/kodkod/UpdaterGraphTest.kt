package io.heapy.kodkod

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
        platform = null,
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
    fun imagePlatform_reads_os_and_arch_and_drops_the_variant() {
        val inspect = jsonObj(
            """{"ImageManifestDescriptor":{"digest":"sha256:abc","platform":{"architecture":"arm64","os":"linux","variant":"v8"}}}""",
        )
        assertEquals("linux/arm64", inspect.imagePlatform())
    }

    @Test
    fun imagePlatform_is_null_without_a_usable_descriptor() {
        assertNull(jsonObj("""{}""").imagePlatform(), "an engine that reports no descriptor pins nothing")
        assertNull(
            jsonObj("""{"ImageManifestDescriptor":{"digest":"sha256:abc"}}""").imagePlatform(),
            "a descriptor without a platform pins nothing",
        )
        assertNull(
            jsonObj("""{"ImageManifestDescriptor":{"platform":{"architecture":"","os":"linux"}}}""").imagePlatform(),
            "`linux/` is not a platform the daemon can resolve",
        )
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
    fun parseDependsOn_reads_all_three_fields() {
        val edges = parseDependsOn("db:service_healthy:true,cache:service_started:false")
        assertEquals(listOf("db", "cache"), edges.map { it.service })
        assertEquals(listOf("service_healthy", "service_started"), edges.map { it.condition })
        assertEquals(listOf(true, false), edges.map { it.restart })
    }

    @Test
    fun parseDependsOn_tolerates_labels_with_fewer_fields() {
        // Older compose versions emit fewer fields; a missing `restart` is "compose said nothing",
        // which must not read as the explicit `false` that can suppress a dependent's restart.
        assertEquals("service_healthy", parseDependsOn("db:service_healthy").single().condition)
        assertNull(parseDependsOn("db:service_healthy").single().restart)
        assertEquals("service_started", parseDependsOn("db").single().condition)
        assertNull(parseDependsOn("db").single().restart)
        assertNull(parseDependsOn("db:service_started:maybe").single().restart, "an unparsable flag is no flag")
        assertTrue(parseDependsOn("").isEmpty(), "compose stamps an empty label on a service with no deps")
        assertTrue(parseDependsOn(null).isEmpty())
    }

    @Test
    fun resolveLinks_keeps_the_condition_and_the_restart_flag_of_each_edge() {
        val db = target("db", "proj-db", project = "proj", service = "db")
        val cache = target("cache", "proj-cache", project = "proj", service = "cache")
        val web = target(
            "web", "proj-web", project = "proj", service = "web",
            labels = """{"com.docker.compose.depends_on":"db:service_healthy:false,cache:service_started:true"}""",
        )
        resolveLinks(listOf(web, db, cache), "kodkod")
        assertEquals(setOf("db", "cache"), web.deps)
        assertEquals(setOf("db"), web.healthGatedDeps, "only the service_healthy edge is gated on health")
        assertEquals(setOf("db"), web.noRestartDeps, "only the restart: false edge may ever be suppressed")
    }

    @Test
    fun propagateLinkedRestart_obeys_restart_false_only_when_asked_to() {
        fun graph(): Pair<Target, Target> {
            val a = target("a", "a")
            val b = target("b", "b")
            b.deps = setOf("a")
            b.noRestartDeps = setOf("a")
            a.stale = true
            return a to b
        }

        val (byDefault, dependentByDefault) = graph()
        propagateLinkedRestart(listOf(byDefault, dependentByDefault))
        assertTrue(
            dependentByDefault.toRestart,
            "restart: false is compose's default; obeying it by default would make kodkod's dependent " +
                "restart a no-op for nearly every stack",
        )

        val (respected, dependentRespected) = graph()
        propagateLinkedRestart(listOf(respected, dependentRespected), respectDependsOnRestart = true)
        assertFalse(dependentRespected.toRestart, "with the flag on the operator asked for compose's semantics")
    }

    @Test
    fun propagateLinkedRestart_never_lets_restart_false_suppress_a_recreate() {
        val a = target("a", "a")
        val b = target("b", "b")
        b.deps = setOf("a")
        b.createTimeDeps = setOf("a") // shares a's network namespace
        b.noRestartDeps = setOf("a")
        a.stale = true

        propagateLinkedRestart(listOf(a, b), respectDependsOnRestart = true)

        assertTrue(
            b.linkedToRecreate,
            "suppressing a create-time edge would leave b attached to a namespace that no longer exists",
        )
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
