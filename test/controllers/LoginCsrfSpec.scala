package controllers

import db.scalikejdbc.{ContestJuryJdbc, PlayTestDb, User}
import org.specs2.specification.AfterAll
import play.api.Application
import play.api.libs.json.Json
import play.api.mvc.Result
import play.api.test.{FakeRequest, PlaySpecification}
import security.ProductionFilters
import services.ApiTokenService

import scala.concurrent.Future

/** N6 (security batch S7): login CSRF. Through the router and the release's filters
  * ([[ProductionFilters]], `play.filters.csrf.header.protectHeaders = null`): a POST from a
  * browser with no cookie for the site needs the CSRF token like any other, so another site
  * can't sign the visitor in to an account of its choosing, nor post the setup, invitation or
  * email-confirmation forms. The sign-in page sets the token (in the session cookie and the
  * form), which lets the sign-in in. A tool's Bearer request to the API still needs none.
  */
class LoginCsrfSpec extends PlaySpecification with PlayTestDb with AccountTestSupport with api.JsonFormat
    with AfterAll {

  sequential

  private val email = "juror@example.com"

  /** A setup token (at least 32 characters), configured where /setup is tested. */
  private val SetupToken = "s" * 40

  /** The app with the setup token, for this spec's examples that test /setup. */
  private val withSetupApp = new ProductionFilters.OwnApp(Map("wlxjury.setup.token" -> SetupToken))

  override def afterAll(): Unit = withSetupApp.stop()

  private val TokenField = """name="csrfToken" value="([^"]+)"""".r

  /** A POST with a form and no cookie, as a forged form on another site sends it from a browser
    * that never visited this one. */
  private def cookieless(app: Application, path: String, form: (String, String)*): Future[Result] =
    route(app, FakeRequest(POST, path).withHeaders(ACCEPT -> "text/html").withFormUrlEncodedBody(form: _*)).get

  private def signIn(password: String) = Seq("login" -> email, "password" -> password)

  "a cookieless POST without the CSRF token" should {
    "not sign in, even with the right password" in {
      ProductionFilters.withSharedApp { implicit app =>
        createContests(10)
        account(email, Some(10), "jury", verified = true)
        val result = cookieless(app, "/auth", signIn("secret"): _*)
        status(result) === FORBIDDEN
        session(result).get(Secured.UserName) must beNone
        session(result).get(Secured.AccountId) must beNone
      }
    }

    "be refused for the setup, invitation and email-confirmation forms" in {
      // the setup form as it would pass (the configured token, an empty installation): refused only for CSRF
      withSetupApp { implicit app =>
        forall(Seq(
          "/setup" -> Seq("token" -> SetupToken, "login" -> "root@example.com", "password" -> "root-password",
            "repeat.password" -> "root-password"),
          "/invitation" -> Seq("token" -> "t", "password" -> "a-long-password"),
          "/email/confirm" -> Seq("token" -> "t")
        )) { case (path, form) =>
          status(cookieless(app, path, form: _*)) aka path must_== FORBIDDEN
        }
        User.count() === 0
      }
    }

    "be refused with a token the session doesn't hold" in {
      ProductionFilters.withSharedApp { implicit app =>
        createContests(10)
        account(email, Some(10), "jury", verified = true)
        val forged = CsrfTestApp.token(app)
        val result = cookieless(app, "/auth", signIn("secret") :+ ("csrfToken" -> forged): _*)
        status(result) === FORBIDDEN
        session(result).get(Secured.UserName) must beNone
      }
    }
  }

  "the sign-in page" should {
    "set the CSRF token in the session and the form, which lets the sign-in in" in {
      ProductionFilters.withSharedApp { implicit app =>
        createContests(10)
        val juror = account(email, Some(10), "jury", verified = true)
        val page = route(app, FakeRequest(GET, "/auth").withHeaders(ACCEPT -> "text/html")).get
        status(page) === OK
        val inSession = session(page).get("csrfToken")
        inSession must beSome
        val inForm = TokenField.findFirstMatchIn(contentAsString(page)).map(_.group(1))
        inForm must beSome

        val result = route(app, FakeRequest(POST, "/auth")
          .withHeaders(ACCEPT -> "text/html", COOKIE -> "PLAY_SESSION=from-the-page")
          .withSession("csrfToken" -> inSession.get)
          .withFormUrlEncodedBody(signIn("secret") ++ Seq("csrfToken" -> inForm.get,
            "returnTo" -> s"/users/edit/${juror.getId}"): _*)).get
        status(result) === SEE_OTHER
        redirectLocation(result) must beSome(s"/users/edit/${juror.getId}")
        session(result).get(Secured.UserName) must beSome(email)
      }
    }

    "set the token for a browser with no cookie at all, the setup page too" in {
      withSetupApp { implicit app =>
        forall(Seq("/auth", "/setup")) { path =>
          val page = route(app, FakeRequest(GET, path).withHeaders(ACCEPT -> "text/html")).get
          status(page) aka path must_== OK
          session(page).get("csrfToken") aka path must beSome
          TokenField.findFirstMatchIn(contentAsString(page)) aka path must beSome
        }
      }
    }

    "let the setup in with the token its page set" in {
      withSetupApp { implicit app =>
        val page = route(app, FakeRequest(GET, "/setup").withHeaders(ACCEPT -> "text/html")).get
        val inSession = session(page).get("csrfToken").get
        val inForm = TokenField.findFirstMatchIn(contentAsString(page)).map(_.group(1)).get
        val created = route(app, FakeRequest(POST, "/setup")
          .withHeaders(ACCEPT -> "text/html", COOKIE -> "PLAY_SESSION=from-the-page")
          .withSession("csrfToken" -> inSession)
          .withFormUrlEncodedBody("token" -> SetupToken, "login" -> "root@example.com", "password" -> "root-password",
            "repeat.password" -> "root-password", "csrfToken" -> inForm)).get
        status(created) === SEE_OTHER
        User.count() === 1
      }
    }
  }

  "the API" should {
    "let a tool's Bearer write in without a CSRF token, and refuse a cookieless write with no credentials" in {
      ProductionFilters.withSharedApp { implicit app =>
        createContests(10)
        val root = account("root@example.com", None, User.ROOT_ROLE, root = true, verified = true)
        val token = ApiTokenService.create(root.getId, "cli", Some(90))
        val contest = Json.toJson(org.intracer.wmua.ContestJury(None, "created by a tool", 2026, "Ukraine", images = None))

        val tool = route(app, FakeRequest(POST, "/api/contests").withHeaders(AUTHORIZATION -> s"Bearer $token")
          .withJsonBody(contest)).get
        status(tool) === OK
        ContestJuryJdbc.findAll().map(_.name) must contain("created by a tool")

        val anonymous = route(app, FakeRequest(POST, "/api/contests").withJsonBody(contest)).get
        status(anonymous) === FORBIDDEN
        (contentAsJson(anonymous) \ "error").as[String] === "csrf"
        // what csrf means here for a tool: send the token
        (contentAsJson(anonymous) \ "hint").as[String] must contain("Authorization: Bearer")
        ContestJuryJdbc.findAll().count(_.name == "created by a tool") === 1
      }
    }
  }
}
