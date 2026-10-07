package gatling.simulations

import gatling.setup.{GatlingConfig, GatlingTestFixture, HeapWatcher}
import io.gatling.core.Predef._
import io.gatling.core.structure.ChainBuilder
import io.gatling.http.Predef._
import scalikejdbc._

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** Many admins creating rounds at the same moment, as after a coordination call of the
  * special-nomination admins, or when several contests' deadlines coincide, while jurors
  * vote and browse their gallery.
  *
  * Each admin submits one round form (POST /admin/rounds/save, which returns at once:
  * the distribution is a background job) and polls the job's status
  * (GET /admin/jobs/:id) until it ends. The mix:
  *  - 2 large first rounds from the contest category (every fixture image, 3 jurors each);
  *  - 4 region rounds: small first rounds, like special nominations (a full scan of the
  *    category, few images pass);
  *  - top 100 and top 500 of the rated round, and the rated round's images averaging 7+;
  *  - "Distribute new files" on the binary round (nothing new: a full scan).
  *
  * Jurors vote and browse in the binary round only: the rounds made from the rated round
  * freeze it (votes there are refused while they run, and its creation closes it).
  *
  * With `soloFirst`, each small job first runs alone, one after another (rounds of the same
  * settings): its time then is the reference for its time among the others.
  *
  * Per admin, the submit-to-done time and the job's figures are appended to
  * target/gatling/concurrent-distribution.txt, with the heap after GC over the concurrent
  * phase.
  */
class ConcurrentRoundCreationSimulation extends Simulation {

  private val baseUrl   = GatlingTestFixture.baseUrl
  private val cfg       = GatlingConfig.Concurrent
  private val contestId = GatlingTestFixture.contestId
  private val binaryId  = GatlingTestFixture.roundBinaryId
  private val ratedId   = GatlingTestFixture.roundRatingId
  private val jurorIds  = GatlingTestFixture.jurorsIn(Some(binaryId)).map(_._1)
  private val admins    = GatlingTestFixture.organizers.take(math.max(cfg.admins, 1))
  private val timeout   = cfg.timeoutMinutes.minutes
  private val reportFile = "concurrent-distribution.txt"

  private implicit val session: DBSession = AutoSession

  // ── the jobs ───────────────────────────────────────────────────────────────

  /** Mid-sized regions: like special nominations, a few hundred images each. */
  private val regions: Seq[(String, Int)] =
    sql"""SELECT SUBSTRING_INDEX(i.monument_id, '-', 1) r, COUNT(*) n FROM images i
          JOIN category_members cm ON cm.page_id = i.page_id
          JOIN contest_jury c ON c.category_id = cm.category_id AND c.id = $contestId
          WHERE i.monument_id IS NOT NULL GROUP BY r ORDER BY n DESC"""
      .map(rs => rs.string(1) -> rs.int(2)).list().slice(6, 10)

  private final case class AdminJob(name: String, small: Boolean, form: Seq[(String, Any)], existingRound: Option[Long] = None)

  private val jurorParams: Seq[(String, Any)] =
    jurorIds.zipWithIndex.map { case (id, i) => s"jurors[$i]" -> id.toString }

  private def newRoundForm(name: String, extra: (String, Any)*): Seq[(String, Any)] =
    Seq[(String, Any)](
      "number" -> "0", "name" -> name, "contest" -> contestId.toString, "roles" -> "jury",
      "distribution" -> cfg.jurorsPerImage.toString, "rates" -> "1",
      "minMpx" -> "", "minSize" -> "", "mediaType" -> "all",
      "submitToken" -> java.util.UUID.randomUUID().toString
    ) ++ extra ++ jurorParams

  private def jobs(suffix: String): Seq[AdminJob] =
    Seq(
      AdminJob(s"Large A$suffix", small = false, newRoundForm(s"Large A$suffix")),
      AdminJob(s"Large B$suffix", small = false, newRoundForm(s"Large B$suffix"))
    ) ++ regions.map { case (r, _) =>
      AdminJob(s"Region $r$suffix", small = true, newRoundForm(s"Region $r$suffix", "regions[0]" -> r))
    } ++ Seq(
      AdminJob(s"Top 100$suffix", small = true,
        newRoundForm(s"Top 100$suffix", "previousRound[0]" -> ratedId.toString, "topImages" -> "100")),
      AdminJob(s"Top 500$suffix", small = true,
        newRoundForm(s"Top 500$suffix", "previousRound[0]" -> ratedId.toString, "topImages" -> "500")),
      AdminJob(s"Rated avg 7$suffix", small = true,
        newRoundForm(s"Rated avg 7$suffix", "previousRound[0]" -> ratedId.toString, "minAvgRate" -> "7")),
      AdminJob(s"New files$suffix", small = true,
        newRoundForm(GatlingTestFixture.managedRounds.head._3, "id" -> binaryId.toString, "newImages" -> "true"), existingRound = Some(binaryId))
    )

