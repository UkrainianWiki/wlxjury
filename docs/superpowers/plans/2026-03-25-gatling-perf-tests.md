# Gatling DB Performance Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire up five Gatling simulations that spin up a MariaDB testcontainer + Play TestServer, load real contest data from CSVs, and produce HTML reports proving that V45 indexes improve performance.

**Architecture:** A singleton Scala `object GatlingTestFixture` starts the container and TestServer on first reference, loads fixture data via existing ScalikeJDBC DAOs, then exposes IDs/credentials to all five simulation classes. The JVM shutdown hook stops everything cleanly. No sbt lifecycle changes needed.

**Tech Stack:** Gatling 3.11.5 (Scala DSL), gatling-sbt 4.9.6, testcontainers-scala (MariaDB), Play TestServer, ScalikeJDBC DAOs, scala-csv 1.4.1.

---

## File Layout

```
project/plugins.sbt                            ← add gatling-sbt plugin
build.sbt                                      ← add GatlingPlugin, source dir, deps, JVM opts
test/resources/application.conf               ← add CSRF filter disable
test/resources/gatling-perf.conf              ← create: defaults for load shape
test/gatling/
  setup/
    GatlingConfig.scala                        ← reads gatling-perf.conf + -D overrides
    GatlingDbSetup.scala                       ← CSV parsing + DAO calls; returns GatlingFixtureData
    GatlingTestFixture.scala                   ← singleton object: container + TestServer + data
  simulations/
    JurorGallerySimulation.scala
    RegionFilterSimulation.scala
    AggregatedRatingsSimulation.scala
    VotingSimulation.scala
    RoundManagementSimulation.scala
conf/db/migration/default/
  V45__Add_performance_indexes.sql             ← applied between baseline and optimized runs
```

---

### Task 1: Gatling sbt wiring

**Files:**
- Modify: `project/plugins.sbt`
- Modify: `build.sbt`
- Modify: `test/resources/application.conf`
- Create: `test/resources/gatling-perf.conf`

- [ ] **Step 1: Add the gatling-sbt plugin**

Append to `project/plugins.sbt`:

```scala
addSbtPlugin("io.gatling" % "gatling-sbt" % "4.9.6")
```

- [ ] **Step 2: Configure Gatling in build.sbt**

In `build.sbt`, add `GatlingPlugin` to the root project's `enablePlugins` list alongside the existing plugins, then add the Gatling settings block. Find the line `.enablePlugins(PlayScala, PlayNettyServer, DebianPlugin, SystemdPlugin, JavaServerAppPackaging, JmhPlugin)` and add `GatlingPlugin`:

```scala
.enablePlugins(
  PlayScala,
  PlayNettyServer,
  DebianPlugin,
  SystemdPlugin,
  JavaServerAppPackaging,
  JmhPlugin,
  GatlingPlugin
)
```

Then add these settings inside `.settings(...)` after the existing JMH settings block:

```scala
// Gatling sources live in test/gatling/ — separate from JMH sources in test/
Gatling / scalaSource     := (Test / sourceDirectory).value / "gatling",
Gatling / javaOptions     += "-Dconfig.file=test/resources/application.conf",
// Forward -Dgatling.* overrides to Gatling JVM
Gatling / javaOptions     ++= sys.props.toSeq.collect {
  case (k, v) if k.startsWith("gatling.") => s"-D$k=$v"
},
```

Then add Gatling library dependencies to `libraryDependencies`:

```scala
"io.gatling.highcharts" % "gatling-charts-highcharts" % "3.11.5" % "gatling",
"io.gatling"            % "gatling-test-framework"     % "3.11.5" % "gatling",
```

- [ ] **Step 3: Disable CSRF filter for test app**

In `test/resources/application.conf`, append:

```
# Disable CSRF for Gatling HTTP load tests — existing DB tests don't use HTTP
play.filters.disabled += "play.filters.csrf.CSRFFilter"
```

- [ ] **Step 4: Create gatling-perf.conf**

Create `test/resources/gatling-perf.conf`:

```hocon
gatling {
  users           = 20
  rampUpSeconds   = 10
  durationSeconds = 60
  jurorFraction   = 0.5
  maxRate         = 10
}
```

- [ ] **Step 5: Verify compilation**

```bash
sbt Gatling/compile
```

