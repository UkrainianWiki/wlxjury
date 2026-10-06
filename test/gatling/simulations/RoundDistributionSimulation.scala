package gatling.simulations

import gatling.setup.{GatlingConfig, GatlingTestFixture, HeapWatcher}
import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scalikejdbc._

import scala.concurrent.duration._

/** Round creation and image distribution, the heaviest thing the app does in memory and
  * in database writes. In a real contest organizers do this before voting starts.
  *
  * One organizer, sequential steps, no concurrency: distribution is a rare, one at a time
  * admin action, so what matters is its latency and memory, not throughput. Each step is
  * a real POST /admin/rounds/save as the round form submits it, followed by a check of
  * the new round's selection rows (read from the DB: the simulation runs in the app's
  * JVM) and a GET of its /roundstat page.
  *
  *  1. A first round from the contest category: every fixture image, `jurorsPerImage`
  *     jurors per image.
  *  2. A round from the binary round: the images selected by at least one juror.
  *  3. Optional (`fromRated`): a round from the rated round's top `topImages` images.
  *  4. Optional (`topUp`): "distribute new files" on round 1, a no-op top-up.
  *
  * The heap after each GC is recorded per step (see [[HeapWatcher]]) and appended to
  * target/gatling/distribution-memory.txt.
  */
class RoundDistributionSimulation extends Simulation {

  private val baseUrl   = s"http://localhost:${GatlingTestFixture.port}"
  private val cfg       = GatlingConfig.Distribution
  private val contestId = GatlingTestFixture.contestId
  private val binaryId  = GatlingTestFixture.roundBinaryId
  private val ratedId   = GatlingTestFixture.roundRatingId
  private val jurorIds  = GatlingTestFixture.jurors.map(_._1)
  private val timeout   = cfg.timeoutMinutes.minutes
  private val (_, orgEmail, orgPassword) = GatlingTestFixture.organizer

  private implicit val session: DBSession = AutoSession

  // ── expected image counts ─────────────────────────────────────────────────

  private def categoryImageCount: Int =
    sql"""SELECT COUNT(DISTINCT cm.page_id) FROM category_members cm
          JOIN contest_jury c ON c.category_id = cm.category_id
          WHERE c.id = $contestId""".map(_.int(1)).single().getOrElse(0)

  private def selectedImageCount(roundId: Long): Int =
    sql"""SELECT COUNT(DISTINCT page_id) FROM selection
          WHERE round_id = $roundId AND rate > 0""".map(_.int(1)).single().getOrElse(0)

  private def imageCount(roundId: Long): Int =
    sql"SELECT COUNT(DISTINCT page_id) FROM selection WHERE round_id = $roundId"
      .map(_.int(1)).single().getOrElse(0)

  private def rowCount(roundId: Long): Int =
    sql"SELECT COUNT(*) FROM selection WHERE round_id = $roundId".map(_.int(1)).single().getOrElse(0)

  private def roundIdByName(name: String): Option[Long] =
    sql"SELECT MAX(id) FROM rounds WHERE contest_id = $contestId AND name = $name"
      .map(_.longOpt(1)).single().flatten

  // ── requests ──────────────────────────────────────────────────────────────

  private val jurorParams: Seq[(String, Any)] =
    jurorIds.zipWithIndex.map { case (id, i) => s"jurors[$i]" -> id.toString }

  /** The round form's fields (controllers.EditRound.editRoundForm) for a new round. */
  private def newRoundForm(name: String, extra: (String, Any)*): Seq[(String, Any)] =
    Seq[(String, Any)](
      "number" -> "0", "name" -> name, "contest" -> contestId.toString, "roles" -> "jury",
      "distribution" -> cfg.jurorsPerImage.toString, "rates" -> "1",
      "minMpx" -> "", "minSize" -> "", "mediaType" -> "all",
      // the new-round form's one-time token (ignored when saving an existing round)
      "submitToken" -> java.util.UUID.randomUUID().toString
    ) ++ extra ++ jurorParams

