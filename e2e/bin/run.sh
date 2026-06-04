#!/usr/bin/env bash
# Assertion-based e2e runner. Talks to whatever DOCKER_HOST points at, and assumes
# e2e/bin/setup.sh has already built kodkod:e2e, started the registry and pushed v1.
#
#   ./e2e/bin/run.sh                 # all scenarios
#   ./e2e/bin/run.sh update rollback # a subset
#
# Designed to run inside Docker-in-Docker (see dind-e2e.sh). Compatible with the
# bash 3.2 that ships on macOS (no associative arrays, no mapfile).
set -uo pipefail
cd "$(dirname "$0")/../.."

PASS=0
FAIL=0
FAILED_LIST=""

c_inspect() { docker inspect -f "$1" "$2" 2>/dev/null; }
dc() { docker compose -f "e2e/compose.$1.yml" "${@:2}"; }

ok()  { echo "    PASS  $1"; PASS=$((PASS + 1)); }
bad() { echo "    FAIL  $1"; FAIL=$((FAIL + 1)); FAILED_LIST="${FAILED_LIST}\n    - $1"; }

assert_eq() { if [ "$2" = "$3" ]; then ok "$1 = [$3]"; else bad "$1 — expected [$2] got [$3]"; fi; }
assert_ne() { if [ "$2" != "$3" ]; then ok "$1 (changed)"; else bad "$1 — did not change [$3]"; fi; }
assert_gt() { if [[ "$2" > "$3" ]]; then ok "$1"; else bad "$1 — [$2] not after [$3]"; fi; }
assert_contains() { case "$3" in *"$2"*) ok "$1 contains [$2]";; *) bad "$1 — expected [$2] in [$3]";; esac; }

# wait_until <timeout_s> <description> <command...> — polls until the command succeeds.
wait_until() {
  local t=$1; shift
  local desc=$1; shift
  local end=$(( $(date +%s) + t ))
  while [ "$(date +%s)" -lt "$end" ]; do
    if "$@"; then return 0; fi
    sleep 2
  done
  echo "    (timeout ${t}s waiting for: $desc)"
  return 1
}

# Predicates (return 0/1) for wait_until.
PREV=""
p_variant()         { [ "$(c_inspect '{{index .Config.Labels "app.variant"}}' "$1")" = "$2" ]; }
p_health()          { [ "$(c_inspect '{{.State.Health.Status}}' "$1")" = "$2" ]; }
p_running()         { [ "$(c_inspect '{{.State.Running}}' "$1")" = "true" ]; }
p_started_changed() { [ -n "$(c_inspect '{{.State.StartedAt}}' "$1")" ] && [ "$(c_inspect '{{.State.StartedAt}}' "$1")" != "$PREV" ]; }
p_running_v1()      { [ "$(c_inspect '{{.State.Running}}' "$1")" = "true" ] && [ "$(c_inspect '{{index .Config.Labels "app.variant"}}' "$1")" = "v1" ]; }
log_has()           { docker logs "$1" 2>&1 | grep -qi "$2"; }

publish_variant() {
  local variant=$1
  if ./e2e/bin/publish.sh "$variant" >/dev/null 2>&1; then return 0; fi
  bad "publish testapp:${variant} failed"
  return 1
}

# ---------------------------------------------------------------------------- #

scenario_autoheal() {
  echo "[A] autoheal — restart an unhealthy container"
  dc autoheal up -d >/dev/null 2>&1
  wait_until 40 "app healthy" p_health e2e-autoheal-app-1 healthy || true
  PREV=$(c_inspect '{{.State.StartedAt}}' e2e-autoheal-app-1)
  docker exec e2e-autoheal-app-1 rm -f /tmp/healthy >/dev/null 2>&1
  wait_until 60 "app restarted by kodkod" p_started_changed e2e-autoheal-app-1 || true
  assert_ne "[A] StartedAt advanced after going unhealthy" "$PREV" "$(c_inspect '{{.State.StartedAt}}' e2e-autoheal-app-1)"
  wait_until 30 "health recovered" p_health e2e-autoheal-app-1 healthy || true
  assert_eq "[A] health recovered" healthy "$(c_inspect '{{.State.Health.Status}}' e2e-autoheal-app-1)"
  dc autoheal down -v >/dev/null 2>&1
}

