# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

WLX Jury Tool is a Play Framework (Scala) web application for image selection and rating in Wiki Loves Monuments and Wiki Loves Earth photo contests. Jurors browse images, rate/select them across configurable rounds, and organizers manage contests and view aggregated results. Images are fetched from Wikimedia Commons.

## Security

Before changing authentication, sessions, roles or accounts, SQL, views or JavaScript, config or deployment, exports or logging, read `docs/security/secure-coding.md` (one rule per past issue class, with its example and guard). Reviewers (and agents acting as reviewers) apply `docs/security/review-checklist.md`; `docs/security/register.md` is the one live status table of security items, updated in the same commit as a fix. Every such batch runs `testOnly security.*` (the guard specs in `test/security/`; `SignedOutSpec` needs Docker). A guard hit, also one after a merge, is fixed or allowlisted for that one file (or action) with a reason, never silenced by widening a pattern.

Before changing SQL, schema, migrations, transactions or locking, read `docs/db/practices.md` (query safety, keys, indexes, benchmarks, transactions, READ COMMITTED, lock order and retry, migrations, tests; with the guards that enforce each rule and where the code doesn't follow one yet); reviewers apply `docs/db/review-checklist.md`.

Before a load test, benchmark, performance change or memory sizing, read `docs/performance/practices.md` (Gatling, paired benchmarks, caching and jobs, heap and pool budgets); review with `docs/performance/review-checklist.md`.

Before writing or running tests, read `docs/testing/practices.md`; review with `docs/testing/review-checklist.md`.

Before branching, committing, opening a stack PR or releasing, read `docs/workflow/practices.md`.

Before changing log lines, readiness, backups or the deploy, read `docs/operations/practices.md`.

Before changing calls to Wikimedia, the scalawiki pin or the OAuth providers, read `docs/integrations/practices.md`.

Before changing templates, scripts, messages or themes, read `docs/ui/practices.md`.

Before adding or updating a dependency, action, image or downloaded tool, read `docs/dependencies/practices.md`.

Which doc to update when, and how to write docs: `docs/README.md`.

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

- Choose the model and effort for the task, not by defaulting to the most capable option. Use a lower-cost near-peer for routine implementation and independent reviews when it meets the need: GPT-6.1 Sol is described as near GPT-6 Astra on coding, computer use and professional work at one-fifth of Astra's standard input/output token prices; Claude Opus 5.5 is described as performing at Claude Fable 5.1 level on most work, with 60% lower standard input/output token prices ($4/$20 vs. $10/$50 per million tokens). Check current prices and task-specific benchmark gaps before extended or high-volume use; token prices do not necessarily map directly to a product's subscription quota. Escalate to the more expensive model when a relevant capability gap, task difficulty or a failed first attempt justifies it. Use medium effort for ordinary implementation and low effort/a smaller available model for mechanical extraction, log summaries and simple edits when the host allows selection. Reserve high effort for unresolved authorization, migrations, concurrency or architecture decisions; high effort does not by itself require the most expensive model. Instructions cannot change a running model: report the actual setting rather than claiming a switch. Codex defaults are in `.codex/config.toml`; explicit app/session choices may override them. Sources: [GPT-6.1 Sol](https://openai.com/index/introducing-gpt-6-1-sol/) and [Claude Opus 5.5](https://www.anthropic.com/claude-opus-5-5).
- Keep the task bounded. Confirm the signed-in account and target contest before investigating a reported permission failure. Do not weaken authorization because another account has the expected role.
- Search with `rg -n` first, then read the relevant range. Do not repeatedly dump whole controllers, plans, dependency catalogs or tool inventories. Parse structured results with scripts; give the model the derived table and anomalies, not raw samples.
- Run checks through `python scripts/agent-check.py -- COMMAND`. It saves full output under `target/checks/`, prints a bounded summary, and preserves the exit code. To inspect an existing log: `python scripts/agent-check.py --summarize PATH --exit-code 1`. Inspect a named failing block only if the summary is insufficient. A successful failure-path test may log `[error]`; use test totals and the process exit code, not that prefix alone.
- Every run has a time limit: `--timeout SECONDS`, default 1200 (20 min) for targeted runs; pass `--timeout 1800` for the full suite (about 10.5 min, plus 3–8 for a cold compile); `--timeout 0` means none (the CI workflows pass it and rely on each job's `timeout-minutes`). When it runs out the script stops the whole process tree, keeps the log, prints `TIMED OUT after N s` and exits 124. On Windows the command runs in a Job Object that kills its tree (the sbt launcher JVM, its forked test JVM) on a timeout and also when the script itself is killed; `taskkill /T /F` only if the job can't be made (the exit line says `tree=job-object` or `tree=taskkill`). On a normal exit, closing the job kills anything the command left running (an `sbt --client` server, daemons): don't use the script for commands that start daemons. On Linux SIGTERM, SIGHUP or SIGINT to the script is forwarded to the command's process group. Treat 124 as a stalled run (a hung spec, or the host slept), not a test failure: read the log's last lines before rerunning. `time.monotonic()` here is `GetTickCount64()` (Python 3.8), which per Microsoft's docs keeps counting in sleep, so a run that slept past the limit is stopped once the host wakes; Linux's `CLOCK_MONOTONIC` doesn't count a suspend.
- The Bash tool's foreground limit is 600 s (10 min): run a check expected to take longer (a cold compile, the full suite, a Gatling or stress run) with `run_in_background`, inside `agent-check.py --timeout …`, and wait for its completion notice instead of a foreground call that the tool would cut off.
- On Windows the script keeps the host from idle sleep while the command runs (`SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)` from the waiting thread, released at the end, a timeout or an interrupt), so the battery's idle sleep after a power cut doesn't stop an unattended run; `--allow-sleep` opts out. The display may still sleep, and it doesn't stop lid or power-button actions or the critical-battery hibernate: the stress harness's INVALID wall-time check and the timeout cover a run they still interrupt. Agents never change the machine's power settings.
- The shared sbt lock `/c/scala/.sbt-jvm.lock` (one sbt or test JVM at a time, [workflow practices](docs/workflow/practices.md) §4.2): prefer `agent-check.py --lock`, which takes it, writes its `owner` file (who, PID, host, start time, timeout, command) and removes it at exit, a timeout included. A held lock is waited for (`--lock-wait`, default 3600 s). Reported, not waited for (exit 75), and never removed by the script or an agent: a lock whose `owner` names a PID on this host that no longer runs (`LOCK OWNER GONE`: its run was killed), and one older than max(1 h, its timeout + 5 min) (`STALE LOCK`). Surface either to the user, who decides. Name yourself with `--owner` or `AGENT_CHECK_OWNER`. A lock taken by hand gets the same `owner` file (§4.2).
- Compile changed Scala/tests before a costly run. On Windows use UTF-8 and the direct sbt launcher, since `sbt.bat` can strip quotes from `set ... Seq(...)` commands. Run it from Git Bash (the Bash tool): Windows PowerShell 5.1 splits `-Dfile.encoding=UTF-8` at the dot and drops the inner double quotes of the `set` argument when passing them to a native program.
  ```bash
  python scripts/agent-check.py --lock -- java -Xmx512m -Dfile.encoding=UTF-8 -jar 'C:/Program Files (x86)/sbt/bin/sbt-launch.jar' 'set Test / javaOptions ++= Seq("-Xmx768m", "-Dfile.encoding=UTF-8")' 'Test/compile' 'testOnly controllers.ContestFilesPageSpec'
  # the full suite, with the longer limit
  python scripts/agent-check.py --lock --timeout 1800 -- java -Xmx512m -Dfile.encoding=UTF-8 -jar 'C:/Program Files (x86)/sbt/bin/sbt-launch.jar' 'set Test / javaOptions ++= Seq("-Xmx768m", "-Dfile.encoding=UTF-8")' 'test'
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

Pending migrations: `sbt -Ddb.default.migration.auto=true run` applies them at start; without it the app refuses to start while one is pending (`db.Migrations`, no flyway-play apply page).

### Testing
```bash
sbt test:compile                                      # Compile all tests
sbt test                                              # Run all tests
sbt "testOnly db.scalikejdbc.RoundSpec"               # Run a single test class
sbt "testOnly db.scalikejdbc.*"                       # Run all DB tests
```

Tests use Testcontainers (Docker) to spin up a MariaDB 10.6 instance automatically (`SharedTestDb.Image`; `-Dwlxjury.test.mariadb-image=mariadb:10.11.14` runs them, and the Gatling fixture, on another server version). No manual DB setup needed for tests. Test configuration is at `test/resources/application.conf`.

**Tests are anonymous to Wikimedia; live, high-load tests are opt-in.**
- **No account in tests:** the test config sets `commons.user` and `commons.password` to `""`, with no `${?COMMONS_USER}` substitution.
  - `CommonsLogin.credentials` gives no account in Mode.Test unless `commons.allowTestLogin` is true. No spec or CI job sets it.
    - `build.sbt` doesn't forward it from sbt's command line.
    - A deliberate manual run with an account needs all three in the forked JVM: `set Test / javaOptions ++= Seq("-Dcommons.allowTestLogin=true", "-Dcommons.user=…", "-Dcommons.password=…")`.
  - **scalawiki's environment login:** `MwBot.fromHost` / `fromSite` default to logging a new bot in as `SCALAWIKI_LOGIN`, and `MwBot.cache` keeps one bot per domain.
    - wlxjury creates its bots through `services.imports.AnonymousBots` (`loginInfo = None`).
    - `AppModule` seeds the anonymous commons and uk.wikipedia bots at startup, so the default calls inside scalawiki (`SpecialNomination.getNominationMonuments`, `CampaignList`) get them. `SpecialNominationFilter` and `ContestController.fetchContests` seed before those calls too.
    - **Not covered:** a scalawiki default call for another domain, in a JVM where neither the app nor `AnonymousBots` created that domain's bot first. wlxjury has none.
  - **Guards:**
    - `TestConfigAnonymousSpec`: the test config and the workflows/actions pass no `COMMONS_*` / `SCALAWIKI_*` credentials and no secrets but `GITHUB_TOKEN`.
    - `AnonymousBotsSpec`: the app seeds the bots, and `app/` creates no bot or `MonumentQuery` with scalawiki's default login.
  - Why: a machine with the bot's credentials in its environment logged the bot in from every test JVM, so a changed bot password became a stream of failed logins on the bot's account.
- **Specs that read live Wikimedia** run only with a flag, and anonymously:
  - `-Dintegration=true`: `AppendImagesSpec` "get international images" (one winners page, 350+ files), `ImagesFromListSpec` (262 listed files) and `LocalImageCacheServiceIntegrationSpec` (5 downloads).
  - `-Ddocker.tests=true`: `LocalImageCacheServiceDockerSpec` (50 files from a category, and their downloads).
  - Nothing reaches live Wikimedia by default. CI runs the `-Dintegration=true` group weekly only (`.github/workflows/integration.yml`).
- **Running sbt or Docker by hand on a machine that has bot credentials:** prefix the command with `env -u COMMONS_USER -u COMMONS_PASSWORD -u SCALAWIKI_LOGIN -u SCALAWIKI_PASSWORD`.


### Performance tests (Gatling)
```bash
sbt gatlingAll                                                  # All simulations, RoundDistribution first
sbt "Gatling/testOnly gatling.simulations.VotingSimulation"     # One simulation
sbt -Dgatling.roundMgmt.stress=true "Gatling/testOnly gatling.simulations.RoundManagementSimulation"
```

- Simulations live in `test/gatling/simulations/`, settings in `test/resources/gatling-perf.conf` (override any key with `-Dgatling.<key>=...`). Each simulation forks a JVM that runs the app (`TestServer`) and restores the fixture DB: `data/wlm-UA-images-2025.csv` (~38k images, all in the contest's category), 20 jurors, a binary and a rated round.
- The fixture DB is cached as a dump in `data/cache/`, keyed by `GatlingDbCache.cacheKey` (migrations, CSV sizes, fixture parameters, `FixtureVersion`). Bump `FixtureVersion` whenever `GatlingDbSetup.load` changes what it writes.
- Organizer flows (RoundManagement, AggregatedRatings) model a few organizers with think time by default; `gatling.roundMgmt.stress` / `gatling.aggRatings.stress` run the old many-sessions worst case.
- `RoundDistributionSimulation` creates rounds through the round form and logs each step's time (submit to the end of its background job), vote rows and heap after GC to `target/gatling/distribution-memory.txt`.
- `ConcurrentRoundCreationSimulation` has 10 admins create rounds at once (large, small, top-N, "distribute new files") while jurors vote and browse; per-admin submit-to-done times, solo reference times and heap go to `target/gatling/concurrent-distribution.txt`. `gatling.jobs.*` sets the app's job workers and chunk size for a run.
- To measure with production-like JVM settings, prefix e.g. `"set Gatling / javaOptions ++= Seq(\"-Xmx512m\", \"-Xlog:gc*:file=target/gatling/gc-%p.log:time,uptime,level,tags\")"`; `%p` keeps one GC log per forked JVM.
- Results: `target/gatling/<simulation>-<timestamp>/` (`js/stats.json` has per-request statistics).
- Never run Gatling, JMH or other benchmarks in an sbt session where `coverage` is on: its classes are instrumented (scoverage) and measure slower. Start a new session, or run `coverageOff`, first.
- To use the fixture by hand or for UI screenshots: `Test/runMain gatling.setup.UiShotsServer` (it sets the images' Commons URLs in its own container, writes `target/ui-shots/server.json`, stops on `target/ui-shots/server.stop`); command and logins in `docs/performance/practices.md` §1.7.

### Building the release
```bash
sbt dist    # The release ZIP, target/universal/wlxjury-<version>.zip (Universal / packageBin)
```

The ZIP is the only release artifact (no Debian or RPM packages); `wlxjury-release` deploys it (`docs/deployment.md`).

## Architecture

### Tech Stack
- **Java 17** baseline: `-release:17` / `--release 17`, CI and production on JDK 17; no Java 11 support (`docs/dependencies/practices.md` §1.2a)
- **Play Framework 3.0** (Scala 2.13, Netty server — Pekko HTTP server disabled)
- **ScalikeJDBC 4.3** with ORM (`CRUDMapper`) for database access
- **MariaDB** as the database with **Flyway** 13 (Community) migrations (`conf/db/migration/default/`, V files only: no `B` baseline script), run by `db.Migrations` (the app at start, `SharedTestDb`, migration specs)
- **Tapir 1.11** for the REST API, served under `/api` by Tapir's Play interpreter (see "REST API")
- **Authentication** is the app's own: session cookie and `Secured` (no pac4j)
- **scalawiki** (internal library) for Wikimedia Commons integration

### Layer Structure

```
app/
  org/intracer/wmua/   # Domain model (case classes): Image, Vote, ContestJury,
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

### Conventions

- **Layers:** a controller authorizes through a `Secured` builder, binds the form, calls a service and renders; business rules and transactions are in `services/`; SQL is in the DAOs under `db/scalikejdbc/` (raw SQL text only in the files `security.RawSqlSpec` lists); templates get ready values and run no queries (not yet so in `editUser`, `logged`, `monuments` and `navbar` (`User.findRoundJurorsToView`), which call DAOs).
- **Long work is a `JobHandler`** (`services.jobs`): `kind`, `step(job, ctx)` doing one bounded chunk in the transaction of its `StepContext` (the job's row locked) and returning `Continue`/`Done`/`Failed(JobError, …)` with the state to resume from; `prepare` (outside any transaction) or a `FetchingJobHandler` for external calls; `onFailed`/`onCancelled`/`onRetry` undo and redo in the same transaction as the status change; cache invalidation via `ctx.afterCommit`. Register it in `modules.JobSchedulerProvider`.
- **Errors are message keys:** user-visible failures carry a key and arguments (`JobError(key, args, reference)`, `views.html.errorPage`, JSON `{error, reference}`), never exception or request text; the text comes from `conf/messages.*` (all four files).
- **Config:** defaults are in `conf/application.conf`, the tests' in `test/resources/application.conf`; production overrides go in the server's `production.conf` (secrets as `${?VAR}`) and are listed in the release notes; a setting whose silent misspelling would be dangerous is validated at start, as `db.Migrations` does: an unknown `db.<name>.migration.*` key fails the start instead of being ignored.

### Key Domain Concepts

- **ContestJury**: A photo contest (e.g., "Wiki Loves Monuments 2024 Ukraine")
- **Round**: A judging round within a contest. Can be binary (select/reject) or rated (1–N stars). Rounds can filter images by region, category, monument, size, media type, and special nomination, and leave out the files of other rounds of the contest (`round.exclude_rounds`, V95, applied in every distribution query, not the pasted list).
- **Vote** (`org.intracer.wmua.Vote`, DAO `VoteJdbc`): A juror's vote/rating on an image in a round, a row of table `vote` (`page_id`, `jury_id`, `round_id`, `rate`; V82 renamed it from `selection`). Votes of a file deleted on Commons are moved aside to `vote_aside` with their ids; `vote_orphan` keeps the votes V58 found without an image; a criteria round's per-criterion rates are `criteria_rate` rows (`vote_id`).
- **Round images** (`round_image`, V84; DAO `RoundImageJdbc`): one row per round and file, status `in` (it has `vote` rows), `deleted` (votes only in `vote_aside`) or `dq` (P1b). Every writer of `vote`/`vote_aside` keeps it in the same transaction, last in the lock order (`round → images → vote → criteria_rate → vote_aside → round_image`): a vote insert adds the file (`addVoted`), a bulk write re-syncs the pairs it touched (`sync`). "Which images" reads (counts, the count check, regions of the whole round, CoI files, deleted-file counts, open rounds of a file, previous-round windows without vote filters) read it; `RoundImageInvariants.violations(roundId)` checks it (specs assert it empty). A spec's raw `INSERT INTO vote` must add the row too (or call `RoundImageJdbc.sync`). Plan: `docs/plans/2026-11-02-round-images-two-step.md`.
- **User roles**: `root` (super-admin, `users.is_root`, no contest; set only by root through `User.setRoot` / account creation, never read from `user_contest`), and per contest exactly one of `User.ContestRoles` (= `FORM_ROLES`: `no`, `jury`, `organizer`, `admin`, `board`, `auditor`, `staff`) in `user_contest.role` (V61/V62): one account per email, a member of any number of contests with a role in each. A role is checked exactly (no splitting, trimming or case folding) by the edit form, `UserController.saveUser` (`User.mayAssignRoles`), `UserService.addToContest`, the DAO (`createMembership` / `updateRole` / `User.create` `require` it) and V76's binary `CHECK` constraint; the reader (`UserContest.roles`) ignores any other stored value (the membership is then inactive). Adding a role needs a V-migration replacing the constraint. The session's `current_contest_id` picks the contest a request runs in (`Secured.inCurrentContest`); users loaded with `User.contestMembers` / `findByContest` / `loadJurors` carry their role in that contest, a plain `findById` has none.

### Background jobs

Long work runs as a job in the generic `job` table (V60), never on a request thread: `services.jobs.JobScheduler` runs one short step (one transaction) of a job at a time on its own pool (`jobs.*` in `application.conf`), taking turns between contests and jobs, with per-kind concurrency limits, a lease (`owner`, `heartbeat_at`) for restart recovery, Cancel and Retry. A `JobHandler` per kind does the steps: `round_distribution` (`services.distribution.RoundDistributionHandler`, the single fill path for a new round, a resubmit and "Distribute new files"), `round_image_check` (`RoundImageCheckHandler`: repairs `round_image` against the votes, a round's by its admins or every round's and the criteria rates of no vote by root), and the import jobs. A round being created stays hidden from jurors (`round.distributing`) until its count check passes. Tests run jobs inline (`jobs.mode = inline` in `test/resources/application.conf`; `services.TestRoundServices` without a Play app).

### Authentication & Authorization

Session-based auth via Play session cookie (username stored as `"username"` key, the account's id as `"account_id"` and its `auth_version` as `"auth_version"`; a session from before S2 has neither and resolves only to an account of version 0, which no account created or re-addressed since has). `Secured` (`app/controllers/Secured.scala`) provides the action builders: `withAuth(permission)` for pages, `withAuthOn(ec)(permission)` for slow blocking JDBC work (runs on that pool), `withAuthJson(permission)` for endpoints called by script. Role checks use `User.hasAnyRole` / `hasContestRole` and contest membership via `User.isInContest`.

- **Denials** go through `onUnAuthorized` (403 page) or `onUnAuthorizedJson` (`{error: "forbidden", reference}`) with a `Secured.Denial` (contest, resource, required roles, reason). Each is logged at WARN on `security.authz` (`Secured.denialLogLine`, key-value, untrusted text quoted) with the reference the user sees. The line identifies the account by `accountId` only, never its email or name (support looks the account up by id); other log lines about a signed-in user do the same.
- **Passwords:** bcrypt `$2b$` via `services.PasswordHasher` (`bcrypt.*` in `application.conf`: cost 12, never below 10 outside the tests' cost 4; at most `maxConcurrent` hash computations at once, a sign-in waits up to `maxWait`, then 503; more than `maxQueued` sign-ins and sign-ups in flight get 503 at once). Set one only with `User.setPassword`, or `PasswordHasher.default.hash` then `User.setPasswordHash` when a form must hash before writing anything (trimmed, at most 72 bytes: `PasswordHasher.newPassword` on forms); `User.login` accepts a legacy SHA-1 hash once and replaces it. Sign-in and sign-up run on `SignInExecutionContext`. Never log a password or hash: a statement that binds one runs through `User.quietly` (ScalikeJDBC's statement and error logging off).
- **Sessions (S3):** a sign-in stores `issued_at` and `last_seen` (epoch seconds) and, if "Keep me signed in" (the form's `remember`, checked by default) was checked, `keep` = 1. `controllers.SessionLifetime` (`wlxjury.session.absoluteDays` 365, `idleDays` 30, `touchMinutes` 5) refuses a session past either limit in `Secured.sessionUserOf`; `modules.SessionLifetimeFilter` (first in `play.filters.enabled`) takes an expired session's sign-in keys out of the request and the browser, upgrades a cookie from before S3 in place (stamps the account id, version and both times as now if it still resolves by S2's rules; else signs it out) only within V80's `session_upgrade_window` (30 days from the S3 deploy; after it such a cookie is signed out), refreshes `last_seen` at most every `touchMinutes` (an upgrade or refresh is written only by a page request or a POST), and writes a kept session's cookie with Max-Age (Play's `session.maxAge` stays unset: otherwise a browser-session cookie); never on a response a shared cache may keep. Through the router a kept session is in `cookies(result)`, not `session(result)`. `users.auth_version` ends every session of an account: `User.endSessions` ("Sign out everywhere", `POST /logout/everywhere`; root's "End sessions", `POST /users/:id/sessions/end`), `User.setPasswordHash` (one's own change keeps that browser with the new version), accepted links and email changes. API tokens survive one's own password change; `POST /users/tokens/revoke-all` revokes them. Rotating `PLAY_HTTP_SECRET_KEY` ends every session (`docs/deployment.md`).
- **Signed out:** a script request (`X-Requested-With` or a non-`navigate` `Sec-Fetch-Mode`) gets JSON 401; a page GET goes to the login page keeping `returnTo`. Every `returnTo` passes `Secured.safeLocalPath` (local paths only).
- **Contest switching:** only page GETs switch the session's contest (`Secured.isPageRequest`: a GET that is neither a script request, `Sec-Fetch-Mode` included, nor a prefetch). A check against a resource's contest (round, job, comment) runs as the user in that contest via `Secured.inContest` (active memberships only), never by switching on a POST or poll. Root needs no membership.
- **Own votes:** a round's galleries and large view use `GalleryService.viewerRounds` / `mayViewRound` / `viewAs`: a juror sees only their own votes; others' (or `asUserId == 0`, all jurors) only for `User.canViewOrgInfo`, and only of a juror of the round (`User.isRoundJuror`; otherwise 404). The galleries' juror menu lists the same set (`User.findRoundJurorsToView`).
- **CSRF:** votes, sign-out (`POST /logout`, `POST /logout/everywhere`) and contest switching (`POST /switch-contest/:id`) are POSTs with the CSRF token: a form field in the large view and forms (`csrfField` renders it only if the request has a token), the `Csrf-Token` header from `data-csrf-token` for gallery votes. A refused token gets `CsrfErrorHandler` (a page saying to reload, or JSON `{error: "csrf", reference}`). Every POST is checked, cookies or not (`play.filters.csrf.header.protectHeaders = null`: no login CSRF from a cookieless browser). Tests run with the CSRF filter off (`test/resources/application.conf`); `controllers.CsrfTestApp.withCsrfApp` re-enables it for router tests.
- **Accounts (S2):** no sign-up. An organizer's add creates an account without a password and mails an invitation (`services.AccountTokenService`, table `account_token`, V77: 32 random bytes, only the SHA-256 stored, single use, expiring, bound to the account and the address it was sent to); accepting it (`/invitation/:token`, POST with CSRF) sets the password, sets `users.email_verified_at`, bumps `users.auth_version` (a session carries `auth_version`, absent = 0; `Secured.sessionUser` refuses another), revokes the account's API tokens and applies pending invitations. An access role (jury, organizer, admin) goes to an existing account directly only if `User.mayTakeAccessRole` (root, verified email, or no password); otherwise an add by email becomes a pending invitation, an add by an (unverified) wiki account is refused, and an edit-form role change becomes an invitation (O2). One's own password or email needs the current password; one's own new email takes effect via a link mailed to it. An admin who may edit the account (`UserController.canEditAccount`), or root, changes another account's email at once, password or not (`UserService.changeEmailByAdmin`: unverified, password kept, sessions ended, identities unlinked, a notice to a verified former address). Invitations on an add, an import or an edit are mailed only if "Send an invitation email" is ticked (unticked by default; an O2 invitation is then kept `account_token.unsent`). Every change an admin or root makes to another person's account is in the account change log (`account_change`, V96; `services.AccountChangeLog`, in the change's transaction where there is one), read only by the contest's admins and root (`AccountChangeController`, the edit page). Root's "reset password" mails a link. Every refused link shows the same 404 page (N7). The first root of an empty DB comes from `/setup` with `wlxjury.setup.token` (P20). `security.account` log lines carry ids only, never emails or tokens (paths with a token are masked, `Secured.logPath`). Mailed links use the request's host only if it is named exactly in `wlxjury.allowedHosts`, else `wlxjury.host` (`services.LinkHost`). Tests: `play.mailer.mock = yes`; specs read links from `controllers.RecordingMail`.
- **Google/Wikimedia sign-in** (`controllers.OAuthController`, `services.oauth`, table `user_identity` V87; docs/oauth.md): off unless `oauth.*` lists a provider's credentials and hosts. It signs in only to an existing account by S2's rules (`IdentityLinker`: a first sign-in links by a verified email or an invitee's, or a verified wiki account; never creates one) and makes the same S3 session as the password (`LoginResults.signedIn`). Nimbus OAuth SDK, no pac4j. Specs use fake providers (`OAuthControllerSpec`, `OAuthRouterSpec`); `ProvidersSpec` runs the real ones against a local fake server.
- **Security headers:** `modules.AppSecurityHeadersFilter` (Play's headers plus `Permissions-Policy`; keeps a page's own `Referrer-Policy: no-referrer`) and Play's `CSPFilter` (`play.filters.csp.directives`) run first in `play.filters.enabled`, then `AllowedHostsFilter`, sessions, CSRF; `ErrorHandler` adds the same headers to what Play's server answers outside the filters (server errors, unparsable requests; `modules.ResponseSecurityHeaders`). The tests' config has no CSPFilter; `security.ProductionFilters` builds an app with the release's filters and policy (`SecurityHeadersSpec`, `LoginCsrfSpec`).
- **Errors:** one error page, `views.html.errorPage` (message keys only, never request or exception text); a missing resource is a 404 with it, never a `.get` on a lookup of a request-supplied id. `ErrorHandler` handles the rest (a server error shows a logged reference).

### Database Tests

One MariaDB Testcontainer per JVM, shared across all specs via `SharedTestDb` (lazy singleton). One Play app per JVM via `SharedPlayApp` (reuses the same container). DAOs available as `contestDao`, `imageDao`, `roundDao`, `selectionDao`, `userDao`.

The test server (`SharedTestDb.forTests`, also `ConvertToInnoDbMigrationSpec`'s databases on it and `SchemaBaselineSpec`'s container; not the Gatling fixture's, which stays production-like) keeps its data directory on tmpfs (1 GB cap) with sized caches (128M buffer pool, 48M redo log, 2000 open tables): `TRUNCATE` re-creates table files, 0.5–1 s per `truncateAll` on disk against 30–40 ms on tmpfs. The data lives in the Docker VM's memory (about 350 MB). Specs that read the live Wikimedia Commons (`AppendImagesSpec` "get international images", `ImagesFromListSpec`, `LocalImageCacheServiceIntegrationSpec`) are skipped unless run with `-Dintegration=true`: `sbt -Dintegration=true "testOnly controllers.AppendImagesSpec controllers.ImagesFromListSpec services.LocalImageCacheServiceIntegrationSpec"` (with the direct launcher on Windows the `-D` goes before `-jar`; `build.sbt` forwards it to the forked JVM).

**Isolation strategy — choose based on whether service code uses `autoSession`:**

| Scenario | Approach |
|---|---|
| Pure DAO tests (session explicitly threaded) | `new AutoRollbackDb { }` (specs2) or `dbTest { session => }` (`AutoRollbackMunitDb`) |
| Service/controller tests (`autoSession` internally) | `BeforeAll` + `BeforeEach(SharedTestDb.truncateAll())` |
| Tests needing a running Play app | extend `PlayTestDb`, call `testDbApp { implicit app => }` |
| Tests needing custom Play config | add `implicit val cfg: Map[String, String] = Map(...)` before `testDbApp` call — starts a fresh per-test app |
| Tests through the release's filters (CSP, allowed hosts, CSRF on) | `ProductionFilters.withSharedApp { app => }` (or `CsrfTestApp.withCsrfApp`): one JVM-wide app, truncated per call, never stopped by a spec (no `running`, no `TestServer`); an example that moves app-level state (the pre-S3 upgrade window), binds modules or serves the app uses `ProductionFilters.withApp` / `withAppWith`; one config shared by a spec's examples: `ProductionFilters.OwnApp`, stopped in `afterAll` |

`autoSession` bypasses any outer AutoRollback transaction and reads only committed data — this is why service-layer tests (`Secured.userFromRequest`, `GalleryService`, `Round.addUsers`) must use truncation not AutoRollback.

**Keyboard voting:** the large view's key handlers vote and move by paths, never `http://` URLs (`Call.absoluteURL` behind the HTTPS proxy redirected the POST to a GET and lost the vote); `views.LargeViewControllerSpec` guards it.

**Pool lifecycle:** a Play app's start makes Play's HikariCP pool ScalikeJDBC's default (`PlayDBApiAdapterModule`, the only module that registers one; `ConnectionPoolConfigSpec`), and `running(app)` (Play test helper) or `app.stop()` closes it. Any spec that uses `running(app)` must call `SharedTestDb.reregisterDefault()` in a `finally` block afterward.

### ScalikeJDBC Patterns

- **`CRUDMapper`**: DAO classes extend `CRUDMapper[Entity]` and `SQLSyntaxSupport[Entity]`. `create(entity)`, `findById(id)`, `findAll()`, `save(entity)`, `destroy(entity)`.
- **`autoSession`**: Many DAO methods default to `autoSession` — each call opens+closes its own connection. Service-layer code calling these cannot participate in an outer test transaction.
- **`hasManyThrough`**: Lazy val on the companion object (e.g., `Round.usersRef`) — must be accessed once before `round.users` is populated. `SharedTestDb.initialized` does this automatically.
- **Source:** GitHub: https://github.com/scalikejdbc/scalikejdbc — local jars: `~/.ivy2/cache/org.scalikejdbc/scalikejdbc_2.13/srcs/`

**Table names:** new tables are singular snake_case (`job`, `round_image`); a link table is `<owner>_<thing>` (`round_user`, `user_contest`); a foreign key column is `<table>_id`; index and constraint names start with the table name. The old plural tables (`images`, `users`, `category_members`, …) keep their names; V82 renamed `rounds` to `round` and `selection` and its copies to `vote`, `vote_aside`, `vote_orphan` (`criteria_rate.selection` to `vote_id`). Scala names (`Round`, …) and URLs/routes are not affected.

### REST API

Tapir endpoints in `app/api/Api.scala`, served by Tapir's Play interpreter (`tapir-play-server`) under `/api` (`-> /api api.ApiRouter` in `conf/routes`), so through Play's filters and on the blocking dispatcher for JDBC. Swagger UI and the OpenAPI document are public at `/api/docs`. See `docs/api.md`.

- **Who calls** (`api.ApiAuth.caller`): an `Authorization` header must be `Bearer <personal token>` and the token usable, else 401 (the session is then ignored, never mixed in); without the header, the session user as `Secured.userFromRequest` gives it. Permissions are the `Secured` ones (`Secured.checked`); the contest endpoints are root-only, as the contest pages.
- **Errors** are JSON (`api.ApiFailure`), also from `ErrorHandler` and `CsrfErrorHandler` for any path under `/api/` (e.g. 413 `payload_too_large` from Play's parser): 401 `unauthenticated` (as `withAuthJson`) with `WWW-Authenticate: Bearer`, 403 `forbidden` with the reference of `Secured.logDenial`'s line (which adds `auth=session` or `auth=token tokenId=…`), 400 `bad_request` (decode failures, never the input), 404 `not_found` (also any other `/api` path), 500 `server_error` with a logged reference.
- **Personal API tokens** (`api_token`, V75; `services.ApiTokenService`, `ApiTokenJdbc`): `wlxj_` + 32 random bytes base64url; only the SHA-256 is stored, the prefix (`wlxj_` + 6) names it in lists and logs. Shown once, in the response that creates it (`no-store`), never flashed, logged or put in a URL. `last_used_at` at most once a minute. Created by root only, on root's own profile (`UserController.createApiToken`, `rolePermission(ROOT_ROLE)`; `revokeApiToken`, CSRF POST forms, session only, at most 20 usable tokens); the create form carries a one-time nonce from the session (`ApiTokensCard.NonceKey`), so reloading the creating response makes no second token; a user revokes only their own. A refused token logs by id or prefix: revoked, expired or of a deleted user at WARN, unknown at INFO, malformed or another scheme at DEBUG (no DB lookup). Tokens are accepted only under `/api/`: the page routes read the session alone.
- **CSRF:** session writes need the `Csrf-Token` header. `modules.CsrfModule` replaces Play's CSRF module (same filter class) so that only a request under `/api/` with a Bearer header and without the session cookie skips the check (`ApiAuth.bearerWithoutSession`). Swagger UI's "Try it out" writes therefore fail in a signed-in browser (by design). Tests: a FakeRequest's cookies are an attribute (`withCookies`); a `Cookie` header alone is not read as cookies.

### Wikimedia Commons Integration

`scalawiki-core` and `scalawiki-wlx` (from `~/.ivy2/local` via scalawiki's `publishLocal`, or `~/.m2`; CI builds the pinned commit from source, see `docs/plans/2026-11-03-ci-and-scalawiki-packages.md`) provide the Commons API client (`MwBot`). The production account is optional (`commons.user` / `commons.password`, from `COMMONS_USER` / `COMMONS_PASSWORD` on the server) but improves API rate limits; tests never use it (Testing).
