# Operations and observability

How the jury tool is run in production and how we see what it does: log lines, health and
readiness, backups and restores, rollback, monitoring, the checks before a deploy and the
deploy itself. Each rule has a short why and an example. The step-by-step deploy and recovery
runbook is [deployment.md](../deployment.md); what may appear in a log line is
[secure-coding.md](../security/secure-coding.md) §13; heap and pool sizing is
[performance/practices.md](../performance/practices.md) §4. **Sources:** `docs/deployment.md`;
secure-coding §13, §18, §20; db practices §1.6 (R227-1); `Secured.denialLogLine`,
`ReadinessController`; the roadmap's "Before any production deploy"; S4/S8 in
[`2026-11-02-security-resolution.md`](../plans/2026-11-02-security-resolution.md);
[`2026-10-30-logging-config-in-releases.md`](../plans/2026-10-30-logging-config-in-releases.md).

Production today: one VPS shared with Apache/WordPress; Apache proxies to Play on
`127.0.0.1:9000` (`conf/apache/0002-wlxjury.conf`; to be confirmed on the server, §5.3); one
`wlxjury.service` running a versioned ZIP release; MariaDB 10.6 on the same host (10.11 after
the move to Ubuntu 24.04, [`2026-10-19-mariadb-10.11.md`](../plans/2026-10-19-mariadb-10.11.md)).

## Contents

