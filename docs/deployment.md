# Deployment and recovery

Use `scripts/deployment/wlxjury-deploy.py` on Linux as root, with Python 3.8+, systemd, Apache and MariaDB client tools, for the one-time setup and the cutover release. After the cutover, routine releases use the smaller [`wlxjury-release`](#routine-deployment-after-the-cutover) script with a manual recovery runbook; see `docs/plans/2026-10-13-deployment-tooling-alternatives.md` for why. The old blue/green scripts are disabled and their slot files removed.

Run every `deploy`, `restore` and `reopen` inside `tmux` (or `screen`). The tool ignores SSH hangups and logs each step to `/var/backups/wlxjury/operations.log`, so a dropped connection does not interrupt a backup, migration or restore. You still need the terminal to follow it and to act on the result.

The selected baseline is the latest benchmarked background-distribution release, including **V55.1 and V60**. The changes from `bcdf9847` are present as `32a1253`; its migration, test and fixture are identical. The measured MariaDB recommendation remains `bench/cnf/recommended-60-wlxjury.cnf`, with a **256 MiB** buffer pool. These changes have been implemented locally; production setup and real-data rehearsal are separate rollout steps.

## One-time setup on the server

Run from a reviewed checkout. `/etc/wlxjury` points into the package, so runtime settings go in a new real directory. No credentials belong in the checkout, ZIP, command-line arguments or console output.

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
sbt -Dwlxjury.buildId=0.13-reviewed dist
sha256sum target/universal/wlxjury-0.13.zip
```

Use a reviewed build name and the final ZIP hash. A requested build ID is refused if `app`, `conf`, `public`, `project` or `build.sbt` has uncommitted **or untracked** changes (untracked Twirl templates are compiled too), and the short commit is appended unless the name already contains it, e.g. `0.13-reviewed-dbedca565807`. Without `-Dwlxjury.buildId` the ID is version and commit, with `-dirty` for any such change. The rules are in `project/BuildIdentity.scala`, tested by `deployment.BuildIdentitySpec`. Server setup files (`conf/wlxjury`, `conf/apache`, `conf/systemd`) are installed from the checkout and are not in the ZIP. `wlxjury-build.json` is both at the release root and embedded in the app; it contains build, version and the latest numerically ordered migration, including 55.1. Artifact hashes distinguish packages even when version/build names repeat.

Rehearse the **exact ZIP** against a consistent isolated production copy on MariaDB 10.6, including Flyway history. Production MyISAM needs locking or stopped writers; `--single-transaction` alone is insufficient. Disable real email, isolate access and staging cache, and prevent copied jobs from contacting production services. Measure migration, startup, backup, restore and peak space; review V58 orphans and V60 job recovery. Test restoring the backup and old release too.

From V53, this release applies V54, V55, V55.1 and V56–V60. V55.1 rebuilds tables and changes collation; earlier production-copy measurements were about 2.5 minutes with considerable disk growth before other migration work. Set an outage/timeout budget from rehearsal. Read `docs/plans/2026-10-07-prod-like-benchmark.md` for measured results and limits. Apply the reviewed 256 MiB MariaDB config in maintenance after conversion, restart/recheck MariaDB and the app before reopening. The tool does not change DB server settings used by other hosted applications.

## Cutover deployment with wlxjury-deploy

Use this for the cutover release. Upload the reviewed ZIP:

```bash
sudo wlxjury-deploy prepare /srv/releases/wlxjury-0.13.zip --sha256 FULL_LOWERCASE_SHA256
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

Once the cutover is complete, deploy releases with `wlxjury-release`. It automates only the normal path; failures are recovered by hand with the runbook below. It reads the same `deployment.json`, `runtime.env` and `db-admin.cnf` as `wlxjury-deploy` and takes the same lock (`/opt/wlxjury/deployment.lock`), so the two never run at once. Do not use `wlxjury-deploy deploy` after switching: it cannot verify releases it did not prepare. Keep `wlxjury-deploy` only to recover from its own earlier records until they are retired.

Install it once, with `curl` and `unzip`:

```bash
sudo install -o root -g root -m 0755 scripts/deployment/wlxjury-release.sh /usr/local/sbin/wlxjury-release
```

Run it inside `tmux`, with the reviewed ZIP from `sbt dist` and its full SHA-256:

```bash
sudo wlxjury-release deploy /srv/releases/wlxjury-0.14.zip FULL_LOWERCASE_SHA256
# Smoke-test http://127.0.0.1:9000/ locally, as for the cutover. Then:
sudo wlxjury-release reopen OPERATION
```

Before changing anything, `deploy` takes the lock and checks:
- the ZIP hash, with no symlinks and one top-level folder;
- the manifest;
- that the release is not older than the database and the database has no failed migration;
- that `db-admin.cnf` reaches the database in `runtime.env`;
- that no database event is enabled, and free space.

It unpacks the release into a new root-owned folder under `/opt/wlxjury/releases`. Then it:
1. creates the operation folder `/var/backups/wlxjury/UTC_TIMESTAMP-BUILD`, with `record`, `milestones` and `operation.log`;
2. enables maintenance and requires HTTP 503 with the maintenance page at every configured URL;
3. turns off automatic restarts, and stops the configured writer units and the app, checking each has stopped;
4. rechecks database events;
5. dumps the database with `--databases --add-drop-database --lock-tables`, then checks the dump and saves its checksum and a config snapshot;
6. flushes them and rechecks free space;
7. switches `/opt/wlxjury/current`, starts the app, and waits until `/health/ready` reports the new build.

`reopen` requires:
- intact milestones;
- that `current` is the operation's candidate release, or its previous release under the rules below;
- the release to be ready.

It then records `traffic-may-resume`, restores automatic restarts, removes maintenance, checks every URL reaches the app, and restarts the writer units that were running. If a URL fails, fix routing and run `reopen` again.

### Recovery runbook

Maintenance is never cleared automatically. Read `OPERATION/milestones`, `record` and `operation.log`, then use the section for the last milestone. `previous` and `candidate` in `record` name the releases.

| Milestones | Meaning |
|---|---|
| No operation folder | `deploy` stopped during its checks. Nothing changed, apart from a new folder under `releases`. |
| Missing file, or the first line is not `started` | Unknown. Keep maintenance on, and inspect `current`, the Flyway history and the log before doing anything. |
| `started`, without `migration-may-start` | The database was never migrated and `current` was never switched. |
| `migration-may-start` | A migration may have run, even if the app never became ready. |
| `ready` | The migration completed; the smoke test decides. |
| `traffic-may-resume` | Writes may have resumed. |

**Before `migration-may-start`:** if the app was stopped, start it with `sudo systemctl start wlxjury`. Then run `wlxjury-release reopen OPERATION`. It checks readiness, restores automatic restarts and restarts the paused writers.

**From `migration-may-start` until reopening** (including a failed smoke test after `ready`): never just restart the previous release, since its readiness cannot show a half-applied migration. Unless a forward fix is chosen instead, restore:

```bash
# As root (sudo -i), inside tmux. The operation folder is readable only by root.
cd /var/backups/wlxjury/OPERATION
ADMIN=--defaults-extra-file=/etc/wlxjury-runtime/db-admin.cnf
systemctl stop wlxjury
mariadb "$ADMIN" -e 'SHOW PROCESSLIST'      # KILL leftover app sessions; wait until a killed ALTER is gone; nobody else may use the database
sha256sum -c database.sql.gz.sha256
zcat database.sql.gz | mariadb "$ADMIN"     # Drops and recreates the database with its own charset and collation
# Only if this release changed config: tar -C / -xzf config.tgz
ln -sfn PREVIOUS /opt/wlxjury/current.next && mv -T /opt/wlxjury/current.next /opt/wlxjury/current
echo database-restored >> milestones && sync milestones
systemctl start wlxjury                     # Check locally; a legacy release answers /auth instead of /health/ready
wlxjury-release reopen OPERATION
```

`reopen` refuses the previous release after `migration-may-start` until `database-restored` is recorded.

**After `traffic-may-resume`:** if only `reopen` failed, fix the reported URL and rerun it. Otherwise, prefer a forward fix. Restoring now loses later votes, comments and jobs, and needs an explicit decision and a plan for reconciling them. If that is the decision:
1. Enable maintenance (`sudo touch /etc/wlxjury-runtime/maintenance`), stop the writer units and the app.
2. Take a fresh dump into the operation folder (`mariadb-dump "$ADMIN" --databases DATABASE --add-drop-database --lock-tables --quick --routines --events --triggers | gzip > before-restore.sql.gz`).
3. Follow the restore above.

Rehearse the deployment and each section of this runbook on a production copy before relying on them.

## Local verification

```bash
docker run --rm --network none --mount type=bind,source="$PWD",target=/workspace,readonly --workdir /workspace/scripts/deployment python:3.8-slim python -B -m unittest -v test_deployment
sbt "testOnly deployment.* controllers.ReadinessControllerSpec services.jobs.JobSchedulerSpec db.ConvertToInnoDbMigrationSpec"
sbt dist
docker build -f scripts/deployment/Dockerfile.test -t wlxjury-deployment-test:local scripts/deployment
docker run --rm --network none --mount type=bind,source="$PWD",target=/workspace,readonly --workdir /workspace/scripts/deployment wlxjury-deployment-test:local -B -m unittest -v test_database_restore test_release_script
docker run --rm --mount type=bind,source="$PWD/scripts/deployment",target=/s,readonly koalaman/shellcheck:stable -S style /s/wlxjury-release.sh
```

Python 3.8 is the oldest supported version. The restore image is self-contained: Ubuntu 22.04's MariaDB 10.6, OpenJDK 17, Python 3, `curl` and `unzip`. Its packaged-release test runs the ZIP from `sbt dist`, checks that it carries no server setup files and that readiness refuses a forwarded request. `test_release_script` runs `wlxjury-release.sh` unmodified against a real MariaDB, with stand-ins only for systemd (a `systemctl` stub that starts and stops the selected release's launcher) and Apache (a fake proxy that honors the maintenance flag). It covers deploying and reopening; the refusals that leave everything unchanged; a vhost without the maintenance page; rerunning `reopen` after a routing failure; and the runbook restore after an interrupted migration. That includes refusing the previous release until `database-restored` is recorded. Tests own a MariaDB 10.6 process and synthetic mixed-engine data. They do not certify real production backups, disk capacity, Apache coverage or outage time; those remain rollout/rehearsal gates.
