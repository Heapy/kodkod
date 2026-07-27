# kodkod end-to-end tests

Unit tests under `src/test` cover three layers, none of which needs Docker:

- pure logic — image-defaults subtraction, the dependency graph, HTTP parsing, config parsing;
- whole `Updater`/`Autoheal` cycles against `FakeDockerClient`, an in-memory daemon that models
  listing filters, the name index and the container lifecycle, so orchestration behaviour (rollback,
  the liveness gate, backoff, cooldowns, ordering) is asserted from the ops a cycle issued;
- `DockerReplayTest`, which replays the committed corpus of **real** Docker responses under
  `src/test/resources/docker-fixtures` through the production `DockerApi`, and fails if the code under
  test issues a request the recording does not have (or stops issuing one it does).

The E2E suite under `src/e2eTest` covers what none of those can: kodkod driving a real Docker daemon.

The E2E scenarios build `kodkod:e2e`, run a throwaway registry, publish movable
test images, recreate containers, validate dependency ordering, roll back failed
updates, restart unhealthy containers, and verify self-protection.

## Safety

Some scenarios set `KODKOD_*_MONITOR_ALL=true`. In that mode kodkod acts on every
running container on the Docker daemon it talks to. Do not run the suite against a
daemon that hosts anything you care about.

By default, `e2eTest` starts an isolated Docker-in-Docker daemon. The host Docker
daemon is used only to run the `docker:dind` container; the registry, test stacks,
and kodkod itself run inside the disposable daemon. The dind container is removed
after the suite unless `-Pkodkod.e2e.keepDind=true` is set.

That removal uses `docker rm -f -v`, and so does the pre-start cleanup of a dind
left over from an earlier run. This matters: `docker:dind` declares
`VOLUME /var/lib/docker`, so each run gets an anonymous volume holding the inner
daemon's entire image store (~1 GB). Without `-v` every run would orphan one on the
host. Keeping the dind with `-Pkodkod.e2e.keepDind=true` keeps its volume too —
the next run reclaims both.

## Prerequisites

- Docker Engine + Compose v2 available on the host.
- JDK 25, via the Gradle wrapper setup used by the project.
- About 1 GB free disk and internet access for `docker:dind`, `busybox`,
  `registry:2`, and the JDK base images used by the kodkod image build.
- Run commands from the repository root.

## Running

Run the unit tests:

```bash
./gradlew test
```

Run all Docker-backed E2E tests in Docker-in-Docker:

```bash
./gradlew e2eTest
```

Run a single scenario:

```bash
./gradlew e2eTest --tests '*KodkodE2eTest.updatePullsAndAdoptsNewImageDefaults'
```

Keep the dind daemon for debugging:

```bash
./gradlew e2eTest -Pkodkod.e2e.keepDind=true
```

Use the current `DOCKER_HOST` instead of dind. This is only safe on a disposable
daemon:

```bash
./gradlew e2eTest -Pkodkod.e2e.useCurrentDocker=true
```

Run the `DockerClient` contract against a real daemon (see the test matrix below for what it is and
why it needs the flag):

```bash
./gradlew e2eTest -Pkodkod.e2e.useCurrentDocker=true --tests '*DockerApiContractTest*'
```

Useful dind overrides:

```bash
./gradlew e2eTest \
  -Pkodkod.e2e.dindName=kodkod-e2e-dind \
  -Pkodkod.e2e.dindImage=docker:dind \
  -Pkodkod.e2e.dindPort=12375
```

JUnit XML and HTML reports are produced by Gradle under:

```text
build/test-results/e2eTest/
build/reports/tests/e2eTest/
```

## How update testing works

kodkod's update path is:

1. Ask the daemon for the registry digest.
2. Pull `<repo>:<tag>` only when that digest is new or unavailable.
3. Compare the local image id with the running container's image id.
4. Recreate the container when the image changed.

To test that for real, the E2E harness runs a throwaway `registry:2` on
`127.0.0.1:5000` and publishes a purpose-built image:

- `e2e/testapp/Dockerfile` bakes `APP_VARIANT` and `app.variant` into image
  defaults.
- `v1` and `v2` have different defaults.
- `broken` (`e2e/testapp/Dockerfile.broken`) creates successfully but fails to start, forcing rollback.
- `crasher` (`e2e/testapp/Dockerfile.crasher`) starts successfully and then exits a moment later — the
  case a `204` from `POST /start` cannot tell apart from a healthy start, and what the liveness gate
  exists for.

