package controllers

import db.scalikejdbc.{PlayTestDb, User, UserContestJdbc, UserIdentity, UserIdentityJdbc}
import modules.BlockingExecutionContext
import org.apache.pekko.stream.Materializer
import org.specs2.mock.Mockito
import play.api.mvc._
import play.api.test.CSRFTokenHelper._
import play.api.test._
import play.api.{Application, Configuration}
import services.oauth._
import services.{SMTPOrWikiMail, UserService}

import java.net.URI
import scala.concurrent.Future

class OAuthControllerSpec extends PlaySpecification with Results with PlayTestDb with Mockito {

  sequential

  implicit def materializer(implicit app: Application): Materializer = app.materializer

  val Host  = "jury.example.org"
  val Other = "jury.other.example"

  /** Signs in whoever `profile` says, for the code "good-code". */
  class FakeProvider(val id: String) extends OAuthProvider {
    @volatile var profile: ExternalProfile = ExternalProfile(id, "nobody")

    def authorizationUri(client: OAuthClient, redirectUri: URI, flow: OAuthFlow): URI =
      URI.create(s"https://$id.example/authorize?client_id=${client.clientId}&state=${flow.state.getValue}&redirect_uri=$redirectUri")

    def exchange(client: OAuthClient, redirectUri: URI, code: String, flow: OAuthFlow): ExternalProfile =
      if (code == "good-code") profile
      else if (code == "boom-code") throw new IllegalStateException("database down")
      else throw new OAuthException("token error: invalid_grant")
  }

  val google    = new FakeProvider(OAuthProviders.Google)
  val wikimedia = new FakeProvider(OAuthProviders.Wikimedia)

  val settings = new OAuthSettings(
    Configuration.from(
      Map(
        "oauth.google.clientId"     -> "google-client",
        "oauth.google.clientSecret" -> "google-secret",
        "oauth.google.hosts"        -> Seq(Host),
        "oauth.wikimedia.clients"   -> Seq(Map("host" -> Host, "clientId" -> "wm-client", "clientSecret" -> "wm-secret"))
      )
    )
  )

  def controller(implicit app: Application) =
    new OAuthController(
      settings,
      new OAuthProviders(Seq(google, wikimedia)),
      app.injector.instanceOf[BlockingExecutionContext],
      app.injector.instanceOf[ControllerComponents]
    )

  def loginController(implicit app: Application) =
    new LoginController(new UserService(mock[SMTPOrWikiMail], app.configuration), settings, app.injector.instanceOf[ControllerComponents])

  def get(path: String, host: String = Host, session: Session = Session()) =
    FakeRequest(GET, path).withHeaders(HOST -> host).withSession(session.data.toSeq: _*)

  /** Leaves for the provider and comes back with `query`; the callback's result. */
  def signIn(provider: String, query: Map[String, String] => String, session: Session = Session())(implicit
      app: Application
  ): Future[Result] = {
    val started = controller.login(provider)(get(s"/oauth/$provider/login", session = session))
    status(started) === SEE_OTHER
    val flowSession = Session(session.data ++ sessionOf(started).data)
    val callback    = s"/oauth/$provider/callback?${query(flowSession.data)}"
    controller.callback(provider)(get(callback, session = flowSession))
  }

  def sessionOf(result: Future[Result]): Session = session(result)

  def good(flow: Map[String, String]): String = s"code=good-code&state=${flow(OAuthController.StateKey)}"

  def user(email: String, wikiAccount: Option[String] = None): User =
    User.create(User("name", email, password = Some("hash"), wikiAccount = wikiAccount))

  "login" should {
    "send the user to the provider, keeping the sign-in in the session" in testDbApp { implicit app =>
      val result = controller.login("google")(get("/oauth/google/login"))

      status(result) === SEE_OTHER
      val location = redirectLocation(result).get
      location must startWith("https://google.example/authorize?client_id=google-client")
      location must contain(s"redirect_uri=https://$Host/oauth/google/callback")
      val s = sessionOf(result)
      location must contain(s"state=${s.get(OAuthController.StateKey).get}")
      OAuthController.FlowKeys.filterNot(_ == OAuthController.LinkUser).map(s.get(_).isDefined).toSet === Set(true)
      s.get(OAuthController.Mode) === Some(OAuthController.LoginMode)
    }

    "not offer a provider on a host not configured for it" in testDbApp { implicit app =>
      status(controller.login("google")(get("/oauth/google/login", host = Other))) === NOT_FOUND
      status(controller.login("github")(get("/oauth/github/login"))) === NOT_FOUND
    }
  }

