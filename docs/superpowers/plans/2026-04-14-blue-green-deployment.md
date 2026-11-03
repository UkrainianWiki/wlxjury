# Blue-Green Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable two simultaneous instances of the WLX Jury Tool (blue on port 9000, green on port 9001) for canary validation and staggered deployments, with shared disk cache protected by atomic rename.

**Architecture:** Blue and green slots each have their own install path (`/opt/wlxjury-<slot>/`), systemd unit, env file, and MariaDB database. Apache reads a single slot-file include to know which port is active; a `promote.sh` script swaps ports, replays binlogs into the canary DB, and reloads Apache. The image cache directory is shared and protected by write-to-temp + atomic `Files.move`.

**Tech Stack:** Play Framework / sbt-native-packager (`sbt dist`), systemd, Apache with `mod_proxy` and `Define`, MariaDB binary logging, `mysqlbinlog`, Scala `java.nio.file.Files.move(ATOMIC_MOVE)`.

**Spec:** `docs/superpowers/specs/2026-04-14-blue-green-deployment-design.md`

---

## File Map

| Action | Path | Purpose |
|--------|------|---------|
| Modify | `app/services/LocalImageCacheService.scala` | Replace direct `ImageIO.write` with temp-file + atomic rename in `saveResized` |
| Modify | `test/services/LocalImageCacheServiceSpec.scala` | Add cross-instance concurrent write test |
| Create | `conf/apache/wlxjury-slot.conf` | Initial slot `Define` values (blue=9000 active) |
| Modify | `conf/apache/0002-wlxjury.conf` | Add `Include`, use `${WLXJURY_ACTIVE_PORT}`, add canary vhost |
| Create | `conf/systemd/wlxjury-blue.service` | Systemd unit for blue slot |
| Create | `conf/systemd/wlxjury-green.service` | Systemd unit for green slot |
| Create | `conf/wlxjury/blue.env.example` | Example env file for blue slot |
| Create | `conf/wlxjury/green.env.example` | Example env file for green slot |
| Create | `conf/wlxjury/slots.env.example` | Example slot-state file |
| Create | `bin/wlxjury-deploy-canary.sh` | Script: extract zip, dump/restore DB, start canary |
| Create | `bin/wlxjury-promote.sh` | Script: replay binlogs, swap slot file, reload Apache |

---

## Task 1: Atomic rename in `saveResized`

**Files:**
- Modify: `app/services/LocalImageCacheService.scala` (`saveResized` method, lines 355–367)
- Modify: `test/services/LocalImageCacheServiceSpec.scala` (add test to "saveResized (concurrent)" section, after line 700)

### Why

The current `saveResized` calls `ImageIO.write(resized, "JPEG", file)` directly. Two JVM instances with separate registries can both enter the write block for the same target file simultaneously, producing a corrupt partial JPEG. `Files.move(..., ATOMIC_MOVE)` ensures a reader always sees a complete file.

---

- [ ] **Step 1: Write the failing test**

Add this test inside the `"saveResized (concurrent)" should` block in `test/services/LocalImageCacheServiceSpec.scala`, after the existing "not corrupt files when called from many threads simultaneously" test (after line 700, before the closing `}`):

```scala
"not corrupt files when two separate instances write the same image simultaneously" in {
  val dir  = mkTempDir()
  val svc1 = mkService(dir) // simulates JVM instance 1 — fresh empty registry
  val svc2 = mkService(dir) // simulates JVM instance 2 — separate empty registry
  val img  = mkImage(filename = "CrossInstance.jpg", w = 4000, h = 3000)
  val src  = new BufferedImage(2200, 1650, BufferedImage.TYPE_INT_RGB)
  val targetHts = Seq(120, 180, 240, 250, 375, 500, 1100, 1650)
  val allWidths = targetHts.map(h => ImageUtil.resizeTo(4000, 3000, h)).filter(_ < 4000)

  val barrier = new java.util.concurrent.CyclicBarrier(2)
  val f1 = Future { barrier.await(); svc1.saveResized(img, src, 2200) }
  val f2 = Future { barrier.await(); svc2.saveResized(img, src, 2200) }
  Await.result(Future.sequence(Seq(f1, f2)), 30.seconds)

  // No temp files must be left behind
  val tmpFiles = dir.listFiles { (_, name) => name.startsWith(".tmp-") }
  Option(tmpFiles).map(_.toSeq).getOrElse(Seq.empty) must beEmpty

  // All produced files must be valid, non-corrupt JPEGs
  val corrupt = allWidths.filterNot { px =>
    val f = svc1.localFile(img, px)
    f.exists() && scala.util.Try(ImageIO.read(f)).toOption.exists(_ != null)
  }
  corrupt must beEmpty
}
```

