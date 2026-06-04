#!/usr/bin/env bash
# Tear down everything the e2e suite creates. Safe to run repeatedly.
# Keeps the kodkod:e2e and base images so re-runs are fast.
set -uo pipefail

cd "$(dirname "$0")/../.."  # repo root
REG="${KODKOD_E2E_REGISTRY:-127.0.0.1:5000}"
# compose.digest.yml interpolates this; give it a dummy so `down` doesn't error.
export TESTAPP_DIGEST="${TESTAPP_DIGEST:-sha256:0000000000000000000000000000000000000000000000000000000000000000}"

for f in autoheal update deps multinet container-mode rollback self digest; do
  docker compose -f "e2e/compose.$f.yml" down -v --remove-orphans >/dev/null 2>&1 || true
done
docker compose -f e2e/compose.registry.yml down -v --remove-orphans >/dev/null 2>&1 || true

# Containers kodkod recreated (kept their e2e- names) and any leftover backups.
docker ps -aq --filter 'name=e2e-' | xargs -r docker rm -f >/dev/null 2>&1 || true
docker ps -aq --filter 'name=_kodkod_old_' | xargs -r docker rm -f >/dev/null 2>&1 || true
for tag in latest v1 v2; do
  docker rmi "${REG}/testapp:${tag}" >/dev/null 2>&1 || true
done

echo "cleanup done (kodkod:e2e kept)"
