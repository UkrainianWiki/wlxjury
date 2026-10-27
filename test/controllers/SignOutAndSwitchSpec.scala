package controllers

import controllers.CsrfTestApp.{token, withCsrfApp}
import db.scalikejdbc.{PlayTestDb, User, UserContestJdbc}
import play.api.Application
import play.api.mvc.Result
import play.api.test.{FakeRequest, PlaySpecification}

import scala.concurrent.Future

/** Signing out and switching the session's contest change the session, so they are POSTs
  * with the CSRF token, through the router and the filters as in production: a link or a
  * GET another site opens does neither.
  */
class SignOutAndSwitchSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val email = "gina@example.com"

  /** A juror of contest 10 and an organizer of contest 20, the session in 10. */
  private def setUp(): Unit = {
    createContests(10, 20)
    val gina = User.create(User(email, email, None, Set("jury"), Some("hash"), Some(10L)))
    UserContestJdbc.createMembership(gina.getId, 20, "organizer")
  }

  /** A signed-in browser's request: a session cookie (which makes the filter check it), the
    * session's CSRF token and contest 10.
    */
  private def send(app: Application, method: String, path: String, sessionToken: String,
                   body: (String, String)*): Future[Result] =
    route(app, FakeRequest(method, path)
      .withHeaders(COOKIE -> "PLAY_SESSION=signed-in", ACCEPT -> "text/html")
      .withSession(Secured.UserName -> email, Secured.CurrentContestId -> "10", "csrfToken" -> sessionToken)
      .withFormUrlEncodedBody(body: _*)).get

  "signing out" should {

    "not happen on a GET, which shows a button that signs out" in {
      withCsrfApp { app =>
        setUp()
        val page = send(app, GET, "/logout?returnTo=%2Fcontest%2F10%2Ffiles", token(app))
        status(page) === OK
        session(page).get(Secured.UserName) must beNone.or(beSome(email))
        val html = contentAsString(page)
        html must contain("""<form method="post" action="/logout">""")
        html must contain("""name="returnTo" value="/contest/10/files"""")
        html must contain("""name="csrfToken"""")
      }
    }

    "be refused without the CSRF token, or with a forged one" in {
      withCsrfApp { app =>
        setUp()
        status(send(app, POST, "/logout", token(app))) === FORBIDDEN
        status(send(app, POST, "/logout", token(app), "csrfToken" -> "forged")) === FORBIDDEN
      }
    }

    "happen on a POST with the token, back to a local returnTo after signing in" in {
      withCsrfApp { app =>
        setUp()
        val sessionToken = token(app)
        val result = send(app, POST, "/logout", sessionToken,
          "csrfToken" -> sessionToken, "returnTo" -> "/contest/10/files?page=2")
        status(result) === SEE_OTHER
        redirectLocation(result) === Some(routes.LoginController.login(Some("/contest/10/files?page=2")).url)
        session(result).get(Secured.UserName) must beNone

        val elsewhere = send(app, POST, "/logout", sessionToken,
          "csrfToken" -> sessionToken, "returnTo" -> "https://evil.example/")
        redirectLocation(elsewhere) === Some(routes.LoginController.login(None).url)
      }
    }

    "be offered by the user menu and the access-denied page as POST forms with the token" in {
      withCsrfApp { app =>
        setUp()
        // a page of contest 20 only an admin may open: denied, with a way back here
        val denied = send(app, GET, "/admin/users?contestId=20", token(app))
        status(denied) === FORBIDDEN
        val html = contentAsString(denied)
        html must not(contain("href=\"/logout"))
        html must not(contain("href=\"/switch-contest"))
        """<form method="post" action="/logout"""".r.findAllMatchIn(html).size === 2
        html must contain("""name="returnTo" value="/admin/users?contestId=20"""")
        html must contain("""<form method="post" action="/switch-contest/20"""")
        html must contain("""name="csrfToken"""")
      }
    }
  }

  "switching the contest" should {

    "not happen on a GET, which goes to the start page" in {
      withCsrfApp { app =>
        setUp()
        val result = send(app, GET, "/switch-contest/20", token(app))
        status(result) === SEE_OTHER
        redirectLocation(result) === Some(routes.LoginController.index.url)
        session(result).get(Secured.CurrentContestId) must not(beSome("20"))
      }
    }

    "be refused without the CSRF token" in {
      withCsrfApp { app =>
        setUp()
        val result = send(app, POST, "/switch-contest/20", token(app))
        status(result) === FORBIDDEN
        session(result).get(Secured.CurrentContestId) must not(beSome("20"))
      }
    }

    "happen on a POST with the token, to one of the user's contests only" in {
      withCsrfApp { app =>
        setUp()
        val sessionToken = token(app)
        val result = send(app, POST, "/switch-contest/20", sessionToken, "csrfToken" -> sessionToken)
        status(result) === SEE_OTHER
        session(result).get(Secured.CurrentContestId) === Some("20")

        createContests(30)
        status(send(app, POST, "/switch-contest/30", sessionToken, "csrfToken" -> sessionToken)) === FORBIDDEN
      }
    }
  }
}
