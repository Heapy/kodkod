package io.heapy.kodkod

/**
 * Runtime configuration, sourced entirely from environment variables so the daemon
 * can be configured the usual docker-compose way (`environment:` / `.env`).
 */
data class Config(
    val dockerSocket: String,
    val labelNamespace: String,
    /**
     * `KODKOD_STOP_TIMEOUT`, or `null` when the operator set nothing. `null` is not a synonym for
     * some hardcoded default: it means kodkod has no opinion and each container's own
     * `Config.StopTimeout` (as recorded by `docker run --stop-timeout` / compose `stop_grace_period`)
     * decides how long its graceful stop gets.
     */
    val defaultStopTimeout: Int?,
    // Autoheal — restart unhealthy containers
    val autohealEnabled: Boolean,
    val autohealInterval: Long,
    /**
     * `KODKOD_AUTOHEAL_MAX_INTERVAL` — the ceiling of the per-container backoff between restarts of a
     * container that stays unhealthy. A container unhealthy because of its *configuration* is not fixed
     * by a restart, and restarting it every [autohealInterval] forever keeps resetting its healthcheck
     * `start_period`, so it reads as freshly starting instead of broken. The wait doubles from
     * [autohealInterval] up to this value; setting it *to* [autohealInterval] disables the backoff and
     * restores the retry-every-cycle behaviour. Never below [autohealInterval] — a ceiling under the
     * loop's own period would throttle nothing.
     */
    val autohealMaxInterval: Long,
    val autohealStartPeriod: Long,
    val autohealMonitorAll: Boolean,
    // Update — pull newer images and recreate containers
    val updateEnabled: Boolean,
    val updateInterval: Long,
    val updateStartPeriod: Long,
    val updateMonitorAll: Boolean,
    val updateCleanup: Boolean,
    /**
     * `KODKOD_UPDATE_VERIFY_SECONDS` — how long a replacement container is watched after `start`
     * before the container and image it replaced are destroyed. A `204` from `POST /start` only says
     * the process was launched, not that it survived, so nothing irreversible happens until this
     * window either confirms the replacement or expires. `0` means "one probe and move on".
     *
     * The whole window is spent unless the container's own healthcheck reports `healthy` — the one
     * positive answer there is, which ends the wait after three consecutive probes. Without a
     * healthcheck the only signal is "it has not exited yet", so the replacement is watched to the end
     * of the window; a service that dies once it fails to reach its database dies *after* its init, and
     * a shorter look would pass it. That is time the cycle lock is held, once per recreated container:
     * this is the knob that trades it against how much of a bad update kodkod can still take back.
     */
    val updateVerifySeconds: Long,
    /**
     * `KODKOD_UPDATE_VERIFY_HEALTH` — whether a replacement that has already *failed* its healthcheck
     * counts as a failed update. A container still inside its `start_period` (`Health=starting`) never
     * does: that period is the image author's own statement about acceptable startup time. It governs
     * the *failing* verdict only — a replacement reporting `healthy` ends the wait early either way.
     */
    val updateVerifyHealth: Boolean,
    /**
     * `KODKOD_UPDATE_FAILURE_COOLDOWN` — how many seconds an image that already failed to come up on a
     * container is left alone before that exact update is tried again. Without it a `:latest` that
     * cannot start makes every single cycle stop the healthy container, rename it, fail, and roll back:
     * a self-inflicted outage once per `KODKOD_UPDATE_INTERVAL`, forever. One flat window, no backoff —
     * the point is to stop repeating a known outage, not to model the failure. `0` disables the memory
     * and restores the retry-every-cycle behaviour.
     */
    val updateFailureCooldown: Long,
    /**
     * `KODKOD_DEPENDENCY_HEALTH_TIMEOUT` — how many seconds a container waits for a dependency compose
     * marked `condition: service_healthy` to actually become healthy before it is started anyway. The
     * condition is about ordering, so the wait must be bounded: a dependency that never passes its
     * healthcheck may delay its dependents, never keep them down (and never stall the whole cycle,
     * which also drives every *other* container's update). `0` means "check once and carry on".
     */
    val dependencyHealthTimeout: Long,
    /**
     * `KODKOD_RESPECT_DEPENDS_ON_RESTART` — whether `depends_on[*].restart: false` suppresses the
     * restart of a dependent. Off by default, deliberately: compose's field governs whether
     * `docker compose restart` propagates, while kodkod *replaces* the dependency with a new container
     * holding a new IP — the case where a dependent usually does need to be restarted. And since
     * `restart: false` is compose's own default, obeying it by default would turn kodkod's documented
     * dependent-restart into a no-op for essentially every stack. Even when this is on, the flag can
     * only suppress a plain restart: a create-time dependent (`--link` / `network_mode: container:`) is
     * always recreated, since leaving it be would leave it on a dead network namespace.
     */
    val respectDependsOnRestart: Boolean,
    val registryAuth: String?,
    /**
     * `KODKOD_SHUTDOWN_GRACE` — how many seconds a stopping kodkod gives the cycle in flight to
     * finish before interrupting it. A recreate is at its most dangerous exactly here: between the
     * rename of the old container and the start of its replacement nothing is serving the service
     * name, so cutting the cycle short is what creates the orphaned `_kodkod_old_` backup that the
     * next process has to reconcile — and every container the cycle had already stopped for a
     * dependency of its own is down until it unwinds. An interrupted cycle gets a few seconds more to
     * put those back (see `stopScheduler`). Note the operator still owns the outer deadline — Docker
     * sends SIGKILL 10s after SIGTERM unless kodkod's own `stop_grace_period` says otherwise.
     */
    val shutdownGrace: Long,
) {
    companion object {
        fun fromEnv(get: (String) -> String? = System::getenv): Config {
            fun str(key: String, default: String) = get(key)?.takeIf { it.isNotBlank() } ?: default
            fun long(key: String, default: Long) = get(key)?.trim()?.toLongOrNull() ?: default
            /** No default: `null` is the answer "the operator set nothing", not a stand-in for one. */
            fun intOrNull(key: String) = get(key)?.trim()?.toIntOrNull()
            // An empty value is an *unset* variable, not `false`: `KODKOD_UPDATE_VERIFY_HEALTH=` in a
            // compose file (or an unresolved `${...}`) must not silently switch a safety check off.
            fun bool(key: String, default: Boolean) =
                get(key)?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()?.let { it in TRUTHY } ?: default

            // Read up front: the backoff ceiling is expressed relative to it.
            val autohealInterval = long("KODKOD_AUTOHEAL_INTERVAL", 30)

            return Config(
                dockerSocket = str("KODKOD_DOCKER_SOCKET", "/var/run/docker.sock"),
                labelNamespace = str("KODKOD_LABEL_NAMESPACE", "kodkod"),
                defaultStopTimeout = intOrNull("KODKOD_STOP_TIMEOUT"),
                autohealEnabled = bool("KODKOD_AUTOHEAL_ENABLED", true),
                autohealInterval = autohealInterval,
                autohealMaxInterval = long("KODKOD_AUTOHEAL_MAX_INTERVAL", 3600).coerceAtLeast(autohealInterval),
                autohealStartPeriod = long("KODKOD_AUTOHEAL_START_PERIOD", 0),
                autohealMonitorAll = bool("KODKOD_AUTOHEAL_MONITOR_ALL", false),
                updateEnabled = bool("KODKOD_UPDATE_ENABLED", true),
                updateInterval = long("KODKOD_UPDATE_INTERVAL", 3600),
                updateStartPeriod = long("KODKOD_UPDATE_START_PERIOD", 0),
                updateMonitorAll = bool("KODKOD_UPDATE_MONITOR_ALL", false),
                updateCleanup = bool("KODKOD_UPDATE_CLEANUP", true),
                updateVerifySeconds = long("KODKOD_UPDATE_VERIFY_SECONDS", 15).coerceAtLeast(0),
                updateVerifyHealth = bool("KODKOD_UPDATE_VERIFY_HEALTH", true),
                updateFailureCooldown = long("KODKOD_UPDATE_FAILURE_COOLDOWN", 21600).coerceAtLeast(0),
                dependencyHealthTimeout = long("KODKOD_DEPENDENCY_HEALTH_TIMEOUT", 120).coerceAtLeast(0),
                respectDependsOnRestart = bool("KODKOD_RESPECT_DEPENDS_ON_RESTART", false),
                registryAuth = get("KODKOD_REGISTRY_AUTH")?.takeIf { it.isNotBlank() },
                shutdownGrace = long("KODKOD_SHUTDOWN_GRACE", 30).coerceAtLeast(0),
            ).also {
                require(it.autohealInterval > 0) { "KODKOD_AUTOHEAL_INTERVAL must be > 0 (got ${it.autohealInterval})" }
                require(it.updateInterval > 0) { "KODKOD_UPDATE_INTERVAL must be > 0 (got ${it.updateInterval})" }
            }
        }
    }
}
