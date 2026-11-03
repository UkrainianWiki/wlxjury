# Blue-Green Promotion DB Sync — Revised Design

**Date:** 2026-04-14
**Status:** Approved
**Amends:** `2026-04-14-blue-green-deployment-design.md` (replaces Section 4 and Section 5)

## Problem

The original design used MariaDB binlog replay (`mysqlbinlog --to-last-log`) to bring the canary DB current at promotion time. This approach has two fatal flaws:

1. **ID conflicts**: during canary testing the canary DB may accumulate rows with auto-increment IDs that conflict with the active DB's inserts when binlogs are replayed.
2. **Schema incompatibility**: binlog ROW events encode the exact column structure of the source table. The canary DB has a newer schema (after Flyway migration); replaying events generated against the old schema fails when column layouts differ (e.g. column renames such as V28, column drops such as V23/V40, or new columns added by V48).

## Solution

Replace binlog replay with a full dump+restore at promotion time, protected by a maintenance-mode 503. Flyway's `flyway_schema_history` table is included in the dump, so on restart the canary service only runs the **new** migration (the delta between the active version and the canary version) — not all 50+ historical ones. Downtime ≈ dump (~1 min) + restore (~1 min) + new migration (seconds to a few minutes).

## 1. Changes to `wlxjury-deploy-canary.sh`

Remove all binlog-tracking from the deploy script:

- Change `mysqldump --single-transaction --master-data=2` → `mysqldump --single-transaction` (no binlog position capture needed)
- Remove the line: `grep "CHANGE MASTER TO" "$DUMP_FILE" > "$SYNC_POS"`
- Remove the line: `echo "DEPLOYED_TO_SLOT=$CANARY_SLOT" >> "$SYNC_POS"`
- Remove the `cat "$SYNC_POS"` diagnostic line
- Remove `SYNC_POS` variable declaration

Remove the `SYNC_POS` variable declaration from both `deploy-canary.sh` and `promote.sh`.

Everything else in the deploy script is unchanged.

## 2. Changes to `wlxjury-promote.sh`

Replace the entire binlog replay block with a dump+restore+health-check flow. The active service is **never stopped** during promotion — `--single-transaction` provides a consistent snapshot while writes continue. The maintenance 503 covers the data-loss window (writes that arrive between dump start and slot switch do not reach the canary DB).

### New promotion flow

```
1. Enable maintenance mode
   touch /etc/wlxjury/maintenance
   systemctl reload apache2
   → all vhosts (production + canary) return 503

2. Dump active DB  (~1 min)
   mysqldump --single-transaction \
     -u"$DB_USER" -p"$DB_PASS" "$ACTIVE_DB" > /tmp/wlxjury-promote-$$.sql
   # flyway_schema_history is included — Flyway will only run the new migration

3. Drop + recreate canary DB, restore dump  (~1 min)
   mysql: DROP DATABASE $CANARY_DB; CREATE DATABASE $CANARY_DB ...
   mysql $CANARY_DB < /tmp/wlxjury-promote-$$.sql
   rm /tmp/wlxjury-promote-$$.sql

4. Restart canary service
   systemctl restart wlxjury-$CANARY_SLOT
   → Flyway runs only the new migration (delta), not all historical ones

5. Health check loop
   Retry curl http://127.0.0.1:$CANARY_PORT/ until 200 or timeout (e.g. 120 s)
   On timeout → rollback (see below)

6. Swap slot file + reload Apache
   sed -i ... $SLOT_FILE
   systemctl reload apache2
   → production traffic now routes to canary; 503 still served until step 7

7. Remove maintenance flag
   rm -f /etc/wlxjury/maintenance
   → all vhosts now serve normally via the new active slot
```

### Rollback path (health check timeout)

```bash
rm -f /etc/wlxjury/maintenance
systemctl reload apache2   # slot file unchanged → active service still receives traffic
echo "Promotion failed. Active slot ($ACTIVE_SLOT) is still serving traffic." >&2
exit 1
```

The active service was never stopped, so traffic returns immediately on rollback. No slot-file swap is needed.

### Rollback after a successful promotion

Running `wlxjury-promote.sh` a second time performs a full dump+restore in the other direction: dumps the promoted slot (now active) and restores into the old active slot (now canary). Same downtime window. This is correct because the old-active slot runs the old code with the old schema — restoring the dump (which has the new schema from the dump's `flyway_schema_history`) and restarting it will run Flyway migrations on it too, bringing it to the new schema. The old code then starts against the new schema. This is safe only if the migration is backward-compatible, OR if the operator accepts that the old code may not work after rollback (degraded state until a hotfix is deployed). Document this in the script.

## 3. Maintenance Mode in Apache Config

Add a flag-file RewriteRule to **every** `<VirtualHost *:80>` block in `conf/apache/0002-wlxjury.conf` — both the six production vhosts and the canary vhost. During the dump+restore+migrate window the canary service is restarted and unavailable; serving a clean 503 is better than a bare 502.

```apache
# Maintenance mode — add inside EVERY <VirtualHost *:80> block
# (production AND canary vhosts)
RewriteEngine On
RewriteCond /etc/wlxjury/maintenance -f
RewriteRule ^ - [R=503,L]
ErrorDocument 503 "Jury tool is undergoing a brief maintenance (typically 2-5 minutes). Please try again shortly."
```

`promote.sh` creates the flag (`touch /etc/wlxjury/maintenance`) before the dump and removes it (`rm -f`) after the slot swap. The window where all vhosts serve 503 is: dump start → slot switch + reload → flag removal.

## Files Changed

| File | Change |
|------|--------|
| `bin/wlxjury-deploy-canary.sh` | Remove `--master-data=2`, remove binlog position saving |
| `bin/wlxjury-promote.sh` | Replace binlog replay with dump+restore+health-check+maintenance flow |
| `conf/apache/0002-wlxjury.conf` | Add maintenance flag RewriteRule to all 7 vhosts |
