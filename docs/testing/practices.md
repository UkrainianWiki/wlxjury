# Testing practices

How the jury tool is tested: which layer a test belongs to, how it stays isolated, what it may
and may not assert, and what a run costs. Each rule has a short why and an example in this
repository. Reviewers tick [review-checklist.md](review-checklist.md). Rules owned by another
practices doc are pointed to, not repeated: DB isolation and lock specs in
[db/practices.md](../db/practices.md) §7.6, §9; load tests and benchmarks in
[performance/practices.md](../performance/practices.md); negative authorization cases and the
security guards in [secure-coding.md](../security/secure-coding.md) §9 and §21; how agents run
checks in [workflow](../workflow/practices.md) §9. **Sources:** the CI plan
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
`BeforeEach(SharedTestDb.truncateAll())`. *Why:*
`autoSession` opens its own connection and sees only committed data, so an outer rollback
transaction is invisible to it (`Secured.userFromRequest`, `GalleryService`, `Round.addUsers`).

| Scenario | Approach |
|---|---|
| Pure DAO tests (session explicitly threaded) | `new AutoRollbackDb { }` (specs2) or `dbTest { session => }` (`AutoRollbackMunitDb`) |
| Service/controller tests (`autoSession` internally) | `BeforeAll` + `BeforeEach(SharedTestDb.truncateAll())` |
| Tests needing a running Play app | extend `PlayTestDb`, call `testDbApp { implicit app => }` |
| Tests needing custom Play config | add `implicit val cfg: Map[String, String] = Map(...)` before `testDbApp` call — starts a fresh per-test app |
| Tests through the release's filters (CSP, allowed hosts, CSRF on) | `ProductionFilters.withSharedApp { app => }` (or `CsrfTestApp.withCsrfApp`): one JVM-wide app, truncated per call, never stopped by a spec (no `running`, no `TestServer`); an example that moves app-level state (the pre-S3 upgrade window), binds modules or serves the app uses `ProductionFilters.withApp` / `withAppWith`; one config shared by a spec's examples: `ProductionFilters.OwnApp`, stopped in `afterAll` |

The DAOs are available in specs as `contestDao`, `imageDao`, `roundDao`, `selectionDao`
(`VoteJdbc`) and `userDao` (`TestDb`). A `hasManyThrough` lazy val on a companion object
(`Round.usersRef`) must be accessed once before `round.users` is populated;
`SharedTestDb.initialized` does this.

**2.2 One MariaDB per JVM, on tmpfs.** `SharedTestDb.forTests`: MariaDB 10.6 (`SharedTestDb.Image`;
10.11 by `-Dwlxjury.test.mariadb-image=mariadb:10.11.14`, which moves the Gatling fixture to that
version too), data directory on tmpfs (`size=1g`, so a runaway spec fails with "table is full"),
128M buffer pool, 48M redo log, flushing off, 1M `max_allowed_packet`, 2000 open tables. The
same setup serves `ConvertToInnoDbMigrationSpec`'s databases (on this server) and
`SchemaBaselineSpec`'s container; not the Gatling fixture's, which stays production-like. The
data lives in the Docker VM's memory (about 350 MB). *Why:* `TRUNCATE` re-creates table files:
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
its external target and `WritePathStress` use the same class (`WritePathStress` takes only
`maxSize` from its `ConnectionPoolSettings`). A Play app's start makes its own HikariCP pool ScalikeJDBC's default
(`PlayDBApiAdapterModule`; `ConnectionPoolConfigSpec`); `running(app)` or `app.stop()` closes it.
A spec that uses `running(app)` calls `SharedTestDb.reregisterDefault()` in a `finally`.
Registering again closes a replaced `HikariTestPool`; a replaced Play pool (a plain
`DataSourceConnectionPool`) is not closed that way, Play closes it when the app stops. *Why:* otherwise every later spec
in the JVM fails on a closed pool; and a spec on another pool than production's tests a pool that
never runs.

**2.5 Order independence.** A spec seeds what it reads and never relies on ids from 1 or on rows
another spec left; `AutoRollbackDb` empties the database before each example (`a65e165c`). A
spec's raw `INSERT INTO vote` adds its `round_image` row too, or calls `RoundImageJdbc.sync`
([db](../db/practices.md) §3.7).

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

