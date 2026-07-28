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
 * A [Config] built from exactly these variables and nothing else. Every suite here needs one and
 * [Config.fromEnv] reads a `(String) -> String?`, which is a lookup no test wants to spell out — and
 * used to spell out three different ways, each with its own `buildMap` of conditional `put`s.
 *
 * A `null` value drops the variable rather than passing it as `""`, so the suites' optional knobs
 * (`stopTimeout: String? = null` and friends) can be forwarded as they are. The two are worth telling
 * apart even where `fromEnv` currently treats a blank value as unset anyway
 * ([ConfigTest.an_empty_value_falls_back_to_the_default_rather_than_to_false] is what pins that): an
 * unset variable is one `System.getenv` answers `null` for, and a helper should hand the code under
 * test the input production actually produces rather than one that happens to be equivalent today.
 */
internal fun configOf(vararg env: Pair<String, String?>): Config {
    val set = env.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
    return Config.fromEnv(set::get)
}

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

/**
 * Register a running, update-eligible container plus its current image. Test bodies then layer on
 * `distribution`/`images` entries to drive the specific staleness path under test.
 *
 * Shared rather than private to one file: the generated worlds in [UpdateCycleModelTest] have to be
 * built out of the same pieces the hand-written ones are, or the two disagree about what a container
 * even looks like.
 */
internal fun FakeDockerClient.container(
    id: String,
    name: String = id,
    imageRef: String = "img:1",
    currentImageId: String = "sha256:$id-old",
    currentRepoDigests: List<String> = emptyList(),
    labels: String = "{}",
    hostConfig: String = "{}",
    networks: String = "{}",
    /** Top-level `Mounts[]`, the only place an anonymous volume's generated name appears. */
    mounts: String = "[]",
    configMacAddress: String? = null,
    configStopTimeout: Int? = null,
    imageManifestPlatform: String? = null,
    state: String = "running",
) {
    val repoDigests = currentRepoDigests.joinToString(",", "[", "]") { "\"$it\"" }
    val mac = configMacAddress?.let { ",\"MacAddress\":\"$it\"" } ?: ""
    // `docker run --stop-timeout` / compose `stop_grace_period`, as the daemon records it.
    val stopTimeout = configStopTimeout?.let { ",\"StopTimeout\":$it" } ?: ""
    // Engines that report it put the resolved manifest (and its platform) on the container inspect.
    val manifest = imageManifestPlatform?.let { ""","ImageManifestDescriptor":{"platform":$it}""" } ?: ""
    // The listing carries names, state, `HostConfig.NetworkMode` and the endpoints' `Links` as the
    // daemon does — that is all a create-time dependency of another container can be recognised from.
    listed += Json.parseToJsonElement(
        """{"Id":"$id","Names":["/$name"],"State":"$state","Labels":$labels,""" +
            """"HostConfig":$hostConfig,"NetworkSettings":{"Networks":$networks}}""",
    ).jsonObject
    containers[id] = Json.parseToJsonElement(
        """{"Name":"/$name","Image":"$currentImageId","Mounts":$mounts,""" +
            """"Config":{"Image":"$imageRef","Labels":$labels$mac$stopTimeout},""" +
            """"HostConfig":$hostConfig,"NetworkSettings":{"Networks":$networks}$manifest}""",
    ).jsonObject
    images[currentImageId] = Json.parseToJsonElement(
        """{"Id":"$currentImageId","Config":{},"RepoDigests":$repoDigests}""",
    ).jsonObject
}
