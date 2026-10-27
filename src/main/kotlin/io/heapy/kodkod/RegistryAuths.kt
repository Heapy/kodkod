package io.heapy.kodkod

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URISyntaxException
import kotlin.io.encoding.Base64

/** Credentials are selected before either registry operation; there is no global fallback. */
class RegistryAuths private constructor(private val headers: Map<String, String>) {
    fun forImage(imageRef: String): String? {
        val first = imageRef.substringBefore('/')
        // Docker's familiar names (nginx:tag, org/image) belong to Docker Hub. A tag's colon
        // is not a registry port: a registry must be followed by a slash and repository.
        val host = if ('/' in imageRef &&
            (first == "localhost" || '.' in first || ':' in first || first.any(Char::isUpperCase))
        ) first else "docker.io"
        return headers[normalizeHost(host)]
    }

    // Config is a data class: its generated toString must never expose these header values.
    override fun toString(): String = "RegistryAuths(${headers.size} registries, credentials redacted)"

    companion object {
        fun fromEnv(get: (String) -> String?): RegistryAuths {
            require(get("KODKOD_REGISTRY_AUTH").isNullOrBlank()) {
                "KODKOD_REGISTRY_AUTH is no longer supported; migrate to KODKOD_REGISTRY_AUTHS (JSON keyed by registry host)"
            }
            val raw = get("KODKOD_REGISTRY_AUTHS")
            if (raw.isNullOrBlank()) return RegistryAuths(emptyMap())
            val entries = try {
                Json.parseToJsonElement(raw)
            } catch (_: IllegalArgumentException) {
                // Parser messages can contain source text, including passwords. Do not chain the cause.
                throw IllegalArgumentException("KODKOD_REGISTRY_AUTHS must be valid JSON")
            }
            require(entries is JsonObject) { "KODKOD_REGISTRY_AUTHS must be a JSON object keyed by registry host" }
            val headers = buildMap {
                for ((key, value) in entries) {
                    val host = normalizeHost(key)
                    require(!containsKey(host)) { "KODKOD_REGISTRY_AUTHS contains duplicate registry hosts or aliases" }
                    require(value is JsonObject) { "KODKOD_REGISTRY_AUTHS credentials must be JSON objects" }
                    require(value.keys.all { it in FIELDS }) {
                        "KODKOD_REGISTRY_AUTHS supports only username, password, identitytoken, registrytoken and serveraddress"
                    }
                    require(value.values.all { it is JsonPrimitive && it.isString }) {
                        "KODKOD_REGISTRY_AUTHS credential fields must be strings"
                    }
                    fun field(name: String) = (value[name] as? JsonPrimitive)?.content
                    require(
                        (!field("username").isNullOrBlank() && !field("password").isNullOrEmpty()) ||
                            !field("identitytoken").isNullOrBlank() || !field("registrytoken").isNullOrBlank(),
                    ) { "KODKOD_REGISTRY_AUTHS requires username/password, identitytoken or registrytoken for each registry" }
                    field("serveraddress")?.let { address ->
                        val serverHost = if (address == "https://index.docker.io/v1/") "docker.io" else normalizeHost(address)
                        require(serverHost == host) { "KODKOD_REGISTRY_AUTHS serveraddress must match its registry key" }
                    }
                    val auth = buildJsonObject {
                        value.forEach { (name, content) -> if (name != "serveraddress") put(name, content) }
                        put("serveraddress", if (host == "docker.io") "https://index.docker.io/v1/" else host)
                    }
                    put(host, Base64.UrlSafe.encode(auth.toString().encodeToByteArray()))
                }
            }
            return RegistryAuths(headers)
        }

        private val FIELDS = setOf("username", "password", "identitytoken", "registrytoken", "serveraddress")

        private fun normalizeHost(value: String): String {
            val uri = try {
                URI("https://$value")
            } catch (_: URISyntaxException) {
                null
            }
            require(uri != null && uri.host != null && uri.rawAuthority == value &&
                uri.rawUserInfo == null && uri.rawPath.isNullOrEmpty() && uri.rawQuery == null &&
                uri.rawFragment == null && uri.port in -1..65535 && !value.endsWith(':')
            ) { "KODKOD_REGISTRY_AUTHS registry keys must be hosts with optional ports, without URLs or paths" }
            return when (val host = value.lowercase()) {
                "index.docker.io", "registry-1.docker.io" -> "docker.io"
                else -> host
            }
        }
    }
}
