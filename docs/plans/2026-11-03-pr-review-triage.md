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
mirror `63c80ff7` has the same tree); from R196-1 on, the followups tip `34788a54` (the mirror
of `stack/71-investigations`, `6c00ed6e`). R193-4 is the first Medium.

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

### R196-1: Several previous rounds merge their ratings unevenly

- **PR:** #196 (13, tip `37e6ea26`). `app/db/scalikejdbc/Round.scala:324` (`sameRateType`),
  `app/org/intracer/wmua/cmd/DistributeImages.scala:163`, `:222`.
- **Severity:** Low (an organizer's choice of several previous rounds picks the wrong files;
  one previous round is unaffected).
- **At the newest tip:** all still there. `Round.scala:431-432` (`sameRateType` compares
  `isBinary` only); `DistributeImages.scala:126` (`prevRound = prevRounds.headOption`), `:197-214`
  (`mergeByPageId`); `ImageWithRatingSeqFilter.scala:116` (`SelectedAtLeast`: `rateSum >= by`);
  the streamed path the same: `RoundDistributionHandler.scala:188-191` (the denominator from the
  first previous round), `:764` (one entry per previous round, juror 0, the round's rate sum).
- **Items, failure scenarios and fixes:**
  1. **Different rating scales pass as one type:** a 1-5 and a 1-10 round (or a comments-only and
     a rated one) may be merged; the first round's `prevMinAvgRate` and top-N then rank sums of
     both scales. Fix: compare `rates.id` (and refuse comments-only with rated) in `sameRateType`,
     which the form and `DistributeImages` both call.
  2. **Optional-rate rounds divide by the first round's jurors:** with two optional-rate previous
     rounds the sums come from both and the denominator from the first
     (`numberOfJurorsForAverageRate`), so an average can exceed the scale and the selection
     depends on the rounds' order. Fix: decide the rule first; refusing several optional-rate
     previous rounds (beside the rate-type check) is the small one, a denominator over all
     contributing rounds the other.
  3. **"Selected by at least N" counts per round, not per juror:** each previous round gives one
     entry with its approvals, so a juror who selected a file in two rounds counts twice and one
     juror satisfies N = 2. Fix: count distinct approving jurors across the previous rounds in
     the candidate query (`COUNT(DISTINCT jury_id)` over `vote` of those rounds), for both paths.
  A spec per item with two previous rounds.
- **Placement:** append after [`2026-11-01-open-followups.md` "Still open"](https://github.com/UkrainianWiki/wlxjury/blob/stack/62-docs-phase/docs/plans/2026-11-01-open-followups.md#still-open),
  decided with the round-images two-step plan (its D6: an image can come from several previous
  rounds), whose candidate queries replace these.

### R196-2: The round form shows both previous-round filter groups at first

- **PR:** #196 (13, tip `37e6ea26`). `app/views/mainAdmin.scala.html:59-68` (review remark).
- **Severity:** Nit.
- **At the newest tip:** still there. Since #217 the handler is in
  `public/javascripts/round-form.js:43-54`, bound at `:134` to `change` only;
  `app/views/round/edit/imageFiltering.scala.html:36`, `:70` render both groups.
- **Failure scenario:** a form shown with previous rounds chosen (a new round's form back with
  an error, or an existing round) shows the yes/no filters and the rating filters together until
  the previous rounds are changed; the hidden one's values are still sent.
- **Suggested fix:** call `previousRoundsChanged()` once in `init`, after binding it; a
  `round-form` jsTest case.
- **Placement:** append after open-followups "Still open", with the next round-form change.

### R196-3: Authorization and query tests that would pass a regression

- **PR:** #196 (13, `37e6ea26`), #197 (14, `9c61c478`), #201 (18, `eb39d040`) review remarks.
- **Severity:** Nit (test gaps; the code they cover is right today).
- **At the newest tip:** all still there.
- **Items:**
  1. No cache-hit test for `page1-` / `lossy-page1-` thumbnails: the PDF and TIFF examples
     (`test/controllers/ImageProxyControllerSpec.scala:150-166`) store no image, so only the
     redirect is tested. Add one with a stored PDF and its cached file, expecting 200.
  2. The cross-contest vote example (`test/controllers/MutationAuthorizationSpec.scala:79-98`)
     seeds no vote, so an update that ran before the check would change nothing and still pass.
     Seed the juror's vote with another rate and assert it is unchanged.
  3. No example of an own round with another contest's `contestId` for `addComment`
     (`ImageDiscussionController.scala:38`, `CommentAuthorizationSpec`): removing the filter
     passes. Add it, expecting the refusal and no comment.
  4. No example refusing another contest's juror the gallery list
     (`GalleryService.isNotAuthorized`; the large view has one, `LargeImageSpec.scala:641`).
  5. The deferred-join plan check (`test/db/scalikejdbc/ImageDbNewDbSpec.scala:219-226`) looks
     for `Using temporary` only, on eight rows; a sort of the joined rows can show as
     `Using filesort` alone. Also refuse `Using filesort` on the joined table, or check the plan's
     table order.
- **Placement:** [testing practices §12](https://github.com/UkrainianWiki/wlxjury/blob/stack/68-juror-guide/docs/testing/practices.md#12-where-the-code-doesnt-follow-these-yet-for-triage)
  as a new T item; with the CI batch's test fixes (as R195-1).

### R197-1: Gatling simulation caps and checks

- **PR:** #197 (14, tip `9c61c478`). `test/gatling/GatlingSmokeSpec.scala:56`,
  `test/gatling/setup/HeapWatcher.scala:54`, `test/gatling/simulations/AggregatedRatingsSimulation.scala:63`,
  `RoundDistributionSimulation.scala:109-111`, `:168`, `RoundManagementSimulation.scala:114`.
- **Severity:** Nit (measurement tools; no effect on the app).
- **At the newest tip:** all still there (`GatlingSmokeSpec.scala:63-66`, `:107`;
  `HeapWatcher.scala:40-76`; `AggregatedRatingsSimulation.scala:81-82`;
  `RoundDistributionSimulation.scala:134`, `:195`; `RoundManagementSimulation.scala:76`, `:112`).
- **Items, failure scenarios and fixes:**
  1. `warmGet` drops the first GET's status (and "RegionStat smoke" its inline warm-up's), so a
     route that fails once and then answers 200 passes. Check both statuses.
  2. `HeapWatcher` counts a GC by when its notification arrives: one that arrives after `end()`
     is lost, after the next `start()` counted there. Attribute by `GcInfo` start time within the
     step's window.
  3. The stress branch of `AggregatedRatingsSimulation` reads the global `rampUpSeconds` and
     `durationSeconds`, so `gatling.aggRatings.*` timing overrides do nothing there. Read `cfg`'s,
     or say in `gatling-perf.conf` that stress mode ignores them.
  4. `RoundDistributionSimulation` caps the run at 60 minutes whatever `iterations`,
     `timeoutMinutes` and the optional steps add up to, so a long configuration is cut off. Derive
     the cap from them.
  5. `RoundManagementSimulation`'s cap (duration + ramp-up + 180 s) is shorter than the create
     request's timeout (`Distribution.timeoutMinutes`), so the run can end with a creation in
     flight and unrecorded. Add the timeout when `createRound` is on.
  6. A distribution step's `ok` compares counts only; a swap of an eligible file for another
     keeps both. Compare the round's page ids with the expected set (it needs its own query).
- **Placement:** with [R190-1](#r190-1-gatling-fixture-settings-and-setup-checks), in
  performance practices §1 follow-ups.

### R197-2: Media type kept, classified and tested loosely

- **PR:** #197 (14, tip `9c61c478`). `app/db/scalikejdbc/ImageJdbc.scala:123`,
  `app/org/intracer/wmua/cmd/ImageWithRatingSeqFilter.scala:106-107`.
- **Severity:** Nit.
- **At the newest tip:** all still there. `ImageJdbc.scala:180-188` (`fileInfo` writes
  `mediaType` as fetched), `services/imports/CommonsFileCheck.scala:241`;
  `ImageWithRatingSeqFilter.scala:135-142` (`MediaTypeIs`), `Image.scala:36` (`isImage = !isVideo`);
  `EditRound.scala:141`, `:203` (`mediaType` is free text).
- **Items, failure scenarios and fixes:**
  1. A refresh writes the fetched `mediaType` even when Commons returns none (a file whose
     current revision is hidden): a stored `VIDEO` becomes NULL and a `.ogg` video is then
     classed by its extension as an image. Keep the stored value when the fetched one is empty.
  2. An image-only round takes every file that isn't a video, audio files too. Require an image
     media type (or `BITMAP`/`DRAWING`, with the extension fallback for rows without one).
  3. A stored media type other than `video` / `image` (the form posts free text) filters nothing.
     Validate the form value against `MediaType.mediaTypes`; fail closed in `MediaTypeIs`.
  4. Tests: no import spec asserts a stored media type (`FetchImageInfoSpec` is gone since #201),
     and `DistributeImagesSpec` has no file whose media type and extension disagree. Import a
     video through `FakeCommonsSource` and check `media_type`; add a `.jpg` with `VIDEO`.
- **Placement:** append after open-followups "Still open"; items 1 and 4 with the next import
  change.

### R199-1: Benchmark scripts lose state on early exits

- **PR:** #199 (16, tip `77b4d2cf`). `bench/README.md:132`, `bench/scripts/convert.sh:23`,
  `bench/scripts/run.sh:70`, `:128-129`.
- **Severity:** Nit (benchmark tooling).
- **At the newest tip:** all still there (`bench/README.md:121-136`, `:95-99`;
  `convert.sh:23`, `:38`; `run.sh:39-43`, `:67-72`, `:130-131`).
- **Items, failure scenarios and fixes:**
  1. The "check after each step" commands run `$B sql` after each step's `$B stop`, which
     removes the container. Run them before the stop (or after `$B start -`).
  2. `convert.sh` starts the sampler with `docker exec -d` and stops it only after the last
     `ANALYZE`; a `set -e` exit before that leaves it running. Stop it in an `EXIT` trap.
  3. `run.sh` refuses a snapshot whose round 1775 has a category, but the documented check and
     `reopen-contest.sql` cover round 1721's only. Add 1775 to the documented check and say how to
     fix such a snapshot.
  4. `run.sh` copies `application.log` and `stdout.log` only after the loop; an early abort
     reaches `cleanup`, whose `bench.sh stop` removes the container and its logs. Copy them in
     `cleanup` first, guarded so the copy after the loop isn't overwritten.
- **Placement:** [performance practices §2](https://github.com/UkrainianWiki/wlxjury/blob/stack/68-juror-guide/docs/performance/practices.md#2-benchmarks)
  follow-ups, before the next benchmark run.

### R200-1: Release tools end and check database sessions too broadly

- **PR:** #200 (17, tip `6a9a60ff`). `scripts/deployment/wlxjury-deploy.py:435`, `:558`, `:563`;
  `scripts/deployment/wlxjury-release.sh:70`, `:257`, `:266`, `:357`.
- **Severity:** Low (items 1, 2, 4), Nit (3, 5). Only with a database account or server
  shared with other work, or a deployment config that leaves a list empty.
- **At the newest tip:** all still there (`wlxjury-deploy.py:475`, `:586-605`;
  `wlxjury-release.sh:75`, `:242-267`, `:350-356`).
- **Items, failure scenarios and fixes:**
  1. **`end_sessions` kills every session of `WLXJURY_DB_USER`, on any database** (both tools),
     before the "other sessions" check: another workload on that account loses its queries and
     transactions. Add `DB = '<database>'` to the kill and to the wait loop.
  2. **The "other sessions" check counts only `DB = '<database>'`:** a session with no default
     database (or another one) can still write to the jury tables, and its writes after the
     rescue dump are lost by the restore's `DROP DATABASE`. Count `DB IS NULL` sessions of
     non-system users too (not `event_scheduler` and the like), and refuse.
  3. **`deploy` dumps without `end_sessions`:** a leftover session of the app account (a long
     `ALTER`) blocks the `--lock-tables` dump while maintenance is on. Call `end_sessions` before
     the dump, as `restore` does.
  4. **An empty or absent `quiesce_units` is one empty writer in `wlxjury-release.sh`** (`json`
     prints an empty line, `mapfile` reads it): `stop_unit ""` fails after maintenance is on.
     Drop empty entries after `mapfile` (or have `json` print nothing for an empty list), so an
     absent or empty list means no writers, as in `wlxjury-deploy.py`.
  5. **`wlxjury-deploy.py` accepts a `.timer` as `service`:** stopping it leaves the JVM running
     during the backup, and the new release never starts. Require `.service` for `service`.
  Cases in `test_release_script.py` / `test_database_restore.py` for 1, 2 and 4.
- **Placement:** [operations practices §8](https://github.com/UkrainianWiki/wlxjury/blob/stack/68-juror-guide/docs/operations/practices.md#8-where-the-setup-doesnt-follow-these-yet-for-triage)
  as a new OP item (3.3, 4), taken with the next release-tool change.

### R200-2: Release tools' copies, file modes and public checks

- **PR:** #200 (17, tip `6a9a60ff`). `scripts/deployment/wlxjury-deploy.py:153`, `:370`, `:694`,
  `:752`, `:965`; `scripts/deployment/wlxjury-release.sh:219`, `:296`;
  `conf/wlxjury/production.conf:6`; review remarks on `ReadinessControllerSpec.scala:59-60` and
  `test_deployment.py:232-234`.
- **Severity:** Low (items 2 and 3), Nit (the rest).
- **At the newest tip:** all still there (`wlxjury-deploy.py:150`, `:223`, `:710-746`,
  `:787-791`, `:1006`; `wlxjury-release.sh:216-220`, `:298`; `production.conf:6`). Fixed
  meanwhile: token- and key-named credentials in the adoption scan (#241).
- **Items, failure scenarios and fixes:**
  1. **A remote database connects without TLS:** `production.conf` sets `useSSL=false`, and
     `adopt-legacy` keeps only the host and database of the old URL, dropping options such as
     `useSSL=true`. Fine for a database on the app's host, which is what `production.conf` is
     written for. Fix: refuse a legacy URL whose options the new config doesn't reproduce, and
     make TLS a `runtime.env` setting (or document that a remote database needs a tunnel).
  2. **`adopt-legacy` copies more than it scans:** `shutil.copytree` copies a symlink's target as
     a regular file, and only `.conf`, `.ini`, `.properties`, `.cnf` and `.env` are scanned; the
     copy is then made 0644 under a 0755 release. Refuse symlinks before the copy, and scan every
     copied text file (checking the bundled `messages.*` and `logback.xml` give no findings).
  3. **A restore writes `/etc/default/wlxjury` back as 0644** whatever its mode was (the snapshot
     is 0600): a credential there becomes readable by local users. Restore it with the original
     file's mode (record it in the snapshot), or 0600 when unknown.
  4. **The ZIP symlink check can miss:** under `pipefail`, `zipinfo | grep -q '^l'` stops `grep` at
     the first match, `zipinfo` may get SIGPIPE, and the `if` reads that as no match. Use
     `grep '^l' >/dev/null` (reads it all). The SHA-256 of the reviewed build is checked first.
  5. **The public checks accept any 2xx/3xx:** a redirect to another host, or a name proxied to
     another live app, passes and `reopen` records `complete`. Compare a 3xx `Location` host with
     the URL's and look for a page marker of the app (both tools).
  6. **Tests:** no case sends a readiness answer with another build or schema to
     `Deployment.wait_ready` (`test_deployment.py`'s fake skips the poller), and the packaged-app
     test sends no `X-Forwarded-For: 127.0.0.1, 203.0.113.5` (expect 404).
- **Placement:** operations practices §8 as a new OP item, with R200-1; items 2 and 3 first.

### R200-3: Operational INFO lines and scheduler start-up failures not logged

- **PR:** #200 (17, tip `6a9a60ff`). `conf/wlxjury/logback.xml:16`,
  `app/services/jobs/JobScheduler.scala:157`.
- **Severity:** Nit.
- **At the newest tip:** still there (`logback.xml:15-30`: `application`, `coi` and
  `services.ApiTokenService` at INFO, root WARN; `JobScheduler.scala:169-175`, used at `:142` and
  `:178`).
- **Items, failure scenarios and fixes:**
  1. Play's `Logging` names a logger after its class, so the INFO lines of `services.*` and
     `controllers.*` (the scheduler's start, requeue counts, download retries, a round's
     distribution) are not written with this config. Decide which packages log at INFO in
     production (a short list, with OP1's `security.*`) and add them.
  2. `tableReady()` swallows the table check's failure: before the first success a database
     failure at start-up gives no scheduler line at all. Log it, throttled as `poll()` does.
- **Placement:** extend operations practices §8 OP1 (and the logging plan's "the loggers as now"
  step, `2026-10-30-logging-config-in-releases.md`).

### R201-1: Adding a member is not atomic and can lose the greeting

- **PR:** #201 (18, tip `eb39d040`). `app/db/scalikejdbc/User.scala:424`,
  `app/db/scalikejdbc/UserContestJdbc.scala:47`, `app/services/UserService.scala:52`.
- **Severity:** Nit.
- **At the newest tip:** all still there (`User.scala:620-648`, `UserContestJdbc.scala:56-60`,
  `UserService.scala:80-91`, `:115-131`).
- **Items, failure scenarios and fixes:**
  1. `User.create` commits the account (auto session) before the membership insert, and drops
     the insert's result: a contest deleted in between leaves an account with no membership, and
     the admin is told the user was added. Run both in one `DB.localTx` and throw when the
     membership isn't inserted.
  2. `createMembership` uses `INSERT IGNORE`, so a foreign key failure (the contest or user
     deleted after the lookup) is "0 rows" and `addToContest` answers `AlreadyMember`. Insert
     without `IGNORE` and treat only a duplicate key (1062) as already a member.
  3. In the existing-account branch the membership commits before `sendMail`; if the mail fails,
     the exception leaves the method, a retried import finds the membership and answers
     `AlreadyMember`, and the greeting is never sent. Record unsent greetings (a flag or an
     outbox row) for a later send; never put the mail inside the transaction.
- **Placement:** append after open-followups "Still open" (with R225-1, the account forms), or
  the account-join plan when its batch is planned.

### R201-2: Two specs call Wikimedia without the integration flag

- **PR:** #201 (18, tip `eb39d040`). `test/controllers/ImportFilesPageSpec.scala:52`,
  `test/services/imports/MonumentListImportSpec.scala:136`.
- **Severity:** Nit.
- **At the newest tip:** still there (`ImportFilesPageSpec.scala:37-52`: the first example runs
  the app's `MwCommonsSource`; `MonumentListImportSpec.scala:135-150`, `MwMonumentListSourceSpec`).
  Testing practices §5.3 says live tests are behind flags, and the MariaDB 10.11 plan's test run
  records both failing on DNS while the machine slept.
- **Suggested fix:** the files-page example with a `FakeCommonsSource` bound in its test app
  (`testDbApp` with an overriding module), and `MwMonumentListSourceSpec` behind
  `-Dintegration=true` (listed in §5.3, run by `integration.yml`).
- **Placement:** testing practices §12 as a new T item; with the CI batch's test fixes.

### R201-3: The files page waits for Commons' category count

- **PR:** #201 (18, tip `eb39d040`). `app/controllers/ImageController.scala:47-49` (review
  remark).
- **Severity:** Low.
- **At the newest tip:** still there (`ImageController.scala:27-31`, `:56-57`;
  `services/imports/MwApi.scala:69-90`: a timeout per attempt, retries with a doubling wait).
- **Failure scenario:** when Commons is slow or failing, the contest files page waits for
  `categoryFileCount` through all its retries on a request thread (`withAuth`, the default
  pool) and then fails with the error page, so the admin sees neither the local count nor the
  import jobs, and can't start or cancel one from there.
- **Suggested fix:** read the count with a short timeout and no retries, off the request thread
  (`withAuthOn(blocking)`) or by script after the page loads; show "unknown" on failure. A spec
  with a failing fake source expecting 200 and the local count.
- **Placement:** [integrations practices §5](https://github.com/UkrainianWiki/wlxjury/blob/stack/68-juror-guide/docs/integrations/practices.md#5-where-the-code-doesnt-follow-these-yet-for-triage)
  as a new I item (it breaks §1.6, "never on a request thread"); fix with the next import change.

### R201-4: Monument list import settings and nomination order

- **PR:** #201 (18, tip `eb39d040`). `app/services/imports/MonumentListImportHandler.scala:93`,
  `:158`.
- **Severity:** Nit.
- **At the newest tip:** still there (`MonumentListImportHandler.scala:86`, `:149-158`).
- **Items, failure scenarios and fixes:**
  1. `jobs.monumentImport.pageSize` of 0 or less reaches the `embeddedin` request as `eilimit`;
     the job fails on its first fetch. Refuse values below 1 at start-up, as the image handler does.
  2. Two list pages of one step can name the same new monument id; both pass the nomination
     filter and the batch keeps the later, so the stored page depends on title order. Keep the
     first: `.distinctBy(_.id)` after the filter.
- **Placement:** append after open-followups "Still open", with the next import change.

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

### CodeRabbit, PRs 196-201

CodeRabbit's 59 inline comments and 30 review-level remarks on #196-#201 (#198 got no review),
classified against the followups tip `34788a54` (the mirror of `stack/71-investigations`,
`6c00ed6e`); one reply per thread, and the review-level remarks in one comment per PR (#196,
#197, #199, #200, #201). New entries:
[R196-1](#r196-1-several-previous-rounds-merge-their-ratings-unevenly),
[R196-2](#r196-2-the-round-form-shows-both-previous-round-filter-groups-at-first),
[R196-3](#r196-3-authorization-and-query-tests-that-would-pass-a-regression),
[R197-1](#r197-1-gatling-simulation-caps-and-checks),
[R197-2](#r197-2-media-type-kept-classified-and-tested-loosely),
[R199-1](#r199-1-benchmark-scripts-lose-state-on-early-exits),
[R200-1](#r200-1-release-tools-end-and-check-database-sessions-too-broadly),
[R200-2](#r200-2-release-tools-copies-file-modes-and-public-checks),
[R200-3](#r200-3-operational-info-lines-and-scheduler-start-up-failures-not-logged),
[R201-1](#r201-1-adding-a-member-is-not-atomic-and-can-lose-the-greeting),
[R201-2](#r201-2-two-specs-call-wikimedia-without-the-integration-flag),
[R201-3](#r201-3-the-files-page-waits-for-commons-category-count),
[R201-4](#r201-4-monument-list-import-settings-and-nomination-order); none Medium or above.
Two #196 comments (the thumbnail writes) go to the existing
[R185-1](#r185-1-local-image-cache-bookkeeping-gaps) item 2.

| PR | Inline comments: class |
|---|---|
| #196 | EditRound.scala:86 non-numeric previousRound[] dropped: not applicable; ImageProxyController.scala:21 pageN- prefix (its PDF-source part fixed later, #202): not applicable; Round.scala:324 sameRateType checks only binary: new entry; SelectionJdbc.scala:243 mergeRounds leaves criteria rates: fixed later; ConnectDb.scala:16 wrong config keys: fixed later; LocalImageCacheService.scala:197 round lookup failure zeroes progress: fixed later; LocalImageCacheService.scala:399 unbounded write futures: new entry; LocalImageCacheServiceSpec.scala:326 wait for all four files: fixed later; LocalImageCacheServiceSpec.scala:342 wait for the 500px file: fixed later; RoundController.scala:205 re-render calls imagesByRound again: fixed later; DistributeImages.scala:52 criteria rates need inserted ids: fixed later; DistributeImages.scala:163 merged optional-rate denominator: new entry; DistributeImages.scala:222 selected-at-least counts per round: new entry; LocalImageCacheService.scala:416 write failures not counted: new entry; RoundService.scala:48 restore loop stops on the first failure: fixed later; V52 rounds.previous varchar(255): not applicable |
| #197 | ImageJdbc.scala:123 refresh clears a stored mediaType: new entry; ImageWithRatingSeqFilter.scala:107 unknown media type passes all: new entry; ImageWithRatingSeqFilter.scala:106 audio counts as image: new entry; GatlingSmokeSpec.scala:56 warm-up status ignored: new entry; HeapWatcher.scala:54 GC counted by arrival: new entry; AggregatedRatingsSimulation.scala:63 stress ignores aggRatings timing: new entry; RoundDistributionSimulation.scala:168 fixed 60-minute cap: new entry; RoundManagementSimulation.scala:114 cap shorter than create timeout: new entry |
| #199 | bench/README.md:132 snapshot check after stop: new entry; convert.sh:23 sampler left running on error: new entry; run.sh:70 round 1775 category undocumented: new entry |
| #200 | JobScheduler.scala:157 tableReady failures not logged: new entry; logback.xml:16 services/controllers INFO dropped: new entry; BuildIdentity.scala:46 Int version components: not applicable; wlxjury-deploy.py:153 legacy JDBC options dropped: new entry; wlxjury-deploy.py:177 token-named credentials: fixed later; wlxjury-deploy.py:370 only some config suffixes scanned: new entry; wlxjury-deploy.py:435 service may be a .timer: new entry; wlxjury-deploy.py:563 sessions without a default database: new entry; wlxjury-deploy.py:694 copytree follows symlinks: new entry; wlxjury-deploy.py:752 3xx to another host passes: new entry; wlxjury-release.sh:70 empty quiesce_units: new entry; wlxjury-release.sh:219 public URL may reach another app: new entry; wlxjury-release.sh:257 end_sessions on every database: new entry; wlxjury-release.sh:266 sessions without a default database: new entry; wlxjury-release.sh:296 grep -q under pipefail: new entry; wlxjury-release.sh:344 database server identity: not applicable; wlxjury-release.sh:357 no end_sessions before the deploy dump: new entry; wlxjury-deploy.py:558 end_sessions on every database: new entry; wlxjury-deploy.py:965 restored /etc/default/wlxjury is 0644: new entry |
| #201 | UserController.scala:225 roles=jury,root: fixed later; UserController.scala:289 failed import entries not reported: fixed later; ImageJdbc.scala:122 author truncated to 256: fixed later; User.scala:424 account and membership not atomic: new entry; UserContestJdbc.scala:47 INSERT IGNORE reported as AlreadyMember: new entry; CommonsSource.scala:52 + decoded as a space: fixed later; ImageText.scala:26 a page without text clears stored values: fixed later; MonumentListImportHandler.scala:93 pageSize unchecked: new entry; MonumentListImportHandler.scala:158 duplicate ids in one batch: new entry; Job.scala:152 deadlock in the duplicate check: fixed later; UserService.scala:52 greeting lost after a mail failure: new entry; ImportFilesPageSpec.scala:52 live Commons: new entry; MonumentListImportSpec.scala:136 live Wikipedia: new entry |

| PR | Review-level remarks: class |
|---|---|
| #196 | selectWS in a closed round: fixed later (#198); running flag on a failed query: fixed later (#216); previous rounds of another contest: fixed later (#198); saveRound role-only check: fixed later (#206); previous-round handler on load: new entry (R196-2); failed creation leaves the round active: fixed later (#198); new-round example with an id: not applicable; PDF/TIFF cache-hit test: new entry (R196-3); no-mutation checks seed no vote: new entry (R196-3); same-contest success tests: fixed later (#198, #206); own round with a foreign contestId: new entry (R196-3); ApiSpec PUT tests: fixed later (#218); list test's requested titles: fixed later (#201) |
| #197 | monument update on the default dispatcher: fixed later (#201); distribution step checks counts only: new entry (R197-1); gallery list foreign-contest test: new entry (R196-3); one-image fetch test without a media type: new entry (R197-2); media type over extension test: new entry (R197-2); isVideo ignores mime: not applicable |
| #199 | run.sh loses the app logs on an abort: new entry (R199-1) |
| #200 | SourcePaths without src/main/resources: not applicable; useSSL=false for a remote database: new entry (R200-2); forwarded-loopback readiness case: new entry (R200-2); readiness with another build or schema: new entry (R200-2); ErrorDocument 503 for a stopped backend: not applicable |
| #201 | canViewOrgInfo jury branch without the contest: fixed later (#206); files page blocks on the Commons count: new entry (R201-3); resetPassword .get: fixed later (#206); fallback to the first admin contest: fixed later (#206); EXPLAIN checks Using temporary only: new entry (R196-3) |
