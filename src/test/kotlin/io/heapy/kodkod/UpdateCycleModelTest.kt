package io.heapy.kodkod

import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Model-based testing of the update cycle: random stacks, random failures, and the handful of things
 * that must be true of the daemon afterwards no matter which of them came up.
 *
 * The hand-written cycle tests each describe one story somebody thought of. Every serious defect this
 * branch shipped and then had to unship was a story nobody thought of, and each took a full review
 * round to find: a cooldown that stopped a netns consumer from following its provider, a reconcile
 * rule that left a crashed replacement holding the service name, a gate that accepted a replacement
 * nothing had started, a container the cycle stopped and then forgot. All four are three or four
 * events deep — exactly the depth a generator reaches and a story-teller does not.
 *
 * So instead of asserting *what* the cycle does, this asserts what the daemon may never look like
 * once it has finished, whatever it did:
 *
 *  - two live containers never hold one name;
 *  - a running container is never joined to a network namespace that is gone;
 *  - a container kodkod does not manage is never touched;
 *  - a managed container is never left stopped in silence.
 *
 * Failures name the seed, and [describe] prints the world it generated, so a red run is a fixture you
 * can paste into [UpdaterTest] as a story somebody now has thought of.
 */
class UpdateCycleModelTest {
    private companion object {
        /** Worlds per property. Each runs up to three full cycles against an in-memory daemon. */
        const val WORLDS = 200
        const val ENABLE_LABEL = """{"kodkod.update.enable":"true"}"""
    }

    /**
     * One generated container, kept alongside the fake so invariants can be stated in world terms.
     *
     * [id] and [name] are deliberately different strings. Compose writes `network_mode:
     * container:<id>` and an id never equals a name, and kodkod decides between *restarting* a
     * dependent and *rebuilding* it on exactly that difference — a world where the two are the same
     * word makes that verdict ambiguous and its own findings unreadable.
     */
    private class Spec(
        val id: String,
        val name: String,
        val managed: Boolean,
        val stale: Boolean,
        /** Id — not name — of the container whose network namespace this one is joined to, if any. */
        val netnsProvider: String?,
    )

    private class World(val docker: FakeDockerClient, val specs: List<Spec>, val seed: Int) {
        val managed get() = specs.filter { it.managed }

        /**
         * Unmanaged containers with no relationship to anything kodkod manages. An unmanaged
         * *sidecar* is a different matter — kodkod is required to refresh one whose provider it
         * replaced — so only these may be asserted untouched.
         */
        val bystanders get() = specs.filter { !it.managed && it.netnsProvider == null }

        fun describe(): String = buildString {
            append("seed $seed\n")
            specs.forEach { spec ->
                append("  ${spec.id} (${spec.name}): managed=${spec.managed} stale=${spec.stale}")
                spec.netnsProvider?.let { append(" netns=container:$it") }
                append('\n')
            }
            append("  failCreate=${docker.failCreate} failStart=${docker.failStart} failRemove=${docker.failRemove}\n")
            append("  ops=${docker.ops}")
        }
    }

