package io.heapy.kodkod

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals

/** Exercises the hand-rolled HTTP/1.1 parsing in isolation — no socket is opened. */
class DockerApiParseTest {
    private val api = DockerApi("/tmp/kodkod-nonexistent.sock")
    private fun bytes(s: String) = s.toByteArray(StandardCharsets.UTF_8)

    @Test
    fun parse_plain_response() {
        val res = api.parse(bytes("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"ok\":true}"))
        assertEquals(200, res.status)
        assertEquals("{\"ok\":true}", res.bodyText)
    }

    @Test
    fun parse_chunked_response() {
        val body = "4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n"
        val res = api.parse(bytes("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n$body"))
        assertEquals(200, res.status)
        assertEquals("Wikipedia", res.bodyText)
    }

    @Test
    fun parse_error_status() {
        val res = api.parse(bytes("HTTP/1.1 404 Not Found\r\n\r\nnope"))
        assertEquals(404, res.status)
        assertEquals("nope", res.bodyText)
    }

    @Test
    fun dechunk_decodes_multiple_chunks() {
        assertEquals("abcde", String(api.dechunk(bytes("3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n")), StandardCharsets.UTF_8))
    }
}
