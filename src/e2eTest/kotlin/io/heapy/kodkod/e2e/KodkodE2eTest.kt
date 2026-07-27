package io.heapy.kodkod.e2e

import io.heapy.kodkod.TRUTHY
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Path
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.io.path.absolute
import kotlin.io.path.pathString

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@DisplayName("kodkod Docker E2E")
class KodkodE2eTest {
    private val e2e = E2eHarness()

    @BeforeAll
    fun setupSuite() {
        e2e.startDocker()
        e2e.setup()
    }

    @AfterAll
    fun cleanupSuite() {
        e2e.close()
    }

    @Test
    @Order(1)
    fun autohealRestartsUnhealthyContainer() = e2e.scenario("autoheal") {
        compose("autoheal", "up", "-d")
        waitUntil(40, "app healthy") { health("e2e-autoheal-app-1") == "healthy" }
        val before = startedAt("e2e-autoheal-app-1")

        docker("exec", "e2e-autoheal-app-1", "rm", "-f", "/tmp/healthy")

        // `waitUntil` fails the test on timeout, so it *is* the assertion — see the note in
        // `dependencyUpdateRestartsDependentAfterProvider`. Restating the condition afterwards would
        // only add an assertion that cannot fire.
        waitUntil(60, "app restarted by kodkod") { startedAt("e2e-autoheal-app-1") != before }
        waitUntil(30, "health recovered") { health("e2e-autoheal-app-1") == "healthy" }
    }

    @Test
    @Order(2)
    fun updatePullsAndAdoptsNewImageDefaults() = e2e.scenario("update") {
        publishVariant("v1")
        compose("update", "up", "-d")
        waitUntil(30, "app v1 up") { variant("e2e-update-app-1") == "v1" }
        val oldImage = inspect("{{.Image}}", "e2e-update-app-1")

        publishVariant("v2")

        waitUntil(90, "app updated to v2") { variant("e2e-update-app-1") == "v2" }
        assertNotEquals(oldImage, inspect("{{.Image}}", "e2e-update-app-1"), "image id should change")
        assertTrue(
            inspect("{{range .Config.Healthcheck.Test}}{{.}} {{end}}", "e2e-update-app-1").contains("/tmp/healthy-v2"),
            "healthcheck command should come from the v2 image",
        )
        assertEquals("5000000000", inspect("{{json .Config.Healthcheck.Interval}}", "e2e-update-app-1"))
        assertEquals("4000000000", inspect("{{json .Config.Healthcheck.Timeout}}", "e2e-update-app-1"))
        assertEquals("4", inspect("{{json .Config.Healthcheck.Retries}}", "e2e-update-app-1"))
    }

    /**
     * Compose decides to recreate a container when either `com.docker.compose.config-hash` or
     * `com.docker.compose.image` on the running container disagrees with what it computes from the
     * (unchanged) compose file. kodkod recreates containers behind compose's back, so a plain
     * `compose up -d` afterwards is the only honest proof that both labels survived the recreate:
     * a stale image label makes compose throw away exactly the container kodkod just updated.
     */
    @Test
    @Order(3)
    fun composeUpAfterKodkodUpdateKeepsTheContainer() = e2e.scenario("update") {
        publishVariant("v1")
        compose("update", "up", "-d")
        waitUntil(30, "app v1 up") { variant("e2e-update-app-1") == "v1" }
        val originalId = inspect("{{.Id}}", "e2e-update-app-1")

        publishVariant("v2")

        waitUntil(90, "app updated to v2") { variant("e2e-update-app-1") == "v2" }
        val kodkodId = inspect("{{.Id}}", "e2e-update-app-1")
        assertNotEquals(originalId, kodkodId, "kodkod should have replaced the container before the second up")

        val secondUp = compose("update", "up", "-d")
        val appProgress = secondUp.output.lineSequence()
            .filter { it.contains("e2e-update-app-1") }
            .toList()

        assertEquals(
            kodkodId,
            inspect("{{.Id}}", "e2e-update-app-1"),
            "repeated compose up must not recreate the container kodkod created; compose said:\n${secondUp.output}",
        )
        assertTrue(
            appProgress.any { it.contains("Running") },
            "compose should report the app service as Running; got: $appProgress",
        )
        assertTrue(
            appProgress.none { it.contains("Recreat", ignoreCase = true) },
            "compose should not recreate the app service; got: $appProgress",
        )
        assertEquals("v2", variant("e2e-update-app-1"), "the surviving container must still be the updated one")
    }