**4.5 Query plan checks.** `QueryPlanSpec` (`test/plans/`,
[`2026-11-13-query-plan-checks.md`](../plans/2026-11-13-query-plan-checks.md)) checks the juror
statements' query plans on a seeded corner with production-scaled statistics:
`sbt "testOnly plans.QueryPlanSpec"`, about 1 min, in a forked JVM and MariaDB container of its
own (`Test / testGrouping`). Outside that group, or on 10.11, it skips and says why;
`testOnly db.*` doesn't include it. Its report: `target/query-plans/summary.md`.

## 5. Wikimedia, live and high-load tests

**5.1 Tests are anonymous to Wikimedia.** The test config sets `commons.user`/`commons.password`
to `""` with no `${?COMMONS_USER}`; `CommonsLogin.credentials` gives no account in Mode.Test
unless `commons.allowTestLogin`; wlxjury's bots come from `services.imports.AnonymousBots`, and
`AppModule` seeds scalawiki's bot cache with them. The only opt-in is a deliberate manual one with
all three in the forked JVM:
`set Test / javaOptions ++= Seq("-Dcommons.allowTestLogin=true", "-Dcommons.user=…", "-Dcommons.password=…")`.
No spec or CI job sets it, and `build.sbt` doesn't forward it from sbt's command line. How the
bots stay anonymous inside scalawiki, and the one case not covered, is
[integrations](../integrations/practices.md) §1.1. Guards: `TestConfigAnonymousSpec` (the test
config and the workflows/actions pass no `COMMONS_*` / `SCALAWIKI_*` credentials and no secrets
but `GITHUB_TOKEN`), `AnonymousBotsSpec` (the app seeds the bots, and `app/` creates no bot or
`MonumentQuery` with scalawiki's default login). *Why:* a machine with the bot's credentials
logged the bot in from every test JVM, and a changed bot password became a stream of failed
logins on its account (CI plan, "Anonymous Wikimedia access").

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
Wikimedia from every run. The live group by hand:
`sbt -Dintegration=true "testOnly controllers.AppendImagesSpec controllers.ImagesFromListSpec services.LocalImageCacheServiceIntegrationSpec"`
(with the direct launcher on Windows the `-D` goes before `-jar`).

**5.4 OAuth providers are faked.** `OAuthControllerSpec` and `OAuthRouterSpec` use fake providers;
`ProvidersSpec` runs the real `GoogleProvider` and `WikimediaProvider` against a local fake
server. No spec calls Google or Wikimedia ([integrations](../integrations/practices.md) §3).

**5.5 Mail is mocked.** `play.mailer.mock = yes`; specs read invitation and password links from
`controllers.RecordingMail`.

## 6. What a test asserts

**6.1 Ids, data attributes, roles and visible text; never CSS classes.** Find elements by `id`,
`data-*`, ARIA role or accessible name, and assert text; a class only where the class is the
behaviour (`is-invalid`, `active`). *Why:* the Bootstrap 3 → 5 move renamed most classes; tests
on styling broke without any behaviour change (theme-modes invariants). Don't run tests that
merely assert CSS class names (§8.1).

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
through `python scripts/agent-check.py -- …` from Git Bash ([workflow](../workflow/practices.md)
§9.4). Locally, the full suite runs once at a release boundary when required; after a failure,
fix and rerun the affected specs before another full run. Repeat broad tests only for a
shared-code change, unresolved risk, or an explicit clean-release gate. CI runs the full suite on
every PR (§8.3). No local tests for documentation-only changes or for tests that merely assert
CSS class names.

**8.1a Every local run has a time limit.** `agent-check.py` stops the run's whole process tree
after `--timeout` seconds (default 1200 for targeted specs; `--timeout 1800` for the full suite,
about 10.5 min plus 3–8 for a cold compile) and exits 124 with `TIMED OUT after N s`, keeping the
log. `--timeout 0` means none (the CI workflows pass it and rely on each job's
`timeout-minutes`). A 124 is a stalled run, not a failing test: read the log's tail (the last
spec started, a Hikari clock-leap warning) before rerunning, and never raise the limit to hide a
hang (§7.1).
The tree is a Windows Job Object (killed with the script, a hard kill included; `taskkill /T /F`
only if the job can't be made, and the exit line says `tree=job-object` or `tree=taskkill`) or a
POSIX process group (the script's SIGTERM/SIGHUP/SIGINT forwarded to it), so neither a timeout
nor a stopped agent leaves a test JVM behind. On a normal exit, closing the job kills anything
the command left running (an `sbt --client` server, daemons): don't use the script for commands
that start daemons. A run longer than the Bash tool's 600 s foreground limit goes in the
background (workflow §9.5). `scripts/test_agent_check.py` runs in CI (`ci.yml`, its Windows
parts mocked or skipped). The script's `time.monotonic()` is `GetTickCount64()` on Windows
(Python 3.8), which per Microsoft's docs keeps counting in sleep, so a run that slept past the limit is stopped once
the host wakes; Linux's `CLOCK_MONOTONIC` doesn't count a suspend. On Windows it also holds a
keep-awake request while the command runs (`SetThreadExecutionState(ES_CONTINUOUS |
ES_SYSTEM_REQUIRED)` from the waiting thread, released at the end, a timeout or an interrupt;
not the display, which may still sleep; `--allow-sleep` opts out), so idle sleep on battery
doesn't stop a long run; lid and power-button actions and critical-battery hibernation still
can, which is what the timeout and the stress harness's INVALID marking (§8.1b) are for. Agents
never change the machine's power settings.
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
in **one build of each engine: the one the pinned Playwright bundles** (1.64.1: Chromium 156,
Firefox 157, WebKit 27.2). *Why Playwright Test rather than the Python Playwright of
`ui-shots.py`:* projects per engine, sign-in projects as dependencies, traces and screenshots on
failure, and an HTML report come with it; Node is already in CI for Jest. What each spec covers:
[browser-tests.md](browser-tests.md).

**What is supported, tested and linted** are three different things. *Supported* is the policy
([UI](../ui/practices.md) §6.1, §6.1a): the current and previous majors of Chromium-based
browsers, Firefox and Safari, and the floor Chrome 109 and Firefox 115. *Tested in a browser* is
only the current Playwright build of each engine. *Linted* is the whole supported range
(`.browserslistrc`; `npm --prefix javascript-test run lint`, a step of `ci.yml`'s job). Older
versions are not run and not planned, so they have only the lint, which can't see rendering or
behaviour differences, or a feature a polyfill or a fallback covers.

**10.1 What runs.** `playwright.config.js` has a project per engine (`chromium`, `firefox`,
`webkit`), each depending on its sign-in project (`setup-<engine>`, `tests/auth.setup.js`), which
signs in through the login form once per role (juror, organizer, admin, root) and saves the
session to `target/browser-test/auth/<engine>-<role>.json`. A test takes a role with
`test.use({ role: 'juror' })`, or opens another session with `contextAs(role)`. Screenshots run
only when asked for. The specs, one by one: [browser-tests.md](browser-tests.md).

**10.2 Every page passes** (`lib/fixtures.js`, for every context of every test). Images and
videos from `upload.wikimedia.org` and this host's `/wikipedia/commons/thumb/` get a placeholder
PNG; a request to any other host, lookalikes included, is refused and fails the test. A CSP
violation, a console error, an uncaught page error, a response of 400 or more or a failed request
fails it, listed by page (a request the page cancelled by leaving, or the guard refused, is
reported as such). A test that opens a 403 or 404 page on purpose says so with
`diagnostics.expectStatus(403)`, for the page's own document only. The only other allowances:
Firefox's console line for a font download a navigation cancelled, and U22's page error in the
two tests that leave a gallery by a POST, in Firefox only. The route doesn't see WebSockets; the
`websocket` event reports one to another host. In local WebKit runs on Windows an init script
(`noMediaSources`) removes the pages' media sources (the original URL stays in `data-wlx-src`).
*Why:* WebKit's media player there fetches from Commons past the routes, and a webm keeps the
load event from firing. The fixture runs with the production CSP, CSRF checked on POSTs, and its
image proxy fetching from a stub in its JVM, never from Commons (`-Dui.csp=production
-Dui.csrf=on -Dui.images=stub`, `fixture-server.mjs`). The guides' screenshots use a docs image
mode: [browser-tests.md](browser-tests.md#opt-in-screenshots).

**10.3 Back after a vote, without routes.** The Back checks run in a second browser per worker
(`routelessAs`): no routes, other hosts sent to a proxy that isn't there (`127.0.0.1:9`; this host
bypasses it), and Chromium (its full build) with its back/forward cache. *Why:* any Playwright
route turns the HTTP cache off, and Playwright starts Chromium with
`--disable-back-forward-cache`, so a page Back brings back is always loaded again and the checks
would pass without `no-store`. The guard's listeners still report every other host.
`guards.spec.js` checks that the proxy refuses an image, an https `fetch` and a `wss://`
WebSocket, with each engine's error (`lib/proxy-errors.js`). **The texts differ by platform**: a
local Windows run doesn't prove CI's Linux ones; `npm run check:proxy-errors` checks the patterns
against both, and a new text comes from a CI log. Which engine catches a dropped `no-store`:
[browser-tests.md](browser-tests.md#back-after-a-vote-which-engine-catches-a-dropped-no-store).

**10.4 Sharing a server.** Each engine votes as its own juror and each voting test changes the
file's vote, so the three engines can run at once against one server and a rerun votes again.
The jurors per engine: [browser-tests.md](browser-tests.md#jurors-and-fixture-data).

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
npx playwright install chromium firefox webkit   # once per Playwright version
env -u COMMONS_USER -u COMMONS_PASSWORD -u SCALAWIKI_LOGIN -u SCALAWIKI_PASSWORD node fixture-server.mjs start
npx playwright test                          # the three engines; or --project=firefox
npx playwright test tests/voting.spec.js --project=webkit --headed
npx playwright show-report ../target/browser-test/report
NO_UPDATE_NOTIFIER=1 npm run report   # the Allure report, checked (§10.10)
node fixture-server.mjs stop
```

`start` compiles, starts `Test/runMain gatling.setup.UiShotsServer` in the background (log:
`target/ui-shots/server-browser-test.log`), returns when `server.json` answers, and reuses a
server that already answers. Stop a server started by `ui-shots.py` first: it lacks
`-Dui.csrf=on` and `-Dui.images=stub`. Flags: `--project=<engine>`, `--headed`, `--debug`,
`-g <title>`, `--trace on`; `WLX_CHROMIUM_BFCACHE=off` runs the routeless Chromium without its
back/forward cache (U23). A failed test keeps its trace and screenshot under
`target/browser-test/results/` (`npx playwright show-trace <zip>`); the routeless browser's pages
have no trace. Delete `target/browser-test/` when done.

**With Linux's fonts:** `node linux-fonts.mjs <Playwright arguments>` runs the tests in the
official Playwright image of the version in `package-lock.json` (pinned by digest, Ubuntu 24.04 as
CI's runners) with DejaVu Sans as `system-ui`, against the fixture server on the host
(`host.docker.internal`); `LINUX_FONTS=image` keeps the image's own fonts. *Why:* Ubuntu draws
text wider than Windows, so a width test can pass on Windows and fail on CI. On a Linux host the
results in `target/browser-test/` are root's.

```bash
node linux-fonts.mjs tests/navbar.spec.js -g "one line"                     # the three engines
node linux-fonts.mjs tests/navbar.spec.js -g "one line" --project=chromium
```

**10.7 Adding a test.** A spec under `browser-test/tests/` imports `test` and `expect` from
`lib/fixtures.js` (never from `@playwright/test`, which has no guards), takes a role, finds
elements by role and accessible name, label or text (`getByRole`, `getByLabel`, `getByText`;
an id or `data-*` attribute where the page has no name, as raty's stars), and asserts that an
element exists before asserting on it (`one()` in `lib/actions.js`, §6.2). Waits are Playwright's
auto-waiting `expect`s, `waitForURL`, and `waitForRequest`/`waitForResponse` on a named request,
never `waitForTimeout` or `networkidle`. Never assert inside an `if` on whether an element is
there (it passes when the element is missing, §6.2), never select by CSS class (§6.1), and take
the base URL from `server.json`. A check of what the browser keeps between pages (Back, caches)
uses `routelessAs` (§10.3). Keep it to what only a browser shows: a template's output belongs in
a view spec, a script's logic in Jest (§1.1). A test of a flow in [stories.md](stories.md)
carries its story tag (`{ tag: '@J-21' }`, §9.4); a new user-visible flow gets a story first. A
test of the harness itself (the guards' self-tests) needs none. Add the spec's row to
[browser-tests.md](browser-tests.md).

**10.8 CI** (`.github/workflows/browser.yml`, its actions pinned by SHA): one job per engine in
parallel on every PR and on pushes to the integration branches, `fail-fast: false`; not a
required check yet. The fixture's dump (`data/cache`, keyed as `GatlingDbCache.cacheKey`) is
saved right after the fixture starts, so a failed test run still keeps it; Playwright's browser is
cached by the lockfile's hash. A stacked PR's first runs are cold: a run reads only its own
branch's and its base branch's caches. The start step has its own 25-minute timeout. Traces,
screenshots, the HTML report and the server's log are uploaded on a failure or a cancellation;
the accessibility report and each engine's Allure results always. The `report` job merges them
(§10.10); its failure fails no engine's job.

**10.9 Times.** Which engines run per PR is decided from **GitHub's job duration** of each
engine's job (its summary shows the fixture start and the tests), not from local times. The
compile is most of each job: one compiling job sharing `target/` is the first saving, before
dropping an engine from PRs.

**10.10 The Allure report.** `allure-playwright` writes each test's result to
`target/browser-test/allure-results/`, labelled with its engine, tags and stories (§9.4).
`npm run report` (from `browser-test/`; `NO_UPDATE_NOTIFIER=1` keeps npm's notice out) runs
`allure generate` (Allure Report 3, `allurerc.mjs`) and then the check, and builds **one HTML
file**, `target/browser-test/allure-report/index.html`, that opens without a server. Results
accumulate until `target/browser-test/` is deleted.

- **The allure2 plugin, not Awesome.** *Why:* Awesome, Allure 3's default report, hard-codes
  Google Analytics with no opt-out (upstream: allure3#476, PR #784); the allure2 plugin honours
  `ALLURE_NO_ANALYTICS`, set to `true` in `allurerc.mjs` and in the report job's env. **Recheck
  on every Allure update:** the plugin's analytics switch, and that the check still passes.
- **The check** (`allure-offline.mjs`) fails a report that loads anything from another host
  (a Google Analytics loader, a script, stylesheet, image, frame, media or `url()`) or is no
  report at all; else it puts a Content-Security-Policy `<meta>` first in `<head>`
  (`default-src 'none'`, `base-uri` left out since the report sets its own `<base>`), so the
  browser refuses any other host even if a later Allure adds one. `npm run report:selftest`
  checks it. *Why:* no report is used or uploaded unchecked.
- **Traces** are attachments; the report's CSP blocks the hosted trace viewer, so a trace is
  neither sent to `trace.playwright.dev` nor viewable in the report. Download it (or the
  `browser-failures-<engine>-attempt-<n>` artifact) and open it with `npx playwright show-trace`.
- **CI:** the `report` job merges each engine's latest attempt and uploads
  `allure-report-attempt-<n>` (14 days). **An artifact only:** GitHub Pages would publish test
  names, steps and failure screenshots, and is not decided
  (plan [`2026-11-04-story-coverage.md`](../plans/2026-11-04-story-coverage.md) §4.5).
- **History:** a JSONL file (`historyPath`, the last 30 runs by `historyLimit`) in the Actions
  cache, appended only on pushes to the branches `browser.yml` runs on push, so a PR's report shows
  trends against its base without adding to them. A cache unused for seven days is evicted: the
  trend restarts, no result is lost.
- **Not in it yet:** the Scala specs (`ci.yml` is another workflow; their IDs are in the matrix,
  §9.4).

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
