# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [0.3.0] - 2026-06-04

### Added
- Update ordering for compose stacks: containers are stopped in reverse dependency order and recreated
  in forward order, and a container that depends on an updated one is restarted too. Dependencies are
  read from Compose's `com.docker.compose.depends_on` labels, or the `kodkod.depends-on` label /
  `--link` / `network_mode: container:` outside compose.
- Multi-network containers are reconnected to every network on recreate; `network_mode: container:<id>`
  is resolved to the referenced container's name so it survives that container being recreated.
- `io.heapy.kodkod.self` image label so kodkod reliably skips its own container regardless of `HOSTNAME`.
- Unit test module covering image-defaults subtraction, the dependency graph, HTTP parsing, config and
  JSON helpers.

### Changed
- Recreate now subtracts the old image's defaults (env, cmd, entrypoint, healthcheck, …) so a new
  image's changed defaults are actually adopted instead of being masked by the old container's resolved
  config.
- Recreate rollback now covers any failure after the container is stopped (including rename), so an
  interrupted update can no longer leave a container stopped.

### Fixed
- Reject non-positive `KODKOD_*_INTERVAL` values with a clear message instead of an opaque scheduler
  error at startup.

## [0.2.0] - 2026-06-03

### Added
- Kotlin 2.4.0 application (Gradle 9.5.1) talking to the Docker Engine API directly
  over the unix socket, with a single runtime dependency (kotlinx-serialization-json).
- Autoheal loop: restarts `unhealthy` containers (labels `kodkod.autoheal.enable`,
  `kodkod.stop.timeout`).
- Update loop: pulls a container's image tag and recreates it — preserving config, env,
  labels and networks — when the image changes (label `kodkod.update.enable`).
- Lightweight multi-stage Dockerfile on BellSoft Liberica (JDK build → JRE Alpine runtime).
