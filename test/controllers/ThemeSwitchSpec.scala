package controllers

import controllers.CsrfTestApp.{token, withCsrfApp}
import db.scalikejdbc.{PlayTestDb, SharedTestDb, User, UserContestJdbc}
import play.api.Application
import org.specs2.mock.Mockito
import play.api.mvc.{ControllerComponents, Cookie, Result}
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import services.{SMTPOrWikiMail, UserService}

import scala.concurrent.Future

/** The colour mode switch (UserController.setTheme, `POST /theme`): a POST with the CSRF
  * token, through the router and the filters as in production, that stores light, dark or
  * auto in the signed-in user's own row and in the theme cookie, and goes back to a local
  * page. Signing in and saving the own profile set the cookie from the user's row.
  */
class ThemeSwitchSpec extends PlaySpecification with PlayTestDb with Mockito {

  sequential

  private val email = "tina@example.com"
  private val otherEmail = "omar@example.com"
  private val rootEmail = "root@example.com"
  private val loneEmail = "lone@example.com"

  /** Tina, a juror of contest 10 and organizer of 20 (the session in 10); Omar, a juror of
    * 10; a root user without a contest; and a user who is in no contest.
    */
  private def setUp(): Unit = {
    createContests(10, 20)
    val tina = User.create(User(email, email, None, Set("jury"), Some(User.sha1("secret")), Some(10L)))
    UserContestJdbc.createMembership(tina.getId, 20, "organizer")
    User.create(User(otherEmail, otherEmail, None, Set("jury"), Some(User.sha1("secret")), Some(10L)))
    User.create(User(rootEmail, rootEmail, password = Some(User.sha1("secret")), isRoot = true))
    User.create(User(loneEmail, loneEmail, password = Some(User.sha1("secret"))))
  }

  private def themeOf(userEmail: String): Option[String] = User.findByEmail(userEmail).head.theme

  /** A signed-in browser's POST: a session cookie (which makes the CSRF filter check it), the
    * session's CSRF token and, if given, contest 10.
    */
  private def post(app: Application, path: String, sessionToken: String, as: String = email,
                   contest: Option[String] = Some("10"), body: Seq[(String, String)] = Nil): Future[Result] =
    route(app, FakeRequest(POST, path)
      .withHeaders(COOKIE -> "PLAY_SESSION=signed-in", ACCEPT -> "text/html")
      .withSession(Seq(Secured.UserName -> as, "csrfToken" -> sessionToken) ++
        contest.map(Secured.CurrentContestId -> _): _*)
      .withFormUrlEncodedBody(body: _*)).get

  private def themeCookie(result: Future[Result]): Option[Cookie] = cookies(result).get(Theme.CookieName)

