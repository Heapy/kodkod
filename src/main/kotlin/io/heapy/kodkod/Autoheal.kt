package io.heapy.kodkod

import kotlinx.serialization.json.jsonObject

/**
 * Restarts unhealthy containers — the same job as the reference `docker-autoheal` tools, driven
 * by labels under the configured namespace:
 *
 *  - `<ns>.autoheal.enable=true|false` — opt in/out (default follows [Config.autohealMonitorAll])
 *  - `<ns>.stop.timeout=<seconds>`     — per-container stop timeout override
 *
 * [clock] and [sleeper] default to the real ones and exist so waiting logic can be driven from tests
 * without spending the wall-clock time it describes.
 */
class Autoheal(
    private val api: DockerClient,
    private val config: Config,
    private val selfId: String?,
    @Suppress("unused") private val clock: TimeSource = TimeSource.SYSTEM,
    @Suppress("unused") private val sleeper: Sleeper = Sleeper.SYSTEM,
) {
    private val ns = config.labelNamespace

    fun runOnce() {
        val filters = linkedMapOf("health" to listOf("unhealthy"))
        // When not monitoring everything, let Docker pre-filter to labelled containers.
        if (!config.autohealMonitorAll) filters["label"] = listOf("$ns.autoheal.enable")

        val containers = api.listContainers(all = false, filters = filters)
        for (element in containers) {
            val container = element.jsonObject
            val id = container.str("Id") ?: continue
            val labels = container.obj("Labels")
            if (isSelf(id, labels, selfId)) continue

            val short = id.take(12)
            val name = container.arr("Names").firstString()?.trimStart('/') ?: short
            if (!labelTruthy(labels, "$ns.autoheal.enable", config.autohealMonitorAll)) continue

            if (container.str("State") == "restarting") {
                Log.info("[$name ($short)] already restarting — skipping")
                continue
            }

            val timeout = labels.label("$ns.stop.timeout")?.toIntOrNull() ?: config.defaultStopTimeout
            Log.warn("[$name ($short)] unhealthy — restarting with ${timeout}s timeout")
            try {
                api.restart(id, timeout)
                Log.info("[$name ($short)] restart successful")
            } catch (e: Exception) {
                Log.error("[$name ($short)] restart failed: ${e.message}")
            }
        }
    }
}
