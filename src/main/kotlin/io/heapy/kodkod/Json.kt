package io.heapy.kodkod

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Values that are treated as a truthy label/env value. */
val TRUTHY = setOf("true", "1", "yes", "on")

/**
 * Label baked into kodkod's own image (see Dockerfile) so it never restarts or updates itself,
 * regardless of `HOSTNAME`. Deliberately *not* under [Config.labelNamespace] — it identifies the
 * binary, not a user opt-in.
 */
const val SELF_LABEL = "io.heapy.kodkod.self"

val EMPTY_OBJECT = JsonObject(emptyMap())

val EMPTY_ARRAY = JsonArray(emptyList())

/** Convenience accessors over the loosely-typed JSON we get back from the Docker Engine API. */
fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** Read a container label, tolerating a missing `Labels` map. */
fun JsonObject?.label(key: String): String? = this?.get(key)?.jsonPrimitive?.contentOrNull

/** First entry of a JSON string array, or null. */
fun JsonArray?.firstString(): String? = this?.firstOrNull()?.jsonPrimitive?.contentOrNull

/** Interpret a boolean label, falling back to [default] when the label is absent. */
fun labelTruthy(labels: JsonObject?, key: String, default: Boolean): Boolean {
    val value = labels.label(key) ?: return default
    return value.trim().lowercase() in TRUTHY
}
