package controllers

import db.scalikejdbc.{PlayTestDb, SharedTestDb, User}
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test._
import scalikejdbc._
import services.diagnostics.HealthFixtures

import scala.concurrent.Future

/** A retired contest (contest series plan §3.1, CY-1) gives its members no access, though the
  * session's current contest still names it: a juror's gallery, large view and vote, an admin's
  * health, rounds and round form are refused, and no vote is written. Root still opens it. A series
  * POST needs the CSRF token.
  */
class RetiredContestAccessSpec extends PlaySpecification with PlayTestDb {

  sequential

  private def send(method: String, path: String, by: User, current: Long, form: (String, String)*)(implicit
      app: Application): Future[Result] =
    route(app, FakeRequest(method, path).withHeaders(ACCEPT -> "text/html")
      .withSession(Secured.UserName -> by.email, Secured.CurrentContestId -> current.toString)
      .withFormUrlEncodedBody(form: _*).withCSRFToken).get

  private def votes(): Long =
    DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM vote".map(_.long(1)).single.apply().getOrElse(0L))

  "a retired contest" should {
    "refuse its members' pages and votes while the session still names it; root opens it" in {
      testDbApp { implicit app =>
        val contest = HealthFixtures.contestWithFiles("Wiki Loves Monuments", 2025, "Nepal", None, 1L to 5L)
        val juror = User.create(User("Juror", "juror.retired@example.org", None, Set("jury"), Some("hash"), contest.id))
        val admin = User.create(User("Admin", "admin.retired@example.org", None, Set("admin"), Some("hash"), contest.id))
        val round = HealthFixtures.round(contest, 1, "Open", files = 1L to 5L, jurors = Seq(juror))
        val id = contest.getId
        // open before the retirement
        status(send(GET, s"/gallery/round/${round.getId}/user/${juror.getId}/page/1", juror, id)) === OK
        // retired with the round still open (as the key and the menus see it)
        DB.autoCommit(implicit s => sql"UPDATE contest_jury SET retired_at = NOW(3) WHERE id = $id".update.apply())

        val jurorPages = Seq(
          send(GET, s"/gallery/round/${round.getId}/user/${juror.getId}/page/1", juror, id),
          send(GET, s"/large/round/${round.getId}/user/${juror.getId}/pageid/1", juror, id),
          send(POST, s"/rate/round/${round.getId}/pageid/1/select/1", juror, id))
        jurorPages.map(status(_)).forall(_ != OK) must beTrue
        votes() === 0L

        val adminPages = Seq(s"/admin/contests/$id/health", s"/admin/rounds?contestId=$id",
          s"/admin/rounds/edit?id=${round.getId}&contestId=$id", s"/admin/users?contestId=$id")
        adminPages.map(p => status(send(GET, p, admin, id))).distinct === Seq(FORBIDDEN)

        val root = User.create(User("Root", "root.retired@example.org", None, Set(User.ROOT_ROLE), isRoot = true))
        status(send(GET, s"/admin/contests/$id/health", root, id)) === OK

        // a stopped round of a retired contest isn't started again
        db.scalikejdbc.Round.setActiveGuarded(round.getId, active = false) must beTrue
        db.scalikejdbc.Round.setActiveGuarded(round.getId, active = true) must beFalse
        db.scalikejdbc.Round.findById(round.getId).map(_.active) must beSome(false)
      }
    }

    "need the CSRF token on a series POST" in {
      CsrfTestApp.withCsrfApp { implicit app =>
        SharedTestDb.truncateAll()
        val root = User.create(User("Root", "root.csrf@example.org", None, Set(User.ROOT_ROLE), isRoot = true))
        val refused = route(app, FakeRequest(POST, "/admin/series")
          .withHeaders(COOKIE -> "PLAY_SESSION=x", ACCEPT -> "text/html")
          .withSession(Secured.UserName -> root.email, "csrfToken" -> CsrfTestApp.token(app))
          .withFormUrlEncodedBody("name" -> "Sneaky")).get
        status(refused) === FORBIDDEN
        db.scalikejdbc.SeriesJdbc.findAll() must beEmpty
      }
    }
  }
}
