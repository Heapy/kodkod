package io.heapy.kodkod

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

/** Unit-tests the [ReplayDockerTransport] matching/exhaustion logic in isolation — no Docker. */
class ReplayDockerTransportTest {
    private fun ex(method: String, path: String, status: Int, file: String) =
        RecordedExchange(method, path, requestBodySummary = null, status = status, responseFile = file)

    /** Build a replay transport whose bodies are the response file names themselves, as bytes. */
    private fun replay(vararg exchanges: RecordedExchange) =
        ReplayDockerTransport(exchanges.toList()) { file -> file.toByteArray(StandardCharsets.UTF_8) }

    private fun call(t: ReplayDockerTransport, method: String, path: String): Pair<Int, String> {
        val parsed = DockerApi.parse(t.exchange(method, path, body = null, headers = emptyMap(), readTimeoutMs = 1))
        return parsed.status to parsed.bodyText
    }

    @Test
    fun serves_responses_for_a_key_in_recorded_order() {
        val t = replay(
            ex("GET", "/images/x/json", 200, "old.json"),
            ex("GET", "/images/x/json", 200, "new.json"),
        )
        assertEquals(200 to "old.json", call(t, "GET", "/images/x/json"))
        assertEquals(200 to "new.json", call(t, "GET", "/images/x/json"), "second call to the same key dequeues the next recording")
    }

    @Test
    fun keys_are_independent() {
        val t = replay(
            ex("GET", "/containers/json", 200, "list.json"),
            ex("POST", "/containers/a/stop?t=10", 204, "stop.json"),
        )
        // Consuming one key does not affect the other.
        assertEquals(204 to "stop.json", call(t, "POST", "/containers/a/stop?t=10"))
        assertEquals(200 to "list.json", call(t, "GET", "/containers/json"))
    }

    @Test
    fun preserves_recorded_status_including_errors() {
        val t = replay(ex("GET", "/distribution/x/json", 404, "missing.json"))
        assertEquals(404 to "missing.json", call(t, "GET", "/distribution/x/json"))
    }

    @Test
    fun unknown_request_throws_with_recorded_keys() {
        val t = replay(ex("GET", "/containers/json", 200, "list.json"))
        val e = assertThrows(NoSuchRecordedExchangeException::class.java) {
            call(t, "GET", "/version")
        }
        assertTrue(e.message!!.contains("GET /version"))
        assertTrue(e.message!!.contains("GET /containers/json"), "the error lists the recorded keys")
    }

    @Test
    fun exhausting_a_key_throws() {
        val t = replay(ex("GET", "/containers/json", 200, "list.json"))
        call(t, "GET", "/containers/json")
        assertThrows(RecordedExchangesExhaustedException::class.java) {
            call(t, "GET", "/containers/json")
        }
    }

    @Test
    fun isFullyConsumed_reflects_remaining_responses() {
        val t = replay(
            ex("GET", "/containers/json", 200, "list.json"),
            ex("POST", "/containers/a/start", 204, "start.json"),
        )
        assertFalse(t.isFullyConsumed())
        call(t, "GET", "/containers/json")
        assertFalse(t.isFullyConsumed())
        call(t, "POST", "/containers/a/start")
        assertTrue(t.isFullyConsumed())
    }

    @Test
    fun remaining_names_the_unused_keys_with_their_counts() {
        val t = replay(
            ex("GET", "/images/x/json", 200, "old.json"),
            ex("GET", "/images/x/json", 200, "new.json"),
            ex("GET", "/containers/json", 200, "list.json"),
        )
        call(t, "GET", "/containers/json")
        assertEquals(listOf("GET /images/x/json (x2)"), t.remaining())
    }

    @Test
    fun misses_are_counted_so_a_swallowed_exception_still_shows_up() {
        val t = replay(ex("GET", "/containers/json", 200, "list.json"))
        assertEquals(emptyList<String>(), t.misses)

        // Callers (Updater/Autoheal) catch Docker errors per container, so the throw alone proves nothing.
        runCatching { call(t, "GET", "/version") }
        assertEquals(listOf("GET /version"), t.misses, "an unrecorded request is counted")

        call(t, "GET", "/containers/json")
        runCatching { call(t, "GET", "/containers/json") }
        assertEquals(
            listOf("GET /version", "GET /containers/json"),
            t.misses,
            "an exhausted key is counted too",
        )
    }

    @Test
    fun a_fully_served_run_records_no_misses() {
        val t = replay(ex("GET", "/containers/json", 200, "list.json"))
        call(t, "GET", "/containers/json")
        assertTrue(t.misses.isEmpty())
        assertTrue(t.isFullyConsumed())
        assertTrue(t.remaining().isEmpty())
    }
}
