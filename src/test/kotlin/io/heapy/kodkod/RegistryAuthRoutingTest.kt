package io.heapy.kodkod

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import kotlin.io.encoding.Base64

/** Exercises the production path from environment through Updater and DockerApi to HTTP headers. */
class RegistryAuthRoutingTest {
    @Test
    fun ghcr_credentials_never_reach_docker_hub_or_other_registries() = verifyRouting(hub = false)

    @Test
    fun different_registries_receive_their_own_credentials() = verifyRouting(hub = true)

    @Test
    fun distribution_failure_keeps_the_same_scoping_when_falling_back_to_pull() =
        verifyRouting(hub = false, failDistribution = true)

    private fun verifyRouting(hub: Boolean, failDistribution: Boolean = false) {
        val config = configOf(
            "KODKOD_UPDATE_MONITOR_ALL" to "true",
            "KODKOD_REGISTRY_AUTHS" to if (hub) {
                """{"ghcr.io":{"username":"irus","password":"ghcr-secret"},
                    "docker.io":{"username":"hub","password":"hub-secret"}}"""
            } else """{"ghcr.io":{"username":"irus","password":"ghcr-secret"}}""",
        )
        val expected = linkedMapOf(
            "ghcr.io/irus/bedroom-planner:main" to "ghcr-secret",
            "ghcr.io/irus/november_2026:main" to "ghcr-secret",
            "ghcr.io/irus/solo-history-processor:main" to "ghcr-secret",
            "ghcr.io/public/image:latest" to "ghcr-secret",
            "nginx:latest" to if (hub) "hub-secret" else null,
            "cloudflare/cloudflared:latest" to if (hub) "hub-secret" else null,
            "docker.io/library/nginx:latest" to if (hub) "hub-secret" else null,
            "index.docker.io/library/nginx:latest" to if (hub) "hub-secret" else null,
            "registry-1.docker.io/library/nginx:latest" to if (hub) "hub-secret" else null,
            "ghcr.io.evil.example/app:latest" to null,
            "ghcr.io:5000/app:latest" to null,
            "quay.io/org/app:latest" to null,
        )
        val observed = mutableMapOf<Pair<String, String>, String?>()
        val api = DockerApi(object : DockerTransport {
            override fun exchange(
                method: String, path: String, body: ByteArray?, headers: Map<String, String>, readTimeoutMs: Long,
            ): ByteArray {
                val ref = if (method == "GET") {
                    path.removePrefix("/distribution/").removeSuffix("/json")
                } else {
                    val query = path.substringAfter('?').split('&').associate {
                        it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
                    }
                    "${query["fromImage"]}:${query["tag"]}"
                }
                val auth = headers["X-Registry-Auth"]?.let { jsonObj(Base64.UrlSafe.decode(it).decodeToString()) }
                observed[method to ref] = auth?.str("password")
                if (expected[ref] == null) assertFalse(headers.containsKey("X-Registry-Auth"), ref)
                return if (method == "GET" && failDistribution) {
                    "HTTP/1.1 401 Unauthorized\r\n\r\n{}".encodeToByteArray()
                } else {
                    "HTTP/1.1 200 OK\r\n\r\n{\"Descriptor\":{\"digest\":\"sha256:remote\"}}".encodeToByteArray()
                }
            }
        })
        val docker = FakeDockerClient()
        expected.keys.forEachIndexed { i, ref ->
            docker.container(id = "app$i", imageRef = ref)
            docker.images[ref] = jsonObj("""{"Id":"sha256:app$i-old","Config":{},"RepoDigests":[]}""")
        }
        val client = object : DockerClient by docker {
            override fun inspectDistribution(ref: String, registryAuth: String?): JsonObject =
                api.inspectDistribution(ref, registryAuth)

            override fun pull(fromImage: String, tag: String, registryAuth: String?, platform: String?) =
                api.pull(fromImage, tag, registryAuth, platform)
        }
        val clock = FakeClock()
        Updater(client, config, selfId = null, clock, clock).plan()

        val expectedCalls = expected.flatMap { (ref, secret) ->
            listOf(("GET" to ref) to secret, ("POST" to ref) to secret)
        }.toMap()
        assertEquals(expectedCalls, observed)
    }
}
