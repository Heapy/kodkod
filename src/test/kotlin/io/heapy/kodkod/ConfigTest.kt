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
        assertNull(c.defaultStopTimeout, "unset means \"no opinion\": each container's own Config.StopTimeout applies")
        assertTrue(c.autohealEnabled)
        assertEquals(30, c.autohealInterval)
        assertEquals(3600, c.updateInterval)
        assertFalse(c.autohealMonitorAll)
        assertTrue(c.updateCleanup)
        assertEquals(15, c.updateVerifySeconds)
        assertTrue(c.updateVerifyHealth)
        assertEquals(21600, c.updateFailureCooldown)
        assertNull(c.registryAuth)
        assertEquals(30, c.shutdownGrace)
    }

    @Test
    fun reads_the_shutdown_grace_period() {
        assertEquals(5, Config.fromEnv(env("KODKOD_SHUTDOWN_GRACE" to "5")).shutdownGrace)
        assertEquals(
            0, Config.fromEnv(env("KODKOD_SHUTDOWN_GRACE" to "0")).shutdownGrace,
            "0 is a legal grace period: interrupt the cycle in flight immediately",
        )
        assertEquals(
            0, Config.fromEnv(env("KODKOD_SHUTDOWN_GRACE" to "-1")).shutdownGrace,
            "a negative grace period cannot mean \"wait backwards\" — it degrades to no wait at all",
        )
        assertEquals(
            30, Config.fromEnv(env("KODKOD_SHUTDOWN_GRACE" to "soon")).shutdownGrace,
            "an unparseable value falls back to the default rather than to no grace period at all",
        )
    }

    @Test
    fun reads_the_liveness_verification_window() {
        assertEquals(45, Config.fromEnv(env("KODKOD_UPDATE_VERIFY_SECONDS" to "45")).updateVerifySeconds)
        assertEquals(
            0, Config.fromEnv(env("KODKOD_UPDATE_VERIFY_SECONDS" to "0")).updateVerifySeconds,
            "0 is a legal window: probe the replacement once and move on",
        )
        assertEquals(
            0, Config.fromEnv(env("KODKOD_UPDATE_VERIFY_SECONDS" to "-5")).updateVerifySeconds,
            "a negative window cannot mean \"wait backwards\" — it degrades to a single probe",
        )
        assertEquals(
            15, Config.fromEnv(env("KODKOD_UPDATE_VERIFY_SECONDS" to "soon")).updateVerifySeconds,
            "unparseable values fall back to the default rather than disabling the gate",
        )
        assertFalse(Config.fromEnv(env("KODKOD_UPDATE_VERIFY_HEALTH" to "false")).updateVerifyHealth)
    }

    @Test
    fun reads_the_failed_update_cooldown() {
        assertEquals(60, Config.fromEnv(env("KODKOD_UPDATE_FAILURE_COOLDOWN" to "60")).updateFailureCooldown)
        assertEquals(
            0, Config.fromEnv(env("KODKOD_UPDATE_FAILURE_COOLDOWN" to "0")).updateFailureCooldown,
            "0 is the off switch: retry a known-bad image every cycle, as kodkod did before it remembered",
        )
        assertEquals(
            0, Config.fromEnv(env("KODKOD_UPDATE_FAILURE_COOLDOWN" to "-1")).updateFailureCooldown,
            "a negative cooldown cannot mean \"wait backwards\" — it degrades to no memory at all",
        )
        assertEquals(
            21600, Config.fromEnv(env("KODKOD_UPDATE_FAILURE_COOLDOWN" to "a while")).updateFailureCooldown,
            "an unparseable value falls back to the default rather than to retrying an outage every cycle",
        )
    }

    @Test
    fun keeps_an_explicitly_set_stop_timeout() {
        assertEquals(25, Config.fromEnv(env("KODKOD_STOP_TIMEOUT" to "25")).defaultStopTimeout)
        assertNull(Config.fromEnv(env("KODKOD_STOP_TIMEOUT" to "nonsense")).defaultStopTimeout)
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
