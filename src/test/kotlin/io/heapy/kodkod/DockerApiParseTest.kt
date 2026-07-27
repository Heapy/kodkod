package io.heapy.kodkod

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

/**
 * Exercises [DockerApi]'s plumbing in isolation — no socket is opened: the hand-rolled HTTP/1.1
 * parsing against raw bytes, and the request targets it builds, against a transport that only records
 * what it was asked for.
 */
class DockerApiParseTest {
    private fun bytes(s: String) = s.toByteArray(StandardCharsets.UTF_8)

    @Test
    fun parse_plain_response() {
        val res = DockerApi.parse(bytes("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"ok\":true}"))
        assertEquals(200, res.status)
        assertEquals("{\"ok\":true}", res.bodyText)
    }

    @Test
    fun parse_chunked_response() {
        val body = "4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n"
        val res = DockerApi.parse(bytes("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n$body"))
        assertEquals(200, res.status)
        assertEquals("Wikipedia", res.bodyText)
    }

    @Test
    fun parse_error_status() {
        val res = DockerApi.parse(bytes("HTTP/1.1 404 Not Found\r\n\r\nnope"))
        assertEquals(404, res.status)
        assertEquals("nope", res.bodyText)
    }

    @Test
    fun dechunk_decodes_multiple_chunks() {
        assertEquals("abcde", String(DockerApi.dechunk(bytes("3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n")), StandardCharsets.UTF_8))
    }

    // --- request targets ------------------------------------------------------------------

    @Test
    fun create_pins_the_platform_when_one_is_given() {
        val transport = CapturingTransport("HTTP/1.1 201 Created\r\n\r\n{\"Id\":\"abc\"}")

        DockerApi(transport).create("web", JsonObject(emptyMap()), platform = "linux/amd64")

        assertEquals(
            listOf("POST /containers/create?name=web&platform=linux%2Famd64"),
            transport.requests,
            "the daemon re-resolves a multi-arch image at create time, so the arch must be pinned there too",
        )
    }

    @Test
    fun create_omits_the_platform_parameter_when_there_is_none() {
        val transport = CapturingTransport("HTTP/1.1 201 Created\r\n\r\n{\"Id\":\"abc\"}")

        DockerApi(transport).create("web", JsonObject(emptyMap()), platform = null)

        assertEquals(listOf("POST /containers/create?name=web"), transport.requests)
    }

    @Test
    fun pull_pins_the_platform_when_one_is_given() {
        val transport = CapturingTransport("HTTP/1.1 200 OK\r\n\r\n{\"status\":\"Downloaded\"}")

        DockerApi(transport).pull("nginx", "1.27", registryAuth = null, platform = "linux/amd64")

        assertEquals(
            listOf("POST /images/create?fromImage=nginx&tag=1.27&platform=linux%2Famd64"),
            transport.requests,
        )
    }

    @Test
    fun pull_omits_the_platform_parameter_when_there_is_none() {
        val transport = CapturingTransport("HTTP/1.1 200 OK\r\n\r\n{\"status\":\"Downloaded\"}")

        DockerApi(transport).pull("nginx", "1.27", registryAuth = null, platform = null)

        assertEquals(listOf("POST /images/create?fromImage=nginx&tag=1.27"), transport.requests)
    }

    /** Answers every exchange with the same canned response, recording `<method> <path>`. */
    private class CapturingTransport(private val response: String) : DockerTransport {
        val requests = mutableListOf<String>()

        override fun exchange(
            method: String,
            path: String,
            body: ByteArray?,
            headers: Map<String, String>,
            readTimeoutMs: Long,
        ): ByteArray {
            requests += "$method $path"
            return response.toByteArray(StandardCharsets.UTF_8)
        }
    }
}
