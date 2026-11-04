#!/usr/bin/env bash
# The docker-compose smoke test, as CI runs it (.github/workflows/compose.yml) and locally:
#   sbt Docker/publishLocal && scripts/docker/smoke-test.sh
# Starts docker-compose.yml as its own project (wlxjury-smoke: its own containers and volumes,
# not those of `docker compose up`) with throwaway secrets, waits for the health checks, and
# checks: readiness with the build's schema, the sign-in page, the setup page with the token,
# creating the first account; then a restart on the same volumes keeps that account (/setup is
# gone). Removes the project's containers and volumes at the end, also after a failure.
# Options: --keep leaves the project running (remove it with the printed command).
# Environment: SMOKE_PORT (default 19000), SMOKE_PROJECT (default wlxjury-smoke),
# SMOKE_LOGS (a directory: the containers' logs are saved there before the project goes).
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
project="${SMOKE_PROJECT:-wlxjury-smoke}"
port="${SMOKE_PORT:-19000}"
keep=false
[ "${1:-}" = "--keep" ] && keep=true

work="$(mktemp -d)"
env_file="$work/env"
jar="$work/cookies"
compose() { docker compose -p "$project" -f "$repo/docker-compose.yml" --env-file "$env_file" "$@"; }

cleanup() {
  status=$?
  if [ -n "${SMOKE_LOGS:-}" ]; then
    mkdir -p "$SMOKE_LOGS"
    compose logs --no-color --timestamps > "$SMOKE_LOGS/compose.log" 2>&1 || true
    compose ps -a > "$SMOKE_LOGS/ps.txt" 2>&1 || true
  fi
  if [ "$status" -ne 0 ]; then
    echo "--- smoke test failed (exit $status); the end of the logs:" >&2
    compose logs --no-color --tail 80 >&2 || true
  fi
  if $keep && [ "$status" -eq 0 ]; then
    echo "kept: docker compose -p $project -f $repo/docker-compose.yml --env-file $env_file down -v"
  else
    compose down -v --remove-orphans >/dev/null 2>&1 || true
    rm -rf "$work"
  fi
  exit "$status"
}
trap cleanup EXIT
# a cancelled CI job (SIGTERM) or Ctrl-C still saves the logs and removes the project
trap 'exit 143' TERM
trap 'exit 130' INT

"$repo/scripts/docker/generate-env.sh" "$env_file" >/dev/null
printf 'WLXJURY_HTTP_PORT=%s\n' "$port" >> "$env_file"
token="$(sed -n 's/^WLXJURY_SETUP_TOKEN=//p' "$env_file")"
base="http://127.0.0.1:$port"
# the app answers to the names in WLXJURY_ALLOWED_HOSTS (default localhost)
get() { curl -sS -H 'Host: localhost' -b "$jar" -c "$jar" "$@"; }
status_of() { get -o /dev/null -w '%{http_code}' "$base$1"; }
expect() {
  local want="$1" path="$2" got
  got="$(status_of "$path")"
  [ "$got" = "$want" ] || { echo "FAIL: GET $path answered $got, expected $want" >&2; exit 1; }
  echo "ok: GET $path $got"
}
seconds() { date +%s; }
up() {
  local start; start=$(seconds)
  compose up -d --wait --wait-timeout 300 >/dev/null
  echo "ok: $1 up and healthy in $(( $(seconds) - start )) s"
}
ready() {
  # /health/ready answers loopback clients only: ask from inside the app's container
  local body; body="$(compose exec -T app curl -fsS http://127.0.0.1:9000/health/ready)"
  echo "ok: readiness $body"
  local schema; schema="$(printf '%s' "$body" | sed -n 's/.*"schemaVersion" *: *"\([^"]*\)".*/\1/p')"
  [ -n "$schema" ] || { echo "FAIL: no schemaVersion in the readiness answer" >&2; exit 1; }
}

up "cold start"
ready
expect 200 /auth
expect 200 /setup

# The first account, through the setup form with its CSRF token
form="$(get "$base/setup")"
csrf="$(printf '%s' "$form" | sed -n 's/.*name="csrfToken" value="\([^"]*\)".*/\1/p' | head -n 1)"
[ -n "$csrf" ] || { echo "FAIL: no CSRF token in the setup form" >&2; exit 1; }
password="$(openssl rand -hex 16)"
created="$(get -o /dev/null -w '%{http_code}' -X POST "$base/setup" \
  --data-urlencode "csrfToken=$csrf" --data-urlencode "token=$token" \
  --data-urlencode "login=smoke@example.org" \
  --data-urlencode "password=$password" --data-urlencode "repeat.password=$password")"
case "$created" in 30[23]) echo "ok: POST /setup $created (first account created)" ;;
  *) echo "FAIL: POST /setup answered $created" >&2; exit 1 ;; esac
expect 404 /setup

# A restart on the same volumes keeps the data
compose down >/dev/null
up "restart"
ready
expect 404 /setup
expect 200 /auth
echo "smoke test passed"
