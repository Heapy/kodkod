package io.heapy.kodkod

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Helpers every test file in this source set needs. They used to be private copies — ten of the parse
 * helper alone, under three different names — which is how two of them ended up subtly different from
 * the rest without anyone noticing.
 */

/**
 * A JSON object written out by hand, the way the daemon's payloads are stated in these tests. Docker's
 * answers are loosely typed and mostly optional, so a fixture that spells only the fields the code
 * under test reads says more about the contract than a builder would.
 */
internal fun jsonObj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

/**
 * Run [block] with stdout captured, and return what it printed.
 *
 * [Log] writes to stdout, and several behaviours have no other output than the line they log — "the
 * rollback did not restore the service", "this wait was truncated" — so for those the log *is* the
 * observable. Stdout is restored even when [block] throws.
 */
internal fun captureLog(block: () -> Unit): String {
    val buffer = ByteArrayOutputStream()
    val original = System.out
    System.setOut(PrintStream(buffer, true))
    try {
        block()
    } finally {
        System.setOut(original)
    }
    return buffer.toString()
}

/**
 * A `/containers/json` summary as the daemon returns it — the shape both create-time relations are
 * discovered from, since neither is declared on the provider's side.
 *
 * [networkMode] is `container:<id|name>` for a shared network namespace, and [links] the raw JSON of a
 * legacy `--link` list (`"provider:db"`), which a listing carries per network endpoint rather than
 * under `HostConfig`. [project] is the compose label the dependent scan narrows by before it widens.
 */
internal fun containerSummary(
    id: String,
    name: String = id,
    state: String = "running",
    networkMode: String = "stack_default",
    project: String? = "stack",
    labels: String = "",
    links: String? = null,
): JsonObject {
    val projectLabel = project?.let { """"$COMPOSE_PROJECT_LABEL":"$it"""" }
    val allLabels = listOfNotNull(projectLabel, labels.takeIf { it.isNotEmpty() }).joinToString(",")
    val networks = links?.let { """{"bridge":{"Links":[$it]}}""" } ?: """{"$networkMode":{}}"""
    return jsonObj(
        """{"Id":"$id","Names":["/$name"],"State":"$state","Labels":{$allLabels},
           "HostConfig":{"NetworkMode":"$networkMode"},"NetworkSettings":{"Networks":$networks}}""",
    )
}
