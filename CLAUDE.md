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
- **Time is injected.** `WallClock`/`Sleeper` (`Time.kt`) are constructor parameters of
  `Updater`/`Autoheal`, defaulting to the real clock. Tests use `FakeClock`; a unit test must never
  actually sleep through a probe interval, a cooldown or a backoff.
- **The update cycle is `plan()` (reads only, no lock) then `apply()` (mutates, under the cycle lock).**
  A pull must not hold the lock — that starves autoheal. `apply` re-checks that the world did not move
  under the plan before acting on it.
- **A container kodkod stopped is kodkod's to bring back.** `apply` stops the whole set before bringing
  any of it back, so anything that ends that pass early leaves containers stopped under their own names
  — a state discovery (`status=running`) and the backup reconcile (`_kodkod_old_*`) both walk past. Every
  such container goes into `stoppedByKodkod` and is retried each cycle; a new failure path that leaves
  one stopped has to record it there. The memory dies with the process, and no heuristic may replace it:
  nothing durable tells a container kodkod stopped from one an operator stopped.

## Test doubles

- `FakeDockerClient` (unit tests) and `OpLoggingClient` (replay tests) record an op **only after the
  delegated call succeeded**. A call that threw is recorded with a `!` marker (`create!:web`,
  `start!:web`). Keep both doubles on that format: an assertion on `ops` must be able to distinguish
  "kodkod did this" from "kodkod tried this and it failed", otherwise a rollback test passes on a
  code path that never ran.
- `FakeDockerClient.listContainers` honours `all` and the `status`/`label`/`health`/`name`/`id`
  filters, and reads state and names from its own lifecycle model rather than from the fixture, so a
  container it created, started, renamed or removed is listed the way the daemon would list it. A test
  proving "we search the whole daemon, not just the monitored set" depends on that — as does anything
  that runs more than one cycle. Keep it honest: a fake that keeps a removed container, or that
  matches every `health` filter regardless of the modelled health, makes the code that tells those
  cases apart untestable.
- **That honesty is now enforced, not just asked for.** `DockerClientContract` (`src/testFixtures`)
  is one suite both implementations answer to: `FakeDockerClientContractTest` runs it against the fake
  on every `./gradlew test`, `DockerApiContractTest` runs the same class against a real daemon under
  `-Pkodkod.e2e.useCurrentDocker=true`. A behaviour the unit suite is allowed to assume therefore has
  to be one Docker was made to demonstrate. New behaviour the fake grows belongs in the contract, not
  in the fake alone — that is the only thing standing between a green unit suite and a fake that
  invented the daemon it stands in for.

## Fixture corpus (record/replay)

- `DockerReplayTest` replays real recorded Docker responses through the real `DockerApi`. The replay
  key is `"<method> <path>"`.
- **The `Config` a corpus is recorded under is one definition** — `recordedUpdateConfig` /
  `recordedAutohealConfig` in `src/testFixtures` — reached by both the recorder (`e2eTest`) and
  `DockerReplayTest` (`test`). It is not a per-suite preference: it decides the `listContainers`
  filters, which are *part of the request path* replay matches on, and the length of the liveness
  window, which is how many inspects of the replacement a recording contains. Neither side gets a local
  copy; a change to it is a re-record.
- **Changing a request's method, path or query obliges a re-record of the corpus in the same change.**
  Dropping a `?t=`, adding an inspect, adding `?platform=` — all of them break replay. Request bodies
  are *not* part of the key, so a change to a create body needs no re-record. Recording requires
  Docker; see the flags in `E2E_TESTING.md`.
- **Never *add* an unfiltered `all=true` listing on a path the recorder walks.** The recorder runs
  against a developer's real daemon, so an unfiltered listing bakes that machine's unrelated
  containers into the committed corpus. Narrow it (e.g. `name=_kodkod_old_`, `status=running`).
  There is exactly one deliberate exception, and it is conditional: `Dependents.findDependents` widens
  its scan to a daemon-wide `all=true` listing with no filters at all, but only for a provider whose
  compose project turns out to contain a create-time dependent (see its "Scan width" paragraph). No
  recorded scenario has an in-project netns dependent today, so the wide listing is never reached
  during a recording — that is the only reason the corpus is clean, not a property of the code. A
  recorder scenario that adds one (a `network_mode: service:` sidecar inside a stack it updates or
  heals) will start capturing the whole host: give it its own disposable daemon, or do not record it.
- A create body is asserted through `FakeDockerClient.created` / `OpLoggingClient.created`, not stored
  as a fixture: recording our own output as a golden file self-heals on every re-record.
- Replay is FIFO **per key**, so the order of calls to two *different* keys is not checked: moving a
  listing from the start of a cycle to the end replays identically. Order that matters is asserted
  from the op log in `DockerReplayTest`, never assumed from a green replay.
- The recording/replay types (`DockerRecording.kt`) live in **`src/testFixtures`**, not `main`: the
  daemon never records or replays anything, and a fixtures jar is the one thing both `test` and
  `e2eTest` can see without shipping it to users. They are `public` there for exactly that reason — a
  separate module makes `internal` invisible to the source sets they exist for, so do not "tidy" them
  to `internal`.
- `DockerTransport` and `UnixSocketTransport` stay in `main` and stay `public`: that pair is the seam
  `DockerApi` is built on (`DockerApi(transport)`), not test scaffolding. The rule of thumb for this
  split is whether production would still need the type if the tests were deleted.

## When you change X, update Y

- A new or changed `KODKOD_*` variable → its KDoc in `Config`, **and** the env table in `README.md`.
- Any user-visible behaviour change → an entry under `## [Unreleased]` in `CHANGELOG.md`, in the same
  change. A behaviour that only kodkod's own logs reveal still counts as user-visible.
- A new or renamed e2e scenario → the test matrix in `E2E_TESTING.md`, plus the file list when it
  brings a new compose file or `Dockerfile.<variant>`.
- A changed request method/path/query → re-record the fixture corpus (see above).
- Behaviour taught to `FakeDockerClient` → a case in `DockerClientContract`, so a real daemon has to
  demonstrate it too. CI runs that half against the runner's daemon on every push; locally it is
  `./gradlew e2eTest -Pkodkod.e2e.useCurrentDocker=true --tests '*DockerApiContractTest*'`.
- A new rule about what the cycle may leave behind → an invariant in `UpdateCycleModelTest`, not only
  a hand-written scenario. That suite generates the stacks instead of listing them, which is the only
  thing here that reaches a four-event sequence nobody thought to write down. Prove a new invariant
  the way the existing ones were proven: neutralise the line that upholds it and watch that one
  property — and only that one — go red. An invariant no mutation can break is not one.
- **Language.** Plans (`docs/plans/`) and `TODO.md` are written in Russian; everything that ships to
  users — `README.md`, `CHANGELOG.md`, `E2E_TESTING.md`, this file, and every comment, KDoc and log
  line in the source — is English. Do not mix the two inside one file.

## Working expectations

- Every change carries tests — new or updated — covering both the success and the failure path.
- `./gradlew test` must be green before moving on. `./gradlew e2eTest` needs Docker and runs the suite
  against a disposable Docker-in-Docker daemon.
- kodkod must never leave a service in a state it cannot recover from by itself: anything that stops a
  container owns putting it back, and "the recovery ran" is not the same claim as "the service is up" —
  verify against the daemon and log loudly when it did not land.
