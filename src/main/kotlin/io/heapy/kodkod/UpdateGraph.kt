package io.heapy.kodkod

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * What one update cycle intends to do, as decided by [Updater.plan] from reads alone and carried out by
 * [Updater.apply]. [targets] is the whole monitored set in dependency order (dependencies first),
 * carrying the per-container verdict — the containers that are not being touched are part of the plan
 * too, since they are what the graph is resolved against.
 */
internal class UpdatePlan(val targets: List<Target>) {
    /** The containers this plan will actually stop and bring back, in the same order. */
    val work: List<Target> = targets.filter { it.toRestart }

    val hasWork: Boolean get() = work.isNotEmpty()

    companion object {
        /** Nothing to do — no monitored containers, or every one of them already up to date. */
        val NOTHING = UpdatePlan(emptyList())
    }
}

/** One container kodkod is considering for an update, plus the verdict it accumulates this cycle. */
internal class Target(
    val id: String,
    val name: String,
    /**
     * The container as the daemon last described it — and what the replacement is built from. Refreshed
     * at the start of the mutating phase, since minutes of image download can sit between the two.
     */
    var inspect: JsonObject,
    val imageRef: String?,
    val currentImageId: String,
    /** `os/arch` this container's image was resolved for, or null when the engine does not report it. */
    val platform: String?,
    val composeLabels: JsonObject?,
    val composeProject: String?,
    val composeService: String?,
) {
    /** This container's own image changed and it should be recreated. */
    var stale: Boolean = false

    /**
     * An update *is* available for this container but kodkod is holding it back — the same image
     * already failed to come up here and its cooldown has not run out (see `suppressedByCooldown`).
     *
     * It suppresses the update and only the update: the container still follows its dependencies
     * ([propagateLinkedRestart]), because a create-time dependent left joined to a namespace that was
     * destroyed this cycle is `Running` with no interfaces — which no gate catches, no rollback undoes
     * and no log line reports.
     *
     * That is not free, and the cost is paid on the other side of the edge: a dependency-driven recreate
     * builds the replacement from the image **ref**, which by now resolves to exactly the image already
     * known to fail here, so the recreate is not merely risky but certain to fail — and its rollback
     * cannot work, since the provider that was replaced took the namespace this container's original
     * would have to rejoin. So the *provider* is the one that gives way: `holdBackUnsafeProviders` keeps
     * it out of the cycle for as long as this flag is set, and nothing is forced anywhere.
     */
    var updateSuppressed: Boolean = false

    /** A container this one depends on is being restarted, so this one must restart too. */
    var linkedToRestarting: Boolean = false

    /** A create-time dependency changed, so this container must be recreated rather than merely started. */
    var linkedToRecreate: Boolean = false

    /**
     * This container is out of the cycle no matter what else it says — a create-time dependent of it
     * could not be put back if the recreate this restart forces were to fail (see
     * `holdBackUnsafeProviders`). It wins over [stale] and over both propagated flags, and is the only
     * thing that does: a container held back is one kodkod deliberately does not touch this cycle.
     */
    var restartHeldBack: Boolean = false

    /** Ids (within this cycle's set) of the containers this one depends on. */
    var deps: Set<String> = emptySet()

    /** Dependency ids that are baked into create-time config (`--link` or `network_mode: container:`). */
    var createTimeDeps: Set<String> = emptySet()

    /** Dependency ids compose marked `condition: service_healthy` — awaited before this one starts. */
    var healthGatedDeps: Set<String> = emptySet()

    /**
     * Dependency ids whose compose edge says `restart: false`. Only consulted when
     * [Config.respectDependsOnRestart] is on, and even then only for a plain restart.
     */
    var noRestartDeps: Set<String> = emptySet()

    /**
     * The container id serving this target right now: its own until a recreate replaced it, the
     * replacement's after that. Anything looking at a *dependency* mid-cycle (the `service_healthy`
     * wait) has to read the container that is actually running, not the one that was removed.
     */
    var liveId: String = id

    /** Name captured before updates for `HostConfig.NetworkMode=container:<id|name>`. */
    var networkModeContainerName: String? = null

    /** The running image's defaults, captured before pulling so a moved tag cannot erase them. */
    var oldImageConfig: JsonObject? = null

    /**
     * Local image id the tag resolves to now — set exactly when [stale] is set, since a target that
     * could not be resolved to a new id is never marked stale. Restamps `com.docker.compose.image`.
     */
    var newImageId: String? = null

    val toRecreate: Boolean get() = !restartHeldBack && (stale || linkedToRecreate)

    val toRestart: Boolean get() = !restartHeldBack && (stale || linkedToRecreate || linkedToRestarting)

    /** How this container is wired to [providerName] at create time, as a log line says it. */
    fun createTimeRelationTo(providerName: String): String {
        val kind = if (netnsRef(inspect.obj("HostConfig")) != null) DependencyKind.NETNS else DependencyKind.LINK
        return kind.relationTo(providerName)
    }
}

