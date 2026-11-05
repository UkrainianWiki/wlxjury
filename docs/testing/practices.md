# Testing practices

How the jury tool is tested: which layer a test belongs to, how it stays isolated, what it may
and may not assert, and what a run costs. Each rule has a short why and an example in this
repository. Reviewers tick [review-checklist.md](review-checklist.md). Rules owned by another
practices doc are pointed to, not repeated: DB isolation and lock specs in
[db/practices.md](../db/practices.md) §7.6, §9; load tests and benchmarks in
[performance/practices.md](../performance/practices.md); negative authorization cases and the
security guards in [secure-coding.md](../security/secure-coding.md) §9 and §21. **Sources:**
CLAUDE.md ("Testing", "Database Tests", "Agent efficiency"), the CI plan
([`2026-11-03-ci-and-scalawiki-packages.md`](../plans/2026-11-03-ci-and-scalawiki-packages.md)),
the test-speed results in `98a8a79e:docs/plans/2026-11-01-open-followups.md`, and the
theme-modes invariants in `98a8a79e:docs/plans/2026-10-31-theme-modes-and-design-system.md`.

## Contents

1. [Layers](#1-layers)
2. [Isolation and the shared server](#2-isolation-and-the-shared-server)
3. [Concurrency: lock, invariant and stress tests](#3-concurrency-lock-invariant-and-stress-tests)
4. [Guard, parity and migration specs](#4-guard-parity-and-migration-specs)
5. [Wikimedia, live and high-load tests](#5-wikimedia-live-and-high-load-tests)
6. [What a test asserts](#6-what-a-test-asserts)
7. [Flaky tests](#7-flaky-tests)
8. [Runtime budget and CI](#8-runtime-budget-and-ci)
9. [Coverage](#9-coverage)
10. [Browser tests](#10-browser-tests)
11. [Guards](#11-guards)
12. [Where the code doesn't follow these yet (for triage)](#12-where-the-code-doesnt-follow-these-yet-for-triage)

## 1. Layers

**1.1 Put a test in the lowest layer that can show the behaviour.** *Why:* a unit spec runs in
milliseconds; a spec through the router starts an app and truncates tables.

| Layer | Where, how | Use it for |
|---|---|---|
| Unit | specs2/munit without a database (`views.*` renders, `CsvSpec`, `CommonsLoginSpec`) | pure logic, a template's output, parsing |
| DB spec | `SharedTestDb`: `AutoRollbackDb` / `dbTest`, or truncation (§2) | DAOs, SQL, transactions, migrations |
| Play-app spec | `PlayTestDb.testDbApp { implicit app => }`; a custom config by an implicit `cfg` map | controllers, services wired by Guice |
| Router + CSRF | `CsrfTestApp.withCsrfApp` (the CSRF filter on; the test config has it off) | POSTs with and without the token, `Csrf-Token` headers |
| Production filters | `security.ProductionFilters.withSharedApp`; `withApp` / `withAppWith` / `OwnApp` for app-level state | CSP, allowed hosts, security headers, `SecurityHeadersSpec`, `LoginCsrfSpec` |
| Jest | `javascript-test/*.test.js` on `public/javascripts/`, jsdom; `sbt test` runs it (`jsTest`, `npm ci`) | page scripts: votes, keys, menus, pager |
| Python | `scripts/deployment/test_deployment.py` (68 tests, `python:3.8.20-slim`, `--network none`), `test_database_restore`, `test_release_script` (24, in `Dockerfile.test` against the `sbt dist` ZIP); `scripts/test_agent_check.py` | the deploy and release tools |
| Gatling | `test/gatling/`, `sbt gatlingAll` | load and latency: [performance](../performance/practices.md) §1 |
| Browser | `browser-test/` (Playwright: one current build each of Chromium, Firefox and WebKit) on the `UiShotsServer` fixture: §10 | what only a real engine shows: votes and Back, keys, first frames, CSP and console |

**1.2 One Play app per JVM.** `SharedPlayApp` and `ProductionFilters.withSharedApp` reuse one app
and the shared container; a spec never stops them (no `running`, no `TestServer` around the shared
app). A spec that needs its own app uses `withApp`/`OwnApp` and stops it in `afterAll`. *Why:*
each app start costs seconds and closes the pool (§2.3).

**1.3 Jobs run inline in tests** (`jobs.mode = inline` in `test/resources/application.conf`;
`services.TestRoundServices` without an app). A spec of the scheduler itself
(`JobSchedulerSpec`) builds its own.

## 2. Isolation and the shared server

**2.1 Choose isolation by session.** Pure DAO specs with the session threaded:
`new AutoRollbackDb { }` (specs2) or `dbTest { session => }` (`AutoRollbackMunitDb`). Anything that
goes through a service, `autoSession` or a Play app: `BeforeAll` +
`BeforeEach(SharedTestDb.truncateAll())`. The table is in CLAUDE.md "Database Tests". *Why:*
`autoSession` opens its own connection and sees only committed data, so an outer rollback
transaction is invisible to it (`Secured.userFromRequest`, `GalleryService`, `Round.addUsers`).

**2.2 One MariaDB per JVM, on tmpfs.** `SharedTestDb.forTests`: MariaDB 10.6 (`SharedTestDb.Image`;
10.11 by `-Dwlxjury.test.mariadb-image=mariadb:10.11.14`), data directory on tmpfs (`size=1g`,
so a runaway spec fails with "table is full"), 128M buffer pool, 48M redo log, flushing off,
1M `max_allowed_packet`. *Why:* `TRUNCATE` re-creates table files:
0.5–1 s per `truncateAll` on disk, 30–40 ms on tmpfs; the suite went from 21:25 to 6:57
(`2d5d0ef4`). It is fast, not durable: never a benchmark (performance §4.6).

**2.3 Lock timeouts fail fast.** The test server runs `--innodb-lock-wait-timeout=10` (row
locks, MariaDB's default 50 s) and `--lock-wait-timeout=60` (metadata locks, default a day). *Why:* a leaked lock or an open transaction now fails the spec with an error naming
it instead of hanging the suite (one `CoiRoundSpec` statement once waited 26 minutes; CI plan,
"Test coverage"). A spec that tests a timeout sets its own session value and resets it in
`finally` (`CriteriaRateSpec`).

**2.4 Pool lifecycle.** The tests run on HikariCP, as production. Without a Play app,
ScalikeJDBC's default pool is `SharedTestDb`'s `HikariTestPool` (`db.scalikejdbc`): Play's
HikariCP settings (autoCommit, 30 s connection timeout, idle timeout and max lifetime), READ
COMMITTED as production's `transactionIsolation` (`CriteriaRateSpec`), 2 idle and 8 at most where
production keeps 10 and 30. HikariCP sets that level on a new connection only if it differs from
the default it read from the pool's first connection, in production as here, so a global level
changed during a pool's life is not picked up; only `WritePathStress` changes it, and it builds
new pools afterwards (and checks each path's level). The Gatling fixture (`gatling.pool=small`),
its external target and `WritePathStress` use the same class. `small` differs from the old
`dbcp` option in more than size: READ COMMITTED where `dbcp` ran at the server's default,
REPEATABLE READ, and a borrow waits 30 s instead of 5 s, so earlier `dbcp` Gatling results are
not like-for-like. `WritePathStress` takes only `maxSize` from its `ConnectionPoolSettings`, not
`connectionTimeoutMillis`. A Play app's start makes its own HikariCP pool ScalikeJDBC's default
(`PlayDBApiAdapterModule`; `ConnectionPoolConfigSpec`); `running(app)` or `app.stop()` closes it.
A spec that uses `running(app)` calls `SharedTestDb.reregisterDefault()` in a `finally`.
Registering again closes a replaced `HikariTestPool`; a replaced Play pool (a plain
`DataSourceConnectionPool`) is not closed that way, Play closes it when the app stops. *Why:* otherwise every later spec
in the JVM fails on a closed pool; and a spec on another pool than production's (ScalikeJDBC's
commons-dbcp2 default until 2026-11-04) tests a pool that never runs. The switch did not change
the tests' speed: `db.*` and `services.distribution.*` (446 tests, ABBA pilot after a warm-up,
2026-11-04) took 174 and 174 s on dbcp2, 171 and 170 s on HikariCP, no spec more than 1 s
apart. dbcp2 validated no borrow (no validation query); HikariCP pinged about 100 of 18,476
borrows, those idle over 500 ms (`aliveBypassWindowMs`), so there is nothing to turn off.

**2.5 Order independence.** A spec seeds what it reads and never relies on ids from 1 or on rows
another spec left; `AutoRollbackDb` empties the database before each example (`a65e165c`). A
spec's raw `INSERT INTO vote` adds its `round_image` row too, or calls `RoundImageJdbc.sync`
(CLAUDE.md "Round images").

## 3. Concurrency: lock, invariant and stress tests

**3.1 Lock specs wait on `INNODB_TRX`, never on sleeps.** Hold the lock in one transaction, start
the other on a thread, poll `information_schema.INNODB_TRX` until it shows `LOCK WAIT`, assert
nothing changed, release, assert the outcome; then check that the spec fails without the lock.
Details: db §7.6. *Examples:* `CriteriaRateSpec`, `RoundImageLockSpec`, `WritePathLockSpec`.
*Why:* a sleep is either too short on a slow runner or wastes time on a fast one, and proves
nothing about blocking.

**3.2 Invariant specs.** A spec that writes votes asserts
`RoundImageInvariants.violations(roundId)` (or `all()`) is empty after each scenario (db §9.4).
*Why:* `round_image` must follow every `vote`/`vote_aside` write; the invariant catches a writer
that forgot it, whatever the scenario checked.

**3.3 The stress harness is opt-in.** `db.stress.WritePathStress` (`test/stress/`, a `main`, never
in `sbt test`) for a change to a write path's locks: count deadlocks and invariant breaks, compare
variants in one JVM; its latencies are not production's (db §7.7, performance §2.8).

## 4. Guard, parity and migration specs

**4.1 Guard specs scan the sources.** A rule that can be checked mechanically gets a guard: most
are in `test/security/` (`testOnly security.*`, secure-coding §21), others are `db.*` and
`views.*` specs (db §10, [UI](../ui/practices.md) §8). A guard's allowlist is one entry per file
or action with a reason; a hit is fixed or allowlisted for that one file, never silenced by
widening a pattern (secure-coding header).

**4.2 A guard must not pass vacuously** (partly enforced). Each guard needs a self-test that its
scan finds the thing it guards: a known good match in the real sources, and a bad example it
flags. Several guards don't have one yet (§12, T8). *Examples:*
`RouteAuthSpec` "be classified by the scan as built (it is not vacuous)", `LogHygieneSpec` "be found
by the scan", `VoteRowLockSpec` (sees `VoteJdbc.rateIn`'s lock by id), `TestConfigAnonymousSpec`
(`env -u NAME` allowed, `-u NAME=x` flagged). *Why:* a scan whose pattern no longer matches
anything (a renamed method, a moved file) passes forever.

**4.3 Parity specs for rewritten reads.** A rewritten query passes the old one's spec on the same
data, checked against the old code once, over juror and organizer, binary and rated, with and
without a region and a filter (db §9.3). *Examples:* `GroupedListParitySpec`,
`RoundImageReadParitySpec`, `ListPositionSpec` (an oracle from the plain ordered list).

**4.4 Migration specs run on production-like chains.** A migration is tested on a database built
the way production's was, not only on a fresh one: `FlywayUpgradeSpec` (a 9.16.3-built V70
database upgraded by Flyway 13 equals a fresh one), `ConvertToInnoDbMigrationSpec` (production's
V53 MyISAM schema), `UserContestRoleMigrationSpec`. Each uses its own database on the shared
server, dropped after (db §8.4, §9.2). *Why:* B44 passed on fresh databases and could not have
migrated production's history (`docs/plans/2026-11-03-flyway.md` §1).

## 5. Wikimedia, live and high-load tests

**5.1 Tests are anonymous to Wikimedia.** The test config sets `commons.user`/`commons.password`
to `""` with no `${?COMMONS_USER}`; `CommonsLogin.credentials` gives no account in Mode.Test
unless `commons.allowTestLogin`; wlxjury's bots come from `services.imports.AnonymousBots`, and
`AppModule` seeds scalawiki's bot cache with them. The only opt-in is a deliberate manual one with
all three `-D` values in the forked JVM (`set Test / javaOptions ++= Seq(...)`): no spec, CI job
or `build.sbt` forwarding sets it. Guards: `TestConfigAnonymousSpec`, `AnonymousBotsSpec`.
*Why:* a machine with the bot's credentials logged the bot in from every test JVM, and a changed
bot password became a stream of failed logins on its account (CI plan, "Anonymous Wikimedia
access"). Details in CLAUDE.md "Testing".

**5.2 The `env -u` prefix.** On a machine that has bot credentials, run sbt and Docker by hand as
`env -u COMMONS_USER -u COMMONS_PASSWORD -u SCALAWIKI_LOGIN -u SCALAWIKI_PASSWORD <command>`;
`coverage.yml` does the same.

**5.3 Live and high-load tests are behind flags, off by default.** `-Dintegration=true`:
`AppendImagesSpec` "get international images", `ImagesFromListSpec`,
`LocalImageCacheServiceIntegrationSpec` (CI weekly, `integration.yml`, anonymous);
`-Ddocker.tests=true`: `LocalImageCacheServiceDockerSpec` (50 downloads); `-Dimport.scale=true`:
`ImportScaleSpec`. `build.sbt` forwards these keys to the forked test JVM, with
`generate.baseline`, `import.latencyMs`, `import.pageSize` and `wlxjury.test.mariadb-image`; the
Gatling flags (`gatling.*`, the stress flags of performance §1.3) go through
`Gatling / javaOptions`. Everything else uses fakes: `FakeCommonsSource`, `FakeWikimedia`,
`FakeMonumentListSource`, mock servers. *Why:* no network flakiness by default, and no load on
Wikimedia from every run.

**5.4 OAuth providers are faked.** `OAuthControllerSpec` and `OAuthRouterSpec` use fake providers;
`ProvidersSpec` runs the real `GoogleProvider` and `WikimediaProvider` against a local fake
server. No spec calls Google or Wikimedia ([integrations](../integrations/practices.md) §3).

**5.5 Mail is mocked.** `play.mailer.mock = yes`; specs read invitation and password links from
`controllers.RecordingMail`.

## 6. What a test asserts

**6.1 Ids, data attributes, roles and visible text; never CSS classes.** Find elements by `id`,
`data-*`, ARIA role or accessible name, and assert text; a class only where the class is the
behaviour (`is-invalid`, `active`). *Why:* the Bootstrap 3 → 5 move renamed most classes; tests
on styling broke without any behaviour change (theme-modes invariants). CLAUDE.md: don't run tests
that merely assert CSS class names.

**6.2 No assertion passes when its element is missing.** Select first and assert that it exists
(a count, `must beSome`, `toHaveLength(1)`), then assert on it. Never `forall` over an empty
selection, `if (found) check`, or `must not(contain(x))` on a page that may not have rendered.
*Why:* a renamed id turns such a test green for good.

**6.3 Negative cases.** A permission change has specs for the roles that must be refused and for
other contests' resources (secure-coding §9 "Tests"); a CSRF change has a refused-token case
through `withCsrfApp`.

**6.4 Messages in all four files.** Every message key the app uses is in `conf/messages.en`,
`.fr`, `.uk` and `.ru`, with the same placeholders; the files have the same keys.
`views.MessagesCompletenessSpec` reads keys from `app/` (literal `Messages("…")`, helpers that
take a key, and `dynamicKeys` for keys built at run time). A new helper taking a key, or a key
built in a new way, needs a pattern or an entry there. *Why:* a missing key shows the raw key or
English on one language's pages. UI side: [UI](../ui/practices.md) §1.

**6.5 Errors by key.** A spec of an error page checks the message key's text and that request or
exception text is absent (`ErrorHandlerSpec`, `security.ErrorTextSpec`; secure-coding §12).

## 7. Flaky tests

**7.1 A flaky test is a bug.** Find the cause, fix it or skip the example with the reason and a
roadmap or plan entry; never add a retry around an assertion or raise a timeout to hide it.

**7.2 Inject the clock.** Code that measures time takes a clock (`RegionCountCache.now`,
`ImageProxyController`'s file check in `ImageProxyFileCheckSpec`); specs advance it instead of
sleeping. A spec that must measure real time uses `System.nanoTime`, never the wall clock, and
generous bounds. *Why:* a laptop that slept, or a VM clock correction, makes the wall clock leap
during a run, and an age or TTL check then fails or passes at random.

**7.3 Windows: paths and temp files.** Compare paths with `/` separators (`LocalImageCacheServiceSpec`,
`a65e165c`); give each spec its own temp directory and don't fail an example on a deletion that
Windows refuses while a file is still open (another process or an unclosed stream holds it);
`deleteOnExit` and the next run clean it. Bind-mounted config files are world-writable on Windows
and MariaDB ignores them: pass server options as flags (`GatlingTestFixture`).

**7.4 Docker.** `TestDocker` fails with "start or restart Docker" if the daemon doesn't answer; a
container left by a killed run is removed by Ryuk.

## 8. Runtime budget and CI

**8.1 Affected specs first.** Compile, then run the specs of the change (with its negative cases)
through `python scripts/agent-check.py -- …` from Git Bash (CLAUDE.md "Agent efficiency").
Locally, the full suite runs once at a release boundary; after a failure, fix and rerun the
affected specs before another full run. CI runs the full suite on every PR (§8.3). No local tests
for documentation-only changes.

**8.1a Every local run has a time limit.** `agent-check.py` stops the run's whole process tree
after `--timeout` seconds (default 1200 for targeted specs; `--timeout 1800` for the full suite,
about 10.5 min plus 3–8 for a cold compile) and exits 124 with `TIMED OUT after N s`, keeping the
log. A 124 is a stalled run, not a failing test: read the log's tail (the last spec started, a
Hikari clock-leap warning) before rerunning, and never raise the limit to hide a hang (§7.1).
The tree is a Windows Job Object (killed with the script, a hard kill included) or a POSIX
process group (the script's SIGTERM/SIGHUP/SIGINT forwarded to it), so neither a timeout nor a
stopped agent leaves a test JVM behind; a run longer than the Bash tool's 600 s foreground limit
goes in the background (CLAUDE.md). `scripts/test_agent_check.py` runs in CI (`ci.yml`, its
Windows parts mocked or skipped). On Windows it also holds a keep-awake request while the command runs (`ES_SYSTEM_REQUIRED`, not
the display; `--allow-sleep` opts out), so idle sleep on battery doesn't stop a long run; lid and
power-button actions and critical-battery hibernation still can, which is what the timeout and
the stress harness's INVALID marking (§8.1b) are for.
*Why:* a run whose host went to sleep, or whose spec hung on a lock, held the shared sbt lock and
the laptop's memory all night, with no output to say why; on 2026-11-05 the AC power dropped at
01:15 and the laptop, its lid closed, slept 6 s later on battery.

**8.1b A stress variant the host slept through is INVALID.** `WritePathStress` marks a variant
INVALID (summary and `results.json`: `valid`, `invalidReason`) when its `nanoTime` elapsed time
is more than `stress.seconds + 300`, or HikariCP logged a clock leap or retrograde clock change
during it: its `Thread.sleep` doesn't count the host's sleep, its elapsed time does. Its numbers
are not compared or recorded as results; rerun it (`WallTimeCheckSpec`).

**8.2 One sbt JVM at a time** across worktrees and agents: the shared lock
([workflow](../workflow/practices.md) §4).

**8.3 CI on every PR.** `ci.yml`: the full sbt suite and Jest on MariaDB 10.6.22 (10.11.14 on
master pushes and weekly), about 5½ minutes; `deployment.yml`: the Python tests and the release
tests; `coverage.yml`: the same suite instrumented (§9); `integration.yml`: the live group
weekly. The check to require on `master` is `CI / sbt test (mariadb:10.6.22)` (CI plan, U8);
coverage is never required.

**8.4 Read results by totals and exit code.** A failure-path test may log `[error]`; the totals and
the process's exit code decide. Full logs stay under `target/checks/`.

## 9. Coverage

**9.1 What it measures.** scoverage (statements, branches) for Scala, V8 for Jest (`coverage.yml`,
job summary by `scripts/coverage-summary.py`; locally `sbt coverage test coverageReport`, then a
new session before any benchmark or `dist`). It measures what runs, not what is asserted: a line
run by a test without an assertion on its result counts as covered. Twirl templates are not
measured (generated code is excluded; the hand-written `app/views/bs/` helpers are); the vendored,
patched gallery (`unitegallery.js`, `ug-theme-tiles.js`) and `jquery.hotkeys`, `jquery.raty` are
outside Jest's `collectCoverageFrom`. The gallery's patches are asserted instead, by
`unitegallery-patches.test.js`: each patch has a test that fails when the patch is reverted
([vendor doc](../vendor/unitegallery.md) §2, §4). Its tests load the files with `eval`, which V8
coverage doesn't count. A one-off measurement (2026-11-04) put the theme at 86.2 % of lines and
82.4 % of branches, with every wlxjury line covered. The theme is about a quarter ours, so it
stays excluded.

**9.2 The baseline** (`4a861818`, run [41649422116](https://github.com/UkrainianWiki/wlxjury/actions/runs/41649422116)):
Scala **89.87 %** statements, **85.84 %** branches; JS **96.76 %** lines, **91.39 %** branches;
1,902 Scala examples, 253 Jest tests. Scala moves by about a tenth between runs (timing-dependent
scheduler paths): read smaller differences as noise.

**9.3 How to use it.** Read the report for the files a change touched: an uncovered branch there
is a missing test case. No minimum is enforced yet (a threshold a few points under a stable
baseline is a later decision, CI plan), so the gallery batch changed no threshold. With its 28
tests, Jest counts 281 tests at 96.76 % of lines and 91.39 % of branches (2026-11-04, local; the
vendored files excluded, as in the baseline).

**9.4 Story coverage.** Line coverage says which lines ran, not which flows a juror or an organizer
can rely on. [stories.md](stories.md) lists what each role does, a story per row with an ID
(`J-` juror, `O-` organizer, `A-` admin, `R-` root, `S-` system or security, two digits; never
reused, a retired one marked *(retired)*), its juror guide section, and *Part:* in Notes while
the tests show only some of it. Tests cite stories:

- **Playwright:** the native tag, on a test or a `describe` (which covers its tests):
  `test('…', { tag: ['@J-21', '@J-24'] }, async (…) => …)`; `npx playwright test --grep @J-21`
  runs a story's tests. The `storyLabels` fixture turns the tags into the Allure report's labels
  (§10.10) and fails a test whose tag the catalogue lacks.
- **Scala:** the ID in brackets in an example's or a `should` block's name, the block covering its
  examples: `"a juror's own votes [J-27]" should { … }`, munit `test("[J-02] …")`. No Allure
  adapter exists for specs2 or munit; the name reaches sbt's JUnit XML unchanged.
- **Which Scala specs:** the story-level ones only, those that drive a request through the
  router or a Play app (or the controller with its database) for a catalogue flow: most of
  `test/controllers/` and the request-level ones of `test/views/` and `test/security/`, about 40
  files, one tag per block where possible. *Not tagged:* `test/db/`, `test/services/`,
  `test/org/`, `test/stress/`, `test/gatling/`, template-rendering view specs, parity specs, the
  source-scan guards (`RawSqlSpec`, `LogHygieneSpec` …), form-mapping unit specs and Jest. *Why:*
  a unit test shows a rule, not a story; tagging ~2,000 examples would make the matrix noisy and
  the tags churn with every refactor. A story whose only tests are service specs says so in Notes.

`scripts/story-coverage.py` (standard library only; a step of `ci.yml` after the tests, in one
matrix leg, and locally `python scripts/story-coverage.py`) reads the catalogue, the tags in
`browser-test/tests/*.js` and the bracketed IDs in `test/**/*.scala`, and writes the matrix
(story, state, browser tests, Scala specs) to stdout, `target/story-coverage.md` and the job's
summary. **It fails** on: an ID the catalogue lacks or retired, in any test, a `fixme` or skipped
one too; a malformed ID (`J-7`, `X-01`, `j-01`, `J01`) in a tag, a Scala name or a catalogue
row; a tag string not attached to a test's or a describe's `tag` option; a Scala ID outside its
`[..]` group (`J-27]`, `[J-27`; string data shaped like an ID, `"S-10"`, counts too: build
such a value instead); a duplicate row; a guide section link without its `#anchor`, and
a guide or Notes link to a missing file or heading; and a vacuous run: no tag in either layer, or
a raw count of the quoted tags (`'@J-21'`, comments included) and of the `[J-27` groups in the
Scala code (comments left out; a type parameter such as `[A1]` is no ID) that differs from
what it parsed (§4.2; `--self-test` runs each case on
`scripts/testdata/story-coverage/`). **It reports, without failing:** the uncovered stories by
role, those cited by one layer only, the tests in `test.fixme`/`test.skip` (not counted), and
the guide's sections no story links (drift). States: *covered* (cited, no *Part:*), *partly
covered* (cited, with *Part:*), *uncovered*. It is static: a tag says a test cites the story, not
that the test asserts the whole outcome (reviewers check a new tag against the sentence) or that
it passed (the Allure report, §10.10). A guard (`RouteAuthSpec`'s scan) shows a rule, not a
story, and carries no tag.

**9.5 Where the numbers show.** Three places, all from the one Coverage run:

- **The run summary** of `coverage.yml` (`scripts/coverage-summary.py`), with the reports as
  artifacts.
- **A PR comment**, one per PR, updated in place on every Coverage run (found by the hidden
  marker `<!-- wlxjury-coverage-comment -->`): Scala statements and branches, JS lines and
  branches, the change against the latest successful Coverage run on the PR's base branch ("no
  base run" when there is none, e.g. its summaries expired after 90 days), and a link to the run.
  The Coverage job writes `coverage-summary.json` (`coverage-summary.py --json`) as the artifact
  `coverage-summary-attempt-N`; `coverage-comment.yml`, a `workflow_run` workflow, reads it with
  `scripts/coverage_comment.py`. That workflow is the only one with a write right
  (`pull-requests: write`, for the comment; its `actions: read`, for the artifacts, only reads),
  so it never checks out or runs the PR's code: it checks out only its script, at the default
  branch, and refuses a summary that isn't exactly the format (known keys, a 40-hex SHA equal to
  the run's head, the run's PR, percentages from 0 to 100) or can't be decoded at all, and a run
  that isn't a PR from this repository (fork PRs get no comment). Its tests, hostile input
  included, are `scripts/test_coverage_reports.py`, a step of `coverage.yml`. A `workflow_run`
  workflow runs only from the default branch, so the comment starts once the workflow is on
  `master`.

  **What the two columns measure.** The PR column is measured on GitHub's test merge commit of
  the PR into its base (a `pull_request` run checks that out), labelled with the PR's head SHA.
  The base column comes from the base branch's latest successful Coverage run: for `master` (or
  `ukwikidev/followups`), a push run on that commit; for a stacked base such as `stack/*`, the
  lower PR's own `pull_request` run, which measured *its* merge commit into the branch below it.
  So in a stack the change is between two merge commits, not between two branch heads. A run
  without a summary, e.g. of a PR branch whose `coverage.yml` predates this step, is skipped with
  "the run has no coverage summary", and as a base it counts as "no base run".
- **Coveralls** ([coveralls.io](https://coveralls.io/github/UkrainianWiki/wlxjury)), once
  enabled: the history per branch and per-file line coverage. `coverage.yml` sends both
  reports in one build with `coverallsapp/github-action` (scoverage's Cobertura XML and Jest's
  lcov, their paths made relative to the repository by `scripts/coveralls-paths.py`),
  authenticated by the job's read-only `GITHUB_TOKEN` (enough for a public repository, on pushes
  and on PRs; no `COVERALLS_REPO_TOKEN`). The step is skipped unless the repository variable
  `COVERALLS_ENABLED` is `true`, and `continue-on-error` keeps a Coveralls failure from failing
  the job. **What Coveralls receives:** each measured file's path, per-line hit counts and
  branch counts, and an MD5 digest of the file (not its text: Coveralls shows the source by
  fetching the public file from GitHub), plus the commit's SHA, branch, subject line and its
  author's and committer's names and e-mail addresses, and the run's id and URL. On a PR run
  the commit reported is GitHub's test merge commit (what the run checked out), not the PR's
  head. Nothing from `target/` but the two reports; no secret but `GITHUB_TOKEN`. **Turning
  Coveralls on adds a third party that keeps this data** (per-file coverage history and the
  commits' names and e-mail addresses), under Coveralls' own terms and retention.

  **To turn it on** (the user's steps): sign in at coveralls.io with GitHub; add the
  repository `UkrainianWiki/wlxjury` (the organisation may first have to approve the Coveralls
  app under its third-party access settings); leave Coveralls' own PR comments off (its
  "Leave comments?" setting, which also needs the `@coveralls` user invited), since the comment
  above already shows the totals; then set the variable: repository Settings → Secrets and
  variables → Actions → Variables → `COVERALLS_ENABLED` = `true`. The next push to `master` or
  PR run uploads. To turn it off, delete the variable or set it to anything else.

## 10. Browser tests

`browser-test/`: `@playwright/test` (JavaScript, its own `package.json` and lockfile, outside
`javascript-test/` so that Jest doesn't collect its specs) against the `UiShotsServer` fixture,
in **one build of each engine: the one Playwright 1.64.1 bundles** (Chromium 156, Firefox 157,
WebKit 27.2), all current. *Why Playwright Test rather than the Python Playwright of
`ui-shots.py`:* projects per engine, sign-in projects as dependencies, traces and screenshots on
failure, and an HTML report come with it; Node is already in CI for Jest.

**What is supported, tested and linted** are three different things. *Supported* is the policy
([UI](../ui/practices.md) §6.1, §6.1a): the current and previous majors of Chromium-based
browsers, Firefox and Safari, and the floor Chrome 109 and Firefox 115. *Tested in a browser* is
only the current Playwright build of each engine, above. *Linted* is the whole supported range
(`.browserslistrc`): the compatibility lint checks our scripts' APIs and the CSS
features caniuse lists against it (`npm --prefix javascript-test run lint`, a step of `ci.yml`'s job). Real runs
on older versions (the previous Chrome major, Chrome 109, Firefox 115, an older Safari) are not
done and not planned (the user, 2026-11-04): the previous majors and the floor are covered by
the lint only, which can't see rendering or behaviour differences, or a feature a polyfill or a
fallback covers.

**10.1 What runs.** `playwright.config.js` has a project per engine (`chromium`, `firefox`,
`webkit`), each depending on its sign-in project (`setup-<engine>`, `tests/auth.setup.js`), which
signs in through the login form once per role (juror, organizer, admin, root) and saves the
session to `target/browser-test/auth/<engine>-<role>.json`. A test takes a role with
`test.use({ role: 'juror' })`, or opens another session with `contextAs(role)`. 55 tests per
engine (165 in all), 4 of them its sign-ins:

| Spec | What it shows | Stories |
|---|---|---|
| `guards` | the fixture sends the production CSP; a CSP violation is reported; an image from another host, a lookalike of Commons' upload host and `commons.wikimedia.org`, and a request to Commons' API, are refused and reported; Commons' and the image proxy's images get the placeholder; the routeless browser refuses other hosts too (the guards' self-tests, §4.2) | S-03 |
| `sign-in` | sign in through the form, sign out through the user menu's POST with its token; a sign-out without the token is refused and the session stays; a wrong password | J-02, J-03, J-06, J-07, J-34, S-02 |
| `voting` | binary round: large view by key and by button, gallery thumb; rated round: large view by key and by star, gallery star (reading the tile's rate first); Back after a large-view vote shows the stored vote, as a fresh load does (from `ui_checks.py`; §10.3) | J-16, J-17, J-20, J-21, J-24, J-36, S-02 |
| `sections` | the large view's and the contest file's side sections restored as stored by DOMContentLoaded; the first-frame check (from `ui_checks.py`) is `fixme`, triage U18 ([UI](../ui/practices.md) §9) | J-33 |
| `theme` | the login page's switch (no request), the navbar's CSRF POST; `auto` in both schemes with every frame, from the first, in the scheme's mode: the `data-bs-theme` attribute, the root's computed `color-scheme` and the page's computed background (the "Firefox theme flash" check) | J-30 |
| `access` | a juror refused another juror's votes (gallery, large view, all jurors) and the admin pages, an organizer the round form: the 403 page with its reference | J-27, J-35, S-01 |
| `organizer` | the organizer role's round statistics and by-rate list; the admin's rounds list, round form with its jurors' table, edit-then-cancel (nothing saved, no job), new round form; root's campaigns page | O-01, O-02, A-01, A-02, R-01 |
| `pages` | a tour of 17 pages (each role's): status 200, one `h1`; in Chromium an axe-core scan per page (§10.5) | S-04 |

**10.2 Every page passes** (`lib/fixtures.js`, for every context of every test): images and
videos from `upload.wikimedia.org` (requests of type image or media only) and this host's
`/wikipedia/commons/thumb/` (on this host only) get a placeholder PNG; a request to any other
host, other `wikimedia.org` hosts and lookalikes included, is refused and fails the test. A CSP
violation (the `securitypolicyviolation` event, which all three engines fire), a console error,
an uncaught page error (with its stack), a response of 400 or more or a failed request (but one
the page cancelled by leaving, or the guard refused, which is reported as such) fails it, listed
by page. A test that opens a 403 or 404 page on purpose says so with
`diagnostics.expectStatus(403)`, which accepts that status for the page's own document only. The
only other allowances: Firefox's console line for a font download a navigation cancelled
(everywhere), and U22's page error in the two tests that leave a gallery by a POST, in Firefox
only. The guard's route doesn't see WebSockets (that would take `routeWebSocket`); the app opens
none, and a page's WebSocket to another host is reported by the `websocket` event. The fixture runs with the production CSP, CSRF checked on POSTs, and its image proxy
fetching from a placeholder stub in its JVM, never from Commons (`-Dui.csp=production
-Dui.csrf=on -Dui.images=stub`, `fixture-server.mjs`).

The user guides' screenshots (`docs-screenshots.spec.js`, `DOCS_SCREENSHOTS=1`; `0` or unset is
off, any other value an error) use a docs image mode instead: `DOCS_SCREENSHOTS=1 node
fixture-server.mjs start` (or `UI_IMAGES=docs`) passes `-Dui.images=docs`, `server.json` says
`"images": "docs"`, and the spec fails unless it does (a running server of the other mode is never
reused). The stub then serves the CC0 and public-domain photos that `browser-test/docs-photos.py`
verified under `target/docs-photos/` (each list's `manifest.json`; that script checks every
file's licence on Commons on every run, names files by a hash of their entry and deletes the
rest). `DocsPhotos` maps a file by its ordinal in the fixture (by page id), so consecutive files
get different photos; one in four gets a degraded one, never a file the shots show at the top of
a page (`UiShotsServer.shownFiles`), and the stub answers 503 until it knows them. The image
proxy's `target/ui-shots/thumbs-docs` is emptied on every docs-mode start. `DOCS_PHOTOS` names the
lists (`wlm-ua`, `wlm-intl`, `wle-ua`, `wle-intl`; default `wlm-ua,wlm-intl`): one contest's
photos per screenshot. The browser guard keeps the proxy's photos in that run and fetches the
upload host's images from the proxy. Normal runs and CI keep the grey stub; the photos are never
committed.

**10.3 Back after a vote, without routes.** Any Playwright route turns the browser's HTTP cache
off, in all three engines, and Playwright starts Chromium with `--disable-back-forward-cache`: a
page Back brings back is then always loaded again, and the Back checks would pass without
`no-store`. So they run in a second browser per worker (`routelessAs`): no routes, other hosts
sent to a proxy that isn't there (`127.0.0.1:9`; this host bypasses it), and Chromium (its full
build) with its back/forward cache. The large image from `upload.wikimedia.org` fails through the
proxy and falls back to this host's image proxy, which answers from the fixture's stub; the
guard's listeners still report every other host (requests and WebSockets), and accept the
engine's console line for a refused file only when its location is that `upload.wikimedia.org`
file, or, where the line carries no location (WebKit), when the same page had an
`upload.wikimedia.org` load refused. `guards.spec.js` checks that the proxy refuses an image, an
https `fetch` and a `wss://` WebSocket, with each engine's error (`lib/proxy-errors.js`).
**The texts differ by platform**, and a local Windows run doesn't prove the Linux ones (CI's):
Chromium `net::ERR_PROXY_CONNECTION_FAILED` and Firefox `NS_ERROR_PROXY_CONNECTION_REFUSED` on
both, WebKit `Could not connect to server` on Windows but `Could not connect to proxy server
127.0.0.1: Connection refused` on Linux; `npm run check:proxy-errors` checks the patterns against
both, and a new text comes from a CI log. WebKit on Linux also types some image loads `other`,
so the guard accepts `other` from the upload host only for a Commons file's path. Shown on 2026-11-04 with the large view's
`no-store` dropped (locally, not committed):

| Engine | With `no-store` | Without it | Shows the regression |
|---|---|---|---|
| Firefox 157 | pass | 4 of 4 fail | yes |
| Chromium 156 | Back restores the page from the back/forward cache with the old vote (U23); with `WLX_CHROMIUM_BFCACHE=off` it shows the stored one | the same | no, while U23 stands: the tests assert U23 there (`pageshow` with `persisted`, the old vote shown, a fresh load showing the stored one, a `U23` annotation), so they fail once Chromium stops restoring the stale page |
| WebKit 27.2 (Windows) | pass | pass (with `max-age=600` instead: 2 of 4 fail) | no: Back after a vote in Safari stays a manual check ([release checklist](../deployment.md#release-checklist)) |

**10.4 Sharing a server.** `UiShotsServer` writes up to eight jurors with files in both rounds to
`server.json`; each engine votes as its own juror (Chromium the first, Firefox the second, WebKit
the third), signs in and out and switches the theme as another, and checks that a seventh's votes
stay hidden, so the three engines can run at once against one server. Each voting test reads the
file's vote first and casts one that changes it, so a rerun against the same server votes again.

**10.5 Accessibility.** In Chromium each page of the tour gets an axe-core scan (WCAG 2.1 A and
AA rules; axe is injected from `node_modules` by `@axe-core/playwright`, never a CDN); its
findings go to `target/browser-test/a11y/<page>.json` and the test's attachments (CI: the
`accessibility-report` artifact). A serious or critical finding fails the test unless the spec's
`KNOWN` list has its rule for that page, pointing to its triage entry (U19–U21,
[UI](../ui/practices.md) §9): the same rule on another page fails.

**10.6 Run it locally** (Git Bash; Docker running; the shared sbt lock, [workflow](../workflow/practices.md) §4,
held while the server runs):

```bash
cd browser-test
npm ci
npx playwright install chromium firefox webkit   # once per Playwright version, ~1.2 GB
env -u COMMONS_USER -u COMMONS_PASSWORD -u SCALAWIKI_LOGIN -u SCALAWIKI_PASSWORD node fixture-server.mjs start
npx playwright test                          # the three engines; or --project=firefox
npx playwright test tests/voting.spec.js --project=webkit --headed
npx playwright show-report ../target/browser-test/report
NO_UPDATE_NOTIFIER=1 npm run report   # the Allure report, checked (§10.10)
node fixture-server.mjs stop
```

`start` compiles, starts `Test/runMain gatling.setup.UiShotsServer` in the background (log:
`target/ui-shots/server-browser-test.log`), and returns when `server.json` answers; it reuses a
server that already answers. `stop` asks it to stop and waits. A server started by
`ui-shots.py` lacks `-Dui.csrf=on` and `-Dui.images=stub`: the CSRF tests then fail and the Back
tests' thumbnails come from Commons, so stop it first. Flags: `--project=<engine>`, `--headed`,
`--debug`, `-g <title>`, `--trace on`; `WLX_CHROMIUM_BFCACHE=off` runs the routeless Chromium
without its back/forward cache (U23). A failed test keeps its trace and screenshot under
`target/browser-test/results/` (`npx playwright show-trace <zip>`); the routeless browser's
pages have no trace. Delete `target/browser-test/` when done; traces are a few MB each.

**10.7 Adding a test.** A spec under `browser-test/tests/` imports `test` and `expect` from
`lib/fixtures.js` (never from `@playwright/test`, which has no guards), takes a role, finds
elements by role and accessible name, label or text (`getByRole`, `getByLabel`, `getByText`;
an id or `data-*` attribute where the page has no name, as raty's stars), and asserts that an
element exists before asserting on it (`one()` in `lib/actions.js`, §6.2). Waits are Playwright's
auto-waiting `expect`s, `waitForURL`, and `waitForRequest`/`waitForResponse` on a named request,
never `waitForTimeout` or `networkidle`. A check of what the browser keeps between pages (Back,
caches) uses `routelessAs` (§10.3). Keep it to what only a browser shows: a template's output
belongs in a view spec, a script's logic in Jest (§1.1). A test of a flow in
[stories.md](stories.md) carries its story tag (`{ tag: '@J-21' }`, §9.4); a new user-visible flow
gets a story first. A test of the harness itself (the guards' self-tests) needs none.

**10.8 CI** (`.github/workflows/browser.yml`, its actions pinned by SHA): one job per engine in
parallel on every PR and on pushes to the integration branches, `fail-fast: false`; not a
required check yet. The fixture's dump (`data/cache`; key: what `GatlingDbCache.cacheKey` hashes,
with the database image read from `SharedTestDb.Image`'s default) is saved right after the
fixture starts, so a failed test run still keeps it; Playwright's browser is cached by the
lockfile's hash. A run reads only its own branch's and its base branch's caches, so a stacked
PR's first runs are cold (the dump built from the CSVs, the browser downloaded). The start step
has its own 25-minute timeout; traces, screenshots, the HTML report and the server's log are
uploaded on a failure or a cancellation, the accessibility report always, and each engine's
Allure results always (`allure-results-<engine>-attempt-<n>`); the `report` job (after the three,
unless the run was cancelled) merges each engine's latest into `allure-report-attempt-<n>` (§10.10), and its failure fails
no engine's job. *Decision pending:* which engines stay per PR, from **GitHub's job duration** of
each engine's job; the job's summary adds its steps' times (fixture start, tests).

**10.9 Times** (2026-11-04; the user decides from CI's own numbers what stays per PR). Locally
(Windows 11, the server on the same machine, 3 workers; 55 tests per engine, 4 of them the
sign-ins, 2 `fixme`):

| | Chromium | Firefox | WebKit |
|---|---|---|---|
| tests, the engine alone (2026-11-04, after the review) | 120 s | 188 s | 176 s |
| the same, before the routeless browser and the new guards' tests | 68 s | 91 s | 91 s |
| CI job, estimated (to replace with GitHub's job duration) | ~11 min | ~13 min | ~13 min |

In Chromium the 4 Back tests assert U23's stale page instead of the stored vote. All three engines at once against one
server: 314 s. The routeless browser (one more browser per worker, its pages loaded
without the guard's stubs) and the new guards' tests roughly doubled the local times. The
fixture's start: 170–260 s with a compiled checkout and the cached dump (sbt start and an
incremental compile, then ~70 s for the fixture); the first start in a fresh checkout compiled
the app and tests (~8 min here) and built the dump from the CSVs (~1.5 min). Setting the images'
Commons urls in one transaction instead of a commit per row took ~2.5 minutes off every start.
*The CI estimate:* setup and caches ~1.5 min, the browser and its system packages ~1 min, a cold
`Test/compile` ~3.5–4 min (`ci.yml`'s job, compile and the whole suite, is ~5½ min), the fixture
~1.5 min (+1.5 on a dump-cache miss), the tests ~1.5–2.5× the local times on a 4-CPU runner with
2 workers. The compile is most of each job; if the three jobs are too slow, one compiling job
sharing `target/` with the engines' jobs is the first saving, before dropping an engine from PRs.

- **Mobile profiles later,** together with mobile support (UI §6.2; mobile is not supported yet).
- **From Montage's Cypress tests** (`hatnote/montage` `d879cb8`, `frontend/cypress/`), taken:
  waiting on a named request instead of a delay (`cy.intercept(...).as()` / `cy.wait('@alias')`:
  here `waitForRequest` on the vote, theme and sign-out POSTs and the jurors' table), a session
  set up before the visit (`cy.setCookie`: here the saved sessions), edit-then-cancel (the round
  form), and "a double click must not save twice" as an idea for later (`data-submit-once`). Not
  copied: assertions inside `if ($body.find(…).length > 0)` (they pass when the element is
  missing, §6.2), CSS-class selectors (§6.1), and the hard-coded dev-server URL
  (`http://localhost:5173`; here the base URL comes from `server.json`).

**10.10 The Allure report.** `allure-playwright` (a reporter beside `list`, `html` and `json`)
writes each test's result to `target/browser-test/allure-results/`, with its engine as the
"Project" parameter, its tags as `tag` labels, and, from the `storyLabels` fixture (§9.4), its
stories as labels: epic the role, feature the guide section (else the role), story the ID and
the catalogue's sentence. `npm run report` (from `browser-test/`) runs `allure generate`
(Allure Report 3, `allurerc.mjs`) and then `node allure-offline.mjs`, so no report is used or
uploaded unchecked. It builds **one HTML file**, `target/browser-test/allure-report/index.html`,
scripts, styles and data inlined: open it in a browser, no server needed. Its **Behaviors** tab
groups the tests by epic, feature and story (a test with several stories under each, a test per
engine); Suites by spec file.

- **The allure2 plugin, not Awesome** (the user, 2026-11-04). Awesome, Allure 3's default report,
  hard-codes Google Analytics (`gtag.js` from `www.googletagmanager.com`: `analyticsEnable: true`
  in `@allurereport/plugin-awesome`'s `generators.js`, as in the classic and dashboard plugins),
  with no opt-out; upstream, allure3#476 and PR #784 are open. The allure2 plugin honours
  `ALLURE_NO_ANALYTICS` (`generators.js:129`), set to `true` in `allurerc.mjs` and again in the
  report job's env. **Recheck on every Allure update:** the plugin's analytics switch, and that
  the check below still passes.
- **The check** (`allure-offline.mjs`): it fails if the report has a Google Analytics loader
  (`googletagmanager`, `gtag`), or markup or CSS that loads a script, stylesheet, image, frame,
  media or `url()` from another host, or is no report at all (no `<head>`), in every `*.html`
file of `allure-report/` (no `index.html` fails too); else it puts a
  Content-Security-Policy `<meta>` first in `<head>` (`default-src 'none'`; scripts and styles
  inline, `data:` and `blob:` URLs), so the browser refuses any other host even if a later
  Allure adds one. `npm run report:selftest` checks it on reports that must fail (a gtag block,
  a CDN stylesheet, a protocol-relative script, a CSS `url()`, no `<head>`) and one that passes.
  Shown on 2026-11-04 in Chromium with every non-file request refused: the allure2 report with
  the CSP draws its tabs with no request leaving the file and no console error (`base-uri` is
  left out of the CSP: the report sets its own `<base>`).
- **Traces:** a failed test's trace is in the report as an attachment. The allure2 bundle's
  trace viewer would load `trace.playwright.dev` (Playwright's hosted viewer) in an `<iframe>`
  and post the trace to it; the report's CSP (`frame-src data: blob:`) blocks that frame, so a
  trace is neither sent to that site nor viewable in the report. Download it from the report,
  or take it from the `browser-failures-<engine>-attempt-<n>` artifact, and open it with
  `npx playwright show-trace <zip>`.
- **Size:** a Chromium run (55 tests, the a11y attachments): 3.8 MB, from 0.7 MB (72 files) of
  results; a history entry about 70 KB, so 30 runs (`historyLimit`) about 2 MB.
- **CI:** each engine's job uploads `allure-results-<engine>-attempt-<n>`; the `report` job
  takes every attempt's artifact and merges each engine's latest (a re-run of the failed jobs
  leaves the passed engines' results under the earlier attempt; result files have unique names,
  and the "Project" parameter keeps an engine's run of a test apart), then uploads
  `allure-report-attempt-<n>` (14 days), its size in the job's summary. **An artifact only:**
  GitHub Pages would publish test names, steps and failure screenshots, and is not decided
  (plan [`2026-11-04-story-coverage.md`](../plans/2026-11-04-story-coverage.md) §4.5).
- **History:** `historyPath` is a JSONL file (`target/browser-test/allure-history/`), kept in
  the Actions cache under a run's key and restored by prefix; it is appended
  (`ALLURE_APPEND_HISTORY=true`) only on pushes to `master` and `ukwikidev/followups`, so a
  PR's report shows trends against its base without adding to them. A cache unused for seven
  days is evicted: the trend restarts, no result is lost.
- **Not in it yet:** the Scala specs (Allure 3 reads JUnit XML, but `ci.yml` is another
  workflow; their IDs are in the matrix, §9.4).
- **Locally:** after a run, from `browser-test/`, `NO_UPDATE_NOTIFIER=1 npm run report` (the
  variable keeps npm's update notice out of the output). The paths are from that directory: the
  reporter's `resultsDir` is resolved from the working directory. Results accumulate across runs
  until `target/browser-test/` is deleted.

## 11. Guards

| Rule | Enforced by | State |
|---|---|---|
| 2.4 the pool belongs to Play's app; the tests' own pool is HikariCP | `ConnectionPoolConfigSpec` | enforced |
| 3.1 lock specs fail without the lock | each lock spec's own "without the lock" check (db §7.6) | enforced per spec |
| 3.2 `round_image` invariants | `RoundImageInvariants` in the write specs | enforced |
| 4.2 guards not vacuous | a self-test in `RouteAuthSpec`, `LogHygieneSpec`, `RoleValuesSpec`, `VoteRowLockSpec`, `TestConfigAnonymousSpec`; `StructuralWritesSpec` checks its list for stale entries | partly enforced: not in `RawHtmlSinksSpec`, `RawSqlSpec`, `ErrorTextSpec`, `ExportsSpec` (§12, T8) |
| 4.4 production-like chains | `FlywayUpgradeSpec`, `ConvertToInnoDbMigrationSpec` | enforced |
| 5.1 anonymous Wikimedia, no credentials in workflows | `TestConfigAnonymousSpec`, `AnonymousBotsSpec` | enforced |
| 5.3 live specs off by default | `skipAllUnless` / `skipped` in each live spec | enforced per spec |
| 6.1 no CSS-class assertions | review | planned: a scan of `test/` and `javascript-test/` for class selectors, with an allowlist of classes that are the behaviour (`is-invalid`, `active`; Jest's `show`, `collapsed`, `tomselected`, `loading`) |
| 6.2 no assertion on a missing element | review; `one()` in `browser-test/lib/actions.js` | review; the browser tests' helper requires exactly one match |
| 6.4 messages in all four files | `views.MessagesCompletenessSpec` | enforced |
| 9 coverage | `coverage.yml` (report only) | enforced as a report; no minimum (planned after a stable baseline) |
| 9.5 the PR comment reads its artifact as validated data, never the PR's code | `scripts/test_coverage_reports.py` (a step of `coverage.yml`); review of `coverage-comment.yml` | enforced for the parser; the workflow by review |
| 9.4 story IDs known, guide anchors exist | `scripts/story-coverage.py` in `ci.yml` (with its `--self-test`); the `storyLabels` fixture in each browser test | enforced |
| 9.4 uncovered stories | the script's summary (`target/story-coverage.md`, the job's summary) | a report; no minimum until the catalogue has settled |
| 10.10 Allure report per run | `browser.yml`'s `report` job | enforced as a report (advisory: it fails no engine's job) |
| 10.10 no analytics, nothing from another host in the report | `allure-offline.mjs` in `npm run report` (the report job, before the upload), with its self-test; `ALLURE_NO_ANALYTICS` | enforced in the report job; recheck on every Allure update |
| 10 browser tests, one current build per engine | `browser.yml`: a job per engine (Chromium, Firefox, WebKit) on every PR | enforced in CI once the job runs; not yet a required check |
| 10 the supported range (previous majors, the floor) | the compatibility lint (`.browserslistrc`; `ci.yml`'s job), with its self-test (`lint:selftest`) | enforced for our scripts' APIs and the CSS features caniuse lists; nothing runs in those versions |
| 10.2 zero CSP violations, console errors, failed requests, other hosts per page | `browser-test/lib/fixtures.js` (every test); its self-tests in `guards.spec.js` | enforced in CI once the job runs; not yet a required check |
| 10.3 Back after a vote | `voting.spec.js` in the routeless browser | enforced in Firefox once the job runs; in Chromium U23 asserted instead; WebKit can't show it (manual in Safari) |
| 10.5 accessibility | axe-core per page of `pages.spec.js` (Chromium) | enforced in CI once the job runs, for serious and critical findings not triaged for that page; the rest a report; not yet a required check |
| 7.2 no wall-clock waits in specs | review | planned |

## 12. Where the code doesn't follow these yet (for triage)

Ids continue the dependencies doc's T1–T4.

- **T5 (6.1):** CSS-class selectors in specs: `GalleryByRateViewSpec` (`.visually-hidden`,
  `.wlx-approvals`, `.bi-check-lg`, `.wlx-byrate-stars`), `LargeViewSideSpec` (`.large-side`,
  `.large-nav`, `.wlx-vote-unrated`).
- **T6 (6.2):** absence assertions that pass when the page changed:
  `doc.select(".wlx-approvals").size === 0` (`GalleryByRateViewSpec`); `ThemeViewSpec`'s
  `if (…) page must not(contain(value))`.
- **T7 (7.2):** `Thread.sleep` in `JobSchedulerSpec`, `LocalImageCacheServiceSpec`,
  `ConvertToInnoDbMigrationSpec` and `CommonsFileCheckSpec`.
- **T8 (4.2):** `RawHtmlSinksSpec`, `RawSqlSpec`, `ErrorTextSpec` and `ExportsSpec` have no
  self-test that their scan still matches anything.
