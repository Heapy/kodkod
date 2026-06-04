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

    private fun config(monitorAll: Boolean = true): Config =
        Config.fromEnv(mapOf("KODKOD_AUTOHEAL_MONITOR_ALL" to monitorAll.toString())::get)

    @Test
    fun restarts_an_unhealthy_container() {
        val docker = FakeDockerClient()
        docker.listed += json("""{"Id":"app","Names":["/app"],"State":"running","Labels":{}}""")

        Autoheal(docker, config(monitorAll = true), selfId = null).runOnce()

        assertEquals(listOf("restart:app"), docker.ops)
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