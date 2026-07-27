package io.heapy.kodkod

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [FakeDockerClient] is the daemon every orchestration test believes, so the ways it can lie have to
 * be closed: a list that ignores its filters would let "we searched the whole daemon" pass on code
 * that only looked at the monitored set, and an op recorded before the call would let a failed
 * mutation read as a successful one.
 */
class FakeDockerClientTest {
    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private val docker = FakeDockerClient()

    private fun summary(id: String, state: String? = null, labels: String = "{}") {
        val stateField = state?.let { ""","State":"$it"""" }.orEmpty()
        docker.listed += json("""{"Id":"$id","Names":["/$id"]$stateField,"Labels":$labels}""")
    }

    private fun ids(all: Boolean, filters: Map<String, List<String>> = emptyMap()): List<String> =
        docker.listContainers(all, filters).mapNotNull { it.jsonObject.str("Id") }

    // --- listContainers: filters ----------------------------------------------------------

    @Test
    fun all_false_hides_containers_that_are_not_up() {
        summary("up", state = "running")
        summary("restarting", state = "restarting")
        summary("gone", state = "exited")

        assertEquals(listOf("up", "restarting"), ids(all = false))
        assertEquals(listOf("up", "restarting", "gone"), ids(all = true), "all=true returns everything")
    }

    @Test
    fun a_summary_without_a_state_counts_as_running() {
        summary("web")

        assertEquals(listOf("web"), ids(all = false), "fixtures that omit State are running containers")
    }

    @Test
    fun the_status_filter_selects_by_state() {
        summary("up", state = "running")
        summary("gone", state = "exited")

        assertEquals(listOf("up"), ids(all = true, filters = mapOf("status" to listOf("running"))))
        assertEquals(listOf("gone"), ids(all = true, filters = mapOf("status" to listOf("exited"))))
        assertEquals(
            listOf("up", "gone"),
            ids(all = true, filters = mapOf("status" to listOf("running", "exited"))),
            "values within one filter are OR'd",
        )
    }

    @Test
    fun the_label_filter_matches_presence_and_value() {
        summary("on", labels = """{"kodkod.update.enable":"true"}""")
        summary("off", labels = """{"kodkod.update.enable":"false"}""")
        summary("bare")

        assertEquals(listOf("on", "off"), ids(all = true, filters = mapOf("label" to listOf("kodkod.update.enable"))))
        assertEquals(listOf("on"), ids(all = true, filters = mapOf("label" to listOf("kodkod.update.enable=true"))))
    }

    @Test
    fun filters_are_combined_with_and() {
        summary("up", state = "running", labels = """{"kodkod.update.enable":"true"}""")
        summary("gone", state = "exited", labels = """{"kodkod.update.enable":"true"}""")
        summary("unlabelled", state = "running")

        val ids = ids(
            all = true,
            filters = mapOf("status" to listOf("running"), "label" to listOf("kodkod.update.enable")),
        )
        assertEquals(listOf("up"), ids, "a container must satisfy every filter, not just one")
    }

    @Test
    fun the_health_filter_selects_only_containers_whose_health_is_modelled() {
        summary("sick", state = "running")
        summary("well", state = "running")
        summary("unmodelled", state = "running")
        docker.health["sick"] = "unhealthy"
        docker.health["well"] = "healthy"

        assertEquals(
            listOf("sick"),
            ids(all = false, filters = mapOf("health" to listOf("unhealthy"))),
            "a container of unknown health belongs in no health-filtered listing — matching every one of " +
                "them made 'still unhealthy' and 'healthy again' the same observation",
        )
        assertEquals(listOf("well"), ids(all = false, filters = mapOf("health" to listOf("healthy"))))
    }

    @Test
    fun the_id_filter_matches_by_prefix() {
        summary("abcdef123456", state = "running")
        summary("999999999999", state = "running")

        assertEquals(listOf("abcdef123456"), ids(all = true, filters = mapOf("id" to listOf("abcdef"))))
    }

    // --- lifecycle reflected in listings ----------------------------------------------------

    @Test
    fun a_removed_container_is_gone_from_every_listing_and_answers_404() {
        summary("web", state = "running")
        docker.containers["web"] = json("""{"Name":"/web"}""")

        docker.remove("web", force = true)

        assertEquals(emptyList<String>(), ids(all = true), "the daemon does not list what it destroyed")
        val gone = assertThrows(DockerException::class.java) { docker.inspectContainer("web") }
        assertEquals(404, gone.status, "and it answers 404, not a fixture error")
    }

    @Test
    fun a_created_container_is_listed_as_created_and_becomes_running_when_started() {
        val id = docker.create("web", json("""{"Image":"app:1","Labels":{"a":"b"}}"""), platform = null)

        assertEquals(emptyList<String>(), ids(all = false), "a created container is not up yet")
        assertEquals(listOf(id), ids(all = true, filters = mapOf("label" to listOf("a=b"))))

        docker.start(id)

        assertEquals(listOf(id), ids(all = false), "the daemon lists it as running once it is started")
    }

    @Test
    fun a_renamed_container_answers_to_its_new_name_in_a_listing() {
        summary("web", state = "running")

        docker.rename("web", "web${BACKUP_MARKER}web")

        assertEquals(
            listOf("web"), ids(all = true, filters = mapOf("name" to listOf(BACKUP_MARKER))),
            "the name index the reconcile pass queries is the daemon's, not the one the fixture was written with",
        )
    }

    @Test
    fun the_name_filter_matches_any_part_of_a_name() {
        summary("web", state = "running")
        summary("web${BACKUP_MARKER}abc123456789", state = "exited")

        assertEquals(
            listOf("web${BACKUP_MARKER}abc123456789"),
            ids(all = true, filters = mapOf("name" to listOf(BACKUP_MARKER))),
            "the daemon matches a name filter unanchored, which is how the reconcile pass narrows all=true",
        )
        assertEquals(
            listOf("web", "web${BACKUP_MARKER}abc123456789"),
            ids(all = true, filters = mapOf("name" to listOf("web"))),
            "a substring hit is still a hit: a name-filtered listing narrows, it does not look up",
        )
    }

    // --- ops: done vs attempted -----------------------------------------------------------

    @Test
    fun a_failed_create_is_marked_and_records_no_body() {
        docker.failCreate += "web"

        assertThrows(DockerException::class.java) { docker.create("web", json("""{"Image":"app:1"}"""), platform = null) }

        assertEquals(listOf("create!:web"), docker.ops)
        assertTrue(docker.created.isEmpty(), "a create that threw produced no body")
    }

    @Test
    fun a_failed_start_remove_and_rename_are_all_marked() {
        docker.failStart += "web"
        docker.failRemove += "web"
        docker.failRename += "taken"

        assertThrows(DockerException::class.java) { docker.start("web") }
        assertThrows(DockerException::class.java) { docker.remove("web", force = true) }
        val conflict = assertThrows(DockerException::class.java) { docker.rename("web", "taken") }

        assertEquals(listOf("start!:web", "remove!:web", "rename!:web->taken"), docker.ops)
        assertEquals(409, conflict.status, "a taken name is a name conflict, like the daemon's")
    }

    @Test
    fun successful_mutations_are_recorded_without_a_marker() {
        docker.create("web", json("{}"), platform = null)
        docker.start("new-web-0")
        docker.rename("new-web-0", "web2")
        docker.remove("new-web-0", force = true)

        assertEquals(listOf("create:web", "start:new-web-0", "rename:new-web-0->web2", "remove:new-web-0"), docker.ops)
    }

    // --- call arguments -------------------------------------------------------------------

    @Test
    fun stop_restart_and_image_removal_record_their_arguments() {
        docker.stop("web", timeout = 30)
        docker.restart("db", timeout = 5)
        docker.removeImage("sha256:old")

        assertEquals(listOf<Int?>(30), docker.stopTimeouts)
        assertEquals(listOf<Int?>(5), docker.restartTimeouts)
        assertEquals(listOf("sha256:old"), docker.removedImages)
    }

    // --- inspect: lifecycle model ---------------------------------------------------------

    @Test
    fun a_container_that_exits_right_after_start_inspects_as_dead() {
        docker.containers["web"] = json("""{"Name":"/web"}""")
        docker.startedThenExits += "web"

        docker.start("web")

        val state = docker.inspectContainer("web").obj("State")!!
        assertFalse(state["Running"]!!.jsonPrimitive.booleanOrNull!!, "start succeeded but the process died")
        assertEquals(1, state["ExitCode"]!!.jsonPrimitive.int)
    }

    @Test
    fun start_and_stop_move_the_inspected_running_flag() {
        docker.containers["web"] = json("""{"Name":"/web"}""")

        assertTrue(running("web"), "a registered container is running until told otherwise")
        docker.stop("web", timeout = 10)
        assertFalse(running("web"))
        docker.start("web")
        assertTrue(running("web"))
    }

    @Test
    fun health_is_reported_through_inspect_and_other_state_fields_survive() {
        docker.containers["web"] = json("""{"Name":"/web","State":{"Restarting":true,"Status":"running"}}""")
        docker.health["web"] = "unhealthy"

        val state = docker.inspectContainer("web").obj("State")!!
        assertEquals("unhealthy", state.obj("Health")?.str("Status"))
        assertTrue(state["Restarting"]!!.jsonPrimitive.booleanOrNull!!, "fields the fake does not own pass through")
        assertEquals("running", state.str("Status"))
    }

    @Test
    fun a_name_a_live_container_holds_cannot_be_taken_from_it() {
        docker.containers["web"] = json("""{"Name":"/web"}""")
        docker.containers["old"] = json("""{"Name":"/web_old"}""")

        val conflict = assertThrows(DockerException::class.java) { docker.rename("old", "web") }
        assertEquals(409, conflict.status, "the daemon's name index refuses this, and so must the fake")

        docker.remove("web", force = true)
        docker.rename("old", "web")

        assertEquals("/web", docker.inspectContainer("old").str("Name"), "a rename moves the name it took")
    }

    @Test
    fun a_created_container_becomes_inspectable_unless_the_test_described_it_first() {
        val plain = docker.create("web", json("{}"), platform = null)
        docker.containers["new-db-1"] = json("""{"Name":"/db","State":{"Restarting":true}}""")
        val described = docker.create("db", json("{}"), platform = null)

        assertEquals("new-web-0", plain)
        assertEquals("/web", docker.inspectContainer(plain).str("Name"), "the daemon knows what it just created")
        assertTrue(running(plain))
        assertEquals("new-db-1", described)
        assertTrue(
            docker.inspectContainer(described).obj("State")!!["Restarting"]!!.jsonPrimitive.booleanOrNull!!,
            "a payload the test registered up front must not be overwritten by create",
        )
    }

    private fun running(id: String): Boolean =
        docker.inspectContainer(id).obj("State")!!["Running"]!!.jsonPrimitive.booleanOrNull!!
}
