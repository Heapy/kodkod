package io.heapy.kodkod

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    val scheduler = Executors.newScheduledThreadPool(2) { runnable ->
        Thread(runnable, "kodkod-worker").apply { isDaemon = true }
    }

    // scheduleWithFixedDelay => the next cycle starts only after the previous one finishes,
    // so cycles never overlap even if a pull/restart runs long.
    if (config.autohealEnabled) {
        scheduler.scheduleWithFixedDelay(
            guarded("autoheal", autoheal::runOnce),
            config.autohealStartPeriod, config.autohealInterval, TimeUnit.SECONDS,
        )
    }
    if (config.updateEnabled) {
        scheduler.scheduleWithFixedDelay(
            guarded("update", updater::runOnce),
            config.updateStartPeriod, config.updateInterval, TimeUnit.SECONDS,
        )
    }

    val shutdown = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            Log.info("kodkod stopping")
            scheduler.shutdownNow()
            shutdown.countDown()
        },
    )
    shutdown.await()
}

/** Wrap a cycle so a thrown exception is logged instead of cancelling the scheduled task. */
private fun guarded(name: String, task: () -> Unit): Runnable = Runnable {
    try {
        task()
    } catch (e: Throwable) {
        Log.error("[$name] cycle failed: ${e.message}")
    }
}
