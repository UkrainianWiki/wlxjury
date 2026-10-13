# Deployment tooling alternatives

Date: 2026-10-13

Status: investigation of existing tools against the custom deployment code at `b9fe181`, revised after cross-review with the [deployment tooling analysis](2026-10-13-deployment-tooling-analysis.md), then extended with a review of which features are optional. Line counts are measured; savings are estimates. Apart from one dump-and-restore check on MariaDB 10.6, nothing has been replaced or tested with another tool.

## Summary

No existing tool does most of what `scripts/deployment/wlxjury-deploy.py` does. The custom code falls into three parts:

1. **Generic mechanics** that standard tools cover: release folders with a `current` link, locking, systemd stop/start, health-check retries, the maintenance flag. About 140 lines.
2. **Recovery logic specific to this setup** (one server, one MariaDB database, migrations that cannot be undone): saved phase records, the `abort`/`reopen`/`restore`/`resume-original` commands, the rescue dump, ending leftover sessions, and refusing a restore after traffic has reopened without `--accept-data-loss`. No tool found provides this.
3. **One-time cutover code** for the old 0.12 installation: `init-runtime`, `adopt-legacy`, `scrub-legacy`, the config externalizer and the credential scanner. About 350 lines of the tool and about 250 lines of tests. This is the most complex part, and both review rounds found their hardest bugs in it.

The largest maintenance saving comes from removing part 3 after production has switched, not from adopting a new tool.

