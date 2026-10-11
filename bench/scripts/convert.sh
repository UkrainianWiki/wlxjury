#!/usr/bin/env bash
# Phase 3: time the MyISAM -> InnoDB conversion table by table on the benchmark server
# (MariaDB only, nothing else running), as a maintenance-window run of V55.1 by hand would.
#
#   convert.sh <out-dir>
#
# Runs V55.1's statement for each table (the same ALTER), records each one's time, and
# samples the datadir's size and mariadbd's RSS every second: the peak disk use is the
# largest datadir size seen during an ALTER (the old table and its InnoDB copy both exist
# until the ALTER ends).
set -euo pipefail
export MSYS_NO_PATHCONV=1
out=$1; mkdir -p "$out"
C=wlxjury-bench
TABLES="users contest_jury rounds round_user category category_members comment criteria criteria_rate monument images selection"

docker exec $C bash -c 'cat > /tmp/sample.sh <<"EOF"
while :; do
  echo "$(date +%s.%N | cut -c1-14) $(du -sb /var/lib/mysql | cut -f1) $(ps -o rss= -C mariadbd | tr -d " ")"
  sleep 1
done
EOF'
docker exec -d $C bash -c 'bash /tmp/sample.sh > /tmp/samples.txt'

docker exec $C mariadb -N wlxjury -e "SELECT TABLE_NAME, ENGINE, TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH FROM information_schema.TABLES WHERE TABLE_SCHEMA='wlxjury' ORDER BY TABLE_NAME" > "$out/tables-before.txt"
echo "table seconds" > "$out/times.txt"
total0=$(date +%s.%N)
for t in $TABLES; do
  t0=$(date +%s.%N)
  docker exec $C mariadb wlxjury -e "SET @t0 = NOW(6); ALTER TABLE \`$t\` ENGINE = InnoDB, ROW_FORMAT = DYNAMIC, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci; SELECT '$t', TIMESTAMPDIFF(MICROSECOND, @t0, NOW(6)) / 1e6;" -N >> "$out/times.txt"
  echo "$t start=$t0 end=$(date +%s.%N)" >> "$out/windows.txt"
done
t0=$(date +%s.%N)
docker exec $C mariadb wlxjury -e "ANALYZE TABLE users, contest_jury, rounds, round_user, category, category_members, comment, criteria, criteria_rate, monument, images, selection" > /dev/null
echo "analyze $(awk -v a="$(date +%s.%N)" -v b="$t0" "BEGIN{print a-b}")" >> "$out/times.txt"
echo "total $(awk -v a="$(date +%s.%N)" -v b="$total0" "BEGIN{print a-b}")" >> "$out/times.txt"
sleep 2
docker exec $C bash -c 'pkill -f "bash /tmp/sampl[e].sh"; cat /tmp/samples.txt' > "$out/samples.txt"
docker exec $C mariadb -N wlxjury -e "SELECT TABLE_NAME, ENGINE, TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH FROM information_schema.TABLES WHERE TABLE_SCHEMA='wlxjury' ORDER BY TABLE_NAME" > "$out/tables-after.txt"
cat "$out/times.txt"