/**
 * Fill in each target's [Target.deps] (ids within the cycle's set), preferring Compose's own
 * `com.docker.compose.depends_on` metadata and falling back to the `<ns>.depends-on` label, legacy
 * `HostConfig.Links`, and `network_mode: container:`.
 */
internal fun resolveLinks(
    targets: List<Target>,
    ns: String,
    externalContainerName: (String) -> String? = { null },
) {
    val byName = HashMap<String, String>()
    val byService = HashMap<String, String>()
    val byId = targets.associateBy { it.id }
    for (target in targets) {
        byName[target.name] = target.id
        if (target.composeProject != null && target.composeService != null) {
            byService[serviceKey(target.composeProject, target.composeService)] = target.id
        }
    }

    for (target in targets) {
        val deps = LinkedHashSet<String>()
        val createTimeDeps = LinkedHashSet<String>()
        val healthGatedDeps = LinkedHashSet<String>()
        val noRestartDeps = LinkedHashSet<String>()
        fun addDep(depId: String?, createTime: Boolean = false) {
            if (depId != null && depId != target.id) deps += depId
            if (createTime && depId != null && depId != target.id) createTimeDeps += depId
        }

        // Compose metadata: "db:service_started:false" — all three fields carry meaning.
        for (edge in parseDependsOn(target.composeLabels.label("com.docker.compose.depends_on"))) {
            val project = target.composeProject ?: continue
            val depId = byService[serviceKey(project, edge.service)] ?: continue
            if (depId == target.id) continue
            addDep(depId)
            if (edge.condition == CONDITION_SERVICE_HEALTHY) healthGatedDeps += depId
            if (edge.restart == false) noRestartDeps += depId
        }
        // Explicit kodkod label for non-compose users: container names (or service names).
        target.composeLabels.label("$ns.depends-on")?.splitToSequence(',')?.forEach { token ->
            val name = token.trim()
            if (name.isNotEmpty()) addDep(byName[name] ?: target.composeProject?.let { byService[serviceKey(it, name)] })
        }
        // Legacy --link: "/source:/container/alias".
        target.inspect.obj("HostConfig")?.arr("Links")?.forEach { link ->
            val source = linkSource(link.jsonPrimitive.contentOrNull ?: return@forEach)
            addDep(byName[source], createTime = true)
        }
        // network_mode: container:<id|name>.
        val ref = netnsRef(target.inspect.obj("HostConfig"))
        if (ref != null) {
            val depId = byName[ref] ?: targets.firstOrNull { it.id.startsWith(ref) }?.id
            addDep(depId, createTime = true)
            target.networkModeContainerName = depId?.let { byId[it]?.name } ?: externalContainerName(ref)
        }
        target.deps = deps
        target.createTimeDeps = createTimeDeps
        target.healthGatedDeps = healthGatedDeps
        target.noRestartDeps = noRestartDeps
    }
}

/** One parsed `com.docker.compose.depends_on` entry. */
internal class DependsOnEdge(
    val service: String,
    /** `service_started` (compose's default and what a field-less entry means), `service_healthy`, … */
    val condition: String,
    /** compose's `restart` field, or `null` when the label does not carry one. */
    val restart: Boolean?,
)

/**
 * Parse a `com.docker.compose.depends_on` label: a comma-separated list of
 * `<service>[:<condition>[:<restart>]]`. Older compose versions emit fewer fields, so a missing
 * condition reads as `service_started` and a missing (or unparsable) `restart` stays `null` —
 * "compose said nothing", which is not the same as the explicit `false` that may suppress a restart.
 */
internal fun parseDependsOn(label: String?): List<DependsOnEdge> =
    label?.split(',')?.mapNotNull { entry ->
        val fields = entry.split(':').map { it.trim() }
        fields[0].takeIf { it.isNotEmpty() }?.let { service ->
            DependsOnEdge(
                service = service,
                condition = fields.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: CONDITION_SERVICE_STARTED,
                restart = fields.getOrNull(2)?.lowercase()?.toBooleanStrictOrNull(),
            )
        }
    } ?: emptyList()

/**
 * Mark every container that depends (transitively) on a restarting container as restarting too. A
 * fixpoint loop so chains `c -> b -> a` propagate fully (watchtower's `UpdateImplicitRestart` is a
 * single pass).
 *
 * With [respectDependsOnRestart] on, an edge compose marked `restart: false` no longer propagates a
 * plain restart (see [Config.respectDependsOnRestart]). It can never stop a *recreate*: create-time
 * dependents are decided from [Target.createTimeDeps], which this subtraction does not touch, so a
 * netns consumer is never left pointing at a namespace that no longer exists.
 *
 * A container whose own update is being held back ([Target.updateSuppressed]) takes part like any
 * other: the cooldown suppresses that container's **image update**, and nothing else. Leaving it out
 * of the graph would leave a create-time dependent of a container replaced this cycle pointing at a
 * network namespace that no longer exists — `Running` with no interfaces, the one failure nothing in
 * the system reports — and would hide it behind a log line about an update cooldown. What keeps that
 * from forcing it onto the very image that failed is that the *provider* is taken out of the cycle
 * instead (`holdBackUnsafeProviders`), which is why this pass is run again after that decision.
 *
 * [Target.restartHeldBack] is the one verdict this pass does not overrule: a container held back is
 * one that must not move at all this cycle, and it neither takes a flag nor hands one on (the flags
 * are read through [Target.toRestart], which is false for it).
 */
