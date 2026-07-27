package io.heapy.kodkod

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** Exercises [Autoheal.runOnce] against a [FakeDockerClient] — no Docker daemon required. */
class AutohealTest {

    /**
     * Register a container the daemon reports as unhealthy: the listing entry *and* the health the
     * `health=unhealthy` filter reads. Both are needed — the fake filters by modelled health, so a
     * summary alone is a container of unknown health and appears in no health-filtered listing.
     */
    private fun FakeDockerClient.unhealthy(
        id: String,
        name: String = id,
        state: String = "running",
        labels: String = "{}",
    ) {
        listed += jsonObj("""{"Id":"$id","Names":["/$name"],"State":"$state","Labels":$labels}""")
        health[id] = "unhealthy"
    }

    private fun config(
        monitorAll: Boolean = true,
        stopTimeout: String? = null,
        maxInterval: String? = null,
    ): Config =
        Config.fromEnv(
            buildMap {
                put("KODKOD_AUTOHEAL_MONITOR_ALL", monitorAll.toString())
                stopTimeout?.let { put("KODKOD_STOP_TIMEOUT", it) }
                maxInterval?.let { put("KODKOD_AUTOHEAL_MAX_INTERVAL", it) }
            }::get,
        )

    /**
     * The [Autoheal] under test. The clock is **always** a [FakeClock], even where a test never moves
     * it: `Autoheal`'s own default is the production clock, and a cycle built on it sleeps through
     * every probe interval for real and measures its backoff against a wall clock the test cannot see.
     */
    private fun autoheal(docker: FakeDockerClient, config: Config, clock: FakeClock = FakeClock()) =
        Autoheal(docker, config, selfId = null, clock = clock, sleeper = clock)

    /**
     * Runs [cycles] autoheal cycles [everyMs] apart on a fake clock and returns the clock time of every
     * restart of `app` — the shape the "30s, then 60s, then 120s" of the backoff is stated in. The
     * container stays unhealthy the whole time, which is exactly the case a restart cannot fix.
     */
    private fun restartTimes(docker: FakeDockerClient, config: Config, cycles: Int, everyMs: Long): List<Long> {
        val clock = FakeClock()
        val autoheal = autoheal(docker, config, clock)
        val times = mutableListOf<Long>()
        repeat(cycles) {
            val before = docker.ops.count { it == "restart:app" }
            autoheal.runOnce()
            if (docker.ops.count { it == "restart:app" } > before) times += clock.millis()
            clock.advance(everyMs)
        }
        return times
    }

    /**
     * The pair every network-namespace test is about: an unhealthy [APP] and a healthy `sidecar` that
     * shares its namespace. Restarting `app` tears that namespace down, so what happens to the sidecar
     * afterwards is the whole question — and it is invisible from the sidecar's own state, which stays
     * `Running` either way.
     */
    private fun FakeDockerClient.appWithSidecar(
        appLabels: String = "",
        sidecarName: String = "sidecar",
        sidecarState: String = "running",
        sidecarLabels: String = "",
    ) {
        listed += containerSummary(APP, "app", labels = appLabels)
        listed += containerSummary(
            SIDE, sidecarName,
            state = sidecarState, networkMode = "$NETNS_PREFIX$APP", labels = sidecarLabels,
        )
        health[APP] = "unhealthy"
        // A stopped container has no health at all, and claiming one would make it match a health filter.
        if (sidecarState == "running") health[SIDE] = "healthy"
    }

    @Test
    fun restarts_an_unhealthy_container() {
        val docker = FakeDockerClient()
        docker.unhealthy("app")

        autoheal(docker, config(monitorAll = true)).runOnce()

        assertEquals(listOf("restart:app"), docker.ops)
    }

    @Test
    fun without_an_override_the_container_decides_its_own_stop_timeout() {
        val docker = FakeDockerClient()
        docker.unhealthy("app")

        autoheal(docker, config(monitorAll = true)).runOnce()

        assertEquals(
            listOf<Int?>(null), docker.restartTimeouts,
            "with no label and no KODKOD_STOP_TIMEOUT nothing may be sent — the daemon applies Config.StopTimeout",
        )
    }

