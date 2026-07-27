package io.heapy.kodkod

import java.util.concurrent.CountDownLatch
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

    if (!config.autohealEnabled && !config.updateEnabled) {
        Log.warn("both autoheal and update are disabled — nothing to do, exiting")
        return
    }

    val api = DockerApi(config.dockerSocket)
    try {
        val version = api.version()
        Log.info("connected to docker engine (version ${version.str("Version")}, API ${version.str("ApiVersion")})")
    } catch (e: Exception) {
        Log.error("cannot reach docker at ${config.dockerSocket}: ${e.message}")
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
            // A cycle in flight may be between renaming the old container away and starting its
            // replacement, where nothing is serving the name; interrupting it there is what leaves an
            // orphaned backup behind. So it gets [Config.shutdownGrace] to finish on its own first, and
            // is only interrupted if it overstays it.
            Log.info("kodkod stopping — giving the cycle in flight up to ${config.shutdownGrace}s to finish")
            scheduler.shutdown()
            val finished = try {
                scheduler.awaitTermination(config.shutdownGrace, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                false
            }
            if (!finished) {
                Log.warn("the cycle in flight did not finish within ${config.shutdownGrace}s — interrupting it")
                scheduler.shutdownNow()
            }
            shutdown.countDown()
        },
    )
    shutdown.await()
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
