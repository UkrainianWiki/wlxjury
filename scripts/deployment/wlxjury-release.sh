#!/bin/bash
# Routine WLX Jury deployments after the cutover: back up, migrate under
# maintenance, reopen after a local smoke test. Recovery is manual and keyed
# on the milestones this script writes; see "Recovery runbook" in
# docs/deployment.md. Never clears maintenance on failure.
#
#   wlxjury-release [--config FILE] deploy RELEASE.zip SHA256
#   wlxjury-release [--config FILE] reopen OPERATION
#
# Run as root inside tmux. Reads the deployment.json and runtime.env that
# wlxjury-deploy uses, and shares its lock, so the two never run at once.
# Needs bash, coreutils, util-linux, curl, unzip, python3 (JSON only) and the
# MariaDB client tools. Prints no credentials.
set -euo pipefail
umask 077

op=''
staging=''

usage() {
  echo "usage: wlxjury-release [--config FILE] deploy RELEASE.zip SHA256 | reopen OPERATION" >&2
  exit 2
}

die() {
  echo "wlxjury-release: $*" >&2
  exit 1
}

on_exit() {
  local status=$?
  [ -z "$staging" ] || rm -rf "$staging"
  if [ "$status" != 0 ] && [ -n "$op" ]; then
    echo "wlxjury-release: stopped (exit $status). Maintenance is never cleared automatically;" \
         "follow the runbook for the last milestone in $op/milestones." >&2
  fi
}
trap on_exit EXIT

json() {  # json FILE KEY: a value, or a list with one item per line; empty when absent
  python3 -c 'import json, sys
value = json.load(open(sys.argv[1], encoding="utf-8")).get(sys.argv[2], "")
print("\n".join(map(str, value)) if isinstance(value, list) else value)' "$1" "$2"
}

