package io.heapy.kodkod.e2e

import io.heapy.kodkod.DockerApi
import io.heapy.kodkod.DockerClient
import io.heapy.kodkod.DockerClientContract
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * [DockerApi] against the shared [DockerClientContract] — the other half of the pair whose first half
 * (`io.heapy.kodkod.FakeDockerClientContractTest`) runs on every `./gradlew test`.
 *
 * This is what stops the fake from inventing behaviour: whatever the unit suite is allowed to assume
 * about a daemon, a real daemon is made to demonstrate here. The very first run of the pair found one
 * such invention — the fake reported a container it had just created as already running, while the
 * daemon (and the fake's own listing) call that state `created`.
 *
 * ## Why it needs `-Pkodkod.e2e.useCurrentDocker=true`
 *
 * [DockerApi] speaks only to a unix socket, and the DinD daemon the rest of the e2e suite runs
 * against is reachable over TCP only — the same constraint that makes the fixture recorder require
 * this flag. So the contract runs against the daemon at `KODKOD_DOCKER_SOCKET` (default
 * `/var/run/docker.sock`), and is skipped otherwise rather than silently testing the wrong daemon.
 *
 * It is safe to point at a working machine: every container it makes is named `kodkod-contract-*`,
 * nothing else is listed, renamed or removed, and each test cleans up after itself. A CI job with a
 * disposable daemon can turn it on unconditionally.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(
    named = "kodkod.e2e.useCurrentDocker",
    matches = "true",
    disabledReason = "DockerApi is unix-socket-only and the suite's DinD daemon is TCP-only; " +
        "run with -Pkodkod.e2e.useCurrentDocker=true to exercise the contract against the local daemon",
)
class DockerApiContractTest : DockerClientContract() {
    private val socket = System.getenv("KODKOD_DOCKER_SOCKET") ?: "/var/run/docker.sock"

    override val client: DockerClient = DockerApi(socket)

    override val imageRef = "busybox:1.36"

    /** The daemon can only create from an image it already has, and the contract does not pull. */
    @BeforeAll
    fun ensureTheImageIsThere() {
        client.pull("busybox", "1.36", registryAuth = null, platform = null)
    }
}