- [ ] **Step 2: Run the new test to verify it fails (or is at risk)**

```bash
sbt "testOnly services.LocalImageCacheServiceSpec -- ex 'two separate instances'"
```

Note: with small synthetic images the race may not reliably produce a corrupt file on every run, but the temp-file cleanup assertion (`tmpFiles must beEmpty`) will fail once the implementation exists and we verify no orphan `.tmp-` files are left. Proceed to implementation.

- [ ] **Step 3: Implement atomic rename in `saveResized`**

In `app/services/LocalImageCacheService.scala`, add `StandardCopyOption` to the `java.nio.file` imports at line 17:

```scala
import java.nio.file.{Files, StandardCopyOption}
```

Replace the `saveResized` method body (lines 355–367):

```scala
private[services] def saveResized(image: Image, sourceImg: BufferedImage, sourcePx: Int): Unit =
  targetHeights.foreach { h =>
    val px = ImageUtil.resizeTo(image.width, image.height, h)
    if (px > 0 && px < image.width && px <= sourcePx) {
      val file = localFile(image, px)
      // putIfAbsent atomically claims the slot within this JVM; only the thread
      // that gets null back proceeds to write. Across JVMs, two instances may
      // both proceed — the atomic rename below ensures the file is never corrupt.
      if (registry.putIfAbsent(file.getAbsolutePath, ()) == null) {
        file.getParentFile.mkdirs()
        val resized = scale(sourceImg, px)
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
      }
    }
  }
```

- [ ] **Step 4: Run the full `LocalImageCacheServiceSpec` to verify all tests pass**

```bash
sbt "testOnly services.LocalImageCacheServiceSpec"
```

Expected: all tests pass, including the new cross-instance test and the pre-existing "not overwrite an existing file on a second call" test (which still works because the registry guard prevents the second call from reaching the write block at all).

- [ ] **Step 5: Commit**

```bash
git add app/services/LocalImageCacheService.scala \
        test/services/LocalImageCacheServiceSpec.scala
git commit -m "feat: atomic rename in saveResized for cross-instance safety"
```

---

## Task 2: Systemd units and env example files

**Files:**
- Create: `conf/systemd/wlxjury-blue.service`
- Create: `conf/systemd/wlxjury-green.service`
- Create: `conf/wlxjury/blue.env.example`
- Create: `conf/wlxjury/green.env.example`
- Create: `conf/wlxjury/slots.env.example`

These are checked into the repo as templates. Operators copy them to `/etc/` on the server and fill in real values.

---

- [ ] **Step 1: Create `conf/systemd/wlxjury-blue.service`**

```ini
[Unit]
Description=WLX Jury Tool (blue slot)
After=network.target

[Service]
Type=simple
User=wlxjury
WorkingDirectory=/opt/wlxjury-blue
EnvironmentFile=/etc/wlxjury/blue.env
ExecStart=/opt/wlxjury-blue/bin/wlxjury \
  -Dhttp.port=${WLXJURY_HTTP_PORT} \
  -Dpidfile.path=/run/wlxjury-blue/play.pid
RuntimeDirectory=wlxjury-blue
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

- [ ] **Step 2: Create `conf/systemd/wlxjury-green.service`**

```ini
[Unit]
Description=WLX Jury Tool (green slot)
After=network.target

[Service]
Type=simple
User=wlxjury
WorkingDirectory=/opt/wlxjury-green
EnvironmentFile=/etc/wlxjury/green.env
ExecStart=/opt/wlxjury-green/bin/wlxjury \
  -Dhttp.port=${WLXJURY_HTTP_PORT} \
  -Dpidfile.path=/run/wlxjury-green/play.pid
