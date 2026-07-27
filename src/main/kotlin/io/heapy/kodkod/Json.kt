package io.heapy.kodkod

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/** Values that are treated as a truthy label/env value. */
val TRUTHY = setOf("true", "1", "yes", "on")

/**
 * Label baked into kodkod's own image (see Dockerfile) so it never restarts or updates itself,
 * regardless of `HOSTNAME`. Deliberately *not* under [Config.labelNamespace] — it identifies the
 * binary, not a user opt-in.
 */
const val SELF_LABEL = "io.heapy.kodkod.self"

/** kodkod never acts on itself: matched by its baked-in [SELF_LABEL], or by HOSTNAME as a fallback. */
fun isSelf(id: String, labels: JsonObject?, selfId: String?): Boolean {
    if (selfId != null && id.startsWith(selfId)) return true
    return labelTruthy(labels, SELF_LABEL, false)
}

val EMPTY_OBJECT = JsonObject(emptyMap())

val EMPTY_ARRAY = JsonArray(emptyList())

/** Convenience accessors over the loosely-typed JSON we get back from the Docker Engine API. */
fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/**
 * A `State` timestamp ([key] being `StartedAt` / `FinishedAt`) as epoch millis. `null` when the field is
 * absent, unparsable, or carries the `0001-01-01T00:00:00Z` the daemon writes for "this never happened"
 * — all three mean the same thing to a caller: the event is not on record. The daemon and kodkod share
 * a host (the socket is local), so these are comparable with [WallClock.millis] and with each other.
 */
internal fun JsonObject.dockerTime(key: String): Long? =
    str(key)
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?.toEpochMilli()
        ?.takeIf { it > 0 }

/** Read a container label, tolerating a missing `Labels` map. */
fun JsonObject?.label(key: String): String? = this?.get(key)?.jsonPrimitive?.contentOrNull

/**
 * Every name a `/containers/json` summary says its container answers to, without the leading slash
 * Docker prefixes them with. A container has more than one when something links to it.
 */
fun JsonObject.containerNames(): List<String> =
    arr("Names")
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trimStart('/')?.takeIf(String::isNotEmpty) }
        ?: emptyList()

/** Interpret a boolean label, falling back to [default] when the label is absent. */
fun labelTruthy(labels: JsonObject?, key: String, default: Boolean): Boolean {
    val value = labels.label(key) ?: return default
    return value.trim().lowercase() in TRUTHY
}
