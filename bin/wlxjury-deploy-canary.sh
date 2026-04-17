#!/usr/bin/env bash
# Usage: wlxjury-deploy-canary.sh <path-to-wlxjury-X.Y.Z.zip>
#
# Deploys the given sbt-dist zip to the canary slot and seeds the canary DB
# from the active DB. Run as root (needs systemctl, mysql access).
#
# Prerequisites:
#   /etc/wlxjury/slots.env   — slot state file (see conf/wlxjury/slots.env.example)
#   /etc/wlxjury/<slot>.env  — per-slot env files (see conf/wlxjury/*.env.example)

set -euo pipefail

ZIP="${1:?Usage: $0 <path-to-wlxjury.zip>}"
SLOTS_ENV=/etc/wlxjury/slots.env
EXTRACT_TMP="/tmp/wlxjury-extract-$$"

source "$SLOTS_ENV"   # ACTIVE_SLOT, CANARY_SLOT, ACTIVE_DB, CANARY_DB
source /etc/wlxjury/"$CANARY_SLOT".env  # WLXJURY_DB_USER, WLXJURY_DB_PASSWORD

DB_USER="$WLXJURY_DB_USER"
DB_PASS="$WLXJURY_DB_PASSWORD"
INSTALL_DIR="/opt/wlxjury-$CANARY_SLOT"
DUMP_FILE="/tmp/wlxjury-sync-$$.sql"

echo "=== Deploying $ZIP to $CANARY_SLOT slot ==="

# 1. Stop canary service if running
echo "Stopping wlxjury-$CANARY_SLOT (if running)..."
systemctl stop "wlxjury-$CANARY_SLOT" 2>/dev/null || true

# 2. Extract zip to slot install directory
# sbt dist produces a zip with a single top-level versioned directory inside.
echo "Extracting $ZIP to $INSTALL_DIR..."
rm -rf "$INSTALL_DIR" "$EXTRACT_TMP"
mkdir -p "$EXTRACT_TMP"
unzip -q "$ZIP" -d "$EXTRACT_TMP"
# Move the single versioned subdirectory into place
mkdir -p "$INSTALL_DIR"
shopt -s dotglob
mv "$EXTRACT_TMP"/*/  "$INSTALL_DIR/" 2>/dev/null || mv "$EXTRACT_TMP"/* "$INSTALL_DIR/"
shopt -u dotglob
rm -rf "$EXTRACT_TMP"
chmod +x "$INSTALL_DIR/bin/wlxjury"
echo "Extracted to $INSTALL_DIR"

# 3. Dump active DB
# flyway_schema_history is included so promote.sh only runs the new migration.
echo "Dumping active DB ($ACTIVE_DB)..."
mysqldump --single-transaction \
  -u"$DB_USER" -p"$DB_PASS" "$ACTIVE_DB" > "$DUMP_FILE"

# 4. Restore dump into canary DB (wipe first so Flyway starts from clean slate)
echo "Restoring into canary DB ($CANARY_DB)..."
mysql -u"$DB_USER" -p"$DB_PASS" \
  -e "DROP DATABASE IF EXISTS \`$CANARY_DB\`; \
      CREATE DATABASE \`$CANARY_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -u"$DB_USER" -p"$DB_PASS" "$CANARY_DB" < "$DUMP_FILE"
rm -f "$DUMP_FILE"
echo "Restored $CANARY_DB"

# 5. Start canary — Flyway migrates the canary DB schema on startup
echo "Starting wlxjury-$CANARY_SLOT..."
systemctl start "wlxjury-$CANARY_SLOT"

echo ""
echo "=== Canary deployed ==="
echo "  Slot:    $CANARY_SLOT  (/opt/wlxjury-$CANARY_SLOT)"
echo "  DB:      $CANARY_DB"
echo "  Test at: https://jury-canary.wle.org.ua"
echo ""
echo "When satisfied, run: wlxjury-promote.sh"