internal fun propagateLinkedRestart(targets: List<Target>, respectDependsOnRestart: Boolean = false) {
    val byId = targets.associateBy { it.id }
    var changed = true
    while (changed) {
        changed = false
        for (target in targets) {
            if (target.restartHeldBack) continue
            val restartDeps = if (respectDependsOnRestart) target.deps - target.noRestartDeps else target.deps
            if (!target.toRecreate && target.createTimeDeps.any { byId[it]?.toRestart == true }) {
                target.linkedToRecreate = true
                changed = true
            }
            if (!target.toRestart && restartDeps.any { byId[it]?.toRestart == true }) {
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

/** `repo:tag`, with the implicit `:latest` spelled out so a ref compares equal to a `RepoTags` entry. */
private fun normalizeImageRef(ref: String): String = splitImageRef(ref).let { (repo, tag) -> "$repo:$tag" }

/**
 * [normalizeImageRef] plus the implicit Docker Hub prefixes spelled *out*: `docker.io/library/nginx`,
 * `library/nginx` and `nginx` all name the same image, but only the last is what the daemon puts in
 * `RepoTags`. Any other registry is left exactly as it is — `myreg:5000/library/app` is a different
 * image from `library/app`.
 */
internal fun canonicalImageRef(ref: String): String {
    val normalized = normalizeImageRef(ref)
    val withoutRegistry = HUB_PREFIXES.firstOrNull(normalized::startsWith)
        ?.let { normalized.removePrefix(it) }
        ?: normalized
    // `library/` is Docker Hub's namespace for official images, and only theirs.
    return if (withoutRegistry.count { it == '/' } == 1) withoutRegistry.removePrefix("library/") else withoutRegistry
}

/** How a Docker Hub ref may spell the registry that is otherwise implicit. */
private val HUB_PREFIXES = listOf("docker.io/", "index.docker.io/", "registry-1.docker.io/")

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

/**
 * `os/arch` of the image manifest this container actually runs, read from the inspect payload's
 * `ImageManifestDescriptor.platform`. Null on engines that do not report the descriptor — then the
 * daemon keeps choosing its own default, exactly as it did before.
 *
 * `variant` is deliberately **not** included: it describes the specific manifest of the *old* image
 * (in the recorded corpus one image reports `arm64`/`v8` and another plain `arm64` on the same host),
 * so pinning it would risk a "no matching manifest" failure against the new image.
 */
internal fun JsonObject.imagePlatform(): String? {
    val platform = obj("ImageManifestDescriptor")?.obj("platform") ?: return null
    val os = platform.str("os")?.takeIf { it.isNotBlank() } ?: return null
    val architecture = platform.str("architecture")?.takeIf { it.isNotBlank() } ?: return null
    return "$os/$architecture"
}

internal fun JsonObject.distributionDigest(): String? =
    obj("Descriptor")?.str("digest")?.takeIf { it.isNotBlank() }

internal fun JsonObject.repoDigests(): Set<String> =
    arr("RepoDigests")
        ?.mapNotNull { ref ->
            ref.jsonPrimitive.contentOrNull
                ?.substringAfter('@', missingDelimiterValue = "")
                ?.takeIf { it.isNotBlank() }
        }
        ?.toSet()
        ?: emptySet()

/**
 * `RepoTags` of an image inspect, without the `<none>:<none>` placeholder some engines emit for an
 * untagged image — that entry is the *absence* of a tag and must not read as somebody's reference.
 */
internal fun JsonObject.repoTags(): Set<String> =
    arr("RepoTags")
        ?.mapNotNull { tag -> tag.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() && it != NO_TAG } }
        ?.toSet()
        ?: emptySet()

/** `docker images` shows an untagged image this way, and some engines put it in `RepoTags` too. */
private const val NO_TAG = "<none>:<none>"

/** compose's default `depends_on` condition: the dependency merely has to have been started. */
private const val CONDITION_SERVICE_STARTED = "service_started"

/**
 * compose's `depends_on` condition that asks for a dependency whose healthcheck actually passes.
 * `internal` rather than file-private because `Updater.awaitHealthy` names it in the line it logs.
 */
internal const val CONDITION_SERVICE_HEALTHY = "service_healthy"

private fun serviceKey(project: String, service: String) = project + '\u0000' + service

/**
 * An image or container id as every log line spells it: the `sha256:` prefix dropped and the first
 * twelve characters kept, which is what `docker` itself shows. `internal` because both this file and
 * `Updater` print ids.
 */
internal fun String.shortId(): String =
    removePrefix("sha256:").take(12).ifEmpty { "<none>" }