  // ── DB lookups (the simulation runs in the app's JVM) ──────────────────────

  private def roundIdByName(name: String): Option[Long] =
    sql"SELECT MAX(id) FROM rounds WHERE contest_id = $contestId AND name = $name".map(_.longOpt(1)).single().flatten

  /** None on the code before background jobs (no job table): the submit itself distributed. */
  private def latestJobId(roundId: Long): Option[Long] =
    scala.util.Try(sql"""SELECT MAX(id) FROM job WHERE kind = 'round_distribution' AND subject_type = 'round'
          AND subject_id = $roundId""".map(_.longOpt(1)).single().flatten).toOption.flatten

  private def roundFigures(roundId: Long): String =
    sql"""SELECT COUNT(DISTINCT page_id), COUNT(*) FROM selection WHERE round_id = $roundId"""
      .map(rs => s"in-request images=${rs.long(1)} rows=${rs.long(2)}").single().getOrElse("")

  private def jobFigures(jobId: Long): String =
    sql"""SELECT status, progress_done, progress_total, JSON_VALUE(state, '$$.imagesAdded'),
                 JSON_VALUE(state, '$$.rowsAdded'), TIMESTAMPDIFF(MICROSECOND, created_at, finished_at) DIV 1000
          FROM job WHERE id = $jobId"""
      .map(rs => s"status=${rs.string(1)} candidates=${rs.longOpt(2).getOrElse(0)}/${rs.longOpt(3).getOrElse(0)} " +
        s"images=${rs.stringOpt(4).getOrElse("0")} rows=${rs.stringOpt(5).getOrElse("0")} " +
        s"jobCreatedToFinished=${rs.longOpt(6).getOrElse(-1)}ms")
      .single().getOrElse("job not found")

  // ── the admin flow ─────────────────────────────────────────────────────────

  private val results = new ConcurrentHashMap[String, Long]()
  private val concurrentStarted = new AtomicBoolean(false)
  private val concurrentLeft = new AtomicInteger(0)
  private val adminCount = new AtomicInteger(0)

  private def login(email: String, password: String) =
    exec(http("login organizer").post("/auth")
      .formParam("login", email).formParam("password", password)
      .check(status.is(303)))
      .exitHereIfFailed

  /** Submit `job`'s form, poll its job to the end, report. */
  private def runJob(job: AdminJob, phase: String): ChainBuilder =
    exec(_.set("t0", System.currentTimeMillis()).set("jobStatus", "queued"))
      .exec(http(s"$phase: submit round form").post("/admin/rounds/save")
        .formParamSeq(job.form)
        .requestTimeout(timeout)
        .check(status.is(303)))
      .exec { s =>
        val roundId = job.existingRound.orElse(roundIdByName(job.name))
        roundId.fold(s.markAsFailed) { rid =>
          latestJobId(rid) match {
            case Some(id) => s.set("jobId", id).set("roundId", rid)
            case None     => s.set("jobStatus", "done").set("roundId", rid) // distributed in the request
          }
        }
      }
      .exitHereIfFailed
      .asLongAs(s => Set("queued", "running").contains(s("jobStatus").as[String]) &&
          System.currentTimeMillis() - s("t0").as[Long] < timeout.toMillis) {
        pause(cfg.pollMillis.millis)
          .exec(http("job status").get("/admin/jobs/#{jobId}")
            .check(status.is(200), jsonPath("$.status").saveAs("jobStatus")))
      }
      .exec { s =>
        val millis = System.currentTimeMillis() - s("t0").as[Long]
        results.put(s"$phase/${job.name}", millis)
        HeapWatcher.report(
          s"phase=$phase admin-job='${job.name}' small=${job.small} submitToDone=${millis}ms " +
            s("jobId").asOption[Long].fold(roundFigures(s("roundId").as[Long]))(jobFigures), reportFile)
        if (s("jobStatus").as[String] == "done") s else s.markAsFailed
      }

  /** Each small job alone, one after another: the reference times. */
  private val soloScn = scenario("Solo jobs")
    .exec(login(admins.head._2, admins.head._3))
    .exec(jobs(" (solo)").filter(_.small).map(j => runJob(j, "solo")))

  private val concurrentJobs = jobs("").take(cfg.admins) // admins = 0: the jurors alone, the reference

