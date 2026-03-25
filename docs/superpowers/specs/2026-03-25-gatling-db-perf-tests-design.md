# Gatling DB Performance Tests Design

**Date:** 2026-03-25
**Status:** Approved

## Problem

The performance analysis in `docs/db-performance-analysis.md` identifies missing indexes on `selection`, `rounds`, `round_user`, and `users` that affect every gallery page load, round management page, and vote submission. We need automated load tests to:

1. Prove the indexes make a measurable difference
2. Establish a regression baseline for future schema changes

## Goal

A fully automated `sbt Gatling/test` command that: spins up a MariaDB testcontainer, loads real contest data from CSV, starts a Play `TestServer`, runs 5 Gatling simulations covering the critical query paths, and produces HTML reports. Running the command before and after applying `V45__Add_performance_indexes.sql` gives a direct before/after comparison.

---

## Architecture

### Lifecycle via `GatlingTestFixture` singleton object

Container and TestServer lifecycle is managed by a Scala `object GatlingTestFixture`. When first referenced by any simulation, the object's initializer:
1. Starts a `MariaDBContainer` (`mariadb:10.6.22`)
2. Starts a Play `TestServer` on a random port (Play initializes ScalikeJDBC at this point)
3. Loads CSV data using existing ScalikeJDBC DAOs (which are now live)
4. Registers a JVM shutdown hook to stop the TestServer and container

All simulation classes reference `GatlingTestFixture.port` (and `contestId`, `roundBinaryId`, etc.) in their `setUp` blocks, triggering initialization on first access. Gatling's classloader guarantees the object is initialized exactly once before any scenario runs.

This avoids the sbt task lifecycle problem: no external "start before / stop after" task is needed in sbt. Everything is self-contained in the Gatling JVM.

### sbt configuration

- `gatling-sbt` plugin added to `project/plugins.sbt`
- `Gatling / scalaSource := (Test / sourceDirectory).value / "gatling"` — distinct from the `test/` root where JMH sources live, avoiding classpath collision
- `Gatling / testFrameworks` and dependencies added in `build.sbt`

### Reports

Standard Gatling HTML reports land in `target/gatling/`. Each run produces a timestamped subdirectory. Two runs (before/after V45) are compared manually via the HTML reports.

---

## Data Loading (`GatlingDbSetup` + `GatlingTestFixture`)

Uses existing ScalikeJDBC DAOs: `MonumentJdbc.batchInsert`, `ImageJdbc.batchInsert`, `ContestJuryJdbc.create`, `Round.create`, `User.create`, `SelectionJdbc.batchInsert`. ScalikeJDBC is live because the Play TestServer has already started when data loading runs.

CSV parsing uses `com.github.tototoshi:scala-csv` (already a project dependency):
- **Monuments**: parse `data/wlm-ua-monuments.csv` → `Seq[Monument]` → `MonumentJdbc.batchInsert`. CSV columns mapped: `id`→`id`, `name`→`name`, `lat`→`lat`, `lon`→`lon`, `type`→`typ`, `year_of_construction`→`year`, `municipality`→`city`. The CSV's `adm0` column (`"ua"`) is **not** used — `MonumentJdbc.batchInsert` derives the DB `adm0` value internally as `id.split("-").head.take(3)` (e.g. `"80"` from `"80-361-9002"`).
- **Images**: parse `data/wlm-UA-images-2025.csv` → `Seq[Image]` → `ImageJdbc.batchInsert`. CSV columns `page_id`, `title`, `width`, `height`, `size_bytes`, `mime`, `monument_id`, `url`, `page_url` map to `Image`.

Both are chunked into batches of 1000 rows before calling `batchInsert` (the DAOs' `DB localTx` handles each chunk's transaction independently).

**Synthetic data created after CSV load:**

| Data | Volume |
|------|--------|
| Contest | 1 |
| Binary round (`rates = 1`) | 1 |
| Rating round (`rates = maxRate`) | 1 |
| Jurors (role = `"jury"`) | `gatling.users` (default 20) |
| Organizer (role = `"organizer"`) | 1 — used by round management simulation |
| Selections | up to `numImages × numJurors × jurorFraction × 2 rounds` |

**Selection generation** (`jurorFraction: Double`, default `0.5`):

For each image, `round(numJurors × jurorFraction)` jurors are chosen randomly (without replacement). A selection row is created for each:
- Binary round: rate = `1` (selected) or `-1` (rejected), chosen randomly. No rate-0 rows inserted — unrated means absent.
- Rating round: rate = random integer in `[1, maxRate]`.

Selections are chunked into 5000-row slices before calling `SelectionJdbc.batchInsert`.

**`GatlingTestFixture` exposes to simulations:**
- `port: Int` — Play TestServer HTTP port
- `contestId: Long`
- `roundBinaryId: Long`
- `roundRatingId: Long`
- `jurorIds: Seq[Long]`
- `organizerId: Long`
- `imagePageIds: Seq[Long]`
- `regions: Seq[String]` — queried from DB after monument load: `SELECT DISTINCT adm0 FROM monument WHERE adm0 IS NOT NULL`. These are 2-digit region codes derived from monument IDs (e.g. `"80"`, `"18"`, `"01"`), not country codes.

---

## Config (`GatlingConfig`)

Reads from `test/resources/gatling-perf.conf` with `-D` overrides:

```
gatling.users           = 20      # concurrent virtual users per simulation
gatling.rampUpSeconds   = 10      # ramp-up period
gatling.durationSeconds = 60      # steady-state duration
gatling.jurorFraction   = 0.5     # fraction of jurors assigned per image
gatling.maxRate         = 10      # max star rating for rating round
```

Override example: `sbt -Dgatling.users=50 Gatling/test`

---

## Authentication

Play session auth reads `request.session.get("username")` (key `"username"` in a signed Play session cookie). Each simulation virtual user performs a login step at the start of the scenario:

```
POST /auth   body: username=juror1&password=...
```

`LoginController.auth()` sets the session cookie on success. Gatling automatically carries the session cookie on subsequent requests within the same virtual user chain. Juror credentials and the organizer credential are stored in `GatlingTestFixture` alongside other fixture data.

---

## Simulations

All five simulation classes live in `test/gatling/simulations/`. Each references `GatlingTestFixture` in its `setUp`, triggering fixture initialization on first access.

### 1. `JurorGallerySimulation`
- **Endpoint:** `GET /gallery/round/:round/user/:user/page/:page`
- **Auth:** juror session (login step per virtual user)
- **Feeder:** cycles through `(jurorId, roundId, page)` — covers both binary and rating rounds, pages 1–N
- **Indexes exercised:** `idx_selection_jury_round`

### 2. `RegionFilterSimulation`
- **Endpoint:** `GET /gallery/round/:round/user/:user/region/:region/page/:page`
- **Auth:** juror session
- **Feeder:** `(jurorId, roundId, region, page)` — `region` drawn from `GatlingTestFixture.regions` (2-digit `adm0` codes derived from monument IDs, e.g. `"80"`, `"18"`, `"01"`)
- **Indexes exercised:** `idx_selection_jury_round`, monument region indexes

### 3. `AggregatedRatingsSimulation`
- **Endpoint:** `GET /roundstat/:round`
- **Auth:** organizer session (login step with organizer credentials)
- **Feeder:** `roundId` — alternates between binary and rating rounds
- **Indexes exercised:** `idx_selection_round_page`, `idx_rounds_contest_active`

### 4. `VotingSimulation`
- **Endpoint:** `POST /rate/round/:round/pageid/:pageId/select/:select`
- **Auth:** juror session
- **Feeder:** `(jurorId, pageId, roundId, rate)` — drawn from existing selection rows (juror-image pairs loaded during setup). Rate values: −1 or 1 for binary round; 1–`maxRate` for rating round.
- **Note:** Feeder uses only juror-image pairs that have existing selection rows (all pre-loaded during setup), so the endpoint updates an existing row rather than inserting — exercising the `rate` UPDATE path.
- **Indexes exercised:** `idx_selection_page_jury_round`

### 5. `RoundManagementSimulation`
- **Endpoint:** `GET /admin/rounds?contestId=:contestId`
- **Auth:** organizer session
- **Feeder:** `contestId`
- **Indexes exercised:** `idx_rounds_contest_active`, `idx_round_user_round_active`

---

## File Layout

```
project/
  plugins.sbt                                  ← add io.gatling:gatling-sbt

test/gatling/
  setup/
    GatlingTestFixture.scala                   ← singleton object: container + TestServer + data load
    GatlingDbSetup.scala                       ← CSV parsing + DAO calls
    GatlingConfig.scala                        ← reads gatling-perf.conf + system properties
  simulations/
    JurorGallerySimulation.scala
    RegionFilterSimulation.scala
    AggregatedRatingsSimulation.scala
    VotingSimulation.scala
    RoundManagementSimulation.scala

test/resources/
  gatling-perf.conf

build.sbt                                      ← Gatling sbt config (scalaSource, dependencies)
conf/db/migration/default/
  V45__Add_performance_indexes.sql             ← applied between baseline and optimized runs
```

---

## Before/After Workflow

**Run 1 — baseline (schema without V45 indexes):**
```bash
sbt Gatling/test
# HTML reports: target/gatling/<timestamp>/
```

**Apply indexes (commit V45 migration):**
```bash
# Flyway applies V45 automatically on next GatlingTestFixture startup
```

**Run 2 — optimized:**
```bash
sbt Gatling/test
# HTML reports: target/gatling/<timestamp>/
```

Compare the two Gatling HTML reports: mean response time, p95, p99, throughput per simulation.

---

## V45 Migration (`V45__Add_performance_indexes.sql`)

```sql
-- Critical
CREATE INDEX idx_selection_jury_round      ON selection(jury_id, round_id);
CREATE INDEX idx_rounds_contest_active     ON rounds(contest_id, active);
CREATE INDEX idx_round_user_round_active   ON round_user(round_id, active);

-- High priority
CREATE INDEX idx_users_contest             ON users(contest_id);
CREATE INDEX idx_users_wiki_account        ON users(wiki_account);
CREATE INDEX idx_selection_page_jury_round ON selection(page_id, jury_id, round_id);
CREATE INDEX idx_selection_round_page      ON selection(round_id, page_id);

-- Medium priority
CREATE INDEX idx_selection_round_rate      ON selection(round_id, rate);
CREATE INDEX idx_criteria_rate_criteria    ON criteria_rate(criteria);

-- Schema fixes
ALTER TABLE monument MODIFY id varchar(190) NOT NULL;
ALTER TABLE monument ADD PRIMARY KEY (id);
ALTER TABLE monument DROP INDEX monument_id_index;
ALTER TABLE comment DROP INDEX id;
```
