package io.heapy.kodkod.e2e

import io.heapy.kodkod.Autoheal
import io.heapy.kodkod.CapturedExchange
import io.heapy.kodkod.Config
import io.heapy.kodkod.DockerApi
import io.heapy.kodkod.FixtureIndex
import io.heapy.kodkod.FixtureLabel
import io.heapy.kodkod.FixtureManifest
import io.heapy.kodkod.FixtureMeta
import io.heapy.kodkod.RecordedExchange
import io.heapy.kodkod.RecordingDockerTransport
import io.heapy.kodkod.UnixSocketTransport
import io.heapy.kodkod.Updater
import io.heapy.kodkod.str
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Opt-in recorder that captures the **real** Docker responses kodkod's update/autoheal cycles
 * produce, into the versioned fixture corpus under `src/test/resources/docker-fixtures`. Those
 * fixtures are then replayed (no Docker) by `DockerReplayTest` in the `test` source set.
 *
 * It runs in-process against the local Docker daemon and is gated so it never runs in normal CI:
 *
 * ```
 * ./gradlew e2eTest -Pkodkod.e2e.useCurrentDocker=true -Pkodkod.e2e.record=true \
 *     --tests '*DockerFixtureRecorder*'
 * ```
 *
 * Each scenario sets up real containers via [E2eHarness] (CLI), then runs the real [Updater] /
 * [Autoheal] through a [DockerApi] backed by a [RecordingDockerTransport]; only the in-process API
 * calls are captured (the harness CLI setup is not). Re-running re-records the current engine/compose
 * label additively — other labels are untouched.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "kodkod.e2e.record", matches = "true")
class DockerFixtureRecorder {
    private val e2e = E2eHarness()
    private val socket = System.getenv("KODKOD_DOCKER_SOCKET") ?: "/var/run/docker.sock"
    private val prettyJson = Json { prettyPrint = true; encodeDefaults = true }

    private val versionInfo by lazy { DockerApi(socket).version() }
    private val composeVer by lazy { e2e.composeVersion() }
    private val label by lazy { "engine-${sanitize(versionInfo.str("Version"))}_compose-${sanitize(composeVer)}" }

    @BeforeAll
    fun setupSuite() {
        e2e.startDocker()
        // The recorder drives Updater/Autoheal in-process, so it does NOT need the kodkod:e2e image
        // that E2eHarness.setup() builds — only the local registry and a published testapp:v1 baseline.
        e2e.compose("registry", "up", "-d")
        e2e.publishVariant("v1")
    }

    @AfterAll
    fun cleanupSuite() {
        e2e.close()
    }

    @Test
    fun recordUpdateRecreate() = record("update-recreate", composeFile = "update") { api ->
        e2e.publishVariant("v1")
        e2e.compose("update", "up", "-d", "app")
        e2e.waitUntil(30, "app v1 up") { e2e.variant("e2e-update-app-1") == "v1" }
        e2e.publishVariant("v2") // overwrite :latest with v2 so the running container is stale
        Updater(api, updateConfig(), selfId = null).runOnce()
    }

    @Test
    fun recordUpdateNoop() = record("update-noop", composeFile = "update") { api ->
        e2e.publishVariant("v1")
        e2e.compose("update", "up", "-d", "app")
        e2e.waitUntil(30, "app v1 up") { e2e.variant("e2e-update-app-1") == "v1" }
        // No new image published — kodkod should find it up to date and do nothing.
        Updater(api, updateConfig(), selfId = null).runOnce()
    }

    @Test
    fun recordAutohealRestart() = record("autoheal-restart", composeFile = "autoheal") { api ->
        e2e.compose("autoheal", "up", "-d", "app")
        e2e.waitUntil(40, "app healthy") { e2e.health("e2e-autoheal-app-1") == "healthy" }
        e2e.docker("exec", "e2e-autoheal-app-1", "rm", "-f", "/tmp/healthy")
        e2e.waitUntil(40, "app unhealthy") { e2e.health("e2e-autoheal-app-1") == "unhealthy" }
        Autoheal(api, autohealConfig(), selfId = null).runOnce()
    }

