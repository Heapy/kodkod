package io.heapy.kodkod

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Replays the committed real-Docker fixtures (under `src/test/resources/docker-fixtures`) through the
 * real [DockerApi] + [Updater]/[Autoheal] and asserts version-independent invariants — no Docker.
 *
 * The corpus is enumerated from `index.json` and is **committed**, so a missing or empty index is a
 * packaging failure rather than an unseeded corpus, and fails the build instead of yielding zero tests.
 *
 * Both [Updater] and [Autoheal] swallow per-container Docker errors by design, so an unrecorded
 * request cannot be relied on to surface as a test failure on its own. Every scenario therefore also
 * asserts, via [assertExhaustive], that the recording was consumed exactly — see
 * [harness_fails_when_the_code_under_test_hits_an_unrecorded_request] for the meta-test that keeps
 * this property honest.
 */
class DockerReplayTest {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fake time, so replaying a recreate does not really sleep through the liveness gate's probe
     * interval. The gate exits on three good probes, which the recording contains, so the clock never
     * reaches the verification window.
     */
    private val clock = FakeClock()

    private fun resourceText(path: String): String? =
        javaClass.classLoader.getResourceAsStream(path)?.use { it.readBytes().toString(StandardCharsets.UTF_8) }

    private fun resourceBytes(path: String): ByteArray =
        javaClass.classLoader.getResourceAsStream(path)?.use { it.readBytes() }
            ?: error("fixture lists a response file that is missing on the classpath: $path")

    @TestFactory
    fun replay(): List<DynamicTest> {
        val indexText = resourceText("docker-fixtures/index.json")
            ?: error(
                "docker-fixtures/index.json is not on the test classpath. The corpus is committed, so " +
                    "an empty replay run means the resources were not packaged — re-record with " +
                    "./gradlew e2eTest -Pkodkod.e2e.useCurrentDocker=true -Pkodkod.e2e.record=true",
            )
        val index = json.decodeFromString(FixtureIndex.serializer(), indexText)
        val scenarios = index.labels.flatMap { label -> label.scenarios.map { label.label to it } }
        check(scenarios.isNotEmpty()) { "docker-fixtures/index.json lists no scenarios — the corpus is broken" }
        return scenarios.map { (label, scenario) ->
            DynamicTest.dynamicTest("$label / $scenario") { runScenario(label, scenario) }
        }
    }

    private fun runScenario(label: String, scenario: String) {
        val base = "docker-fixtures/$label/$scenario"
        val manifest = json.decodeFromString(
            FixtureManifest.serializer(),
            resourceText("$base/manifest.json") ?: error("missing manifest for $base"),
        )
        val replay = ReplayDockerTransport(manifest.exchanges) { file -> resourceBytes("$base/$file") }
        val client = OpLoggingClient(DockerApi(replay))

        if (scenario.startsWith("autoheal")) {
            Autoheal(client, autohealConfig(), selfId = null).runOnce()
        } else {
            Updater(client, updateConfig(), selfId = null, clock, clock).runOnce()
        }

        // First, because a miss is the root cause of any op assertion that fails downstream.
        assertExhaustive(scenario, replay)
        assertScenario(scenario, client)
    }

    /**
     * The scenario must have used the recording exactly: no request without a recorded answer (the
     * code under test changed, or the fixture is stale) and no recorded answer left unused (the code
     * under test stopped making a call it used to make).
     */
    private fun assertExhaustive(scenario: String, replay: ReplayDockerTransport) {
        assertTrue(
            replay.misses.isEmpty(),
            "[$scenario] the code under test issued unrecorded requests: ${replay.misses} — " +
                "re-record the fixtures if the change is intended",
        )
        assertTrue(
            replay.isFullyConsumed(),
            "[$scenario] recorded responses were never used: ${replay.remaining()} — " +
                "the code under test skipped calls it used to make",
        )
    }

    private fun assertScenario(scenario: String, client: OpLoggingClient) {
        val ops = client.ops
        when (scenario) {
            "update-recreate" -> {
                // Note: with a local `docker build` the new image is already present, so kodkod
                // recreates via the registry-digest branch without necessarily pulling; don't assert pull.
                val rename = firstIndex(ops, "rename") { it.startsWith("rename:") }
                val create = firstIndex(ops, "create") { it.startsWith("create:") }
                val start = firstIndex(ops, "start") { it.startsWith("start:") }
                val remove = firstIndex(ops, "remove") { it.startsWith("remove:") }
                assertTrue(rename < create && create < start && start < remove, "recreate order wrong: $ops")
                assertEquals(1, ops.count { it.startsWith("create:") }, "exactly one container recreated: $ops")
                assertTrue(
                    client.created.single().second.str("Image")?.contains("testapp") == true,
                    "replacement created against the new image: ${client.created}",
                )
            }

            "update-noop" -> {
                // Non-vacuity: an empty container list would also mutate nothing, so prove the cycle
                // actually looked at a container and reached the image comparison before standing down.
                assertTrue(
                    client.reads.any { it.startsWith("inspect:") },
                    "the cycle must have inspected a container: ${client.reads}",
                )
                assertTrue(
                    client.reads.any { it.startsWith("distribution:") || it.startsWith("inspectImage:") },
                    "the up-to-date verdict must come from a real image/registry check: ${client.reads}",
                )
                assertTrue(
                    ops.none { it.startsWith("create:") || it.startsWith("stop:") || it.startsWith("rename:") },
                    "an up-to-date update must not mutate anything: $ops",
                )
            }

            "autoheal-restart" -> {
                assertEquals(1, ops.size, "autoheal should issue exactly one op: $ops")
                assertTrue(ops.single().startsWith("restart:"), "autoheal should restart the unhealthy container: $ops")
            }

            "deps-ordered" -> {
                val stopWeb = firstIndex(ops, "stop web") { it.startsWith("stop:") && it.contains("web") }
                val stopDb = firstIndex(ops, "stop db") { it.startsWith("stop:") && it.contains("db") }
                assertTrue(stopWeb < stopDb, "dependent (web) must stop before its dependency (db): $ops")
                val createDb = firstIndex(ops, "create db") { it.startsWith("create:") && it.contains("db") }
                val startWeb = firstIndex(ops, "start web") { it.startsWith("start:") && it.contains("web") }
                assertTrue(createDb < startWeb, "db must be recreated before web is restarted: $ops")
                assertTrue(
                    ops.none { it.startsWith("create:") && it.contains("web") },
                    "web only depends on db; it is restarted, not recreated: $ops",
                )
            }

            else -> error("no replay expectations defined for scenario '$scenario'")
        }
    }

