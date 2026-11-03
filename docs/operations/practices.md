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

## 1. Log lines

**1.1 One event, one key-value line.** An event name first, then `key=value` fields, untrusted
text quoted by `Secured.logValue` so a line stays single and parseable:
`authorization_denied ref=… accountId=… method=… path=… reason=missing_role`
(`Secured.denialLogLine`), `server_error ref=… method=… path=…` (`ErrorHandler`),
`image_fetch_refused host=…` (`LocalImageCacheService`). *Why:* support and fail2ban match lines
by event and field, not by prose that changes.

**1.2 A reference links what the user saw to the line.** A denial, a refused CSRF token and a
server error show a short reference on the page or in the JSON (`{error, reference}`); the same
`ref=` is in the log line. Users never see the exception (secure-coding §12).

**1.3 Named loggers by concern.** `security.authz` (denials, WARN), `security.account`
(invitations, links, OAuth: `oauth_*`), `security.auth` (today one line:
`oauth_choose_proof_failed accountId=… proof=password ip=…`, a wrong password in the OAuth
account chooser, `OAuthChooserController`; the password sign-in form, `LoginController`, logs no
failure line: OP4), `security.roles`, `security.password`; `coi` (conflict-of-interest
removals, the only record of them, INFO); `readiness`; `services.ApiTokenService` (refused tokens by
prefix). A new class of security event gets a `security.*` logger and a level the production
logback writes.

**1.4 Ids, never personal data.** Accounts by `accountId`/`userId`; no email, name, password,
token or a whole `User`; mailed-link tokens masked in paths (`Secured.logPath`). Rules and guards:
secure-coding §13 (`LogHygieneSpec`).

**1.5 No bound values from the database.** SQL is logged as templates with `?` (`SqlLogging`);
`org.mariadb.jdbc` stays at ERROR because its WARN lines quote values. Driver messages inside
logged exceptions still quote values (`Duplicate entry '<email>'`): R227-1 plans a redacting
converter (db §1.6, §11 V2). Until it lands, a new `logger.error(…, e)` on a database path is a
review item.

**1.6 The production logback lives on the server.** Production starts with
`-Dlogger.file=/etc/wlxjury-runtime/logback.xml`, installed from `wlxjury-infra`'s template, a
copy of `conf/wlxjury/logback.xml`: root WARN, rolling `/var/log/wlxjury/application.log` (50 MB
files, 30 days, 1 GB cap). A logger change in the repository is a pre-deploy item for the server
until the logging plan ships the file with each release. *Why:* the `coi` logger went into the
repository's copy only, so its removal lines were not written in production (2026-10-30).

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

**3.2 A restore is checked before it deletes anything:** checksum and gzip integrity, the
database's identity, free space, and after writes resumed a fresh `before-restore-*` rescue dump
(deployment.md, "Routine deployment").

**3.3 Rehearse restores.** Each deploy and each section of the recovery runbook is rehearsed on an
isolated production copy before relying on it (deployment.md, "Build and rehearse"); CI's
`test_database_restore` and `test_release_script` run the restore path against a real MariaDB and
the packaged ZIP on every PR. *Why:* a backup that was never restored is a hope, not a backup.

**3.4 Dumps hold personal data.** A production dump is restored only inside an isolated container
and anonymised at once; it never enters a repository or `data/cache` (secure-coding §22,
performance §2.7).

## 4. Deploys and rollback

**4.1 The runbook is deployment.md.** Every `deploy`, `restore` and `reopen` runs inside `tmux`;
the tool logs each step to `/var/backups/wlxjury/operations.log`. One app instance at a time
(deployment.md, "One app instance"): the in-memory caches and limits assume it.

**4.2 Pre-deploy checks** are the roadmap's
["Before any production deploy"](../plans/2026-11-02-roadmap.md#before-any-production-deploy-of-followups):
the user's manual checks per release and per migration (e.g. `SELECT @@log_bin, @@binlog_format`
before READ COMMITTED writes; V84's backfill size; the infra template's O1 keys). The ZIP is
built only after the user confirms them.