    /**
     * A random stack. Managed containers may share a namespace with each other and may be stale. An
     * unmanaged container is either a bystander — unrelated to everything, so touching it is always a
     * mistake — or a sidecar joined to a managed container's namespace, which kodkod is obliged to
     * refresh when it replaces the provider. Both shapes are generated: the first is what the
     * "never touched" property is about, the second is the only way the daemon-wide dependent scan is
     * reached at all, since a consumer inside the monitored set is handled by the ordered pass.
     */
    private fun world(seed: Int): World {
        val random = Random(seed)
        val docker = FakeDockerClient()
        val clock = FakeClock()
        docker.clock = clock

        val managedCount = random.nextInt(1, 4)
        val unmanagedCount = random.nextInt(0, 3)
        val specs = mutableListOf<Spec>()

        repeat(managedCount) { i ->
            // A namespace can only be shared with a container that already exists, so edges point back.
            val provider = specs.filter { it.managed && it.netnsProvider == null }
                .takeIf { it.isNotEmpty() && random.nextInt(3) == 0 }
                ?.let { it[random.nextInt(it.size)].id }
            specs += Spec(
                id = "id-m$i", name = "svc-m$i",
                managed = true, stale = random.nextInt(3) != 0, netnsProvider = provider,
            )
        }
        repeat(unmanagedCount) { i ->
            // Half of them are sidecars on a managed container — the shape only the daemon-wide scan
            // can rescue, and the one that fails silently when it is not rescued.
            val provider = specs.filter { it.managed }
                .takeIf { random.nextBoolean() }
                ?.let { it[random.nextInt(it.size)].id }
            specs += Spec(id = "id-u$i", name = "svc-u$i", managed = false, stale = false, netnsProvider = provider)
        }

        specs.forEach { spec ->
            val hostConfig = spec.netnsProvider?.let { """{"NetworkMode":"container:$it"}""" } ?: "{}"
            docker.container(
                id = spec.id,
                name = spec.name,
                imageRef = "img-${spec.name}:1",
                currentImageId = "sha256:${spec.name}-old",
                labels = if (spec.managed) ENABLE_LABEL else "{}",
                hostConfig = hostConfig,
            )
            // Staleness is the tag resolving to an image id the container is not running.
            val resolved = if (spec.stale) "sha256:${spec.name}-new" else "sha256:${spec.name}-old"
            docker.images["img-${spec.name}:1"] = jsonObj("""{"Id":"$resolved","Config":{},"RepoDigests":[]}""")
        }

        // Failure injection, aimed only at containers kodkod is allowed to act on.
        val managed = specs.filter { it.managed }
        val victim = managed[random.nextInt(managed.size)]
        if (random.nextInt(4) == 0) docker.failCreate += victim.name
        if (random.nextInt(4) == 0) docker.failStart += "new-${victim.name}-0"
        if (random.nextInt(5) == 0) docker.failRemove += victim.id
        // A daemon that also refuses to start the ORIGINAL back is what turns a failed update into a
        // container left stopped. Without this every rollback lands and the "never left stopped in
        // silence" property has nothing to be true about — verified by mutation: silencing the report
        // that names such a container went unnoticed until these worlds could produce one.
        if (random.nextInt(6) == 0) docker.failStart += victim.id

        return World(docker, specs, seed)
    }

    private fun run(world: World, cycles: Int): String {
        val clock = FakeClock()
        world.docker.clock = clock
        val updater = Updater(
            world.docker,
            Config.fromEnv(mapOf("KODKOD_UPDATE_MONITOR_ALL" to "false")::get),
            selfId = null,
            clock,
            clock,
        )
        return captureLog { repeat(cycles) { updater.runOnce() } }
    }

    /** Every container the daemon still knows about, as `id -> (name, state, netns ref)`. */
    private fun liveState(docker: FakeDockerClient): Map<String, Triple<String, String, String?>> =
        docker.listContainers(all = true, filters = emptyMap()).associate { element ->
            val summary = element.jsonObject
            val id = summary.str("Id").orEmpty()
            val name = summary.containerNames().firstOrNull().orEmpty()
            val state = summary.str("State").orEmpty()
            val netns = summary.obj("HostConfig")?.str("NetworkMode")
                ?.takeIf { it.startsWith("container:") }?.removePrefix("container:")
            id to Triple(name, state, netns)
        }

    private fun forEachWorld(cycles: Int, check: (World, String) -> Unit) {
        for (seed in 0 until WORLDS) {
            val world = world(seed)
            val log = try {
                run(world, cycles)
            } catch (e: Throwable) {
                throw AssertionError("a cycle must not throw out of runOnce\n${world.describe()}", e)
            }
            try {
                check(world, log)
            } catch (e: Throwable) {
                throw AssertionError("${e.message}\n${world.describe()}", e)
            }
        }
    }

    /**
     * The daemon's name index is not a suggestion: a recreate parks the original under a backup name
     * precisely so the replacement can take the real one, and every rollback path has to hand it back.
     * Two containers answering to one name means one of them is unreachable by name — which is how
     * `compose up` ends up acting on a corpse.
     */
    @Test
    fun no_two_live_containers_ever_hold_the_same_name() {
        forEachWorld(cycles = 2) { world, _ ->
            val byName = liveState(world.docker).entries.groupBy { it.value.first }
            byName.forEach { (name, holders) ->
                if (holders.size > 1) {
                    fail<Unit>("'$name' is held by ${holders.map { it.key }} at once")
                }
            }
        }
    }

