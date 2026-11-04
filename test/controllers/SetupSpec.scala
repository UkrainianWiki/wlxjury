package controllers

import db.scalikejdbc.{PlayTestDb, SharedTestDb, User}
import play.api.Application
import play.api.test.CSRFTokenHelper._
import play.api.test._
import services.PasswordHasher

import java.util.concurrent.{Callable, Executors}

/** P20: the first account of an empty installation is created on /setup with the configured
  * setup token, never by a public form; once an account exists, or without a token, there is
  * no such page; of two concurrent setups only one creates root.
  */
class SetupSpec extends PlaySpecification with PlayTestDb with AccountTestSupport {

  sequential

  private val SetupToken = "s" * 40

  private def post(fields: (String, String)*)(implicit app: Application) =
    route(app, FakeRequest(POST, "/setup").withFormUrlEncodedBody(fields: _*).withCSRFToken).get

  private def form(token: String = SetupToken, email: String = "Root@Example.com", password: String = "root-password") =
    Seq("token" -> token, "login" -> email, "password" -> password, "repeat.password" -> password)

  "without a setup token [R-02]" should {
    "have no setup page, even on an empty database, and the login page say so" in {
      testDbApp { implicit app =>
        status(route(app, FakeRequest(GET, "/setup")).get) === NOT_FOUND
        status(post(form(): _*)) === NOT_FOUND
        User.count() === 0L
        contentAsString(route(app, FakeRequest(GET, "/auth").withCSRFToken).get) must contain("This installation has no accounts yet.")
      }
    }
  }

  "with a setup token [R-02]" should {
    implicit val config: Map[String, String] = Map("wlxjury.setup.token" -> SetupToken)

    "create root once with the right token, verified, and then be gone" in {
      testDbApp { implicit app =>
        SharedTestDb.truncateAll() // a per-test app's database is not truncated
        status(route(app, FakeRequest(GET, "/setup").withCSRFToken).get) === OK

        val wrong = post(form(token = "x" * 40): _*)
        status(wrong) === BAD_REQUEST
        contentAsString(wrong) must contain("The setup token is not correct.")
        status(post(form(password = "a" * 73): _*)) === BAD_REQUEST
        User.count() === 0L

        val created = post(form(): _*)
        status(created) === SEE_OTHER
        // kept signed in (no checkbox here): modules.SessionLifetimeFilter sends the session as its own
        // persistent cookie, so it is read from the cookie, not session(created)
        val baker = app.injector.instanceOf[play.api.mvc.SessionCookieBaker]
        val createdSession = baker.decodeFromCookie(cookies(created).get(baker.COOKIE_NAME))
        createdSession.get(Secured.UserName) === Some("root@example.com")
        createdSession.get(controllers.SessionLifetime.Keep) === Some("1")
        val root = User.findByEmail("root@example.com").head
        root.isRoot must beTrue
        root.emailVerified must beTrue
        User.login("root@example.com", "root-password").flatMap(_.id) === root.id

        status(route(app, FakeRequest(GET, "/setup")).get) === NOT_FOUND
        status(post(form(email = "second@example.com"): _*)) === NOT_FOUND
        User.count() === 1L
      }
    }

    "create one root of two concurrent setups" in {
      testDbApp { implicit app =>
        SharedTestDb.truncateAll()
        val hash = PasswordHasher.default.hash("root-password")
        val pool = Executors.newFixedThreadPool(2)
        try {
          val results = Seq("first@example.com", "second@example.com")
            .map(email => pool.submit(new Callable[Option[User]] { def call() = User.createFirstRoot(email, hash) }))
            .map(_.get())
          results.count(_.isDefined) === 1
          User.findAll().count(_.isRoot) === 1
        } finally pool.shutdown()
      }
    }

    "refuse a short token from the configuration" in {
      def tokenOf(value: String) =
        new services.UserService(new RecordingMail, play.api.Configuration("wlxjury.setup.token" -> value)).setupToken
      (tokenOf(SetupToken) must beSome(SetupToken)) and (tokenOf("short") must beNone) and (tokenOf(" " * 40) must beNone)
    }
  }
}
