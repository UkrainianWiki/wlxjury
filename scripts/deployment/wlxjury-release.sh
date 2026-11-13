#!/bin/bash
# Routine WLX Jury deployments after the cutover: back up, migrate under
# maintenance, reopen after a local smoke test, and restore the pre-deployment
# database and release when a deployment must be backed out. A release without
# new migrations skips the backup unless --backup asks for one; backing it out
# then only switches the release back. A deployment refuses while a background
# job is queued or running, unless --allow-running-jobs accepts that they go
# back to the queue and continue after the restart. Every step stops at the first
# failure and never clears maintenance on failure; the runbook in
# docs/deployment.md says which command fits the operation's milestones.
#
#   wlxjury-release [--config FILE] deploy RELEASE.zip SHA256 [--backup] [--allow-running-jobs]
#   wlxjury-release [--config FILE] reopen OPERATION
#   wlxjury-release [--config FILE] restore OPERATION [--accept-data-loss]
#   wlxjury-release [--config FILE] prune [--dry-run]
#
# A successful reopen ends with prune: old release folders and old
# pre-deployment dumps are deleted, keeping what a restore can still need
# (docs/deployment.md, "Retention"). If only that prune fails, reopen exits 3:
# traffic is open and the operation complete; run prune again once fixed.
#
# Run as root inside tmux. Reads the deployment.json and runtime.env that
# wlxjury-deploy uses, and shares its lock, so the two never run at once.
# Needs bash, coreutils, util-linux, curl, unzip, python3 (JSON only) and the
# MariaDB client tools. Prints no credentials.
set -euo pipefail
umask 077

op=''
staging=''
active_jobs=''

