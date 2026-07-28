package io.heapy.kodkod

import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets

/**
 * Record/replay support for [DockerTransport], plus the on-disk fixture model and the [Config] a
 * corpus is recorded under.
 *
 * [RecordingDockerTransport] wraps the real socket transport and captures the (decoded) response of
 * every exchange; the recorder ([io.heapy.kodkod] e2e `DockerFixtureRecorder`) writes those out as a
 * versioned fixture set. [ReplayDockerTransport] serves a captured set back with no daemon, so the
 * real [DockerApi] + [Updater]/[Autoheal] can be exercised against genuine Docker JSON in unit tests.
 *
 * This lives in `testFixtures` rather than `main` because none of it is production code: the daemon
 * never records or replays anything. It used to sit in `main` purely so both test source sets could
 * reach it, which shipped the whole fixture model in the production jar. Test fixtures are the one
 * place `test` and `e2eTest` can both see, and they are packaged separately — so the seam that
 * production does need ([DockerTransport], [UnixSocketTransport]) stays in `main` and this does not.
 *
 * Everything here is `public` for the same reason [DockerClientContract] is: a fixtures jar is a
 * separate module, so `internal` would hide it from the very source sets it exists for.
 */

// --- The configuration a corpus is recorded under -----------------------------------------

/**
 * The [Config] the update scenarios of the fixture corpus are recorded with — and therefore the only
 * one they can be replayed under. One definition, reached by both the recorder (`e2eTest`) and
 * `DockerReplayTest` (`test`), because the two drifting apart is a failure with no symptom until the
 * next re-record: the config decides the `listContainers` filters, which are *part of the request path*
 * replay matches on, and the length of the liveness gate's window, which is how many inspects of the
 * replacement a recording contains.
 *
 * `KODKOD_UPDATE_MONITOR_ALL` is left at its default of `false` on purpose — the recorder runs against a
 * developer's real daemon, and with it on kodkod would treat every running container there as a target,
 * not just the e2e compose services labelled for it.
 *
 * `KODKOD_UPDATE_VERIFY_SECONDS=1` is what keeps the probe count deterministic: the gate watches the
 * whole window unless the replacement's own healthcheck reports `healthy`, so a one-second window is
 * three inspects (probes at 0, 500 and 1000ms) instead of a number that depends on how fast that
 * healthcheck passes — a recording a replay could only match by luck.
 *
 * `KODKOD_UPDATE_VERIFY_HEALTH=false` keeps a healthcheck that fails a beat inside that window from
 * turning a recorded update into a recorded rollback. `KODKOD_UPDATE_CLEANUP=true` is already the
 * default and is spelled out because the corpus contains the prune calls it makes.
 */
fun recordedUpdateConfig(): Config =
    Config.fromEnv(
        mapOf(
            "KODKOD_UPDATE_CLEANUP" to "true",
            "KODKOD_UPDATE_VERIFY_HEALTH" to "false",
            "KODKOD_UPDATE_VERIFY_SECONDS" to "1",
        )::get,
    )

/**
 * The [Config] the autoheal scenarios are recorded with: stock defaults, which for autoheal already
 * means `monitorAll` off — see [recordedUpdateConfig] for why that matters to a recorder.
 */
fun recordedAutohealConfig(): Config = Config.fromEnv { null }

// --- On-disk fixture model ----------------------------------------------------------------

/** One recorded request→response, as stored in a scenario's `manifest.json`. */
@Serializable
class RecordedExchange(
    val method: String,
    val path: String,
    /** Human-readable note only (e.g. "1873 bytes"); request bodies are never matched on replay. */
    val requestBodySummary: String? = null,
    val status: Int,
    /** Name of the sibling file holding the (decoded) response body. */
    val responseFile: String,
)

/** A single scenario's recording: the ordered exchanges plus which label produced them. */
@Serializable
class FixtureManifest(
    val scenario: String,
    val label: String,
    val exchanges: List<RecordedExchange>,
)

/** Engine/compose versions a `<label>` was recorded against. */
@Serializable
class FixtureMeta(
    val dockerVersion: String,
    val apiVersion: String,
    val composeVersion: String,
    val recordedAt: String,
)

/** Enumerates the committed fixtures (classpath directories can't be listed portably). */
@Serializable
class FixtureIndex(
    val labels: List<FixtureLabel> = emptyList(),
)

@Serializable
class FixtureLabel(
    val label: String,
    val scenarios: List<String>,
)

// --- Recording ----------------------------------------------------------------------------

/** A captured exchange held in memory by [RecordingDockerTransport] before the recorder writes it out. */
class CapturedExchange(
    val method: String,
    val path: String,
    val requestBodySummary: String?,
    val status: Int,
    /** The decoded (dechunked) response body bytes. */
    val responseBody: ByteArray,
)

