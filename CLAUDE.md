# kodkod — working notes for agents

A docker-compose companion doing exactly two things: restart `unhealthy` containers (autoheal) and
recreate containers whose image tag moved (update). Kotlin, one runtime dependency
(kotlinx-serialization-json). See `README.md` for behaviour and `E2E_TESTING.md` for the test layout.

## Architecture rules

- **Socket only.** The daemon is reached over the unix socket (`DockerApi`, HTTP/1.1 hand-rolled).
  No `docker` CLI, no compose CLI, no `DOCKER_HOST`/TCP support in production code.
- **No compose-file parsing.** Everything kodkod knows about a stack comes from container labels
  (`com.docker.compose.*`) and inspect payloads, never from `docker-compose.yml`.
- **Orchestration goes through `DockerClient`, never `DockerApi` directly.** `Updater` and `Autoheal`
  depend on the interface so they can be driven by an in-memory daemon in unit tests.
- **All configuration is env, parsed in one place** (`Config.fromEnv`). No config reading anywhere else.
  Document each variable's *why* in its KDoc and add it to the README table in the same change.
- **Helpers are `internal` and pure** (`buildCreateBody`, `resolveMounts`, `resolveLinks`, `topoSort`,
  `parseDependsOn`, …) so behaviour can be tested as a JSON-in/JSON-out transformation.
- **Time is injected.** `TimeSource`/`Sleeper` (`Time.kt`) are constructor parameters of
  `Updater`/`Autoheal`, defaulting to the real clock. Tests use `FakeClock`; a unit test must never
  actually sleep through a probe interval, a cooldown or a backoff.
- **The update cycle is `plan()` (reads only, no lock) then `apply()` (mutates, under the cycle lock).**
  A pull must not hold the lock — that starves autoheal. `apply` re-checks that the world did not move
  under the plan before acting on it.

## Test doubles

- `FakeDockerClient` (unit tests) and `OpLoggingClient` (replay tests) record an op **only after the
  delegated call succeeded**. A call that threw is recorded with a `!` marker (`create!:web`,
  `start!:web`). Keep both doubles on that format: an assertion on `ops` must be able to distinguish
  "kodkod did this" from "kodkod tried this and it failed", otherwise a rollback test passes on a
  code path that never ran.
- `FakeDockerClient.listContainers` honours `all` and the `status`/`label`/`health`/`name` filters. A
  test proving "we search the whole daemon, not just the monitored set" depends on that.

## Fixture corpus (record/replay)

- `DockerReplayTest` replays real recorded Docker responses through the real `DockerApi`. The replay
  key is `"<method> <path>"`.
- **Changing a request's method, path or query obliges a re-record of the corpus in the same change.**
  Dropping a `?t=`, adding an inspect, adding `?platform=` — all of them break replay. Request bodies
  are *not* part of the key, so a change to a create body needs no re-record. Recording requires
  Docker; see the flags in `E2E_TESTING.md`.
- **Never issue an unfiltered `all=true` listing on a path the recorder walks.** The recorder runs
  against a developer's real daemon, so an unfiltered listing bakes that machine's unrelated
  containers into the committed corpus. Narrow it (e.g. `name=_kodkod_old_`, `status=running`).
- A create body is asserted through `FakeDockerClient.created` / `OpLoggingClient.created`, not stored
  as a fixture: recording our own output as a golden file self-heals on every re-record.

## Working expectations

- Every change carries tests — new or updated — covering both the success and the failure path.
- `./gradlew test` must be green before moving on. `./gradlew e2eTest` needs Docker and runs the suite
  against a disposable Docker-in-Docker daemon.
- kodkod must never leave a service in a state it cannot recover from by itself: anything that stops a
  container owns putting it back, and "the recovery ran" is not the same claim as "the service is up" —
  verify against the daemon and log loudly when it did not land.
