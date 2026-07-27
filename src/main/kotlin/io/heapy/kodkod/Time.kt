package io.heapy.kodkod

/**
 * The clock kodkod reads, injected into [Updater] and [Autoheal] so that time-dependent behaviour —
 * the liveness gate after a start, the cooldown on an image that failed to come up, autoheal backoff —
 * is driven by the test rather than by real elapsed seconds. Production always gets [SYSTEM].
 */
fun interface WallClock {
    /** Milliseconds since the epoch, with the same meaning as [System.currentTimeMillis]. */
    fun millis(): Long

    companion object {
        /** The real wall clock. */
        val SYSTEM: WallClock = WallClock { System.currentTimeMillis() }
    }
}

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