config=/etc/wlxjury-runtime/deployment.json
if [ "${1:-}" = --config ]; then
  [ $# -ge 2 ] || usage
  config=$2
  shift 2
fi
[ "$(id -u)" = 0 ] || die "run as root"
if [ ! -f "$config" ] || [ -L "$config" ]; then
  die "missing config $config"
fi

ROOT=$(json "$config" release_root)
RUNTIME=$(json "$config" runtime_dir)
BACKUPS=$(json "$config" backup_root)
DATADIR=$(json "$config" database_dir)
SERVICE=$(json "$config" service)
DROPIN_DIR=$(json "$config" systemd_dropin_dir)
PORT=$(json "$config" port)
READY_TIMEOUT=$(json "$config" ready_timeout_seconds)
MIN_FREE=$(json "$config" minimum_free_bytes)
mapfile -t WRITERS < <(json "$config" quiesce_units)
mapfile -t URLS < <(json "$config" maintenance_urls)
for value in "$ROOT" "$RUNTIME" "$BACKUPS" "$DATADIR" "$SERVICE" "$DROPIN_DIR" "$PORT" "$READY_TIMEOUT" "$MIN_FREE"; do
  [ -n "$value" ] || die "incomplete config $config"
done
[ "${#URLS[@]}" -gt 0 ] || die "list every jury HTTP/HTTPS URL in maintenance_urls"
FLAG=$RUNTIME/maintenance
HOLD=$DROPIN_DIR/zz-deployment-hold.conf
ADMIN=--defaults-extra-file=$RUNTIME/db-admin.cnf

env_value() {  # One fully double-quoted value per line, as wlxjury-deploy writes them.
  sed -n "s/^$1=\"\\(.*\\)\"\$/\\1/p" "$RUNTIME/runtime.env"
}
DATABASE=$(env_value WLXJURY_DB)
[[ $DATABASE =~ ^[A-Za-z0-9_]+$ ]] || die "unsupported WLXJURY_DB in runtime.env"
DB_HOST=$(env_value WLXJURY_DB_HOST)
if [[ $DB_HOST == *:* ]]; then DB_PORT=${DB_HOST##*:}; else DB_PORT=3306; fi

sql() {
  mariadb "$ADMIN" --batch --skip-column-names --database="$DATABASE" -e "$1"
}

schema() {
  sql "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL
       ORDER BY installed_rank DESC LIMIT 1"
}

mark() {  # Durable before the step it announces.
  echo "$1" >> "$op/milestones"
  sync "$op/milestones" "$op"
  echo "Milestone: $1"
}

lock() {
  mkdir -p "$ROOT/releases" "$BACKUPS"
  # The service user reads releases; only root reads backups.
  chmod 755 "$ROOT" "$ROOT/releases"
  chmod 700 "$BACKUPS"
  exec 9>> "$ROOT/deployment.lock"
  flock -n 9 || die "another deployment operation holds the lock"
}

capacity() {
  local dir free
  for dir in "$ROOT" "$BACKUPS" "$DATADIR"; do
    free=$(df --output=avail -B1 "$dir" | tail -1 | tr -d ' ')
    [ "$free" -ge "$MIN_FREE" ] || die "less than $MIN_FREE bytes free at $dir"
  done
}

ready() {  # ready RELEASE: the service runs that release with its own schema
  local manifest=$1/wlxjury-build.json body
  systemctl is-active --quiet "$SERVICE" || return 1
  if [ "$(json "$manifest" legacy)" = True ]; then
    # The adopted 0.12 release has no /health/ready.
    curl -fsS --max-time 5 -o /dev/null "http://127.0.0.1:$PORT/auth" 2>/dev/null || return 1
    [ "$(schema 2>/dev/null)" = "$(json "$manifest" schemaVersion)" ]
  else
    # 200 only when the schema matches the running build's manifest, which it returns.
    body=$(curl -fsS --max-time 5 "http://127.0.0.1:$PORT/health/ready" 2>/dev/null) || return 1
    [ "$(printf '%s' "$body" | json /dev/stdin build 2>/dev/null)" = "$(json "$manifest" build)" ]
  fi
}

wait_ready() {
  local deadline=$((SECONDS + READY_TIMEOUT))
  until ready "$1"; do
    [ "$SECONDS" -lt "$deadline" ] || die "readiness deadline exceeded for $1"
    sleep 2
  done
}

check_maintenance() {
  local url code page
  page=$(mktemp)
  for url in "${URLS[@]}"; do
    code=$(curl -sS --max-time 10 -o "$page" -w '%{http_code}' "$url") || code=000
    # A vhost without the snippet also answers 503 once the app stops, but not with this page.
    if [ "$code" != 503 ] || ! grep -q 'undergoing maintenance' "$page"; then
      rm -f "$page"
      die "$url does not show the maintenance page (HTTP $code); include wlxjury-maintenance.conf in that vhost"
    fi
  done
  rm -f "$page"
}

check_public() {
  local url code
  for url in "${URLS[@]}"; do
    code=$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' "$url") || code=000
    [[ $code =~ ^[23] ]] || die "traffic is open but $url answers HTTP $code; fix routing, then rerun reopen"
  done
}

hold_restarts() {  # A JVM killed mid-migration must not restart and rerun it.
  mkdir -p "$DROPIN_DIR"
  printf '[Service]\n# Written by wlxjury-release while a deployment is open.\nRestart=no\n' > "$HOLD"
  chmod 644 "$HOLD"
  systemctl daemon-reload
}

stop_unit() {
  systemctl stop "$1"
  ! systemctl is-active --quiet "$1" || die "$1 is still running"
}

deploy() {
  local zip=$1 sha=$2 top manifest build want have release previous unit
  local -a entries active=() config_files
  [[ $sha =~ ^[0-9a-f]{64}$ ]] || die "pass the full lowercase SHA-256"
  [ -f "$zip" ] || die "missing $zip"
  lock
  [ ! -e "$FLAG" ] || die "maintenance is on: finish or recover the previous operation first"
  [ -L "$ROOT/current" ] || die "$ROOT/current must link to the running release"
  echo "$sha  $zip" | sha256sum -c --quiet - || die "ZIP checksum mismatch"
  # Releases come from the reviewed sbt dist build only; symlinks are never expected.
  if zipinfo "$zip" | grep -q '^l'; then die "ZIP contains symlinks"; fi

  # Everything up to the maintenance flag changes nothing: checks run on a staging copy.
  staging=$(mktemp -d "$ROOT/releases/.staging.XXXXXX")
  unzip -q "$zip" -d "$staging"
  entries=("$staging"/*)
  if [ "${#entries[@]}" != 1 ] || [ ! -d "${entries[0]}" ]; then
    die "ZIP must have one top-level directory"
  fi
  top=${entries[0]}
  manifest=$top/wlxjury-build.json
  [ -f "$manifest" ] && [ -f "$top/bin/wlxjury" ] || die "ZIP lacks wlxjury-build.json or bin/wlxjury; rebuild with sbt dist"
  build=$(json "$manifest" build)
  want=$(json "$manifest" schemaVersion)
  [[ $build =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,95}$ ]] || die "invalid build identity in the ZIP"
  [[ $want =~ ^[0-9]+(\.[0-9]+)*$ ]] || die "invalid schema version in the ZIP"
  release=$ROOT/releases/$build-${sha:0:12}
  [ ! -e "$release" ] || die "$release exists; never overwrite a release"

  [ "$(sql 'SELECT @@port')" = "$DB_PORT" ] || die "db-admin.cnf reaches a different MariaDB server than runtime.env"
  [ "$(sql 'SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0')" = 0 ] || die "the database has a failed migration"
  have=$(schema)
  # Flyway would refuse an older release only after the outage had started.
  [ "$(printf '%s\n' "$have" "$want" | sort -V | tail -1)" = "$want" ] ||
    die "release expects schema $want but the database is at $have; nothing was changed"
  [ "$(sql "SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA = DATABASE() AND STATUS = 'ENABLED'")" = 0 ] ||
    die "coordinate enabled database events before deploying"
  capacity
  previous=$(readlink -f "$ROOT/current")

  find "$top" -type d -exec chmod 755 {} +
  find "$top" -type f -exec chmod 644 {} +
  chmod 755 "$top"/bin/*
  chown -R root:root "$top"
  mv -T "$top" "$release"
  echo "Prepared $release"

  op=$BACKUPS/$(date -u +%Y%m%dT%H%M%SZ)-$build
  mkdir -m 700 "$op"
  exec > >(tee -a "$op/operation.log") 2> >(tee -a "$op/operation.log" >&2)
  for unit in "${WRITERS[@]}"; do
    if systemctl is-active --quiet "$unit"; then active+=("$unit"); fi
  done
  printf 'previous=%s\ncandidate=%s\nzip_sha256=%s\nwriters=%s\n' \
    "$previous" "$release" "$sha" "${active[*]}" > "$op/record"
  sync "$op/record" "$op"
  echo "Operation $op: $previous -> $release"
  # Every operation's milestones start here, so a missing or damaged file means
  # "unknown", never "nothing happened".
  mark started

  touch "$FLAG"
  chmod 644 "$FLAG"
  check_maintenance  # Every host, before anything stops.
  hold_restarts
  for unit in "${active[@]}"; do stop_unit "$unit"; done
  stop_unit "$SERVICE"
  [ "$(sql "SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA = DATABASE() AND STATUS = 'ENABLED'")" = 0 ] ||
    die "an enabled database event appeared; coordinate it before the backup"

  # --lock-tables: MyISAM tables make --single-transaction alone inconsistent.
  # --databases --add-drop-database: the restore recreates the database with its
  # own character set and collation, and drops tables a migration added.
  mariadb-dump "$ADMIN" --databases "$DATABASE" --add-drop-database --lock-tables --quick \
    --routines --events --triggers | gzip > "$op/database.sql.gz"
  gzip -t "$op/database.sql.gz"
  (cd "$op" && sha256sum database.sql.gz > database.sql.gz.sha256)
  config_files=("${RUNTIME#/}")
  if [ -f /etc/default/wlxjury ]; then config_files+=(etc/default/wlxjury); fi
  tar -C / -czf "$op/config.tgz" "${config_files[@]}"
  sync "$op"/*
  capacity  # The dump must not have used the migration reserve.
  mark backup-complete

  mark migration-may-start
  ln -sfn "$release" "$ROOT/current.next"
  mv -T "$ROOT/current.next" "$ROOT/current"
  sync "$ROOT"
  systemctl start "$SERVICE"  # Flyway migrates on start.
  wait_ready "$release"
  mark ready
  echo "Ready under maintenance. Smoke-test http://127.0.0.1:$PORT/ locally, then run:"
  echo "  wlxjury-release reopen $(basename "$op")"
}

reached() {
  grep -qx "$1" "$op/milestones"
}

reopen() {
  local current previous candidate writers unit
  op=$BACKUPS/$1
  case $1 in /*) op=$1 ;; esac
  if [ ! -f "$op/record" ] || [ "$(dirname "$(realpath "$op")")" != "$(realpath "$BACKUPS")" ]; then
    op=''
    die "not an operation folder: $1"
  fi
  lock
  exec > >(tee -a "$op/operation.log") 2> >(tee -a "$op/operation.log" >&2)
  if [ ! -f "$op/milestones" ] || [ "$(head -n 1 "$op/milestones")" != started ]; then
    die "milestones are missing or damaged, so the state is unknown; keep maintenance on and investigate"
  fi
  if [ ! -e "$FLAG" ] && ! reached traffic-may-resume; then
    die "the maintenance flag is absent; inspect live routing before reopening"
  fi
  previous=$(sed -n 's/^previous=//p' "$op/record")
  candidate=$(sed -n 's/^candidate=//p' "$op/record")
  current=$(readlink -f "$ROOT/current")
  if [ "$current" = "$previous" ]; then
    # `current` is switched only after migration-may-start is durable, so without
    # it the database was never migrated. With it, only a restore makes the
    # previous release safe again.
    if reached migration-may-start && ! reached database-restored; then
      die "a migration may have run: restore the database and record database-restored first (see the runbook)"
    fi
  elif [ "$current" != "$candidate" ]; then
    die "$current is neither the previous nor the candidate release of this operation"
  fi
  ready "$current" || die "$current is not ready; maintenance stays on"
  mark traffic-may-resume
  rm -f "$HOLD"
  systemctl daemon-reload
  rm -f "$FLAG"
  check_public
  writers=$(sed -n 's/^writers=//p' "$op/record")
  for unit in $writers; do systemctl start "$unit"; done
  mark complete
  echo "Traffic resumed with $current. Keep $op and the previous release."
}

case ${1:-} in
  deploy) [ $# = 3 ] || usage; deploy "$2" "$3" ;;
  reopen) [ $# = 2 ] || usage; reopen "$2" ;;
  *) usage ;;
esac
