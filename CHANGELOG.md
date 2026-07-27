# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- Liveness gate before anything irreversible: a replacement container is probed for
  `KODKOD_UPDATE_VERIFY_SECONDS` (default 15) after `start`, and the old container and image are only
  released once it has stayed up. `KODKOD_UPDATE_VERIFY_HEALTH` (default `true`) also fails an update
  whose replacement reports `unhealthy`; a container still inside its `start_period` is accepted.
- Memory of an image that could not come up on a container: the same update is not retried for
  `KODKOD_UPDATE_FAILURE_COOLDOWN` seconds (default 6h), so an unstartable `:latest` no longer costs a
  self-inflicted outage every cycle. Cleared as soon as the tag resolves to a different image. A
  container held back this way still follows its own dependencies — being left on a network namespace
  that was destroyed is the worse outcome — but nothing may force it onto that image either, so the
  container it is joined to is the one that waits (see below). Note the memory lives in the process:
  restarting kodkod forgets every cooldown.
- Reconciliation of orphaned `_kodkod_old_` backups, at startup and at the beginning of every cycle:
  restored when nothing serves the service name, removed once the replacement is running. It runs even
  with `KODKOD_UPDATE_ENABLED=false`, since otherwise a backup left by a killed process would never
  come back.
- `KODKOD_SHUTDOWN_GRACE` (default 30s): a stopping kodkod waits for the cycle in flight instead of
  interrupting a recreate between the rename of a container and the start of its replacement.
- Per-container exponential backoff for autoheal restarts, capped by `KODKOD_AUTOHEAL_MAX_INTERVAL`
  (default 3600). Restarting a container that is unhealthy because of its configuration every cycle
  only kept resetting its healthcheck `start_period`. The counter is reset by the daemon reporting the
  container `healthy` — not by its absence from the `health=unhealthy` listing, which is also what a
  container that is merely `starting` after its restart looks like. Like the update cooldown, the
  counters live in the process and are forgotten on restart.
- Autoheal restarts the containers that share the restarted container's network namespace or link to
  it, instead of leaving them attached to a namespace that no longer exists.
- Compose `depends_on` conditions are honoured: a dependency marked `condition: service_healthy` is
  waited for (bounded by `KODKOD_DEPENDENCY_HEALTH_TIMEOUT`, default 120s) before its dependent starts.
  The `restart` field of the same label is obeyed only under `KODKOD_RESPECT_DEPENDS_ON_RESTART=true`.
- The running image's platform (`os/arch`) is pinned on both the pull and the create, so a container
  running a non-native image is not silently updated to the host's architecture.
- Record/replay test harness: real Docker responses are captured into a versioned fixture corpus
  (`src/test/resources/docker-fixtures/engine-<ver>_compose-<ver>`) by an opt-in e2e recorder and
  replayed through the real `DockerApi` in unit tests. See `E2E_TESTING.md`.
- CI job running the Docker-backed e2e suite.

### Changed
- The update cycle is split into a read-only `plan()` (list, inspect, registry probe, pull) and a
  mutating `apply()`. Only the second half holds the cycle lock, so a long pull no longer starves
  autoheal, and a plan whose containers or images moved underneath it is dropped instead of applied.
- Orchestration talks to the daemon through a `DockerClient` interface, so `Updater`/`Autoheal` are
  testable against an in-memory daemon rather than the HTTP layer.
- `stop`/`restart` send no `t` parameter unless kodkod has an explicit override (the
  `kodkod.stop.timeout` label or `KODKOD_STOP_TIMEOUT`), so the daemon applies the container's own
  `Config.StopTimeout` / compose `stop_grace_period`. The read timeout is sized from whichever value
  will actually apply, instead of a flat 60s that could cut a long graceful stop short.
- `KODKOD_UPDATE_CLEANUP` skips an old image that still carries a tag other than the one just updated,
  so a pinned rollback tag (`app:1.26`) is no longer silently untagged by the prune. The prune is also
  limited to updates that actually replaced the image: a container recreated because a create-time
  dependency moved runs the same image its replacement runs, so there is nothing to reclaim. Tags are
  compared canonically, so `docker.io/library/nginx:1.27` matches the `nginx:1.27` the daemon records.
- The e2e suite is a JUnit suite (`src/e2eTest`) instead of shell scripts, running against a
  disposable Docker-in-Docker daemon by default.

