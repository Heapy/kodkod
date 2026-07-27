package io.heapy.kodkod

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises [findDependents] against a [FakeDockerClient], which models the daemon's `all` and
 * `label` filtering — so a test can tell "kodkod looked beyond its own project" apart from "kodkod
 * happened to have the container in the set it was already holding".
 */
class DependentsTest {

    private val providerId = "prov1234567890abcdef"

    private fun provider(project: String? = "stack") =
        DependencyProvider(providerId, setOf("provider"), project)

    private fun docker(vararg containers: JsonObject): FakeDockerClient =
        FakeDockerClient().apply {
            listed += containerSummary(providerId, "provider")
            listed += containers
        }

    @Test
    fun finds_a_netns_consumer_that_names_the_provider_by_full_id() {
        val docker = docker(containerSummary("side1", "sidecar", networkMode = "container:$providerId"))

        assertEquals(
            listOf("side1" to DependencyKind.NETNS),
            findDependents(docker, provider()).map { it.id to it.kind },
        )
    }

    @Test
    fun finds_a_netns_consumer_that_names_the_provider_by_name() {
        val docker = docker(containerSummary("side1", "sidecar", networkMode = "container:provider"))

        assertEquals(listOf("side1"), findDependents(docker, provider()).map { it.id })
    }

    @Test
    fun finds_a_netns_consumer_that_names_the_provider_by_short_id() {
        val docker = docker(containerSummary("side1", "sidecar", networkMode = "container:${providerId.take(12)}"))

        assertEquals(listOf("side1"), findDependents(docker, provider()).map { it.id })
    }

    @Test
    fun finds_a_legacy_link_dependent() {
        val docker = docker(containerSummary("web1", "web", links = "\"provider:db\""))

        assertEquals(
            listOf("web1" to DependencyKind.LINK),
            findDependents(docker, provider()).map { it.id to it.kind },
        )
    }

    @Test
    fun a_container_that_merely_shares_a_network_is_not_a_dependent() {
        val docker = docker(
            containerSummary("peer1", "peer"),
            containerSummary("other", "other", networkMode = "container:somebodyelse"),
        )

        // Resolved once: the message is built eagerly, and a second scan in it would double every
        // listing this test's neighbours assert `listFilters` on.
        val found = findDependents(docker, provider())

        assertTrue(found.isEmpty(), "sharing a bridge network is not a create-time dependency: $found")
    }

    @Test
    fun a_stopped_dependent_is_reported_with_its_state() {
        val docker = docker(containerSummary("side1", "sidecar", networkMode = "container:$providerId", state = "exited"))

        val dependent = findDependents(docker, provider()).single()
        assertEquals("exited", dependent.state, "the state is carried through, not just the fact of a match")
        assertFalse(dependent.running, "an exited dependent must not read as running")
    }

    @Test
    fun a_consumer_outside_the_compose_project_is_found_once_the_project_shares_a_namespace() {
        val docker = docker(
            containerSummary("side1", "sidecar", networkMode = "container:$providerId"),
            containerSummary("side2", "outsider", networkMode = "container:$providerId", project = null),
        )

        assertEquals(
            listOf("side1", "side2"), findDependents(docker, provider()).map { it.id },
            "a sidecar started outside compose shares the same dead namespace and must be found too",
        )
    }

    @Test
    fun a_provider_without_a_compose_project_is_searched_across_the_whole_daemon() {
        val docker = docker(containerSummary("side2", "outsider", networkMode = "container:$providerId", project = null))

        assertEquals(
            listOf("side2"), findDependents(docker, provider(project = null)).map { it.id },
            "nothing to narrow by means the whole daemon is the search space",
        )
        assertEquals(
            listOf(emptyMap<String, List<String>>()), docker.listFilters,
            "one unfiltered listing, not a project probe that cannot match: ${docker.listFilters}",
        )
    }

    @Test
    fun a_compose_project_that_shares_nothing_costs_one_narrowed_listing() {
        val docker = docker(containerSummary("peer1", "peer"))

        assertTrue(findDependents(docker, provider()).isEmpty(), "a peer on the same bridge is not a dependent")
        assertEquals(
            listOf(mapOf("label" to listOf("$COMPOSE_PROJECT_LABEL=stack"))), docker.listFilters,
            "the common case must not pull (or record) every container on the host: ${docker.listFilters}",
        )
    }

    @Test
    fun the_provider_is_never_its_own_dependent() {
        val docker = FakeDockerClient()
        // A provider whose own NetworkMode is `container:` — it joined somebody else's namespace.
        docker.listed += containerSummary(providerId, "provider", networkMode = "container:$providerId")

        assertTrue(findDependents(docker, provider()).isEmpty(), "a container cannot depend on itself")
    }

    @Test
    fun a_listing_failure_yields_no_dependents_instead_of_propagating() {
        val docker = docker(containerSummary("side1", "sidecar", networkMode = "container:$providerId"))
        docker.failList = true

        assertTrue(
            findDependents(docker, provider()).isEmpty(),
            "a failed listing must not abort the restart that prompted it",
        )
    }

    /**
     * The scan is two listings — the provider's own compose project, then the whole daemon — and losing
     * the second one is not the same as losing the first. What the narrow listing already found are
     * containers sharing a namespace that is about to be torn down; dropping them because a *wider*
     * listing failed leaves exactly those containers `Running` with no interfaces, which is the failure
     * this whole scan exists to prevent.
     */
    @Test
    fun a_failed_daemon_wide_listing_keeps_the_dependents_the_project_listing_found() {
        val docker = docker(containerSummary("side1", "sidecar", networkMode = "container:$providerId"))
        docker.failListWhen = { filters -> filters.isEmpty() }

        assertEquals(
            listOf("side1"), findDependents(docker, provider()).map { it.id },
            "the in-project sidecar was already found, and its namespace dies either way",
        )
    }

    @Test
    fun link_entries_are_read_in_both_spellings() {
        assertEquals("db", linkSource("/db:/web/db"), "inspect spells a link with leading slashes")
        assertEquals("db", linkSource("db:alias"), "a listing endpoint spells the same link without them")
    }

    @Test
    fun only_a_container_network_mode_yields_a_netns_reference() {
        assertEquals("abc", netnsRef(jsonObj("""{"NetworkMode":"container:abc"}""")))
        assertEquals(null, netnsRef(jsonObj("""{"NetworkMode":"bridge"}""")))
        assertEquals(null, netnsRef(jsonObj("""{"NetworkMode":"container:"}""")), "an empty reference is not a reference")
        assertEquals(null, netnsRef(null))
    }
}
