package io.heapy.kodkod

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [OpLoggingClient] is what replay scenarios assert against, so its own bookkeeping has to be exact:
 * a call that threw must never look like a call that succeeded.
 */
class OpLoggingClientTest {

    private val fake = FakeDockerClient()
    private val client = OpLoggingClient(fake)

    @Test
    fun a_successful_mutation_is_logged_without_a_marker() {
        val id = client.create("web", jsonObj("""{"Image":"app:latest"}"""), platform = null)

        assertEquals(listOf("create:web"), client.ops)
        assertEquals(listOf("web"), client.created.map { it.first })
        assertEquals("new-web-0", id)
    }

    @Test
    fun a_failed_mutation_is_marked_and_does_not_count_as_done() {
        fake.failCreate += "web"

        assertThrows(DockerException::class.java) { client.create("web", jsonObj("""{"Image":"app:latest"}"""), platform = null) }

        assertEquals(listOf("create!:web"), client.ops, "an attempted create must not read as a create")
        assertTrue(client.created.isEmpty(), "a create that threw produced no body worth inspecting")
    }

    @Test
    fun a_failed_start_is_marked_too() {
        fake.failStart += "web-1"

        assertThrows(DockerException::class.java) { client.start("web-1") }

        assertEquals(listOf("start!:web-1"), client.ops)
    }

    @Test
    fun reads_are_tracked_separately_from_mutations() {
        fake.containers["c1"] = jsonObj("""{"Id":"c1","Name":"/web"}""")
        fake.images["app:latest"] = jsonObj("""{"Id":"sha256:aa"}""")
        fake.distribution["app:latest"] = "sha256:bb"

        client.listContainers(all = false, filters = emptyMap())
        client.inspectContainer("c1")
        client.inspectImage("app:latest")
        client.inspectDistribution("app:latest", registryAuth = null)

        assertEquals(
            listOf("list:all=false", "inspect:c1", "inspectImage:app:latest", "distribution:app:latest"),
            client.reads,
        )
        assertTrue(client.ops.isEmpty(), "reads never land in ops")
    }

    @Test
    fun ids_are_labelled_with_the_name_learned_from_inspect() {
        fake.containers["c1"] = jsonObj("""{"Id":"c1","Name":"/web"}""")
        client.inspectContainer("c1")

        client.stop("c1", timeout = 10)

        assertEquals(listOf("stop:web"), client.ops)
    }
}
