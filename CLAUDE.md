# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository. It holds the essentials; the details are in the docs listed under "Where the details are".

## Project

WLX Jury Tool is a Play Framework (Scala) web application for image selection and rating in Wiki Loves Monuments and Wiki Loves Earth photo contests. Jurors browse images, rate/select them across configurable rounds, and organizers manage contests and view aggregated results. Images are fetched from Wikimedia Commons.

## Rules that are never broken

- **Authorization:** every action is built with a `Secured` builder (`withAuth`, `withAuthOn`, `withAuthJson`, `withAuthForm`) and a contest-, round- or role-scoped permission, or is on the public list. A permission change has negative tests: another contest's admin, an organizer, a juror, an inactive membership, signed out. Never weaken authorization because another account has the expected role ([secure-coding](docs/security/secure-coding.md) §9, §10).
- **Contest switching:** only page GETs switch the session's contest (`Secured.isPageRequest`). A check against a resource's contest (round, job, comment) runs as the user in that contest via `Secured.inContest`, never by switching on a POST or poll.
- **Own votes:** a juror sees only their own votes (`GalleryService.viewerRounds` / `mayViewRound` / `viewAs`); others' only for `User.canViewOrgInfo`, and only of a juror of the round (otherwise 404).
- **CSRF:** every state change is a POST with the CSRF token (forms: `@csrfField()`; scripts: the `Csrf-Token` header from `data-csrf-token`); a GET handler changes nothing; no `+ nocsrf` route (secure-coding §11).
- **Errors:** users see message keys and a logged reference (`views.html.errorPage`, JSON `{error, reference}`), never request or exception text. A missing resource is a 404, never a `.get` on a lookup of a request-supplied id (secure-coding §12). Message keys are in all four `conf/messages.*` files.
- **No personal data in logs:** log lines identify accounts by id, never an email, name, password, token or a whole `User` (secure-coding §13).
- **No personal data or local paths in the repository:** files, commit messages and identities carry no names of people, account names, personal addresses, home folders or local paths of a checkout or worktree; write repository-relative paths and `{placeholder}` names. The hooks (`git config core.hooksPath .githooks`) and CI run `scripts/check-private-strings.py`; a hit is fixed, never bypassed ([CONTRIBUTING.md](CONTRIBUTING.md#private-strings)).
- **Security changes:** before changing authentication, sessions, roles or accounts, SQL, views or JavaScript, config or deployment, exports or logging, read [secure-coding.md](docs/security/secure-coding.md); review with [review-checklist.md](docs/security/review-checklist.md); run `testOnly security.*` (`SignedOutSpec` needs Docker). A guard hit, also one after a merge, is fixed or allowlisted for that one file (or action) with a reason, never silenced by widening a pattern. [register.md](docs/security/register.md) is updated in the same commit as a fix.
- **Merges, not rebases:** review fixes are new commits; newer master comes in by `git merge origin/master`. No `--amend`, rebase or force push on a pushed branch; no `--no-verify`. Commit only your own paths (`git commit -- <paths>`), with the message from a file (`git commit -F`) ([workflow](docs/workflow/practices.md) §5, §6).
- **Review before merge:** every change is a PR to `master` with one theme, reviewed (workflow §2.1), and merged by a maintainer with "Squash and merge" once CI is green (§6.2, §6.6). The practices doc a change affects is updated in the same PR (§8.1).
- **Review threads before merge:** every review thread, CodeRabbit's or a person's, gets a reply on the thread (the fixing `<commit>`, or one of workflow §3.1's classes) and is resolved; real points go to the builder. If CodeRabbit posted nothing (rate limit), don't wait for it: our own review stays the gate. The `master` ruleset blocks a merge while a thread is unresolved (workflow §3.4).
- **One sbt or test JVM at a time,** across all worktrees: the shared lock `.sbt-jvm.lock` in the folder that holds the repository and its worktrees, taken by `python scripts/agent-check.py --lock`. A held lock is waited for; a `LOCK OWNER GONE` or `STALE LOCK` is reported to the user, never removed by an agent (workflow §4.2).

## Build, run and test

### Running the application

```bash
sbt run          # Start with auto-reloading on HTTP requests
sbt ~run         # Start with triggered background recompilation
```

Requires environment variables: `WLXJURY_DB_HOST`, `WLXJURY_DB`, `WLXJURY_DB_USER`, `WLXJURY_DB_PASSWORD`. Pending migrations: `sbt -Ddb.default.migration.auto=true run` applies them at start; without it the app refuses to start while one is pending (`db.Migrations`). Database, scalawiki and first-account setup: [DEV.md](DEV.md).

### Testing

```bash
sbt test:compile                                      # Compile all tests
sbt test                                              # Run all tests
sbt "testOnly db.scalikejdbc.RoundSpec"               # Run a single test class
sbt "testOnly db.scalikejdbc.*"                       # Run all DB tests
```

- Tests start MariaDB 10.6 with Testcontainers (Docker); no manual DB setup. Test configuration: `test/resources/application.conf` (jobs run inline, the CSRF filter is off; `controllers.CsrfTestApp.withCsrfApp` re-enables it).
- **Isolation:** pure DAO specs roll back (`AutoRollbackDb`, `dbTest`); anything through a service, `autoSession` or a Play app has the tables truncated before each example (`BeforeEach(SharedTestDb.truncateAll())`), since `autoSession` reads only committed data. A spec that uses `running(app)` calls `SharedTestDb.reregisterDefault()` in a `finally` ([testing](docs/testing/practices.md) §2).
- **Tests are anonymous to Wikimedia:** the test config sets `commons.user` and `commons.password` to `""`, and no spec or workflow passes `COMMONS_*` / `SCALAWIKI_*` credentials. On a machine that has bot credentials, prefix sbt and Docker runs with `env -u COMMONS_USER -u COMMONS_PASSWORD -u SCALAWIKI_LOGIN -u SCALAWIKI_PASSWORD`. Specs that read live Wikimedia run only with `-Dintegration=true` or `-Ddocker.tests=true` (testing §5).
- **What to run:** start with the affected specs, including negative authorization cases for permission changes; the full suite once at the release boundary when required; no tests for documentation-only changes (testing §8.1).

### Running checks as an agent

Run checks through `python scripts/agent-check.py -- COMMAND`: it saves full output under `target/checks/`, prints a bounded summary, preserves the exit code, stops a stalled run after `--timeout` (default 1200 s, exit 124) and takes the sbt lock with `--lock`. Run a check longer than the Bash tool's 600 s foreground limit with `run_in_background`. Details (the lock's `owner` file, time limits, waits, models and effort): [workflow](docs/workflow/practices.md) §4.2 and §9, [testing](docs/testing/practices.md) §8.1a.

Compile changed Scala/tests before a costly run. On Windows use UTF-8 and the direct sbt launcher, since `sbt.bat` can strip quotes from `set ... Seq(...)` commands. Run it from Git Bash (the Bash tool): Windows PowerShell 5.1 splits `-Dfile.encoding=UTF-8` at the dot and drops the inner double quotes of the `set` argument when passing them to a native program.

```bash
python scripts/agent-check.py --lock -- java -Xmx512m -Dfile.encoding=UTF-8 -jar 'C:/Program Files (x86)/sbt/bin/sbt-launch.jar' 'set Test / javaOptions ++= Seq("-Xmx768m", "-Dfile.encoding=UTF-8")' 'Test/compile' 'testOnly controllers.ContestFilesPageSpec'
# the full suite, with the longer limit
python scripts/agent-check.py --lock --timeout 1800 -- java -Xmx512m -Dfile.encoding=UTF-8 -jar 'C:/Program Files (x86)/sbt/bin/sbt-launch.jar' 'set Test / javaOptions ++= Seq("-Xmx768m", "-Dfile.encoding=UTF-8")' 'test'
```

### Changelog entries as an agent

A changelog entry is one short user-facing line per change (a few words, no implementation details); see [CONTRIBUTING.md](CONTRIBUTING.md), "Changelog entries".

### Plans and decisions as an agent

- A plan or design (anything the maintainer will decide on, or with open questions) is committed to `docs/plans/YYYY-MM-DD-<topic>.md` on a branch, with a PR (a draft while undecided), as soon as it is written; decisions are recorded in that file when made. Before starting a planned feature, check its plan is in `docs/plans`; if not, recover it first (from git branches, from the session transcripts the agent tool keeps, e.g. Claude Code's `~/.claude/projects/<project>/*.jsonl`, or ask the maintainer).
- Runbooks, analysis outputs and review reports that must not be public go to the maintainer's durable local work folder outside the repository (ask if it is not known), not a temp directory, and never with a local path written into the repository.
- Session scratchpads and temp directories hold only files that can be regenerated. A plan, hand-off note or PR body never points at a scratchpad path.

### Front-end work as an agent

Work on a template, a script or CSS consults the Modern Web Guidance skill first: `.claude/skills/modern-web-guidance` (Claude Code) and `.agents/skills/modern-web-guidance` (Codex), one pinned copy (CLI `modern-web-guidance@1.0.2`, never `@latest`; telemetry off with `DISABLE_TELEMETRY=1` in `.claude/settings.json` and `.codex/config.toml`). Apply a guide only inside the browser floor or with its fallback, review with [ui/review-checklist.md](docs/ui/review-checklist.md), and end the PR with the skill's "Modern Web Guidance" note. Copied guide text or code is credited with a source link and a NOTICE entry; updating the skill is deliberate (the checklist's "Updating the skill").

### Performance tests (Gatling)

```bash
sbt gatlingAll                                                  # All simulations, RoundDistribution first
sbt "Gatling/testOnly gatling.simulations.VotingSimulation"     # One simulation
```

The simulations, the fixture and its cache, stress flags, JVM settings and results: [performance](docs/performance/practices.md) §1. Never benchmark in an sbt session where `coverage` is on (performance §2.3).

### Building the release

```bash
sbt dist    # The release ZIP, target/universal/wlxjury-<version>.zip (Universal / packageBin)
```

The ZIP is the only release artifact; `wlxjury-release` deploys it ([deployment.md](docs/deployment.md)).

### Looking Up Library Sources

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

ScalikeJDBC on GitHub: https://github.com/scalikejdbc/scalikejdbc — local jars: `~/.ivy2/cache/org.scalikejdbc/scalikejdbc_2.13/srcs/`. scalawiki is read at its pinned commit ([integrations](docs/integrations/practices.md) §2.2).

## Architecture

### Tech stack

- **Java 17** baseline (`-release:17` / `--release 17`; no Java 11 support, [dependencies](docs/dependencies/practices.md) §1.2a)
- **Play Framework 3.0** (Scala 2.13, Netty server — Pekko HTTP server disabled)
- **ScalikeJDBC 4.3** with ORM (`CRUDMapper`) for database access
- **MariaDB** with **Flyway** 13 (Community) migrations (`conf/db/migration/default/`, V files only), run by `db.Migrations` (the app at start, `SharedTestDb`, migration specs)
- **Tapir 1.11** for the REST API, served under `/api` by Tapir's Play interpreter
- **Authentication** is the app's own: session cookie and `Secured` (no pac4j)
- **scalawiki** (internal library) for Wikimedia Commons integration

### Layer structure

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
- **DAOs** extend `CRUDMapper[Entity]` and `SQLSyntaxSupport[Entity]` (`create`, `findById`, `findAll`, `save`, `destroy`). Many methods default to `autoSession`, which opens its own connection: inside a transaction pass the session ([db](docs/db/practices.md) §5.2).
- **Long work is a job,** never on a request thread: a `JobHandler` per kind in the `job` table, run one short transaction per step by `services.jobs.JobScheduler` (db §5.5a, §5.5b).
- **Errors are message keys:** user-visible failures carry a key and arguments (`JobError(key, args, reference)`, `views.html.errorPage`, JSON `{error, reference}`); the text comes from `conf/messages.*` (all four files).
- **Config:** defaults are in `conf/application.conf`, the tests' in `test/resources/application.conf`; production overrides go in the server's `production.conf` (secrets as `${?VAR}`) and are listed in the release notes; a setting whose silent misspelling would be dangerous is validated at start, as `db.Migrations` does: an unknown `db.<name>.migration.*` key fails the start instead of being ignored.
- **Table names:** new tables are singular snake_case (`job`, `round_image`); old plural tables keep their names (db §2.1).

### Key domain concepts

- **ContestJury**: A photo contest (e.g., "Wiki Loves Monuments 2024 Ukraine")
- **Round**: A judging round within a contest. Can be binary (select/reject) or rated (1–N stars). Rounds can filter images by region, category, monument, size, media type, and special nomination, and leave out the files of other rounds of the contest (`round.exclude_rounds`, V95, applied in every distribution query, not the pasted list).
- **Vote** (`org.intracer.wmua.Vote`, DAO `VoteJdbc`): A juror's vote/rating on an image in a round, a row of table `vote` (`page_id`, `jury_id`, `round_id`, `rate`). Votes of a file deleted on Commons are moved aside to `vote_aside` with their ids; `vote_orphan` keeps the votes V58 found without an image; a criteria round's per-criterion rates are `criteria_rate` rows (`vote_id`).
- **Round images** (`round_image`, V84; DAO `RoundImageJdbc`): one row per round and file, kept by every writer of `vote`/`vote_aside` in the same transaction; "which images are in the round" reads it (db §3.7, lock order §7.1).
- **User roles**: `root` (super-admin, `users.is_root`, no contest, never read from `user_contest`), and per contest exactly one of `User.ContestRoles` (`no`, `jury`, `organizer`, `admin`, `board`, `auditor`, `staff`) in `user_contest.role`: one account per email, a member of any number of contests with a role in each. Roles are checked exactly; adding one needs a migration (secure-coding §10). The session's `current_contest_id` picks the contest a request runs in (`Secured.inCurrentContest`); users loaded with `User.contestMembers` / `findByContest` / `loadJurors` carry their role in that contest, a plain `findById` has none.

### Authentication and authorization

Session-based auth via the Play session cookie (the user name, the account's id and its `auth_version`). `Secured` (`app/controllers/Secured.scala`) provides the action builders: `withAuth(permission)` for pages, `withAuthOn(ec)(permission)` for slow blocking JDBC work, `withAuthJson(permission)` for endpoints called by script, `withAuthForm(permission)` for a form a script may also send. Denials go through `onUnAuthorized` / `onUnAuthorizedJson` and are logged on `security.authz` with the reference the user sees. Details: secure-coding §3 (passwords), §6 (security headers), §9 (authorization), §11 (CSRF), §12 (errors), §14 (accounts and invitations), §15 (sessions); Google and Wikimedia sign-in: [oauth.md](docs/oauth.md).

### REST API and Commons

- **REST API:** Tapir endpoints in `app/api/Api.scala`, served under `/api` by `api.ApiRouter` through Play's filters (allowed hosts included); Swagger UI at `/api/docs`. `api.ApiAuth` takes a personal Bearer token (root creates them; a read-only, server-only scope exists) or the session, with the `Secured` permissions; a session's write needs the CSRF header, a Bearer request without a session cookie skips CSRF ([api.md](docs/api.md), secure-coding §9 and §11).
- **Wikimedia Commons:** `scalawiki-core` and `scalawiki-wlx`, published locally from the commit in `project/scalawiki.sha`, provide the Commons API client (`MwBot`); bots are anonymous except production's optional bot password ([integrations](docs/integrations/practices.md) §1–§2).

## Where the details are

Read the practices doc before the change it covers; reviewers apply its checklist.

| Before | Read | Review with |
|---|---|---|
| auth, sessions, roles or accounts, SQL, views or JavaScript, config or deployment, exports or logging | [security/secure-coding.md](docs/security/secure-coding.md) | [security/review-checklist.md](docs/security/review-checklist.md); status: [register.md](docs/security/register.md) |
| SQL, schema, migrations, transactions or locking; jobs | [db/practices.md](docs/db/practices.md) | [db/review-checklist.md](docs/db/review-checklist.md) |
| a load test, benchmark, performance change or memory sizing | [performance/practices.md](docs/performance/practices.md) | [performance/review-checklist.md](docs/performance/review-checklist.md) |
| writing or running tests (isolation, flags, CI, browser tests) | [testing/practices.md](docs/testing/practices.md) | [testing/review-checklist.md](docs/testing/review-checklist.md) |
| branching, committing, a PR or a release; agent runs (models, `agent-check.py`, the lock, waits) | [workflow/practices.md](docs/workflow/practices.md) (people: [CONTRIBUTING.md](CONTRIBUTING.md)) | — |
| log lines, readiness, backups or the deploy | [operations/practices.md](docs/operations/practices.md) | — |
| calls to Wikimedia, the scalawiki pin or the OAuth providers | [integrations/practices.md](docs/integrations/practices.md) | — |
| templates, scripts, messages or themes (the large view votes by path) | [ui/practices.md](docs/ui/practices.md) | [ui/review-checklist.md](docs/ui/review-checklist.md) |
| adding or updating a dependency, action, image or downloaded tool | [dependencies/practices.md](docs/dependencies/practices.md) | — |

Which doc to update when, how to write docs, and the reference docs (API, deployment, OAuth): [docs/README.md](docs/README.md).
