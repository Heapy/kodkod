package io.heapy.kodkod

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The injected clock is what makes the liveness gate, cooldowns and backoff testable at all, so its
 * two properties are worth pinning down: virtual time never moves on its own, and the production
 * default really is the wall clock.
 */
class TimeTest {
    @Test
    fun the_fake_clock_only_moves_when_the_test_moves_it() {
        val clock = FakeClock()

        assertEquals(0L, clock.millis())
        Thread.yield()
        assertEquals(0L, clock.millis(), "real time passing must not move virtual time")

        clock.advance(5_000)
        assertEquals(5_000L, clock.millis())
    }

    @Test
    fun sleeping_records_the_wait_and_advances_virtual_time() {
        val clock = FakeClock(now = 1_000)

        val start = System.nanoTime()
        clock.sleep(60_000)
        clock.sleep(500)
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000

        assertEquals(listOf(60_000L, 500L), clock.sleeps)
        assertEquals(61_500L, clock.millis())
        assertTrue(elapsedMillis < 1_000, "a minute of virtual sleep must not cost real time (took ${elapsedMillis}ms)")
    }

    @Test
    fun the_system_defaults_are_the_real_clock() {
        val before = System.currentTimeMillis()
        val reading = TimeSource.SYSTEM.millis()
        assertTrue(reading >= before, "the default time source reads the wall clock (got $reading, now $before)")

        val start = System.nanoTime()
        Sleeper.SYSTEM.sleep(5)
        Sleeper.SYSTEM.sleep(0)
        assertTrue(System.nanoTime() - start >= 5_000_000, "the default sleeper really waits")
    }

    @Test
    fun the_cycles_accept_an_injected_clock() {
        val docker = FakeDockerClient()
        val clock = FakeClock()

        Updater(docker, Config.fromEnv(emptyMap<String, String>()::get), selfId = null, clock, clock).runOnce()
        Autoheal(docker, Config.fromEnv(emptyMap<String, String>()::get), selfId = null, clock, clock).runOnce()

        assertTrue(docker.ops.isEmpty(), "nothing to do on an empty daemon: ${docker.ops}")
        assertTrue(clock.sleeps.isEmpty(), "no cycle waits on an empty daemon: ${clock.sleeps}")
    }
}
