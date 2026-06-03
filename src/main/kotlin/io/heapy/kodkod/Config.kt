package io.heapy.kodkod

/**
 * Runtime configuration, sourced entirely from environment variables so the daemon
 * can be configured the usual docker-compose way (`environment:` / `.env`).
 */
data class Config(
    val dockerSocket: String,
    val labelNamespace: String,
    val defaultStopTimeout: Int,
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
    val registryAuth: String?,
) {
    companion object {
        fun fromEnv(get: (String) -> String? = System::getenv): Config {
            fun str(key: String, default: String) = get(key)?.takeIf { it.isNotBlank() } ?: default
            fun long(key: String, default: Long) = get(key)?.trim()?.toLongOrNull() ?: default
            fun int(key: String, default: Int) = get(key)?.trim()?.toIntOrNull() ?: default
            fun bool(key: String, default: Boolean) =
                get(key)?.trim()?.lowercase()?.let { it in TRUTHY } ?: default

            return Config(
                dockerSocket = str("KODKOD_DOCKER_SOCKET", "/var/run/docker.sock"),
                labelNamespace = str("KODKOD_LABEL_NAMESPACE", "kodkod"),
                defaultStopTimeout = int("KODKOD_STOP_TIMEOUT", 10),
                autohealEnabled = bool("KODKOD_AUTOHEAL_ENABLED", true),
                autohealInterval = long("KODKOD_AUTOHEAL_INTERVAL", 30),
                autohealStartPeriod = long("KODKOD_AUTOHEAL_START_PERIOD", 0),
                autohealMonitorAll = bool("KODKOD_AUTOHEAL_MONITOR_ALL", false),
                updateEnabled = bool("KODKOD_UPDATE_ENABLED", true),
                updateInterval = long("KODKOD_UPDATE_INTERVAL", 3600),
                updateStartPeriod = long("KODKOD_UPDATE_START_PERIOD", 0),
                updateMonitorAll = bool("KODKOD_UPDATE_MONITOR_ALL", false),
                updateCleanup = bool("KODKOD_UPDATE_CLEANUP", true),
                registryAuth = get("KODKOD_REGISTRY_AUTH")?.takeIf { it.isNotBlank() },
            ).also {
                require(it.autohealInterval > 0) { "KODKOD_AUTOHEAL_INTERVAL must be > 0 (got ${it.autohealInterval})" }
                require(it.updateInterval > 0) { "KODKOD_UPDATE_INTERVAL must be > 0 (got ${it.updateInterval})" }
            }
        }
    }
}
