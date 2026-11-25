package controllers

import db.scalikejdbc.{PlayTestDb, Round, RoundUser, User}
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

  /** A creation whose job was cancelled (RoundDistributionHandler.onCancelled): `cancelled`, `distributing`. */
  private def cancelled(f: Fixture, number: Int): Round =
    roundDao.create(Round(None, number, Some(s"Round $number"), contestId = f.contestId, active = false,
      distributing = true, status = Some(Round.Status.Cancelled)))

  /** A job of `round` ended with `status` (failed, cancelled). */
  private def endedJob(f: Fixture, round: Round, status: String): Long = {
    val id = job(f, round, "mode" -> RoundDistributionHandler.Mode.Fill, "distributeAfter" -> true)
    scalikejdbc.DB.autoCommit { implicit s =>
      scalikejdbc.SQL("UPDATE job SET status = ? WHERE id = ?").bind(status, id).update.apply()
    }
    id
  }

  /** A queued round_distribution job of `round` (never run here). */
  private def job(f: Fixture, round: Round, params: (String, play.api.libs.json.Json.JsValueWrapper)*): Long =
    JobJdbc.insert(NewJob(RoundDistributionHandler.Kind, contestId = Some(f.contestId),
      subjectType = Some(RoundDistributionHandler.SubjectType), subjectId = round.id,
      dedupeKey = Some(RoundDistributionHandler.dedupeKey(round.getId)), params = Json.obj(params: _*)),
      owner = None)(scalikejdbc.AutoSession)

  private def roundsPage(app: Application, f: Fixture, user: User): Future[Result] =
    route(app, FakeRequest(GET, s"/admin/rounds?contestId=${f.contestId}")
      .withSession(Secured.UserName -> user.email).withCSRFToken).get

  private def setRound(app: Application, user: User, round: Round, active: Boolean): Future[Result] =
    route(app, FakeRequest(POST, "/admin/setround")
      .withSession(Secured.UserName -> user.email, Secured.CurrentContestId -> round.contestId.toString)
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
        // one Distribute per row: the review block keeps its images and Review link only
        val distributes = row(html, review).select(s"a[href='/admin/rounds/${review.getId}/jurors']")

        (hasStart(html, review) must beFalse) and
          (stateOf(html, review) must beSome("not.distributed" -> "Not started – waiting for distribution")) and
          (link.attr("href") === s"/admin/rounds/${review.getId}/jurors") and
          (link.text() === "Distribute to jurors") and
          (distributes.size() === 1) and
          (row(html, review).select(s"#round-review-${review.getId} a").size() === 1)
      }
    }

    "show a running distribution, a selection for review, a failed, a cancelled and an unfinished creation each as such" in {
      testDbApp { implicit app =>
        val f = fixture()
        val distributing = inReview(f, 1)
        job(f, distributing, "mode" -> RoundDistributionHandler.Mode.Distribute)
        val selecting = draft(f, 2)
        job(f, selecting, "mode" -> RoundDistributionHandler.Mode.Fill, "distributeAfter" -> false)
        val distributeAfter = draft(f, 3)
        job(f, distributeAfter, "mode" -> RoundDistributionHandler.Mode.Fill, "distributeAfter" -> true)
        val failed = draft(f, 4)
        endedJob(f, failed, "failed")
        val cancelledOne = cancelled(f, 5)
        endedJob(f, cancelledOne, "cancelled")
        val unfinished = draft(f, 6)
        val html = contentAsString(roundsPage(app, f, f.admin))

        (Seq(distributing, selecting, distributeAfter, failed, cancelledOne, unfinished).map(hasStart(html, _)) ===
          Seq.fill(6)(false)) and
          (stateOf(html, cancelledOne) must beSome("cancelled" -> "Not started – its creation was cancelled")) and
          (stateOf(html, failed) must beSome("failed" -> "Not started – its distribution failed")) and
          (stateOf(html, unfinished).map(_._1) must beSome("incomplete")) and
          (stateOf(html, distributing) must beSome(
            "distributing" -> "Images are being distributed. The round starts by itself when done.")) and
          (stateOf(html, selecting).map(_._1) must beSome("selecting")) and
          (stateOf(html, distributeAfter).map(_._1) must beSome("distributing")) and
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

    "refuse a running distribution, a selection for review, a failed, a cancelled and an unfinished creation each with its own message" in {
      testDbApp { implicit app =>
        val f = fixture()
        val distributing = inReview(f, 1)
        job(f, distributing, "mode" -> RoundDistributionHandler.Mode.Distribute)
        val selecting = draft(f, 2)
        job(f, selecting, "mode" -> RoundDistributionHandler.Mode.Fill, "distributeAfter" -> false)
        val failed = draft(f, 3)
        endedJob(f, failed, "failed")
        val cancelledOne = cancelled(f, 4)
        endedJob(f, cancelledOne, "cancelled")
        // with a juror, so the round form's "Distribute ? new files" isn't read only
        val juror = userDao.create(User("Juror", "juror@example.com", None, Set("jury"), contestId = Some(f.contestId)))
        cancelledOne.addUsers(Seq(RoundUser(cancelledOne.getId, juror.getId, "jury", active = true)))
        val unfinished = draft(f, 5)
        val cancelledNoJurors = cancelled(f, 6)
        endedJob(f, cancelledNoJurors, "cancelled")
        def error(r: Round) = flash(setRound(app, f.admin, r, active = true)).get("error")

        (error(distributing) must beSome(
          "Round 1 (Round 1) can't be started yet: its images are still being distributed. " +
            "It starts by itself once its distribution has finished.")) and
          (error(selecting) must beSome(contain("its images are still being selected for review"))) and
          (error(failed) must beSome(
            "Round 3 (Round 3) can't be started: its distribution failed. Retry its job in its row.")) and
          // no Retry for a cancelled job: the round form's "Distribute new files" reinstates it
          (error(cancelledOne) must beSome(
            "Round 4 (Round 4) can't be started: its creation was cancelled. To create it after all, edit the round, " +
              "tick “Distribute ? new files” and save.")) and
          (error(cancelledOne) must beSome(not(contain("Retry")))) and
          // no jurors: the form's box is read only; meant to be distributed after its fill, nothing finishes
          // it (a new round instead); meant for review, "Find the matching files again" does (#194)
          (error(cancelledNoJurors) must beSome(
            "Round 6 (Round 6) can't be started: its setup didn't finish, and it has no jurors to give its images " +
              "to. Set up a new round instead.")) and
          (error(unfinished) must beSome(contain("its setup didn't finish, and it has no jurors yet. " +
            "Use “Find the matching files again” in its row"))) and
          (error(unfinished) must beSome(not(contain("Retry")))) and
          unchanged(distributing) and unchanged(selecting) and unchanged(failed) and unchanged(cancelledOne) and
          unchanged(unfinished) and unchanged(cancelledNoJurors)
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

    "refuse a stopped round of a retired contest as retired (refused by the update, the round read again)" in {
      testDbApp { implicit app =>
        val f = fixture()
        val stopped = roundDao.create(Round(None, 2, Some("Stopped"), contestId = f.contestId, active = false))
        scalikejdbc.DB.autoCommit { implicit s =>
          scalikejdbc.SQL("UPDATE contest_jury SET retired_at = NOW(3) WHERE id = ?").bind(f.contestId).update.apply()
        }
        val root = userDao.create(User("Root", "root@example.com", None, Set(User.ROOT_ROLE), isRoot = true))
        val result = setRound(app, root, stopped, active = true)

        (status(result) === SEE_OTHER) and
          (flash(result).get("error") must beSome("Round 2 (Stopped) can't be started: its contest is retired.")) and
          (Round.findById(stopped.getId).map(_.active) must beSome(false))
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
