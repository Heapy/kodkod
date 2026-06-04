package io.heapy.kodkod

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The slice of the Docker Engine API that kodkod actually calls. [DockerApi] is the production
 * implementation (HTTP/1.1 over the unix socket); tests substitute an in-memory fake so the
 * autoheal and update orchestration — [Autoheal] and [Updater] — can be exercised without a live
 * daemon.
 *
 * Every method maps to a single Engine endpoint and raises [DockerException] on an unexpected
 * non-2xx response. Status codes that are deliberately tolerated (because they mean "already in the
 * desired state") are called out per method.
 */
interface DockerClient {
    /** `GET /version` — engine version metadata, used for the startup connectivity check. */
    fun version(): JsonObject

    /** `GET /containers/json` — containers matching [filters] (`all=false` keeps it to running ones). */
    fun listContainers(all: Boolean, filters: Map<String, List<String>>): JsonArray

    /** `GET /containers/{id}/json` — the full inspect payload for one container. */
    fun inspectContainer(id: String): JsonObject

    /** `POST /containers/{id}/restart` — stop then start, allowing [timeout]s for the graceful stop. */
    fun restart(id: String, timeout: Int)

    /** `POST /containers/{id}/stop` — graceful stop with a [timeout]s deadline; tolerates "already stopped". */
    fun stop(id: String, timeout: Int)

    /** `POST /containers/{id}/start` — start a created/stopped container; tolerates "already started". */
    fun start(id: String)

    /** `POST /containers/{id}/rename` — give an existing container a new [name]. */
    fun rename(id: String, name: String)

    /** `DELETE /containers/{id}` — remove a container (tolerating "already gone"); named volumes are kept. */
    fun remove(id: String, force: Boolean)

    /** `POST /networks/{network}/connect` — attach an already-created container to a further network. */
    fun connectNetwork(network: String, containerId: String, endpoint: JsonObject)

    /** `POST /containers/create` — create a container named [name] from [body]; returns the new id. */
    fun create(name: String, body: JsonObject): String

    /** `GET /images/{ref}/json` — inspect a local image by `repo:tag`, digest, or id. */
    fun inspectImage(ref: String): JsonObject

    /** `DELETE /images/{ref}` — best-effort local image removal (tolerates "in use" / "absent"). */
    fun removeImage(ref: String)

    /** `GET /distribution/{ref}/json` — registry manifest metadata, fetched without pulling layers. */
    fun inspectDistribution(ref: String, registryAuth: String?): JsonObject

    /** `POST /images/create` — pull [fromImage]:[tag]; throws if the progress stream reports an error. */
    fun pull(fromImage: String, tag: String, registryAuth: String?)
}
