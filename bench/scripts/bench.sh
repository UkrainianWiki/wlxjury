#!/usr/bin/env bash
# The production-like benchmark environment (docs/plans/2026-10-07-prod-like-benchmark.md).
#
#   bench.sh image                       build the server image (bench/server)
#   bench.sh dist <name> <commit>        `sbt dist` of a commit (git archive, built outside the
#                                        repo) unzipped into the dists volume as <name>
#   bench.sh restore-dump <dump.sql.gz>  restore a production dump into the data volume and
#                                        anonymise it at once (bench/scripts/anonymise.sql)
#   bench.sh start <dist|-> [cnf]        start the server: MariaDB (+ the app <dist>), with
#                                        bench/cnf/<cnf>.cnf as 60-wlxjury.cnf if given
#   bench.sh stop                        stop it cleanly (the app, then MariaDB)
#   bench.sh wait-app                    wait until the app answers on /auth
#   bench.sh save <snap> / load <snap>   copy the stopped server's datadir to / from a volume
#   bench.sh sql [args]                  mariadb client in the container, as root
#   bench.sh drop-caches                 drop the Docker VM's page cache (a cold start)
#   bench.sh status <file>               SHOW GLOBAL STATUS into a file
#   bench.sh nmt <file>                  jcmd VM.native_memory summary of the app
#
# Personal data: the dump goes from the host file straight into the container's database
# (a Docker volume) and is anonymised there before anything else reads it. Snapshots are
# volumes too. Nothing derived from the dump is written to the repository.
set -euo pipefail

NAME=${BENCH_CONTAINER:-wlxjury-bench}
IMAGE=wlxjury-bench-server
DATA_VOL=wlxjury-bench-mysql
DISTS_VOL=wlxjury-bench-dists
APP_PORT=${BENCH_APP_PORT:-19000}
DB_PORT=${BENCH_DB_PORT:-13306}
REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
SCRATCH=${BENCH_SCRATCH:?set BENCH_SCRATCH to a directory outside the repository (builds)}
# Docker Desktop on Windows needs Windows paths for bind mounts
winpath() { if command -v cygpath >/dev/null; then cygpath -w "$1"; else echo "$1"; fi; }
# Git Bash would rewrite container paths (/var/lib/mysql) as Windows paths
dk() { MSYS_NO_PATHCONV=1 docker "$@"; }

log() { echo "[bench] $(date +%H:%M:%S) $*" >&2; }