    @Test
    fun the_label_overrides_the_stop_timeout() {
        val docker = FakeDockerClient()
        docker.unhealthy("app", labels = """{"kodkod.stop.timeout":"45"}""")

        autoheal(docker, config(monitorAll = true, stopTimeout = "25")).runOnce()

        assertEquals(listOf<Int?>(45), docker.restartTimeouts, "the label beats the env default")
    }

    @Test
    fun the_env_default_overrides_the_stop_timeout() {
        val docker = FakeDockerClient()
        docker.unhealthy("app")

        autoheal(docker, config(monitorAll = true, stopTimeout = "25")).runOnce()

        assertEquals(listOf<Int?>(25), docker.restartTimeouts, "an explicitly set KODKOD_STOP_TIMEOUT is an override")
    }

    @Test
    fun an_explicit_enable_false_opts_out_even_when_monitoring_everything() {
        val docker = FakeDockerClient()
        docker.unhealthy("app", labels = """{"kodkod.autoheal.enable":"false"}""")

        autoheal(docker, config(monitorAll = true)).runOnce()

        assertTrue(
            docker.ops.isEmpty(),
            "KODKOD_AUTOHEAL_MONITOR_ALL is a default, not an override — an explicit opt-out is the only " +
                "way to keep one container out of it: ${docker.ops}",
        )
    }

    @Test
    fun the_stop_window_the_restart_is_waited_for_is_the_one_that_was_asked_for() {
        val docker = FakeDockerClient()
        docker.unhealthy("app", labels = """{"kodkod.stop.timeout":"45"}""")

        autoheal(docker, config(monitorAll = true)).runOnce()

        assertEquals(
            listOf<Int?>(45), docker.restartExpected,
            "the read timeout has to cover the graceful window, or a good restart reports a timeout",
        )
    }

    @Test
    fun skips_a_container_that_is_already_restarting() {
        val docker = FakeDockerClient()
        docker.unhealthy("app", state = "restarting")

        autoheal(docker, config(monitorAll = true)).runOnce()

        assertTrue(docker.ops.isEmpty(), "a container Docker is already restarting should be left alone: ${docker.ops}")
    }

    @Test
    fun never_restarts_itself() {
        val docker = FakeDockerClient()
        docker.unhealthy("self", labels = """{"$SELF_LABEL":"true"}""")

        autoheal(docker, config(monitorAll = true)).runOnce()

        assertTrue(docker.ops.isEmpty(), "kodkod must never restart its own container: ${docker.ops}")
    }

    @Test
    fun restarts_the_containers_sharing_the_unhealthy_container_s_network_namespace() {
        val docker = FakeDockerClient()
        docker.appWithSidecar()

        autoheal(docker, config(monitorAll = true)).runOnce()

        assertEquals(
            listOf("restart:$APP", "restart:$SIDE"), docker.ops,
            "a plain restart leaves a netns consumer without eth0 while it still reports Running — " +
                "it has to be restarted after its provider",
        )
    }

