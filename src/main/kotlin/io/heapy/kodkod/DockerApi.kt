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
 * delimited by the socket close). Production wires a [UnixSocketTransport] over `/var/run/docker.sock`
 * through the secondary `DockerApi(socketPath)` constructor; tests inject a recording or replay
 * transport.
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

    override fun restart(id: String, timeout: Int?, expectedStopSeconds: Int?) {
        val path = "/containers/${enc(id)}/restart${timeoutParam(timeout)}"
        ok(request("POST", path, readTimeoutMs = stopReadTimeoutMs(expectedStopSeconds)))
    }

    override fun stop(id: String, timeout: Int?, expectedStopSeconds: Int?) {
        val path = "/containers/${enc(id)}/stop${timeoutParam(timeout)}"
        ok(request("POST", path, readTimeoutMs = stopReadTimeoutMs(expectedStopSeconds)), 304)
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

    override fun create(name: String, body: JsonObject, platform: String?): String {
        val response = request(
            method = "POST",
            path = "/containers/create?name=${enc(name)}${platformParam(platform)}",
            body = body.toString().toByteArray(StandardCharsets.UTF_8),
            headers = mapOf("Content-Type" to "application/json"),
        )
        ok(response)
        return json.parseToJsonElement(response.bodyText).jsonObject["Id"]!!.jsonPrimitive.content
    }

    /** `GET /images/{ref}/json` — the ref keeps its slashes and colons, which are valid path chars. */
    override fun inspectImage(ref: String): JsonObject = getJson("/images/${encRef(ref)}/json").jsonObject

    override fun removeImage(ref: String) {
        ok(request("DELETE", "/images/${encRef(ref)}?force=false&noprune=false"), 404, 409)
    }

    /** `GET /distribution/{ref}/json` — fetch registry manifest metadata without pulling layers. */
    override fun inspectDistribution(ref: String, registryAuth: String?): JsonObject {
        val headers = buildMap {
            if (registryAuth != null) put("X-Registry-Auth", registryAuth)
        }
        val response = request("GET", "/distribution/${encRef(ref)}/json", headers = headers)
        ok(response)
        return json.parseToJsonElement(response.bodyText).jsonObject
    }

    /**
     * `POST /images/create` — pull an image. Docker answers 200 and streams newline-delimited
     * JSON progress objects; a failed pull surfaces an `error` field in the stream, so we scan for it.
     */
    override fun pull(fromImage: String, tag: String, registryAuth: String?, platform: String?) {
        val headers = buildMap {
            if (registryAuth != null) put("X-Registry-Auth", registryAuth)
        }
        val response = request(
            method = "POST",
            path = "/images/create?fromImage=${enc(fromImage)}&tag=${enc(tag)}${platformParam(platform)}",
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

    /**
     * An image reference as a *path* segment. Unlike [enc] this keeps the characters a reference is
     * made of — `/`, `:`, `@`, `.`, `_`, `-`, `+` — since the engine routes on `{name:.*}` and expects
     * them literally. Everything else is percent-encoded: a reference comes from a container's
     * `Config.Image`, so a space or a `?` in it would otherwise end up rewriting the request target.
     */
    private fun encRef(ref: String): String = buildString(ref.length) {
        for (byte in ref.toByteArray(StandardCharsets.UTF_8)) {
            val char = byte.toInt().toChar()
            if (byte >= 0 && (char.isLetterOrDigit() || char in REF_SAFE)) append(char) else append("%%%02X".format(byte))
        }
    }

    /**
     * `&platform=os%2Farch`, or nothing at all. The parameter is omitted rather than sent empty: an
     * empty `platform=` is a malformed platform spec to the daemon, not "no preference".
     */
    private fun platformParam(platform: String?): String =
        platform?.takeIf { it.isNotBlank() }?.let { "&platform=${enc(it)}" }.orEmpty()

    /**
     * `?t=<seconds>`, or nothing at all. Omitting the parameter is not the same as sending the API
     * default: it makes the daemon fall back to the container's own `Config.StopTimeout`.
     */
    private fun timeoutParam(timeout: Int?): String = timeout?.let { "?t=$it" }.orEmpty()

    /**
     * How long to wait for a stop/restart response: the graceful window plus headroom for the
     * SIGKILL and teardown that follow it, never below the default. Without this a container with a
     * long `StopTimeout` would have its (perfectly healthy) stop reported as a read timeout.
     */
    private fun stopReadTimeoutMs(expectedStopSeconds: Int?): Long =
        maxOf(DEFAULT_READ_TIMEOUT_MS, ((expectedStopSeconds ?: 0) + 15).toLong() * 1_000)

    // --- HTTP/1.1 request/response handling -----------------------------------------------

    internal class HttpResponse(val status: Int, val body: ByteArray) {
        val bodyText: String get() = String(body, StandardCharsets.UTF_8)
    }

    private fun request(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    ): HttpResponse = parse(transport.exchange(method, path, body, headers, readTimeoutMs))

    internal companion object {
        /** Idle read timeout for calls that do not size their own, and the floor for those that do. */
        private const val DEFAULT_READ_TIMEOUT_MS = 60_000L

        /** Non-alphanumeric characters an image reference may carry into a request path unencoded. */
        private const val REF_SAFE = "/:@._-+~"

        private val CRLF = "\r\n".toByteArray(StandardCharsets.US_ASCII)
        private val CRLF_CRLF = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)

        /** Split raw HTTP/1.1 response bytes into a status + body, decoding a chunked body. */
        internal fun parse(raw: ByteArray): HttpResponse {
            val separator = indexOf(raw, CRLF_CRLF, 0)
                ?: throw DockerException(-1, "malformed http response (no header terminator)")

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

        /**
         * Decode a `Transfer-Encoding: chunked` body into the concatenated chunk payloads.
         *
         * A body that ends without its terminating `0` chunk, or whose chunk size cannot be read, is a
         * **truncated response** — the connection was cut mid-answer. Returning what arrived so far
         * makes that look like a short but complete answer, which downstream reads as "the daemon says
         * there are no containers" or "this image has no tags". So it is an error instead.
         */
        internal fun dechunk(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            var pos = 0
            while (pos < data.size) {
                val lineEnd = indexOf(data, CRLF, pos)
                    ?: throw DockerException(-1, "truncated chunked body: no chunk header at byte $pos")
                val sizeToken = String(data, pos, lineEnd - pos, StandardCharsets.US_ASCII)
                    .substringBefore(';').trim()
                // RFC 9112 chunk-size is 1*HEXDIG — no sign, no prefix — but `toIntOrNull(16)` accepts
                // one. Both spellings it lets through are dangerous: "-1" becomes a negative length
                // handed to a byte-range copy, and "-0" becomes a counterfeit terminator that ends the
                // body early and returns the prefix that arrived as if it were the whole answer.
                val size = sizeToken.takeIf { token -> token.isNotEmpty() && token.all { it.digitToIntOrNull(16) != null } }
                    ?.toIntOrNull(16)
                    ?: throw DockerException(-1, "malformed chunked body: unreadable chunk size '$sizeToken'")
                pos = lineEnd + CRLF.size
                if (size == 0) return out.toByteArray()
                // Subtraction, not `pos + size > data.size`: that sum overflows to a negative number
                // for a size near Int.MAX_VALUE, which turns the bounds check into its own opposite
                // and hands the range straight to the copy below. `pos <= data.size` here, so the
                // difference cannot overflow.
                if (size > data.size - pos) {
                    throw DockerException(-1, "truncated chunked body: chunk of $size bytes cut short")
                }
                out.write(data, pos, size)
                pos += size + CRLF.size // skip the chunk data plus its trailing CRLF
            }
            throw DockerException(-1, "truncated chunked body: the terminating zero-length chunk never arrived")
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int? {
            outer@ for (i in from..haystack.size - needle.size) {
                for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
                return i
            }
            return null
        }
    }
}
