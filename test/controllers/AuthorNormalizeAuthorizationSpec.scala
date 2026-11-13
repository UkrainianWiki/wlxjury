package controllers

import controllers.CsrfTestApp.{token, withCsrfApp}
import db.scalikejdbc._
import org.intracer.wmua.Image
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._
import services.imports.AuthorNormalizeHandler

import scala.concurrent.Future

/** Starting the `author_normalize` job ("Normalize stored authors"): root only, with the
  * CSRF token; refused to a contest's admin, organizer and juror and to the signed out, as a
  * page, and its job's status to them as JSON. Nothing is queued when refused. The files
  * hold only links (no credit template): the job asks Commons nothing.
  */
class AuthorNormalizeAuthorizationSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val contestId = 10L

  private def member(email: String, role: String): User =
    User.create(User(email, email, None, Set(role), Some(services.PasswordHasher.default.hash("secret")), Some(contestId)))

  private def setUp(): Unit = {
    createContests(contestId)
    imageDao.batchInsert(Seq(Image(1L, "File:Image1.jpg", None, None, 640, 480, author = Some("[[User:Петро_Грушко|Петро]]"))))
    member("juror@example.com", "jury")
    member("organizer@example.com", "organizer")
    member("admin@example.com", "admin")
    User.create(User("root@example.com", "root@example.com", None, Set(User.ROOT_ROLE), Some("hash")))
  }

  private def jobs(): Seq[(Option[Long], String)] = DB.readOnly { implicit s =>
    sql"SELECT contest_id, status FROM job WHERE kind = ${AuthorNormalizeHandler.Kind} ORDER BY id"
      .map(rs => rs.longOpt(1) -> rs.string(2)).list()
  }

  private def author(): Option[String] = DB.readOnly { implicit s =>
    sql"SELECT author FROM images WHERE page_id = 1".map(_.stringOpt(1)).single().flatten
  }

  private def signedIn[A](request: FakeRequest[A], email: Option[String]): FakeRequest[A] =
    email.fold(request)(e => request.withSession(Secured.UserName -> e, Secured.CurrentContestId -> contestId.toString))

  private def start(app: Application, email: Option[String]): Future[Result] =
    route(app, signedIn(FakeRequest(POST, "/admin/authors/normalize").withFormUrlEncodedBody("returnTo" -> "/admin/rounds"),
      email).withCSRFToken).get

  "normalizing stored authors" should {

    "be started by root, with no contest, and run" in {
      testDbApp { app =>
        setUp()
        val result = start(app, Some("root@example.com"))
        (status(result) === SEE_OTHER) and (redirectLocation(result) === Some("/admin/rounds")) and
          (jobs() === Seq(None -> "done")) and (author() === Some("Петро Грушко"))
      }
    }

    "be refused to a contest's admin, organizer and juror, and to the signed out" in {
      testDbApp { app =>
        setUp()
        (status(start(app, Some("admin@example.com"))) === FORBIDDEN) and
          (status(start(app, Some("organizer@example.com"))) === FORBIDDEN) and
          (status(start(app, Some("juror@example.com"))) === FORBIDDEN) and
          (redirectLocation(start(app, None)) must not(beSome("/admin/rounds"))) and
          (jobs() must beEmpty) and (author() === Some("[[User:Петро_Грушко|Петро]]"))
      }
    }

    "show its job's status as JSON to root only" in {
      testDbApp { app =>
        setUp()
        status(start(app, Some("root@example.com")))
        val id = DB.readOnly(implicit s => sql"SELECT MAX(id) FROM job".map(_.long(1)).single().get)
        def poll(email: String) = route(app, signedIn(FakeRequest(GET, s"/admin/jobs/$id")
          .withHeaders("X-Requested-With" -> "XMLHttpRequest"), Some(email))).get
        val asAdmin = poll("admin@example.com")
        (status(poll("root@example.com")) === OK) and
          ((contentAsJson(poll("root@example.com")) \ "kind").as[String] === AuthorNormalizeHandler.Kind) and
          (status(asAdmin) === NOT_FOUND) and ((contentAsJson(asAdmin) \ "error").as[String] === "job not found") and
          (status(poll("organizer@example.com")) === NOT_FOUND)
      }
    }

    "need the CSRF token" in {
      withCsrfApp { app =>
        setUp()
        val sessionToken = token(app)
        def send(body: (String, String)*) =
          route(app, FakeRequest(POST, "/admin/authors/normalize")
            .withHeaders(COOKIE -> "PLAY_SESSION=signed-in", ACCEPT -> "text/html")
            .withSession(Secured.UserName -> "root@example.com", Secured.CurrentContestId -> contestId.toString,
              "csrfToken" -> sessionToken)
            .withFormUrlEncodedBody(body: _*)).get
        (status(send()) === FORBIDDEN) and (status(send("csrfToken" -> "forged")) === FORBIDDEN) and
          (jobs() must beEmpty) and
          (status(send("csrfToken" -> sessionToken)) === SEE_OTHER) and (jobs().size === 1)
      }
    }

    "show the button and the latest run's result on the rounds page to root only" in {
      testDbApp { app =>
        setUp()
        def page(email: String) = contentAsString(route(app, signedIn(FakeRequest(GET, s"/admin/rounds?contestId=$contestId"),
          Some(email)).withCSRFToken).get)
        val before = page("root@example.com")
        status(start(app, Some("root@example.com")))
        val after = page("root@example.com")
        (page("admin@example.com") must not(contain("/admin/authors/normalize"))) and
          (before must contain("/admin/authors/normalize")) and (before must not(contain("author-normalize-status"))) and
          (after must contain("author-normalize-summary")) and (after must contain("1 author changed")) and
          (page("admin@example.com") must not(contain("author-normalize")))
      }
    }
  }
}
