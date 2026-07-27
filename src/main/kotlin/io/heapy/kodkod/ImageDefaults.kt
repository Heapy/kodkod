package io.heapy.kodkod

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Subtracting an image's baked-in defaults from a container's *resolved* `Config` when recreating it.
 *
 * `docker inspect` reports a container's `Config` as the image's defaults (`Env`, `Cmd`, `Entrypoint`,
 * `Healthcheck`, …) merged with the user's overrides. Recreating against a **new** image with that
 * resolved config verbatim would freeze the **old** image's defaults and silently mask whatever the new
 * image changed. So we subtract the old image's config and keep only the user's overrides; the new image
 * then supplies its own defaults. Ported from watchtower's `GetCreateConfig`
 * (`pkg/container/container.go`) and `internal/util/util.go`.
 */

/** Element-wise equality of two JSON arrays; order matters and `null` is treated as an empty array. */
internal fun sliceEqual(a: JsonArray?, b: JsonArray?): Boolean {
    val left = a ?: EMPTY_ARRAY
    val right = b ?: EMPTY_ARRAY
    if (left.size != right.size) return false
    return left.indices.all { left[it] == right[it] }
}

/** Keep the container entries that are not present verbatim in the image (exact match — used for `Env`). */
internal fun sliceSubtract(container: JsonArray?, image: JsonArray?): JsonArray {
    if (container == null) return EMPTY_ARRAY
    val imageEntries = image?.toSet() ?: emptySet()
    return JsonArray(container.filter { it !in imageEntries })
}

/** Keep container env vars whose names are absent from the image env. Used when old defaults are gone. */
internal fun envKeySubtract(container: JsonArray?, image: JsonArray?): JsonArray {
    if (container == null) return EMPTY_ARRAY
    val imageKeys = image?.mapNotNullTo(HashSet()) { it.envKey() } ?: emptySet()
    if (imageKeys.isEmpty()) return container
    return JsonArray(container.filter { it.envKey() !in imageKeys })
}

/** Keep a label/entry when its key is absent from the image, or present there with a different value. */
internal fun stringMapSubtract(container: JsonObject?, image: JsonObject?): JsonObject {
    if (container == null) return EMPTY_OBJECT
    val img = image ?: EMPTY_OBJECT
    return JsonObject(container.filter { (key, value) -> img[key] != value })
}

/** Keep labels/entries whose keys are absent from the image. Used when old defaults are gone. */
internal fun stringMapSubtractKeys(container: JsonObject?, image: JsonObject?): JsonObject {
    if (container == null) return EMPTY_OBJECT
    val img = image ?: EMPTY_OBJECT
    return JsonObject(container.filterKeys { it !in img })
}

/** Keep healthcheck fields whose values differ from the image defaults. */
internal fun healthcheckSubtract(container: JsonObject?, image: JsonObject?): JsonObject {
    if (container == null) return EMPTY_OBJECT
    val img = image ?: EMPTY_OBJECT
    return JsonObject(container.filter { (key, value) -> img[key] != value })
}

/** Keep healthcheck fields whose names are absent from the image. Used when old defaults are gone. */
internal fun healthcheckSubtractKeys(container: JsonObject?, image: JsonObject?): JsonObject {
    if (container == null) return EMPTY_OBJECT
    val img = image ?: EMPTY_OBJECT
    return JsonObject(container.filterKeys { it !in img })
}

/** Keep only the keys absent from the image (values ignored — used for anonymous `Volumes`). */
internal fun structMapSubtract(container: JsonObject?, image: JsonObject?): JsonObject {
    if (container == null) return EMPTY_OBJECT
    val img = image ?: EMPTY_OBJECT
    return JsonObject(container.filterKeys { it !in img })
}

/**
 * Carry the volumes a container is actually using into the replacement's `HostConfig.Mounts`.
 *
 * An anonymous volume is only fully described by the container's **top-level** `Mounts[]` — that is the
 * one place the generated volume `Name` appears. `HostConfig` either names the destination with an empty
 * `Source` (compose's `volumes: ["/data"]`), or does not mention it at all (the image declared `VOLUME`,
 * or `docker run -v /data`, where the path lives in `Config.Volumes` and [structMapSubtract] drops it as
 * an image default). Recreating from `HostConfig` alone therefore hands the new container a *fresh* empty
 * volume and orphans the old data — the database-loses-its-data failure.
 *
 * So the resolver is driven from [inspectMounts]: every `Type=volume` entry with a non-empty `Name` whose
 * `Destination` is not already covered by `HostConfig.Mounts`/`Binds` becomes an explicit mount of that
 * volume. Existing entries are completed in place (their `Source` filled in) and otherwise kept verbatim,
 * since whatever else they carry — `VolumeOptions`, `BindOptions` — was already accepted at create time.
 *
 * Synthesized entries emit **only** `Type`/`Source`/`Target`/`ReadOnly`: the remaining top-level keys
 * (`Destination`, `Name`, `Mode`, `Propagation`, `RW`) are not part of the `HostConfig.Mounts` schema and
 * `/containers/create` rejects unknown fields with a 400. Binds, tmpfs and anonymous entries without a
 * `Name` are left alone — there is nothing to inherit.
 */