A further review ([Optional features](#optional-features-a-simpler-deployment-with-standard-tools)) found that at this app's scale most of parts 1 and 2 is optional too. The Debian package the build already produces, standard `mariadb-dump` options, `curl`, `flock` and `tmux` cover routine deployment in a script of about 40 lines plus a recovery runbook. That trades the tool's guards against operator mistakes, and its automated tests, for a rehearsed manual procedure.

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
| Saved phase records and the recovery commands | No equivalent. Ansible's [`block/rescue`](https://docs.ansible.com/projects/ansible/latest/playbook_guide/playbooks_blocks.html) runs only the recovery tasks you write, and an unreachable host skips it. Tasks can save state, but Ansible has no resumable deployment record of its own. | This is the core value of the custom tool. |
| Build ID | [sbt-dynver](https://github.com/sbt/sbt-dynver) with sbt-buildinfo | Partly covered: only the version, commit and dirty suffix. The schema version from migration file names, the manifest and the reviewed-ID checks stay custom. sbt-dynver detects a dirty tree with `git describe --dirty`, which [ignores untracked files](https://public-inbox.org/git/xmqq5z89i5j3.fsf@gitster.c.googlers.com/), so the `git status --porcelain` check must stay too. Tags here have no `v` prefix (`0.10`), which needs `dynverVTagPrefix := false`. |
| Credential scan during adoption | [Gitleaks](https://github.com/gitleaks/gitleaks#commands) (`gitleaks dir PATH`), [detect-secrets](https://pypi.org/project/detect-secrets/) | Heuristic scanners, with their own misses and false positives. They can help a manual cutover but do not prove every credential was externalized; the manual review stays. |
| Whole-stack replacement | [Kamal](https://kamal-deploy.org/docs/commands/rollback) | Rollback switches only the app image; the database is still yours. Apache can stay in front, because kamal-proxy's [`http_port`/`https_port`](https://kamal-deploy.org/docs/configuration/proxy/) are configurable, but containerizing the app is still too large a change for one VM. |

## Optional features: a simpler deployment with standard tools

### Scale

WLX Jury runs as one service on one VM with one MariaDB database. Releases happen a few times a year, a short maintenance window is acceptable, and the developer is also the operator. The current tool makes every step automatic and guards against interruptions and operator mistakes. At this scale most of that is optional: each guard can become one standard command or a runbook step, provided the procedure is rehearsed.

### Standard tools already in place

- **The Debian package.** The build already produces a systemd Debian package (`DebianPlugin`, `SystemdPlugin`, `JavaServerAppPackaging`; `sbt packageDebSystemd`), and production runs one: `/usr/share/wlxjury`, with `/etc/wlxjury` pointing into it. In sbt-native-packager 1.10.0 the old package's `prerm` stops and disables the service, and the new package's `postinst` enables and starts it. So `dpkg -i` takes the place of the tool's release preparation, selection and start, and Flyway migrates on start as it does now.
- **Configuration.** The packaged unit reads `/etc/default/wlxjury`, and the packaged start script takes `JAVA_OPTS` from the environment. So `JAVA_OPTS="-Dconfig.file=/etc/wlxjury-runtime/production.conf ..."` there selects the external overlay without overriding `ExecStart`, and a drop-in only adds `EnvironmentFile=/etc/wlxjury-runtime/runtime.env` and `Restart=on-failure`. The package's `conf/` files are dpkg conffiles; once they are no longer edited on the server, upgrades replace them without prompting.
- **Database.** `mariadb-dump --databases --add-drop-database` writes `DROP DATABASE`, then `CREATE DATABASE` with the original character set and collation. Tested on MariaDB 10.6: restoring such a dump after a simulated migration brought back the original collation and rows and removed the table the migration had added. This replaces the tool's charset recording and its own `DROP`/`CREATE`.
- **Older releases.** Flyway refuses to start an older release against a newer schema: flyway-play always overrides Flyway 9's `*:future` ignore default, and `production.conf` sets `ignoreMigrationPatterns = []`. The tool's early refusal only avoids an outage that ends in that refusal.
- **Rollback package for the cutover.** `dpkg-repack wlxjury` rebuilds a `.deb` from the installed package, including its current conffiles. It gives the running 0.12 installation a rollback package without `adopt-legacy`. The repacked package holds the old literal credentials, so keep it root-only and delete it once it is no longer a rollback target.
- **Generic commands.** `flock` for the lock, `curl` for the maintenance and readiness checks (`--retry-all-errors` needs curl 7.71 or later), `tee` and `tmux` for the log and disconnects, `df` for space, `tar` for config snapshots.

### Feature by feature

| Feature | Tool lines (about) | Protects against | Standard replacement | Verdict |
|---|---|---|---|---|
| Immutable release folders, ZIP path checks, per-file hashes, `current` link (`prepare`, `identity`, `select`) | 90 | Modified or half-extracted releases | `sha256sum -c` on the `.deb`, then `dpkg -i`; keep the previous `.deb` | Drop |
| Reviewed build ID and manifest (`BuildIdentity.scala`, build manifest task) | 54, plus build.sbt | Deploying an unreviewed or dirty build | Package version plus commit; `/health/ready` reports the build | Optional |
| Older-release refusal | 5 | An outage that Flyway ends by refusing to start | Flyway's own refusal, plus a runbook rule | Drop |
| Operation lock | 16 | Two operations at once | `flock -n` | Replace |
| Maintenance and public URL checks with TLS/DNS diagnosis | 50 | A vhost without the snippet; routing broken after reopening | `curl` loop over the URLs, checking for the maintenance page | Replace, keep the check |
| Readiness wait matching build and schema | 20 | Reopening on a failed or wrong release | `curl --retry … /health/ready` | Replace |
| Free-space checks | 15 | Disk filling during the dump or migration | `df` before starting | Replace |
| Restart hold | 10 | A JVM killed mid-migration restarting and rerunning a partly applied migration | Two lines: a `Restart=no` drop-in and `systemctl daemon-reload` | Keep, as script lines |
| Writer units and DB events | 10 | Other writers changing data during the backup | `systemctl stop` the backup timer; events checked once in the runbook | Replace |
| Backup with checksum, config snapshot | 30 | A missing or unusable backup | `mariadb-dump … \| gzip`, `sha256sum`, `tar` | Replace |
| Phase records and the `abort`/`resume-original`/`reopen` states | 140 | Running the wrong recovery for how far the deployment got; interrupted runs | A linear script with a log, and a runbook keyed on the last logged step | Optional (main trade-off) |
| Restore automation: session ending, rescue dump, charset, history check, data-loss guard | 90 | Leftover sessions blocking the restore; losing writes made after reopening | Runbook commands: `SHOW PROCESSLIST`, `KILL`, a fresh dump, import, `dpkg -i previous.deb` | Replace, rehearse |
| Operations log and SIGHUP handling | 20 | A dropped SSH session killing a step | `tmux`, `tee -a` | Replace |
| `runtime.env` parser, protected-file checks, DB client setup | 40 | Misreading credentials the tool itself needs | Not needed: systemd reads the file, commands use `--defaults-extra-file` | Drop |
| One-time cutover code | 350 | Literal credentials in the copied installation | `dpkg-repack` for rollback, manual externalization, a checklist | Drop |

The app-side pieces stay either way: the external `production.conf` overlay with `runtime.env`, the `/health/ready` endpoint and the Apache maintenance snippet.

### Guards worth keeping in any form

Each is a line or two in a script or runbook:

1. The maintenance page is verified on every public URL before anything stops.
2. Writers are stopped before the backup, and the backup is complete before the package is installed.
3. Automatic restarts stay off until traffic reopens.
4. Traffic reopens only after a local smoke test.
5. After reopening, a restore needs an explicit decision and a fresh dump first.

### A minimal procedure (sketch, untested)

```bash
#!/bin/bash
# wlxjury-deploy NEW.deb SHA256 -- run as root inside tmux.
set -euo pipefail
URLS=(http://jury.wle.org.ua/ https://jury.wle.org.ua/)   # every jury URL, HTTP and HTTPS
DATABASE=wlxjury                                           # the WLXJURY_DB value
ADMIN=--defaults-extra-file=/etc/wlxjury-runtime/db-admin.cnf
exec 9>/run/wlxjury-deploy.lock; flock -n 9
deb=$(realpath "$1"); echo "$2  $deb" | sha256sum -c -
dir=/var/backups/wlxjury/$(date -u +%Y%m%dT%H%M%SZ); mkdir -m 700 "$dir"
exec > >(tee -a "$dir/deploy.log") 2>&1
cp /var/backups/wlxjury/installed.deb "$dir/previous.deb"  # the dpkg-repack output for the cutover
touch /etc/wlxjury-runtime/maintenance
for url in "${URLS[@]}"; do
  curl -s "$url" | grep -q 'undergoing maintenance' || { echo "No maintenance page at $url"; exit 1; }
done
printf '[Service]\nRestart=no\n' > /etc/systemd/system/wlxjury.service.d/zz-hold.conf
systemctl daemon-reload
systemctl stop wlxjury-backup.timer wlxjury
mariadb-dump "$ADMIN" --databases "$DATABASE" --add-drop-database --lock-tables --quick \
  --routines --events --triggers | gzip > "$dir/database.sql.gz"
sha256sum "$dir/database.sql.gz" > "$dir/database.sql.gz.sha256"
tar -C / -czf "$dir/config.tgz" etc/wlxjury-runtime etc/default/wlxjury
dpkg -i "$deb"                      # postinst starts the app; Flyway migrates
cp "$deb" /var/backups/wlxjury/installed.deb
curl -fsS --retry 150 --retry-delay 2 --retry-all-errors http://127.0.0.1:9000/health/ready
echo "Ready under maintenance. Smoke-test 127.0.0.1:9000, then reopen."
```

The runbook covers the rest:

- **Reopen** after the smoke test: remove `zz-hold.conf`, `systemctl daemon-reload`, remove the maintenance flag, check each URL with `curl -fsS`, start the backup timer.
- **Failed before `dpkg -i`** (no dpkg output in the log): remove the hold, `systemctl daemon-reload`, start the app and the backup timer, remove the flag.
- **Failed at or after `dpkg -i`, traffic still blocked:** stop the app; end leftover app sessions (`SHOW PROCESSLIST`, `KILL`) and confirm nobody else uses the database; check the dump with `sha256sum -c`; import it with `zcat database.sql.gz | mariadb "$ADMIN"`; restore `config.tgz` if config changed; `dpkg -i previous.deb`, which starts the old release; check it locally (the 0.12 release has no `/health/ready`, so use `/auth`); reopen.
- **After reopening:** prefer a forward fix. If a restore is still the decision, take a fresh dump first and accept losing later writes explicitly.

### What is lost

- **Guards against operator mistakes.** The script cannot stop someone running the wrong recovery for how far the deployment got, or restoring after reopening without a fresh dump. The runbook and the log carry that instead.
- **Machine-readable state for interrupted runs.** Recovery means reading the log.
- **About 1,230 lines of automated tests.** The rehearsal on a production copy, which the procedure requires anyway, becomes the main verification and must include the restore path.
- **Side-by-side releases.** Rollback needs the previous `.deb` kept, and an installed release is not re-verified before each use.

## Recommendations

Two paths remain. Choose before the cutover, because they use different layouts: the current tool moves the app to `/opt/wlxjury/current`, while the simple path keeps the package layout under `/usr/share/wlxjury`.

**Path A: keep the current tool**, for its automatic guards and tests.

1. **Remove the one-time cutover code once production has switched and recovery to the adopted release is no longer needed.** Do the cutover with a written checklist, optionally running `gitleaks dir` over the copied `conf/`. This removes about 350 lines of the tool and about 250 lines of tests. Keep the legacy support that recovery still uses: `wait_ready` checks `/auth` instead of `/health/ready` for a release marked `legacy`, and restore selects that release. Keep it until every recovery record targeting the adopted release has been explicitly retired. A later deployment does not retire them: earlier records are kept and can still select the adopted release.
2. **Keep `BuildIdentity.scala`.** sbt-dynver would replace only its version formatting; for a 54-line helper, adding a plugin needs a better reason than saving lines.
3. **Keep the core deploy and recovery tool.** Moving it to Ansible would replace roughly 140 lines of generic helpers with YAML. The recovery commands depend on the same helpers, so they would have to move to Ansible as well, where resumable phases have no built-in support, or the helpers would exist twice.

After step 1 the tool would be about 750 lines: the recovery logic plus the release handling and validation it shares with routine deployment.

**Path B: the Debian package, a short script and a runbook.** Recommended for this app's scale, if the developer remains the operator.

1. **Cut over with standard tools.** Make a rollback package with `dpkg-repack`, take a database dump, move the credentials into `runtime.env` and `production.conf` by hand, then install the new package with `dpkg -i --force-confnew`. Delete the `.dpkg-old` copies and the repacked package once they are no longer needed, since both hold the old credentials.
2. **Deploy with the script above and recover with the runbook**, keeping the five guards.
3. **Delete the custom tool**: `wlxjury-deploy.py`, its tests, `Dockerfile.test` and the ZIP-specific build steps. Keep `/health/ready`, the external config overlay and the Apache snippet; `BuildIdentity.scala` becomes optional.
4. **Rehearse the exact package on a production copy before the cutover**, including the restore path and the downgrade with `dpkg -i previous.deb`.

Path B removes about 2,300 lines of tool and test code for about 40 lines of script and a page of runbook. Path A remains the better choice if automatic guards matter more than less code, or if someone other than the developer will run deployments.

In either path, consider filesystem snapshots only after checking what a restore would cover. If the MariaDB data directory is on ZFS or LVM, a snapshot before migration restores faster than dump and import. But rolling back a data directory rolls back every database in that MariaDB instance, and a broader filesystem rollback can also undo unrelated files. Check storage isolation, consistency, snapshot growth and restore scope first ([MariaDB backup overview](https://mariadb.com/docs/server/server-usage/backup-and-restore/backup-and-restore-overview)). The server's storage has not been checked.

## Comparison with the deployment tooling analysis

[Deployment tooling analysis](2026-10-13-deployment-tooling-analysis.md) is a separate investigation of the same question. Its first version recommended Ansible for generic server operations, with pyinfra as the alternative for a Windows controller. This section reviews that first version. Its revised version, after reading this document, agrees with the combined recommendation below.

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

### Combined recommendation

This predates the optional-features review and corresponds to Path A in [Recommendations](#recommendations).

1. Finish the cutover, then remove the one-time code, keeping the legacy readiness check until no kept record can restore to the adopted release.
2. If Ansible is adopted, use it for server setup, not for deployment and recovery.
3. Keep the deploy and recovery tool and its shared helpers.
4. Add a small `ssh` plus `tmux` wrapper for single-command deployment, with explicit smoke-test acceptance before reopening.

## Limits

Upstream capabilities were taken from documentation, and the `ansible.mariadb.mariadb_db`, sbt-dynver, kamal-proxy and Gitleaks behavior was checked against their published docs and source. The Debian package behavior was read from the sbt-native-packager 1.10.0 jar this build uses, and the Flyway behavior from the flyway-play 8.0.1 and Flyway 9.16.3 sources. The dump-and-restore claim was tested on MariaDB 10.6 in the deployment test image. The script and runbook in Path B are a sketch: they have not been run, and no alternative tool was installed or run against this server.