  "the theme switch" should {

    "be refused without the CSRF token, or with a forged one, changing nothing" in {
      withCsrfApp { app =>
        setUp()
        val missing = post(app, "/theme", token(app), body = Seq("theme" -> "dark"))
        status(missing) === FORBIDDEN
        themeCookie(missing) must beNone
        val forged = post(app, "/theme", token(app), body = Seq("theme" -> "dark", "csrfToken" -> "forged"))
        status(forged) === FORBIDDEN
        themeCookie(forged) must beNone
        themeOf(email) must beNone
      }
    }

    "treat a signed-out POST as other POSTs, changing nothing" in {
      withCsrfApp { app =>
        setUp()
        def signedOut(path: String) =
          route(app, FakeRequest(POST, path).withHeaders(ACCEPT -> "text/html")
            .withFormUrlEncodedBody("theme" -> "dark")).get
        val theme = signedOut("/theme")
        val switch = signedOut("/switch-contest/20")
        status(theme) === status(switch)
        redirectLocation(theme) === redirectLocation(switch)
        themeCookie(theme) must beNone
        User.findAll().flatMap(_.theme) must beEmpty
      }
    }

    "store the mode in the user's own row and the cookie, and go back to the local page" in {
      withCsrfApp { app =>
        setUp()
        val sessionToken = token(app)
        val result = post(app, "/theme", sessionToken,
          body = Seq("theme" -> "dark", "csrfToken" -> sessionToken, "returnTo" -> "/contest/10/files?page=2",
            // a user id in the form changes nothing: only the session's user is updated
            "id" -> User.findByEmail(otherEmail).head.getId.toString))
        status(result) === SEE_OTHER
        redirectLocation(result) === Some("/contest/10/files?page=2")
        themeOf(email) === Some("dark")
        themeOf(otherEmail) must beNone

        val cookie = themeCookie(result)
        cookie.map(_.value) === Some("dark")
        cookie.map(_.path) === Some("/")
        cookie.flatMap(_.sameSite) === Some(Cookie.SameSite.Lax)
        cookie.map(_.httpOnly) === Some(false)
        cookie.exists(_.maxAge.exists(_ >= 30 * 24 * 3600)) must beTrue
        // the test config's session cookie is not secure, so neither is this one
        cookie.map(_.secure) === Some(false)

        // the session's contest stays as it was
        session(result).get(Secured.CurrentContestId) must beNone.or(beSome("10"))

        val auto = post(app, "/theme", sessionToken, body = Seq("theme" -> "auto", "csrfToken" -> sessionToken))
        status(auto) === SEE_OTHER
        themeOf(email) === Some("auto")
        themeCookie(auto).map(_.value) === Some("auto")
      }
    }

    "go to the start page for an external, protocol-relative or missing returnTo" in {
      withCsrfApp { app =>
        setUp()
        val sessionToken = token(app)
        forall(Seq(Some("https://evil.example/"), Some("//evil.example/x"), Some("/\\evil.example"), None)) { returnTo =>
          val result = post(app, "/theme", sessionToken,
            body = Seq("theme" -> "light", "csrfToken" -> sessionToken) ++ returnTo.map("returnTo" -> _))
          status(result) === SEE_OTHER
          redirectLocation(result) === Some(routes.LoginController.index.url)
          themeCookie(result).map(_.value) === Some("light")
        }
      }
    }

    "change nothing for a value other than light, dark or auto" in {
      withCsrfApp { app =>
        setUp()
        val sessionToken = token(app)
        User.updateTheme(User.findByEmail(email).head.getId, Theme.Dark)
        forall(Seq(Some("purple"), Some("\"><script>alert(1)</script>"), Some("DARK"), Some(""), None)) { value =>
          val result = post(app, "/theme", sessionToken,
            body = Seq("csrfToken" -> sessionToken, "returnTo" -> "/contest/10/files") ++ value.map("theme" -> _))
          status(result) === SEE_OTHER
          redirectLocation(result) === Some("/contest/10/files")
          themeCookie(result) must beNone
          themeOf(email) === Some("dark")
        }
      }
    }

    "work for root without a contest and for a user in no contest" in {
      withCsrfApp { app =>
        setUp()
        val sessionToken = token(app)
        val root = post(app, "/theme", sessionToken, as = rootEmail, contest = None,
          body = Seq("theme" -> "dark", "csrfToken" -> sessionToken))
        status(root) === SEE_OTHER
        themeOf(rootEmail) === Some("dark")
        themeCookie(root).map(_.value) === Some("dark")
        session(root).get(Secured.CurrentContestId) must beNone

        val lone = post(app, "/theme", sessionToken, as = loneEmail, contest = None,
          body = Seq("theme" -> "light", "csrfToken" -> sessionToken, "returnTo" -> "/users/edit/1"))
        status(lone) === SEE_OTHER
        redirectLocation(lone) === Some("/users/edit/1")
        themeOf(loneEmail) === Some("light")
      }
    }

    "be offered by the navbar as a POST form with the token and this page to come back to" in {
      withCsrfApp { app =>
        setUp()
        val page = route(app, FakeRequest(GET, "/users/edit/" + User.findByEmail(email).head.getId)
          .withHeaders(COOKIE -> "PLAY_SESSION=signed-in", ACCEPT -> "text/html")
          .withSession(Secured.UserName -> email, Secured.CurrentContestId -> "10", "csrfToken" -> token(app))
          .withCookies(Cookie(Theme.CookieName, "dark"))).get
        status(page) === OK
        val html = contentAsString(page)
        val form = html.substring(html.indexOf("""id="theme-switch""""))
        html must contain("""<form method="post" action="/theme"""")
        form must contain("""name="csrfToken"""")
        form must contain("""name="returnTo" value="/users/edit/""")
        form must contain("""name="theme" value="light" class="dropdown-item">""")
        form must contain("""name="theme" value="dark" class="dropdown-item active" aria-current="true">""")
        form must contain("""name="theme" value="auto" class="dropdown-item">""")
        html.split("""name="theme" value=""").length === 4
      }
    }

    "be an icon button left of the profile menu, the current mode's, not in the profile menu" in {
      withCsrfApp { app =>
        setUp()
        forall(Seq("light" -> "sun-fill", "dark" -> "moon-stars-fill", "auto" -> "circle-half")) { case (mode, icon) =>
          val page = route(app, FakeRequest(GET, "/users/edit/" + User.findByEmail(email).head.getId)
            .withHeaders(COOKIE -> "PLAY_SESSION=signed-in", ACCEPT -> "text/html")
            .withSession(Secured.UserName -> email, Secured.CurrentContestId -> "10", "csrfToken" -> token(app))
            .withCookies(Cookie(Theme.CookieName, mode))).get
          val html = contentAsString(page)
          val toggle = html.substring(html.indexOf("""id="theme-menu""""), html.indexOf("</a>", html.indexOf("""id="theme-menu"""")))
          val label = "Theme: " + Map("light" -> "Light", "dark" -> "Dark", "auto" -> "System")(mode)
          toggle must contain(s"""title="$label"""")
          toggle must contain(s"""<i class="bi bi-$icon" aria-hidden="true"></i><span class="visually-hidden">$label</span>""")
          html must contain("""<ul class="dropdown-menu dropdown-menu-end" aria-labelledby="theme-menu">""")
          // the switch, then the profile menu with the profile link and signing out only
          val profileMenu = html.substring(html.indexOf("bi-person-fill"))
          html.indexOf("""id="theme-switch"""") must be_<(html.indexOf("bi-person-fill"))
          profileMenu must contain("/logout")
          profileMenu must not(contain("""action="/theme""""))
        }
      }
    }
  }

  "signing in" should {

    "set the theme cookie from the user's row" in {
      testDbApp { implicit app =>
        setUp()
        User.updateTheme(User.findByEmail(email).head.getId, Theme.Dark)
        val result = route(app, FakeRequest(POST, "/auth")
          .withFormUrlEncodedBody("login" -> email, "password" -> "secret")).get
        status(result) must be_<(400)
        themeCookie(result).map(_.value) === Some("dark")
      }
    }

    "keep the browser's cookie when the user never chose a mode" in {
      testDbApp { implicit app =>
        setUp()
        val result = route(app, FakeRequest(POST, "/auth")
          .withFormUrlEncodedBody("login" -> email, "password" -> "secret")).get
        status(result) must be_<(400)
        themeCookie(result) must beNone
      }
    }
  }

  "saving a profile" should {

    def save(app: Application, as: String, user: User, theme: Option[String]) =
      route(app, FakeRequest(POST, "/users/save")
        .withSession(Secured.UserName -> as, Secured.CurrentContestId -> "10")
        .withFormUrlEncodedBody(Seq(
          "id" -> user.getId.toString,
          "fullname" -> user.fullname,
          "email" -> user.email,
          "contest" -> "10"
        ) ++ theme.map("theme" -> _): _*)).get

    "store the own profile's mode and set the cookie" in {
      testDbApp { implicit app =>
        setUp()
        val tina = User.findByEmail(email).head
        val result = save(app, email, tina, Some("light"))
        status(result) === SEE_OTHER
        themeOf(email) === Some("light")
        themeCookie(result).map(_.value) === Some("light")
      }
    }

    "change nothing for another value, or without the field" in {
      testDbApp { implicit app =>
        setUp()
        val tina = User.findByEmail(email).head
        User.updateTheme(tina.getId, Theme.Dark)
        forall(Seq(Some("purple"), None)) { value =>
          val result = save(app, email, tina, value)
          status(result) === SEE_OTHER
          themeOf(email) === Some("dark")
          themeCookie(result) must beNone
        }
      }
    }

    "not touch another user's mode, nor the admin's cookie, when an admin saves their profile" in {
      testDbApp { implicit app =>
        setUp()
        val admin = User.create(User("admin@example.com", "admin@example.com", None, Set("admin"), Some("hash"), Some(10L)))
        val omar = User.findByEmail(otherEmail).head
        val result = save(app, admin.email, omar, Some("dark"))
        status(result) === SEE_OTHER
        themeOf(otherEmail) must beNone
        themeOf(admin.email) must beNone
        themeCookie(result) must beNone
      }
    }

    "drop another value when an admin creates a user, with no error" in {
      testDbApp { implicit app =>
        setUp()
        val admin = User.create(User("admin@example.com", "admin@example.com", None, Set("admin"), Some("hash"), Some(10L)))
        implicit val mat: org.apache.pekko.stream.Materializer = app.materializer
        // a mailer that sends nothing: a new member may get the contest's greeting
        val controller = new UserController(app.injector.instanceOf[ControllerComponents],
          new UserService(mock[SMTPOrWikiMail], app.configuration))
        val result = call(controller.saveUser(), FakeRequest(POST, "/users/save")
          .withSession(Secured.UserName -> admin.email, Secured.CurrentContestId -> "10")
          .withFormUrlEncodedBody(
            "id" -> "0", "fullname" -> "New Juror", "email" -> "new@example.com", "roles" -> "jury",
            "contest" -> "10", "theme" -> "a-much-too-long-bogus-theme").withCSRFToken)
        status(result) === SEE_OTHER
        User.findByEmail("new@example.com").map(_.theme) === Seq(None)
        themeCookie(result) must beNone
      }
    }

    "show the own profile's mode in its radio buttons, the default with none, and none on another user's" in {
      testDbApp { implicit app =>
        setUp()
        val tina = User.findByEmail(email).head
        val own = route(app, FakeRequest(GET, "/users/edit/" + tina.getId)
          .withSession(Secured.UserName -> email, Secured.CurrentContestId -> "10")
          .withCookies(Cookie(Theme.CookieName, "dark")).withCSRFToken).get
        status(own) === OK
        val html = contentAsString(own)
        html must contain("""<div class="btn-group" role="group" aria-labelledby="theme-label">""")
        html must contain("""<legend class="form-label fs-6" id="theme-label">Theme</legend>""")
        html must contain("""<input type="radio" class="btn-check" name="theme" id="theme-light" value="light" autocomplete="off">""")
        html must contain("""<input type="radio" class="btn-check" name="theme" id="theme-dark" value="dark" autocomplete="off" checked>""")
        html must contain("""<input type="radio" class="btn-check" name="theme" id="theme-auto" value="auto" autocomplete="off">""")
        html must contain("""<label class="btn btn-outline-secondary" for="theme-auto"><i class="bi bi-circle-half" aria-hidden="true"></i> System</label>""")
        html must not(contain("""<select id="theme""""))

        // none stored and no cookie: the default, dark; the stored mode over the cookie
        def ownPage(cookies: Cookie*) = contentAsString(route(app, FakeRequest(GET, "/users/edit/" + tina.getId)
          .withSession(Secured.UserName -> email, Secured.CurrentContestId -> "10")
          .withCookies(cookies: _*).withCSRFToken).get)
        ownPage() must contain("""id="theme-dark" value="dark" autocomplete="off" checked>""")
        User.updateTheme(tina.getId, Theme.Auto)
        ownPage(Cookie(Theme.CookieName, "light")) must contain("""id="theme-auto" value="auto" autocomplete="off" checked>""")

        val admin = User.create(User("admin@example.com", "admin@example.com", None, Set("admin"), Some("hash"), Some(10L)))
        val other = route(app, FakeRequest(GET, "/users/edit/" + tina.getId)
          .withSession(Secured.UserName -> admin.email, Secured.CurrentContestId -> "10").withCSRFToken).get
        status(other) === OK
        // (the admin's own user menu still has its switch)
        contentAsString(other) must not(contain("""class="btn-check" name="theme""""))
      }
    }
  }

  "the theme cookie" should {
    "be Secure when the session cookie is" in {
      implicit val cfg: Map[String, String] = Map("play.http.session.secure" -> "true")
      testDbApp { implicit app =>
        // a fresh app with this config: the shared one's truncation doesn't run
        SharedTestDb.truncateAll()
        setUp()
        val result = route(app, FakeRequest(POST, "/theme")
          .withSession(Secured.UserName -> email, Secured.CurrentContestId -> "10")
          .withFormUrlEncodedBody("theme" -> "dark")).get
        status(result) === SEE_OTHER
        themeCookie(result).map(_.secure) === Some(true)
      }
    }
  }
}
