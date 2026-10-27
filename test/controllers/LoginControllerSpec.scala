package controllers

import db.scalikejdbc.{PlayTestDb, User}
import org.apache.pekko.stream.Materializer
import org.specs2.mock.Mockito
import play.api.Application
import play.api.mvc._
import play.api.test.CSRFTokenHelper._
import play.api.test._
import services.{SMTPOrWikiMail, UserService}

class LoginControllerSpec extends PlaySpecification with Results with PlayTestDb with Mockito {

  sequential

  def userService(implicit app: Application) =
    new UserService(mock[SMTPOrWikiMail], app.configuration)
  def login(implicit app: Application) =
    new LoginController(userService, Helpers.stubControllerComponents())

  /** With the app's messages, for the pages' text. */
  def appLogin(implicit app: Application) =
    new LoginController(userService, app.injector.instanceOf[ControllerComponents])

  "auth" should {
    "fail empty request" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        val result = login.auth().apply(FakeRequest().withCSRFToken)
        status(result) === BAD_REQUEST
      }
    }

    "fail when no users" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        val result = login
          .auth()
          .apply(
            FakeRequest()
              .withHeaders(
                "login" -> "qwerty",
                "password" -> "1234"
              )
              .withCSRFToken)

        status(result) === BAD_REQUEST
      }
    }

    "fail when wrong password" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        User.create(User("name", "qwerty@dot.com"))

        val result = login
          .auth()
          .apply(
            FakeRequest()
              .withHeaders(
                "login" -> "qwerty@dot.com",
                "password" -> "1234"
              )
              .withCSRFToken)

        status(result) === BAD_REQUEST
      }
    }

    "no rights" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        User.create(
          User("name", "qwerty@dot.com", password = Some(User.sha1("strong"))))
        val result = appLogin
          .auth()
          .apply(
            FakeRequest()
              .withFormUrlEncodedBody(
                "login" -> "qwerty@dot.com",
                "password" -> "strong"
              )
              .withCSRFToken)

        // the denial page, saying whom to ask, not a redirect to /error
        status(result) === FORBIDDEN
        contentAsString(result) must contain("If you need access, ask your contest organizer.")
        session(result).get(Secured.UserName) === Some("qwerty@dot.com")
      }
    }

    "root rights" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        User.create(
          User("name",
               "qwerty@dot.com",
               password = Some(User.sha1("strong")),
               isRoot = true)
        )
        val result = login
          .auth()
          .apply(
            FakeRequest()
              .withFormUrlEncodedBody(
                "login" -> "qwerty@dot.com",
                "password" -> "strong"
              )
              .withCSRFToken)

        status(result) === SEE_OTHER
        header(LOCATION, result) === Some("/contests")
      }
    }

    "return to the requested local page after signing in" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        User.create(User("name", "return@dot.com", password = Some(User.sha1("strong"))))

        val result = login.auth().apply(
          FakeRequest()
            .withFormUrlEncodedBody(
              "login" -> "return@dot.com",
              "password" -> "strong",
              "returnTo" -> "/contest/628/files"
            )
            .withCSRFToken
        )

        status(result) === SEE_OTHER
        header(LOCATION, result) === Some("/contest/628/files")
        session(result).get(Secured.UserName) === Some("return@dot.com")
      }
    }

    "ignore an external return URL after signing in" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        User.create(
          User(
            "root",
            "root-return@dot.com",
            password = Some(User.sha1("strong")),
            roles = Set(User.ROOT_ROLE)
          )
        )

        val result = login.auth().apply(
          FakeRequest()
            .withFormUrlEncodedBody(
              "login" -> "root-return@dot.com",
              "password" -> "strong",
              "returnTo" -> "https://evil.example/"
            )
            .withCSRFToken
        )

        status(result) === SEE_OTHER
        header(LOCATION, result) === Some("/contests")
      }
    }

  }

  "a request without a session" should {
    "go to the login page, which comes back to a GET's page" in {
      testDbApp { implicit app =>
        val page = route(app, FakeRequest(GET, "/contest/628/files?page=2")).get
        status(page) === SEE_OTHER
        redirectLocation(page) === Some(routes.LoginController.login(Some("/contest/628/files?page=2")).url)
        redirectLocation(page) === Some("/auth?returnTo=%2Fcontest%2F628%2Ffiles%3Fpage%3D2")

        val post = route(app, FakeRequest(POST, "/admin/setround").withHeaders("Csrf-Token" -> "nocheck")).get
        status(post) === SEE_OTHER
        redirectLocation(post) === Some("/auth")
      }
    }
  }

  "the error page" should {
    "show only known messages, never the link's text" in {
      testDbApp { implicit app =>
        val contest = createContests(10).head
        val juror = User.create(User("Juror", "juror@dot.com", None, Set("jury"), Some("hash"), contest.id))

        def errorPage(message: String) = {
          val result = route(app,
            FakeRequest(GET, s"/error?message=${java.net.URLEncoder.encode(message, "UTF-8")}")
              .withSession(Secured.UserName -> juror.email)).get
          (status(result), contentAsString(result))
        }

        val (arbitraryStatus, arbitrary) = errorPage("Your account is suspended, call +1 555 0100")
        arbitraryStatus === OK
        arbitrary must not(contain("suspended"))
        arbitrary must contain("Something went wrong.")

        errorPage("no.round.yet")._2 must contain("No round configured yet.")
      }
    }

    "send a juror without an active round to a page saying so" in {
      testDbApp { implicit app =>
        val contest = createContests(10).head
        val juror = User.create(User("Juror", "juror@dot.com", None, Set("jury"), Some("hash"), contest.id))

        val result = route(app, FakeRequest(GET, "/").withSession(Secured.UserName -> juror.email)).get
        status(result) === OK
        contentAsString(result) must contain("No round configured yet.")
      }
    }
  }

  "signUp" should {
    "log in as the new user, not as the user named by the password" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        User.create(
          User("root",
               "root@dot.com",
               password = Some(User.sha1("strong")),
               roles = Set(User.ROOT_ROLE))
        )
        val result = login
          .signUp()
          .apply(
            FakeRequest()
              .withFormUrlEncodedBody(
                "login" -> "new@dot.com",
                "password" -> "root@dot.com",
                "repeat.password" -> "root@dot.com"
              )
              .withCSRFToken)

        // a new account has no role in any contest yet: the denial page
        status(result) === FORBIDDEN
        session(result).get(Secured.UserName) === Some("new@dot.com")
      }
    }

    def signUp(loginName: String)(implicit app: Application) = {
      implicit val mat: Materializer = app.materializer
      login
        .signUp()
        .apply(
          FakeRequest()
            .withFormUrlEncodedBody(
              "login" -> loginName,
              "password" -> "anything",
              "repeat.password" -> "anything"
            )
            .withCSRFToken)
    }

    "not log in as an existing account with the email" in {
      testDbApp { implicit app =>
        val root = User.create(
          User("root", "root@dot.com", password = Some(User.sha1("strong")), roles = Set(User.ROOT_ROLE))
        )
        val result = signUp("Root@Dot.com")

        status(result) === BAD_REQUEST
        session(result).get(Secured.UserName) must beNone
        User.findAll().map(_.id) === Seq(root.id)
        User.findById(root.getId).flatMap(_.password) === Some(User.sha1("strong"))
      }
    }

    "not take a login that is not an email: it would be read as someone's wiki account" in {
      testDbApp { implicit app =>
        User.create(User("Wiki user", "wiki@dot.com", wikiAccount = Some("WikiUser"), password = Some("x")))
        val result = signUp("WikiUser")

        status(result) === BAD_REQUEST
        session(result).get(Secured.UserName) must beNone
        User.findAll().size === 1
      }
    }
  }
}
