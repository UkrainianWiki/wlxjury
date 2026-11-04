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
`stack/62-docs-phase`; from R191-1 on, `stack/68-juror-guide` (`06103e89`, the followups
mirror `63c80ff7` has the same tree). R193-4 is the first Medium.

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
     #230), so the gain is small. (#187 review remarks.) Also share the decoded source among
     concurrent misses of one image: since #193 they share the download, but each still
     decodes and scales its own copy, and the miss path has no concurrency bound (#193 review
     remark on `LocalImageCacheService.scala:121-127`).
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
  4. A `gatling.jurorFraction` that rounds to no jurors (`math.round(numJurors * fraction)`
     is 0, e.g. below 1/6 with the smoke spec's 3 users) writes no selections, and the first
     vote (`GatlingTestFixture.nextVote`) fails with an exception that names no setting
     (`GatlingDbSetup.scala:196`, `:204`). Fix: require at least one juror per image in
     `GatlingConfig` or the setup, naming the fraction. (#193 `gatling-perf.conf:11`.)
- **Placement:** append to [performance practices §1](https://github.com/UkrainianWiki/wlxjury/blob/stack/62-docs-phase/docs/performance/practices.md#1-load-tests-gatling)
  follow-ups, done with open-followups 3.6 (the benchmark workload gaps) before the next
  capacity measurement.

### R191-1: Shared test DB helpers leak state on failure

- **PR:** #191 (08, tip `7989c055`). `test/db/scalikejdbc/AutoRollbackMunitDb.scala:30`,
  `test/db/scalikejdbc/SharedPlayApp.scala:69`, `test/services/LocalImageCacheServiceDockerSpec.scala:226`.
- **Severity:** Nit.
- **At the newest tip:** all still there (`AutoRollbackMunitDb.scala:27-29`;
  `SharedTestDb.scala:17-24`, `SharedPlayApp.scala:66-72`; `LocalImageCacheServiceDockerSpec.scala:224`).
  Fixed meanwhile, so not part of this entry: committed rows of other specs reaching the
  rollback specs (#197, `AutoRollbackDb.db()` truncates before each example).
- **Items, failure scenarios and fixes:**
  1. `AutoRollbackMunitDb`'s fixture borrows a connection, then calls `db.begin()` and
     `db.withinTxSession()`; if either throws, the setup fails, the teardown never runs and the
     connection is never returned to the pool. Later `dbTest`s in the JVM then wait for a
     connection. Fix: close the `DB` and rethrow when the transaction start fails.
  2. `truncateAll` (both `SharedTestDb` and `SharedPlayApp`) sets `FOREIGN_KEY_CHECKS=0`,
     truncates, then sets it back; a failing `TRUNCATE` (a lock wait timeout, §2.3) skips the
     reset and returns the pooled connection with foreign key checks off, so later specs on
     that connection can insert rows the schema refuses. Fix: reset in a `finally`.
  3. The Docker spec's cleanup (`if (SharedTestDb.initialized) SharedTestDb.reregisterDefault()`)
     forces the lazy shared container: when the spec runs before any DB spec, its `finally`
     starts a second MariaDB and runs the migrations, and a failure there replaces the
     spec's own result. Fix: `SharedTestDb.isStarted`, as `SchemaBaselineSpec` and
     `GatlingSmokeSpec` do. (Runs only with `-Ddocker.tests`.)
- **Placement:** append to [testing practices §2](https://github.com/UkrainianWiki/wlxjury/blob/stack/68-juror-guide/docs/testing/practices.md#2-isolation-and-the-shared-server)
  follow-ups; take with the CI batch's test fixes (as R185-2 items 1 and 5).

### R193-1: Gatling fixture cache can keep a broken or stale dump

- **PR:** #193 (10, tip `c7bac6de`). `test/gatling/setup/GatlingDbCache.scala:39`, `:68`,
  `:98`, `:102-107`; `test/gatling/GatlingSmokeSpec.scala:13`.
- **Severity:** Nit.
- **At the newest tip:** all still there (`GatlingDbCache.scala:45-49`, `:79-84`, `:101-110`,
  `:114-121`; `GatlingSmokeSpec.scala:16`). Fixed meanwhile: the fixture generator's changes
  invalidate the dump through `FixtureVersion` (#197).
- **Items, failure scenarios and fixes:**
  1. **The key hashes the CSV files' sizes only:** a same-length edit of
     `data/wlm-UA-images-2025.csv` or `data/wlm-ua-monuments.csv` restores the old dump, and the
     simulations run on the old rows. Fix: hash the contents (38k rows; or the file's
     modification time plus size, if hashing is too slow).
  2. **A failed `zcat` passes the restore:** `zcat … | mysql …` returns `mysql`'s status, and
     `mysql` exits 0 on empty input, so an empty or corrupt dump restores an empty fixture
     without an error. Fix: `set -o pipefail` in the `sh -c` (the image's shell permitting) or
     decompress to a file and chain `&&`.
  3. **A failed `mysqldump` passes the save:** `mysqldump … | gzip > …` returns `gzip`'s
     status, so a partial dump is cached and reused. Fix: as item 2.
  4. **The copy writes the final dump path:** an interrupted copy leaves a partial
     `gatling-<key>.sql.gz`, which `exists` then treats as a cache hit. Fix: copy to a
     temp file beside it and move it into place.
  5. **The smoke spec's `Run:` line names `gatling.GatlinSmokeSpec`**, so the copied command
     selects nothing. Fix: the class name.
- **Placement:** with [R190-1](#r190-1-gatling-fixture-settings-and-setup-checks), in
  performance practices §1 follow-ups.

### R193-2: GatlingCompare compares nothing since V55

- **PR:** #193 (10, tip `c7bac6de`). `test/gatling/setup/GatlingCompare.scala:139`, `:237`,
  `:250`, `:289`.
- **Severity:** Nit (a measurement tool; no effect on the app).
- **At the newest tip:** still there (`GatlingCompare.scala:134-140`, `:240-251`, `:263-271`),
  and worse: #197 (`9c61c478`) moved V45a-V45g into V55 (Flyway skipped the `V45x` names), so
  the files the tool stashes and restores don't exist. Every pass runs the full schema, and
  [performance practices §1.6](https://github.com/UkrainianWiki/wlxjury/blob/stack/68-juror-guide/docs/performance/practices.md#1-load-tests-gatling)
  still describes the tool as comparing runs pass by pass.
- **Items, failure scenarios and fixes:**
  1. Each pass's attribution row measures run-to-run noise (all of them now; at #193 the V45a
     row already did, since V48 drops V45a's index).
  2. `simColumns` uses camel-case keys while `collectLogs` keys the results by Gatling's
     lowercase directory names (`votingsimulation-…`), so every attribution cell is `n/a`.
  3. A pass whose `sbt Gatling/test` failed is still recorded (a warning only), and `n/a`
     means both "missing" and "under 1%".
  4. `stashFrom` and `restoreUpTo` ignore `renameTo`'s result, and nothing restores the files
     if a pass throws.
- **Suggested fix:** retire the tool: delete `GatlingCompare.scala` and point §1.6 at the
  V45 result (`git show 0a43cc9f:docs/gatling-comparison.txt`) and at paired runs of two
  builds (performance practices §2.4). Fix items 2-4 only if a per-migration comparison is
  wanted again.
- **Placement:** with the docs batch (performance practices §1.6), or with R190-1.

### R193-3: extractDepSources keeps partial or stale sources

- **PR:** #193 (10, tip `c7bac6de`). `build.sbt:24`.
- **Severity:** Nit (developer tooling).
- **At the newest tip:** still there (`build.sbt:22-26`).
- **Failure scenario:** the task skips a module whose `target/dep-sources/<org>/<name>-<revision>`
  directory exists. An extraction stopped halfway leaves the directory, so the sources stay
  incomplete; a `SNAPSHOT` republished at the same revision (scalawiki `0.7.0-SNAPSHOT`) keeps
  the old sources, which CLAUDE.md tells agents to read first.
  [Integrations practices §2.2](https://github.com/UkrainianWiki/wlxjury/blob/stack/68-juror-guide/docs/integrations/practices.md#2-scalawiki)
  works around it for scalawiki (read it at the pinned commit).
- **Suggested fix:** unzip into a temp directory and rename it into place; re-extract when the
  source jar is newer than the directory (or always for `SNAPSHOT` revisions).
- **Placement:** with the scalawiki packages plan
  ([`2026-11-03-ci-and-scalawiki-packages.md`](https://github.com/UkrainianWiki/wlxjury/blob/stack/68-juror-guide/docs/plans/2026-11-03-ci-and-scalawiki-packages.md)),
  whose versioned releases remove the SNAPSHOT case.

### R193-4: run-with-prod-dump.sh exposes the restored production data

- **PR:** #193 (10, tip `c7bac6de`). `scripts/run-with-prod-dump.sh:68-75`, `:122`, `:144`,
  `:152`, `:178`.
- **Severity:** Medium (item 1), Low (item 2), Nit (items 3-5). The restored dump has the
  users' emails and password hashes.
- **At the newest tip:** all still there (`:257`, `:89` and `:216`, `:210`, `:181`, `:64-80`).
  Fixed meanwhile: the credentials go in mode-600 option files, not `MYSQL_PWD` (#221, B6).
- **Items, failure scenarios and fixes:**
  1. **The local MariaDB is published on every interface** (`-p "${LOCAL_PORT}:3306"`), and
     its passwords are literals in the script (`:87-88`, a public repository): anyone who can
     reach the developer's machine on port 3307 can read the restored production data. Fix:
     `-p "127.0.0.1:${LOCAL_PORT}:3306"` (the script connects only through 127.0.0.1).
  2. **The dump is written with the caller's umask** (`gzip > "$tmp_file"`, then `mv` to
     `/tmp/wlxjury-prod.sql.gz`) and stays there for `--skip-dump`: with umask 022 other local
     users can read it. Fix: `umask 077` for the dump (and `chmod 600` an existing one).
  3. **`mysqldump -h "$WLXJURY_DB_HOST"`** gets the app's `host:port` form, so a configured
     port fails host resolution. Fix: split it and pass `-P` (as `wlxjury-deploy.py` does).
  4. **`--reuse-container` checks only that the container runs**, not its published port,
     so sbt can reach another service on 3307 (and run migrations there with
     `migration.auto`). Fix: check `docker port` for `${LOCAL_PORT}`.
  5. **`--reuse-container` still requires `.env`** with the four production variables, which
     the reuse path doesn't use. Fix: load and check them only when dumping.
- **Placement:** [secure-coding §4](https://github.com/UkrainianWiki/wlxjury/blob/stack/68-juror-guide/docs/security/secure-coding.md#4-secrets-in-scripts)
  (secrets in scripts), with its planned guard G12; items 1 and 2 first.

### R195-1: SelectionQuery injection tests don't show the filter applies

- **PR:** #195 (12, tip `f42d0bb3`). `test/db/scalikejdbc/SelectionQuerySpec.scala:49-55`,
  `test/db/scalikejdbc/ImageDbNewDbSpec.scala:144-148`.
- **Severity:** Nit.
- **At the newest tip:** still there (`SelectionQuerySpec.scala:50-57`,
  `ImageDbNewDbSpec.scala:140-158`).
- **Items, failure scenarios and fixes:**
  1. The multi-code region test checks `in (?, ?)` and the parameters but not the column, so
     rendering `m.adm1` in that branch still passes. Fix: assert `m.adm0 in (?, ?)`.
  2. The injection examples insert no votes, so they return `Nil` even with the region
     predicate removed. Fix: seed an image, the user's vote in the round and a monument whose
     `adm0` the payload doesn't match, and assert it is excluded.
- **Placement:** with the CI batch's test fixes (testing practices §6, weak assertions).

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

### CodeRabbit, PRs 191-195

CodeRabbit's 51 inline comments and 13 review-level remarks on #191-#195, classified against
the followups tip `63c80ff7` (the tree of `stack/68-juror-guide`, `06103e89`); one reply per
thread, and the review-level remarks in one comment per PR (#192, #193, #195). New entries:
[R191-1](#r191-1-shared-test-db-helpers-leak-state-on-failure),
[R193-1](#r193-1-gatling-fixture-cache-can-keep-a-broken-or-stale-dump),
[R193-2](#r193-2-gatlingcompare-compares-nothing-since-v55),
[R193-3](#r193-3-extractdepsources-keeps-partial-or-stale-sources),
[R193-4](#r193-4-run-with-prod-dumpsh-exposes-the-restored-production-data) (Medium),
[R195-1](#r195-1-selectionquery-injection-tests-dont-show-the-filter-applies); items added to
[R185-1](#r185-1-local-image-cache-bookkeeping-gaps) (item 7, shared decode) and
[R190-1](#r190-1-gatling-fixture-settings-and-setup-checks) (item 4, juror fraction).

| PR | Inline comments: class |
|---|---|
| #191 | CategoryJdbc.scala:54 category link insert transaction: fixed later; ImageJdbc.scala:105 image batch insert transaction: fixed later; SelectionJdbc.scala:84 selection batch insert transaction: fixed later; AutoRollbackMunitDb.scala:30 connection leak on begin failure: new entry; ImageSpec.scala:25 committed rows from other specs: fixed later; JurorImagesSpec.scala:32 page id collisions: fixed later; RoundSpec.scala:14 committed rounds from other specs: fixed later; SelectionSpec.scala:20 committed selections from other specs: fixed later; SharedPlayApp.scala:69 FOREIGN_KEY_CHECKS not restored in finally: new entry; LocalImageCacheServiceDockerSpec.scala:226 forces SharedTestDb.initialized: new entry |
| #192 | ImageJdbc.scala:248 duplicate selection rows in ranked join: fixed later; ImageDbNew.scala:112 regions need the monument join: fixed later; ImageRankedSpec.scala:43 ROW_NUMBER loses shared ranks: fixed later; RoundUserStatSpec.scala:27 leftover rows: fixed later |
| #193 | ImageDbNew.scala:118 selection.monument_id not synced: fixed later; LocalImageCacheService.scala:164 running flag on lookup failure: fixed later; build.sbt:24 dep-source extraction partial and stale: new entry; selection-monument-id plan:57 test:compile runs no Flyway: fixed later; GatlingCompare.scala:139 camel-case simColumns keys: new entry; GatlingCompare.scala:289 failed passes recorded: new entry; GatlingDbCache.scala:44 generator not in cache key: fixed later; GatlingDbCache.scala:68 zcat status ignored: new entry; GatlingDbCache.scala:98 mysqldump status ignored: new entry; gatling-perf.conf:11 juror fraction selecting no jurors: new entry; LocalImageCacheServiceSpec.scala:689 barrier on the dispatcher: not applicable; run-with-prod-dump.sh:122 reused container's port: new entry; run-with-prod-dump.sh:144 host:port passed to -h: new entry; run-with-prod-dump.sh:152 dump file mode: new entry; run-with-prod-dump.sh:178 port published on all interfaces: new entry; GatlingCompare.scala:237 V45a row measures noise: new entry; GatlingCompare.scala:250 renameTo unchecked: new entry; GatlingDbCache.scala:39 CSV sizes in cache key: new entry; LocalImageCacheServiceSpec.scala:698 partial JPEG and claim release: fixed later |
| #194 | LocalImageCacheService.scala:147 startup deletes another slot's .tmp- files: fixed later; LocalImageCacheService.scala:380 claim kept after a failed write: fixed later; wlxjury-deploy-canary.sh:36 mv nests the release: fixed later; wlxjury-deploy-canary.sh:47 DB host and dump permissions: fixed later; wlxjury-promote.sh:33 predictable dump file: fixed later; wlxjury-promote.sh:49 signals leave maintenance on: fixed later; wlxjury-promote.sh:59 in-flight writes missed by the snapshot: fixed later; wlxjury-promote.sh:53 concurrent promotions: fixed later; wlxjury-promote.sh:66 WLXJURY_DB_HOST unused: fixed later; wlxjury-promote.sh:74 health check timeout and target: fixed later; wlxjury-promote.sh:88 post-swap failure keeps maintenance: fixed later; wlxjury-promote.sh:90 slot files out of sync: fixed later; 0002-wlxjury.conf:98 maintenance rule missing on HTTPS: fixed later; wlxjury-blue.service:11 listener on all interfaces: fixed later; blue-green-deployment plan:9 replay steps: fixed later |
| #195 | security/review.md:57 option file mode before the write: fixed later; sql-injection-fix plan:181 monument join condition: fixed later; ImagesSqlSpec.scala:173 regional count counts rows: not applicable |

| PR | Review-level remarks: class |
|---|---|
| #192 | MonumentJdbc.scala:40 batch without a transaction: fixed later (#201) |
| #193 | B44 monument.id key: fixed later (#195 V51, #234); V45x names skipped by Flyway: fixed later (#197 V55); GatlingSmokeSpec Run: typo: new entry (R193-1); cache dump copied to the final path: new entry (R193-1); orphan selections in count/list: fixed later (#198 V58); shared decode on concurrent misses: new entry (R185-1 item 7); Voting check reads no stored rate: fixed later (#214); .env required with --reuse-container: new entry (R193-4); Page overload untested: fixed later (#216) |
| #195 | short-region LIKE wildcards: fixed later (#205); multi-code test column: new entry (R195-1); injection cases seed no rows: new entry (R195-1) |