/**
 * Wraps a real [DockerTransport], forwarding every call but capturing the decoded `(status, body)`
 * of each response. Returns the original raw bytes so the production [DockerApi] parsing path runs
 * exactly as it would in production.
 */
class RecordingDockerTransport(
    private val delegate: DockerTransport,
) : DockerTransport {
    private val captured = mutableListOf<CapturedExchange>()
    val exchanges: List<CapturedExchange> get() = captured

    override fun exchange(
        method: String,
        path: String,
        body: ByteArray?,
        headers: Map<String, String>,
        readTimeoutMs: Long,
    ): ByteArray {
        val raw = delegate.exchange(method, path, body, headers, readTimeoutMs)
        val parsed = DockerApi.parse(raw)
        captured += CapturedExchange(
            method = method,
            path = path,
            requestBodySummary = body?.let { "${it.size} bytes" },
            status = parsed.status,
            responseBody = parsed.body,
        )
        return raw
    }
}

// --- Replay -------------------------------------------------------------------------------

/** Raised when the code under test issues a request that was never recorded. */
class NoSuchRecordedExchangeException(key: String, recordedKeys: Set<String>) :
    RuntimeException("no recorded Docker response for '$key'; recorded keys: ${recordedKeys.sorted()}")

/** Raised when the code under test issues more requests for a key than were recorded — re-record. */
class RecordedExchangesExhaustedException(key: String) :
    RuntimeException(
        "recorded Docker responses for '$key' are exhausted — the code under test made more calls " +
            "than were recorded; the fixture needs to be re-recorded",
    )

/**
 * Serves a recorded exchange set with no socket. Matching is by `"<method> <path>"` with a FIFO queue
 * per key, so repeated identical requests (e.g. `GET /images/<ref>/json` before and after a `pull`)
 * return successive recordings in order. Request bodies are outputs of the code under test and are
 * never matched. Each served response is synthesized into a minimal HTTP/1.1 message and fed back
 * through [DockerApi]'s parser, so the production parsing path still runs on replay.
 *
 * **What replay does not check.** Ordering is FIFO *per key*, so the relative order of calls to two
 * different keys is invisible here: moving `GET /containers/json?...` from the start of a cycle to the
 * end replays identically. That is deliberate — the alternative is a corpus that fails on every
 * harmless reordering — but it means "the recorded corpus replays" is not a statement about call
 * order. Order that matters (stop before its dependency, create before start) is asserted explicitly
 * by `DockerReplayTest` from the op log, and the fixtures are re-recorded when a call moves.
 */
class ReplayDockerTransport(
    exchanges: List<RecordedExchange>,
    private val bodyLoader: (responseFile: String) -> ByteArray,
) : DockerTransport {
    private val queues: Map<String, ArrayDeque<RecordedExchange>> =
        LinkedHashMap<String, ArrayDeque<RecordedExchange>>().apply {
            exchanges.forEach { getOrPut(key(it.method, it.path)) { ArrayDeque() }.addLast(it) }
        }

    private val missed = mutableListOf<String>()

    /**
     * Keys the code under test asked for that had no (remaining) recording. Every miss is *also*
     * thrown, but both [Updater] and [Autoheal] deliberately swallow Docker errors per container, so
     * a scenario can go green while it silently skipped everything it meant to assert. A replay
     * harness must therefore check this list, not just the exceptions.
     */
    val misses: List<String> get() = missed

    override fun exchange(
        method: String,
        path: String,
        body: ByteArray?,
        headers: Map<String, String>,
        readTimeoutMs: Long,
    ): ByteArray {
        val k = key(method, path)
        val queue = queues[k]
        if (queue == null) {
            missed += k
            throw NoSuchRecordedExchangeException(k, queues.keys)
        }
        if (queue.isEmpty()) {
            missed += k
            throw RecordedExchangesExhaustedException(k)
        }
        val ex = queue.removeFirst()
        return synthesize(ex.status, bodyLoader(ex.responseFile))
    }

    /** True once every recorded exchange has been consumed (catches a "fewer calls than recorded" drift). */
    fun isFullyConsumed(): Boolean = queues.values.all { it.isEmpty() }

    /** Keys with responses left over, as `"<method> <path>" (xN)` — the diagnostic for [isFullyConsumed]. */
    fun remaining(): List<String> =
        queues.entries.filter { it.value.isNotEmpty() }.map { (k, queue) -> "$k (x${queue.size})" }

    private fun key(method: String, path: String): String = "$method $path"

    private fun synthesize(status: Int, body: ByteArray): ByteArray {
        val head = "HTTP/1.1 $status REPLAY\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        return head.toByteArray(StandardCharsets.US_ASCII) + body
    }
}
