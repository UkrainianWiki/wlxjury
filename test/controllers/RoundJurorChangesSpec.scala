package controllers

import db.scalikejdbc._
import org.intracer.wmua._
import org.jsoup.Jsoup
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import services.TestRoundServices

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** A round stopped, started again, or closed by creating the next round, by an account that judges
  * it (the maintainer's decision of 2026-11-24, with the conflict-of-interest rule): a
  * `contest_change` entry of the round's kind and a WARN line on `security.authz` (ids only); none
  * when an admin who doesn't judge the round does the same. The round's statistics page shows the
  * entries, with who, to those who see its organiser view, not to a juror of the open round.
  */
class RoundJurorChangesSpec extends PlaySpecification with PlayTestDb {

  sequential

  private case class Fixture(contest: ContestJury, round: Round, adminJ: User, admin: User, juror: User)

  /** An open binary round R with 20 files, judged by a juror and an admin of the contest
    * (`adminJ`); another admin who doesn't judge it.
    */
  private def fixture(): Fixture = {
    SharedTestDb.truncateAll()
    val c = createContests(10).head
    val images = (1 to 20).map(i => Image(i.toLong, s"File:Image$i.jpg", None, None, 640, 480, None))
    imageDao.batchInsert(images)
    CategoryLinkJdbc.addToCategory(contestDao.findById(c.getId).get.categoryId.get, images)
    def user(name: String, role: String) =
      userDao.create(User(name, s"${name.toLowerCase.replace(" ", "")}@example.com", None, Set(role), contestId = c.id))
    val juror = user("Juror One", "jury")
    val adminJ = user("Admin Juror", "admin")
    val admin = user("Admin Only", "admin")
    val round = roundDao.create(Round(None, 1, Some("R"), contestId = c.getId, rates = Round.binaryRound, active = true))
    round.addUsers(Seq(juror, adminJ).map(j => RoundUser(round.getId, j.getId, "jury", active = true)))
    selectionDao.batchInsert(for (j <- Seq(juror, adminJ); image <- images) yield Vote(image, j, round, rate = 1))
    Fixture(contestDao.findById(c.getId).get, round, adminJ, admin, juror)
  }

  private def setRound(app: Application, by: User, round: Round, active: Boolean): Future[Result] =
    route(app, FakeRequest(POST, "/admin/setround")
      .withFormUrlEncodedBody("currentId" -> round.getId.toString, "setActive" -> active.toString)
      .withSession(Secured.UserName -> by.email)
      .withCSRFToken).get

  private def statPage(app: Application, viewer: User, round: Round) =
    Jsoup.parse(contentAsString(route(app, FakeRequest(GET, s"/roundstat/${round.getId}")
      .withSession(Secured.UserName -> viewer.email).withCSRFToken).get))

  private def entries(f: Fixture) = ContestChangeJdbc.findByRound(f.contest.getId, f.round.getId, 10).reverse

  private def line(kind: String, by: User, f: Fixture, byRound: Option[Long] = None) =
    s"juror_round_change kind=$kind accountId=${by.getId} contestId=${f.contest.getId} roundId=${f.round.getId}" +
      byRound.fold("")(id => s" byRoundId=$id")

  "a round's open state changed by a juror of it" should {

    "be logged on stop and on start again, with an entry each, and none for an admin who doesn't judge it" in {
      testDbApp { app =>
        val f = fixture()
        val (codes, logs) = LogCapture("security.authz") {
          Seq(
            status(setRound(app, f.adminJ, f.round, active = false)),
            status(setRound(app, f.adminJ, f.round, active = true)),
            // the same twice: no change, nothing logged
            status(setRound(app, f.adminJ, f.round, active = true)),
            status(setRound(app, f.admin, f.round, active = false)),
            status(setRound(app, f.admin, f.round, active = true)))
        }
        (codes.forall(_ == SEE_OTHER) must beTrue) and
          (entries(f).map(e => (e.kind, e.actorId, (e.detail \ "number").asOpt[Long])) === Seq(
            (ContestChangeJdbc.RoundJurorStopped, f.adminJ.getId, Some(1L)),
            (ContestChangeJdbc.RoundJurorReopened, f.adminJ.getId, Some(1L)))) and
          (logs("security.authz") === Seq(
            line(ContestChangeJdbc.RoundJurorStopped, f.adminJ, f),
            line(ContestChangeJdbc.RoundJurorReopened, f.adminJ, f))) and
          // a contest's own kinds only for an undo's "changed since"
          (ContestChangeJdbc.latestOf(Seq(f.contest.getId)) must beEmpty)
      }
    }

    "be logged when they close it by creating the next round, and not when an admin who doesn't judge it does" in {
      testDbApp { _ =>
        val f = fixture()
        val service = TestRoundServices.roundService()
        def next(name: String, by: User) = {
          val r = Round(None, 0, Some(name), f.contest.getId, distribution = 0, active = true,
            previous = Some(f.round.getId.toString))
          service.submit(r, Seq(f.juror.getId), createdBy = Some(by.getId), review = false).round
        }
        val (created, logs) = LogCapture("security.authz")(next("R2", f.adminJ))
        val closed = roundDao.findById(f.round.getId).map(_.active)
        Round.setActive(f.round.getId, active = true)
        val (_, otherLogs) = LogCapture("security.authz")(next("R3", f.admin))
        (closed === Some(false)) and
          (entries(f).map(e => (e.kind, e.actorId, (e.detail \ "byRound").asOpt[Long], (e.detail \ "byRoundNumber").asOpt[Long])) ===
            Seq((ContestChangeJdbc.RoundJurorClosed, f.adminJ.getId, created.id, roundDao.findById(created.getId).map(_.number)))) and
          (logs("security.authz") === Seq(line(ContestChangeJdbc.RoundJurorClosed, f.adminJ, f, created.id))) and
          (roundDao.findById(f.round.getId).map(_.active) === Some(false)) and
          (otherLogs("security.authz") must beEmpty)
      }
    }

    "show on the round's statistics with who, to those who see its organiser view only" in {
      testDbApp { app =>
        val f = fixture()
        status(setRound(app, f.adminJ, f.round, active = false)) === SEE_OTHER
        // stopped: closed, so its jurors see its organiser view too
        val closed = statPage(app, f.admin, f.round)
        val closedJ = statPage(app, f.adminJ, f.round)
        status(setRound(app, f.adminJ, f.round, active = true)) === SEE_OTHER
        val open = statPage(app, f.admin, f.round)
        val openJ = statPage(app, f.adminJ, f.round)
        (closed.select("#round-juror-changes li").eachText().asScala.map(_.replaceFirst("^.* UTC — ", "")) ===
          Seq("stopped by Admin Juror, a juror of this round")) and
          (closedJ.select("#round-juror-changes li").size === 1) and
          (open.select("#round-juror-changes li.round-juror-change").eachAttr("data-kind").asScala ===
            Seq(ContestChangeJdbc.RoundJurorReopened, ContestChangeJdbc.RoundJurorStopped)) and
          // a juror of the open round: their own votes' view, without the entries
          (openJ.select("#round-juror-changes").size === 0)
      }
    }
  }
}
