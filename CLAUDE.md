# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

WLX Jury Tool is a Play Framework (Scala) web application for image selection and rating in Wiki Loves Monuments and Wiki Loves Earth photo contests. Jurors browse images, rate/select them across configurable rounds, and organizers manage contests and view aggregated results. Images are fetched from Wikimedia Commons.

## Looking Up Library Sources

**Before searching the web for any library API** (ScalikeJDBC, Testcontainers, Play, Pekko, etc.), check `target/dep-sources/` first — it contains extracted `.scala`/`.java` source files for all dependencies.

```bash
# One-time setup (re-run after adding new dependencies):
sbt extractDepSources

# Then search directly — much faster than web search:
# grep -r "class MariaDBContainer" target/dep-sources/
# grep -r "def withinTxSession" target/dep-sources/
```

Layout: `target/dep-sources/<org>/<artifact-name>-<version>/` — e.g.:
- `target/dep-sources/org.testcontainers/testcontainers-1.x.x/`
- `target/dep-sources/com.dimafeng/testcontainers-scala-core_2.13-0.41.0/`
- `target/dep-sources/org.scalikejdbc/scalikejdbc_2.13-4.3.2/`
- `target/dep-sources/com.typesafe.play/play_2.13-3.0.7/`

If `target/dep-sources/` is empty or missing, run `sbt extractDepSources` once.

## Commands

### Agent efficiency (applies to Codex and Claude)

- Use medium effort for ordinary implementation. Use low effort/a smaller available model for mechanical extraction, log summaries and simple edits when the host allows selection. Reserve high effort for unresolved authorization, migrations, concurrency or architecture decisions; return to the ordinary setting afterward. Instructions cannot change a running model: report the actual setting rather than claiming a switch. Codex defaults are in `.codex/config.toml`; explicit app/session choices may override them.
- Keep the task bounded. Confirm the signed-in account and target contest before investigating a reported permission failure. Do not weaken authorization because another account has the expected role.
- Search with `rg -n` first, then read the relevant range. Do not repeatedly dump whole controllers, plans, dependency catalogs or tool inventories. Parse structured results with scripts; give the model the derived table and anomalies, not raw samples.
- Run checks through `python scripts/agent-check.py -- COMMAND`. It saves full output under `target/checks/`, prints a bounded summary, and preserves the exit code. To inspect an existing log: `python scripts/agent-check.py --summarize PATH --exit-code 1`. Inspect a named failing block only if the summary is insufficient. A successful failure-path test may log `[error]`; use test totals and the process exit code, not that prefix alone.
- Compile changed Scala/tests before a costly run. On Windows use UTF-8 and the direct sbt launcher, since `sbt.bat` can strip quotes from `set ... Seq(...)` commands. Example in PowerShell:
  ```powershell
  python scripts/agent-check.py -- java -Xmx512m -Dfile.encoding=UTF-8 -jar 'C:/Program Files (x86)/sbt/bin/sbt-launch.jar' 'set Test / javaOptions ++= Seq("-Xmx768m", "-Dfile.encoding=UTF-8")' 'Test/compile' 'testOnly controllers.ContestFilesPageSpec'
  ```
- Start with affected specs, including negative authorization cases for permission changes. Run the full suite once at the release boundary when required. If it fails, compile and rerun the affected specs after fixes before considering another full run. Repeat broad tests only for a shared-code change, unresolved risk, or an explicit clean-release gate. Do not run tests for documentation-only changes or tests that merely assert CSS class names.
- Benchmark progressively: reuse relevant measurements, run a small paired pilot on the actual application path, then expand only if the result could change the decision. State absolute response deltas and run variation separately; do not infer user impact from query percentages alone. Estimate runtime before launching a large matrix; avoid overlapping DB benchmarks and tests. Disable per-query SQL debug logging for performance runs and record that choice consistently for both variants. Stop when the decision is supported, retaining outliers and limitations.
- While a process runs, use the tool's bounded wait (up to 60 seconds) rather than alternating sleep, clock, tail and status calls. Keep required user updates short. Do not reopen unchanged source, reanalyse the plan, or print raw logs on each wait. Resume investigation only on completion, failure, or a meaningful progress change.
- Use an independent reviewer only when requested or required. Give it the final diff, relevant invariants and concise validation results. One review plus one follow-up for material fixes is normally enough; do not re-review each assertion, import or helper qualification. Do not rerun tests in the reviewer unless a specific uncertainty requires it.
- Preserve full logs and machine-readable benchmark results. Reduce what enters model context, not evidence or required safety/release checks. Do not change sandbox, approval or deployment safeguards to save usage.

