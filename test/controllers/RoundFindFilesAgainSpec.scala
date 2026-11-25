package controllers

import db.scalikejdbc._
import org.intracer.wmua.Image
import org.jsoup.Jsoup
import play.api.Application
import play.api.libs.json.Json
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._
import services.distribution.RoundDistributionHandler
import services.jobs.{JobJdbc, NewJob}

import scala.concurrent.Future

/** "Find the matching files again" (issue #194): a round whose setup was cancelled or didn't finish,
  * meant to be reviewed first and with no jurors, is filled again and held in review; offered on the
  * Rounds page exactly when its POST takes it; Start stays refused until it is distributed. A round
  * with jurors keeps the round form's box, one meant to be distributed after its fill gets neither.
  * The contest's admins and root only.
  */
class RoundFindFilesAgainSpec extends PlaySpecification with PlayTestDb {

  sequential

  private implicit val autoSession: DBSession = AutoSession

  private val contestId = 10L

  private def member(email: String, role: String, contest: Long = contestId): User =
    User.create(User(email, email, None, Set(role), Some(services.PasswordHasher.default.hash("secret")), Some(contest)))

  /** Contest 10 with 3 files in its category, its admin, organizer and a juror; contest 20's admin. */
  private def setUp(): Map[String, User] = {
    createContests(contestId, 20)
    val imgs = (1L to 3L).map(i => Image(i, s"File:Image$i.jpg", None, None, 640, 480, Some(s"12-345-$i")))
    imageDao.batchInsert(imgs)
    CategoryLinkJdbc.addToCategory(contestDao.findById(contestId).get.categoryId.get, imgs)
    Map("admin" -> member("admin@example.com", "admin"), "organizer" -> member("organizer@example.com", "organizer"),
      "juror" -> member("juror@example.com", "jury"), "other-admin" -> member("other-admin@example.com", "admin", contest = 20))
  }

  /** A round being set up, `status` (draft or cancelled), with its latest fill job ended `jobStatus`
    * (None: no job), meant to be reviewed first unless `distributeAfter`.
    */
  private def settingUp(number: Int, status: String, jobStatus: Option[String], distributeAfter: Boolean = false): Round = {
    val round = roundDao.create(Round(None, number, Some(s"R$number"), contestId, distribution = 0, active = false,
      distributing = true, status = Some(status)))
    for (st <- jobStatus) {
      val id = JobJdbc.insert(NewJob(RoundDistributionHandler.Kind, Some(contestId), Some(RoundDistributionHandler.SubjectType),
        round.id, Some(RoundDistributionHandler.dedupeKey(round.getId)),
        params = Json.obj("roundId" -> round.getId, "mode" -> RoundDistributionHandler.Mode.Fill,
          "distributeAfter" -> distributeAfter, "completingCreation" -> true)), owner = None)
      sql"UPDATE job SET status = $st WHERE id = $id".update()
    }
    Round.findById(round.getId).get
  }

  private def request(method: String, email: String, path: String, body: (String, String)*) =
    FakeRequest(method, path).withFormUrlEncodedBody(body: _*)
      .withSession(Secured.UserName -> email,
        Secured.CurrentContestId -> (if (email.startsWith("other-")) "20" else contestId.toString)).withCSRFToken

  private def roundsPage(app: Application): String =
    contentAsString(route(app, request(GET, "admin@example.com", s"/admin/rounds?contestId=$contestId")).get)

  private def findAgain(app: Application, email: String, r: Round): Future[Result] =
    route(app, request(POST, email, s"/admin/rounds/${r.getId}/find-files-again")).get

  private def start(app: Application, r: Round): Future[Result] =
    route(app, request(POST, "admin@example.com", "/admin/setround", "currentId" -> r.getId.toString, "setActive" -> "true")).get

  private def offered(html: String, r: Round): Boolean =
    !Jsoup.parse(html).select(s"tr.round-row[data-round=${r.getId}] form.round-find-again").isEmpty

  private def files(r: Round): Int =
    sql"SELECT COUNT(*) FROM round_image WHERE round_id = ${r.getId}".map(_.int(1)).single().getOrElse(0)

