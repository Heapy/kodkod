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
        assertEquals(5_000_000_000L, clock.nanos(), "both hands show the same virtual instant")
    }

    /**
     * The two hands mean different things: durations are measured with [WallClock.nanos] precisely
     * because [WallClock.millis] can be corrected under a running process, and only the latter is an
     * instant anybody can print.
     */
    @Test
    fun the_production_clock_is_a_wall_clock_and_a_monotonic_one() {
        val first = WallClock.SYSTEM.nanos()
        val second = WallClock.SYSTEM.nanos()

        assertTrue(second >= first, "elapsed time never runs backwards ($first -> $second)")
        assertTrue(
            WallClock.SYSTEM.millis() > 1_600_000_000_000L,
            "and millis() is still an instant since the epoch, which is what the log lines print",
        )
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
