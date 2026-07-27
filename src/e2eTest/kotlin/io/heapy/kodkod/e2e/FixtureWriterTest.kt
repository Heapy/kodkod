package io.heapy.kodkod.e2e

import io.heapy.kodkod.CapturedExchange
import io.heapy.kodkod.FixtureIndex
import io.heapy.kodkod.FixtureManifest
import io.heapy.kodkod.FixtureMeta
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * The recorder overwrites a **committed** corpus, so a run that dies midway must be a no-op rather
 * than a half-written scenario the strict replay suite would then choke on. Needs no Docker: the
 * failures are injected through [FixtureWriter]'s write seam.
 */
class FixtureWriterTest {
    @TempDir
    lateinit var root: Path

    private val json = Json { ignoreUnknownKeys = true }

    private fun exchange(method: String, path: String, body: String) =
        CapturedExchange(method, path, requestBodySummary = null, status = 200, responseBody = body.toByteArray())

    private fun snapshot(dir: Path): Map<String, String> =
        Files.walk(dir).use { paths ->
            paths.filter { Files.isRegularFile(it) }
                .toList()
                .associate { dir.relativize(it).toString() to Files.readString(it) }
        }

    private fun committedScenario(label: String, scenario: String): Path {
        val dir = root.resolve(label).resolve(scenario)
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("0001.GET.old.bin"), "previous body")
        Files.writeString(dir.resolve("manifest.json"), """{"scenario":"$scenario","label":"$label","exchanges":[]}""")
        FixtureWriter(root).upsertIndex(label, scenario)
        return dir
    }

    private fun index(): FixtureIndex =
        json.decodeFromString(FixtureIndex.serializer(), Files.readString(root.resolve("index.json")))

    // --- happy path -------------------------------------------------------------------------

    @Test
    fun a_recorded_scenario_lands_with_bodies_manifest_and_index_entry() {
        val dir = FixtureWriter(root).commitScenario(
            "engine-1_compose-2",
            "update-recreate",
            listOf(
                exchange("GET", "/containers/json?all=false", "[]"),
                exchange("POST", "/containers/abc/stop", ""),
            ),
            META,
        )

        val manifest = json.decodeFromString(FixtureManifest.serializer(), Files.readString(dir.resolve("manifest.json")))
        assertEquals(listOf("0001.GET.containers-json.bin", "0002.POST.containers-abc-stop.bin"), manifest.exchanges.map { it.responseFile })
        assertEquals(listOf("/containers/json?all=false", "/containers/abc/stop"), manifest.exchanges.map { it.path })
        assertEquals("[]", Files.readString(dir.resolve("0001.GET.containers-json.bin")))
        assertEquals(listOf("update-recreate"), index().labels.single().scenarios)
        assertTrue(Files.exists(root.resolve("engine-1_compose-2/meta.json")), "the engine the corpus came from")
    }

    @Test
    fun a_re_recording_replaces_the_scenario_and_keeps_its_neighbours() {
        val label = "engine-1_compose-2"
        committedScenario(label, "update-noop")
        val committed = committedScenario(label, "update-recreate")
        FixtureWriter(root).upsertIndex("engine-9_compose-9", "autoheal-restart")

        FixtureWriter(root).writeScenario(label, "update-recreate", listOf(exchange("GET", "/version", "{}")))

        assertEquals(
            mapOf("0001.GET.version.bin" to "{}"),
            snapshot(committed).filterKeys { it != "manifest.json" },
            "the stale body of the previous recording must be gone, not merged with the new one",
        )
        assertTrue(Files.exists(root.resolve(label).resolve("update-noop")), "a sibling scenario must survive")
        assertEquals(
            listOf("engine-1_compose-2" to listOf("update-noop", "update-recreate"), "engine-9_compose-9" to listOf("autoheal-restart")),
            index().labels.map { it.label to it.scenarios },
            "the index is additive across labels and scenarios",
        )
    }

    @Test
    fun meta_records_the_engine_the_corpus_was_taken_from() {
        FixtureWriter(root).writeMeta("engine-1_compose-2", FixtureMeta("29.5.2", "1.52", "5.1.4", "2026-07-27T00:00:00Z"))

        assertTrue(Files.readString(root.resolve("engine-1_compose-2/meta.json")).contains("29.5.2"))
    }

    // --- failure leaves the committed corpus alone ------------------------------------------

    @Test
    fun a_body_write_that_fails_midway_leaves_the_committed_fixture_untouched() {
        val label = "engine-1_compose-2"
        val committed = committedScenario(label, "update-recreate")
        val before = snapshot(committed)
        val writer = FixtureWriter(root, failingOn("0002"))

        assertThrows(IOException::class.java) {
            writer.writeScenario(
                label,
                "update-recreate",
                listOf(exchange("GET", "/version", "{}"), exchange("GET", "/info", "{}")),
            )
        }
        // The recorder never gets here, which is the point: the index only ever names a scenario that
        // is already complete on disk.

        assertEquals(before, snapshot(committed), "the committed recording must survive a failed re-record")
        assertEquals(emptyList<Path>(), leftovers(label), "no staging or backup directories may be left behind")
    }

    @Test
    fun a_manifest_write_that_fails_leaves_the_committed_fixture_untouched() {
        val label = "engine-1_compose-2"
        val committed = committedScenario(label, "update-recreate")
        val before = snapshot(committed)

        assertThrows(IOException::class.java) {
            FixtureWriter(root, failingOn("manifest.json"))
                .writeScenario(label, "update-recreate", listOf(exchange("GET", "/version", "{}")))
        }

        assertEquals(before, snapshot(committed), "bodies without a manifest must never reach the corpus")
        assertEquals(emptyList<Path>(), leftovers(label))
    }

    /**
     * Through the whole commit, which is the only way this claim can be tested: the index entry is the
     * last thing written, so a body that never lands takes the index entry with it. Asserted against
     * `FixtureWriter.writeScenario` alone it was a tautology — that method cannot reach `index.json` at
     * all, and the ordering it was supposed to protect lived in the recorder, untested.
     */
    @Test
    fun a_failed_recording_does_not_leave_a_stub_in_the_index() {
        val label = "engine-1_compose-2"
        committedScenario(label, "update-recreate")
        val writer = FixtureWriter(root, failingOn("0001"))

        assertThrows(IOException::class.java) {
            writer.commitScenario(label, "deps-ordered", listOf(exchange("GET", "/version", "{}")), META)
        }

        assertEquals(
            listOf("update-recreate"), index().labels.single().scenarios,
            "the replay suite loads exactly what the index names, so a scenario named there and missing " +
                "from disk fails every later run",
        )
        assertTrue(Files.notExists(root.resolve(label).resolve("deps-ordered")))
        assertEquals(emptyList<Path>(), leftovers(label))
    }

    @Test
    fun a_failed_index_write_keeps_the_previous_index() {
        val label = "engine-1_compose-2"
        committedScenario(label, "update-recreate")
        val before = Files.readString(root.resolve("index.json"))

        assertThrows(IOException::class.java) { FixtureWriter(root, failingOn("index.json")).upsertIndex(label, "deps-ordered") }

        assertEquals(before, Files.readString(root.resolve("index.json")), "a truncated index would break every replay run")
    }

    /** Staging/backup directories the writer creates while swapping; none may outlive a call. */
    private fun leftovers(label: String): List<Path> =
        Files.list(root.resolve(label)).use { entries ->
            entries.filter { it.name.contains(".staging-") || it.name.contains(".backup-") || it.name.contains(".tmp-") }.toList()
        }

    private fun failingOn(marker: String): (Path, ByteArray) -> Unit = { path, bytes ->
        if (path.name.contains(marker)) throw IOException("simulated disk failure writing ${path.name}")
        Files.write(path, bytes)
    }

    // --- daemon guard -----------------------------------------------------------------------

    @Test
    fun recording_without_use_current_docker_is_refused() {
        val reason = recorderDaemonMismatch(useCurrentDocker = false, dockerHost = null, socket = SOCKET)

        assertNotNull(reason)
        assertTrue(reason!!.contains("-Pkodkod.e2e.useCurrentDocker=true"), "the message must name the missing flag: $reason")
    }

    @Test
    fun recording_against_a_docker_host_the_recorder_cannot_reach_is_refused() {
        val reason = recorderDaemonMismatch(useCurrentDocker = true, dockerHost = "tcp://127.0.0.1:12375", socket = SOCKET)

        assertNotNull(reason)
        assertTrue(reason!!.contains("tcp://127.0.0.1:12375"), "the message must name the daemon it would miss: $reason")
    }

    @Test
    fun recording_against_the_local_daemon_is_allowed() {
        assertNull(recorderDaemonMismatch(useCurrentDocker = true, dockerHost = null, socket = SOCKET))
        assertNull(recorderDaemonMismatch(useCurrentDocker = true, dockerHost = "  ", socket = SOCKET))
        assertNull(recorderDaemonMismatch(useCurrentDocker = true, dockerHost = "unix://$SOCKET", socket = SOCKET))
    }

    private companion object {
        const val SOCKET = "/var/run/docker.sock"

        /** What the recorder stamps a label with; irrelevant to every assertion but required to commit. */
        val META = FixtureMeta("29.5.2", "1.52", "5.1.4", "2026-07-27T00:00:00Z")
    }
}