cmd=${1:-}; shift || true
case "$cmd" in
  image)
    dk build -t "$IMAGE" "$REPO/bench/server"
    ;;

  dist)
    dname=$1 commit=$2
    src="$SCRATCH/build/$dname"
    rm -rf "$src"; mkdir -p "$src"
    git -C "$REPO" archive "$commit" | tar -x -C "$src"
    log "sbt dist of $commit in $src"
    (cd "$src" && sbt -Dsbt.server.forcestart=true -batch dist)
    zip=$(ls "$src"/target/universal/*.zip | head -1)
    rm -rf "$SCRATCH/dists/$dname"; mkdir -p "$SCRATCH/dists/$dname"
    unzip -q "$zip" -d "$SCRATCH/dists/$dname.tmp"
    mv "$SCRATCH/dists/$dname.tmp"/*/* "$SCRATCH/dists/$dname/"; rm -rf "$SCRATCH/dists/$dname.tmp"
    echo "$commit" > "$SCRATCH/dists/$dname/COMMIT"
    dk volume create "$DISTS_VOL" >/dev/null
    dk run --rm -v "$DISTS_VOL:/dists" -v "$(winpath "$SCRATCH/dists/$dname"):/src:ro" --entrypoint bash "$IMAGE" \
      -c "rm -rf /dists/$dname && cp -a /src /dists/$dname && chmod -R a+rX /dists/$dname && chmod 755 /dists/$dname/bin/*"
    log "dist $dname ($commit) ready"
    ;;

  restore-dump)
    dump=$1
    "$0" stop 2>/dev/null || true
    dk volume rm "$DATA_VOL" >/dev/null 2>&1 || true
    "$0" start -
    t0=$(date +%s)
    log "restoring $dump"
    gzip -dc "$dump" | dk exec -i "$NAME" mariadb wlxjury
    log "restored in $(( $(date +%s) - t0 )) s; anonymising"
    dk exec -i "$NAME" mariadb wlxjury < "$REPO/bench/scripts/anonymise.sql"
    log "anonymised"
    ;;

  start)
    dist=${1:--} cnf=${2:-}
    dk rm -f "$NAME" >/dev/null 2>&1 || true
    dk volume create "$DATA_VOL" >/dev/null
    dk volume create "$DISTS_VOL" >/dev/null
    envs=()
    [[ "$dist" != "-" ]] && envs+=(-e "WLXJURY_DIST=$dist")
    [[ -n "$cnf" ]] && envs+=(-e "BENCH_DB_CNF=$cnf")
    [[ -n "${BENCH_JOBS_WORKERS:-}" ]] && envs+=(-e "BENCH_JOBS_WORKERS=$BENCH_JOBS_WORKERS")
    [[ -n "${BENCH_JOBS_CHUNK_ROWS:-}" ]] && envs+=(-e "BENCH_JOBS_CHUNK_ROWS=$BENCH_JOBS_CHUNK_ROWS")
    # The server's limits, by count only: 2 CPUs (no quota), 4.9 GB RAM + 2 GB swap,
    # no I/O throttling; MariaDB and the app share them.
    dk run -d --name "$NAME" \
      --cpuset-cpus=0,1 --memory=4900m --memory-swap=6900m \
      -p "$APP_PORT:9000" -p "$DB_PORT:3306" \
      -v "$DATA_VOL:/var/lib/mysql" -v "$DISTS_VOL:/opt/dists:ro" \
      -v "$(winpath "$REPO/bench/cnf"):/bench/cnf:ro" \
      -e "BENCH_JAVA_OPTS=-Xlog:gc*:file=/var/log/wlxjury/gc.log:time,uptime,level,tags" \
      "${envs[@]}" --stop-timeout 300 "$IMAGE" >/dev/null
    for _ in $(seq 1 900); do
      dk logs "$NAME" 2>&1 | grep -q "MariaDB up" && break
      dk ps -q -f "name=^$NAME$" | grep -q . || { dk logs "$NAME"; exit 1; }
      sleep 1
    done
    log "server up (dist=$dist cnf=${cnf:-production})"
    ;;

  wait-app)
    t0=$(date +%s)
    until curl -fs "http://localhost:$APP_PORT/auth" >/dev/null; do
      if ! dk exec "$NAME" pgrep -u wlxjury java >/dev/null 2>&1 && (( $(date +%s) - t0 > 30 )); then
        dk exec "$NAME" tail -50 /var/log/wlxjury/stdout.log; exit 1
      fi
      (( $(date +%s) - t0 > ${BENCH_APP_TIMEOUT:-3600} )) && { log "app timeout"; exit 1; }
      sleep 2
    done
    log "app up after $(( $(date +%s) - t0 )) s"
    ;;

  stop)
    dk stop -t 300 "$NAME" >/dev/null 2>&1 || true
    dk rm "$NAME" >/dev/null 2>&1 || true
    ;;

  save|load)
    snap=wlxjury-bench-snap-$1
    dk ps -q -f "name=^$NAME$" | grep -q . && { log "stop the server first"; exit 1; }
    if [[ "$cmd" == save ]]; then from=$DATA_VOL to=$snap; else from=$snap to=$DATA_VOL; fi
    dk volume inspect "$from" >/dev/null
    dk volume create "$to" >/dev/null
    t0=$(date +%s)
    dk run --rm -v "$from:/from:ro" -v "$to:/to" --entrypoint bash "$IMAGE" \
      -c 'find /to -mindepth 1 -delete && cp -a /from/. /to/ && du -sh /to'
    log "$cmd $1: $(( $(date +%s) - t0 )) s"
    ;;

  sql)
    dk exec -i "$NAME" mariadb wlxjury "$@"
    ;;

  drop-caches)
    dk run --rm --privileged --entrypoint sh "$IMAGE" -c 'sync; echo 3 > /proc/sys/vm/drop_caches'
    ;;

  status)
    dk exec "$NAME" mariadb -N -e "SHOW GLOBAL STATUS" > "$1"
    ;;

  nmt)
    dk exec "$NAME" bash -c 'runuser -u wlxjury -- jcmd $(pgrep -u wlxjury java) VM.native_memory summary' > "$1"
    ;;

  *)
    sed -n '2,20p' "$0"; exit 1
    ;;
esac
