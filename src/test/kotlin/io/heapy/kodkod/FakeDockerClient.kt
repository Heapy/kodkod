package io.heapy.kodkod

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * In-memory [DockerClient] for unit tests. It serves canned `list`/`inspect`/`distribution` data and
 * records every mutating call in [ops] (in order), so a test can assert *what* the orchestration did
 * — and in what sequence — without a Docker daemon.
 *
 * Reads ([inspectContainer], [inspectImage], [inspectDistribution]) are NOT recorded; only state
 * changes ([stop]/[start]/[rename]/[remove]/[create]/[connectNetwork]/[removeImage]/[restart]) and
 * [pull] land in [ops]. As in [OpLoggingClient], an op is appended **after** the call succeeded and a
 * call that threw is recorded with a `!` marker (`create!:web`), so "kodkod did this" and "kodkod
 * tried this" never read the same. [create] returns deterministic ids of the form `new-<name>-<n>`,
 * so tests can predict and reference the replacement container — and registers it in [containers] so
 * it can be inspected, unless the test already registered a payload for that id itself.
 *
 * [listContainers] applies `all` and the `status`/`label`/`health` filters to [listed] the way the
 * daemon would, so code that deliberately looks beyond the monitored set (`all=true`, no label
 * filter) can be told apart from code that only ever sees its own targets.
 */
class FakeDockerClient : DockerClient {
    /** Container summaries the daemon knows about; [listContainers] filters this list. */
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

    /** Timeouts passed to [stop], in call order. */
    val stopTimeouts = mutableListOf<Int?>()

    /** Timeouts passed to [restart], in call order. */
    val restartTimeouts = mutableListOf<Int?>()

    /** Refs passed to [removeImage], in call order. */
    val removedImages = mutableListOf<String>()

    /**
     * `platform` passed to [pull] and [create], in call order, `null` entries included — "the daemon
     * was left to pick" and "we pinned the wrong arch" must not read the same. Every call is recorded,
     * including one that goes on to fail, so this list does not line up index-wise with [created].
     */
    val platforms = mutableListOf<String?>()

    /** Invoked from [pull]; lets a test mutate [images] to simulate a freshly-pulled (moved) tag. */
    var onPull: (repo: String, tag: String) -> Unit = { _, _ -> }

    /** Container names for which [create] should throw — used to drive the recreate rollback path. */
    val failCreate = mutableSetOf<String>()

    /** Container ids for which [start] should throw — e.g. `new-web-0` to fail the replacement's start. */
    val failStart = mutableSetOf<String>()

    /** Container ids for which [remove] should throw — used to strand a replacement during rollback. */
    val failRemove = mutableSetOf<String>()

    /**
     * New names for which [rename] should throw 409 unconditionally. A name another *live* container
     * already holds is refused with 409 anyway (see [rename]), so this set is only needed for a daemon
     * that keeps refusing a name kodkod has already cleared.
     */
    val failRename = mutableSetOf<String>()

    /**
     * Container ids whose [start] succeeds but leaves the container dead: [inspectContainer] then
     * reports `State.Running=false, ExitCode=1`, the shape a crash-looping replacement has.
     */
    val startedThenExits = mutableSetOf<String>()

    /** id -> `State.Health.Status` reported by [inspectContainer] and matched by the `health` filter. */
    val health = mutableMapOf<String, String>()

    /** id -> running, as tracked through [start]/[stop]/[restart]; absent means "as registered". */
    private val running = mutableMapOf<String, Boolean>()

    /** id -> name given by [rename]; absent means the name in the registered payload still stands. */
    private val renamed = mutableMapOf<String, String>()

    /** Ids [remove] deleted — they hold no name any more, so a [rename] onto it is free to succeed. */
    private val removed = mutableSetOf<String>()

    private var createSeq = 0

    /** Record `<verb>:<arg>` once [call] returned, or `<verb>!:<arg>` if it threw. */
    private fun <T> op(verb: String, arg: String, call: () -> T): T {
        val result = try {
            call()
        } catch (e: Throwable) {
            ops += "$verb!:$arg"
            throw e
        }
        ops += "$verb:$arg"
        return result
    }

    override fun version(): JsonObject = obj("""{"Version":"0.0.0-fake","ApiVersion":"1.45"}""")

    override fun listContainers(all: Boolean, filters: Map<String, List<String>>): JsonArray =
        JsonArray(listed.filter { matches(it, all, filters) })

    /**
     * The daemon's own filtering, modelled only as far as kodkod uses it: values within one filter are
     * OR'd, filters are AND'd, and an unknown filter key is ignored. A summary without `State` counts
     * as running, and a container whose health this fake does not model matches any `health` filter —
     * both keep hand-written fixtures, which register containers the daemon already filtered, valid.
     */
    private fun matches(summary: JsonObject, all: Boolean, filters: Map<String, List<String>>): Boolean {
        val state = summary.str("State") ?: "running"
        if (!all && state !in LISTED_WITHOUT_ALL) return false
        return filters.all { (key, values) ->
            when (key) {
                "status" -> state in values
                "label" -> values.all { matchesLabel(summary.obj("Labels"), it) }
                "health" -> health[summary.str("Id")]?.let { it in values } ?: true
                // The daemon matches a `name` filter as an unanchored pattern over every name a
                // container answers to, which is how the reconcile pass narrows `all=true` down to
                // backup candidates instead of listing the whole host.
                "name" -> values.any { needle -> summary.containerNames().any { it.contains(needle) } }
                else -> true
            }
        }
    }

