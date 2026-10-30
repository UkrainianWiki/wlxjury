# Ship the production logging config with each release

Production starts the app with `-Dlogger.file=/etc/wlxjury-runtime/logback.xml`, a file the
infra role installs once (`wlxjury-infra/roles/wlxjury_app/templates/logback.xml.j2`, a copy of
this repository's `conf/wlxjury/logback.xml`). Which loggers are written is code's business, but
changing them needs the playbook: the conflict-of-interest filter's `coi` logger went into the
repository's copy, not the server's, so its removal lines are not written in production
(2026-10-30, round 1958).

## What exists

- **Why the file is outside the release.** `dbedca56` (2026-10-13) moved production from the
  Debian package to versioned releases with configuration in `/etc/wlxjury-runtime`
  (`docs/plans/2026-10-11-deployment-procedure.md`, "Configuration without manual merges"). The
  release's own `conf/logback.xml` writes to `${application.home}/logs`, inside the release
  directory: root-owned, not writable by the `wlxjury` user, and a new one each deploy. The
  external file writes to `/var/log/wlxjury` (owned by `wlxjury`). No hand-made production
  change was involved: the old package's `logback.xml` is the repository's `8fdaa7d3`.
- **Kept out of releases on purpose.** `build.sbt` drops `conf/wlxjury/`, `conf/apache/` and
  `conf/systemd/` from the ZIP: "server setup files are installed once from a reviewed checkout".
- **The deploy tools snapshot it.** `wlxjury-deploy.py` copies `runtime.env`, `production.conf`
  and `logback.xml` into each operation's `config/` and writes them back on restore;
  `test_deployment.py` and `test_database_restore.py` (which starts the app with
  `-Dlogger.file=/workspace/conf/wlxjury/logback.xml`) expect it. `wlxjury-release.sh` doesn't
  touch it. `docs/deployment.md` installs it by hand for a new server.
- **Two copies drift.** `conf/wlxjury/logback.xml` (this repository, has `coi`) and the infra
  template (no `coi`).

## The change

1. **This repository:** move `conf/wlxjury/logback.xml` to `conf/logback-production.xml`, which
   the ZIP includes (only `conf/wlxjury/` and the like are dropped). Same appenders and
   `/var/log/wlxjury` path; the loggers as now (`application`, `coi`). Logback finds only
   `logback.xml` / `logback-test.xml` on the classpath by itself, so the extra file changes
   nothing in development or tests.
2. **The unit:** `-Dlogger.file=/opt/wlxjury/current/conf/logback-production.xml` in
   `wlxjury.service.j2` (it already starts `current/bin/wlxjury`). A release then switches its
   logging config with its code, and a backout restores the old one with the old release.
3. **Order, so no start lacks the file:** deploy a release that contains
   `conf/logback-production.xml` first (still started with the runtime file), then apply the
   playbook that changes the unit. A backout to a release older than step 1 needs the unit
   pointed back (or the runtime file kept, see 4).
4. **Infra role:** stop templating `logback.xml`; leave the existing
   `/etc/wlxjury-runtime/logback.xml` in place for one release cycle (a backout to an older
   release can use it), then remove it and the template.
5. **Deploy tools:** drop `logback.xml` from `wlxjury-deploy.py`'s snapshot and restore lists
   (accept its absence for records made before), `test_deployment.py` and
   `test_database_restore.py` (start with the release's file); `docs/deployment.md` stops
   installing it.

## Alternatives

- **Keep it external, apply the playbook for logger changes:** works, but a logging change then
  ships apart from the code that logs, and a backout doesn't restore it.
- **Logger levels in `production.conf`** (`logger.coi = INFO`): Play 3 no longer configures
  logback levels from application config.
- **Log at WARN** so the root level writes it: not a warning; hides real ones.

## Tests

- `ProductionConfigSpec` (or a new spec): the ZIP contains `conf/logback-production.xml`, its
  file appender writes under `/var/log/wlxjury`, and it enables `application` and `coi` at INFO.
- `test_database_restore` starts the packaged release with its own file.
- `test_deployment`: a restore of a record with and without a `logback.xml` snapshot.

## Size

Half a day here (move, packaging test, deploy tool lists) and an hour in `wlxjury-infra` (unit
template, config task, container test); one release and one playbook run to apply.
