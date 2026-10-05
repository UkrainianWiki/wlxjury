# Performance and Memory Plan (October 2026)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Cut the jury tool's memory footprint on the shared production server, remove the slow SQL that makes pages take seconds and makes requests queue behind each other, and make the Gatling benchmarks realistic, so the improvements can be measured honestly.

**Where this comes from (2026-10-05):**
- Measurements on the production server (wlm.org.ua).
- Two Gatling runs of all five simulations: a baseline with the current production JVM settings, and a run with the proposed memory settings.
- Two read-only code analyses: a slow-SQL analysis of the baseline run's log, and a round-management review.

**Fact or inference:** Every query plan and gain estimate marked **(inferred)** comes from reading the code, the migrations and the logs. None of it has been EXPLAINed yet. Each task says how to verify it. Measured numbers are marked **(measured)**.

**Tech stack:** Scala 2.13, Play 3.0 (Netty server), ScalikeJDBC 4.3 + scalikejdbc-play-initializer, MariaDB 10.6, Gatling 3.8.4, specs2.

**Key files to read before starting:**
- `app/controllers/RoundController.scala`, `app/services/RoundService.scala`
- `app/db/scalikejdbc/{Round,ImageJdbc,SelectionJdbc,User}.scala`, `app/db/scalikejdbc/rewrite/ImageDbNew.scala`
- `app/org/intracer/wmua/cmd/DistributeImages.scala`
- `app/views/{editRound,rounds,roundStatTable}.scala.html`
- `conf/application.conf` (DB pool keys), `conf/routes`, the Flyway migrations under `conf/db/migration` (indexes up to V55)
- `test/gatling/simulations/*.scala`, `test/gatling/setup/{GatlingConfig,GatlingDbSetup,GatlingTestFixture}.scala`, `test/resources/gatling-perf.conf`
- Earlier analyses: `docs/db-performance-analysis.md`, `docs/schema-performance-analysis.txt`, `docs/queries-improvement.txt`. `docs/gatling-comparison.txt` predates V48–V55, so its numbers can't be compared with today's.

**Recommended order:**
1. Task 1 (production JVM settings) is ops and can be done independently right away.
2. Then Task 2 (fix the benchmarks) and Task 2b (add a round-distribution benchmark that runs first, before Voting), so every later task is measured against an honest baseline that also covers round creation.
3. Then Tasks 3–9 in order; each is independently shippable.
4. Task 10 needs production index statistics first.

---

## Evidence

### Production memory (measured 2026-10-05, jury tool PID 4026, up 10 days)

**Overall:**
- The process holds 1.7–1.86 GB of RSS (`RssAnon` ≈ RSS; mapped files only 10 MB), with a high-water mark of 2.07 GB.
- The service starts Java with **no memory flags**. `/etc/default/wlxjury` and `conf/application.ini` set nothing. Java therefore picks a maximum heap of 1.23 GB (25% of 4.9 GB RAM) and uses G1.
- The server has 4.9 GB RAM, **no swap** and 2 CPUs. It is shared with MariaDB (650 MB), Apache (about ten workers at 60–100 MB each) and a nightly stats job (about 1.3 GB). The kernel killed that job once because the jury tool had grown.

**`GC.class_histogram`** (it forces a full GC first, so this is live data):
- The top 100 classes hold **79 MB**, so the whole live heap is about 85–90 MB.
- That is mostly `byte[]`/`String` and about 160k `ConcurrentHashMap$Node`s.

**`GC.heap_info` / `jstat -gc`, taken before that full GC:**
- 560 MB heap committed, 407 MB used.
- Old generation 430 of 438 MB: mostly garbage waiting for collection.
- 6,982 concurrent cycles in 10 days.

**After the histogram's full GC:**
- RSS dropped to 1.41 GB, so **about 1 GB of RSS is outside the heap**. Metaspace is only 106 MB, and there are 64 threads.
- The most likely cause is glibc malloc arena fragmentation in a long-running, multi-threaded JVM, plus Netty buffers and 448 pending `java.util.zip.Inflater`s **(inferred)**.

**Garbage source:**
- The day's `application.log` is about 36,000 errors from internet scanners: 404s and CSRF failures on `/cgi-bin/php…`, `/v1/graphql`, `/api/designer/…`, `/read-document`, …
- The scanner traffic keeps allocating, so G1 grows the heap toward its maximum.
- G1 never gives the heap back without a full GC or a periodic GC, and `G1PeriodicGCInterval` is 0 (off).

### Gatling: production settings vs proposed memory settings (measured, this Windows PC)

**How the runs were set up:**
- Both runs used `-XX:ActiveProcessorCount=2`. The baseline also used `-XX:MaxRAM=4913m`, so Java chose the same 1 GB heap as on the server.
- Both had native memory tracking and GC logging on.
- Gatling forks **one JVM per simulation**, and the app runs inside it (`TestServer`). The heap figures therefore include Gatling itself, which is stricter than production.
- `-Xlog:gc` was written to one file name, so the GC-log columns below cover only the last JVM (RegionFilter). The NMT summaries cover every JVM.

| Per JVM | Baseline (auto 1 GB heap) | Proposed: `-Xmx512m -XX:MaxDirectMemorySize=128m -XX:G1PeriodicGCInterval=60000 -XX:+ExitOnOutOfMemoryError` |
|---|---|---|
| OOM / full GCs | 0 / 0 | 0 / 0 |
| Live heap after GC (GC log) | max 150 MB, median 82 MB | max 132 MB, median 72 MB |
| Heap committed (NMT) | 288–416 MB | 147–297 MB |
| Total committed (NMT) | 557–693 MB | 424–573 MB |
| GC pause total (last simulation) | 1.1 s | 1.1 s |
| Voting "cast vote" | mean 15 ms, p99 65 ms | mean 13 ms, p99 80 ms |

**What the run did not cover:**
- `MALLOC_ARENA_MAX` cannot be tested on Windows.
- Periodic GC never triggered, because the runs had no idle time.
- Round creation and image distribution, which load a whole round's images, are not covered by any simulation.

**Response times of the heavy simulations:** both runs were dominated by DB queueing (next section), not by the JVM.

| Simulation / request | Baseline mean · p95 · KO | Proposed mean · p95 · KO |
|---|---|---|
| RoundManagement / rounds list | 8.0 s · 20.5 s · 0 (+540 fake OKs, see Task 2) | 10.8 s · 18.7 s · 40 |
| RoundManagement / login organizer | 31.4 s · 60.0 s · 27 | 27.0 s · 40.7 s · 0 |
| AggregatedRatings / round stats | 14.3 s · 60.0 s · 162 | 11.5 s · 60.0 s · 181 |
| AggregatedRatings / login organizer | 39.6 s · 60.0 s · 54 | 46.7 s · 60.0 s · 52 |
| JurorGallery / gallery page | 4.1 s · 7.4 s · 0 | 2.3 s · 3.7 s · 0 |
| RegionFilter / region gallery page | 3.5 s · 8.1 s · 0 | 4.1 s · 6.5 s · 0 |

The raw data is in the session scratchpad: `jurybench/results-{baseline,proposed}`, `sbt-*.log` and `gc-*.log`. To reproduce:

```
sbt -Dsbt.server.forcestart=true "set Gatling / javaOptions ++= Seq(...)" Gatling/test
```

### Slow SQL in the baseline run (measured from ScalikeJDBC warnings, only statements over 1 s)

**Fixture size:** 38,367 images, 20 jurors, `jurorFraction=0.5`, 2 rounds. That gives about 767k `selection` rows: about 383k per round and about 19k per juror per round. The MariaDB container runs with `--innodb-buffer-pool-size=512M`.

