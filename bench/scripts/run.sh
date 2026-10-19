#!/usr/bin/env bash
# One measured run on the benchmark server (docs/plans/2026-10-07-prod-like-benchmark.md):
#
#   run.sh <label> <dist> <snapshot> <cnf|-> <simulation>... [-- <extra -D options for sbt>]
#
#  1. checks the laptop is on mains power (Win32_Battery.BatteryStatus = 2);
#  2. loads the data snapshot, drops the VM's page cache, starts the server with <dist> and
#     MariaDB config bench/cnf/<cnf>.cnf (- = production's files only), waits for the app;
#  3. warms up (quick Voting, JurorGallery and RoundManagement runs, not recorded): a
#     production server is warm;
#  4. runs each simulation in Gatling's external-target mode, recording per simulation:
#     Gatling's results, SHOW GLOBAL STATUS before and after, `docker stats` and the
#     processes' RSS every few seconds;
#  5. collects the app's GC log, `jcmd VM.native_memory summary`, MariaDB's slow log and
#     the effective variables, then stops the server.
#
# Results: $BENCH_SCRATCH/results/<label>/ (outside the repository).
# A simulation name may carry -D options for itself: "VotingSimulation@-Dgatling.users=40".
set -euo pipefail
export MSYS_NO_PATHCONV=1 # Git Bash: keep container paths as they are

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$here/../.." && pwd)
B="$here/bench.sh"
: "${BENCH_SCRATCH:?set BENCH_SCRATCH}"

