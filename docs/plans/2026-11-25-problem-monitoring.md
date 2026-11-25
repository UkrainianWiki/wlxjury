# Problem monitoring: refusals, errors and failed jobs seen without looking for them (plan, 2026-11-25)

**Status:** draft, for the maintainer's decisions (§9); Q1 and Q2 decided 2026-11-25. Read-only survey of master at `5d72c4d8`
(after the 26.11.16 build); no code changed, no test run. References are to files and functions;
line numbers drift.

**Why.** On production, 2026-11-25 08:05–08:13 CET, an admin's round form was refused 11 times in a
row (`POST /admin/rounds/save` → 400), and a twelfth save at 08:15 was taken as a resubmit of a
round already created at 08:01, so its changes were dropped (production logs, 2026-11-25). Nobody
noticed at the time. It was found by chance in Apache's access log. None of the 11 refusals left an
application log line, so their cause can only be inferred (the UI-path testing plan, draft PR #189,
§0.5, lists the candidates).

**Decided by the maintainer, 2026-11-25:**

- **Monitor, don't find by chance.** Such situations must be detected, not stumbled on.
- **Everything runs in the app on the server.** Detection must not depend on a script someone
  runs on a PC (or over ssh, like `wlxjury-diag`) at their discretion.
- **Nothing is lost when nobody looks.** A fixed 7-day window is not enough, since someone may
  look weekly or less often. Events are kept (Q2, [user] 2026-11-25: stored with no purge for
  now, cleanup decided later; §3.2), and problems are "new since last seen" until marked as seen.
- **History in the database.** Events live in the database, so they are in the regular backups
  and comparable across seasons, not only in rotated logs. Longer access-log retention is a
  one-time server change by the maintainer (sudo): a runbook item here (§7, slice M-5).
- **Root sees it everywhere.** A badge "Problems: N new" on every root page and a root-only
  Problems page.
- **No mail digest now (Q1, [user] 2026-11-25).** Root sees problems through the badge and the
  Problems page only; an opt-in digest is revisited after a season.

**Builds on, does not reopen:**

- **Error rules:** message keys and a logged reference, never request or exception text
  (secure-coding §12; `ErrorHandler`, `CsrfErrorHandler`, `JobError`). What may go in a log line
  (§13) and privacy (§22).
- **The denial line** `authorization_denied` and its reference (`Secured.logDenial`,
  `Secured.denialLogLine`, logger `security.authz`), and `Secured.accountFields` (ids, never the
  email) of the `server_error` and `csrf_failure` lines.
- **The support diagnostics plan**
  ([`2026-11-18-support-diagnostics.md`](2026-11-18-support-diagnostics.md)). Its **D-3**
  (`support_event`, "one row per denial, CSRF refusal, server error, job failure or empty import",
  30 days, not built) is **taken over by this plan** (slice M-2), with more kinds and no purge for
  now (Q2; its deferred DG5 for this table waits for the later cleanup decision). Its health page (D-1a) and
  `GET /api/diagnostics/recent` (D-1b) read the same table once it exists, as D-3 planned. Its
  check **H11** (stuck jobs) and this plan's pattern P5 are one function.