  "callback" should {
    "log in the account with the verified email" in testDbApp { implicit app =>
      createContests(10L)
      val alice = user("alice@example.com")
      UserContestJdbc.createMembership(alice.getId, 10L, "organizer")
      google.profile = ExternalProfile("google", "g-1", Some("alice@example.com"), emailVerified = true)

      val result = signIn("google", good)

      status(result) === SEE_OTHER
      redirectLocation(result) === Some(routes.RoundController.currentRoundStat().url)
      val s = sessionOf(result)
      s.get(Secured.UserId) === Some(alice.getId.toString)
      s.get(Secured.UserName) === Some("alice@example.com")
      s.get(Secured.CurrentContestId) === Some("10")
      OAuthController.FlowKeys.flatMap(s.get) must beEmpty
      UserIdentityJdbc.find("google", "g-1").map(_.userId) === Some(alice.getId)
    }

    "log in the account with the Wikimedia username as wiki account" in testDbApp { implicit app =>
      val jane = user("jane@example.com", Some("Jane_Doe"))
      wikimedia.profile = ExternalProfile("wikimedia", "777", username = Some("Jane Doe"))

      val result = signIn("wikimedia", good)

      sessionOf(result).get(Secured.UserId) === Some(jane.getId.toString)
    }

    "not log in without an account" in testDbApp { implicit app =>
      google.profile = ExternalProfile("google", "g-2", Some("stranger@example.com"), emailVerified = true)

      val result = signIn("google", good)

      redirectLocation(result) === Some(routes.LoginController.login().url)
      flash(result).get(OAuthController.FlashError) === Some("oauth.no.account")
      sessionOf(result).get(Secured.UserId) must beNone
      OAuthController.FlowKeys.flatMap(sessionOf(result).get) must beEmpty
    }

    "not log in with another sign-in's state" in testDbApp { implicit app =>
      user("alice@example.com")
      google.profile = ExternalProfile("google", "g-1", Some("alice@example.com"), emailVerified = true)

      val result = signIn("google", _ => "code=good-code&state=forged")

      flash(result).get(OAuthController.FlashError) === Some("oauth.failed")
      sessionOf(result).get(Secured.UserId) must beNone
      UserIdentityJdbc.find("google", "g-1") must beNone
    }

    "not log in without a sign-in in progress" in testDbApp { implicit app =>
      user("alice@example.com")
      google.profile = ExternalProfile("google", "g-1", Some("alice@example.com"), emailVerified = true)

      val result = controller.callback("google")(get("/oauth/google/callback?code=good-code&state=x"))

      flash(result).get(OAuthController.FlashError) === Some("oauth.failed")
      sessionOf(result).get(Secured.UserId) must beNone
    }

    "not log in a sign-in that took too long" in testDbApp { implicit app =>
      user("alice@example.com")
      google.profile = ExternalProfile("google", "g-1", Some("alice@example.com"), emailVerified = true)
      val started = controller.login("google")(get("/oauth/google/login"))
      val old     = sessionOf(started) + (OAuthController.StartedAt -> (System.currentTimeMillis - 3600000).toString)

      val result = controller.callback("google")(get(s"/oauth/google/callback?${good(old.data)}", session = old))

      flash(result).get(OAuthController.FlashError) === Some("oauth.failed")
      sessionOf(result).get(Secured.UserId) must beNone
    }

    "not log in with another provider's sign-in" in testDbApp { implicit app =>
      user("alice@example.com")
      val started = controller.login("wikimedia")(get("/oauth/wikimedia/login"))
      val s       = sessionOf(started)

      val result = controller.callback("google")(get(s"/oauth/google/callback?${good(s.data)}", session = s))

      flash(result).get(OAuthController.FlashError) === Some("oauth.failed")
      sessionOf(result).get(Secured.UserId) must beNone
    }

    "tell a cancelled sign-in" in testDbApp { implicit app =>
      val result = signIn("google", flow => s"error=access_denied&state=${flow(OAuthController.StateKey)}")

      flash(result).get(OAuthController.FlashError) === Some("oauth.cancelled")
    }

    "tell a failed token exchange" in testDbApp { implicit app =>
      val result = signIn("google", flow => s"code=bad-code&state=${flow(OAuthController.StateKey)}")

      flash(result).get(OAuthController.FlashError) === Some("oauth.failed")
    }

    "tell any other failure, not show an error page with the code" in testDbApp { implicit app =>
      val result = signIn("google", flow => s"code=boom-code&state=${flow(OAuthController.StateKey)}")

      status(result) === SEE_OTHER
      redirectLocation(result) === Some(routes.LoginController.login().url)
      flash(result).get(OAuthController.FlashError) === Some("oauth.failed")
      OAuthController.FlowKeys.flatMap(sessionOf(result).get) must beEmpty
    }

    "leave out a login name that is someone else's too" in testDbApp { implicit app =>
      // a version from before ids reads the user from the login name: the first with it
      user("other@example.com", Some("Twin"))
      val twin = user("", Some("Twin"))
      UserIdentityJdbc.create(UserIdentity("wikimedia", "777", twin.getId))
      wikimedia.profile = ExternalProfile("wikimedia", "777", username = Some("Twin"))

      val s = sessionOf(signIn("wikimedia", good))

      s.get(Secured.UserId) === Some(twin.getId.toString)
      s.get(Secured.UserName) must beNone
    }
  }

