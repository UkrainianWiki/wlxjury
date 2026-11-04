#!/usr/bin/env bash
# The docker-compose smoke test, as CI runs it (.github/workflows/compose.yml) and locally:
#   sbt Docker/publishLocal && scripts/docker/smoke-test.sh
# Starts docker-compose.yml as its own project (wlxjury-smoke: its own containers and volumes,
# not those of `docker compose up`) with throwaway secrets, waits for the health checks, and
# checks: readiness with the build's schema, the sign-in page, the setup page with the token,
# creating the first account, a file written to the thumbnail cache volume by the app's user;
# then a restart on the same volumes keeps the account (/setup is gone) and the file. Removes
# the project's containers and volumes at the end, also after a failure or a cancel.
# Options: --keep leaves the project running (remove it with the printed command).
# Environment: SMOKE_PORT (default 19000), SMOKE_PROJECT (default wlxjury-smoke; never wlxjury,
# the project of `docker compose up`), SMOKE_LOGS (a directory: the containers' logs are saved
# there before the project goes).
set -euo pipefail
umask 077

repo="$(cd "$(dirname "$0")/../.." && pwd)"
project="${SMOKE_PROJECT:-wlxjury-smoke}"
port="${SMOKE_PORT:-19000}"
keep=false
[ "${1:-}" = "--keep" ] && keep=true

# Its cleanup runs `down -v`: never on the developer's own project and its database
if [ "$project" = "wlxjury" ]; then
  echo "SMOKE_PROJECT=wlxjury is docker-compose.yml's own project: its volumes would be deleted" >&2
  exit 2
fi

work="$(mktemp -d)"
# curl's name@file opens that path itself: in Git Bash (a Windows curl) as C:/..., not /tmp/...
files="$(cygpath -m "$work" 2>/dev/null || printf '%s' "$work")"
env_file="$work/env"
jar="$work/cookies"
# The settings come from the throwaway env file only: a variable exported in the shell would
# take precedence over --env-file
compose() {
  env -u WLXJURY_COMPOSE_DB_PASSWORD -u WLXJURY_COMPOSE_DB_ROOT_PASSWORD -u PLAY_HTTP_SECRET_KEY -u WLXJURY_SETUP_TOKEN \
    -u WLXJURY_ALLOWED_HOSTS -u WLXJURY_HOST -u WLXJURY_HTTP_BIND -u WLXJURY_HTTP_PORT \
    -u WLXJURY_IMAGE -u COMPOSE_FILE -u COMPOSE_PROJECT_NAME -u COMPOSE_PROFILES \
    docker compose -p "$project" -f "$repo/docker-compose.yml" --env-file "$env_file" "$@"
}

"$repo/scripts/docker/generate-env.sh" "$env_file" >/dev/null
printf 'WLXJURY_HTTP_PORT=%s\nWLXJURY_HOST=http://localhost:%s/\n' "$port" "$port" >> "$env_file"

# A project left from an earlier run (--keep, or one killed) is not ours to remove
if [ -n "$(compose ps -a -q 2>/dev/null)" ]; then
  echo "the compose project $project already has containers: remove them first" \
    "(docker compose -p $project down -v) or set SMOKE_PROJECT" >&2
  rm -rf "$work"
  exit 2
fi

redact() { grep -Eiv 'GENERATED ROOT PASSWORD|PASSWORD *[:=]' || true; }

cleanup() {
  status=$?
  if [ -n "${SMOKE_LOGS:-}" ]; then
    mkdir -p "$SMOKE_LOGS"
    # no secret in an uploaded log: drop any line that prints a password, belt and braces (the
    # root password is given, so MariaDB prints none)
    compose logs --no-color --timestamps 2>&1 | redact > "$SMOKE_LOGS/compose.log" || true
    compose ps -a > "$SMOKE_LOGS/ps.txt" 2>&1 || true
  fi
  if [ "$status" -ne 0 ]; then
    echo "--- smoke test failed (exit $status); the end of the logs:" >&2
    compose logs --no-color --tail 80 2>&1 | redact >&2 || true
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
# The thumbnail cache volume, as the app's user sees it
cache_file=/var/www/jury-images/smoke/120px-smoke.jpg
cached() { compose exec -T app sh -c "stat -c '%U:%G' $cache_file"; }

up "cold start"
ready
expect 200 /auth
expect 200 /setup

# The first account, through the setup form with its CSRF token. The form's values go to curl
# in files (name@file), not on its command line.
form="$(get "$base/setup")"
csrf="$(printf '%s' "$form" | sed -n 's/.*name="csrfToken" value="\([^"]*\)".*/\1/p' | head -n 1)"
[ -n "$csrf" ] || { echo "FAIL: no CSRF token in the setup form" >&2; exit 1; }
printf '%s' "$csrf" > "$work/csrf"
sed -n 's/^WLXJURY_SETUP_TOKEN=//p' "$env_file" | tr -d '\n' > "$work/token"
openssl rand -hex 16 | tr -d '\n' > "$work/password"
created="$(get -o /dev/null -w '%{http_code}' -X POST "$base/setup" \
  --data-urlencode "csrfToken@$files/csrf" --data-urlencode "token@$files/token" \
  --data-urlencode "login=smoke@example.org" \
  --data-urlencode "password@$files/password" --data-urlencode "repeat.password@$files/password")"
case "$created" in 30[23]) echo "ok: POST /setup $created (first account created)" ;;
  *) echo "FAIL: POST /setup answered $created" >&2; exit 1 ;; esac
expect 404 /setup

# A thumbnail in the cache volume, written as the app's user (the cache's own writes need it)
compose exec -T app sh -c "mkdir -p ${cache_file%/*} && printf smoke > $cache_file"
owner="$(cached)"
[ "$owner" = "wlxjury:wlxjury" ] || { echo "FAIL: the cache file is owned by $owner" >&2; exit 1; }
echo "ok: thumbnail cache writable ($owner)"

# A restart on the same volumes keeps the data
compose down >/dev/null
up "restart"
ready
expect 404 /setup
expect 200 /auth
[ "$(cached)" = "wlxjury:wlxjury" ] || { echo "FAIL: the cache file is gone after the restart" >&2; exit 1; }
echo "ok: thumbnail cache kept"
echo "smoke test passed"
