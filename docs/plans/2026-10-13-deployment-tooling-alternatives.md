# Deployment tooling alternatives

Date: 2026-10-13

Status: investigation of existing tools against the custom deployment code at `b9fe181`, revised after cross-review with the [deployment tooling analysis](2026-10-13-deployment-tooling-analysis.md), extended with a review of which features are optional, and revised again after comparing that review with the other analysis's reduced-feature profile. Line counts are measured; savings are estimates. Apart from one dump-and-restore check on MariaDB 10.6, nothing has been replaced or tested with another tool.

## Summary

No existing tool does most of what `scripts/deployment/wlxjury-deploy.py` does. The custom code falls into three parts:

1. **Generic mechanics** that standard tools cover: release folders with a `current` link, locking, systemd stop/start, health-check retries, the maintenance flag. About 140 lines.
2. **Recovery logic specific to this setup** (one server, one MariaDB database, migrations that cannot be undone): saved phase records, the `abort`/`reopen`/`restore`/`resume-original` commands, the rescue dump, ending leftover sessions, and refusing a restore after traffic has reopened without `--accept-data-loss`. No tool found provides this.
3. **One-time cutover code** for the old 0.12 installation: `init-runtime`, `adopt-legacy`, `scrub-legacy`, the config externalizer and the credential scanner. About 350 lines of the tool and about 250 lines of tests. This is the most complex part, and both review rounds found their hardest bugs in it.

No tool replaces part 2, but at this app's scale most of it is optional ([Optional features](#optional-features-a-simpler-deployment-with-standard-tools)). A short script using `unzip`, `mariadb-dump`, `curl`, `flock` and `tmux`, with three durable milestones, covers routine deployment; failures are handled by a rehearsed manual runbook. That trades the tool's guided recovery commands and its automated tests for operator work, which is acceptable while the developer is also the operator.

