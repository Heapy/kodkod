# kodkod end-to-end tests

Unit tests under `src/test` cover pure logic: image-defaults subtraction, the
dependency graph, HTTP parsing, and config parsing. The E2E suite under
`src/e2eTest` covers what unit tests cannot: kodkod driving a real Docker daemon.

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
- `broken` creates successfully but fails to start, forcing rollback.

The container overrides none of those values. A successful update therefore proves
that kodkod adopted the new image defaults instead of freezing the old container's
resolved config.

## Test matrix

| Test method | Aspect verified | Compose file | Registry? |
|-------------|-----------------|--------------|-----------|
| `autohealRestartsUnhealthyContainer` | Restart an unhealthy container | `compose.autoheal.yml` | no |
| `updatePullsAndAdoptsNewImageDefaults` | Pull, recreate, adopt new image defaults | `compose.update.yml` | yes |
| `composeUpAfterKodkodUpdateKeepsTheContainer` | A repeated `compose up -d` does not recreate what kodkod updated | `compose.update.yml` | yes |
| `dependencyUpdateRestartsDependentAfterProvider` | `depends_on` ordering and dependent restart | `compose.deps.yml` | yes |
| `multiNetworkContainerIsReconnectedToEveryNetwork` | Multi-network reconnect on recreate | `compose.multinet.yml` | yes |
| `containerNetworkModeDependentIsRecreatedAfterProviderUpdate` | `network_mode: container:` rewrite | `compose.container-mode.yml` | yes |
| `failedRecreateRollsBackToRunningOriginal` | Failed recreate rolls back to original | `compose.rollback.yml` | yes |
| `digestPinnedContainerIsSkipped` | Digest-pinned containers are skipped | `compose.digest.yml` | yes |
| `monitorAllDoesNotActOnKodkodItself` | Self-protection under monitor-all | `compose.self.yml` | no |

## What the JUnit suite does

`KodkodE2eTest` performs the old shell setup and assertions in Kotlin:

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

## Manual debugging

When `-Pkodkod.e2e.keepDind=true` is used, the test output prints a `DOCKER_HOST`
value for the kept daemon. Export it to inspect state or rerun Docker commands:

```bash
export DOCKER_HOST=tcp://127.0.0.1:12375
docker ps -a
docker logs e2e-update-kodkod-1
```

To manually publish the test image variants without the removed helper scripts:

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

## Files

```text
E2E_TESTING.md
src/e2eTest/kotlin/io/heapy/kodkod/e2e/KodkodE2eTest.kt
e2e/
  testapp/Dockerfile
  testapp/Dockerfile.broken
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
