# Deployment and recovery

Use `scripts/deployment/wlxjury-deploy.py` on Linux as root, with Python 3.8+, systemd, Apache and MariaDB client tools. The old blue/green scripts are disabled.

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

Install `conf/apache/wlxjury-maintenance.conf` as a readable root-owned Apache snippet. Include it **inside every jury HTTP and HTTPS VirtualHost, before redirect rules**; preserve certificates and unrelated websites. Fix the stale jury proxy to 9001 so it uses 9000. Verify all aliases, run `sudo apachectl configtest` and reload Apache once. The flag is evaluated on every request; later deployments need no Apache edits or reload.

Adopt a sanitized recovery snapshot without restarting the running original:

```bash
sudo wlxjury-deploy adopt-legacy /usr/share/wlxjury --build legacy-0.12-v53
sudo install -D -o root -g root -m 0644 conf/systemd/wlxjury.service.d/deployment.conf /etc/systemd/system/wlxjury.service.d/deployment.conf
sudo systemctl daemon-reload
```

Adoption checks external and JAR migration catalogs and refuses installed files with migrations beyond the live DB version: retrieve the actual old release if package files were updated without restarting. It then copies the launcher/libraries/config, removes literal credentials from the copy, excludes package-conflict/backup config copies, records its schema/hashes and selects it via `/opt/wlxjury/current`. It leaves the original installation untouched. Logs live at `/var/log/wlxjury`, PID at `/run/wlxjury`, and the launcher binds to loopback. The drop-in preserves the installed service/user and allows six minutes for graceful stop. Stopping the service also stops V60 job workers; committed job state remains in the DB for recovery.

First validate external configuration with the adopted release itself, without a schema upgrade:

```bash
sudo wlxjury-deploy deploy /opt/wlxjury/releases/legacy-0.12-v53 --writers-reviewed
# Check login/normal pages locally at port 9000 while maintenance stays enabled.
sudo wlxjury-deploy reopen RECORD_ID
sudo wlxjury-deploy scrub-legacy RECORD_ID --legacy-config /etc/wlxjury/application.conf
```

Use the printed record ID. `scrub-legacy` requires an accepted rollout and verifies that the live JVM uses external config. It retains the original only in the protected recovery record and replaces old literal credentials with environment references. Review other historical copies/backups for exposed credentials under your retention policy. Keep the signing key during the move; rotation is separate. Keep the installed package for initial recovery, but do not run package upgrades that bypass the new procedure.

## Build and rehearse

```bash
sbt test
sbt -Dwlxjury.buildId=REVIEWED_COMMIT dist
sha256sum target/universal/wlxjury-0.13.zip
```

Use a unique reviewed build ID and the final ZIP hash. The default development ID includes version/commit and a tracked-source dirty marker. `wlxjury-build.json` is both at the release root and embedded in the app; it contains build, version and the latest numerically ordered migration, including 55.1. Artifact hashes distinguish packages even when version/build names repeat.

Rehearse the **exact ZIP** against a consistent isolated production copy on MariaDB 10.6, including Flyway history. Production MyISAM needs locking or stopped writers; `--single-transaction` alone is insufficient. Disable real email, isolate access and staging cache, and prevent copied jobs from contacting production services. Measure migration, startup, backup, restore and peak space; review V58 orphans and V60 job recovery. Test restoring the backup and old release too.

From V53, this release applies V54, V55, V55.1 and V56–V60. V55.1 rebuilds tables and changes collation; earlier production-copy measurements were about 2.5 minutes with considerable disk growth before other migration work. Set an outage/timeout budget from rehearsal. Read `docs/plans/2026-10-07-prod-like-benchmark.md` for measured results and limits. Apply the reviewed 256 MiB MariaDB config in maintenance after conversion, restart/recheck MariaDB and the app before reopening. The tool does not change DB server settings used by other hosted applications.

## Routine deployment

Upload the reviewed ZIP:

```bash
sudo wlxjury-deploy prepare /srv/releases/wlxjury-0.13.zip --sha256 FULL_LOWERCASE_SHA256
sudo wlxjury-deploy deploy /opt/wlxjury/releases/PRINTED_RELEASE_NAME --writers-reviewed
```

`prepare` verifies the ZIP hash, rejects traversal/symlinks, strips its single archive root and creates a service-readable immutable release with file hashes. It never overwrites the active release. `--writers-reviewed` acknowledges that external writers/DDL jobs were identified and coordinated. Stay outside the Kyiv midnight statistics job or explicitly pause that writer too; a proxy flag alone does not drain in-flight work. Review import interruption/retry behavior.

Deployment locks operations; checks current schema and capacity; creates a private recovery/config record; enables and verifies HTTP **503 at every configured HTTP/HTTPS URL**; stops relevant units and the app; takes a locking mixed-engine backup with history; fsyncs/checksums it; rechecks space; atomically switches release; starts/migrates; and waits for strict HTTP 200 with expected build/schema. `/health/ready` uses the actual app pool and returns 503 for connection failure, failed history or old/missing schema, without exposing credentials or driver details.

**Traffic remains blocked when deployment returns.** Smoke-test localhost port 9000: login, gallery/images, results, agreed reversible votes/comments/management, and job resumption. Inspect logs, table engines/collations/constraints and latest successful Flyway installed rank. Then:

```bash
sudo wlxjury-deploy reopen RECORD_ID
```

`reopen` checks readiness/maintenance again, durably records that writes may resume, removes maintenance and resumes paused units. Verify public HTTPS and monitor errors/memory/jobs. Keep backups/old release. Routine deployments modify **no credential or site-config files** and need no application-config merge. Recovery records/config snapshots live in root-only `/var/backups/wlxjury/UTC_TIMESTAMP-ID/`.

## Recovery

Errors/interrupts never remove maintenance automatically. Inspect the printed record and phase. Before migration starts, including a failed backup:

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

Restore verifies checksum and gzip integrity **before DB deletion**, checks DB identity, stops writers, recreates the target, restores the entire dump/history/config and old release, then verifies readiness. The migration-started phase is saved **before** candidate startup: a failed start may already have committed DDL. The adopted old binary uses a clearly marked `/auth` + schema check until replaced by releases with readiness.

After traffic resumes, prefer a forward fix or a separately rehearsed compatible-code deployment preserving current data. If snapshot restoration is the agreed decision:

```bash
sudo wlxjury-deploy restore RECORD_ID --accept-data-loss
```

This explicitly accepts losing later writes and first saves a fresh `before-restore-*` rescue dump. Maintenance stays enabled for verification. Plan reconciliation of later votes/comments/jobs; the tool does not replay them across incompatible schemas. It never automatically repairs/cleans/baselines Flyway or deletes snapshots.

An interrupted reopening conservatively marks possible writes even if maintenance still exists; restoration then requires the explicit decision. `reopen` can complete an interrupted opening after rechecking the selected release. Unexpected `current.next` or `*.tmp` leftovers require inspection of the durable record/contents before removing only that specific leftover and retrying. Resolve space, routing, credential or host failures before retrying.

## Local verification

```bash
docker run --rm --network none --mount type=bind,source="$PWD",target=/workspace,readonly --workdir /workspace/scripts/deployment python:3.10-slim python -B -m unittest -v test_deployment
docker build -f scripts/deployment/Dockerfile.test -t wlxjury-deployment-test:local scripts/deployment
docker run --rm --network none --mount type=bind,source="$PWD",target=/workspace,readonly --workdir /workspace/scripts/deployment wlxjury-deployment-test:local -B -m unittest -v test_database_restore
sbt "testOnly deployment.* controllers.ReadinessControllerSpec services.jobs.JobSchedulerSpec db.ConvertToInnoDbMigrationSpec"
sbt dist
```

The restore image reuses `wlxjury-bench-server:latest` from the benchmark setup and adds Python. Tests own a MariaDB 10.6 process and synthetic mixed-engine data. They do not certify real production backups, disk capacity, Apache coverage or outage time; those remain rollout/rehearsal gates.
