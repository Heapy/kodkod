package io.heapy.kodkod

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The behaviour every [DockerClient] must have, run against each implementation there is.
 *
 * kodkod's unit suite talks to an in-memory fake, and the fake decides what those tests can see. Over
 * this branch it was wrong five separate times — `remove` left the container in the listing, `rename`
 * was a no-op the inspect never reflected, a created container reported `State: created` forever, a
 * reference spelled as a name resolved to nothing, and any container matched any health filter. Each
 * time the fake's generosity had been holding up tests that could not fail, and each time it was
 * found by accident, cycles later.
 *
 * The cure is not a stricter fake but a shared contract: this class is subclassed twice, once against
 * [FakeDockerClient] (fast, in `test`) and once against [DockerApi] on a real daemon (in `e2eTest`).
 * A behaviour the fake invents now has to be invented by Docker too, or the pair goes red.
 *
 * It uses nothing but the [DockerClient] interface, so both subclasses are a handful of lines. What a
 * subclass owes it: a [client], and an [imageRef] that daemon can already create a container from.
 */
abstract class DockerClientContract {
    /** The implementation under test. */
    protected abstract val client: DockerClient

    /** An image the daemon under test can create from *without pulling* — the subclass ensures it is there. */
    protected abstract val imageRef: String

    private val createdIds = mutableListOf<String>()
    private var nameSeq = 0

