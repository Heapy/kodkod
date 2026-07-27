package io.heapy.kodkod

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
    fun envKeySubtract_removes_entries_by_name() {
        assertEquals(
            jsonArr("""["USER_SET=1"]"""),
            envKeySubtract(jsonArr("""["APP_VARIANT=v1","PATH=/bin","USER_SET=1"]"""), jsonArr("""["APP_VARIANT=v2","PATH=/usr/bin"]""")),
        )
    }

    @Test
    fun stringMapSubtract_keeps_absent_or_differing() {
        val result = stringMapSubtract(jsonObj("""{"a":"1","b":"2","c":"3"}"""), jsonObj("""{"a":"1","b":"9"}"""))
        // a removed (same value), b kept (different value), c kept (absent from image)
        assertEquals(jsonObj("""{"b":"2","c":"3"}"""), result)
    }

    @Test
    fun stringMapSubtractKeys_removes_entries_by_key() {
        val result = stringMapSubtractKeys(jsonObj("""{"app.variant":"v1","custom":"x"}"""), jsonObj("""{"app.variant":"v2"}"""))
        assertEquals(jsonObj("""{"custom":"x"}"""), result)
    }

    @Test
    fun healthcheckSubtract_removes_matching_fields() {
        val result = healthcheckSubtract(
            jsonObj(
                """
                {
                  "Test":["CMD-SHELL","curl -f http://localhost/old || exit 1"],
                  "Interval":5000000000,
                  "Timeout":3000000000,
                  "Retries":3
                }
                """.trimIndent(),
            ),
            jsonObj(
                """
                {
                  "Test":["CMD-SHELL","curl -f http://localhost/old || exit 1"],
                  "Interval":30000000000,
                  "Timeout":3000000000,
                  "Retries":3
                }
                """.trimIndent(),
            ),
        )

        assertEquals(jsonObj("""{"Interval":5000000000}"""), result)
    }

    @Test
    fun healthcheckSubtractKeys_removes_fields_by_name() {
        val result = healthcheckSubtractKeys(
            jsonObj(
                """
                {
                  "Test":["CMD-SHELL","curl -f http://localhost/old || exit 1"],
                  "Interval":5000000000
                }
                """.trimIndent(),
            ),
            jsonObj(
                """
                {
                  "Test":["CMD-SHELL","curl -f http://localhost/new || exit 1"]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(jsonObj("""{"Interval":5000000000}"""), result)
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
              "Hostname":"deadbeef1234",
              "Healthcheck":{
                "Test":["CMD-SHELL","curl -f http://localhost/old || exit 1"],
                "Interval":5000000000,
                "Timeout":3000000000,
                "Retries":3
              }
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
              "User":"root",
              "Healthcheck":{
                "Test":["CMD-SHELL","curl -f http://localhost/old || exit 1"],
                "Interval":30000000000,
                "Timeout":3000000000,
                "Retries":3
              }
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
        assertEquals(jsonObj("""{"Interval":5000000000}"""), result["Healthcheck"])
    }

    @Test
    fun buildContainerConfig_can_drop_new_image_default_keys_when_old_image_is_unavailable() {
        val containerConfig = jsonObj(
            """
            {
              "Image":"app:1",
              "Env":["APP_VARIANT=v1","CUSTOM_ENV=yes"],
              "Cmd":["old-default"],
              "Labels":{"app.variant":"v1","custom":"x"},
              "Healthcheck":{
                "Test":["CMD-SHELL","curl -f http://localhost/old || exit 1"],
                "Interval":5000000000
              }
            }
            """.trimIndent(),
        )
        val newImageConfig = jsonObj(
            """
            {
              "Env":["APP_VARIANT=v2"],
              "Cmd":["new-default"],
              "Labels":{"app.variant":"v2"},
              "Healthcheck":{
                "Test":["CMD-SHELL","curl -f http://localhost/new || exit 1"]
              }
            }
            """.trimIndent(),
        )
        val result = buildContainerConfig(
            containerConfig,
            newImageConfig,
            hostConfig = null,
            oldId = "id",
            imageRef = "app:2",
            subtractImageDefaultsByKey = true,
        )

        assertEquals("app:2", result["Image"]!!.jsonPrimitive.content)
        assertEquals(jsonArr("""["CUSTOM_ENV=yes"]"""), result["Env"])
        assertNull(result["Cmd"])
        assertEquals(jsonObj("""{"custom":"x"}"""), result["Labels"])
        assertEquals(jsonObj("""{"Interval":5000000000}"""), result["Healthcheck"])
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
    fun buildContainerConfig_restamps_the_compose_image_label_with_the_new_id() {
        val result = buildContainerConfig(
            jsonObj(
                """
                {
                  "Image":"app:1",
                  "Labels":{
                    "com.docker.compose.project":"stack",
                    "com.docker.compose.image":"sha256:OLD",
                    "com.docker.compose.config-hash":"h1"
                  }
                }
                """.trimIndent(),
            ),
            imageConfig = null,
            hostConfig = null,
            oldId = "id",
            imageRef = "app:2",
            newComposeImageId = "sha256:NEW",
        )

        val labels = result.obj("Labels")!!
        assertEquals("sha256:NEW", labels.label("com.docker.compose.image"))
        // The hash covers the compose file's service definition, which kodkod never edits.
        assertEquals("h1", labels.label("com.docker.compose.config-hash"))
        assertEquals("stack", labels.label("com.docker.compose.project"))
    }

    @Test
    fun buildContainerConfig_copies_the_compose_image_label_when_the_image_did_not_change() {
        val result = buildContainerConfig(
            jsonObj("""{"Image":"app:1","Labels":{"com.docker.compose.image":"sha256:OLD"}}"""),
            imageConfig = null,
            hostConfig = null,
            oldId = "id",
            imageRef = "app:1",
        )

        assertEquals("sha256:OLD", result.obj("Labels").label("com.docker.compose.image"))
    }

    @Test
    fun buildContainerConfig_does_not_invent_a_compose_image_label() {
        val result = buildContainerConfig(
            jsonObj("""{"Image":"app:1","Labels":{"custom":"x"}}"""),
            imageConfig = null,
            hostConfig = null,
            oldId = "id",
            imageRef = "app:2",
            newComposeImageId = "sha256:NEW",
        )

        assertEquals(jsonObj("""{"custom":"x"}"""), result["Labels"])
    }

    private fun createBodyMounts(inspectMounts: JsonArray, hostConfig: JsonObject?): JsonArray? {
        val body = buildCreateBody(
            containerConfig = jsonObj("""{"Image":"app:1"}"""),
            imageConfig = null,
            hostConfig = resolveMounts(inspectMounts, hostConfig),
            imageRef = "app:2",
            oldId = "id",
            firstNetwork = null,
        )
        return body.obj("HostConfig")?.arr("Mounts")
    }

    @Test
    fun resolveMounts_names_the_volume_of_an_anonymous_compose_mount() {
        // compose `volumes: ["/data"]` records the destination but leaves the source for the daemon to fill.
        val mounts = createBodyMounts(
            jsonArr(
                """
                [{
                  "Type":"volume",
                  "Name":"vol123",
                  "Source":"/var/lib/docker/volumes/vol123/_data",
                  "Destination":"/data",
                  "Driver":"local",
                  "Mode":"z",
                  "RW":true,
                  "Propagation":""
                }]
                """.trimIndent(),
            ),
            jsonObj("""{"NetworkMode":"bridge","Mounts":[{"Type":"volume","Source":"","Target":"/data"}]}"""),
        )!!

        assertEquals(1, mounts.size, "the existing entry must be completed, not duplicated")
        assertEquals(jsonObj("""{"Type":"volume","Source":"vol123","Target":"/data"}"""), mounts[0])
    }

    @Test
    fun resolveMounts_synthesizes_a_mount_for_a_volume_missing_from_the_host_config() {
        // `VOLUME /data` in the image (or `docker run -v /data`): HostConfig has no entry at all.
        val inspectMounts = jsonArr(
            """
            [{
              "Type":"volume",
              "Name":"anon456",
              "Source":"/var/lib/docker/volumes/anon456/_data",
              "Destination":"/var/lib/postgresql/data",
              "Driver":"local",
              "Mode":"",
              "RW":true,
              "Propagation":""
            }]
            """.trimIndent(),
        )
        val hostConfig = jsonObj("""{"NetworkMode":"bridge"}""")
        val mounts = createBodyMounts(inspectMounts, hostConfig)!!

        assertEquals(1, mounts.size)
        // Only create-valid keys: Destination/Name/Mode/RW/Propagation would fail the request with a 400.
        assertEquals(
            jsonObj("""{"Type":"volume","Source":"anon456","Target":"/var/lib/postgresql/data","ReadOnly":false}"""),
            mounts[0],
        )
        assertEquals("bridge", resolveMounts(inspectMounts, hostConfig)!!.str("NetworkMode"), "the rest of HostConfig must survive")
    }

    @Test
    fun resolveMounts_marks_a_read_only_volume_read_only() {
        val mounts = createBodyMounts(
            jsonArr("""[{"Type":"volume","Name":"ro789","Destination":"/ref","RW":false}]"""),
            jsonObj("""{"NetworkMode":"bridge"}"""),
        )!!

        assertEquals(jsonObj("""{"Type":"volume","Source":"ro789","Target":"/ref","ReadOnly":true}"""), mounts[0])
    }

    @Test
    fun resolveMounts_leaves_a_destination_already_covered_by_binds_alone() {
        val hostConfig = jsonObj("""{"Binds":["vol123:/data:rw"],"NetworkMode":"bridge"}""")
        val resolved = resolveMounts(
            jsonArr("""[{"Type":"volume","Name":"vol123","Destination":"/data","RW":true}]"""),
            hostConfig,
        )

        assertEquals(hostConfig, resolved, "the legacy bind already mounts the volume; a second entry would conflict")
    }

    @Test
    fun resolveMounts_keeps_a_named_volume_entry_verbatim() {
        val hostConfig = jsonObj(
            """
            {"Mounts":[{
              "Type":"volume",
              "Source":"pgdata",
              "Target":"/data",
              "ReadOnly":false,
              "VolumeOptions":{"NoCopy":true}
            }]}
            """.trimIndent(),
        )
        val resolved = resolveMounts(
            jsonArr("""[{"Type":"volume","Name":"pgdata","Destination":"/data","RW":true}]"""),
            hostConfig,
        )

        assertEquals(hostConfig, resolved)
    }

    @Test
    fun resolveMounts_ignores_binds_tmpfs_and_unnamed_entries() {
        val hostConfig = jsonObj("""{"NetworkMode":"bridge"}""")
        val resolved = resolveMounts(
            jsonArr(
                """
                [
                  {"Type":"bind","Source":"/host/etc","Destination":"/etc/app","RW":true},
                  {"Type":"tmpfs","Destination":"/tmp/scratch","RW":true},
                  {"Type":"volume","Name":"","Destination":"/nameless","RW":true}
                ]
                """.trimIndent(),
            ),
            hostConfig,
        )

        assertEquals(hostConfig, resolved, "binds and tmpfs are already fully described by HostConfig")
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