scenario_update() {
  echo "[U] update — pull + recreate + adopt new image defaults"
  publish_variant v1 || return
  dc update up -d >/dev/null 2>&1
  wait_until 30 "app v1 up" p_variant e2e-update-app-1 v1 || true
  local old_img; old_img=$(c_inspect '{{.Image}}' e2e-update-app-1)
  publish_variant v2 || return
  wait_until 90 "app updated to v2" p_variant e2e-update-app-1 v2 || true
  assert_eq "[U] app.variant adopted from new image" v2 "$(c_inspect '{{index .Config.Labels "app.variant"}}' e2e-update-app-1)"
  assert_ne "[U] image id changed (recreated)" "$old_img" "$(c_inspect '{{.Image}}' e2e-update-app-1)"
  assert_contains "[U] healthcheck test adopted from new image" "/tmp/healthy-v2" "$(c_inspect '{{range .Config.Healthcheck.Test}}{{.}} {{end}}' e2e-update-app-1)"
  assert_eq "[U] healthcheck interval override preserved" 5000000000 "$(c_inspect '{{json .Config.Healthcheck.Interval}}' e2e-update-app-1)"
  assert_eq "[U] healthcheck timeout adopted from new image" 4000000000 "$(c_inspect '{{json .Config.Healthcheck.Timeout}}' e2e-update-app-1)"
  assert_eq "[U] healthcheck retries adopted from new image" 4 "$(c_inspect '{{json .Config.Healthcheck.Retries}}' e2e-update-app-1)"
  dc update down -v >/dev/null 2>&1
}

scenario_deps() {
  echo "[D] dependencies — ordered recreate + dependent restart"
  publish_variant v1 || return
  dc deps up -d >/dev/null 2>&1
  wait_until 30 "db v1 up" p_variant e2e-deps-db-1 v1 || true
  publish_variant v2 || return
  wait_until 90 "db updated to v2" p_variant e2e-deps-db-1 v2 || true
  assert_eq "[D] db recreated on v2" v2 "$(c_inspect '{{index .Config.Labels "app.variant"}}' e2e-deps-db-1)"
  local db_start web_start
  db_start=$(c_inspect '{{.State.StartedAt}}' e2e-deps-db-1)
  web_start=$(c_inspect '{{.State.StartedAt}}' e2e-deps-web-1)
  assert_gt "[D] web restarted AFTER db (forward order)" "$web_start" "$db_start"
  dc deps down -v >/dev/null 2>&1
}

scenario_multinet() {
  echo "[N] multi-network — reconnected to every network on recreate"
  publish_variant v1 || return
  dc multinet up -d >/dev/null 2>&1
  wait_until 30 "app v1 up" p_variant e2e-multinet-app-1 v1 || true
  publish_variant v2 || return
  wait_until 90 "app updated to v2" p_variant e2e-multinet-app-1 v2 || true
  local nets; nets=$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}' e2e-multinet-app-1 2>/dev/null)
  case "$nets" in *e2e-multinet_neta*) ok "[N] attached to neta";; *) bad "[N] missing neta (got: $nets)";; esac
  case "$nets" in *e2e-multinet_netb*) ok "[N] attached to netb";; *) bad "[N] missing netb (got: $nets)";; esac
  dc multinet down -v >/dev/null 2>&1
}

scenario_cmode() {
  echo "[C] network_mode: container: — rewritten to the provider's name"
  publish_variant v1 || return
  dc container-mode up -d >/dev/null 2>&1
  wait_until 30 "consumer v1 up" p_variant e2e-cmode-consumer-1 v1 || true
  publish_variant v2 || return
  wait_until 90 "consumer updated to v2" p_variant e2e-cmode-consumer-1 v2 || true
  assert_eq "[C] consumer recreated on v2" v2 "$(c_inspect '{{index .Config.Labels "app.variant"}}' e2e-cmode-consumer-1)"
  local provider_id; provider_id=$(c_inspect '{{.Id}}' e2e-cmode-provider-1)
  assert_eq "[C] NetworkMode points at the provider" "container:$provider_id" "$(c_inspect '{{.HostConfig.NetworkMode}}' e2e-cmode-consumer-1)"
  assert_eq "[C] consumer running" true "$(c_inspect '{{.State.Running}}' e2e-cmode-consumer-1)"
  dc container-mode down -v >/dev/null 2>&1
}

scenario_rollback() {
  echo "[R] rollback — a failed recreate restores the running original"
  publish_variant v1 || return
  dc rollback up -d >/dev/null 2>&1
  wait_until 30 "app v1 up" p_variant e2e-rollback-app-1 v1 || true
  publish_variant broken || return
  wait_until 90 "kodkod logged a rollback" log_has e2e-rollback-kodkod-1 "rolling back" || true
  assert_eq "[R] kodkod reported the failed recreate" yes "$(log_has e2e-rollback-kodkod-1 'rolling back' && echo yes || echo no)"
  # Republish v1 so the retry loop becomes a no-op (cached build => identical id), then assert the steady state.
  publish_variant v1 || return
  wait_until 60 "app back to running v1" p_running_v1 e2e-rollback-app-1 || true
  assert_eq "[R] app still on v1 (broken not adopted)" v1 "$(c_inspect '{{index .Config.Labels "app.variant"}}' e2e-rollback-app-1)"
  assert_eq "[R] app is running" true "$(c_inspect '{{.State.Running}}' e2e-rollback-app-1)"
  local backups; backups=$(docker ps -aq --filter 'name=_kodkod_old_' 2>/dev/null | wc -l | tr -d ' ')
  assert_eq "[R] no backup container left behind" 0 "$backups"
  dc rollback down -v >/dev/null 2>&1
}

