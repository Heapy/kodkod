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

    private val NO_CONTENT = "HTTP/1.1 204 No Content\r\n\r\n"

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

    @Test
    fun stop_without_a_timeout_sends_no_t_parameter() {
        val transport = CapturingTransport(NO_CONTENT)

        DockerApi(transport).stop("web", timeout = null)

        assertEquals(
            listOf("POST /containers/web/stop"),
            transport.requests,
            "no ?t= at all is what makes the daemon fall back to the container's own Config.StopTimeout",
        )
        assertEquals(listOf(60_000L), transport.readTimeouts, "nothing known about the window -> the 60s floor")
    }

    @Test
    fun stop_sends_the_timeout_it_was_given() {
        val transport = CapturingTransport(NO_CONTENT)

        DockerApi(transport).stop("web", timeout = 30)

        assertEquals(listOf("POST /containers/web/stop?t=30"), transport.requests)
        assertEquals(listOf(60_000L), transport.readTimeouts, "30s + headroom is still under the floor")
    }

    @Test
    fun a_long_graceful_window_stretches_the_read_timeout() {
        val transport = CapturingTransport(NO_CONTENT)

        // The container's own StopTimeout is 120s: not sent to the daemon, but we must wait for it.
        DockerApi(transport).stop("web", timeout = null, expectedStopSeconds = 120)

        assertEquals(listOf("POST /containers/web/stop"), transport.requests)
        assertEquals(listOf(135_000L), transport.readTimeouts, "120s window + 15s headroom for SIGKILL and teardown")
    }

    @Test
    fun restart_sizes_its_read_timeout_the_same_way() {
        val transport = CapturingTransport(NO_CONTENT)

        DockerApi(transport).restart("web", timeout = 120)

        assertEquals(listOf("POST /containers/web/restart?t=120"), transport.requests)
        assertEquals(listOf(135_000L), transport.readTimeouts)
    }

    /**
     * An image reference comes out of a container's own `Config.Image`, which kodkod never validated —
     * so it must not be able to rewrite the request target. Its own punctuation still has to survive:
     * the engine routes these on `{name:.*}` and resolves `registry:5000/repo:tag` literally.
     */
    @Test
    fun an_image_reference_keeps_its_own_punctuation_and_nothing_else() {
        val transport = CapturingTransport("HTTP/1.1 200 OK\r\n\r\n{}")
        val api = DockerApi(transport)

        api.inspectImage("127.0.0.1:5000/team/app:v1.2_beta")
        api.inspectImage("sha256:abc123")
        api.removeImage("app:1 --force ?x=y")

        assertEquals(
            listOf(
                "GET /images/127.0.0.1:5000/team/app:v1.2_beta/json",
                "GET /images/sha256:abc123/json",
                "DELETE /images/app:1%20--force%20%3Fx%3Dy?force=false&noprune=false",
            ),
            transport.requests,
        )
    }

    /** Answers every exchange with the same canned response, recording `<method> <path>` and the timeout. */
    private class CapturingTransport(private val response: String) : DockerTransport {
        val requests = mutableListOf<String>()
        val readTimeouts = mutableListOf<Long>()

        override fun exchange(
            method: String,
            path: String,
            body: ByteArray?,
            headers: Map<String, String>,
            readTimeoutMs: Long,
        ): ByteArray {
            requests += "$method $path"
            readTimeouts += readTimeoutMs
            return response.toByteArray(StandardCharsets.UTF_8)
        }
    }
}
