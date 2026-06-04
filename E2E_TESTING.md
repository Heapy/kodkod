# kodkod — end-to-end test plan

The unit tests under `src/test` cover the pure logic (image-defaults subtraction,
the dependency graph, HTTP parsing, config). This document covers what unit tests
cannot: **kodkod driving a real Docker daemon** — pulling images, recreating
containers, ordering a stack, rolling back a failed update, restarting on a
healthcheck, and refusing to touch itself.

It is both a **runbook** and an assertion suite: the `docker compose` files under
`e2e/` isolate each aspect, the sections below show the manual commands and
pass/fail checks, and `e2e/bin/run.sh` executes those checks automatically (see
[Automating](#automating)).

---

## ⚠️ Safety — read before running

Some scenarios set `KODKOD_*_MONITOR_ALL=true`. In that mode kodkod acts on **every
running container on the daemon it talks to**, not just the test ones. **Do not run
this suite against a Docker daemon that hosts anything you care about.**

Use a disposable daemon. The simplest path is the Docker-in-Docker wrapper:

```bash
./e2e/bin/dind-e2e.sh
```

or a throwaway VM / a dedicated Colima profile (`colima start kodkod-e2e`). Every
artifact this suite creates is named `e2e-*`; `e2e/bin/cleanup.sh` removes them,
and `e2e/bin/dind-e2e.sh` removes its dind container unless `KEEP=1` is set.

---

## Prerequisites

- Docker Engine + Compose v2 (`docker compose version` ≥ 2.20; developed against
  Engine 29 / Compose v5).
- ~1 GB free disk and internet access (pulls `busybox`, `registry:2`, and the JDK
  base images for the kodkod build).
- The repository checked out; run all commands from the repo root.

---

## How update testing works (the local registry)

kodkod's update path is, faithfully: *ask the daemon for the registry digest, pull
`<repo>:<tag>` only when that digest is new or unavailable, compare the local image id
with the running container's, and recreate if it changed.* To exercise that for real
you need a registry whose tag you can move. So the harness runs a throwaway
**`registry:2` on `127.0.0.1:5000`** and a purpose-built image:

- **`e2e/testapp/Dockerfile`** bakes `APP_VARIANT` (env) and `app.variant` (label)
  into the image **defaults**. The running container overrides neither — so an
  update is only visible if kodkod adopts the **new** image's defaults instead of
  freezing the old container's resolved config. That is precisely the
  *subtract-old-image-defaults* behaviour (plan fix 1).
- **Variants:** `v1` and `v2` (different defaults) and `broken` (an entrypoint that
  can't exec → the container *starts* fail, used to force a rollback).

`127.0.0.1` is an insecure registry by default, so no daemon config is needed.

---

## Quick start

Fast path: run the assertion suite against an isolated Docker-in-Docker daemon.
The host Docker daemon is used only to start the dind container; the build,
registry, kodkod, and all test stacks run inside that disposable daemon.

```bash
# Run all scenarios, then remove the dind container
./e2e/bin/dind-e2e.sh

# Run only selected scenarios
./e2e/bin/dind-e2e.sh update rollback

# Keep the dind daemon for debugging or re-runs
KEEP=1 ./e2e/bin/dind-e2e.sh self
```

`dind-e2e.sh` runs `setup.sh`, then `run.sh`, then `cleanup.sh`. With `KEEP=1`,
it leaves the dind container up and prints the `DOCKER_HOST` to reuse.

Manual path, useful when you are already on a disposable daemon and want to step
through a scenario:

```bash
# 1. Build kodkod:e2e, start the registry, publish testapp:latest = v1
./e2e/bin/setup.sh

# 2. Run the scripted assertions, or follow a scenario manually below
./e2e/bin/run.sh update
# docker compose -f e2e/compose.update.yml up -d
# docker logs -f e2e-update-kodkod-1      # watch cycles; Ctrl-C to detach

# 3. Tear everything down
./e2e/bin/cleanup.sh
```

Compose names containers `<project>-<service>-<n>`, e.g. `e2e-update-app-1`,
`e2e-update-kodkod-1`. The scenarios reference those names.

Intervals in the compose files are deliberately short (8–15 s) so a cycle lands
quickly; "wait for a cycle" below means ~`interval + start_period + a few seconds`.

---

## Test matrix

| ID | Aspect verified | Plan fix | Compose file | Registry? |
|----|-----------------|----------|--------------|-----------|
| A  | Restart an unhealthy container | autoheal | `compose.autoheal.yml` | no |
| U  | Pull → recreate → **adopt new image defaults** | 1 | `compose.update.yml` | yes |
| D  | **depends_on** ordering + dependent restart | 5 | `compose.deps.yml` | yes |
| N  | **Multi-network** reconnect on recreate | 4 | `compose.multinet.yml` | yes |
| C  | **`network_mode: container:`** rewritten to a name | 4 | `compose.container-mode.yml` | yes |
| R  | **Rollback** leaves the original running | 2 | `compose.rollback.yml` | yes |
| S  | **Self-protection** under monitor-all + custom hostname | 3 | `compose.self.yml` | no |
| P  | **Digest-pinned** containers are skipped | — | `compose.digest.yml` | yes |

A and S need no registry when run manually. The rest do; run
`./e2e/bin/setup.sh` first, or use `./e2e/bin/dind-e2e.sh` and let it prepare the
whole suite.

Helper for the registry scenarios: `e2e/bin/publish.sh {v1|v2|broken}` builds and
pushes that variant to `testapp:latest`.

---

## Scenario A — Autoheal restarts an unhealthy container

**Proves:** an `unhealthy` container with `kodkod.autoheal.enable=true` is restarted.

```bash
docker compose -f e2e/compose.autoheal.yml up -d

# wait until healthy, then record the current start time
sleep 8
docker inspect -f '{{.State.Health.Status}}' e2e-autoheal-app-1   # -> healthy
BEFORE=$(docker inspect -f '{{.State.StartedAt}}' e2e-autoheal-app-1)

# break the healthcheck
docker exec e2e-autoheal-app-1 rm -f /tmp/healthy

# wait for it to go unhealthy (~6-9s) and for kodkod to act (interval 5s)
sleep 20
AFTER=$(docker inspect -f '{{.State.StartedAt}}' e2e-autoheal-app-1)

echo "before=$BEFORE"; echo "after =$AFTER"
docker inspect -f '{{.State.Health.Status}}' e2e-autoheal-app-1   # -> healthy again
```

**Pass:** `AFTER` > `BEFORE` (StartedAt advanced — it was restarted) and Health is
back to `healthy`. `docker logs e2e-autoheal-kodkod-1` shows
`unhealthy — restarting` then `restart successful`.

**Teardown:** `docker compose -f e2e/compose.autoheal.yml down -v`

---

## Scenario U — Update pulls and adopts the new image's defaults

**Proves:** plan fix 1 — the recreated container reflects v2's baked-in env/label
and healthcheck defaults, not v1's, while preserving a service-level healthcheck
interval override.

```bash
./e2e/bin/publish.sh v1                       # ensure :latest = v1
docker compose -f e2e/compose.update.yml up -d
sleep 5
OLD_IMG=$(docker inspect -f '{{.Image}}' e2e-update-app-1)
docker inspect -f '{{index .Config.Labels "app.variant"}}' e2e-update-app-1  # -> v1

# publish the "new release"
./e2e/bin/publish.sh v2

# wait for a kodkod update cycle (interval 15s + start 5s)
sleep 30
docker inspect -f '{{index .Config.Labels "app.variant"}}' e2e-update-app-1  # -> v2
docker inspect -f '{{.Image}}' e2e-update-app-1                              # != OLD_IMG
docker inspect -f '{{range .Config.Healthcheck.Test}}{{.}} {{end}}' e2e-update-app-1
# -> contains /tmp/healthy-v2
docker inspect -f '{{json .Config.Healthcheck.Interval}}' e2e-update-app-1    # -> 5000000000
docker inspect -f '{{json .Config.Healthcheck.Timeout}}' e2e-update-app-1     # -> 4000000000
docker inspect -f '{{json .Config.Healthcheck.Retries}}' e2e-update-app-1     # -> 4
docker logs e2e-update-app-1 | tail -n1                                      # "live variant=v2"
```

**Pass:** `app.variant` flipped to **v2**, the image id changed, and the container
log prints `variant=v2`. The healthcheck command points at v2, timeout/retries are
v2 defaults, and the compose interval override remains 5s. (If kodkod had copied
the old resolved config verbatim, these defaults would still read `v1`.)

**Teardown:** `docker compose -f e2e/compose.update.yml down -v`

---

## Scenario D — Dependency ordering and dependent restart

**Proves:** plan fix 5 — `web depends_on db`; updating `db` recreates it and
restarts `web` **after** it.

```bash
./e2e/bin/publish.sh v1
docker compose -f e2e/compose.deps.yml up -d
sleep 5

./e2e/bin/publish.sh v2          # db's image changes; web's (busybox) does not
sleep 30

DB_IMG=$(docker inspect -f '{{index .Config.Labels "app.variant"}}' e2e-deps-db-1)
DB_START=$(docker inspect -f '{{.State.StartedAt}}' e2e-deps-db-1)
WEB_START=$(docker inspect -f '{{.State.StartedAt}}' e2e-deps-web-1)

echo "db variant=$DB_IMG"
echo "db  started $DB_START"
echo "web started $WEB_START"
```

**Pass:**
- `db variant` = **v2** (db was recreated on the new image);
- both `StartedAt` values are recent (web was restarted even though its own image
  didn't change — it's a dependent);
- `WEB_START` > `DB_START` (start order is forward: db came up first, then web).

`docker logs e2e-deps-kodkod-1` shows `web` reported as
`restarted (a dependency was updated)`.

> StartedAt is RFC3339 UTC, so a plain string comparison orders correctly.

**Teardown:** `docker compose -f e2e/compose.deps.yml down -v`

---

## Scenario N — Multi-network container is reconnected to every network

**Proves:** plan fix 4 — a container on two user networks is recreated attached to
**both** (create takes the first endpoint, the rest are connected before start).

```bash
./e2e/bin/publish.sh v1
docker compose -f e2e/compose.multinet.yml up -d
sleep 5
docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}' e2e-multinet-app-1
#   -> e2e-multinet_neta e2e-multinet_netb

./e2e/bin/publish.sh v2
sleep 30
docker inspect -f '{{index .Config.Labels "app.variant"}}' e2e-multinet-app-1   # -> v2
docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}' e2e-multinet-app-1
```

**Pass:** after the update the container reports **both** `e2e-multinet_neta` and
`e2e-multinet_netb`, and `app.variant` = v2 (it really was recreated, not just left
alone).

**Teardown:** `docker compose -f e2e/compose.multinet.yml down -v`

---

## Scenario C — `network_mode: container:` dependent is recreated after provider update

**Proves:** plan fixes 4 and 5 together — when kodkod recreates a provider
container, a monitored container using `network_mode: service:<provider>` is
recreated too, and its `container:<old-id>` network mode is rewritten to the
provider's name before the old provider id is removed.

```bash
./e2e/bin/publish.sh v1
docker compose -f e2e/compose.container-mode.yml up -d
sleep 5
OLD_PROVIDER=$(docker inspect -f '{{.Id}}' e2e-cmode-provider-1)
OLD_CONSUMER=$(docker inspect -f '{{.Id}}' e2e-cmode-consumer-1)
# initially compose has resolved the consumer's network mode to the provider's id:
docker inspect -f '{{.HostConfig.NetworkMode}}' e2e-cmode-consumer-1   # container:<hex id>

./e2e/bin/publish.sh v2          # provider's image changes -> consumer is recreated as a dependent
sleep 30
docker inspect -f '{{index .Config.Labels "app.variant"}}' e2e-cmode-provider-1  # -> v2
docker inspect -f '{{.Id}}' e2e-cmode-provider-1    # != OLD_PROVIDER
docker inspect -f '{{.Id}}' e2e-cmode-consumer-1    # != OLD_CONSUMER
docker inspect -f '{{.HostConfig.NetworkMode}}' e2e-cmode-consumer-1
#   -> container:<current provider id>  (Docker normalizes the name back to an id)
docker inspect -f '{{.State.Running}}' e2e-cmode-consumer-1            # -> true
```

**Pass:** provider is **v2**, both provider and consumer container ids changed,
consumer is running, and `NetworkMode` points at the current provider id. Docker
stores the resolved id in `HostConfig.NetworkMode`, so inspect will not keep the
name string even when kodkod created the replacement with
`container:e2e-cmode-provider-1`.

**Teardown:** `docker compose -f e2e/compose.container-mode.yml down -v`

---

## Scenario R — Rollback never leaves the container stopped

**Proves:** plan fix 2 — a failure *after* the container is stopped restores the
original, running container.

```bash
./e2e/bin/publish.sh v1
docker compose -f e2e/compose.rollback.yml up -d
sleep 5
docker inspect -f '{{.State.Running}} {{index .Config.Labels "app.variant"}}' e2e-rollback-app-1
#   -> true v1

# publish a release that CREATES fine but FAILS to start
./e2e/bin/publish.sh broken
sleep 30

docker inspect -f '{{.State.Running}}' e2e-rollback-app-1                      # -> true
docker inspect -f '{{index .Config.Labels "app.variant"}}' e2e-rollback-app-1 # -> v1
docker logs e2e-rollback-kodkod-1 | grep -i 'rolling back'
docker ps -a --filter 'name=_kodkod_old_' --format '{{.Names}}'               # -> (empty)
```

**Pass:** app is **still running on v1** (the broken image was not adopted), kodkod
logged `recreate failed — rolling back`, and no `*_kodkod_old_*` backup container is
left behind. kodkod retries every cycle while `broken` is the published tag —
publish `v1` again to make it settle.

**Teardown:** `docker compose -f e2e/compose.rollback.yml down -v`

---

## Scenario S — kodkod refuses to act on itself

**Proves:** plan fix 3 — under `MONITOR_ALL=true` and a custom `hostname:`, kodkod
still skips its own container via the baked-in `io.heapy.kodkod.self` label.

> ⚠️ monitor-all — run on a disposable daemon only (see [Safety](#-safety--read-before-running)).

```bash
docker compose -f e2e/compose.self.yml up -d
sleep 3
docker inspect -f '{{index .Config.Labels "io.heapy.kodkod.self"}}' e2e-self-kodkod-1  # -> true
docker inspect -f '{{.Config.Hostname}}' e2e-self-kodkod-1                              # -> kodkod-custom-host
BEFORE=$(docker inspect -f '{{.State.StartedAt}}' e2e-self-kodkod-1)
DUMMY_BEFORE=$(docker inspect -f '{{.State.StartedAt}}' e2e-self-dummy-1)

# let several cycles run
sleep 35
AFTER=$(docker inspect -f '{{.State.StartedAt}}' e2e-self-kodkod-1)
DUMMY_AFTER=$(docker inspect -f '{{.State.StartedAt}}' e2e-self-dummy-1)
echo "before=$BEFORE"; echo "after =$AFTER"
echo "dummy before=$DUMMY_BEFORE"; echo "dummy after =$DUMMY_AFTER"
docker ps --format '{{.Names}}'    # kodkod + dummy still there; no kodkod_old backups
```

**Pass:** `AFTER` == `BEFORE` (kodkod never restarted/recreated itself) even though
its `Hostname` is not its container id and its healthcheck is failing. The
`dummy` container's `StartedAt` advances because monitor-all autoheal restarted it,
confirming cycles actually executed while self-protection skipped kodkod itself.

**Teardown:** `docker compose -f e2e/compose.self.yml down -v`

---

## Scenario P — Digest-pinned containers are skipped

**Proves:** a container pinned to `image@sha256:...` is never updated.

```bash
./e2e/bin/publish.sh v1
export TESTAPP_DIGEST=$(docker inspect -f '{{index .RepoDigests 0}}' \
    127.0.0.1:5000/testapp:latest | cut -d@ -f2)
echo "pinning $TESTAPP_DIGEST"

docker compose -f e2e/compose.digest.yml up -d
sleep 3
OLD_IMG=$(docker inspect -f '{{.Image}}' e2e-digest-app-1)

# move :latest forward — the pin must ignore it
./e2e/bin/publish.sh v2
sleep 25

docker inspect -f '{{.Image}}' e2e-digest-app-1   # == OLD_IMG (unchanged)
docker logs e2e-digest-kodkod-1 | grep -i 'digest-pinned'
```

**Pass:** the image id is unchanged and kodkod logged `digest-pinned … skipping`.
(`TESTAPP_DIGEST` must stay exported for `down` too, or use `cleanup.sh`.)

**Teardown:** `docker compose -f e2e/compose.digest.yml down -v`

---

## Automating

The Bash assertion runner already exists:

```bash
./e2e/bin/run.sh                 # all scenarios against the current DOCKER_HOST
./e2e/bin/run.sh update rollback # selected scenarios
```

`run.sh` assumes `setup.sh` has already built `kodkod:e2e`, started the local
registry, and pushed `testapp:latest = v1`. It replaces fixed sleeps with poll
loops where possible, prints per-check `PASS`/`FAIL` lines, and exits non-zero on
any failed assertion.

For CI or for local runs that must not touch the host Docker daemon, use the dind
wrapper:

```bash
./e2e/bin/dind-e2e.sh                 # setup + run + cleanup in Docker-in-Docker
./e2e/bin/dind-e2e.sh update rollback # same, but only selected scenarios
KEEP=1 ./e2e/bin/dind-e2e.sh          # keep the daemon for debugging
```

Remaining automation work is reporting polish, not the runner itself: optional
TAP/JUnit output for CI annotations, or a heavier Testcontainers/Gradle
`integrationTest` source set if JVM-native assertions become worth the extra
maintenance.

CI note: the kodkod image build runs Gradle inside the build stage (minutes on a
cold cache). Build `kodkod:e2e` once and cache it; keep `registry:2`/`busybox`
layers cached between runs.

---

## Cleanup

```bash
./e2e/bin/cleanup.sh
```

Downs every `e2e-*` project and the registry, removes containers kodkod recreated
and any `*_kodkod_old_*` backups, and drops `127.0.0.1:5000/testapp:latest`. The
`kodkod:e2e` and base images are kept so re-runs are fast. To reclaim those:
`docker rmi kodkod:e2e registry:2 busybox:1.36`.

---

## Files

```
E2E_TESTING.md                       this runbook
e2e/
  testapp/Dockerfile                 v1/v2 test image (defaults baked into env+label)
  testapp/Dockerfile.broken          start-failing image (rollback)
  compose.registry.yml               local registry:2 on 127.0.0.1:5000
  compose.autoheal.yml      (A)      healthcheck restart
  compose.update.yml        (U)      pull + recreate + image-defaults adoption
  compose.deps.yml          (D)      depends_on ordering + dependent restart
  compose.multinet.yml      (N)      multi-network reconnect
  compose.container-mode.yml(C)      network_mode: container: resolution
  compose.rollback.yml      (R)      rollback on failed recreate
  compose.self.yml          (S)      self-protection under monitor-all
  compose.digest.yml        (P)      digest-pinned skip
  bin/setup.sh                       build kodkod:e2e, start registry, push v1
  bin/run.sh                         assertion runner for all or selected scenarios
  bin/dind-e2e.sh                    isolated Docker-in-Docker wrapper around setup/run/cleanup
  bin/publish.sh {v1|v2|broken}      build+push a variant to testapp:latest
  bin/cleanup.sh                     tear everything down
```
