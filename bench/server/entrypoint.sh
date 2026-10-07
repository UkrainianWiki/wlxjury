#!/bin/bash
# Starts MariaDB as Ubuntu's mariadb.service does (mariadbd directly, LimitNOFILE=32768),
# then, if WLXJURY_DIST names a dist under /opt/dists, the jury tool as wlxjury.service
# does: /usr/share/wlxjury/bin/wlxjury as user wlxjury, with /etc/default/wlxjury's
# environment (JAVA_OPTS, MALLOC_ARENA_MAX).
#
# Environment:
#   BENCH_DB_CNF     name of a file in /bench/cnf (bind-mounted) to install as
#                    /etc/mysql/mariadb.conf.d/60-wlxjury.cnf (Phase 4); unset = production's files only
#   WLXJURY_DIST     directory name under /opt/dists (a volume of unzipped `sbt dist`s); unset = DB only
#   BENCH_JAVA_OPTS  appended to JAVA_OPTS (the GC log)
#   BENCH_JOBS_*     see /etc/wlxjury/bench.conf
set -euo pipefail

log() { echo "[bench-entrypoint] $(date -u +%H:%M:%S) $*"; }

if [[ -n "${BENCH_DB_CNF:-}" ]]; then
  # copied, not bind-mounted: MariaDB ignores a world-writable config file
  install -m 644 "/bench/cnf/${BENCH_DB_CNF}.cnf" /etc/mysql/mariadb.conf.d/60-wlxjury.cnf
  log "MariaDB config: 60-wlxjury.cnf = ${BENCH_DB_CNF}.cnf"
fi

mkdir -p /run/mysqld && chown mysql:mysql /run/mysqld
if [[ ! -d /var/lib/mysql/mysql ]]; then
  log "initialising an empty datadir"
  mariadb-install-db --user=mysql --datadir=/var/lib/mysql --skip-test-db >/dev/null
fi
[[ "$(stat -c %U /var/lib/mysql)" == mysql ]] || chown -R mysql:mysql /var/lib/mysql

ulimit -n 32768
/usr/sbin/mariadbd --user=mysql &
DB_PID=$!
for _ in $(seq 1 600); do
  mariadb-admin ping --silent 2>/dev/null && break
  kill -0 "$DB_PID" 2>/dev/null || { log "mariadbd exited"; exit 1; }
  sleep 1
done
log "MariaDB up"

# Accounts. The app's, as on production: privileges on its own database only. (With more,
# e.g. SELECT on *.*, ScalikeJDBC's column discovery for `users` also finds
# performance_schema.users, and every user query fails.) 'bench' is for checks from the host.
mariadb <<'SQL'
CREATE DATABASE IF NOT EXISTS wlxjury;
CREATE USER IF NOT EXISTS 'wlxjury'@'%' IDENTIFIED BY 'wlxjury';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'wlxjury'@'%';
GRANT ALL ON wlxjury.* TO 'wlxjury'@'%';
CREATE USER IF NOT EXISTS 'bench'@'%' IDENTIFIED BY 'bench';
GRANT ALL PRIVILEGES ON *.* TO 'bench'@'%';
SQL

APP_PID=
if [[ -n "${WLXJURY_DIST:-}" ]]; then
  rm -rf /usr/share/wlxjury
  cp -a "/opt/dists/${WLXJURY_DIST}" /usr/share/wlxjury
  ln -sfn /var/log/wlxjury /usr/share/wlxjury/logs
  chown -R wlxjury:wlxjury /usr/share/wlxjury /var/log/wlxjury /run/wlxjury
  set -a
  # shellcheck source=/dev/null
  . /etc/default/wlxjury
  set +a
  export JAVA_OPTS="${JAVA_OPTS} ${BENCH_JAVA_OPTS:-}"
  export WLXJURY_DB_HOST=localhost WLXJURY_DB=wlxjury WLXJURY_DB_USER=wlxjury WLXJURY_DB_PASSWORD=wlxjury
  rm -f /run/wlxjury/play.pid
  log "starting ${WLXJURY_DIST}: JAVA_OPTS=${JAVA_OPTS} MALLOC_ARENA_MAX=${MALLOC_ARENA_MAX:-}"
  cd /usr/share/wlxjury
  runuser -u wlxjury -- bin/wlxjury \
    -Dpidfile.path=/run/wlxjury/play.pid -Dhttp.port=9000 -Dconfig.file=/etc/wlxjury/bench.conf \
    >>/var/log/wlxjury/stdout.log 2>&1 &
  APP_PID=$!
fi

shutdown() {
  log "stopping"
  if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
    pkill -TERM -u wlxjury java || true
    wait "$APP_PID" || true
  fi
  mariadb-admin shutdown || kill -TERM "$DB_PID"
  wait "$DB_PID" || true
  log "stopped"
  exit 0
}
trap shutdown TERM INT

if [[ -n "$APP_PID" ]]; then
  wait -n "$DB_PID" "$APP_PID" || true
  log "a process exited (db alive: $(kill -0 $DB_PID 2>/dev/null && echo yes || echo no))"
  # keep the DB up for inspection when the app dies (OOM exit, failed migration)
  wait "$DB_PID"
else
  wait "$DB_PID"
fi
