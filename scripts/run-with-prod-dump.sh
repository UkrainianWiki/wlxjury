#!/usr/bin/env bash
# scripts/run-with-prod-dump.sh
#
# Dumps the production DB, restores it into a local MariaDB 10.6 Docker
# container, and starts the sbt dev server against it.
#
# Usage:
#   ./scripts/run-with-prod-dump.sh            # full flow: dump → restore → run
#   ./scripts/run-with-prod-dump.sh --skip-dump # re-use existing /tmp/wlxjury-prod.sql.gz
#   ./scripts/run-with-prod-dump.sh --dump-only # dump and restore, do not start sbt
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"
ENV_FILE="$REPO_DIR/.env"

# ── Usage ──────────────────────────────────────────────────────────────────────
usage() {
  cat <<'EOF'
Usage:
  ./scripts/run-with-prod-dump.sh                  # full flow: dump → restore → run
  ./scripts/run-with-prod-dump.sh --skip-dump       # re-use existing /tmp/wlxjury-prod.sql.gz
  ./scripts/run-with-prod-dump.sh --dump-only       # dump and restore, do not start sbt
  ./scripts/run-with-prod-dump.sh --reuse-container # skip dump+restore, use running container
EOF
}

# ── Helpers ────────────────────────────────────────────────────────────────────
format_duration() {
  local secs=$1
  if [[ $secs -lt 60 ]]; then
    echo "${secs}s"
  else
    echo "$((secs / 60))m $((secs % 60))s"
  fi
}

# ── Argument parsing ───────────────────────────────────────────────────────────
SKIP_DUMP=false
DUMP_ONLY=false
REUSE_CONTAINER=false

for arg in "$@"; do
  case "$arg" in
    --skip-dump)       SKIP_DUMP=true ;;
    --dump-only)       DUMP_ONLY=true ;;
    --reuse-container) REUSE_CONTAINER=true ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $arg" >&2
      exit 1
      ;;
  esac
done

if [[ "$REUSE_CONTAINER" == true && "$DUMP_ONLY" == true ]]; then
  echo "ERROR: --reuse-container and --dump-only cannot be used together." >&2
  exit 1
fi

# ── Load prod credentials from .env ───────────────────────────────────────────
if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: $ENV_FILE not found. Create it with WLXJURY_DB_HOST, WLXJURY_DB, WLXJURY_DB_USER, WLXJURY_DB_PASSWORD." >&2
  exit 1
fi
set -a
# shellcheck source=/dev/null
source "$ENV_FILE"
set +a

: "${WLXJURY_DB_HOST:?WLXJURY_DB_HOST must be set in .env}"
: "${WLXJURY_DB:?WLXJURY_DB must be set in .env}"
: "${WLXJURY_DB_USER:?WLXJURY_DB_USER must be set in .env}"
: "${WLXJURY_DB_PASSWORD:?WLXJURY_DB_PASSWORD must be set in .env}"

# ── Local container config ─────────────────────────────────────────────────────
CONTAINER_NAME="wlxjury-local-dev"
LOCAL_PORT=3307
LOCAL_DB="wlxjury"
LOCAL_USER="wlxjury_user"
LOCAL_PASSWORD="wlxjury_localpass"
LOCAL_ROOT_PASSWORD="wlxjury_rootpass"
DUMP_FILE="/tmp/wlxjury-prod.sql.gz"
CONTAINER_STARTED=false

# ── Credentials files ──────────────────────────────────────────────────────────
# No password goes on a command line (`docker -e NAME=value` shows it in the
# process list). The prod client reads a temporary option file, mode 600, mounted
# read-only into its container; the local container gets its passwords from an
# --env-file, and its root client reads /root/.my.cnf inside the container. The
# host files live in SECRETS_DIR (mode 700), removed as soon as they are used and
# by the EXIT trap on every other path.
SECRETS_DIR=""

remove_secrets() {
  if [[ -n "$SECRETS_DIR" ]]; then
    rm -rf "$SECRETS_DIR" 2>/dev/null || true
    SECRETS_DIR=""
  fi
}

make_secrets_dir() {
  if [[ -z "$SECRETS_DIR" ]]; then
    SECRETS_DIR="$(umask 077 && mktemp -d "${TMPDIR:-/tmp}/wlxjury-dump.XXXXXX")"
    chmod 700 "$SECRETS_DIR"
  fi
}

# A host path as Docker takes it: C:/... under Git Bash (MSYS), else unchanged.
host_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

