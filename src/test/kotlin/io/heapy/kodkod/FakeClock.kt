package io.heapy.kodkod

/**
 * A [WallClock] and [Sleeper] whose clock moves only when the test moves it — by hand via [advance],
 * or because the code under test slept. Every sleep is recorded in [sleeps], so a test can assert
 * *how long* the code waited (and that it waited at all) without spending that time.
 */
class FakeClock(private var now: Long = 0L) : WallClock, Sleeper {
    /** Durations passed to [sleep], in call order. */
    val sleeps = mutableListOf<Long>()

    override fun millis(): Long = now

    override fun sleep(millis: Long) {
        sleeps += millis
        if (millis > 0) now += millis
    }

    /** Move the clock forward without the code under test having asked to wait. */
    fun advance(millis: Long) {
        now += millis
    }
}