| # | Query (normalized) | Caller → page | n > 1 s | total s | median / max ms |
|---|---|---|---|---|---|
| 1 | `SELECT rate, count(1) FROM (SELECT DISTINCT s.page_id, s.rate FROM selection s WHERE s.round_id=?) t GROUP BY rate` | `Round.roundRateStat` (Round.scala:358) ← `RoundService.getRoundStat:148` ← `/roundstat/:round` | 840 | **2,110 (71%)** | 2398 / 4343 |
| 2 | `SELECT r.id, count(DISTINCT s.page_id) FROM rounds r JOIN selection s ON r.id=s.round_id WHERE r.contest_id=? GROUP BY r.id LIMIT ?` | `ImageJdbc.roundsStat` (:201) ← `RoundController.rounds:46` ← `/admin/rounds` | 682 | **858 (29%)** | 1140 / 3768 |
| 3 | gallery list `… STRAIGHT_JOIN images … WHERE s.jury_id=? AND s.round_id=? ORDER BY rate DESC, s.monument_id ASC, s.page_id ASC LIMIT 15 OFFSET ?` (+ `i.monument_id LIKE 'NN%'` for regions) | `ImageDbNew.SelectionQuery.list` (:102) ← `GalleryService.filesByUserId:68` | 37 | 40 | ~1050 / 1553 |
| 5 | `SELECT COUNT(DISTINCT s.page_id) FROM selection s WHERE s.round_id=?` | `SelectionQuery.count` (:106) ← `getRoundStat:149` | 2 | 2 | 1049 |

**Other entries in the log are queueing side effects, not slow SQL:**
- `User.findByEmail` from `Secured.userFromRequest`, and `ContestJuryJdbc.findById`.
- One vote `UPDATE`: the first write after the dump restore, on a cold buffer pool.

**Why trivial requests wait 8–40 s:** logins are a single lookup on a unique key, so the time is spent waiting for a connection or a thread, not in SQL. See Task 3.

**Indexes on `selection` today:**
- The primary key `id`.
- V7, single-column: `rate`, `round_id`, `jury_id`, `page_id`.
- V46 `(round_id, rate)`.
- V48 `(jury_id, round_id, rate, monument_id, page_id)`.
- V49 `(round_id, jury_id, rate)`.
- V50 `(round_id, monument_id)`.
- V53 UNIQUE `(page_id, jury_id, round_id)`.
- V55 `(round_id, page_id)`.

---

## Task 1: Production JVM settings for the jury tool (ops, no code)

**Background:**
- The start script (`/usr/share/wlxjury/bin/wlxjury`, sbt-native-packager) reads `JAVA_OPTS` from the environment.
- `wlxjury.service` loads `/etc/default/wlxjury` as its `EnvironmentFile`, and has `Restart=always`.

**Change** `/etc/default/wlxjury` (root):

```
JAVA_OPTS="-Xmx512m -XX:MaxDirectMemorySize=128m -XX:G1PeriodicGCInterval=60000 -XX:NativeMemoryTracking=summary -XX:+ExitOnOutOfMemoryError"
MALLOC_ARENA_MAX=2
```

Then run `sudo systemctl restart wlxjury`, at a quiet time: the restart logs everyone out.

**What each setting does:**
- `-Xmx512m`: over 3× the largest live heap measured, even with Gatling in the same JVM. Use `768m` if you want more margin for distributing images to a new round of a large contest. No benchmark covers that path yet; Task 2b adds one, and its result should confirm or adjust this value.
- `MaxDirectMemorySize`: caps Netty's off-heap buffers.
- `G1PeriodicGCInterval`: returns unused heap to the OS when idle.
- `MALLOC_ARENA_MAX=2`: the usual cure for glibc arena bloat.
- `NativeMemoryTracking`: costs a few percent; it lets `jcmd` show where non-heap memory goes.
- `ExitOnOutOfMemoryError`: with `Restart=always`, a wrong limit costs a restart, not a hang.

**Verify:**
- After a day, `ps -o rss= -p $(pgrep -u wlxjury java)` should show about 600–800 MB **(inferred)**, against 1.4–2.07 GB now.
- `sudo jcmd $(pgrep -u wlxjury java) VM.native_memory summary` shows where the remaining native memory goes.
- Watch the first image distribution to a new round. On `java.lang.OutOfMemoryError` in `/var/log/wlxjury/application.log`, raise `-Xmx`.

**Related, optional:** most heap churn on production comes from scanners. Have Apache proxy only the jury tool's real paths to it, or ban the scanners with fail2ban.

---

## Task 2: Make the Gatling benchmarks honest and realistic (do before Tasks 3–9)

### Problem 1: fake successes (measured)

In RoundManagement, 27 organizer logins timed out. Those virtual users carried on without a session:
- each of their 20 `/admin/rounds` requests redirected to `/login`;
- Gatling followed the redirect;
- the login page returned 200, which passed `status.is(200)`.

That produced **540 = 27 × 20** "rounds list Redirect 1" OKs. AggregatedRatings has the same flaw: 54 login timeouts and 961 redirect "OK"s.

### Problem 2: unrealistic load

`RoundManagementSimulation` uses Voting's injection profile. That is 140 sessions of the same organizer, each requesting the rounds list 20 times with no pause: about 2,800 requests offered in 70 s, and 296 s to drain.

In reality, a contest has from a few up to a few tens of rounds, and one to three organizers edit them now and then. Round management should be **at least an order of magnitude** below voting.

The benchmark also never requests the expensive page, `GET /admin/rounds/edit` (see Task 7).

### Change: replace `test/gatling/simulations/RoundManagementSimulation.scala`

**Default flow:** a few organizer sessions doing list → edit → save → list → stats → list, with human think time. At `pauseScale=0.25` that is about 0.8 req/s, around 40× below Voting (about 34 req/s). At `pauseScale=1` it is about 0.25 req/s.

**Opt-in worst case:** `-Dgatling.roundMgmt.stress=true` keeps the old load, now with strict checks.

