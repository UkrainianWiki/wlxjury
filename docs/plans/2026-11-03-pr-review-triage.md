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
of `stack/71-investigations`, `6c00ed6e`); from R202-1 on, the followups tip `b85b0b60` (the
mirror of `stack/77-dependency-updates`, `17e145b3`); from R207-1 on, the top of the stack,
`stack/90-triage-fixes` (`f339aa13`, #273); from R220-1 on, `stack/101-multi-role-plan`
(`0f441881`, #284; the followups mirror `6b33abff` has the same tree). R193-4 is the first Medium.

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

- **PR:** #185 (02, tip `3ebe865f`), #186 (03, `155040c4`), #187 (04, `6a13cd09`); item 8
  from #202 (19, `feba8519`).
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
  8. **A write can outlive `evict`** (`evict` `:187-193`, `saveResized` `:630-668`; at the
     followups tip `b85b0b60`): when Commons replaces a file (`FileCheck.Changed`), `evict` lists
     and deletes the image's cached sizes, but a `saveResized` write still pending, or one started
     afterwards from source bytes read before the eviction, moves the old version's thumbnail in
     after the listing. Its registry claim stays, so the old thumbnail is served for good (or,
     with no claim left, registered by the next startup scan). Fix: track the pending writes per
     image directory and let `evict` wait for them before it lists; give each write the
     eviction count it started under and drop the write if it changed. (#202
     `LocalImageCacheService.scala:190`.)
- **Placement:** append after [`2026-11-01-open-followups.md` "Still open"](https://github.com/UkrainianWiki/wlxjury/blob/stack/62-docs-phase/docs/plans/2026-11-01-open-followups.md#still-open)
  as a "local image cache" item; items 1–5 together (item 8 with item 2: both track the write
  futures), item 6 with them if cheap, item 7 only after a measurement.

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
  `test/db/scalikejdbc/SharedPlayApp.scala:69`, `test/services/LocalImageCacheServiceDockerSpec.scala:226`;
  item 4 from #204 (21, `52ad411b`).
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
  4. `TestDocker.start` stops a container whose start ended just after `StartTimeout` from
     `onTimeout`, on the calling thread and with no bound (`test/db/TestDocker.scala:37-46`,
     `within` `:52-68`): a Docker that stopped answering then holds the failing spec past its
     timeout instead of reporting it. Fix: run the stop as `within` runs the start (a daemon
     thread, a bounded wait), keeping the `IllegalStateException` as the error. (#204 review
     remark on `TestDocker.scala:64-66`.)
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
  `test_deployment.py:232-234`; item 7 from #204 (21, `52ad411b`).
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
  7. **Restore step 1 reads as a refusal after `backup-skipped`** (`docs/deployment.md:232`,
     at the followups tip; #204 `docs/deployment.md:196`): "It refuses an operation without a
     complete backup", while the paragraph after the steps (`:240`) says such an operation is
     restored by switching the release back. An operator reading only the steps may hold off a
     back-out that would work. Fix: name the exception in step 1 and link the paragraph.
- **Placement:** operations practices §8 as a new OP item, with R200-1; items 2 and 3 first,
  item 7 with the next deployment docs change.

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

### R202-1: The Commons file check can apply a stale answer

- **PR:** #202 (19, tip `feba8519`). `app/services/imports/CommonsFileCheck.scala:136`, `:266`;
  `app/services/imports/DeletedFiles.scala:60`.
- **Severity:** Nit (each needs an import of the same file within the check's few hundred
  milliseconds; the next import or check puts it right).
- **At the newest tip:** all still there (`CommonsFileCheck.scala:131-140` `reusable`,
  `:202-216` `ask`, `:248-263` `write`; `DeletedFiles.scala:60` `restore`).
- **Items, failure scenarios and fixes:**
  1. **A pending check answers `Deleted` for a restored file:** `reusable` hands out a check
     still being asked (`case None`) whatever the file's state; only a finished `Deleted` is
     dropped for a file no longer marked deleted. An import that restores the file while the
     check runs leaves a thumbnail request joining it with `Deleted` (then `NotFound`). Fix:
     don't join a pending check for a file whose `restored` test holds, or re-read
     `ImageJdbc.isDeleted` before answering `Deleted`.
  2. **`write` applies Commons' answer to the rows as `ask` read them:** `stored` is the
     requesting `Image`, read without a lock; an import that updates the file between that read
     and `write`'s transaction is overwritten with the older answer, and a rename recorded from
     the wrong former title. Fix: after `markDeleted` locks the rows (`alsoLock`), re-read their
     file fields and skip `updateFileInfo` and `recordRename` for a row that differs, answering
     it `Unknown`.
  3. **A deletion mark from an outdated read:** a file back on Commons between `ask`'s request
     and `write`'s commit can be restored by a concurrent import before the mark commits
     (`restore` doesn't see it), so the mark stays and the file's votes stay aside in open rounds
     until an import of that file runs again. Fix: after the mark commits, ask Commons once more
     for the deleted files and restore those present, as `settle` runs after it; a failed request
     leaves the marks.
  4. **An import writes a textless file's monument id and description from an unlocked read**
     (#208, `ImageImportHandler.scala:302`; at `f339aa13` `:304-309` and `:333`): for a file
     fetched without its text, `writeImages` copies the stored monument id and description from
     `findWithCategoryLink` (no `FOR UPDATE`) and `updateIn` writes them back with the changed
     file fields. Two app instances importing the same file at once (each scheduler allows one
     import job, so only blue and green together) can overwrite a monument id the other just set.
     Fix: on that path update only the file-information columns, or re-read the two columns under
     the row lock `RoundWrites.lockFiles` already takes.
- **Placement:** append after open-followups "Still open"; take with the write-path plan's split
  of `CommonsFileCheck.write` into W12 and W13 (`2026-11-03-write-path-simplification.md`), which
  changes the same transaction.

### R203-2: Adding a pasted list waits for Commons and misses some files

- **PR:** #203 (20, tip `8a05dc37`). `app/services/distribution/RoundDistributionHandler.scala:135`,
  `:307-309` and `:134-135` (review remarks); `app/services/imports/FileTitles.scala:49`;
  `app/services/RoundService.scala:60`, `:221-222` (review remark).
- **Severity:** Low (item 1), Nit (the rest).
- **At the newest tip:** all still there (`RoundDistributionHandler.scala:143-148` `prepare`,
  `:160-196` `context`, `:205-227` `listChunk`, `:355-390` the list step;
  `FileTitles.scala:44-49`; `RoundService.scala:55-63` `submit`, `:222-243` `distributeNewFiles`;
  `RoundController.scala:373-377`).
- **Items, failure scenarios and fixes:**
  1. **A list-only job reads the round's category filters from Commons:** `prepare` and `step`
     build the whole `Context`, whose `categoryFileIds` asks Commons for each category operand
     (up to 5 minutes each). "Add files" to a round with a category filter waits for those
     lookups, and fails when Commons does, though the list step uses only the round, its jurors
     and its conflicts of interest. Fix: a list-only context without the category ids (and
     without the previous rounds' votes). #210 adds `categoryRule.requireValid()` to the same
     context (`RoundDistributionHandler.scala:187-188` at `f339aa13`): a round whose stored
     category settings are invalid fails before its list is added; the list-only context skips
     the check too.
  2. **A list step isn't bounded by `chunkRows`:** it takes `commons.maxIdsPerRequest` titles
     (up to 500) and inserts one row per juror and file in one transaction, while the
     distribution path sizes its window by `chunkRows / perImage`. Fix: take
     `max(1, min(maxIdsPerRequest, chunkRows / perImage))` titles a step.
  3. **A listed file marked deleted locally is dropped silently:** Commons answers its title
     with the same page id, `insertIgnore` keeps the row with its `deleted_at`, and
     `imagesIn` leaves it out, so the file is neither added nor reported missing. Fix: restore
     such files in the step (`DeletedFiles.restore`, as the import's write step does), or
     report them; a spec with a stored deleted row.
  4. **A caption with a wiki link hides the file:** `withoutWikiLink` starts at the first
     `[[`, so `File:A.jpg|Photo by [[User:Jane]]` reads as `File:User:Jane`. Fix: only a `[[`
     before the first `|` starts a link; add the case to `FileTitlesSpec`.
  5. **An empty list without "new files" distributes the filters' files:**
     `distributeNewFiles(titles = Nil, newFiles = false)` enqueues an ordinary distribution
     (`listOnly` needs a title), though its doc says without `newFiles` only the list is added.
     The controller never calls it so. Fix: refuse (or do nothing for) an empty list without
     `newFiles`.
  6. **A resubmitted form drops its changed list unsaid:** a resubmit (Back, edit the list,
     Submit) finds the round and returns before reading `titles`; the flash says only that the
     round was already created. Fix: when the resubmit carries a list, say some listed files may
     not have been added and point to "Add files".

  Items 7-9 are from #209 (26, tip `cb5f4a33`; `RoundDistributionHandler.scala:203`, `:210` and
  `:215-218`, a review remark), all still at `f339aa13` (`RoundDistributionHandler.scala:244-266`
  `listChunk`):

  7. **An all-stored list fails while Commons is down:** `listChunk` asks Commons for every title
     (`filesByTitles`, `:249`) before it reads the stored files; #203 skipped Commons when every
     title was stored. During an outage "Add files" with stored files only fails in `prepare`.
     Fix: on a failed lookup where every title is stored and the round doesn't leave out
     conflicts of interest, use the stored rows; rethrow otherwise (a successful lookup still
     refreshes them, which the conflict check reads).
  8. **A file renamed twice is not found as stored:** stored files are looked up by the listed
     and the fetched titles only (`:253`); a file stored under a third title is missed, so
     `insertIgnore` keeps its stale row and the conflict check reads its old author. Fix: look up
     the fetched page ids too.
  9. **A listed title Commons now has as another file keeps the stored one:** `fresh` drops a
     fetched file whose title is stored under another page id (`:262-264`, as the comment says),
     so the round gets the stored file (deleted on Commons) and the new file never enters it.
     Fix: when Commons answers the title with another page id, take Commons' file and leave the
     stored one to the deleted-file check.
- **Placement:** with [R203-1](#r203-1-a-pasted-file-list-over-100-kb-is-refused), in the
  Montage parity plan's import batch (gap row 24, order item 9); items 4-6 are small and can go
  with the next round form change.

### R203-3: Files, region and sign-out tests that would pass a regression

- **PR:** #203 (20, `8a05dc37`), #205 (22, `e70ed585`), #206 (23, `398fc43d`): review remarks and
  `test/controllers/SignOutAndSwitchSpec.scala:45`, `:104`.
- **Severity:** Nit (test gaps; the code they cover is right today).
- **At the newest tip:** all still there.
- **Items:**
  1. No controller example posts a contest import list with a line that names no file
     (`ImageController.importImages`, `FileTitles.unreadable`; `test/controllers/ImportFilesPageSpec.scala`
     posts an empty list only). Post `https://commons.wikimedia.org/w/index.php?curid=1`, expect
     400, no import job and the source unchanged.
  2. "count the regions on every view with the cache switched off"
     (`test/controllers/ContestFilesPageSpec.scala:395-407`) drops both pages and checks only that
     the cache loaded nothing; uncached counts of 0 would pass. Assert `All files (8)` on both.
  3. The whole-round `regionCounts` examples (`test/db/scalikejdbc/RegionCountsSpec.scala:75-90`)
     have no `rated = Some(false)`, the one case of the whole-round query grouped by page (the
     juror example doesn't run that query). Add a `wholeRound(rated = Some(false))` assertion.
  4. The GET `/logout` example (`SignOutAndSwitchSpec.scala:41-46`) accepts a session without
     the user name, so a GET that signs out passes. Assert the response sets no session, or one
     that still names the user.
  5. The GET `/switch-contest/20` example (`SignOutAndSwitchSpec.scala:96-104`) checks only that
     the contest isn't 20; a GET that clears the session or switches elsewhere passes. Assert the
     session is unchanged (no session cookie set).
  6. "see a juror's votes, and all together" (`test/controllers/OwnVotesSpec.scala:157-165`)
     checks statuses only, and both jurors have the same ratings, so a view of the wrong juror's
     votes passes. Give the jurors distinct ratings and assert one only the requested juror has
     (gallery, by rate, file list, CSV), and that the all-jurors view differs.

  Items 7-11 are from #208 (25, `6a3dbef2`) and #210 (27, `25e7df01`) review remarks, all still at
  `f339aa13`:

  7. The no-text reimport example (`test/services/imports/PageImportSpec.scala:122-133`) asserts
     only the kept monument id and description; a no-text branch that kept the whole stored row
     would pass. Give the second fetch another `width` (a `changedImages` field; `pageUrl` isn't
     one) and assert it is written.
  8. The Armenia country filter (`test/db/scalikejdbc/ContestFilesDbSpec.scala:112`) lists only
     the category's files 1-4; file 13, linked to Armenia but not in the category, is left out of
     the filter's ids, so losing the category restriction passes. Add `13L` to the filter.
  9. `test/controllers/AssetDigestSpec.scala` writes its own `.md5` and nothing checks the
     packaged output: removing `pipelineStages := Seq(digest)` passes every test (CI's
     `deployment.yml` runs `sbt dist` but asserts nothing on assets). Assert one asset's
     `<name>.md5` and `<md5>-<name>` in the staged output.
  10. "not stored again" (`test/services/distribution/RoundFileListSpec.scala:120`) checks the
      page id and title only, with Commons' copy equal to the stored row; an overwrite passes.
      Give Commons' copy another `url` and assert the stored row keeps its own.
  11. The locked category operation of an existing round is tested only in Jest on hand-written
      markup (`javascript-test/category-filter.test.js:44-48`). Add a `RoundEditPageSpec` example
      that renders the edit page and asserts, on the rendered HTML, that the operation can't be
      changed and that the stored value is still submitted.
- **Placement:** [testing practices §12](https://github.com/UkrainianWiki/wlxjury/blob/stack/68-juror-guide/docs/testing/practices.md#12-where-the-code-doesnt-follow-these-yet-for-triage)
  as a new T item, with [R196-3](#r196-3-authorization-and-query-tests-that-would-pass-a-regression);
  with the CI batch's test fixes.

### R204-1: Contest file paging, window and video sources

- **PR:** #204 (21, tip `52ad411b`). `app/controllers/GalleryController.scala:441`, `:464`;
  `app/views/large/contestFile.scala.html:134-135`.
- **Severity:** Nit.
- **At the newest tip:** all still there (`app/controllers/Pager.scala:14`;
  `GalleryController.scala:485-497` `contestFileLarge`; `contestFile.scala.html:90-93`,
  `:129-133`, `large.scala.html:64-65`, `thumbsBar.scala.html:25-26`,
  `galleryByRate.scala.html:92-93`).
- **Items, failure scenarios and fixes:**
  1. **A huge page number shows the first page:** `Pager.offset` computes `(page - 1) * pageSize`
     in `Int`; past about 143 million it wraps negative and `max(0, …)` makes it 0, so the files
     page lists page 1 while the pager names the requested one (every gallery's `Pager` too).
     Fix: compute in `Long` and clamp to `Int.MaxValue` (an empty page).
  2. **The large view's rank and window are two reads:** `fileRank` and `listFiles` run as
     separate statements; files added before the target between them shift the window, the
     target falls out of it, and the view redirects to page 1. Fix: read both in one
     transaction with a consistent snapshot, or retry once when the target isn't in the window.
  3. **Every video is declared WebM:** the `<source>` tags say `type="video/webm"` whatever the
     file is, so a browser without WebM skips an Ogg (or other) video it could play. Fix: the
     stored `mime` as `type`, none when unknown.
  4. **A deleted video gets an empty player:** the video branches render Commons' URL also for a
     file marked deleted, with no fallback, while a deleted image shows its cached thumbnail or a
     grey box. Fix: for `deletedAt`, show the cached poster (or the grey box) instead.
- **Placement:** append after open-followups "Still open"; items 3 and 4 with the next large
  view change.

### R205-1: Region filter and region count cache nits

- **PR:** #205 (22, tip `e70ed585`) review remarks: `app/db/scalikejdbc/rewrite/ImageDbNew.scala:253-254`,
  `app/db/scalikejdbc/RegionCountCache.scala:50-56`, `app/services/imports/ImageImportHandler.scala:250-252`.
- **Severity:** Nit.
- **At the newest tip:** all still there (`ImageDbNew.scala:465-467`; `RegionCountCache.scala:45-61`;
  `ImageImportHandler.scala:327`).
- **Items, failure scenarios and fixes:**
  1. **`%` and `_` in a region reach the sub-region `LIKE`:** with `subRegions` (a "Віа Регіа"
     special nomination round) a region of two characters or fewer goes into
     `s.monument_id like '<region>%'` unescaped, so `region=0_` lists a juror's files of several
     regions (their own round's files; nothing outside it). Fix: escape both, as the other
     branch's `regionOf` check makes it exact.
  2. **Concurrent misses each count the category:** a miss runs the count query per request,
     with no load in flight shared, on the 8-thread blocking pool the slow pages share. Fix: one
     load per category (a future in the cache), the versions checked as now; measure first
     (performance practices §3.1).
  3. **Any changed file clears every category's counts:** `writeImages` calls
     `invalidateMonumentIds()` for a change of url, size or description too, though counts
     depend only on links and monument ids. Fix: invalidate only when a changed file's monument
     id differs from the stored one.
- **Placement:** append after open-followups "Still open"; item 2 only after a measurement.

### R205-2: agent-check and region benchmark tools

- **PR:** #205 (22, tip `e70ed585`). `scripts/agent-check.py:34`, `:42`, `:72`;
  `test/gatling/setup/RegionCountBenchmark.scala:59`, `RegionCountPairedBenchmark.scala:129`; review
  remarks on `RegionCountBenchmark.scala:268-274` and `scripts/test_agent_check.py:31-37`.
- **Severity:** Nit (developer tools; no effect on the app).
- **At the newest tip:** all still there (`agent-check.py:25-43` `summarize`, `:79`;
  `RegionCountBenchmark.scala:58`, `:245`; `RegionCountPairedBenchmark.scala:67`).
- **Items, failure scenarios and fixes:**
  1. `summarize` keeps the first three failure anchors only, so a compile error after three
     earlier matches is summarized as `Compilation failed` without its `file.scala:line`. Take
     anchors from the start and the end, and say how many were left out.
  2. The truncation notice is added after `max_chars`, so a summary reaches 6,048 characters.
     Reserve its length inside the limit.
  3. The wrapper reads the whole log into memory to summarize it; a very large log can exhaust
     memory after the command ended. Stream it line by line, keeping the summary lines and the
     failure windows.
  4. `RegionCountBenchmark.main` catches nothing: an exception after the fixture server started
     leaves Play's non-daemon threads running and the forked JVM hangs. Catch `Throwable`, print
     it and `sys.exit(1)`, as `RegionCountPairedBenchmark` does.
  5. `RegionCountPairedBenchmark` takes `bench.pairs` and `bench.roundPairs` of 0, then fails on
     an empty sample after writing its output files. `require` both positive before the fixture
     starts.
  6. `bench.maxSeconds` stops starting runs but doesn't bound one in flight; a query that never
     returns keeps the benchmark running. Say so in the scaladoc, or set a statement timeout.
  7. The output-bound test (`scripts/test_agent_check.py:30-37`) never reaches the truncation
     branch: add a selected line over `max_chars` and assert the notice and the bound (with
     item 2).
- **Placement:** items 1-3 and 7 with the next change of the agent tools (CLAUDE.md "Agent
  efficiency" names them); items 4-6 with [R190-1](#r190-1-gatling-fixture-settings-and-setup-checks)
  and [R197-1](#r197-1-gatling-simulation-caps-and-checks), in performance practices §1 follow-ups.

### R206-1: A missing round's comment and the pollers' denials

- **PR:** #206 (23, tip `398fc43d`). `app/controllers/ImageDiscussionController.scala:42`,
  `javascript-test/job-status.test.js:137`, `public/javascripts/cache-status.js:46`.
- **Severity:** Nit.
- **At the newest tip:** all still there (`ImageDiscussionController.scala:29-45`;
  `public/javascripts/job-status.js:94`; `cache-status.js:48-52`).
- **Items, failure scenarios and fixes:**
  1. `addComment` answers a round id that doesn't exist with the 403 page, while a missing
     resource is a 404 elsewhere (`error.round.not.found`). Answer 404 when the id names no
     round; keep the denial for a round of another contest.
  2. `JobController` answers a job the user may not see with the same JSON 404 as a missing one,
     and `pollJobStatus` treats only 401 and 403 as denied: on a 404 it blanks the text, hides
     Retry and Cancel and stops without the denied text. Treat 404 as denied (and test it).
  3. `pollCacheStatus` stops on 401 or 403 without a word, leaving the last progress text
     ("… running…"). Write the element's `data-denied-text`, as `job-status.js` does.
- **Placement:** append after open-followups "Still open"; with the next change of the job or
  cache pages.

### R207-1: An international import's countries: the gate, category imports, a missing file

- **PR:** #207 (24, tip `b04ad4c4`). `app/services/imports/ImageImportHandler.scala:212`, `:215`,
  `:232-237` (review remark); `test/services/imports/PageImportSpec.scala:74`. #208 (25, tip
  `6a3dbef2`): `ImageImportHandler.scala:211`; `ContestCountries.scala:32-33` (review remark).
- **Severity:** Low.
- **At the newest tip:** all still there (`ImageImportHandler.scala:203` the pasted list's
  countries, `:209-213` the listing branch's `byCountry`, `countryOf` and `kept`, `:330`
  `addCountries`; `PageImportSpec.scala:94-99`).
- **Items, failure scenarios and fixes:**
  1. **The country filter is decided by the root category's own files:** `byCountry` holds when
     `Category:Images from <contest> <year>` has files of its own (`categoryFileCount`, direct
     members only). A root that holds only its countries' categories, or a count Commons doesn't
     give, turns the filter off: a winners page's logos and icons are imported with the photos,
     and no file gets its country. The spec "import all its files for a contest without country
     categories" sets the root's count to 0 but gives file 11 an Armenia category. Fix: decide
     from the countries found (any fetched file in a contest country category), the root's count
     at most a hint; give that spec's files no country category.
  2. **A category or template import of a contest of many countries gives no file its country:**
     `byCountry` (and so `countryOf`) is only for a page source, while a pasted list asks for the
     countries whenever `ContestCountries.ofManyCountries` holds. Those files are missing from the
     country menu and filter. Fix: compute `countryOf` for every listing source of a contest of
     many; keep `kept` tied to `byCountry`.
  3. **A file Commons sends without image info fails the step:** `countries` maps every fetched
     page with an id, but `ImageJdbc.fromPage` can leave a page out of `images`; for a file not
     stored, `addCountries` then inserts a link to a missing `images` row, which
     `FK_category_member_page_id` (V65) refuses, so the step fails, and fails again on retry.
     Fix: keep only the countries of files written or already stored.
- **Placement:** append after open-followups "Still open"; with the next import change (the
  import-top-of-a-round plan, [R208-2](#r208-2-the-top-of-a-round-import-plan-has-gaps),
  touches the same branch).

### R207-2: Gallery paging, redirects and the region menu

- **PR:** #207 (24, `b04ad4c4`): `app/controllers/LargeViewController.scala:60`,
  `app/controllers/Pager.scala:25`, `public/javascripts/region-menu.js:55`; review remarks
  `GalleryController.scala:194-199`, `RoundList.scala:164-166`, `:305-309`. #208 (25, `6a3dbef2`):
  `Pager.scala:42`, `app/views/pageLinks.scala.html:23`, `public/javascripts/pager-jump.js:11`.
- **Severity:** Nit.
- **At the newest tip:** all still there (`LargeViewController.scala:67-69`; `Pager.scala:3-4`,
  `:9-26`, `:42`; `GalleryController.scala:91-98`, `:186-191`, `:218-219`, `:427`;
  `RoundList.scala:291-292`, `:470-471`; `ImageDiscussionController.scala:60-64`
  `addCommentTo`; `region-menu.js:41-60`; `pageLinks.scala.html:19-22`; `pager-jump.js:9-16`).
- **Items, failure scenarios and fixes:**
  1. **A vote in a round closed meanwhile loses the list's query:** the redirect to the gallery
     drops `filter`, `module` and `sort`. Fix: pass `request.queryString`, as `listCurrent` does.
  2. **A page past the last one shows a pager for an empty page** ("20–20 of 20", a previous
     link to another empty page). Fix: clamp the page to the page count (at least 1) where the
     pager is made, with [R204-1](#r204-1-contest-file-paging-window-and-video-sources) item 1.
  3. **Page 0 is documented as the whole list** (`Pager`'s doc) but shows `pageSize` files;
     nothing links it. Fix: correct the doc.
  4. **`/filelist/...?format=html` renders the wiki list:** `fileList` takes `format` and never
     uses it; `galleryOf` passes `"wiki"`. Fix: pass it through (`wiki` or `html`, else 400), or
     drop the parameter and the `html` branch.
  5. **A comment returns to the commenter's own list:** `commentUrl` carries no `asUserId` and
     `addCommentTo` builds the list for `user.getId`; an organizer commenting in a juror's list
     lands in their own (page 1 if they have no vote for the file). Fix: carry the viewed juror
     and check it with `GalleryService.viewAs`.
  6. **The region menu's counts stay as first read:** it loads once per page, so after a vote in
     the gallery a filtered list's counts are stale until reload. Fix: reset its state when a
     vote succeeds (an event from `ug-theme-tiles.js`), or read on every opening.
  7. **`urlTemplate` replaces every `987654321`:** an id equal to the sentinel would be replaced
     too (theoretical). Fix: replace the last occurrence only.
  8. **The page box:** without `novalidate` the browser refuses a number outside 1 to `pages`
     before `pager-jump.js` can clamp it; `parseInt` reads `1e1` as 1. Fix: `novalidate`, and
     `Number()` with an integer check.
- **Placement:** append after open-followups "Still open"; with the next gallery change.

### R207-3: Request timing and Gatling tool nits

- **PR:** #207 (24, `b04ad4c4`): `app/modules/RequestTiming.scala:96`, `scripts/timing-summary.py:120`
  and `:58-62` (review remark), `test/gatling/setup/GatlingConfig.scala:55`,
  `test/gatling/simulations/JurorClickSimulation.scala:60`.
- **Severity:** Nit (development tools).
- **At the newest tip:** all still there (`GalleryController.scala:218`, `:430`, `:505`;
  `timing-summary.py:58-62`, `:104-106`; `GatlingConfig.scala:55`,
  `GatlingTestFixture.scala:109-117`; `JurorClickSimulation.scala:59-62`).
- **Items:**
  1. The file list, the contest files page and the contest large view render without
     `RequestTiming.render`, so their `renderMs` is 0 and `timing-summary.py` counts the
     rendering as action time. Wrap them.
  2. `timing-summary.py` never reads `status`: a redirect or error under a case's label counts
     in its percentiles. Keep the expected status per label and report how many were left out.
     Its fallback label drops the query string, merging `/query?module=gallery` and `byrate`;
     keep it or say so.
  3. An unknown `gatling.pool` (`hiraki`) runs on the default pool under the requested name;
     only `dbcp` is refused. Refuse anything but `hikari` and `small`.
  4. When a vote's redirect has no `pageid` (the round closed, a one-file list), the simulation
     keeps the previous page id and votes on that file again. Stop the visit's vote loop then.
- **Placement:** with the next change of the Gatling or timing tools.

### R208-1: A contest's file countries: the choice, stale links and the country menu

- **PR:** #208 (25, tip `6a3dbef2`). `app/services/imports/ContestCountries.scala:49`, `:81`;
  `app/db/scalikejdbc/ImageJdbc.scala:530`; review remarks `CategoryJdbc.scala:72`,
  `GalleryController.scala:435-436`.
- **Severity:** Low (item 1), Nit (the rest).
- **At the newest tip:** all still there (`ContestCountries.scala:47-51` `candidates`, `:58-68`
  `choose`, `:76-83` `fileCountries`; `ImageJdbc.scala:660-672` `categoryCountryLinks`;
  `CategoryJdbc.scala:61-73` `addCountries`; `GalleryController.scala:480-481`
  `countryCounts`, `:525-529`; `PageImportSpec.scala:135-146`).
- **Items, failure scenarios and fixes:**
  1. **The files page shows a country from shared counts, not the import's choice:** an import
     adds the chosen country's link and never removes one (on purpose: `category_members` also
     holds the jury tool's categories), and `fileCountries` picks a file's country by the
     categories' file counts, then name length. After Commons moves a file to another country,
     the old link stays and, with more files, wins: the file stays under the old country (the
     spec "add a file's new country" leaves 11 in Armenia and Georgia, shown as Armenia). Fix:
     store each file's chosen country per contest, written by every import, and read it in
     `contestFilesView`; `fileCountries` only for files without one.
  2. **One category per country name hides a parent:** `candidates` keeps one category per name
     (the upload campaigns' prefix first) and `choose` tests parents against that one only; if
     England's parent is the plain-prefix United Kingdom category, nothing matches and the
     shorter `England` is stored. Fix: keep every category per name and test parents against
     each; add that case.
  3. **A contest whose category is a country category loses that country:** the query's
     `cc.category_id <> m.category_id` drops the category's own link, so its files get no
     country (missing from the menu; choosing it is a 404). Fix: drop the inequality, as the
     prefix test already keeps only country categories.
  4. **The deleted view's country menu counts all files:** `countryCounts` counts
     `countryFiles` whatever `deletedOnly` (and region); the list behind each link has the
     deleted files only. Fix: count the view's list per country.
  5. **Every reimport clears the countries' region counts:** `addCountries` returns all the
     pages' country categories, not only those that gained a link, so each reimport of an
     unchanged list evicts their cached counts (a 0.1-0.15 s recount at 38k files). Fix: return
     the categories whose insert added a row.
- **Placement:** item 1 with the next international contest work (it needs a table or column);
  items 2-5 with the next change of `ContestCountries` or the files page.

### R208-2: The top-of-a-round import plan has gaps

- **PR:** #208 (25, `6a3dbef2`). `docs/plans/2026-10-29-import-top-of-another-contests-round.md:35`,
  `:37`, `:48`; review remarks `:35-38`, `:47-48`.
- **Severity:** Low (item 5, an authorization gap), Nit (the rest). The plan isn't implemented.
- **At the newest tip:** all still in the plan (lines 25-49 "The feature", 51-61
  "Authorization").
- **Items and fixes:**
  1. **Deleted winners can't be reported:** step 3 leaves out and reports files deleted on
     Commons, but `DistributionSource.rank` already drops rows with `deleted_at`, so the job never
     sees them and fills the place with the next file. Query the deleted ranked files separately
     and report them; don't change `rank` (distribution relies on it).
  2. **The tie-break text** in step 2 says "rate sum, page id"; `rank` (and line 13) orders by the
     rate sum, then the raw sum, then page id.
  3. **Ratings nobody supplies:** step 4's details list each file's rating, but `rank` returns
     page ids only. Have it return the scores, or add a step that reads them.
  4. **The rating of the first file left out:** `rank` returns at most N; ask for N + 1, import
     N, and show the Nth and the (N+1)th ratings, or drop that promise.
  5. **The source ratings reach the target's admins:** the job's details (with ratings) are
     shown to any admin of the target contest, while the plan checks only the submitter's right
     to see the source round. Leave ratings out of the details, or check the viewer like the
     submitter.
- **Placement:** amend the plan before it is implemented.

### R209-1: Conflicts of interest: the form's confirmation, legacy names, records

- **PR:** #209 (26, tip `cb5f4a33`). `app/controllers/RoundController.scala:135`,
  `app/views/editRound.scala.html:72`, `app/db/scalikejdbc/CoiMembers.scala:30`,
  `conf/db/migration/default/V67__Users_email_nullable.sql:8`, `app/db/scalikejdbc/Round.scala:278`,
  `app/services/imports/ImageText.scala:56`; review remark `UserService.scala:74-82`.
- **Severity:** Nit.
- **At the newest tip:** items 1 and 3-5 still there (`RoundController.scala:147-155`
  `coiCounts`, `:272-290` the binding-error page; `RoundService.scala:188-210`;
  `ImageText.scala:55-56`; `UserService.scala:101`, `:150-153`). Item 2: #225 normalizes every
  wiki account written or looked up (`User.normalizeWikiAccount`, `User.scala:741-757`, refusing
  `:`), so only rows stored before it are left.
- **Items, failure scenarios and fixes:**
  1. **Saving "Leave out conflicts of interest" can skip its confirmation:** for a contest without
     a category `coiCounts` gives no round count, and the binding-error page passes no counts, so
     `data-coi-round-files` is 0; the save then disqualifies the round's conflict files (their
     votes set aside, undone on the conflicts page) without the prompt. Fix: count the round's
     files without the category; pass `coiCounts` on the binding-error page.
  2. **Wiki accounts stored before #225:** a row such as `user:Board member` or
     `Користувач:Петро` isn't matched by a normalized lookup, so adding the person by wiki
     account makes a second account, and a localized namespace prefix (which `CoiMembers.normalize`
     doesn't strip) keeps their files out of the conflicts. Fix: a one-off report of
     `users.wiki_account` values that `normalizeWikiAccount` changes or refuses, then a reviewed
     merge or rewrite.
  3. **The job's settings snapshot has no `excludeCoi`**, and the comment beside it says a
     round's settings don't change once created, which `excludeCoi` does. Add it and correct the
     comment.
  4. **`storedAuthor` cuts by UTF-16 unit** (`take(MaxAuthor)`): a supplementary character at the
     limit leaves a lone surrogate. Cut by code point.
  5. **An existing account added with the role `no` gets the greeting:** `mayBeGreeted` checks
     `NON_ACCESS_ROLES`, which lacks `no`. Use `INACTIVE_ROLES`.
- **Placement:** append after open-followups "Still open"; item 2 with the account-merge work,
  the rest with the next round form or COI change.

### R209-2: The logging-config plan's rollout misses the systemd drop-in

- **PR:** #209 (26, `cb5f4a33`). `docs/plans/2026-10-30-logging-config-in-releases.md:37`, `:42`.
- **Severity:** Low (following the plan as written stops production logging to the file).
- **At the newest tip:** both still there (plan lines 29-50, not implemented;
  `conf/systemd/wlxjury.service.d/deployment.conf:10` sets its own `ExecStart` with
  `-Dlogger.file=/etc/wlxjury-runtime/logback.xml`).
- **Items and fixes:**
  1. The drop-in resets `ExecStart`, so step 2's change to the unit template does nothing while
     it is installed, and step 4 removes the file it still names. Change the drop-in's
     `-Dlogger.file` in the same rollout, and remove the runtime file only once nothing names it.
  2. Step 3 offers keeping the runtime file instead of pointing the unit back for a backout, but
     the unit then still names the release's file. Say a backout to an older release needs the
     unit (and drop-in) pointed back to the runtime file.
- **Placement:** amend the plan before its rollout.

### R210-1: A stored category operation the form doesn't know

- **PR:** #210 (27, tip `25e7df01`). `app/controllers/EditRound.scala:60`,
  `app/views/round/edit/imageFiltering.scala.html:73`.
- **Severity:** Nit (only a value written by hand: V70 converts the legacy `1`, `-1`, `0`, and the
  form refuses others).
- **At the newest tip:** both still there (`CategoryRule.scala:55-62` throws for an unknown
  clause; `EditRound.scala:166` reads it to fill the form; `imageFiltering.scala.html:121-127`).
- **Items and fixes:**
  1. Opening such a round's edit page is a server error, so the organizer can't choose another
     operation. Fill the form without throwing (an unknown operation shown as such), keeping the
     stored value until a valid one is saved.
  2. Redisplaying a refused submission with an unknown operation selects the first option
     (none), so submitting again saves the round without a category filter. Keep the submitted
     value as an option, so the form is refused again.
- **Placement:** with the next round form change.

### R220-1: The password-busy page and user import's silent failures

- **PR:** #220 (37, tip `f39e2164`). `app/controllers/UserController.scala:283`, `:403`;
  `test/controllers/PasswordStorageSpec.scala:167`.
- **Severity:** Nit.
- **At the newest tip:** all still there (`UserController.scala:510-513` the `Busy` catch,
  `:554-558` `passwordBusy`, `:740-741` `importUsers`; `PasswordStorageSpec.scala:339`).
- **Items, failure scenarios and fixes:**
  1. **One's own password change under load gets the admin's form:** with no hashing turn,
     `updateUser` answers through `passwordBusy`, which renders `views.html.editUser` for one's own
     profile too: the 503 page has no API tokens card (root) or linked accounts card and takes the
     edited account's contest, unlike every other refusal of one's own form (`badRequest` uses
     `ownProfile`). Fix: in `passwordBusy`, for `editedUser.id == user.id`, return
     `ownProfile(ServiceUnavailable, user, userForm = Some(form.withGlobalError("password.busy")))`
     with `Retry-After`; a spec with every turn taken.
  2. **A failed import line leaves no trace:** `case Failure(_)` counts the user as not added and
     goes on, with no log line (before #220 the `Try` dropped it too, without even a count; the
     review's "it reached `ErrorHandler`" is wrong). A database or mail failure after `User.create`
     is then invisible. Fix: log
     the exception's class (not its message: emails, SQL values) with the importing account's id
     at WARN, on `security.account` or the controller's logger.
  3. **`withEveryTurnTaken` can hang the spec:** `holding.await()` has no bound; a holder that gets
     no turn within `maxWait` throws `Busy` and never counts down. Fix:
     `require(holding.await(10, SECONDS), "not every turn was taken")`.
- **Placement:** a new commit at the top of the stack (small, one controller and one spec).

### R223-1: Two-step rounds and criteria plans: re-apply, the count check, the damage query

- **PR:** #223 (40, tip `928d1b9f`). `docs/plans/2026-11-02-round-images-two-step.md:220`, `:228`;
  `docs/plans/2026-11-02-criteria-rounds.md:166`.
- **Severity:** Low (followed as written, item 1 prunes files that still pass the filters and item
  2 refuses a valid "Distribute new files"; item 3 lets U12 miss damaged votes or "repair" partial
  ones). P3 isn't built; C0 hasn't been run.
- **At the newest tip:** all still in the plans (round images `:284-286` Fill's anti-join,
  `:293-294` the distribute check, `:300-301` re-apply and Prune, `:312-313` adding a juror;
  criteria `:168-170`, `:184-186` the damage queries, `:201-202`, `:921-922` U12).
- **Items and fixes:**
  1. **Re-apply can't see the rows it keeps:** Fill's anti-join skips every existing `round_image`
     row, while re-apply "marks the rows it sees" and Prune deletes the unseen `fill` rows, so a
     file still passing the filters is never seen and is pruned. Fix: in re-apply mode, scan the
     existing `fill` rows against the filters (mark the passing ones) separately from the insert's
     anti-join, then prune.
  2. **The distribute count check assumes one juror count for all rows:** "every `in` row's
     `assigned` = the round's jurors" fails after a juror is added and "Distribute new files" runs
     (new files get the new count, existing ones keep theirs, as the plan intends). Fix: check the
     rows this job assigned against its own expected count, and the others unchanged.
  3. **The S4 damage query reads only live votes, and, run after V83, counts partial votes:** a
     damaged vote moved aside (a deletion on Commons) is neither listed nor repaired by U12; after
     U5 a partly rated vote (rate 0, criteria sum above 0) is listed as damage. Fix: add the
     `selection_deleted`/`vote_aside` rows (a `UNION ALL`), and in the "after V82" block count a
     criteria vote only when all its round's criteria are rated (or note that the block must run
     before V83); U12's repair the same.
  4. **Superseded sketches read as current:** round images §5 `:179-180` (inserts add
     `assigned + n`: P2a recomputes instead), `:254-255` (`restoreVotes` by status `deleted`: P1
     restores every aside vote but a `dq` pair's), §8 `:380` (top-N from `round_image` rows: P2b
     kept it on the votes). Fix: a "superseded by §13/§16/§17" note on each.
- **Placement:** amend the plans in a docs commit at the top of the stack (items 1-2 before P3 is
  started, item 3 before C0 is run).

### R223-2: A clean N1 data check doesn't rule out a reverted role

- **PR:** #223 (40, `928d1b9f`). `docs/security/2026-11-02-rereview-report.md:62`.
- **Severity:** Low (an operator could read a clean check as "never exploited").
- **At the newest tip:** still there (`docs/history/security/2026-11-02-rereview-report.md:60-66`,
  a record; `docs/security/register.md:33`, the N1 production row: "open: … the leftover query").
- **Failure scenario:** an admin sets a membership role that includes root (N1), uses root, then
  sets the role back: no `user_contest.role` anomaly and no `users.is_root` remain, and the
  role-change POSTs leave no app log before S1 (no audit log, P19).
- **Log check (the maintainer's decision; run read-only on 2026-11-10):**
  - window: the jury vhosts' access logs (`other_vhosts_access.log*`, all six jury hosts) from
    2026-10-27 00:00 (the oldest kept; daily rotation, 14 days) to the hotfix deploy, 2026-11-02
    15:01 CET;
  - `POST /users/save`: 21 requests (one admin's jury-setup burst on jury.wikilovesearth.org, one
    juror's own-profile saves, 2 by a root session);
  - root-only pages (`/admin/allUsers`, `/contests`, `/contests/save`, `/contests/import`, the API
    token admin and image check routes; most other `/admin/...` routes are contest admins' pages):
    answered 200 only to 2 IP addresses, which had root sessions from the first logged day; none
    came after a `users/save`; no 403 on a root-only path;
  - result: no sign of exploitation in the window, but not conclusive. The logs miss the earlier
    vulnerable period and record IP addresses, not accounts, and no form bodies; the 2 addresses
    aren't identified yet by the maintainer. The `is_root` audit stays the main evidence.
- **Fix:** say in the register's N1 production row that a clean data check doesn't rule out a
  reverted role, and record this log check and its limits.
- **Placement:** a docs commit at the top of the stack (register only; the report stays a record).

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

### CodeRabbit, PRs 202-206

CodeRabbit's 31 inline comments and 15 review-level remarks on #202-#206 (the remarks are in
the review bodies, under "Follow-ups" and "Nitpick comments", which `threads.py` doesn't list;
#202 has none), classified against the followups tip `b85b0b60` (the mirror of
`stack/77-dependency-updates`, `17e145b3`); one reply per thread, and the review-level remarks in
one comment per PR (#203, #204, #205, #206). New entries:
[R202-1](#r202-1-the-commons-file-check-can-apply-a-stale-answer),
[R203-2](#r203-2-adding-a-pasted-list-waits-for-commons-and-misses-some-files) (Low),
[R203-3](#r203-3-files-region-and-sign-out-tests-that-would-pass-a-regression),
[R204-1](#r204-1-contest-file-paging-window-and-video-sources),
[R205-1](#r205-1-region-filter-and-region-count-cache-nits),
[R205-2](#r205-2-agent-check-and-region-benchmark-tools),
[R206-1](#r206-1-a-missing-rounds-comment-and-the-pollers-denials); none Medium or above. Items
added to [R185-1](#r185-1-local-image-cache-bookkeeping-gaps) (item 8, writes that outlive
`evict`), [R191-1](#r191-1-shared-test-db-helpers-leak-state-on-failure) (item 4, the unbounded
stop in `TestDocker`) and [R200-2](#r200-2-release-tools-copies-file-modes-and-public-checks)
(item 7, restore step 1). The 100 KB form limit (#203 `editRound.scala.html:71`) is the
existing [R203-1](#r203-1-a-pasted-file-list-over-100-kb-is-refused).

| PR | Inline comments: class |
|---|---|
| #202 | SelectionJdbc.scala:280 target votes in the other table: fixed later (#239); CommonsFileCheck.scala:136 pending Deleted for a restored file: new entry; CommonsFileCheck.scala:266 write applies an unlocked snapshot: new entry; DeletedFiles.scala:60 mark from an outdated read: new entry; ImageImportHandler.scala:260 restored file of another contest not refreshed: fixed later (#209); LocalImageCacheService.scala:190 writes outlive evict: new entry (R185-1) |
| #203 | RoundDistributionHandler.scala:135 list-only job reads the category filters: new entry; RoundDistributionHandler.scala:183 stored title not checked on Commons: not applicable; RoundDistributionHandler.scala:309 stored title not updated: fixed later (#209); FileTitles.scala:49 wiki link in a caption: new entry; RoundService.scala:60 resubmit drops a changed list: new entry; editRound.scala.html:71 list over 100 KB refused: new entry (R203-1) |
| #204 | GalleryController.scala:441 page offset overflow: new entry; GalleryController.scala:464 rank and window in two reads: new entry; contestFile.scala.html:134 video type always WebM: new entry; contestFile.scala.html:135 deleted video without a fallback: new entry; deployment.md:196 restore step 1 and backup-skipped: new entry (R200-2) |
| #205 | agent-check.py:34 first three anchors only: new entry; agent-check.py:42 notice past max_chars: new entry; agent-check.py:72 whole log read: new entry; RegionCountBenchmark.scala:59 no exit on an exception: new entry; RegionCountPairedBenchmark.scala:129 pairs of 0: new entry |
| #206 | GalleryController.scala:305 juror lookup with round 0: fixed later (#207); ImageDiscussionController.scala:42 missing round answered 403: new entry; LoginController.scala:60 returnTo on the first-account sign-up: fixed later (#225); LoginController.scala:83 CSRF token dropped from the session: not applicable; main_large.scala.html:47 no-JavaScript binary vote: not applicable; job-status.test.js:137 404 not treated as denied: new entry; cache-status.js:46 no denial text: new entry; SignOutAndSwitchSpec.scala:45 GET logout assertion: new entry (R203-3); SignOutAndSwitchSpec.scala:104 GET switch assertion: new entry (R203-3) |

| PR | Review-level remarks: class |
|---|---|
| #203 | list step leaves a locally deleted file out: new entry (R203-2); list step not bounded by chunkRows: new entry (R203-2); empty list without new files: new entry (R203-2); import list line naming no file untested: new entry (R203-3) |
| #204 | TestDocker stop after a late start unbounded: new entry (R191-1); another contest's admin on the files list untested: fixed later (#209) |
| #205 | sub-region LIKE wildcards: new entry (R205-1); concurrent region-count loads: new entry (R205-1); invalidation on any changed file: new entry (R205-1); maxSeconds doesn't bound a run in flight: new entry (R205-2); cache-off test asserts no count: new entry (R203-3); oversized selected line untested: new entry (R205-2); whole-round unrated count untested: new entry (R203-3); cached-image test reads no body: fixed later (#206) |
| #206 | organizer, admin and root vote views check statuses only: new entry (R203-3) |

### CodeRabbit, PRs 207-210

CodeRabbit's 45 inline comments and 21 review-level remarks on #207-#210 (17, 13, 12 and 3 inline;
the remarks are the review bodies' "Follow-ups", "Nitpick comments" and "Additional comments"),
classified against the top of the stack, `stack/90-triage-fixes` (`f339aa13`, #273); one reply per
thread, and the review-level remarks in one comment per PR. New entries:
[R207-1](#r207-1-an-international-imports-countries-the-gate-category-imports-a-missing-file) (Low),
[R207-2](#r207-2-gallery-paging-redirects-and-the-region-menu),
[R207-3](#r207-3-request-timing-and-gatling-tool-nits),
[R208-1](#r208-1-a-contests-file-countries-the-choice-stale-links-and-the-country-menu) (Low),
[R208-2](#r208-2-the-top-of-a-round-import-plan-has-gaps) (Low),
[R209-1](#r209-1-conflicts-of-interest-the-forms-confirmation-legacy-names-records),
[R209-2](#r209-2-the-logging-config-plans-rollout-misses-the-systemd-drop-in) (Low),
[R210-1](#r210-1-a-stored-category-operation-the-form-doesnt-know); none Medium or above. Items
added to [R202-1](#r202-1-the-commons-file-check-can-apply-a-stale-answer) (item 4, an import's
unlocked read), [R203-2](#r203-2-adding-a-pasted-list-waits-for-commons-and-misses-some-files)
(item 1's category check, items 7-9) and
[R203-3](#r203-3-files-region-and-sign-out-tests-that-would-pass-a-regression) (items 7-11).

| PR | Inline comments: class |
|---|---|
| #207 | LargeViewController.scala:60 closed-round redirect drops the query: new entry (R207-2); Pager.scala:25 page past the last: new entry (R207-2); RoundList.scala:151 file list and CSV ignore sort=file: not applicable (a file list and CSV rank by rate whatever the order, `RoundList.scala:178-186`, `:297`); CategoryJdbc.scala:69 stale country links kept: not applicable (since #208 an import never removes a link, on purpose, `CategoryJdbc.scala:55-60`; the stale country shown is R208-1 item 1); RequestTiming.scala:96 fileList not timed: new entry (R207-3); Image.scala:78 SVG cache files hold JPEG under .png: not applicable (no Apache vhost serves the cache since #255; the proxy sends cached files as image/jpeg, `ImageProxyController.scala:151-155`); ContestCountries.scala:33 a subcategory as the country: fixed later (#208, `overlaps`/`candidates`); LocalImageCacheService.scala:478 protocol-relative URLs: fixed later (#230, `withScheme` first); region-menu.js:55 counts after a vote: new entry (R207-2); timing-summary.py:120 status not filtered: new entry (R207-3); GatlingConfig.scala:55 unknown pool: new entry (R207-3); RegionCountPairedBenchmark.scala:90 regionnav markers: fixed later (#222, the page cases replaced by the menu cases; #216 still had them); JurorClickSimulation.scala:55 CSRF tokens in external mode: not applicable (the external target is the bench server, which lets POSTs through as the fixture does, `bench/server/bench.conf:5-6`); JurorClickSimulation.scala:60 vote redirect without pageid: new entry (R207-3); PageImportSpec.scala:74 root count 0 with a country category: new entry (R207-1); ImageImportHandler.scala:212 country gate from the root's count: new entry (R207-1); ImageImportHandler.scala:215 category imports get no countries: new entry (R207-1) |
| #208 | Pager.scala:42 every sentinel replaced: new entry (R207-2); ImageJdbc.scala:530 a country category's own link dropped: new entry (R208-1); ContestCountries.scala:49 one category per name: new entry (R208-1); ContestCountries.scala:81 gallery country from counts: new entry (R208-1); ImageImportHandler.scala:211 category imports get no countries: new entry (R207-1); ImageImportHandler.scala:302 textless page clears monument id and description: fixed later (#209, `textless` and `ImageService.withStoredText`); ImageImportHandler.scala:302 no-text path writes an unlocked read: new entry (R202-1 item 4); pageLinks.scala.html:23 novalidate: new entry (R207-2); build.sbt:127 old digested assets after a release: accepted, low risk (a page requests most assets as it loads, but a tab left open across a release that lazy-loads a script or font afterwards can get a 404 for it; a reload fixes it); import-top plan:35 deleted winners: new entry (R208-2); import-top plan:37 tie-break text: new entry (R208-2); import-top plan:48 ratings not returned: new entry (R208-2); pager-jump.js:11 parseInt: new entry (R207-2) |
| #209 | RoundController.scala:135 no confirmation without a category: new entry (R209-1); RoundController.scala:163 excludeCoi set while distributing: fixed later (#244, `RoundConflicts.gate` refuses before the flag is set, `RoundConflicts.scala:83-96`, `:118-126`); CoiMembers.scala:30 localized user prefix: new entry (R209-1 item 2, legacy rows only since #225); CoiMembers.scala:43 comma-separated roles: fixed later (#223, V76 allows one role per row); Round.scala:278 excludeCoi not in the settings snapshot: new entry (R209-1); RoundDistributionHandler.scala:203 all-stored list in an outage: new entry (R203-2 item 7); RoundDistributionHandler.scala:210 stored files by page id: new entry (R203-2 item 8); ImageText.scala:56 author cut by UTF-16 unit: new entry (R209-1); editRound.scala.html:72 counts on the binding-error page: new entry (R209-1); V67 wiki accounts canonicalized: fixed later (#225, `User.normalizeWikiAccount` on every write and lookup; rows from before it: R209-1 item 2); logging plan:37 drop-in: new entry (R209-2); logging plan:42 backout: new entry (R209-2) |
| #210 | EditRound.scala:60 unknown stored clause: new entry (R210-1); RoundDistributionHandler.scala:164 list-only job validates categories: existing entry (R203-2 item 1); imageFiltering.scala.html:73 unknown operation on redisplay: new entry (R210-1) |

| PR | Review-level remarks: class |
|---|---|
| #207 | fileList ignores format: new entry (R207-2); comment redirect loses the viewer: new entry (R207-2); page 0 documented as whole: new entry (R207-2); timing-summary query string merged: new entry (R207-3); countries of a page without image info: new entry (R207-1); star-vote criteria query unasserted: fixed later (#217, `LargeViewUrlsSpec.scala:137-139`); all-juror rankPosition by an individual rate (unverified): fixed later (#215, the all-jurors order and ranks use explicit aggregates, `RankKey`) |
| #208 | job details expose source ratings: new entry (R208-2); N+1 for the boundary rating: new entry (R208-2); addCountries returns every category: new entry (R208-1); deleted view's country counts: new entry (R208-1); country gate with plain-prefix categories only: new entry (R207-1); no-text reimport metadata: new entry (R203-3); Armenia filter non-member: new entry (R203-3); packaged digest output: new entry (R203-3); "not stored again" overwrite: new entry (R203-3) |
| #209 | greeting to the no role: new entry (R209-1); a pasted title's stored row with another page id: new entry (R203-2 item 9); moved-aside criteria rates unchecked: fixed later (#244, votes now kept aside, `CoiRoundSpec.scala:213-235`) |
| #210 | locked category operation untested: new entry (R203-3); MINUS A A in two Commons requests (unverified): not applicable (the same category twice is no filter an organizer means, and a file that leaves it between the two requests no longer belongs in it) |

The later PRs of the series: #211, #212 and #213 got no inline comments; CodeRabbit skipped #214
(121 files, over its 100-file limit); #215's review was incremental, over commits it had reviewed,
with nothing new.

### CodeRabbit, PRs 220-224

CodeRabbit's 16 inline comments and 8 review-level remarks on #220-#224 (1 inline on #220, 15 on
#223; the remarks are the review bodies' "Nitpick comments": 4 on #220, 1 on #222, 3 on #223; #221's
review has only the "Docstring Coverage" pre-merge check, not answered, as on earlier PRs; #224 was
clean), classified against the top of the stack, `stack/101-multi-role-plan` (`0f441881`, #284);
one reply per thread, and the review-level remarks in one comment per PR (#220, #222, #223). New
entries: [R220-1](#r220-1-the-password-busy-page-and-user-imports-silent-failures),
[R223-1](#r223-1-two-step-rounds-and-criteria-plans-re-apply-the-count-check-the-damage-query)
(Low), [R223-2](#r223-2-a-clean-n1-data-check-doesnt-rule-out-a-reverted-role) (Low); none Medium
or above. Most of #223's comments are on plans that later PRs implemented (P0 #232, P1 #236, P2a
#265, P2b #267, C2 #235, C3 #247) or on security documents #228 replaced (`secure-coding.md`,
`register.md`).

| PR | Inline comments: class |
|---|---|
| #220 | UserController.scala:283 own profile's 503 page without its cards: new entry (R220-1) |
| #223 | criteria-rounds.md:114 partial votes in rate_sum: fixed later (#235, U5: `vote.rate` 0 until every criterion is rated); criteria-rounds.md:166 damage check leaves out aside votes: new entry (R223-1); criteria-rounds.md:232 half-star units: fixed later (#247, `perCriterion / starUnits`); roadmap.md:43 N6 without a batch: fixed later (#231, S7, `protectHeaders = null`, `LoginCsrfSpec`); round-images.md:69 rollback keeps the column rename: fixed later (#232, §12 rollback SQL); round-images.md:114 pairs with live and aside votes: fixed later (#236, a mixed pair stays `in` and `restoreVotes` moves back every aside vote; #244 keeps a `dq` pair's aside); round-images.md:158 inserted rated votes' counts: fixed later (#265, counts recomputed from the votes); round-images.md:220 re-apply can't see existing fill rows: new entry (R223-1); round-images.md:228 count check per distribution batch: new entry (R223-1); round-images.md:312 top-N from round_image rows: fixed later (#267, top-N stays on the votes); security-practices.md:40 bearer API calls: fixed later (#228, `secure-coding.md` §11 exception); security-practices.md:57 wrappers and delegates in G2: fixed later (#228, `RouteAuthSpec` follows helpers); rereview-previous.md:155 wiki account uniqueness: fixed later (#225, H1: only a verified wiki account joins); rereview-report.md:62 clean data queries: new entry (R223-2); rereview-report.md:75 N1 status: fixed later (#228, `register.md`) |

| PR | Review-level remarks: class |
|---|---|
| #220 | `importUsers` failure not logged: new entry (R220-1); per-client sign-in limit: planned (P1, fail2ban, S4 with the infra plan); unbounded `holding.await()`: new entry (R220-1); "Password storage (done)" with dormant SHA-1 hashes: fixed later (#221 status rewritten; #230, S6/P8, V81 retires them) |
| #222 | paired benchmark's warm-up not on the pool: accepted as is (per-block medians, alternating order, B10 decided) |
| #223 | invalid-role warning on every lazy read: not applicable (V76's `CHECK`); `roleRefused` binds twice: won't fix (no cost; keeps the submitted values); V76 not atomic: not applicable (as designed; roadmap A3 pre-deploy check) |

The N1 access-log check recorded in R223-2 was run on 2026-11-10 at the maintainer's request: no
sign of exploitation from 2026-10-27 to the hotfix, not conclusive.