The container overrides none of those values. A successful update therefore proves
that kodkod adopted the new image defaults instead of freezing the old container's
resolved config.

## Test matrix

Listed in the order they run — the class is `@TestMethodOrder(OrderAnnotation)`, and the `@Order` on
each method is what this table's rows follow.

| # | Test method | Aspect verified | Compose file | Registry? |
|---|-------------|-----------------|--------------|-----------|
| 1 | `autohealRestartsUnhealthyContainer` | Restart an unhealthy container | `compose.autoheal.yml` | no |
| 2 | `updatePullsAndAdoptsNewImageDefaults` | Pull, recreate, adopt new image defaults | `compose.update.yml` | yes |
| 3 | `composeUpAfterKodkodUpdateKeepsTheContainer` | A repeated `compose up -d` does not recreate what kodkod updated | `compose.update.yml` | yes |
| 4 | `dependencyUpdateRestartsDependentAfterProvider` | `depends_on` ordering and dependent restart | `compose.deps.yml` | yes |
| 5 | `multiNetworkContainerIsReconnectedToEveryNetwork` | Multi-network reconnect on recreate | `compose.multinet.yml` | yes |
| 6 | `containerNetworkModeDependentIsRecreatedAfterProviderUpdate` | `network_mode: container:` rewrite, for a monitored consumer and an unlabelled sidecar | `compose.container-mode.yml` | yes |
| 7 | `failedRecreateRollsBackToRunningOriginal` | Failed recreate rolls back to original | `compose.rollback.yml` | yes |
| 8 | `digestPinnedContainerIsSkipped` | Digest-pinned containers are skipped | `compose.digest.yml` | yes |
| 9 | `monitorAllDoesNotActOnKodkodItself` | Self-protection under monitor-all | `compose.self.yml` | no |
| 10 | `anonymousVolumeIsInheritedByTheRecreatedContainer` | An anonymous volume (and its data) survives a recreate | `compose.update.yml` | yes |
| 11 | `aReplacementThatStartsAndThenDiesIsRolledBack` | The liveness gate rolls back a container that starts and then exits | `compose.rollback.yml` | yes |
| 12 | `aBackupOrphanedByAKilledKodkodIsRecoveredOnRestart` | A `_kodkod_old_` backup left by a killed process is reconciled on the next start | `compose.rollback.yml` | yes |

`FixtureWriterTest` also lives in this source set but needs no Docker: the `test` source set cannot
see `e2eTest` sources, and that is the only reason it is here.

```bash
./gradlew e2eTest --tests '*FixtureWriterTest*'
```

`DockerApiContractTest` lives here too, and is the only test in the suite that does **not** run by
default. It drives the real `DockerApi` through `DockerClientContract` — the same class
`FakeDockerClientContractTest` runs against the fake on every `./gradlew test` — so that a behaviour
the unit suite assumes about a daemon is a behaviour a daemon was made to demonstrate. `DockerApi`
speaks only to a unix socket while the suite's DinD daemon is reachable over TCP, so it needs the
local daemon and skips itself otherwise rather than testing the wrong one:

```bash
./gradlew e2eTest --tests '*DockerApiContractTest*' -Pkodkod.e2e.useCurrentDocker=true
```

It is safe to point at a working machine: every container it makes is named `kodkod-contract-*`,
nothing else is listed, renamed or removed, and each test cleans up after itself. It does pull
`busybox:1.36`, since the daemon can only create from an image it already has.

CI runs exactly that command against the runner's own daemon, before the dind suite, so the daemon
half of the contract is checked on every push rather than whenever somebody remembers the flag.

## What the JUnit suite does

The `e2eTest` source set holds three independent things, and only the first needs the dind lifecycle:

- `KodkodE2eTest` — the scenarios in the matrix above, run against Docker-in-Docker.
- `DockerApiContractTest` — the shared `DockerClient` contract against a real daemon, gated on
  `-Pkodkod.e2e.useCurrentDocker=true` (see Running).
- `FixtureWriterTest` — the fixture corpus's write/swap seam, which needs no daemon at all.

`KodkodE2eTest` owns the whole suite lifecycle; `E2eHarness` (same file) is the thin Docker CLI wrapper
it drives:

1. Starts Docker-in-Docker unless `-Pkodkod.e2e.useCurrentDocker=true`.
2. Builds `kodkod:e2e`.
3. Starts the local registry.
4. Publishes `testapp:latest = v1`.
5. Runs each scenario as a separate JUnit test.
6. Tears down compose projects, backup containers, and test images.
7. Removes the dind container unless it is kept for debugging.

