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

    /** A non-positive duration must not block; every retry loop passes one at least once. */
    @Test
    fun the_default_sleeper_returns_at_once_on_a_non_positive_wait() {
        val start = System.nanoTime()
        Sleeper.SYSTEM.sleep(0)
        Sleeper.SYSTEM.sleep(-1)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 500, "a zero-length sleep must not wait")
    }
}
