package io.heapy.kodkod

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** How a dependent is wired to the container it depends on — both are baked in at create time. */
internal enum class DependencyKind {
    /** `--network container:<id|name>`, i.e. compose `network_mode: service:x` — a shared network namespace. */
    NETNS,

    /** Legacy `--link`: the daemon writes the target's address into the dependent's `/etc/hosts`. */
    LINK,
}

/** A container pointing at another container's create-time configuration. */
internal data class Dependent(
    val id: String,
    val name: String,
    val kind: DependencyKind,
    val state: String,
    val labels: JsonObject?,
    /**
     * Whether the reference is spelled as the provider's **id** (full or short) rather than its name —
     * which is what compose writes for `network_mode: service:x`.
     *
     * An id dies with the container it names. Once the provider has been *replaced* rather than merely
     * restarted, such a dependent cannot be started at all any more (`No such container`), so a restart
     * would only add a stopped container to a broken network: it has to be recreated against the
     * replacement. A reference by name still resolves, because the replacement takes the name over.
     */
    val pinnedToProviderId: Boolean = false,
) {
    val running: Boolean get() = state == "running"
    val short: String get() = id.take(12)
}

/** The container others may be pointing at, in the shape `/containers/json` reports it. */
internal data class DependencyProvider(
    val id: String,
    val names: Set<String>,
    val composeProject: String?,
) {
    /** Whether a `container:<ref>` or `--link` reference resolves to this container. */
    fun matches(ref: String): Boolean = ref == id || ref in names || (ref.length >= 4 && id.startsWith(ref))
}

/** Read a provider out of a `/containers/json` summary. */
internal fun providerOf(summary: JsonObject): DependencyProvider? {
    val id = summary.str("Id") ?: return null
    return DependencyProvider(
        id = id,
        names = summary.containerNames().toSet(),
        composeProject = summary.obj("Labels").label(COMPOSE_PROJECT_LABEL),
    )
}

/**
 * Containers across the **whole daemon** whose create-time configuration points at [provider].
 *
 * A container joined to another's network namespace (`network_mode: service:x`, `--network
 * container:x`) loses its interfaces the moment the provider's namespace goes away — a plain
 * `docker restart` of the provider is enough — and keeps reporting `Running` with no egress. The
 * same applies to legacy `--link`, whose address is resolved once, at create time. Both relations
 * are declared on the *dependent* side, so they can only be found by scanning containers, and the
 * scan deliberately leaves the caller's monitored set: an unlabelled sidecar is exactly the one that
 * nothing else will notice is broken.
 *
 * **Scan width.** The whole-daemon listing is the correct query but costs a full container list on
 * every restart, and would drag every unrelated container on the host into the fixture corpus the
 * recorder produces. So a compose-managed provider is probed first within its own project — where
 * `network_mode: service:` relations live by construction — and only a project that turns out to
 * share namespaces at all is re-scanned across the daemon, picking up sidecars started outside
 * compose. The blind spot is narrow and deliberate: a compose stack with no in-project dependent
 * does not pay for a full listing to find an out-of-project one.
 *
 * Errors are logged and yield an empty list — a listing failure must not abort the restart that
 * prompted it.
 */
internal fun findDependents(api: DockerClient, provider: DependencyProvider): List<Dependent> {
    val project = provider.composeProject
    if (project != null) {
        val withinProject = list(api, mapOf("label" to listOf("$COMPOSE_PROJECT_LABEL=$project"))) ?: return emptyList()
        if (dependentsIn(withinProject, provider).isEmpty()) return emptyList()
    }
    return dependentsIn(list(api, emptyMap()) ?: return emptyList(), provider)
}

/** Stopped containers included: a dependent that is down still has to be told the reference moved. */
private fun list(api: DockerClient, filters: Map<String, List<String>>): JsonArray? =
    try {
        api.listContainers(all = true, filters = filters)
    } catch (e: Exception) {
        Log.error("could not list containers to find dependents: ${e.message}")
        null
    }

/** Pick the containers in [summaries] that depend on [provider]. Pure — the listing is the caller's. */
internal fun dependentsIn(summaries: JsonArray, provider: DependencyProvider): List<Dependent> =
    summaries.mapNotNull { element ->
        val summary = element.jsonObject
        val id = summary.str("Id")?.takeIf { it != provider.id } ?: return@mapNotNull null
        val netns = netnsRef(summary.obj("HostConfig"))?.takeIf(provider::matches)
        val link = summary.linkSources().firstOrNull(provider::matches)
        val (kind, ref) = when {
            netns != null -> DependencyKind.NETNS to netns
            link != null -> DependencyKind.LINK to link
            else -> return@mapNotNull null
        }
        Dependent(
            id = id,
            name = summary.containerNames().firstOrNull() ?: id.take(12),
            kind = kind,
            state = summary.str("State") ?: "running",
            labels = summary.obj("Labels"),
            pinnedToProviderId = ref !in provider.names,
        )
    }

/** The `<ref>` of a `HostConfig.NetworkMode` of `container:<id|name>`, or `null` for any other mode. */
internal fun netnsRef(hostConfig: JsonObject?): String? =
    hostConfig?.str("NetworkMode")
        ?.takeIf { it.startsWith(NETNS_PREFIX) }
        ?.removePrefix(NETNS_PREFIX)
        ?.takeIf(String::isNotEmpty)

/**
 * The source container of a `--link` entry. Inspect spells it `/source:/dependent/alias`, the
 * endpoint in a listing spells the same link `source:alias` — both start with the source name.
 */
internal fun linkSource(link: String): String = link.removePrefix("/").substringBefore(':')

/**
 * Link sources visible in a `/containers/json` summary. Links live per network endpoint there
 * (inspect keeps the same list under `HostConfig.Links`), which is why the whole map is walked.
 */
private fun JsonObject.linkSources(): List<String> =
    obj("NetworkSettings")?.obj("Networks")?.values.orEmpty()
        .flatMap { network -> (network as? JsonObject)?.arr("Links").orEmpty() }
        .mapNotNull { it.jsonPrimitive.contentOrNull?.let(::linkSource)?.takeIf(String::isNotEmpty) }

const val NETNS_PREFIX = "container:"

const val COMPOSE_PROJECT_LABEL = "com.docker.compose.project"
