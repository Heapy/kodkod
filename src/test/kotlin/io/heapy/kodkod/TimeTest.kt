package io.heapy.kodkod

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
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

    /**
     * A minute of waiting is recorded and paid in virtual time only. There is deliberately no
     * wall-clock bound here: that would be a threshold this test passes or fails by how busy the
     * machine is, and a [FakeClock] that really slept would blow the runtime of the whole suite —
     * which is a far louder signal than one assertion on a stopwatch.
     */
    @Test
    fun sleeping_records_the_wait_and_advances_virtual_time() {
        val clock = FakeClock(now = 1_000)

        clock.sleep(60_000)
        clock.sleep(500)

        assertEquals(listOf(60_000L, 500L), clock.sleeps, "every wait is recorded, in order")
        assertEquals(61_500L, clock.millis(), "and paid out of virtual time, from where the clock started")
    }

    /**
     * A non-positive duration must not block, and every retry loop passes one at least once. What that
     * takes is a *guard*: `Thread.sleep(-1)` throws `IllegalArgumentException`, which inside a liveness
     * probe or a start retry would abort the loop it is meant to pace. So the assertion is that the
     * call returns at all, not how quickly it did.
     */
    @Test
    fun the_default_sleeper_returns_at_once_on_a_non_positive_wait() {
        assertDoesNotThrow {
            Sleeper.SYSTEM.sleep(0)
            Sleeper.SYSTEM.sleep(-1)
        }
    }
}
