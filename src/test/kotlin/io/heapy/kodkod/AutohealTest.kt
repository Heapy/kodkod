package io.heapy.kodkod

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Exercises [Autoheal.runOnce] against a [FakeDockerClient] — no Docker daemon required. */
class AutohealTest {
    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    /**
     * A `/containers/json` summary as the daemon returns it: compose stamps its project on every
     * service, and `HostConfig.NetworkMode` is `container:<id>` for a shared network namespace.
     */
    private fun summary(
        id: String,
        name: String,
        netnsOf: String? = null,
        project: String? = "stack",
        labels: String = "",
        state: String = "running",
    ): JsonObject {
        val projectLabel = project?.let { """"com.docker.compose.project":"$it"""" }
        val allLabels = listOfNotNull(projectLabel, labels.takeIf { it.isNotEmpty() }).joinToString(",")
        val mode = netnsOf?.let { "container:$it" } ?: "stack_default"
        return json(
            """{"Id":"$id","Names":["/$name"],"State":"$state","Labels":{$allLabels},
               "HostConfig":{"NetworkMode":"$mode"}}""",
        )
    }

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
        listed += json("""{"Id":"$id","Names":["/$name"],"State":"$state","Labels":$labels}""")
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

    private fun autoheal(docker: FakeDockerClient, config: Config, clock: FakeClock) =
        Autoheal(docker, config, selfId = null, clock = clock, sleeper = clock)

    /**
     * Runs [cycles] autoheal cycles [everyMs] apart on a fake clock and returns the clock time of every
     * restart of [id] — the shape the "30s, then 60s, then 120s" of the backoff is stated in. The
     * container stays unhealthy the whole time, which is exactly the case a restart cannot fix.
     */
    private fun restartTimes(
        docker: FakeDockerClient,
        config: Config,
        cycles: Int,
        everyMs: Long,
        id: String = "app",
    ): List<Long> {
        val clock = FakeClock()
        val autoheal = autoheal(docker, config, clock)
        val times = mutableListOf<Long>()
        repeat(cycles) {
            val before = docker.ops.count { it == "restart:$id" }
            autoheal.runOnce()
            if (docker.ops.count { it == "restart:$id" } > before) times += clock.millis()
            clock.advance(everyMs)
        }
        return times
    }

    @Test
    fun restarts_an_unhealthy_container() {
        val docker = FakeDockerClient()
        docker.unhealthy("app")

        Autoheal(docker, config(monitorAll = true), selfId = null).runOnce()

        assertEquals(listOf("restart:app"), docker.ops)
    }

    @Test
    fun without_an_override_the_container_decides_its_own_stop_timeout() {
        val docker = FakeDockerClient()
        docker.unhealthy("app")

        Autoheal(docker, config(monitorAll = true), selfId = null).runOnce()

        assertEquals(
            listOf<Int?>(null), docker.restartTimeouts,
            "with no label and no KODKOD_STOP_TIMEOUT nothing may be sent — the daemon applies Config.StopTimeout",
        )
    }

    @Test
    fun the_label_overrides_the_stop_timeout() {
        val docker = FakeDockerClient()
        docker.unhealthy("app", labels = """{"kodkod.stop.timeout":"45"}""")

        Autoheal(docker, config(monitorAll = true, stopTimeout = "25"), selfId = null).runOnce()

        assertEquals(listOf<Int?>(45), docker.restartTimeouts, "the label beats the env default")
    }

    @Test
    fun the_env_default_overrides_the_stop_timeout() {
        val docker = FakeDockerClient()
        docker.unhealthy("app")

        Autoheal(docker, config(monitorAll = true, stopTimeout = "25"), selfId = null).runOnce()

        assertEquals(listOf<Int?>(25), docker.restartTimeouts, "an explicitly set KODKOD_STOP_TIMEOUT is an override")
    }

    @Test
    fun skips_a_container_that_is_already_restarting() {
        val docker = FakeDockerClient()
        docker.unhealthy("app", state = "restarting")

        Autoheal(docker, config(monitorAll = true), selfId = null).runOnce()

        assertTrue(docker.ops.isEmpty(), "a container Docker is already restarting should be left alone: ${docker.ops}")
    }

    @Test
    fun never_restarts_itself() {
        val docker = FakeDockerClient()
        docker.unhealthy("self", labels = """{"$SELF_LABEL":"true"}""")

        Autoheal(docker, config(monitorAll = true), selfId = null).runOnce()

        assertTrue(docker.ops.isEmpty(), "kodkod must never restart its own container: ${docker.ops}")
    }

    @Test
    fun restarts_the_containers_sharing_the_unhealthy_container_s_network_namespace() {
        val docker = FakeDockerClient()
        docker.listed += summary("app000000000000", "app", netnsOf = null)
        docker.listed += summary("side00000000000", "sidecar", netnsOf = "app000000000000")
        docker.health["app000000000000"] = "unhealthy"
        docker.health["side00000000000"] = "healthy"

        Autoheal(docker, config(monitorAll = true), selfId = null).runOnce()

        assertEquals(
            listOf("restart:app000000000000", "restart:side00000000000"), docker.ops,
            "a plain restart leaves a netns consumer without eth0 while it still reports Running — " +
                "it has to be restarted after its provider",
        )
    }

    @Test
    fun restarts_a_consumer_that_is_not_monitored_itself() {
        val docker = FakeDockerClient()
        docker.listed += summary("app000000000000", "app", labels = """"kodkod.autoheal.enable":"true"""")
        docker.listed += summary("side00000000000", "sidecar", netnsOf = "app000000000000")
        docker.health["app000000000000"] = "unhealthy"
        docker.health["side00000000000"] = "healthy"

        Autoheal(docker, config(monitorAll = false), selfId = null).runOnce()

        assertEquals(
            listOf("restart:app000000000000", "restart:side00000000000"), docker.ops,
            "an unlabelled sidecar is exactly the one nothing else will notice is broken",
        )
    }

    @Test
    fun leaves_a_consumer_that_is_not_running_alone() {
        val docker = FakeDockerClient()
        docker.listed += summary("app000000000000", "app")
        docker.listed += summary("side00000000000", "sidecar", netnsOf = "app000000000000", state = "exited")
        docker.health["app000000000000"] = "unhealthy"

        Autoheal(docker, config(monitorAll = true), selfId = null).runOnce()

        assertEquals(
            listOf("restart:app000000000000"), docker.ops,
            "a stopped consumer has no namespace to lose — starting it is not autoheal's call: ${docker.ops}",
        )
    }

    @Test
    fun does_not_restart_consumers_when_the_provider_itself_failed_to_restart() {
        val docker = FakeDockerClient()
        docker.listed += summary("app000000000000", "app")
        docker.listed += summary("side00000000000", "sidecar", netnsOf = "app000000000000")
        docker.health["app000000000000"] = "unhealthy"
        docker.health["side00000000000"] = "healthy"
        docker.failRestart += "app000000000000"

        Autoheal(docker, config(monitorAll = true), selfId = null).runOnce()

        assertEquals(
            listOf("restart!:app000000000000"), docker.ops,
            "restarting the consumer of a namespace that never came back only spreads the outage: ${docker.ops}",
        )
    }

    @Test
    fun never_restarts_itself_as_a_consumer() {
        val docker = FakeDockerClient()
        docker.listed += summary("app000000000000", "app")
        docker.listed += summary(
            "self00000000000", "kodkod",
            netnsOf = "app000000000000",
            labels = """"$SELF_LABEL":"true"""",
        )
        docker.health["app000000000000"] = "unhealthy"
        docker.health["self00000000000"] = "healthy"

        Autoheal(docker, config(monitorAll = true), selfId = null).runOnce()

        assertEquals(
            listOf("restart:app000000000000"), docker.ops,
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
        docker.listed += summary("app000000000000", "app")
        docker.listed += summary("side00000000000", "sidecar", netnsOf = "app000000000000")
        docker.health["app000000000000"] = "unhealthy"
        docker.health["side00000000000"] = "healthy"
        docker.containers["app000000000000"] = json("""{"Name":"/app"}""")
        val clock = FakeClock()

        Autoheal(ReadTimeout(docker, "app000000000000"), config(monitorAll = true), null, clock, clock).runOnce()

        assertEquals(
            listOf("restart:app000000000000", "restart:side00000000000"), docker.ops,
            "the daemon restarted it; only the answer was lost, and the sidecar has no eth0 either way: ${docker.ops}",
        )
    }

    @Test
    fun a_restart_that_really_did_not_happen_leaves_the_dependents_alone() {
        val docker = FakeDockerClient()
        docker.listed += summary("app000000000000", "app")
        docker.listed += summary("side00000000000", "sidecar", netnsOf = "app000000000000")
        docker.health["app000000000000"] = "unhealthy"
        docker.health["side00000000000"] = "healthy"
        // Registered as stopped, and it stays that way: the read-back finds nothing running.
        docker.containers["app000000000000"] = json("""{"Name":"/app","State":{"Running":false}}""")
        docker.failRestart += "app000000000000"
        val clock = FakeClock()

        Autoheal(ReadTimeout(docker, "app000000000000"), config(monitorAll = true), null, clock, clock).runOnce()

        assertEquals(
            listOf("restart!:app000000000000"), docker.ops,
            "restarting the consumers of a namespace that never came back only spreads the outage: ${docker.ops}",
        )
        assertTrue(clock.sleeps.isNotEmpty(), "the outcome has to have been waited for, not assumed: ${clock.sleeps}")
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
 * A [FakeDockerClient] whose [restart] of [target] does exactly what the daemon does and *then* reports
 * a read timeout — the shape a `stop_grace_period` longer than the socket's read timeout produces. The
 * transport error carries no HTTP status, which is what tells it apart from a daemon that said no.
 */
private class ReadTimeout(
    private val delegate: FakeDockerClient,
    private val target: String,
) : DockerClient by delegate {
    override fun restart(id: String, timeout: Int?, expectedStopSeconds: Int?) {
        runCatching { delegate.restart(id, timeout, expectedStopSeconds) }
        if (id == target) throw DockerException(-1, "read timed out after 60000ms")
    }
}