    @Test
    fun restarts_a_consumer_that_is_not_monitored_itself() {
        val docker = FakeDockerClient()
        docker.appWithSidecar(appLabels = """"kodkod.autoheal.enable":"true"""")

        autoheal(docker, config(monitorAll = false)).runOnce()

        assertEquals(
            listOf("restart:$APP", "restart:$SIDE"), docker.ops,
            "an unlabelled sidecar is exactly the one nothing else will notice is broken",
        )
    }

    @Test
    fun leaves_a_consumer_that_is_not_running_alone() {
        val docker = FakeDockerClient()
        docker.appWithSidecar(sidecarState = "exited")

        autoheal(docker, config(monitorAll = true)).runOnce()

        assertEquals(
            listOf("restart:$APP"), docker.ops,
            "a stopped consumer has no namespace to lose — starting it is not autoheal's call: ${docker.ops}",
        )
    }

    @Test
    fun never_restarts_itself_as_a_consumer() {
        val docker = FakeDockerClient()
        docker.appWithSidecar(sidecarName = "kodkod", sidecarLabels = """"$SELF_LABEL":"true"""")

        autoheal(docker, config(monitorAll = true)).runOnce()

        assertEquals(
            listOf("restart:$APP"), docker.ops,
            "kodkod restarting itself mid-cycle would abandon the rest of the pass: ${docker.ops}",
        )
    }

    /**
     * `POST /restart` is answered only once the daemon has stopped *and* started the container, so a
     * container whose stop window outlasts the socket's read timeout reports a failure for a restart
     * that is going through perfectly well. Believing that report is what leaves every consumer of its
     * network namespace joined to a namespace that has already been torn down — while still reporting
     * `Running`, which is precisely the state nothing else notices.
     */
    @Test
    fun a_restart_whose_answer_never_arrived_still_refreshes_the_dependents() {
        val docker = FakeDockerClient()
        docker.appWithSidecar()
        // Up since well before the restart — and brought up again by it, which is the difference.
        docker.containers[APP] = jsonObj("""{"Name":"/app","State":{"StartedAt":"$LONG_AGO"}}""")
        val clock = FakeClock(now = NOW)
        docker.clock = clock

        Autoheal(ReadTimeout(docker, APP), config(monitorAll = true), null, clock, clock).runOnce()

        assertEquals(
            listOf("restart:$APP", "restart:$SIDE"), docker.ops,
            "the daemon restarted it; only the answer was lost, and the sidecar has no eth0 either way: ${docker.ops}",
        )
    }

    /**
     * The case the read-back exists for is a stop window longer than the socket's read timeout — which
     * means that when the timeout fires the daemon is typically *still inside the graceful stop*: the
     * container is up, from before, and `Running` alone answers "landed" on the very first probe. Acting
     * on that restarts the netns consumers seconds before the provider's namespace is torn down, and
     * says "it is running again" while doing it. Only a container that came up *since* the restart was
     * asked for is evidence of anything.
     */
    @Test
    fun a_restart_still_inside_the_graceful_stop_is_not_read_as_one_that_landed() {
        val docker = FakeDockerClient()
        docker.appWithSidecar()
        // Still the run it was in before the restart was asked for: nothing has happened to it yet.
        docker.containers[APP] = jsonObj("""{"Name":"/app","State":{"StartedAt":"$LONG_AGO"}}""")
        val clock = FakeClock(now = NOW)
        docker.clock = clock
        val stuck = ReadTimeout(docker, APP, carriedOut = false)

        Autoheal(stuck, config(monitorAll = true), null, clock, clock).runOnce()

        assertTrue(
            docker.ops.none { it.contains(SIDE) },
            "the provider's namespace is about to be torn down, so refreshing its consumers now is exactly " +
                "what abandons them: ${docker.ops}",
        )
        assertTrue(clock.sleeps.isNotEmpty(), "the outcome has to have been waited for, not assumed: ${clock.sleeps}")
    }

    @Test
    fun a_restart_that_really_did_not_happen_leaves_the_dependents_alone() {
        val docker = FakeDockerClient()
        docker.appWithSidecar()
        // Registered as stopped, and it stays that way: the read-back finds nothing running.
        docker.containers[APP] = jsonObj("""{"Name":"/app","State":{"Running":false}}""")
        docker.failRestart += APP
        val clock = FakeClock(now = NOW)
        docker.clock = clock

        Autoheal(ReadTimeout(docker, APP), config(monitorAll = true), null, clock, clock).runOnce()

        assertEquals(
            listOf("restart!:$APP"), docker.ops,
            "restarting the consumers of a namespace that never came back only spreads the outage: ${docker.ops}",
        )
        assertTrue(clock.sleeps.isNotEmpty(), "the outcome has to have been waited for, not assumed: ${clock.sleeps}")
    }

    /**
     * The read-back is for an answer that never came, and only for that. A daemon that *did* answer —
     * no such container, a conflict, an internal error — has given its verdict, and spending 60s per
     * container waiting for it to change is 60s of the shared cycle lock: every other unhealthy
     * container in this cycle, and the whole mutating half of the update cycle, wait behind it. The log
     * would say the daemon "gave no usable answer" while quoting the answer it gave.
     */
    @Test
    fun a_restart_the_daemon_itself_refused_is_not_waited_out() {
        val docker = FakeDockerClient()
        docker.appWithSidecar()
        docker.failRestart += APP // a real HTTP status, not a lost answer
        val clock = FakeClock(now = NOW)
        docker.clock = clock

        val log = captureLog { Autoheal(docker, config(monitorAll = true), null, clock, clock).runOnce() }

        assertEquals(
            listOf("restart!:$APP"), docker.ops,
            "the consumers of a namespace that never came back are left alone either way: ${docker.ops}",
        )
        assertTrue(
            clock.sleeps.isEmpty(),
            "and nothing may be waited out: the daemon already said what happened: ${clock.sleeps}",
        )
        assertFalse(
            log.contains("gave no usable answer"),
            "a 500 from the daemon is a usable answer, and reporting it as silence is a falsehood: $log",
        )
    }

    /**
     * The read-back has to outlast the wait it exists for. Autoheal passes no `expectedStopSeconds`
     * (it deliberately makes no inspect per unhealthy container), so the restart's own read timeout
     * keeps its 60s floor and fires while the daemon is still inside a `stop_grace_period: 120s` — an
     * ordinary Postgres or Elasticsearch setting. A flat 60s budget on top of that expires at ~2
     * minutes, just before the container comes back, and the netns consumers are then left on a
     * namespace that is being torn down as the decision is made. The container's own `StopTimeout` is
     * what the budget has to be sized from — and it is right there in the inspect the read-back makes.
     */
    @Test
    fun a_restart_is_given_the_time_the_containers_own_stop_window_needs() {
        val docker = FakeDockerClient()
        docker.appWithSidecar()
        docker.containers[APP] =
            jsonObj("""{"Name":"/app","Config":{"StopTimeout":120},"State":{"StartedAt":"$LONG_AGO"}}""")
        val clock = FakeClock(now = NOW)
        docker.clock = clock
        // 120s of graceful stop, then the start: the daemon answers at ~t=125s, the socket at t=60s.
        val late = LateRestart(docker, APP, landsAfterMs = 125_000, clock = clock)

        Autoheal(late, config(monitorAll = true), null, clock, clock).runOnce()

        assertEquals(
            listOf("restart!:$APP", "restart:$SIDE"), docker.ops,
            "the restart landed, late — giving up on it strands the sidecar on a dead namespace: ${docker.ops}",
        )
    }

    /**
     * The container's own stop window sizes the read-back, but it does not get to size it without a
     * ceiling. The wait is held under the shared cycle lock, so it is paid by every *other* unhealthy
     * container in the same cycle and by the whole mutating half of the update cycle — a single service
     * with `stop_grace_period: 1h` would park kodkod for an hour. Past the cap the restart is recorded
     * as lost, which costs this one container's dependents a refresh and nothing else.
     */
    @Test
    fun a_stop_window_no_cycle_can_afford_is_capped() {
        val docker = FakeDockerClient()
        docker.appWithSidecar()
        docker.containers[APP] =
            jsonObj("""{"Name":"/app","Config":{"StopTimeout":3600},"State":{"StartedAt":"$LONG_AGO"}}""")
        val clock = FakeClock(now = NOW)
        docker.clock = clock
        val late = LateRestart(docker, APP, landsAfterMs = 400_000, clock = clock)

        val log = captureLog { Autoheal(late, config(monitorAll = true), null, clock, clock).runOnce() }

        assertEquals(
            listOf("restart!:$APP"), docker.ops,
            "an hour of stop window is not an hour the rest of the host may be held for: ${docker.ops}",
        )
        assertTrue(
            log.contains("at most 300s"),
            "and a budget that was truncated has to say so — it is why the dependents were left: $log",
        )
    }

    /** A container that says nothing about its stop window keeps the flat budget, and no more. */
    @Test
    fun a_restart_that_lands_past_every_budget_is_still_treated_as_lost() {
        val docker = FakeDockerClient()
        docker.appWithSidecar()
        docker.containers[APP] = jsonObj("""{"Name":"/app","State":{"StartedAt":"$LONG_AGO"}}""")
        val clock = FakeClock(now = NOW)
        docker.clock = clock
        val late = LateRestart(docker, APP, landsAfterMs = 125_000, clock = clock)

        Autoheal(late, config(monitorAll = true), null, clock, clock).runOnce()

        assertEquals(
            listOf("restart!:$APP"), docker.ops,
            "nothing said this container needs longer, and waiting forever holds up every other one: ${docker.ops}",
        )
    }

    @Test
    fun does_not_restart_a_still_unhealthy_container_every_interval() {
        val docker = FakeDockerClient()
        docker.unhealthy("app")

        val times = restartTimes(docker, config(monitorAll = true), cycles = 5, everyMs = 30_000)

        assertEquals(
            listOf(0L, 30_000L, 90_000L), times,
            "a container unhealthy because of its configuration never recovers: restarting it every " +
                "KODKOD_AUTOHEAL_INTERVAL forever only keeps resetting its healthcheck start_period, " +
                "which hides the real fault",
        )
    }

    @Test
    fun the_wait_between_restarts_doubles() {
        val docker = FakeDockerClient()
        docker.unhealthy("app")

        val times = restartTimes(docker, config(monitorAll = true), cycles = 300, everyMs = 1_000)

        assertEquals(
            listOf(0L, 30_000L, 90_000L, 210_000L), times,
            "gaps of 30s, 60s and 120s — the interval doubles with every restart that did not help",
        )
    }

    @Test
    fun the_wait_between_restarts_stops_growing_at_the_ceiling() {
        val docker = FakeDockerClient()
        docker.unhealthy("app")

        val times = restartTimes(docker, config(monitorAll = true, maxInterval = "60"), cycles = 300, everyMs = 1_000)

        assertEquals(
            listOf(0L, 30_000L, 90_000L, 150_000L, 210_000L, 270_000L), times,
            "KODKOD_AUTOHEAL_MAX_INTERVAL caps the growth: gaps of 30s, 60s, then 60s forever",
        )
    }

    @Test
    fun the_backoff_resets_once_the_container_is_healthy_again() {
        val docker = FakeDockerClient()
        docker.unhealthy("app")
        val clock = FakeClock()
        val autoheal = autoheal(docker, config(monitorAll = true), clock)

        autoheal.runOnce()
        clock.advance(30_000)
        autoheal.runOnce()
        // Recovered — and the daemon says so: the container is still there, reporting `healthy`.
        docker.health["app"] = "healthy"
        clock.advance(1_000)
        autoheal.runOnce()
        docker.health["app"] = "unhealthy"
        clock.advance(1_000)
        autoheal.runOnce()

        assertEquals(
            listOf("restart:app", "restart:app", "restart:app"), docker.ops,
            "the second restart bought a 60s window, but the container recovered in between — the next " +
                "unhealthy spell is a new problem and starts from the base interval: ${docker.ops}",
        )
    }

    /**
     * The bug this distinction exists for. `docker restart` resets the healthcheck to `starting`, so a
     * restarted container drops out of the `health=unhealthy` listing for as long as its `start_period`
     * and retries take — byte for byte the same signal a recovered container gives. Reading that as
     * recovery wiped the counter before the container was ever seen unhealthy again, which made the
     * whole backoff inert on the defaults: a restart every interval, forever.
     */
    @Test
    fun a_container_that_is_merely_starting_again_does_not_reset_the_backoff() {
        val docker = FakeDockerClient()
        docker.unhealthy("app")
        val clock = FakeClock()
        val autoheal = autoheal(docker, config(monitorAll = true), clock)

        autoheal.runOnce() // 0s: restart #1
        // The restart reset the healthcheck: `starting` is in neither the unhealthy nor the healthy list.
        docker.health["app"] = "starting"
        clock.advance(30_000)
        autoheal.runOnce()
        clock.advance(30_000)
        autoheal.runOnce()
        // The healthcheck finally gives its verdict — the restart did not fix anything.
        docker.health["app"] = "unhealthy"
        clock.advance(30_000)
        autoheal.runOnce() // 90s: restart #2, which buys a 60s window
        clock.advance(30_000)
        autoheal.runOnce() // 120s: still inside it

        assertEquals(
            listOf("restart:app", "restart:app"), docker.ops,
            "the restart at 90s is this container's second, so the next one is not due before 150s — " +
                "counting it as a first (because `starting` looked like recovery) would buy a fresh 30s " +
                "window and restart it again right here: ${docker.ops}",
        )
    }

    /**
     * The counter may not be kept forever either: a container that is in no listing at all — removed,
     * unlabelled, or simply stopped — is never going to produce the healthy sighting that resets it.
     */
    @Test
    fun a_container_that_disappears_for_good_is_forgotten_eventually() {
        val docker = FakeDockerClient()
        docker.unhealthy("app")
        val clock = FakeClock()
        val autoheal = autoheal(docker, config(monitorAll = true), clock)

        autoheal.runOnce() // restart #1
        docker.listed.clear() // removed from the daemon: in neither listing any more
        docker.health.clear()
        clock.advance(30_000)
        autoheal.runOnce()
        clock.advance(3_600_000) // a whole KODKOD_AUTOHEAL_MAX_INTERVAL of being gone
        autoheal.runOnce()
        // A container with the same id is back, and unhealthy — a new spell, not the old one.
        docker.unhealthy("app")
        clock.advance(1_000)
        autoheal.runOnce()
        clock.advance(30_000)
        autoheal.runOnce()

        assertEquals(
            3, docker.ops.size,
            "the entry was dropped, so the restart at 3631s counts as the first of a new spell and buys " +
                "the base 30s window — a remembered entry would have made it the second and bought 60s, " +
                "leaving only two restarts: ${docker.ops}",
        )
    }

    /**
     * The other half of that sweep: an absence that is *over* is not evidence of anything.
     *
     * A container in a bad spell does not read as unhealthy on every cycle — each restart resets its
     * healthcheck to `starting`, so what the listings show is unhealthy → absent → unhealthy → absent.
     * Carrying the first absence forward makes the "gone for a whole KODKOD_AUTOHEAL_MAX_INTERVAL" sweep
     * measure a gap that ended long ago: the counter is dropped in the middle of the spell, the next
     * restart counts as a first one, and the container is back to being restarted every base interval —
     * the exact behaviour the backoff exists to stop, and with nothing in the log to say so.
     */
    @Test
    fun an_absence_that_ended_does_not_age_the_backoff_out_from_under_the_container() {
        val docker = FakeDockerClient()
        docker.unhealthy("app")
        val clock = FakeClock()
        val autoheal = autoheal(docker, config(monitorAll = true, maxInterval = "120"), clock)

        autoheal.runOnce() // 0s: restart #1, which buys a 30s window
        // The restart reset the healthcheck: `starting` is in neither listing, so this is an absence.
        docker.health["app"] = "starting"
        clock.advance(10_000)
        autoheal.runOnce()
        docker.health["app"] = "unhealthy" // 20s: seen again — whatever that absence was, it is over
        clock.advance(10_000)
        autoheal.runOnce()
        docker.health["app"] = "starting" // 140s: absent again, 130s after the *first* absence began
        clock.advance(120_000)
        autoheal.runOnce()
        docker.health["app"] = "unhealthy"
        clock.advance(10_000)
        autoheal.runOnce() // 150s: restart #2, which buys 60s
        clock.advance(40_000)
        autoheal.runOnce() // 190s: still inside that window

        assertEquals(
            listOf("restart:app", "restart:app"), docker.ops,
            "the absence at 140s had just started, so the container is still the one that was restarted " +
                "at 0s and the restart at 150s is its second: measuring that absence from 10s instead " +
                "drops the counter, and the restart at 190s comes as the second of a fresh spell: ${docker.ops}",
        )
    }

    @Test
    fun counts_the_restarts_of_each_container_separately() {
        val docker = FakeDockerClient()
        docker.unhealthy("app")
        val clock = FakeClock()
        val autoheal = autoheal(docker, config(monitorAll = true), clock)

        autoheal.runOnce()
        docker.unhealthy("db")
        clock.advance(30_000)
        autoheal.runOnce()
        clock.advance(30_000)
        autoheal.runOnce()

        assertEquals(
            listOf("restart:app", "restart:app", "restart:db", "restart:db"), docker.ops,
            "at 60s `app` is on its second restart and held back, while `db` — unhealthy since 30s — is " +
                "still owed its second: one flapping container must not throttle another: ${docker.ops}",
        )
    }
}
/**
 * A [FakeDockerClient] whose [restart] of [target] reports a read timeout — the shape a
 * `stop_grace_period` longer than the socket's read timeout produces. The transport error carries no
 * HTTP status, which is what tells it apart from a daemon that said no.
 *
 * [carriedOut] is the half the caller cannot see and has to establish for itself: `true` is a daemon
 * that did the whole restart and only lost the answer, `false` a daemon that is still inside the
 * graceful stop — where the container is *still up from before*, which is why "is it running?" is not
 * the question.
 */
private class ReadTimeout(
    private val delegate: FakeDockerClient,
    private val target: String,
    private val carriedOut: Boolean = true,
) : DockerClient by delegate {
    override fun restart(id: String, timeout: Int?, expectedStopSeconds: Int?) {
        if (carriedOut || id != target) runCatching { delegate.restart(id, timeout, expectedStopSeconds) }
        if (id == target) throw DockerException(-1, "read timed out after 60000ms")
    }
}

/**
 * A [FakeDockerClient] whose [restart] of [target] reports a read timeout and whose daemon then takes
 * [landsAfterMs] to finish the job — a graceful stop that outlasts the socket by a long way, followed
 * by the start. Until that instant the container reads exactly as it did before the restart (up, from
 * its previous run), which is the only honest way to model a restart still in progress.
 */
private class LateRestart(
    private val delegate: FakeDockerClient,
    private val target: String,
    private val landsAfterMs: Long,
    private val clock: WallClock,
) : DockerClient by delegate {
    private var landsAt = Long.MAX_VALUE

    override fun restart(id: String, timeout: Int?, expectedStopSeconds: Int?) {
        if (id != target) {
            delegate.restart(id, timeout, expectedStopSeconds)
            return
        }
        landsAt = clock.millis() + landsAfterMs
        delegate.ops += "restart!:$id"
        throw DockerException(-1, "read timed out after 60000ms")
    }

    override fun inspectContainer(id: String): JsonObject {
        val inspect = delegate.inspectContainer(id)
        if (id != target || clock.millis() < landsAt) return inspect
        return buildJsonObject {
            inspect.forEach { (key, value) -> if (key != "State") put(key, value) }
            put(
                "State",
                buildJsonObject {
                    put("Running", true)
                    put("StartedAt", Instant.ofEpochMilli(landsAt).toString())
                },
            )
        }
    }
}

/** A wall-clock instant the autoheal tests run "now" at — anything but the epoch, which reads as unset. */
private val NOW = Instant.parse("2026-07-01T12:00:00Z").toEpochMilli()

/** `State.StartedAt` of a container that has been up since long before this cycle. */
private const val LONG_AGO = "2026-07-01T10:00:00.000000000Z"

/**
 * Ids long enough for the daemon's own prefix matching to have something to work with — a `container:`
 * reference may be spelled as a 12-character short id, and an id of five characters cannot be one.
 */
private const val APP = "app000000000000"
private const val SIDE = "side00000000000"
