package io.heapy.kodkod

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * A [DockerClient] decorator that delegates every call to a real client (in replay tests, a
 * [DockerApi] over a [ReplayDockerTransport]) while recording the mutating calls into [ops] — using
 * the exact same string format as [FakeDockerClient], so the `assertOrder`-style assertions from the
 * hand-written unit tests apply unchanged.
 *
 * An op is logged **after** the delegate returned, so `ops` means "kodkod did this", not "kodkod
 * tried this". A call that threw is logged with a `!` marker instead (`create!:web`), which keeps the
 * attempt visible without letting a failed mutation masquerade as a successful one.
 *
 * Reads land in [reads] rather than [ops] (mutations only), so a scenario can prove it was not
 * vacuous — an update that mutates nothing must still show that it inspected something.
 *
 * Container ids are resolved to their `Name` (learned from [inspectContainer]) so ops read as
 * `stop:e2e-deps-web-1` rather than a raw id, keeping replay assertions stable and legible.
 */
class OpLoggingClient(private val delegate: DockerClient) : DockerClient {
    val ops = mutableListOf<String>()
    val created = mutableListOf<Pair<String, JsonObject>>()

    /** Read-only calls, in order: `list:all=false`, `inspect:web`, `inspectImage:<ref>`, `distribution:<ref>`. */
    val reads = mutableListOf<String>()

    private val names = mutableMapOf<String, String>()

    private fun label(id: String): String = names[id] ?: id.take(12)

    /** Run [call], logging `<verb>:<arg>` on success and `<verb>!:<arg>` when it threw. */
    private fun <T> op(verb: String, arg: String, log: MutableList<String>, call: () -> T): T {
        fun entry(marker: String) = if (arg.isEmpty()) "$verb$marker" else "$verb$marker:$arg"
        val result = try {
            call()
        } catch (e: Throwable) {
            log += entry("!")
            throw e
        }
        log += entry("")
        return result
    }

    override fun version(): JsonObject = op("version", "", reads) { delegate.version() }

    override fun listContainers(all: Boolean, filters: Map<String, List<String>>): JsonArray =
        op("list", "all=$all", reads) { delegate.listContainers(all, filters) }

    override fun inspectContainer(id: String): JsonObject =
        op("inspect", label(id), reads) {
            delegate.inspectContainer(id).also { inspect ->
                inspect.str("Name")?.trimStart('/')?.let { names[id] = it }
            }
        }

    override fun restart(id: String, timeout: Int) {
        op("restart", label(id), ops) { delegate.restart(id, timeout) }
    }

    override fun stop(id: String, timeout: Int) {
        op("stop", label(id), ops) { delegate.stop(id, timeout) }
    }

    override fun start(id: String) {
        op("start", label(id), ops) { delegate.start(id) }
    }

    override fun rename(id: String, name: String) {
        op("rename", "${label(id)}->$name", ops) { delegate.rename(id, name) }
    }

    override fun remove(id: String, force: Boolean) {
        op("remove", label(id), ops) { delegate.remove(id, force) }
    }

    override fun connectNetwork(network: String, containerId: String, endpoint: JsonObject) {
        op("connect", "$network:${label(containerId)}", ops) { delegate.connectNetwork(network, containerId, endpoint) }
    }

    override fun create(name: String, body: JsonObject): String =
        op("create", name, ops) { delegate.create(name, body).also { created += name to body } }

    override fun inspectImage(ref: String): JsonObject =
        op("inspectImage", ref, reads) { delegate.inspectImage(ref) }

    override fun removeImage(ref: String) {
        op("removeImage", ref, ops) { delegate.removeImage(ref) }
    }

    override fun inspectDistribution(ref: String, registryAuth: String?): JsonObject =
        op("distribution", ref, reads) { delegate.inspectDistribution(ref, registryAuth) }

    override fun pull(fromImage: String, tag: String, registryAuth: String?) {
        op("pull", "$fromImage:$tag", ops) { delegate.pull(fromImage, tag, registryAuth) }
    }
}