```scala
package gatling.simulations

import gatling.setup.{GatlingConfig, GatlingTestFixture}
import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._

/** Round management as organizers actually do it: a few organizer sessions browsing
  * list -> edit -> save -> list -> stats -> list with think time (≥ an order of
  * magnitude below VotingSimulation). `-Dgatling.roundMgmt.stress=true` runs the old
  * worst case instead (many concurrent sessions hammering the rounds list). */
class RoundManagementSimulation extends Simulation {

  private val baseUrl   = s"http://localhost:${GatlingTestFixture.port}"
  private val cfg       = GatlingConfig.RoundMgmt
  private val contestId = GatlingTestFixture.contestId
  private val jurorIds  = GatlingTestFixture.jurors.map(_._1)

  private def t(sec: Int): FiniteDuration = (sec * cfg.pauseScale * 1000).round.millis

  // (id, number, name, ratesId): saving with the same values is idempotent
  private val roundFeeder = Iterator.continually(Seq(
    (GatlingTestFixture.roundBinaryId, 1, "Binary Round", 1),
    (GatlingTestFixture.roundRatingId, 2, "Rating Round", GatlingConfig.maxRate)
  )).flatten.map { case (id, n, name, rates) =>
    Map("roundId" -> id, "roundNumber" -> n, "roundName" -> name, "rates" -> rates)
  }

  private val organizerFeeder = Iterator.continually(GatlingTestFixture.organizers).flatten.map {
    case (_, email, pass) => Map("orgEmail" -> email, "orgPassword" -> pass)
  }

  private val login =
    exec(http("login organizer").post("/auth")
      .formParam("login", "#{orgEmail}").formParam("password", "#{orgPassword}")
      .check(status.is(303)))
      .exitHereIfFailed   // no unauthenticated requests "succeeding" on the login page

  private val roundsList = http("rounds list")
    .get(s"/admin/rounds?contestId=$contestId")
    .check(status.is(200), substring("/admin/rounds/edit"))

  private val editRound = http("edit round")
    .get(s"/admin/rounds/edit?id=#{roundId}&contestId=$contestId")
    .check(status.is(200), substring("/admin/rounds/save"))

  private val saveRound = http("save round")
    .post("/admin/rounds/save")
    .formParam("id", "#{roundId}").formParam("number", "#{roundNumber}")
    .formParam("name", "#{roundName}").formParam("contest", contestId)
    .formParam("roles", "jury").formParam("distribution", 0).formParam("rates", "#{rates}")
    .formParam("minMpx", "").formParam("minSize", "").formParam("mediaType", "all")
    .formParam("jurors[0]", jurorIds.head)        // required by the form; ignored for an existing round
    .check(status.is(303))

  private val roundStat = http("round stats")
    .get("/roundstat/#{roundId}")
    .check(status.is(200), substring("/roundstat/"))

  // Optional, once per simulation. The fixture contest has no image category, so a
  // round without previous rounds gets 0 images; this measures only the round +
  // round_user inserts. Each simulation restores the DB dump, so the extra round
  // doesn't leak into other simulations.
  private val createRound = http("create round")
    .post("/admin/rounds/save")
    .formParam("number", 0).formParam("name", "Gatling created round")
    .formParam("contest", contestId).formParam("roles", "jury")
    .formParam("distribution", 0).formParam("rates", 1)
    .formParam("minMpx", "").formParam("minSize", "").formParam("mediaType", "all")
    .formParamSeq(jurorIds.zipWithIndex.map { case (id, i) => s"jurors[$i]" -> id })
    .check(status.is(303))

  private val organizerScn = scenario("Round Management")
    .feed(organizerFeeder)
    .exec(login)
    .exec(roundsList).pause(t(3), t(10))
    .doIf(session => cfg.createRound && session.userId == 1L) {
      exec(createRound).pause(t(5), t(15))
    }
    .during(cfg.durationSeconds.seconds) {
      feed(roundFeeder)
        .exec(editRound).pause(t(10), t(30))   // read / change the form
        .exec(saveRound)
        .exec(roundsList).pause(t(5), t(15))   // the browser follows the save redirect
        .exec(roundStat).pause(t(15), t(45))   // look at juror progress
        .exec(roundsList).pause(t(5), t(15))
    }

  // Old worst case, kept as an explicit opt-in (single organizer, no think time).
  private val (_, orgEmail, orgPassword) = GatlingTestFixture.organizer
  private val stressScn = scenario("Round Management (stress)")
    .exec(session => session.set("orgEmail", orgEmail).set("orgPassword", orgPassword))
    .exec(login)
    .repeat(cfg.stressRepeat)(exec(roundsList))

  setUp(
    if (cfg.stress)
      stressScn.inject(
        rampUsers(cfg.stressUsers).during(GatlingConfig.rampUpSeconds.seconds),
        constantUsersPerSec(cfg.stressUsers.toDouble / 10).during(GatlingConfig.durationSeconds.seconds))
    else
      organizerScn.inject(rampUsers(cfg.organizers).during(cfg.rampUpSeconds.seconds))
  ).protocols(http.baseUrl(baseUrl).disableFollowRedirect)
   .maxDuration((cfg.durationSeconds + cfg.rampUpSeconds + 180).seconds)
}
```

**Check before running:** the form field names in `saveRound` / `createRound` (`roles`, `distribution`, `rates`, `minMpx`, `minSize`, `mediaType`, `jurors[i]`) must match `RoundController.editRoundForm`. Adjust them if they differ.

### Config: append to `test/resources/gatling-perf.conf`

New keys, separate from `gatling.users`. `gatling.users` also sets the fixture's juror count and the DB-dump cache key, so reusing it would change the fixture.

```hocon
gatling {
  roundMgmt {
    stress               = false  # true = old worst case (stressUsers sessions, no think time)
    organizers           = 3      # concurrent organizer sessions (accounts organizer, organizer2, ...)
    rampUpSeconds        = 10
    durationSeconds      = 180    # each organizer loops for this long; use 300 for before/after comparisons
    quickDurationSeconds = 30
    pauseScale           = 0.25   # multiplies human think times; 1.0 = real pace, 0 = no pauses
    createRound          = false  # one round creation per simulation
    stressUsers          = 20
    stressRepeat         = 20
  }
}
```

### `test/gatling/setup/GatlingConfig.scala`: add

```scala
  object RoundMgmt {
    private val c = cfg.getConfig("gatling.roundMgmt")
    val stress: Boolean      = c.getBoolean("stress")
    val organizers: Int      = c.getInt("organizers")
    val rampUpSeconds: Int   = c.getInt("rampUpSeconds")
    val durationSeconds: Int = if (quick) c.getInt("quickDurationSeconds") else c.getInt("durationSeconds")
    val pauseScale: Double   = c.getDouble("pauseScale")
    val createRound: Boolean = c.getBoolean("createRound")
    val stressUsers: Int     = c.getInt("stressUsers")
    val stressRepeat: Int    = c.getInt("stressRepeat")
  }
```

### Fixture: extra organizer accounts

Create them after the restore, so they are not part of the dump or the cache key:

```scala
// GatlingDbSetup
def ensureExtraOrganizers(contestId: Long, n: Int): Seq[(Long, String, String)] =
  (2 to n + 1).map { i =>
    val email = s"organizer$i@gatling.test"; val pass = s"orgpass$i"
    val u = User.findByEmail(email).headOption.getOrElse(
      User.create(fullname = s"Organizer $i", email = email, password = User.sha1(pass),
                  roles = Set("admin"), contestId = Some(contestId)))
    (u.id.get, email, pass)
  }

// GatlingTestFixture
val organizers: Seq[(Long, String, String)] =
  organizer +: GatlingDbSetup.ensureExtraOrganizers(contestId, GatlingConfig.RoundMgmt.organizers - 1)
```

They are created after `GatlingDbCache.save`, so `loadFromDb`'s `find(admin)` still picks the original organizer.

### `AggregatedRatingsSimulation`: apply the same pattern

It has the same flaws: 140 sessions of one organizer, each requesting `/roundstat` 20 times with no pause. Apply:
- an organizer feeder;
- `exitHereIfFailed` after login;
- `disableFollowRedirect`;
- a content check;
- 15–45 s × `pauseScale` between views;
- a `gatling.aggRatings.stress` opt-in.

Alternatively, treat the roundStat step of the new RoundManagement flow as the realistic case, and keep AggregatedRatings only as the explicit `/roundstat` stress test.

**Other simulations:**
- Voting, JurorGallery and RegionFilter use jurors, so their injection is roughly plausible. They lack think time; that is acceptable as a stress level.
- Add `exitHereIfFailed` after login to all of them anyway.
- `GatlingCompare` aggregates per simulation. For mixed flows, compare per request name from `js/stats.json`. Re-baseline after this task: old and new RoundManagement numbers are not comparable.

**Fidelity notes (inferred):**
- `loadFromDb` touches `Round.usersRef` (`hasManyThrough … .byDefault`) in the server JVM. As a result, every Round query in the benchmark carries LEFT JOINs to `round_user` and `users`, which production never has.
- The fixture contest has no `categoryId`, so `imagesByRound` (edit page, round creation) does not load contest images the way production does.