### Running the application
```bash
sbt run          # Start with auto-reloading on HTTP requests
sbt ~run         # Start with triggered background recompilation
```

Requires environment variables: `WLXJURY_DB_HOST`, `WLXJURY_DB`, `WLXJURY_DB_USER`, `WLXJURY_DB_PASSWORD`

### Testing
```bash
sbt test:compile                                      # Compile all tests
sbt test                                              # Run all tests
sbt "testOnly db.scalikejdbc.RoundSpec"               # Run a single test class
sbt "testOnly db.scalikejdbc.*"                       # Run all DB tests
```

Tests use Testcontainers (Docker) to spin up a MariaDB 10.6 instance automatically. No manual DB setup needed for tests. Test configuration is at `test/resources/application.conf`.

### Performance tests (Gatling)
```bash
sbt gatlingAll                                                  # All simulations, RoundDistribution first
sbt "Gatling/testOnly gatling.simulations.VotingSimulation"     # One simulation
sbt -Dgatling.roundMgmt.stress=true "Gatling/testOnly gatling.simulations.RoundManagementSimulation"
```

- Simulations live in `test/gatling/simulations/`, settings in `test/resources/gatling-perf.conf` (override any key with `-Dgatling.<key>=...`). Each simulation forks a JVM that runs the app (`TestServer`) and restores the fixture DB: `data/wlm-UA-images-2025.csv` (~38k images, all in the contest's category), 20 jurors, a binary and a rated round.
- The fixture DB is cached as a dump in `data/cache/`, keyed by `GatlingDbCache.cacheKey` (migrations, CSV sizes, fixture parameters, `FixtureVersion`). Bump `FixtureVersion` whenever `GatlingDbSetup.load` changes what it writes.
- Organizer flows (RoundManagement, AggregatedRatings) model a few organizers with think time by default; `gatling.roundMgmt.stress` / `gatling.aggRatings.stress` run the old many-sessions worst case.
- `RoundDistributionSimulation` creates rounds through the round form and logs each step's time (submit to the end of its background job), selection rows and heap after GC to `target/gatling/distribution-memory.txt`.
- `ConcurrentRoundCreationSimulation` has 10 admins create rounds at once (large, small, top-N, "distribute new files") while jurors vote and browse; per-admin submit-to-done times, solo reference times and heap go to `target/gatling/concurrent-distribution.txt`. `gatling.jobs.*` sets the app's job workers and chunk size for a run.
- To measure with production-like JVM settings, prefix e.g. `"set Gatling / javaOptions ++= Seq(\"-Xmx512m\", \"-Xlog:gc*:file=target/gatling/gc-%p.log:time,uptime,level,tags\")"`; `%p` keeps one GC log per forked JVM.
- Results: `target/gatling/<simulation>-<timestamp>/` (`js/stats.json` has per-request statistics).

### Building packages
```bash
sbt packageDebSystemd    # Debian package with systemd
sbt packageRpmSystemd    # RPM package with systemd
sbt packageAll           # All package variants
```

## Architecture

### Tech Stack
- **Play Framework 3.0** (Scala 2.13, Netty server — Pekko HTTP server disabled)
- **ScalikeJDBC 4.3** with ORM (`CRUDMapper`) for database access
- **MariaDB** as the database with **Flyway** migrations (`conf/db/migration/default/`)
- **Tapir 1.11** + Pekko HTTP for the REST API layer
- **pac4j** via play-pac4j for authentication
- **scalawiki** (internal library) for Wikimedia Commons integration

### Layer Structure

```
app/
  org/intracer/wmua/   # Domain model (case classes): Image, Selection, ContestJury,
                       #   Round, User, Comment, CriteriaRate, ImageWithRating
  db/                  # Repository interfaces (ImageRepo, RoundRepo)
  db/scalikejdbc/      # Repository implementations using ScalikeJDBC CRUDMapper
  db/scalikejdbc/rewrite/ # ImageDbNew: newer query builder for image queries
  controllers/         # Play MVC controllers (extend Secured)
  services/            # Business logic (ContestService, RoundService, ImageService, etc.)
  api/                 # Tapir REST endpoints (contests CRUD + Swagger UI at /api/...)
  modules/             # Guice DI: AppModule binds RoundRepo→Round, ImageRepo→ImageJdbc
  views/               # Twirl templates
```

### Key Domain Concepts

- **ContestJury**: A photo contest (e.g., "Wiki Loves Monuments 2024 Ukraine")
- **Round**: A judging round within a contest. Can be binary (select/reject) or rated (1–N stars). Rounds can filter images by region, category, monument, size, media type, and special nomination.
- **Selection**: A juror's vote/rating on an image in a round (`page_id`, `jury_id`, `round_id`, `rate`)
- **User roles**: `root` (super-admin, `users.is_root`, no contest), and per contest `admin`, `organizer`, `jury` in `user_contest` (V61/V62): one account per email, a member of any number of contests with a role in each. The session's `current_contest_id` picks the contest a request runs in (`Secured.inCurrentContest`); users loaded with `User.contestMembers` / `findByContest` / `loadJurors` carry their role in that contest, a plain `findById` has none.

### Background jobs

Long work runs as a job in the generic `job` table (V60), never on a request thread: `services.jobs.JobScheduler` runs one short step (one transaction) of a job at a time on its own pool (`jobs.*` in `application.conf`), taking turns between contests and jobs, with per-kind concurrency limits, a lease (`owner`, `heartbeat_at`) for restart recovery, Cancel and Retry. A `JobHandler` per kind does the steps; only `round_distribution` (`services.distribution.RoundDistributionHandler`, the single fill path for a new round, a resubmit and "Distribute new files") exists so far. A round being created stays hidden from jurors (`rounds.distributing`) until its count check passes. Tests run jobs inline (`jobs.mode = inline` in `test/resources/application.conf`; `services.TestRoundServices` without a Play app).

### Authentication & Authorization

Session-based auth via Play session cookie (username stored as `"username"` key). `Secured` trait (`app/controllers/Secured.scala`) provides `withAuth(permission)` action builder. Role checks use `User.hasAnyRole` and contest membership via `User.isInContest`.

### Database Tests

One MariaDB Testcontainer per JVM, shared across all specs via `SharedTestDb` (lazy singleton). One Play app per JVM via `SharedPlayApp` (reuses the same container). DAOs available as `contestDao`, `imageDao`, `roundDao`, `selectionDao`, `userDao`.

**Isolation strategy — choose based on whether service code uses `autoSession`:**

| Scenario | Approach |
|---|---|
| Pure DAO tests (session explicitly threaded) | `new AutoRollbackDb { }` (specs2) or `dbTest { session => }` (`AutoRollbackMunitDb`) |
| Service/controller tests (`autoSession` internally) | `BeforeAll` + `BeforeEach(SharedTestDb.truncateAll())` |
| Tests needing a running Play app | extend `PlayTestDb`, call `testDbApp { implicit app => }` |
| Tests needing custom Play config | add `implicit val cfg: Map[String, String] = Map(...)` before `testDbApp` call — starts a fresh per-test app |

`autoSession` bypasses any outer AutoRollback transaction and reads only committed data — this is why service-layer tests (`Secured.userFromRequest`, `GalleryService`, `Round.addUsers`) must use truncation not AutoRollback.

**Pool lifecycle:** `running(app)` (Play test helper) stops Play and calls `ConnectionPool.closeAll()`. Any spec that uses `running(app)` must call `SharedTestDb.reregisterDefault()` in a `finally` block afterward.

### ScalikeJDBC Patterns

- **`CRUDMapper`**: DAO classes extend `CRUDMapper[Entity]` and `SQLSyntaxSupport[Entity]`. `create(entity)`, `findById(id)`, `findAll()`, `save(entity)`, `destroy(entity)`.
- **`autoSession`**: Many DAO methods default to `autoSession` — each call opens+closes its own connection. Service-layer code calling these cannot participate in an outer test transaction.
- **`hasManyThrough`**: Lazy val on the companion object (e.g., `Round.usersRef`) — must be accessed once before `round.users` is populated. `SharedTestDb.initialized` does this automatically.
- **Source:** GitHub: https://github.com/scalikejdbc/scalikejdbc — local jars: `~/.ivy2/cache/org.scalikejdbc/scalikejdbc_2.13/srcs/`

### REST API

Tapir endpoints are defined in `app/api/Api.scala` and routed through Pekko HTTP (separate from Play routing). Swagger UI is auto-generated at `/api/docs`. Play routes are in `conf/routes`.

### Wikimedia Commons Integration

`scalawiki-core` and `scalawiki-wlx` (resolved from `Resolver.mavenLocal` or bintray) provide the Commons API client (`MwBot`). Credentials are optional (`commons.user` / `commons.password` env vars) but improve API rate limits.
