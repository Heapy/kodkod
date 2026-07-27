package io.heapy.kodkod

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class JsonTest {
    private fun jsonObj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun accessors_read_or_return_null() {
        val o = jsonObj("""{"Id":"abc","Config":{"x":1},"Names":["/a","/b"]}""")
        assertEquals("abc", o.str("Id"))
        assertNull(o.str("missing"))
        assertEquals(1, o.obj("Config")!!.size)
        assertEquals("/a", o.arr("Names").firstString())
        assertNull(o.arr("missing").firstString())
    }

    @Test
    fun labelTruthy_uses_default_when_absent() {
        val labels = jsonObj("""{"a":"true","b":"false","c":"1"}""")
        assertTrue(labelTruthy(labels, "a", default = false))
        assertFalse(labelTruthy(labels, "b", default = true))
        assertTrue(labelTruthy(labels, "c", default = false))
        assertTrue(labelTruthy(labels, "missing", default = true))
        assertFalse(labelTruthy(labels, "missing", default = false))
        assertTrue(labelTruthy(null, "x", default = true))
    }

    /**
     * The three ways a `State` timestamp can fail to name a moment have to read the same, because they
     * mean the same thing to every caller: the event is not on record. `0001-01-01T00:00:00Z` is the one
     * that parses — it is what the daemon writes for "this never happened" (a container that has never
     * stopped, or is paused) — and taken at face value it is a real instant two millennia before the
     * epoch, which makes any duration measured against it enormous and negative.
     */
    @Test
    fun dockerTime_reads_only_a_timestamp_that_names_a_moment() {
        val state = jsonObj(
            """{"StartedAt":"2026-07-01T10:00:00.000000000Z","FinishedAt":"0001-01-01T00:00:00Z",
               "PausedAt":"","BrokenAt":"not a time"}""",
        )
        assertEquals(Instant.parse("2026-07-01T10:00:00Z").toEpochMilli(), state.dockerTime("StartedAt"))
        assertNull(state.dockerTime("FinishedAt"), "the daemon's `never happened` sentinel is not a moment")
        assertNull(state.dockerTime("PausedAt"))
        assertNull(state.dockerTime("BrokenAt"))
        assertNull(state.dockerTime("Missing"))
    }

    @Test
    fun isSelf_matches_hostname_or_self_label() {
        assertTrue(isSelf("abcdef123456", labels = null, selfId = "abcdef"))
        assertFalse(isSelf("abcdef123456", labels = null, selfId = "123456"))

        assertTrue(isSelf("abcdef123456", labels = jsonObj("""{"$SELF_LABEL":"true"}"""), selfId = null))
        assertFalse(isSelf("abcdef123456", labels = jsonObj("""{"$SELF_LABEL":"false"}"""), selfId = null))
    }
}