usage() {
  echo "usage: wlxjury-release [--config FILE] deploy RELEASE.zip SHA256 [--backup] [--allow-running-jobs]" \
       "| reopen OPERATION | restore OPERATION [--accept-data-loss] | prune [--dry-run]" >&2
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
RESTORE_MIN_FREE=$(json "$config" restore_minimum_free_bytes)
RESTORE_MIN_FREE=${RESTORE_MIN_FREE:-$MIN_FREE}
SESSION_TIMEOUT=$(json "$config" session_end_timeout_seconds)
SESSION_TIMEOUT=${SESSION_TIMEOUT:-600}
mapfile -t WRITERS < <(json "$config" quiesce_units)
mapfile -t URLS < <(json "$config" maintenance_urls)
for value in "$ROOT" "$RUNTIME" "$BACKUPS" "$DATADIR" "$SERVICE" "$DROPIN_DIR" "$PORT" "$READY_TIMEOUT" "$MIN_FREE"; do
  [ -n "$value" ] || die "incomplete config $config"
done
[ "${#URLS[@]}" -gt 0 ] || die "list every jury HTTP/HTTPS URL in maintenance_urls"
FLAG=$RUNTIME/maintenance
HOLD=$DROPIN_DIR/zz-deployment-hold.conf
# The app's image cache (wlxjury.thumbs.local-path); checked only where it exists.
IMAGE_CACHE=$(json "$config" image_cache_dir)
IMAGE_CACHE=${IMAGE_CACHE:-/var/www/jury-images}
# The account the service runs as, which must own the cache.
SERVICE_USER=$(json "$config" service_user)
SERVICE_USER=${SERVICE_USER:-wlxjury}

env_value() {  # One fully double-quoted value per line, as wlxjury-deploy writes them.
  sed -n "s/^$1=\"\\(.*\\)\"\$/\\1/p" "$RUNTIME/runtime.env"
}
DATABASE=$(env_value WLXJURY_DB)
[[ $DATABASE =~ ^[A-Za-z0-9_]+$ ]] || die "unsupported WLXJURY_DB in runtime.env"
DB_USER=$(env_value WLXJURY_DB_USER)
[ -n "$DB_USER" ] || die "missing WLXJURY_DB_USER in runtime.env"
DB_HOST=$(env_value WLXJURY_DB_HOST)
[[ $DB_HOST =~ ^([A-Za-z0-9_.-]+)(:([0-9]+))?$ ]] || die "unsupported WLXJURY_DB_HOST in runtime.env"
DB_PORT=${BASH_REMATCH[3]:-3306}
# Administrative commands always reach the app's own host, whatever db-admin.cnf
# says. A bare "localhost" means the local server through db-admin.cnf's socket
# (root socket authentication); check_endpoint then confirms it listens on the
# app's port. Anything else is reached by TCP at the app's host and port.
CLIENT=("--defaults-extra-file=$RUNTIME/db-admin.cnf" "--host=${BASH_REMATCH[1]}")
if [ "$DB_HOST" = localhost ]; then
  CLIENT+=(--protocol=SOCKET)
else
  CLIENT+=("--port=$DB_PORT" --protocol=TCP)
fi

admin() {
  mariadb "${CLIENT[@]}" --batch --skip-column-names "$@"
}

sql() {
  admin --database="$DATABASE" -e "$1"
}

schema() {
  sql "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL
       ORDER BY installed_rank DESC LIMIT 1"
}

check_endpoint() {
  local port
  port=$(admin -e 'SELECT @@port') || die "cannot reach MariaDB at $DB_HOST with db-admin.cnf"
  [ "$port" = "$DB_PORT" ] || die "db-admin.cnf reaches MariaDB on port $port, but the app uses $DB_HOST"
}

check_jobs() {  # check_jobs yes|no: queued or running background jobs refuse the deployment, or (yes) go to $active_jobs
  local rows id kind contest status
  # A query that fails (the table missing, no access) must not pass as "no jobs".
  rows=$(sql "SELECT id, kind, IFNULL(contest_id, 'none'), status FROM job
              WHERE status IN ('queued', 'running') ORDER BY id") ||
    die "cannot read the background jobs (table job) to check that none is queued or running; nothing was changed"
  [ -n "$rows" ] || return 0
  while IFS=$'\t' read -r id kind contest status; do
    active_jobs+="  job $id: $kind, contest $contest, $status"$'\n'
  done <<< "$rows"
  active_jobs=${active_jobs%$'\n'}
  [ "$1" = yes ] || die "background jobs are queued or running; nothing was changed:
$active_jobs
Wait for them to finish (or cancel them in the app), or rerun with --allow-running-jobs:
running jobs go back to the queue and continue after the restart (if the app was killed, once their lease expires)."
}

no_enabled_events() {
  [ "$(sql "SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA = DATABASE() AND STATUS = 'ENABLED'")" = 0 ]
}

mark() {  # Durable before the step it announces.
  echo "$1" >> "$op/milestones"
  sync "$op/milestones" "$op"
  echo "Milestone: $1"
}

reached() {
  grep -qx "$1" "$op/milestones"
}

record_value() {
  sed -n "s/^$1=//p" "$op/record"
}

lock() {
  mkdir -p "$ROOT/releases" "$BACKUPS"
  # The service user reads releases; only root reads backups.
  chmod 755 "$ROOT" "$ROOT/releases"
  chmod 700 "$BACKUPS"
  exec 9>> "$ROOT/deployment.lock"
  flock -n 9 || die "another deployment operation holds the lock"
}

open_operation() {  # Lock, log, and require intact milestones.
  op=$BACKUPS/$1
  case $1 in /*) op=$1 ;; esac
  if [ ! -f "$op/record" ] || [ "$(dirname "$(realpath "$op")")" != "$(realpath "$BACKUPS")" ]; then
    op=''
    die "not an operation folder: $1"
  fi
  lock
  exec > >(tee -a "$op/operation.log") 2> >(tee -a "$op/operation.log" >&2)
  # Every operation's milestones start with "started", so a missing or damaged
  # file means "unknown", never "nothing happened".
  if [ ! -f "$op/milestones" ] || [ "$(head -n 1 "$op/milestones")" != started ]; then
    die "milestones are missing or damaged, so the state is unknown; keep maintenance on and investigate"
  fi
}

check_image_cache() {
  # Only the app writes the cache, and it serves it itself (ImageProxyController): a group- or
  # world-writable folder would let any other local process plant files in it.
  # A missing folder is a new server's: the app creates it. A symlink is checked at its target.
  local mode owner fix='fix it as docs/deployment.md, "The image cache folder", says (chown, chmod, find)'
  [ -e "$IMAGE_CACHE" ] || return 0
  [ -d "$IMAGE_CACHE" ] || die "image_cache_dir $IMAGE_CACHE is not a directory; nothing was changed; $fix"
  mode=$(stat -L -c %a "$IMAGE_CACHE") || die "cannot read the mode of $IMAGE_CACHE"
  owner=$(stat -L -c %U "$IMAGE_CACHE") || die "cannot read the owner of $IMAGE_CACHE"
  if [ "$owner" != "$SERVICE_USER" ]; then
    die "the image cache $IMAGE_CACHE is owned by $owner, not $SERVICE_USER; nothing was changed; $fix"
  fi
  if (( 8#$mode & 8#022 )); then
    die "the image cache $IMAGE_CACHE is group- or world-writable (mode $mode); nothing was changed; $fix"
  fi
}

capacity() {  # capacity [BYTES]: that much free on every filesystem involved
  local need=${1:-$MIN_FREE} dir free
  for dir in "$ROOT" "$BACKUPS" "$DATADIR"; do
    free=$(df --output=avail -B1 "$dir" | tail -1 | tr -d ' ')
    [ "$free" -ge "$need" ] || die "less than $need bytes free at $dir"
  done
}

database_bytes() {  # Zero when the database does not exist, as after an interrupted import.
  admin -e "SELECT COALESCE(SUM(DATA_LENGTH + INDEX_LENGTH), 0) FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = '$DATABASE'"
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

enable_maintenance() {
  touch "$FLAG"
  chmod 644 "$FLAG"
  check_maintenance  # Every host, before anything stops.
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

stop_writers() {
  # Every configured unit, active or not: a timer can start its service at any
  # moment. The recorded state only decides what reopen restarts.
  local unit
  for unit in "${WRITERS[@]}"; do stop_unit "$unit"; done
  stop_unit "$SERVICE"
}

end_sessions() {
  # Before a dump or import: end the stopped app's leftover sessions, such as an
  # ALTER that outlived its JVM, and refuse while anyone else uses the database.
  local mine ids id others deadline
  mine="ID <> CONNECTION_ID() AND HEX(USER) = '$(printf '%s' "$DB_USER" | od -An -tx1 | tr -d ' \n' | tr a-f A-F)'"
  ids=$(admin -e "SELECT ID FROM information_schema.PROCESSLIST WHERE $mine")
  for id in $ids; do
    [[ $id =~ ^[0-9]+$ ]] || die "unexpected session ID"
    admin -e "KILL $id" 2>/dev/null || true  # It may have ended on its own.
  done
  deadline=$((SECONDS + SESSION_TIMEOUT))
  # A killed ALTER stays listed until its rollback finishes.
  while [ "$(admin -e "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE $mine")" != 0 ]; do
    [ "$SECONDS" -lt "$deadline" ] || die "application sessions are still ending; rerun once they end"
    sleep 2
  done
  others=$(admin -e "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID <> CONNECTION_ID() AND DB = '$DATABASE'")
  [ "$others" = 0 ] || die "other sessions use the database; end them (SHOW PROCESSLIST), then rerun"
}

dump() {  # dump NAME: a new, verified and flushed dump in the operation folder
  [ ! -e "$op/$1" ] || die "$op/$1 exists; never overwrite a dump"
  # --lock-tables: MyISAM tables make --single-transaction alone inconsistent.
  # --databases --add-drop-database: a restore recreates the database with its
  # own character set and collation, and drops tables a migration added.
  mariadb-dump "${CLIENT[@]}" --databases "$DATABASE" --add-drop-database --lock-tables --quick \
    --routines --events --triggers | gzip > "$op/$1"
  gzip -t "$op/$1"
  (cd "$op" && sha256sum "$1" > "$1.sha256")
  sync "$op/$1" "$op/$1.sha256" "$op"
}

select_release() {
  ln -sfn "$1" "$ROOT/current.next"
  mv -T "$ROOT/current.next" "$ROOT/current"
  sync "$ROOT"
}

deploy() {
  local zip=$1 sha=$2 backup=$3 allow_jobs=$4 top manifest build want have release previous unit
  local -a entries active=() config_files
  [[ $sha =~ ^[0-9a-f]{64}$ ]] || die "pass the full lowercase SHA-256"
  [ -f "$zip" ] || die "missing $zip"
  lock
  [ ! -e "$FLAG" ] || die "maintenance is on: finish or recover the previous operation first"
  [ -L "$ROOT/current" ] || die "$ROOT/current must link to the running release"
  check_image_cache
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
  if [ ! -f "$manifest" ] || [ ! -f "$top/bin/wlxjury" ]; then
    die "ZIP lacks wlxjury-build.json or bin/wlxjury; rebuild with sbt dist"
  fi
  build=$(json "$manifest" build)
  want=$(json "$manifest" schemaVersion)
  [[ $build =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,95}$ ]] || die "invalid build identity in the ZIP"
  [[ $want =~ ^[0-9]+(\.[0-9]+)*$ ]] || die "invalid schema version in the ZIP"
  release=$ROOT/releases/$build-${sha:0:12}
  [ ! -e "$release" ] || die "$release exists; never overwrite a release"

  check_endpoint
  [ "$(sql 'SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0')" = 0 ] || die "the database has a failed migration"
  have=$(schema)
  # Flyway would refuse an older release only after the outage had started.
  [ "$(printf '%s\n' "$have" "$want" | sort -V | tail -1)" = "$want" ] ||
    die "release expects schema $want but the database is at $have; nothing was changed"
  # Without migrations the database stays as it is, so the release alone can be switched back.
  if [ "$want" != "$have" ]; then backup=yes; fi
  no_enabled_events || die "coordinate enabled database events before deploying"
  check_jobs "$allow_jobs"
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
  # The database identity lets restore refuse a backup aimed at another database.
  printf 'previous=%s\ncandidate=%s\nzip_sha256=%s\nwriters=%s\ndatabase=%s\ndb_host=%s\n' \
    "$previous" "$release" "$sha" "${active[*]}" "$DATABASE" "$DB_HOST" > "$op/record"
  sync "$op/record" "$op"
  echo "Operation $op: $previous -> $release"
  if [ "$backup" = no ]; then echo "No new migrations (schema $have): skipping the database backup"; fi
  if [ -n "$active_jobs" ]; then
    echo "wlxjury-release: warning: deploying with background jobs queued or running (--allow-running-jobs);" \
         "running ones go back to the queue and continue after the restart:" >&2
    echo "$active_jobs" >&2
  fi
  mark started

  enable_maintenance
  hold_restarts
  stop_writers
  no_enabled_events || die "an enabled database event appeared; coordinate it before the backup"
  if [ "$backup" = yes ]; then dump database.sql.gz; fi
  config_files=("${RUNTIME#/}")
  if [ -f /etc/default/wlxjury ]; then config_files+=(etc/default/wlxjury); fi
  tar -C / -czf "$op/config.tgz" "${config_files[@]}"
  sync "$op"/*
  capacity  # The dump must not have used the migration reserve.
  if [ "$backup" = yes ]; then mark backup-complete; else mark backup-skipped; fi

  mark migration-may-start
  select_release "$release"
  systemctl start "$SERVICE"  # Flyway migrates on start.
  wait_ready "$release"
  mark ready
  echo "Ready under maintenance. Smoke-test http://127.0.0.1:$PORT/ locally, then run:"
  echo "  wlxjury-release reopen $(basename "$op")"
}

reopen() {
  local current previous candidate last_restore writers unit
  open_operation "$1"
  if [ ! -e "$FLAG" ] && ! reached traffic-may-resume; then
    die "the maintenance flag is absent; inspect live routing before reopening"
  fi
  last_restore=$(grep -xE 'restoring|database-restored|release-restored' "$op/milestones" | tail -n 1 || true)
  [ "$last_restore" != restoring ] || die "a restore did not finish; rerun wlxjury-release restore"
  previous=$(record_value previous)
  candidate=$(record_value candidate)
  current=$(readlink -f "$ROOT/current")
  if [ "$current" = "$previous" ]; then
    # `current` is switched only after migration-may-start is durable, so without
    # it the database was never migrated. With it, only a finished restore makes
    # the previous release safe again.
    if reached migration-may-start && [ "$last_restore" != database-restored ] &&
       [ "$last_restore" != release-restored ]; then
      die "a migration may have run: run wlxjury-release restore first"
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
  writers=$(record_value writers)
  for unit in $writers; do systemctl start "$unit"; done
  mark complete
  echo "Traffic resumed with $current."
  # Only now, with traffic back on a ready release, may old releases and dumps go. A failure
  # here leaves the deployment complete: exit 3, distinct from a failed operation (1).
  if ! (prune_old yes); then
    local name
    name=$(basename "$op")
    op=''  # Nothing for the runbook: the operation is complete.
    echo "wlxjury-release: PRUNE FAILED after a successful reopen: traffic is open and $name is complete." \
         "Do not rerun reopen; fix the cause above, then run wlxjury-release prune --dry-run and prune." >&2
    exit 3
  fi
}

restore_release() {  # A deployment without migrations: the database stays, the release goes back.
  local previous want
  previous=$(record_value previous)
  if [ ! -f "$previous/wlxjury-build.json" ] || [ ! -x "$previous/bin/wlxjury" ]; then
    die "the previous release $previous is missing"
  fi
  want=$(json "$previous/wlxjury-build.json" schemaVersion)
  check_endpoint
  # A later deployment may have migrated since; only its own backup can undo that.
  if [ "$(sql 'SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0')" != 0 ] || [ "$(schema)" != "$want" ]; then
    die "the database no longer has schema $want of $previous, and this operation has no backup; nothing was changed"
  fi
  enable_maintenance
  hold_restarts
  stop_writers
  select_release "$previous"
  mark release-restored
  systemctl start "$SERVICE"
  wait_ready "$previous"
  echo "Switched back to $previous under maintenance; the database was kept. Check it locally, then run:"
  echo "  wlxjury-release reopen $(basename "$op")"
}

restore() {
  local accept=$2 previous want rescue='' allowance=0
  open_operation "$1"
  if reached backup-skipped; then
    restore_release
    return
  fi
  reached backup-complete || die "this operation has no complete backup; nothing was migrated, so use reopen"
  [ -f "$op/database.sql.gz" ] ||
    die "$op/database.sql.gz is gone (pruned: see $BACKUPS/prune.log); nothing was changed"
  if reached traffic-may-resume && [ "$accept" != yes ]; then
    die "traffic has resumed, so later writes would be lost: rerun with --accept-data-loss after a reconciliation decision"
  fi
  # The backup replaces a whole database: only ever the one it was taken from.
  if [ "$(record_value database)" != "$DATABASE" ] || [ "$(record_value db_host)" != "$DB_HOST" ]; then
    die "the backup is of database $(record_value database) at $(record_value db_host), but runtime.env now names" \
        "$DATABASE at $DB_HOST; nothing was changed"
  fi
  previous=$(record_value previous)
  if [ ! -f "$previous/wlxjury-build.json" ] || [ ! -x "$previous/bin/wlxjury" ]; then
    die "the previous release $previous is missing"
  fi
  want=$(json "$previous/wlxjury-build.json" schemaVersion)
  # Verify the backup completely before anything changes.
  (cd "$op" && sha256sum -c --quiet database.sql.gz.sha256) || die "backup checksum mismatch; nothing was changed"
  gzip -t "$op/database.sql.gz" || die "the backup is not a readable gzip file; nothing was changed"
  check_endpoint
  # Writes since reopening need a rescue dump, unless an unfinished import
  # followed the last reopening: then the database is partly restored (or even
  # dropped), and the rescue dump taken before that import already holds them.
  if [ "$(grep -xE 'traffic-may-resume|restoring' "$op/milestones" | tail -n 1 || true)" = traffic-may-resume ]; then
    rescue=before-restore-$(date -u +%Y%m%dT%H%M%SZ).sql.gz
    allowance=$(database_bytes)
  fi
  capacity $((RESTORE_MIN_FREE + allowance))

  enable_maintenance
  hold_restarts
  stop_writers
  end_sessions
  if [ -n "$rescue" ]; then
    # Earlier rescue dumps are never replaced.
    dump "$rescue"
    mark "rescue $rescue"
  fi
  capacity "$RESTORE_MIN_FREE"
  mark restoring
  # pipefail: a failure on either side of the pipe stops here.
  zcat "$op/database.sql.gz" | admin || die "the import failed; maintenance stays on; rerun restore"
  if [ "$(sql 'SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0')" != 0 ] || [ "$(schema)" != "$want" ]; then
    die "the restored database does not have schema $want of $previous"
  fi
  select_release "$previous"
  mark database-restored
  systemctl start "$SERVICE"
  wait_ready "$previous"
  echo "Restored the pre-deployment database and $previous under maintenance. Check it locally, then run:"
  echo "  wlxjury-release reopen $(basename "$op")"
}

# Retention (docs/deployment.md, "Retention"). Each rule only ever keeps more: what a restore can
# still need is never deleted, and every kept dump keeps the release its restore switches back
# to. Rescue dumps (before-restore-*) and wlxjury-deploy's cutover records are never touched; the
# server's long-term backup is separate. Everything is decided before anything is deleted, so a
# refusal deletes nothing.

setting() {  # setting KEY DEFAULT: a whole number from deployment.json, as decimal
  local value
  value=$(json "$config" "$1") || die "cannot read $1 from $config"
  value=${value:-$2}
  [[ $value =~ ^[0-9]{1,9}$ ]] || die "$1 in $config must be a whole number"
  echo "$((10#$value))"
}

operation_open() {  # operation_open DIR: anything but a finished operation; damaged or unknown counts as open
  if [ -f "$1/record" ]; then
    [ "$(head -n 1 "$1/milestones" 2>/dev/null)" != started ] ||
      [ "$(tail -n 1 "$1/milestones" 2>/dev/null)" != complete ]
  else  # A wlxjury-deploy record of the cutover.
    [ "$(json "$1/deployment.json" phase 2>/dev/null)" != complete ]
  fi
}

record_of() {  # record_of DIR KEY: a value from an operation's record
  sed -n "s/^$2=//p" "$1/record"
}

operation_releases() {  # The previous and candidate releases, one per line; fails if unreadable.
  local previous candidate
  if [ -f "$1/record" ]; then
    previous=$(record_of "$1" previous) || return 1
    candidate=$(record_of "$1" candidate) || return 1
    if [ -z "$previous" ] || [ -z "$candidate" ]; then return 1; fi
  else
    previous=$(json "$1/deployment.json" previous 2>/dev/null) || return 1
    candidate=$(json "$1/deployment.json" candidate 2>/dev/null) || return 1
    [ -n "$previous" ] || return 1  # The cutover names its candidate only once it selects it.
  fi
  printf '%s\n' "$previous"
  if [ -n "$candidate" ]; then printf '%s\n' "$candidate"; fi
}

release_name() {  # release_name PATH: the folder name, if PATH resolves to an entry of releases
  local path
  path=$(realpath -m -- "$1" 2>/dev/null) || return 0
  if [ -n "$1" ] && [ "$(dirname "$path")" = "$(realpath -m -- "$ROOT/releases")" ]; then
    basename "$path"
  fi
}

remove() {  # remove yes|no PATH LABEL [MORE...]: delete PATH and MORE, or say it would
  local apply=$1 path=$2 label=$3 size
  shift 3
  size=$(du -sh "$path" 2>/dev/null | cut -f1)
  if [ "$apply" != yes ]; then
    echo "Would delete $label ($size)"
    return 0
  fi
  rm -rf --one-file-system -- "$path" "$@" || die "could not delete $path; the rest was kept"
  printf '%s deleted %s (%s)\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$path" "$size" >> "$BACKUPS/prune.log"
  echo "Deleted $label ($size)"
}

prune_old() {  # prune_old yes|no: delete (yes) or list (no) what the retention rules let go
  local apply=$1 keep_releases keep_dumps keep_days current current_op=-1 dir name path dump why
  local i order=0 kept=0 count=0 now mtime paths previous
  local -a operations=() names=() lines=() drop_releases=() drop_dumps=() unpacks=()
  # Not "staging": on_exit removes that global, and a local of that name would shadow it.
  local -A last=() keep=()
  # Explicit exits: from reopen this runs where set -e does not apply.
  # By count (the user, 2026-11-04: releases are infrequent); keep_backup_days 0 turns the age
  # rule off, and is the default.
  keep_releases=$(setting keep_previous_releases 4) || exit 1
  keep_dumps=$(setting keep_backup_dumps 5) || exit 1
  keep_days=$(setting keep_backup_days 0) || exit 1
  [ "$keep_releases" -ge 1 ] || die "keep_previous_releases must be at least 1: restore needs the previous release"
  [ "$keep_dumps" -ge 1 ] || die "keep_backup_dumps must be at least 1: restore needs the newest dump"
  [ -L "$ROOT/current" ] || die "$ROOT/current must link to the running release; nothing was deleted"
  current=$(release_name "$(readlink -f "$ROOT/current")")
  [ -n "$current" ] || die "$ROOT/current does not point into $ROOT/releases; nothing was deleted"
  keep[$current]='the current release'

  # Operations oldest first: both tools name their folders by UTC start time. A symlink there
  # could hide an open operation, so it stops the prune; so does a name the line-based lists
  # below can't carry.
  for dir in "$BACKUPS"/* "$ROOT/releases"/*; do
    case $dir in
      *$'\n'* | *$'\t'*) die "a name with a line break or tab in $(dirname "$dir"); rename it by hand; nothing was deleted" ;;
    esac
  done
  for dir in "$BACKUPS"/*; do
    if [ -L "$dir" ] && { [ -e "$dir/record" ] || [ -e "$dir/deployment.json" ]; }; then
      die "$dir is a symlink to an operation folder; resolve it by hand; nothing was deleted"
    fi
  done
  mapfile -t operations < <(
    for dir in "$BACKUPS"/*; do
      if [ -d "$dir" ] && [ ! -L "$dir" ] && { [ -f "$dir/record" ] || [ -f "$dir/deployment.json" ]; }; then
        printf '%s\n' "$dir"
      fi
    done | LC_ALL=C sort)
  for i in "${!operations[@]}"; do
    dir=${operations[i]}
    if ! paths=$(operation_releases "$dir"); then
      if operation_open "$dir"; then
        die "cannot read the releases of the open operation $(basename "$dir"); nothing was deleted"
      elif [ -f "$dir/database.sql.gz" ]; then
        die "cannot read the releases of the operation $(basename "$dir"), which has a dump; nothing was deleted"
      fi
      echo "Note: cannot read the releases of the finished operation $(basename "$dir"); it keeps nothing"
      continue
    fi
    while IFS= read -r path; do
      name=$(release_name "$path")
      if [ -z "$name" ]; then
        if operation_open "$dir"; then
          die "the open operation $(basename "$dir") names $path, outside $ROOT/releases; nothing was deleted"
        elif [ -f "$dir/record" ] && [ -f "$dir/database.sql.gz" ]; then
          die "the operation $(basename "$dir"), which has a dump, names $path, outside $ROOT/releases; nothing was deleted"
        fi
        continue
      fi
      order=$((order + 1))
      last[$name]=$order
      if operation_open "$dir" && [ -z "${keep[$name]:-}" ]; then
        keep[$name]="named by the open operation $(basename "$dir")"
      fi
    done <<< "$paths"
    if [ -f "$dir/record" ] && [ "$(release_name "$(record_of "$dir" candidate)")" = "$current" ]; then
      current_op=$i
    fi
  done

  # Pre-deployment dumps, newest first. A kept dump keeps the release its restore switches back to.
  now=$(date +%s)
  for (( i = ${#operations[@]} - 1; i >= 0; i-- )); do
    dir=${operations[i]}
    dump=$dir/database.sql.gz
    if [ ! -f "$dir/record" ] || [ ! -f "$dump" ]; then continue; fi
    count=$((count + 1))
    if operation_open "$dir"; then why='its operation is open'
    elif [ "$count" -le "$keep_dumps" ]; then why="one of the newest $keep_dumps"
    elif [ "$i" = "$current_op" ]; then why='taken by the operation that deployed the current release'
    elif [ "$keep_days" = 0 ]; then why=''  # The age rule is off.
    elif ! mtime=$(stat -c %Y -- "$dump" 2>/dev/null) || ! [[ $mtime =~ ^[0-9]+$ ]]; then why="its age can't be read"
    elif [ $((now - mtime)) -lt $((keep_days * 86400)) ]; then why="younger than $keep_days days"
    else why=''
    fi
    if [ -z "$why" ]; then
      drop_dumps+=("$dir")
      continue
    fi
    echo "Keep dump $(basename "$dir")/database.sql.gz: $why"
    previous=$(record_of "$dir" previous)
    name=$(release_name "$previous")
    if [ ! -d "$ROOT/releases/$name" ]; then
      echo "Note: the previous release of $(basename "$dir") is gone, so its dump is for a manual import only"
    elif [ -z "${keep[$name]:-}" ]; then
      keep[$name]="restore of the kept dump $(basename "$dir") switches back to it"
    fi
  done

  # Releases by their last use in an operation, newest first; folders no operation names (a
  # deploy that stopped during its checks) after them, newest first. Only folders with a
  # manifest are releases; anything else is left alone.
  for dir in "$ROOT/releases"/*; do
    if [ ! -e "$dir" ] && [ ! -L "$dir" ]; then continue; fi
    name=$(basename "$dir")
    if [ -L "$dir" ]; then
      echo "Skip $name: a symlink, never pruned"
    elif [ ! -d "$dir" ] || [ ! -f "$dir/wlxjury-build.json" ]; then
      echo "Skip $name: not a release folder (no wlxjury-build.json), never pruned"
    else
      mtime=$(stat -c %Y -- "$dir" 2>/dev/null) || mtime=0
      lines+=("${last[$name]:-0}"$'\t'"$mtime"$'\t'"$name")
    fi
  done
  if [ "${#lines[@]}" -gt 0 ]; then
    mapfile -t names < <(printf '%s\n' "${lines[@]}" | LC_ALL=C sort -t $'\t' -k1,1nr -k2,2nr | cut -f3-)
  fi
  # The current release aside, the newest keep_previous_releases stay whatever else keeps
  # them, and a release something else keeps stays beyond that count too.
  for name in "${names[@]}"; do
    if [ -n "${keep[$name]:-}" ]; then
      echo "Keep release $name: ${keep[$name]}"
      if [ "$name" != "$current" ]; then kept=$((kept + 1)); fi
    elif [ "$kept" -lt "$keep_releases" ]; then
      kept=$((kept + 1))
      echo "Keep release $name: one of the $keep_releases used most recently besides the current one"
    else
      drop_releases+=("$name")
    fi
  done
  for dir in "$ROOT/releases"/.staging.*; do
    if [ -d "$dir" ] && [ ! -L "$dir" ]; then unpacks+=("$dir"); fi
  done

  # Only now, with every decision made, delete.
  for name in "${drop_releases[@]}"; do
    remove "$apply" "$ROOT/releases/$name" "release $name" || exit 1
  done
  for dir in "${unpacks[@]}"; do
    remove "$apply" "$dir" "the unfinished unpack $(basename "$dir")" || exit 1
  done
  for dir in "${drop_dumps[@]}"; do
    remove "$apply" "$dir/database.sql.gz" "dump $(basename "$dir")/database.sql.gz" "$dir/database.sql.gz.sha256" || exit 1
    if [ "$apply" = yes ]; then
      echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) database.sql.gz pruned by wlxjury-release prune" >> "$dir/operation.log"
    fi
  done
  echo "Rescue dumps (before-restore-*) and the cutover's records are never pruned; the long-term backup is separate."
}

prune() {
  lock
  [ ! -e "$FLAG" ] || die "maintenance is on: finish the open operation (reopen or restore) before pruning"
  prune_old "$1"
}

case ${1:-} in
  prune)
    if [ $# = 1 ]; then prune yes
    elif [ $# = 2 ] && [ "$2" = --dry-run ]; then prune no
    else usage
    fi ;;
  deploy)
    [ $# -ge 3 ] || usage
    zip=$2 sha=$3 backup=no allow_jobs=no
    shift 3
    for option in "$@"; do  # Each option at most once, in any order.
      case $option in
        --backup) [ "$backup" = no ] || usage; backup=yes ;;
        --allow-running-jobs) [ "$allow_jobs" = no ] || usage; allow_jobs=yes ;;
        *) usage ;;
      esac
    done
    deploy "$zip" "$sha" "$backup" "$allow_jobs" ;;
  reopen) [ $# = 2 ] || usage; reopen "$2" ;;
  restore)
    if [ $# = 2 ]; then restore "$2" no
    elif [ $# = 3 ] && [ "$3" = --accept-data-loss ]; then restore "$2" yes
    else usage
    fi ;;
  *) usage ;;
esac