Expected: `[success]` (no sources yet, that's fine — just confirm the plugin loads and deps resolve)

- [ ] **Step 6: Commit**

```bash
git add project/plugins.sbt build.sbt test/resources/application.conf test/resources/gatling-perf.conf
git commit -m "build: wire Gatling sbt plugin, source dir, and dependencies"
```

---

### Task 2: GatlingConfig

**Files:**
- Create: `test/gatling/setup/GatlingConfig.scala`

- [ ] **Step 1: Create GatlingConfig**

```scala
package gatling.setup

import com.typesafe.config.ConfigFactory

import java.io.File

object GatlingConfig {
  private val cfg =
    ConfigFactory.systemProperties()
      .withFallback(ConfigFactory.parseFile(new File("test/resources/gatling-perf.conf")))
      .resolve()

  val users: Int           = cfg.getInt("gatling.users")
  val rampUpSeconds: Int   = cfg.getInt("gatling.rampUpSeconds")
  val durationSeconds: Int = cfg.getInt("gatling.durationSeconds")
  val jurorFraction: Double= cfg.getDouble("gatling.jurorFraction")
  val maxRate: Int         = cfg.getInt("gatling.maxRate")
}
```

- [ ] **Step 2: Compile**

```bash
sbt Gatling/compile
```

Expected: `[success]`

- [ ] **Step 3: Commit**

```bash
git add test/gatling/setup/GatlingConfig.scala
git commit -m "feat: add GatlingConfig reading gatling-perf.conf with -D override support"
```

---

### Task 3: GatlingDbSetup

**Files:**
- Create: `test/gatling/setup/GatlingDbSetup.scala`

This object parses the two CSV files, inserts all fixture data via existing ScalikeJDBC DAOs, and returns a `GatlingFixtureData` case class. It must be called only after the Play TestServer has started (so ScalikeJDBC is initialized).

- [ ] **Step 1: Create GatlingDbSetup.scala**

```scala
package gatling.setup

import com.github.tototoshi.csv.CSVReader
import db.scalikejdbc._
import org.intracer.wmua.{Image, Selection}
import org.scalawiki.wlx.dto.Monument
import scalikejdbc._

import java.io.File
import scala.util.Random

case class GatlingFixtureData(
    port: Int,
    contestId: Long,
    roundBinaryId: Long,
    roundRatingId: Long,
    jurors: Seq[(Long, String, String)],    // (id, email, plainPassword)
    organizer: (Long, String, String),       // (id, email, plainPassword)
    imagePageIds: Seq[Long],
    regions: Seq[String],
    votingPairs: Seq[(Long, Long, Long, Int)] // (juryId, pageId, roundId, rate)
)

object GatlingDbSetup {

  def load(port: Int, cfg: GatlingConfig.type): GatlingFixtureData = {
    val numUsers    = cfg.users
    val fraction    = cfg.jurorFraction
    val maxRate     = cfg.maxRate
    val rng         = new Random(42)

    // 1 ── Load monuments from CSV
    val monumentRows = parseCsv("data/wlm-ua-monuments.csv")
    val monuments = monumentRows.map { row =>
      new Monument(
        id          = row("id"),
        name        = row("name"),
        lat         = row.get("lat"),
        lon         = row.get("lon"),
        typ         = row.get("type"),
        year        = row.get("year_of_construction"),
        city        = row.get("municipality"),
        page        = row("id")   // use id as page identifier
      )
    }
    monuments.grouped(1000).foreach(MonumentJdbc.batchInsert)

    // 2 ── Load images from CSV
    val imageRows = parseCsv("data/wlm-UA-images-2025.csv")
    val images = imageRows.flatMap { row =>
      row.get("page_id").filter(_.nonEmpty).map { pid =>
        Image(
          pageId      = pid.toLong,
          title       = row.getOrElse("title", ""),
          url         = row.get("url").filter(_.nonEmpty),
          pageUrl     = row.get("page_url").filter(_.nonEmpty),
          width       = row.get("width").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(0),
          height      = row.get("height").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(0),
          monumentId  = row.get("monument_id").filter(_.nonEmpty),
          size        = row.get("size_bytes").flatMap(s => scala.util.Try(s.toInt).toOption),
          mime        = row.get("mime").filter(_.nonEmpty)
        )
      }
    }
    images.grouped(1000).foreach(ImageJdbc.batchInsert)
    val imagePageIds = images.map(_.pageId)

    // 3 ── Contest
    val contest = ContestJuryJdbc.create(
      id      = None,
      name    = "Gatling Perf Test Contest",
      year    = 2024,
      country = "ua"
    )
    val contestId = contest.id.get

    // 4 ── Jurors
    val jurors = (1 to numUsers).map { i =>
      val email    = s"juror$i@gatling.test"
      val password = s"pass$i"
      val hashed   = User.sha1(password)
      val user = User.create(
        fullname  = s"Juror $i",
        email     = email,
        password  = hashed,
        roles     = Set("jury"),
        contestId = Some(contestId)
      )
      (user.id.get, email, password)
    }

    // 5 ── Organizer
    val orgEmail    = "organizer@gatling.test"
    val orgPassword = "orgpass"
    val orgUser = User.create(
      fullname  = "Organizer",
      email     = orgEmail,
      password  = User.sha1(orgPassword),
      roles     = Set("organizer"),
      contestId = Some(contestId)
    )
    val organizer = (orgUser.id.get, orgEmail, orgPassword)

    // 6 ── Rounds
    val binaryRound = Round.create(Round(
      id        = None,
      number    = 1,
      name      = Some("Binary Round"),
      contestId = contestId,
      rates     = Round.binaryRound,
      active    = true
    ))
    val ratingRound = Round.create(Round(
      id        = None,
      number    = 2,
      name      = Some("Rating Round"),
      contestId = contestId,
      rates     = Round.rateRounds.find(_.id == maxRate).getOrElse(Round.rateRounds.last),
      active    = true
    ))

    // 7 ── Add jurors to both rounds (required for activeRounds(user) EXISTS check)
    // Note: Round.addUsers always uses the receiver round's id, ignoring ru.roundId.
    val jurorUsers = jurors.map { case (id, _, _) =>
      RoundUser(roundId = binaryRound.id.get, userId = id, role = "jury", active = true)
    }
    binaryRound.addUsers(jurorUsers)
    ratingRound.addUsers(jurorUsers)  // must call on ratingRound — receiver's id is used

    // 8 ── Generate selections
    val numJurors = jurors.length
    val jurorIds  = jurors.map(_._1)

    val binarySelections = for {
      pageId <- imagePageIds
      chosen  = rng.shuffle(jurorIds).take(math.round(numJurors * fraction).toInt)
      juryId <- chosen
    } yield {
      val rate = if (rng.nextBoolean()) 1 else -1
      Selection(pageId = pageId, juryId = juryId, roundId = binaryRound.id.get, rate = rate)
    }

    val ratingSelections = for {
      pageId <- imagePageIds
      chosen  = rng.shuffle(jurorIds).take(math.round(numJurors * fraction).toInt)
      juryId <- chosen
    } yield {
      val rate = rng.nextInt(maxRate) + 1
      Selection(pageId = pageId, juryId = juryId, roundId = ratingRound.id.get, rate = rate)
    }

    val allSelections = binarySelections ++ ratingSelections
    allSelections.grouped(5000).foreach(SelectionJdbc.batchInsert)

    // voting feeder: at most 50k pairs to keep memory reasonable
    val votingPairs = allSelections
      .map(s => (s.juryId, s.pageId, s.roundId, s.rate))
      .take(50000)

    // 9 ── Regions derived from monument adm0 (set by batchInsert)
    val regions = DB readOnly { implicit session =>
      sql"SELECT DISTINCT adm0 FROM monument WHERE adm0 IS NOT NULL"
        .map(_.string("adm0")).list()
    }

    GatlingFixtureData(
      port          = port,
      contestId     = contestId,
      roundBinaryId = binaryRound.id.get,
      roundRatingId = ratingRound.id.get,
      jurors        = jurors,
      organizer     = organizer,
      imagePageIds  = imagePageIds,
      regions       = regions,
      votingPairs   = votingPairs
    )
  }

  private def parseCsv(path: String): List[Map[String, String]] = {
    val reader = CSVReader.open(new File(path))
    try reader.allWithHeaders()
    finally reader.close()
  }
}
```

- [ ] **Step 2: Compile**

```bash
sbt Gatling/compile
```

Expected: `[success]`. Fix any import or API errors before continuing.

- [ ] **Step 3: Commit**

```bash
git add test/gatling/setup/GatlingDbSetup.scala
git commit -m "feat: add GatlingDbSetup — CSV parsing and DAO-based fixture loading"
```

---

### Task 4: GatlingTestFixture

**Files:**
- Create: `test/gatling/setup/GatlingTestFixture.scala`

This singleton initializes exactly once when first accessed (Scala object semantics). All five simulations reference `GatlingTestFixture.port` in their `setUp` blocks, triggering initialization before any scenario runs.

- [ ] **Step 1: Create GatlingTestFixture.scala**

```scala
package gatling.setup

import com.dimafeng.testcontainers.MariaDBContainer
import org.testcontainers.utility.DockerImageName
import play.api.test.TestServer
import play.api.inject.guice.GuiceApplicationBuilder

import java.net.ServerSocket

object GatlingTestFixture {

  private val data: GatlingFixtureData = init()

  private def init(): GatlingFixtureData = {
    val container = MariaDBContainer(
      dockerImageName = DockerImageName.parse("mariadb:10.6.22"),
      dbName          = "wlxjury",
      dbUsername      = "WLXJURY_DB_USER",
      dbPassword      = "WLXJURY_DB_PASSWORD"
    )
    container.start()

    val port = freePort()
    val app  = new GuiceApplicationBuilder()
      .configure(Map[String, Any](
        "db.default.driver"   -> container.driverClassName,
        "db.default.username" -> container.username,
        "db.default.password" -> container.password,
        "db.default.url"      -> container.jdbcUrl
      ))
      .build()
    val server = TestServer(port, app)
    server.start()

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      try server.stop()  finally container.stop()
    }))

    GatlingDbSetup.load(port, GatlingConfig)
  }

  private def freePort(): Int = {
    val s = new ServerSocket(0)
    try s.getLocalPort finally s.close()
  }

  val port: Int          = data.port
  val contestId: Long    = data.contestId
  val roundBinaryId: Long= data.roundBinaryId
  val roundRatingId: Long= data.roundRatingId
  val jurors: Seq[(Long, String, String)] = data.jurors      // (id, email, plainPassword)
  val organizer: (Long, String, String)   = data.organizer
  val imagePageIds: Seq[Long]             = data.imagePageIds
  val regions: Seq[String]                = data.regions
  val votingPairs: Seq[(Long, Long, Long, Int)] = data.votingPairs
}
```

- [ ] **Step 2: Compile**

```bash
sbt Gatling/compile
```

Expected: `[success]`

- [ ] **Step 3: Commit**

```bash
git add test/gatling/setup/GatlingTestFixture.scala
git commit -m "feat: add GatlingTestFixture — container + TestServer + shutdown hook"
```

---

### Task 5: JurorGallerySimulation

**Files:**
- Create: `test/gatling/simulations/JurorGallerySimulation.scala`

Exercises `GET /gallery/round/:round/user/:user/page/:page`. Each virtual user logs in once as a juror, then repeatedly browses gallery pages alternating between binary and rating rounds.

- [ ] **Step 1: Create JurorGallerySimulation.scala**

```scala
package gatling.simulations

import gatling.setup.GatlingTestFixture
import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._
import scala.util.Random

class JurorGallerySimulation extends Simulation {

  private val baseUrl = s"http://localhost:${GatlingTestFixture.port}"
  private val cfg     = gatling.setup.GatlingConfig

  private val rounds = Seq(GatlingTestFixture.roundBinaryId, GatlingTestFixture.roundRatingId)

  // Each virtual user gets one juror credential
  private val jurorFeeder = Iterator.continually(GatlingTestFixture.jurors).flatten.map {
    case (id, email, pass) =>
      Map("userId" -> id.toString, "email" -> email, "password" -> pass)
  }

  private val scn = scenario("Juror Gallery")
    .feed(jurorFeeder)
    .exec(http("login")
      .post("/auth")
      .formParam("login", "#{email}")
      .formParam("password", "#{password}")
      .check(status.in(200, 303)))
    .repeat(10) {
      exec { session =>
        val round = rounds(Random.nextInt(rounds.length))
        val page  = Random.nextInt(5) + 1
        session
          .set("roundId", round)
          .set("page", page)
      }
        .exec(http("gallery page")
          .get("/gallery/round/#{roundId}/user/#{userId}/page/#{page}")
          .check(status.is(200)))
    }

  setUp(
    scn.inject(
      rampUsers(cfg.users).during(cfg.rampUpSeconds.seconds),
      constantUsersPerSec(cfg.users.toDouble / 10).during(cfg.durationSeconds.seconds)
    )
  ).protocols(http.baseUrl(baseUrl))
}
```

- [ ] **Step 2: Compile**

```bash
sbt Gatling/compile
```

Expected: `[success]`

- [ ] **Step 3: Commit**

```bash
git add test/gatling/simulations/JurorGallerySimulation.scala
git commit -m "feat: add JurorGallerySimulation exercising idx_selection_jury_round"
```

---

### Task 6: RegionFilterSimulation

**Files:**
- Create: `test/gatling/simulations/RegionFilterSimulation.scala`

Exercises `GET /gallery/round/:round/user/:user/region/:region/page/:page`. Same login pattern; cycles through `(jurorId, roundId, region, page)` combinations.

- [ ] **Step 1: Create RegionFilterSimulation.scala**

```scala
package gatling.simulations

import gatling.setup.GatlingTestFixture
import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._
import scala.util.Random

class RegionFilterSimulation extends Simulation {

  private val baseUrl = s"http://localhost:${GatlingTestFixture.port}"
  private val cfg     = gatling.setup.GatlingConfig

  private val rounds  = Seq(GatlingTestFixture.roundBinaryId, GatlingTestFixture.roundRatingId)
  private val regions = GatlingTestFixture.regions

  private val jurorFeeder = Iterator.continually(GatlingTestFixture.jurors).flatten.map {
    case (id, email, pass) =>
      Map("userId" -> id.toString, "email" -> email, "password" -> pass)
  }

  private val scn = scenario("Region Filter Gallery")
    .feed(jurorFeeder)
    .exec(http("login")
      .post("/auth")
      .formParam("login", "#{email}")
      .formParam("password", "#{password}")
      .check(status.in(200, 303)))
    .repeat(10) {
      exec { session =>
        val round  = rounds(Random.nextInt(rounds.length))
        val region = regions(Random.nextInt(regions.length))
        val page   = Random.nextInt(3) + 1
        session
          .set("roundId", round)
          .set("region", region)
          .set("page", page)
      }
        .exec(http("region gallery page")
          .get("/gallery/round/#{roundId}/user/#{userId}/region/#{region}/page/#{page}")
          .check(status.is(200)))
    }

  setUp(
    scn.inject(
      rampUsers(cfg.users).during(cfg.rampUpSeconds.seconds),
      constantUsersPerSec(cfg.users.toDouble / 10).during(cfg.durationSeconds.seconds)
    )
  ).protocols(http.baseUrl(baseUrl))
}
```

- [ ] **Step 2: Compile**

```bash
sbt Gatling/compile
```

Expected: `[success]`

- [ ] **Step 3: Commit**

```bash
git add test/gatling/simulations/RegionFilterSimulation.scala
git commit -m "feat: add RegionFilterSimulation exercising adm0 monument region filter"
```

---

### Task 7: AggregatedRatingsSimulation

**Files:**
- Create: `test/gatling/simulations/AggregatedRatingsSimulation.scala`

Exercises `GET /roundstat/:round`. Organizer session alternates between binary and rating rounds.

- [ ] **Step 1: Create AggregatedRatingsSimulation.scala**

```scala
package gatling.simulations

import gatling.setup.GatlingTestFixture
import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._
import scala.util.Random

class AggregatedRatingsSimulation extends Simulation {

  private val baseUrl = s"http://localhost:${GatlingTestFixture.port}"
  private val cfg     = gatling.setup.GatlingConfig

  private val rounds  = Seq(GatlingTestFixture.roundBinaryId, GatlingTestFixture.roundRatingId)
  private val (_, orgEmail, orgPassword) = GatlingTestFixture.organizer

  private val scn = scenario("Aggregated Ratings")
    .exec(http("login organizer")
      .post("/auth")
      .formParam("login", orgEmail)
      .formParam("password", orgPassword)
      .check(status.in(200, 303)))
    .repeat(20) {
      exec { session =>
        session.set("roundId", rounds(Random.nextInt(rounds.length)))
      }
        .exec(http("round stats")
          .get("/roundstat/#{roundId}")
          .check(status.is(200)))
    }

  setUp(
    scn.inject(
      rampUsers(cfg.users).during(cfg.rampUpSeconds.seconds),
      constantUsersPerSec(cfg.users.toDouble / 10).during(cfg.durationSeconds.seconds)
    )
  ).protocols(http.baseUrl(baseUrl))
}
```

- [ ] **Step 2: Compile**

```bash
sbt Gatling/compile
```

Expected: `[success]`

- [ ] **Step 3: Commit**

```bash
git add test/gatling/simulations/AggregatedRatingsSimulation.scala
git commit -m "feat: add AggregatedRatingsSimulation exercising idx_selection_round_page"
```

---

### Task 8: VotingSimulation

**Files:**
- Create: `test/gatling/simulations/VotingSimulation.scala`

Exercises `POST /rate/round/:round/pageid/:pageId/select/:select`. Uses pre-loaded selection rows (UPDATE path, not INSERT). Each virtual user is a juror who repeatedly updates votes.

- [ ] **Step 1: Create VotingSimulation.scala**

```scala
package gatling.simulations

import gatling.setup.GatlingTestFixture
import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._

class VotingSimulation extends Simulation {

  private val baseUrl = s"http://localhost:${GatlingTestFixture.port}"
  private val cfg     = gatling.setup.GatlingConfig

  // Feeder over pre-existing (juryId, pageId, roundId, rate) tuples
  private val voteFeeder = Iterator.continually(GatlingTestFixture.votingPairs).flatten.map {
    case (juryId, pageId, roundId, rate) =>
      Map(
        "juryId"  -> juryId.toString,
        "pageId"  -> pageId.toString,
        "roundId" -> roundId.toString,
        "rate"    -> rate.toString
      )
  }

  // Each VU logs in as the juror from its feed slot
  private val jurorFeeder = Iterator.continually(GatlingTestFixture.jurors).flatten.map {
    case (id, email, pass) =>
      Map("userId" -> id.toString, "email" -> email, "password" -> pass)
  }

  private val scn = scenario("Voting")
    .feed(jurorFeeder)
    .exec(http("login")
      .post("/auth")
      .formParam("login", "#{email}")
      .formParam("password", "#{password}")
      .check(status.in(200, 303)))
    .repeat(15) {
      feed(voteFeeder)
        .exec(http("cast vote")
          .post("/rate/round/#{roundId}/pageid/#{pageId}/select/#{rate}")
          .check(status.in(200, 303)))
    }

  setUp(
    scn.inject(
      rampUsers(cfg.users).during(cfg.rampUpSeconds.seconds),
      constantUsersPerSec(cfg.users.toDouble / 10).during(cfg.durationSeconds.seconds)
    )
  ).protocols(http.baseUrl(baseUrl))
}
```

- [ ] **Step 2: Compile**

```bash
sbt Gatling/compile
```

Expected: `[success]`

- [ ] **Step 3: Commit**

```bash
git add test/gatling/simulations/VotingSimulation.scala
git commit -m "feat: add VotingSimulation exercising idx_selection_page_jury_round"
```

---

### Task 9: RoundManagementSimulation

**Files:**
- Create: `test/gatling/simulations/RoundManagementSimulation.scala`

Exercises `GET /admin/rounds?contestId=:contestId`. Organizer session. Exercises `idx_rounds_contest_active` and `idx_round_user_round_active`.

- [ ] **Step 1: Create RoundManagementSimulation.scala**

```scala
package gatling.simulations

import gatling.setup.GatlingTestFixture
import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._

class RoundManagementSimulation extends Simulation {

  private val baseUrl = s"http://localhost:${GatlingTestFixture.port}"
  private val cfg     = gatling.setup.GatlingConfig

  private val (_, orgEmail, orgPassword) = GatlingTestFixture.organizer

  private val scn = scenario("Round Management")
    .exec(http("login organizer")
      .post("/auth")
      .formParam("login", orgEmail)
      .formParam("password", orgPassword)
      .check(status.in(200, 303)))
    .repeat(20) {
      exec(http("rounds list")
        .get(s"/admin/rounds?contestId=${GatlingTestFixture.contestId}")
        .check(status.is(200)))
    }

  setUp(
    scn.inject(
      rampUsers(cfg.users).during(cfg.rampUpSeconds.seconds),
      constantUsersPerSec(cfg.users.toDouble / 10).during(cfg.durationSeconds.seconds)
    )
  ).protocols(http.baseUrl(baseUrl))
}
```

- [ ] **Step 2: Compile**

```bash
sbt Gatling/compile
```

Expected: `[success]`

- [ ] **Step 3: Compile all Gatling sources together**

```bash
sbt Gatling/compile
```

Expected: `[success]` with no errors across all five simulations.

- [ ] **Step 4: Commit**

```bash
git add test/gatling/simulations/RoundManagementSimulation.scala
git commit -m "feat: add RoundManagementSimulation exercising idx_rounds_contest_active"
```

---

### Task 10: Baseline run

This establishes the before-indexes baseline. Run it and save the report location.

- [ ] **Step 1: Ensure Docker is running**

```bash
docker info
```

Expected: Docker daemon info without error. If not running: `open -a Docker && sleep 15`

- [ ] **Step 2: Run the full baseline Gatling suite**

```bash
sbt Gatling/test 2>&1 | tee /tmp/gatling-baseline.log
```

This starts MariaDB container, starts Play TestServer, loads CSV data, runs all five simulations, and writes HTML reports to `target/gatling/`. First run takes several minutes for container startup and CSV load.

Expected: 5 simulation directories created under `target/gatling/`, each with `index.html`.

- [ ] **Step 3: Note baseline report paths**

```bash
ls target/gatling/
```

Record the timestamped directory names for each simulation (e.g. `jurorgallery-20260325123456/`). These are your baseline numbers.

- [ ] **Step 4: Commit the V45 migration file created in the next task placeholder**

(No commit here — proceed to Task 11.)

---

### Task 11: V45 performance indexes migration

**Files:**
- Create: `conf/db/migration/default/V45__Add_performance_indexes.sql`

- [ ] **Step 1: Create V45__Add_performance_indexes.sql**

```sql
-- Critical: fixes highest-impact query paths
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

- [ ] **Step 2: Commit**

```bash
git add conf/db/migration/default/V45__Add_performance_indexes.sql
git commit -m "feat: add V45 performance indexes for selection, rounds, round_user, users"
```

---

### Task 12: Optimized run and comparison

Flyway applies V45 automatically on the next `GatlingTestFixture` startup because it runs in a fresh container.

- [ ] **Step 1: Run the optimized Gatling suite**

```bash
sbt Gatling/test 2>&1 | tee /tmp/gatling-optimized.log
```

Expected: 5 new simulation directories created under `target/gatling/`.

- [ ] **Step 2: Compare reports**

```bash
ls -lt target/gatling/ | head -12
```

Open the two sets of HTML reports in a browser (baseline vs optimized). Compare for each simulation:
- Mean response time
- p95 / p99 latency
- Requests/second

Expected improvements per `docs/db-performance-analysis.md`:
- Gallery page (JurorGallerySimulation): 20–40% faster
- Round management (RoundManagementSimulation): 30–50% faster
- Vote submission (VotingSimulation): 15–30% faster

- [ ] **Step 3: Final commit**

```bash
git add .
git commit -m "test: complete Gatling baseline + V45 optimized runs"
```

---

## Troubleshooting

**`NoSuchElementException` on CSV row field:** Check column names with `head -1 data/wlm-ua-monuments.csv`. Use `.get(key)` instead of `(key)` for optional columns.

**`Skipping unavailable simulation`:** Gatling can't find the class. Verify `Gatling / scalaSource` points to `test/gatling/`.

**Login returns 403:** CSRF filter still active. Confirm `play.filters.disabled` line is in `test/resources/application.conf` and the file is on the classpath (`-Dconfig.file=test/resources/application.conf` in `Gatling / javaOptions`).

**`round.addUsers` ignores `ru.roundId`:** `Round.addUsers` always uses the receiver round's own `id` as the DB `round_id`, ignoring the `roundId` field in each `RoundUser`. Call `binaryRound.addUsers(jurorUsers)` and `ratingRound.addUsers(jurorUsers)` as two separate calls.

**Container startup timeout:** Increase Docker memory in Docker Desktop settings (≥ 4 GB recommended for the full CSV load).