    private fun firstIndex(ops: List<String>, what: String, predicate: (String) -> Boolean): Int =
        ops.indexOfFirst(predicate).also { assertTrue(it >= 0, "expected op [$what] in $ops") }

    // --- Meta-tests: the harness itself must be able to fail --------------------------------

    /**
     * A synthetic in-memory recording of a digest-pinned container: kodkod lists, inspects, decides
     * there is nothing to check, and stops. Two exchanges is the smallest recording that still has a
     * swallowed-error hole to fall into.
     */
    private fun syntheticExchanges(): List<RecordedExchange> = listOf(
        recorded("GET", SYNTHETIC_LIST_PATH, "list"),
        recorded("GET", "/containers/$SYNTHETIC_ID/json", "inspect"),
    )

    private fun recorded(method: String, path: String, body: String) =
        RecordedExchange(method, path, requestBodySummary = null, status = 200, responseFile = body)

    private fun runSynthetic(exchanges: List<RecordedExchange>) {
        val replay = ReplayDockerTransport(exchanges) { file ->
            SYNTHETIC_BODIES.getValue(file).toByteArray(StandardCharsets.UTF_8)
        }
        Updater(OpLoggingClient(DockerApi(replay)), updateConfig(), selfId = null, clock, clock).runOnce()
        assertExhaustive("synthetic", replay)
    }

    @Test
    fun harness_fails_when_the_code_under_test_hits_an_unrecorded_request() {
        assertDoesNotThrow { runSynthetic(syntheticExchanges()) }

        // Updater catches the inspect failure per container and carries on with an empty target set,
        // so nothing mutates and every op assertion still holds — only the miss counter notices.
        val withoutInspect = syntheticExchanges().filterNot { it.path.startsWith("/containers/$SYNTHETIC_ID") }
        val failure = assertThrows(AssertionError::class.java) { runSynthetic(withoutInspect) }
        assertTrue(
            failure.message!!.contains("GET /containers/$SYNTHETIC_ID/json"),
            "the failure must name the unrecorded request: ${failure.message}",
        )
    }

    @Test
    fun harness_fails_when_a_recorded_response_is_never_used() {
        val withExtra = syntheticExchanges() + recorded("GET", "/version", "version")
        val failure = assertThrows(AssertionError::class.java) { runSynthetic(withExtra) }
        assertTrue(
            failure.message!!.contains("GET /version"),
            "the failure must name the unused recording: ${failure.message}",
        )
    }

    // Must match the recorder's config so the listContainers filter (hence request paths) line up:
    // monitorAll=false scopes to kodkod-labelled containers. KODKOD_UPDATE_VERIFY_HEALTH=false is what
    // makes the number of liveness probes — and therefore the number of recorded inspects of the
    // replacement — a constant instead of a race against the new container's healthcheck.
    private fun updateConfig(): Config =
        Config.fromEnv(mapOf("KODKOD_UPDATE_CLEANUP" to "true", "KODKOD_UPDATE_VERIFY_HEALTH" to "false")::get)

    private fun autohealConfig(): Config =
        Config.fromEnv(emptyMap<String, String>()::get)

    private companion object {
        const val SYNTHETIC_ID = "5ynthet1c0000000000000000000000000000000000000000000000000000000"

        /** Exactly what [DockerApi.listContainers] builds for [updateConfig] — keys in insertion order. */
        val SYNTHETIC_LIST_PATH = "/containers/json?all=false&filters=" +
            URLEncoder.encode("""{"status":["running"],"label":["kodkod.update.enable"]}""", StandardCharsets.UTF_8)

        val SYNTHETIC_BODIES = mapOf(
            "list" to """[{"Id":"$SYNTHETIC_ID","Names":["/synthetic"],"Labels":{"kodkod.update.enable":"true"}}]""",
            // Digest-pinned, so the update check stops right after the inspect and the recording stays tiny.
            "inspect" to """
                {"Id":"$SYNTHETIC_ID","Name":"/synthetic","Image":"sha256:cafe",
                 "Config":{"Image":"example.com/app@sha256:cafe","Labels":{"kodkod.update.enable":"true"}},
                 "HostConfig":{"NetworkMode":"bridge"},"NetworkSettings":{"Networks":{}}}
            """.trimIndent(),
            "version" to """{"Version":"synthetic"}""",
        )
    }
}
