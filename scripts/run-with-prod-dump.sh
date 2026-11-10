#!/usr/bin/env bash
# scripts/run-with-prod-dump.sh
#
# Dumps the production DB, restores it into a local MariaDB 10.6 Docker
# container, and starts the sbt dev server against it.
#
# Usage:
#   ./scripts/run-with-prod-dump.sh            # full flow: dump → restore → run
#   ./scripts/run-with-prod-dump.sh --skip-dump # re-use the existing dump
#   ./scripts/run-with-prod-dump.sh --dump-only # dump and restore, do not start sbt
#
# The restored dump holds the users' emails and password hashes: the dump is kept in a
# directory of its owner's only (${XDG_CACHE_HOME:-$HOME/.cache}/wlxjury, mode 700, the file
# mode 600), the container publishes its port on 127.0.0.1 only, and its passwords are
# generated for each new container (docs/security/secure-coding.md §4).
#
# The dump's connection to the production DB is not encrypted by this script (the mariadb
# 10.6 client's defaults): it must go through an SSH tunnel to the DB server (for example
# `ssh -L 3306:127.0.0.1:3306 <server>` on this machine), or to a server that takes TLS with
# a verified certificate. Never point it at the DB server across a network directly.
# mysqldump runs in a container, where 127.0.0.1 is the container itself: with Docker
# Desktop (Windows, macOS) WLXJURY_DB_HOST is host.docker.internal:<tunnel port>; on Linux the
# client container shares this machine's network (--network host), so it is 127.0.0.1:<port>.
# Docker Desktop on Linux, and WSL2 with Docker Desktop, count as Linux here, but their host
# network is the Desktop VM's: they too use host.docker.internal:<port>.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"
ENV_FILE="$REPO_DIR/.env"

