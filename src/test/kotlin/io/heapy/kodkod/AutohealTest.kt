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

    private fun config(monitorAll: Boolean = true, stopTimeout: String? = null): Config =
        Config.fromEnv(
            buildMap {
                put("KODKOD_AUTOHEAL_MONITOR_ALL", monitorAll.toString())
                stopTimeout?.let { put("KODKOD_STOP_TIMEOUT", it) }
            }::get,
        )

    @Test
    fun restarts_an_unhealthy_container() {
        val docker = FakeDockerClient()
        docker.listed += json("""{"Id":"app","Names":["/app"],"State":"running","Labels":{}}""")

        Autoheal(docker, config(monitorAll = true), selfId = null).runOnce()

        assertEquals(listOf("restart:app"), docker.ops)
    }

    @Test
    fun without_an_override_the_container_decides_its_own_stop_timeout() {
        val docker = FakeDockerClient()
        docker.listed += json("""{"Id":"app","Names":["/app"],"State":"running","Labels":{}}""")

        Autoheal(docker, config(monitorAll = true), selfId = null).runOnce()

        assertEquals(
            listOf<Int?>(null), docker.restartTimeouts,
            "with no label and no KODKOD_STOP_TIMEOUT nothing may be sent — the daemon applies Config.StopTimeout",
        )
    }

    @Test
    fun the_label_overrides_the_stop_timeout() {
        val docker = FakeDockerClient()
        docker.listed += json("""{"Id":"app","Names":["/app"],"State":"running","Labels":{"kodkod.stop.timeout":"45"}}""")

        Autoheal(docker, config(monitorAll = true, stopTimeout = "25"), selfId = null).runOnce()

        assertEquals(listOf<Int?>(45), docker.restartTimeouts, "the label beats the env default")
    }

    @Test
    fun the_env_default_overrides_the_stop_timeout() {
        val docker = FakeDockerClient()
        docker.listed += json("""{"Id":"app","Names":["/app"],"State":"running","Labels":{}}""")

        Autoheal(docker, config(monitorAll = true, stopTimeout = "25"), selfId = null).runOnce()

        assertEquals(listOf<Int?>(25), docker.restartTimeouts, "an explicitly set KODKOD_STOP_TIMEOUT is an override")
    }

    @Test
    fun skips_a_container_that_is_already_restarting() {
        val docker = FakeDockerClient()
        docker.listed += json("""{"Id":"app","Names":["/app"],"State":"restarting","Labels":{}}""")

        Autoheal(docker, config(monitorAll = true), selfId = null).runOnce()

        assertTrue(docker.ops.isEmpty(), "a container Docker is already restarting should be left alone: ${docker.ops}")
    }

    @Test
    fun never_restarts_itself() {
        val docker = FakeDockerClient()
        docker.listed += json("""{"Id":"self","Names":["/self"],"State":"running","Labels":{"$SELF_LABEL":"true"}}""")

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
}