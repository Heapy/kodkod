package io.heapy.kodkod

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.system.exitProcess

/**
 * kodkod — a small docker-compose companion that
 *   1. restarts unhealthy containers, and
 *   2. updates containers when a newer image is published.
 *
 * Both jobs talk straight to the Docker Engine API over the unix socket and are opt-in per
 * container via labels (see [Config] and the README).
 */
fun main() {
    val config = Config.fromEnv()
    Log.info("kodkod starting")
    Log.info("docker socket   : ${config.dockerSocket}")
    Log.info("label namespace : ${config.labelNamespace}")
    Log.info(
        "autoheal        : enabled=${config.autohealEnabled} interval=${config.autohealInterval}s " +
            "monitorAll=${config.autohealMonitorAll}",
    )
    Log.info(
        "update          : enabled=${config.updateEnabled} interval=${config.updateInterval}s " +
            "monitorAll=${config.updateMonitorAll} cleanup=${config.updateCleanup}",
    )

    // Even with both loops off there is one thing left to do: recover a container a previous kodkod
    // left parked under its `_kodkod_old_` backup name. That is a service that is down right now, and
    // switching the updater off after being burned by it is exactly when it needs recovering.
    val nothingScheduled = !config.autohealEnabled && !config.updateEnabled

    val api = DockerApi(config.dockerSocket)
    try {
        val version = api.version()
        Log.info("connected to docker engine (version ${version.str("Version")}, API ${version.str("ApiVersion")})")
    } catch (e: Exception) {
        Log.error("cannot reach docker at ${config.dockerSocket}: ${e.message}")
        // With nothing scheduled, an unreachable daemon leaves nothing undone — exiting cleanly beats
        // a restart loop over a process that was asked to do nothing anyway.
        if (nothingScheduled) return
        exitProcess(1)
    }

    // Docker sets HOSTNAME to the container's short id; used to avoid acting on ourselves.
    val selfId = System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() }
    val autoheal = Autoheal(api, config, selfId)
    val updater = Updater(api, config, selfId)
    val cycleLock = ReentrantLock()

    val scheduler = Executors.newScheduledThreadPool(2) { runnable ->
        Thread(runnable, "kodkod-worker").apply { isDaemon = true }
    }

    // Before anything is scheduled, and deliberately not gated on `config.updateEnabled`: a container
    // left parked under its `_kodkod_old_` backup name by a previous process that died mid-recreate is
    // down *right now*, and with the updater switched off no cycle would ever come looking for it.
    guarded("reconcile", cycleLock, updater::reconcileOrphanedBackups).run()

    if (nothingScheduled) {
        Log.warn("both autoheal and update are disabled — nothing left to do after the reconcile, exiting")
        return
    }

    // scheduleWithFixedDelay prevents a job from overlapping with itself; the shared lock also
    // serializes autoheal and update cycles so restart/recreate operations cannot race.
    if (config.autohealEnabled) {
        scheduler.scheduleWithFixedDelay(
            guarded("autoheal", cycleLock, autoheal::runOnce),
            config.autohealStartPeriod, config.autohealInterval, TimeUnit.SECONDS,
        )
    }
    if (config.updateEnabled) {
        scheduler.scheduleWithFixedDelay(
            updateCycle(cycleLock, updater),
            config.updateStartPeriod, config.updateInterval, TimeUnit.SECONDS,
        )
    }

    val shutdown = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            Log.info("kodkod stopping — giving the cycle in flight up to ${config.shutdownGrace}s to finish")
            stopScheduler(scheduler, config.shutdownGrace)
            shutdown.countDown()
        },
    )
    shutdown.await()
}

