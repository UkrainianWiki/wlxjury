# Blue-Green Deployment Design

**Date:** 2026-04-14
**Status:** Approved

## Overview

Run two instances of the WLX Jury Tool simultaneously to support canary validation and staggered deployments. A new version is deployed alongside the current one (canary), tested via a separate subdomain, then promoted to active. The old active becomes a backup for instant rollback. Each instance has its own install path and database; both share the same image cache directory on disk.

## 1. Systemd Units and Instance Configuration

Each slot (blue/green) has its own install path, env file, and MariaDB database.

**Install paths:**
```
/opt/wlxjury-blue/
  bin/wlxjury          ← start script (from sbt dist)
  lib/*.jar            ← app + all dependencies
  conf/                ← application.conf, logback.xml, routes, …

/opt/wlxjury-green/
  (same structure, different version when canary is deployed)
```

**Environment files:**
```
/etc/wlxjury/blue.env
  WLXJURY_HTTP_PORT=9000
  WLXJURY_DB=wlxjury_blue
  WLXJURY_DB_HOST=...
  WLXJURY_DB_USER=...
  WLXJURY_DB_PASSWORD=...

/etc/wlxjury/green.env
  WLXJURY_HTTP_PORT=9001
  WLXJURY_DB=wlxjury_green
  WLXJURY_DB_HOST=...   (same host)
  WLXJURY_DB_USER=...
  WLXJURY_DB_PASSWORD=...
```

**Slot state file** (tracks which slot is currently active):
```
/etc/wlxjury/slots.env
  ACTIVE_SLOT=blue
  CANARY_SLOT=green
  ACTIVE_DB=wlxjury_blue
  CANARY_DB=wlxjury_green
```

**Systemd units:**
```
/etc/systemd/system/wlxjury-blue.service
/etc/systemd/system/wlxjury-green.service
```

Each unit sets `EnvironmentFile=/etc/wlxjury/<slot>.env`, `ExecStart=/opt/wlxjury-<slot>/bin/wlxjury`, and `WorkingDirectory=/opt/wlxjury-<slot>`. Both slots point at the same shared image cache (`/var/www/jury-images`).

Databases are kept separate because Flyway runs on startup and migrates the schema automatically. If both instances shared one DB, the canary starting up would migrate the schema under the still-running active instance.

## 2. Apache Routing and Slot File

The active port is stored in a single include file:

```
/etc/apache2/wlxjury-slot.conf
  Define WLXJURY_ACTIVE_PORT 9000
  Define WLXJURY_CANARY_PORT 9001
```

The main vhost config includes this file and uses the variables:

```apache
Include /etc/apache2/wlxjury-slot.conf

# All production virtual hosts route to the active slot
<VirtualHost *:443>
    ServerName jury.wle.org.ua
    # ... (and all other production ServerName entries)
    ProxyPass / http://127.0.0.1:${WLXJURY_ACTIVE_PORT}/ retry=0
    ProxyPassReverse / http://127.0.0.1:${WLXJURY_ACTIVE_PORT}/
</VirtualHost>

# Canary virtual host — testers only
<VirtualHost *:443>
    ServerName jury-canary.wle.org.ua
    ProxyPass / http://127.0.0.1:${WLXJURY_CANARY_PORT}/ retry=0
    ProxyPassReverse / http://127.0.0.1:${WLXJURY_CANARY_PORT}/
</VirtualHost>
```

Promotion rewrites `wlxjury-slot.conf` (swapping the two port values) and runs `systemctl reload apache2`. The vhost file itself is never edited.

## 3. Disk Cache — Atomic Rename

Both instances share `/var/www/jury-images`. The current `saveResized` writes directly with `ImageIO.write(resized, "JPEG", file)` — two instances writing the same file simultaneously could produce a corrupt partial file.

The fix: write to a temp file in the same directory, then `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`. On Linux, rename is atomic — readers always see either the old file or the complete new one, never a partial write.

```scala
// Replace direct ImageIO.write with:
val tmp = File.createTempFile(".tmp-", ".jpg", file.getParentFile)
try {
  ImageIO.write(resized, "JPEG", tmp)
  Files.move(tmp.toPath, file.toPath,
    StandardCopyOption.ATOMIC_MOVE,
    StandardCopyOption.REPLACE_EXISTING)
} catch {
  case ex: Exception =>
    tmp.delete()
    throw ex
}
```

