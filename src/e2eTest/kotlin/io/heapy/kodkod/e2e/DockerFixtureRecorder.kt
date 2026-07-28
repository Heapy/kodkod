package io.heapy.kodkod.e2e

import io.heapy.kodkod.Autoheal
import io.heapy.kodkod.CapturedExchange
import io.heapy.kodkod.DockerApi
import io.heapy.kodkod.FixtureMeta
import io.heapy.kodkod.RecordingDockerTransport
import io.heapy.kodkod.UnixSocketTransport
import io.heapy.kodkod.Updater
import io.heapy.kodkod.recordedAutohealConfig
import io.heapy.kodkod.recordedUpdateConfig
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
            useCurrentDocker = boolProperty("kodkod.e2e.useCurrentDocker"),
            socket = socket,
            // Exactly the invocation every scenario's setup uses, environment and all.
            probeCli = { daemonId("the Docker CLI as the harness runs it") },
            probeSocket = { daemonId(socket, "-H", "unix://$socket") },
        )?.let { error(it) }

        e2e.startDocker()
        // The recorder drives Updater/Autoheal in-process, so it does NOT need the kodkod:e2e image
        // E2eHarness.setup() builds — only the registry half of it. Nor a baseline testapp: every
        // scenario that wants one publishes the variant it needs as its own first step.
        e2e.startRegistry()
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
        Updater(api, recordedUpdateConfig(), selfId = null).runOnce()
    }

    @Test
    fun recordUpdateNoop() = record("update-noop", composeFile = "update") { api ->
        e2e.publishVariant("v1")
        e2e.compose("update", "up", "-d", "app")
        e2e.waitUntil(30, "app v1 up") { e2e.variant("e2e-update-app-1") == "v1" }
        // No new image published — kodkod should find it up to date and do nothing.
        Updater(api, recordedUpdateConfig(), selfId = null).runOnce()
    }

    @Test
    fun recordAutohealRestart() = record("autoheal-restart", composeFile = "autoheal") { api ->
        e2e.compose("autoheal", "up", "-d", "app")
        e2e.waitUntil(40, "app healthy") { e2e.health("e2e-autoheal-app-1") == "healthy" }
        e2e.docker("exec", "e2e-autoheal-app-1", "rm", "-f", "/tmp/healthy")
        e2e.waitUntil(40, "app unhealthy") { e2e.health("e2e-autoheal-app-1") == "unhealthy" }
        Autoheal(api, recordedAutohealConfig(), selfId = null).runOnce()
    }

    @Test
    fun recordDepsOrdered() = record("deps-ordered", composeFile = "deps") { api ->
        e2e.publishVariant("v1")
        e2e.compose("deps", "up", "-d", "db", "web")
        e2e.waitUntil(30, "db v1 up") { e2e.variant("e2e-deps-db-1") == "v1" }
        e2e.publishVariant("v2") // only db uses testapp; web (busybox) is an unchanged dependent
        Updater(api, recordedUpdateConfig(), selfId = null).runOnce()
    }

    /**
     * Ask the daemon reached by `docker [globalArgs] info` what its own id is. [globalArgs] go before the
     * subcommand, which is where `-H` belongs: an explicit host wins over `DOCKER_HOST`, over
     * `DOCKER_CONTEXT` and over the active context, so it is how one *specific* daemon gets asked while
     * the same call without it reports whichever daemon the environment actually points at.
     */
    private fun daemonId(via: String, vararg globalArgs: String): DaemonProbe =
        daemonProbe(via, e2e.docker(*globalArgs, "info", "--format", "{{.ID}}", check = false))

    // --- recording plumbing ---------------------------------------------------------------

    private fun record(scenario: String, composeFile: String, block: (DockerApi) -> Unit) {
        val transport = RecordingDockerTransport(UnixSocketTransport(socket))
        try {
            block(DockerApi(transport))
        } finally {
            e2e.compose(composeFile, "down", "-v", check = false)
        }
        commitRecording(scenario, transport.exchanges)
    }

    /** Hand one scenario's captured exchanges to [FixtureWriter.commitScenario] and say where they went. */
    private fun commitRecording(scenario: String, exchanges: List<CapturedExchange>) {
        // The order the corpus depends on lives in the writer, where a test can hold it to it.
        val dir = writer.commitScenario(
            label,
            scenario,
            exchanges,
            FixtureMeta(
                dockerVersion = versionInfo.str("Version").orEmpty(),
                apiVersion = versionInfo.str("ApiVersion").orEmpty(),
                composeVersion = composeVer,
                recordedAt = Instant.now().toString(),
            ),
        )
        println("[record] $label/$scenario — ${exchanges.size} exchanges -> $dir")
    }

    private fun fixturesRoot(): Path = e2e.root.resolve("src/test/resources/docker-fixtures")

    private fun sanitize(version: String?): String =
        (version?.takeIf { it.isNotBlank() } ?: "unknown").replace(Regex("[^A-Za-z0-9.]+"), "-").trim('-')
}