    /** A `label` filter value is either `key` (present at all) or `key=value` (present and equal). */
    private fun matchesLabel(labels: JsonObject?, filter: String): Boolean {
        val key = filter.substringBefore('=')
        val value = labels.label(key) ?: return false
        return !filter.contains('=') || value == filter.substringAfter('=')
    }

    /**
     * The registered payload with `Name` and `State` reflecting this fake's lifecycle model: containers
     * answer to the name [rename] last gave them, are running until [stop] (or a [startedThenExits]
     * start) says otherwise, and report `State.Health.Status` from [health]. Any other `State` field the
     * test registered is passed through.
     */
    override fun inspectContainer(id: String): JsonObject {
        val stored = containers[id] ?: error("fake: no container registered for id '$id'")
        val storedState = stored.obj("State") ?: EMPTY_OBJECT
        val alive = running[id] ?: storedState["Running"]?.jsonPrimitive?.booleanOrNull ?: true
        val declaredHealth = health[id]
        return buildJsonObject {
            stored.forEach { (key, value) -> if (key != "State" && key != "Name") put(key, value) }
            nameOf(id)?.let { put("Name", "/$it") }
            put(
                "State",
                buildJsonObject {
                    storedState.forEach { (key, value) ->
                        if (key !in COMPUTED_STATE_KEYS && !(key == "Health" && declaredHealth != null)) put(key, value)
                    }
                    put("Running", alive)
                    put("ExitCode", if (alive) 0 else 1)
                    declaredHealth?.let { status -> put("Health", buildJsonObject { put("Status", status) }) }
                },
            )
        }
    }

    override fun restart(id: String, timeout: Int?, expectedStopSeconds: Int?) {
        op("restart", id) {
            restartTimeouts += timeout
            running[id] = id !in startedThenExits
        }
    }

    override fun stop(id: String, timeout: Int?, expectedStopSeconds: Int?) {
        op("stop", id) {
            stopTimeouts += timeout
            running[id] = false
        }
    }

    override fun start(id: String) {
        op("start", id) {
            if (id in failStart) throw DockerException(500, "fake: start failure for '$id'")
            running[id] = id !in startedThenExits
        }
    }

    /**
     * Renaming is modelled with the daemon's name index: a name another live container holds is refused
     * with 409, exactly as `POST /containers/{id}/rename` does. Without that a fake rollback could
     * quietly take a name off a container that is still sitting on it, which is the whole failure mode
     * the recreate path has to survive.
     */
    override fun rename(id: String, name: String) {
        op("rename", "$id->$name") {
            val holder = holderOf(name)
            if (name in failRename || (holder != null && holder != id)) {
                throw DockerException(409, "fake: name '$name' is already in use")
            }
            renamed[id] = name
        }
    }

    override fun remove(id: String, force: Boolean) {
        op("remove", id) {
            if (id in failRemove) throw DockerException(500, "fake: remove failure for '$id'")
            running.remove(id)
            removed += id
        }
    }

    /** Name [id] answers to right now — what [rename] last set, else what its payload was registered with. */
    private fun nameOf(id: String): String? = renamed[id] ?: containers[id]?.str("Name")?.trimStart('/')

    /** The container currently holding [name], as the daemon's name index would answer. */
    private fun holderOf(name: String): String? =
        (containers.keys + renamed.keys).firstOrNull { it !in removed && nameOf(it) == name }

    override fun connectNetwork(network: String, containerId: String, endpoint: JsonObject) {
        op("connect", "$network:$containerId") {}
    }

    override fun create(name: String, body: JsonObject, platform: String?): String =
        op("create", name) {
            platforms += platform
            if (name in failCreate) throw DockerException(500, "fake: create failure for '$name'")
            created += name to body
            val id = "new-$name-${createSeq++}"
            // The daemon knows the replacement from here on, so whatever inspects it next (the liveness
            // gate) gets an answer. A payload the test registered for this id up front wins, which is how
            // a test asks for a replacement that comes up `Restarting`.
            containers.getOrPut(id) { obj("""{"Name":"/$name","Config":{},"HostConfig":{},"NetworkSettings":{"Networks":{}}}""") }
            id
        }

    override fun inspectImage(ref: String): JsonObject =
        images[ref] ?: error("fake: no image registered for ref '$ref'")

    override fun removeImage(ref: String) {
        op("removeImage", ref) { removedImages += ref }
    }

    override fun inspectDistribution(ref: String, registryAuth: String?): JsonObject {
        val digest = distribution[ref] ?: error("fake: no distribution registered for ref '$ref'")
        return obj("""{"Descriptor":{"digest":"$digest"}}""")
    }

    override fun pull(fromImage: String, tag: String, registryAuth: String?, platform: String?) {
        op("pull", "$fromImage:$tag") {
            platforms += platform
            onPull(fromImage, tag)
        }
    }

    private companion object {
        /** States `docker ps` shows without `--all`. */
        val LISTED_WITHOUT_ALL = setOf("running", "restarting", "paused")

        /** `State` fields this fake owns; anything else in a registered payload is passed through. */
        val COMPUTED_STATE_KEYS = setOf("Running", "ExitCode")

        fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    }
}
