package controllers

import db.scalikejdbc.{PlayTestDb, User, UserContestJdbc}
import org.apache.pekko.stream.Materializer
import org.specs2.mock.Mockito
import play.api.Application
import play.api.i18n.{Lang, MessagesApi}
import play.api.mvc._
import play.api.test.CSRFTokenHelper._
import play.api.test._
import services.{SMTPOrWikiMail, UserService}

class LoginControllerSpec extends PlaySpecification with Results with PlayTestDb with Mockito {

  sequential

  def userService(implicit app: Application) =
    new UserService(mock[SMTPOrWikiMail], app.configuration)
  private def signIn(implicit app: Application) = app.injector.instanceOf[modules.SignInExecutionContext]

  def login(implicit app: Application) =
    new LoginController(userService, Helpers.stubControllerComponents(), signIn)

  /** With the app's messages, for the pages' text. */
  def appLogin(implicit app: Application) =
    new LoginController(userService, app.injector.instanceOf[ControllerComponents], signIn)

  "auth [J-02]" should {
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

    "keep the login page, with its error, for a wrong password [J-03]" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        User.create(User("name", "qwerty@dot.com", password = Some(services.PasswordHasher.default.hash("strong"))))

        val result = login
          .auth()
          .apply(
            FakeRequest()
              .withFormUrlEncodedBody(
                "login" -> "qwerty@dot.com",
                "password" -> "1234"
              )
              .withCSRFToken)

        status(result) === BAD_REQUEST
        val page = contentAsString(result)
        // this controller renders message keys (no messages file): the form's error, in its alert
        page must contain("role=\"alert\"")
        page must contain("invalid.user.or.password")
        page must contain("name=\"password\"")
        session(result).get(Secured.UserName) must beNone
      }
    }

    "no rights" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        User.create(
          User("name", "qwerty@dot.com", password = Some(services.PasswordHasher.default.hash("strong"))))
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
        contentAsString(result) must contain("If you need access, ask your contest admin.")
        session(result).get(Secured.UserName) === Some("qwerty@dot.com")
      }
    }

    "root rights" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        User.create(
          User("name",
               "qwerty@dot.com",
               password = Some(services.PasswordHasher.default.hash("strong")),
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

    "return to the requested local page after signing in [J-07]" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        User.create(User("name", "return@dot.com", password = Some(services.PasswordHasher.default.hash("strong"))))

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

    // a regression test: inCurrentContest picks an active membership, so it also passes
    // without auth's permitted fallback
    "(regression) start in the contest where the user is an active member, never a deactivated one" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val juror = User.create(User("Juror", "juror@dot.com", None, Set("jury"), Some(services.PasswordHasher.default.hash("strong")), Some(10L)))
        UserContestJdbc.createMembership(juror.getId, 20L, User.NO_ROLE)

        val result = appLogin.auth().apply(
          FakeRequest().withFormUrlEncodedBody("login" -> "juror@dot.com", "password" -> "strong").withCSRFToken
        )

        // a juror of contest 10 without a round yet: the page saying so, not a denial
        status(result) === OK
        contentAsString(result) must contain("No round configured yet.")
        session(result).get(Secured.CurrentContestId) === Some("10")
      }
    }

    "deny a user whose memberships are all deactivated, saying so" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val former = User.create(User("Former", "former@dot.com", None, Set(User.NO_ROLE), Some(services.PasswordHasher.default.hash("strong")), Some(10L)))
        UserContestJdbc.createMembership(former.getId, 20L, User.NO_ROLE)

        val result = appLogin.auth().apply(
          FakeRequest().withFormUrlEncodedBody("login" -> "former@dot.com", "password" -> "strong").withCSRFToken
        )

        status(result) === FORBIDDEN
        contentAsString(result) must contain("Your membership in this contest has been deactivated.")
        val signedIn = Secured.inCurrentContest(User.findById(former.getId).get, None)
        Secured.denialLogLine(signedIn, FakeRequest(POST, "/auth"),
          Secured.denialOf(Secured.RolePermission(Secured.ViewerRoles)), "0a1b2c3d") must
          endWith("reason=membership_inactive")
      }
    }

    "ignore an external return URL after signing in [J-07]" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        User.create(
          User(
            "root",
            "root-return@dot.com",
            password = Some(services.PasswordHasher.default.hash("strong")),
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

  "a request without a session [J-07]" should {
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

  "the error page [S-05]" should {
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

    "keep the apostrophes of a French message (MessageFormat drops a single one)" in {
      testDbApp { implicit app =>
        val messages = app.injector.instanceOf[MessagesApi]
        messages("no.round.yet")(Lang("fr")) must contain("l'administrateur du concours configure l'outil")
      }
    }

    "send a juror without an active round to a page saying so [J-08]" in {
      testDbApp { implicit app =>
        val contest = createContests(10).head
        val juror = User.create(User("Juror", "juror@dot.com", None, Set("jury"), Some("hash"), contest.id))

        val result = route(app, FakeRequest(GET, "/").withSession(Secured.UserName -> juror.email)).get
        status(result) === OK
        contentAsString(result) must contain("No round configured yet.")
      }
    }
  }

  "the login page [J-02]" should {
    "show the sign-in form on an empty database, never a sign-up (S2, P20)" in {
      testDbApp { implicit app =>
        val page = appLogin.login().apply(FakeRequest(GET, "/auth").withCSRFToken)
        status(page) === OK
        val html = contentAsString(page)
        html must contain("""action="/auth"""")
        html must not(contain("/signup"))
        html must contain("This installation has no accounts yet.")
      }
    }
  }

  "signing in [J-02]" should {
    "put the account's auth version in the session, which a later version refuses" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        val root = User.create(User("root", "versioned@dot.com",
          password = Some(services.PasswordHasher.default.hash("strong")), isRoot = true))
        scalikejdbc.DB.autoCommit { implicit s =>
          scalikejdbc.SQL("UPDATE users SET auth_version = 3 WHERE id = ?").bind(root.getId).update.apply()
        }
        val result = login.auth().apply(
          FakeRequest().withFormUrlEncodedBody("login" -> "versioned@dot.com", "password" -> "strong").withCSRFToken)
        session(result).get(Secured.AuthVersion) === Some("3")
        Secured.sessionUser("versioned@dot.com", None, Some("3"), None) must beSome
        Secured.sessionUser("versioned@dot.com", None, None, None) must beNone
      }
    }
  }

  "the sign-in page" should {
    "name the product WLX Jury, one name untranslated, in its title, heading and top bar" in {
      testDbApp { implicit app =>
        val messages = app.injector.instanceOf[MessagesApi]
        val doc = org.jsoup.Jsoup.parse(contentAsString(route(app, FakeRequest(GET, "/auth").withCSRFToken).get))
        forall(Seq("en", "fr", "uk", "ru"))(l => messages("application.name")(Lang(l)) === "WLX Jury") and
          (doc.title === "WLX Jury") and
          (doc.select("h1").text === "WLX Jury") and
          (doc.select("nav .navbar-brand").text === "WLX Jury") and
          // sentence case, as the other buttons
          (doc.select("button#submit").text === "Sign in")
      }
    }

    "name the product in the account mails, as plain text with the link on a line of its own" in {
      testDbApp { implicit app =>
        val messages = app.injector.instanceOf[MessagesApi]
        val link = "https://jury.example.org/invitation/abc"
        val mails = Seq("invitation.subject.tool" -> Nil, "invitation.body.tool" -> Seq(link, 7, "Anna Admin"),
          "password.link.subject" -> Nil, "password.link.body" -> Seq(link, 7),
          "email.change.subject" -> Nil, "email.change.body" -> Seq(link, 24, "jury.example.org"),
          "email.changed.subject" -> Nil, "email.changed.body" -> Seq("new@example.org"),
          "email.changed.by.admin.body" -> Seq("n***@example.org", "Anna Admin", "jury.example.org"))
        forall(for (l <- Seq("en", "fr", "uk", "ru"); (key, args) <- mails) yield (l, key, args)) { case (l, key, args) =>
          val text = messages(key, args: _*)(Lang(l))
          (text must contain("WLX Jury")) and (text must not(contain("<"))) and
            ((!args.headOption.contains(link) || text.split("\n").contains(link)) must beTrue)
        }
      }
    }
  }
}