    /**
     * A container joined to another's network namespace loses its interfaces the moment that other
     * container is destroyed, and goes on reporting `running` while it does. Nothing downstream
     * notices, which is what makes it the worst outcome a cycle can leave behind.
     *
     * Two endings have to be told apart, and the difference is what kodkod can still do about it.
     * A provider that is **gone** is final: the dependent can never be started again as it stands, so
     * leaving it that way is kodkod's fault whatever else happened. A provider that is merely **down**
     * is not: while the daemon is refusing to start it, no amount of care can give the dependent its
     * namespace back — but kodkod has to have said so, because a silent one is a container nothing
     * will ever come back to.
     */
    @Test
    fun no_running_container_is_left_joined_to_a_namespace_that_is_gone() {
        forEachWorld(cycles = 2) { world, log ->
            val live = liveState(world.docker)
            live.forEach { (id, state) ->
                val (_, lifecycle, netns) = state
                if (lifecycle != "running" || netns == null) return@forEach
                val provider = live.entries.firstOrNull { (pid, p) ->
                    pid == netns || p.first == netns || (netns.length >= 4 && pid.startsWith(netns))
                }
                if (provider == null) {
                    fail<Unit>("$id is running but joined to container:$netns, which no longer exists")
                } else if (provider.value.second != "running" && !log.contains(provider.value.first)) {
                    fail<Unit>(
                        "$id is running but joined to container:$netns, which is ${provider.value.second} " +
                            "and which the log never mentions",
                    )
                }
            }
        }
    }

    /**
     * With `KODKOD_UPDATE_MONITOR_ALL=false` an unlabelled container is somebody else's. kodkod may
     * still touch one as a create-time dependent of something it does manage — these worlds
     * deliberately leave the unmanaged ones unrelated, so any mention of them is a mistake.
     */
    @Test
    fun a_container_kodkod_does_not_manage_is_never_touched() {
        forEachWorld(cycles = 2) { world, _ ->
            world.bystanders.forEach { spec ->
                val touched = world.docker.ops.filter { it.contains(spec.id) || it.contains(spec.name) }
                assertTrue(touched.isEmpty(), "bystander ${spec.id} was acted on: $touched")
            }
        }
    }

    /**
     * The failure mode a whole review round was spent on: a container the cycle stopped and never
     * brought back is invisible to discovery (`status=running`), to the backup sweep (`_kodkod_old_*`)
     * and to everything else, so nothing ever comes back to it.
     *
     * Stated as "the log mentions it" this property is worth nothing — kodkod prefixes almost every
     * line with `[name]`, so a container it touched at all satisfies that by accident (confirmed by
     * mutation: silencing the line that reports such a container went unnoticed). The guarantee that
     * actually matters is not a sentence but a retry, so that is what is asserted: whatever a cycle
     * leaves stopped, the *next* cycle must reach for again.
     */
    @Test
    fun whatever_a_cycle_leaves_stopped_the_next_cycle_reaches_for_again() {
        for (seed in 0 until WORLDS) {
            val world = world(seed)
            val clock = FakeClock()
            world.docker.clock = clock
            val updater = Updater(
                world.docker,
                Config.fromEnv(mapOf("KODKOD_UPDATE_MONITOR_ALL" to "false")::get),
                selfId = null,
                clock,
                clock,
            )

            captureLog { updater.runOnce() }
            val afterFirst = liveState(world.docker)
            val opsAfterFirst = world.docker.ops.size
            val strandedByFirst = world.managed
                .filter { spec -> afterFirst[spec.id]?.second?.let { it != "running" } == true }
            if (strandedByFirst.isEmpty()) continue

            captureLog { updater.runOnce() }
            val secondCycle = world.docker.ops.drop(opsAfterFirst)

            strandedByFirst.forEach { spec ->
                assertTrue(
                    secondCycle.any { it.contains(spec.id) || it.contains(spec.name) },
                    "${spec.name} was left stopped by the first cycle and the second never reached for it: " +
                        "second cycle did $secondCycle\n${world.describe()}",
                )
            }
        }
    }
}
