package io.heapy.kodkod

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
}