    @Test
    fun recordDepsOrdered() = record("deps-ordered", composeFile = "deps") { api ->
        e2e.publishVariant("v1")
        e2e.compose("deps", "up", "-d", "db", "web")
        e2e.waitUntil(30, "db v1 up") { e2e.variant("e2e-deps-db-1") == "v1" }
        e2e.publishVariant("v2") // only db uses testapp; web (busybox) is an unchanged dependent
        Updater(api, updateConfig(), selfId = null).runOnce()
    }

    // --- recording plumbing ---------------------------------------------------------------

    private fun record(scenario: String, composeFile: String, block: (DockerApi) -> Unit) {
        val transport = RecordingDockerTransport(UnixSocketTransport(socket))
        try {
            block(DockerApi(transport))
        } finally {
            e2e.compose(composeFile, "down", "-v", check = false)
        }
        writeScenario(scenario, transport.exchanges)
    }

    private fun writeScenario(scenario: String, exchanges: List<CapturedExchange>) {
        val dir = fixturesRoot().resolve(label).resolve(scenario)
        if (Files.exists(dir)) dir.toFile().deleteRecursively()
        Files.createDirectories(dir)

        val records = exchanges.mapIndexed { i, ex ->
            val file = responseFileName(i + 1, ex.method, ex.path)
            Files.write(dir.resolve(file), ex.responseBody)
            RecordedExchange(ex.method, ex.path, ex.requestBodySummary, ex.status, file)
        }
        Files.writeString(
            dir.resolve("manifest.json"),
            prettyJson.encodeToString(FixtureManifest.serializer(), FixtureManifest(scenario, label, records)),
        )
        writeMeta()
        upsertIndex(scenario)
        println("[record] $label/$scenario — ${records.size} exchanges -> $dir")
    }

    private fun writeMeta() {
        val meta = FixtureMeta(
            dockerVersion = versionInfo.str("Version").orEmpty(),
            apiVersion = versionInfo.str("ApiVersion").orEmpty(),
            composeVersion = composeVer,
            recordedAt = Instant.now().toString(),
        )
        Files.writeString(
            fixturesRoot().resolve(label).resolve("meta.json"),
            prettyJson.encodeToString(FixtureMeta.serializer(), meta),
        )
    }

    private fun upsertIndex(scenario: String) {
        val indexFile = fixturesRoot().resolve("index.json")
        val current =
            if (Files.exists(indexFile)) {
                prettyJson.decodeFromString(FixtureIndex.serializer(), Files.readString(indexFile))
            } else {
                FixtureIndex()
            }
        val labels = current.labels.associateBy { it.label }.toMutableMap()
        val scenarios = ((labels[label]?.scenarios ?: emptyList()) + scenario).distinct().sorted()
        labels[label] = FixtureLabel(label, scenarios)

        Files.createDirectories(fixturesRoot())
        Files.writeString(
            indexFile,
            prettyJson.encodeToString(FixtureIndex.serializer(), FixtureIndex(labels.values.sortedBy { it.label })),
        )
    }

    private fun fixturesRoot(): Path = e2e.root.resolve("src/test/resources/docker-fixtures")

    private fun responseFileName(seq: Int, method: String, path: String): String {
        val hint = path.substringBefore('?').trim('/')
            .replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(40).ifEmpty { "root" }
        return "%04d.%s.%s.bin".format(seq, method, hint)
    }

    private fun sanitize(version: String?): String =
        (version?.takeIf { it.isNotBlank() } ?: "unknown").replace(Regex("[^A-Za-z0-9.]+"), "-").trim('-')

    // IMPORTANT: monitorAll stays FALSE so the recorder only ever acts on containers explicitly
    // labelled for kodkod (the e2e compose services) — never the developer's own running containers.
    // With monitorAll=true, kodkod would treat every running container on the daemon as a target.
    private fun updateConfig(): Config =
        Config.fromEnv(mapOf("KODKOD_UPDATE_CLEANUP" to "true")::get)

    private fun autohealConfig(): Config =
        Config.fromEnv(emptyMap<String, String>()::get)
}
