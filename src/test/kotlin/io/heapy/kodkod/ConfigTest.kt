package io.heapy.kodkod

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigTest {
    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { map[it] }
    }

    @Test
    fun applies_defaults_when_unset() {
        val c = Config.fromEnv { null }
        assertEquals("/var/run/docker.sock", c.dockerSocket)
        assertEquals("kodkod", c.labelNamespace)
        assertEquals(10, c.defaultStopTimeout)
        assertTrue(c.autohealEnabled)
        assertEquals(30, c.autohealInterval)
        assertEquals(3600, c.updateInterval)
        assertFalse(c.autohealMonitorAll)
        assertTrue(c.updateCleanup)
        assertNull(c.registryAuth)
    }

    @Test
    fun parses_truthy_and_falsey_values() {
        assertFalse(Config.fromEnv(env("KODKOD_AUTOHEAL_ENABLED" to "false")).autohealEnabled)
        assertTrue(Config.fromEnv(env("KODKOD_UPDATE_MONITOR_ALL" to "yes")).updateMonitorAll)
        assertTrue(Config.fromEnv(env("KODKOD_UPDATE_MONITOR_ALL" to "1")).updateMonitorAll)
        assertFalse(Config.fromEnv(env("KODKOD_UPDATE_MONITOR_ALL" to "nope")).updateMonitorAll)
    }

    @Test
    fun rejects_non_positive_intervals() {
        assertThrows(IllegalArgumentException::class.java) { Config.fromEnv(env("KODKOD_AUTOHEAL_INTERVAL" to "0")) }
        assertThrows(IllegalArgumentException::class.java) { Config.fromEnv(env("KODKOD_UPDATE_INTERVAL" to "-5")) }
    }
}