    @AfterEach
    fun removeWhatTheContractCreated() {
        // Reversed: a namespace provider cannot go before the container joined to it.
        createdIds.asReversed().forEach { id -> runCatching { client.remove(id, force = true) } }
        createdIds.clear()
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun uniqueName(hint: String) = "kodkod-contract-$hint-${nameSeq++}"

    /** Create a long-lived container the contract can push through the lifecycle, and clean it up later. */
    private fun given(
        name: String,
        labels: Map<String, String> = emptyMap(),
        hostConfig: JsonObject? = null,
    ): String {
        val body = buildJsonObject {
            put("Image", imageRef)
            put("Cmd", buildJsonArray { add("sleep"); add("300") })
            if (labels.isNotEmpty()) putJsonObject("Labels") { labels.forEach { (k, v) -> put(k, v) } }
            hostConfig?.let { put("HostConfig", it) }
        }
        return client.create(name, body, null).also { createdIds += it }
    }

    private fun listedIds(all: Boolean, filters: Map<String, List<String>> = emptyMap()): List<String> =
        client.listContainers(all, filters).mapNotNull { it.jsonObject.str("Id") }

    private fun inspectedName(ref: String): String? =
        client.inspectContainer(ref).str("Name")?.trimStart('/')

    private fun isRunning(ref: String): Boolean =
        client.inspectContainer(ref).obj("State")?.get("Running")?.jsonPrimitive?.content == "true"

    private fun listedState(id: String): String? =
        client.listContainers(true, emptyMap()).map { it.jsonObject }
            .firstOrNull { it.str("Id") == id }?.str("State")

    // --- the contract ----------------------------------------------------------------------

    /**
     * A created container exists but is not running. kodkod leans on the difference in two places:
     * discovery filters on `status=running`, and the reconcile pass tells a replacement that never
     * started from one that ran and was stopped.
     */
    @Test
    fun a_created_container_is_visible_but_not_running() {
        val name = uniqueName("created")
        val id = given(name)

        assertEquals(name, inspectedName(id), "inspect must answer with the name it was created under")
        assertFalse(isRunning(id), "a container that was never started is not running")
        assertEquals("created", listedState(id), "and the listing has to say so too")
        assertTrue(id in listedIds(all = true), "all=true lists containers whatever their state")
        assertFalse(id in listedIds(all = false), "all=false is the running set, which this is not in")
    }

    /** Starting it moves it into the running set — in the inspect *and* in the listing. */
    @Test
    fun starting_a_container_moves_it_into_the_running_listing() {
        val id = given(uniqueName("start"))

        client.start(id)

        assertTrue(isRunning(id), "inspect must reflect the start")
        assertEquals("running", listedState(id))
        assertTrue(id in listedIds(all = false), "the running listing is what discovery reads")
    }

    /** And stopping it takes it back out, which is what makes a stopped container invisible to discovery. */
    @Test
    fun stopping_a_container_takes_it_out_of_the_running_listing() {
        val id = given(uniqueName("stop"))
        client.start(id)

        client.stop(id, timeout = 1)

        assertFalse(isRunning(id))
        assertFalse(id in listedIds(all = false), "a stopped container is not in the running set")
        assertTrue(id in listedIds(all = true), "but it still exists")
    }

    /**
     * A removed container is gone from everything. The fake once kept it, so a second cycle
     * re-discovered a container the daemon had destroyed — a state no daemon can produce, and one
     * that quietly held up the multi-cycle tests.
     */
    @Test
    fun a_removed_container_is_gone_from_every_answer() {
        val id = given(uniqueName("remove"))
        client.start(id)

        client.remove(id, force = true)
        createdIds -= id

        assertFalse(id in listedIds(all = true), "a removed container is in no listing")
        assertThrows(DockerException::class.java, { client.inspectContainer(id) }) {
            "inspecting a removed container must fail the way the daemon fails it, with a DockerException"
        }
    }

    /**
     * Rename is what the whole recreate dance is built on: the original is parked under a backup name
     * so the replacement can take the real one. If a rename does not change the name the container
     * answers to, every rollback test is theatre.
     */
    @Test
    fun a_renamed_container_answers_to_the_new_name_and_not_the_old() {
        val old = uniqueName("rename-from")
        val new = uniqueName("rename-to")
        val id = given(old)

        client.rename(id, new)

        assertEquals(new, inspectedName(id), "inspect by id must report the new name")
        assertEquals(new, inspectedName(new), "and the new name must resolve")
    }

    /**
     * Two containers cannot hold one name. `rollback` depends on being told so: it has to notice that
     * the replacement is still sitting on the service name rather than silently taking it away.
     */
    @Test
    fun renaming_onto_a_name_that_is_taken_is_refused() {
        val taken = uniqueName("holder")
        given(taken)
        val other = given(uniqueName("other"))

        assertThrows(DockerException::class.java, { client.rename(other, taken) }) {
            "the daemon answers 409 here, and kodkod's rollback is written against that"
        }
        assertEquals(taken, inspectedName(taken), "the refused rename must not have moved the name")
    }

    /**
     * The daemon resolves a container reference by full id, by name, or by an id prefix, and kodkod
     * relies on all three: compose writes `network_mode: container:<id>`, kodkod rewrites it to a
     * name, and `docker ps` shows people the short id they then put in a label.
     */
    @Test
    fun a_container_resolves_by_id_by_name_and_by_id_prefix() {
        val name = uniqueName("refs")
        val id = given(name)

        assertEquals(name, inspectedName(id))
        assertEquals(name, inspectedName(name))
        assertEquals(name, inspectedName(id.take(12)), "a 12-character prefix is how ids are usually written")
    }

    /** Discovery is a filtered listing, so the filters have to mean what kodkod thinks they mean. */
    @Test
    fun the_status_filter_selects_by_lifecycle_state() {
        val running = given(uniqueName("filter-running"))
        val created = given(uniqueName("filter-created"))
        client.start(running)

        val runningOnly = listedIds(all = true, filters = mapOf("status" to listOf("running")))

        assertTrue(running in runningOnly)
        assertFalse(created in runningOnly, "a created container is not a running one")
    }

    /** `label=key` is presence, `label=key=value` is equality — kodkod uses both spellings. */
    @Test
    fun the_label_filter_selects_by_presence_and_by_value() {
        val labelled = given(uniqueName("labelled"), labels = mapOf("kodkod.contract" to "yes"))
        val other = given(uniqueName("unlabelled"), labels = mapOf("kodkod.other" to "yes"))

        val byKey = listedIds(all = true, filters = mapOf("label" to listOf("kodkod.contract")))
        assertTrue(labelled in byKey)
        assertFalse(other in byKey)

        val byValue = listedIds(all = true, filters = mapOf("label" to listOf("kodkod.contract=yes")))
        assertTrue(labelled in byValue)
        assertFalse(other in byValue, "a different key must not match on the value alone")

        val byWrongValue = listedIds(all = true, filters = mapOf("label" to listOf("kodkod.contract=no")))
        assertFalse(labelled in byWrongValue)
    }

    /**
     * The `name` filter matches a substring, which is the only reason the backup sweep can ask for
     * `_kodkod_old_` and get every parked container back in one request.
     */
    @Test
    fun the_name_filter_matches_on_a_substring() {
        val name = uniqueName("needle-in-here")
        val id = given(name)

        assertTrue(id in listedIds(all = true, filters = mapOf("name" to listOf("needle-in-here"))))
        assertFalse(id in listedIds(all = true, filters = mapOf("name" to listOf("no-such-substring"))))
    }

    /**
     * The one that cost the most to learn: a container joined to another's network namespace cannot
     * start once that other container is gone. `refreshCreateTimeDependents` exists because of it, and
     * a fake that starts such a container anyway makes every test of that path vacuous.
     */
    @Test
    fun a_container_cannot_start_into_a_namespace_whose_provider_is_gone() {
        val provider = given(uniqueName("provider"))
        client.start(provider)
        val consumer = given(
            uniqueName("consumer"),
            hostConfig = buildJsonObject { put("NetworkMode", "container:$provider") },
        )
        client.start(consumer)
        client.stop(consumer, timeout = 1)

        client.remove(provider, force = true)
        createdIds -= provider

        assertThrows(DockerException::class.java, { client.start(consumer) }) {
            "the namespace this container names no longer exists, so the daemon must refuse the start"
        }
    }
}