/**
 * Stop [scheduler], in two waits.
 *
 * The first is [graceSeconds] (`KODKOD_SHUTDOWN_GRACE`), because a cycle in flight may be between
 * renaming the old container away and starting its replacement, where nothing is serving the name;
 * interrupting it there is what leaves an orphaned backup behind.
 *
 * The second is what makes the interrupt survivable when the first one runs out. `Updater.apply` stops
 * the whole set it is going to touch before bringing any of it back, and unwinds through a pass that
 * starts again whatever it had stopped (`bringBackWhatIsStillDown`) — but that pass is Docker calls, and
 * it only happens if the process is still alive to make them. Without this wait the hook returns the
 * moment it has interrupted the worker, `main` returns, and the JVM exits out from under a recovery that
 * had barely begun, leaving containers stopped under their own names that no later cycle will list.
 *
 * Both waits sit inside the operator's own deadline: Docker sends `SIGKILL` 10s after `SIGTERM` unless
 * the container's `stop_grace_period` says otherwise, and no amount of waiting here survives that.
 *
 * @return whether the cycle finished on its own, without being interrupted.
 */
internal fun stopScheduler(scheduler: ExecutorService, graceSeconds: Long): Boolean {
    scheduler.shutdown()
    if (awaitTermination(scheduler, graceSeconds)) return true
    Log.warn("the cycle in flight did not finish within ${graceSeconds}s — interrupting it")
    scheduler.shutdownNow()
    if (!awaitTermination(scheduler, UNWIND_GRACE_SECONDS)) {
        Log.error(
            "the interrupted cycle did not unwind within ${UNWIND_GRACE_SECONDS}s — a container it had " +
                "stopped may be left stopped, under its own name, where no later cycle will look for it " +
                "(discovery only lists running containers)",
        )
    }
    return false
}

/**
 * How long an interrupted cycle is given to put back what it had stopped. It is a handful of `start`
 * calls against a local socket, not a wait on anything: long enough that a busy daemon can answer them,
 * short enough to stay inside a `stop_grace_period` an operator has already stretched once for
 * `KODKOD_SHUTDOWN_GRACE`.
 */
private const val UNWIND_GRACE_SECONDS = 5L

/** [ExecutorService.awaitTermination], with an interrupt of our own reading as "it did not finish". */
private fun awaitTermination(scheduler: ExecutorService, seconds: Long): Boolean =
    try {
        scheduler.awaitTermination(seconds, TimeUnit.SECONDS)
    } catch (_: InterruptedException) {
        false
    }

/**
 * One update cycle, in two halves, and only the second one under [cycleLock].
 *
 * All of a cycle's waiting is in the first half: `api.pull` is granted ten minutes of idle time per
 * image by design (see [DockerApi.pull]), and a registry that answers slowly (or not at all) stretches
 * that across every stale container in the stack. Holding the lock through it meant autoheal could not
 * restart an unhealthy container for minutes at a time, waiting in `lockInterruptibly()` with nothing
 * logged to say why. Planning touches no container, so it does not need the lock at all.
 *
 * What [Updater.apply] holds the lock for is bounded by configuration rather than by a registry:
 * the graceful stops (`KODKOD_STOP_TIMEOUT` or each container's own `StopTimeout`), the liveness gate
 * after every `start` (`KODKOD_UPDATE_VERIFY_SECONDS`) and each `service_healthy` dependency edge
 * (`KODKOD_DEPENDENCY_HEALTH_TIMEOUT`) — all per container, all finite, none of them a network fetch.
 *
 * A plan that could not be built degrades to an empty one instead of skipping the second half: the
 * reconcile of orphaned backups lives there, and a daemon that failed a listing is no reason to leave a
 * service parked under its backup name for another interval. The plan is re-checked against the daemon
 * inside [Updater.apply], since the state it was built from may have moved while an image downloaded.
 */
internal fun updateCycle(cycleLock: ReentrantLock, updater: Updater): Runnable = Runnable {
    val plan = try {
        updater.plan()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        UpdatePlan.NOTHING
    } catch (e: Throwable) {
        Log.error("[update] planning failed: ${e.message}")
        UpdatePlan.NOTHING
    }
    guarded("update", cycleLock) { updater.apply(plan) }.run()
}

/** Wrap a cycle so a thrown exception is logged instead of cancelling the scheduled task. */
private fun guarded(name: String, cycleLock: ReentrantLock, task: () -> Unit): Runnable = Runnable {
    var locked = false
    try {
        cycleLock.lockInterruptibly()
        locked = true
        task()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    } catch (e: Throwable) {
        Log.error("[$name] cycle failed: ${e.message}")
    } finally {
        if (locked) cycleLock.unlock()
    }
}
