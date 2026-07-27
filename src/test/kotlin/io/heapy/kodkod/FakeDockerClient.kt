package io.heapy.kodkod

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

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
 * [listContainers] applies `all` and the `status`/`label`/`health`/`name`/`id` filters to [listed] the
 * way the daemon would, so code that deliberately looks beyond the monitored set (`all=true`, no label
 * filter) can be told apart from code that only ever sees its own targets. `name` matches as an
 * unanchored substring over every name a container answers to (what narrows the backup reconcile) and
 * `id` matches by prefix (what asks about one backed-off container without listing the host) — both are
 * the daemon's own semantics, and both are load-bearing: code narrowing a listing that way is asserted
 * on through [listFilters] *and* has to get the right containers back.
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

    /** Timeouts passed to [stop], in call order — the `?t=` the daemon is actually sent. */
    val stopTimeouts = mutableListOf<Int?>()

    /**
     * `expectedStopSeconds` passed to [stop], in call order. It never reaches the daemon (it only sizes
     * the caller's own read timeout), so nothing else can tell whether it was computed at all — and a
     * stop whose read timeout is shorter than its stop window reports a perfectly good stop as a
     * failure.
     */
    val stopExpected = mutableListOf<Int?>()

    /** Timeouts passed to [restart], in call order. */
    val restartTimeouts = mutableListOf<Int?>()

    /** `expectedStopSeconds` passed to [restart], in call order; see [stopExpected]. */
    val restartExpected = mutableListOf<Int?>()

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

    /** Container ids for which [restart] should throw — used to drive autoheal's failure path. */
    val failRestart = mutableSetOf<String>()

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

    /**
     * Container ids whose [start] succeeds and whose container the daemon then forgets entirely, so
     * every later [inspectContainer] answers 404 — what an `AutoRemove` container that exits at once
     * (or somebody else's `docker rm`) leaves behind.
     */
    val vanishesAfterStart = mutableSetOf<String>()

    /** Container ids whose [inspectContainer] throws — a daemon that cannot answer, not a 404. */
    val failInspect = mutableSetOf<String>()

    /** Container ids for which [stop] should throw — used to drive the "stop failed" branches. */
    val failStop = mutableSetOf<String>()

    /** id -> `State.Health.Status` reported by [inspectContainer] and matched by the `health` filter. */
    val health = mutableMapOf<String, String>()

    /**
     * The clock this fake stamps `State.StartedAt` from. A container it starts (or restarts) is recorded
     * as having started *now*, which is the only thing that tells a restart the daemon carried out from
     * one it has not got to yet — the two are indistinguishable by `Running` alone. Tests driving virtual
     * time pass their own [FakeClock] so the stamps and the code under test read the same clock.
     */
    var clock: WallClock = WallClock.SYSTEM

    /** id -> running, as tracked through [start]/[stop]/[restart]; absent means "as registered". */
    private val running = mutableMapOf<String, Boolean>()

    /** id -> `State.StartedAt` as epoch millis, set by the last [start]/[restart] that went through. */
    private val startedAt = mutableMapOf<String, Long>()

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

    /** When set, [listContainers] throws — the daemon being unreachable mid-cycle. */
    var failList = false

    /**
     * Which *individual* listings throw, by the filters they were asked with. A single boolean cannot
     * express a caller that makes two listings and has to survive losing one of them: with both gone,
     * only the first failure is ever exercised. The whole-daemon listing is `emptyMap()`.
     */
    var failListWhen: (Map<String, List<String>>) -> Boolean = { false }

    /** Filter maps passed to [listContainers], in call order — proof of how wide a scan really was. */
    val listFilters = mutableListOf<Map<String, List<String>>>()

    override fun listContainers(all: Boolean, filters: Map<String, List<String>>): JsonArray {
        listFilters += filters
        if (failList || failListWhen(filters)) throw DockerException(500, "fake: list failure")
        return JsonArray(listed.filter { matches(it, all, filters) }.map(::asListedNow))
    }

    /**
     * The summary as a listing taken **now** would carry it: `State` and `Names` are this fake's
     * lifecycle model, the very values [matches] filters on.
     *
     * Without this the two halves disagreed: a replacement [create] appended was filtered as running
     * once something started it, but the payload handed back still said `"State":"created"` — a
     * combination no daemon can produce. Everything that reads a container's state out of a *listing*
     * (`dependentsIn`, the reconcile pass) was therefore told the container had never run, which made
     * the branches keyed on that state unreachable and the tests covering them unable to fail.
     */
    private fun asListedNow(summary: JsonObject): JsonObject {
        val id = summary.str("Id")
        val state = stateOf(id, summary)
        val name = id?.let(renamed::get)
        if (state == summary.str("State") && name == null) return summary
        return buildJsonObject {
            summary.forEach { (key, value) -> if (key != "State" && key != "Names") put(key, value) }
            put("State", state)
            put("Names", name?.let { JsonArray(listOf(JsonPrimitive("/$it"))) } ?: summary["Names"] ?: EMPTY_ARRAY)
        }
    }

    /**
     * The lifecycle wins over the registered summary: a container this fake started or stopped reports
     * the state it is actually in, exactly as a listing taken afterwards would.
     */
    private fun stateOf(id: String?, summary: JsonObject): String =
        id?.let(running::get)?.let { if (it) "running" else "exited" } ?: summary.str("State") ?: "running"

    /**
     * The daemon's own filtering, modelled only as far as kodkod uses it: values within one filter are
     * OR'd, filters are AND'd, and an unknown filter key is ignored. A summary without `State` counts
     * as running, which keeps hand-written fixtures valid.
     *
     * A `health` filter, however, matches only containers whose health this fake actually models. It
     * used to match unmodelled ones too, which made "is it unhealthy?" and "is it healthy again?"
     * indistinguishable — and any code that tells them apart untestable.
     */
    private fun matches(summary: JsonObject, all: Boolean, filters: Map<String, List<String>>): Boolean {
        val id = summary.str("Id")
        val state = stateOf(id, summary)
        if (!all && state !in LISTED_WITHOUT_ALL) return false
        // Same for the name: after a rename the daemon's index knows only the new one.
        val names = renamed[id]?.let(::listOf) ?: summary.containerNames()
        return filters.all { (key, values) ->
            when (key) {
                "status" -> state in values
                "label" -> values.all { matchesLabel(summary.obj("Labels"), it) }
                "health" -> health[id] in values
                // The daemon matches an `id` filter by prefix, which is how a backed-off container is
                // asked about by id without listing the host.
                "id" -> values.any { needle -> id?.startsWith(needle) == true }
                // The daemon matches a `name` filter as an unanchored pattern over every name a
                // container answers to, which is how the reconcile pass narrows `all=true` down to
                // backup candidates instead of listing the whole host.
                "name" -> values.any { needle -> names.any { it.contains(needle) } }
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
     *
     * [ref] is resolved the way `GET /containers/{id}/json` resolves it — full id, name, or id prefix —
     * because that is what a `container:<ref>` network mode is followed with. A fake that only answered
     * to full ids made "the reference names a container that is gone" and "the reference is spelled as a
     * name, so it now resolves to the replacement" indistinguishable: both came back as a fixture error.
     */
    override fun inspectContainer(ref: String): JsonObject {
        if (ref in failInspect) throw DockerException(500, "fake: inspect failure for '$ref'")
        // A container the daemon has forgotten answers 404, which is an answer; a reference no test ever
        // registered is a broken fixture and must not be mistaken for one.
        val id = resolveRef(ref)
            ?: if (removed.any { it == ref || (ref.length >= 4 && it.startsWith(ref)) }) {
                throw DockerException(404, "fake: no such container: $ref")
            } else {
                error("fake: no container registered for id '$ref'")
            }
        val stored = containers[id] ?: error("fake: no container registered for id '$ref'")
        val storedState = stored.obj("State") ?: EMPTY_OBJECT
        val alive = running[id] ?: storedState["Running"]?.jsonPrimitive?.booleanOrNull ?: true
        val declaredHealth = health[id]
        val started = startedAt[id]
        // What this fake models itself wins over the registered payload; everything else passes through.
        val computed = COMPUTED_STATE_KEYS +
            listOfNotNull("Health".takeIf { declaredHealth != null }, "StartedAt".takeIf { started != null })
        return buildJsonObject {
            stored.forEach { (key, value) -> if (key != "State" && key != "Name") put(key, value) }
            nameOf(id)?.let { put("Name", "/$it") }
            put(
                "State",
                buildJsonObject {
                    storedState.forEach { (key, value) -> if (key !in computed) put(key, value) }
                    put("Running", alive)
                    put("ExitCode", if (alive) 0 else 1)
                    declaredHealth?.let { status -> put("Health", buildJsonObject { put("Status", status) }) }
                    started?.let { put("StartedAt", Instant.ofEpochMilli(it).toString()) }
                },
            )
        }
    }

    override fun restart(id: String, timeout: Int?, expectedStopSeconds: Int?) {
        op("restart", id) {
            restartTimeouts += timeout
            restartExpected += expectedStopSeconds
            if (id in failRestart) throw DockerException(500, "fake: restart failure for '$id'")
            requireNamespaceProvider(id)
            running[id] = id !in startedThenExits
            startedAt[id] = clock.millis()
        }
    }

    override fun stop(id: String, timeout: Int?, expectedStopSeconds: Int?) {
        op("stop", id) {
            stopTimeouts += timeout
            stopExpected += expectedStopSeconds
            if (id in failStop) throw DockerException(500, "fake: stop failure for '$id'")
            running[id] = false
        }
    }

    override fun start(id: String) {
        op("start", id) {
            if (id in failStart) throw DockerException(500, "fake: start failure for '$id'")
            requireNamespaceProvider(id)
            running[id] = id !in startedThenExits
            startedAt[id] = clock.millis()
            if (id in vanishesAfterStart) {
                containers.remove(id)
                removed += id
            }
        }
    }

    /**
     * A container joined to another's network namespace (`HostConfig.NetworkMode=container:<ref>`) can only
     * be started while that other container exists **and is running** — the daemon answers `No such
     * container` / `cannot join network of a non running container` otherwise. The fake used to start such a
     * container regardless, which made a namespace destroyed by a recreate indistinguishable from a live one:
     * the rollback of a create-time dependent looked like it worked, and every test asserting that it did was
     * asserting production behaviour that cannot happen.
     */
    private fun requireNamespaceProvider(id: String) {
        val ref = netnsRef(containers[id]?.obj("HostConfig")) ?: return
        val provider = resolveRef(ref) ?: throw DockerException(404, "fake: no such container: $ref")
        if (!isRunning(provider)) {
            throw DockerException(500, "fake: cannot join network of a non running container: $ref")
        }
    }

    /**
     * The container a reference names, resolved the way the daemon does: by full id, by name, or by id
     * prefix — in that order, so a fixture in which one container's name is another's id resolves the
     * way the daemon's index would rather than the way the map happens to be ordered.
     */
    private fun resolveRef(ref: String): String? {
        val live = (containers.keys + renamed.keys).filterNot { it in removed }
        return live.firstOrNull { it == ref }
            ?: live.firstOrNull { nameOf(it) == ref }
            ?: live.firstOrNull { ref.length >= 4 && it.startsWith(ref) }
    }

    /** Whether the daemon still knows [id] and reports it running — this fake's lifecycle model wins. */
    private fun isRunning(id: String): Boolean {
        if (id in removed || id !in containers) return false
        running[id]?.let { return it }
        containers[id]?.obj("State")?.get("Running")?.jsonPrimitive?.booleanOrNull?.let { return it }
        return (listed.firstOrNull { it.str("Id") == id }?.str("State") ?: "running") == "running"
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

    /**
     * A removed container is *gone*: out of every listing, out of [inspectContainer] (which answers 404
     * from then on) and off the name index. The fake used to keep it, so a second cycle re-discovered
     * and re-updated a container the daemon had destroyed — a state no real daemon can produce, and one
     * that quietly propped up the multi-cycle tests.
     */
    override fun remove(id: String, force: Boolean) {
        op("remove", id) {
            if (id in failRemove) throw DockerException(500, "fake: remove failure for '$id'")
            running.remove(id)
            startedAt.remove(id)
            renamed.remove(id)
            containers.remove(id)
            listed.removeAll { it.str("Id") == id }
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
            containers.getOrPut(id) { inspectOf(id, name, body) }
            // And it appears in listings — as `created` until something starts it. Without this a later
            // pass (a second cycle's discovery, the daemon-wide scan for create-time dependents) cannot
            // see the container that was just made, so tests of "do not touch it twice" cannot fail.
            listed += summaryOf(id, name, body)
            id
        }

    /** The inspect payload of a container created from [body] — what the daemon would report back. */
    private fun inspectOf(id: String, name: String, body: JsonObject): JsonObject = buildJsonObject {
        put("Id", id)
        put("Name", "/$name")
        // The daemon resolves the ref to an image id; an unknown ref keeps the ref, as a digest would.
        val ref = body.str("Image").orEmpty()
        put("Image", images[ref]?.str("Id") ?: ref)
        put(
            "Config",
            buildJsonObject {
                body.forEach { (key, value) -> if (key !in NON_CONFIG_KEYS) put(key, value) }
            },
        )
        put("HostConfig", body.obj("HostConfig") ?: EMPTY_OBJECT)
        put(
            "NetworkSettings",
            buildJsonObject { put("Networks", body.obj("NetworkingConfig")?.obj("EndpointsConfig") ?: EMPTY_OBJECT) },
        )
    }

    /** The `/containers/json` summary of that same container. */
    private fun summaryOf(id: String, name: String, body: JsonObject): JsonObject = buildJsonObject {
        put("Id", id)
        put("Names", JsonArray(listOf(JsonPrimitive("/$name"))))
        put("State", "created")
        put("Labels", body.obj("Labels") ?: EMPTY_OBJECT)
        put("HostConfig", body.obj("HostConfig") ?: EMPTY_OBJECT)
        put(
            "NetworkSettings",
            buildJsonObject { put("Networks", body.obj("NetworkingConfig")?.obj("EndpointsConfig") ?: EMPTY_OBJECT) },
        )
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

        /** Create-body keys that are not part of the container's `Config`. */
        val NON_CONFIG_KEYS = setOf("HostConfig", "NetworkingConfig")

        /** `State` fields this fake owns; anything else in a registered payload is passed through. */
        val COMPUTED_STATE_KEYS = setOf("Running", "ExitCode")

        fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    }
}
