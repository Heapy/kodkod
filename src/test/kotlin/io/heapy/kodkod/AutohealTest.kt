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
}