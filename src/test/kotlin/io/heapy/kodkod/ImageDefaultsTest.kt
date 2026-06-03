package io.heapy.kodkod

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageDefaultsTest {
    private fun jsonObj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun jsonArr(s: String): JsonArray = Json.parseToJsonElement(s) as JsonArray

    @Test
    fun sliceEqual_handles_null_and_order() {
        assertTrue(sliceEqual(null, null))
        assertTrue(sliceEqual(null, jsonArr("[]")))
        assertTrue(sliceEqual(jsonArr("""["a","b"]"""), jsonArr("""["a","b"]""")))
        assertFalse(sliceEqual(jsonArr("""["a","b"]"""), jsonArr("""["b","a"]""")))
        assertFalse(sliceEqual(jsonArr("""["a"]"""), jsonArr("""["a","b"]""")))
    }

    @Test
    fun sliceSubtract_removes_exact_entries() {
        assertEquals(
            jsonArr("""["B=2"]"""),
            sliceSubtract(jsonArr("""["A=1","B=2","C=3"]"""), jsonArr("""["A=1","C=3"]""")),
        )
    }

    @Test
    fun stringMapSubtract_keeps_absent_or_differing() {
        val result = stringMapSubtract(jsonObj("""{"a":"1","b":"2","c":"3"}"""), jsonObj("""{"a":"1","b":"9"}"""))
        // a removed (same value), b kept (different value), c kept (absent from image)
        assertEquals(jsonObj("""{"b":"2","c":"3"}"""), result)
    }

    @Test
    fun structMapSubtract_keeps_absent_keys() {
        assertEquals(
            jsonObj("""{"/anon":{}}"""),
            structMapSubtract(jsonObj("""{"/data":{},"/anon":{}}"""), jsonObj("""{"/data":{}}""")),
        )
    }

    @Test
    fun mergeExposedPorts_subtracts_image_and_adds_bindings() {
        val result = mergeExposedPorts(
            containerPorts = jsonObj("""{"80/tcp":{},"9000/tcp":{}}"""),
            imagePorts = jsonObj("""{"80/tcp":{}}"""),
            portBindings = jsonObj("""{"443/tcp":[{"HostPort":"443"}]}"""),
        )
        // 80 dropped (from image), 9000 kept, 443 added from published bindings
        assertEquals(setOf("9000/tcp", "443/tcp"), result.keys)
    }

    @Test
    fun buildContainerConfig_drops_old_image_defaults() {
        val containerConfig = jsonObj(
            """
            {
              "Image":"app:1",
              "Env":["PATH=/usr/bin","APP_KEY=secret"],
              "Cmd":["nginx","-g","daemon off;"],
              "Entrypoint":["/entry.sh"],
              "Labels":{"com.docker.compose.service":"app","custom":"x"},
              "WorkingDir":"/app",
              "User":"root",
              "Hostname":"deadbeef1234"
            }
            """.trimIndent(),
        )
        val imageConfig = jsonObj(
            """
            {
              "Env":["PATH=/usr/bin"],
              "Cmd":["nginx","-g","daemon off;"],
              "Entrypoint":["/entry.sh"],
              "Labels":{"com.docker.compose.service":"app"},
              "WorkingDir":"/app",
              "User":"root"
            }
            """.trimIndent(),
        )
        val result = buildContainerConfig(containerConfig, imageConfig, hostConfig = null, oldId = "deadbeef1234abcd", imageRef = "app:2")

        assertEquals("app:2", result["Image"]!!.jsonPrimitive.content)
        assertEquals(jsonArr("""["APP_KEY=secret"]"""), result["Env"]) // PATH (from image) removed
        assertNull(result["Entrypoint"]) // equal to image -> dropped
        assertNull(result["Cmd"]) // entrypoint matched & cmd equal -> dropped
        assertNull(result["WorkingDir"]) // equal to image -> dropped
        assertNull(result["User"]) // equal to image -> dropped
        assertNull(result["Hostname"]) // == oldId short -> dropped (auto-assigned)
        assertEquals(jsonObj("""{"custom":"x"}"""), result["Labels"]) // compose label (from image) removed
    }

    @Test
    fun buildContainerConfig_keeps_overrides_that_differ() {
        val result = buildContainerConfig(
            jsonObj("""{"Image":"app:1","Cmd":["custom"],"Entrypoint":["/e"]}"""),
            jsonObj("""{"Cmd":["default"],"Entrypoint":["/e"]}"""),
            hostConfig = null,
            oldId = "id",
            imageRef = "app:2",
        )
        // entrypoint matches the image but cmd differs -> cmd must be kept
        assertEquals(jsonArr("""["custom"]"""), result["Cmd"])
    }

    @Test
    fun buildContainerConfig_drops_hostname_for_container_network_mode() {
        val result = buildContainerConfig(
            jsonObj("""{"Image":"app:1","Hostname":"whatever"}"""),
            imageConfig = null,
            hostConfig = jsonObj("""{"NetworkMode":"container:abc"}"""),
            oldId = "id",
            imageRef = "app:2",
        )
        assertNull(result["Hostname"])
    }

    @Test
    fun buildCreateBody_includes_only_the_first_network() {
        val body = buildCreateBody(
            containerConfig = jsonObj("""{"Image":"app:1"}"""),
            imageConfig = null,
            hostConfig = jsonObj("""{"NetworkMode":"frontend"}"""),
            imageRef = "app:2",
            oldId = "id",
            firstNetwork = "frontend" to jsonObj("""{"Aliases":["app"]}"""),
        )
        val endpoints = body.obj("NetworkingConfig")!!.obj("EndpointsConfig")!!
        assertEquals(setOf("frontend"), endpoints.keys)
        assertEquals("app:2", body["Image"]!!.jsonPrimitive.content)
    }
}
