package controllers

import controllers.CsrfTestApp.{token, withCsrfApp}
import db.scalikejdbc._
import org.intracer.wmua.Image
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._
import services.distribution.RoundImageCheckHandler

import scala.concurrent.Future

/** Queuing round_image_check jobs: a round's by its contest's admins (checked in the round's
  * contest), every round's by root; refused to jurors, organizers, another contest's admins,
  * the signed out, and without the CSRF token. Nothing is queued when refused.
  */
class RoundImageCheckAuthorizationSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val contestId = 10L

  private def member(email: String, role: String, contest: Long = contestId): User =
    User.create(User(email, email, None, Set(role), Some(services.PasswordHasher.default.hash("secret")), Some(contest)))

  /** Contest 10 with a round of two files, its juror, organizer and admin; contest 20 with its
    * own admin and round; root.
    */
  private def setUp(): (Round, Round) = {
    createContests(contestId, 20)
    imageDao.batchInsert(Seq(1L, 2L).map(i => Image(i, s"File:Image$i.jpg", None, None, 640, 480, Some(s"12-345-$i"))))
    val juror = member("juror@example.com", "jury")
    member("organizer@example.com", "organizer")
    member("admin@example.com", "admin")
    member("other-admin@example.com", "admin", contest = 20)
    User.create(User("root@example.com", "root@example.com", None, Set(User.ROOT_ROLE), Some("hash")))
    val round = roundDao.create(Round(None, 1, Some("R"), contestId, active = true))
    val other = roundDao.create(Round(None, 1, Some("O"), 20, active = true))
    Seq(1L, 2L).foreach(p => VoteJdbc.create(p, 0, juror.getId, round.getId))
    (round, other)
  }

  private def jobs(): Seq[(Option[Long], Option[Long])] = DB.readOnly { implicit s =>
    sql"SELECT contest_id, subject_id FROM job WHERE kind = ${RoundImageCheckHandler.Kind} ORDER BY id"
      .map(rs => rs.longOpt(1) -> rs.longOpt(2)).list()
  }

  /** A signed-in POST, in the user's own contest (another contest's admin's is 20). */
  private def post(app: Application, email: Option[String], path: String): Future[Result] = {
    val request = FakeRequest(POST, path).withFormUrlEncodedBody("returnTo" -> "/admin/rounds")
    val contest = if (email.exists(_.startsWith("other-"))) 20L else contestId
    route(app, email.fold(request)(e => request.withSession(Secured.UserName -> e, Secured.CurrentContestId -> contest.toString))
      .withCSRFToken).get
  }

  private def checkRound(round: Long) = s"/admin/rounds/$round/check-images"
  private val checkAll = "/admin/round-images/check"

  "checking a round's images [A-11]" should {

    "be queued for its contest's admin and root, in the round's contest, and run" in {
      testDbApp { app =>
        val (round, other) = setUp()
        val r = round.getId
        (status(post(app, Some("admin@example.com"), checkRound(r))) === SEE_OTHER) and
          (status(post(app, Some("other-admin@example.com"), checkRound(other.getId))) === SEE_OTHER) and
          (status(post(app, Some("root@example.com"), checkRound(r))) === SEE_OTHER) and
          (jobs() === Seq(Some(contestId) -> Some(r), Some(20L) -> Some(other.getId), Some(contestId) -> Some(r))) and
          (DB.readOnly(implicit s => sql"SELECT DISTINCT status FROM job".map(_.string(1)).list()) === List("done"))
      }
    }

    "be refused to a juror, an organizer, another contest's admin (also for this contest's round), and a missing round" in {
      testDbApp { app =>
        val (round, other) = setUp()
        val r = round.getId
        (status(post(app, Some("juror@example.com"), checkRound(r))) === FORBIDDEN) and
          (status(post(app, Some("organizer@example.com"), checkRound(r))) === FORBIDDEN) and
          (status(post(app, Some("other-admin@example.com"), checkRound(r))) === FORBIDDEN) and
          (status(post(app, Some("admin@example.com"), checkRound(other.getId))) === FORBIDDEN) and
          (status(post(app, Some("admin@example.com"), checkRound(99999L))) === NOT_FOUND) and
          (jobs() must beEmpty)
      }
    }

    "be refused when signed out" in {
      testDbApp { app =>
        val (round, _) = setUp()
        (status(post(app, None, checkRound(round.getId))) must not(beEqualTo(OK))) and
          (status(post(app, None, checkAll)) must not(beEqualTo(OK))) and (jobs() must beEmpty)
      }
    }
  }

  "checking every round's images [A-11]" should {

    "be queued for root only, with no contest" in {
      testDbApp { app =>
        setUp()
        (status(post(app, Some("admin@example.com"), checkAll)) === FORBIDDEN) and
          (status(post(app, Some("organizer@example.com"), checkAll)) === FORBIDDEN) and
          (jobs() must beEmpty) and
          (status(post(app, Some("root@example.com"), checkAll)) === SEE_OTHER) and
          (jobs() === Seq(None -> None))
      }
    }
  }

  "the forms [A-11]" should {

    "need the CSRF token" in {
      withCsrfApp { app =>
        val (round, _) = setUp()
        val sessionToken = token(app)
        def send(path: String, email: String, body: (String, String)*) =
          route(app, FakeRequest(POST, path)
            .withHeaders(COOKIE -> "PLAY_SESSION=signed-in", ACCEPT -> "text/html")
            .withSession(Secured.UserName -> email, Secured.CurrentContestId -> contestId.toString, "csrfToken" -> sessionToken)
            .withFormUrlEncodedBody(body: _*)).get
        (status(send(checkRound(round.getId), "admin@example.com")) === FORBIDDEN) and
          (status(send(checkRound(round.getId), "admin@example.com", "csrfToken" -> "forged")) === FORBIDDEN) and
          (status(send(checkAll, "root@example.com")) === FORBIDDEN) and
          (jobs() must beEmpty) and
          (status(send(checkRound(round.getId), "admin@example.com", "csrfToken" -> sessionToken)) === SEE_OTHER) and
          (jobs().size === 1)
      }
    }

    "show the buttons: a round's on its edit page to its admin, every round's on the rounds page to root only" in {
      testDbApp { app =>
        val (round, _) = setUp()
        def page(email: String, path: String) = contentAsString(route(app, FakeRequest(GET, path)
          .withSession(Secured.UserName -> email, Secured.CurrentContestId -> contestId.toString).withCSRFToken).get)
        (page("admin@example.com", s"/admin/rounds/edit?contestId=$contestId&id=${round.getId}") must
          contain(checkRound(round.getId))) and
          (page("admin@example.com", s"/admin/rounds?contestId=$contestId") must not(contain(checkAll))) and
          (page("root@example.com", s"/admin/rounds?contestId=$contestId") must contain(checkAll))
      }
    }
  }
}