### Fixed
- A create-time dependent is no longer forced through a recreate that could not be undone. Its
  replacement is built from its image *ref*, so once that ref has moved on — the dependent has an update
  of its own pending, or is inside the cooldown of one that failed — the recreate can fail, and by then
  the provider's old container is gone and the dependent's original cannot be started at all (it is
  joined to a namespace that no longer exists). The provider is therefore kept out of the cycle until
  the dependent's own image has settled, one update per cycle instead of an outage with no way back.
- A create-time dependent that could not be recreated *and* could not be rolled back is remembered and
  brought back on every later cycle, instead of being left stopped under its own name where neither
  discovery (`status=running`) nor the backup reconcile (`_kodkod_old_*`) would ever look at it again.
  What it takes is decided each cycle from the daemon: while the namespace it names is still its
  provider's, a `start` is issued and nothing is destroyed; once that container is gone — which a later
  cycle can do at any time, since a stopped dependent no longer holds its provider back — only a rebuild
  against the container serving the provider's name can work, and that is what it gets. Like the
  cooldown, this memory lives in the process: the log line says so, and names the command to run by hand
  if kodkod restarts before the container is serving again.
- Recreate create-time dependents (`--link` / `network_mode: container:`) when a dependency is updated,
  and resolve `network_mode: container:<id>` to a container name before any old ids are removed. This
  now also covers dependents kodkod does not monitor itself.
- Stamp `com.docker.compose.image` with the id of the image kodkod resolved, instead of copying the old
  one. With the stale id the next `docker compose up` recreated the container kodkod had just updated.
- Inherit the anonymous volumes a container is running with (resolved from the top-level `Mounts[]`,
  the only place their generated names appear) instead of handing the replacement fresh empty volumes —
  the data-loss case for a database recreated by an update.
- Keep the endpoint's `GwPriority` (which network provides the default route) on recreate, and carry
  over an explicitly requested `MacAddress` on engines that still report `Config.MacAddress`.
- Rollback no longer gives up on a name conflict: the failed replacement is removed or parked out of
  the way, the rename is retried, and the end state is verified against the daemon, so "the rollback
  ran" and "the service is back" are no longer the same claim. Failures are logged instead of swallowed.
- Rollback works on an interrupted thread: the interrupt flag is cleared for its duration and handed
  back afterwards. A `SIGTERM` mid-recreate used to make every NIO call fail instantly and leave the
  service stopped under its backup name.
- A container this cycle stopped for a dependency of its own is retried a few times if `start` is
  refused, and reported at `ERROR` if it stays down, instead of being silently left stopped.
- The liveness gate no longer passes without evidence. A window in which no probe could be read at all
  fails the update instead of releasing the old container and image, and a `404` on the replacement (an
  `AutoRemove` inherited from the old container, an outside `docker rm`) is treated as the failure it
  is rather than as "still starting".
- The reconcile of orphaned backups no longer force-removes a container it did not create. Only a
  holder that was created and never started is cleared out of the way; a holder that has run is left
  alone and reported, because that is also what a completed update stopped by an operator looks like.
- The failed-update cooldown is only recorded when the replacement actually ran: a refused stop, a name
  conflict or a rejected create says nothing about the image and no longer freezes the update for six
  hours.
- The plan is re-checked against the daemon before it is applied, including whether the container is
  still running, and the replacement is built from the state at that moment — a `docker update`, a
  `network connect` or a label change made while the image downloaded is no longer reverted.
- An autoheal restart whose answer never arrived (a `stop_grace_period` longer than the socket's read
  timeout) is read back from the daemon instead of assumed failed, so the containers sharing its network
  namespace are still refreshed. The read-back is given as long as the container's own
  `Config.StopTimeout` needs — read from the inspect it was making anyway — instead of a flat minute
  that expired just short of a `stop_grace_period: 120s` restart, which is exactly the configuration
  that makes the answer go missing in the first place. That wait is held under the cycle lock, so it is
  capped at five minutes and the truncation is logged: one container's stop window may not park autoheal
  and the updater for as long as it likes.
- A rollback that failed before the container was renamed no longer asks the daemon to rename it onto
  its own name, which was refused and reported as two ERRORs about a container that never moved.
- Chunked responses that were cut off mid-body are reported as transport errors instead of being
  returned as short-but-complete answers (an empty container list, an image with no tags).
- Image references are escaped into request paths, an empty environment variable no longer reads as
  `false`, and the compose service key no longer embeds a literal NUL byte in the source.

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
