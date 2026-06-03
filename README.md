<p align="center">
  <img src="logo/kodkod-logo-simple.svg" alt="Kodkod Logo" width="200">
</p>

# Kodkod

A tiny **docker-compose companion** that does two things and nothing else:

1. **Restarts unhealthy containers** — like [`docker-autoheal`](https://github.com/tmknight/docker-autoheal), it watches container health and restarts containers that go `unhealthy`.
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

When `*_MONITOR_ALL` is enabled (see below) the relevant feature applies to **all** containers and the label flips to an opt-**out** (`...enable=false` to exclude).

## Configuration

All configuration is via environment variables:

| Variable                       | Default                  | Description                                                            |
|--------------------------------|--------------------------|------------------------------------------------------------------------|
| `KODKOD_DOCKER_SOCKET`         | `/var/run/docker.sock`   | Path to the Docker Engine unix socket.                                 |
| `KODKOD_LABEL_NAMESPACE`       | `kodkod`                 | Prefix for all labels (e.g. `kodkod.autoheal.enable`).                 |
| `KODKOD_STOP_TIMEOUT`          | `10`                     | Default stop timeout (seconds) for restart/recreate.                   |
| `KODKOD_AUTOHEAL_ENABLED`      | `true`                   | Enable the autoheal loop.                                              |
| `KODKOD_AUTOHEAL_INTERVAL`     | `30`                     | Seconds between unhealthy-container checks.                            |
| `KODKOD_AUTOHEAL_START_PERIOD` | `0`                      | Seconds to wait before the first autoheal check.                      |
| `KODKOD_AUTOHEAL_MONITOR_ALL`  | `false`                  | Heal **all** containers with a healthcheck (label becomes opt-out).    |
| `KODKOD_UPDATE_ENABLED`        | `true`                   | Enable the auto-update loop.                                           |
| `KODKOD_UPDATE_INTERVAL`       | `3600`                   | Seconds between image-update checks.                                   |
| `KODKOD_UPDATE_START_PERIOD`   | `0`                      | Seconds to wait before the first update check.                        |
| `KODKOD_UPDATE_MONITOR_ALL`    | `false`                  | Update **all** running containers (label becomes opt-out).            |
| `KODKOD_UPDATE_CLEANUP`        | `true`                   | Remove the previous image after a successful update (best-effort).     |
| `KODKOD_REGISTRY_AUTH`         | —                        | Base64 `X-Registry-Auth` value for pulling from private registries.    |

## How updates work

For each container marked for updates, kodkod:

1. reads its image reference (e.g. `nginx:1.27`) — containers pinned to a digest (`image@sha256:...`) are skipped;
2. pulls that repo/tag from the registry;
3. compares the freshly-pulled image id with the container's current image id;
4. if they differ, **recreates** the container from its existing configuration against the new image:
   stop → rename old → create new (same `Config` + `HostConfig` + networks) → start → remove old.

If create or start fails, kodkod rolls back to the original container. The kodkod container never updates or restarts **itself**.

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