  "link" should {
    "link the account signed in with to the logged-in user's" in testDbApp { implicit app =>
      val alice = user("alice@example.com")
      google.profile = ExternalProfile("google", "g-9", Some("alice.personal@gmail.com"), emailVerified = true)
      val loggedIn = Session(Map(Secured.UserId -> alice.getId.toString))

      val started = call(controller.link("google"), get("/oauth/google/link", session = loggedIn))
      status(started) === SEE_OTHER
      val s = sessionOf(started)
      s.get(OAuthController.LinkUser) === Some(alice.getId.toString)

      val result = controller.callback("google")(get(s"/oauth/google/callback?${good(s.data)}", session = s))

      status(result) === SEE_OTHER
      redirectLocation(result) === Some(routes.UserController.editUser(alice.getId).url)
      flash(result).get(OAuthController.FlashSuccess) === Some("oauth.linked")
      sessionOf(result).get(Secured.UserId) === Some(alice.getId.toString)
      UserIdentityJdbc.find("google", "g-9").map(_.userId) === Some(alice.getId)
    }

    "not link for a session whose user changed meanwhile" in testDbApp { implicit app =>
      val alice = user("alice@example.com")
      val bob   = user("bob@example.com")
      google.profile = ExternalProfile("google", "g-9", Some("x@gmail.com"), emailVerified = true)
      val started = call(controller.link("google"), get("/oauth/google/link", session = Session(Map(Secured.UserId -> alice.getId.toString))))
      val s       = sessionOf(started) + (Secured.UserId -> bob.getId.toString)

      await(controller.callback("google")(get(s"/oauth/google/callback?${good(s.data)}", session = s)))

      UserIdentityJdbc.find("google", "g-9") must beNone
    }

    "not link an identity linked to another user" in testDbApp { implicit app =>
      val alice = user("alice@example.com")
      val bob   = user("bob@example.com")
      UserIdentityJdbc.create(UserIdentity("google", "g-9", bob.getId))
      google.profile = ExternalProfile("google", "g-9", Some("x@gmail.com"), emailVerified = true)
      val started = call(controller.link("google"), get("/oauth/google/link", session = Session(Map(Secured.UserId -> alice.getId.toString))))
      val s       = sessionOf(started)

      val result = controller.callback("google")(get(s"/oauth/google/callback?${good(s.data)}", session = s))

      flash(result).get(OAuthController.FlashError) === Some("oauth.linked.other")
      UserIdentityJdbc.find("google", "g-9").map(_.userId) === Some(bob.getId)
    }

    "need a logged-in user" in testDbApp { implicit app =>
      val result = call(controller.link("google"), get("/oauth/google/link"))
      status(result) === SEE_OTHER
      redirectLocation(result) === Some(routes.LoginController.login().url)
    }
  }

  "unlink" should {
    "unlink only the user's own identity" in testDbApp { implicit app =>
      val alice = user("alice@example.com")
      val bob   = user("bob@example.com")
      UserIdentityJdbc.create(UserIdentity("google", "a-1", alice.getId))
      UserIdentityJdbc.create(UserIdentity("google", "b-1", bob.getId))

      def unlink(subject: String) =
        call(
          controller.unlink("google"),
          FakeRequest(POST, "/oauth/google/unlink")
            .withSession(Secured.UserId -> alice.getId.toString)
            .withFormUrlEncodedBody("subject" -> subject)
        )

      flash(unlink("b-1")).get(OAuthController.FlashError) === Some("oauth.failed")
      UserIdentityJdbc.find("google", "b-1") must beSome

      flash(unlink("a-1")).get(OAuthController.FlashSuccess) === Some("oauth.unlinked")
      UserIdentityJdbc.find("google", "a-1") must beNone
    }
  }

  "login page" should {
    "offer the providers configured for the host" in testDbApp { implicit app =>
      user("alice@example.com")

      val here = contentAsString(loginController.login(get("/auth").withCSRFToken))
      here must contain("""id="oauth-wikimedia"""")
      here must contain("""id="oauth-google"""")
      here must contain("/oauth/google/login")

      val elsewhere = contentAsString(loginController.login(get("/auth", host = Other).withCSRFToken))
      elsewhere must not(contain("oauth-google"))
      elsewhere must not(contain("oauth-wikimedia"))
    }

    "show why a sign-in failed" in testDbApp { implicit app =>
      user("alice@example.com")

      val page = loginController.login(get("/auth").withFlash(OAuthController.FlashError -> "oauth.no.account").withCSRFToken)
      contentAsString(page) must contain("No jury account matches this account")
    }
  }
}
