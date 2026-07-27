package io.heapy.kodkod

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Restarts unhealthy containers — the same job as the reference `docker-autoheal` tools, driven
 * by labels under the configured namespace:
 *
 *  - `<ns>.autoheal.enable=true|false` — opt in/out (default follows [Config.autohealMonitorAll])
 *  - `<ns>.stop.timeout=<seconds>`     — per-container stop timeout override
 *
 * A restart is never issued in isolation: containers wired to the restarted one at create time
 * (`network_mode: service:x`, legacy `--link`) are restarted after it — see [findDependents] — and
 * they are looked up across the whole daemon, not just the labelled set.
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

            // No override means no `?t=`: the daemon then applies the container's own Config.StopTimeout.
            // Autoheal works off the container list alone — deliberately no inspect per unhealthy
            // container — so in that case it cannot know that value and the read timeout keeps its 60s
            // floor. A container with a longer stop window may therefore report a read timeout while the
            // daemon is still stopping it (the restart itself still completes); paying an inspect per
            // unhealthy container just to size a timeout is not worth it.
            val timeout = stopTimeout(labels)
            val window = timeout?.let { "${it}s timeout" } ?: "its own stop timeout"
            Log.warn("[$name ($short)] unhealthy — restarting with $window")
            try {
                api.restart(id, timeout)
                Log.info("[$name ($short)] restart successful")
            } catch (e: Exception) {
                Log.error("[$name ($short)] restart failed: ${e.message}")
                continue
            }
            restartDependents(container, name)
        }
    }

    /**
     * Restart what the container we just restarted was holding up.
     *
     * Restarting a container tears down its network namespace, so every container joined to it
     * (`network_mode: service:x`) comes back without interfaces — no `eth0`, no egress — while still
     * reporting `Running`, which is precisely the state nothing else notices. Legacy `--link`
     * dependents keep a stale address for the same reason. Only meaningful once the provider is
     * actually back, hence after a successful restart.
     */
    private fun restartDependents(summary: JsonObject, providerName: String) {
        val provider = providerOf(summary) ?: return
        for (dependent in findDependents(api, provider)) {
            if (isSelf(dependent.id, dependent.labels, selfId)) continue
            val where = "[${dependent.name} (${dependent.short})]"
            if (!dependent.running) {
                Log.info("$where depends on $providerName but is ${dependent.state} — leaving it alone")
                continue
            }
            val reason = when (dependent.kind) {
                DependencyKind.NETNS -> "shares the network namespace of $providerName"
                DependencyKind.LINK -> "is --link'ed to $providerName"
            }
            Log.warn("$where $reason and would be left with a dead one — restarting it too")
            try {
                api.restart(dependent.id, stopTimeout(dependent.labels))
                Log.info("$where restart successful")
            } catch (e: Exception) {
                Log.error("$where restart failed — it may have no working network: ${e.message}")
            }
        }
    }

    private fun stopTimeout(labels: JsonObject?): Int? =
        labels.label("$ns.stop.timeout")?.toIntOrNull() ?: config.defaultStopTimeout
}
