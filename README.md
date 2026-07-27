<p align="center">
  <img src="logo/kodkod-logo-simple.svg" alt="Kodkod Logo" width="200">
</p>

# Kodkod

A tiny **docker-compose companion** that does two things and nothing else:

1. **Restarts unhealthy containers** — like [`docker-autoheal`](https://github.com/tmknight/docker-autoheal), it watches container health and restarts containers that go `unhealthy`. Containers sharing the restarted container's network namespace (or linked to it) are restarted with it, and a container that stays unhealthy is retried with a growing backoff instead of every cycle.
2. **Auto-updates containers** — like Watchtower, it pulls the image tag a container runs and recreates the container (preserving its config, env, labels and networks) when a newer image is published.

Both jobs talk **directly to the Docker Engine API over the unix socket** — no `docker` CLI, no compose CLI, no extra tooling in the image. It is written in Kotlin with a single runtime dependency (a JSON library) and ships as a small Liberica JRE Alpine image.

Everything is **opt-in per container via labels**, so kodkod only ever touches the containers you mark.

## Quick start

Add kodkod to your `docker-compose.yml` and label the services you want it to manage:

```yaml
services:
  app:
    image: ghcr.io/you/app:latest
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
      interval: 10s
      retries: 3
    labels:
      kodkod.autoheal.enable: "true"   # restart this container when it goes unhealthy
      kodkod.update.enable: "true"     # recreate it when ghcr.io/you/app:latest changes

  kodkod:
    image: ghcr.io/heapy/kodkod:latest
    restart: always
    # Docker SIGKILLs 10s after SIGTERM unless told otherwise, and a recreate in flight can be between
    # renaming the old container away and starting its replacement — the one moment nothing is serving
    # the name. Give kodkod more than its KODKOD_SHUTDOWN_GRACE (30s) so it can finish instead.
    stop_grace_period: 45s
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
      # optional: match host time in logs
      - /etc/localtime:/etc/localtime:ro
```

> **Autoheal requires a `HEALTHCHECK`** on the target image/service — kodkod can only restart what Docker reports as `unhealthy`. See the [Docker docs](https://docs.docker.com/reference/dockerfile/#healthcheck).

## Labels

Labels live under a namespace (default `kodkod`, configurable via `KODKOD_LABEL_NAMESPACE`).

| Label                    | Default | Description                                                                                 |
|--------------------------|---------|---------------------------------------------------------------------------------------------|
| `kodkod.autoheal.enable` | `false` | Restart this container when it becomes `unhealthy`.                                         |
| `kodkod.update.enable`   | `false` | Pull its image tag and recreate the container when the image changes.                       |
| `kodkod.stop.timeout`    | —       | Per-container stop timeout (seconds) for restart/recreate. Overrides `KODKOD_STOP_TIMEOUT`. |
| `kodkod.depends-on`      | —       | Comma-separated container/service names this one depends on, for update ordering. Only needed outside compose — compose stacks are ordered automatically (see below). |

When `*_MONITOR_ALL` is enabled (see below) the relevant feature applies to **all** containers and the label flips to an opt-**out** (`...enable=false` to exclude).

## Configuration

All configuration is via environment variables:

| Variable                       | Default                  | Description                                                            |
|--------------------------------|--------------------------|------------------------------------------------------------------------|
| `KODKOD_DOCKER_SOCKET`         | `/var/run/docker.sock`   | Path to the Docker Engine unix socket.                                 |
| `KODKOD_LABEL_NAMESPACE`       | `kodkod`                 | Prefix for all labels (e.g. `kodkod.autoheal.enable`).                 |
| `KODKOD_STOP_TIMEOUT`          | —                        | Default stop timeout (seconds) for restart/recreate. Unset: no `t` is sent at all, so each container's own stop timeout (`stop_grace_period`) applies. |
| `KODKOD_SHUTDOWN_GRACE`        | `30`                     | Seconds a stopping kodkod gives the cycle in flight to finish before interrupting it. The outer deadline is still yours: Docker sends `SIGKILL` 10s after `SIGTERM` unless kodkod's own `stop_grace_period` says otherwise. |
| `KODKOD_AUTOHEAL_ENABLED`      | `true`                   | Enable the autoheal loop.                                              |
| `KODKOD_AUTOHEAL_INTERVAL`     | `30`                     | Seconds between unhealthy-container checks.                            |
| `KODKOD_AUTOHEAL_MAX_INTERVAL` | `3600`                   | Ceiling of the per-container backoff between restarts of a container that stays unhealthy. The wait doubles from `KODKOD_AUTOHEAL_INTERVAL` up to this value; setting it *to* the interval restores retry-every-cycle. Never below the interval. |
| `KODKOD_AUTOHEAL_START_PERIOD` | `0`                      | Seconds to wait before the first autoheal check.                      |
| `KODKOD_AUTOHEAL_MONITOR_ALL`  | `false`                  | Heal **all** containers with a healthcheck (label becomes opt-out).    |
| `KODKOD_UPDATE_ENABLED`        | `true`                   | Enable the auto-update loop. Orphaned `_kodkod_old_` backups are still reconciled at startup even when this is off. |
| `KODKOD_UPDATE_INTERVAL`       | `3600`                   | Seconds between image-update checks.                                   |
| `KODKOD_UPDATE_START_PERIOD`   | `0`                      | Seconds to wait before the first update check.                        |
| `KODKOD_UPDATE_MONITOR_ALL`    | `false`                  | Update **all** running containers (label becomes opt-out).            |
| `KODKOD_UPDATE_CLEANUP`        | `true`                   | Remove the previous image after a successful update. Skipped when that image still carries a tag other than the one just updated, so a pinned rollback tag (`app:1.26`) is never untagged. |
| `KODKOD_UPDATE_VERIFY_SECONDS` | `15`                     | How long a replacement container is watched after `start` before the container and image it replaced are destroyed. Three consecutive good probes end the wait early; `0` means one probe and move on. |
| `KODKOD_UPDATE_VERIFY_HEALTH`  | `true`                   | Treat a replacement that has already *failed* its healthcheck as a failed update. A container still inside its `start_period` (`Health=starting`) is always accepted. |
| `KODKOD_UPDATE_FAILURE_COOLDOWN` | `21600`                | Seconds an image that failed to come up on a container is left alone before that exact update is tried again — otherwise an unstartable `:latest` costs one self-inflicted outage per cycle, forever. A replacement that ran and did not stay up counts on the first failure; a `start` the daemon refused outright counts only on the second in a row, since that answer is a host problem (a port still in teardown, a resource limit) as often as an image one. `0` disables the memory. |
| `KODKOD_DEPENDENCY_HEALTH_TIMEOUT` | `120`                | Seconds a container waits for a dependency marked `condition: service_healthy` to become healthy before starting anyway. `0` means check once and carry on. |
| `KODKOD_RESPECT_DEPENDS_ON_RESTART` | `false`             | Obey compose's `depends_on[*].restart: false` when deciding whether to restart a dependent (see below).  |
| `KODKOD_REGISTRY_AUTH`         | —                        | Base64 `X-Registry-Auth` value for pulling from private registries.    |

## How updates work

For each container marked for updates, kodkod:

1. reads its image reference (e.g. `nginx:1.27`) — containers pinned to a digest (`image@sha256:...`) are skipped;
2. asks the registry for the tag's manifest digest and skips the pull when it already matches the
   running image;
3. pulls that repo/tag only when the digest is new or unavailable, then compares the local image id
   with the container's current image id;
4. if they differ, **recreates** the container against the new image:
   stop → rename old → create new → reconnect networks → start → verify it stayed up → remove old.

When rebuilding the new container, kodkod starts from the running container's configuration but
**subtracts the old image's defaults** (env, entrypoint, cmd, healthcheck, …), keeping only the
settings you actually overrode. This way a new image that changes its own defaults is genuinely
adopted instead of being masked by the old image's baked-in values.

What carries over to the replacement:

- the container's `HostConfig` — ports, restart policy, resource limits, binds, capabilities, and
  the rest — verbatim, with two deliberate edits: `network_mode: container:<id>` is rewritten to the
  target's **name**, and the volumes the container is actually using are re-attached explicitly;
- **anonymous volumes**, by name. They are only named in the container's top-level `Mounts[]`, so
  they are resolved from there and mounted explicitly — without that the replacement would get a
  fresh, empty volume and the old data would be orphaned;
- **labels**, minus the ones the old image itself declared with the same value (those come back from
  the new image), and with `com.docker.compose.image` restamped to the id kodkod just resolved the
  tag to — otherwise the next `docker compose up` would consider the container stale and recreate it;
- **every attached network**, with each endpoint's aliases, static `IPAMConfig`, links, driver
  options and gateway priority. Host, `none` and `container:` network modes attach no endpoint;
- the image **platform** (`os/arch`, without the manifest variant) on both the pull and the create.

Nothing irreversible happens on the strength of a `204` from `POST /start`: the replacement is
probed for up to `KODKOD_UPDATE_VERIFY_SECONDS` and only then are the old container and image
released. A replacement that exits, crash-loops or (with `KODKOD_UPDATE_VERIFY_HEALTH`) reports
`unhealthy` is discarded and the previous container is put back.

If anything fails after the container is stopped, kodkod rolls the service back: it removes the
failed replacement, frees the service name if a corpse is still holding it, renames the original
back, starts it, and then **inspects it to confirm** it is running under its own name again. Every
step that fails is logged, and a rollback that did not land says `ROLLBACK INCOMPLETE` at `ERROR`
rather than reporting success. If kodkod is killed mid-recreate, the `_kodkod_old_` backup it left
behind is reconciled — restoring it when nothing is serving the name, removing it when the
replacement is running. That reconcile runs at startup *and* at the head of every update cycle, and
it is not gated on `KODKOD_UPDATE_ENABLED`: an orphan is a service that is down right now.

The awkward case is a backup whose service name is held by a container that is **stopped**, and it is
decided by that container's own uptime against `max(KODKOD_UPDATE_VERIFY_SECONDS, 60s)` — the same
window a replacement has to survive to be accepted:

- it ran at least that long before it stopped: both containers are left alone and the situation is
  reported, because that shape is also what "the update went through and an operator stopped the
  result" looks like. So is a holder the daemon reports no run times for;
- it never proved that much (including never having been started at all): that is what a kodkod killed
  inside the liveness gate leaves behind, so the holder is **removed** and the backup takes the name
  back. The removal is logged at `ERROR`, naming the container and the threshold it missed, and sends
  `v=false` so the container's volumes survive it. Getting this wrong costs one container and is undone
  by the next cycle; the opposite mistake leaves a crashing replacement on the name until a human
  notices.

### Limitations

- An explicitly requested MAC address (`docker run --mac-address`, compose `mac_address:`) is **not**
  carried over on Docker >= 27. The engine no longer returns `Config.MacAddress` in container
  inspect, and `NetworkSettings.Networks[*].MacAddress` is populated for every container whether or
  not the address was asked for — so kodkod cannot tell a user-set MAC from a daemon-generated one
  and deliberately pins neither. The replacement gets a fresh generated MAC. On engines that still
  report `Config.MacAddress`, an explicit MAC is preserved.
- The health branch of the liveness gate (`Health=unhealthy` / `Health=starting`) is covered by unit
  tests only. The recorded-fixture and end-to-end runs go with `KODKOD_UPDATE_VERIFY_HEALTH=false`,
  because the number of probes is otherwise a race between the probe interval and the replacement's
  own healthcheck.
- With `KODKOD_UPDATE_VERIFY_HEALTH=true` (the default), a replacement reporting `Health=starting`
  never satisfies the early exit, so a service with a long `start_period` pays the **full**
  `KODKOD_UPDATE_VERIFY_SECONDS` — under the cycle lock, which also holds off autoheal for that long.
  Shorten the window, or turn the health check of the gate off, if that matters more than the check.
- Dependents that live **outside** the provider's compose project are only found when the project
  contains at least one create-time dependent of its own. The scan starts narrowed to the provider's
  `com.docker.compose.project` and widens to the whole daemon only when that narrow look finds
  something; a stack with no in-project `network_mode: container:` relation does not pay for a full
  listing on every restart, and an out-of-project sidecar joined to it is therefore not seen.
- kodkod's memories are **in-process only**, and one of them matters more than the rest. The
  failed-update cooldown (`KODKOD_UPDATE_FAILURE_COOLDOWN`) and the autoheal restart backoff are merely
  forgotten on a restart: an image that cannot start is retried on the next cycle, and a flapping
  container's backoff starts again from `KODKOD_AUTOHEAL_INTERVAL`. The list of **stranded create-time
  dependents** — containers kodkod stopped for a recreate and could neither rebuild nor roll back — is
  the dangerous one, because that list is the only thing that will ever look at those containers again:
  they are stopped under their own name, and discovery lists running containers only. A kodkod that
  restarts before such a container is serving again never comes back to it. There is no way to fix
  that from the daemon's side — the container is the *original*, not one kodkod created, Docker cannot
  label a container that already exists, and the netns id it carries names something that no longer
  exists — so the `ERROR` that reports it says so out loud and names the command to run by hand.

### Ordering & dependencies

kodkod updates the whole monitored set together so it can respect dependencies:

- containers are **stopped in reverse dependency order** and brought back in **forward order**;
- a container that **depends on an updated one is restarted too**, even if its own image didn't change;
- create-time dependents (`--link` and `network_mode: container:`) are **recreated** instead of only
  restarted, so Docker refreshes those references against the new dependency container. This also
  applies to dependents kodkod does not monitor itself — a sidecar sharing a recreated container's
  network namespace would otherwise be left on a namespace that no longer exists — and it follows
  chains, since recreating that sidecar tears down *its* namespace in turn. See Limitations for the
  one case this scan does not cover;
- `network_mode: container:<id>` is rewritten to the target's **name** so it survives that container
  being recreated;
- a container whose create-time dependent has an update pending of its own (or is inside the cooldown
  of one that failed) **waits a cycle**. That dependent's forced recreate is built from its image ref,
  which no longer names the image it is running, and once the provider's old container is gone a
  failed recreate has nothing to roll back to: the dependent's original container is joined to a
  namespace that no longer exists. So the dependent updates alone first — where a failure *can* be
  rolled back — and the provider follows on a later cycle. The delay is logged every cycle;
- a create-time dependent kodkod could neither recreate nor roll back is **brought back on every later
  cycle of this process** until it serves again: it is the one container nothing else would ever look
  at, since discovery lists running containers only. What that takes is checked against the daemon each
  cycle — a `start` while the namespace it names is still its provider's, a rebuild against the
  provider's name once that container is gone (which a later cycle can do at any time: a stopped
  dependent no longer holds its provider back). The retry does **not** survive a restart of kodkod
  itself, and the `ERROR` reporting the container says so and names the manual fix — see Limitations;
- a dependency compose marked `condition: service_healthy` is **waited for** before its dependent is
  started, bounded by `KODKOD_DEPENDENCY_HEALTH_TIMEOUT`. Only dependencies this cycle brought back
  are waited for, and the timeout starts the dependent anyway: the condition may delay a container,
  never keep it down.

Dependencies are detected automatically from Docker Compose's own `com.docker.compose.depends_on`
labels. Outside compose, declare them with the `kodkod.depends-on` label, classic `--link`, or
`network_mode: container:`.

#### `depends_on.restart` is opt-in

Compose's `depends_on` entries carry three fields (`<service>:<condition>:<restart>`). kodkod always
honours `condition`; it obeys `restart` only when `KODKOD_RESPECT_DEPENDS_ON_RESTART=true`.

The reason is that the two mean different things. Compose's `restart` field governs whether
`docker compose restart` **propagates** to a dependent. kodkod does something else: it replaces the
dependency with a **new container**, holding a new IP and a new DNS record — precisely the case where
a dependent usually does need to be restarted. And since `restart: false` is compose's own default,
obeying it by default would turn kodkod's documented dependent-restart into a no-op for essentially
every stack.

Even with the flag on, `restart: false` can only suppress a plain **restart**. A create-time
dependent (`--link`, `network_mode: container:`) is always recreated, because leaving it alone would
leave it pointing at a dead network namespace.

The kodkod container never updates or restarts **itself** — it recognises its own container by the
`io.heapy.kodkod.self` label baked into the image (independent of `HOSTNAME`, so a custom `hostname:`
won't fool it).

## Build from source

Requires JDK 25+ (the Gradle 9.5.1 wrapper and Kotlin 2.4.0 are pinned):

```bash
./gradlew installDist          # build/install/kodkod/bin/kodkod
./gradlew build                # compile + checks

docker build -t kodkod .       # build the Liberica JRE image
```

## Security notes

- kodkod needs read/write access to the Docker socket to inspect, restart and recreate containers — this is effectively root on the host, so run it only where you trust the workloads.
- Mount the socket and run nothing else in the container; the image contains only a JRE and kodkod.
- Keep `KODKOD_*_MONITOR_ALL=false` (the default) and use per-container labels to keep the blast radius small.

## Credits

The autoheal behaviour mirrors the excellent
[`docker-autoheal`](https://github.com/tmknight/docker-autoheal) (Rust) and
[`willfarrell/docker-autoheal`](https://github.com/willfarrell/docker-autoheal) (shell) projects.

## License

Apache-2.0 — see [LICENSE](LICENSE).
