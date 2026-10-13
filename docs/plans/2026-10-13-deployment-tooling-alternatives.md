# Deployment tooling alternatives

Date: 2026-10-13

Status: investigation of existing tools against the custom deployment code at `b9fe181`. Line counts are measured; nothing has been replaced or tested with another tool.

## Summary

No existing tool does most of what `scripts/deployment/wlxjury-deploy.py` does. The custom code falls into three parts:

1. **Generic mechanics** that standard tools cover: release folders with a `current` link, locking, systemd stop/start, health-check retries, the maintenance flag. About 140 lines.
2. **Recovery logic specific to this setup** (one server, one MariaDB database, migrations that cannot be undone): saved phase records, the `abort`/`reopen`/`restore`/`resume-original` commands, the rescue dump, ending leftover sessions, and refusing a restore after traffic has reopened without `--accept-data-loss`. No tool found provides this.
3. **One-time cutover code** for the old 0.12 installation: `init-runtime`, `adopt-legacy`, `scrub-legacy`, the config externalizer and the credential scanner. About 350 lines of the tool and about 250 lines of tests. This is the most complex part, and both review rounds found their hardest bugs in it.

The largest maintenance saving comes from removing part 3 after production has switched, not from adopting a new tool.

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
| Saved phase records and the recovery commands | No equivalent. Ansible's `block/rescue` rolls back within one run but keeps no state if the operator's SSH session drops mid-outage. | This is the core value of the custom tool. |
| Build ID | [sbt-dynver](https://github.com/sbt/sbt-dynver) with sbt-buildinfo | Mostly covered. sbt-dynver detects a dirty tree with `git describe --dirty`, which [ignores untracked files](https://public-inbox.org/git/xmqq5z89i5j3.fsf@gitster.c.googlers.com/), so the `git status --porcelain` check must stay. Tags here have no `v` prefix (`0.10`), which needs `dynverVTagPrefix := false`. |
| Credential scan during adoption | [gitleaks](https://trickest.com/tools/gitleaks) (`--no-git` directory scan), [detect-secrets](https://pypi.org/project/detect-secrets/) | Heuristic scanners, with their own misses and false positives. Adequate as a one-off aid during a manual cutover. |
| Whole-stack replacement | [Kamal](https://kamal-deploy.org/docs/commands/rollback) | Rollback switches only the app image; the database is still yours. It would require containerizing the app and replacing Apache's proxy role, which is too large a change for one VM. |

## Recommendations

1. **Remove the one-time cutover code once production has switched and recovery from the adopted release is no longer needed.** Do the cutover with a written checklist, optionally running `gitleaks --no-git` over the copied `conf/`. This removes about 350 lines of the tool and about 250 lines of tests. The adopted release keeps working as a recovery target.
2. **Optionally replace `BuildIdentity.scala` with sbt-dynver**, keeping a few lines that run `git status --porcelain` for untracked files. The saving is small.
3. **Keep the core deploy and recovery tool.** Moving it to Ansible would replace roughly 140 lines of generic helpers with YAML. The recovery commands depend on the same helpers, so they would have to move to Ansible as well, where `block/rescue` expresses resumable phases poorly, or the helpers would exist twice.
4. **If the MariaDB data directory is on ZFS or LVM**, a filesystem snapshot before migration would make restore much simpler than dump and import. The server's storage has not been checked.

After step 1 the tool would be about 750 lines, nearly all of it recovery logic specific to this deployment.

## Comparison with the deployment tooling analysis

[Deployment tooling analysis](2026-10-13-deployment-tooling-analysis.md) is a separate investigation of the same question. It recommends Ansible for generic server operations, with pyinfra as the alternative for a Windows controller.

### Agreement

- No tool provides the database recovery needed here, and Flyway undo is no replacement.
- Kamal is too large a change for one VM.
- A smaller recovery component stays custom: locking, saved phase records, migration checks and the data-loss decision.
- The one-time legacy code should be separated from routine deployment and retired.

### Where that analysis is stronger

- **Server setup.** It covers provisioning and configuration management: Apache vhosts, the systemd drop-in, the runtime directory and permissions. These are manual steps in `docs/deployment.md` today, not part of the tool, and Ansible fits them well. This document did not consider them.
- **Ansible pitfalls.** Its async timeout terminates a remote task that is still running, which matters for long migrations and restores. The database module's import is not idempotent, so rerunning a playbook cannot be trusted to resume a restore. Both points are correct.
- **Operator flow.** One command that stops under maintenance for smoke tests and needs explicit acceptance before reopening. A noninteractive mode never accepts or restores on its own.

### Where it falls short

1. **The main recommendation is unmeasured**, as that analysis states. Ansible could replace about 140 lines of generic helpers in the tool. The recovery commands use the same helpers, so splitting deployment from recovery either keeps them twice (Ansible for deployment, Python for recovery) or moves recovery into Ansible, which that analysis itself warns against. A split would also need the lock and phase records shared between Ansible tasks and the Python component. With a second toolchain and a Linux controller for Windows, maintenance could increase.
2. **The largest saving is underplayed.** The cutover code (about 350 lines of the tool and 250 lines of tests) outweighs anything Ansible would replace, but appears only as step 4 of its migration path, without figures.
3. **The Windows argument for pyinfra is weaker than it appears.** The tool runs on the server, so the controller only matters for the single-command frontend. A plain `ssh` plus `tmux` wrapper provides that without either tool. It also answers a question that analysis leaves open: interactive acceptance must survive an SSH disconnect, so the frontend should attach to a server-side session rather than hold the operation itself.
4. **Gaps.** It does not consider sbt-dynver for the build ID, filesystem snapshots for restore, or gitleaks/detect-secrets for the one-time scan.

### Combined recommendation

1. Finish the cutover, then remove the one-time code.
2. If Ansible is adopted, use it for server setup, not for deployment and recovery.
3. Keep the deploy and recovery tool.
4. Add a small `ssh` plus `tmux` wrapper for single-command deployment.

## Limits

Upstream capabilities were taken from documentation, and the `ansible.mariadb.mariadb_db` and sbt-dynver behavior was checked against their published docs and source. No alternative tool was installed or run against this server.
