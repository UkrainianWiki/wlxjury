package controllers

import db.scalikejdbc.{PlayTestDb, Round, User}
import org.jsoup.Jsoup
import play.api.Application
import play.api.libs.json.Json
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import services.distribution.RoundDistributionHandler
import services.jobs.{JobJdbc, NewJob}

import scala.concurrent.Future

/** A round not yet distributed to its jurors has no Start on the Rounds page: its state instead,
  * and, waiting in review, a link to its jurors form, where it is distributed. A start posted
  * anyway is refused with a message that says why (in review: not distributed; a job running:
  * being distributed or selected; a creation that didn't finish), and nothing changes. An open or
  * stopped round keeps its Start/Stop. The Rounds page and the start are for the contest's admins
  * only: an organizer is refused both.
  */
class RoundStartBlockSpec extends PlaySpecification with PlayTestDb {

  sequential

  private case class Fixture(contestId: Long, admin: User, organizer: User)

  private def fixture(): Fixture = {
    val contest = contestDao.create(None, "WLE", 2024, "Serbia")
    val admin = userDao.create(User("Admin", "admin@example.com", None, Set("admin"), contestId = contest.id))
    val organizer = userDao.create(User("Org", "org@example.com", None, Set("organizer"), contestId = contest.id))
    Fixture(contest.getId, admin, organizer)
  }

  /** A round held in review (P3): filled, `paused`, still `distributing`. */
  private def inReview(f: Fixture, number: Int): Round =
    roundDao.create(Round(None, number, Some(s"Round $number"), contestId = f.contestId, active = false,
      distributing = true, status = Some(Round.Status.Paused)))

  /** A round being created: `draft`, `distributing`. */
  private def draft(f: Fixture, number: Int): Round =
    roundDao.create(Round(None, number, Some(s"Round $number"), contestId = f.contestId, active = false,
      distributing = true, status = Some(Round.Status.Draft)))

  /** A queued round_distribution job of `round` (never run here). */
  private def job(f: Fixture, round: Round, params: (String, play.api.libs.json.JsValueWrapper)*): Long =
    JobJdbc.insert(NewJob(RoundDistributionHandler.Kind, contestId = Some(f.contestId),
      subjectType = Some(RoundDistributionHandler.SubjectType), subjectId = round.id,
      dedupeKey = Some(RoundDistributionHandler.dedupeKey(round.getId)), params = Json.obj(params: _*)),
      owner = None)(scalikejdbc.AutoSession)

  private def roundsPage(app: Application, f: Fixture, user: User): Future[Result] =
    route(app, FakeRequest(GET, s"/admin/rounds?contestId=${f.contestId}")
      .withSession(Secured.UserName -> user.email).withCSRFToken).get

  private def setRound(app: Application, user: User, round: Round, active: Boolean): Future[Result] =
    route(app, FakeRequest(POST, "/admin/setround")
      .withSession(Secured.UserName -> user.email)
      .withFormUrlEncodedBody("currentId" -> round.getId.toString, "setActive" -> active.toString)
      .withCSRFToken).get

  private def row(html: String, round: Round) =
    Jsoup.parse(html).select(s"tr.round-row[data-round=${round.getId}]").first()

  private def stateOf(html: String, round: Round): Option[(String, String)] =
    Option(row(html, round).selectFirst(".wlx-round-start-state")).map(e => e.attr("data-state") -> e.text())

  private def hasStart(html: String, round: Round): Boolean =
    !row(html, round).select(s"#set-active-form-${round.getId}").isEmpty

