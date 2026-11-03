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
where it moved. Entries from CodeRabbit's reviews (R185-1 onwards) give the newest tip,
`stack/62-docs-phase`.

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
- **Suggested fix (design):** redact the values but keep the error diagnosable,
  for a `java.sql.SQLException` anywhere in the cause chain of the
  `server_error` and job-crash logs (a scrubbed wrapper `Throwable` or a logback
  `throwableConverter` for `java.sql.*`, building on `SqlLogging.describe`):
  - **Keep:** the SQLState, the vendor error code, and the constraint or index
    name parsed from the driver message (`Duplicate entry '…' for key
    'users_email_uidx'` gives key `users_email_uidx`); the statement template
    with placeholders (ScalikeJDBC has it without the bound params), each
    parameter's type and length; the request context: the user-facing
    reference, path, account id, and contest, round or job ids; the stack
    frames.
  - **Redact:** the quoted values in the driver message.
  - **Keyed hash:** in place of each redacted value, a truncated HMAC with a
    per-installation secret. Two errors with the same value then correlate, and
    an operator can test a suspected value by hashing it, without the log ever
    holding the value.
  - **Opt-in unredacted mode** for staging or development: a config flag, off
    by default and never on in production config, with a startup WARN when it
    is on.

  Specs: a forced duplicate-key error logs the SQLState, error code, key name,
  template and reference, and a hash but no bound value; the same value gives
  the same hash; the flag logs the value and warns at startup.
- **Placement:** a new item after S5 in
  [`2026-11-02-security-resolution.md`](2026-11-02-security-resolution.md),
  "Diagnosable redaction of database errors (fixes the remaining value leak;
  keeps reproduction possible)".

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

### R185-1: Local image cache bookkeeping gaps

From CodeRabbit's reviews of #185, #186 and #187. Each item is small; together they decide
whether the cache's counts and registry tell the truth. File:line at the newest tip
(`stack/62-docs-phase`, `c5f0d164`), `app/services/LocalImageCacheService.scala` unless named.