/**
 * A daemon's answer to "who are you": `GET /info`'s `ID`, which the engine generates once, on the first
 * start against a given data root, and keeps for its lifetime. Two daemons never share one, and one
 * daemon reports the same id no matter which of its sockets or ports the question arrives on — which is
 * what makes it the right thing to compare, and a socket path the wrong one.
 */
internal class DaemonProbe(
    /** How this daemon was addressed, named in the failure message. */
    val via: String,
    /** The `ID` it reported, or empty when the question could not be answered. */
    val id: String,
    /** What went wrong, when [id] is empty. */
    val detail: String = "",
)

/**
 * Read a daemon's id out of what `docker info --format '{{.ID}}'` answered, [via] naming how it was
 * asked. Anything short of a clean answer is an *unanswered* probe carrying the output, never a blank
 * id that could later read as agreement.
 *
 * The id is the last non-blank line: with `--format` the CLI writes nothing but the template's result,
 * and whatever it has to say for itself (the harness merges stderr into stdout) is written before it.
 */
internal fun daemonProbe(via: String, result: CommandResult): DaemonProbe {
    val id = result.output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
    if (result.exitCode != 0 || id.isEmpty()) {
        return DaemonProbe(via, "", "`docker info` exited ${result.exitCode}: ${result.output.trim()}")
    }
    return DaemonProbe(via, id)
}

/**
 * Guards the one way the recorder can produce a plausible-looking but worthless corpus.
 *
 * kodkod's transport is unix-socket only, so the in-process [DockerApi] always records from [socket].
 * The scenario's containers, meanwhile, are created by the **CLI**, and the CLI picks its daemon from
 * three places kodkod has no say in: `DOCKER_HOST`, `DOCKER_CONTEXT`, and the context left active by
 * `docker context use`. Any of them pointing somewhere else — the harness's own Docker-in-Docker, a
 * colima or a remote context — and the cycle sees none of the scenario's containers, records a handful
 * of empty listings, and overwrites the committed fixture with them. Nothing fails.
 *
 * So the two are not compared by *address* — enumerating the ways they can be pointed apart is a list
 * that grows with somebody else's release notes, and it gets the ordinary case wrong in both directions
 * (Docker Desktop's active context is `unix:///Users/<me>/.docker/run/docker.sock` while the recorder
 * reads `/var/run/docker.sock`: different paths, one daemon). They are compared by the identity the
 * daemon itself reports, [DaemonProbe]. Anything that cannot be established refuses to record: a corpus
 * taken from the wrong daemon is indistinguishable from a correct one after the fact.
 *
 * @param probeCli    what the harness's own `docker` invocation reaches — probed lazily, it costs a
 *                    subprocess and is pointless when the flag below already rules recording out.
 * @param probeSocket what a `docker` pinned to [socket] with `-H` reaches, which is by construction the
 *                    daemon `DockerApi(socket)` records from.
 * @return the reason recording is unsafe, or `null` when both name the same daemon.
 */
internal fun recorderDaemonMismatch(
    useCurrentDocker: Boolean,
    socket: String,
    probeCli: () -> DaemonProbe,
    probeSocket: () -> DaemonProbe,
): String? {
    if (!useCurrentDocker) {
        return "the fixture recorder requires -Pkodkod.e2e.useCurrentDocker=true: without it the harness " +
            "starts Docker-in-Docker and drives the CLI through DOCKER_HOST, while the recorder can only " +
            "reach the unix socket $socket — the scenario would run on one daemon and be recorded from " +
            "another, silently producing an empty fixture"
    }
    val cli = probeCli()
    val recorded = probeSocket()
    listOf(cli, recorded).firstOrNull { it.id.isBlank() }?.let {
        return "could not ask ${it.via} which daemon it is (${it.detail}) — refusing to record, because a " +
            "corpus taken from the wrong daemon looks exactly like a correct one"
    }
    if (cli.id != recorded.id) {
        return "the Docker CLI and the recorder are on different daemons: the scenario's containers would " +
            "be created on ${cli.id} (${cli.via}) while the fixture is recorded from ${recorded.id} " +
            "($socket). Check DOCKER_HOST, DOCKER_CONTEXT and `docker context ls` (the active context is " +
            "what an unset DOCKER_HOST falls back to), or point the recorder at the same daemon with " +
            "KODKOD_DOCKER_SOCKET"
    }
    return null
}