  "the Rounds page" should {
    "show a round in review as waiting for distribution, with no Start and a link to its jurors form" in {
      testDbApp { implicit app =>
        val f = fixture()
        val review = inReview(f, 2)
        val html = contentAsString(roundsPage(app, f, f.admin))
        val link = row(html, review).selectFirst("a.round-start-distribute")

        (hasStart(html, review) must beFalse) and
          (stateOf(html, review) must beSome("not.distributed" -> "Not started – waiting for distribution")) and
          (link.attr("href") === s"/admin/rounds/${review.getId}/jurors") and
          (link.text() === "Distribute to jurors")
      }
    }

    "show a round whose distribution runs as being distributed, a selection for review as such, a failed creation as unfinished" in {
      testDbApp { implicit app =>
        val f = fixture()
        val distributing = inReview(f, 1)
        job(f, distributing, "mode" -> RoundDistributionHandler.Mode.Distribute)
        val selecting = draft(f, 2)
        job(f, selecting, "mode" -> RoundDistributionHandler.Mode.Fill, "distributeAfter" -> false)
        val distributeAfter = draft(f, 3)
        job(f, distributeAfter, "mode" -> RoundDistributionHandler.Mode.Fill, "distributeAfter" -> true)
        val failed = draft(f, 4)
        val html = contentAsString(roundsPage(app, f, f.admin))

        (Seq(distributing, selecting, distributeAfter, failed).map(hasStart(html, _)) === Seq(false, false, false, false)) and
          (stateOf(html, distributing) must beSome(
            "distributing" -> "Images are being distributed. The round starts by itself when done.")) and
          (stateOf(html, selecting).map(_._1) must beSome("selecting")) and
          (stateOf(html, distributeAfter).map(_._1) must beSome("distributing")) and
          (stateOf(html, failed).map(_._1) must beSome("incomplete")) and
          (row(html, distributing).select("a.round-start-distribute").isEmpty must beTrue)
      }
    }

    "keep Start and Stop for a stopped and an open round" in {
      testDbApp { implicit app =>
        val f = fixture()
        val open = roundDao.create(Round(None, 1, Some("Open"), contestId = f.contestId, active = true))
        val stopped = roundDao.create(Round(None, 2, Some("Stopped"), contestId = f.contestId, active = false))
        val html = contentAsString(roundsPage(app, f, f.admin))

        (hasStart(html, open) must beTrue) and (hasStart(html, stopped) must beTrue) and
          (stateOf(html, open) must beNone) and (stateOf(html, stopped) must beNone) and
          (row(html, open).select(s"#setActive-${open.getId}").attr("value") === "false") and
          (row(html, stopped).select(s"#setActive-${stopped.getId}").attr("value") === "true")
      }
    }

    "be refused to an organizer" in {
      testDbApp { implicit app =>
        val f = fixture()
        inReview(f, 1)
        status(roundsPage(app, f, f.organizer)) === FORBIDDEN
      }
    }
  }

  "starting a round" should {
    def unchanged(round: Round) = {
      val now = Round.findById(round.getId).get
      (now.active must beFalse) and (now.distributing must beTrue) and (now.status === round.status)
    }

    "refuse a round in review: not distributed to its jurors yet, nothing changed" in {
      testDbApp { implicit app =>
        val f = fixture()
        val review = inReview(f, 2)
        val result = setRound(app, f.admin, review, active = true)

        (status(result) === SEE_OTHER) and
          (flash(result).get("error") must beSome(
            "Round 2 (Round 2) hasn't been distributed to jurors yet. Choose its jurors and distribute its images first: " +
              "“Distribute to jurors” in its row.")) and
          unchanged(review)
      }
    }

    "refuse a round whose distribution runs as still being distributed, a selection for review and an unfinished creation each with their own message" in {
      testDbApp { implicit app =>
        val f = fixture()
        val distributing = inReview(f, 1)
        job(f, distributing, "mode" -> RoundDistributionHandler.Mode.Distribute)
        val selecting = draft(f, 2)
        job(f, selecting, "mode" -> RoundDistributionHandler.Mode.Fill, "distributeAfter" -> false)
        val failed = draft(f, 3)
        def error(r: Round) = flash(setRound(app, f.admin, r, active = true)).get("error")

        (error(distributing) must beSome(
          "Round 1 (Round 1) can't be started yet: its images are still being distributed. " +
            "It starts by itself once its distribution has finished.")) and
          (error(selecting) must beSome(contain("its images are still being selected for review"))) and
          (error(failed) must beSome(contain("its distribution didn't finish"))) and
          unchanged(distributing) and unchanged(selecting) and unchanged(failed)
      }
    }

    "start a stopped round and stop an open one as before" in {
      testDbApp { implicit app =>
        val f = fixture()
        val open = roundDao.create(Round(None, 1, Some("Open"), contestId = f.contestId, active = true))
        val stopped = roundDao.create(Round(None, 2, Some("Stopped"), contestId = f.contestId, active = false))
        val stop = setRound(app, f.admin, open, active = false)
        val start = setRound(app, f.admin, stopped, active = true)

        (status(stop) === SEE_OTHER) and (status(start) === SEE_OTHER) and
          (flash(start).get("error") must beNone) and
          (Round.findById(open.getId).map(_.active) must beSome(false)) and
          (Round.findById(stopped.getId).map(_.active) must beSome(true))
      }
    }

    "be refused to an organizer, a round in review left as it was" in {
      testDbApp { implicit app =>
        val f = fixture()
        val review = inReview(f, 2)
        (status(setRound(app, f.organizer, review, active = true)) === FORBIDDEN) and unchanged(review)
      }
    }
  }
}
