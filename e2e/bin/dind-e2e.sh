#!/usr/bin/env bash
# Run the whole e2e suite against an isolated Docker-in-Docker daemon, so nothing
# touches the host's own Docker (required for the monitor-all scenarios).
#
#   ./e2e/bin/dind-e2e.sh                 # all scenarios, then remove the dind
#   ./e2e/bin/dind-e2e.sh update rollback # a subset
#   KEEP=1 ./e2e/bin/dind-e2e.sh          # leave the dind up afterwards to re-run
#
# The host docker is used ONLY to manage the dind container; everything else
# (build, registry, kodkod, the test stacks) runs inside it.
set -uo pipefail
cd "$(dirname "$0")/../.."

DIND_NAME="${DIND_NAME:-kodkod-e2e-dind}"
DIND_IMAGE="${DIND_IMAGE:-docker:dind}"
DIND_PORT="${DIND_PORT:-12375}"
KEEP="${KEEP:-0}"

host_docker() { env -u DOCKER_HOST docker "$@"; }

teardown() {
  local rc=$?
  if [ "$KEEP" = "1" ]; then
    echo "==> KEEP=1: leaving '$DIND_NAME' up (export DOCKER_HOST=tcp://127.0.0.1:$DIND_PORT)"
  else
    echo "==> removing dind '$DIND_NAME'"
    host_docker rm -f "$DIND_NAME" >/dev/null 2>&1 || true
  fi
  exit "$rc"
}
trap teardown EXIT INT TERM

echo "==> (re)starting Docker-in-Docker from $DIND_IMAGE"
host_docker rm -f "$DIND_NAME" >/dev/null 2>&1 || true
host_docker run -d --privileged --name "$DIND_NAME" \
  -e DOCKER_TLS_CERTDIR="" \
  -p "127.0.0.1:$DIND_PORT:2375" \
  "$DIND_IMAGE" >/dev/null || { echo "could not start dind (is the host docker available?)"; exit 1; }

export DOCKER_HOST="tcp://127.0.0.1:$DIND_PORT"

echo "==> waiting for the inner daemon on $DOCKER_HOST"
ready=0
for _ in $(seq 1 90); do
  if docker version >/dev/null 2>&1; then ready=1; break; fi
  sleep 1
done
if [ "$ready" != "1" ]; then
  echo "inner daemon never became ready; last dind logs:"
  host_docker logs "$DIND_NAME" 2>&1 | tail -n 40
  exit 1
fi
docker version --format '    inner engine: {{.Server.Version}}'

echo "==> setup: build kodkod:e2e, start registry, push testapp v1"
if ! ./e2e/bin/setup.sh; then echo "setup failed"; exit 1; fi

echo "==> scenarios: ${*:-all}"
./e2e/bin/run.sh "$@"
suite_rc=$?

echo "==> tearing down e2e stacks inside dind"
./e2e/bin/cleanup.sh >/dev/null 2>&1 || true

exit "$suite_rc"
