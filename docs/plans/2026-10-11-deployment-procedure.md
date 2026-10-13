# Deployment investigation and implementation plan

Date: 2026-10-11. Implementation added 2026-10-13 on `codex/deployment-procedure`, based on the selected V60 benchmark branch. Production inspection was read-only; production rollout and real-data rehearsal remain pending. The current operational procedure is [docs/deployment.md](../deployment.md).

## Recommendation

Keep the existing single `wlxjury.service`, use immutable versioned ZIP releases, and deploy during a maintenance window. Rehearse the exact release and migrations against an isolated database first. Move credentials and the small set of production overrides outside the release directory once, so later deployments require no configuration merges.

The current blue/green scripts need substantial corrections before operational use. They provide neither zero downtime nor a generally safe post-migration rollback. A single-service procedure is easier to operate on this server and avoids keeping two application processes and two full databases on its nearly full filesystem. Blue/green can be added later if reducing startup downtime becomes a requirement.

## Verified production state

Inspected over `ssh ilya@wlm.org.ua`, using selected service properties, configuration reads with credential values withheld, database metadata SELECTs, and localhost HTTP requests. No service, config, data, or routing was changed.

| Item | Observed state |
|---|---|
| Service | `wlxjury.service`, active, user `wlxjury`, `Restart=always` |
| Unit | `/lib/systemd/system/wlxjury.service` |
| Application | Debian package version `0.12`, `/usr/share/wlxjury` |
| Configuration | `/etc/wlxjury` **symlinks to** `/usr/share/wlxjury/conf`; `application.conf.dpkg-dist` exists |
| Environment file | `/etc/default/wlxjury`; currently contains JVM settings, no application credential variables |
| Secrets | Play signing secret, Commons account/password, database username/password and URL are literal values in the deployed `application.conf` |
| File permissions | Deployed `application.conf` is mode `0644`, owned by `wlxjury`; `/etc/default/wlxjury` is also `0644` |
| JVM | `-Xmx1g`, direct-memory cap 128 MB, G1 periodic GC 60 s, native-memory tracking and exit on OOM |
| Application libraries | Play 3.0.7; Flyway core/MySQL 9.16.3; flyway-play 8.0.1; MariaDB JDBC 3.5.3 |
| Database | `wlxjury`, localhost, MariaDB 10.6.23; latest successful migration V53 |
| Storage engines | All observed application tables are still MyISAM; Flyway history is InnoDB |
| Database size | Approximately 1.3 GB of table data/indexes; selection 3.32 million rows, images 994 thousand rows |
| Free disk | 5.5 GB available on the shared root filesystem, 91% used |
| Routing | Live jury HTTP/HTTPS Apache vhosts mostly proxy to port 9000; no maintenance directives were found in those vhosts |
| Routing exception | One HTTP vhost for `wlxjury.wikimedia.in.ua` in the SSL config proxies to 9001; no listener was present there |
| Listeners | Application listens on `*:9000`; no 9001 listener, no blue/green services or install directories found |
| HTTP checks | `/` returns 303; `/auth` returns 200 |
| Backups | `wmua-backup-db@wlxjury.timer` is scheduled; last service result was success. Restore usability and snapshot consistency have **not** been verified |

Production has `db.default.migration.auto=true` and `initOnMigrate=true`. Its public-link setting still uses `http://jury.wikilovesearth.org.ua/`. These are configuration differences to resolve deliberately, rather than merging the whole old config into a new release. Old pool keys also differ from the current repository's HikariCP settings; preserve the current code's effective defaults instead of copying obsolete tuning.

## Findings in the blue/green scripts

The checked-in implementation uses **separate databases**, as shown by `conf/wlxjury/{blue,green,slots}.env.example`. The shared-database concern would apply if both slot files were configured with the same DB. The scripts currently do not check that invariant.