  /** Every admin at once, one job each. */
  private lazy val adminsScn = scenario("Admins at once")
    .exec { s =>
      val i = adminCount.getAndIncrement() % concurrentJobs.size
      if (concurrentStarted.compareAndSet(false, true)) {
        HeapWatcher.start("concurrent")
        concurrentLeft.set(concurrentJobs.size)
      }
      s.set("adminEmail", admins(i)._2).set("adminPassword", admins(i)._3).set("jobIndex", i)
    }
    .exec(http("login organizer").post("/auth")
      .formParam("login", "#{adminEmail}").formParam("password", "#{adminPassword}")
      .check(status.is(303)))
    .exitHereIfFailed
    .doSwitch("#{jobIndex}")(concurrentJobs.zipWithIndex.map { case (j, i) => (i: Any) -> runJob(j, "concurrent") }: _*)
    .exec { s =>
      if (concurrentLeft.decrementAndGet() == 0) {
        val mem = HeapWatcher.end()
        HeapWatcher.report(
          s"phase=concurrent all-done " + mem.fold("") { m =>
            s"time=${m.millis}ms gcs=${m.gcs} fullGcs=${m.fullGcs} " +
              s"maxHeapAfterGc=${m.maxAfterGcMb.fold("n/a")(mb => s"${mb}MB")} usedAtEnd=${m.usedAtEndMb}MB"
          } + " " + summary, reportFile)
      }
      s
    }

  private def summary: String = {
    val r = results.asScala
    concurrentJobs.filter(_.small).flatMap { j =>
      for (solo <- r.get(s"solo/${j.name} (solo)"); together <- r.get(s"concurrent/${j.name}"))
        yield s"'${j.name}' solo=${solo}ms together=${together}ms"
    }.mkString("; ")
  }

  // ── jurors in the binary round meanwhile ───────────────────────────────────

  // a vote on one of the juror's own images in the binary round
  private val nextVote = exec { s =>
    val (pageId, roundId, rate) = GatlingTestFixture.nextVote(s("userId").as[String].toLong, Some(binaryId))
    s.set("pageId", pageId).set("roundId", roundId).set("rate", rate)
  }

  private val jurorFeeder = Iterator.continually(GatlingTestFixture.jurorsIn(Some(binaryId))).flatten.map { case (id, email, pass) =>
    Map("userId" -> id.toString, "email" -> email, "password" -> pass)
  }

  private val jurorLogin =
    exec(http("juror login").post("/auth")
      .formParam("login", "#{email}").formParam("password", "#{password}")
      .check(status.is(303)))
      .exitHereIfFailed

  private val votersScn = scenario("Voting meanwhile")
    .feed(jurorFeeder)
    .exec(jurorLogin)
    .repeat(15) {
      exec(nextVote)
        .exec(http("cast vote")
          .post("/rate/round/#{roundId}/pageid/#{pageId}/select/#{rate}")
          .check(status.is(200), bodyString.is("success")))
    }

  private val galleryScn = scenario("Gallery meanwhile")
    .feed(jurorFeeder)
    .exec(jurorLogin)
    .repeat(10) {
      exec(_.set("page", scala.util.Random.nextInt(5) + 1))
        .exec(http("gallery page")
          .get(s"/gallery/round/$binaryId/user/#{userId}/page/#{page}")
          .check(status.is(200)))
    }

  private def meanwhile(scn: io.gatling.core.structure.ScenarioBuilder) =
    scn.inject(
      rampUsers(cfg.voterUsers).during(10.seconds),
      constantUsersPerSec(cfg.voterUsers.toDouble / 10).during(cfg.durationSeconds.seconds)
    )

  private val concurrentPhase =
    (if (concurrentJobs.nonEmpty) Seq(adminsScn.inject(atOnceUsers(concurrentJobs.size))) else Nil) ++
      (if (cfg.withVoting) Seq(meanwhile(votersScn), meanwhile(galleryScn)) else Nil)

  HeapWatcher.report(
    s"start admins=${concurrentJobs.size} regions=${regions.map { case (r, n) => s"$r:$n" }.mkString(",")} " +
      s"workers=${GatlingConfig.Jobs.workers} distributionConcurrency=${GatlingConfig.Jobs.distributionConcurrency} " +
      s"chunkRows=${GatlingConfig.Jobs.chunkRows} soloFirst=${cfg.soloFirst} withVoting=${cfg.withVoting}",
    reportFile)

  private val populations =
    if (cfg.soloFirst) Seq(soloScn.inject(atOnceUsers(1)).andThen(concurrentPhase: _*)) else concurrentPhase

  setUp(populations: _*).protocols(http.baseUrl(baseUrl).disableFollowRedirect)
    .maxDuration(60.minutes)
}
