package io.heapy.kodkod

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Keeps containers up to date by pulling their image tag and, when the resolved image id changes,
 * recreating the container from its existing configuration against the new image. Driven by labels:
 *
 *  - `<ns>.update.enable=true|false` — opt in/out (default follows [Config.updateMonitorAll])
 *  - `<ns>.stop.timeout=<seconds>`   — stop timeout used while recreating
 *  - `<ns>.depends-on=a,b`           — restart ordering for non-compose users (compose stacks are
 *                                       ordered automatically from the `com.docker.compose.*` labels)
 *
 * A cycle works on the whole monitored set at once so it can honour dependency order: containers are
 * stopped in reverse dependency order and brought back in forward order. Ordinary dependents are
 * restarted; create-time dependents (`--link` / `network_mode: container:`) are recreated so Docker
 * refreshes their references. Containers pinned to a digest (`image@sha256:...`) are never stale but can
 * still be restarted or recreated as a dependent. Create-time dependents that are *not* monitored are
 * searched for across the whole daemon once the cycle is done — see [refreshCreateTimeDependents].
 *
 * Compose's `depends_on` carries a condition and a `restart` flag per edge. `condition: service_healthy`
 * is always honoured — a dependent waits for its dependency's healthcheck to pass, bounded by
 * `KODKOD_DEPENDENCY_HEALTH_TIMEOUT` — while `restart: false` is obeyed only under
 * `KODKOD_RESPECT_DEPENDS_ON_RESTART` (see [Config.respectDependsOnRestart]).
 *
 * A replacement container is watched for a short window after `start` (see `verifyStarted`) and the
 * container and image it replaced are destroyed only once it has proven it stays up. An update that
 * failed that gate is remembered for `KODKOD_UPDATE_FAILURE_COOLDOWN` so a broken `:latest` costs one
 * interrupted service instead of one per cycle.
 *
 * A cycle comes in two halves: [plan] decides everything from reads alone — including the image pull,
 * which is where all the waiting is — and [apply] makes every change. Only the second half has to be
 * serialized against autoheal, which is the whole point of the split; see `updateCycle` in `main`.
 *
 * [clock] and [sleeper] default to the real ones and exist so waiting logic can be driven from tests
 * without spending the wall-clock time it describes.
 */