| Finding | Effect / required correction |
|---|---|
| ZIP extraction keeps the versioned parent directory | `mv "$EXTRACT_TMP"/*/ "$INSTALL_DIR/"` normally creates `/opt/wlxjury-green/wlxjury-<version>/...`; the subsequent `/opt/wlxjury-green/bin/wlxjury` lookup fails. Validate one archive root and install its contents at the expected path. |
| `--single-transaction` against live MyISAM | Canary seeding is not a consistent snapshot while writes continue. Use an appropriate locking snapshot or stop writers; do not assume the flag protects MyISAM. |
| Promotion leaves active running | Maintenance blocks new proxied requests only. In-flight requests, imports, direct-port access and other writers can change data after the snapshot. Drain/stop the service and other writers before the final dump. |
| Promotion restores while canary is running | Stop the destination service **before** dropping/restoring its DB, rather than restarting only afterward. |
| DB host omitted from CLI | Both scripts use local-client defaults, ignoring `WLXJURY_DB_HOST`; this happens to match production today. Read an explicit client configuration for the intended server and privileges. |
| Application credentials also used for DB administration | Canary credentials are assumed to read the source and create/drop the destination. Separate application access from deployment/backup access. |
| No mutual exclusion or state validation | Concurrent deployment/promotion can delete an install or database in use. Lock operations and validate slot, port, DB and path consistency before mutation. |
| Temporary SQL files and command-line passwords | Dumps have predictable `/tmp` paths and no explicit restrictive umask; passwords are passed as `-p...`. Use private backups/temp files and a protected client option file or local administrative socket authentication. |
| HTTP-only Apache template | `conf/apache/0002-wlxjury.conf` requires separate edits to generated HTTPS vhosts. Verify maintenance on every live jury hostname and both protocols. |
| Weak health check | `curl -sf /` accepts 303; it does not verify the documented HTTP 200, DB access or expected release. Use explicit readiness and release identification. |
| Fixed 120-second wait | V55.1 alone has a documented 2.5-minute conversion on a production copy, before dump/restore and other migrations. Derive a configurable timeout and outage budget from rehearsal. |
| Nonatomic cutover state | Two files are rewritten, then Apache reloaded without a config test; the ERR trap is disabled before this part. A failure can leave maintenance enabled and state files inconsistent with live routing. Save prior state, validate and recover by phase. |
| “Run promote again to rollback” | It overwrites the old database with the new schema and data. Old code may fail validation or SQL/schema assumptions; it is not a schema rollback. |
| Old backup remains exposed as canary | After promotion the former active DB can receive test writes. A retained recovery database must stay stopped and isolated. |

The April design documents are historical: the original binlog/instant-swap design was replaced by maintenance-window dump/restore. Neither description should be used as the current operational runbook.