    @Test
    @Order(4)
    fun dependencyUpdateRestartsDependentAfterProvider() = e2e.scenario("deps") {
        publishVariant("v1")
        compose("deps", "up", "-d")
        waitUntil(30, "db v1 up") { variant("e2e-deps-db-1") == "v1" }

        publishVariant("v2")

        waitUntil(90, "db updated to v2") { variant("e2e-deps-db-1") == "v2" }
        assertEquals("v2", variant("e2e-deps-db-1"))
        val dbStart = startedAt("e2e-deps-db-1")
        // db being up is not the end of the cycle: the dependents only come back once db has passed its
        // liveness gate, seconds later. Comparing timestamps the moment db appears races that window —
        // the wait is what makes the ordering assertion mean "web restarted after db" rather than
        // "web has not restarted yet".
        waitUntil(90, "web restarted after db") { startedAt("e2e-deps-web-1") > dbStart }
        // `cache` carries no `kodkod.depends-on` fallback: its edge exists only if kodkod parsed
        // compose's `<service>:<condition>:<restart>` triple, and survives only if the `restart: false`
        // compose puts there by default did not silence the dependent restart.
        waitUntil(90, "cache restarted after db (compose depends_on label only)") {
            startedAt("e2e-deps-cache-1") > dbStart
        }
        // No assertion follows: `waitUntil` fails the test on timeout, so re-asserting the condition it
        // just proved would only be an assertion that can never fire.
    }

    @Test
    @Order(5)
    fun multiNetworkContainerIsReconnectedToEveryNetwork() = e2e.scenario("multinet") {
        publishVariant("v1")
        compose("multinet", "up", "-d")
        waitUntil(30, "app v1 up") { variant("e2e-multinet-app-1") == "v1" }
        waitUntil(30, "mac v1 up") { variant("e2e-multinet-mac-1") == "v1" }
        val oldImage = inspect("{{.Image}}", "e2e-multinet-app-1")

        publishVariant("v2")

        waitUntil(90, "app updated to v2") { variant("e2e-multinet-app-1") == "v2" }
        assertNotEquals(oldImage, inspect("{{.Image}}", "e2e-multinet-app-1"), "image id should change")
        val networks = inspect($$"{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}", "e2e-multinet-app-1")
        assertTrue(networks.contains("e2e-multinet_neta"), "missing neta; got: $networks")
        assertTrue(networks.contains("e2e-multinet_netb"), "missing netb; got: $networks")

        // The `mac` service asked for a MAC explicitly, and kodkod may only carry one over when the
        // engine exposes `Config.MacAddress` — that field is what separates a requested MAC from the
        // one the daemon generates for every endpoint. Docker >= 27 does not report it at all, so on
        // every engine this suite runs against the rule cannot fire, and asserting it conditionally
        // would only look like coverage. What the rule does with a MAC it *can* see is asserted in
        // UpdaterTest (`recreate_keeps_an_explicit_mac_address_and_the_gateway_priority`); what a real
        // daemon adds here is that the replacement comes back attached and addressable at all.
        waitUntil(90, "mac service updated to v2") { variant("e2e-multinet-mac-1") == "v2" }
        assertTrue(
            endpointMac("e2e-multinet-mac-1", "e2e-multinet_neta").isNotEmpty(),
            "the replacement must be back on neta with a working endpoint MAC",
        )
    }

