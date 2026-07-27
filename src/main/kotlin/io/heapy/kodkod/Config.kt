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
     */
    val updateVerifySeconds: Long,
    /**
     * `KODKOD_UPDATE_VERIFY_HEALTH` — whether a replacement that has already *failed* its healthcheck
     * counts as a failed update. A container still inside its `start_period` (`Health=starting`) never
     * does: that period is the image author's own statement about acceptable startup time.
     */
    val updateVerifyHealth: Boolean,
    val registryAuth: String?,
    /**
     * `KODKOD_SHUTDOWN_GRACE` — how many seconds a stopping kodkod gives the cycle in flight to
     * finish before interrupting it. A recreate is at its most dangerous exactly here: between the
     * rename of the old container and the start of its replacement nothing is serving the service
     * name, so cutting the cycle short is what creates the orphaned `_kodkod_old_` backup that the
     * next process has to reconcile. Note the operator still owns the outer deadline — Docker sends
     * SIGKILL 10s after SIGTERM unless kodkod's own `stop_grace_period` says otherwise.
     */
    val shutdownGrace: Long,
) {
    companion object {
        fun fromEnv(get: (String) -> String? = System::getenv): Config {
            fun str(key: String, default: String) = get(key)?.takeIf { it.isNotBlank() } ?: default
            fun long(key: String, default: Long) = get(key)?.trim()?.toLongOrNull() ?: default
            fun bool(key: String, default: Boolean) =
                get(key)?.trim()?.lowercase()?.let { it in TRUTHY } ?: default

            return Config(
                dockerSocket = str("KODKOD_DOCKER_SOCKET", "/var/run/docker.sock"),
                labelNamespace = str("KODKOD_LABEL_NAMESPACE", "kodkod"),
                defaultStopTimeout = get("KODKOD_STOP_TIMEOUT")?.trim()?.toIntOrNull(),
                autohealEnabled = bool("KODKOD_AUTOHEAL_ENABLED", true),
                autohealInterval = long("KODKOD_AUTOHEAL_INTERVAL", 30),
                autohealStartPeriod = long("KODKOD_AUTOHEAL_START_PERIOD", 0),
                autohealMonitorAll = bool("KODKOD_AUTOHEAL_MONITOR_ALL", false),
                updateEnabled = bool("KODKOD_UPDATE_ENABLED", true),
                updateInterval = long("KODKOD_UPDATE_INTERVAL", 3600),
                updateStartPeriod = long("KODKOD_UPDATE_START_PERIOD", 0),
                updateMonitorAll = bool("KODKOD_UPDATE_MONITOR_ALL", false),
                updateCleanup = bool("KODKOD_UPDATE_CLEANUP", true),
                updateVerifySeconds = long("KODKOD_UPDATE_VERIFY_SECONDS", 15).coerceAtLeast(0),
                updateVerifyHealth = bool("KODKOD_UPDATE_VERIFY_HEALTH", true),
                registryAuth = get("KODKOD_REGISTRY_AUTH")?.takeIf { it.isNotBlank() },
                shutdownGrace = long("KODKOD_SHUTDOWN_GRACE", 30).coerceAtLeast(0),
            ).also {
                require(it.autohealInterval > 0) { "KODKOD_AUTOHEAL_INTERVAL must be > 0 (got ${it.autohealInterval})" }
                require(it.updateInterval > 0) { "KODKOD_UPDATE_INTERVAL must be > 0 (got ${it.updateInterval})" }
            }
        }
    }
}