# An option file value in double quotes, its backslashes and double quotes escaped.
cnf_value() {
  local v=$1
  v=${v//\\/\\\\}
  v=${v//\"/\\\"}
  printf '"%s"' "$v"
}

# Writes a [client] option file with the user and the password to $1, mode 600.
write_client_cnf() {
  local file=$1 user=$2 password=$3
  (
    umask 077
    printf '[client]\nuser=%s\npassword=%s\n' "$(cnf_value "$user")" "$(cnf_value "$password")" > "$file"
  )
  chmod 600 "$file"
}

# The local container's root client: /root/.my.cnf (mode 600) inside the container,
# written there from the container's own MARIADB_ROOT_PASSWORD. Idempotent.
ensure_local_root_cnf() {
  # single quotes: the container's shell expands the variable, not this one
  # shellcheck disable=SC2016
  docker exec "$CONTAINER_NAME" sh -c \
    'umask 077; printf "[client]\nuser=root\npassword=\"%s\"\n" "$MARIADB_ROOT_PASSWORD" > /root/.my.cnf'
}

# ── Cleanup on exit ────────────────────────────────────────────────────────────
cleanup() {
  local exit_code=$?
  remove_secrets
  rm -f "${DUMP_FILE}.tmp" 2>/dev/null || true
  if [[ $exit_code -ne 0 ]]; then
    echo ""
    echo "Script exited with code $exit_code."
    if [[ "$CONTAINER_STARTED" == true ]]; then
      echo ""
      echo "Container '$CONTAINER_NAME' may still be running on port $LOCAL_PORT."
      echo "  Inspect : docker exec -it $CONTAINER_NAME mysql $LOCAL_DB"
      echo "  Remove  : docker rm -f $CONTAINER_NAME"
    fi
  fi
}
trap cleanup EXIT
# A signal ends the script through exit, so the EXIT trap still removes the files.
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

echo "==> Config"
echo "    Prod host       : $WLXJURY_DB_HOST"
echo "    Prod DB         : $WLXJURY_DB"
echo "    Local port      : $LOCAL_PORT"
echo "    Dump file       : $DUMP_FILE"
echo "    Skip dump       : $SKIP_DUMP"
echo "    Dump only       : $DUMP_ONLY"
echo "    Reuse container : $REUSE_CONTAINER"

if [[ "$REUSE_CONTAINER" == true ]]; then
  # ── Reuse path ──────────────────────────────────────────────────────────────
  echo "==> Reusing existing container '$CONTAINER_NAME' on port $LOCAL_PORT (skipping dump and restore)..."
  if [[ "$(docker inspect --format='{{.State.Running}}' "$CONTAINER_NAME" 2>/dev/null)" != "true" ]]; then
    echo "ERROR: --reuse-container set but container '$CONTAINER_NAME' is not running." >&2
    echo "       Start one first:  ./scripts/run-with-prod-dump.sh --dump-only" >&2
    exit 1
  fi
  CONTAINER_STARTED=true
  ensure_local_root_cnf
  reuse_db_size=$(docker exec \
    "$CONTAINER_NAME" \
    mysql -sNe \
      "SELECT CONCAT(ROUND(SUM(data_length + index_length) / 1024 / 1024, 1), ' MB') FROM information_schema.tables WHERE table_schema = '$LOCAL_DB';" \
    2>/dev/null) || reuse_db_size="unknown"
  echo "    DB size: $reuse_db_size"

else
  # ── Phase 1: Dump ────────────────────────────────────────────────────────────
  dump_prod_db() {
    local tmp_file="${DUMP_FILE}.tmp"
    local start=$SECONDS
    echo "==> Dumping prod DB (via docker mariadb:10.6 client)..."
    make_secrets_dir
    write_client_cnf "$SECRETS_DIR/prod.cnf" "$WLXJURY_DB_USER" "$WLXJURY_DB_PASSWORD"
    # --defaults-extra-file must be the client's first option. MSYS_NO_PATHCONV:
    # Git Bash would rewrite the container paths as Windows ones.
    MSYS_NO_PATHCONV=1 docker run --rm \
      -v "$(host_path "$SECRETS_DIR/prod.cnf"):/etc/wlxjury-dump/client.cnf:ro" \
      mariadb:10.6 \
      mysqldump \
        --defaults-extra-file=/etc/wlxjury-dump/client.cnf \
        -h "$WLXJURY_DB_HOST" \
        --single-transaction \
        --quick \
        --triggers \
        --routines \
        "$WLXJURY_DB" \
    | gzip > "$tmp_file"
    remove_secrets
    mv "$tmp_file" "$DUMP_FILE"
    echo "    Dump written to $DUMP_FILE  $(du -sh "$DUMP_FILE" | cut -f1)  (took $(format_duration "$((SECONDS - start))"))"
  }

  if [[ "$SKIP_DUMP" == true ]]; then
    if [[ ! -f "$DUMP_FILE" ]]; then
      echo "ERROR: --skip-dump set but $DUMP_FILE does not exist." >&2
      exit 1
    fi
    echo "==> Skipping dump; using existing $DUMP_FILE ($(du -sh "$DUMP_FILE" | cut -f1))"
  else
    dump_prod_db
  fi

  # ── Phase 2: Start local MariaDB container ──────────────────────────────────
  echo "==> Starting local MariaDB container '$CONTAINER_NAME'..."

  if docker inspect "$CONTAINER_NAME" &>/dev/null; then
    echo "    Removing existing container..."
    docker rm -f "$CONTAINER_NAME"
  fi

  # Server tuning args (passed through to mariadbd):
  #  * --innodb-use-native-aio=0 : on hosts where io_uring is blocked
  #    (Docker Desktop / WSL2 set kernel.io_uring_disabled=2), mariadbd 10.6
  #    logs "io_uring_queue_init() failed with EPERM" and then a tpool I/O
  #    thread busy-loops at ~100% CPU (see MDEV-31122). Disabling native AIO
  #    up front skips that code path entirely.
  #  * The remaining flags just make the multi-GB dump restore faster; this
  #    is a throwaway dev container, so durability settings are safe to relax.
  make_secrets_dir
  (
    umask 077
    printf 'MARIADB_DATABASE=%s\nMARIADB_USER=%s\nMARIADB_PASSWORD=%s\nMARIADB_ROOT_PASSWORD=%s\n' \
      "$LOCAL_DB" "$LOCAL_USER" "$LOCAL_PASSWORD" "$LOCAL_ROOT_PASSWORD" > "$SECRETS_DIR/local.env"
  )
  docker run -d \
    --name "$CONTAINER_NAME" \
    --env-file "$SECRETS_DIR/local.env" \
    -p "${LOCAL_PORT}:3306" \
    mariadb:10.6 \
    --innodb-use-native-aio=0 \
    --innodb-buffer-pool-size=1G \
    --innodb-flush-log-at-trx-commit=0 \
    --skip-innodb-doublewrite \
    --skip-innodb-buffer-pool-dump-at-shutdown \
    --skip-innodb-buffer-pool-load-at-startup
  CONTAINER_STARTED=true
  remove_secrets

  echo -n "    Waiting for MariaDB to accept connections"
  retries=60
  until docker exec "$CONTAINER_NAME" \
        mysqladmin ping -h 127.0.0.1 --silent 2>/dev/null; do
    retries=$((retries - 1))
    if [[ $retries -le 0 ]]; then
      echo ""
      echo "ERROR: MariaDB container did not become healthy in time." >&2
      docker logs "$CONTAINER_NAME" >&2
      exit 1
    fi
    echo -n "."
    sleep 1
  done
  echo " ready"
  ensure_local_root_cnf

  # ── Phase 3: Restore dump ───────────────────────────────────────────────────
  echo "==> Restoring dump into local container..."
  restore_start=$SECONDS
  zcat < "$DUMP_FILE" \
    | docker exec -i \
        "$CONTAINER_NAME" \
        mysql \
          --init-command="SET SESSION unique_checks=0, foreign_key_checks=0" \
          "$LOCAL_DB"
  restore_elapsed=$((SECONDS - restore_start))
  db_size=$(docker exec \
    "$CONTAINER_NAME" \
    mysql -sNe \
      "SELECT CONCAT(ROUND(SUM(data_length + index_length) / 1024 / 1024, 1), ' MB') FROM information_schema.tables WHERE table_schema = '$LOCAL_DB';" \
    2>/dev/null) || db_size="unknown"
  echo "    Restore complete  (took $(format_duration "$restore_elapsed"))  |  DB size: $db_size"

  if [[ "$DUMP_ONLY" == true ]]; then
    echo "==> --dump-only set; skipping sbt run."
    echo "    Container '$CONTAINER_NAME' left running on port $LOCAL_PORT."
    echo "    Connect: mysql -h 127.0.0.1 -P $LOCAL_PORT -u $LOCAL_USER -p$LOCAL_PASSWORD $LOCAL_DB"
    exit 0
  fi

fi

# ── Phase 4: Run dev server ────────────────────────────────────────────────────
echo "==> Starting sbt dev server against local prod dump..."
echo "    DB  : jdbc:mariadb://127.0.0.1:${LOCAL_PORT}/${LOCAL_DB}"
echo "    Stop: Ctrl-C  (container '$CONTAINER_NAME' remains; remove: docker rm -f $CONTAINER_NAME)"
echo ""

WLXJURY_DB_HOST="127.0.0.1:${LOCAL_PORT}" \
WLXJURY_DB="$LOCAL_DB" \
WLXJURY_DB_USER="$LOCAL_USER" \
WLXJURY_DB_PASSWORD="$LOCAL_PASSWORD" \
  sbt -Ddb.default.migration.auto=true run