`REPLACE_EXISTING` handles the case where two instances race to the same path — both are writing identical content (same source image, same resize), so last-writer-wins is correct. The existing `putIfAbsent` guard on the in-memory registry continues to prevent redundant work within a single instance.

## 4. Database Sync — Binlog-Based Incremental

**Prerequisite:** MariaDB must have binary logging enabled:
```
log_bin = ON
binlog_format = ROW
```

### Canary setup (once per deployment to canary slot)

```bash
# Dump active DB, capturing binlog position in the dump header
mysqldump --single-transaction --master-data=2 \
  -u "$DB_USER" -p"$DB_PASS" "$ACTIVE_DB" > /tmp/wlxjury-sync.sql

# Restore into canary DB
mysql -u "$DB_USER" -p"$DB_PASS" \
  -e "DROP DATABASE IF EXISTS $CANARY_DB; CREATE DATABASE $CANARY_DB CHARACTER SET utf8mb4;"
mysql -u "$DB_USER" -p"$DB_PASS" "$CANARY_DB" < /tmp/wlxjury-sync.sql

# Save binlog position for use at promotion time
grep "MASTER_LOG_FILE\|MASTER_LOG_POS" /tmp/wlxjury-sync.sql \
  > /etc/wlxjury/canary-sync-pos.txt

# Start canary service — Flyway migrates canary DB on startup
systemctl restart wlxjury-"$CANARY_SLOT"
```

The canary DB now has the active DB's data with the new schema applied.

### At promotion time

```bash
# Replay binlogs from saved position to now — takes seconds, not a minute
LOG_FILE=$(grep -oP "MASTER_LOG_FILE='\K[^']+" /etc/wlxjury/canary-sync-pos.txt)
LOG_POS=$(grep -oP "MASTER_LOG_POS=\K[0-9]+"   /etc/wlxjury/canary-sync-pos.txt)
# --to-last-log reads all binlog files from $LOG_FILE forward, handling rotation
mysqlbinlog --start-position="$LOG_POS" --to-last-log /var/log/mysql/"$LOG_FILE" \
  | mysql -u "$DB_USER" -p"$DB_PASS" "$CANARY_DB"
```

The binlog replay catches up all writes made to the active DB since the initial dump. Any writes during the few seconds of the subsequent Apache reload are the only gap — acceptable for this use case.

## 5. Promote Script

`/usr/local/sbin/wlxjury-promote.sh` — reads the current slot state, replays binlogs, swaps the Apache slot file, and reloads Apache.

```bash
#!/usr/bin/env bash
set -euo pipefail

SLOT_FILE=/etc/apache2/wlxjury-slot.conf
SYNC_POS=/etc/wlxjury/canary-sync-pos.txt
SLOTS_ENV=/etc/wlxjury/slots.env

source "$SLOTS_ENV"  # ACTIVE_SLOT, CANARY_SLOT, ACTIVE_DB, CANARY_DB
source /etc/wlxjury/"$CANARY_SLOT".env  # WLXJURY_DB_USER, WLXJURY_DB_PASSWORD

DB_USER="$WLXJURY_DB_USER"
DB_PASS="$WLXJURY_DB_PASSWORD"

echo "Promoting $CANARY_SLOT → active (was $ACTIVE_SLOT)"

# 1. Replay binlogs to bring canary DB current
LOG_FILE=$(grep -oP "MASTER_LOG_FILE='\K[^']+" "$SYNC_POS")
LOG_POS=$(grep -oP "MASTER_LOG_POS=\K[0-9]+"   "$SYNC_POS")
# --to-last-log reads all binlog files from $LOG_FILE forward, handling rotation
mysqlbinlog --start-position="$LOG_POS" --to-last-log /var/log/mysql/"$LOG_FILE" \
  | mysql -u"$DB_USER" -p"$DB_PASS" "$CANARY_DB"

# 2. Swap slot file
if grep -q "WLXJURY_ACTIVE_PORT 9000" "$SLOT_FILE"; then
  sed -i \
    's/WLXJURY_ACTIVE_PORT 9000/WLXJURY_ACTIVE_PORT 9001/;
     s/WLXJURY_CANARY_PORT 9001/WLXJURY_CANARY_PORT 9000/' "$SLOT_FILE"
  sed -i \
    's/ACTIVE_SLOT=blue/ACTIVE_SLOT=green/;
     s/CANARY_SLOT=green/CANARY_SLOT=blue/;
     s/ACTIVE_DB=wlxjury_blue/ACTIVE_DB=wlxjury_green/;
     s/CANARY_DB=wlxjury_green/CANARY_DB=wlxjury_blue/' "$SLOTS_ENV"
else
  sed -i \
    's/WLXJURY_ACTIVE_PORT 9001/WLXJURY_ACTIVE_PORT 9000/;
     s/WLXJURY_CANARY_PORT 9000/WLXJURY_CANARY_PORT 9001/' "$SLOT_FILE"
  sed -i \
    's/ACTIVE_SLOT=green/ACTIVE_SLOT=blue/;
     s/CANARY_SLOT=blue/CANARY_SLOT=green/;
     s/ACTIVE_DB=wlxjury_green/ACTIVE_DB=wlxjury_blue/;
     s/CANARY_DB=wlxjury_blue/CANARY_DB=wlxjury_green/' "$SLOTS_ENV"
fi

# 3. Reload Apache (~2 sec gap)
systemctl reload apache2

echo "Done. Active: $CANARY_SLOT"
grep "ACTIVE_PORT\|CANARY_PORT" "$SLOT_FILE"
```