1. [Log lines](#1-log-lines)
2. [Health and readiness](#2-health-and-readiness)
3. [Backups and restore drills](#3-backups-and-restore-drills)
4. [Deploys and rollback](#4-deploys-and-rollback)
5. [Monitoring and throttling](#5-monitoring-and-throttling)
6. [Runtime limits](#6-runtime-limits)
7. [Guards](#7-guards)
8. [Where the setup doesn't follow these yet (for triage)](#8-where-the-setup-doesnt-follow-these-yet-for-triage)
9. [Contest series (root)](#9-contest-series-root)

## 1. Log lines

**1.1 One event, one key-value line.** An event name first, then `key=value` fields, untrusted
text quoted by `Secured.logValue` so a line stays single and parseable:
`authorization_denied ref=… accountId=… method=… path=… reason=missing_role`
(`Secured.denialLogLine`), `server_error ref=… method=… path=… accountId=… contestId=…`
(`ErrorHandler`), `image_fetch_refused host=…` (`LocalImageCacheService`). *Why:* support and fail2ban match lines
by event and field, not by prose that changes.

**1.2 A reference links what the user saw to the line.** A denial, a refused CSRF token and a
server error show a short reference on the page or in the JSON (`{error, reference}`); the same
`ref=` is in the log line. Users never see the exception (secure-coding §12).

**1.3 Named loggers by concern.** `security.authz` (denials, WARN), `security.account`
(invitations, links, OAuth: `oauth_*`; a user import line that failed,
`user_import_line_failed byAccountId=… contestId=… error=<class name>`, WARN; the account actions
of secure-coding §14, INFO, by ids only: `account_search`, `account_added_notice`,
`wiki_confirm_page`, `add_confirmed_unverified_wiki`, `wiki_taken_panel`, `password_set_by_admin`,
`password_set_by_admin_refused`, `account_retired`, `account_retire_refused`, `duplicates_report`,
`accounts_kept_apart`, `account_join_queued`, `account_join_refused`, `account_joined`,
`account_join_abandoned`, `sign_in_refused reason=account_join` (`LoginResults`); WARN:
`account_added_notice_failed`),
`security.auth` (today one line:
`oauth_choose_proof_failed accountId=… proof=password ip=…`, a wrong password in the OAuth
account chooser, `OAuthChooserController`; the password sign-in form, `LoginController`, logs no
failure line: OP4), `security.roles`, `security.password`; `coi` (conflict-of-interest
removals, the only record of them, INFO); `readiness`; `services.ApiTokenService` (refused tokens by
prefix); `round.admin` (a round's admin changes: `freeze_release`, `juror_add`, `juror_reassign`;
and root's contest series work: `series_created`, `series_renamed`, `series_retired`,
`series_unretired`, `series_assigned`, `series_unassigned`, `series_changed` (each with `contestId=`,
`seriesId=`, `from=`, `by=` and a bulk assignment's `batch=`), `series_batch_undone batch= undone=
left= by=`, `contest_created contestId= how= by=`, `contest_identity`, `contest_retired`,
`contest_unretired`; §9);
`round.access` (a juror refused a round's gallery or large view, §1.7); `services.imports` and
`services.jobs` (the class loggers of the imports and the job scheduler, §1.7). A new class of security event gets a `security.*` logger and a level the production
logback writes.

**1.4 Ids, never personal data.** Accounts by `accountId`/`userId`; no email, name, password,
token or a whole `User`; mailed-link tokens masked in paths (`Secured.logPath`). Rules and guards:
secure-coding §13 (`LogHygieneSpec`).

**1.5 No bound values from the database.** SQL is logged as templates with `?` (`SqlLogging`);
`org.mariadb.jdbc` stays at ERROR because its WARN lines quote values. Driver messages inside
logged exceptions still quote values (`Duplicate entry '<email>'`): R227-1 plans a redacting
converter (db §1.6, §11 V2). Until it lands, a new `logger.error(…, e)` on a database path is a
review item.

**1.6 The production logback ships in the release.** `conf/logback-production.xml`: root WARN,
rolling `/var/log/wlxjury/application.log` (50 MB files, 30 days, 1 GB cap), the app's INFO
loggers. The unit (`wlxjury-infra`'s `wlxjury.service.j2`) starts the selected release with
`-Dlogger.file=/opt/wlxjury/current/conf/logback-production.xml`, so a logger change ships with
the code that logs. A backout (`wlxjury-release restore`) selects the old release, and its next
start reads that release's own file; for one release cycle the unit falls back to the infra
role's `/etc/wlxjury-runtime/logback.xml` when the selected release predates the file (without a
readable `-Dlogger.file` the app exits before any log exists). The deploy tools no longer
snapshot a `logback.xml`. `sbt dist` refuses a ZIP without the file. *Why:* the `coi` logger went
into the repository's copy only, so its removal lines were not written in production
(2026-10-30; [logging plan](../plans/2026-10-30-logging-config-in-releases.md)). *In effect*
after the playbook run that installs the unit and the app's next restart; until then production
reads `/etc/wlxjury-runtime/logback.xml`.

**1.7 The support lines** (D-0b of the [support diagnostics
plan](../plans/2026-11-18-support-diagnostics.md)). Production writes these at INFO
(`conf/logback-production.xml`: `services.imports`, `services.jobs`, `security.account`,
`security.roles`, `round.admin`, `round.access`; `round.counts` stays at WARN, its INFO being
the periodic recompute's summary). `services.imports` at INFO also writes `CommonsFileCheck`'s
per-file lines (a file renamed, changed or deleted on Commons, as a check of the stored files
finds them):

- `import_done job=… contestId=… mode=import|monument_ids source="…" titles=… seen=… inserted=…
  linked=… updated=… monumentIdsChanged=… missing=… notInCountries=… byCountry=true|false|-`
  (`ImageImportHandler`): an import's summary, the source quoted; `titles` the files of a
  pasted list (0: the source's), `byCountry` whether a page's files were limited to the contest's
  country categories. `monument_lists_done job=… contestId=… lists="…" pages=… monuments=…`
  (`MonumentListImportHandler`).
- `job_done job=… kind=… contestId=…` (INFO), `job_failed job=… kind=… contestId=… error="…"`
  (WARN), `job_crashed job=… kind=… contestId=… ref=…` (ERROR, with the exception; `preparing=true`
  when its fetch failed) (`JobScheduler`); `contestId=-` for a job of no contest.
- `round_access_refused reason=round_stopped|round_preparing|round_closing|round_distributing|juror_stopped
  roundId=… accountId=… contestId=…` (`round.access`, INFO): a juror of the round opened its
  gallery, large view or region menu, or commented on it, while it was stopped, frozen as the
  previous round of a distribution that gives it back (`round_preparing`, e.g. "Distribute new
  files") or of a creation that closes it (`round_closing`), still being distributed, or they
  were stopped on it. They see a page saying so (200, DG7), or a script the 409 `round_closed` with
  the `reason`, so there is no `authorization_denied` line; anyone else refused there still gets
  one.
- `import_source_check_failed contestId=… source="…" error=<class name>` (WARN,
  `services.imports.SourceName`): the files page could not check a source name without a
  namespace prefix on Commons (DG11); the admin was told and nothing was queued.
- `server_error` and `csrf_failure` carry `accountId=… contestId=… accountFrom=…`:
  `accountFrom=user`, the session's user as the database told it, with their current contest;
  `session`, the signed session's own ids, unchecked; `none`, signed out (`-`). `server_error`
  is logged first, from the session's ids, so that a lookup waiting for a connection never
  delays it.
- The Commons and image-cache WARN lines (`MwApi`, `CommonsLogin`, `CommonsFileCheck`,
  `LocalImageCacheService`) quote file titles, URLs, response bodies and exception text with
  `logValue`.
- `authorization_denied … resource=api:<endpoint> reason=token_read_only|token_endpoint|token_not_local
  … auth=token tokenId=… scope=local_read remote="…"` (`security.authz`, WARN; D-1b): a read-only,
  server-only API token used for anything but a `GET`, on an endpoint not open to such tokens, or
  not from the server itself (a remote address, or any `Forwarded` / `X-Forwarded-*` header:
  through Apache). `remote` is the client's address as Play resolved it (behind Apache, the
  forwarded one); it is on these lines only. A remote one is a sign the token leaked:
  revoke it on the owner's profile ([api.md](../api.md)). `wlxjury-diag recent` (§5.5) counts the
  denials with a `targetContestId` and the `round_access_refused` lines per contest.

## 2. Health and readiness

**2.1 `/health/ready` is for the deploy tools.** `GET /health/ready` answers only a literal
loopback address (`ReadinessController.isLocal`; a request through Apache carries
`X-Forwarded-For` and gets 404): 200 with the release identity (`wlxjury-build.json`) when the
database's Flyway version equals the build's `schemaVersion` **and** the pool runs at READ
COMMITTED; otherwise 503 `{status: unavailable}`. No JDBC URL, credential or exception text,
`no-store`. *Why:* the release tools wait on it before reopening, and a server whose
`production.conf` overrides `db.default.hikaricp.transactionIsolation` must not take traffic (the
write paths rely on READ COMMITTED, db §6.1); the refusal is logged on `readiness` with the
setting to fix.

**2.2 Liveness is systemd's.** The service restarts on failure (`Restart=on-failure`); there is no
separate liveness endpoint. A legacy release answers `/auth`, not `/health/ready`
(deployment.md, "Recovery runbook").

## 3. Backups and restore drills

**3.1 Every release that migrates takes a dump first.** `wlxjury-release deploy` dumps the
database (`--databases --add-drop-database --lock-tables`) into the operation folder
`/var/backups/wlxjury/<UTC>-<build>/`, checks it and saves its checksum and a config snapshot; a
release without new migrations records `backup-skipped`. The server's own database-backup timer
is paused during an operation (deployment.json).

**3.1a The server's nightly timer dumps without locks once every table is InnoDB.** Its dump uses
`--lock-tables` whenever the database has a non-InnoDB table, which read-locked all of wlxjury's tables for about
40 s a night until V113 converted the last seven (unused legacy tables). After that release, the backup
log's line "dumping database wlxjury" must not say "non-InnoDB"; if it does, list the culprit with the
query in V113's header. A new table must be InnoDB (the default).

**3.2 A restore is checked before it deletes anything:** checksum and gzip integrity, the
database's identity, free space, and after writes resumed a fresh `before-restore-*` rescue dump
(deployment.md, "Deploying a release").

**3.3 Rehearse restores.** Each deploy and each section of the recovery runbook is rehearsed on an
isolated production copy before relying on it (deployment.md, "Build and rehearse"); CI's
`test_database_restore` and `test_release_script` run the restore path against a real MariaDB and
the packaged ZIP on every PR. At a local release build the release agent always runs
`test_database_restore` (it restores the build's own ZIP, with `-e CI`, so a missing ZIP fails) and runs `test_release_script` only when
`scripts/deployment/` changed since the last released version (`git diff <previous release branch
or tag>..HEAD -- scripts/deployment` is not empty), and the release record says which ran and why
(the user, 2026-11-14). *Why:* a backup that was never restored is a hope, not a backup.

**3.3a Keep on the server only what a restore can need.** `wlxjury-release prune`, run at the end
of every successful `reopen` (never after a failed one), keeps the current release and the 4
used before it and the dumps of the 5 newest operations that took one: by count, since releases
are infrequent; an age rule (`keep_backup_days`) is off unless set (settings in
`deployment.json`, user, 2026-11-04). It never deletes what an open operation
names, the dump of the operation that deployed the current release, a rescue dump, or the
previous release of any dump it keeps, so every dump left stays restorable with `restore`; when
it can't tell what is safe, it stops and deletes nothing (deployment.md, "Retention"). The
server's long-term backup is separate. *Why:* release folders and dumps otherwise pile up on a
disk that also holds the database and the image cache; on a host shared with other services, a
full disk stops the database for all of them.

**3.4 Dumps hold personal data.** A production dump is restored only inside an isolated container
and anonymised at once; it never enters a repository or `data/cache` (secure-coding §22,
performance §2.7).

## 4. Deploys and rollback

**4.1 The runbook is deployment.md.** Every `deploy`, `restore` and `reopen` runs inside `tmux`.
`wlxjury-release` (every release since the cutover) logs each step to the operation's own folder,
`/var/backups/wlxjury/<UTC start>-<build>/operation.log`, beside its `record`, `milestones` and, for
a migration, the backup `database.sql.gz`; the folder's name is the operation id. Only the cutover
tool, `wlxjury-deploy.py`, writes `/var/backups/wlxjury/operations.log`. One app instance at a time
(deployment.md, "One app instance"): the in-memory caches and limits assume it.

**4.2 Pre-deploy checks** are the roadmap's
["Before any production deploy"](../plans/2026-11-02-roadmap.md#before-any-production-deploy-of-followups):
the user's manual checks per release and per migration (e.g. `SELECT @@log_bin, @@binlog_format`
before READ COMMITTED writes; V84's backfill size; the infra template's O1 keys). The ZIP is
built only after the user confirms them. The checks of every release are the
[release checklist](../deployment.md#release-checklist).

**4.3 Rollback by milestone.** Before `migration-may-start`, restart and `reopen`. From
`migration-may-start` until reopening, `restore OPERATION` (never just start the previous release:
its readiness can't show a half-applied migration). After `traffic-may-resume`, prefer a forward
fix; a restore then loses later votes and needs `--accept-data-loss` and an explicit decision.
Migrations are forward-only: no Flyway undo, no edits to an applied migration (db §8).

**4.4 Configuration changes travel with the release notes.** Every setting `ProductionConfigSpec`
requires is in the deployed template too; a config change in the repository names what the server
needs (secure-coding §20). Secrets are `${?VAR}` references in `production.conf`, values in the
root-only `runtime.env`.

**4.5 No containers on production now** (the user, 2026-11-04). Production keeps the `sbt dist`
ZIP, systemd, Apache and the host's MariaDB. *Why:* when other services share the host and its
Apache (with its ports and certificate), MariaDB and mail, a container would wrap only the JVM,
and would still need host networking for the database and mail on loopback and for Play's
loopback proxy trust, while Apache and `wlxjury-release` stayed. It would add a runtime,
base images to patch outside the host's package updates (which patch the host's JRE) and image
disk, and wouldn't shorten the outage: migrations still need maintenance and one app instance. If
containers come later (a new server, or a JRE kept apart from the host), the path is **rootful
Podman with a Quadlet unit and `Network=host`** (Quadlet needs Podman 4.4 or later), as its own
change, never as part of an OS upgrade: no daemon, no `docker` group, no published ports (which
can bypass a host firewall), and the generated `wlxjury.service` keeps the unit's ordering and
the deployment hold. For other
deployers, a reference image is queued (roadmap, "Reference container image on GHCR with
provenance"). Evidence: the investigation of 2026-11-04 (options compared: keep and simplify,
Compose with and without MariaDB, Podman Quadlet, Kamal).

## 5. Monitoring and throttling

**5.1 What is watched today.** The application log and Apache's logs on the server; the deploy
tools' readiness checks; root's "Check every round's images" (`round_image_check`) after a
release that touches votes (it must report no drift); monit's alert-only check of the service
and `/health/ready`, quiet while the maintenance flag is on (`conf/monit/wlxjury`, deployment.md,
"Monitoring with monit"; where it is installed). It never restarts the app: systemd does after
a crash, and a restart during a deployment would bypass its hold. There is no external uptime or
metrics monitoring yet.

**5.2 Jobs.** A job's status and Cancel/Retry are on its page; a job that keeps failing shows in
`job.attempts` (claims since its last explicit Retry). A job step that runs out of memory restarts the
JVM (`ExitOnOutOfMemoryError`, `Restart=on-failure`) at most `jobs.maxAttempts` times (5): the
next instance then fails the job with `job.error.attempts` and an ERROR line with its reference
(R198-1, performance §4.2). Retry it only after the cause is fixed.

**5.3 Sign-in throttling is to be fail2ban's** (user decision, S4), planned with the infra plan
(postponed). It needs a `security.auth` failure line with `ip=` for the password form, which
doesn't exist yet (OP4; only the OAuth chooser's `oauth_choose_proof_failed` has one). Its
`ip=` is the client's only while Apache proxies to Play on `127.0.0.1`, because Play trusts
`X-Forwarded-For` only from loopback ([integrations](../integrations/practices.md) §3.2).

**5.4 Watch after a release:** RSS after adding a library with native buffers (performance §4.7),
heap after GC, and the error rate in the first hour.

**5.5 `wlxjury-diag`: what happened, over ssh** (support diagnostics plan §3.4, D-1b). A read-only
CLI the maintainer (and Claude, as the maintainer's own account) runs on the server, with no sudo
and no database login. It ships in each release as `scripts/diagnostics/wlxjury-diag.py` (`sbt dist`
refuses a ZIP without it) and reads the app's API on `http://127.0.0.1:9000` with a personal API
token of the **read-only, from the server only** scope, plus the app log for denials:

- `wlxjury-diag recent [--since 24h]`: the contests with activity in the window (jobs and round
  status changes from `GET /api/diagnostics/recent`, root only; denial counts per contest from
  `/var/log/wlxjury/application.log*`, gzip included), the worst first.
- `wlxjury-diag contest 466 [--verbose] [--names]`: the contest's health report
  (`GET /api/contests/466/health`), warnings and failures first; `--verbose` every check, round
  and import, `--names` who started each import.

Every command prints a few lines and ends with one next command filled with real ids
(`recent` names the worst contest's `contest` command). Contest names, sources and hint texts are
printed with control characters stripped; no email is in its output. It sends the token only to
plain HTTP on a loopback address, follows no redirect, and refuses a token file others can read.

One-time setup, as the maintainer's own account (the token is created on root's profile page, **API
tokens**, access **Read-only, from the server only**; paste it when `cat` waits, then Ctrl-D):

```bash
mkdir -p ~/.config/wlxjury-diag ~/bin && chmod 700 ~/.config/wlxjury-diag
(umask 077; cat > ~/.config/wlxjury-diag/token)
echo jury.wle.org.ua > ~/.config/wlxjury-diag/host
printf '#!/bin/sh\nexec python3 /opt/wlxjury/current/scripts/diagnostics/wlxjury-diag.py "$@"\n' > ~/bin/wlxjury-diag
chmod 755 ~/bin/wlxjury-diag
~/bin/wlxjury-diag recent
```

Ubuntu's `~/.profile` puts `~/bin` on the `PATH` from the next login, so the commands it prints
then run as they are. The host is any name of `WLXJURY_ALLOWED_HOSTS` (the app answers no other `Host`). The wrapper
follows `current`, so each release brings the CLI that matches its API. Revoke the token on the
profile page when it is no longer needed; a revoked or expired token gets a one-line `HTTP 401`
error. `log` (the request and denial lines of one contest, account or reference) comes with D-1c.

## 6. Runtime limits

The JVM is OpenJDK 17, the build's baseline ([deployment.md](../deployment.md), "Java 17 is
required"). It runs with `-Xmx1g -XX:MaxDirectMemorySize=128m -XX:+ExitOnOutOfMemoryError` and
`MALLOC_ARENA_MAX=2` (`/etc/default/wlxjury`); MariaDB has a 256 MiB buffer pool
(`bench/cnf/recommended-60-wlxjury.cnf`, applied 2026-11-24, performance §4.6); pools, dispatchers and job workers have the budget
of performance §4.5 and db §5.6; per-request limits (bcrypt, API tokens, the image proxy) are
secure-coding §18. A new pool, worker count or limit is checked against those budgets.

## 7. Guards

| Rule | Enforced by | State |
|---|---|---|
| 1.1, 1.4 key-value denial line by `accountId` | `SecuredSpec` (denial line), `security.LogHygieneSpec` | enforced |
| 1.5 SQL logged as templates | `SqlLoggingSpec` | enforced |
| 1.5 driver text redacted (R227-1) | a spec logging a duplicate-key error (db §10, proposed 2) | planned |
| 1.6 the release ships the production logback | `sbt dist` (the `Universal / mappings` check), `ProductionConfigSpec`, `test_database_restore` (starts the ZIP with it) | enforced; production uses it once the infra unit points at it |
| 2.1 readiness: local only, schema and READ COMMITTED | `ReadinessControllerSpec`, `ReadinessDatabaseSpec` | enforced |
| 3.3 restore path | `test_database_restore`, `test_release_script` (`deployment.yml`) | enforced on every PR; at a release build `test_database_restore` always, `test_release_script` when `scripts/deployment/` changed |
| 3.3a retention: prune keeps what a restore needs | `test_release_script` (the prune tests) | enforced on every PR; at a release build when `scripts/deployment/` changed |
| The image cache is the service user's and not group- or world-writable | `wlxjury-release deploy` refuses; `test_release_script` | enforced at each deploy |
| 5.1 monit check: alert only, quiet in maintenance | `MonitCheckTest` (with `monit -t`) | enforced on every PR; installing it is the user's step |
| 4.4 production settings | `ProductionConfigSpec`; G14 `ProductionConfigParitySpec` for the infra template | enforced; G14 planned |
| 5.3 fail2ban on sign-in failures | infra plan (S4); the password form's failure line (OP4) | planned (postponed) |
| 5.1 external uptime monitoring | — | planned: not decided |

## 8. Where the setup doesn't follow these yet (for triage)

- **OP1 (1.6, 1.3):** `conf/logback-production.xml` sets no `security.*` logger, so with root at WARN
  the INFO lines of `security.account` (`oauth_*`, invitations) are not written; the OAuth port's
  pre-deploy list asks for `security.account` at INFO (D-0b of the support diagnostics plan adds
  it). The infra template also lacks `coi` until the unit uses the release's file (1.6).
- **OP2 (1.5):** whole exceptions with driver text are logged (db §11 V2).
- **OP3 (5.1):** no external monitoring or alerting; a stopped service is noticed by users. The
  local monit check (`conf/monit/wlxjury`) covers a stopped or unready app where it is installed
  and monit's alerts reach someone. Configuring where monit alerts go is deferred (user,
  2026-11-04: later); until then the check only writes monit's own log.
- **OP4 (1.3, 5.3):** the password sign-in form (`LoginController`) logs no failure line: a
  wrong password only re-renders the form with `invalid.user.or.password`. fail2ban (S4) needs a
  `security.auth` line with the client's `ip=` (and the account id, if any) there first.
- **OP5 (2, 6; low to medium, pre-existing; found in #250's review):** the image cache's start-up
  walk (`LocalImageCacheService.initRegistry`, `Files.walk`) throws `NoSuchFileException` if an
  entry disappears mid-walk, and deletes every `.tmp-` file, in-flight writes included. Across
  instances it can't happen now (blue/green was dropped); within one instance it can: the walk
  races the thumbnail writes of the first requests (`saveResized` writes `.tmp-` then moves). A
  failed walk leaves `registryReady` failed for good, so local image downloads fail until a
  restart, and the unused-sizes cleanup is skipped. Suggested fix: `Files.walkFileTree` with
  `visitFileFailed` returning `CONTINUE`, and delete only `.tmp-` files older than about an hour.
- **OP6 (3.3a, 5.1):** installing the monit check, fixing the image cache folder's owner and
  mode, and the first prune (with `--dry-run`) are deployment steps (deployment.md); the infra
  roles should own them. The rule for a
  shared host: other monit checks there should not restart MariaDB during a deployment; add a
  filesystem space alert beside them.
- **OP7 (3.3a):** prune never deletes rescue dumps (`before-restore-*`) or `wlxjury-deploy`'s
  cutover records (their dumps and config copies). They go by hand after review, or with the
  cutover's retirement (roadmap).

## 9. Contest series (root)

**9.1 What is recorded.** Every change root makes to series and contests through the series
pages is a row of `contest_change` (V109; the contest's or series' **Change log**, newest first)
and a `round.admin` INFO line (§1.3). A bulk assignment's rows share a `batch_id`; an undo is a
batch of `series_unassigned` rows whose `detail.undoOf` names the batch it undid.

**9.2 Assigning existing contests** (the plan's ops step after CY-1). On a production copy first:
open `/admin/series/assign`, read what is proposed and what is left out with why; then on
production confirm in batches of a few proposals each, reading the Series page's batches list
after each. A wrong batch: **Undo batch** (contests changed since are left and listed; move them
one by one on their Series and status page; a series the batch created stays, empty: retire it
if it isn't wanted). A confirmation is all or nothing against the page root saw: if the proposals
changed meanwhile, nothing is assigned. The proposals page reads every unassigned contest's rounds
and their files' categories: on the production copy (V107, 2026-11-20) 912 rounds' 1.35 million
files in 4.4 s, on the blocking pool; its confirmation reads only the ticked contests' files.

**9.3 Retiring.** A contest is retired on its Series and status page, refused while a round is
open, paused (frozen while open), being created or reviewed, or whose creation failed (finish or
cancel it first); the contest's rounds are locked while it is retired. Its members lose access at
once; root still opens it and keeps write access (a read-only root view is a follow-up). To bring it back, un-retire it (refused while another live contest of its series has
its year: move or retire that one first). A retired series keeps its contests live.

**9.4 Back-out.** V109's rollback is in its last comment (the series assignments and the log are
lost); a pre-V109 binary runs on the V109 schema.

**9.5 Series admins (CY-2, V112).** A grant is a row of `series_admin` (one live grant per account
and series: `live_grant_series_id`); each grant, revocation and start-year change is an
`account_change` row on the grantee (`series_admin_granted` with the computed default start year in
`old_value` and the chosen one in `new_value`, `series_admin_revoked`, `series_admin_from_year` with
old and new values; comment `series #<id>`, after a join `series #<id>; #<other account>`) and an
INFO line on `security.roles`: `series_admin_granted account= series= from_year= default_year= by=
root= grantId=`, `series_admin_revoked account= series= grantId= by=` with `chain= top=` or
`reason=account_join|account_retired`, `series_admin_from_year account= series= old= new= by=
grantId=`, `series_admin_moved account= from= series= grantId= from_year= by=`.

- **Grant:** root or a series admin of the series, on the series page (`/series/<id>`, **Add a
  series admin**). Only an active member of one of the series' live contests; never a root account,
  a deleted or retiring account, oneself, or an existing grantee. Root sees each grant a series
  admin added on `/admin/series` (the notices on top, and each series' last 24 hours).
- **Revoke:** root only, on the series' root page (`/admin/series/<id>`); **Also revoke the grants
  this admin added** follows `granted_by` to any depth in one transaction under the series' lock.
  A grant root revoked is granted again by root only. A revocation takes effect on the grantee's
  next request (series reach is read per request).
- **Start year:** root changes it on the same page; it takes effect on the next request, account
  reach included (lowering it widens reach to older contests' members); it doesn't cascade to the
  grants that admin added (the tree lists them). The recomputed default is shown under it.
- **Joins and retirement:** joining accounts moves the joined account's grants to the kept one with
  their start years (a clash on one series keeps the kept account's grant and revokes the other),
  and the grants it added count as added by the kept one; retiring an account revokes its grants.
- **Un-retiring a series** (the maintainer's decision of 2026-11-24): its unrevoked grants come back
  with `confirm_needed_at` set (`series_admin_to_confirm ... reason=series_unretired`): no access and
  no protection, as a revoked grant, until root presses **Confirm** on the series' root page
  (`series_admin_confirmed` in `account_change` and on `security.roles`). Their `granted_by` chains
  are kept; a grant whose granter is still to confirm is refused until the granter is confirmed (a
  revoked granter doesn't block it). An unconfirmed grant can be revoked as any.
- **Protection** (the security review of #140): an account with any unrevoked grant, live or waiting
  for confirmation or of a retired series, is protected: only root edits, links, invites, retires or
  joins it, and in the series' contests only root makes it a Juror or removes or lowers its Admin
  role (plan §3.3, kept by the maintainer). A join moves only live grants (it revokes waiting
  ones and those of a retired series) and carries the joined account's revoked grants to the kept
  one, so a grant root revoked stays root's to give again. Root's **Confirm** is refused if the
  account's email, password, wiki account, root flag, joins, links or sign-in identities changed
  since the grant began waiting (or since the series was last retired before that): revoke it and
  grant again if it is the same person.
- **What a revoke leaves:** it revokes the unused invitations, password and share links the account
  made in the series' contests. It doesn't touch real rows: a real Admin (or other) row the series
  admin holds in a contest stays (root removes it on the users page), and so do the accounts and
  memberships the series admin added while the grant lived, and the contests' settings and rounds
  they changed (read the contests' account change logs and round logs).
- **Pilot back-out:** revoke the grant (with the chain if the pilot added others), then remove any
  real rows listed above that root doesn't want kept.
- **Migration back-out:** V112's rollback is in its last comment (every grant and its log rows are
  lost; an older binary reads no grant, so series admins lose their series access with it).
