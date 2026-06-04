#!/usr/bin/env bash
# Build a testapp variant and push it to testapp:latest, simulating a release.
#   e2e/bin/publish.sh v1       # good image, APP_VARIANT/label = v1
#   e2e/bin/publish.sh v2       # good image, APP_VARIANT/label = v2 (the "update")
#   e2e/bin/publish.sh broken   # start-failing image (for the rollback scenario)
set -euo pipefail

cd "$(dirname "$0")/../.."  # repo root
REG="${KODKOD_E2E_REGISTRY:-127.0.0.1:5000}"
VARIANT="${1:-v2}"

case "$VARIANT" in
  v1|v2)
    docker build --target "${VARIANT}" --build-arg "VARIANT=${VARIANT}" -t "${REG}/testapp:latest" -t "${REG}/testapp:${VARIANT}" e2e/testapp
    ;;
  broken)
    docker build -f e2e/testapp/Dockerfile.broken -t "${REG}/testapp:latest" e2e/testapp
    ;;
  *)
    echo "usage: $0 {v1|v2|broken}" >&2
    exit 2
    ;;
esac

n=0
until docker push "${REG}/testapp:latest" >/dev/null 2>&1; do
  n=$((n + 1))
  if [ "$n" -ge 20 ]; then echo "push to ${REG} failed after retries" >&2; exit 1; fi
  sleep 2
done
echo "==> testapp:latest is now '${VARIANT}'"