    /**
     * Both kinds of namespace consumer: `consumer` is labelled for kodkod (the dependency graph brings it
     * along), `sidecar` is not labelled at all, so with the default `KODKOD_UPDATE_MONITOR_ALL=false` the
     * update cycle never lists it. Only a real daemon can prove the difference that makes: a container
     * whose provider was force-removed keeps reporting `Running` while its interfaces are gone, so the
     * assertion that matters is not the recreate but `eth0` being there afterwards.
     */
    @Test
    @Order(6)
    fun containerNetworkModeDependentIsRecreatedAfterProviderUpdate() = e2e.scenario("container-mode") {
        publishVariant("v1")
        compose("container-mode", "up", "-d")
        waitUntil(30, "provider v1 up") { variant("e2e-cmode-provider-1") == "v1" }
        waitUntil(30, "consumer running") { running("e2e-cmode-consumer-1") }
        waitUntil(30, "sidecar running") { running("e2e-cmode-sidecar-1") }
        val oldProviderId = inspect("{{.Id}}", "e2e-cmode-provider-1")
        val oldConsumerId = inspect("{{.Id}}", "e2e-cmode-consumer-1")
        val oldSidecarId = inspect("{{.Id}}", "e2e-cmode-sidecar-1")
        assertTrue(interfaces("e2e-cmode-sidecar-1").contains("eth0"), "the sidecar starts out on a live namespace")

        publishVariant("v2")

        waitUntil(90, "provider updated to v2") { variant("e2e-cmode-provider-1") == "v2" }
        waitUntil(60, "consumer recreated after provider update") { inspect("{{.Id}}", "e2e-cmode-consumer-1") != oldConsumerId }
        waitUntil(60, "unlabelled sidecar recreated after provider update") {
            inspect("{{.Id}}", "e2e-cmode-sidecar-1") != oldSidecarId
        }
        val providerId = inspect("{{.Id}}", "e2e-cmode-provider-1")
        assertNotEquals(oldProviderId, providerId, "provider id should change")
        assertNotEquals(oldConsumerId, inspect("{{.Id}}", "e2e-cmode-consumer-1"), "consumer id should change")
        assertEquals("container:$providerId", inspect("{{.HostConfig.NetworkMode}}", "e2e-cmode-consumer-1"))
        assertTrue(running("e2e-cmode-consumer-1"))

        // The sidecar carries no kodkod label, so nothing in the monitored set knows it exists; without
        // the daemon-wide scan it would be running here with `lo` and nothing else.
        assertEquals(
            "container:$providerId",
            inspect("{{.HostConfig.NetworkMode}}", "e2e-cmode-sidecar-1"),
            "the unlabelled sidecar must be joined to the replacement's namespace, not the removed one",
        )
        waitUntil(30, "sidecar running again") { running("e2e-cmode-sidecar-1") }
        val addresses = interfaces("e2e-cmode-sidecar-1")
        assertTrue(addresses.contains("eth0"), "the sidecar must have a working network after the update: $addresses")
    }

    @Test
    @Order(7)
    fun failedRecreateRollsBackToRunningOriginal() = e2e.scenario("rollback") {
        publishVariant("v1")
        compose("rollback", "up", "-d")
        waitUntil(30, "app v1 up") { variant("e2e-rollback-app-1") == "v1" }

        publishVariant("broken")

        waitUntil(90, "kodkod logged a rollback") { logHas("e2e-rollback-kodkod-1", "rolling back") }

        publishVariant("v1")

        waitUntil(60, "app back to running v1") { running("e2e-rollback-app-1") && variant("e2e-rollback-app-1") == "v1" }
        assertEquals(0, containerIdsByName("_kodkod_old_").size, "backup containers should be removed")
    }