The recommended plan ([Recommendations](#recommendations)): do the coming cutover with the current, tested tool; switch routine deployments to the script; delete the tool once its recovery records are retired or covered by the runbook. The Debian package the build already produces stays a later option.

## Current size

| File | Lines |
|---|---|
| `scripts/deployment/wlxjury-deploy.py` | 1,107 |
| `scripts/deployment/test_deployment.py` | 932 |
| `scripts/deployment/test_database_restore.py` | 294 |
| `project/BuildIdentity.scala` | 54 |
| `docs/deployment.md` | 143 |

## By area

| Area | Existing option | Assessment |
|---|---|---|
| Release folders, `current` link, keep the last N, lock | [Ansistrano](https://galaxy.semaphoreui.com/views/ansistrano/deploy) (Ansible port of Capistrano), Capistrano, Deployer | Fully covered, but only about 60 lines here. |
| systemd control, readiness wait, public URL checks | Ansible `systemd_service` and `uri` with retries; `curl --retry` | Covered. The recovery commands use the same helpers, so they would remain in Python unless recovery moves too. |
| Maintenance flag with Apache 503 | Standard pattern (e.g. capistrano-maintenance) | Keep the Apache snippet; the tool's part is small. |
| Backup before migrating, restore | `ansible.mariadb.mariadb_db` or `community.mysql.mysql_db` (dump/import); `mariadb-backup`; mydumper | Dump and import are covered. Session handling, integrity and identity checks, charset/collation and the data-loss guard stay custom in any tool. |
| Database rollback | Flyway undo is a [Teams-edition feature](https://forum.red-gate.com/discussion/89862/does-flyway-community-edition-supports-undo) and cannot restore deleted data | Not a replacement for backup and restore. |
| Saved phase records and the recovery commands | No equivalent. Ansible's [`block/rescue`](https://docs.ansible.com/projects/ansible/latest/playbook_guide/playbooks_blocks.html) runs only the recovery tasks you write, and an unreachable host skips it. Tasks can save state, but Ansible has no resumable deployment record of its own. | This is the core value of the custom tool, and the main thing a simpler deployment gives up. |
| Build ID | [sbt-dynver](https://github.com/sbt/sbt-dynver) with sbt-buildinfo | Partly covered: only the version, commit and dirty suffix. The schema version from migration file names, the manifest and the reviewed-ID checks stay custom. sbt-dynver detects a dirty tree with `git describe --dirty`, which [ignores untracked files](https://public-inbox.org/git/xmqq5z89i5j3.fsf@gitster.c.googlers.com/), so the `git status --porcelain` check must stay too. Tags here have no `v` prefix (`0.10`), which needs `dynverVTagPrefix := false`. |
| Credential scan during adoption | [Gitleaks](https://github.com/gitleaks/gitleaks#commands) (`gitleaks dir PATH`), [detect-secrets](https://pypi.org/project/detect-secrets/) | Heuristic scanners, with their own misses and false positives. They can help a manual cutover but do not prove every credential was externalized; the manual review stays. |
| Whole-stack replacement | [Kamal](https://kamal-deploy.org/docs/commands/rollback) | Rollback switches only the app image; the database is still yours. Apache can stay in front, because kamal-proxy's [`http_port`/`https_port`](https://kamal-deploy.org/docs/configuration/proxy/) are configurable, but containerizing the app is still too large a change for one VM. |

## Optional features: a simpler deployment with standard tools

### Scale

WLX Jury runs as one service on one VM with one MariaDB database. Releases happen a few times a year, a short maintenance window is acceptable, and the developer is also the operator. The current tool makes every step automatic and guards against interruptions and operator mistakes. At this scale most of that is optional: each guard can become one standard command or a runbook step, provided the procedure is rehearsed.

The current tool already leaves recovery to the operator: it never restores automatically, it only guides and guards the recovery commands. What becomes optional is that guided automation, not an automatic rollback.

### Standard tools that cover the routine path

- **Release folders.** The script keeps the current tool's layout, so it can take over after the cutover without moving the app: `sha256sum -c` on the reviewed ZIP from `sbt dist`, `unzip` into a new folder under `/opt/wlxjury/releases`, and an atomic switch of `/opt/wlxjury/current` (`ln -sfn` to a temporary link, then `mv -T`). Releases are root-owned and never overwritten, so earlier ones stay available for rollback. This narrows the trust model to archives built from the reviewed project; arbitrary ZIPs are not supported.
- **Database.** `mariadb-dump --databases --add-drop-database` writes `DROP DATABASE`, then `CREATE DATABASE` with the original character set and collation. Tested on MariaDB 10.6: restoring such a dump after a simulated migration brought back the original collation and rows and removed the table the migration had added. An exact restore must not leave tables from the new release behind, and this dump guarantees that without recording the charset separately. `--lock-tables` stays: while MyISAM tables remain, `--single-transaction` alone is not consistent.
- **Older releases.** Flyway refuses to start an older release against a newer schema: flyway-play always overrides Flyway 9's `*:future` ignore default, and `production.conf` sets `ignoreMigrationPatterns = []`. The script still checks first, because the check is one line and avoids an outage that would end in that refusal.
- **Readiness.** `/health/ready` answers 200 only when the database schema matches the running build's manifest, and returns that manifest. Comparing its `build` with the new release's manifest proves the intended release is serving.
- **Generic commands.** `flock` for one operation at a time, `curl` for the maintenance and readiness checks (`--retry-all-errors` needs curl 7.71 or later), `tee` and `tmux` (or `systemd-run`) for the log and disconnects, `df` for space, `tar` for a config snapshot.

### Feature by feature

| Feature | Tool lines (about) | Protects against | Standard replacement | Verdict |
|---|---|---|---|---|
| ZIP validator, custom extractor, per-file hashes re-checked before each use (`prepare`, `identity`) | 75 | Modified or hostile archives; edited release files | `sha256sum -c` on the ZIP, `unzip` into a new root-owned folder; keep the ZIP to re-extract a suspect release | Replace |
| `current` link switch (`select`) | 15 | A half-switched release | `ln -sfn` to a temporary link, then `mv -T` | Replace |
| Reviewed build ID and manifest (`BuildIdentity.scala`, build manifest task) | 54, plus build.sbt | Deploying an unreviewed or dirty build; not knowing which build serves | Unchanged: the script uses the manifest | Keep |
| Older-release refusal | 5 | An outage that Flyway ends by refusing to start | A `sort -V` comparison of the manifest's schema with the database | Keep, as one line |
| Operation lock | 16 | Two operations at once | One `flock` held for the whole script; an existing maintenance flag also blocks a new run | Replace |
| Maintenance and public URL checks with TLS/DNS diagnosis | 50 | A vhost without the snippet; routing broken after reopening | `curl` loop over the URLs, checking for the maintenance page | Replace, keep the check |
| Readiness wait matching build and schema | 20 | Reopening on a failed or wrong release | `curl --retry … /health/ready`, comparing the returned build | Replace |
| Free-space checks | 15 | Disk filling during the dump or migration | `df` against a measured budget | Replace, keep the check |
| Restart hold | 10 | A JVM killed mid-migration restarting and rerunning a partly applied migration | Two lines: a `Restart=no` drop-in and `systemctl daemon-reload` | Keep, as script lines |
| Writer units and DB events | 10 | Other writers changing data during the backup | `systemctl stop` the backup timer; events checked once in the runbook | Replace |
| Backup with checksum, config snapshot | 30 | A missing or unusable backup | `mariadb-dump … \| gzip`, `gzip -t`, `sha256sum`, `tar` | Replace |
| Phase records and the `abort`/`resume-original`/`reopen` states | 140 | Running the wrong recovery for how far the deployment got; interrupted runs | Three durable milestones and a runbook keyed on them | Reduce (main trade-off) |
| Restore automation: session ending, rescue dump, charset, history check, data-loss guard | 90 | Leftover sessions blocking the restore; losing writes made after reopening | Runbook commands: `SHOW PROCESSLIST`, `KILL`, a fresh dump, import, switch `current` back | Replace, rehearse |
| Operations log and SIGHUP handling | 20 | A dropped SSH session killing a step | `tmux` or `systemd-run`, `tee -a` | Replace |
| `runtime.env` parser, protected-file checks, DB client setup | 40 | Misreading credentials the tool itself needs | Not needed: systemd reads the file, commands use `--defaults-extra-file` | Drop |
| One-time cutover code | 350 | Literal credentials in the copied installation | Used once for the cutover, then deleted | Drop after the cutover |

The app-side pieces stay: the external `production.conf` overlay with `runtime.env`, the `/health/ready` endpoint, the build manifest and the Apache maintenance snippet.

### Guards worth keeping in any form

Each is a line or two in the script or runbook:

1. The maintenance page is verified on every public URL before anything stops.
2. Writers are stopped before the backup, and the backup is complete and checked before the new release starts.
3. Automatic restarts stay off until traffic reopens.
4. Traffic reopens only after a local smoke test.
5. After reopening, a restore needs an explicit decision and a fresh dump first.
6. Three milestones are written durably before the step they describe: `backup-complete`, `migration-may-start` and `traffic-may-resume`. A missing or damaged milestone file is never taken as proof that a step did not happen.

### A minimal procedure (sketch, untested)

```bash
#!/bin/bash
# wlxjury-deploy RELEASE.zip SHA256 -- run as root inside tmux, after the cutover.
set -euo pipefail
URLS=(http://jury.wle.org.ua/ https://jury.wle.org.ua/)   # every jury URL, HTTP and HTTPS
DATABASE=wlxjury                                           # the WLXJURY_DB value
ADMIN=--defaults-extra-file=/etc/wlxjury-runtime/db-admin.cnf
ROOT=/opt/wlxjury
exec 9>/run/wlxjury-deploy.lock; flock -n 9
[ ! -e /etc/wlxjury-runtime/maintenance ] || { echo "Maintenance is on: finish the previous operation"; exit 1; }
zip=$(realpath "$1"); echo "$2  $zip" | sha256sum -c -
dir=/var/backups/wlxjury/$(date -u +%Y%m%dT%H%M%SZ); mkdir -m 700 "$dir"
exec > >(tee -a "$dir/deploy.log") 2>&1
mark() { echo "$1" >> "$dir/milestones"; sync "$dir/milestones"; }
field() { python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"; }

# Unpack into a new root-owned release; never overwrite one.
release=$ROOT/releases/$(basename "$zip" .zip)-${2:0:12}
[ ! -e "$release" ] || { echo "Release exists: $release"; exit 1; }
staging=$(mktemp -d "$ROOT/releases/.staging.XXXXXX")
unzip -q "$zip" -d "$staging"; mv "$staging"/*/ "$release"; rmdir "$staging"
build=$(field build < "$release/wlxjury-build.json")
want=$(field schemaVersion < "$release/wlxjury-build.json")
have=$(mariadb "$ADMIN" -N -e "SELECT version FROM $DATABASE.flyway_schema_history
  WHERE success = 1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")
[ "$(printf '%s\n' "$have" "$want" | sort -V | tail -1)" = "$want" ] || { echo "Release is older than the database"; exit 1; }
[ "$(df --output=avail -B1 /var/lib/mysql | tail -1)" -gt $((4 << 30)) ] || { echo "Less than 4 GiB free"; exit 1; }
printf 'previous=%s\ncandidate=%s\nzip=%s\n' "$(readlink -f "$ROOT/current")" "$release" "$2" > "$dir/record"

touch /etc/wlxjury-runtime/maintenance
for url in "${URLS[@]}"; do
  curl -s "$url" | grep -q 'undergoing maintenance' || { echo "No maintenance page at $url"; exit 1; }
done
printf '[Service]\nRestart=no\n' > /etc/systemd/system/wlxjury.service.d/zz-hold.conf
systemctl daemon-reload
systemctl stop wlxjury-backup.timer wlxjury
mariadb-dump "$ADMIN" --databases "$DATABASE" --add-drop-database --lock-tables --quick \
  --routines --events --triggers | gzip > "$dir/database.sql.gz"
gzip -t "$dir/database.sql.gz"; sha256sum "$dir/database.sql.gz" > "$dir/database.sql.gz.sha256"
tar -C / -czf "$dir/config.tgz" etc/wlxjury-runtime etc/default/wlxjury
mark backup-complete

mark migration-may-start
ln -sfn "$release" "$ROOT/current.next"; mv -T "$ROOT/current.next" "$ROOT/current"
systemctl start wlxjury            # Flyway migrates on start
serving=$(curl -fsS --retry 150 --retry-delay 2 --retry-all-errors http://127.0.0.1:9000/health/ready | field build)
[ "$serving" = "$build" ] || { echo "Serving $serving, expected $build"; exit 1; }
echo "Ready under maintenance. Smoke-test 127.0.0.1:9000, then reopen $dir."
```

Reopening is a few more lines in a second script: `mark traffic-may-resume`, remove `zz-hold.conf`, `systemctl daemon-reload`, remove the maintenance flag, check each URL with `curl -fsS`, start the backup timer.

### Recovery runbook

Read the last milestone in the operation's folder and use the matching section:

- **No `backup-complete`:** the database and the selected release are unchanged. If the app is still running (for example, a URL had no maintenance page), check it locally and remove the flag. If it was stopped, remove the hold, `systemctl daemon-reload`, start the app and the backup timer, check it locally, then remove the flag.
- **`backup-complete` but no `migration-may-start`:** the same, since the old release was never switched or restarted.
- **`migration-may-start`, traffic still blocked:** never just restart the old release; the migration may have run. Stop the app. End leftover app sessions (`SHOW PROCESSLIST`, `KILL`, and wait for a killed `ALTER` to finish rolling back) and confirm nobody else uses the database. Check the dump with `sha256sum -c`, import it with `zcat database.sql.gz | mariadb "$ADMIN"`, and restore `config.tgz` if config changed. Switch `current` back to `previous` from the record, start the app, check readiness (a legacy release has no `/health/ready`; use `/auth` and the Flyway history), then reopen.
- **`traffic-may-resume`:** later writes may exist. Prefer a forward fix. A restore needs an explicit decision to lose or reconcile those writes, and a fresh dump taken first.

Rehearse every section on a production copy. The existing Docker test image can exercise the database steps, as the dump check above did.

### What is lost

- **Guided recovery commands.** The milestones say which runbook section applies, but the operator carries out the recovery by hand, and nothing stops the wrong command being typed. Recovery may take longer.
- **About 1,230 lines of automated tests.** The rehearsal on a production copy, which the procedure requires anyway, becomes the main verification and must include each runbook section.
- **Release re-verification.** Extracted files are not re-hashed before each use; a suspect release is re-extracted from its kept ZIP instead.
- **Automatic config restore.** The runbook restores `config.tgz` only when config changed with the release; routine releases keep config unchanged.

### Later option: the Debian package

The build already produces a systemd Debian package (`DebianPlugin`, `SystemdPlugin`, `JavaServerAppPackaging`; `sbt packageDebSystemd`), and the old 0.12 installation is one. It could replace the release folders entirely:

- In sbt-native-packager 1.10.0, read from the jar this build uses, the old package's `prerm` stops and disables the service, and the new package's `postinst` enables and starts it. With the backup taken before `dpkg -i`, that start is the deliberate migration start.
- The packaged unit reads `/etc/default/wlxjury`, and the packaged start script takes `JAVA_OPTS` from the environment, so the external `production.conf` can be selected there instead of overriding `ExecStart`.
- The package's `conf/` files are dpkg conffiles; once they are not edited on the server, upgrades replace them without prompting.
- `dpkg-repack wlxjury` rebuilds a `.deb` from an installed package, which gives a rollback package for whatever is installed. A repack of the 0.12 package holds its literal credentials, so it must stay root-only.

It is not the first step, for four reasons:

1. **Build route.** The build has no `JDebPackaging`, and Windows has no `dpkg-deb`, so the package must be built in WSL or Docker, or the build must add JDebPackaging. `sbt dist` builds the ZIP on Windows directly.
2. **Layout change.** After a cutover with the current tool, the app runs from `/opt/wlxjury/current`; returning to `/usr/share/wlxjury` changes the systemd drop-in and is another behavior to validate.
3. **Old maintainer scripts.** During an upgrade the installed package's `prerm` runs, and the 0.12 package was built with an unknown native-packager version. Check `/var/lib/dpkg/info/wlxjury.prerm` on the server before relying on it.
4. **Migration-heavy first release.** Changing packaging during the V53-to-V60 release adds risk to the release that can least afford it.

## Recommendations

1. **Do the cutover with the current, tested tool.** Its adoption, recovery and tests are verified locally, and the V53-to-V60 release is the riskiest one. Remove the one-time cutover code afterwards (about 350 lines of the tool and about 250 lines of tests). Keep the legacy support that recovery still uses: `wait_ready` checks `/auth` instead of `/health/ready` for a release marked `legacy`, and restore selects that release. Keep it until every recovery record targeting the adopted release has been explicitly retired. A later deployment does not retire them: earlier records are kept and can still select the adopted release.
2. **Switch routine deployments to the script and runbook above**, keeping the six guards. The script keeps the tool's layout, so no migration is needed.
3. **Rehearse the script and every runbook section on a production copy** before relying on them, including an interrupted migration.
4. **Delete the Python tool, its tests and `Dockerfile.test`** once the tool's recovery records are retired, or the runbook covers restoring from them (each holds a dump, a config snapshot and the previous release). This removes about 2,300 lines of tool and test code for about 50 lines of script and a page of runbook.
5. **Keep `BuildIdentity.scala` and the manifest.** The script relies on them for the older-release check and the readiness comparison.
6. **Use Ansible, if at all, for server setup**, not for deployment and recovery.
7. **Consider the Debian package later**, once its build route and the installed package's maintainer scripts have been checked.

Keep the current tool instead (step 1 only) if guided recovery matters more than less code, or if someone other than the developer will run deployments.

Consider filesystem snapshots only after checking what a restore would cover. If the MariaDB data directory is on ZFS or LVM, a snapshot before migration restores faster than dump and import. But rolling back a data directory rolls back every database in that MariaDB instance, and a broader filesystem rollback can also undo unrelated files. Check storage isolation, consistency, snapshot growth and restore scope first ([MariaDB backup overview](https://mariadb.com/docs/server/server-usage/backup-and-restore/backup-and-restore-overview)). The server's storage has not been checked.

## Comparison with the deployment tooling analysis

[Deployment tooling analysis](2026-10-13-deployment-tooling-analysis.md) is a separate investigation of the same question. Its first version recommended Ansible for generic server operations, with pyinfra as the alternative for a Windows controller. The first part of this section reviews that version; [the last part](#comparison-with-its-reduced-feature-profile) compares its later reduced-feature profile with the optional-features review above.

### Agreement

- No tool provides the database recovery needed here, and Flyway undo is no replacement.
- Kamal is too large a change for one VM.
- A smaller recovery component stays custom: locking, saved phase records, migration checks and the data-loss decision.
- The one-time legacy code should be separated from routine deployment and retired.

### Where that analysis is stronger

- **Server setup.** It covers provisioning and configuration management: Apache vhosts, the systemd drop-in, the runtime directory and permissions. These are manual steps in `docs/deployment.md` today, not part of the tool, and Ansible fits them well. This document did not consider them.
- **Ansible pitfalls.** Its async timeout terminates a remote task that is still running, which matters for long migrations and restores. The database module's import is not idempotent, so rerunning a playbook cannot be trusted to resume a restore. Both points are correct.
- **Operator flow.** One command that stops under maintenance for smoke tests and needs explicit acceptance before reopening. A noninteractive mode never accepts or restores on its own.

### Where its first version fell short

1. **The main recommendation was unmeasured**, as that analysis stated. Ansible could replace about 140 lines of generic helpers in the tool. The recovery commands use the same helpers, so splitting deployment from recovery either keeps them twice (Ansible for deployment, Python for recovery) or moves recovery into Ansible, which that analysis itself warns against. A split would also need the lock and phase records shared between Ansible tasks and the Python component. With a second toolchain and a Linux controller for Windows, maintenance could increase.
2. **The largest saving was underplayed.** The cutover code (about 350 lines of the tool and 250 lines of tests) outweighs anything Ansible would replace, but appeared only as step 4 of its migration path, without figures.
3. **The Windows argument for pyinfra is weaker than it appears.** The tool runs on the server, so the controller only matters for the single-command frontend. A plain `ssh` plus `tmux` wrapper provides that without either tool. Interactive acceptance must survive an SSH disconnect, so the frontend should reconnect to work running on the server rather than hold the operation itself. A server-side session such as tmux does that; as the revised analysis notes, a systemd job with the saved phase records could too.
4. **Gaps.** It did not consider sbt-dynver for the build ID, filesystem snapshots for restore, or Gitleaks/detect-secrets for the one-time scan.

### Corrections from the cross-review

The revised analysis corrected six points in the first version of this document. Each was checked against the cited documentation or the code, and the sections above now reflect them:

- A filesystem snapshot restore covers the whole MariaDB instance and possibly more, so it is not automatically simpler.
- Ansible's `block/rescue` runs only the tasks you write, and tasks can save state; the earlier wording was too absolute. Recovery still stays custom.
- sbt-dynver replaces only part of `BuildIdentity.scala`; the earlier "mostly covered" overstated it.
- Kamal does not require removing Apache.
- Current Gitleaks scans directories with `gitleaks dir PATH`; `--no-git` is outdated.
- The remaining ~750 lines include shared release handling, not only recovery logic, and retiring the adoption code must keep the legacy readiness check while the adopted release can still be restored.

### Comparison with its reduced-feature profile

Both analyses then reached the same main conclusion independently: the large reduction comes from accepting manual recovery, not from swapping tools. Both keep the same guards, require an exact database restore, and keep Ansible optional.

That profile improved this document's first optional-features version in five ways, all adopted above:

1. **Sequencing.** It keeps the tested tool for the migration-heavy cutover and simplifies afterwards. With a ZIP-based script the layout stays the same, so the earlier argument for deciding before the cutover applied only to the Debian path.
2. **Build route.** It flagged the Windows build route for the Debian package. Checked: the build has no `JDebPackaging` and Windows has no `dpkg-deb`, so a `.deb` needs WSL or Docker; `sbt dist` builds the ZIP natively.
3. **Milestones instead of the log.** Three durable milestones replace reading the log to decide which recovery applies; a log written through `tee` can lose its last lines when the process is killed.
4. **Cheap checks.** It keeps cheap checks such as the older-release refusal and capacity, which this document had dropped or reduced to a runbook step.
5. **Runbook care.** Never restart the old release once a migration may have begun, and `--single-transaction` alone is not consistent while MyISAM tables remain.

This document adds four points to that profile:

1. **Package scripts.** The Debian maintainer scripts are verified rather than unknown: in sbt-native-packager 1.10.0 the old `prerm` stops and disables the service, and the new `postinst` enables and starts it. With the backup taken before `dpkg -i`, the package's start is the intended migration start rather than an early one. The open question is the installed 0.12 package's own `prerm`.
2. **Exact restore.** `mariadb-dump --databases --add-drop-database` was tested to restore the original collation and remove tables added by a migration, so the record need not store the charset.
3. **Config snapshot.** By the profile's own rule of keeping cheap checks, a one-line `tar` of the runtime config is worth keeping as evidence rather than dropping.
4. **Size.** A concrete sketch puts the routine path at about 50 lines, against about 2,300 lines of tool and tests today.

### Earlier combined recommendation

This predates the optional-features review and matches step 1 of [Recommendations](#recommendations), which now supersedes it.

1. Finish the cutover, then remove the one-time code, keeping the legacy readiness check until no kept record can restore to the adopted release.
2. If Ansible is adopted, use it for server setup, not for deployment and recovery.
3. Keep the deploy and recovery tool and its shared helpers.
4. Add a small `ssh` plus `tmux` wrapper for single-command deployment, with explicit smoke-test acceptance before reopening.

## Limits

Upstream capabilities were taken from documentation, and the `ansible.mariadb.mariadb_db`, sbt-dynver, kamal-proxy and Gitleaks behavior was checked against their published docs and source. The Debian package behavior was read from the sbt-native-packager 1.10.0 jar this build uses, the Flyway behavior from the flyway-play 8.0.1 and Flyway 9.16.3 sources, and the readiness behavior from `ReadinessController`. The dump-and-restore claim was tested on MariaDB 10.6 in the deployment test image. The script and runbook are a sketch: they have not been run, and no alternative tool was installed or run against this server.
