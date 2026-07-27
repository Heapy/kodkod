package io.heapy.kodkod

/**
 * The clock kodkod reads, injected into [Updater] and [Autoheal] so that time-dependent behaviour —
 * the liveness gate after a start, the cooldown on an image that failed to come up, autoheal backoff —
 * is driven by the test rather than by real elapsed seconds. Production always gets [SYSTEM].
 *
 * It has two hands, and they are not interchangeable. [millis] is the wall clock and is read **only**
 * where an absolute instant is printed ("next attempt no earlier than ...") or compared against one the
 * daemon reported (`State.StartedAt`). Every *duration* — every deadline, every backoff window — is
 * measured with [nanos] instead, because the wall clock is not monotonic: an NTP step backwards during
 * the liveness gate extends it by the size of the step while `apply` holds the cycle lock, blocking
 * autoheal and the updater alike, and a step forwards ends the window early and accepts a replacement
 * that is still dying — just before the container and image it replaced are destroyed.
 */
interface WallClock {
    /** Milliseconds since the epoch, with the same meaning as [System.currentTimeMillis]. */
    fun millis(): Long

    /** A monotonic reading in nanoseconds, with the same meaning as [System.nanoTime]. */
    fun nanos(): Long

    companion object {
        /** The real clock: the wall clock for instants, [System.nanoTime] for everything measured. */
        val SYSTEM: WallClock = object : WallClock {
            override fun millis(): Long = System.currentTimeMillis()

            override fun nanos(): Long = System.nanoTime()
        }
    }
}

/** [WallClock.nanos] of a duration given in seconds, the unit every kodkod window is configured in. */
internal fun secondsToNanos(seconds: Long): Long = seconds * 1_000_000_000L

/** [WallClock.nanos] of a duration given in milliseconds. */
internal fun millisToNanos(millis: Long): Long = millis * 1_000_000L

/**
 * Pauses the calling thread. Injected alongside [WallClock] so that polling loops (liveness probes,
 * retry backoff) cost nothing under test while still being expressed as ordinary blocking waits.
 */
fun interface Sleeper {
    /** Block for [millis]; a non-positive duration returns immediately. */
    fun sleep(millis: Long)

    companion object {
        /** The real [Thread.sleep]. */
        val SYSTEM: Sleeper = Sleeper { millis -> if (millis > 0) Thread.sleep(millis) }
    }
}