    @Test
    @Order(8)
    fun digestPinnedContainerIsSkipped() = with(e2e) {
        // The digest has to be read before the stack comes up — `compose.digest.yml` interpolates it
        // into the image ref — and the teardown needs it too, so it is resolved outside the scenario.
        publishVariant("v1")
        val repoDigest = inspect("{{index .RepoDigests 0}}", "$registry/testapp:latest")
        val digest = repoDigest.substringAfter("@", missingDelimiterValue = "")
        assertTrue(digest.isNotBlank(), "could not read v1 RepoDigest")
        val digestEnv = mapOf("TESTAPP_DIGEST" to digest)

        scenario("digest", env = digestEnv) {
            compose("digest", "up", "-d", env = digestEnv)
            waitUntil(30, "pinned app up") { running("e2e-digest-app-1") }
            val oldImage = inspect("{{.Image}}", "e2e-digest-app-1")

            publishVariant("v2")

            waitUntil(50, "kodkod logged digest-pinned skip") { logHas("e2e-digest-kodkod-1", "digest-pinned") }
            assertEquals(oldImage, inspect("{{.Image}}", "e2e-digest-app-1"), "the pinned container must not move")
        }
    }

    @Test
    @Order(9)
    fun monitorAllDoesNotActOnKodkodItself() = e2e.scenario("self") {
        compose("registry", "stop", check = false)
        try {
            compose("self", "up", "-d")
            waitUntil(30, "kodkod up") { running("e2e-self-kodkod-1") }
            assertEquals("true", inspect("{{index .Config.Labels \"io.heapy.kodkod.self\"}}", "e2e-self-kodkod-1"))
            assertEquals("kodkod-custom-host", inspect("{{.Config.Hostname}}", "e2e-self-kodkod-1"))
            val before = startedAt("e2e-self-kodkod-1")
            waitUntil(30, "dummy up") { running("e2e-self-dummy-1") }
            val dummyBefore = startedAt("e2e-self-dummy-1")

            sleep(35)

            assertEquals(before, startedAt("e2e-self-kodkod-1"), "kodkod should not act on itself")
            // The dummy restarting is what proves cycles ran at all — without it the assertion above
            // would pass just as well against a kodkod that never woke up.
            waitUntil(30, "dummy restarted by monitor-all") { startedAt("e2e-self-dummy-1") != dummyBefore }
            assertTrue(running("e2e-self-dummy-1"), "and it has to be up again, not merely restarted")
        } finally {
            compose("registry", "start", check = false)
        }
    }

    /**
     * An anonymous volume is named only in the container's top-level `Mounts[]`; `HostConfig` records the
     * destination with an empty source. Recreating from `HostConfig` alone therefore hands the replacement
     * a brand-new empty volume — for a database that is silent data loss. Only a real daemon can prove the
     * volume was inherited, hence the file written before the update.
     */
    @Test
    @Order(10)
    fun anonymousVolumeIsInheritedByTheRecreatedContainer() = e2e.scenario("update") {
        publishVariant("v1")
        compose("update", "up", "-d")
        waitUntil(30, "vol v1 up") { variant("e2e-update-vol-1") == "v1" }
        val volumeBefore = volumeName("e2e-update-vol-1", "/data")
        assertTrue(volumeBefore.isNotBlank(), "expected an anonymous volume on /data, got '$volumeBefore'")
        docker("exec", "e2e-update-vol-1", "sh", "-c", "echo kodkod-was-here > /data/keep")

        publishVariant("v2")

        waitUntil(90, "vol updated to v2") { variant("e2e-update-vol-1") == "v2" }
        assertEquals(
            volumeBefore,
            volumeName("e2e-update-vol-1", "/data"),
            "the recreated container must keep mounting the original anonymous volume",
        )
        waitUntil(30, "vol container running") { running("e2e-update-vol-1") }
        val kept = docker("exec", "e2e-update-vol-1", "cat", "/data/keep", check = false)
        assertEquals("kodkod-was-here", kept.output.trim(), "the data written before the update must survive it")
    }

