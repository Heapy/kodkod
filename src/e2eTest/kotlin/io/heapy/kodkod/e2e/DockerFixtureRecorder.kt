package io.heapy.kodkod.e2e

import io.heapy.kodkod.Autoheal
import io.heapy.kodkod.CapturedExchange
import io.heapy.kodkod.Config
import io.heapy.kodkod.DockerApi
import io.heapy.kodkod.FixtureMeta
import io.heapy.kodkod.RecordingDockerTransport
import io.heapy.kodkod.UnixSocketTransport
import io.heapy.kodkod.Updater
import io.heapy.kodkod.str
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
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
 * `-Pkodkod.e2e.useCurrentDocker=true` is mandatory, not decorative: see [recorderDaemonMismatch].
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

    private val versionInfo by lazy { DockerApi(socket).version() }
    private val composeVer by lazy { e2e.composeVersion() }
    private val label by lazy { "engine-${sanitize(versionInfo.str("Version"))}_compose-${sanitize(composeVer)}" }
    private val writer by lazy { FixtureWriter(fixturesRoot()) }

    @BeforeAll
    fun setupSuite() {
        recorderDaemonMismatch(
            useCurrentDocker = System.getProperty("kodkod.e2e.useCurrentDocker")?.trim()?.lowercase()
                in setOf("true", "1", "yes", "on"),
            dockerHost = System.getenv("DOCKER_HOST"),
            socket = socket,
        )?.let { error(it) }

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
        // Order matters: the scenario is only visible in the index once it is complete on disk.
        val dir = writer.writeScenario(label, scenario, exchanges)
        writer.writeMeta(
            label,
            FixtureMeta(
                dockerVersion = versionInfo.str("Version").orEmpty(),
                apiVersion = versionInfo.str("ApiVersion").orEmpty(),
                composeVersion = composeVer,
                recordedAt = Instant.now().toString(),
            ),
        )
        writer.upsertIndex(label, scenario)
        println("[record] $label/$scenario — ${exchanges.size} exchanges -> $dir")
    }

    private fun fixturesRoot(): Path = e2e.root.resolve("src/test/resources/docker-fixtures")

    private fun sanitize(version: String?): String =
        (version?.takeIf { it.isNotBlank() } ?: "unknown").replace(Regex("[^A-Za-z0-9.]+"), "-").trim('-')

    // IMPORTANT: monitorAll stays FALSE so the recorder only ever acts on containers explicitly
    // labelled for kodkod (the e2e compose services) — never the developer's own running containers.
    // With monitorAll=true, kodkod would treat every running container on the daemon as a target.
    //
    // KODKOD_UPDATE_VERIFY_HEALTH=false keeps the liveness gate's probe count deterministic: with health
    // verification on, the gate waits out `Health=starting` and the number of inspects of the replacement
    // becomes a race between the probe interval and the new container's healthcheck — a recording that
    // replay could only match by luck. DockerReplayTest sets the same flag.
    private fun updateConfig(): Config =
        Config.fromEnv(mapOf("KODKOD_UPDATE_CLEANUP" to "true", "KODKOD_UPDATE_VERIFY_HEALTH" to "false")::get)

    private fun autohealConfig(): Config =
        Config.fromEnv(emptyMap<String, String>()::get)
}

/**
 * Guards the one way the recorder can produce a plausible-looking but worthless corpus.
 *
 * kodkod's transport is unix-socket only, so the in-process [DockerApi] always talks to [socket] —
 * it cannot follow a `tcp://` `DOCKER_HOST`. The harness, however, drives the CLI through
 * `DOCKER_HOST` whenever it starts Docker-in-Docker. Without `-Pkodkod.e2e.useCurrentDocker=true`
 * the containers would therefore be created on the inner daemon while the recording is taken from
 * the developer's host daemon: the cycle sees none of the scenario's containers, records a handful
 * of empty listings, and overwrites the committed fixture with them. Nothing fails.
 *
 * @return the reason recording is unsafe, or `null` when the CLI and the recorder share a daemon.
 */
internal fun recorderDaemonMismatch(useCurrentDocker: Boolean, dockerHost: String?, socket: String): String? {
    if (!useCurrentDocker) {
        return "the fixture recorder requires -Pkodkod.e2e.useCurrentDocker=true: without it the harness " +
            "starts Docker-in-Docker and drives the CLI through DOCKER_HOST, while the recorder can only " +
            "reach the unix socket $socket — the scenario would run on one daemon and be recorded from " +
            "another, silently producing an empty fixture"
    }
    val host = dockerHost?.trim().orEmpty()
    if (host.isEmpty() || host.removePrefix("unix://") == socket) return null
    return "DOCKER_HOST=$host points the Docker CLI at a daemon the recorder cannot reach: kodkod speaks " +
        "unix socket only and would record from $socket instead. Unset DOCKER_HOST, or point the recorder " +
        "at the same daemon via KODKOD_DOCKER_SOCKET"
}
