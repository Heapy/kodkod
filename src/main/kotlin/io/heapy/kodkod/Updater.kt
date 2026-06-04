package io.heapy.kodkod

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Keeps containers up to date by pulling their image tag and, when the resolved image id changes,
 * recreating the container from its existing configuration against the new image. Driven by labels:
 *
 *  - `<ns>.update.enable=true|false` — opt in/out (default follows [Config.updateMonitorAll])
 *  - `<ns>.stop.timeout=<seconds>`   — stop timeout used while recreating
 *  - `<ns>.depends-on=a,b`           — restart ordering for non-compose users (compose stacks are
 *                                       ordered automatically from the `com.docker.compose.*` labels)
 *
 * A cycle works on the whole monitored set at once so it can honour dependency order: containers are
 * stopped in reverse dependency order and brought back in forward order, and a container that depends on
 * an updated one is restarted too (watchtower's `UpdateImplicitRestart`). Containers pinned to a digest
 * (`image@sha256:...`) are never stale but can still be restarted as a dependent.
 */
class Updater(
    private val api: DockerApi,
    private val config: Config,
    private val selfId: String?,
) {
    private val ns = config.labelNamespace

    fun runOnce() {
        val targets = collectTargets()
        if (targets.isEmpty()) return

        markStale(targets)
        propagateLinkedRestart(targets)
        if (targets.none { it.toRestart }) {
            Log.info("update: all monitored containers are up to date")
            return
        }

        val ordered = topoSort(targets)

        // Stop dependents before the dependencies they rely on.
        for (target in ordered.asReversed()) {
            if (!target.toRestart) continue
            try {
                api.stop(target.id, stopTimeout(target))
            } catch (e: Exception) {
                Log.error("[${target.name}] stop failed: ${e.message}")
            }
        }
        // Bring everything back in dependency order: recreate the stale ones, restart the rest.
        for (target in ordered) {
            if (!target.toRestart) continue
            try {
                if (target.stale) {
                    recreate(target)
                } else {
                    api.start(target.id)
                    Log.info("[${target.name}] restarted (a dependency was updated)")
                }
            } catch (e: Exception) {
                Log.error("[${target.name}] ${if (target.stale) "recreate" else "restart"} failed: ${e.message}")
            }
        }
    }

    // --- Discovery ------------------------------------------------------------------------

    private fun collectTargets(): List<Target> {
        val filters = linkedMapOf("status" to listOf("running"))
        if (!config.updateMonitorAll) filters["label"] = listOf("$ns.update.enable")
        val summaries = api.listContainers(all = false, filters = filters)

        val targets = ArrayList<Target>()
        for (element in summaries) {
            val summary = element.jsonObject
            val id = summary.str("Id") ?: continue
            val summaryLabels = summary.obj("Labels")
            if (isSelf(id, summaryLabels, selfId)) continue
            if (!labelTruthy(summaryLabels, "$ns.update.enable", config.updateMonitorAll)) continue
            val inspect = try {
                api.inspectContainer(id)
            } catch (e: Exception) {
                Log.error("[${id.take(12)}] inspect failed: ${e.message}")
                continue
            }
            targets += toTarget(id, inspect)
        }
        resolveLinks(targets, ns)
        return targets
    }

    private fun toTarget(id: String, inspect: JsonObject): Target {
        val containerConfig = inspect.obj("Config")
        val labels = containerConfig?.obj("Labels")
        return Target(
            id = id,
            name = (inspect.str("Name") ?: id).trimStart('/'),
            inspect = inspect,
            imageRef = containerConfig?.str("Image"),
            currentImageId = inspect.str("Image").orEmpty(),
            composeLabels = labels,
            composeProject = labels.label("com.docker.compose.project"),
            composeService = labels.label("com.docker.compose.service"),
        )
    }

    private fun markStale(targets: List<Target>) {
        for (target in targets) {
            val imageRef = target.imageRef
            if (imageRef == null) {
                Log.warn("[${target.name}] container has no image reference — skipping update check")
                continue
            }
            if (imageRef.contains('@')) {
                Log.info("[${target.name}] image is digest-pinned ($imageRef) — skipping update check")
                continue
            }
            try {
                val (repo, tag) = splitImageRef(imageRef)
                Log.info("[${target.name}] checking $imageRef for updates")
                target.oldImageConfig = inspectOldImageConfig(target)
                api.pull(repo, tag, config.registryAuth)
                val newImageId = api.inspectImage(imageRef).str("Id")
                when {
                    newImageId == null ->
                        Log.warn("[${target.name}] could not inspect pulled image $imageRef — skipping")
                    newImageId == target.currentImageId ->
                        Log.info("[${target.name}] already up to date")
                    else -> {
                        Log.warn("[${target.name}] update available (${target.currentImageId.shortId()} -> ${newImageId.shortId()})")
                        target.stale = true
                    }
                }
            } catch (e: Exception) {
                Log.error("[${target.name}] update check failed: ${e.message}")
            }
        }
    }

    // --- Recreate -------------------------------------------------------------------------

    private fun recreate(target: Target) {
        val name = target.name
        val imageRef = target.imageRef ?: return
        val containerConfig = target.inspect.obj("Config") ?: EMPTY_OBJECT

        // Subtract the OLD image's defaults so the NEW image's defaults are not masked. Pulling a moved
        // tag can make the old image id disappear from the local image store, so prefer the config captured
        // before the pull. If the old defaults are already gone, fall back to subtracting keys present in
        // the newly pulled image so its env/labels/cmd defaults can still win over stale resolved config.
        val oldImageConfig = target.oldImageConfig ?: inspectOldImageConfig(target)
        val (imageConfig, subtractByKey) = if (oldImageConfig != null) {
            oldImageConfig to false
        } else {
            val newImageConfig = inspectImageConfig(imageRef)
            if (newImageConfig == null) {
                Log.warn("[$name] could not inspect old or new image defaults — keeping full config")
                null to false
            } else {
                Log.warn("[$name] could not inspect old image defaults — subtracting new image default keys")
                newImageConfig to true
            }
        }

        val hostConfig = resolveHostConfig(target.inspect.obj("HostConfig"))
        val networks = networkEndpoints(target.inspect, hostConfig, target.id)
        val body = buildCreateBody(
            containerConfig,
            imageConfig,
            hostConfig,
            imageRef,
            target.id,
            networks.firstOrNull(),
            subtractImageDefaultsByKey = subtractByKey,
        )
        val backupName = "${name}_kodkod_old_${target.id.take(12)}"
        val timeout = stopTimeout(target)

        try {
            api.stop(target.id, timeout) // usually a no-op (already stopped in the reverse-order pass)
            api.rename(target.id, backupName)
            val newId = api.create(name, body)
            try {
                networks.drop(1).forEach { (net, endpoint) -> api.connectNetwork(net, newId, endpoint) }
                api.start(newId)
            } catch (e: Exception) {
                runCatching { api.remove(newId, force = true) }
                throw e
            }
            Log.info("[$name] update complete")
            try {
                api.remove(target.id, force = true)
            } catch (e: Exception) {
                Log.warn("[$name] could not remove old container $backupName: ${e.message}")
            }
            if (config.updateCleanup && target.currentImageId.isNotEmpty()) {
                // Best-effort: the old image is often still referenced; ignore failures.
                runCatching { api.removeImage(target.currentImageId) }
            }
        } catch (e: Exception) {
            // Any failure after we stopped the container must restore the original, running container.
            Log.error("[$name] recreate failed — rolling back: ${e.message}")
            rollback(target.id, name)
            throw e
        }
    }

    private fun rollback(oldId: String, name: String) {
        runCatching { api.rename(oldId, name) }
        runCatching { api.start(oldId) }
    }

    /**
     * Resolve a `network_mode: container:<id>` to the referenced container's **name**, so the recreated
     * container keeps sharing that container's network namespace even after the provider is itself
     * recreated (its id would otherwise change). Other host configs pass through untouched.
     */
    private fun resolveHostConfig(hostConfig: JsonObject?): JsonObject? {
        if (hostConfig == null) return null
        val mode = hostConfig.str("NetworkMode") ?: return hostConfig
        if (!mode.startsWith("container:")) return hostConfig
        val ref = mode.removePrefix("container:")
        val resolvedName = try {
            api.inspectContainer(ref).str("Name")?.trimStart('/')
        } catch (e: Exception) {
            Log.warn("could not resolve network_mode container:$ref — keeping as-is: ${e.message}")
            null
        } ?: return hostConfig
        Log.info("resolved network_mode container:$ref -> container:$resolvedName")
        return buildJsonObject {
            hostConfig.forEach { (key, value) -> if (key != "NetworkMode") put(key, value) }
            put("NetworkMode", "container:$resolvedName")
        }
    }

    /**
     * The networks (with create-relevant endpoint fields) to attach the replacement to, in order. Empty
     * for host/none/container network modes, where `HostConfig.NetworkMode` is authoritative. The first
     * entry goes into the create body; the rest are connected afterwards (Docker rejects multiple
     * endpoints at create time — docker/docker#29265).
     */
    private fun networkEndpoints(inspect: JsonObject, hostConfig: JsonObject?, oldId: String): List<Pair<String, JsonObject>> {
        val networks = inspect.obj("NetworkSettings")?.obj("Networks") ?: return emptyList()
        val mode = hostConfig?.str("NetworkMode").orEmpty()
        if (networks.isEmpty() || mode == "host" || mode == "none" || mode.startsWith("container:")) return emptyList()
        return networks.map { (netName, endpoint) -> netName to cleanEndpoint(endpoint.jsonObject, oldId) }
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

    private fun stopTimeout(target: Target): Int =
        target.composeLabels.label("$ns.stop.timeout")?.toIntOrNull() ?: config.defaultStopTimeout

    private fun inspectOldImageConfig(target: Target): JsonObject? =
        if (target.currentImageId.isEmpty()) {
            null
        } else {
            inspectImageConfig(target.currentImageId)
        }

    private fun inspectImageConfig(ref: String): JsonObject? =
        runCatching { api.inspectImage(ref).obj("Config") }.getOrNull()
}

/** One container kodkod is considering for an update, plus the verdict it accumulates this cycle. */
internal class Target(
    val id: String,
    val name: String,
    val inspect: JsonObject,
    val imageRef: String?,
    val currentImageId: String,
    val composeLabels: JsonObject?,
    val composeProject: String?,
    val composeService: String?,
) {
    /** This container's own image changed and it should be recreated. */
    var stale: Boolean = false

    /** A container this one depends on is being restarted, so this one must restart too. */
    var linkedToRestarting: Boolean = false

    /** Ids (within this cycle's set) of the containers this one depends on. */
    var deps: Set<String> = emptySet()

    /** The running image's defaults, captured before pulling so a moved tag cannot erase them. */
    var oldImageConfig: JsonObject? = null

    val toRestart: Boolean get() = stale || linkedToRestarting
}

/**
 * Fill in each target's [Target.deps] (ids within the cycle's set), preferring Compose's own
 * `com.docker.compose.depends_on` metadata and falling back to the `<ns>.depends-on` label, legacy
 * `HostConfig.Links`, and `network_mode: container:`.
 */
internal fun resolveLinks(targets: List<Target>, ns: String) {
    val byName = HashMap<String, String>()
    val byService = HashMap<String, String>()
    for (target in targets) {
        byName[target.name] = target.id
        if (target.composeProject != null && target.composeService != null) {
            byService[serviceKey(target.composeProject, target.composeService)] = target.id
        }
    }

    for (target in targets) {
        val deps = LinkedHashSet<String>()
        fun addDep(depId: String?) {
            if (depId != null && depId != target.id) deps += depId
        }

        // Compose metadata: entries look like "db:service_started:true" — take the service name.
        target.composeLabels.label("com.docker.compose.depends_on")?.splitToSequence(',')?.forEach { entry ->
            val service = entry.substringBefore(':').trim()
            val project = target.composeProject
            if (service.isNotEmpty() && project != null) addDep(byService[serviceKey(project, service)])
        }
        // Explicit kodkod label for non-compose users: container names (or service names).
        target.composeLabels.label("$ns.depends-on")?.splitToSequence(',')?.forEach { token ->
            val name = token.trim()
            if (name.isNotEmpty()) addDep(byName[name] ?: target.composeProject?.let { byService[serviceKey(it, name)] })
        }
        // Legacy --link: "/source:/container/alias".
        target.inspect.obj("HostConfig")?.arr("Links")?.forEach { link ->
            val source = (link.jsonPrimitive.contentOrNull ?: return@forEach).removePrefix("/").substringBefore(':')
            addDep(byName[source])
        }
        // network_mode: container:<id|name>.
        val mode = target.inspect.obj("HostConfig")?.str("NetworkMode").orEmpty()
        if (mode.startsWith("container:")) {
            val ref = mode.removePrefix("container:")
            addDep(byName[ref] ?: targets.firstOrNull { it.id.startsWith(ref) }?.id)
        }
        target.deps = deps
    }
}

/**
 * Mark every container that depends (transitively) on a restarting container as restarting too. A
 * fixpoint loop so chains `c -> b -> a` propagate fully (watchtower's `UpdateImplicitRestart` is a
 * single pass).
 */
internal fun propagateLinkedRestart(targets: List<Target>) {
    val byId = targets.associateBy { it.id }
    var changed = true
    while (changed) {
        changed = false
        for (target in targets) {
            if (target.toRestart) continue
            if (target.deps.any { byId[it]?.toRestart == true }) {
                target.linkedToRestarting = true
                changed = true
            }
        }
    }
}

/** Topological order, dependencies first. On a cycle, logs and falls back to best-effort order. */
internal fun topoSort(targets: List<Target>): List<Target> {
    val byId = targets.associateBy { it.id }
    val visited = HashSet<String>()
    val visiting = HashSet<String>()
    val result = ArrayList<Target>(targets.size)

    fun visit(target: Target) {
        if (target.id in visited) return
        if (!visiting.add(target.id)) {
            Log.error("[${target.name}] dependency cycle detected — updating without ordering guarantees")
            return
        }
        for (depId in target.deps) byId[depId]?.let(::visit)
        visiting.remove(target.id)
        visited += target.id
        result += target
    }

    for (target in targets) visit(target)
    return result
}

/** Split `registry:5000/repo:tag` into (`registry:5000/repo`, `tag`), defaulting the tag to `latest`. */
internal fun splitImageRef(ref: String): Pair<String, String> {
    val lastSlash = ref.lastIndexOf('/')
    val lastColon = ref.lastIndexOf(':')
    return if (lastColon > lastSlash) {
        ref.substring(0, lastColon) to ref.substring(lastColon + 1)
    } else {
        ref to "latest"
    }
}

private fun serviceKey(project: String, service: String) = "$project $service"

private fun String.shortId(): String =
    removePrefix("sha256:").take(12).ifEmpty { "<none>" }