internal fun resolveMounts(
    inspectMounts: JsonArray?,
    hostConfig: JsonObject?,
): JsonObject? {
    if (inspectMounts.isNullOrEmpty()) return hostConfig

    val declared = hostConfig?.arr("Mounts")?.mapNotNull { it as? JsonObject } ?: emptyList()
    val boundTargets = hostConfig?.arr("Binds")
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.bindTarget() }
        ?.toSet()
        ?: emptySet()
    val indexByTarget = HashMap<String, Int>()
    declared.forEachIndexed { index, mount -> mount.str("Target")?.let { indexByTarget.putIfAbsent(it, index) } }

    val resolved = declared.toMutableList()
    var changed = false
    for (element in inspectMounts) {
        val mount = element as? JsonObject ?: continue
        if (mount.str("Type") != "volume") continue
        val name = mount.str("Name")?.takeIf { it.isNotEmpty() } ?: continue
        val target = mount.str("Destination")?.takeIf { it.isNotEmpty() } ?: continue

        val declaredIndex = indexByTarget[target]
        if (declaredIndex != null) {
            val spec = resolved[declaredIndex]
            if (!spec.str("Source").isNullOrEmpty()) continue
            resolved[declaredIndex] = JsonObject(spec + ("Source" to JsonPrimitive(name)))
            changed = true
            continue
        }
        if (target in boundTargets) continue
        resolved += buildJsonObject {
            put("Type", JsonPrimitive("volume"))
            put("Source", JsonPrimitive(name))
            put("Target", JsonPrimitive(target))
            put("ReadOnly", JsonPrimitive(mount["RW"]?.jsonPrimitive?.booleanOrNull == false))
        }
        changed = true
    }
    if (!changed) return hostConfig

    return buildJsonObject {
        hostConfig?.forEach { (key, value) -> if (key != "Mounts") put(key, value) }
        put("Mounts", JsonArray(resolved))
    }
}

/** Destination of a legacy `Binds` entry: `source:/target[:opts]`. */
private fun String.bindTarget(): String? =
    split(':').getOrNull(1)?.takeIf { it.isNotEmpty() }

/**
 * Compose's record of the local image id its service currently resolves to — a verbatim copy of the
 * container's `Image` field at create time.
 */
internal const val COMPOSE_IMAGE_LABEL = "com.docker.compose.image"

/**
 * Restamp [COMPOSE_IMAGE_LABEL] with the id kodkod just resolved the tag to.
 *
 * Carrying the *old* id into the replacement is what makes the next `docker compose up` decide the
 * service drifted and recreate the container kodkod had just updated — the label, not the image ref, is
 * what compose compares. Only a real image update ([Target.stale]) restamps: a container recreated
 * because a create-time dependency changed still runs the same image, so its label is already correct.
 *
 * The sibling `com.docker.compose.config-hash` is deliberately **copied verbatim**. It hashes the
 * *service definition in the compose file*, which kodkod never touches; restamping it would paper over
 * a genuine drift between the running container and the compose file. Leaving it alone is only safe
 * because the rest of the create body faithfully reproduces the container — otherwise compose would
 * consider the service converged while kodkod had silently dropped part of its configuration.
 *
 * A container without the label (not managed by compose) never gets one invented.
 */
private fun restampComposeImage(
    labels: JsonObject,
    containerLabels: JsonObject?,
    newComposeImageId: String?,
): JsonObject {
    if (newComposeImageId == null || containerLabels?.containsKey(COMPOSE_IMAGE_LABEL) != true) return labels
    return JsonObject(labels + (COMPOSE_IMAGE_LABEL to JsonPrimitive(newComposeImageId)))
}

/** `ExposedPorts` = (container ports not declared by the image) plus every published `PortBindings` key. */
internal fun mergeExposedPorts(
    containerPorts: JsonObject?,
    imagePorts: JsonObject?,
    portBindings: JsonObject?,
): JsonObject {
    val img = imagePorts ?: EMPTY_OBJECT
    val result = LinkedHashMap<String, JsonElement>()
    containerPorts?.forEach { (port, value) -> if (port !in img) result[port] = value }
    portBindings?.keys?.forEach { result.putIfAbsent(it, EMPTY_OBJECT) }
    return JsonObject(result)
}

/**
 * Build the create-time `Config` for the replacement: copy the running container's `Config`, drop the
 * fields the old image contributed (so the new image's defaults win), and swap in the new image ref.
 * Pure — no Docker calls.
 *
 * [newComposeImageId] is the local image id the tag now resolves to, passed only when this container's
 * own image changed; see [restampComposeImage].
 */