RuntimeDirectory=wlxjury-green
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

- [ ] **Step 3: Create `conf/wlxjury/blue.env.example`**

```bash
WLXJURY_HTTP_PORT=9000
WLXJURY_DB=wlxjury_blue
WLXJURY_DB_HOST=localhost
WLXJURY_DB_USER=wlxjury
WLXJURY_DB_PASSWORD=changeme
COMMONS_USER=
COMMONS_PASSWORD=
```

- [ ] **Step 4: Create `conf/wlxjury/green.env.example`**

```bash
WLXJURY_HTTP_PORT=9001
WLXJURY_DB=wlxjury_green
WLXJURY_DB_HOST=localhost
WLXJURY_DB_USER=wlxjury
WLXJURY_DB_PASSWORD=changeme
COMMONS_USER=
COMMONS_PASSWORD=
```

- [ ] **Step 5: Create `conf/wlxjury/slots.env.example`**

```bash
# Tracks which slot is currently active and which is canary.
# Updated in-place by wlxjury-promote.sh on each promotion.
ACTIVE_SLOT=blue
CANARY_SLOT=green
ACTIVE_DB=wlxjury_blue
CANARY_DB=wlxjury_green
```

- [ ] **Step 6: Commit**

```bash
git add conf/systemd/wlxjury-blue.service \
        conf/systemd/wlxjury-green.service \
        conf/wlxjury/blue.env.example \
        conf/wlxjury/green.env.example \
        conf/wlxjury/slots.env.example
git commit -m "feat: add systemd units and env templates for blue/green slots"
```

---

## Task 3: Apache slot-file config

**Files:**
- Create: `conf/apache/wlxjury-slot.conf`
- Modify: `conf/apache/0002-wlxjury.conf`

---

- [ ] **Step 1: Create `conf/apache/wlxjury-slot.conf`**

This file is installed to `/etc/apache2/wlxjury-slot.conf` on the server. It holds the current active/canary port assignment and is rewritten by `wlxjury-promote.sh`.

```apache
# Managed by wlxjury-promote.sh — do not edit manually.
# Blue slot: 9000, Green slot: 9001
Define WLXJURY_ACTIVE_PORT 9000
Define WLXJURY_CANARY_PORT 9001
```

- [ ] **Step 2: Rewrite `conf/apache/0002-wlxjury.conf`**

Read the current file first (it is already read above), then replace its entire contents:

```apache
# Include the slot file so ${WLXJURY_ACTIVE_PORT} and ${WLXJURY_CANARY_PORT} are defined.
# Install wlxjury-slot.conf to /etc/apache2/ on the server.
Include /etc/apache2/wlxjury-slot.conf

# ── Production virtual hosts — route to active slot ──────────────────────────

<VirtualHost *:80>
    ProxyPreserveHost On
    ServerName jury.wikilovesearth.org.ua
    ProxyPass    /excluded ! retry=0
    ProxyPass    / http://127.0.0.1:${WLXJURY_ACTIVE_PORT}/ retry=0
    ProxyPassReverse / http://127.0.0.1:${WLXJURY_ACTIVE_PORT}/
    RewriteEngine on
    RewriteCond %{SERVER_NAME} =jury.wikilovesearth.org.ua
    RewriteRule ^ https://%{SERVER_NAME}%{REQUEST_URI} [END,NE,R=permanent]
</VirtualHost>

<VirtualHost *:80>
    ProxyPreserveHost On
    ServerName jury.wikilovesearth.org
    ProxyPass    /excluded ! retry=0
    ProxyPass    / http://127.0.0.1:${WLXJURY_ACTIVE_PORT}/ retry=0
    ProxyPassReverse / http://127.0.0.1:${WLXJURY_ACTIVE_PORT}/
</VirtualHost>

<VirtualHost *:80>
    ProxyPreserveHost On
    ServerName jury.wikilovesmonuments.org.ua
    ProxyPass    /excluded ! retry=0
    ProxyPass    / http://127.0.0.1:${WLXJURY_ACTIVE_PORT}/ retry=0
    ProxyPassReverse / http://127.0.0.1:${WLXJURY_ACTIVE_PORT}/
    RewriteEngine on
    RewriteCond %{SERVER_NAME} =jury.wikilovesmonuments.org.ua
    RewriteRule ^ https://%{SERVER_NAME}%{REQUEST_URI} [END,NE,R=permanent]
</VirtualHost>

<VirtualHost *:80>
    ProxyPreserveHost On
    ServerName jury.wle.org.ua
    ProxyPass    /excluded ! retry=0
    ProxyPass    / http://127.0.0.1:${WLXJURY_ACTIVE_PORT}/ retry=0
    ProxyPassReverse / http://127.0.0.1:${WLXJURY_ACTIVE_PORT}/
    RewriteEngine on
    RewriteCond %{SERVER_NAME} =jury.wle.org.ua
    RewriteRule ^ https://%{SERVER_NAME}%{REQUEST_URI} [END,NE,R=permanent]
</VirtualHost>

<VirtualHost *:80>
    ProxyPreserveHost On
    ServerName jury.wlm.org.ua
    ProxyPass    /excluded ! retry=0
    ProxyPass    / http://127.0.0.1:${WLXJURY_ACTIVE_PORT}/ retry=0
    ProxyPassReverse / http://127.0.0.1:${WLXJURY_ACTIVE_PORT}/
    RewriteEngine on
    RewriteCond %{SERVER_NAME} =jury.wlm.org.ua
    RewriteRule ^ https://%{SERVER_NAME}%{REQUEST_URI} [END,NE,R=permanent]
</VirtualHost>

<VirtualHost *:80>
    ProxyPreserveHost On
    ServerName wlxjury.wikimedia.in.ua
    ProxyPass    /excluded ! retry=0
    ProxyPass    / http://127.0.0.1:${WLXJURY_ACTIVE_PORT}/ retry=0
    ProxyPassReverse / http://127.0.0.1:${WLXJURY_ACTIVE_PORT}/
    RewriteEngine on
    RewriteCond %{SERVER_NAME} =jury.wle.org.ua [OR]
    RewriteCond %{SERVER_NAME} =wlxjury.wikimedia.in.ua
    RewriteRule ^ https://%{SERVER_NAME}%{REQUEST_URI} [END,NE,R=permanent]
</VirtualHost>

# ── Canary virtual host — testers only ───────────────────────────────────────
# Add DNS entry for jury-canary.wle.org.ua pointing to this server.
# Add TLS certificate (e.g. via Certbot) for jury-canary.wle.org.ua.

<VirtualHost *:80>
    ProxyPreserveHost On
    ServerName jury-canary.wle.org.ua
    ProxyPass    /excluded ! retry=0
    ProxyPass    / http://127.0.0.1:${WLXJURY_CANARY_PORT}/ retry=0
    ProxyPassReverse / http://127.0.0.1:${WLXJURY_CANARY_PORT}/
    RewriteEngine on
    RewriteCond %{SERVER_NAME} =jury-canary.wle.org.ua
    RewriteRule ^ https://%{SERVER_NAME}%{REQUEST_URI} [END,NE,R=permanent]
</VirtualHost>
```

- [ ] **Step 3: Commit**

```bash
git add conf/apache/wlxjury-slot.conf conf/apache/0002-wlxjury.conf
git commit -m "feat: Apache slot-file routing for blue/green deployment"
```

---

## Task 4: `wlxjury-deploy-canary.sh`

**Files:**
- Create: `bin/wlxjury-deploy-canary.sh`

Installs a new version to the canary slot and seeds its database from the active slot.

---

- [ ] **Step 1: Create `bin/wlxjury-deploy-canary.sh`**