class Updater(
    private val api: DockerClient,
    private val config: Config,
    private val selfId: String?,
    private val clock: WallClock = WallClock.SYSTEM,
    private val sleeper: Sleeper = Sleeper.SYSTEM,
) {
    private val ns = config.labelNamespace

    /**
     * Updates that already took a service down, by container id. The [FailedUpdate.imageId] is part of
     * the memory rather than of the key so that a tag moving on to *another* image is an obvious reset
     * rather than a second entry: the container is only ever suppressed for the exact image that failed.
     *
     * This is the only state [Updater] keeps between cycles, which is what makes it necessary at all —
     * a cycle that knows nothing about the previous one repeats a failed recreate (stop, rename, create,
     * fail, roll back) every `KODKOD_UPDATE_INTERVAL`, forever. An entry is dropped the moment it stops
     * applying: the tag moved on, the cooldown ran out, or the update went through after all.
     */
    private val failedUpdates = HashMap<String, FailedUpdate>()

    /**
     * An image that failed to come up on a container, and when that was last attempted — twice over,
     * because the two readings answer different questions: [attemptedAtNanos] is what the cooldown is
     * measured with (elapsed time, immune to the wall clock being corrected under us) and
     * [attemptedAtMillis] is what the log line naming the next attempt is printed from.
     */
    private class FailedUpdate(val imageId: String, val attemptedAtNanos: Long, val attemptedAtMillis: Long)

    /**
     * Create-time dependents kodkod stopped for a recreate and could not put back, by the id of the
     * container it left stopped — see [recoverStrandedDependents].
     *
     * `holdBackUnsafeProviders` keeps the *likely* version of this failure from ever being set up, but a
     * recreate can still fail for a reason that has nothing to do with the image (a port the previous
     * process has not released, a network being rewired, a daemon that stopped answering mid-way). When
     * it does, the container that used to serve the name is joined to a network namespace this cycle
     * destroyed and cannot be started by anybody — not by kodkod's rollback, not by a human's
     * `docker start`. Only a recreate against the container now holding the provider's name can bring
     * it back, and nothing else in the system would ever attempt one: discovery lists running containers
     * only, and the name is held by the very container that is down.
     */
    private val strandedDependents = LinkedHashMap<String, StrandedDependent>()

    /** A container [recoverStrandedDependents] has to rebuild, and the provider name to rebuild it against. */
    private class StrandedDependent(val name: String, val providerName: String)

    /**
     * Bring back containers a kodkod that died mid-recreate left parked under their
     * `<name>_kodkod_old_<short id>` backup name.
     *
     * [rollback] covers a recreate *step* that failed, but nothing in-process can cover a SIGKILL, an
     * OOM kill, or a host reboot between `rename(old -> backup)` and the replacement's `start`. In that
     * window nothing holds the service name, and the container that used to serve it is stopped under a
     * name no later cycle looks at (discovery lists running containers only) — the service is simply
     * down until this pass finds it.
     *
     * Called at the top of every cycle *and* from `main` at process start, the latter regardless of
     * [Config.updateEnabled]: an operator who switched the updater off after being burned would
     * otherwise never get the orphan back.
     */
    fun reconcileOrphanedBackups() {
        val candidates = listByName(BACKUP_MARKER) ?: return
        for (element in candidates) {
            val summary = element.jsonObject
            val id = summary.str("Id") ?: continue
            for (backupName in summary.containerNames()) {
                val name = canonicalNameOfBackup(backupName, id) ?: continue
                reconcileBackup(id, backupName, name)
            }
        }
    }

    /**
     * Decide what a single leftover [backupName] means. The container currently holding the service
     * [name] is what tells the possible histories apart: a replacement that is up means the recreate
     * got all the way through and only the cleanup was cut short, so the backup is garbage; nothing on
     * the name means the backup is the only copy of a working service.
     *
     * The third case — the name is held by a container that is **not** running — is the dangerous one,
     * and is decided by [holderEverStayedUp]: a replacement that never proved itself is what a kodkod
     * killed inside the liveness gate leaves behind, and finishing that rollback is exactly this pass's
     * job; a replacement that ran for a long time and was stopped afterwards is somebody's decision, and
     * force-removing it to start an older container in its place is not a call this pass may take.
     */
    private fun reconcileBackup(id: String, backupName: String, name: String) {
        // Read back from the daemon rather than from the listing above, which was narrowed to backups.
        val holder = listByName(name)?.map { it.jsonObject }?.firstOrNull { name in it.containerNames() }
        if (holder == null) {
            Log.warn(
                "[$name] found '$backupName' left over from an interrupted recreate and nothing serving " +
                    "'$name' — restoring it",
            )
            restoreBackup(id, name)
            return
        }
        val holderId = holder.str("Id").orEmpty()
        val holderState = holder.str("State") ?: "running"
        if (holderState == "running") {
            Log.warn(
                "[$name] removing '$backupName' left over from an interrupted recreate — its replacement " +
                    "${holderId.take(12)} is running",
            )
            try {
                api.remove(id, force = true)
            } catch (e: Exception) {
                Log.warn("[$name] could not remove the leftover backup '$backupName': ${e.message}")
            }
            return
        }
        if (holderId.isEmpty()) {
            Log.warn("[$name] '$backupName' is a leftover backup and the daemon named no holder of '$name'")
            return
        }
        when (holderEverStayedUp(holderId, name)) {
            true -> Log.warn(
                "[$name] '$backupName' is a leftover backup, but the container holding '$name' " +
                    "(${holderId.take(12)}) is $holderState after a run long enough to have been accepted — it " +
                    "may be an up-to-date replacement that was stopped on purpose. Leaving both alone: decide " +
                    "by hand which one should serve '$name'",
            )
            null -> Log.warn(
                "[$name] '$backupName' is a leftover backup and the container holding '$name' " +
                    "(${holderId.take(12)}) is $holderState, but the daemon does not say how long it ran — " +
                    "leaving both alone: decide by hand which one should serve '$name'",
            )
            false -> {
                Log.error(
                    "[$name] '$backupName' is a leftover backup and the container holding '$name' " +
                        "(${holderId.take(12)}) never ran the ${provenUptimeMs / 1000}s a replacement has to " +
                        "survive to be accepted — REMOVING it and putting the backup back in its place",
                )
                if (freeName(name, holderId)) restoreBackup(id, name)
            }
        }
    }

    /**
     * Whether the container holding a service name ever ran long enough for kodkod to have accepted it,
     * `null` when the daemon does not say. This is what separates the two ways a **stopped** container
     * can come to hold the name of a service kodkod has a backup of:
     *
     *  - it is the replacement of a recreate that was cut short — killed before `start`, or inside
     *    `verifyStarted` while it was crashing — and the backup is the only working copy of the service.
     *    Discovery lists running containers only, so nothing else will ever look at either of them again;
     *    leaving them be means the service stays down until a human notices.
     *  - it served for a while and was stopped afterwards, which nothing in kodkod does: an operator did,
     *    and undoing that by destroying it is not this pass's call.
     *
     * The discriminator is the container's own uptime against the window a replacement has to survive to
     * be accepted ([Config.updateVerifySeconds]) — the same measure `verifyStarted` applies live, which is
     * what makes this the completion of that decision rather than a new one. The floor underneath it
     * ([MIN_PROVEN_UPTIME_MS]) matters because that window can be configured down to zero.
     *
     * The daemon records nothing that separates the two histories outright — both leave a stopped
     * container holding the name, created and finished within seconds of the backup either way, and an
     * exit code that is as likely to be non-zero for a `docker stop` (143) as for a crash. So this is a
     * verdict on the only evidence there is, and it can be wrong in one direction: an operator who stops
     * a *completed* update inside that same window, in the narrower window where kodkod also failed to
     * delete the backup, has that replacement removed and the previous container started in its place.
     * That is deliberate. The mistake costs one container (its volumes survive — `remove`
     * sends `v=false`) and is undone by the next cycle, which updates the restored container to the
     * same image again; the opposite mistake — leaving a crashing replacement on the name — costs a
     * service that stays down until a human notices, which is what this whole pass exists to prevent.
     * The removal is logged at ERROR, naming the container and the threshold it missed.
     */
    private fun holderEverStayedUp(holderId: String, name: String): Boolean? {
        val state = try {
            api.inspectContainer(holderId).obj("State")
        } catch (e: Exception) {
            Log.warn("[$name] could not inspect ${holderId.take(12)}, which holds the name: ${e.message}")
            return null
        } ?: return null
        // No start time at all is an answer, and the clearest one: the container was created and never
        // run, which only a recreate cut short between `create` and `start` leaves behind.
        val startedAt = state.dockerTime("StartedAt") ?: return false
        val finishedAt = state.dockerTime("FinishedAt") ?: return null
        return finishedAt - startedAt >= provenUptimeMs
    }

    /** How long a container must have run to count as one kodkod would have accepted. */
    private val provenUptimeMs: Long
        get() = maxOf(config.updateVerifySeconds * 1000L, MIN_PROVEN_UPTIME_MS)

    /** Give [id] the service name back and start it; a rename that fails leaves the container alone. */
    private fun restoreBackup(id: String, name: String) {
        if (!renameBack(id, name)) return
        try {
            api.start(id)
            Log.info("[$name] recovered from an interrupted recreate")
        } catch (e: Exception) {
            Log.error(
                "[$name] restored the name of ${id.take(12)} but could not start it — the service is " +
                    "DOWN and needs a human: ${e.message}",
            )
        }
    }

    /**
     * Containers whose name contains [fragment], stopped ones included. The daemon's own `name` filter
     * does the narrowing so the pass does not have to pull (or record) every container on the host.
     */
    private fun listByName(fragment: String): JsonArray? =
        try {
            api.listContainers(all = true, filters = mapOf("name" to listOf(fragment)))
        } catch (e: Exception) {
            Log.error("reconcile: could not list containers named like '$fragment': ${e.message}")
            null
        }

    /**
     * Rebuild the create-time dependents a failed recreate left stopped (see [strandedDependents]),
     * against whatever holds their provider's name now.
     *
     * Retried every cycle rather than once: the container is already down, so an attempt that fails
     * again costs nothing but a create and the liveness window it was going to spend anyway — and the
     * most likely reason for the first failure (an image the tag has since moved on from, a daemon that
     * was busy) is one that later cycles fix by themselves. An entry is dropped as soon as it stops
     * meaning anything: the container came back up, somebody removed it, or the rebuild went through.
     *
     * A rollback that failed for any *other* reason is deliberately not remembered here: that container
     * is intact and under its own name, so `docker start` puts it back, whereas one joined to a
     * destroyed namespace cannot be started by anybody.
     */
    private fun recoverStrandedDependents() {
        if (strandedDependents.isEmpty()) return
        // A snapshot: a rebuild that fails puts its own entry straight back.
        for ((id, stranded) in strandedDependents.toList()) {
            val inspect = try {
                api.inspectContainer(id)
            } catch (e: Exception) {
                if (e is DockerException && e.status == 404) {
                    Log.warn(
                        "[${stranded.name}] the container kodkod left stopped is gone — whoever removed it owns " +
                            "the service now, kodkod stops trying to rebuild it",
                    )
                    strandedDependents.remove(id)
                } else {
                    Log.warn("[${stranded.name}] could not check on the container kodkod left stopped: ${e.message}")
                }
                continue
            }
            if (inspect.obj("State")?.str("Running") == "true") {
                Log.info("[${stranded.name}] is running again — nothing left to rebuild")
                strandedDependents.remove(id)
                continue
            }
            Log.warn(
                "[${stranded.name}] was left stopped by a recreate that could not be rolled back, and its " +
                    "network namespace is gone — rebuilding it against ${stranded.providerName}",
            )
            val target = toTarget(id, inspect).also {
                it.linkedToRecreate = true
                it.networkModeContainerName = stranded.providerName
            }
            try {
                recreate(target)
                strandedDependents.remove(id)
                Log.info("[${stranded.name}] is serving again")
            } catch (e: Exception) {
                Log.error("[${stranded.name}] could not be rebuilt and is still DOWN: ${e.message}")
            }
        }
    }

    /** A whole cycle: decide what to do, then do it. The two halves are separate for [main]'s sake. */
    fun runOnce() = apply(plan())

    /**
     * Decide what this cycle should do, **without changing anything**: discovery, the registry probe
     * and the image pull, which is where all of a cycle's waiting lives — a pull of a large image is
     * granted minutes of idle time by design (see [DockerApi]), and a stalled registry can stretch it
     * further. None of it touches a container, which is what lets [main] run this half outside the
     * cycle lock so autoheal is not held off by somebody else's slow download.
     *
     * The only state it does change is kodkod's own: [failedUpdates] forgets a memory that no longer
     * applies (see [suppressedByCooldown]).
     */
    internal fun plan(): UpdatePlan {
        // A memory whose window has run out no longer decides anything, and a container that was
        // removed never comes back to have its entry dropped at check time — so the map is swept here
        // rather than growing by one entry per container that ever failed an update.
        failedUpdates.values.removeIf { clock.nanos() - it.attemptedAtNanos >= cooldownNanos }
        val targets = collectTargets()
        if (targets.isEmpty()) return UpdatePlan.NOTHING

        markStale(targets)
        propagateLinkedRestart(targets, config.respectDependsOnRestart)
        holdBackUnsafeProviders(targets)
        if (targets.none { it.toRestart }) {
            Log.info("update: all monitored containers are up to date")
            return UpdatePlan.NOTHING
        }
        return UpdatePlan(topoSort(targets))
    }

    /**
     * Take a container out of this cycle when restarting it would force a create-time dependent through
     * a recreate that **cannot be rolled back**.
     *
     * A netns consumer (`network_mode: service:x`, `--link`) is recreated whenever its provider moves,
     * and that recreate is built from the consumer's image *ref*. While the ref still names the image the
     * consumer is running, the replacement is the container that is already up and a failure is a blip;
     * once the ref has moved on — the consumer's own update is pending ([Target.stale]) or is being held
     * back because that exact image already failed here ([Target.updateSuppressed]) — the recreate
     * genuinely can fail. And by then it is beyond rescue: the provider's old container was force-removed
     * the moment its replacement passed the liveness gate, so the consumer's original container, whose
     * `HostConfig.NetworkMode` still names that id, can no longer be started at all. The rollback's
     * `start` is refused, and the service is left stopped under its own name — where discovery
     * (`status=running`) and [reconcileOrphanedBackups] (`_kodkod_old_*`) both walk straight past it.
     *
     * So the provider waits instead. The consumer's own update goes ahead this cycle *by itself*, which
     * is the one shape that is safe: nothing else moved, so a failure rolls back onto a namespace that is
     * still there. The provider's update follows a cycle later, when the ref names what the consumer runs.
     * A consumer whose image is broken for good therefore keeps its provider back for as long as the
     * cooldown does — deliberately: a delayed update is recoverable and is announced every cycle, while
     * the alternative is a container that is down with no way back.
     *
     * The hold-back follows create-time edges transitively: a provider that is itself joined to another
     * container's namespace would be dragged along by *its* provider, which would drag the consumer along
     * with it.
     */
    private fun holdBackUnsafeProviders(targets: List<Target>) {
        val byId = targets.associateBy { it.id }
        // provider to hold back -> what a log line should say it is being held back for.
        val queue = ArrayDeque<Pair<Target, String>>()
        for (dependent in targets) {
            if (!dependent.stale && !dependent.updateSuppressed) continue
            val moving = dependent.createTimeDeps.mapNotNull(byId::get).filter { it.toRestart }
            if (moving.isEmpty()) continue
            for (provider in moving) {
                queue += provider to (
                    "${dependent.name} ${dependent.createTimeRelationTo(provider.name)} and its own image has " +
                        "moved on, so the recreate this restart forces would build ${dependent.name} from " +
                        "${dependent.imageRef} — and once ${provider.name}'s old container is gone that recreate " +
                        "cannot be undone. ${dependent.name}'s own update goes ahead alone this cycle; " +
                        "${provider.name} follows once it has settled"
                    )
            }
        }
        if (queue.isEmpty()) return
        while (queue.isNotEmpty()) {
            val (provider, why) = queue.removeFirst()
            if (provider.restartHeldBack) continue
            provider.restartHeldBack = true
            Log.warn("[${provider.name}] not restarting it this cycle — $why")
            provider.createTimeDeps.mapNotNull(byId::get).mapTo(queue) {
                it to "restarting it would restart ${provider.name}, which is being held back"
            }
        }
        // What propagated through a container that is no longer moving has to be recomputed from scratch:
        // a dependent marked only because of it must not be restarted for a restart that is not happening.
        for (target in targets) {
            target.linkedToRestarting = false
            target.linkedToRecreate = false
        }
        propagateLinkedRestart(targets, config.respectDependsOnRestart)
    }

    /**
     * Carry out [plan] — the half that stops, renames, creates, starts and removes, and therefore the
     * only half that has to be serialized against autoheal.
     *
     * [reconcileOrphanedBackups] and [recoverStrandedDependents] belong here rather than in [plan] for the
     * same reason: they rename, create and start containers. They stay first in the cycle, and
     * unconditional — both are about a service that is down right now, and a cycle that found nothing to
     * update is exactly the cycle that would otherwise walk past it.
     */
    internal fun apply(plan: UpdatePlan) {
        reconcileOrphanedBackups()
        recoverStrandedDependents()
        if (!plan.hasWork || !isCurrent(plan)) return
        val ordered = plan.targets

        // Stop dependents before the dependencies they rely on.
        for (target in ordered.asReversed()) {
            if (!target.toRestart) continue
            try {
                stopGracefully(target)
            } catch (e: Exception) {
                Log.error("[${target.name}] stop failed: ${e.message}")
            }
        }
        // Bring everything back in dependency order: recreate stale/create-time-linked containers,
        // restart ordinary dependents.
        val byId = ordered.associateBy { it.id }
        for (target in ordered) {
            if (!target.toRestart) continue
            awaitHealthyDependencies(target, byId)
            try {
                if (target.toRecreate) recreate(target) else startDependent(target)
            } catch (e: Exception) {
                Log.error("[${target.name}] ${if (target.toRecreate) "recreate" else "restart"} failed: ${e.message}")
            }
        }
        refreshCreateTimeDependents(ordered)
    }

    /**
     * Whether the daemon still agrees with [plan]. The plan was built outside the cycle lock and the
     * pull it waited for can take minutes, so "this container runs that image" is a statement about the
     * past by the time we get here — while every mutation below aims at the container id and the image
     * id the plan recorded: a `remove` at the wrong id destroys somebody else's container, and a prune
     * at the wrong image id deletes a live one.
     *
     * Only the containers the plan means to touch are re-checked, and a plan that no longer holds is
     * dropped **whole**: the targets are a dependency graph, and going ahead with the half of it that
     * still checks out means stopping containers for a dependency that is no longer being updated. The
     * next cycle re-plans from the state that actually exists, one interval later.
     *
     * The fresh inspect is also **kept**, because it is what the replacement is built from: a
     * `docker update`, a `docker network connect` or a label change made while the image downloaded
     * would otherwise be quietly reverted by a create body describing the container as it was before
     * the pull.
     */
    private fun isCurrent(plan: UpdatePlan): Boolean {
        for (target in plan.work) {
            val inspect = try {
                api.inspectContainer(target.id)
            } catch (e: Exception) {
                Log.warn(
                    "update: dropping this cycle's plan — ${target.name} (${target.id.take(12)}) could no longer " +
                        "be inspected, so the state it was planned against is gone: ${e.message}",
                )
                return false
            }
            val imageId = inspect.str("Image").orEmpty()
            if (imageId != target.currentImageId) {
                Log.warn(
                    "update: dropping this cycle's plan — ${target.name} now runs ${imageId.shortId()} rather " +
                        "than the ${target.currentImageId.shortId()} it was planned against, so something else " +
                        "changed it while kodkod was planning",
                )
                return false
            }
            // Discovery only ever lists running containers, so every target was running when the plan
            // was made. One that is not any more was stopped by somebody else during the pull, and
            // stopping, renaming, recreating and *starting* it would silently undo that decision.
            if (inspect.obj("State")?.str("Running") != "true") {
                Log.warn(
                    "update: dropping this cycle's plan — ${target.name} (${target.id.take(12)}) is no longer " +
                        "running, so it was stopped while kodkod was planning and recreating it would start it " +
                        "again behind whoever stopped it",
                )
                return false
            }
            target.inspect = inspect
        }
        return true
    }

    // --- create-time dependents outside the monitored set -----------------------------------

    /**
     * Refresh containers wired to this cycle's containers at create time that are **not** in the
     * monitored set — the blind spot [resolveLinks] cannot cover, since it can only relate targets to
     * each other.
     *
     * With the documented default `KODKOD_UPDATE_MONITOR_ALL=false`, discovery is pre-filtered by the
     * daemon to kodkod-labelled containers, so an unlabelled sidecar on `network_mode: service:app` is
     * invisible to the whole cycle: kodkod recreates `app`, force-removes the container whose namespace
     * the sidecar is joined to, and the sidecar goes on reporting `Running` with no interfaces and
     * nothing in its log. A `--link` dependent outside the set keeps a stale address the same way.
     *
     * Run after the whole bring-back pass, because a dependent may only be pointed at a replacement
     * that is already up. What each dependent needs then depends on how its reference is spelled — see
     * [Dependent.pinnedToProviderId]: an id that no longer exists has to be rebuilt, anything else is
     * refreshed by a restart. Both are announced; a dependent kodkod finds but cannot fix is a WARN,
     * because "sidecar with no network" is a state nothing else in the system reports.
     */
    private fun refreshCreateTimeDependents(targets: List<Target>) {
        // Both ids of every target the cycle actually brought back: what it already handled must not be
        // handled twice, and a dependent the graph recreated answers under a *new* id whose NetworkMode
        // now names the provider by name — which would otherwise read as a fresh find and recreate it
        // again. A target the cycle did *not* touch is not "handled" and stays in scope: the graph can
        // only relate targets to each other, so a monitored container wired at create time to something
        // outside the monitored set is found here or not at all.
        val handled = targets.filter { it.toRestart }.flatMapTo(HashSet()) { listOf(it.id, it.liveId) }
        // A dependent that had to be refreshed becomes a provider in its own right: `c` joined to `b`
        // joined to `a` loses its namespace when `b` is recreated, exactly as `b` lost it when `a` was.
        // Every container this cycle brought back is a starting point, at depth 0: the bound below is on
        // the length of the *chains followed from them*, never on how many of them there are — a stack of
        // forty updated containers is not a namespace chain, and dropping the last eight would leave
        // their sidecars broken for a reason that has nothing to do with them.
        val queue = ArrayDeque<RefreshedProvider>()
        targets.filter { it.toRestart }.mapTo(queue) {
            RefreshedProvider(it.id, it.name, it.liveId, it.composeProject, depth = 0)
        }
        // Termination does not rest on the bound: a container is enqueued only when `handled` accepted
        // it, so each one is followed at most once.
        val abandoned = ArrayList<String>()
        while (queue.isNotEmpty()) {
            val provider = queue.removeFirst()
            val asProvider = DependencyProvider(provider.id, setOf(provider.name), provider.composeProject)
            for (dependent in findDependents(api, asProvider)) {
                if (!handled.add(dependent.id)) continue
                if (isSelf(dependent.id, dependent.labels, selfId)) continue
                val liveId = refreshDependent(dependent, provider) ?: continue
                handled += liveId
                val next = RefreshedProvider(
                    dependent.id,
                    dependent.name,
                    liveId,
                    dependent.labels.label(COMPOSE_PROJECT_LABEL),
                    provider.depth + 1,
                )
                if (next.depth <= MAX_DEPENDENT_CHAIN) queue += next else abandoned += next.name
            }
        }
        if (abandoned.isNotEmpty()) {
            Log.warn(
                "stopped following create-time dependents past $MAX_DEPENDENT_CHAIN links — anything joined " +
                    "to ${abandoned.joinToString(", ")} may be left on a dead network namespace",
            )
        }
    }

    /** The id serving [dependent] once it was refreshed, or `null` when nothing was (or could be) done. */
    private fun refreshDependent(dependent: Dependent, provider: RefreshedProvider): String? {
        val where = "[${dependent.name} (${dependent.short})]"
        val replaced = provider.replaced
        val relation = dependent.kind.relationTo(provider.name)
        val what = if (replaced) "replaced" else "restarted"
        if (!dependent.running) {
            // Starting a container somebody else stopped is not this cycle's call, but staying quiet
            // about one whose create-time reference just died would leave the operator to find out from
            // a container that refuses to start much later, for no visible reason.
            val doomed = if (dependent.pinnedToProviderId && replaced) {
                " — and it references that container by id, so it will refuse to start until it is recreated"
            } else {
                ""
            }
            Log.warn("$where $relation, which this cycle $what, but is ${dependent.state} — leaving it alone$doomed")
            return null
        }
        if (dependent.pinnedToProviderId && replaced) {
            Log.warn(
                "$where $relation by id and that container is gone — recreating it against the replacement " +
                    "(it is outside the monitored set, so nothing else would)",
            )
            return recreateForeignDependent(dependent, provider)
        }
        return dependent.id.takeIf {
            restartDependent(api, dependent, provider.name, what, stopTimeout(dependent.labels, config, ns))
        }
    }

    /**
     * A container this cycle restarted or replaced, seen from the point of view of whatever was wired
     * to it at create time. Targets and foreign dependents both become one, which is what lets a chain
     * of shared namespaces be followed with the same code.
     */
    private class RefreshedProvider(
        val id: String,
        val name: String,
        val liveId: String,
        val composeProject: String?,
        /** How many namespace links away from this cycle's own containers this one is. */
        val depth: Int,
    ) {
        /** Whether the container answering to [name] now is a different one than [id]. */
        val replaced: Boolean get() = liveId != id
    }

    /**
     * Recreate a dependent that is not one of this cycle's targets, so its create-time reference is
     * rebuilt against [provider]'s replacement — by *name*, the one reference that survives the change
     * of id. Its own image did not move, so it goes down exactly the same non-stale recreate path an
     * in-set netns consumer takes: same create body, same liveness gate, same rollback.
     */
    private fun recreateForeignDependent(dependent: Dependent, provider: RefreshedProvider): String? {
        val where = "[${dependent.name} (${dependent.short})]"
        val inspect = try {
            api.inspectContainer(dependent.id)
        } catch (e: Exception) {
            Log.warn(
                "$where cannot be recreated because it could not be inspected — it is left joined to a " +
                    "network namespace that no longer exists and needs a human: ${e.message}",
            )
            return null
        }
        val target = toTarget(dependent.id, inspect).also {
            it.linkedToRecreate = true
            it.networkModeContainerName = provider.name
        }
        return try {
            recreate(target)
            target.liveId
        } catch (e: Exception) {
            Log.warn("$where could not be recreated and may be left without a working network: ${e.message}")
            null
        }
    }

    /**
     * Hold [target] back until every dependency compose marked `condition: service_healthy` reports
     * healthy. Compose's own `up` does this, and a dependent started against a database that is up but
     * still replaying its log is exactly what the condition exists to prevent.
     *
     * Only dependencies *this cycle* brought back are waited for. One it did not touch has been running
     * since before the cycle: its health is not something this ordering can influence, and blocking on
     * it would stall every cycle of a stack that has one permanently unhealthy service — including the
     * updates of all the other containers.
     */
    private fun awaitHealthyDependencies(target: Target, byId: Map<String, Target>) {
        for (depId in target.healthGatedDeps) {
            val dep = byId[depId] ?: continue
            if (dep.toRestart) awaitHealthy(target, dep)
        }
    }

    /**
     * Wait for [dep] to report healthy, for at most [Config.dependencyHealthTimeout]. Every exit is a
     * *start*: the condition orders the two containers, and a dependency that never goes healthy must
     * cost its dependent a delay, not an outage — so the timeout is loud and then proceeds.
     */
    private fun awaitHealthy(target: Target, dep: Target) {
        val deadline = clock.nanos() + secondsToNanos(config.dependencyHealthTimeout)
        var announced = false
        var unreadable = 0
        while (true) {
            val state = runCatching { api.inspectContainer(dep.liveId).obj("State") }
                .onFailure {
                    // Once per wait, not once per probe: a dependency that cannot be inspected cannot
                    // be inspected 240 times either, and the log is a report, not a transcript.
                    if (unreadable++ == 0) {
                        Log.warn("[${target.name}] could not read the health of ${dep.name}: ${it.message}")
                    }
                }
                .getOrNull()
            // No `Health` at all means the image declares no healthcheck, so `healthy` is a state this
            // container can never reach: compose would have refused the stack, and waiting out the whole
            // timeout on every cycle is a self-inflicted delay with no possible payoff.
            if (state != null && state.obj("Health") == null) {
                Log.warn(
                    "[${target.name}] depends on ${dep.name} with condition $CONDITION_SERVICE_HEALTHY, but its " +
                        "image declares no healthcheck — starting without waiting",
                )
                return
            }
            val health = state?.healthStatus()
            if (health == "healthy") {
                if (announced) Log.info("[${target.name}] ${dep.name} is healthy — starting")
                return
            }
            if (clock.nanos() >= deadline) {
                Log.error(
                    "[${target.name}] ${dep.name} did not become healthy within " +
                        "${config.dependencyHealthTimeout}s (health: ${health ?: "unknown"}) — starting anyway, " +
                        "the depends_on condition can delay this container but not keep it down",
                )
                return
            }
            if (!announced) {
                Log.info("[${target.name}] waiting for ${dep.name} to become healthy (health: ${health ?: "unknown"})")
                announced = true
            }
            sleeper.sleep(PROBE_INTERVAL_MS)
        }
    }

    /**
     * Start a container this cycle stopped only because a dependency of its was updated, retrying a
     * few times before giving up. Everything this pass touches was running a moment ago, so a refused
     * `start` is far more likely to be a transient daemon state (a port the previous process has not
     * released yet, a network being rewired) than a permanent verdict.
     *
     * Giving up is loud on purpose: discovery filters `status=running`, so a container left stopped
     * here is invisible to every later cycle and would sit dead until a human noticed.
     */
    private fun startDependent(target: Target) {
        var lastError: Exception? = null
        for (attempt in 1..START_ATTEMPTS) {
            try {
                api.start(target.id)
                Log.info("[${target.name}] restarted (a dependency was updated)")
                return
            } catch (e: Exception) {
                lastError = e
                Log.warn("[${target.name}] start failed (attempt $attempt/$START_ATTEMPTS): ${e.message}")
                if (attempt < START_ATTEMPTS) sleeper.sleep(START_RETRY_INTERVAL_MS)
            }
        }
        Log.error(
            "[${target.name}] could not be started after $START_ATTEMPTS attempts — the container is LEFT " +
                "STOPPED and later cycles will not see it (discovery only lists running containers): " +
                "${lastError?.message}",
        )
    }

    // --- Discovery ------------------------------------------------------------------------

    private fun collectTargets(): List<Target> {
        val filters = linkedMapOf("status" to listOf("running"))
        if (!config.updateMonitorAll) filters["label"] = listOf("$ns.update.enable")
        val summaries = api.listContainers(all = false, filters = filters)

        val targets = ArrayList<Target>()
        for (element in summaries) {
            val summary = element.jsonObject
            val id = summary.str("Id") ?: continue
            val summaryLabels = summary.obj("Labels")
            if (isSelf(id, summaryLabels, selfId)) continue
            if (!labelTruthy(summaryLabels, "$ns.update.enable", config.updateMonitorAll)) continue
            val inspect = try {
                api.inspectContainer(id)
            } catch (e: Exception) {
                Log.error("[${id.take(12)}] inspect failed: ${e.message}")
                continue
            }
            targets += toTarget(id, inspect)
        }
        resolveLinks(targets, ns) { ref ->
            runCatching { api.inspectContainer(ref).str("Name")?.trimStart('/') }
                .onFailure { Log.warn("could not resolve network_mode container:$ref before update: ${it.message}") }
                .getOrNull()
        }
        return targets
    }

    private fun toTarget(id: String, inspect: JsonObject): Target {
        val containerConfig = inspect.obj("Config")
        val labels = containerConfig?.obj("Labels")
        return Target(
            id = id,
            name = (inspect.str("Name") ?: id).trimStart('/'),
            inspect = inspect,
            imageRef = containerConfig?.str("Image"),
            currentImageId = inspect.str("Image").orEmpty(),
            platform = inspect.imagePlatform(),
            composeLabels = labels,
            composeProject = labels.label(COMPOSE_PROJECT_LABEL),
            composeService = labels.label("com.docker.compose.service"),
        )
    }

    private fun markStale(targets: List<Target>) {
        for (target in targets) {
            val imageRef = target.imageRef
            if (imageRef == null) {
                Log.warn("[${target.name}] container has no image reference — skipping update check")
                continue
            }
            if (imageRef.contains('@')) {
                Log.info("[${target.name}] image is digest-pinned ($imageRef) — skipping update check")
                continue
            }
            try {
                val (repo, tag) = splitImageRef(imageRef)
                Log.info("[${target.name}] checking $imageRef for updates")
                target.oldImageConfig = inspectOldImageConfig(target)

                val remoteDigest = remoteDigest(imageRef, target)
                if (remoteDigest != null && hasRepoDigest(target.currentImageId, remoteDigest)) {
                    Log.info("[${target.name}] already up to date (digest ${remoteDigest.shortId()})")
                    continue
                }
                if (remoteDigest != null && hasRepoDigest(imageRef, remoteDigest)) {
                    val localImageId = api.inspectImage(imageRef).str("Id")
                    if (localImageId == null) {
                        Log.warn("[${target.name}] could not inspect local image $imageRef — falling back to pull")
                    } else if (localImageId == target.currentImageId) {
                        Log.info("[${target.name}] already up to date (digest ${remoteDigest.shortId()})")
                        continue
                    } else {
                        markUpdateAvailable(target, localImageId)
                        continue
                    }
                }

                api.pull(repo, tag, config.registryAuth, target.platform)
                val newImageId = api.inspectImage(imageRef).str("Id")
                when {
                    newImageId == null ->
                        Log.warn("[${target.name}] could not inspect pulled image $imageRef — skipping")
                    newImageId == target.currentImageId ->
                        Log.info("[${target.name}] already up to date")
                    else -> markUpdateAvailable(target, newImageId)
                }
            } catch (e: Exception) {
                Log.error("[${target.name}] update check failed: ${e.message}")
            }
        }
    }

    /**
     * Record that [target] can be updated to [newImageId] — unless that exact update is the one that
     * already took this container down and its cooldown has not run out. The availability is logged
     * either way: the update *is* available, and an operator watching for it must not have to guess
     * whether kodkod saw it or decided not to act on it.
     */
    private fun markUpdateAvailable(target: Target, newImageId: String) {
        Log.warn("[${target.name}] update available (${target.currentImageId.shortId()} -> ${newImageId.shortId()})")
        if (suppressedByCooldown(target, newImageId)) {
            target.updateSuppressed = true
            return
        }
        target.newImageId = newImageId
        target.stale = true
    }

    /**
     * Whether updating [target] to [newImageId] is being held back by a previous failed attempt. A
     * memory that no longer applies — the tag has moved on to a different image, or the cooldown has
     * run out — is forgotten here rather than merely ignored, so the next failure starts a fresh window.
     */
    private fun suppressedByCooldown(target: Target, newImageId: String): Boolean {
        val failure = failedUpdates[target.id] ?: return false
        val nextAttempt = failure.attemptedAtMillis + cooldownMs
        if (failure.imageId != newImageId || clock.nanos() - failure.attemptedAtNanos >= cooldownNanos) {
            failedUpdates.remove(target.id)
            return false
        }
        Log.warn(
            "[${target.name}] skipping this update: ${newImageId.shortId()} already failed to come up on " +
                "this container, and retrying it means stopping a healthy container for nothing — " +
                "next attempt no earlier than ${Instant.ofEpochMilli(nextAttempt)} " +
                "(KODKOD_UPDATE_FAILURE_COOLDOWN=${config.updateFailureCooldown}s). It still follows its own " +
                "dependencies this cycle: being left on a dead network namespace is the worse outcome",
        )
        return true
    }

    /**
     * Remember that the image [target] was being updated to could not be brought up, so the next cycles
     * leave the (still running, still healthy) container alone instead of taking it down again.
     *
     * Only an actual image update is remembered: a recreate that failed while following a dependency
     * has no new image to blame, and suppressing it would leave a container pinned to a dead namespace.
     * The caller decides *when* there is something to blame the image for — only a replacement that
     * was actually started can have failed because of it.
     */
    private fun rememberFailedUpdate(target: Target) {
        val imageId = target.newImageId?.takeIf { target.stale } ?: return
        if (cooldownMs <= 0) return
        val now = clock.millis()
        failedUpdates[target.id] = FailedUpdate(imageId, clock.nanos(), now)
        Log.warn(
            "[${target.name}] not trying ${imageId.shortId()} again before " +
                "${Instant.ofEpochMilli(now + cooldownMs)} — a repeat of this update is a repeat of this outage",
        )
    }

    /** The cooldown as the log lines print it. What it is *measured* with is [cooldownNanos]. */
    private val cooldownMs: Long get() = config.updateFailureCooldown * 1000L

    private val cooldownNanos: Long get() = secondsToNanos(config.updateFailureCooldown)

    private fun remoteDigest(imageRef: String, target: Target): String? {
        val digest = runCatching {
            api.inspectDistribution(imageRef, config.registryAuth).distributionDigest()
        }.getOrElse {
            Log.warn("[${target.name}] could not read registry digest for $imageRef — falling back to pull: ${it.message}")
            return null
        }

        if (digest == null) {
            Log.warn("[${target.name}] registry did not return a digest for $imageRef — falling back to pull")
        }
        return digest
    }

    private fun hasRepoDigest(imageRef: String, digest: String): Boolean =
        runCatching { api.inspectImage(imageRef).repoDigests().contains(digest) }.getOrDefault(false)

    // --- Recreate -------------------------------------------------------------------------

    private fun recreate(target: Target) {
        val name = target.name
        val imageRef = target.imageRef
        if (imageRef == null) {
            Log.warn("[$name] cannot recreate because the container has no image reference — restarting instead")
            api.start(target.id)
            return
        }
        val containerConfig = target.inspect.obj("Config") ?: EMPTY_OBJECT

        // Subtract the OLD image's defaults so the NEW image's defaults are not masked. Pulling a moved
        // tag can make the old image id disappear from the local image store, so prefer the config captured
        // before the pull. If the old defaults are already gone, fall back to subtracting keys present in
        // the newly pulled image so its env/labels/cmd defaults can still win over stale resolved config.
        val oldImageConfig = target.oldImageConfig ?: inspectOldImageConfig(target)
        val (imageConfig, subtractByKey) = if (oldImageConfig != null) {
            oldImageConfig to false
        } else {
            val newImageConfig = inspectImageConfig(imageRef)
            if (newImageConfig == null) {
                Log.warn("[$name] could not inspect old or new image defaults — keeping full config")
                null to false
            } else {
                Log.warn("[$name] could not inspect old image defaults — subtracting new image default keys")
                newImageConfig to true
            }
        }

        // The anonymous volumes the container is running with are only named in the top-level `Mounts[]`,
        // so they are re-attached explicitly; without that the replacement gets fresh empty volumes.
        val hostConfig = resolveMounts(
            target.inspect.arr("Mounts"),
            resolveHostConfig(target.inspect.obj("HostConfig"), target.networkModeContainerName),
        )
        val networks = networkEndpoints(target.inspect, hostConfig, target.id)
        val body = buildCreateBody(
            containerConfig,
            imageConfig,
            hostConfig,
            imageRef,
            target.id,
            networks.firstOrNull(),
            subtractImageDefaultsByKey = subtractByKey,
            // Only an actual image update restamps the compose label — with one exception: a
            // dependency-driven recreate creates from the image *ref* too, and a tag that moved since
            // this container was started makes the replacement run a different image after all.
            newComposeImageId = if (target.stale) target.newImageId else driftedImageId(target, imageRef),
        )
        val backupName = backupName(name, target.id)
        // A replacement we failed to delete still owns [name], which is what the rollback needs back.
        var stranded: String? = null
        // Whether the name was actually taken away from the old container yet.
        var parked = false

        // Whether the failure (if any) is evidence about the *image*. Only a replacement that was
        // actually asked to run says anything about it: a refused stop, a name conflict or a create
        // the daemon rejected happen with any image, and remembering them would freeze the update for
        // `KODKOD_UPDATE_FAILURE_COOLDOWN` over a blip that never let the image run at all.
        var imageBlamed = false

        try {
            stopGracefully(target) // usually a no-op (already stopped in the reverse-order pass)
            api.rename(target.id, backupName)
            parked = true
            val newId = api.create(name, body, target.platform)
            try {
                networks.drop(1).forEach { (net, endpoint) -> api.connectNetwork(net, newId, endpoint) }
                imageBlamed = true
                api.start(newId)
                // Everything past this point destroys the only copy of the previous state, so the
                // replacement has to prove it is actually up first.
                verifyStarted(name, newId)
            } catch (e: Exception) {
                // A gate that failed for want of an *answer* is not evidence about the image either:
                // the replacement may well have been running the whole time, and remembering it would
                // hold a good update back for `KODKOD_UPDATE_FAILURE_COOLDOWN` over a blip on the socket.
                if (e is UnverifiableReplacement) imageBlamed = false
                stranded = discardReplacement(name, newId)
                throw e
            }
            // The replacement is what serves this target from here on: a dependent waiting for it to
            // become healthy has to probe the new container, not the one about to be removed.
            target.liveId = newId
            // The image proved it can run here, so whatever this container was suppressed for is history.
            failedUpdates.remove(target.id)
            if (target.stale) {
                Log.info("[$name] update complete")
            } else {
                Log.info("[$name] recreated (a create-time dependency was updated)")
            }
            try {
                api.remove(target.id, force = true)
            } catch (e: Exception) {
                Log.warn("[$name] could not remove old container $backupName: ${e.message}")
            }
            // Only an image that was actually replaced can be pruned. A container recreated because a
            // create-time dependency moved runs the *same* image its replacement now runs, so there is
            // nothing to reclaim and asking the daemon to delete it is asking it to delete a live image.
            if (config.updateCleanup && target.stale && target.currentImageId.isNotEmpty()) {
                pruneOldImage(name, target.currentImageId, imageRef)
            }
        } catch (e: Exception) {
            // Any failure after we stopped the container must restore the original, running container.
            Log.error("[$name] recreate failed — rolling back: ${e.message}")
            if (imageBlamed) rememberFailedUpdate(target)
            if (!rollback(target.id, name, stranded, parked)) rememberStranded(target)
            throw e
        }
    }

    /**
     * Remember a create-time dependent whose rollback did not land, so [recoverStrandedDependents] keeps
     * trying to rebuild it. Only a container joined to a namespace **this cycle destroyed** is recorded:
     * [Target.networkModeContainerName] is set exactly when the create body's netns reference had to be
     * rewritten to the provider's name, which is also what says the original reference is dead. Anything
     * else that failed to roll back is still startable as it stands and stays a human's call.
     */
    private fun rememberStranded(target: Target) {
        val provider = target.networkModeContainerName ?: return
        if (netnsRef(target.inspect.obj("HostConfig")) == null) return
        strandedDependents[target.id] = StrandedDependent(target.name, provider)
        Log.error(
            "[${target.name}] cannot be put back as it was — it is joined to the network namespace of a " +
                "container this cycle replaced, which no `start` can bring back. kodkod will rebuild it " +
                "against $provider on every cycle until it is serving again",
        )
    }

    /**
     * The id [imageRef] resolves to right now, when that is **not** the image [target] is running —
     * that is, when a recreate nobody meant as an update will change the image anyway. `null` in the
     * ordinary case (the tag still names the running image) and whenever the image cannot be read:
     * both mean there is nothing to restamp.
     *
     * The replacement is created from the ref, never from the id: `Config.Image` is what the *next*
     * cycle reads back as this container's image, and an id there has no tag to follow, which would
     * silently pin the container out of every future update. So the drift is reported and recorded in
     * `com.docker.compose.image` — a label left naming an image the container no longer runs is what
     * makes the next `docker compose up` recreate it.
     */
    private fun driftedImageId(target: Target, imageRef: String): String? {
        val resolved = runCatching { api.inspectImage(imageRef).str("Id") }.getOrNull() ?: return null
        if (resolved.isEmpty() || resolved == target.currentImageId) return null
        Log.warn(
            "[${target.name}] is being recreated because a dependency changed, and $imageRef has moved to " +
                "${resolved.shortId()} since this container was started — the replacement runs that image, " +
                "not ${target.currentImageId.shortId()}",
        )
        return resolved
    }

    /**
     * Delete the replacement that just failed, returning its id when it is *still there* — a corpse we
     * could not remove keeps holding the service name and would make the rollback's rename fail with a
     * 409, so the rollback has to be told about it rather than discovering a name conflict blind.
     */
    private fun discardReplacement(name: String, newId: String): String? =
        try {
            api.remove(newId, force = true)
            null
        } catch (e: Exception) {
            Log.error(
                "[$name] could not remove the failed replacement ${newId.take(12)} — " +
                    "it still holds the name '$name': ${e.message}",
            )
            newId
        }

    /**
     * Put the original container back the way it was: under [name] and running. [parked] says whether
     * the rename to the backup name actually happened — a recreate that failed before it still owns
     * its name and must not be renamed onto itself. [blockingId] is a
     * replacement that is known to still hold [name] (see [discardReplacement]) and is cleared out of
     * the way before the rename is retried — without that the service would keep running under its
     * `_kodkod_old_` backup name while a dead container owns the real one.
     *
     * Every step logs its own failure instead of being swallowed, and the end state is then verified
     * against the daemon: this is the last line of defence for a service kodkod itself stopped, so
     * "the rollback ran" and "the service is back" must not be the same claim.
     *
     * The interrupt flag is cleared for the duration and handed back afterwards. A shutdown interrupts
     * the worker thread, and a NIO channel refuses every operation while the calling thread is
     * interrupted — so the recreate this rollback is cleaning up after is very often one that a
     * shutdown just killed, and running with the flag set would mean every call here fails instantly
     * and the service stays down under its backup name with nothing but log lines to show for it.
     *
     * @return whether the service really is being served again by [oldId] — the caller has a last resort
     * for the cases where it is not (see [rememberStranded]), and must not be handed the mere fact that
     * the rollback ran.
     */
    private fun rollback(oldId: String, name: String, blockingId: String?, parked: Boolean): Boolean {
        val interrupted = Thread.interrupted()
        if (interrupted) {
            Log.warn("[$name] rolling back on an interrupted thread — finishing the rollback before stopping")
        }
        try {
            // A recreate that failed before the rename never lost the name, and the daemon refuses to
            // rename a container to the name it already has ("Renaming a container with the same name
            // as its current name"). Asking anyway produced two ERRORs about a container that is fine.
            if (parked) restoreName(oldId, name, blockingId)
            try {
                api.start(oldId)
            } catch (e: Exception) {
                Log.error("[$name] rollback: could not start the previous container ${oldId.take(12)}: ${e.message}")
            }
            return verifyRolledBack(oldId, name)
        } finally {
            // The interrupt belongs to whoever asked for the shutdown; swallowing it would keep the
            // cycle (and the JVM) running past the point it was told to stop.
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun restoreName(oldId: String, name: String, blockingId: String?) {
        if (renameBack(oldId, name)) return
        if (blockingId == null) {
            Log.error("[$name] rollback: the name is taken and kodkod did not create its holder — leaving it alone")
            return
        }
        if (freeName(name, blockingId)) renameBack(oldId, name)
    }

    /** Shared by the in-process rollback and the reconcile pass, hence the caller-neutral log line. */
    private fun renameBack(oldId: String, name: String): Boolean =
        try {
            api.rename(oldId, name)
            true
        } catch (e: Exception) {
            Log.error("[$name] could not rename ${oldId.take(12)} back to '$name': ${e.message}")
            false
        }

    /**
     * Take [name] away from [blockingId] — by deleting it, or, if the daemon will not delete it either,
     * by parking it under a name of its own so the container that should be serving can have its name
     * back. Used both by the in-process rollback and by the reconcile pass, which finds the same shape
     * of obstacle (a replacement that is not running yet owns the service name).
     */
    private fun freeName(name: String, blockingId: String): Boolean {
        try {
            api.remove(blockingId, force = true)
            return true
        } catch (e: Exception) {
            Log.error("[$name] could not remove ${blockingId.take(12)}, which holds the name: ${e.message}")
        }
        // A blocker the daemon refused to delete may well still be *running* — a replacement that failed
        // the liveness gate is stopped by nothing else — and a running container keeps this service's
        // published ports, its network aliases and its volumes. Renaming it away would hand back the name
        // and nothing else: the `start` that follows then fails on a port conflict and the rollback ends
        // in ROLLBACK INCOMPLETE. No override is applied, so the container's own `StopTimeout` decides.
        try {
            api.stop(blockingId, config.defaultStopTimeout)
        } catch (e: Exception) {
            Log.warn(
                "[$name] could not stop ${blockingId.take(12)} before moving it off the name — it may keep " +
                    "this service's ports: ${e.message}",
            )
        }
        val parkedName = "${name}_kodkod_failed_${blockingId.take(12)}"
        return try {
            api.rename(blockingId, parkedName)
            Log.warn("[$name] parked the undeletable replacement as $parkedName")
            true
        } catch (e: Exception) {
            Log.error("[$name] could not move ${blockingId.take(12)} off the name '$name': ${e.message}")
            false
        }
    }

    /** Ask the daemon what actually happened, so a rollback that did not land is reported as such. */
    private fun verifyRolledBack(oldId: String, name: String): Boolean {
        val inspect = try {
            api.inspectContainer(oldId)
        } catch (e: Exception) {
            Log.error("[$name] rollback could not be verified — inspect of ${oldId.take(12)} failed: ${e.message}")
            return false
        }
        val actualName = inspect.str("Name")?.trimStart('/')
        val running = inspect.obj("State")?.str("Running") == "true"
        if (actualName == name && running) {
            Log.info("[$name] rolled back to the previous container")
            return true
        }
        Log.error(
            "[$name] ROLLBACK INCOMPLETE — the previous container ${oldId.take(12)} is " +
                "${if (running) "running" else "stopped"} under the name '${actualName ?: "?"}': " +
                "nothing is serving as '$name' and it needs a human",
        )
        return false
    }

    /**
     * Watch the freshly started replacement [newId] for up to [Config.updateVerifySeconds] and throw
     * unless it stays up. A `204` from `POST /start` only means the process was launched: an image
     * missing a dependency, or one whose new config is wrong, exits a moment later. Destroying the old
     * container and image on the strength of that `204` turns a bad image into an outage, so the gate
     * runs before either of them is touched and a failure goes down the ordinary rollback path.
     *
     * [REQUIRED_GOOD_PROBES] consecutive good probes end the wait early — the happy path must not pay
     * for the whole window. `Health=starting` is *not* good enough to exit early but is never a
     * failure either: it is the image author's own `start_period` talking, and a container still
     * inside it is accepted once the window runs out. Treating it as a failure would roll back healthy
     * updates of every slow-starting service.
     *
     * A probe that could not be *read* is not a verdict — a blip on the socket says nothing about the
     * container — but neither is it evidence. A window in which no probe ever came back therefore
     * fails the gate: what follows destroys the only copy of the previous state, and "we could not
     * look" must never be spent as "it is fine". A `404` is the one error that *is* an answer, and it
     * is the worst one: the replacement is gone (an `AutoRemove` inherited from the old container, an
     * outside `docker rm`), which without this reads as "still starting" all the way to the removal of
     * the container it replaced.
     */
    private fun verifyStarted(name: String, newId: String) {
        val deadline = clock.nanos() + secondsToNanos(config.updateVerifySeconds)
        var good = 0
        var readable = 0
        var unreadable = 0
        while (true) {
            val state = try {
                api.inspectContainer(newId).obj("State")
            } catch (e: Exception) {
                if (e is DockerException && e.status == 404) {
                    error("the replacement did not stay up: the daemon does not know ${newId.take(12)} any more")
                }
                // Only the first failure is logged: an unreachable daemon answers the same way on every
                // probe, and ~30 identical WARN lines per container per cycle is noise, not a report.
                if (unreadable == 0) Log.warn("[$name] could not probe the replacement: ${e.message}")
                unreadable++
                null
            }
            if (state != null) readable++
            livenessFailure(state)?.let { error("the replacement did not stay up: $it") }
            val settled = state != null && !(config.updateVerifyHealth && state.healthStatus() == "starting")
            if (settled && ++good >= REQUIRED_GOOD_PROBES) {
                if (unreadable > 0) Log.warn("[$name] $unreadable liveness probe(s) could not be read")
                Log.info("[$name] replacement is up ($good consecutive probes)")
                return
            }
            if (!settled) good = 0
            if (clock.nanos() >= deadline) {
                if (readable == 0) {
                    throw UnverifiableReplacement(
                        "the replacement could not be inspected once in ${config.updateVerifySeconds}s " +
                            "($unreadable unreadable probe(s)) — nothing says it is running, and the container " +
                            "it replaced is the only way back",
                    )
                }
                if (unreadable > 0) Log.warn("[$name] $unreadable of ${readable + unreadable} liveness probes could not be read")
                val why = if (settled) "up but only $good/$REQUIRED_GOOD_PROBES probes in" else "still starting up"
                Log.info("[$name] replacement is $why after ${config.updateVerifySeconds}s — accepting it")
                return
            }
            sleeper.sleep(PROBE_INTERVAL_MS)
        }
    }

    /** Why this probe says the replacement is not alive, or `null` when it looks fine (or unreadable). */
    private fun livenessFailure(state: JsonObject?): String? {
        if (state == null) return null
        // Checked before `Running`, which a container between restart-policy attempts also reports false.
        if (state.str("Restarting") == "true") return "it is restarting (a crash loop)"
        if (state.str("Running") == "false") return "it exited with code ${state.str("ExitCode") ?: "?"}"
        if (config.updateVerifyHealth && state.healthStatus() == "unhealthy") return "its healthcheck reports unhealthy"
        return null
    }

    /**
     * Resolve a `network_mode: container:<id>` to the referenced container's **name**, captured before
     * any dependency is renamed/removed. This lets the recreated container join the replacement provider's
     * network namespace. Other host configs pass through untouched.
     */
    private fun resolveHostConfig(hostConfig: JsonObject?, resolvedContainerName: String?): JsonObject? {
        if (hostConfig == null) return null
        val ref = netnsRef(hostConfig) ?: return hostConfig
        val resolvedName = resolvedContainerName ?: return hostConfig
        Log.info("resolved network_mode $NETNS_PREFIX$ref -> $NETNS_PREFIX$resolvedName")
        return buildJsonObject {
            hostConfig.forEach { (key, value) -> if (key != "NetworkMode") put(key, value) }
            put("NetworkMode", "$NETNS_PREFIX$resolvedName")
        }
    }

    /**
     * The networks (with create-relevant endpoint fields) to attach the replacement to, in order. Empty
     * for host/none/container network modes, where `HostConfig.NetworkMode` is authoritative. The first
     * entry goes into the create body; the rest are connected afterwards (Docker rejects multiple
     * endpoints at create time — docker/docker#29265).
     */
    private fun networkEndpoints(inspect: JsonObject, hostConfig: JsonObject?, oldId: String): List<Pair<String, JsonObject>> {
        val networks = inspect.obj("NetworkSettings")?.obj("Networks") ?: return emptyList()
        val mode = hostConfig?.str("NetworkMode").orEmpty()
        if (networks.isEmpty() || mode == "host" || mode == "none" || mode.startsWith("container:")) return emptyList()
        // `--mac-address` / compose `mac_address:` is what fills `Config.MacAddress`, and only such an
        // explicitly requested MAC may be carried over. `NetworkSettings.Networks[*].MacAddress` is always
        // populated — in Docker >= 26 with a randomly generated address — so it cannot tell the two apart on
        // its own, and pinning a generated MAC onto the replacement would invent configuration.
        val keepMac = !inspect.obj("Config")?.str("MacAddress").isNullOrEmpty()
        return networks.map { (netName, endpoint) -> netName to cleanEndpoint(endpoint.jsonObject, oldId, keepMac) }
    }

    /** Keep only the create-relevant endpoint fields and drop the auto-generated container-id alias. */
    private fun cleanEndpoint(endpoint: JsonObject, oldId: String, keepMacAddress: Boolean): JsonObject {
        val short = oldId.take(12)
        return buildJsonObject {
            endpoint.arr("Aliases")?.let { aliases ->
                val kept = aliases.filter {
                    val alias = it.jsonPrimitive.content
                    alias != short && alias != oldId
                }
                if (kept.isNotEmpty()) put("Aliases", JsonArray(kept))
            }
            endpoint.obj("IPAMConfig")?.let { put("IPAMConfig", it) }
            endpoint.arr("Links")?.let { put("Links", it) }
            endpoint.obj("DriverOpts")?.let { put("DriverOpts", it) }
            // Which network provides the default route; losing it silently re-routes egress traffic.
            endpoint["GwPriority"]?.let { put("GwPriority", it) }
            if (keepMacAddress) endpoint.str("MacAddress")?.takeIf { it.isNotEmpty() }?.let { put("MacAddress", it) }
        }
    }

    /**
     * Stop [target] with kodkod's override if there is one, and otherwise with no `?t=` at all so the
     * daemon honours the container's own `Config.StopTimeout` — which we still read out of the inspect
     * we already hold, to size the read timeout for the wait we are actually signing up for.
     */
    private fun stopGracefully(target: Target) =
        api.stop(target.id, stopTimeout(target), expectedStopSeconds = effectiveStopTimeout(target))

    /** The explicit override: per-container label first, then `KODKOD_STOP_TIMEOUT`; `null` = none. */
    private fun stopTimeout(target: Target): Int? = stopTimeout(target.composeLabels, config, ns)

    /** How long the graceful stop will really take: the override, else the container's own timeout. */
    private fun effectiveStopTimeout(target: Target): Int? =
        stopTimeout(target) ?: target.inspect.obj("Config")?.str("StopTimeout")?.toIntOrNull()

    /**
     * Delete the image [imageId] the container was running — but only once we know nobody else names
     * it. `DELETE /images/<id>` is refused with 409 while **two or more** tags point at the image, so
     * the case that needs guarding is exactly **one** tag left over: `app:1.26` still on the old image
     * after `app:latest` moved to the new one. That delete succeeds and silently takes the operator's
     * pinned rollback tag with it, which is why the tags are read first and any tag other than the ref
     * we just updated ([updatedRef]) cancels the prune.
     *
     * The comparison goes through [canonicalImageRef] because the two sides are spelled differently:
     * `RepoTags` is what the daemon normalised the tag to (`nginx:1.27`), while [updatedRef] is
     * whatever the container's `Config.Image` says, which may well be `docker.io/library/nginx:1.27`.
     * Compared raw, the only tag on the image never matches the ref it belongs to, every prune stands
     * down, and `KODKOD_UPDATE_CLEANUP=true` quietly does nothing at all.
     *
     * An image we cannot inspect is left in place: a stale image costs disk, an untagged one costs a
     * rollback path.
     */
    private fun pruneOldImage(name: String, imageId: String, updatedRef: String) {
        val tags = try {
            api.inspectImage(imageId).repoTags()
        } catch (e: Exception) {
            Log.warn("[$name] could not inspect old image ${imageId.shortId()} — keeping it: ${e.message}")
            return
        }
        val updated = canonicalImageRef(updatedRef)
        val foreignTags = tags.filterNot { canonicalImageRef(it) == updated }
        if (foreignTags.isNotEmpty()) {
            Log.info("[$name] keeping old image ${imageId.shortId()} — still tagged ${foreignTags.joinToString(", ")}")
            return
        }
        try {
            api.removeImage(imageId)
        } catch (e: Exception) {
            Log.warn("[$name] could not remove old image ${imageId.shortId()}: ${e.message}")
        }
    }

    private fun inspectOldImageConfig(target: Target): JsonObject? =
        if (target.currentImageId.isEmpty()) {
            null
        } else {
            inspectImageConfig(target.currentImageId)
        }

    private fun inspectImageConfig(ref: String): JsonObject? =
        runCatching { api.inspectImage(ref).obj("Config") }.getOrNull()

    private companion object {
        /** Gap between liveness probes; the daemon's own state transitions are far coarser than this. */
        const val PROBE_INTERVAL_MS = 500L

        /** Consecutive good probes that end the liveness wait early. */
        const val REQUIRED_GOOD_PROBES = 3

        /** Attempts at starting a container this cycle stopped for a dependency of its own. */
        const val START_ATTEMPTS = 3

        /** Pause between those attempts — long enough for a port or a network to be released. */
        const val START_RETRY_INTERVAL_MS = 1_000L

        /**
         * How far a chain of shared namespaces is followed away from the containers this cycle brought
         * back — `c` joined to `b` joined to `a` being two links. Each link costs a listing, and a chain
         * deeper than this is not a shape kodkod should keep paying to walk.
         */
        const val MAX_DEPENDENT_CHAIN = 32

        /**
         * Uptime below which a stopped container that holds a service name proved nothing, whatever
         * `KODKOD_UPDATE_VERIFY_SECONDS` was set to (it may be `0`). See [holderEverStayedUp].
         */
        const val MIN_PROVEN_UPTIME_MS = 60_000L
    }
}

/**
 * The liveness gate ending without a single readable probe. It fails the update like any other gate
 * failure — "we could not look" must never be spent as "it is fine" — but it is the one failure that
 * says nothing about the *image*, and is therefore a type rather than a message: unlike a replacement
 * seen exiting or reporting `unhealthy`, an unanswered window is just as likely to be a daemon that
 * stopped answering while the replacement ran perfectly well.
 */
private class UnverifiableReplacement(message: String) : Exception(message)

/** Infix of the name kodkod parks a container under while its replacement takes over the real one. */
internal const val BACKUP_MARKER = "_kodkod_old_"

/** The name [id] is parked under while the replacement for [name] is created and proves itself. */
internal fun backupName(name: String, id: String): String = "$name$BACKUP_MARKER${id.take(12)}"

/**
 * The service name [backup] is kodkod's backup *of*, or `null` when this name is not one.
 *
 * The discriminator is deliberately strict: the suffix has to carry the container's **own** short id,
 * so a container an operator happened to call `web_kodkod_old_something` — or a backup of a *different*
 * container that ended up with a similar name — is never renamed or deleted by the reconcile pass.
 */
internal fun canonicalNameOfBackup(backup: String, id: String): String? =
    backup.removeSuffix(backupName("", id)).takeIf { it != backup && it.isNotEmpty() }

/** `State.Health.Status` — absent for a container whose image declares no healthcheck. */
private fun JsonObject.healthStatus(): String? = obj("Health")?.str("Status")


/**
 * What one update cycle intends to do, as decided by [Updater.plan] from reads alone and carried out by
 * [Updater.apply]. [targets] is the whole monitored set in dependency order (dependencies first),
 * carrying the per-container verdict — the containers that are not being touched are part of the plan
 * too, since they are what the graph is resolved against.
 */
internal class UpdatePlan(val targets: List<Target>) {
    /** The containers this plan will actually stop and bring back, in the same order. */
    val work: List<Target> = targets.filter { it.toRestart }

    val hasWork: Boolean get() = work.isNotEmpty()

    companion object {
        /** Nothing to do — no monitored containers, or every one of them already up to date. */
        val NOTHING = UpdatePlan(emptyList())
    }
}

/** One container kodkod is considering for an update, plus the verdict it accumulates this cycle. */
internal class Target(
    val id: String,
    val name: String,
    /**
     * The container as the daemon last described it — and what the replacement is built from. Refreshed
     * at the start of the mutating phase, since minutes of image download can sit between the two.
     */
    var inspect: JsonObject,
    val imageRef: String?,
    val currentImageId: String,
    /** `os/arch` this container's image was resolved for, or null when the engine does not report it. */
    val platform: String?,
    val composeLabels: JsonObject?,
    val composeProject: String?,
    val composeService: String?,
) {
    /** This container's own image changed and it should be recreated. */
    var stale: Boolean = false

    /**
     * An update *is* available for this container but kodkod is holding it back — the same image
     * already failed to come up here and its cooldown has not run out (see `suppressedByCooldown`).
     *
     * It suppresses the update and only the update: the container still follows its dependencies
     * ([propagateLinkedRestart]), because a create-time dependent left joined to a namespace that was
     * destroyed this cycle is `Running` with no interfaces — which no gate catches, no rollback undoes
     * and no log line reports.
     *
     * That is not free, and the cost is paid on the other side of the edge: a dependency-driven recreate
     * builds the replacement from the image **ref**, which by now resolves to exactly the image already
     * known to fail here, so the recreate is not merely risky but certain to fail — and its rollback
     * cannot work, since the provider that was replaced took the namespace this container's original
     * would have to rejoin. So the *provider* is the one that gives way: `holdBackUnsafeProviders` keeps
     * it out of the cycle for as long as this flag is set, and nothing is forced anywhere.
     */
    var updateSuppressed: Boolean = false

    /** A container this one depends on is being restarted, so this one must restart too. */
    var linkedToRestarting: Boolean = false

    /** A create-time dependency changed, so this container must be recreated rather than merely started. */
    var linkedToRecreate: Boolean = false

    /**
     * This container is out of the cycle no matter what else it says — a create-time dependent of it
     * could not be put back if the recreate this restart forces were to fail (see
     * `holdBackUnsafeProviders`). It wins over [stale] and over both propagated flags, and is the only
     * thing that does: a container held back is one kodkod deliberately does not touch this cycle.
     */
    var restartHeldBack: Boolean = false

    /** Ids (within this cycle's set) of the containers this one depends on. */
    var deps: Set<String> = emptySet()

    /** Dependency ids that are baked into create-time config (`--link` or `network_mode: container:`). */
    var createTimeDeps: Set<String> = emptySet()

    /** Dependency ids compose marked `condition: service_healthy` — awaited before this one starts. */
    var healthGatedDeps: Set<String> = emptySet()

    /**
     * Dependency ids whose compose edge says `restart: false`. Only consulted when
     * [Config.respectDependsOnRestart] is on, and even then only for a plain restart.
     */
    var noRestartDeps: Set<String> = emptySet()

    /**
     * The container id serving this target right now: its own until a recreate replaced it, the
     * replacement's after that. Anything looking at a *dependency* mid-cycle (the `service_healthy`
     * wait) has to read the container that is actually running, not the one that was removed.
     */
    var liveId: String = id

    /** Name captured before updates for `HostConfig.NetworkMode=container:<id|name>`. */
    var networkModeContainerName: String? = null

    /** The running image's defaults, captured before pulling so a moved tag cannot erase them. */
    var oldImageConfig: JsonObject? = null

    /**
     * Local image id the tag resolves to now — set exactly when [stale] is set, since a target that
     * could not be resolved to a new id is never marked stale. Restamps `com.docker.compose.image`.
     */
    var newImageId: String? = null

    val toRecreate: Boolean get() = !restartHeldBack && (stale || linkedToRecreate)

    val toRestart: Boolean get() = !restartHeldBack && (stale || linkedToRecreate || linkedToRestarting)

    /** How this container is wired to [providerName] at create time, as a log line says it. */
    fun createTimeRelationTo(providerName: String): String {
        val kind = if (netnsRef(inspect.obj("HostConfig")) != null) DependencyKind.NETNS else DependencyKind.LINK
        return kind.relationTo(providerName)
    }
}

/**
 * Fill in each target's [Target.deps] (ids within the cycle's set), preferring Compose's own
 * `com.docker.compose.depends_on` metadata and falling back to the `<ns>.depends-on` label, legacy
 * `HostConfig.Links`, and `network_mode: container:`.
 */
internal fun resolveLinks(
    targets: List<Target>,
    ns: String,
    externalContainerName: (String) -> String? = { null },
) {
    val byName = HashMap<String, String>()
    val byService = HashMap<String, String>()
    val byId = targets.associateBy { it.id }
    for (target in targets) {
        byName[target.name] = target.id
        if (target.composeProject != null && target.composeService != null) {
            byService[serviceKey(target.composeProject, target.composeService)] = target.id
        }
    }

    for (target in targets) {
        val deps = LinkedHashSet<String>()
        val createTimeDeps = LinkedHashSet<String>()
        val healthGatedDeps = LinkedHashSet<String>()
        val noRestartDeps = LinkedHashSet<String>()
        fun addDep(depId: String?, createTime: Boolean = false) {
            if (depId != null && depId != target.id) deps += depId
            if (createTime && depId != null && depId != target.id) createTimeDeps += depId
        }

        // Compose metadata: "db:service_started:false" — all three fields carry meaning.
        for (edge in parseDependsOn(target.composeLabels.label("com.docker.compose.depends_on"))) {
            val project = target.composeProject ?: continue
            val depId = byService[serviceKey(project, edge.service)] ?: continue
            if (depId == target.id) continue
            addDep(depId)
            if (edge.condition == CONDITION_SERVICE_HEALTHY) healthGatedDeps += depId
            if (edge.restart == false) noRestartDeps += depId
        }
        // Explicit kodkod label for non-compose users: container names (or service names).
        target.composeLabels.label("$ns.depends-on")?.splitToSequence(',')?.forEach { token ->
            val name = token.trim()
            if (name.isNotEmpty()) addDep(byName[name] ?: target.composeProject?.let { byService[serviceKey(it, name)] })
        }
        // Legacy --link: "/source:/container/alias".
        target.inspect.obj("HostConfig")?.arr("Links")?.forEach { link ->
            val source = linkSource(link.jsonPrimitive.contentOrNull ?: return@forEach)
            addDep(byName[source], createTime = true)
        }
        // network_mode: container:<id|name>.
        val ref = netnsRef(target.inspect.obj("HostConfig"))
        if (ref != null) {
            val depId = byName[ref] ?: targets.firstOrNull { it.id.startsWith(ref) }?.id
            addDep(depId, createTime = true)
            target.networkModeContainerName = depId?.let { byId[it]?.name } ?: externalContainerName(ref)
        }
        target.deps = deps
        target.createTimeDeps = createTimeDeps
        target.healthGatedDeps = healthGatedDeps
        target.noRestartDeps = noRestartDeps
    }
}

/** One parsed `com.docker.compose.depends_on` entry. */
internal class DependsOnEdge(
    val service: String,
    /** `service_started` (compose's default and what a field-less entry means), `service_healthy`, … */
    val condition: String,
    /** compose's `restart` field, or `null` when the label does not carry one. */
    val restart: Boolean?,
)

/**
 * Parse a `com.docker.compose.depends_on` label: a comma-separated list of
 * `<service>[:<condition>[:<restart>]]`. Older compose versions emit fewer fields, so a missing
 * condition reads as `service_started` and a missing (or unparsable) `restart` stays `null` —
 * "compose said nothing", which is not the same as the explicit `false` that may suppress a restart.
 */
internal fun parseDependsOn(label: String?): List<DependsOnEdge> =
    label?.split(',')?.mapNotNull { entry ->
        val fields = entry.split(':').map { it.trim() }
        fields[0].takeIf { it.isNotEmpty() }?.let { service ->
            DependsOnEdge(
                service = service,
                condition = fields.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: CONDITION_SERVICE_STARTED,
                restart = fields.getOrNull(2)?.lowercase()?.toBooleanStrictOrNull(),
            )
        }
    } ?: emptyList()

/**
 * Mark every container that depends (transitively) on a restarting container as restarting too. A
 * fixpoint loop so chains `c -> b -> a` propagate fully (watchtower's `UpdateImplicitRestart` is a
 * single pass).
 *
 * With [respectDependsOnRestart] on, an edge compose marked `restart: false` no longer propagates a
 * plain restart (see [Config.respectDependsOnRestart]). It can never stop a *recreate*: create-time
 * dependents are decided from [Target.createTimeDeps], which this subtraction does not touch, so a
 * netns consumer is never left pointing at a namespace that no longer exists.
 *
 * A container whose own update is being held back ([Target.updateSuppressed]) takes part like any
 * other: the cooldown suppresses that container's **image update**, and nothing else. Leaving it out
 * of the graph would leave a create-time dependent of a container replaced this cycle pointing at a
 * network namespace that no longer exists — `Running` with no interfaces, the one failure nothing in
 * the system reports — and would hide it behind a log line about an update cooldown. What keeps that
 * from forcing it onto the very image that failed is that the *provider* is taken out of the cycle
 * instead (`holdBackUnsafeProviders`), which is why this pass is run again after that decision.
 *
 * [Target.restartHeldBack] is the one verdict this pass does not overrule: a container held back is
 * one that must not move at all this cycle, and it neither takes a flag nor hands one on (the flags
 * are read through [Target.toRestart], which is false for it).
 */
internal fun propagateLinkedRestart(targets: List<Target>, respectDependsOnRestart: Boolean = false) {
    val byId = targets.associateBy { it.id }
    var changed = true
    while (changed) {
        changed = false
        for (target in targets) {
            if (target.restartHeldBack) continue
            val restartDeps = if (respectDependsOnRestart) target.deps - target.noRestartDeps else target.deps
            if (!target.toRecreate && target.createTimeDeps.any { byId[it]?.toRestart == true }) {
                target.linkedToRecreate = true
                changed = true
            }
            if (!target.toRestart && restartDeps.any { byId[it]?.toRestart == true }) {
                target.linkedToRestarting = true
                changed = true
            }
        }
    }
}

/** Topological order, dependencies first. On a cycle, logs and falls back to best-effort order. */
internal fun topoSort(targets: List<Target>): List<Target> {
    val byId = targets.associateBy { it.id }
    val visited = HashSet<String>()
    val visiting = HashSet<String>()
    val result = ArrayList<Target>(targets.size)

    fun visit(target: Target) {
        if (target.id in visited) return
        if (!visiting.add(target.id)) {
            Log.error("[${target.name}] dependency cycle detected — updating without ordering guarantees")
            return
        }
        for (depId in target.deps) byId[depId]?.let(::visit)
        visiting.remove(target.id)
        visited += target.id
        result += target
    }

    for (target in targets) visit(target)
    return result
}

/** `repo:tag`, with the implicit `:latest` spelled out so a ref compares equal to a `RepoTags` entry. */
internal fun normalizeImageRef(ref: String): String = splitImageRef(ref).let { (repo, tag) -> "$repo:$tag" }

/**
 * [normalizeImageRef] plus the implicit Docker Hub prefixes spelled *out*: `docker.io/library/nginx`,
 * `library/nginx` and `nginx` all name the same image, but only the last is what the daemon puts in
 * `RepoTags`. Any other registry is left exactly as it is — `myreg:5000/library/app` is a different
 * image from `library/app`.
 */
internal fun canonicalImageRef(ref: String): String {
    val normalized = normalizeImageRef(ref)
    val withoutRegistry = HUB_PREFIXES.firstOrNull(normalized::startsWith)
        ?.let { normalized.removePrefix(it) }
        ?: normalized
    // `library/` is Docker Hub's namespace for official images, and only theirs.
    return if (withoutRegistry.count { it == '/' } == 1) withoutRegistry.removePrefix("library/") else withoutRegistry
}

/** How a Docker Hub ref may spell the registry that is otherwise implicit. */
private val HUB_PREFIXES = listOf("docker.io/", "index.docker.io/", "registry-1.docker.io/")

/** Split `registry:5000/repo:tag` into (`registry:5000/repo`, `tag`), defaulting the tag to `latest`. */
internal fun splitImageRef(ref: String): Pair<String, String> {
    val lastSlash = ref.lastIndexOf('/')
    val lastColon = ref.lastIndexOf(':')
    return if (lastColon > lastSlash) {
        ref.substring(0, lastColon) to ref.substring(lastColon + 1)
    } else {
        ref to "latest"
    }
}

/**
 * `os/arch` of the image manifest this container actually runs, read from the inspect payload's
 * `ImageManifestDescriptor.platform`. Null on engines that do not report the descriptor — then the
 * daemon keeps choosing its own default, exactly as it did before.
 *
 * `variant` is deliberately **not** included: it describes the specific manifest of the *old* image
 * (in the recorded corpus one image reports `arm64`/`v8` and another plain `arm64` on the same host),
 * so pinning it would risk a "no matching manifest" failure against the new image.
 */
internal fun JsonObject.imagePlatform(): String? {
    val platform = obj("ImageManifestDescriptor")?.obj("platform") ?: return null
    val os = platform.str("os")?.takeIf { it.isNotBlank() } ?: return null
    val architecture = platform.str("architecture")?.takeIf { it.isNotBlank() } ?: return null
    return "$os/$architecture"
}

internal fun JsonObject.distributionDigest(): String? =
    obj("Descriptor")?.str("digest")?.takeIf { it.isNotBlank() }

internal fun JsonObject.repoDigests(): Set<String> =
    arr("RepoDigests")
        ?.mapNotNull { ref ->
            ref.jsonPrimitive.contentOrNull
                ?.substringAfter('@', missingDelimiterValue = "")
                ?.takeIf { it.isNotBlank() }
        }
        ?.toSet()
        ?: emptySet()

/**
 * `RepoTags` of an image inspect, without the `<none>:<none>` placeholder some engines emit for an
 * untagged image — that entry is the *absence* of a tag and must not read as somebody's reference.
 */
internal fun JsonObject.repoTags(): Set<String> =
    arr("RepoTags")
        ?.mapNotNull { tag -> tag.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() && it != NO_TAG } }
        ?.toSet()
        ?: emptySet()

/** `docker images` shows an untagged image this way, and some engines put it in `RepoTags` too. */
private const val NO_TAG = "<none>:<none>"

/** compose's default `depends_on` condition: the dependency merely has to have been started. */
private const val CONDITION_SERVICE_STARTED = "service_started"

/** compose's `depends_on` condition that asks for a dependency whose healthcheck actually passes. */
private const val CONDITION_SERVICE_HEALTHY = "service_healthy"

private fun serviceKey(project: String, service: String) = project + '\u0000' + service

private fun String.shortId(): String =
    removePrefix("sha256:").take(12).ifEmpty { "<none>" }
