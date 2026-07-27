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
 * still be restarted or recreated as a dependent.
 *
 * A replacement container is watched for a short window after `start` (see `verifyStarted`) and the
 * container and image it replaced are destroyed only once it has proven it stays up. An update that
 * failed that gate is remembered for `KODKOD_UPDATE_FAILURE_COOLDOWN` so a broken `:latest` costs one
 * interrupted service instead of one per cycle.
 *
 * [clock] and [sleeper] default to the real ones and exist so waiting logic can be driven from tests
 * without spending the wall-clock time it describes.
 */
class Updater(
    private val api: DockerClient,
    private val config: Config,
    private val selfId: String?,
    private val clock: TimeSource = TimeSource.SYSTEM,
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

    /** An image that failed to come up on a container, and when that was last attempted. */
    private class FailedUpdate(val imageId: String, val attemptedAt: Long)

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
     * [name] is what tells the two possible histories apart: a replacement that is up means the
     * recreate got all the way through and only the cleanup was cut short, so the backup is garbage;
     * anything else means the backup is still the only copy of a working service.
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
        if ((holder.str("State") ?: "running") == "running") {
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
        Log.error(
            "[$name] '$backupName' is a leftover backup and the container holding '$name' " +
                "(${holderId.take(12)}) is not running — putting the backup back in its place",
        )
        if (holderId.isNotEmpty() && freeName(name, holderId)) restoreBackup(id, name)
    }

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

    fun runOnce() {
        reconcileOrphanedBackups()
        val targets = collectTargets()
        if (targets.isEmpty()) return

        markStale(targets)
        propagateLinkedRestart(targets)
        if (targets.none { it.toRestart }) {
            Log.info("update: all monitored containers are up to date")
            return
        }

        val ordered = topoSort(targets)

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
        for (target in ordered) {
            if (!target.toRestart) continue
            try {
                if (target.toRecreate) recreate(target) else startDependent(target)
            } catch (e: Exception) {
                Log.error("[${target.name}] ${if (target.toRecreate) "recreate" else "restart"} failed: ${e.message}")
            }
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
        if (suppressedByCooldown(target, newImageId)) return
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
        val nextAttempt = failure.attemptedAt + cooldownMs
        if (failure.imageId != newImageId || clock.millis() >= nextAttempt) {
            failedUpdates.remove(target.id)
            return false
        }
        Log.warn(
            "[${target.name}] skipping this update: ${newImageId.shortId()} already failed to come up on " +
                "this container, and retrying it means stopping a healthy container for nothing — " +
                "next attempt no earlier than ${Instant.ofEpochMilli(nextAttempt)} " +
                "(KODKOD_UPDATE_FAILURE_COOLDOWN=${config.updateFailureCooldown}s)",
        )
        return true
    }

    /**
     * Remember that the image [target] was being updated to could not be brought up, so the next cycles
     * leave the (still running, still healthy) container alone instead of taking it down again.
     *
     * Only an actual image update is remembered: a recreate that failed while following a dependency
     * has no new image to blame, and suppressing it would leave a container pinned to a dead namespace.
     */
    private fun rememberFailedUpdate(target: Target) {
        val imageId = target.newImageId?.takeIf { target.stale } ?: return
        if (cooldownMs <= 0) return
        val now = clock.millis()
        failedUpdates[target.id] = FailedUpdate(imageId, now)
        Log.warn(
            "[${target.name}] not trying ${imageId.shortId()} again before " +
                "${Instant.ofEpochMilli(now + cooldownMs)} — a repeat of this update is a repeat of this outage",
        )
    }

    private val cooldownMs: Long get() = config.updateFailureCooldown * 1000L

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
            // Only an actual image update restamps the compose label; a container recreated because a
            // create-time dependency moved still runs the image its label already names.
            newComposeImageId = if (target.stale) target.newImageId else null,
        )
        val backupName = backupName(name, target.id)
        // A replacement we failed to delete still owns [name], which is what the rollback needs back.
        var stranded: String? = null

        try {
            stopGracefully(target) // usually a no-op (already stopped in the reverse-order pass)
            api.rename(target.id, backupName)
            val newId = api.create(name, body, target.platform)
            try {
                networks.drop(1).forEach { (net, endpoint) -> api.connectNetwork(net, newId, endpoint) }
                api.start(newId)
                // Everything past this point destroys the only copy of the previous state, so the
                // replacement has to prove it is actually up first.
                verifyStarted(name, newId)
            } catch (e: Exception) {
                stranded = discardReplacement(name, newId)
                throw e
            }
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
            if (config.updateCleanup && target.currentImageId.isNotEmpty()) {
                pruneOldImage(name, target.currentImageId, imageRef)
            }
        } catch (e: Exception) {
            // Any failure after we stopped the container must restore the original, running container.
            Log.error("[$name] recreate failed — rolling back: ${e.message}")
            rememberFailedUpdate(target)
            rollback(target.id, name, stranded)
            throw e
        }
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
     * Put the original container back the way it was: under [name] and running. [blockingId] is a
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
     */
    private fun rollback(oldId: String, name: String, blockingId: String?) {
        val interrupted = Thread.interrupted()
        if (interrupted) {
            Log.warn("[$name] rolling back on an interrupted thread — finishing the rollback before stopping")
        }
        try {
            restoreName(oldId, name, blockingId)
            try {
                api.start(oldId)
            } catch (e: Exception) {
                Log.error("[$name] rollback: could not start the previous container ${oldId.take(12)}: ${e.message}")
            }
            verifyRolledBack(oldId, name)
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
    private fun verifyRolledBack(oldId: String, name: String) {
        val inspect = try {
            api.inspectContainer(oldId)
        } catch (e: Exception) {
            Log.error("[$name] rollback could not be verified — inspect of ${oldId.take(12)} failed: ${e.message}")
            return
        }
        val actualName = inspect.str("Name")?.trimStart('/')
        val running = inspect.obj("State")?.str("Running") == "true"
        if (actualName == name && running) {
            Log.info("[$name] rolled back to the previous container")
            return
        }
        Log.error(
            "[$name] ROLLBACK INCOMPLETE — the previous container ${oldId.take(12)} is " +
                "${if (running) "running" else "stopped"} under the name '${actualName ?: "?"}': " +
                "nothing is serving as '$name' and it needs a human",
        )
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
     */
    private fun verifyStarted(name: String, newId: String) {
        val deadline = clock.millis() + config.updateVerifySeconds * 1000L
        var good = 0
        while (true) {
            val state = probeState(name, newId)
            livenessFailure(state)?.let { error("the replacement did not stay up: $it") }
            // A probe we could not read counts as "not settled yet" rather than as a failure: a blip on
            // the socket is not evidence the container is broken, and a rollback would be self-inflicted.
            val settled = state != null && !(config.updateVerifyHealth && state.healthStatus() == "starting")
            if (settled && ++good >= REQUIRED_GOOD_PROBES) {
                Log.info("[$name] replacement is up ($good consecutive probes)")
                return
            }
            if (!settled) good = 0
            if (clock.millis() >= deadline) {
                val why = if (settled) "up but only $good/$REQUIRED_GOOD_PROBES probes in" else "still starting up"
                Log.info("[$name] replacement is $why after ${config.updateVerifySeconds}s — accepting it")
                return
            }
            sleeper.sleep(PROBE_INTERVAL_MS)
        }
    }

    private fun probeState(name: String, newId: String): JsonObject? =
        runCatching { api.inspectContainer(newId).obj("State") }
            .onFailure { Log.warn("[$name] could not probe the replacement: ${it.message}") }
            .getOrNull()

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
    private fun stopTimeout(target: Target): Int? =
        target.composeLabels.label("$ns.stop.timeout")?.toIntOrNull() ?: config.defaultStopTimeout

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
        val foreignTags = tags - normalizeImageRef(updatedRef)
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
    }
}

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

/** One container kodkod is considering for an update, plus the verdict it accumulates this cycle. */
internal class Target(
    val id: String,
    val name: String,
    val inspect: JsonObject,
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

    /** A container this one depends on is being restarted, so this one must restart too. */
    var linkedToRestarting: Boolean = false

    /** A create-time dependency changed, so this container must be recreated rather than merely started. */
    var linkedToRecreate: Boolean = false

    /** Ids (within this cycle's set) of the containers this one depends on. */
    var deps: Set<String> = emptySet()

    /** Dependency ids that are baked into create-time config (`--link` or `network_mode: container:`). */
    var createTimeDeps: Set<String> = emptySet()

    /** Name captured before updates for `HostConfig.NetworkMode=container:<id|name>`. */
    var networkModeContainerName: String? = null

    /** The running image's defaults, captured before pulling so a moved tag cannot erase them. */
    var oldImageConfig: JsonObject? = null

    /**
     * Local image id the tag resolves to now — set exactly when [stale] is set, since a target that
     * could not be resolved to a new id is never marked stale. Restamps `com.docker.compose.image`.
     */
    var newImageId: String? = null

    val toRecreate: Boolean get() = stale || linkedToRecreate

    val toRestart: Boolean get() = toRecreate || linkedToRestarting
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
        fun addDep(depId: String?, createTime: Boolean = false) {
            if (depId != null && depId != target.id) deps += depId
            if (createTime && depId != null && depId != target.id) createTimeDeps += depId
        }

        // Compose metadata: entries look like "db:service_started:true" — take the service name.
        target.composeLabels.label("com.docker.compose.depends_on")?.splitToSequence(',')?.forEach { entry ->
            val service = entry.substringBefore(':').trim()
            val project = target.composeProject
            if (service.isNotEmpty() && project != null) addDep(byService[serviceKey(project, service)])
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
    }
}

/**
 * Mark every container that depends (transitively) on a restarting container as restarting too. A
 * fixpoint loop so chains `c -> b -> a` propagate fully (watchtower's `UpdateImplicitRestart` is a
 * single pass).
 */
internal fun propagateLinkedRestart(targets: List<Target>) {
    val byId = targets.associateBy { it.id }
    var changed = true
    while (changed) {
        changed = false
        for (target in targets) {
            if (!target.toRecreate && target.createTimeDeps.any { byId[it]?.toRestart == true }) {
                target.linkedToRecreate = true
                changed = true
            }
            if (!target.toRestart && target.deps.any { byId[it]?.toRestart == true }) {
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

private fun serviceKey(project: String, service: String) = project + '\u0000' + service

private fun String.shortId(): String =
    removePrefix("sha256:").take(12).ifEmpty { "<none>" }