**4.3 Rollback by milestone.** Before `migration-may-start`, restart and `reopen`. From
`migration-may-start` until reopening, `restore OPERATION` (never just start the previous release:
its readiness can't show a half-applied migration). After `traffic-may-resume`, prefer a forward
fix; a restore then loses later votes and needs `--accept-data-loss` and an explicit decision.
Migrations are forward-only: no Flyway undo, no edits to an applied migration (db §8).

**4.4 Configuration changes travel with the release notes.** Every setting `ProductionConfigSpec`
requires is in the deployed template too; a config change in the repository names what the server
needs (secure-coding §20). Secrets are `${?VAR}` references in `production.conf`, values in the
root-only `runtime.env`.

## 5. Monitoring and throttling

**5.1 What is watched today.** The application log and Apache's logs on the server; the deploy
tools' readiness checks; root's "Check every round's images" (`round_image_check`) after a
release that touches votes (it must report no drift). There is no external uptime or metrics
monitoring yet.

**5.2 Jobs.** A job's status and Cancel/Retry are on its page; a job that keeps failing shows in
`job.attempts`. Until R198-1 is fixed, a job step that runs out of memory restarts the JVM about
once a minute (`ExitOnOutOfMemoryError`, `Restart=on-failure`); cancel it in SQL (performance
§4.2).

**5.3 Sign-in throttling is to be fail2ban's** (user decision, S4), planned with the infra plan
(postponed). It needs a `security.auth` failure line with `ip=` for the password form, which
doesn't exist yet (OP4; only the OAuth chooser's `oauth_choose_proof_failed` has one). Its
`ip=` is the client's only while Apache proxies to Play on `127.0.0.1`, because Play trusts
`X-Forwarded-For` only from loopback ([integrations](../integrations/practices.md) §3.2).

**5.4 Watch after a release:** RSS after adding a library with native buffers (performance §4.7),
heap after GC, and the error rate in the first hour.

## 6. Runtime limits

The JVM runs with `-Xmx1g -XX:MaxDirectMemorySize=128m -XX:+ExitOnOutOfMemoryError` and
`MALLOC_ARENA_MAX=2` (`/etc/default/wlxjury`); MariaDB has a 128 MiB buffer pool, 256 MiB
recommended (`bench/cnf/recommended-60-wlxjury.cnf`, performance §4.6); pools, dispatchers and job workers have the budget
of performance §4.5 and db §5.6; per-request limits (bcrypt, API tokens, the image proxy) are
secure-coding §18. A new pool, worker count or limit is checked against those budgets.

## 7. Guards

| Rule | Enforced by | State |
|---|---|---|
| 1.1, 1.4 key-value denial line by `accountId` | `SecuredSpec` (denial line), `security.LogHygieneSpec` | enforced |
| 1.5 SQL logged as templates | `SqlLoggingSpec` | enforced |
| 1.5 driver text redacted (R227-1) | a spec logging a duplicate-key error (db §10, proposed 2) | planned |
| 1.6 the server's logback equals the repository's | — | planned (logging plan: ship it with each release) |
| 2.1 readiness: local only, schema and READ COMMITTED | `ReadinessControllerSpec`, `ReadinessDatabaseSpec` | enforced |
| 3.3 restore path | `test_database_restore`, `test_release_script` (`deployment.yml`) | enforced on every PR |
| 4.4 production settings | `ProductionConfigSpec`; G14 `ProductionConfigParitySpec` for the infra template | enforced; G14 planned |
| 5.3 fail2ban on sign-in failures | infra plan (S4); the password form's failure line (OP4) | planned (postponed) |
| 5.1 uptime monitoring | — | planned: not decided |

## 8. Where the setup doesn't follow these yet (for triage)

- **OP1 (1.6, 1.3):** `conf/wlxjury/logback.xml` sets no `security.*` logger, so with root at WARN
  the INFO lines of `security.account` (`oauth_*`, invitations) are not written; the OAuth port's
  pre-deploy list asks for `security.account` at INFO. The infra template also lacks `coi`.
- **OP2 (1.5):** whole exceptions with driver text are logged (db §11 V2).
- **OP3 (5.1):** no external monitoring or alerting; a stopped service is noticed by users.
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