- **PR:** #185 (02, tip `3ebe865f`), #186 (03, `155040c4`), #187 (04, `6a13cd09`).
- **Severity:** Low (the first item), Nit (the rest).
- **At the newest tip:** all still there. Fixed meanwhile, so not part of this entry: the
  atomic start (#196), lookup failures and failed streams shown as failed (#216), counted
  download and decode failures (#196), temp file plus atomic move (#194), `saveResized`
  rewriting a file deleted from disk (#196), protocol-relative URLs (#230), the served widths
  (#230).
- **Items, failure scenarios and fixes:**
  1. **`wlxjury.thumbs.parallelism` of 0 or less** (`:78`, used at `:400`) is read unchecked.
     Pekko's `mapAsyncUnordered` has no `require` on it (`MapAsyncUnordered`, pekko-stream
     1.0.3 `Ops.scala:1394`); with 0 it never pulls, the stream never completes, and the
     contest's progress stays `running` until a restart, so later starts are refused. Fix:
     reject values below 1 at startup (or fall back to the default) with a WARN.
     (#185 `LocalImageCacheService.scala:169`.)
  2. **Write failures are neither awaited nor counted** (`downloadAndResize` `:524-531`,
     `saveResized` `:650-668`): the writes run in unawaited futures whose `recover` only logs
     and releases the claim, so the run counts the image as done with no error. `ImageIO.write`
     returning `false` (no writer) is not treated as a failure either (`:133`, `:655`). Fix:
     return the write futures from `saveResized` (one combined future), await them in
     `downloadAndResize`, and fail when `ImageIO.write` returns `false`. (#187 `:284`, review
     remark "Throw when ImageIO.write returns false".)
  3. **The registry claim is not atomic** (`:647-648`: `containsKey` then `put`). Two writers
     of one file both write (harmless since the atomic move), but a failing one removes the
     claim the other made, so the file is rewritten on the next run. Fix: `putIfAbsent` and
     skip when claimed; on failure remove only the own claim. (#187 `:363`.)
  4. **The bulk filter trusts the registry only** (`allSizesCached` `:484-486`): a file
     deleted while the app runs stays "cached", so a bulk run skips the image; the proxy
     notices (`ImageProxyController.scala:150`, `file.exists()`), the bulk job doesn't.
     Fix: check the widest target file on disk in `allSizesCached`. (#186 `:225`.)
  5. **The proxy doesn't wait for the startup scan** (`fileIfCached` `:98-101` before
     `registryReady` `:719`): in the minutes after a restart, a cached file is fetched from
     Wikimedia again, and on a failed fetch the request is redirected. Fix: wait on
     `registryReady` (with a short timeout) in `serveImage`, or serve a file that exists on
     disk while the scan runs. (#186 `:94`.)
  6. **`Retry-After` as an HTTP date** (`retryDelay` `:612-623`) falls back to the
     exponential delay. Wikimedia sends seconds today, so this is defensive. Fix: parse the
     HTTP-date form too (Pekko's typed `Retry-After` header has both forms). (#185 `:279`;
     integrations practices §1.5 says the downloads honour `Retry-After`.)
  7. **Optional optimizations of the proxy's miss path**, to be measured first (performance
     practices §3.1): return the requested size before the rest of the cascade is scaled; skip
     the cascade when all target sizes are registered; reuse the nearest cascade output for a
     width that isn't generated. A miss happens once per image and served width (`servedWidths`,
     #230), so the gain is small. (#187 review remarks.)
- **Placement:** append after [`2026-11-01-open-followups.md` "Still open"](https://github.com/UkrainianWiki/wlxjury/blob/stack/62-docs-phase/docs/plans/2026-11-01-open-followups.md#still-open)
  as a "local image cache" item; items 1–5 together, item 6 with them if cheap, item 7 only
  after a measurement.

### R185-2: Image cache tests and tooling nits

- **PR:** #185 (02, tip `3ebe865f`), #186 (03, `155040c4`), #187 (04, `6a13cd09`).
- **Severity:** Nit.
- **At the newest tip:** all still there.
- **Items, failure scenarios and fixes:**
  1. **The Docker spec empties a fixed shared directory**
     (`test/services/LocalImageCacheServiceDockerSpec.scala:42`, `:107-112`;
     `docker-compose.test.yml:8`): it deletes everything under `/tmp/wlxjury-jury-images`,
     which a manual Apache stack or a second run may be using. Fix: a per-run directory passed
     to the app config and the compose bind mount (testing practices §7.3, "its own temp
     directory"). (#185 `:115`.)
  2. **`run-dev.sh` runs sbt in the caller's directory** (`run-dev.sh:5`) while it reads `.env`
     from its own: run from elsewhere, sbt starts in the wrong place. Fix: `cd "$(dirname "$0")"`
     first. (#185 `run-dev.sh:5`.)
  3. **Stale `wlxjury.thumbs.host` comment** (`conf/apache/jury-images.conf:3`): the app no
     longer reads that key since #186 (thumbnails go through the Play proxy); the comment still
     says to set it. Fix: describe what the vhost is for now (the Docker spec's Apache, a cache
     front) or drop the line. (#185 `jury-images.conf:6`, #186 review remark on the plan.)
  4. **The cascaded benchmark stops at the requested width**
     (`test/services/FetchAndCacheAllBenchmark.scala:60-64`, `takeWhile(_ >= requestedPx)`),
     while `fetchAndCacheAll` cascades through every target width below it too, before it
     answers. The `cascaded` numbers under-measure the response path for every width above the
     smallest. Fix: cascade all target widths, or name the benchmark for what it measures.
     (#187 `:65`.)
  5. **Weak assertions:** the `cascadeScale` tests use a blank source, so wrong pixels pass (add
     a two-colour source and sample both halves); the "download a response larger than the
     default 16 MB Pekko HTTP limit" example (`LocalImageCacheServiceSpec.scala:434-445`) calls
     `toStrict` on a local entity, not `downloadWithRetry`, so it guards nothing in the
     download path (serve a body over 8 MB from a local server through `downloadWithRetry`, and
     rename the example). (#187 review remarks; testing practices §6.)
- **Placement:** fold into the same "local image cache" item as R185-1 (after open-followups
  "Still open"); items 1 and 5 can go with the CI batch's test fixes.

### R186-1: V44 narrows images.title to 255 characters

- **PR:** #186 (03, tip `155040c4`). `conf/db/migration/default/V44__Alter_images_title_index.sql:1`:
  `ALTER TABLE images MODIFY title varchar(255)` (from `varchar(4000)`).
- **Severity:** Low.
- **At the newest tip:** still there (applied migrations don't change; V63's
  `VARCHAR(255)` title columns for renamed files have the same limit).
- **Failure scenario:** stored titles carry the `File:` prefix, and Commons allows file names up
  to 255 bytes. An ASCII-named file of 251–255 bytes gives a 256–260 character title, which
  `images.title` can't hold: under MariaDB's `STRICT_TRANS_TABLES` the import step's insert
  fails ("Data too long") and the step's job fails or retries; without strict mode the title is
  truncated and `findByFilename` never matches it. Rare (most titles are far shorter; a
  Cyrillic name reaches 255 bytes at about 127 characters).
- **Suggested fix:** a new migration widening `images.title` (and V63's title columns) to
  `VARCHAR(260)`, `utf8mb4` (the index stays under InnoDB's 3072-byte key limit); a spec that
  imports a 255-byte ASCII file name. First check production for titles near the limit.
- **Placement:** append to [db practices §11](https://github.com/UkrainianWiki/wlxjury/blob/stack/62-docs-phase/docs/db/practices.md#11-where-the-code-doesnt-follow-these-yet-for-triage)
  as a new item beside V7 (the same kind of limit for `category.title`), and take it with the
  next schema batch.

### R190-1: Gatling fixture settings and setup checks

- **PR:** #190 (07, tip `ac4bedbd`). `test/gatling/setup/GatlingConfig.scala:16`,
  `test/gatling/setup/GatlingDbSetup.scala:99`, `:128`, `test/gatling/setup/GatlingTestFixture.scala:23`.
- **Severity:** Nit.
- **At the newest tip:** still there (`GatlingConfig.scala:17`; `GatlingDbSetup.scala:180`,
  `:196-207`, `:229`; `GatlingTestFixture.scala:52`, `:73`).
- **Items, failure scenarios and fixes:**
  1. `gatling.maxRate` below 1 passes config loading and fails later in `rng.nextInt` with a
     message that doesn't name the setting; above 20 the rating round falls back to `1-20` but
     ratings up to `maxRate` are still generated and voted. Fix: require
     `1 <= maxRate <= Round.rateRounds.last.id` in `GatlingConfig`.
  2. The setup builds every selection before `take(50000)`: with many users and
     `jurorFraction = 1.0` (about 3.8M selections at 50 users) the fixture build can run out of
     heap. Fix: insert per image batch and keep only the first 50,000 voting pairs, keeping the
     RNG order so the cached fixture stays the same (`FixtureVersion` unchanged).
  3. `freePort()` closes its probe socket before `TestServer` binds the port, so another
     process can take it in between. Fix: retry the start on a bind failure (or bind port 0 if
     `TestServer` reports the port).
- **Placement:** append to [performance practices §1](https://github.com/UkrainianWiki/wlxjury/blob/stack/62-docs-phase/docs/performance/practices.md#1-load-tests-gatling)
  follow-ups, done with open-followups 3.6 (the benchmark workload gaps) before the next
  capacity measurement.

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

### CodeRabbit, PRs 185-190

CodeRabbit's inline comments and review-level remarks on #185-#190, classified against the
current stack tips (`series.json`, then `stack/60`-`62`); each has a reply on its PR.
Review-level remarks are answered in one comment per PR. New entries:
[R185-1](#r185-1-local-image-cache-bookkeeping-gaps), [R185-2](#r185-2-image-cache-tests-and-tooling-nits),
[R186-1](#r186-1-v44-narrows-imagestitle-to-255-characters), [R190-1](#r190-1-gatling-fixture-settings-and-setup-checks).

| PR | Inline comments: class |
|---|---|
| #185 | ImageController.scala:90 round ownership: fixed later; LocalImageCacheService.scala:92 atomic start: fixed later; LocalImageCacheService.scala:104 lookup failure: fixed later; LocalImageCacheService.scala:169 parallelism: new entry; LocalImageCacheService.scala:236 failed downloads counted: fixed later; LocalImageCacheService.scala:247 body size cap: not applicable; LocalImageCacheService.scala:279 Retry-After date: new entry; LocalImageCacheService.scala:295 temp file and move: fixed later; build.sbt:13 jsTest npm ci: fixed later; jury-images.conf:6 HTTPS note: new entry; run-dev.sh:5 working directory: new entry; LocalImageCacheServiceDockerSpec.scala:115 shared /tmp: new entry |
| #186 | ImageProxyController.scala:19 page1- prefix: fixed later; ImageProxyController.scala:27 + in file names: fixed later; ImageProxyController.scala:33 width bound: fixed later; LocalImageCacheService.scala:94 registry wait: new entry; LocalImageCacheService.scala:109 protocol-relative URLs: fixed later; LocalImageCacheService.scala:225 disk check: new entry; LocalImageCacheService.scala:337 atomic writes: fixed later; V44 images.title varchar(255): new entry; LocalImageCacheServiceSpec.scala:575 sleep: fixed later |
| #187 | LocalImageCacheService.scala:284 await writes: new entry; LocalImageCacheService.scala:359 register after write: fixed later; LocalImageCacheService.scala:363 atomic claim: new entry; FetchAndCacheAllBenchmark.scala:65 cascade widths: new entry; LocalImageCacheServiceSpec.scala:306 sleep: fixed later |
| #188 | flyway-baseline-design.md:88 regeneration command: fixed later; SchemaBaselineSpec.scala:17 generate.baseline=false: fixed later; SchemaBaselineSpec.scala:50 mysqldump check: fixed later |
| #189 | db-performance-analysis.md:237 comment.id: fixed later |
| #190 | schema-performance-analysis.txt:155 conclusions: fixed later; gatling-perf-tests.md:9 versions: fixed later; gatling-db-perf-tests-design.md:11 V45: fixed later; GatlingCompare.scala:171 missing V45: fixed later; GatlingConfig.scala:16 maxRate below 1: new entry; GatlingDbSetup.scala:99 maxRate above 20: new entry; GatlingDbSetup.scala:128 streaming selections: new entry; GatlingTestFixture.scala:23 port gap: new entry; RoundManagementSimulation.scala:24 admin user: fixed later; VotingSimulation.scala:39 vote tuples: fixed later |
