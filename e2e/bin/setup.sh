#!/usr/bin/env bash
# Build the kodkod image, start the local registry, and publish testapp:latest=v1.
# Run once before the scenarios. Re-runnable (idempotent-ish).
set -euo pipefail

cd "$(dirname "$0")/../.."  # repo root
REG="${KODKOD_E2E_REGISTRY:-127.0.0.1:5000}"

echo "==> building kodkod:e2e (Gradle runs inside the build stage; first run is slow)"
docker build -t kodkod:e2e .

echo "==> starting local registry on ${REG}"
docker compose -f e2e/compose.registry.yml up -d

echo "==> building testapp v1"
docker build --target v1 --build-arg VARIANT=v1 -t "${REG}/testapp:latest" -t "${REG}/testapp:v1" e2e/testapp

echo "==> pushing testapp:latest = v1 (retries until the registry accepts)"
n=0
until docker push "${REG}/testapp:latest" >/dev/null 2>&1; do
  n=$((n + 1))
  if [ "$n" -ge 20 ]; then echo "push to ${REG} failed after retries" >&2; exit 1; fi
  sleep 2
done

cat <<EOF

Ready.
  registry : ${REG}  (testapp:latest = v1)
  image    : kodkod:e2e

Next: pick a scenario from E2E_TESTING.md, e.g.
  docker compose -f e2e/compose.update.yml up -d
EOF
