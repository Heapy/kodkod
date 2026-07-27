package io.heapy.kodkod

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant

/**
 * Restarts unhealthy containers — the same job as the reference `docker-autoheal` tools, driven
 * by labels under the configured namespace:
 *
 *  - `<ns>.autoheal.enable=true|false` — opt in/out (default follows [Config.autohealMonitorAll])
 *  - `<ns>.stop.timeout=<seconds>`     — per-container stop timeout override
 *
 * A restart is never issued in isolation: containers wired to the restarted one at create time
 * (`network_mode: service:x`, legacy `--link`) are restarted after it — see [findDependents] — and
 * they are looked up across the whole daemon, not just the labelled set.
 *
 * Neither is a restart repeated indefinitely: a container that comes back unhealthy every time is
 * backed off per container (see [restarts]), because the restart is evidently not the fix.
 *
 * [clock] and [sleeper] default to the real ones and exist so waiting logic can be driven from tests
 * without spending the wall-clock time it describes.
 */
class Autoheal(
    private val api: DockerClient,
    private val config: Config,
    private val selfId: String?,
    private val clock: WallClock = WallClock.SYSTEM,
    private val sleeper: Sleeper = Sleeper.SYSTEM,
) {
    private val ns = config.labelNamespace

    /**
     * Restarts issued per container id, and when the last one was — the only state [Autoheal] keeps
     * between cycles, and what makes it necessary at all. A container that is unhealthy because of its
     * *configuration* is never fixed by a restart, yet a cycle knowing nothing about the previous one
     * restarts it every `KODKOD_AUTOHEAL_INTERVAL`, forever — and every restart resets the container's
     * healthcheck `start_period`, so it reads as freshly starting rather than broken and the real fault
     * is masked. Containers are counted independently: one flapping service must not throttle another.
     */
    private val restarts = HashMap<String, Restart>()

    /**
     * How many restarts a container has had in its current unhealthy spell, when the last one was, and
     * since when it has been missing from the `health=unhealthy` listing (`null` while it is still in
     * it) — see [forgetRecovered].
     *
     * Every *window* here is measured in elapsed nanoseconds ([atNanos], [absentSince]), because a wall
     * clock corrected under a running process would otherwise stretch or collapse a backoff by the size
     * of the correction. [at] is the wall-clock reading of the same moment, and exists only to print the
     * instant of the next attempt.
     */
    private class Restart(val count: Int, val at: Long, val atNanos: Long, val absentSince: Long? = null) {
        /** The same record, with the container observed as unhealthy again right now. */
        fun seen(): Restart = if (absentSince == null) this else Restart(count, at, atNanos)
    }

    fun runOnce() {
        val containers = api.listContainers(all = false, filters = unhealthyFilters())

        forgetRecovered(containers.mapNotNullTo(HashSet()) { it.jsonObject.str("Id") })

        for (element in containers) {
            val container = element.jsonObject
            val id = container.str("Id") ?: continue
            val labels = container.obj("Labels")
            if (isSelf(id, labels, selfId)) continue

            val short = id.take(12)
            val name = container.arr("Names").firstString()?.trimStart('/') ?: short
            if (!labelTruthy(labels, "$ns.autoheal.enable", config.autohealMonitorAll)) continue

            if (container.str("State") == "restarting") {
                Log.info("[$name ($short)] already restarting — skipping")
                continue
            }

            // No override means no `?t=`: the daemon then applies the container's own Config.StopTimeout.
            // Autoheal works off the container list alone — deliberately no inspect per unhealthy
            // container — so in that case it cannot know that value and the read timeout keeps its 60s
            // floor. A container with a longer stop window therefore reports a read timeout while the
            // daemon is still restarting it; that outcome is read back from the daemon rather than
            // believed (see [restartLanded]), which is cheaper than an inspect per unhealthy container.
            // The read-back is where the value is finally learned — from the inspect it was going to
            // make anyway — and it is what sizes its own budget, so the saving costs no fidelity.
            val where = "[$name ($short)]"
            if (backoffHolds(id, where)) continue

            val timeout = stopTimeout(labels)
            val window = timeout?.let { "${it}s timeout" } ?: "its own stop timeout"
            val attempt = (restarts[id]?.count ?: 0) + 1
            val again = if (attempt > 1) " (restart #$attempt for this container)" else ""
            Log.warn("$where unhealthy — restarting with $window$again")
            // Counted before the call, not after: a restart the daemon refuses is not a reason to keep
            // asking every cycle either. The same instant is what a lost answer is read back against.
            val issuedAt = clock.millis()
            restarts[id] = Restart(attempt, issuedAt, clock.nanos())
            val restarted = try {
                api.restart(id, timeout)
                Log.info("$where restart successful")
                true
            } catch (e: Exception) {
                Log.error("$where restart failed: ${e.message}")
                restartLanded(id, where, issuedAt, e)
            }
            if (restarted) restartDependents(container, name)
        }
    }

    /** The listing autoheal works off: unhealthy containers, narrowed to the labelled ones if asked. */
    private fun unhealthyFilters(): Map<String, List<String>> =
        linkedMapOf<String, List<String>>("health" to listOf("unhealthy")).also {
            // When not monitoring everything, let Docker pre-filter to labelled containers.
            if (!config.autohealMonitorAll) it["label"] = listOf("$ns.autoheal.enable")
        }

    /**
     * Whether the restart happened after all, despite the call reporting a failure.
     *
     * A read timeout is not an answer. `POST /restart` is answered only once the daemon has stopped and
     * started the container, so a container whose stop window is longer than the socket's read timeout
     * reports a failure for a restart that is going through perfectly well — and treating that as "did
     * not happen" is what leaves every consumer of its network namespace joined to a namespace that has
     * already been torn down, with nothing but `Running` to show for it. So the daemon is asked what
     * actually happened instead.
     *
     * A failure the daemon *did* answer (any real HTTP status: no such container, a conflict, an
     * internal error) is a verdict and is taken as one — waiting on it would only delay every other
     * unhealthy container. Only a transport failure, which carries no status at all, is unknown.
     *
     * What the read-back looks for is a **new** run, not merely a running container: the very case this
     * exists for — a stop window longer than the read timeout — is one in which the daemon has not even
     * finished stopping the container when the timeout fires, so it is still up, from before. `Running`
     * on its own therefore answers yes at once, and autoheal would refresh the netns consumers at the
     * moment the read timed out while the provider's namespace is torn down seconds later. So the
     * container's own `State.StartedAt` has to be at or past [issuedAt], the instant the restart was
     * asked for; both are readings of the same host clock (kodkod talks to a local socket).
     */
    private fun restartLanded(id: String, where: String, issuedAt: Long, failure: Exception): Boolean {
        if (failure is DockerException && failure.status >= 400) return false
        var deadline = clock.nanos() + millisToNanos(RESTART_VERIFY_MS)
        var sized = false
        Log.warn("$where the daemon gave no usable answer — reading back whether the restart landed")
        while (true) {
            val inspect = runCatching { api.inspectContainer(id) }.getOrNull()
            // The first answer that comes back is also the first chance to learn how long this
            // container is entitled to take. Free by construction: the read-back is already inspecting.
            if (inspect != null && !sized) {
                sized = true
                deadline = maxOf(deadline, stopWindowDeadline(inspect, where) ?: deadline)
            }
            val state = inspect?.obj("State")
            if (state != null && state.startedSince(issuedAt)) {
                Log.warn("$where it started again — treating the restart as done and refreshing its dependents")
                return true
            }
            if (clock.nanos() >= deadline) {
                Log.error(
                    "$where has not started again — leaving its dependents alone, restarting them against a " +
                        "container that is down would only spread the outage",
                )
                return false
            }
            sleeper.sleep(PROBE_INTERVAL_MS)
        }
    }

    /**
     * The deadline this container's **own** stop window earns the read-back, or `null` when the flat
     * [RESTART_VERIFY_MS] already covers it (or the daemon does not report one).
     *
     * Without this the budget was the one thing in the chain that did not scale with the wait it exists
     * for. Autoheal deliberately works off the container listing alone, so it passes no
     * `expectedStopSeconds` and the restart's read timeout keeps its 60s floor; the read-back then
     * allowed another 60s. A container with `stop_grace_period: 120s` — an ordinary Postgres or
     * Elasticsearch setting, and precisely the configuration that makes the answer go missing in the
     * first place — finishes its restart at ~2 minutes, just past that deadline. The restart was then
     * recorded as "did not land" while it was landing, and the containers sharing its network namespace
     * were left on a namespace being torn down as the decision was made: `Running`, no interfaces, and
     * nothing in the log about it.
     *
     * The window is measured from here rather than from the restart, so it is granted in full on top of
     * the read timeout that already ran out — which is the safe direction: the cost of waiting too long
     * is one autoheal cycle spent on a container that is restarting, and the cost of waiting too little
     * is the silent failure above.
     */
    private fun stopWindowDeadline(inspect: JsonObject, where: String): Long? {
        val stopSeconds = inspect.obj("Config")?.str("StopTimeout")?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val window = stopSeconds * 1000L + RESTART_VERIFY_HEADROOM_MS
        if (window <= RESTART_VERIFY_MS) return null
        Log.info(
            "$where its own stop timeout is ${stopSeconds}s, so the restart is given ${window / 1000}s to " +
                "show up rather than ${RESTART_VERIFY_MS / 1000}s",
        )
        return clock.nanos() + millisToNanos(window)
    }

    /** Whether this `State` describes a container that came up again at or after [issuedAt]. */
    private fun JsonObject.startedSince(issuedAt: Long): Boolean {
        if (str("Running") != "true" || str("Restarting") == "true") return false
        // A daemon that reports no start time at all leaves the restart unproven; the wait then runs out
        // and the dependents are left alone, which is the safe half of an outcome we cannot establish.
        return (dockerTime("StartedAt") ?: return false) >= issuedAt
    }

    /**
     * Drop the backoff state of containers that actually recovered, and only those.
     *
     * Absence from a `health=unhealthy` listing is **not** recovery: `docker restart` resets the
     * healthcheck to `starting`, so the container leaves that listing for as long as its `start_period`
     * and retries take — which is exactly the window the counter has to survive for the backoff to mean
     * anything at all. Resetting on absence made the backoff inert on the defaults: the counter was
     * always wiped before the container was seen unhealthy again, and it was restarted every interval
     * forever, which is the behaviour the backoff exists to stop.
     *
     * So the reset needs positive evidence — the daemon reporting the container `healthy` — and the one
     * extra listing that takes is paid only while something is actually being backed off. A container
     * that is in neither listing (removed, unlabelled, or stopped) is forgotten once it has been gone
     * for a whole [Config.autohealMaxInterval]: nothing else would ever clear it.
     */
    private fun forgetRecovered(unhealthyIds: Set<String>) {
        if (restarts.isEmpty()) return
        val missing = restarts.keys.filterNot { it in unhealthyIds }
        val recovered = if (missing.isEmpty()) emptySet() else healthyAmong(missing)
        val now = clock.nanos()
        val kept = HashMap<String, Restart>(restarts.size)
        for ((id, restart) in restarts) {
            when {
                id in unhealthyIds -> kept[id] = restart.seen()
                id in recovered ->
                    Log.info("[${id.take(12)}] is healthy again — its restart backoff starts from scratch")
                else -> {
                    val absentSince = restart.absentSince ?: now
                    if (now - absentSince < secondsToNanos(config.autohealMaxInterval)) {
                        kept[id] = Restart(restart.count, restart.at, restart.atNanos, absentSince)
                    }
                }
            }
        }
        restarts.clear()
        restarts.putAll(kept)
    }

    /** Which of [ids] the daemon currently reports as healthy. A listing that fails forgets nothing. */
    private fun healthyAmong(ids: List<String>): Set<String> =
        try {
            api.listContainers(all = false, filters = mapOf("health" to listOf("healthy"), "id" to ids))
                .mapNotNullTo(HashSet()) { it.jsonObject.str("Id") }
        } catch (e: Exception) {
            Log.warn("could not check whether backed-off containers recovered: ${e.message}")
            emptySet()
        }

    /**
     * Restart what the container we just restarted was holding up.
     *
     * Restarting a container tears down its network namespace, so every container joined to it
     * (`network_mode: service:x`) comes back without interfaces — no `eth0`, no egress — while still
     * reporting `Running`, which is precisely the state nothing else notices. Legacy `--link`
     * dependents keep a stale address for the same reason. Only meaningful once the provider is
     * actually back, hence after a successful restart.
     */
    private fun restartDependents(summary: JsonObject, providerName: String) {
        val provider = providerOf(summary) ?: return
        for (dependent in findDependents(api, provider)) {
            if (isSelf(dependent.id, dependent.labels, selfId)) continue
            if (!dependent.running) {
                Log.info(
                    "[${dependent.name} (${dependent.short})] depends on $providerName but is " +
                        "${dependent.state} — leaving it alone",
                )
                continue
            }
            restartDependent(api, dependent, providerName, "restarted", stopTimeout(dependent.labels))
        }
    }

    /**
     * Whether [id] is still inside the window earned by its previous restarts. Nothing is remembered
     * about *why* the container is unhealthy — only that the last restart did not make it healthy, which
     * is the one thing a restart was supposed to achieve.
     */
    private fun backoffHolds(id: String, where: String): Boolean {
        val last = restarts[id] ?: return false
        val window = backoffSeconds(last.count)
        if (clock.nanos() - last.atNanos >= secondsToNanos(window)) return false
        Log.info(
            "$where still unhealthy after ${last.count} restart(s) — restarting it again is unlikely to " +
                "help, so the next attempt is no earlier than ${Instant.ofEpochMilli(last.at + window * 1000L)} " +
                "(KODKOD_AUTOHEAL_MAX_INTERVAL=${config.autohealMaxInterval}s)",
        )
        return true
    }

    /**
     * `KODKOD_AUTOHEAL_INTERVAL * 2^(count-1)`, capped at `KODKOD_AUTOHEAL_MAX_INTERVAL`. Reached by
     * doubling rather than by shifting so that a large ceiling cannot overflow the exponent, and so the
     * loop ends the moment the cap is in reach.
     */
    private fun backoffSeconds(count: Int): Long {
        val max = config.autohealMaxInterval
        var seconds = config.autohealInterval
        repeat(count - 1) {
            if (seconds >= max / 2) return max
            seconds *= 2
        }
        return seconds
    }

    private fun stopTimeout(labels: JsonObject?): Int? = stopTimeout(labels, config, ns)

    private companion object {
        /** Gap between state reads while waiting out a restart whose outcome the socket did not carry. */
        const val PROBE_INTERVAL_MS = 500L

        /**
         * How long a restart that reported a transport failure is given to show up as a running
         * container, when nothing better is known. Generous on purpose: what is being waited out is a
         * graceful stop window that already outlasted the socket's own read timeout. A container whose
         * own `Config.StopTimeout` needs more than this gets more — see [stopWindowDeadline].
         */
        const val RESTART_VERIFY_MS = 60_000L

        /** Room for the SIGKILL and the teardown that follow a stop window, plus the start after them. */
        const val RESTART_VERIFY_HEADROOM_MS = 15_000L
    }
}