```bash
#!/usr/bin/env bash
# Usage: wlxjury-deploy-canary.sh <path-to-wlxjury-X.Y.Z.zip>
#
# Deploys the given sbt-dist zip to the canary slot and seeds the canary DB
# from the active DB. Run as root (needs systemctl, mysql access).
#
# Prerequisites:
#   /etc/wlxjury/slots.env   — slot state file (see conf/wlxjury/slots.env.example)
#   /etc/wlxjury/<slot>.env  — per-slot env files (see conf/wlxjury/*.env.example)
#   MariaDB log_bin=ON, binlog_format=ROW

set -euo pipefail

ZIP="${1:?Usage: $0 <path-to-wlxjury.zip>}"
SLOTS_ENV=/etc/wlxjury/slots.env
SYNC_POS=/etc/wlxjury/canary-sync-pos.txt
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

# 3. Dump active DB, capturing binlog position in the dump header (--master-data=2
#    writes it as a SQL comment so the dump can be replayed without replication setup)
echo "Dumping active DB ($ACTIVE_DB)..."
mysqldump --single-transaction --master-data=2 \
  -u"$DB_USER" -p"$DB_PASS" "$ACTIVE_DB" > "$DUMP_FILE"

# 4. Extract and save binlog position for the promote step
grep "CHANGE MASTER TO" "$DUMP_FILE" > "$SYNC_POS"
echo "Saved binlog position:"
cat "$SYNC_POS"

# 5. Restore dump into canary DB (wipe first so Flyway starts from clean slate)
echo "Restoring into canary DB ($CANARY_DB)..."
mysql -u"$DB_USER" -p"$DB_PASS" \
  -e "DROP DATABASE IF EXISTS \`$CANARY_DB\`; \
      CREATE DATABASE \`$CANARY_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -u"$DB_USER" -p"$DB_PASS" "$CANARY_DB" < "$DUMP_FILE"
rm -f "$DUMP_FILE"
echo "Restored $CANARY_DB"

# 6. Start canary — Flyway migrates the canary DB schema on startup
echo "Starting wlxjury-$CANARY_SLOT..."
systemctl start "wlxjury-$CANARY_SLOT"

echo ""
echo "=== Canary deployed ==="
echo "  Slot:    $CANARY_SLOT  (/opt/wlxjury-$CANARY_SLOT)"
echo "  DB:      $CANARY_DB"
echo "  Test at: https://jury-canary.wle.org.ua"
echo ""
echo "When satisfied, run: wlxjury-promote.sh"
```

- [ ] **Step 2: Make executable**

```bash
chmod +x bin/wlxjury-deploy-canary.sh
```

- [ ] **Step 3: Commit**

```bash
git add bin/wlxjury-deploy-canary.sh
git commit -m "feat: add wlxjury-deploy-canary.sh"
```

---

## Task 5: `wlxjury-promote.sh`

**Files:**
- Create: `bin/wlxjury-promote.sh`

Replays binlogs into the canary DB, swaps the Apache slot file, reloads Apache. Safe to run twice for rollback.

---

- [ ] **Step 1: Create `bin/wlxjury-promote.sh`**