  private def jobs(r: Round): Int =
    sql"SELECT COUNT(*) FROM job WHERE subject_id = ${r.getId} AND kind = ${RoundDistributionHandler.Kind}".map(_.int(1)).single().getOrElse(0)

  "a cancelled or unfinished setup meant for review, with no jurors" should {

    "be offered Find the matching files again, which fills it and holds it in review; Start stays refused" in {
      testDbApp { app =>
        setUp()
        val cancelled = settingUp(1, Round.Status.Cancelled, Some("cancelled"))
        val unfinished = settingUp(2, Round.Status.Draft, None)
        val before = roundsPage(app)
        val startBefore = flash(start(app, cancelled)).get("error")
        Seq(cancelled, unfinished).map { r =>
          val result = findAgain(app, "admin@example.com", r)
          // the action ends (its job runs inline) before the round is read
          val code = status(result)
          val after = Round.findById(r.getId).get
          val refused = flash(start(app, after)).get("error")
          (offered(before, r) aka s"round ${r.number} offered" must beTrue) and
            (code === SEE_OTHER) and
            (redirectLocation(result) must beSome(startWith(s"/admin/rounds?contestId=$contestId"))) and
            (flash(result).get("success") must beSome(contain("finding its matching files again"))) and
            (after.inReview aka s"round ${r.number} in review" must beTrue) and (files(after) === 3) and
            (User.findRoundJurors(r.getId) must beEmpty) and
            (refused must beSome(contain("hasn't been distributed to jurors yet"))) and
            (Round.findById(r.getId).map(_.active) must beSome(false))
        }.reduce(_ and _) and
          (startBefore must beSome(contain("“Find the matching files again” in its row"))) and {
            // in review now: its jurors form's Distribute, no Find again
            val after = roundsPage(app)
            (offered(after, cancelled) must beFalse) and (offered(after, unfinished) must beFalse) and
              (Jsoup.parse(after).select(s"tr.round-row[data-round=${cancelled.getId}] a.round-start-distribute").isEmpty must beFalse)
          }
      }
    }
  }

  "other rounds being set up" should {

    "keep the round form's box for a round with jurors, and refuse one meant to be distributed after its fill" in {
      testDbApp { app =>
        val users = setUp()
        val withJuror = settingUp(1, Round.Status.Cancelled, Some("cancelled"))
        withJuror.addUsers(Seq(RoundUser(withJuror.getId, users("juror").getId, "jury", active = true)))
        val distributeAfter = settingUp(2, Round.Status.Cancelled, Some("cancelled"), distributeAfter = true)
        val inReview = settingUp(3, Round.Status.Paused, Some("done"))
        val html = roundsPage(app)
        val refusals = Seq(withJuror, distributeAfter, inReview).map { r =>
          val jobsBefore = jobs(r)
          val result = findAgain(app, "admin@example.com", r)
          val code = status(result)
          (offered(html, r) aka s"round ${r.number} offered" must beFalse) and (code === SEE_OTHER) and
            (flash(result).get("error") must beSome(contain("can't be found again from here"))) and
            (jobs(r) aka s"round ${r.number} jobs" must_== jobsBefore) and (files(r) === 0)
        }.reduce(_ and _)
        refusals and
          (flash(start(app, withJuror)).get("error") must beSome(contain("tick “Distribute ? new files”"))) and
          (flash(start(app, distributeAfter)).get("error") must beSome(contain("Set up a new round instead")))
      }
    }

    "be refused to its organizer, a juror and another contest's admin, with nothing queued" in {
      testDbApp { app =>
        setUp()
        val cancelled = settingUp(1, Round.Status.Cancelled, Some("cancelled"))
        val jobsBefore = jobs(cancelled)
        Seq("organizer@example.com", "juror@example.com", "other-admin@example.com").map { e =>
          status(findAgain(app, e, cancelled)) aka e must_== FORBIDDEN
        }.reduce(_ and _) and (jobs(cancelled) === jobsBefore) and (files(cancelled) === 0) and
          (Round.findById(cancelled.getId).map(_.lifecycle) must beSome(Round.Status.Cancelled))
      }
    }
  }
}