# ── Usage ──────────────────────────────────────────────────────────────────────
usage() {
  cat <<'EOF'
Usage:
  ./scripts/run-with-prod-dump.sh                  # full flow: dump → restore → run
  ./scripts/run-with-prod-dump.sh --skip-dump       # re-use the existing dump (see below)
  ./scripts/run-with-prod-dump.sh --dump-only       # dump and restore, do not start sbt
  ./scripts/run-with-prod-dump.sh --reuse-container # skip dump+restore, use running container

Only the dump reads .env (WLXJURY_DB_HOST as host or host:port, WLXJURY_DB,
WLXJURY_DB_USER, WLXJURY_DB_PASSWORD): --skip-dump and --reuse-container need none.
The dump's connection is not encrypted by this script: it must go through an SSH tunnel
to the DB server, or to a server that takes TLS. mysqldump runs in a container: for a
tunnel on this machine, WLXJURY_DB_HOST is host.docker.internal:<port> with Docker
Desktop (Windows, macOS), 127.0.0.1:<port> on Linux (the container uses --network host).
Docker Desktop on Linux and WSL2 with Docker Desktop use host.docker.internal:<port> too.
The dump is kept in ${XDG_CACHE_HOME:-$HOME/.cache}/wlxjury/prod.sql.gz (directory mode 700).
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

# ── Local container config ─────────────────────────────────────────────────────
# The container publishes its port on the loopback interface only: nothing but this
# machine needs to reach the restored data. Its passwords are generated for each new
# container (never literals in this public script); a reused container's is read back
# from its own environment.
CONTAINER_NAME="wlxjury-local-dev"
LOCAL_BIND="127.0.0.1"
LOCAL_PORT=3307
LOCAL_DB="wlxjury"
LOCAL_USER="wlxjury_user"
LOCAL_PASSWORD=""
# In a directory of this user's only, not the shared /tmp: there another local user could
# create the file (or a symlink) first and have the dump written through it.
DUMP_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/wlxjury"
DUMP_FILE="$DUMP_DIR/prod.sql.gz"
OLD_DUMP_FILE="/tmp/wlxjury-prod.sql.gz"
DUMP_TMP=""
CONTAINER_STARTED=false
PROD_HOST=""
PROD_PORT=""

# 32 hex characters from /dev/urandom: nothing to quote in an env file or option file.
random_password() {
  od -An -tx1 -N16 /dev/urandom | tr -d ' \n'
}

# ── Load prod credentials from .env ───────────────────────────────────────────
# Only the dump needs them.
load_prod_env() {
  if [[ ! -f "$ENV_FILE" ]]; then
    echo "ERROR: $ENV_FILE not found. Create it with WLXJURY_DB_HOST, WLXJURY_DB, WLXJURY_DB_USER, WLXJURY_DB_PASSWORD." >&2
    exit 1
  fi
  set -a
  # shellcheck source=/dev/null
  source "$ENV_FILE"
  set +a
  # Not in the environment of the commands below (docker, gzip, zcat): the password goes
  # only into the option file, and phase 4 passes sbt the local values explicitly.
  export -n WLXJURY_DB_PASSWORD

  : "${WLXJURY_DB_HOST:?WLXJURY_DB_HOST must be set in .env}"
  : "${WLXJURY_DB:?WLXJURY_DB must be set in .env}"
  : "${WLXJURY_DB_USER:?WLXJURY_DB_USER must be set in .env}"
  : "${WLXJURY_DB_PASSWORD:?WLXJURY_DB_PASSWORD must be set in .env}"

  # The app's host or host:port (as wlxjury-deploy.py reads it): the client gets the port
  # apart (-P), or it would look the whole of it up as a host name.
  if [[ "$WLXJURY_DB_HOST" =~ ^([A-Za-z0-9_.-]+)(:([0-9]+))?$ ]]; then
    PROD_HOST="${BASH_REMATCH[1]}"
    PROD_PORT="${BASH_REMATCH[3]:-3306}"
  else
    echo "ERROR: WLXJURY_DB_HOST in .env must be a host name or host:port." >&2
    exit 1
  fi
}

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

# The dump's directory: created mode 700, and refused unless it is a directory of this user
# (not a symlink), so that only this user can create or replace files in it.
ensure_dump_dir() {
  ( umask 077 && mkdir -p "$DUMP_DIR" )
  if [[ -L "$DUMP_DIR" || ! -d "$DUMP_DIR" || ! -O "$DUMP_DIR" ]]; then
    echo "ERROR: $DUMP_DIR must be a directory of this user, not a symlink." >&2
    exit 1
  fi
  chmod 700 "$DUMP_DIR"
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
  if [[ -n "$DUMP_TMP" ]]; then rm -f "$DUMP_TMP" 2>/dev/null || true; fi
  if [[ $exit_code -ne 0 ]]; then
    echo ""
    echo "Script exited with code $exit_code."
    if [[ "$CONTAINER_STARTED" == true ]]; then
      echo ""
      echo "Container '$CONTAINER_NAME' may still be running on $LOCAL_BIND:$LOCAL_PORT."
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

if [[ "$REUSE_CONTAINER" != true && "$SKIP_DUMP" != true ]]; then
  load_prod_env
fi

echo "==> Config"
if [[ -n "$PROD_HOST" ]]; then
  echo "    Prod host       : $PROD_HOST (port $PROD_PORT)"
  echo "    Prod DB         : $WLXJURY_DB"
fi
echo "    Local port      : $LOCAL_BIND:$LOCAL_PORT"
echo "    Dump file       : $DUMP_FILE"
echo "    Skip dump       : $SKIP_DUMP"
echo "    Dump only       : $DUMP_ONLY"
echo "    Reuse container : $REUSE_CONTAINER"

if [[ "$REUSE_CONTAINER" == true ]]; then
  # ── Reuse path ──────────────────────────────────────────────────────────────
  echo "==> Reusing existing container '$CONTAINER_NAME' on $LOCAL_BIND:$LOCAL_PORT (skipping dump and restore)..."
  if [[ "$(docker inspect --format='{{.State.Running}}' "$CONTAINER_NAME" 2>/dev/null)" != "true" ]]; then
    echo "ERROR: --reuse-container set but container '$CONTAINER_NAME' is not running." >&2
    echo "       Start one first:  ./scripts/run-with-prod-dump.sh --dump-only" >&2
    exit 1
  fi
  # Its 3306 published exactly on $LOCAL_BIND:$LOCAL_PORT: otherwise sbt (with
  # migration.auto) would reach whatever else answers on that port, and a container made
  # before the loopback binding would still offer the data on every interface.
  published="$(docker port "$CONTAINER_NAME" 3306/tcp 2>/dev/null || true)"
  if [[ "$published" != "$LOCAL_BIND:$LOCAL_PORT" ]]; then
    echo "ERROR: container '$CONTAINER_NAME' publishes 3306 as '${published:-nothing}', not $LOCAL_BIND:$LOCAL_PORT." >&2
    echo "       Recreate it:  docker rm -f $CONTAINER_NAME && ./scripts/run-with-prod-dump.sh --skip-dump --dump-only" >&2
    exit 1
  fi
  CONTAINER_STARTED=true
  # its own password, generated when it was created; printed by the container to this
  # shell's variable, never on a command line
  LOCAL_PASSWORD="$(docker exec "$CONTAINER_NAME" printenv MARIADB_PASSWORD 2>/dev/null || true)"
  if [[ -z "$LOCAL_PASSWORD" ]]; then
    echo "ERROR: container '$CONTAINER_NAME' has no MARIADB_PASSWORD; recreate it (see above)." >&2
    exit 1
  fi
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
    local start=$SECONDS
    echo "==> Dumping prod DB (via docker mariadb:10.6 client)..."
    ensure_dump_dir
    # a new file of its own (mktemp: created exclusively, mode 600), renamed over the dump
    DUMP_TMP="$(mktemp "$DUMP_DIR/prod.sql.gz.XXXXXX")"
    local tmp_file="$DUMP_TMP"
    make_secrets_dir
    write_client_cnf "$SECRETS_DIR/prod.cnf" "$WLXJURY_DB_USER" "$WLXJURY_DB_PASSWORD"
    # --defaults-extra-file must be the client's first option. MSYS_NO_PATHCONV:
    # Git Bash would rewrite the container paths as Windows ones. The dump is created
    # mode 600 (umask 077 in this subshell), whatever the caller's umask.
    # On Linux the client shares this machine's network, so that a tunnel on 127.0.0.1 is
    # reached; Docker Desktop reaches it as host.docker.internal (see the header).
    local network=()
    if [[ "$(uname -s)" == Linux ]]; then network=(--network host); fi
    (
      umask 077
      MSYS_NO_PATHCONV=1 docker run --rm ${network[@]+"${network[@]}"} \
        -v "$(host_path "$SECRETS_DIR/prod.cnf"):/etc/wlxjury-dump/client.cnf:ro" \
        mariadb:10.6 \
        mysqldump \
          --defaults-extra-file=/etc/wlxjury-dump/client.cnf \
          -h "$PROD_HOST" \
          -P "$PROD_PORT" \
          --protocol=TCP \
          --single-transaction \
          --quick \
          --triggers \
          --routines \
          "$WLXJURY_DB" \
      | gzip > "$tmp_file"
    )
    remove_secrets
    chmod 600 "$tmp_file"
    mv -f "$tmp_file" "$DUMP_FILE"
    DUMP_TMP=""
    echo "    Dump written to $DUMP_FILE  $(du -sh "$DUMP_FILE" | cut -f1)  (took $(format_duration "$((SECONDS - start))"))"
  }

  if [[ -f "$OLD_DUMP_FILE" ]]; then
    echo "    NOTE: a dump of before is still at $OLD_DUMP_FILE, in the shared /tmp: delete it"
    echo "          (rm -f $OLD_DUMP_FILE); dumps are now kept in $DUMP_DIR."
  fi
  if [[ "$SKIP_DUMP" == true ]]; then
    ensure_dump_dir
    if [[ ! -f "$DUMP_FILE" || -L "$DUMP_FILE" ]]; then
      echo "ERROR: --skip-dump set but $DUMP_FILE does not exist (or is a symlink)." >&2
      exit 1
    fi
    chmod 600 "$DUMP_FILE"
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
  LOCAL_PASSWORD="$(random_password)"
  make_secrets_dir
  (
    umask 077
    printf 'MARIADB_DATABASE=%s\nMARIADB_USER=%s\nMARIADB_PASSWORD=%s\nMARIADB_ROOT_PASSWORD=%s\n' \
      "$LOCAL_DB" "$LOCAL_USER" "$LOCAL_PASSWORD" "$(random_password)" > "$SECRETS_DIR/local.env"
  )
  docker run -d \
    --name "$CONTAINER_NAME" \
    --env-file "$SECRETS_DIR/local.env" \
    -p "${LOCAL_BIND}:${LOCAL_PORT}:3306" \
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
    echo "    Container '$CONTAINER_NAME' left running on $LOCAL_BIND:$LOCAL_PORT."
    echo "    Connect: docker exec -it $CONTAINER_NAME mysql $LOCAL_DB"
    echo "    (its password: docker exec $CONTAINER_NAME printenv MARIADB_PASSWORD)"
    exit 0
  fi

fi

# ── Phase 4: Run dev server ────────────────────────────────────────────────────
echo "==> Starting sbt dev server against local prod dump..."
echo "    DB  : jdbc:mariadb://${LOCAL_BIND}:${LOCAL_PORT}/${LOCAL_DB}"
echo "    Stop: Ctrl-C  (container '$CONTAINER_NAME' remains; remove: docker rm -f $CONTAINER_NAME)"
echo ""

WLXJURY_DB_HOST="${LOCAL_BIND}:${LOCAL_PORT}" \
WLXJURY_DB="$LOCAL_DB" \
WLXJURY_DB_USER="$LOCAL_USER" \
WLXJURY_DB_PASSWORD="$LOCAL_PASSWORD" \
  sbt -Ddb.default.migration.auto=true run