    /**
     * A `204` from `POST /start` is not evidence the update worked: an image whose process dies a
     * moment later leaves the daemon perfectly satisfied. Only a real daemon can prove that kodkod
     * probes the replacement afterwards and that the original container — the only copy of the working
     * state — is still there to be rolled back to.
     */
    @Test
    @Order(11)
    fun aReplacementThatStartsAndThenDiesIsRolledBack() = e2e.scenario("rollback") {
        publishVariant("v1")
        compose("rollback", "up", "-d")
        waitUntil(30, "app v1 up") { variant("e2e-rollback-app-1") == "v1" }
        val originalId = inspect("{{.Id}}", "e2e-rollback-app-1")

        publishVariant("crasher")

        waitUntil(120, "kodkod rejected the replacement that did not stay up") {
            logHas("e2e-rollback-kodkod-1", "did not stay up")
        }
        publishVariant("v1") // stop the next cycle from re-attempting the doomed update

        waitUntil(60, "app back to running v1") { running("e2e-rollback-app-1") && variant("e2e-rollback-app-1") == "v1" }
        assertEquals(
            originalId,
            inspect("{{.Id}}", "e2e-rollback-app-1"),
            "the original container must be the one running again, not a fresh replacement",
        )
        assertEquals(0, containerIdsByName("_kodkod_old_").size, "backup containers should be removed")
    }

    /**
     * The in-process rollback covers a recreate *step* that failed; nothing in-process can cover kodkod
     * itself being killed between `rename(app -> app_kodkod_old_<id>)` and the replacement's `start`.
     * After that the service is down under a name no cycle looks at (discovery lists running containers
     * only), and only a restarted kodkod can bring it back.
     *
     * Racing a real kodkod into that window would make a flaky test, so the window's *outcome* is
     * reproduced with the CLI — app stopped and parked under its backup name, nothing holding the real
     * one — which is byte-for-byte the state the daemon is left in. What only a real daemon can prove is
     * the rest: that the `name` filter really finds the orphan, that the daemon's own name index lets
     * the rename back through, and that the recovered container is the original one.
     */
    @Test
    @Order(12)
    fun aBackupOrphanedByAKilledKodkodIsRecoveredOnRestart() = e2e.scenario("rollback") {
        publishVariant("v1")
        compose("rollback", "up", "-d")
        waitUntil(30, "app v1 up") { variant("e2e-rollback-app-1") == "v1" }
        val originalId = inspect("{{.Id}}", "e2e-rollback-app-1")
        assertTrue(originalId.isNotBlank(), "could not read the app container's id")

        // SIGKILL, so no shutdown hook runs — then the exact daemon state that window leaves behind.
        docker("kill", "e2e-rollback-kodkod-1")
        docker("stop", "e2e-rollback-app-1")
        docker("rename", "e2e-rollback-app-1", "e2e-rollback-app-1_kodkod_old_${originalId.take(12)}")
        assertEquals(1, containerIdsByName("_kodkod_old_").size, "the orphaned backup must be set up")
        assertFalse(running("e2e-rollback-app-1"), "nothing may be serving the service name")

        docker("start", "e2e-rollback-kodkod-1")

        waitUntil(60, "app recovered from the orphaned backup") { running("e2e-rollback-app-1") }
        assertEquals(
            originalId,
            inspect("{{.Id}}", "e2e-rollback-app-1"),
            "the orphaned container itself must come back, not a fresh replacement",
        )
        assertEquals("v1", variant("e2e-rollback-app-1"))
        assertEquals(0, containerIdsByName("_kodkod_old_").size, "no backup name may be left behind")
    }
}

internal class E2eHarness {
    val root: Path = Path.of(System.getProperty("user.dir")).absolute()

    /**
     * Where the throwaway registry answers — deliberately **not** configurable.
     *
     * All nine `e2e/compose.*.yml` files spell `127.0.0.1:5000/testapp` into their `image:` lines, and
     * compose is what starts every stack. An override here would therefore publish the variants to one
     * registry while every stack kept pulling from another: the containers would come up on whatever
     * image was already local, and the suite would pass without testing an update at all. Moving the
     * registry means moving it in the compose files in the same change.
     */
    val registry: String = "127.0.0.1:5000"

    private val useCurrentDocker = boolProperty("kodkod.e2e.useCurrentDocker")
    private val keepDind = boolProperty("kodkod.e2e.keepDind") || System.getenv("KEEP") == "1"
    private val dindName = System.getProperty("kodkod.e2e.dindName", "kodkod-e2e-dind")
    private val dindImage = System.getProperty("kodkod.e2e.dindImage", "docker:dind")
    private val dindPort = System.getProperty("kodkod.e2e.dindPort", "12375")
    private var dockerHost: String? = null

