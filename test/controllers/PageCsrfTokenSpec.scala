package controllers

import db.scalikejdbc.{PlayTestDb, User}
import org.specs2.specification.AfterAll
import play.api.Application
import play.api.mvc.Result
import play.api.test.{FakeRequest, PlaySpecification}
import security.{ProductionFilters, SourceScan}

import scala.concurrent.Future

/** A page with a form gets the CSRF token whatever the request's Accept header (26.11.3: a
  * scanner's GET /auth with an Accept without text/html got a 500, "No CSRF token was generated
  * for this request", since Play's filter by default creates a token only for a GET or HEAD
  * that accepts HTML, and the sign-in form needs one). Through the router and the release's
  * filters ([[ProductionFilters]], CSRF on), as in production.
  */
class PageCsrfTokenSpec extends PlaySpecification with PlayTestDb with AccountTestSupport with AfterAll {

  sequential

  private val SetupToken = "s" * 40

  /** The app with the setup token, for /setup. */
  private val withSetupApp = new ProductionFilters.OwnApp(Map("wlxjury.setup.token" -> SetupToken))

  override def afterAll(): Unit = withSetupApp.stop()

  private val TokenField = """name="csrfToken" value="([^"]+)"""".r

  /** Accept headers of clients that don't ask for HTML (None: no Accept header at all). */
  private val NonHtmlAccepts: Seq[Option[String]] =
    Seq(None, Some("*/*"), Some("application/json"), Some("text/plain"), Some("image/webp"),
      Some("application/xml;q=0.9"))

  private def get(app: Application, path: String, accept: Option[String], method: String = GET,
                  session: Seq[(String, String)] = Nil): Future[Result] = {
    val request = FakeRequest(method, path).withSession(session: _*)
    route(app, accept.fold(request)(a => request.withHeaders(ACCEPT -> a))).get
  }

  private def tokenIn(result: Future[Result]): Option[String] =
    TokenField.findFirstMatchIn(contentAsString(result)).map(_.group(1))

  "a signed-out page with a form, for a client that doesn't ask for HTML" should {
    "render the sign-in page with the token in the form and the session, never a 500" in {
      ProductionFilters.withSharedApp { implicit app =>
        createContests(10)
        forall(NonHtmlAccepts) { accept =>
          val page = get(app, "/auth", accept)
          status(page) aka s"$accept" must_== OK
          tokenIn(page) aka s"$accept" must beSome
          session(page).get("csrfToken") aka s"$accept" must beSome
        }
      }
    }

    "answer a HEAD of the sign-in page, never a 500" in {
      ProductionFilters.withSharedApp { implicit app =>
        forall(NonHtmlAccepts) { accept =>
          status(get(app, "/auth", accept, method = HEAD)) aka s"$accept" must_== OK
        }
      }
    }

    "render the setup page with the token" in {
      withSetupApp { implicit app =>
        forall(NonHtmlAccepts) { accept =>
          val page = get(app, "/setup", accept)
          status(page) aka s"$accept" must_== OK
          tokenIn(page) aka s"$accept" must beSome
        }
      }
    }

    "never answer the home page, a mailed link's page, sign-out or an error page with a 500" in {
      ProductionFilters.withSharedApp { implicit app =>
        forall(for (path <- Seq("/", "/invitation/t", "/email/confirm/t", "/logout", "/error", "/no-such-page");
                    accept <- NonHtmlAccepts) yield (path, accept)) { case (path, accept) =>
          status(get(app, path, accept)) aka s"$path $accept" must be_<(500)
        }
      }
    }

    "let the sign-in in with the token the page gave a non-HTML client" in {
      ProductionFilters.withSharedApp { implicit app =>
        createContests(10)
        val juror = account("juror@example.com", Some(10), "jury", verified = true)
        val page = get(app, "/auth", Some("application/json"))
        val inSession = session(page).get("csrfToken").get
        val result = route(app, FakeRequest(POST, "/auth")
          .withHeaders(ACCEPT -> "text/html", COOKIE -> "PLAY_SESSION=from-the-page")
          .withSession("csrfToken" -> inSession)
          .withFormUrlEncodedBody("login" -> "juror@example.com", "password" -> "secret",
            "csrfToken" -> tokenIn(page).get, "returnTo" -> s"/users/edit/${juror.getId}")).get
        status(result) === SEE_OTHER
        redirectLocation(result) must beSome(s"/users/edit/${juror.getId}")
        session(result).get(Secured.UserName) must beSome("juror@example.com")
      }
    }
  }

  "a browser's sign-in page (Accept text/html)" should {
    "still have the token in the form and the session" in {
      ProductionFilters.withSharedApp { implicit app =>
        val page = get(app, "/auth", Some("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"))
        status(page) === OK
        tokenIn(page) must beSome
        session(page).get("csrfToken") must beSome
      }
    }
  }

  "the templates" should {
    "render the token only through csrfField, never Play's helper that throws without one" in {
      // helper.CSRF.formField and CSRF.getToken(...).get fail with a 500 on a request without a
      // token; csrfField renders it if there is one (and the filter gives every GET one)
      val Throwing = """helper\.CSRF\.formField|helper\.CSRF\.getToken|CSRF\.getToken(\([^)]*\))?\.get\b""".r
      SourceScan.hits(SourceScan.files("app", ".scala", ".html"), Throwing) must beEmpty
    }
  }

  "a signed-in page with forms, for a client that doesn't ask for HTML and has no token yet" should {
    "render with the token, never a 500" in {
      ProductionFilters.withSharedApp { implicit app =>
        createContests(10)
        val root = account("root@example.com", None, User.ROOT_ROLE, root = true, verified = true)
        val juror = account("juror@example.com", Some(10), "jury", verified = true)
        val asRoot = Seq(Secured.UserName -> root.email, Secured.CurrentContestId -> "10")
        val pages = Seq(
          "/contests", "/admin/users?contestId=10", "/admin/allUsers", "/admin/rounds?contestId=10",
          "/admin/rounds/edit?contestId=10", s"/users/edit/${juror.getId}", "/admin/importusers?contestId=10",
          "/admin/greeting?contestId=10", "/admin/criteria?contestId=10", "/contest/10/images",
          s"/admin/users/${juror.getId}/tokens"
        )
        forall(for (path <- pages; accept <- Seq(Some("application/json"), Some("text/plain"))) yield (path, accept)) {
          case (path, accept) =>
            val page = get(app, path, accept, session = asRoot)
            status(page) aka s"$path $accept" must_== OK
            tokenIn(page) aka s"$path $accept" must beSome
        }
        val asJuror = Seq(Secured.UserName -> juror.email, Secured.CurrentContestId -> "10")
        val home = get(app, "/", Some("application/json"), session = asJuror)
        status(home) must be_<(500)
      }
    }
  }
}
