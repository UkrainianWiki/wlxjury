# Deployment and recovery

Use `scripts/deployment/wlxjury-deploy.py` on Linux as root, with Python 3.8+, systemd, Apache and MariaDB client tools, for the one-time setup and the cutover release. After the cutover, routine releases use the smaller [`wlxjury-release`](#routine-deployment-after-the-cutover) script, with a `restore` command and a recovery runbook ([why](#why-it-works-this-way)). The old blue/green scripts are disabled and their slot files removed.

Run every `deploy`, `restore` and `reopen` inside `tmux` (or `screen`). The tool ignores SSH hangups and logs each step to `/var/backups/wlxjury/operations.log`, so a dropped connection does not interrupt a backup, migration or restore. You still need the terminal to follow it and to act on the result.

The selected baseline is the latest benchmarked background-distribution release, including **V55.1 and V60**. The changes from `bcdf9847` are present as `32a1253`; its migration, test and fixture are identical. The measured MariaDB recommendation remains `bench/cnf/recommended-60-wlxjury.cnf`, with a **256 MiB** buffer pool. These changes have been implemented locally; production setup and real-data rehearsal are separate rollout steps.

## Why it works this way

One `wlxjury.service`, immutable versioned ZIP releases, a maintenance window and a rehearsal
against an isolated database (2026-10-11 investigation; implemented in `dbedca56`, review fixes
`ebb6fd04`). Blue/green was dropped: it gave neither zero downtime nor a safe rollback after a
migration, and two app processes and two databases don't fit the server's disk. No standard
tool (Ansible, pyinfra, Capistrano-style release folders) covers the recovery part (phase
records, `restore` with its dump, refusing a restore after reopening), so the one-time cutover
keeps the tested `wlxjury-deploy.py`; routine releases use the small `wlxjury-release`
(`b5a1f9ec`, `restore` in `cc15dfd7`) with a rehearsed runbook instead of guided recovery
commands (tooling comparisons `24d0c62a`, `6d96d264`). The cutover code (`adopt-legacy`,
`scrub-legacy`, about 350 lines) is retired once the cutover is accepted and its recovery
records are gone (roadmap). The release artifact is the ZIP from `sbt dist`; the Debian and RPM
packages were dropped (2026-11-04, unused for years). The server's original `wlxjury.service` unit
and `wlxjury` user came from the old Debian package; `wlxjury-infra` now manages both (its
`wlxjury_app` role). No containers on production for now (the user, 2026-11-04): the reasons,
and the path if that changes, are in [operations](operations/practices.md) §4.5.

## One-time setup on the server

**The server setup is now automated** by the separate `wlxjury-infra` repository (Ansible). It manages:
- the Apache vhosts with the maintenance include, and the maintenance snippet;
- the certificates;
- `/etc/wlxjury-runtime` and its files, with secrets from an encrypted vault;
- the systemd unit and the app's MariaDB settings;
- the installation of `wlxjury-deploy` and `wlxjury-release`.

**Java 17 is required.** Releases are compiled for Java 17 (class file version 61; `-release:17`
in `build.sbt`); on Java 11 the app fails at start with `UnsupportedClassVersionError`.
Production runs OpenJDK 17 (17.0.20.1 when checked on 2026-11-04), the default `java` on Ubuntu
22.04; `wlxjury-infra` installs `openjdk-17-jre-headless`. The launcher (`bin/wlxjury`) runs
`$JAVA_HOME/bin/java` if `JAVA_HOME` is set (and that file is executable), else the `java` on
`PATH`, so keep 17 the default and don't point `JAVA_HOME` at another JDK. The server also has an old Java 11, not the default and no longer needed:
the user may remove it later (for example `apt remove openjdk-11-jre-headless`, after checking
with `update-alternatives --list java` that nothing else selects it). Production's
`-XX:G1PeriodicGCInterval` already needed Java 12 or later.

It can also recreate a new server. For this server, follow its `docs/adopting-the-existing-server.md`. It replaces the manual steps below, apart from `adopt-legacy` and `scrub-legacy`. Those steps, and the files under `conf/apache`, `conf/systemd` and `conf/wlxjury`, stay here until production is configured from that repository.

Run from a reviewed checkout. `/etc/wlxjury` points into the old Debian installation (`/usr/share/wlxjury`), so runtime settings go in a new real directory. No credentials belong in the checkout, ZIP, command-line arguments or console output.

```bash
sudo install -D -o root -g root -m 0755 scripts/deployment/wlxjury-deploy.py /usr/local/sbin/wlxjury-deploy
sudo install -d -o root -g root -m 0755 /etc/wlxjury-runtime
sudo wlxjury-deploy init-runtime --legacy-config /etc/wlxjury/application.conf
sudo install -o root -g wlxjury -m 0640 conf/wlxjury/production.conf /etc/wlxjury-runtime/production.conf
sudo install -o root -g wlxjury -m 0640 conf/wlxjury/logback.xml /etc/wlxjury-runtime/logback.xml
sudo install -o root -g root -m 0600 conf/wlxjury/deployment.json.example /etc/wlxjury-runtime/deployment.json
sudo install -o root -g root -m 0600 conf/wlxjury/db-admin.cnf.example /etc/wlxjury-runtime/db-admin.cnf
```

`init-runtime` copies supported literal credentials directly on the server, retaining the signing key without printing it. It leaves old config and services unchanged and refuses to replace an existing `runtime.env`. It rejects complex HOCON, duplicate keys, multiline strings and unsupported URLs instead of guessing. If rejected, populate `runtime.env` directly on the server using the example's key list. Use one fully double-quoted value per line; escape backslashes and double quotes. Spaces, `$`, `#` and backticks are literal. Omit unused Commons variables. Never source this file as shell.

The runtime directory is `0755` so Apache can stat its nonsecret maintenance flag. Secrets are root-only `0600`; app-readable nonsecret config is `0640 root:wlxjury`. Preserve `/etc/default/wlxjury` JVM tuning. The production overlay explicitly includes the release's classpath defaults, requires the signing key, maps DB/Commons credentials to the environment and enables Flyway migration/validation while prohibiting automatic baseline/clean behavior. Review intentional production URL, greeting and mail overrides once; leave application tuning with the release.

Sign-in with Google and Wikimedia accounts is off until `production.conf` lists hosts and credentials for it; [oauth.md](oauth.md) covers registering the clients, the `runtime.env` variables and the hosts (each also in `WLXJURY_ALLOWED_HOSTS`).

Review `db-admin.cnf`: its example uses local root Unix-socket authentication. Otherwise configure a separate administrative account in that protected file. Verify access to the intended DB, table locking, routine/event/trigger dumps and database recreation/restore. Application passwords are never passed to DB client commands. The tool uses the same host/port/name as `runtime.env`.

Edit `deployment.json` for the actual datadir, all jury HTTP/HTTPS URLs and coordinated writer services/timers. The example pauses the database-backup timer and any running backup service. Enabled DB events block deployment until coordinated. The 4 GiB migration / 2 GiB recovery free-space gates are starting values to replace with measured budgets, including backups, indexes, conversion, binlogs and other hosted services. They are not a capacity certification. Paths must be real, root-owned directories.

Install `conf/apache/wlxjury-maintenance.conf` as `/etc/apache2/wlxjury-maintenance.conf`, readable and root-owned. Include it **inside every jury HTTP and HTTPS VirtualHost, before redirect rules**; `conf/apache/0002-wlxjury.conf` shows the HTTP vhosts. Preserve certificates and unrelated websites. Fix the stale jury proxy to 9001 so it uses 9000. Verify all aliases, run `sudo apachectl configtest` and reload Apache once. The flag is evaluated on every request; later deployments need no Apache edits or reload.

Every URL in `maintenance_urls` must resolve to this server **from the server itself**, with a valid certificate: the tool fetches each one, expects 503 **with the snippet's maintenance page** before it stops anything (a vhost without the snippet also answers 503 while the app is stopped, but with Apache's own page), and expects the app (2xx/3xx) after `reopen`. A failure names the URL and the cause (HTTP status, TLS certificate, DNS, connection). `/health/ready` answers only requests from the server itself; through Apache it is a 404.