    fun startDocker() {
        if (useCurrentDocker) {
            println("==> using current Docker daemon")
            return
        }

        println("==> (re)starting Docker-in-Docker from $dindImage")
        removeDind()
        hostDocker(
            "run",
            "-d",
            "--privileged",
            "--name",
            dindName,
            "-e",
            "DOCKER_TLS_CERTDIR=",
            "-p",
            "127.0.0.1:$dindPort:2375",
            dindImage,
        )

        dockerHost = "tcp://127.0.0.1:$dindPort"
        println("==> waiting for the inner daemon on $dockerHost")
        waitUntil(90, "inner Docker daemon ready") {
            docker("version", check = false).exitCode == 0
        }
        val version = docker("version", "--format", "{{.Server.Version}}").output.trim()
        println("    inner engine: $version")
    }

    fun setup() {
        println("==> setup: build kodkod:e2e, start registry, push testapp v1")
        println("==> building kodkod:e2e (Gradle runs inside the build stage; first run is slow)")
        docker("build", "-t", "kodkod:e2e", ".")

        startRegistry()
        publishVariant("v1")
        println(
            """

            Ready.
              registry : $registry  (testapp:latest = v1)
              image    : kodkod:e2e
            """.trimIndent(),
        )
    }

    /**
     * The registry half of [setup], on its own because `DockerFixtureRecorder` needs exactly this and
     * nothing else: it drives `Updater`/`Autoheal` in-process, so the `kodkod:e2e` image [setup] builds
     * would be a several-minute build of something it never runs.
     */
    fun startRegistry() {
        println("==> starting local registry on $registry")
        compose("registry", "up", "-d")
    }

    fun close() {
        try {
            cleanupE2e()
        } finally {
            if (!useCurrentDocker) {
                if (keepDind) {
                    println("==> KEEP=1: leaving '$dindName' up (export DOCKER_HOST=tcp://127.0.0.1:$dindPort)")
                } else {
                    println("==> removing dind '$dindName'")
                    removeDind()
                }
            }
        }
    }

    /**
     * `docker:dind` declares `VOLUME /var/lib/docker` in its image config, so every `docker run` of it gets a
     * fresh anonymous volume that ends up holding the inner daemon's whole image store (~1 GB: the `kodkod:e2e`
     * build, `registry:2`, `busybox`, the testapp variants). Removing the container without `-v` orphans that
     * volume on the **host**, and a handful of runs is enough to fill a Docker Desktop VM disk.
     *
     * Both callers matter: this also runs before the container is started, to reclaim a dind that outlived a
     * previous run (`-Pkodkod.e2e.keepDind=true` / `KEEP=1`, or a JVM killed before [close] ran).
     *
     * Deliberately not a named volume mounted at `/var/lib/docker`: a persistent inner image store means the
     * suite no longer starts from a clean image state, which changes the code path the recorded fixtures
     * capture — with the image already present locally kodkod takes the registry-digest branch instead of
     * pulling (see the note in `DockerReplayTest`). The disposable anonymous volume is the point; it just has
     * to be reclaimed.
     */
    private fun removeDind() {
        hostDocker("rm", "-f", "-v", dindName, check = false)
    }

    fun publishVariant(variant: String) {
        println("==> publishing testapp:latest = $variant")
        when (variant) {
            "v1", "v2" -> docker(
                "build",
                "--target",
                variant,
                "--build-arg",
                "VARIANT=$variant",
                "-t",
                "$registry/testapp:latest",
                "-t",
                "$registry/testapp:$variant",
                "e2e/testapp",
            )

            "broken", "crasher" -> docker(
                "build",
                "-f",
                "e2e/testapp/Dockerfile.$variant",
                "-t",
                "$registry/testapp:latest",
                "e2e/testapp",
            )

            else -> error("unknown testapp variant: $variant")
        }

        repeat(20) { attempt ->
            if (docker("push", "$registry/testapp:latest", check = false).exitCode == 0) return
            if (attempt < 19) sleep(2)
        }
        error("push to $registry failed after retries")
    }

