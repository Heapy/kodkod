package io.heapy.kodkod

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * In-memory [DockerClient] for unit tests. It serves canned `list`/`inspect`/`distribution` data and
 * records every mutating call in [ops] (in order), so a test can assert *what* the orchestration did
 * — and in what sequence — without a Docker daemon.
 *
 * Reads ([inspectContainer], [inspectImage], [inspectDistribution]) are NOT recorded; only state
 * changes ([stop]/[start]/[rename]/[remove]/[create]/[connectNetwork]/[removeImage]/[restart]) and
 * [pull] land in [ops]. [create] returns deterministic ids of the form `new-<name>-<n>`, so tests can
 * predict and reference the replacement container.
 */
class FakeDockerClient : DockerClient {
    /** Returned verbatim by [listContainers] — already represents the post-filter set Docker would return. */
    val listed = mutableListOf<JsonObject>()

    /** id -> inspect payload returned by [inspectContainer]. */
    val containers = mutableMapOf<String, JsonObject>()

    /** `repo:tag` or image id -> inspect payload returned by [inspectImage]. */
    val images = mutableMapOf<String, JsonObject>()

    /** `repo:tag` -> registry `Descriptor.digest`; a missing entry makes [inspectDistribution] fail. */
    val distribution = mutableMapOf<String, String>()

    /** Ordered log of mutating calls, e.g. `stop:web`, `create:web`, `start:new-web-0`. */
    val ops = mutableListOf<String>()

    /** Bodies passed to [create], paired with the requested name, in call order. */
    val created = mutableListOf<Pair<String, JsonObject>>()

    /** Invoked from [pull]; lets a test mutate [images] to simulate a freshly-pulled (moved) tag. */
    var onPull: (repo: String, tag: String) -> Unit = { _, _ -> }

    /** Container names for which [create] should throw — used to drive the recreate rollback path. */
    val failCreate = mutableSetOf<String>()

    /** Container ids for which [start] should throw — e.g. `new-web-0` to fail the replacement's start. */
    val failStart = mutableSetOf<String>()

    private var createSeq = 0

    override fun version(): JsonObject = obj("""{"Version":"0.0.0-fake","ApiVersion":"1.45"}""")

    override fun listContainers(all: Boolean, filters: Map<String, List<String>>): JsonArray =
        JsonArray(listed)

    override fun inspectContainer(id: String): JsonObject =
        containers[id] ?: error("fake: no container registered for id '$id'")

    override fun restart(id: String, timeout: Int) {
        ops += "restart:$id"
    }

    override fun stop(id: String, timeout: Int) {
        ops += "stop:$id"
    }

    override fun start(id: String) {
        ops += "start:$id"
        if (id in failStart) throw DockerException(500, "fake: start failure for '$id'")
    }

    override fun rename(id: String, name: String) {
        ops += "rename:$id->$name"
    }

    override fun remove(id: String, force: Boolean) {
        ops += "remove:$id"
    }

    override fun connectNetwork(network: String, containerId: String, endpoint: JsonObject) {
        ops += "connect:$network:$containerId"
    }

    override fun create(name: String, body: JsonObject): String {
        ops += "create:$name"
        if (name in failCreate) throw DockerException(500, "fake: create failure for '$name'")
        created += name to body
        return "new-$name-${createSeq++}"
    }

    override fun inspectImage(ref: String): JsonObject =
        images[ref] ?: error("fake: no image registered for ref '$ref'")

    override fun removeImage(ref: String) {
        ops += "removeImage:$ref"
    }

    override fun inspectDistribution(ref: String, registryAuth: String?): JsonObject {
        val digest = distribution[ref] ?: error("fake: no distribution registered for ref '$ref'")
        return obj("""{"Descriptor":{"digest":"$digest"}}""")
    }

    override fun pull(fromImage: String, tag: String, registryAuth: String?) {
        ops += "pull:$fromImage:$tag"
        onPull(fromImage, tag)
    }

    private companion object {
        fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    }
}