label=$1 dist=$2 snap=$3 cnf=$4; shift 4
sims=(); extra=()
while (( $# )); do
  if [[ "$1" == "--" ]]; then shift; extra=("$@"); break; fi
  sims+=("$1"); shift
done
[[ "$cnf" == "-" ]] && cnf=""

out="$BENCH_SCRATCH/results/$label"
[[ ! -e "$out/run.log" ]] || { echo "result label already exists: $label" >&2; exit 1; }
mkdir -p "$out"
sp=""
cleanup() {
  [[ -z "$sp" ]] || { kill "$sp" 2>/dev/null || true; wait "$sp" 2>/dev/null || true; sp=""; }
  "$B" stop
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
log() { echo "[run $label] $(date -Iseconds) $*" | tee -a "$out/run.log" >&2; }

power() { powershell.exe -NoProfile -Command "(Get-CimInstance Win32_Battery).BatteryStatus" | tr -d '\r'; }
p=$(power)
if [[ "$p" != "2" ]]; then log "NOT on mains power (BatteryStatus=$p): aborting"; exit 2; fi
log "on mains power; image=${BENCH_IMAGE:-wlxjury-bench-server} dist=$dist snapshot=$snap cnf=${cnf:-production} sims=${sims[*]} extra=${extra[*]:-}"

"$B" stop
# Release previous VM cache before copying a fresh snapshot.
"$B" drop-caches
"$B" load "$snap" >>"$out/run.log" 2>&1
"$B" drop-caches
"$B" start "$dist" "$cnf" >>"$out/run.log" 2>&1
BENCH_APP_TIMEOUT=${BENCH_APP_TIMEOUT:-3600} "$B" wait-app 2>&1 | tee -a "$out/run.log"
docker exec wlxjury-bench mariadb -N -e "SHOW GLOBAL VARIABLES" > "$out/variables.txt"
docker exec wlxjury-bench mariadb -N -e \
  "SELECT installed_rank, version, description, execution_time, success FROM wlxjury.flyway_schema_history ORDER BY installed_rank" \
  > "$out/flyway.txt" || true
docker inspect wlxjury-bench > "$out/container-start.json"
docker exec wlxjury-bench mariadb -N -e "SELECT id, active, category FROM wlxjury.rounds WHERE id IN (1721,1775); SELECT round_id, SUM(active), COUNT(*) FROM wlxjury.round_user WHERE round_id IN (1721,1775) GROUP BY round_id; SELECT TABLE_NAME, ENGINE, TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH FROM information_schema.tables WHERE table_schema='wlxjury' ORDER BY TABLE_NAME; SELECT COUNT(*) AS unexpected_emails FROM wlxjury.users WHERE email NOT LIKE '%@bench.test'" > "$out/snapshot-checks.txt"
if [[ "$(tail -1 "$out/snapshot-checks.txt")" != "0" ]] ||
   ! grep -Fxq $'1721\t1\tNULL' "$out/snapshot-checks.txt" ||
   ! grep -Fxq $'1775\t1\tNULL' "$out/snapshot-checks.txt" ||
   ! grep -Fxq $'1721\t17\t17' "$out/snapshot-checks.txt" ||
   ! grep -Fxq $'1775\t13\t13' "$out/snapshot-checks.txt"; then
  log "snapshot email/reopening checks failed: aborting before warm-up"
  exit 1
fi
# the slow log covers this run only (the snapshot may carry entries from migrations)
docker exec wlxjury-bench bash -c ": > /var/lib/mysql/mariadb-slow.log" || true
docker exec wlxjury-bench mariadb -e "SET GLOBAL slow_query_log = OFF; TRUNCATE mysql.slow_log; SET GLOBAL slow_query_log = ON; FLUSH SLOW LOGS" || true

sbt_run() { # <simulation> <-D options...>
  local sim=$1; shift
  (cd "$REPO" && sbt -Dsbt.server.forcestart=true -batch -Dgatling.target=external "$@" \
     "Gatling/testOnly gatling.simulations.$sim")
}

sampler() { # docker stats + RSS of mariadbd and java, every ~3 s
  echo "time,cpu_pct,mem_usage,mariadbd_rss_kb,java_rss_kb" > "$1"
  while :; do
    s=$(docker stats --no-stream --format '{{.CPUPerc}},{{.MemUsage}}' wlxjury-bench 2>/dev/null) || break
    r=$(docker exec wlxjury-bench bash -c 'echo "$(ps -o rss= -C mariadbd | tr -d " "),$(ps -o rss= -u wlxjury | sort -n | tail -1 | tr -d " ")"' 2>/dev/null) || r=","
    echo "$(date +%H:%M:%S),$s,$r" >> "$1"
    sleep 1
  done
}

log "warm-up"
for w in VotingSimulation JurorGallerySimulation RoundManagementSimulation; do
  sbt_run "$w" -Dgatling.quick=true "${extra[@]}" > "$out/warmup-$w.log" 2>&1 || log "warm-up $w failed (see warmup-$w.log)"
done

failed=0
for spec in "${sims[@]}"; do
  sim=${spec%%@*}; opts=()
  [[ "$spec" == *@* ]] && read -r -a opts <<< "${spec#*@}"
  name=$sim; [[ ${#opts[@]} -gt 0 ]] && name="$sim$(echo "${opts[*]}" | tr -c 'A-Za-z0-9=.' '_' | sed 's/-Dgatling\.//g')"
  d="$out/$name"; mkdir -p "$d"
  p=$(power); [[ "$p" == "2" ]] || { log "power changed (BatteryStatus=$p): aborting"; exit 2; }
  docker exec wlxjury-bench mariadb -N -e "SHOW GLOBAL STATUS" > "$d/status-before.txt"
  sampler "$d/samples.csv" & sp=$!
  before=$(ls -1d "$REPO"/target/gatling/*/ 2>/dev/null | sort || true)
  log "simulation $name"
  t0=$(date +%s)
  if sbt_run "$sim" "${extra[@]}" "${opts[@]}" > "$d/sbt.log" 2>&1; then rc=ok; else rc=failed; fi
  kill "$sp" 2>/dev/null || true; wait "$sp" 2>/dev/null || true; sp=""
  docker exec wlxjury-bench mariadb -N -e "SHOW GLOBAL STATUS" > "$d/status-after.txt"
  after=$(ls -1d "$REPO"/target/gatling/*/ 2>/dev/null | sort || true)
  new=$(comm -13 <(echo "$before") <(echo "$after") | tail -1)
  [[ -n "$new" ]] && cp -r "$new" "$d/gatling"
  for f in distribution-memory.txt concurrent-distribution.txt; do
    [[ -f "$REPO/target/gatling/$f" ]] && mv "$REPO/target/gatling/$f" "$d/$f"
  done
  log "simulation $name: $rc in $(( $(date +%s) - t0 )) s"
  [[ "$rc" == ok ]] || { failed=1; break; }
done

docker exec wlxjury-bench bash -c 'runuser -u wlxjury -- jcmd $(pgrep -u wlxjury java) VM.native_memory summary' > "$out/nmt.txt" 2>&1 || true
docker exec wlxjury-bench bash -c 'ps -o rss=,comm= -C mariadbd; ps -o rss=,comm= -u wlxjury' > "$out/rss-end.txt" || true
docker exec wlxjury-bench cat /var/log/wlxjury/gc.log > "$out/gc.log" 2>/dev/null || true
docker exec wlxjury-bench cat /var/lib/mysql/mariadb-slow.log > "$out/mariadb-slow.log" 2>/dev/null || true
docker exec wlxjury-bench cat /var/log/wlxjury/application.log > "$out/application.log" 2>/dev/null || true
docker exec wlxjury-bench cat /var/log/wlxjury/stdout.log > "$out/stdout.log" 2>/dev/null || true
docker exec wlxjury-bench bash -c 'grep -E "WARN|ERROR|warn|error" /var/log/wlxjury/application.log | tail -300' > "$out/app-warnings.log" 2>&1 || true
"$B" stop
log "done (failed=$failed)"
exit "$failed"