    /**
     * Run one scenario and tear its stack down afterwards, whatever happened.
     *
     * [env] is handed to that teardown because a compose file may *require* a variable to be resolvable
     * at all — `compose.digest.yml` interpolates `${TESTAPP_DIGEST:?...}` — and compose refuses to parse
     * the file without it. A `down` that cannot parse the file leaves the whole stack running for the
     * next scenario to trip over, and with `check = false` it does so in silence.
     */
    fun scenario(composeProject: String, env: Map<String, String> = emptyMap(), block: E2eHarness.() -> Unit) {
        println("[$composeProject] starting")
        try {
            block()
        } finally {
            compose(composeProject, "down", "-v", check = false, env = env)
        }
    }

    fun compose(file: String, vararg args: String, check: Boolean = true, env: Map<String, String> = emptyMap()): CommandResult {
        return docker("compose", "-f", "e2e/compose.$file.yml", *args, check = check, env = env)
    }

    fun docker(vararg args: String, check: Boolean = true, env: Map<String, String> = emptyMap()): CommandResult {
        return command(listOf("docker") + args, check = check, env = env, hostDocker = false)
    }

    fun hostDocker(vararg args: String, check: Boolean = true): CommandResult {
        return command(listOf("docker") + args, check = check, env = emptyMap(), hostDocker = true)
    }

    /** The `docker compose` plugin version (e.g. "2.29.7"), used to label recorded fixtures. */
    fun composeVersion(): String = docker("compose", "version", "--short", check = false).output.trim()

    fun inspect(format: String, target: String): String {
        val result = docker("inspect", "-f", format, target, check = false)
        return if (result.exitCode == 0) result.output.trim() else ""
    }

    fun health(container: String): String = inspect("{{.State.Health.Status}}", container)

    fun running(container: String): Boolean = inspect("{{.State.Running}}", container) == "true"

    fun startedAt(container: String): String = inspect("{{.State.StartedAt}}", container)

    fun variant(container: String): String = inspect("{{index .Config.Labels \"app.variant\"}}", container)

    /** MAC of [container]'s endpoint on [network], generated by the daemon unless one was requested. */
    fun endpointMac(container: String, network: String): String =
        inspect("{{index .NetworkSettings.Networks \"$network\" \"MacAddress\"}}", container)

    /**
     * The network interfaces [container] can see from the inside. A container whose shared network
     * namespace was destroyed still reports `Running` and still has a plausible `NetworkMode`, and this
     * is the only place the difference shows: everything but `lo` is gone.
     */
    fun interfaces(container: String): String =
        docker("exec", container, "ip", "-o", "addr", check = false).output

    /** Name of the volume mounted at [destination], as the daemon reports it in the top-level `Mounts`. */
    fun volumeName(container: String, destination: String): String =
        inspect("{{range .Mounts}}{{if eq .Destination \"$destination\"}}{{.Name}}{{end}}{{end}}", container)

    fun logHas(container: String, text: String): Boolean {
        return docker("logs", container, check = false).output.contains(text, ignoreCase = true)
    }

    fun containerIdsByName(name: String): List<String> {
        val result = docker("ps", "-aq", "--filter", "name=$name", check = false)
        return result.output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    fun waitUntil(timeoutSeconds: Long, description: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos()
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            sleep(2)
        }
        fail<Unit>("Timed out after ${timeoutSeconds}s waiting for: $description")
    }

    fun sleep(seconds: Long) {
        Thread.sleep(Duration.ofSeconds(seconds).toMillis())
    }

    private fun cleanupE2e() {
        println("==> tearing down e2e stacks")
        val digestEnv = mapOf("TESTAPP_DIGEST" to "sha256:${"0".repeat(64)}")
        listOf("autoheal", "update", "deps", "multinet", "container-mode", "rollback", "self", "digest").forEach {
            compose(it, "down", "-v", "--remove-orphans", check = false, env = digestEnv)
        }
        compose("registry", "down", "-v", "--remove-orphans", check = false, env = digestEnv)

        removeContainersByName("e2e-")
        removeContainersByName("_kodkod_old_")
        listOf("latest", "v1", "v2").forEach { tag ->
            docker("rmi", "$registry/testapp:$tag", check = false)
        }
        println("cleanup done (kodkod:e2e kept)")
    }

