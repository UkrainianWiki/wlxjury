# PR review triage: CodeRabbit comments on the stack (2026-11-03)

This document collects review comments on the stacked PRs (#184 to #237, see
[the PR series](2026-11-03-pr-series.md)) that are valid, are not fixed later in
the stack, and are not covered by an existing plan. Replies on GitHub link to the
entries here.

Each comment gets one of four classes:

1. **Fixed later in the stack**: the reply names the first later PR whose tip no
   longer has the problem.
2. **Already planned**: the reply links the plan section that covers it.
3. **Not fixed and not planned**: an entry below, with the plan or batch that
   should take it.
4. **Not applicable or a false positive**: the reply gives the reason.

## Open entries

These come from our own stack-aware review of all 54 PRs (2026-11-03). Each
reviewer checked whether a problem is fixed later in the stack or already
planned, and flagged only what is neither. There are 8 entries in 7 PRs, none
High or Medium. File:line is at the PR's tip, and at the final tip `8b246ace`
where it moved.

### R198-1: A job that crashes the JVM is retried forever

- **PR:** #198 (15, tip `95e4b785`). `app/services/jobs/JobScheduler.scala:181-185`
  (`work`, `catch { case NonFatal(e) ... }`, likewise `fetch`) and
  `app/services/jobs/Job.scala:183-185` (`claim`: `attempts = attempts + 1`,
  which nothing reads).
- **Severity:** Low.
- **At the final tip:** still there (`JobScheduler.scala:221-237` `work`,
  `:208-219` `fetch`; `Job.scala:192-198` `claim`).
- **Failure scenario:** production runs `-XX:+ExitOnOutOfMemoryError` with
  `Restart=on-failure`. A distribution or import step that runs out of memory
  exits the JVM without `requeueOwned`; after the 60 s lease the restarted
  instance claims the job again and runs out of memory again. The site restarts
  about once a minute until the job is cancelled in SQL (the UI's Cancel is
  unreachable). Without `ExitOnOutOfMemoryError`, the `Error` escapes `work`
  before its bookkeeping, so the kind's slot stays taken and the heartbeat keeps
  the job "running" until a restart.
- **Suggested fix:** in `claim` (or `poll` after it), fail a job whose `attempts`
  exceeds a limit (for example 3 claims without progress) with a `JobError` key
  such as `job.error.crashed.repeatedly`; do the bookkeeping in `work` and
  `fetch` in a `finally`.
- **Placement:** a new item in section 1 "Bugs" of
  [`2026-11-01-open-followups.md`](2026-11-01-open-followups.md), after the
  background-distribution items; also note it by the lease description in
  `2026-10-07-background-distribution.md`.

### R198-2: Duplicate round numbers on concurrent creation

- **PR:** #198 (15, tip `95e4b785`). `app/services/RoundService.scala:112`:
  `val number = dao.countByContest(round.contestId) + 1`, read outside the
  transaction, with no unique `(contest_id, number)`.
- **Severity:** Nit.
- **At the final tip:** still there (`RoundService.insert`, line ~119).
- **Failure scenario:** two admins create rounds of one contest at once (as
  `ConcurrentRoundCreationSimulation` does); both read N and both rounds are
  numbered N+1 in the rounds list and flash messages. A deleted round's number is
  reused, since it is a count, not a maximum. Cosmetic: the number is shown,
  never looked up.
- **Suggested fix:** in the transaction, after `Round.lock` on the contest's
  rounds, use `COALESCE(MAX(number), 0) + 1`.
- **Placement:** a new item in section 4 "Cleanup" of
  [`2026-11-01-open-followups.md`](2026-11-01-open-followups.md).

### R203-1: A pasted file list over 100 KB is refused

- **PR:** #203 (20, tip `8a05dc37`). `app/views/editRound.scala.html:67-71`
  (the `fileList` textarea) and `app/controllers/RoundController.scala:182`
  (`saveRound`, default parser); the contest import list too
  (`app/controllers/ImageController.scala:53`, `importImages`).
