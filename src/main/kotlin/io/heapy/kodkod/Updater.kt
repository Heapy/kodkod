package io.heapy.kodkod

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonObject

/**
 * Keeps containers up to date by pulling their image tag and, when the resolved image id changes,
 * recreating the container from its existing configuration against the new image. Driven by labels:
 *
 *  - `<ns>.update.enable=true|false` — opt in/out (default follows [Config.updateMonitorAll])
 *  - `<ns>.stop.timeout=<seconds>`   — stop timeout used while recreating
 *
 * Containers pinned to a digest (`image@sha256:...`) are skipped — there is nothing to update.
 */
class Updater(
    private val api: DockerApi,
    private val config: Config,
    private val selfId: String?,
) {
    private val ns = config.labelNamespace

    fun runOnce() {
        val filters = linkedMapOf("status" to listOf("running"))
        if (!config.updateMonitorAll) filters["label"] = listOf("$ns.update.enable")

        val containers = api.listContainers(all = false, filters = filters)
        for (element in containers) {
            val container = element.jsonObject
            val id = container.str("Id") ?: continue
            if (isSelf(id)) continue
            if (!labelTruthy(container.obj("Labels"), "$ns.update.enable", config.updateMonitorAll)) continue

            try {
                updateOne(id)
            } catch (e: Exception) {
                Log.error("[${id.take(12)}] update failed: ${e.message}")
            }
        }
    }

    private fun updateOne(id: String) {
        val inspect = api.inspectContainer(id)
        val name = (inspect.str("Name") ?: id).trimStart('/')
        val imageRef = inspect.obj("Config")?.str("Image")
            ?: throw IllegalStateException("container has no image reference")

        if (imageRef.contains('@')) {
            Log.info("[$name] image is digest-pinned ($imageRef) — skipping")
            return
        }

        val currentImageId = inspect.str("Image").orEmpty()
        val (repo, tag) = splitImageRef(imageRef)
        Log.info("[$name] checking $imageRef for updates")
        api.pull(repo, tag, config.registryAuth)

        val newImageId = api.inspectImage(imageRef).str("Id")
        if (newImageId == null) {
            Log.warn("[$name] could not inspect pulled image $imageRef — skipping")
            return
        }
        if (newImageId == currentImageId) {
            Log.info("[$name] already up to date")
            return
        }

        Log.warn("[$name] update available (${currentImageId.short()} -> ${newImageId.short()}) — recreating")
        recreate(id, name, inspect, imageRef)
        Log.info("[$name] update complete")

        if (config.updateCleanup && currentImageId.isNotEmpty()) {
            // Best-effort: the old image is often still referenced; ignore failures.
            try {
                api.removeImage(currentImageId)
            } catch (_: Exception) {
            }
        }
    }

    private fun recreate(oldId: String, name: String, inspect: JsonObject, imageRef: String) {
        val labels = inspect.obj("Config")?.obj("Labels")
        val stopTimeout = labels.label("$ns.stop.timeout")?.toIntOrNull() ?: config.defaultStopTimeout
        val backupName = "${name}_kodkod_old"
        val body = buildCreateBody(inspect, imageRef, oldId)

        api.stop(oldId, stopTimeout)
        api.rename(oldId, backupName)

        val newId = try {
            api.create(name, body)
        } catch (e: Exception) {
            Log.error("[$name] create failed — rolling back: ${e.message}")
            rollback(oldId, name)
            throw e
        }

        try {
            api.start(newId)
        } catch (e: Exception) {
            Log.error("[$name] start failed — rolling back: ${e.message}")
            runCatching { api.remove(newId, force = true) }
            rollback(oldId, name)
            throw e
        }

        runCatching { api.remove(oldId, force = true) }
    }

    private fun rollback(oldId: String, name: String) {
        runCatching { api.rename(oldId, name) }
        runCatching { api.start(oldId) }
    }

    /**
     * Build the `/containers/create` body from the existing container's inspect output: reuse the
     * full `Config` (with `Image` swapped for the new ref), the `HostConfig`, and the networks the
     * container is attached to. Treating these as opaque JSON preserves every setting without
     * mapping each field by hand.
     */
    private fun buildCreateBody(inspect: JsonObject, imageRef: String, oldId: String): JsonObject {
        val containerConfig = inspect.obj("Config") ?: EMPTY_OBJECT
        val hostConfig = inspect.obj("HostConfig")
        val networks = inspect.obj("NetworkSettings")?.obj("Networks")
        val networkMode = hostConfig?.str("NetworkMode").orEmpty()

        // EndpointsConfig is only meaningful for user-defined/bridge networks; skip it for
        // host/none/container-share modes where HostConfig.NetworkMode is authoritative.
        val emitNetworks = networks != null && networks.isNotEmpty() &&
            networkMode != "host" && networkMode != "none" && !networkMode.startsWith("container:")

        // Docker auto-assigns Config.Hostname to the container's own short id when none was set;
        // carrying that over would give the replacement a stale hostname, so drop it in that case.
        val autoHostname = containerConfig.str("Hostname") == oldId.take(12)

        return buildJsonObject {
            containerConfig.forEach { (key, value) -> if (!(autoHostname && key == "Hostname")) put(key, value) }
            put("Image", JsonPrimitive(imageRef))
            if (hostConfig != null) put("HostConfig", hostConfig)
            if (emitNetworks) {
                putJsonObject("NetworkingConfig") {
                    putJsonObject("EndpointsConfig") {
                        networks.forEach { (netName, endpoint) ->
                            put(netName, cleanEndpoint(endpoint.jsonObject, oldId))
                        }
                    }
                }
            }
        }
    }

    /** Keep only the create-relevant endpoint fields and drop the auto-generated container-id alias. */
    private fun cleanEndpoint(endpoint: JsonObject, oldId: String): JsonObject {
        val short = oldId.take(12)
        return buildJsonObject {
            endpoint.arr("Aliases")?.let { aliases ->
                val kept = aliases.filter {
                    val alias = it.jsonPrimitive.content
                    alias != short && alias != oldId
                }
                if (kept.isNotEmpty()) put("Aliases", JsonArray(kept))
            }
            endpoint.obj("IPAMConfig")?.let { put("IPAMConfig", it) }
            endpoint.arr("Links")?.let { put("Links", it) }
            endpoint.obj("DriverOpts")?.let { put("DriverOpts", it) }
        }
    }

    private fun isSelf(id: String) = selfId != null && id.startsWith(selfId)

    companion object {
        /** Split `registry:5000/repo:tag` into (`registry:5000/repo`, `tag`), defaulting tag to `latest`. */
        fun splitImageRef(ref: String): Pair<String, String> {
            val lastSlash = ref.lastIndexOf('/')
            val lastColon = ref.lastIndexOf(':')
            return if (lastColon > lastSlash) {
                ref.substring(0, lastColon) to ref.substring(lastColon + 1)
            } else {
                ref to "latest"
            }
        }

        private fun String.short(): String =
            removePrefix("sha256:").take(12).ifEmpty { "<none>" }
    }
}