Each scenario calls Docker through `ProcessBuilder` and uses polling rather than
fixed sleeps where possible. Failed Docker commands include their captured output
in the JUnit failure message.

## Recording Docker fixtures

`DockerFixtureRecorder` (in this source set) captures the **real** responses a Docker daemon gives
kodkod, so that `DockerReplayTest` in the `test` source set can replay them through the real
`DockerApi` + `Updater`/`Autoheal` with no Docker at all. Unit tests otherwise only ever see JSON we
invented ourselves; the corpus is what catches engine and compose format drift.

Each scenario sets up real containers through the harness CLI, then drives `Updater`/`Autoheal`
in-process against a `DockerApi` wrapped in a `RecordingDockerTransport`. Only the in-process API
calls are recorded — the CLI setup is not.

It is opt-in and never runs in normal CI:

```bash
./gradlew e2eTest \
  -Pkodkod.e2e.useCurrentDocker=true \
  -Pkodkod.e2e.record=true \
  --tests '*DockerFixtureRecorder*'
```

Both flags are required, and `-Pkodkod.e2e.useCurrentDocker=true` is not decorative. Without it the
harness starts Docker-in-Docker and points the CLI at it through `DOCKER_HOST`, while kodkod speaks
**only** the unix socket — so the scenario's containers would be created on the inner daemon while
the recording was taken from the host daemon. The result would be a plausible-looking corpus of a
cycle that saw nothing. The recorder therefore refuses to run when the CLI and the recorder do not
share a daemon (`recorderDaemonMismatch`), rather than silently recording the wrong one.

`DOCKER_HOST` is not the only way the two can be pointed apart: the CLI also follows `DOCKER_CONTEXT`
and whatever context `docker context use` left active, neither of which kodkod's unix-socket transport
can see. So the guard does not compare addresses — it asks both sides which daemon they are
(`docker info --format '{{.ID}}'`, once as the harness runs it and once pinned to the recorder's socket
with `-H`) and refuses unless the two ids are equal. Addresses would get it wrong in both directions:
on Docker Desktop the active context is `unix://$HOME/.docker/run/docker.sock` while the recorder reads
`/var/run/docker.sock`, which is one daemon under two names. A probe that cannot be answered at all is a
refusal too, since a corpus taken from the wrong daemon is indistinguishable from a correct one
afterwards. Set `KODKOD_DOCKER_SOCKET` to record from the daemon the CLI is actually on.

Recording is additive and versioned. Fixtures are written to:

```text
src/test/resources/docker-fixtures/<engine-version>_<compose-version>/<scenario>/
src/test/resources/docker-fixtures/<engine-version>_<compose-version>/meta.json
src/test/resources/docker-fixtures/index.json
```

Re-running rewrites the label of the engine/compose versions on the machine doing the recording and
leaves any other label alone, so a corpus recorded on a different engine keeps being exercised. Each
scenario is written to a temporary directory and swapped in only after it is complete, and
`index.json` is updated last — a failed run cannot leave a stub behind an index entry.

**Any change to a request's method, path or query obliges a re-record.** The replay key is
`"<method> <path>"`, so removing a `?t=`, adding an inspect, or adding a `?platform=` makes the
committed corpus miss and `DockerReplayTest` fail. Request *bodies* are not part of the key, so a
change to a create body needs no re-record (and is asserted on directly, not against fixtures —
recording our own output as a golden file would just self-heal on every re-record).

Review the fixture diff before committing it: only the paths and bodies you meant to change should
move. Container and image ids differ on every run, so compare manifests with the ids normalized.

The recorder's update scenarios run with four settings pinned, and the replay side has to match all
four or the corpus misses:

- `KODKOD_UPDATE_MONITOR_ALL=false`, so the recorder only ever acts on containers labelled for kodkod —
  never the developer's own running containers on the daemon it is recording from;
- `KODKOD_UPDATE_VERIFY_SECONDS=1` (as in `DockerReplayTest`), which fixes the liveness gate's probe
  count: the gate watches the whole window unless the replacement reports `healthy`, and a window of
  one second is exactly three inspects at the gate's own 500ms interval — on the recording daemon and
  on the replay alike;
- `KODKOD_UPDATE_VERIFY_HEALTH=false` (likewise), so a healthcheck that fails a beat inside that window
  cannot turn a recorded update into a recorded rollback;
