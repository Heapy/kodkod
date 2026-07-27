package io.heapy.kodkod

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Raised for non-2xx Docker API responses (or transport errors). */
class DockerException(val status: Int, message: String) : RuntimeException("docker api error ($status): $message")

/**
 * A tiny Docker Engine API client. It builds the request, hands the bytes to a [DockerTransport],
 * and parses the raw HTTP/1.1 response (`Transfer-Encoding: chunked` decoded here, or simply
 * delimited by the socket close). Production wires a [UnixSocketTransport] over
 * `/var/run/docker.sock` via the [secondary][DockerApi] socket-path constructor; tests inject a
 * recording or replay transport.
 */
class DockerApi(private val transport: DockerTransport) : DockerClient {

    /** Production entry point: talk to the Docker engine over the unix socket at [socketPath]. */
    constructor(socketPath: String) : this(UnixSocketTransport(socketPath))

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // --- High level helpers ---------------------------------------------------------------

    override fun version(): JsonObject = getJson("/version").jsonObject

    override fun listContainers(all: Boolean, filters: Map<String, List<String>>): JsonArray {
        val filterJson = buildJsonObject {
            filters.forEach { (key, values) -> putJsonArray(key) { values.forEach { add(it) } } }
        }.toString()
        val query = "all=$all&filters=${enc(filterJson)}"
        return getJson("/containers/json?$query").jsonArray
    }

    override fun inspectContainer(id: String): JsonObject = getJson("/containers/${enc(id)}/json").jsonObject

    override fun restart(id: String, timeout: Int) {
        ok(request("POST", "/containers/${enc(id)}/restart?t=$timeout"))
    }

    override fun stop(id: String, timeout: Int) {
        ok(request("POST", "/containers/${enc(id)}/stop?t=$timeout"), 304)
    }

    override fun start(id: String) {
        ok(request("POST", "/containers/${enc(id)}/start"), 304)
    }

    override fun rename(id: String, name: String) {
        ok(request("POST", "/containers/${enc(id)}/rename?name=${enc(name)}"))
    }

    override fun remove(id: String, force: Boolean) {
        ok(request("DELETE", "/containers/${enc(id)}?force=$force&v=false"), 404)
    }

    /** `POST /networks/{id}/connect` — attach an already-created container to another network. */
    override fun connectNetwork(network: String, containerId: String, endpoint: JsonObject) {
        val body = buildJsonObject {
            put("Container", containerId)
            put("EndpointConfig", endpoint)
        }
        ok(
            request(
                method = "POST",
                path = "/networks/${enc(network)}/connect",
                body = body.toString().toByteArray(StandardCharsets.UTF_8),
                headers = mapOf("Content-Type" to "application/json"),
            ),
        )
    }

    override fun create(name: String, body: JsonObject): String {
        val response = request(
            method = "POST",
            path = "/containers/create?name=${enc(name)}",
            body = body.toString().toByteArray(StandardCharsets.UTF_8),
            headers = mapOf("Content-Type" to "application/json"),
        )
        ok(response)
        return json.parseToJsonElement(response.bodyText).jsonObject["Id"]!!.jsonPrimitive.content
    }

    /** `GET /images/{ref}/json` — note the ref (repo/tag) is kept raw; its slashes/colons are valid path chars. */
    override fun inspectImage(ref: String): JsonObject = getJson("/images/$ref/json").jsonObject

    override fun removeImage(ref: String) {
        ok(request("DELETE", "/images/$ref?force=false&noprune=false"), 404, 409)
    }

    /** `GET /distribution/{ref}/json` — fetch registry manifest metadata without pulling layers. */
    override fun inspectDistribution(ref: String, registryAuth: String?): JsonObject {
        val headers = buildMap {
            if (registryAuth != null) put("X-Registry-Auth", registryAuth)
        }
        val response = request("GET", "/distribution/$ref/json", headers = headers)
        ok(response)
        return json.parseToJsonElement(response.bodyText).jsonObject
    }

    /**
     * `POST /images/create` — pull an image. Docker answers 200 and streams newline-delimited
     * JSON progress objects; a failed pull surfaces an `error` field in the stream, so we scan for it.
     */
    override fun pull(fromImage: String, tag: String, registryAuth: String?) {
        val headers = buildMap {
            if (registryAuth != null) put("X-Registry-Auth", registryAuth)
        }
        val response = request(
            method = "POST",
            path = "/images/create?fromImage=${enc(fromImage)}&tag=${enc(tag)}",
            headers = headers,
            readTimeoutMs = 600_000, // pulls can be slow; allow up to 10 minutes of idle gap
        )
        ok(response)
        for (rawLine in response.bodyText.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val element = runCatching { json.parseToJsonElement(line) }.getOrNull() ?: continue
            (element as? JsonObject)?.str("error")?.let { throw DockerException(-1, "pull failed: $it") }
        }
    }

    // --- Response handling ----------------------------------------------------------------

    private fun getJson(path: String) = request("GET", path).let { ok(it); json.parseToJsonElement(it.bodyText) }

    private fun ok(response: HttpResponse, vararg allowed: Int) {
        if (response.status in 200..299 || response.status in allowed) return
        throw DockerException(response.status, response.bodyText.take(500))
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    // --- HTTP/1.1 request/response handling -----------------------------------------------

    internal class HttpResponse(val status: Int, val body: ByteArray) {
        val bodyText: String get() = String(body, StandardCharsets.UTF_8)
    }

    private fun request(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        readTimeoutMs: Long = 60_000,
    ): HttpResponse = parse(transport.exchange(method, path, body, headers, readTimeoutMs))

    internal companion object {
        private val CRLF = "\r\n".toByteArray(StandardCharsets.US_ASCII)
        private val CRLF_CRLF = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)

        /** Split raw HTTP/1.1 response bytes into a status + body, decoding a chunked body. */
        internal fun parse(raw: ByteArray): HttpResponse {
            val separator = indexOf(raw, CRLF_CRLF, 0)
            if (separator < 0) throw DockerException(-1, "malformed http response (no header terminator)")

            val headerText = String(raw, 0, separator, StandardCharsets.US_ASCII)
            val lines = headerText.split("\r\n")
            val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull()
                ?: throw DockerException(-1, "malformed status line: ${lines.first()}")

            val chunked = lines.drop(1).any {
                val colon = it.indexOf(':')
                colon > 0 &&
                    it.substring(0, colon).trim().equals("Transfer-Encoding", ignoreCase = true) &&
                    it.substring(colon + 1).trim().equals("chunked", ignoreCase = true)
            }

            val bodyStart = separator + CRLF_CRLF.size
            val body = raw.copyOfRange(bodyStart, raw.size)
            return HttpResponse(status, if (chunked) dechunk(body) else body)
        }

        /** Decode a `Transfer-Encoding: chunked` body into the concatenated chunk payloads. */
        internal fun dechunk(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            var pos = 0
            while (pos < data.size) {
                val lineEnd = indexOf(data, CRLF, pos)
                if (lineEnd < 0) break
                val sizeToken = String(data, pos, lineEnd - pos, StandardCharsets.US_ASCII)
                    .substringBefore(';').trim()
                val size = sizeToken.toIntOrNull(16) ?: break
                pos = lineEnd + CRLF.size
                if (size == 0) break
                if (pos + size > data.size) break
                out.write(data, pos, size)
                pos += size + CRLF.size // skip the chunk data plus its trailing CRLF
            }
            return out.toByteArray()
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
            outer@ for (i in from..haystack.size - needle.size) {
                for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
