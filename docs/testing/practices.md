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
10. [Browser tests (planned)](#10-browser-tests-planned)
11. [Guards](#11-guards)

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
| Browser | planned: §10 | what only a real engine shows |

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
1M `max_allowed_packet`, READ COMMITTED in the URL. *Why:* `TRUNCATE` re-creates table files:
0.5–1 s per `truncateAll` on disk, 30–40 ms on tmpfs; the suite went from 21:25 to 6:57
(`2d5d0ef4`). It is fast, not durable: never a benchmark (performance §4.6).

**2.3 Lock timeouts fail fast.** The test server runs `--innodb-lock-wait-timeout=10` (row
locks, MariaDB's default 50 s) and `--lock-wait-timeout=60` (metadata locks, default a day). *Why:* a leaked lock or an open transaction now fails the spec with an error naming
it instead of hanging the suite (one `CoiRoundSpec` statement once waited 26 minutes; CI plan,
"Test coverage"). A spec that tests a timeout sets its own session value and resets it in
`finally` (`CriteriaRateSpec`).

**2.4 Pool lifecycle.** A Play app's start makes its HikariCP pool ScalikeJDBC's default
(`PlayDBApiAdapterModule`; `ConnectionPoolConfigSpec`); `running(app)` or `app.stop()` closes it.
A spec that uses `running(app)` calls `SharedTestDb.reregisterDefault()` in a `finally`.
*Why:* otherwise every later spec in the JVM fails on a closed pool.

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
`views.*` specs (db §10, [UI](../ui/practices.md) §6). A guard's allowlist is one entry per file
or action with a reason; a hit is fixed or allowlisted for that one file, never silenced by
widening a pattern (secure-coding header).

**4.2 A guard must not pass vacuously.** Each guard has a self-test that its scan finds the thing
it guards: a known good match in the real sources, and a bad example it flags. *Examples:*
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
`ImportScaleSpec`; the Gatling stress flags (performance §1.3). `build.sbt` forwards exactly these
keys to the forked JVM. Everything else uses fakes: `FakeCommonsSource`, `FakeWikimedia`,
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
exception text is absent (`LoginControllerSpec`, `ThemeViewSpec`; secure-coding §12).

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
through `python scripts/agent-check.py -- …` from Git Bash (CLAUDE.md "Agent efficiency"). The
full suite runs once at a release point or a merge into the integration branch; after a failure,
fix and rerun the affected specs before another full run. No tests for documentation-only changes.

**8.2 One sbt JVM at a time** across worktrees and agents: the shared lock
([workflow](../workflow/practices.md) §4).

**8.3 CI on every PR.** `ci.yml`: the full sbt suite and Jest on MariaDB 10.6.22 (10.11.14 on
master pushes and weekly), about 5½ minutes; `deployment.yml`: the Python tests and the release
tests; `coverage.yml`: the same suite instrumented (§9); `integration.yml`: the live group weekly. The check to require on `master` is
`CI / sbt test (mariadb:10.6.22)` (CI plan, U8); coverage is never required.

**8.4 Read results by totals and exit code.** A failure-path test may log `[error]`; the totals and
the process's exit code decide. Full logs stay under `target/checks/`.

## 9. Coverage

**9.1 What it measures.** scoverage (statements, branches) for Scala, V8 for Jest (`coverage.yml`,
job summary by `scripts/coverage-summary.py`; locally `sbt coverage test coverageReport`, then a
new session before any benchmark or `dist`). It measures what runs, not what is asserted: a line
run by a test without an assertion on its result counts as covered. Twirl templates are not
measured (generated code is excluded; the hand-written `app/views/bs/` helpers are); the vendored,
patched gallery (`unitegallery.js`, `ug-theme-tiles.js`) and `jquery.hotkeys`, `jquery.raty` are
outside Jest's `collectCoverageFrom`.

**9.2 The baseline** (`4a861818`, run [41649422116](https://github.com/UkrainianWiki/wlxjury/actions/runs/41649422116)):
Scala **89.87 %** statements, **85.84 %** branches; JS **96.76 %** lines, **91.39 %** branches;
1,902 Scala examples, 253 Jest tests. Scala moves by about a tenth between runs (timing-dependent
scheduler paths): read smaller differences as noise.

**9.3 How to use it.** Read the report for the files a change touched: an uncovered branch there
is a missing test case. No minimum is enforced yet (a threshold a few points under a stable
baseline is a later decision, CI plan). The gallery's own tests are queued (roadmap, "the patched
gallery").

## 10. Browser tests (planned)

Not built yet; queued in the roadmap. Today `scripts/ui-shots.py` and `ui_checks.py` cover the
visual and two behaviour checks by hand ([UI](../ui/practices.md) §5).

- **Engines:** Playwright on desktop Chromium, Firefox and WebKit, as parallel jobs on every PR.
  Record each engine's job time first, then decide which engines stay per PR and which move to
  master pushes.
- **Mobile profiles later,** together with mobile support (UI §7).
- **Sign-in once per role:** a saved storage state per role (root, admin, organizer, juror),
  made by signing in through the form at the start of the run, not per test.
- **No network:** Commons and image URLs stubbed by route (as `ui-shots.py` does), any other host
  blocked and listed; the server is the `UiShotsServer` fixture.
- **Every page passes:** zero CSP violations and zero console errors, collected per page.
- **From Montage's Cypress tests** (`hatnote/montage` `d879cb8`, `frontend/cypress/`): take request
  stubs with named aliases and waiting on them, a session set up before the visit, and
  edit-then-cancel flows. Don't copy: assertions inside `if ($body.find(…).length > 0)` (they pass
  when the element is missing, §6.2), CSS-class selectors (§6.1), and the hard-coded dev-server
  URL (`http://localhost:5173`; take the base URL from the fixture's `server.json`).

## 11. Guards

| Rule | Enforced by | State |
|---|---|---|
| 2.4 the pool belongs to Play's app | `ConnectionPoolConfigSpec` | enforced |
| 3.1 lock specs fail without the lock | each lock spec's own "without the lock" check (db §7.6) | enforced per spec |
| 3.2 `round_image` invariants | `RoundImageInvariants` in the write specs | enforced |
| 4.2 guards not vacuous | a self-test in each guard spec | enforced (one per guard) |
| 4.4 production-like chains | `FlywayUpgradeSpec`, `ConvertToInnoDbMigrationSpec` | enforced |
| 5.1 anonymous Wikimedia, no credentials in workflows | `TestConfigAnonymousSpec`, `AnonymousBotsSpec` | enforced |
| 5.3 live specs off by default | `skipAllUnless` / `skipped` in each live spec | enforced per spec |
| 6.1 no CSS-class assertions | review | planned: a scan of `test/` and `javascript-test/` for class selectors, with an allowlist (`is-invalid`, `active`) |
| 6.2 no assertion on a missing element | review | planned with the browser tests (a helper that requires exactly one match) |
| 6.4 messages in all four files | `views.MessagesCompletenessSpec` | enforced |
| 9 coverage | `coverage.yml` (report only) | enforced as a report; no minimum (planned after a stable baseline) |
| 10 browser tests | — | planned (roadmap) |
| 7.2 no wall-clock waits in specs | review | planned |