- `KODKOD_UPDATE_CLEANUP=true`, which is what puts the old image's `DELETE /images/<id>` — and the
  `GET /images/<id>/json` that decides whether it may be deleted — into the recording at all.

The autoheal scenario takes the defaults (`Config.fromEnv` over an empty map).

## Manual debugging

When `-Pkodkod.e2e.keepDind=true` is used, the test output prints a `DOCKER_HOST`
value for the kept daemon. Export it to inspect state or rerun Docker commands:

```bash
export DOCKER_HOST=tcp://127.0.0.1:12375
docker ps -a
docker logs e2e-update-kodkod-1
```

To publish the test image variants by hand — the same builds `E2eHarness.publishVariant` makes:

```bash
REG=127.0.0.1:5000

docker build --target v1 --build-arg VARIANT=v1 \
  -t "$REG/testapp:latest" -t "$REG/testapp:v1" e2e/testapp
docker push "$REG/testapp:latest"

docker build --target v2 --build-arg VARIANT=v2 \
  -t "$REG/testapp:latest" -t "$REG/testapp:v2" e2e/testapp
docker push "$REG/testapp:latest"

docker build -f e2e/testapp/Dockerfile.broken \
  -t "$REG/testapp:latest" e2e/testapp
docker push "$REG/testapp:latest"

docker build -f e2e/testapp/Dockerfile.crasher \
  -t "$REG/testapp:latest" e2e/testapp
docker push "$REG/testapp:latest"
```

To manually clean up a disposable daemon:

```bash
export TESTAPP_DIGEST=sha256:0000000000000000000000000000000000000000000000000000000000000000

for f in autoheal update deps multinet container-mode rollback self digest; do
  docker compose -f "e2e/compose.$f.yml" down -v --remove-orphans || true
done
docker compose -f e2e/compose.registry.yml down -v --remove-orphans || true

docker ps -aq --filter 'name=e2e-' | xargs -r docker rm -f
docker ps -aq --filter 'name=_kodkod_old_' | xargs -r docker rm -f
docker rmi 127.0.0.1:5000/testapp:latest 127.0.0.1:5000/testapp:v1 127.0.0.1:5000/testapp:v2 || true
```

Note the missing `-v` on those two: the filter is a name *substring*, and a
`_kodkod_old_` backup is by construction the last container referencing a real
service's anonymous volumes (kodkod removes the pre-update container with
`v=false` so that data survives an update). Run against the host daemon, `-v`
there deletes it. `compose down -v` above already reclaims what the stacks
created.

To manually drop a dind left behind by `-Pkodkod.e2e.keepDind=true`, on the host
daemon (`-v` also reclaims its ~1 GB `/var/lib/docker` volume):

```bash
docker rm -f -v kodkod-e2e-dind
```

## Files

```text
E2E_TESTING.md
src/e2eTest/kotlin/io/heapy/kodkod/e2e/KodkodE2eTest.kt      # the suite + E2eHarness
src/e2eTest/kotlin/io/heapy/kodkod/e2e/DockerFixtureRecorder.kt
src/e2eTest/kotlin/io/heapy/kodkod/e2e/FixtureWriter.kt
src/e2eTest/kotlin/io/heapy/kodkod/e2e/FixtureWriterTest.kt
src/e2eTest/kotlin/io/heapy/kodkod/e2e/DockerApiContractTest.kt  # the contract, against a real daemon
src/testFixtures/kotlin/io/heapy/kodkod/DockerClientContract.kt  # shared by both contract tests
src/test/kotlin/io/heapy/kodkod/FakeDockerClientContractTest.kt  # the contract, against the fake
src/main/kotlin/io/heapy/kodkod/DockerTransport.kt           # the seam both sides plug into
src/main/kotlin/io/heapy/kodkod/UnixSocketTransport.kt       # production transport, what the recorder wraps
src/testFixtures/kotlin/io/heapy/kodkod/DockerRecording.kt   # recording/replay transports + the corpus types
src/test/kotlin/io/heapy/kodkod/DockerReplayTest.kt
src/test/resources/docker-fixtures/
e2e/
  testapp/Dockerfile
  testapp/Dockerfile.broken
  testapp/Dockerfile.crasher
  compose.registry.yml
  compose.autoheal.yml
  compose.update.yml
  compose.deps.yml
  compose.multinet.yml
  compose.container-mode.yml
  compose.rollback.yml
  compose.self.yml
  compose.digest.yml
```
