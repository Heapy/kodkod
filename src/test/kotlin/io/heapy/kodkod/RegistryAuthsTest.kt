package io.heapy.kodkod

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.encoding.Base64

class RegistryAuthsTest {
    private fun auths(json: String?) = configOf("KODKOD_REGISTRY_AUTHS" to json).registryAuths

    private fun decoded(auths: RegistryAuths, ref: String): JsonObject? =
        auths.forImage(ref)?.let { jsonObj(Base64.UrlSafe.decode(it).decodeToString()) }

    @Test
    fun missing_blank_and_empty_configuration_are_anonymous() {
        for (json in listOf(null, "", "  ", "{}")) {
            for (ref in listOf("nginx:1", "cloudflare/cloudflared:latest", "ghcr.io/irus/private:main")) {
                assertNull(auths(json).forImage(ref))
            }
        }
    }

    @Test
    fun credentials_match_only_the_complete_host_and_port() {
        val auths = auths(
            """{"GHCR.IO":{"username":"irus","password":"ghcr-secret"},
                "registry.example:5000":{"identitytoken":"identity-secret"},
                "localhost:5001":{"registrytoken":"bearer-secret"},
                "[::1]:5002":{"username":"local","password":"ipv6-secret"}}""",
        )
        for (ref in listOf("ghcr.io/irus/private:main", "GHCR.IO/public/image@sha256:abc")) {
            assertEquals("ghcr-secret", decoded(auths, ref)?.str("password"))
            assertEquals("ghcr.io", decoded(auths, ref)?.str("serveraddress"))
        }
        assertEquals("identity-secret", decoded(auths, "registry.example:5000/org/image:1")?.str("identitytoken"))
        assertEquals("bearer-secret", decoded(auths, "localhost:5001/image:1")?.str("registrytoken"))
        assertEquals("ipv6-secret", decoded(auths, "[::1]:5002/image:1")?.str("password"))
        for (ref in listOf(
            "nginx:1", "cloudflare/cloudflared:latest", "ghcr.io.evil.example/irus/image:1",
            "sub.ghcr.io/irus/image:1", "ghcr.io:443/irus/image:1", "registry.example:5002/image:1",
            "registry.example/image:1", "localhost:5002/image:1", "[::1]:5003/image:1",
        )) assertNull(auths.forImage(ref), ref)
    }

    @Test
    fun docker_hub_familiar_names_and_aliases_share_one_credential() {
        for (key in listOf("docker.io", "index.docker.io", "registry-1.docker.io")) {
            val auths = auths("""{"$key":{"username":"hub","password":"hub-secret"}}""")
            for (ref in listOf(
                "nginx", "nginx:1.27", "library/nginx:1.27", "cloudflare/cloudflared:latest",
                "docker.io/library/nginx:1.27", "index.docker.io/library/nginx:1.27",
                "registry-1.docker.io/library/nginx:1.27",
            )) {
                assertEquals("hub-secret", decoded(auths, ref)?.str("password"), ref)
                assertEquals("https://index.docker.io/v1/", decoded(auths, ref)?.str("serveraddress"))
            }
            assertNull(auths.forImage("ghcr.io/public/image:1"))
            assertNull(auths.forImage("docker.io:5000/library/nginx:1"))
        }
    }

    @Test
    fun optional_serveraddress_must_match_and_is_canonicalized() {
        val auths = auths(
            """{"ghcr.io":{"username":"irus","password":"secret","serveraddress":"GHCR.IO"},
                "docker.io":{"registrytoken":"secret","serveraddress":"https://index.docker.io/v1/"}}""",
        )
        assertEquals("ghcr.io", decoded(auths, "ghcr.io/irus/app")?.str("serveraddress"))
        assertEquals("https://index.docker.io/v1/", decoded(auths, "nginx")?.str("serveraddress"))
    }

    @Test
    fun encodes_json_as_base64url_and_redacts_config_string() {
        val secret = "secret-\uFFFF-\"-\\-\n-\$"
        val json = """{"ghcr.io":{"username":"irus","password":${kotlinx.serialization.json.JsonPrimitive(secret)}}}"""
        val config = configOf("KODKOD_REGISTRY_AUTHS" to json)
        val header = config.registryAuths.forImage("ghcr.io/irus/app")!!
        assertTrue(header.matches(Regex("[A-Za-z0-9_=-]+")))
        assertEquals(secret, decoded(config.registryAuths, "ghcr.io/irus/app")?.str("password"))
        assertFalse(config.toString().contains("secret-"))
        assertFalse(config.toString().contains(header))
    }

    @Test
    fun invalid_configuration_is_rejected_without_secret_text_or_parser_causes() {
        for (json in listOf(
            "secret-marker", """{"ghcr.io":{"password":"secret-marker"""",
            "[]", "null", "42", """{"ghcr.io":"secret-marker"}""",
            """{"ghcr.io":{"username":"u","password":123,"identitytoken":"secret-marker"}}""",
            """{"ghcr.io":{"password":"secret-marker"}}""",
            """{"ghcr.io":{"username":"u","password":""}}""",
            """{"ghcr.io":{"identitytoken":null}}""",
            """{"ghcr.io":{"secret-marker":"value"}}""",
            """{"ghcr.io":{"identitytoken":"secret-marker","serveraddress":"docker.io"}}""",
            """{"docker.io":{"registrytoken":"secret-marker"},"index.docker.io":{"registrytoken":"other"}}""",
            """{"ghcr.io":{"registrytoken":"secret-marker"},"GHCR.IO":{"registrytoken":"other"}}""",
        ) + listOf("https://ghcr.io", "ghcr.io/path", "*.ghcr.io", "user@ghcr.io", "ghcr.io?x", "ghcr.io#x",
            " ghcr.io", "ghcr.io:", "ghcr.io:65536").map {
            """{"$it":{"registrytoken":"secret-marker"}}"""
        }) {
            val error = assertThrows(IllegalArgumentException::class.java) { auths(json) }
            assertTrue(error.message!!.contains("KODKOD_REGISTRY_AUTHS"))
            assertFalse(error.stackTraceToString().contains("secret-marker"))
            assertNull(error.cause)
        }
    }

    @Test
    fun legacy_variable_is_rejected_even_when_new_configuration_is_set() {
        for (json in listOf(null, "{}", """{"ghcr.io":{"registrytoken":"new-secret"}}""")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                configOf("KODKOD_REGISTRY_AUTH" to "old-secret", "KODKOD_REGISTRY_AUTHS" to json)
            }
            assertTrue(error.message!!.contains("migrate to KODKOD_REGISTRY_AUTHS"))
            assertFalse(error.stackTraceToString().contains("old-secret"))
        }
        assertNull(configOf("KODKOD_REGISTRY_AUTH" to "  ").registryAuths.forImage("nginx"))
    }
}