A companion `wlxjury-deploy-canary.sh` handles the setup step (extract zip → dump → restore → restart canary service) and writes `canary-sync-pos.txt`.

## 6. Deploy Workflow

```
1. Build
   sbt dist
   # produces target/universal/wlxjury-<version>.zip

2. Deploy to canary slot
   wlxjury-deploy-canary.sh target/universal/wlxjury-2.x.zip
   # - extracts zip to /opt/wlxjury-<canary-slot>/ (replacing previous contents)
   # - dumps active DB (--master-data=2), restores to canary DB
   # - saves binlog position to /etc/wlxjury/canary-sync-pos.txt
   # - restarts wlxjury-<canary-slot>.service → Flyway migrates canary DB on startup

3. Test on jury-canary.wle.org.ua
   # Canary DB has active data + new schema applied
   # Active instance continues serving real traffic on its own DB, unaffected

4. Promote
   wlxjury-promote.sh
   # - replays binlogs into canary DB (seconds)
   # - swaps slot file → reloads Apache (~2 sec gap)
   # - canary becomes active; old active becomes backup

5. Rollback (if needed, any time after promotion)
   wlxjury-promote.sh   # run again — swaps back immediately
   # No DB sync needed: backup slot still has its old DB intact
   # Writes made after promotion are lost on rollback — operator decides if acceptable

6. Re-deploy after rollback
   # canary-sync-pos.txt now refers to the old (pre-rollback) binlog position
   # and is stale. Re-run wlxjury-deploy-canary.sh for any new deployment.
```

## Code Changes Required

| File | Change |
|------|--------|
| `app/services/LocalImageCacheService.scala` | Replace `ImageIO.write` in `saveResized` with write-to-temp + `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` |
| `conf/apache/0002-wlxjury.conf` | Add `Include wlxjury-slot.conf`; use `${WLXJURY_ACTIVE_PORT}` in ProxyPass directives; add canary vhost |

## New Files Required

| File | Purpose |
|------|---------|
| `conf/apache/wlxjury-slot.conf` | Initial slot file (`Define WLXJURY_ACTIVE_PORT 9000`, `Define WLXJURY_CANARY_PORT 9001`) |
| `conf/systemd/wlxjury-blue.service` | Systemd unit for blue slot |
| `conf/systemd/wlxjury-green.service` | Systemd unit for green slot |
| `conf/wlxjury/blue.env.example` | Example env file for blue slot |
| `conf/wlxjury/green.env.example` | Example env file for green slot |
| `conf/wlxjury/slots.env.example` | Example slot state file |
| `bin/wlxjury-deploy-canary.sh` | Canary setup script |
| `bin/wlxjury-promote.sh` | Promotion script |
