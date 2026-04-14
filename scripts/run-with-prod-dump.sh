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

# ── Cleanup hint on exit ───────────────────────────────────────────────────────
cleanup() {
  local exit_code=$?
  rm -f "${DUMP_FILE}.tmp" 2>/dev/null || true
  if [[ $exit_code -ne 0 ]]; then
    echo ""
    echo "Script exited with code $exit_code."
    if [[ "$CONTAINER_STARTED" == true ]]; then
      echo ""
      echo "Container '$CONTAINER_NAME' may still be running on port $LOCAL_PORT."
      echo "  Inspect : docker exec -it $CONTAINER_NAME mysql -u $LOCAL_USER -p$LOCAL_PASSWORD $LOCAL_DB"
      echo "  Remove  : docker rm -f $CONTAINER_NAME"
    fi
  fi
}
trap cleanup EXIT

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
  reuse_db_size=$(docker exec \
    -e MYSQL_PWD="$LOCAL_ROOT_PASSWORD" \
    "$CONTAINER_NAME" \
    mysql -u root -sNe \
      "SELECT CONCAT(ROUND(SUM(data_length + index_length) / 1024 / 1024, 1), ' MB') FROM information_schema.tables WHERE table_schema = '$LOCAL_DB';" \
    2>/dev/null) || reuse_db_size="unknown"
  echo "    DB size: $reuse_db_size"

else
  # ── Phase 1: Dump ────────────────────────────────────────────────────────────
  dump_prod_db() {
    local tmp_file="${DUMP_FILE}.tmp"
    local start=$SECONDS
    echo "==> Dumping prod DB (via docker mariadb:10.6 client)..."
    docker run --rm \
      -e MYSQL_PWD="$WLXJURY_DB_PASSWORD" \
      mariadb:10.6 \
      mysqldump \
        -h "$WLXJURY_DB_HOST" \
        -u "$WLXJURY_DB_USER" \
        --single-transaction \
        --quick \
        --triggers \
        --routines \
        "$WLXJURY_DB" \
    | gzip > "$tmp_file"
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

  docker run -d \
    --name "$CONTAINER_NAME" \
    -e MARIADB_DATABASE="$LOCAL_DB" \
    -e MARIADB_USER="$LOCAL_USER" \
    -e MARIADB_PASSWORD="$LOCAL_PASSWORD" \
    -e MARIADB_ROOT_PASSWORD="$LOCAL_ROOT_PASSWORD" \
    -p "${LOCAL_PORT}:3306" \
    mariadb:10.6
  CONTAINER_STARTED=true

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

  # ── Phase 3: Restore dump ───────────────────────────────────────────────────
  echo "==> Restoring dump into local container..."
  restore_start=$SECONDS
  zcat < "$DUMP_FILE" \
    | docker exec -i \
        -e MYSQL_PWD="$LOCAL_ROOT_PASSWORD" \
        "$CONTAINER_NAME" \
        mysql -u root "$LOCAL_DB"
  restore_elapsed=$((SECONDS - restore_start))
  db_size=$(docker exec \
    -e MYSQL_PWD="$LOCAL_ROOT_PASSWORD" \
    "$CONTAINER_NAME" \
    mysql -u root -sNe \
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