- **The UI-path testing plan** (PR #189, `docs/plans/2026-11-25-ui-path-testing.md`), decided by
  the maintainer. Its Q3 (decided): refused saves are logged in the 26.11.17 refusal-UX PR, as
  `form_refused`; that log item **is** slice M-1 here, built once, in that PR. Its Q6 (decided):
  Back after creating a round lands on the created round's Edit page (§2.3). Its Q7 (decided): a
  refusal that is a wait (`error.exclude.rounds.busy`, `error.previous.round.rebalancing`) shows the
  blocking job's live progress; such refusals are still `form_refused` events, and P1 counts them
  like any other (three waits in 15 minutes is still a stuck admin).
- **Background jobs** (`services.jobs.JobScheduler`, the `job` table, V60) and the app's existing
  timers (`modules.RoundCountsTask` / `RoundCountsTimer`, `modules.LegacyPasswordRetirementTask`).
- **The app never mails automatically** (the existing-accounts plan's unticked "Tell its owner by
  email"; the contest series plan). Any mail here is opt-in (Q1).

## 1. Goals and non-goals

**Goals.**

1. A refused save, a denial, a server error, a refused CSRF token, a failed or stuck job is
   **recorded** in the database at the moment it happens, with enough to act on (who, which
   contest/round/form/job, which fields and message keys, the log reference), and **no
   user-entered values**.
2. An **in-app periodic check** turns recorded events into **problems** by pattern ("3 refused
   saves of one form by one account within 15 minutes"), so one admin's struggle is one problem,
   not 11 rows.
3. Root **sees** new problems on every page (badge), reads them on one page, follows the links to
   the round, contest, job or log reference, and **marks them as seen**. A problem with new events
   after it was seen becomes new again.
4. **Logs and database agree:** every recorded row has its structured log line with the same
   reference (events folded into a row by the flood cap, §3.4, are summed in one
   `support_event_capped` line).

**Non-goals.**

- External uptime or metrics monitoring (monit's alert-only check stays, operations §5.1; an app
  that is down can't report itself).
- Sign-in failures and fail2ban (the `security.auth` line, operations §5.3, is its own plan).
- A per-request log line (support diagnostics D-2) or an activity timeline (D-4).
- Fixing the round form itself (the UI-path plan's refusal-UX PR does).

## 2. What is recorded, and where each hook goes

One recorder, `services.monitoring.EventRecorder.record(event: SupportEvent): Unit`, used by every
hook. It writes the log line (§5) at once, on the caller's thread, and **hands the row to a bounded
in-memory queue** (1,000 events) drained by one writer thread of its own, which inserts in small
batches with `autoSession`. A request thread therefore never waits for the database: when the
database is slow or down (it may be the cause of a 500), the queue fills, further rows are dropped
and counted, and a `support_event_lost count= kinds=` WARN line (once a minute) says so; the log
lines are still written. The writer never throws (error class only in its WARN line). On shutdown
it drains the queue for up to 5 seconds. A flood is capped in memory before the queue (§3.4). In
tests the recorder writes inline (`problems.mode = manual`), so specs read the row right after the
request.

### 2.1 Event kinds

| Kind | When | Fields beyond the common ones (time, kind, contest, account, route template, method, status, ref) | Hook (file / function) | Existing log line |
|---|---|---|---|---|
| `form_refused` | A page form POST answered 400 or 422 with its form shown again (HTML only) | `form` (stable id, e.g. `round`, `contest`, `user`, `criteria`), `round_id` or "new", the **field names** with errors, the **message keys** (field and global), never the values or the keys' arguments | `controllers.FormRefusal` (new, §2.2), called from each form's error branch; `RefusalFilter` (new, `app/modules/`, next to `SessionLifetimeFilter`) as the fallback for any form that doesn't call it | none today (new `form_refused`) |
| `resubmit_dropped` | A round form's token was already used: no new round, the posted changes dropped (`RoundService.Resubmitted`) | `round_id`, `outcome` = `resumed` / `distributing` / `given_up` / `same` / `changed` (`same` / `changed`: §2.3) | `RoundController.saveRound`, the `case resubmitted: RoundService.Resubmitted` branch | `Contest N: Round … was already created from this form` (WARN, quotes the round's description unquoted: replaced, §5) |
| `denied` | Every 403 of a signed-in user, page, JSON or API | `resource`, `reason` (`Secured.denialReason`), required roles | `Secured.logDenial` (the single point: `onUnAuthorized`, `onUnAuthorizedJson` and `api.ApiAuth` all go through it) | `authorization_denied` |
| `server_error` | A 500 | none (the reference finds the stack trace in the log) | `ErrorHandler.onServerError`, after the reference is made | `server_error` |
| `client_error` | A non-404 client error answered by `ErrorHandler` (a body too large, a malformed POST) for a signed-in session | `status`, `message_key` (the page's key, never Play's message text) | `ErrorHandler.onClientError` (marks its result, so `RefusalFilter` skips it) | `client_error` (404 stays DEBUG and unrecorded) |
| `csrf_refused` | A refused CSRF token | none | `CsrfErrorHandler.handle` | `csrf_failure` |
| `job_failed` | A step returned `StepResult.Failed`, a job crashed (`job_crashed`), or was given up after its attempts (`job.error.attempts`) | `job_id`, job kind, `round_id` when the job has one, the `JobError` key | `JobScheduler`: the `StepResult.Failed` branch, `fail` (both `job_crashed` sites: the step's and the preparing one, `job_crashed … preparing=true`, and the attempts), `giveUpIfExhausted`, all via one private `recordEnd(job, error)`, so no failure path bypasses it | `job_failed`, `job_crashed` |
| `job_cancelled` | Cancel (by an account or by a handler) | `job_id`, kind, `by` account | `JobScheduler.cancelOrRefuse` | none (new) |
| `link_refused` | An invitation, set-password or shared link refused (onboarding is personal and mail-free, so a stuck juror is a real problem) | the refusal reason key, the token's id or prefix as already logged | the `account_token_refused`, `shared_link_refused`, `account_link_share_refused`, `account_link_by_provider_refused` sites | those lines |
| `round_access_refused` | A juror refused a stopped, distributing or paused round (support diagnostics D-0b, INFO) | `round_id`, reason | where D-0b logs `round_access_refused` | `round_access_refused` |
| `task_failed` | A periodic in-app task failed a tick | task name, error class | `RoundCountsTimer.guarded`, `LegacyPasswordRetirementTask`, the problem check itself (§4) | `round_counts_tick_failed`, `legacy_passwords_retire_failed` |

Considered and left out: sign-in failures (they carry login names, and belong to the fail2ban
line); 404s (bots); the vote path's 409 `round_closed` (a juror's race with a stop, already shown
to them, `vote-errors.js`); Commons and image-cache retries (high volume; the health page's H19
covers Commons); gallery and large-view vote 400s (script calls, §2.2). Each can become a kind
later with the same recorder. D-3's "empty import" is not an event here: the health page's H4
already reports an import that found 0 files.

### 2.2 One helper, so new forms get it for free

Two layers:

1. **`FormRefusal`** (new, `app/controllers/FormRefusal.scala`):

   ```scala
   // the BadRequest of a refused form, recorded with its fields and keys
   def badRequest(form: String, user: User, contestId: Option[Long], roundId: Option[Long],
                  refused: Form[_])(page: Html)(implicit request: RequestHeader): Result
   // the same for a refusal that isn't a Play form error (reRender's global error)
   def refused(form: String, user: User, contestId: Option[Long], roundId: Option[Long],
               keys: Seq[String], fields: Seq[String] = Nil)(result: Result)(implicit request: RequestHeader): Result
   ```

   It takes from `Form.errors` each `FormError.key` (the field name, `""` for a global error) and
   the **first message key** of `FormError.messages`, and **drops `FormError.args`**: an argument
   may be user text (`files.list.unreadable` takes the first five unreadable lines;
   `RoundController.refusal` passes a refusal's text). It records the event, and marks the result
   with a request attribute (`Result.addAttr(FormRefusal.Recorded, ref)`) so the filter below
   doesn't record it twice.
2. **`RefusalFilter`** (new, registered in `play.filters.enabled`), the fallback for a form that
   doesn't call `FormRefusal`. It records `form_refused` with `form = <route's controller.method>`
   (the route's `HandlerDef`) and `keys = unknown` only when **all** of these hold:
   - a POST of a signed-in session (the session's `AccountId`, as `Secured.accountFields` reads it);
   - **not a script request** (`X-Requested-With` or a non-`navigate` `Sec-Fetch-Mode`, as
     `!Secured.isScriptRequest(request)` tells them; a browser form POST has `Sec-Fetch-Mode: navigate`), so votes, polls and JSON calls never count;
   - the route is not on the **vote list** (the gallery and large-view vote routes, by
     `HandlerDef`), a second guard for clients without those headers: the vote path's 409
     `round_closed` (`GalleryController`) and its 400s are routine juror races, not refused forms;
   - the result is 400 or 422 with an **HTML** body (`text/html`): a form shown again, not an error
     code;
   - the result has no `FormRefusal.Recorded` attribute and was not answered by `ErrorHandler`
     (which marks its results the same way when it records `client_error`), so one 400 is one
     event.

   A new form is therefore covered on its first deploy; calling `FormRefusal` only adds its field
   names and keys. A fallback event shows on the Problems page as "fields unknown: add
   FormRefusal to this form" (P9).

**Slices.** In M-1 (no table yet) `FormRefusal` only writes the `form_refused` line; M-2 adds the
recorder call inside it. `badRequest` and `refused` return the same `Result` in both, so the call
sites don't change between slices. The filter comes with M-2.

**Call sites to convert** (approximate counts of `BadRequest` / `formWithErrors` lines in
`app/controllers` on master): `RoundController` (about 11: the `formWithErrors` branch and
`reRender` in `saveRound`; the select-round and other small 400s stay fallback-only),
`ContestController` (about 6), `UserController` (about 23), `CriteriaController`, `RoundJurorsController` (2), `AddFromContestController` (2),
`SeriesController`, `DuplicateAccountsController`, `ImageController` (3), `AccountJoinController`
(2). `LoginController` (6) is signed out: the filter skips it, and it isn't converted (sign-in
failures are out of scope). The `GalleryController` and `LargeViewController` 400s are script
calls (votes): neither converted nor recorded by the filter.

### 2.3 A dropped resubmit: a double click or a lost edit

`Resubmitted.messageKey` is `round.resubmitted` both for a double click (the same form posted twice)
and for the 2026-11-25 case (Back, edit, Save: the edits dropped). To tell them apart,
`RoundController` compares the posted form with the one the round was created from: when a round is
created from a token, a hash of the posted settings (the bound `EditRound`, with the token and the
"Add files" list's order normalised) is kept **in memory only** (a bounded map, token → hash,
1 hour; never stored or logged); on a resubmit with that token the new hash is compared. Equal:
`outcome=same` (a double click, no problem). Different: `outcome=changed` (edits lost). After a
restart the hash is gone and the outcome is `changed` (the safe side).

The maintainer decided (UI-path plan, Q6) that Back after creating a round lands on the created
round's Edit page with "Round N has been created. You can change it here." and never shows the
stale form. After that fix `changed` should be rare; when it happens the fix has a gap, which is
exactly what P2 should report.

## 3. Storage

### 3.1 Tables

One migration, `V<next>__Support_events_and_problems.sql`. **The number is assigned at build
time:** master is at V113, #178 takes V114, lineage slice A may take V115, so this is likely V116.
Both tables are InnoDB (operations §3.1a) and carry **no foreign keys** (an event outlives a
deleted round or a retired account; a purge must not wait on other tables' locks).

```sql
CREATE TABLE support_event (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  created_at    DATETIME(3)  NOT NULL,
  kind          VARCHAR(32)  NOT NULL,           -- §2.1
  contest_id    BIGINT       NULL,
  round_id      BIGINT       NULL,               -- NULL with form_refused and is_new_round = 1: "new"
  is_new_round  TINYINT(1)   NOT NULL DEFAULT 0,
  job_id        BIGINT       NULL,
  account_id    BIGINT       NULL,               -- never the email
  form          VARCHAR(64)  NULL,               -- form id, or controller.method for the fallback
  route         VARCHAR(160) NULL,               -- the route template (/admin/rounds/:id), never the path
  method        VARCHAR(8)   NULL,
  status        SMALLINT     NULL,
  reason        VARCHAR(64)  NULL,               -- denial reason, JobError key, refusal reason key
  fields        VARCHAR(512) NULL,               -- comma-separated field names
  message_keys  VARCHAR(512) NULL,               -- comma-separated message keys
  ref           CHAR(8)      NOT NULL,           -- the log line's reference
  repeat_count  INT          NOT NULL DEFAULT 1, -- §3.4
  problem_id    BIGINT       NULL,
  KEY support_event_created (created_at),
  KEY support_event_kind (kind, created_at),
  KEY support_event_contest (contest_id, created_at),
  KEY support_event_account (account_id, created_at),
  KEY support_event_problem (problem_id),
  KEY support_event_ref (ref)
);

CREATE TABLE support_problem (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  pattern        VARCHAR(48)  NOT NULL,          -- P1..P11, §4.2
  scope_key      VARCHAR(191) NOT NULL,          -- e.g. form=round;account=12;contest=631
  severity       VARCHAR(8)   NOT NULL,          -- warn | fail
  contest_id     BIGINT       NULL,
  round_id       BIGINT       NULL,
  job_id         BIGINT       NULL,
  account_id     BIGINT       NULL,
  first_event_at DATETIME(3)  NOT NULL,
  last_event_at  DATETIME(3)  NOT NULL,
  event_count    INT          NOT NULL,
  detected_at    DATETIME(3)  NOT NULL,
  seen_at        DATETIME(3)  NULL,
  seen_by        BIGINT       NULL,              -- the root account that marked it
  KEY support_problem_scope (pattern, scope_key, last_event_at),
  KEY support_problem_last (last_event_at)
);
```

A problem is **new** when `seen_at IS NULL OR last_event_at > seen_at`: new events after it was
seen bring it back, so nothing is lost when nobody looks. Seen is shared by all root accounts
(`seen_by` shows who), not per account (Q5).

### 3.2 Retention (Q2, decided)

**Decided [user] 2026-11-25:** "just store them now, think about cleanup later".

- **Events: kept, no purge for now.** No purge is built in M-2. The retention is decided later,
  once real volumes are known (§3.3).
- **Problems: kept** (a few per week; each keeps its counts and times, so a later purge of events
  doesn't lose the season comparison).
- **A later cleanup stays easy:** `created_at` is indexed and there are no foreign keys, so a purge
  is a batched `DELETE … WHERE created_at < ? LIMIT 5000` run by the problem check (§4), with no
  schema change. Options to choose from then: 90 days, 400 days (one season and the same weeks a
  year later), or by kind (e.g. `denied` shorter). "The contest's lifetime" doesn't fit: contests
  are reused across years (support diagnostics H21), and denials and server errors often have no
  contest.

### 3.3 Size

The real rates are unknown until M-2 runs; the access log of 2026-11-25 is the only sample. An
estimate:

- **Row:** ~250 bytes with its indexes counted roughly twice: ~0.5 KB.
- **Typical day** (a season day: a few denials, rare 500s, a handful of refused saves and job ends):
  under 200 events → ~0.1 MB/day, ~40 MB a year without a purge.
- **Worst day without the cap** (a bot posting with stale CSRF tokens): tens of thousands. The cap
  (§3.4) keeps it to one row per minute per fingerprint.

M-2's PR adds a count per kind per day to the Problems page's Events tab, with the table's size.
The retention decision (later) is made from those figures, earlier if a kind dominates or the table
passes ~500 MB.

### 3.4 Flood cap

An in-memory map per `(kind, account or "-", route, form, reason)` allows at most 5 rows per minute;
further events in that minute increment `repeat_count` of the last row (one `UPDATE`) and write no
log line beyond a `support_event_capped` summary when the minute ends. Restarting the app resets
the map; that only lets a few more rows through.

### 3.5 Privacy and backups

- **Ids, keys and names of fields only.** Account id, never the email; field names and message keys,
  never values or message arguments; the route template, never the path or query; no IP, no user
  agent, no request or exception text. Secure-coding §13 and §22 gain the table; `LogHygieneSpec`
  gains the new lines.
- **Who reads it:** root (§6), and, if Q3 says so, a contest's admins for their contest's events.
- **Backups:** both tables are in the nightly dump and in each release's pre-migration dump
  (operations §3.1), so history survives a restore and a server move. At ~40 MB a year they don't
  change the dump time noticeably. A restore brings back the events up to the dump; the gap since
  is in the application log.
- **Contest export and archive snapshots** don't include these tables (they hold account ids of
  other contests' members and are support data, not contest data).
- **Retiring an account** keeps its id in events (the account row no longer holds the email after
  retirement); until the later cleanup decides otherwise.

## 4. Pattern detection: an in-app periodic check

### 4.1 Where it runs: a timer, not a job

`modules.ProblemCheckTask`, bound eagerly in `AppModule` after the migrations, like
`RoundCountsTask`, on a `RoundCountsTimer` of its own (one daemon thread, a tick every
`problems.interval`, default 5 minutes, `guarded` so a failed tick doesn't cancel the next).
`problems.mode = manual` in `test/resources/application.conf`; specs call `ProblemCheck.runOnce()`.

**Why not a `JobScheduler` job.** Jobs are for long work split into steps, with leases,
per-contest turns, Cancel and Retry; a check is one short read and a few writes every few minutes,
with nothing to cancel or retry. And the check must see **jobs that are stuck or failing**: if the
job scheduler itself stops claiming (a poll failing, a lease never released), a check that is a job
would stop with it. A separate timer keeps it independent, as the round-counts recompute is.

**It watches itself.** Each tick records its end time in memory. If the last successful tick is
older than three intervals, the root badge shows "Problem check not running" instead of a count,
and the Problems page says when it last ran. A failed tick records a `task_failed` event (if the
database takes it).

One server runs the app today. If a second instance ever runs, the tick takes a
`GET_LOCK('wlxjury_problem_check', 0)` and skips when another holds it.

### 4.2 Patterns

Each tick reads events since the last tick (minus the longest window) and the `job` table, and for
each match creates a problem or extends an open one: the same `pattern` and `scope_key` with
`last_event_at` within 24 hours → `last_event_at`, `event_count` updated, its events'
`problem_id` set. A match after 24 hours of quiet is a new problem.

| # | Pattern | Default rule | Severity | Default on (Q4) |
|---|---|---|---|---|
| P1 | **Refused saves of one form** | ≥ 3 `form_refused` of one form by one account within 15 minutes (the 2026-11-25 case: it fires at the third, 08:06) | warn | on |
| P2 | **Resubmit dropped** | any `resubmit_dropped` with outcome `changed` (the 08:15 save: edits lost; rare once the UI-path Q6 fix ships) or `given_up`; `same` (a double click) makes no problem | warn | on |
| P3 | **Server errors** | any `server_error`, grouped by route template per 24 hours | fail | on |
| P4 | **Job failed** | any `job_failed` (failed, crashed, given up after attempts) | fail | on |
| P5 | **Job stuck** | from the `job` table, no event needed: queued longer than 30 minutes while no job of its kind runs, or running with `heartbeat_at` older than twice the lease. One function with support diagnostics H11 | fail | on |
| P6 | **Denial burst of one account** | ≥ 5 `denied` of one account within 15 minutes | warn | on |
| P7 | **CSRF refusals of one account** | ≥ 3 `csrf_refused` of one account within 15 minutes (a page left open across a deploy or a session change) | warn | on |
| P8 | **Link refused** | ≥ 2 `link_refused` for one token or account within 24 hours (someone can't get in) | warn | on |
| P9 | **Fallback refusals** | any `form_refused` with `keys = unknown` (a form without `FormRefusal`) | warn | on |
| P10 | **Jurors on a stopped round** | ≥ 5 `round_access_refused` of one round within 1 hour | warn | off |
| P11 | **In-app task failing** | ≥ 2 `task_failed` of one task within 1 hour | fail | on |
| — | **Denials across a contest** | ≥ 20 `denied` in one contest within 1 hour | warn | off |
| — | **Jobs cancelled** | `job_cancelled`: recorded, shown on the Problems page's Events tab, no problem (an admin's Cancel is a normal action) | — | off |

Thresholds are settings (`problems.patterns.p1.count`, `.window`, `.enabled`), so they can be tuned
on production without a release. Exact rules are tuned in M-3 against the first weeks of M-2's
events.

## 5. Logging: one structured line per event

Each recorded row has its WARN line (INFO for `round_access_refused` and `job_cancelled`, as today)
with the row's `ref`, key-value, untrusted text through `Secured.logValue`, ids through
`Secured.accountFields`:

```
form_refused ref=3f9a02c1 form=round contestId=631 roundId=new accountId=1234 status=400 fields=categoryBox1,topImages keys=error.category.first.required,error.min
resubmit_dropped ref=0b77e5d2 form=round contestId=631 roundId=1991 accountId=1234 outcome=dropped
```

- **Existing lines stay as they are** (`authorization_denied`, `server_error`, `csrf_failure`,
  `client_error`, `job_failed`, `job_crashed`, the link refusals): the recorder writes the row with
  the reference the line already carries and writes no second line.
- **New lines:** `form_refused`, `resubmit_dropped`, `job_cancelled`, `support_event_lost`,
  `support_event_capped`, `problem_detected ref= pattern= scope= count=` (one per new problem; the
  ref is the problem's first event's).
- **Replaced:** `RoundController`'s `Contest N: Round … was already created from this form` (it
  quotes the round's description without `logValue`) becomes `resubmit_dropped`.
- **Logger:** `support.event`, at INFO in the production logback (`conf/logback-production.xml`,
  support diagnostics D-0a), so the lines are kept even if a logger is turned down.
- **Refused form pages** may show the reference small under the error ("Reference 3f9a02c1"), as
  the 403 and 500 pages do; that belongs to the UI-path plan's refusal-UX PR and is offered to it,
  not decided here.

## 6. UI

### 6.1 The badge

In `views/navbar.scala.html`, for root only (`user.hasRole(User.ROOT_ROLE)`): **"Problems: N new"**
linking to `/admin/problems`, hidden when N = 0, red when any new problem is `fail`. "Problem check
not running" replaces it when §4.1's watchdog trips. N comes from
`ProblemCount.newCount()`: one `COUNT(*)` on `support_problem` (a small table), cached in memory for
30 seconds and refreshed by each tick, so a root page costs no query in most requests.

### 6.2 The Problems page

`GET /admin/problems` (`controllers.ProblemsController`, `withAuthOn(blocking)(rolePermission(Set(User.ROOT_ROLE)))`):

- **Filters:** new / seen / all (default new), contest, pattern, severity, time range; paged.
- **Each problem:** pattern name (message key), contest (link to the contest and its health page),
  round (link to its statistics or review page), job (link to its page), account (name as on the
  Users page; the email masked, `OAuthChooserController.maskEmail`), first and last time, count,
  for P1 the field names and message keys **with the field labels** from the form's messages
  ("Media filtering → Category 1: required"), and the log references.
- **Its events**, expandable: time, kind, route, status, keys, reference.
- **Mark as seen** per problem and "Mark all shown as seen" (POSTs with the CSRF token, `seen_at`,
  `seen_by`). **Mark as new** undoes it.
- **Events tab:** the raw events with the same filters, for kinds that make no problem (jobs
  cancelled, a single denial).
- A line at the top: when the check last ran, and the retention ("events are kept; no cleanup yet").

### 6.3 Contest admins (Q3)

Root only in the first slices. If Q3 says yes, the contest health page (support diagnostics D-1a,
`/admin/contests/:id/health`, already admins and root) gets a **Problems** section for that contest:
only its problems and events with `contest_id` = the contest, only of its members, no `denied` of
other contests, no `server_error` (root support), the email masked. No badge for admins.

## 7. Rollout slices

| Slice | Content | Size | When |
|---|---|---|---|
| **M-1** | `FormRefusal` (keys and field names, arguments dropped), used only in `RoundController.saveRound`'s two refusal branches (`formWithErrors` and `reRender`), and the `resubmit_dropped` line with §2.3's in-memory form hash (`same` / `changed`), replacing the unquoted resubmit line. **Log only: no table, no migration, no filter.** The line is named `form_refused`; the UI-path plan (#189) is aligned to that name, and M-1 *is* its refusal-UX log item, not a second logger | S (≈ 1 day) | **26.11.17**, inside the UI-path refusal-UX PR (d) |
| **M-2** | The migration (both tables), `EventRecorder`, the flood cap, `RefusalFilter`, the hooks of §2.1 (`logDenial`, `ErrorHandler`, `CsrfErrorHandler`, `JobScheduler`, link refusals, timers), `FormRefusal` in the other forms, secure-coding §13/§22, `LogHygieneSpec`. Takes over support diagnostics D-3 | M (2–3 days) | after lineage slice A (its migration number then) |
| **M-3** | `ProblemCheckTask` and its watchdog, patterns P1–P11 with settings, `problem_detected`; P5 shared with H11 | M (2–3 days) | right after M-2 |
| **M-4** | Badge, Problems page, mark seen/new, filters, links; negative authorization specs | S–M (2 days) | with M-3 or right after |
| **M-5** | Runbook: Apache access-log retention for the jury vhosts (below); operations §5 "What is watched" gains the Problems page | S (half a day + the maintainer's sudo step) | any time; best before the next season |
| later | Contest admins' section (Q3) | S (1 day) | if Q3 = yes |
| later | Opt-in digest (Q1) | S–M (1–2 days) | not now (Q1 decided); revisit after a season |

About **8–10 days** for M-1 to M-5. M-1 has no migration, so it ships in 26.11.17 without
touching the migration order. M-2 to M-4 are one feature; they can ship together in one release
after lineage slice A, before or alongside regional winners (Q6).

**M-5 runbook: access-log retention (maintainer, sudo, one time).** Ubuntu's
`/etc/logrotate.d/apache2` rotates daily and keeps 14. The plan: a stanza of its own for the jury
vhosts' access and error logs, before the generic one, keeping the period decided in Q7:

```
/var/log/apache2/jury*.log {
    daily
    rotate 90
    compress
    delaycompress
    missingok
    notifempty
    sharedscripts
    create 640 root adm
    postrotate
        if systemctl is-active --quiet apache2; then systemctl reload apache2 > /dev/null; fi
    endscript
}
```

The file names and the period are filled from the server (`ls /var/log/apache2/`) and Q7 when M-5
is built, then the same file goes into the infra repo's playbook so a rebuilt server keeps it.
Check: `sudo logrotate -d /etc/logrotate.d/apache2-jury` (dry run) and, the next day, one rotated
file more. The app's own log keeps 30 days / 1 GB (support diagnostics §7), unchanged: the
database holds the history.

## 8. Tests

- **M-1** (`controllers.RoundSaveRefusalLogSpec`): a new round's save with a bad category box and
  `topImages = 0` gives one `form_refused` line with `form=round roundId=new`, the field names and
  the keys; a distinctive value posted in the category box and in "Add files" appears **neither in
  the line nor anywhere in the captured log** (`LogHygieneSpec`'s capture); a `files.list.unreadable`
  refusal logs its key without the lines; an edit's refusal logs its round id; a resubmit logs
  `resubmit_dropped` and not the description.
- **M-2:**
  - `services.monitoring.EventRecorderSpec`: a row per kind with the expected columns; the cap
    (the sixth event in a minute increments `repeat_count`); a failed insert doesn't throw and logs
    `support_event_lost`.
  - `controllers.FormRefusalSpec`: a refused save creates a row with the field names and keys and
    no posted value (the row's every column checked for the value); the filter records a 400 of a
    form without `FormRefusal` once, with `keys=unknown`, and a form with it once, not twice; a
    signed-out 400 records nothing.
  - Denial: a juror's request to an admin page creates a `denied` row whose `ref` is the one on the
    403 page; the same through `/api` with a token.
  - Server error and CSRF: a route made to throw creates `server_error` with the page's reference;
    `CsrfTestApp.withCsrfApp` with a bad token creates `csrf_refused`.
  - Jobs: a handler returning `Failed`, one throwing, and one given up after attempts each create
    `job_failed` with the key; Cancel creates `job_cancelled` with `by`.
  - Service and controller specs use truncation (`BeforeEach(SharedTestDb.truncateAll())`), since
    the recorder writes with `autoSession` (CLAUDE.md, Database Tests).
- **M-3** (`services.monitoring.ProblemCheckSpec`): three refused saves of one form by one account
  within 15 minutes make one P1 problem, two don't, three by two accounts don't; a fourth extends
  it; one after 24 hours of quiet makes a new one; a seen problem with a new event is new again;
  P5 on a queued job with no running job of its kind and on a stale heartbeat; a disabled pattern
  makes nothing; a tick that throws leaves the next tick running and trips the watchdog after three
  intervals.
- **M-4** (`controllers.ProblemsPageSpec`), with the negative authorization cases:
  - root sees the page, the badge with the right count, and can mark seen and new;
  - **an admin, an organizer and a juror** each get **403** on `GET /admin/problems` and on the
    mark-seen POST, with a `denied` row; signed out gets the login redirect (page) or 401 (script);
  - no badge on any page for admin, organizer or juror (rendered navbar checked);
  - the mark-seen POST without a CSRF token is refused (router test with CSRF on);
  - the page shows masked emails and no posted values.
- **M-5:** none (server configuration); its dry run is in the runbook.

## 9. Questions for the maintainer

One decision each. A recommendation is this plan's, open to the maintainer.

1. **Q1. A mail digest to root?** **Decided [user] 2026-11-25: no digest now; badge and
   Problems page only; revisit after a season.** The question as asked: the app never mails automatically. The option: a daily digest of
   new problems, sent only to root accounts that tick "Mail me new problems daily" (unticked by
   default, on root's profile page), with the problems' kinds and counts and a link, no names or
   emails of others. Without it, problems are seen only when root opens the app; with it, a quiet
   week still reaches root. Mail delivery is unreliable here (admins are onboarded personally).
   Recommendation was: not now, the badge for a season first (taken).
2. **Q2. How long are events kept?** **Decided [user] 2026-11-25: "just store them now, think
   about cleanup later": no purge for now (§3.2).** The question as asked: options 90 days; 400
   days; the life of the database (never purged). 90 days loses last season by the next; never purging grows without bound and
   keeps account ids indefinitely. Problems are kept in every option. Recommendation was 400 days; the cleanup is
   to be decided later from real volumes.
3. **Q3. Do contest admins see their own contest's problems?** Options: root only; also a contest's
   admins, on its health page, limited to that contest's events (§6.3). Admins would see their own
   jurors' struggles (refused links, a juror on a stopped round) without asking root; the risk is
   noise and showing one member's refusals to the contest's admins, which the health page's member
   view already allows. **Recommendation: root only for M-4; add the admins' section after a season,
   if root finds the problems would have been better handled by the contest's admins.**
4. **Q4. Which patterns are on by default?** §4.2's table: P1–P9 and P11 on; P10 (jurors on a stopped
   round) and denials across a contest off, as likely noise. **Recommendation: as in the table**,
   tuned after M-2's first weeks of events.
5. **Q5. Is "seen" shared by all root accounts or per account?** Shared: one person marks it, every
   root sees it handled, with who marked it. Per account: each root sees everything once.
   **Recommendation: shared** (one or two root accounts, and the point is "someone looked").
6. **Q6. Where do M-2 to M-4 go in the queue?** After 26.11.17's small fixes (M-1 in them) and
   lineage slice A: before regional winners, or after. Before: the next season's first problems are
   caught. After: regional winners ships sooner. **Recommendation: before regional winners**, since
   the 2026-11-25 case shows the gap is live now.
7. **Q7. How long are the jury vhosts' Apache access logs kept (M-5)?** Options: 14 days (Ubuntu's
   default, today); 90 days; 400 days. The access log has IP addresses, so a longer period keeps
   more personal data (secure-coding §22); the database events (no IP) carry the long history.
   **Recommendation: 90 days**, compressed: enough to read the request lines around a problem
   found up to a season later in the Problems page.