**Verify:**
- 0 KO.
- No "Redirect 1" entries on list or stat requests.
- The global rate is about 0.8 req/s.
- The new "edit round" metric shows the edit page as the slowest round-management page (several seconds expected before Task 7, inferred).
- `stress=true` reproduces the old numbers, without fake OKs.

---

## Task 2b: Round distribution benchmark (runs first, before Voting)

**Why:**
- In a real contest, organizers create rounds and distribute images to jurors *before* voting starts.
- Distribution is the heaviest thing the app does, both in memory and in database writes. No simulation exercises it, so the Task 1 heap limit (`-Xmx512m`) is unvalidated for exactly that path.
- The fixture never runs distribution either: `GatlingDbSetup.load` writes `selection` rows directly with `SelectionJdbc.batchInsert` (about line 161). The real path is `RoundController.saveRound` → `RoundService.distributeAndVerify` → `DistributeImages.imagesByRound` + `distributeImages`.

**The two paths to cover:**
1. **First round of a contest.** Images come from the contest's category: `getFilteredImages` → `imageRepo.findByContestId(round.contestId)` → `ContestJury.categoryId` → `findByCategory` over `category_links`.
   - The fixture contest has **no `categoryId`**, so this path currently distributes **0 images**.
   - This is also the fidelity gap noted in Task 2: the edit page's `imagesByRound` is cheap in the benchmark but loads every contest image in production.
2. **Next round built from previous rounds.** For example, "images selected at least once in the binary round" (`prevSelectedBy = 1`), or the top N by average rating of a rated round (`topImages`). This goes through `imageRepo.byRoundMerged(prev, rated = …)` over the previous round's ~383k selection rows, then `mergeByPageId`, the filters and the batch insert. It works with the current fixture.

Both paths build the full candidate list in memory (`ImageWithRating` per image), then insert `images × jurors-per-image` selection rows in one transaction. With 38k images and, say, 5 jurors per image, that is about 190k rows per round.

### Fixture change: give the contest an image category (enables path 1)

In `GatlingDbSetup.load`, after the images are inserted:
- Create the category with `CategoryJdbc.findOrInsert("Images from Wiki Loves Monuments 2025 in Ukraine")`.
- Set it as the fixture contest's `categoryId`.
- Insert `category_links (category_id, page_id)` for every fixture image, batched inside the existing bulk-insert transaction.

Check `CategoryJdbc` / `CategoryLinkJdbc` (or whatever `findByCategory` joins) for the exact table and batch-insert API.

**Cache key:** the DB-dump cache key (`GatlingDbCache.cacheKey`) hashes the migrations, the CSV sizes and the fixture parameters, **but not the fixture code**. Add a fixture version constant to the hash, e.g. `md.update("fixture-v2".getBytes)`, and bump it whenever `GatlingDbSetup.load` changes. Otherwise an old cached dump without the category gets restored.

**Side effects to expect:**
- `imagesByRound` on the edit page now loads all ~38k contest images.
- RoundManagement "edit round" (Task 2) therefore shows the real pre-Task-7 cost, as in production.

### New `test/gatling/simulations/RoundDistributionSimulation.scala`

One organizer, sequential steps, no concurrency. Distribution is a rare, one-at-a-time admin operation; what matters is its latency and its memory, not throughput. Each step is a real `POST /admin/rounds/save`, exactly as the round form submits it.

**Steps, each followed by a verification GET:**
1. **Create a first round from the contest category.** Binary rates, all fixture jurors, `distribution = N` jurors per image (config, e.g. 3–5).
   - Expect 303 to the rounds list.
   - Then `GET /roundstat/<new id>` (or the rounds list) and check the image count ≈ the number of fixture images. Parse it from the page, or add a small JSON endpoint if parsing is fragile.
2. **Create a second round from the binary round.**
   - Set the previous round to the fixture's binary round and `prevSelectedBy = 1` (images selected by at least one juror). Use the round form's real field names for previous rounds and filters; check `RoundController.editRoundForm`.
   - Check the count ≈ `selectedImageCount(binary round)`.
3. **Optional (`distribution.fromRated = true`):** create a round from the rated round with `topImages = K`.
4. **Optional (`distribution.topUp = true`):** "distribute new files" on the round from step 1, by saving it with `newImages = true`. After step 1 nothing is new, so this measures the no-op top-up: still a full `imagesByRound` and two COUNT DISTINCTs, which is worth knowing.

```scala
// Sketch: field names must match RoundController.editRoundForm.
private val createFromCategory = http("create round: from contest category")
  .post("/admin/rounds/save")
  .formParam("number", 0).formParam("name", "Distribution round A")
  .formParam("contest", contestId).formParam("roles", "jury")
  .formParam("distribution", cfg.jurorsPerImage).formParam("rates", 1)
  .formParam("minMpx", "").formParam("minSize", "").formParam("mediaType", "all")
  .formParamSeq(jurorIds.zipWithIndex.map { case (id, i) => s"jurors[$i]" -> id })
  .check(status.is(303), header("Location").saveAs("afterCreate"))
  // then read the new round id (rounds list or Location) and verify its image count

setUp(organizerScn.inject(atOnceUsers(1)))
  .protocols(http.baseUrl(baseUrl).disableFollowRedirect)
  .maxDuration(30.minutes)
```

### Configuration and timeouts

Add to `gatling-perf.conf` under `gatling.distribution`:
- `jurorsPerImage` (default 3)
- `fromRated` (false), `topImages` (100)
- `topUp` (false)
- `iterations` (1). Each iteration creates new rounds. The DB is restored per JVM, so rounds only accumulate within one run.

**Timeouts:** a distribution request can take minutes.
- Raise Gatling's request timeout for this simulation (`gatling.http.requestTimeout`, Gatling's default is 60 s).
- Raise Play's `play.server.http.idleTimeout` in the test `application.conf`. Play's default is 75 s; `conf/application.conf` doesn't override it.
- Note whether production would hit the same limit. If so, that is a real finding: the organizer's browser gets cut off while the distribution keeps running, or gets aborted.

### Run order: distribution first, then voting

- Each simulation runs in its own forked JVM and restores the DB dump, so simulations don't share data; order is about the natural flow and reporting.
- The observed order of `Gatling/test` (Voting, RoundManagement, JurorGallery, AggregatedRatings, RegionFilter) is not configured anywhere.
- Make the order explicit with a command alias in `build.sbt`:

```scala
addCommandAlias("gatlingAll",
  ";Gatling/testOnly gatling.simulations.RoundDistributionSimulation" +
  ";Gatling/testOnly gatling.simulations.VotingSimulation" +
  ";Gatling/testOnly gatling.simulations.JurorGallerySimulation" +
  ";Gatling/testOnly gatling.simulations.RegionFilterSimulation" +
  ";Gatling/testOnly gatling.simulations.RoundManagementSimulation" +
  ";Gatling/testOnly gatling.simulations.AggregatedRatingsSimulation")
```

and document `sbt gatlingAll` in the Gatling docs.

**Optional, later:** chain distribution and voting in one JVM, so Voting votes on the rounds that RoundDistribution just created. That needs either a combined simulation or skipping the dump restore. Keep them independent for now; comparable baselines matter more.

### Measuring memory (the main point of this task)

- Pass the Task 1 JVM flags to the forked JVM, so the run validates them.
- Write a GC log per JVM: `-Xlog:gc*:file=target/gatling/gc-%p.log:time,uptime,level,tags`. The `%p` matters: without it each forked JVM overwrites the previous log. The 2026-10-05 comparison lost all but the last simulation's GC log that way.
- Record, per step:
  - the response time;
  - the selection rows inserted;
  - the max heap after GC during the step, from the GC log timestamps.