  private val login =
    exec(http("login organizer").post("/auth")
      .formParam("login", orgEmail).formParam("password", orgPassword)
      .check(status.is(303)))
      .exitHereIfFailed

  /** One distribution step: POST the form, then check the round's selection rows.
    *
    * @param roundName  the name of the round the step creates or saves
    * @param expected   (images, rows) the round should have afterwards
    */
  private def step(title: String, roundName: String, form: Seq[(String, Any)])(
      expected: => (Int, Int)) =
    exec { s =>
      HeapWatcher.start(title)
      s.remove("roundId")
    }
      .exec(http(title).post("/admin/rounds/save")
        .formParamSeq(form)
        .requestTimeout(timeout)
        .check(status.is(303), header("Location").saveAs("location")))
      .exec { s =>
        val mem      = HeapWatcher.end()
        val location = s("location").asOption[String].getOrElse("")
        val roundId  = roundIdByName(roundName)
        val (expImages, expRows) = expected
        val images   = roundId.fold(0)(imageCount)
        val rows     = roundId.fold(0)(rowCount)
        // a failed or short distribution redirects to the round's edit page instead of the list
        val ok = roundId.isDefined && !location.contains("/admin/rounds/edit") &&
          images == expImages && rows == expRows
        HeapWatcher.report(
          s"step='$title' round=${roundId.getOrElse("?")} ok=$ok " +
            s"images=$images (expected $expImages) rows=$rows (expected $expRows) " +
            mem.fold("") { m =>
              s"time=${m.millis}ms gcs=${m.gcs} fullGcs=${m.fullGcs} " +
                s"maxHeapAfterGc=${m.maxAfterGcMb.fold("n/a")(mb => s"${mb}MB")} usedAtEnd=${m.usedAtEndMb}MB"
            })
        val withRound = roundId.fold(s)(id => s.set("roundId", id))
        if (ok) withRound else withRound.markAsFailed
      }
      .exitHereIfFailed
      .exec(http(s"$title: round stats")
        .get("/roundstat/#{roundId}")
        .check(status.is(200), substring("/roundstat/")))

  private def iterationScn(i: Int) = {
    val roundA = s"Distribution round A$i"
    val roundB = s"Distribution round B$i"
    val roundC = s"Distribution round C$i"

    val createFromCategory =
      step("create round: from contest category", roundA, newRoundForm(roundA)) {
        val n = categoryImageCount
        (n, n * cfg.jurorsPerImage)
      }.exec(s => s.set("roundAId", s("roundId").as[Long]))

    val createFromBinary =
      step("create round: from binary round", roundB,
        newRoundForm(roundB, "previousRound[0]" -> binaryId.toString, "minJurors" -> "1")) {
        val n = selectedImageCount(binaryId)
        (n, n * cfg.jurorsPerImage)
      }

    val createFromRated =
      step("create round: top images of rated round", roundC,
        newRoundForm(roundC, "previousRound[0]" -> ratedId.toString, "topImages" -> cfg.topImages.toString)) {
        val n = math.min(cfg.topImages, imageCount(ratedId))
        (n, n * cfg.jurorsPerImage)
      }

    // "Distribute new files" on round A, saved as an existing round with newImages = true.
    // Round A already has every category image, so nothing is added.
    val topUp =
      step("top up round: distribute new files", roundA,
        newRoundForm(roundA, "id" -> "#{roundAId}", "newImages" -> "true")) {
        val n = categoryImageCount
        (n, n * cfg.jurorsPerImage)
      }

    exec(createFromCategory)
      .exec(createFromBinary)
      .doIf(_ => cfg.fromRated)(exec(createFromRated))
      .doIf(_ => cfg.topUp)(exec(topUp))
  }

  private val scn = scenario("Round Distribution")
    .exec(login)
    .exec((1 to cfg.iterations).map(iterationScn))

  setUp(scn.inject(atOnceUsers(1)))
    .protocols(http.baseUrl(baseUrl).disableFollowRedirect)
    .maxDuration(60.minutes)
}