- **Severity:** Low.
- **At the final tip:** still there. Nothing sets
  `play.http.parser.maxMemoryBuffer` (default `100k`, which also sizes the CSRF
  filter's `body.bufferSize`); `saveRound` is at `RoundController.scala:254`.
- **Failure scenario:** an organizer pastes about 450 Ukrainian titles. URL
  encoding writes each Cyrillic letter as 6 bytes, so the body passes 100 KB.
  Play answers 413 (or the CSRF filter refuses the form if the token comes after
  the list), and the form and the list are lost. Splitting the list helps
  little: a second "Add files" is refused while the first job runs.
- **Suggested fix:** take long lists from sources that don't go through the
  form body (see the placement). Interim stopgap, if organizers need long lists
  before that lands: give `saveRound` and `importImages`
  `parse.formUrlEncoded(maxLength = 2.MB)` (or raise `maxMemoryBuffer` and the
  CSRF `body.bufferSize` to about 2 MB), cap the line count with a form error,
  and add a spec that posts a 150 KB list.
- **Placement (decided):** (b) a change to an existing plan,
  [`2026-11-02-montage-rounds-parity.md`](https://github.com/UkrainianWiki/wlxjury/blob/stack/54-mariadb-10-11/docs/plans/2026-11-02-montage-rounds-parity.md#3-gaps):
  extend gap row 24 "CSV / sheet / gist URL source" (and with it
  [order item 9](https://github.com/UkrainianWiki/wlxjury/blob/stack/54-mariadb-10-11/docs/plans/2026-11-02-montage-rounds-parity.md#4-order-after-the-two-step-rounds),
  "Import: checked report, URL sources…") to cover these list sources for a
  round's "Add files" and for the contest import: a file upload (a text or CSV
  file); a wiki page, on Commons or another wiki; a Google Sheets or gist URL.
  All of them avoid the form-body limit. The Montage plan itself is updated when
  that batch is planned.

### R216-1: Thumbnail URL export uses only the configured host

- **PR:** #216 (33, tip `04545c8a`). `app/controllers/GalleryController.scala:535`
  (`config.getOptional[String]("wlxjury.host").getOrElse(...)`, used by
  `resizedImagesUrls` for `/thumb_urls`).
- **Severity:** Low.
- **At the final tip:** still there (`GalleryController.scala:548`).
- **Failure scenario:** production serves several names
  (`WLXJURY_ALLOWED_HOSTS`), and `wlxjury.host` is `https://jury.wle.org.ua/`.
  An admin signed in on another name exports thumbnail URLs; every line points
  at jury.wle.org.ua, whose proxy needs a session, while the exported cookie is
  scoped to the admin's host. wget with `--load-cookies` gets 401 on every line.
  Plan item 1.4 asked to decide the export's host, which was not decided for
  more than one host.
- **Suggested fix:** choose the host as `UserService.linkUrl` does (the request's
  host if it is one of the exactly named `wlxjury.allowedHosts`, otherwise
  `wlxjury.host`), in a shared helper; add a `ThumbnailUrlsExportSpec` case with
  a second allowed host.
- **Placement:** a new item after
  [`2026-11-01-open-followups.md`](2026-11-01-open-followups.md) (a B1 1.4
  follow-up), or with the next small multi-host or link-URL fix.

### R225-1: An email over 190 characters gives a server error

- **PR:** #225 (42, tip `88b55929`). `app/controllers/UserController.scala:605`
  (the `"email"` mapping of `editUserForm`); also the `setupForm` `"login"`
  mapping in `app/controllers/LoginController.scala`.
- **Severity:** Low.
- **At the final tip:** still there (`UserController.scala:624`:
  `Constraints.emailAddress()` only, no length limit).
- **Failure scenario:** a user changes their email to a valid address over 190
  characters (a long plus-tag). `UserService.requestEmailChange` inserts into
  `account_token.email VARCHAR(190)`, which fails under `STRICT_TRANS_TABLES`
  ("Data too long"): a 500 page and an ERROR line instead of a form error. The
  same on an admin's address fix (`users.email VARCHAR(190)`), the add row or
  import, and `/setup`. Nothing is corrupted.
- **Suggested fix:** `maxLength(190)` on the edit form's `email`, the setup
  form's `login` and the add/import email parsing, with `error.maxLength`; a spec
  where a 191-character address gets a 400 with the field error and nothing is
  written.
- **Placement:** a new item after B12 in
  [`2026-11-01-open-followups.md`](2026-11-01-open-followups.md), "form field
  lengths match their columns" (other free-text fields such as `fullname` in the
  same pass).

### R227-1: Database error messages with bound values still reach the log

- **PR:** #227 (44, tip `cd5856a7`). `app/ErrorHandler.scala:53`
  (`logger.error(..., exception)`) and `app/services/jobs/JobScheduler.scala`
  (`:375`, `:442`) log the full exception; `conf/wlxjury/logback.xml:25` says the
  app logs failures without the values.
- **Severity:** Low.
- **At the final tip:** still there (`ErrorHandler.scala:63`, `logback.xml:27`).
- **Failure scenario:** an unhandled `SQLIntegrityConstraintViolationException`
  (a uniqueness race on `users.email`) reaches `onServerError`; its message
  "Duplicate entry 'alice@example.org' for key ..." is logged at ERROR. That is
  the leak N11 lists as fixed in the app; the driver logger at ERROR removes only
  the driver's own copy.
- **Suggested fix:** for a `java.sql.SQLException` in the cause chain, log
  `SqlLogging.describe(e)` and the stack frames without messages (a scrubbed
  wrapper `Throwable` or a logback `throwableConverter`); or correct the comment
  and the register. Add a spec where a forced duplicate-key error logs no bound
  value.
- **Placement:** a new item after S5 in
  [`2026-11-02-security-resolution.md`](2026-11-02-security-resolution.md),
  "N11 residual: exception messages in the server_error and job logs".

### R229-1: V80 undo does not sign out pre-S3 cookies

- **PR:** #229 (46, tip `61a2ba05`).
  `conf/db/migration/default/V80__Session_upgrade_window.sql:9` says that after
  `DROP TABLE session_upgrade_window` "all are signed out", but
  `app/modules/SessionUpgradeWindow.scala:36` maps the read failure
  (ER_NO_SUCH_TABLE) to `Unknown`, and the filter keeps such a cookie unchanged.
- **Severity:** Nit.
- **At the final tip:** still there.
- **Failure scenario:** an operator follows the documented undo. Every pre-S3
  cookie, a leaked one replayed included, keeps working indefinitely, and each
  request queries the database and logs `session_upgrade_window_unreadable`.
- **Suggested fix:** treat ER_NO_SUCH_TABLE (1146 / 42S02) as `Closed`, as S6's
  `LegacyPasswordRetirementJdbc.secondsLeft` does; or change the undo note to
  `DELETE FROM session_upgrade_window` (no row means `Closed`).
- **Placement:** a new item after S3 in
  [`2026-11-02-security-resolution.md`](2026-11-02-security-resolution.md)
  ("S3 review fixes" follow-up).

### R230-1: A refused legacy hash still counts as a usable password

- **PR:** #230 (47, tip `af4adc1e`). `app/db/scalikejdbc/User.scala:74`
  (`hasUsablePassword = password.exists(_ != RetiredLegacy)`) and `:77`
  (`passwordRetired`), used by the `reinvite` guard
  (`app/controllers/UserController.scala:740`), `UserService.needsPassword`
  (`app/services/UserService.scala:84`) and the users list
  (`app/views/users.scala.html:101`, `:105`). Sign-in already treats such a hash
  as none (`usableHash`).
- **Severity:** Low.
- **At the final tip:** still there.
- **Failure scenario:** with `wlxjury.legacyPasswords.retire = false`
  (supported), or in the up-to-6-hour gap before the retirement task runs, a
  legacy hash past the grace period no longer signs in but is not replaced. For
  a verified legacy account, `reinvite` answers "invitation.not.possible", adding
  it to another contest sends the "use your password" greeting, and the list
  shows no "password expired" badge. Only root's password link recovers it.
- **Suggested fix:** base both helpers on the sign-in rule
  (`!(PasswordHasher.isLegacy(h) && User.legacyGraceOver())` as well), or one
  `User.passwordUsable(user)` for all three call sites; add an
  `AccountInvitationSpec` case with the grace over, the hash not replaced and
  `retire = false`.
- **Placement:** a new item after S6 in
  [`2026-11-02-security-resolution.md`](2026-11-02-security-resolution.md)
  ("S6 review fixes" follow-up).

New entries use the format `### R<PR>-<n>: <short title>`, then PR and file:line
at its tip, severity, whether it is still at the final tip, the failure
scenario, the suggested fix and the placement (append after an existing plan,
change an existing plan's batch, or fold into in-flight work: the robustness
batch, the OAuth port, CI or the deploy-adoption fix).

## Stack-aware review results

Every PR, #184 to #237, was reviewed against the rest of the stack. "No findings" means
nothing in that PR is left open: what the review found is fixed later in the stack or
covered by a plan. The CodeRabbit review of #184 is under [Reviewed PRs](#reviewed-prs).

| # | PR | Title | Result |
|---|---|---|---|
| 01 | #184 | Transient: back out GitHub master's 2026-04-22 API review fixes (they return in PR 13) | No findings |
| 02 | #185 | Local image cache: integration and Docker end-to-end tests, round downloads | No findings |
| 03 | #186 | Play image proxy for cached thumbnails (V44) | No findings |
| 04 | #187 | Cascaded thumbnail resize and its JMH benchmarks | No findings |
| 05 | #188 | Flyway B44 baseline and its generator spec | No findings |
| 06 | #189 | Gatling fixture data (data only) | No findings |
| 07 | #190 | Gatling DB performance suite | No findings |
| 08 | #191 | Shared test DB, AutoRollback and the shared Play app | No findings |
| 09 | #192 | Ranked and region-stat query rewrites | No findings |
| 10 | #193 | DB indexes and selection.monument_id (V45a-g, V46-V50) | No findings |
| 11 | #194 | Blue-green deployment scripts | No findings |
| 12 | #195 | SelectionQuery SQL-injection guards (V51) | No findings |
| 13 | #196 | security/local_images merge, round checks, distribution verification (V52, V53) | No findings |
| 14 | #197 | Test build fixes, media type (V54), skipped V45 indexes (V55), honest benchmarks | No findings |
| 15 | #198 | Performance and memory, background jobs and distribution, InnoDB (V56-V60, V55.1) | [R198-1](#r198-1-a-job-that-crashes-the-jvm-is-retried-forever) (Low), [R198-2](#r198-2-duplicate-round-numbers-on-concurrent-creation) (Nit) |
| 16 | #199 | Production-like benchmark results | No findings |
| 17 | #200 | Deployment tooling (wlxjury-release) | No findings |
| 18 | #201 | Multi-contest users (V61, V62) and background Commons import | No findings |
| 19 | #202 | Deleted and renamed Commons files (V63, V64), thumbnail sizes | No findings |
| 20 | #203 | Add files to a round from a pasted list; WebP thumbnails | [R203-1](#r203-1-a-pasted-file-list-over-100-kb-is-refused) (Low) |
| 21 | #204 | Contest files gallery (V65, V66) | No findings |
| 22 | #205 | Region improvements (with the agent-check tooling) | No findings |
| 23 | #206 | Authorization denials, own votes, CSRF on votes | No findings |
| 24 | #207 | Gallery latency per click | No findings |
| 25 | #208 | International winners, asset digests and gallery-latency follow-ups | No findings |
| 26 | #209 | Conflict-of-interest filter (V67, V68) | No findings |
| 27 | #210 | Category exclusions and named operations (V69, V70) - production f9c31ce5 | No findings |
| 28 | #211 | Theme phase 1: one self-hosted layout, screenshot harness | No findings |
| 29 | #212 | Theme phase 2: Bootstrap 5 | No findings |
| 30 | #213 | Theme phases 3-4: colour modes (V71), inline styles | No findings |
| 31 | #214 | Theme follow-ups 1-11 | No findings |
| 32 | #215 | Yes/no rounds' aggregate votes (V72-V74) | No findings |
| 33 | #216 | Follow-ups B1-B2: cleanup, one pool | [R216-1](#r216-1-thumbnail-url-export-uses-only-the-configured-host) (Low) |
| 34 | #217 | Follow-up B3: no inline scripts | No findings |
| 35 | #218 | API with session and token authentication (V75) | No findings |
| 36 | #219 | Follow-up B5: allowed hosts, Secure cookies, CSP by hash | No findings |
| 37 | #220 | Follow-up B4: bcrypt | No findings |
| 38 | #221 | Follow-ups B6 and B8: escaping and secrets, by-rate benchmark choice | No findings |
| 39 | #222 | Follow-up B11: ranked lists counted from rank counts | No findings |
| 40 | #223 | S1 role allowlist (V76), with the plans and decisions it came with | No findings |
| 41 | #224 | Follow-up B12: grouped pages read selection alone | No findings |
| 42 | #225 | S2: invitations only, verified email changes (V77) | [R225-1](#r225-1-an-email-over-190-characters-gives-a-server-error) (Low) |
| 43 | #226 | C1: criteria vote integrity (V78) | No findings |
| 44 | #227 | S5/S10: output and log hygiene (V79) | [R227-1](#r227-1-database-error-messages-with-bound-values-still-reach-the-log) (Low) |
| 45 | #228 | Security practices and guards G1/G2/G2b | No findings |
| 46 | #229 | S3: sessions (V80) | [R229-1](#r229-1-v80-undo-does-not-sign-out-pre-s3-cookies) (Nit) |
| 47 | #230 | S6: proxy widths, stopped jurors, legacy SHA-1 retirement (V81) | [R230-1](#r230-1-a-refused-legacy-hash-still-counts-as-a-usable-password) (Low) |
| 48 | #231 | S7: security headers, CSP config; shared filter test app - release 1 tip d8054737 | No findings |
| 49 | #232 | P0: selection to vote, rounds to round (V82) | No findings |
| 50 | #233 | Test speed-up: tmpfs test DB | No findings |
| 51 | #234 | Flyway 13.12.0 via db.Migrations, B44 removed | No findings |
| 52 | #235 | C2: criteria catalogue (V83) | No findings |
| 53 | #236 | P1: round_image (V84) | No findings |
| 54 | #237 | MariaDB 10.11 port: tests on 10.6 and 10.11 (V85, V86) | No findings |

## Reviewed PRs

### PR 184 (01, transient revert)

CodeRabbit reviewed `c48cd68c` (the pre-rebuild SHA of `ccbff0a9`): 8 inline comments and 3 review-level remarks.
All 11 are changes this PR backs out on purpose (the five 2026-04-22 review fixes
on GitHub master) or tests for them, so none needs an entry here:

| Comment | Class | Where it is fixed |
|---|---|---|
| `EditRound.scala:97` special nomination contest and year check | 1 | #196 (`37e6ea26`) |
| `LoginController.scala:59` `no-store` on invalid sign-in | 1 | #196 (`37e6ea26`) |
| `LoginController.scala:75` `no-store` on sign-up | 1 | #196 (`37e6ea26`) |
| `Tools.scala:23` MySQL driver class (and `ConnectDb`) | 1 | #196 (`37e6ea26`) |
| `ContestService.scala:70` campaign not passed to `create` | 1 | #196 (`37e6ea26`) |
| `conf/routes:13` `+ nocsrf` on `POST /auth` | 1 | #196 (`37e6ea26`) |
| `ContestSpec.scala:16` live Commons bot | 1 | #196 (`37e6ea26`) |
| `ContestSpec.scala:30` expected contest without campaign | 1 | #196 (`37e6ea26`) |
| `Api.scala:37` `getContest` 404 (review-level) | 1 | #196 (`37e6ea26`) |
| `ApiSpec.scala:17` routed unknown-id and PUT tests (review-level) | 1 | #218 (`16b4fccf`) |
| `conf/routes:13` routed tokenless `POST /auth` test (review-level) | 1 | #231 (`40aef2e1`) |