Adopt a sanitized recovery snapshot without restarting the running original:

```bash
sudo wlxjury-deploy adopt-legacy /usr/share/wlxjury --build legacy-0.12-v53
sudo install -D -o root -g root -m 0644 conf/systemd/wlxjury.service.d/deployment.conf /etc/systemd/system/wlxjury.service.d/deployment.conf
sudo systemctl daemon-reload
```

Adoption checks external and JAR migration catalogs and refuses installed files with migrations beyond the live DB version: retrieve the actual old release if package files were updated without restarting. It then copies the launcher/libraries/config, removes literal credentials from the copy, excludes package-conflict/backup config copies, records its schema/hashes and selects it via `/opt/wlxjury/current`. It leaves the original installation untouched.

Before the copy becomes readable, adoption scans its `*.conf`, `*.ini`, `*.properties`, `*.cnf` and `*.env` files for any setting named like a password or secret that still has a literal value. HOCON files are read by full key path, so nested blocks (`play.http.secret { key = ... }`), quoted or joined key parts and `+=` all count, while text inside strings, such as the jury greeting's `password: {{Password}}`, does not. Other formats, and `.conf` files that are not HOCON (Apache, systemd), are read line by line (`key = value`, `-Dkey=value`, properties `key value`). URL parameters such as `&password=` count everywhere. Commented-out credentials are dropped from the copy. A live one, such as a mail password, stops the adoption, removes the copy and names the file, line and key, never the value. In that case, copy the installation (`cp -a /usr/share/wlxjury /root/wlxjury-legacy-reviewed`), change those settings to `${?VARIABLE}` references, add the variables to `runtime.env`, and adopt from the reviewed copy. Logs live at `/var/log/wlxjury`, PID at `/run/wlxjury`, and the launcher binds to loopback. The drop-in preserves the installed service/user and allows six minutes for graceful stop. Stopping the service also stops V60 job workers; committed job state remains in the DB for recovery.

First validate external configuration with the adopted release itself, without a schema upgrade:

```bash
sudo wlxjury-deploy deploy /opt/wlxjury/releases/legacy-0.12-v53 --writers-reviewed
# Check login/normal pages locally at port 9000 while maintenance stays enabled.
sudo wlxjury-deploy reopen RECORD_ID
sudo wlxjury-deploy scrub-legacy RECORD_ID --legacy-config /etc/wlxjury/application.conf
```

Use the printed record ID. `scrub-legacy` requires an accepted rollout and verifies that the live JVM uses external config: its last `-Dconfig.file` must be `production.conf`, with no `-Dconfig.resource`/`-Dconfig.url` and no `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` or `_JAVA_OPTIONS` naming another config. It retains the original only in the protected recovery record and replaces old literal credentials with environment references. Review other historical copies/backups for exposed credentials under your retention policy. Keep the signing key during the move; rotation is separate. Keep the installed package for initial recovery, but do not run package upgrades that bypass the new procedure.

## Build and rehearse

```bash
sbt test
sbt -Dwlxjury.buildId=26.11 dist
sha256sum target/universal/wlxjury-26.11.zip
```

Versions are calendar-based, `YY.MM` with `.N` for an extra deploy in the same month; the release commit sets `version := "26.11"` in `build.sbt` (between releases it is the next one's snapshot, `26.11-SNAPSHOT`, whose ZIP is `wlxjury-26.11-SNAPSHOT.zip`). The steps are in [workflow practices](workflow/practices.md) §7.4. Builds before this scheme are named `0.13-…`. Use a reviewed build name and the final ZIP hash. A requested build ID is refused if `app`, `conf`, `public`, `project` or `build.sbt` has uncommitted **or untracked** changes (untracked Twirl templates are compiled too), and the short commit is appended unless the name already contains it, e.g. `26.11-dbedca565807`; the release directory on the server is that ID plus the ZIP hash's first 12 characters. Without `-Dwlxjury.buildId` the ID is version and commit, with `-dirty` for any such change. The rules are in `project/BuildIdentity.scala`, tested by `deployment.BuildIdentitySpec`. Server setup files (`conf/wlxjury`, `conf/apache`, `conf/systemd`) are installed from the checkout and are not in the ZIP. `wlxjury-build.json` is both at the release root and embedded in the app; it contains build, version and the latest numerically ordered migration, including 55.1. Artifact hashes distinguish packages even when version/build names repeat.

Rehearse the **exact ZIP** against a consistent isolated production copy on production's MariaDB (10.6 until the move to Ubuntu 24.04's 10.11, `docs/plans/2026-10-19-mariadb-10.11.md`), including Flyway history. Production MyISAM needs locking or stopped writers; `--single-transaction` alone is insufficient. Disable real email, isolate access and staging cache, and prevent copied jobs from contacting production services. Measure migration, startup, backup, restore and peak space; review V58 orphans, V65 orphans (the count of `category_members` rows whose image or category is gone, which V65 archives into `category_members_orphans` and deletes; the check query is in its header) and V60 job recovery. Test restoring the backup and old release too.

From V53, this release applies V54, V55, V55.1 and V56–V60. V55.1 rebuilds tables and changes collation; earlier production-copy measurements were about 2.5 minutes with considerable disk growth before other migration work. Set an outage/timeout budget from rehearsal. Read `docs/history/2026-10-07-prod-like-benchmark.md` for measured results and limits. Apply the reviewed 256 MiB MariaDB config in maintenance after conversion, restart/recheck MariaDB and the app before reopening. The tool does not change DB server settings used by other hosted applications.

## Cutover deployment with wlxjury-deploy

Use this for the cutover release (release 1, `26.11`; the ZIP built above). Upload the reviewed ZIP:

```bash
sudo wlxjury-deploy prepare /srv/releases/wlxjury-26.11.zip --sha256 FULL_LOWERCASE_SHA256
sudo wlxjury-deploy deploy /opt/wlxjury/releases/PRINTED_RELEASE_NAME --writers-reviewed
```

`prepare` verifies the ZIP hash, rejects traversal/symlinks, strips its single archive root and creates a service-readable immutable release with file hashes. It never overwrites the active release. `--writers-reviewed` acknowledges that external writers/DDL jobs were identified and coordinated. Stay outside the Kyiv midnight statistics job or explicitly pause that writer too; a proxy flag alone does not drain in-flight work. Review import interruption/retry behavior.

Deployment refuses, before changing anything, a release whose expected schema is older than the database (Flyway would only reject it after the outage began). It then locks operations; checks current schema and capacity; creates a private recovery/config record; enables and verifies HTTP **503 at every configured HTTP/HTTPS URL**; turns off automatic restarts (`zz-deployment-hold.conf` with `Restart=no` beside the drop-in, so a failed or killed start never re-runs migrations on its own); stops relevant units and the app; takes a locking mixed-engine backup with history; fsyncs/checksums it; rechecks space; atomically switches release; starts/migrates; and waits for strict HTTP 200 with expected build/schema. `/health/ready` uses the actual app pool and returns 503 for connection failure, failed history or old/missing schema, without exposing credentials or driver details.

**Traffic remains blocked when deployment returns.** Smoke-test localhost port 9000: login, gallery/images, results, agreed reversible votes/comments/management, and job resumption. Inspect logs, table engines/collations/constraints and latest successful Flyway installed rank. Then:

```bash
sudo wlxjury-deploy reopen RECORD_ID
```

`reopen` checks readiness/maintenance again, restores automatic restarts, durably records that writes may resume, removes maintenance, checks that every jury URL reaches the app and resumes paused units. If a URL or a paused unit fails, the record stays `opening`: fix it and run `reopen` again. Verify public HTTPS and monitor errors/memory/jobs. Keep backups/old release. Routine deployments modify **no credential or site-config files** and need no application-config merge. Recovery records/config snapshots live in root-only `/var/backups/wlxjury/UTC_TIMESTAMP-ID/`.

## Recovery

Errors/interrupts never remove maintenance automatically. Inspect the printed record and phase.

Before the backup (phase `created` or `maintenance`), for example when a URL did not answer 503:

```bash
sudo wlxjury-deploy abort RECORD_ID
```

`abort` leaves the original release running (or starts it if the service was already stopped), restores automatic restarts and paused units, removes the flag and checks every URL. A routing gap does not block it. Fix the gap before the next `deploy`.

After the service stopped and before migration starts, including a failed backup:

```bash
sudo wlxjury-deploy resume-original RECORD_ID
# Verify locally, then:
sudo wlxjury-deploy reopen RECORD_ID
```

Once candidate startup/migration may have begun, while traffic remains blocked:

```bash
sudo wlxjury-deploy restore RECORD_ID
# Verify old app and data locally, then:
sudo wlxjury-deploy reopen RECORD_ID
```

Restore verifies checksum and gzip integrity **before DB deletion**, checks DB identity, stops writers, ends the app account's leftover sessions (such as an `ALTER` that outlived the stopped JVM; a killed `ALTER` is waited for up to `session_end_timeout_seconds`), refuses while any other session uses the database (all before the post-cutover rescue dump, whose table locks would otherwise wait on a leftover session; after a refusal maintenance stays on, so end those sessions and rerun `restore`), recreates the target with its recorded character set and collation, restores the entire dump/history/config and old release, then verifies readiness. The migration-started phase is saved **before** candidate startup: a failed start may already have committed DDL. The adopted old binary uses a clearly marked `/auth` + schema check until replaced by releases with readiness.

After traffic resumes, prefer a forward fix or a separately rehearsed compatible-code deployment preserving current data. If snapshot restoration is the agreed decision:

```bash
sudo wlxjury-deploy restore RECORD_ID --accept-data-loss
```

This explicitly accepts losing later writes and first saves a fresh `before-restore-*` rescue dump; the free-space check counts the current database size for it before anything stops. Maintenance stays enabled for verification. Plan reconciliation of later votes/comments/jobs; the tool does not replay them across incompatible schemas. It never automatically repairs/cleans/baselines Flyway or deletes snapshots.

An interrupted reopening conservatively marks possible writes even if maintenance still exists; restoration then requires the explicit decision. `reopen` can complete an interrupted opening after rechecking the selected release. Unexpected `current.next` or `*.tmp` leftovers require inspection of the durable record/contents before removing only that specific leftover and retrying. Resolve space, routing, credential or host failures before retrying.

## Routine deployment after the cutover

Once the cutover is complete, deploy releases with `wlxjury-release`. It reads the same `deployment.json`, `runtime.env` and `db-admin.cnf` as `wlxjury-deploy` and takes the same lock (`/opt/wlxjury/deployment.lock`), so no two operations of either tool overlap. Do not use `wlxjury-deploy deploy` after switching: it cannot verify releases it did not prepare. Keep `wlxjury-deploy` only to recover from its own earlier records until they are retired.

Its database commands always use the app's host from `runtime.env`, whatever `db-admin.cnf` says. For `WLXJURY_DB_HOST=localhost` they connect through the socket in `db-admin.cnf`, with root socket authentication, and check that this server listens on port 3306. For any other host, or an explicit port, they connect by TCP to that host and port, so `db-admin.cnf` then needs an administrative account that can log in over TCP. Each operation records the database name and endpoint, and `restore` refuses a backup whose database differs from what `runtime.env` now names.

Install it once, with `curl` and `unzip`:

```bash
sudo install -o root -g root -m 0755 scripts/deployment/wlxjury-release.sh /usr/local/sbin/wlxjury-release
```

Run it inside `tmux`, with the reviewed ZIP from `sbt dist` and its full SHA-256:

```bash
sudo wlxjury-release deploy /srv/releases/wlxjury-26.11.zip FULL_LOWERCASE_SHA256
# Smoke-test http://127.0.0.1:9000/ locally with a jury name (curl -H 'Host: jury.example.org'), as for the cutover. Then:
sudo wlxjury-release reopen OPERATION
```