- Run it twice: with the Task 1 flags (`-Xmx512m`) and with the production-like baseline (`-XX:MaxRAM=4913m`, plus `-XX:ActiveProcessorCount=2` for both).

**Pass criteria:**
- no `OutOfMemoryError` and no full GC with `-Xmx512m`;
- max heap after GC under about 50% of `-Xmx`.

If it fails, raise Task 1's `-Xmx` (768m) or make distribution stream or batch its candidates instead of materializing them all. That would be its own follow-up task.

**Scale knob (optional):** production contests can be bigger than the 38k-image fixture CSV. A `gatling.distribution.imageMultiplier` fixture option could duplicate images with synthetic page ids to test 100k+ images. Use a separate cache key.

**Verify:**
- Step 1 distributes about `images × jurorsPerImage` selection rows, and the verification GET shows the expected image count.
- Step 2's count matches `SELECT COUNT(DISTINCT page_id) FROM selection WHERE round_id = <binary> AND rate > 0`.
- No `RoundNotFullyDistributed` errors in the log.
- The memory pass criteria above hold with the Task 1 flags.
- After this task, `sbt gatlingAll` runs RoundDistribution first.

---

## Task 3: DB connection pool and blocking dispatcher (app config)

**Background (inferred from the scalikejdbc 4.3 sources, not observed on a running pool):**
- `conf/application.conf` sets `db.default.minPoolSize` / `maxPoolSize = 30`.
- scalikejdbc-play-initializer reads settings through `TypesafeConfigReader`, which only knows `poolInitialSize`, `poolMaxSize` and `poolConnectionTimeoutMillis`. The pool therefore falls back to its default **max size of 8**.
- All blocking JDBC runs on Play's default dispatcher. A handful of 1–4 s aggregate queries hold every connection and thread, and logins and votes queue behind them: logins took 8–40 s or timed out at 60 s in the benchmark.
- Under load, throughput was about 7.8 rounds-list requests per second at about 1 s per `roundsStat`, which means about 8 queries at a time. That matches an 8-connection pool.

**Change:**

```diff
-db.default.minPoolSize = 30
-db.default.maxPoolSize = 30
+db.default.poolInitialSize = 10
+db.default.poolMaxSize = 30
+db.default.poolConnectionTimeoutMillis = 30000
```

Use the exact current key names and values from `application.conf`. Then move the DB-heavy actions onto the existing `blocking-dispatcher`, which `Api.scala` already uses:
- `RoundController.roundStat`
- the new `roundStatTable` and `newFilesCount` (Task 7)
- `saveRound` when it distributes images
- the gallery actions

A bigger pool alone just means 30 concurrent aggregates on 2 CPUs. Tasks 4–9 are what remove the cost.

**Verify:**
- Find the effective pool size in the startup log or a thread dump.
- In RoundManagement `stress=true`, "login organizer" p95 should be under 100 ms while heavy pages run.

---

## Task 4: `/roundstat`: count only what the page shows (query #1, 71% of slow time)