internal fun buildContainerConfig(
    containerConfig: JsonObject,
    imageConfig: JsonObject?,
    hostConfig: JsonObject?,
    oldId: String,
    imageRef: String,
    subtractImageDefaultsByKey: Boolean = false,
    newComposeImageId: String? = null,
): JsonObject {
    val networkMode = hostConfig?.str("NetworkMode").orEmpty()
    val isContainerMode = networkMode.startsWith(NETNS_PREFIX)
    // Docker auto-assigns Config.Hostname to the container's own short id when none was set; carrying
    // that over would give the replacement a stale hostname. Container-mode shares another netns, so it
    // must not carry a hostname at all.
    val dropHostname = isContainerMode || containerConfig.str("Hostname") == oldId.take(12)

    return buildJsonObject {
        for ((key, value) in containerConfig) {
            when (key) {
                "Image" -> {} // set explicitly below
                "Hostname" -> if (!dropHostname) put(key, value)
                "Env" -> if (subtractImageDefaultsByKey) {
                    envKeySubtract(value as? JsonArray, imageConfig?.arr("Env"))
                } else {
                    sliceSubtract(value as? JsonArray, imageConfig?.arr("Env"))
                }
                    .let { if (it.isNotEmpty()) put("Env", it) }
                "Entrypoint" -> if (subtractImageDefaultsByKey) {
                    if (!imageConfig.has("Entrypoint")) put("Entrypoint", value)
                } else {
                    if (!sliceEqual(value as? JsonArray, imageConfig?.arr("Entrypoint"))) put("Entrypoint", value)
                }
                "Cmd" -> {
                    if (subtractImageDefaultsByKey) {
                        if (!imageConfig.has("Cmd")) put("Cmd", value)
                    } else {
                        // Only drop Cmd when the Entrypoint also matched the image — otherwise a custom
                        // entrypoint may rely on the image's Cmd as its arguments.
                        val entrypointMatches = sliceEqual(containerConfig.arr("Entrypoint"), imageConfig?.arr("Entrypoint"))
                        val cmdMatches = sliceEqual(value as? JsonArray, imageConfig?.arr("Cmd"))
                        if (!(entrypointMatches && cmdMatches)) put("Cmd", value)
                    }
                }
                "Healthcheck" -> if (subtractImageDefaultsByKey) {
                    healthcheckSubtractKeys(value as? JsonObject, imageConfig?.obj("Healthcheck"))
                } else {
                    healthcheckSubtract(value as? JsonObject, imageConfig?.obj("Healthcheck"))
                }.let { if (it.isNotEmpty()) put("Healthcheck", it) }
                "Labels" -> if (subtractImageDefaultsByKey) {
                    stringMapSubtractKeys(value as? JsonObject, imageConfig?.obj("Labels"))
                } else {
                    stringMapSubtract(value as? JsonObject, imageConfig?.obj("Labels"))
                }
                    .let { restampComposeImage(it, value as? JsonObject, newComposeImageId) }
                    .let { if (it.isNotEmpty()) put("Labels", it) }
                "Volumes" -> structMapSubtract(value as? JsonObject, imageConfig?.obj("Volumes"))
                    .let { if (it.isNotEmpty()) put("Volumes", it) }
                "WorkingDir" -> if (subtractImageDefaultsByKey) {
                    if (!imageConfig.has("WorkingDir")) put(key, value)
                } else {
                    if (value != imageConfig?.get("WorkingDir")) put(key, value)
                }
                "User" -> if (subtractImageDefaultsByKey) {
                    if (!imageConfig.has("User")) put(key, value)
                } else {
                    if (value != imageConfig?.get("User")) put(key, value)
                }
                "ExposedPorts" -> {} // handled after the loop together with PortBindings
                else -> put(key, value)
            }
        }
        mergeExposedPorts(containerConfig.obj("ExposedPorts"), imageConfig?.obj("ExposedPorts"), hostConfig?.obj("PortBindings"))
            .let { if (it.isNotEmpty()) put("ExposedPorts", it) }
        put("Image", JsonPrimitive(imageRef))
    }
}

/**
 * Assemble the full `/containers/create` body: the subtracted `Config`, the (already container-mode
 * resolved) `HostConfig`, and at most ONE network endpoint — Docker rejects multiple endpoints at create
 * time (docker/docker#29265), so the remaining networks are connected separately after create.
 */
internal fun buildCreateBody(
    containerConfig: JsonObject,
    imageConfig: JsonObject?,
    hostConfig: JsonObject?,
    imageRef: String,
    oldId: String,
    firstNetwork: Pair<String, JsonObject>?,
    subtractImageDefaultsByKey: Boolean = false,
    newComposeImageId: String? = null,
): JsonObject = buildJsonObject {
    buildContainerConfig(
        containerConfig,
        imageConfig,
        hostConfig,
        oldId,
        imageRef,
        subtractImageDefaultsByKey,
        newComposeImageId,
    ).forEach { (k, v) -> put(k, v) }
    if (hostConfig != null) put("HostConfig", hostConfig)
    if (firstNetwork != null) {
        buildJsonObject {
            put(firstNetwork.first, firstNetwork.second)
        }.let { endpoints ->
            put("NetworkingConfig", buildJsonObject { put("EndpointsConfig", endpoints) })
        }
    }
}

private fun JsonObject?.has(key: String): Boolean =
    this != null && key in this

private fun JsonElement.envKey(): String? =
    jsonPrimitive.contentOrNull?.substringBefore('=')