A release with new migrations always gets a database backup. A release without them (its `schemaVersion` equals the database's) skips the dump, which shortens the outage, and records `backup-skipped`; add `--backup` to take one anyway. Backing out such a deployment only switches the release back, keeping the database and every write made since.

Before changing anything, `deploy` takes the lock and checks:
- the ZIP hash, with no symlinks and one top-level folder;
- the manifest;
- that `db-admin.cnf` reaches the app's database endpoint;
- that the release is not older than the database and the database has no failed migration;
- that no database event is enabled, and free space.

It unpacks the release into a new root-owned folder under `/opt/wlxjury/releases`. Then it:
1. creates the operation folder `/var/backups/wlxjury/UTC_TIMESTAMP-BUILD`, with `record`, `milestones` and `operation.log`;
2. enables maintenance and requires HTTP 503 with the maintenance page at every configured URL;
3. turns off automatic restarts, and stops **every** configured writer unit and the app, checking each has stopped (a timer can start its service at any moment, so the recorded state only decides what `reopen` restarts);
4. rechecks database events;
5. dumps the database with `--databases --add-drop-database --lock-tables`, unless the backup is skipped, then checks the dump and saves its checksum and a config snapshot;
6. flushes them and rechecks free space;
7. switches `/opt/wlxjury/current`, starts the app, and waits until `/health/ready` reports the new build.

`reopen` requires:
- intact milestones and no unfinished restore;
- that `current` is the operation's candidate release, or its previous release after a finished restore (`database-restored` or `release-restored`);
- the release to be ready.

It then records `traffic-may-resume`, restores automatic restarts, removes maintenance, checks every URL reaches the app, and restarts the writer units that were running before the deployment. If a URL fails, fix routing and run `reopen` again. Once it records `complete`, it [prunes](#retention-old-releases-and-dumps) old releases and dumps; a failed prune only warns, since traffic is open by then.

`deploy` also refuses, before anything changes, while [the image cache folder](#the-image-cache-folder) is not owned by the service user or is group- or world-writable.

`restore OPERATION` backs out a deployment, holding the lock throughout and stopping at the first failure:
1. It refuses an operation without a complete backup, refuses after `traffic-may-resume` without `--accept-data-loss`, refuses a backup of a database other than the one `runtime.env` now names, and refuses when the dump was pruned.
2. It verifies the backup's checksum and gzip integrity before changing anything, and checks free space: `restore_minimum_free_bytes` (or `minimum_free_bytes`), plus the database's size when a rescue dump is needed.
3. It enables and checks maintenance, turns off automatic restarts, and stops every writer unit and the app.
4. It ends the app account's leftover sessions, waiting up to `session_end_timeout_seconds` for a killed `ALTER` to roll back, and refuses while any other session uses the database.
5. If writes may have happened since the last import attempt (`traffic-may-resume` after any earlier `restoring`), it saves the current database as a new `before-restore-UTC_TIMESTAMP.sql.gz`, checked, with its checksum, and flushed, and records its name as a `rescue` milestone. Earlier rescue dumps are never replaced. A retry after an unfinished import takes no new rescue dump: the database is partly restored or even dropped, and the earlier rescue dump already holds those writes.
6. It rechecks the free-space reserve, records `restoring` and imports the backup; a failure on either side of the import stops it.
7. It checks that the database has the previous release's schema and no failed migration, switches `current` back, records `database-restored`, starts the previous release and waits until it is ready.

After `backup-skipped`, `restore OPERATION` imports nothing and needs no `--accept-data-loss`: it checks that the database still has the previous release's schema with no failed migration (refusing if a later deployment migrated it), enables and checks maintenance, turns off automatic restarts, stops the writers and the app, switches `current` back, records `release-restored`, starts the previous release and waits until it is ready.

It never restores config: if the release changed config, restore `config.tgz` by hand before reopening.

### Retention: old releases and dumps

Release folders and pre-deployment dumps (`database.sql.gz`) stay on the server only as long as a `restore` can need them; the server's long-term backup is separate. `wlxjury-release prune` deletes the rest. It runs by itself at the end of every successful `reopen`, never after a failed one, so a rollback stays possible throughout a deployment. By hand:

```bash
sudo wlxjury-release prune --dry-run   # lists what it keeps, and why, and what it would delete
sudo wlxjury-release prune
```

It takes the deployment lock and refuses while maintenance is on. If only the prune at the end of a `reopen` fails, `reopen` prints `PRUNE FAILED after a successful reopen` and exits 3: traffic is open and the operation is `complete`, so don't rerun `reopen`; fix the cause and run `prune`. The settings are in `deployment.json`, with these defaults (the user, 2026-11-04; the numbers may change):

| Setting | Default | Keeps |
|---|---|---|
| `keep_previous_releases` | 2 | the current release, plus the 2 releases besides it that an operation named most recently (as `previous` or `candidate`), whatever else keeps them; folders no operation names come last |
| `keep_backup_dumps` | 3 | the dumps of the 3 newest operations that took one (every release with migrations, and `--backup`) |
| `keep_backup_days` | 14 | every dump younger than that, whatever the count |

Whatever the numbers, it never deletes:
- the release `current` points at, or a release named by an operation that is not `complete` (of either tool; a missing or damaged milestones file, or a damaged record, counts as open);
- the dump of an open operation, or of the operation that deployed the current release;
- **the previous release of every dump it keeps**, so every dump left can be restored with `wlxjury-release restore`;
- rescue dumps (`before-restore-*`), operation folders (`record`, `milestones`, `config.tgz`, the log), or anything of `wlxjury-deploy`'s cutover records, apart from their releases once those records are `complete`;
- symlinks, and folders without a `wlxjury-build.json` (such as `lost+found`).

So more than three releases can stay: one per kept dump besides. With a migrating deploy every day, the 14-day rule alone keeps about 14 dumps and so about 14 releases; lower `keep_backup_days` if that is too much disk.

It decides everything before deleting anything, and stops, deleting nothing, when it can't tell what is safe: an open operation whose releases it can't read or that names a path outside `releases`, or a symlink among the operation folders. A dump whose age can't be read is kept. Paths are compared resolved (`realpath -m`), and the settings are read as decimal whole numbers.

It also removes `.staging.*` folders that a killed `deploy` left. Each deletion is logged with its size to `prune.log` in the backup folder, and, after a `reopen`, to that operation's `operation.log`; a pruned dump also gets a line in its own operation's log, and `restore` of that operation then refuses before anything changes. `keep_previous_releases` and `keep_backup_dumps` must be at least 1, since a restore needs the previous release and the newest dump.

Example: releases r1 to r8 deployed in order, then switched back to r2, with every dump a month old except r4's (3 days). Prune keeps:
- r2, the current release;
- r8 and r7, the two used most recently besides it;
- r7, r6 and r5, the previous releases of the newest three dumps;
- r3, for r4's young dump, and r1, for r2's dump, which deployed the current release.

It deletes r4 and the legacy release, and the dumps of r1's, r3's and r5's operations.

### The image cache folder

The image cache (`wlxjury.thumbs.local-path`, default `/var/www/jury-images`) holds the thumbnails the app downloads from Commons. Only the app reads and writes it: `ImageProxyController` serves it to signed-in users. The optional `conf/apache/jury-images.conf` would serve it to anyone, without that check; the steps here don't use it. The app writes each file as a temporary `.tmp-*` file in the target folder and moves it into place, creating folders as it goes; it needs to own the folders, nothing more. So the folder is owned by the service user (`wlxjury`), mode `0755`: folders `0755` and files `0644`, as the app creates them (with the unit's default umask).

`deploy` refuses, before anything changes, while the folder named by `image_cache_dir` in `deployment.json` is not owned by `service_user` (default `wlxjury`) or is group- or world-writable. It is checked only if it exists (on a new server the app creates it), and at its target if it is a symlink. If the folder is group- or world-writable, or owned by another account, fix it with:

```bash
sudo chown wlxjury:wlxjury /var/www/jury-images
sudo chmod 0755 /var/www/jury-images
# Then anything below that others could write to. find passes names as arguments, never through
# a shell or xargs, so Cyrillic and other names are safe; -xdev stays on this filesystem. Only
# entries that need it are changed, so a large cache isn't rewritten.
sudo find /var/www/jury-images -xdev \! -user wlxjury -print            # expect nothing
sudo find /var/www/jury-images -xdev -type d -perm /022 -exec chmod go-w {} +
sudo find /var/www/jury-images -xdev -type f -perm /022 -exec chmod go-w {} +
stat -c '%a %U:%G' /var/www/jury-images                                 # 755 wlxjury:wlxjury
```

`wlxjury-infra` should own this (the draft in [`ci-drafts/infra/`](plans/ci-drafts/infra/ops-retention-monit.diff)).

### Monitoring with monit

Where monit is installed, [`conf/monit/wlxjury`](../conf/monit/wlxjury) adds the app. It is **alert only**: no start, stop or restart. systemd already restarts the app after a crash (`Restart=on-failure`), and a restart by monit during a deployment would bypass the deployment's hold (`Restart=no`) and could rerun a migration. For the same reason, other monit checks on a shared host should not restart MariaDB during a deployment.

It runs [`conf/monit/wlxjury-check`](../conf/monit/wlxjury-check) with `deployment.json`, from which it reads the service, the port and the runtime folder. It fails when the service is not active or `http://127.0.0.1:PORT/health/ready` doesn't answer 200 (the release's schema and READ COMMITTED; loopback only, any `Host` header, [operations](operations/practices.md) §2.1), or when it can't read `deployment.json`. While the maintenance flag is on it is quiet, so a deployment raises no alert, unless the flag is more than two hours old (an operation that stopped). An alert comes after three failed cycles in a row, so a crash that systemd restarts in seconds raises none. Install it once:

```bash
sudo install -D -o root -g root -m 0755 conf/monit/wlxjury-check /usr/local/lib/wlxjury/monit-check
sudo install -o root -g root -m 0644 conf/monit/wlxjury /etc/monit/conf.d/wlxjury
sudo /usr/local/lib/wlxjury/monit-check /etc/wlxjury-runtime/deployment.json; echo "exit $?"   # ready, exit 0
sudo monit -t && sudo monit reload    # if monit -t fails: sudo rm /etc/monit/conf.d/wlxjury
sudo tail -n 5 /var/log/monit.log     # a reload line, no 'wlxjury' error
```

Undo: remove both files and `sudo monit reload`. Whom monit alerts, and how, is the server's monit configuration (`set alert`, `set mailserver`); check that it alerts someone. A filesystem space alert (`check filesystem` on the disk that holds the releases, dumps and image cache) is worth adding there too. `wlxjury-infra` should install these files (the draft in [`ci-drafts/infra/`](plans/ci-drafts/infra/ops-retention-monit.diff)).

### Static assets

`sbt dist` fingerprints `/public` (sbt-digest): pages link `/assets/<md5>-<name>`, which the app serves with `Cache-Control: public, max-age=31536000, immutable`, so a release that changes a file changes its URL and browsers cannot run new pages with old scripts or styles. Plain names (raty's star images, which it finds by their directory, and old links) are still served, cached for an hour. Apache passes `/assets` through unchanged. To check a release:

```bash
curl -s -H 'Host: jury.wle.org.ua' http://127.0.0.1:9000/auth | grep -o '/assets/[^"]*main.css'   # /assets/<32 hex digits>-main.css
curl -sI -H 'Host: jury.wle.org.ua' http://127.0.0.1:9000/assets/stylesheets/THAT_NAME | grep -i cache-control
```

`sbt run` and the tests serve plain names: the digests exist only in the packaged assets jar.

### Host names and secure cookies

The app answers only requests whose `Host` names it (Play's `AllowedHostsFilter`); any other gets a 400 page. The names come from `WLXJURY_ALLOWED_HOSTS` in `runtime.env`: every `ServerName` of the jury vhosts, comma-separated, for example `"jury.wle.org.ua,jury.wlm.org.ua"`. Leave out the default ports (`:80`, `:443`), which browsers don't send; a suffix such as `.wle.org.ua` allows its subdomains, and needs at least two labels. The vhosts keep the client's `Host` (`ProxyPreserveHost On`). Loopback names aren't allowed: Apache's default vhost would pass a client's `Host: localhost` through. The readiness check (`http://127.0.0.1:9000/health/ready`) is routed for any host (`+ anyhost` in `conf/routes`) and answers only clients on the server itself. A local smoke test sends a jury name:

```bash
curl -s -H 'Host: jury.wle.org.ua' http://127.0.0.1:9000/auth | head
```

`production.conf` requires the variable, and a production start (Mode.Prod) without a name, or with a name it can't use, fails, so a missing variable stops the deployment at the readiness check, with maintenance still on. A host name missing from the list answers 400 at that URL, and `reopen`'s URL check names it: add it to `runtime.env`, restart the app, and run `reopen` again. Add the variable to `runtime.env` before installing a `production.conf` that refers to it; older releases ignore it, so a restore to one keeps working with the new file.

`production.conf` also marks the session, flash, language, theme and gallery-order cookies `Secure`, so browsers send them over HTTPS only, and signing in over plain HTTP no longer works. Every jury HTTP vhost must therefore redirect to HTTPS before this release is deployed. `conf/apache/0002-wlxjury.conf` now redirects `jury.wikilovesearth.org` too (it was the one HTTP vhost that proxied to the app); the production vhosts are generated by `wlxjury-infra`'s templates, which need the same redirect for every name, with or before this release. HSTS (`Strict-Transport-Security`) on the HTTPS vhosts is recommended, also in `wlxjury-infra`. Play needn't see the request as HTTPS for the cookies, and nothing in the app reads `request.secure`, so no `X-Forwarded-Proto` header is needed. If one is ever added, set it on every vhost (`https` on 443, `http` on 80), since Play trusts it from Apache on loopback and a client could otherwise send its own.

`sbt run` and the tests allow Play's default names (`localhost`, `127.0.0.1`, `*.local`) and send cookies without `Secure`.

### Accounts, invitations and the first account

There is no sign-up. An organizer's add (the users page, "Import users") creates an account
without a password and mails an invitation link; following it sets the password and proves the
email. Root's "reset password" mails a link the same way. These mails go through `play.mailer`:
the server's SMTP settings must work, or nobody new can sign in (a failed mail is counted on the
users page and logged on `security.account` with account ids; the invitation can be sent again).
A link points at the host the request that sent it came to (so a WLM juror gets a
`jury.wlm.org.ua` link) only if that host is named exactly in `WLXJURY_ALLOWED_HOSTS` (a suffix
pattern such as `.wle.org.ua` never counts); otherwise, and for mail sent without a request, at
`wlxjury.host`, whose scheme every link uses. A forged `Host` header can therefore never choose
where a password link points. The old address of a changed email gets a short notice (no link),
sent from `wlxjury.mail.from` (`WLXJURY_MAIL_FROM`; unset: the SMTP user if it is an address, else
`no-reply@` the host of `wlxjury.host`), never from the account, whose address is the new one.
Set it to an address the mail server may send from.

The link's token is in the URL path (`/invitation/<token>`, `/email/confirm/<token>`), so the
Apache access log records it. A link is single use and expires (7 days, 24 hours for an email
change), and the app's own log lines mask it (`/invitation/***`), but treat the access logs
as sensitive while links are live, or exclude those paths from them.

A new, empty database has no account. To create the first one (root), set a setup token of at
least 32 characters for the install only, e.g. `WLXJURY_SETUP_TOKEN=$(openssl rand -hex 32)` in
`runtime.env` (read as `wlxjury.setup.token`), restart, open `/setup`, enter the token, an email
and a password, then remove the variable and restart. `/setup` is a 404 whenever an account
exists or no token is set; the token is never logged.

Before deploying the release that carries V77 (S2), run the read-only count of duplicate wiki
accounts in the S2 section of `docs/plans/2026-11-02-security-resolution.md`: a wiki account
that several accounts share no longer signs anyone in (they sign in by email).

### Sessions and the signing key

A signed-in session lasts at most a year from sign-in and a month without a request
(`wlxjury.session.absoluteDays`, `idleDays`; security batch S3). "Keep me signed in" (checked by
default) keeps the cookie across browser restarts; unchecked, it ends with the browser. Every
account has a session version (`users.auth_version`) that each request checks: "Sign out
everywhere" on the profile, a password change or a password link, a confirmed email change and
root's "End sessions" on an account's edit page end every session of that account (the password
change keeps the browser that made it). API tokens are not sessions: a user's own password change
leaves them (the profile's "Revoke all tokens" ends them); an accepted password link or
invitation revokes them. Root's "Reset password" only mails a link: the account's sessions and
tokens end when the link is used. For an account that may be compromised, root also uses "End
sessions" on its edit page and revokes its API tokens (the "API tokens" link there) at once.
Ordinary releases keep everyone signed in: the cookies are signed with `PLAY_HTTP_SECRET_KEY`,
which a release does not change.

Cookies from before S3 (without the sign-in times) are upgraded in place on their first request
for 30 days after the S3 deploy (V80 records the end, `session_upgrade_window`; a restart or a
later deploy does not move it), so nobody is signed out by the release. After that window such a
cookie is signed out (`old_cookie_expired` on `security.auth`): a copy of an old cookie that never
took the upgraded value (a replay) ends with it.

**Rotating the signing key** (`PLAY_HTTP_SECRET_KEY` in `runtime.env`) ends every session at once,
for every account: everyone signs in again. It also invalidates the flash and the CSRF tokens of
open pages (a form sent from a page loaded before the restart shows the "reload the page" page
once). API tokens and mailed links are not affected (they are checked against the database). Do
it when the key may have leaked (a copy of `runtime.env`, a backup or a log that held it), when
someone who could read it leaves, or to end every session without touching accounts (cookies from
before S3 included, which until the upgrade window ends are otherwise upgraded to a new session).
For one account, use "End sessions" instead. To rotate:

1. Generate a new random key of at least 32 bytes on the server, e.g. `openssl rand -base64 48`,
   and write it as the `PLAY_HTTP_SECRET_KEY` value in `runtime.env` (one double-quoted value,
   the file stays root-only `0600`). Don't print it into a shared terminal log, a ticket or a
   document, and don't keep the old value.
2. Restart the app so it reads the new key: either with the next release
   (`sudo wlxjury-release deploy ...`, which stops and starts it) or at once with
   `sudo systemctl restart wlxjury`, at a quiet time (votes in flight are retried by the user).
3. Check `/health/ready` and sign in once.
4. Old copies of the key: every `wlxjury-deploy` backup keeps the `runtime.env` of its time
   (`<backup_root>/<id>/config/runtime.env`, root-only), and its `restore` writes that file back,
   old key included. After a rotation, replace the `PLAY_HTTP_SECRET_KEY` line in those copies with
   the new value (or delete backups that are no longer needed), so that a later restore cannot
   bring back the rotated key. After any restore, check that `runtime.env` holds the current key
   (compare it on the server, e.g. with `sha256sum` of the line, without printing it).

### MariaDB 10.11: V85's gallery index

V85 (`V85__Vote_gallery_index_rate_desc.sql`) stores `vote.rate` descending in the juror
gallery's index. Only MariaDB 10.8 and later can do that. The plan is
`docs/plans/2026-10-19-mariadb-10.11.md`.

**On production's 10.6**, every deploy that applies V85 logs this warning:
`WARN … DB: V85: vote gallery index left ascending (needs MariaDB 10.8+); run its ALTER again after the server move`.
This is expected. V85 changes nothing on 10.6 and is recorded as applied.

**After the move to 10.11 (Ubuntu 24.04)**, by dump and restore or by in-place upgrade, check the
index in the app's database:

1. Connect as the release tool does. The database is `WLXJURY_DB` in
   `/etc/wlxjury-runtime/runtime.env` (`wlxjury` in the example), and `--host` is its
   `WLXJURY_DB_HOST`:

   ```bash
   sudo mariadb --defaults-extra-file=/etc/wlxjury-runtime/db-admin.cnf --host=localhost wlxjury
   ```

2. Run the check:

   ```sql
   SELECT INDEX_NAME, COLLATION FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vote' AND COLUMN_NAME = 'rate'
     AND INDEX_NAME IN ('vote_jury_round_rate_mon_page_idx', 'vote_jury_round_ratedesc_mon_page_idx');
   ```

3. Read the result:

| Result | Meaning | Action |
|---|---|---|
| one row: `vote_jury_round_ratedesc_mon_page_idx`, `D` | done | none |
| one row: `vote_jury_round_rate_mon_page_idx`, `A` | V85 ran on 10.6 | run V85 again (below) |
| two rows: `…ratedesc…` `D` and `…rate_mon…` `A` | part-way: the old index is still there | run V85 again; it drops the old index |
| `…ratedesc…` with `A` (with or without a `…rate_mon…` row) | the descending-named index was built before 10.8, e.g. from a dump of a 10.11 database restored on 10.6 | run V85 again; it rebuilds the index descending |
| no row | neither gallery index exists | stop and investigate; V85 doesn't create one from nothing |

**To run V85 again**, run the script itself in that session. It ships in the release at
`/opt/wlxjury/current/conf/db/migration/default/V85__Vote_gallery_index_rate_desc.sql`
(`release_root` in `deployment.json`, then the release's `conf/`):

```bash
sudo mariadb --defaults-extra-file=/etc/wlxjury-runtime/db-admin.cnf --host=localhost wlxjury \
  < /opt/wlxjury/current/conf/db/migration/default/V85__Vote_gallery_index_rate_desc.sql
```

The script reads the state above and runs only the `ALTER` that state needs, then
`ANALYZE TABLE vote`. Where the index is already done it runs nothing. Running it is therefore
the same as running that `ALTER` by hand. For the common case, only the old index, the `ALTER` is:

```sql
ALTER TABLE vote
  ADD INDEX vote_jury_round_ratedesc_mon_page_idx (jury_id, round_id, rate DESC, monument_id, page_id),
  DROP INDEX vote_jury_round_rate_mon_page_idx,
  ALGORITHM = INPLACE, LOCK = NONE;
ANALYZE TABLE vote;
```

It runs online and took about 11 s on 3M rows; measure it on the rehearsal copy. Then repeat the
check. Don't add a row to `flyway_schema_history`.

**The app reminds you.** On a 10.8+ server where a gallery index still has `rate` ascending,
every start logs:
`vote's gallery index has rate ascending on a server with descending index keys: run V85's ALTER again`.

**After every restore on 10.11, check again.** A backup taken before the re-run (on 10.6, or on
10.11 before V85 ran again) brings back the ascending index when restored, whether with
`wlxjury-release restore` or a manual import. Repeat the check, and run V85 again if needed.

### One app instance

The app assumes that one instance serves a database at a time. Some state lives in the JVM:
`RoundImageCounts` caches each round's image count and is invalidated only in the process that
wrote (another instance's counts stay stale for up to the cache's TTL, 10 minutes); the job
scheduler's per-kind limits and turn-taking, the bcrypt sign-in limits (`bcrypt.*`), local image
download progress and the distribution's cached chunks are per process too. Rounds are locked
in the database (`SELECT … FOR UPDATE`) and jobs hold a lease in the `job` table, but running two
instances against one database was never designed for or tested. The release tools stop the old
service before starting the new one; keep it that way (the blue/green slots, which could run two
at once, are disabled).

### Recovery runbook

Maintenance is never cleared automatically. Read `OPERATION/milestones`, `record` and `operation.log`, then use the section for the last milestone. `previous` and `candidate` in `record` name the releases.

| Milestones | Meaning |
|---|---|
| No operation folder | `deploy` stopped during its checks. Nothing changed, apart from a new folder under `releases`. |
| Missing file, or the first line is not `started` | Unknown. Keep maintenance on, and inspect `current`, the Flyway history and the log before doing anything. Every command refuses this operation. |
| `started`, without `migration-may-start` | The database was never migrated and `current` was never switched. |
| `backup-skipped` | The release had no new migrations, so there is no dump; `restore` only switches the release back. |
| `migration-may-start` | A migration may have run, even if the app never became ready. |
| `ready` | The migration completed; the smoke test decides. |
| `traffic-may-resume` | Writes may have resumed. |
| `restoring` as the last restore milestone | A restore did not finish: the database may be partly imported. |

**Before `migration-may-start`:** if the app was stopped, start it with `sudo systemctl start wlxjury`. Then run `sudo wlxjury-release reopen OPERATION`. It checks readiness, restores automatic restarts and restarts the writers that were running.

**From `migration-may-start` until reopening**, including a failed smoke test after `ready`: never just restart the previous release, since its readiness cannot show a half-applied migration. Prefer a forward fix if one is ready; otherwise:

```bash
sudo wlxjury-release restore OPERATION
# Check the previous release locally (a legacy release answers /auth, not /health/ready). Then:
sudo wlxjury-release reopen OPERATION
```

**After `restoring` without `database-restored`:** fix what stopped the restore (for example a leftover session, or space), then rerun `restore`. `reopen` refuses until a restore finishes.

**After `backup-skipped`**, at any later milestone: `sudo wlxjury-release restore OPERATION`, then `reopen`. No data is lost, so this needs no decision.

**After `traffic-may-resume`:** if only `reopen` failed, fix the reported URL and rerun it. Otherwise, prefer a forward fix. Restoring now loses later votes, comments and jobs, and needs an explicit decision and a plan for reconciling them from the rescue dump. If that is the decision:

```bash
sudo wlxjury-release restore OPERATION --accept-data-loss
```

Rehearse the deployment and each section of this runbook on a production copy before relying on them.

## Release checklist

The one list of the checks made by hand before or after a release (the user, 2026-11-04). The
deploy itself is [Routine deployment after the cutover](#routine-deployment-after-the-cutover)
and the way back the [Recovery runbook](#recovery-runbook); a release's own items (config keys,
migrations, queries for that release) are in the roadmap's
["Before any production deploy"](plans/2026-11-02-roadmap.md#before-any-production-deploy-of-followups).
A change that adds, removes or automates a manual check updates this section
([docs/README.md](README.md) §3).

### Before the release

| Check | What to do | Why by hand | When |
|---|---|---|---|
| The release's own items | The roadmap's list for this release (release 1, release 2): config keys, HTTPS, loggers, the pre-release queries (`SELECT @@log_bin, @@binlog_format`, the password and role counts), each with its detail there; [operations](operations/practices.md) §4.2 | They read production's config and data, which no test has | Every release, as the list for it says |
| The `auto` colour mode, looked at | `target/ui-venv/Scripts/python scripts/ui-shots.py shoot --label <release> --mode auto --scheme light --start-server`, the same with `--scheme dark`; look through the shots, or `diff` them against the last release's ([UI](ui/practices.md) §5.1) | Looked at by a person: the browser tests check the mode of every frame, not how a page looks in it | Every release; necessarily when a template, `theme.css` or `main.css` changed |
| Back after a vote in Safari | In Safari (macOS), as a juror: open a binary round's large view, vote with Space, press Back: the file shows the new vote, not the one before; the same with a digit in a rated round | Playwright's WebKit (Windows and Linux builds) brings the page back fresh even without `no-store`, so the browser tests can't show the regression there ([testing](testing/practices.md) §10.3) | Every release; necessarily when the large view, its keys or `Secured.noStore` changed |
| Category suggestions against live Commons | As an organizer, in a category field (the contest images page, the round form's image filtering): type a few letters of a real category; Commons' categories are suggested and one can be picked | Needs live Commons: the tests stub every Commons call and refuse its hosts | Every release; necessarily when `category-suggest.js`, Tom Select or the suggestions' endpoint changed |
| Signing in with Google or Wikimedia | On a staging host first: sign in with each provider and link an account ([integrations](integrations/practices.md) §3.3) | Real providers, which the tests fake | Before a production host is listed for OAuth, and when the providers' config or the sign-in code changed |

### After the deploy

| Check | What to do | Why by hand | When |
|---|---|---|---|
| Smoke test before reopening | The release's pages on `127.0.0.1:9000` with a jury host name, as [Routine deployment after the cutover](#routine-deployment-after-the-cutover) says, then `reopen` | The production server, its config and data | Every deploy |
| Back out if needed | The [Recovery runbook](#recovery-runbook), by the last milestone | A decision | When the smoke test fails |
| What was pruned, and monit | Read `reopen`'s `Keep` and `Deleted` lines ([Retention](#retention-old-releases-and-dumps)); `df -h /`; `stat -c '%a %U:%G' /var/www/jury-images` shows `755 wlxjury:wlxjury` ([the image cache folder](#the-image-cache-folder)); `/var/log/monit.log` has no `wlxjury` error since the reopen ([Monitoring with monit](#monitoring-with-monit)) | The server's disk and monit, which no test sees | Every deploy |

### Covered by the browser tests: not repeated by hand

On every PR, in one current build of each engine (Chromium 156, Firefox 157, WebKit 27.2;
[testing](testing/practices.md) §10), `browser.yml`:

- the theme flash: `auto` in both schemes, every frame from the first in the right mode
  (`theme.spec.js`), in all three engines, Firefox included; and the two theme switches;
- Back after a vote shows the stored vote, in Firefox (`voting.spec.js`); voting by key, button,
  thumb and star in both rounds (`voting.spec.js`);
- zero CSP violations, console errors and failed requests on every page visited, and the
  production CSP sent (`guards.spec.js`, every spec);
- signing in and out with the CSRF token (`sign-in.spec.js`); a juror's and an organizer's 403
  pages (`access.spec.js`); the organizer and admin pages, and the round form's edit-then-cancel
  (`organizer.spec.js`);
- the large view's side sections restored before its scripts run (`sections.spec.js`);
- one `h1` per page, and an accessibility scan in Chromium (`pages.spec.js`).

### Known gaps

- **Back after a vote in Chrome shows the old vote** (U23, [UI](ui/practices.md) §9): Chromium
  brings the `no-store` large view back from its back/forward cache; the browser tests assert
  that stale page there, and fail once it stops. Nothing to check by hand until it is fixed, but once, in a real Chrome, whether
  Chrome does it too (U23 says so).
- **The previous majors and the floor (Chrome 109, Firefox 115) are linted, not run**
  ([UI](ui/practices.md) §6.1, §6.1a): the compatibility lint (`.browserslistrc`, `ci.yml`) sees
  our scripts' APIs and syntax and the CSS features caniuse lists, not rendering or behaviour. A
  release that changes how the core pages work (sign-in, voting) can be tried by hand in
  Firefox 115 ESR or Chrome 109 where one is at hand; it is not required.
- **The side sections' first frame** (U18) and **Firefox's page error when leaving a gallery**
  (U22) are open triage items, not checks.
- **Phones:** not supported yet ([UI](ui/practices.md) §6.2).

## Local verification

```bash
docker run --rm --network none --mount type=bind,source="$PWD",target=/workspace,readonly --workdir /workspace/scripts/deployment python:3.8-slim python -B -m unittest -v test_deployment
sbt "testOnly deployment.* controllers.ReadinessControllerSpec services.jobs.JobSchedulerSpec db.ConvertToInnoDbMigrationSpec"
sbt dist
docker build -f scripts/deployment/Dockerfile.test -t wlxjury-deployment-test:local scripts/deployment
docker run --rm --network none --mount type=bind,source="$PWD",target=/workspace,readonly --workdir /workspace/scripts/deployment wlxjury-deployment-test:local -B -m unittest -v test_database_restore test_release_script
docker run --rm --mount type=bind,source="$PWD",target=/s,readonly koalaman/shellcheck:stable -S style /s/scripts/deployment/wlxjury-release.sh /s/conf/monit/wlxjury-check
```

Python 3.8 is the oldest supported version. The restore image is self-contained: Ubuntu 22.04's MariaDB 10.6 (`--build-arg UBUNTU=24.04` for the new server's 10.11), OpenJDK 17, Python 3, `curl`, `unzip` and `monit`. Its packaged-release test runs the ZIP from `sbt dist`, checks that it carries no server setup files and that readiness refuses a forwarded request. `test_release_script` runs `wlxjury-release.sh` unmodified against a real MariaDB, with stand-ins only for systemd (a `systemctl` stub that starts and stops the selected release's launcher) and Apache (a fake proxy that honors the maintenance flag). It covers:
- deploying and reopening, including a timer that starts its job during the maintenance checks;
- a release without migrations: no dump by default (or one with `--backup`), and a `restore` that only switches the release back, keeping later writes, but refuses once a later migration ran;
- refusals that leave everything unchanged, including an unreachable database endpoint;
- a vhost without the maintenance page, and a lost milestones file;
- rerunning `reopen` after a routing failure;
- `restore` after an interrupted migration, refusing the previous release until the restore finishes;
- a damaged backup, an import that fails part-way, and its rerun; an import that fails just after dropping the database, and its retry without a new rescue dump;
- refusing a backup of another database, too little free space for the reserve and the rescue dump, and a `db-admin.cnf` that names a remote host while the app uses the local server;
- `restore` after reopening: the data-loss decision, refusing while another session uses the database, ending a leftover app session that holds a table lock before the rescue dump, and keeping every rescue dump across retries;
- the image cache folder refused before anything changes when group- or world-writable, owned by another account, not a folder, or a symlink to such a folder; a missing one accepted;
- pruning: nothing deleted by a failed `reopen` and the prune after its rerun; exit 3 when only that prune fails; fewer releases than the keep count; the current release kept however old; the dumps of the newest operations, of young ones and of the operation that deployed the current release, each with its previous release, including two restored deployments after a completed one; releases and dumps of an open operation and of an open cutover record; missing or damaged milestones counted as open; stopping, deleting nothing, on an open operation it can't read, one naming a path outside `releases`, a corrupt cutover record or a symlinked operation folder; paths compared resolved; decimal settings with leading zeros; a dump whose age can't be read kept; symlinks and non-release folders skipped; `--dry-run` listing exactly what the real run deletes; refusing during maintenance, under a held lock and with bad settings; unfinished unpacks removed; `restore` refusing a pruned dump before the data-loss decision.

`MonitCheckTest` (in the same file) runs `conf/monit/wlxjury-check` with a test `deployment.json`, a stub `systemctl` and a fake readiness endpoint (ready, 503, not active, quiet in maintenance, a stale flag, nothing listening, an unreadable config), checks that `conf/monit/wlxjury` only alerts, and runs `monit -t` on it in the image.

Tests own a MariaDB 10.6 process and synthetic mixed-engine data. They do not certify real production backups, disk capacity, Apache coverage or outage time; those remain rollout/rehearsal gates.