    /**
     * Deliberately **without** `-v`, unlike [removeDind] and the `compose down -v` above.
     *
     * Containers are matched by name *substring* (`e2e-`, `_kodkod_old_`), and under
     * `-Pkodkod.e2e.useCurrentDocker=true` — the mode re-recording fixtures runs in — every `docker` call goes
     * to the host daemon, where a container whose name merely contains one of those fragments may well belong
     * to somebody else. A `_kodkod_old_` backup in particular is the one container that is by construction the
     * *sole remaining reference* to a real service's anonymous volumes: kodkod removes the pre-update container
     * with `v=false` precisely so the data it carries survives the update (`DockerApi.remove`). Taking the
     * container out with `-v` here would take that data with it — the leftover would be cleaned up either way,
     * so the volume is all `-v` can add.
     *
     * The stacks' own anonymous volumes are already reclaimed by `compose down -v`, which knows what it created.
     */
    private fun removeContainersByName(name: String) {
        val ids = containerIdsByName(name)
        if (ids.isNotEmpty()) {
            docker(*((listOf("rm", "-f") + ids).toTypedArray()), check = false)
        }
    }

    private fun command(
        args: List<String>,
        check: Boolean,
        env: Map<String, String>,
        hostDocker: Boolean,
        timeout: Duration = DEFAULT_COMMAND_TIMEOUT,
    ): CommandResult {
        val processBuilder = ProcessBuilder(args)
            .directory(root.toFile())
            .redirectErrorStream(true)

        val processEnv = processBuilder.environment()
        processEnv["DOCKER_BUILDKIT"] = "1"
        processEnv["BUILDKIT_PROGRESS"] = "plain"
        if (hostDocker) {
            processEnv.remove("DOCKER_HOST")
        } else {
            dockerHost?.let {
                processEnv["DOCKER_HOST"] = it
                processEnv.remove("DOCKER_TLS_VERIFY")
                processEnv.remove("DOCKER_CERT_PATH")
            }
        }
        processEnv.putAll(env)

        val process = processBuilder.start()
        val output = StringBuilder()
        val reader = thread(start = true, isDaemon = true) {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(output) {
                        output.appendLine(line)
                        if (output.length > MAX_CAPTURE_CHARS) {
                            output.delete(0, output.length - MAX_CAPTURE_CHARS)
                        }
                    }
                }
            }
        }

        val exited = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!exited) {
            process.destroyForcibly()
            reader.join(1000)
            error("command timed out after $timeout: ${args.joinToString(" ")}")
        }

        val exitCode = process.exitValue()
        reader.join(1000)
        val text = synchronized(output) { output.toString().trimEnd() }
        val result = CommandResult(exitCode, text)
        if (check && exitCode != 0) {
            error(
                """
                command failed with exit code $exitCode
                cwd: ${root.pathString}
                command: ${args.joinToString(" ")}
                output:
                $text
                """.trimIndent(),
            )
        }
        return result
    }

    private companion object {
        val DEFAULT_COMMAND_TIMEOUT: Duration = Duration.ofMinutes(10)
        const val MAX_CAPTURE_CHARS = 128_000
    }
}

internal data class CommandResult(
    val exitCode: Int,
    val output: String,
)

/**
 * A `-Pkodkod.e2e.*` flag as Gradle hands it to the test JVM (`systemProperty`), read with the same
 * truthiness kodkod's own labels and env use. Shared with `DockerFixtureRecorder`, which gates on
 * `kodkod.e2e.useCurrentDocker` too and must not disagree about what "true" means.
 */
internal fun boolProperty(name: String): Boolean = System.getProperty(name)?.trim()?.lowercase() in TRUTHY