scenario_digest() {
  echo "[P] digest-pinned — never updated"
  publish_variant v1 || return
  local digest; digest=$(docker inspect -f '{{index .RepoDigests 0}}' 127.0.0.1:5000/testapp:latest 2>/dev/null | cut -d@ -f2)
  if [ -z "$digest" ]; then bad "[P] could not read v1 RepoDigest"; return; fi
  export TESTAPP_DIGEST="$digest"
  dc digest up -d >/dev/null 2>&1
  wait_until 30 "pinned app up" p_running e2e-digest-app-1 || true
  local old_img; old_img=$(c_inspect '{{.Image}}' e2e-digest-app-1)
  publish_variant v2 || return
  wait_until 50 "kodkod logged digest-pinned skip" log_has e2e-digest-kodkod-1 "digest-pinned" || true
  assert_eq "[P] kodkod skipped the pinned container" yes "$(log_has e2e-digest-kodkod-1 'digest-pinned' && echo yes || echo no)"
  assert_eq "[P] pinned image id unchanged" "$old_img" "$(c_inspect '{{.Image}}' e2e-digest-app-1)"
  dc digest down -v >/dev/null 2>&1
  unset TESTAPP_DIGEST
}

scenario_self() {
  echo "[S] self-protection — monitor-all + custom hostname must not touch kodkod"
  # Take the registry out of view so monitor-all only considers kodkod + dummy.
  docker compose -f e2e/compose.registry.yml stop >/dev/null 2>&1 || true
  dc self up -d >/dev/null 2>&1
  wait_until 30 "kodkod up" p_running e2e-self-kodkod-1 || true
  assert_eq "[S] self label baked into the image" true "$(c_inspect '{{index .Config.Labels "io.heapy.kodkod.self"}}' e2e-self-kodkod-1)"
  assert_eq "[S] custom hostname (defeats the HOSTNAME fallback)" kodkod-custom-host "$(c_inspect '{{.Config.Hostname}}' e2e-self-kodkod-1)"
  local before; before=$(c_inspect '{{.State.StartedAt}}' e2e-self-kodkod-1)
  wait_until 30 "dummy up" p_running e2e-self-dummy-1 || true
  local dummy_before; dummy_before=$(c_inspect '{{.State.StartedAt}}' e2e-self-dummy-1)
  sleep 35  # several monitor-all cycles
  assert_eq "[S] kodkod did NOT act on itself" "$before" "$(c_inspect '{{.State.StartedAt}}' e2e-self-kodkod-1)"
  PREV="$dummy_before"
  wait_until 30 "dummy restarted by monitor-all" p_started_changed e2e-self-dummy-1 || true
  assert_ne "[S] cycles actually ran (dummy restarted)" "$dummy_before" "$(c_inspect '{{.State.StartedAt}}' e2e-self-dummy-1)"
  wait_until 30 "bystander running after monitor-all cycles" p_running e2e-self-dummy-1 || true
  assert_eq "[S] bystander left running" true "$(c_inspect '{{.State.Running}}' e2e-self-dummy-1)"
  dc self down -v >/dev/null 2>&1
  docker compose -f e2e/compose.registry.yml start >/dev/null 2>&1 || true
}

# ---------------------------------------------------------------------------- #

ALL="autoheal update deps multinet cmode rollback digest self"
SELECTED="${*:-$ALL}"

echo "############ kodkod e2e suite ############"
for s in $SELECTED; do
  case "$s" in
    autoheal)             scenario_autoheal ;;
    update)               scenario_update ;;
    deps)                 scenario_deps ;;
    multinet)             scenario_multinet ;;
    cmode|container-mode) scenario_cmode ;;
    rollback)             scenario_rollback ;;
    digest)               scenario_digest ;;
    self)                 scenario_self ;;
    *) echo "unknown scenario: $s" ;;
  esac
done

echo "##########################################"
echo "RESULT: ${PASS} passed, ${FAIL} failed"
if [ "$FAIL" -gt 0 ]; then
  printf "Failures:%b\n" "$FAILED_LIST"
  exit 1
fi
echo "ALL GREEN"