```bash
#!/usr/bin/env bash
# Promotes the canary slot to active.
#
# 1. Replays MariaDB binlogs from the position saved by wlxjury-deploy-canary.sh
#    into the canary DB, bringing it current (takes seconds, not a minute).
# 2. Swaps /etc/apache2/wlxjury-slot.conf and /etc/wlxjury/slots.env.
# 3. Reloads Apache (~2 sec gap while workers drain).
#
# Run twice to rollback. After rollback, canary-sync-pos.txt is stale —
# re-run wlxjury-deploy-canary.sh before the next deployment.
#
# Run as root.

set -euo pipefail

SLOT_FILE=/etc/apache2/wlxjury-slot.conf
SYNC_POS=/etc/wlxjury/canary-sync-pos.txt
SLOTS_ENV=/etc/wlxjury/slots.env
BINLOG_DIR=/var/log/mysql

source "$SLOTS_ENV"   # ACTIVE_SLOT, CANARY_SLOT, ACTIVE_DB, CANARY_DB
source /etc/wlxjury/"$CANARY_SLOT".env  # WLXJURY_DB_USER, WLXJURY_DB_PASSWORD

DB_USER="$WLXJURY_DB_USER"
DB_PASS="$WLXJURY_DB_PASSWORD"

echo "=== Promoting $CANARY_SLOT → active (current active: $ACTIVE_SLOT) ==="

# 1. Replay binlogs from the position captured at canary-deploy time.
#    --to-last-log reads all binlog files from $LOG_FILE forward, handling rotation.
LOG_FILE=$(grep -oP "MASTER_LOG_FILE='\K[^']+" "$SYNC_POS")
LOG_POS=$(grep -oP  "MASTER_LOG_POS=\K[0-9]+"  "$SYNC_POS")
echo "Replaying binlogs from $LOG_FILE position $LOG_POS into $CANARY_DB..."
mysqlbinlog --start-position="$LOG_POS" --to-last-log "$BINLOG_DIR/$LOG_FILE" \
  | mysql -u"$DB_USER" -p"$DB_PASS" "$CANARY_DB"
echo "Binlog replay complete."

# 2. Swap Apache slot file and slots.env
if grep -q "WLXJURY_ACTIVE_PORT 9000" "$SLOT_FILE"; then
  # blue→active, green→canary  ⟹  swap to  green→active, blue→canary
  sed -i \
    's/WLXJURY_ACTIVE_PORT 9000/WLXJURY_ACTIVE_PORT 9001/;
     s/WLXJURY_CANARY_PORT 9001/WLXJURY_CANARY_PORT 9000/' "$SLOT_FILE"
  sed -i \
    's/ACTIVE_SLOT=blue/ACTIVE_SLOT=green/;
     s/CANARY_SLOT=green/CANARY_SLOT=blue/;
     s/ACTIVE_DB=wlxjury_blue/ACTIVE_DB=wlxjury_green/;
     s/CANARY_DB=wlxjury_green/CANARY_DB=wlxjury_blue/' "$SLOTS_ENV"
else
  # green→active, blue→canary  ⟹  swap to  blue→active, green→canary
  sed -i \
    's/WLXJURY_ACTIVE_PORT 9001/WLXJURY_ACTIVE_PORT 9000/;
     s/WLXJURY_CANARY_PORT 9000/WLXJURY_CANARY_PORT 9001/' "$SLOT_FILE"
  sed -i \
    's/ACTIVE_SLOT=green/ACTIVE_SLOT=blue/;
     s/CANARY_SLOT=blue/CANARY_SLOT=green/;
     s/ACTIVE_DB=wlxjury_green/ACTIVE_DB=wlxjury_blue/;
     s/CANARY_DB=wlxjury_blue/CANARY_DB=wlxjury_green/' "$SLOTS_ENV"
fi

# 3. Reload Apache (graceful — existing connections finish before workers restart)
systemctl reload apache2

echo ""
echo "=== Promotion complete ==="
grep "ACTIVE_SLOT\|CANARY_SLOT" "$SLOTS_ENV"
grep "ACTIVE_PORT\|CANARY_PORT" "$SLOT_FILE"
echo ""
echo "To rollback: run this script again."
echo "Writes made after this promotion will be lost on rollback."
```

- [ ] **Step 2: Make executable**

```bash
chmod +x bin/wlxjury-promote.sh
```

- [ ] **Step 3: Commit**

```bash
git add bin/wlxjury-promote.sh
git commit -m "feat: add wlxjury-promote.sh"
```

---

## Post-implementation checklist

- [ ] Deploy blue slot manually once to `/opt/wlxjury-blue/`, copy env files to `/etc/wlxjury/`, install systemd units, reload systemd (`systemctl daemon-reload`)
- [ ] Install `wlxjury-slot.conf` to `/etc/apache2/`, add `Include` to Apache config, test with `apachectl configtest`
- [ ] Create MariaDB `wlxjury_green` database and grant user permissions
- [ ] Enable MariaDB binary logging if not already: add `log_bin = ON` and `binlog_format = ROW` to `/etc/mysql/mariadb.conf.d/50-server.cnf`, restart MariaDB
- [ ] Add DNS A record for `jury-canary.wle.org.ua`
- [ ] Obtain TLS certificate for `jury-canary.wle.org.ua` (e.g. `certbot --apache -d jury-canary.wle.org.ua`)