**Background:**
- `Round.roundRateStat` computes `(rate, count)` for **every** rate of a binary round.
- The view (`roundStatTable`) only reads `totalByRate.getOrElse(1, 0)` ("selected"). "Unrated" is derived as `total - selected`.
- No index has `(round_id, rate, page_id)` together, so the query does about 383k row lookups (or scans the V48 index), then builds a DISTINCT temporary table **(inferred)**.
- `total` (query #5) is the same COUNT DISTINCT as the rounds list, and can come from Task 5's cache.

**Migration** `V56__selection_round_rate_page.sql`. Use the next free version number and the existing migration style. It replaces `idx_selection_round_rate`, so the index count and the per-vote write cost stay the same; the new index still serves `byRating` (`WHERE rate=? AND round_id=?`).

```sql
ALTER TABLE selection
  ADD INDEX idx_selection_round_rate_page (round_id, rate, page_id),
  DROP INDEX IF EXISTS idx_selection_round_rate,
  ALGORITHM=INPLACE, LOCK=NONE;
ANALYZE TABLE selection;
```

On production this is an online, in-place index build: minutes for a few million rows. The `DROP` only changes metadata.

**Code (`RoundService.getRoundStat`):**

```diff
-    val totalByRate = if (round.isBinary) dao.roundRateStat(roundId).toMap else Map.empty[Int, Int]
-    val total = SelectionQuery(roundId = Some(roundId), grouped = true).count()
+    // the view reads only totalByRate(1) ("selected"); "unrated" = total - selected
+    val totalByRate =
+      if (round.isBinary) Map(1 -> dao.selectedImageCount(roundId)) else Map.empty[Int, Int]
+    val total = RoundImageCounts.get(Seq(roundId))(ImageJdbc.imageCountByRounds).getOrElse(roundId, 0)
```

```scala
// Round (+ add to RoundRepo). Keep roundRateStat (RoundUserStatSpec covers it), but simplify it:
def selectedImageCount(roundId: Long): Int =
  sql"""SELECT COUNT(DISTINCT s.page_id) FROM selection s
        WHERE s.round_id = $roundId AND s.rate = 1""".map(_.int(1)).single().getOrElse(0)

def roundRateStat(roundId: Long): Seq[(Int, Int)] =
  sql"""SELECT s.rate, COUNT(DISTINCT s.page_id) FROM selection s
        WHERE s.round_id = $roundId GROUP BY s.rate""".map(rs => (rs.int(1), rs.int(2))).list()
```

- **Correctness:** both are exact. The count of distinct (page, rate) pairs at rate r equals the count of distinct pages at rate r.
- **Expected:** `/roundstat` drops from 2–4 s to about 0.3–0.6 s **(inferred)**. What remains is mostly `roundUserStat` (covering V49 index, 0.1–0.4 s).
- **Optional:** cache `roundUserStat` and the selected count per round with a 30–60 s TTL. Organizers accept a minute of staleness while monitoring. Keep `RoundUser.byRoundId` (juror start/stop flags) uncached. Inactive rounds can't be cached forever, because votes still land on them (see the side issues at the end).

**Verify:**
- `EXPLAIN SELECT COUNT(DISTINCT page_id) FROM selection WHERE round_id = 1 AND rate = 1;` should show a range scan on `idx_selection_round_rate_page`, `Using index`.
- `EXPLAIN SELECT s.rate, COUNT(DISTINCT s.page_id) FROM selection s WHERE s.round_id = 1 GROUP BY s.rate;` should show no `Using temporary`.
- `RoundUserStatSpec` passes.
- Gatling "round stats" p95 drops, and `Round.scala:358` disappears from the slow-query warnings.

---

## Task 5: Rounds list: cache per-round image counts (query #2, 29% of slow time)

**Background:**
- `ImageJdbc.roundsStat` runs `COUNT(DISTINCT page_id)` over every round of the contest (about 767k index entries) on every `/admin/rounds` view.
- The V55 `(round_id, page_id)` index exists. The cost is volume.
- The join through `rounds` prevents a loose index scan and probably adds a temporary table.
- The `LIMIT` is applied after the full GROUP BY.
- A round's image set changes **only** when images are distributed, removed or merged. A vote only updates `rate` on an existing row (`SelectionJdbc.rate`). So the count can be cached with exact invalidation.

**New file** `app/db/scalikejdbc/RoundImageCounts.scala`. It lives in the db package so that DAO code can invalidate the cache without a layering inversion.

```scala
package db.scalikejdbc

import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap

/** Distinct images per round. Changes only on distribute / remove / merge (never on a
  * vote), so it is invalidated explicitly; the TTL only bounds staleness from writers
  * outside this JVM (scripts, manual SQL). Invalidate AFTER the writing tx commits. */
object RoundImageCounts {
  private final case class Entry(count: Int, at: Long, version: Long)
  private val TtlMs    = 10 * 60 * 1000L
  private val entries  = TrieMap.empty[Long, Entry]
  private val versions = TrieMap.empty[Long, AtomicLong]
  private def ver(id: Long): Long = versions.getOrElseUpdate(id, new AtomicLong).get

  def get(roundIds: Seq[Long])(load: Seq[Long] => Map[Long, Int]): Map[Long, Int] = {
    val now   = System.currentTimeMillis()
    val fresh = roundIds.flatMap { id =>
      entries.get(id).filter(e => now - e.at < TtlMs && e.version == ver(id)).map(e => id -> e.count)
    }.toMap
    val missing = roundIds.filterNot(fresh.contains)
    if (missing.isEmpty) fresh
    else {
      val before = missing.map(id => id -> ver(id)).toMap    // read version BEFORE querying
      val loaded = load(missing)
      missing.foreach { id =>                                // drop result if invalidated meanwhile
        if (ver(id) == before(id)) entries.put(id, Entry(loaded.getOrElse(id, 0), now, before(id)))
      }
      fresh ++ missing.map(id => id -> loaded.getOrElse(id, 0))
    }
  }

  /** Seed with a value read after commit (e.g. distributeAndVerify's "after" count). */
  def put(roundId: Long, count: Int): Unit =
    entries.put(roundId, Entry(count, System.currentTimeMillis(), ver(roundId)))

  def invalidate(roundIds: Long*): Unit = roundIds.foreach { id =>
    versions.getOrElseUpdate(id, new AtomicLong).incrementAndGet(); entries.remove(id)
  }

  def clear(): Unit = { entries.clear(); versions.clear() }   // call from SharedTestDb.truncateAll()
}
```

**`ImageJdbc`:** query `selection` only, by the round ids the controller already has.

```diff
-  def roundsStat(contestId: Long, limit: Int): Seq[(Long, Int)] =
-    sql"""SELECT r.id, count(DISTINCT(s.page_id)) FROM rounds r
-  JOIN selection s ON r.id = s.round_id WHERE r.contest_id = $contestId GROUP BY r.id LIMIT $limit
-      """.map(rs => (rs.long(1), rs.int(2))).list()
+  def imageCountByRounds(roundIds: Seq[Long]): Map[Long, Int] =
+    if (roundIds.isEmpty) Map.empty
+    else sql"""SELECT s.round_id, COUNT(DISTINCT s.page_id) FROM selection s
+               WHERE s.round_id IN ($roundIds) GROUP BY s.round_id"""
+      .map(rs => rs.long(1) -> rs.int(2)).list().toMap
```

**`RoundController.rounds`:**

```diff
           val rounds = Round.findByContest(contestId)
           Ok(views.html.rounds(user, rounds,
-              ImageJdbc.roundsStat(contestId, rounds.size).toMap,
+              RoundImageCounts.get(rounds.flatMap(_.id))(ImageJdbc.imageCountByRounds),
```

**Invalidation points.** Always invalidate after the commit; the version check makes concurrent loads race-free.
- `DistributeImages.distributeImages(round, images, jurors)`: after the `DB.localTx { … }` block, `round.id.foreach(RoundImageCounts.invalidate(_))`.
- `RoundService.distributeAndVerify`: `val after = SelectionJdbc.imageCountByRound(id); RoundImageCounts.put(id, after.toInt)`. The verification itself keeps the **uncached** before and after counts: it must stay exact.
- `RoundService.mergeRounds`: after its `DB.localTx`, `RoundImageCounts.invalidate(targetId, sourceId)`.
- These run in autocommit, so invalidate right after their statement:
  - `SelectionJdbc.removeImage(pageId, roundId)`
  - `SelectionJdbc.removeUnrated(roundId)`
  - `SelectionJdbc.setRound(_, old, new)` (both ids)
  - `Round.delete(ids)`

  This also covers `LargeViewController.removeImage` and in-process `Tools` scripts.
- `SelectionJdbc.destroyAll` is a soft delete. The counts ignore `deleted_at`, so it needs no invalidation.
- Tests: call `RoundImageCounts.clear()` from `SharedTestDb.truncateAll()` and `SharedPlayApp.truncateAll()`. TRUNCATE resets AUTO_INCREMENT, so round ids get reused.

**Correctness:**
- Exact within the app JVM.
- Writers outside the JVM (scripts, manual SQL) can be stale for up to 10 minutes (the TTL).
- This assumes a single app instance **(inferred from the deployment)**. A blue-green switch with two live processes would need a shared store or a short TTL.

**Expected:** the list page needs about 3 small queries, under 20 ms on a cache hit **(inferred)**. On a miss it runs the same ~1 s query once. Inactive and archived rounds are always cache hits.

**Alternative:** a persisted `rounds.image_count` column, set by `distributeAndVerify` and recomputed on remove/merge, with a backfill migration. It survives restarts and multiple instances, but has no TTL safety net against drift.

**Verify:**
- `EXPLAIN SELECT s.round_id, COUNT(DISTINCT s.page_id) FROM selection s WHERE s.round_id IN (1,2) GROUP BY s.round_id;` should use `idx_selection_round_page`, `Using index`, possibly with "Using index for group-by".
- Gatling "rounds list" p95 under 50 ms in the default flow; the stress-mode mean drops from 8 s to tens of ms.
- `ImageJdbc.scala:201` disappears from the slow-query warnings.

---

## Task 6: `User.findByRoundSelection`: loose index scan instead of reading the whole round

**Background:**
- It joins all of a round's `selection` rows (about 383k) to `users` with GROUP BY (0.3–1 s, inferred).
- It runs twice per edit page, in `distributeNewImages`, and in the organizer gallery's CSV and filelist views.

```diff
-  def findByRoundSelection(roundId: Long): Seq[User] = withSQL {
-    import SelectionJdbc.s
-    select(u.result.*).from(User as u).join(SelectionJdbc as s).on(u.id, s.juryId)
-      .where.eq(s.roundId, roundId).groupBy(u.id).orderBy(u.id)
-  }.map(User(u)).list()
+  /** DISTINCT jury_id over idx_selection_round_jury_rate is a loose index scan
+    * (one dive per juror) instead of reading every selection row of the round. */
+  def findByRoundSelection(roundId: Long): Seq[User] = {
+    val ids = sql"SELECT DISTINCT jury_id FROM selection WHERE round_id = $roundId".map(_.long(1)).list()
+    if (ids.isEmpty) Nil else findAllBy(sqls.in(u.id, ids)).sortBy(_.id)
+  }
```

**Semantics are unchanged:** the same users, ordered by id, with no `deleted_at` filter.
- Do **not** filter by `users.contest_id`: a user's single `contest_id` can move to a later contest.
- Do **not** switch to `round_user`: it is not guaranteed to match `selection` for legacy rounds.

**Verify:**
- `EXPLAIN SELECT DISTINCT jury_id FROM selection WHERE round_id = 1;` should show `idx_selection_round_jury_rate`, "Using index for group-by", and rows ≈ the number of jurors.
- Existing tests for juror lists and distribution pass.

---

## Task 7: Edit round page: stop doing work it doesn't need on every view (biggest logical win)

**Background:** `GET /admin/rounds/edit?id=X` runs about 17 queries, an estimated 4–9 s per view for a binary round **(inferred)**:
- `Round.findByContest` 3 times;
- `User.findByRoundSelection` twice;
- `ContestJuryJdbc.findById` 3 times;
- the full `getRoundStat` (`roundUserStat`, `roundRateStat`, COUNT DISTINCT);
- `distributeImages.imagesByRound`, only to print `newImages.size`. That is `byRoundMerged` over 383k selection rows with a STRAIGHT_JOIN to `images`, building about 38k `ImageWithRating` objects (tens of MB). For rounds with a category, it also makes blocking Commons API calls (`categoryFileIds`, `.await(5.minutes)`) on a Play request thread.

The new-files count is informational only: `saveRound` with `newImages` recomputes `imagesByRound` inside `distributeAndVerify`. Removing it from GET changes no outcome. (The checkbox's `readonly` never blocked submission anyway.)

**`RoundController`:**

```diff
   def editRound(roundId: Option[Long], contestId: Long, topImages: Option[Int]): EssentialAction =
     withAuth(contestPermission(User.ADMIN_ROLES, Some(contestId))) { user => implicit request =>
       val rounds = Round.findByContest(contestId)
-      val round: Round = roundId.flatMap(Round.findById)
-        .getOrElse(new Round(id = None, contestId = contestId, number = rounds.size + 1))
+      // reuse the list, and require the round to belong to the permission-checked contest
+      val round: Round = roundId.flatMap(id => rounds.find(_.id.contains(id)))
+        .getOrElse(new Round(id = None, contestId = contestId, number = rounds.size + 1))
       val withTopImages = topImages.map(n => round.copy(topImages = Some(n))).getOrElse(round)
       val jurors = withTopImages.id.fold(User.loadJurors(contestId))(User.findByRoundSelection).sorted
       val filledRound = editRoundForm.fill(EditRound(withTopImages, jurors.flatMap(_.id), None))
-      Ok(roundFormView(user, withTopImages, filledRound))
+      Ok(roundFormView(user, withTopImages, filledRound, Some(rounds), Some(jurors)))
     }

-  private def roundFormView(user: User, round: Round, form: Form[EditRound])(implicit request: RequestHeader): Html = {
+  private def roundFormView(user: User, round: Round, form: Form[EditRound],
+      knownRounds: Option[Seq[Round]] = None, knownJurors: Option[Seq[User]] = None)(
+      implicit request: RequestHeader): Html = {
     val contestId = round.contestId
-    val prevRounds = round.previousIds.flatMap(Round.findById)
     views.html.editRound(
-      user, form, round.id.isEmpty, Round.findByContest(contestId), Some(contestId),
-      round.id.fold(User.loadJurors(contestId))(User.findByRoundSelection).sorted,
+      user, form, round.id.isEmpty, knownRounds.getOrElse(Round.findByContest(contestId)), Some(contestId),
+      knownJurors.getOrElse(round.id.fold(User.loadJurors(contestId))(User.findByRoundSelection).sorted),
       jurorsMapping,
       contestsController.regions(contestId),
-      round.id.map(id => roundsService.getRoundStat(id, round)),
-      round.id.map(_ => distributeImages.imagesByRound(round, prevRounds)).getOrElse(Nil),
-      contestSpecialNominations(contestId)
+      statOpt = None,                                   // lazy, see roundStatTable below
+      specialNominations = contestSpecialNominations(contestId)
     )
   }
+
+  /** On demand ("Count new files" button): the expensive filter run, off the GET path. */
+  def newFilesCount(id: Long): EssentialAction =
+    withAuth(roundPermission(User.ADMIN_ROLES, id)) { _ => _ =>
+      Round.findById(id).fold(NotFound(Json.obj("error" -> "round not found"))) { round =>
+        Ok(Json.obj("count" -> distributeImages.imagesByRound(round, round.previousIds.flatMap(Round.findById)).size))
+      }
+    }
+
+  /** Juror stat table fragment, loaded when the "jurors" panel is expanded. */
+  def roundStatTable(roundId: Long): EssentialAction =
+    withAuth(roundPermission(User.ADMIN_ROLES, roundId)) { user => implicit request =>
+      Round.findById(roundId).fold(NotFound("")) { round =>
+        Ok(views.html.roundStatTable(user, round, roundsService.getRoundStat(roundId, round)))
+      }
+    }
```

**`conf/routes`:**

```
GET   /admin/rounds/newfiles     @controllers.RoundController.newFilesCount(id: Long)
GET   /roundstat/:round/table    @controllers.RoundController.roundStatTable(round: Long)
```

**`app/views/editRound.scala.html`:**

```diff
-        statOpt: Option[RoundStat] = None,
-        newImages: Seq[Image] = Nil,
+        statOpt: Option[RoundStat] = None,
         specialNominations: Seq[SpecialNomination] = Nil
 ...
                             @snippets.collapsible("jurors", "headingJurors", "collapseJurors") {
-                                @statOpt.map { stat => @roundStatTable(user, stat.round, stat) }
+                                <div id="round-stat-table"
+                                     data-src="/roundstat/@editRoundForm("id").value/table">…</div>
                             }
 ...
                                 @b3.checkbox(field = editRoundForm("newImages"),
-                                    Symbol("_label") -> Messages("distribute.x.new.files", newImages.size),
-                                    Symbol("readonly") -> (numJurors == 0 || newImages.isEmpty))
+                                    Symbol("_label") -> Messages("distribute.x.new.files", "?"),
+                                    Symbol("readonly") -> (numJurors == 0))
+                                <button type="button" class="btn btn-default btn-xs" id="count-new-files"
+                                  data-src="/admin/rounds/newfiles?id=@editRoundForm("id").value">Count</button>
+                                <span id="new-files-count"></span>
+<script>
+$(function () {
+  $('#collapseJurors').one('show.bs.collapse', function () {
+    var el = $('#round-stat-table'); el.load(el.data('src'));
+  });
+  $('#count-new-files').on('click', function () {
+    var b = $(this).prop('disabled', true);
+    $.getJSON(b.data('src')).done(function (r) { $('#new-files-count').text(r.count); })
+      .always(function () { b.prop('disabled', false); });
+  });
+});
+</script>
```

Use the project's existing script conventions; check whether inline scripts are allowed by the CSP and how other views load fragments.

- **Expected:** the edit page drops to about 6 small queries, under 50 ms **(inferred)**. No Commons HTTP calls and no 38k-object allocation on GET.
- **Risks:**
  - Without JS, the stat table no longer shows on the edit page; it is still at `/roundstat/:id`.
  - `editRound` now refuses a round id from another contest and falls back to the new-round form. That is an authorization fix; see the side issues.
  - `newFilesCount` still blocks; run it on `blocking-dispatcher` (Task 3).

**Verify:**
- Gatling "edit round" (Task 2) mean drops from seconds to under 100 ms.
- Add a `GatlingSmokeSpec` case: `GET /admin/rounds/edit?id=<binary round>&contestId=…` ≤ 1 s.
- Measure the removed work: `ANALYZE SELECT sum(s.rate), count(s.rate), i.* FROM selection s STRAIGHT_JOIN images i ON i.page_id = s.page_id WHERE s.round_id = 1 GROUP BY s.page_id ORDER BY 1 DESC;` (look at `r_rows`, `r_total_time_ms`).

---

## Task 8: Bug: `updateMonumentId` leaves `selection.monument_id` stale (correctness, do before Task 9)

**Background:**
- `ImageJdbc.updateMonumentId` (called from `ImageService:67`) updates `images.monument_id` but not the denormalized `selection.monument_id` (added in V48).
- The gallery ORDER BY and `byRegionStat` already read `s.monument_id`, so they can drift today.
- Task 9 makes the region filter depend on that column too.

**Change:** in the same transaction, also run the following. It uses the unique `(page_id, jury_id, round_id)` index.

```sql
UPDATE selection SET monument_id = ? WHERE page_id = ?
```

**Verify:** add a spec. Update an image's monument id, then check that its `selection` rows and the region gallery follow.

---

## Task 9: Juror gallery list, count and region filter (queries #3 / #3b)

**Background (inferred, confirm with ANALYZE first):**
- V48 assumed its `(jury_id, round_id, rate, monument_id, page_id)` index gives an index-only sort for `ORDER BY rate DESC, s.monument_id ASC, s.page_id ASC`. **On MariaDB 10.6 it does not.** A mixed-direction ORDER BY cannot be read from an ascending index, and descending index keys only exist from 10.8 (10.6 parses `DESC` and ignores it).
- So every gallery page filesorts all of the juror's ~19k rows, with a full-row lookup per row because it selects `s.*`.
- The region filter is on `i.monument_id`, which adds about 19k `images` lookups. `s.monument_id` was denormalized precisely to avoid that.

**Fix A: deferred join, same ordering.** Sort only the narrow index entries, then fetch 15 full rows. Every InnoDB secondary index includes `s.id`, so the inner query is index-only.

```sql
SELECT <i.result.*>, <s.result.*>
FROM (SELECT s.id FROM selection s
      WHERE s.jury_id = ? AND s.round_id = ? [AND s.monument_id LIKE 'NN%']
      ORDER BY s.rate DESC, s.monument_id ASC, s.page_id ASC
      LIMIT 15 OFFSET ?) k
JOIN selection s ON s.id = k.id
STRAIGHT_JOIN images i ON i.page_id = s.page_id
ORDER BY s.rate DESC, s.monument_id ASC, s.page_id ASC
```

**Implementing Fix A in `ImageDbNew.query()`:**
- Use this path when all of these hold: `!count && !idOnly && !byRegion && !grouped && !noLimit && limit.isDefined && !needsMonumentJoin`.
- Build the inner query as `select s.id from selection s ${where()} ${orderBy()} ${limitSql()}`.
- In `where()`, change the single-prefix region branch to the denormalized column, after Task 8:

```diff
-            sqls"i.monument_id like $likeParam"
+            sqls"s.monument_id like $likeParam"
```

**Fix B: page count.** When the juror is fixed, the unique `(page_id, jury_id, round_id)` index makes DISTINCT redundant.

```diff
-        val countExpr = SQLSyntax.createUnsafely("COUNT(DISTINCT s.page_id)")
+        val countExpr = SQLSyntax.createUnsafely(if (userId.isDefined) "COUNT(*)" else "COUNT(DISTINCT s.page_id)")
```

With a region filter, the count can then be computed on `selection` alone, with no join to `images`.

**Alternatives:**
- **Cheaper, but a product decision:** order everything descending (`rate DESC, s.monument_id DESC, s.page_id DESC`). The index is then read backwards with no filesort, but monuments with the same rate appear in reverse order.
- **On MariaDB 10.8 or later:** a `(jury_id, round_id, rate DESC, monument_id, page_id)` index solves it with no rewrite.

**Expected:** the list query drops from hundreds of ms (about 1 s under load) to tens of ms **(inferred)**.

**Verify:**
- `ANALYZE FORMAT=JSON` on the current query (e.g. `jury_id=12, round_id=2, LIMIT 15 OFFSET 60`) should show a filesort on `s` over about 19k rows.
- The new inner query should show `Using where; Using index; Using filesort`, and the outer join reads 15 rows.
- `ImageDbNewDbSpec` (ordering) and `GatlingSmokeSpec` pass.
- JurorGallery "gallery page" and RegionFilter "region gallery page" mean and p95 drop (now 2.3–4.1 s).

**Also on every gallery page (optional, low priority):** `byRegionStat` runs `SELECT DISTINCT m.adm0 FROM selection s JOIN monument m …`. That is about 19k monument lookups per juror page, and about 383k for an organizer.
- `adm0` is `monument_id.split("-").head.take(3)` (`MonumentJdbc.toBatchParams`). So, for the non-`subRegions` case, this equivalent is index-only:
  ```sql
  SELECT DISTINCT LEFT(SUBSTRING_INDEX(s.monument_id,'-',1),3) FROM selection s
  WHERE s.round_id = ? [AND s.jury_id = ?] AND s.monument_id IS NOT NULL
  ```
- It can also return prefixes of ids missing from `monument`. Filter those in Scala against the known regions (`RegionStatSpec` covers this).
- Alternatively, cache the result per (round, juror).

---

## Task 10: Drop redundant `selection` indexes (write cost and buffer pool on the 5 GB server)

**Background:** the four V7 single-column indexes are redundant:
- `round_id` leads 5 other indexes;
- `jury_id` is the V48 prefix;
- `page_id` is the unique-index prefix;
- `rate` is never filtered without `round_id`.

`selection` has no foreign keys that need them. Dropping them means:
- a vote updates 3 index entries instead of 4;
- image distribution writes 6 indexes instead of 10;
- `selection` and its indexes shrink by roughly 20–25% (about 370 MB in the fixture).

**First, on production:** enable `userstat=1` for a few days, then check `information_schema.INDEX_STATISTICS` to confirm these indexes are unused.

**Migration:**

```sql
ALTER TABLE selection
  DROP INDEX IF EXISTS selection_rate_index,
  DROP INDEX IF EXISTS selection_round_index,
  DROP INDEX IF EXISTS selection_jury_id_index,
  DROP INDEX IF EXISTS selection_page_id_index;
ANALYZE TABLE selection;
```

Also: V51's `monument_id_unique(id)` duplicates `monument`'s primary key.

**Verify:**
- Re-run the EXPLAINs from Tasks 4–9.
- Voting "cast vote" p95/p99 should not get worse.
- Every new migration changes the Gatling DB-dump cache key, so the first run after it rebuilds the fixture.

---

## Side issues found (not performance; triage separately)

All inferred from reading the code:

- **Authorization:** `saveRound` checks only the role, not that the round belongs to the organizer's contest. `editRound` loads any round id with `Round.findById` (Task 7 fixes the edit page; `saveRound` still needs a contest check).
- **Behaviour:** `saveRound` sets `active = true` on every edit (`editForm.round.copy(active = true)`), re-activating a round whenever it is saved.
- **Votes on inactive rounds:** `selectWS` (`POST /rate/...`) does not check `round.active`.
- **Dead code:** `RoundService.mergeRounds` and `LargeViewController.removeImage` have no routes, and `Round.delete` is unused. Keep the Task 5 invalidation hooks anyway, in case they get wired up.
- **Dependencies:** the deployed jury tool still bundles the old ChronicleMap jars (`net.openhft.*`) through its scalawiki dependency. Upgrading scalawiki (which replaced ChronicleMap with a plain file cache) removes them.

---

## Verification checklist after implementation

1. Task 2 benchmark, default flow: 0 KO, no redirect "OK"s, about 0.8 req/s.
1b. Task 2b: RoundDistribution runs first in `sbt gatlingAll`, distributes the expected number of images in both paths (contest category, previous round), and passes its memory criteria with the Task 1 JVM flags.
2. RoundManagement and AggregatedRatings in `stress=true` mode, before and after Tasks 3–7: "rounds list", "round stats" and "login organizer" means and p95; no 60 s timeouts.
3. JurorGallery and RegionFilter before and after Task 9.
4. The ScalikeJDBC warnings (> 1 s) in the sbt log: `Round.scala:358`, `ImageJdbc.scala:201` and `ImageDbNew.scala:102` should be gone.
5. The EXPLAIN / ANALYZE statements listed in each task, on the fixture DB, with no benchmark running.
6. Production, after Task 1 and deployment: jury tool RSS, `VM.native_memory summary`, and the MariaDB slow log.