MariaDB documents that `--single-transaction` provides consistency for transactional tables and not MyISAM. [MariaDB dump documentation](https://mariadb.com/docs/server/clients-and-utilities/backup-restore-and-import-clients/mariadb-dump).

## Configuration without manual merges

Use a new real directory, `/etc/wlxjury-runtime`, because `/etc/wlxjury` already belongs to the package layout. Do not place runtime secrets behind that existing symlink.

| Location | Ownership / contents |
|---|---|
| Release `conf/application.conf` | Version-controlled application defaults and environment references; no actual credentials; installed unchanged |
| `/etc/wlxjury-runtime/production.conf` | Root-owned, service-readable; includes the release's classpath config and only necessary production overrides |
| `/etc/wlxjury-runtime/runtime.env` | `root:root`, mode `0600`; systemd reads required credential variables |
| `/etc/default/wlxjury` | Existing JVM/runtime tuning, retained initially; no secrets needed here |
| `/etc/systemd/system/wlxjury.service.d/deployment.conf` | Root-owned drop-in selecting the external config, runtime environment and current release launcher |
| Deployment DB authentication | Local root socket authentication if available, otherwise a root-only client option file with a separate administrative account |

The runtime directory can be `root:wlxjury` mode `0750`; nonsecret config `0640`, with group `wlxjury`. The application user must not be able to change its service configuration or release executables. Systemd can read the root-only environment file before starting the process as `wlxjury`.

Required variables: `WLXJURY_DB_HOST`, `WLXJURY_DB`, `WLXJURY_DB_USER`, `WLXJURY_DB_PASSWORD`, and `PLAY_HTTP_SECRET_KEY`. Optional Commons credentials retain the repository's `COMMONS_USER` / `COMMONS_PASSWORD` names. Do not set optional credentials to empty strings to represent absence.

The proposed production overlay must:

1. Include the release's **classpath** `application.conf` explicitly, so it follows new application defaults instead of including another external copy by accident.
2. Require `play.http.secret.key = ${PLAY_HTTP_SECRET_KEY}` so production cannot silently use the repository's `changeme` development value.
3. Map the DB URL/user/password and optional Commons settings to environment variables. DB mappings already exist in the current repository; include explicit mappings during the transition because the installed 0.12 config has literal values.
4. Set production automatic migration explicitly after testing the pinned Flyway integration. Existing history must be preserved; deployment must not baseline, repair, clean, or suppress validation automatically.
5. Preserve the image-cache path and decide the canonical HTTPS public URL. Carry over a custom greeting or mail settings only if the comparison finds intentional overrides.
6. Keep site overrides small; leave pool sizes, dispatchers and application tuning with the release unless an operator has a specific reason to override them.

Start with `-Dconfig.file=/etc/wlxjury-runtime/production.conf`; use a systemd drop-in rather than editing the package-owned `/lib/systemd/system` unit. Retain `/etc/default/wlxjury` first, then load `runtime.env`. EnvironmentFile syntax is not a shell script: deployment code should not `source` it; test quoting with credential values containing special characters.

Play supports external production configuration with an include of application defaults. [Play production configuration](https://www.playframework.com/documentation/3.0.x/ProductionConfiguration), [Play application secret](https://www.playframework.com/documentation/3.0.x/ApplicationSecret).

One-time migration: capture settings directly on the server into the protected runtime files, verify equivalent configuration with the existing code/schema, then remove literal credentials from the deployed application config. Locate old config copies containing the same credentials and protect or retire them under an explicit retention policy. Preserve the signing key during this move to avoid an unrelated session reset; credential rotation can then be done as a separate operation. Never copy production secrets into Git or build artifacts.

## Release and persistent state layout

Use `sbt dist` and identify each artifact by commit and SHA-256, since `build.sbt` currently has the static version `0.13`.

```text
/opt/wlxjury/releases/<version>-<commit>/  # immutable release contents
/opt/wlxjury/current                     # symlink selecting the release
/etc/wlxjury-runtime/                    # persistent configuration
/var/log/wlxjury/                        # persistent logs
/run/wlxjury/                            # PID and runtime files
/var/backups/wlxjury/                    # protected deployment records/backups
/var/www/jury-images/                    # existing persistent image cache
```

Keep the current package installation intact through the first rollout as a recovery source. Switching to ZIP-managed releases is a deliberate packaging transition: the new deployment procedure must not run `dpkg -i` concurrently or let a package restart bypass its maintenance/backup steps. Decide package retirement later.

Use `RuntimeDirectory=wlxjury`, a PID path under `/run/wlxjury`, and persistent log destinations rather than writing inside immutable releases. Bind the app to `127.0.0.1:9000` once proxy use is confirmed. Preserve the measured JVM settings initially; this deployment change is not a memory-tuning experiment. Fix or remove the stale 9001 proxy and verify all jury aliases.

## Straightforward deployment procedure

These are the intended operations for a future deployment wrapper, **not commands to run with the existing blue/green scripts**.

### Before the maintenance window

1. Build and test the selected commit, run `sbt dist`, and record artifact hash. Upload and extract into a new release directory; verify archive layout, launcher and file ownership. Never overwrite the running release.
2. Restore a **consistent** production backup into an isolated MariaDB 10.6 database on another host or additional volume. Copy Flyway history with the data. While production is MyISAM, obtain a locking snapshot or a backup taken with writers stopped; the existing `run-with-prod-dump.sh --single-transaction` shortcut is insufficient for this rehearsal.
3. Start the exact packaged release on the copy with its own DB credentials and port. Restrict access, disable real outgoing email, use a staging public URL, and avoid publishing test writes or allowing cleanup of the shared production image cache.
4. Measure migrations, startup, backup and restore times and peak disk usage. Check login, gallery, voting, comments, results and round creation on the copy. Test restoring the backup and starting the **old** release too.
5. Record current release, DB history/checksums, pending migrations, previous runtime configuration and rollback class. Retain the release artifacts and protected backups outside the release path.
6. Confirm disk and outage budgets, consistent backup availability, migration results and maintenance coverage. A scheduled backup job reporting success does not substitute for a tested release-specific restore.

### During deployment

1. Acquire a deployment lock and verify the expected current release/schema. Validate Apache configuration. Prepare the previous release/config record before touching production.
2. Enable a persistent maintenance flag understood by **all jury HTTP and HTTPS vhosts**. Verify 503 responses for those hosts; keep unrelated websites on this Apache server available.
3. Drain in-flight writes and imports, then stop `wlxjury.service` and verify it is stopped. Coordinate other DB writers/DDL jobs. A proxy flag alone is not the write barrier.
4. Take a durable, consistent pre-deployment backup including Flyway history. For the current mixed MyISAM/InnoDB database, use a locking dump with appropriate privileges, such as `mariadb-dump --defaults-extra-file=<protected-file> --lock-tables --quick ...`; omit `--single-transaction`. Include required triggers/routines/events after inventorying them. Store with restrictive permissions, checksum it, and check every pipeline exit code. Do not hold a server-wide lock on unrelated databases unnecessarily.
5. Atomically switch `current` to the prepared release and start the service. Flyway migrates with production traffic still blocked. Use the rehearsed migration/startup timeout; collect sanitized failure diagnostics.
6. Require readiness that checks DB access and expected build/schema, with a strict HTTP status and request timeout. The observed `/auth` 200 plus DB checks is a transitional check; add a small readiness endpoint returning 503 until ready and identifying the build without exposing credentials.
7. Perform smoke checks locally while maintenance remains enabled. Check Flyway history, primary read paths, and agreed write checks. Keep write smoke tests isolated or reversible and account for them in rollback.
8. Clear maintenance only after success. Verify HTTPS through Apache and record the point at which real writes resume. Retain the previous release and backup; resume paused jobs according to the deployment record.

On failure after migration may have started, keep maintenance enabled until recovery is verified. A blanket EXIT/ERR trap must not reopen traffic against a partially migrated database. If failure happens before mutation, restoring the original service can safely end maintenance after its readiness succeeds.

## Rollback contract

| State / change | Recovery | Data consequence |
|---|---|---|
| Failure before migration | Restore original release/config and start it; verify health | No deployment-induced schema change |
| Migration fails or new version fails before maintenance ends | Stop candidate; restore the complete pre-deploy DB into a clean target, including history; restore old release/config; verify | Preserves real writes because production was stopped before backup; any later deployment smoke-test writes are discarded |
| Traffic resumed; schema unchanged or proven compatible | Deploy previous binary against current DB under maintenance, only if the exact old artifact passes Flyway validation and functional checks | Preserves production writes |
| Traffic resumed; schema/data transformation incompatible | Prefer a forward fix; alternatively restore old DB plus old release under maintenance following an explicit decision about lost/reconciled writes | Snapshot restoration discards writes made after backup unless recovered through a separately tested reconciliation process |

DDL is not rolled back by swapping binaries. Separate blue/green databases preserve an old snapshot but do not keep it current after cutover. Neither architecture provides a generic rollback that both reverses incompatible schema changes and preserves all subsequent writes.

Flyway checksum and migration-resolution validation is another compatibility condition; do not infer that an old release can start merely because columns appear compatible. Test the installed Flyway 9.16.3 behavior, including any future-migration policy, rather than applying newer documentation defaults blindly. [Flyway validation documentation](https://documentation.red-gate.com/flyway/reference/commands/validate).

## Next release: migration and capacity gate

With production at V53, current repository files introduce V54, V55, **V55.1**, V56, V57, V58 and V59. V55.1 is the new engine conversion; the latest numeric version alone is not enough to assess a migration set.

- V54 adds image media type; V55 creates indexes that earlier invalid filenames skipped.
- V55.1 rebuilds application tables as InnoDB and changes character set/collation. Its comments report 2.5 minutes on a production copy, size growth from 1.35 to 2.6 GB and peak 3.2 GB. These are prior measurements, not timings measured during this investigation.
- V56 changes a selection index; V57 resynchronizes denormalized monument IDs.
- V58 archives and deletes orphan selections and introduces cascading deletion. Run its documented orphan-count query and review the data/behavior change during rehearsal.
- V59 adds the round submission token column/index.

The current 5.5 GB of free space is not an approved capacity budget. Account for conversion temporary files, index rebuilds, orphan archive, database/binlog growth, backup output, both release artifacts and reserve for other hosted services. A second migrated DB on the same disk adds substantially more space pressure. Rehearse elsewhere and free or add storage before deployment if the measured budget does not fit; do not rely on a failed conversion cleaning up all space.

## Implementation sequence and acceptance

1. **External configuration first:** add secret-free production overlay and environment examples, required signing-key substitution, a single-service drop-in example, protected client-auth guidance and ignore rules for real local runtime files. Verify equivalent behavior on the installed release before bundling schema changes.
2. **Release/readiness support:** immutable release paths, persistent logs/PID, build identification, DB-backed readiness. Check absent credentials fail clearly, exact archive extraction, and systemd environment quoting. Match shutdown timeouts to the write-draining procedure.
3. **Single-service wrapper:** explicit prepare/deploy/restore operations, lock, validated targets, capacity preflight, durable backup, all-vhost maintenance, service stop/drain, atomic release switch, measured readiness deadline and phase-specific recovery. Recovery after real traffic resumes must not silently restore an old snapshot.
4. **Migration rehearsal:** restore real-sized data off production, run the exact artifact, measure the full sequence and restore old artifact+DB. Review V58 orphans and confirm integrity/Flyway history after migration and restore.
5. **Failure testing:** exercise backup failure, startup/migration failure, readiness failure and interrupted deployment on disposable infrastructure. Verify production would remain behind maintenance until a healthy original or candidate is selected; no snapshot is deleted or mistaken for an automatic rollback.
6. **Operational rollout:** one-time configuration migration, then the rehearsed maintenance deployment. Document the actual expected outage and recovery time. Publish one short runbook naming the wrapper commands and rollback classes.
7. **Existing blue/green artifacts:** mark them as legacy/unsafe for the current server setup. If retained for future implementation, correct the extraction, snapshot, write-quiescing, service-stop, secrets, state-transition and rollback defects listed above before use.

Acceptance: a subsequent deployment installs the unmodified packaged application config, changes no credential file, requires no config merge, validates a consistent restore, and accurately states whether previous code can run against the resulting DB. No destructive action proceeds with unknown target paths or DB identity.

## Verification limits

Implementation verification (2026-10-13): targeted Scala configuration/readiness, database readiness, job recovery and V55.1 migration tests passed; `sbt dist` succeeded with V55.1/V60 in the ZIP. Disposable Linux tests passed for deployment failures, credential adoption, actual MariaDB 10.6 mixed-engine backup/restore and rescue snapshots. The actual ZIP also started in production mode using the external overlay, reported build/schema readiness, rejected a missing signing key, and emitted no startup heartbeat failures. This is local synthetic-data verification, not a real production rehearsal.

Review follow-up (2026-10-13): automatic restarts are held while a deployment is open; `abort` backs out a pre-backup failure; `reopen` verifies public routing and can be retried; restore ends leftover app sessions, recreates the recorded character set and budgets the rescue dump; older releases are refused before maintenance; readiness is local-only; legacy adoption refuses unexternalized credentials; release build IDs require clean sources including untracked files; the blue/green slot files were removed and server setup files left out of the ZIP. [docs/deployment.md](../deployment.md) describes each.

This investigation did not deploy a release, restart production, modify credentials, take a production dump, test backup restoration, count V58 orphans, or benchmark the migrations. Local `sbt extractDepSources` could not run because the sandbox denied access to the sbt boot lock; framework configuration behavior was cross-checked with primary documentation instead. Implementation and disposable-environment checks above remain planned work.
