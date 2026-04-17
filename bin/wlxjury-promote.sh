#!/usr/bin/env bash
# Promotes the canary slot to active via a maintenance-window dump+restore.
#
# 1. Enables maintenance mode — all vhosts return 503 (see conf/apache/0002-wlxjury.conf).
# 2. Dumps the active DB into the canary DB (flyway_schema_history included,
#    so Flyway only runs the new migration on restart — not all historical ones).
# 3. Restarts the canary service; Flyway applies the delta migration only.
# 4. Waits for the canary to pass a health check (HTTP 200 on canary port).
# 5. Swaps the Apache slot file and reloads Apache (traffic switches to canary).
# 6. Removes the maintenance flag — all vhosts serve normally again.
#
# On failure before the slot swap: rollback removes the maintenance flag and
# reloads Apache — the active service was never stopped, so traffic returns.
#
# To rollback after a successful promotion: run this script again. The dump+restore
# runs in the other direction. Note: the backup slot (old active) will have the new
# schema after rollback; old code may not work if the migration was a breaking change.
#
# Run as root.

set -euo pipefail

SLOT_FILE=/etc/apache2/wlxjury-slot.conf
SLOTS_ENV=/etc/wlxjury/slots.env
MAINTENANCE=/etc/wlxjury/maintenance
HEALTH_TIMEOUT=120   # seconds

source "$SLOTS_ENV"   # ACTIVE_SLOT, CANARY_SLOT, ACTIVE_DB, CANARY_DB
source /etc/wlxjury/"$CANARY_SLOT".env  # WLXJURY_DB_USER, WLXJURY_DB_PASSWORD

DB_USER="$WLXJURY_DB_USER"
DB_PASS="$WLXJURY_DB_PASSWORD"
DUMP_FILE="/tmp/wlxjury-promote-$$.sql"

# Read CANARY_PORT from the slot file for the health check
CANARY_PORT=$(grep -oP 'WLXJURY_CANARY_PORT \K[0-9]+' "$SLOT_FILE")

echo "=== Promoting $CANARY_SLOT → active (current active: $ACTIVE_SLOT) ==="
echo "    Maintenance window begins — all vhosts will return 503"

# ── Rollback on any error before the slot swap ────────────────────────────────
rollback() {
  echo "ROLLBACK: removing maintenance flag and reloading Apache." >&2
  rm -f "$DUMP_FILE"
  rm -f "$MAINTENANCE"
  systemctl reload apache2
  echo "Active slot ($ACTIVE_SLOT) is still serving traffic." >&2
  exit 1
}
trap rollback ERR

# 1. Enable maintenance mode
touch "$MAINTENANCE"
systemctl reload apache2

# 2. Dump active DB (flyway_schema_history included)
echo "Dumping active DB ($ACTIVE_DB)..."
mysqldump --single-transaction \
  -u"$DB_USER" -p"$DB_PASS" "$ACTIVE_DB" > "$DUMP_FILE"

# 3. Drop + recreate canary DB, restore dump
echo "Restoring into canary DB ($CANARY_DB)..."
mysql -u"$DB_USER" -p"$DB_PASS" \
  -e "DROP DATABASE IF EXISTS \`$CANARY_DB\`; \
      CREATE DATABASE \`$CANARY_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -u"$DB_USER" -p"$DB_PASS" "$CANARY_DB" < "$DUMP_FILE"
rm -f "$DUMP_FILE"

# 4. Restart canary — Flyway runs the new migration only
echo "Restarting wlxjury-$CANARY_SLOT (Flyway will apply delta migration)..."
systemctl restart "wlxjury-$CANARY_SLOT"

# 5. Health check loop
echo "Waiting for canary health check on port $CANARY_PORT (timeout: ${HEALTH_TIMEOUT}s)..."
ELAPSED=0
until curl -sf "http://127.0.0.1:$CANARY_PORT/" -o /dev/null; do
  sleep 5
  ELAPSED=$((ELAPSED + 5))
  if [[ $ELAPSED -ge $HEALTH_TIMEOUT ]]; then
    echo "Health check timed out after ${HEALTH_TIMEOUT}s." >&2
    false
  fi
  echo "  ...still waiting (${ELAPSED}s elapsed)"
done
echo "Canary is healthy."

# Disable ERR trap — slot swap and Apache reload errors should not trigger rollback
trap - ERR

# 6. Swap slot file and slots.env
if grep -q "WLXJURY_ACTIVE_PORT 9000" "$SLOT_FILE"; then
  # blue→active  ⟹  swap to  green→active
  sed -i \
    's/WLXJURY_ACTIVE_PORT 9000/WLXJURY_ACTIVE_PORT 9001/;
     s/WLXJURY_CANARY_PORT 9001/WLXJURY_CANARY_PORT 9000/' "$SLOT_FILE"
  sed -i \
    's/ACTIVE_SLOT=blue/ACTIVE_SLOT=green/;
     s/CANARY_SLOT=green/CANARY_SLOT=blue/;
     s/ACTIVE_DB=wlxjury_blue/ACTIVE_DB=wlxjury_green/;
     s/CANARY_DB=wlxjury_green/CANARY_DB=wlxjury_blue/' "$SLOTS_ENV"
else
  # green→active  ⟹  swap to  blue→active
  sed -i \
    's/WLXJURY_ACTIVE_PORT 9001/WLXJURY_ACTIVE_PORT 9000/;
     s/WLXJURY_CANARY_PORT 9000/WLXJURY_CANARY_PORT 9001/' "$SLOT_FILE"
  sed -i \
    's/ACTIVE_SLOT=green/ACTIVE_SLOT=blue/;
     s/CANARY_SLOT=blue/CANARY_SLOT=green/;
     s/ACTIVE_DB=wlxjury_green/ACTIVE_DB=wlxjury_blue/;
     s/CANARY_DB=wlxjury_blue/CANARY_DB=wlxjury_green/' "$SLOTS_ENV"
fi
systemctl reload apache2

# 7. Remove maintenance flag
rm -f "$MAINTENANCE"

echo ""
echo "=== Promotion complete ==="
grep "ACTIVE_SLOT\|CANARY_SLOT" "$SLOTS_ENV"
grep "ACTIVE_PORT\|CANARY_PORT" "$SLOT_FILE"
echo ""
echo "To rollback: run this script again (full dump+restore in reverse)."
echo "Note: on rollback the old-active slot will have the new schema applied."
echo "Old code may not work if the migration was a breaking change."
