package controllers

import db.scalikejdbc.{PlayTestDb, User, UserContestJdbc, UserIdentity, UserIdentityJdbc}
import modules.BlockingExecutionContext
import org.apache.pekko.stream.Materializer
import play.api.mvc._
import play.api.test.CSRFTokenHelper._
import play.api.test._
import play.api.{Application, Configuration}
import scalikejdbc._
import services.oauth._

import java.net.URI
import scala.concurrent.Future

/** The original's OAuthControllerSpec (oauth-login, 97cf9d07 + 405f6a7e), ported to S2/S3, with
  * the negative cases of the port: no account for an uninvited person, a forged state, an open
  * redirect, a deleted or deactivated account, an auth version bump. The providers are fakes:
  * no call leaves the JVM (the real ones are tested against a local fake, ProvidersSpec).
  */
class OAuthControllerSpec extends PlaySpecification with Results with PlayTestDb with AccountTestSupport {

  sequential

  implicit def materializer(implicit app: Application): Materializer = app.materializer

  val Host  = "jury.example.org"
  val Other = "jury.other.example"

  /** Signs in whoever `profile` says, for the code "good-code". */
  class FakeProvider(val id: String) extends OAuthProvider {
    @volatile var profile: ExternalProfile = ExternalProfile(id, "nobody")
    @volatile var exchanges = 0

    def authorizationUri(client: OAuthClient, redirectUri: URI, flow: OAuthFlow): URI =
      URI.create(s"https://$id.example/authorize?client_id=${client.clientId}&state=${flow.state.getValue}&redirect_uri=$redirectUri")

    def exchange(client: OAuthClient, redirectUri: URI, code: String, flow: OAuthFlow): ExternalProfile = {
      exchanges += 1
      if (code == "good-code") profile
      else if (code == "boom-code") throw new IllegalStateException("database down")
      else throw new OAuthException("token error: invalid_grant")
    }
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

  def controller(implicit app: Application): OAuthController = controllerWith(new OAuthProviders(Seq(google, wikimedia)))

  def controllerWith(providers: OAuthProviders)(implicit app: Application): OAuthController =
    new OAuthController(
      settings,
      providers,
      app.injector.instanceOf[BlockingExecutionContext],
      app.injector.instanceOf[ControllerComponents],
      ThemeCookies.default
    )

  def loginPageController(implicit app: Application) =
    new LoginController(userService(new RecordingMail), app.injector.instanceOf[ControllerComponents],
      app.injector.instanceOf[modules.SignInExecutionContext], ThemeCookies.default, settings)

  def profileController(implicit app: Application) =
    new UserController(app.injector.instanceOf[ControllerComponents], userService(new RecordingMail),
      app.injector.instanceOf[BlockingExecutionContext], ThemeCookies.default, settings)

  def get(path: String, host: String = Host, session: Session = Session()) =
    FakeRequest(GET, path).withHeaders(HOST -> host).withSession(session.data.toSeq: _*)

  /** Leaves for the provider and comes back with `query`; the callback's result. */
  def signIn(provider: String, query: Map[String, String] => String, session: Session = Session(),
             returnTo: Option[String] = None)(implicit app: Application): Future[Result] = {
    val started = controller.login(provider, returnTo)(get(s"/oauth/$provider/login", session = session))
    status(started) === SEE_OTHER
    val flowSession = Session(session.data ++ sessionOf(started).data)
    val callback    = s"/oauth/$provider/callback?${query(flowSession.data)}"
    controller.callback(provider)(get(callback, session = flowSession))
  }

  def sessionOf(result: Future[Result]): Session = session(result)

  def good(flow: Map[String, String]): String = s"code=good-code&state=${flow(OAuthController.StateKey)}"

  /** A signed-in session of `user`, as a password sign-in makes it. */
  def signedInAs(user: User): Session = Session(Secured.signedInSession(user, user.emailLo, keep = false).toMap)

  /** Leaves for the provider to link, from `user`'s own profile (its link nonce, M3). */
  def linkStart(provider: String, user: User, from: Session = Session())(implicit app: Application): Future[Result] =
    call(controller.link(provider, Some("profile-nonce")),
      get(s"/oauth/$provider/link", session = Session((if (from.isEmpty) signedInAs(user) else from).data +
        (OAuthController.LinkNonceKey -> "profile-nonce"))))

  /** Links `profile` to `user`'s account from their profile; the callback's result. */
  def linkWith(provider: String, user: User, from: Session = Session())(implicit app: Application): Future[Result] = {
    val s = sessionOf(linkStart(provider, user, from))
    controller.callback(provider)(get(s"/oauth/$provider/callback?${good(s.data)}", session = s))
  }

  /** The account the session acts as, if any (Secured.sessionUserOf: id, version, S3's limits). */
  def actsAs(s: Session): Option[Long] = Secured.sessionUserOf(FakeRequest().withSession(s.data.toSeq: _*)).flatMap(_.id)

  /** An account whose holder proved its email, a member of contest 10 with `role` if given. */
  def verified(email: String, role: Option[String] = Some("organizer"), wikiAccount: Option[String] = None): User = {
    val u = account(email, None, "jury", verified = true, wikiAccount = wikiAccount)
    role.foreach(UserContestJdbc.createMembership(u.getId, 10L, _))
    u
  }

  def verifyWiki(u: User): User = {
    DB.autoCommit { implicit s => sql"UPDATE users SET wiki_account_verified_at = NOW(3) WHERE id = ${u.getId}".update.apply() }
    User.findById(u.getId).get
  }

  /** A Google profile with a verified email Google is authoritative for (M4): a Gmail address, or
    * here a Workspace account's (an `hd` claim).
    */
  def googleProfile(sub: String, email: String) =
    ExternalProfile("google", sub, Some(email), emailVerified = true,
      hostedDomain = Option.when(!email.endsWith("@gmail.com"))(email.substring(email.indexOf('@') + 1)))

  "login [J-04]" should {
    "send the user to the provider, keeping the sign-in in the session" in testDbApp { implicit app =>
      val result = controller.login("google")(get("/oauth/google/login"))

      status(result) === SEE_OTHER
      val location = redirectLocation(result).get
      location must startWith("https://google.example/authorize?client_id=google-client")
      location must contain(s"redirect_uri=https://$Host/oauth/google/callback")
      val s = sessionOf(result)
      location must contain(s"state=${s.get(OAuthController.StateKey).get}")
      Seq(OAuthController.Provider, OAuthController.StateKey, OAuthController.Verifier, OAuthController.NonceKey,
        OAuthController.StartedAt).map(s.get(_).isDefined).toSet === Set(true)
      s.get(OAuthController.Mode) === Some(OAuthController.LoginMode)
      header(CACHE_CONTROL, result) === Some("no-store")
    }

    "not offer a provider on a host not configured for it, nor an unknown provider" in testDbApp { implicit app =>
      status(controller.login("google")(get("/oauth/google/login", host = Other))) === NOT_FOUND
      status(controller.login("github")(get("/oauth/github/login"))) === NOT_FOUND
    }

    "keep only a local path to come back to (Secured.safeLocalPath)" in testDbApp { implicit app =>
      def kept(returnTo: String) =
        sessionOf(controller.login("google", Some(returnTo))(get("/oauth/google/login"))).get(OAuthController.ReturnTo)

      kept("/round/stat?x=1") === Some("/round/stat?x=1")
      forall(Seq("//evil.example/", "https://evil.example/", "/\\evil.example", "javascript:alert(1)")) { r =>
        kept(r) aka r must beNone
      }
    }
  }

  "callback [J-04]" should {
    "sign in the account with the verified email, as a password sign-in does (S3)" in testDbApp { implicit app =>
      createContests(10L)
      val alice = verified("alice@example.com")
      google.profile = googleProfile("g-1", "alice@example.com")

      val result = signIn("google", good)

      status(result) === SEE_OTHER
      redirectLocation(result) === Some(routes.RoundController.currentRoundStat().url)
      val s = sessionOf(result)
      s.get(Secured.UserName) === Some("alice@example.com")
      s.get(Secured.AccountId) === Some(alice.getId.toString)
      s.get(Secured.AuthVersion) === Some(alice.authVersion.toString)
      s.get(SessionLifetime.IssuedAt) must beSome
      s.get(SessionLifetime.Keep) === Some("1")
      s.get(Secured.CurrentContestId) === Some("10")
      OAuthController.FlowKeys.flatMap(s.get) must beEmpty
      header("Referrer-Policy", result) === Some("no-referrer")
      UserIdentityJdbc.find("google", "g-1").map(_.userId) === Some(alice.getId)
      actsAs(s) === Some(alice.getId)
    }

    "come back to a local returnTo, never to another site" in testDbApp { implicit app =>
      createContests(10L)
      verified("alice@example.com")
      google.profile = googleProfile("g-1", "alice@example.com")

      redirectLocation(signIn("google", good, returnTo = Some("/round/stat"))) === Some("/round/stat")
      forall(Seq("//evil.example/", "https://evil.example/x")) { r =>
        (redirectLocation(signIn("google", good, returnTo = Some(r))) aka r) must_==
          Some(routes.RoundController.currentRoundStat().url)
      }
      // a forged flow key in the session is checked again
      val started = controller.login("google")(get("/oauth/google/login"))
      val forged  = sessionOf(started) + (OAuthController.ReturnTo -> "//evil.example/")
      redirectLocation(controller.callback("google")(get(s"/oauth/google/callback?${good(forged.data)}", session = forged))) ===
        Some(routes.RoundController.currentRoundStat().url)
    }

    "sign in a wiki-only placeholder by its wiki account, verifying it" in testDbApp { implicit app =>
      createContests(10L)
      val jane = User.create(User("Jane", "", password = None, wikiAccount = Some("Jane_Doe")))
      UserContestJdbc.createMembership(jane.getId, 10L, "jury")
      wikimedia.profile = ExternalProfile("wikimedia", "777", username = Some("Jane Doe"))

      sessionOf(signIn("wikimedia", good)).get(Secured.AccountId) === Some(jane.getId.toString)
      User.findById(jane.getId).get.wikiAccountVerified must beTrue
    }

    "sign an invitee in by the provider-verified address, accepting the live invitation (B3)" in testDbApp {
      implicit app =>
        createContests(10L)
        val root = account("root@example.com", None, "jury", verified = true, root = true)
        val bob  = User.create(User("Bob", "bob@example.com", password = None))
        services.AccountTokenService.create(bob.getId, db.scalikejdbc.AccountTokenJdbc.Invitation, "bob@example.com",
          Some(10L), Some("organizer"), root.id)
        google.profile = googleProfile("g-5", "bob@example.com")

        val result = signIn("google", good)

        redirectLocation(result) === Some(routes.RoundController.currentRoundStat().url)
        val reloaded = User.findById(bob.getId).get
        reloaded.emailVerified must beTrue
        sessionOf(result).get(Secured.AuthVersion) === Some(reloaded.authVersion.toString)
        actsAs(sessionOf(result)) === Some(bob.getId)
    }

    "link a root account with a Gmail address on its first sign-in, logging it at WARN (B2)" in testDbApp {
      implicit app =>
        val root = account("root.person@gmail.com", None, "jury", verified = true, root = true)
        google.profile = googleProfile("g-root", "root.person@gmail.com")

        val (result, lines) = accountLog(await(signIn("google", good)))

        actsAs(sessionOf(Future.successful(result))) === Some(root.getId)
        UserIdentityJdbc.find("google", "g-root").map(_.userId) === Some(root.getId)
        lines must contain(s"oauth_signin provider=google accountId=${root.getId} root=true")
    }

    "not sign in an uninvited email, and create no account" in testDbApp { implicit app =>
      verified("alice@example.com", role = None)
      val before = User.count()
      google.profile = googleProfile("g-2", "stranger@example.com")

      val result = signIn("google", good)

      redirectLocation(result) === Some(routes.LoginController.login(None).url)
      flash(result).get(OAuthController.FlashError) === Some("oauth.no.account")
      sessionOf(result).get(Secured.AccountId) must beNone
      sessionOf(result).get(Secured.UserName) must beNone
      OAuthController.FlowKeys.flatMap(sessionOf(result).get) must beEmpty
      User.count() === before
      UserIdentityJdbc.find("google", "g-2") must beNone
    }

    "not sign in an uninvited wiki user, and create no account" in testDbApp { implicit app =>
      val before = User.count()
      wikimedia.profile = ExternalProfile("wikimedia", "999", Some("w@example.org"), emailVerified = true, Some("Stranger"))

      val result = signIn("wikimedia", good)

      flash(result).get(OAuthController.FlashError) === Some("oauth.no.account")
      sessionOf(result).get(Secured.AccountId) must beNone
      User.count() === before
      User.findByAccount("Stranger") must beEmpty
    }

    "not sign in to an account with a password and an unverified email (S2)" in testDbApp { implicit app =>
      createContests(10L)
      account("alice@example.com", Some(10L), "jury", verified = false)
      google.profile = googleProfile("g-1", "alice@example.com")

      val result = signIn("google", good)

      flash(result).get(OAuthController.FlashError) === Some("oauth.unverified")
      sessionOf(result).get(Secured.AccountId) must beNone
      UserIdentityJdbc.find("google", "g-1") must beNone
    }

    "not sign in by an unverified wiki account" in testDbApp { implicit app =>
      createContests(10L)
      verified("jane@example.com", wikiAccount = Some("Jane Doe"))
      wikimedia.profile = ExternalProfile("wikimedia", "777", username = Some("Jane Doe"))

      flash(signIn("wikimedia", good)).get(OAuthController.FlashError) === Some("oauth.unverified")
      UserIdentityJdbc.find("wikimedia", "777") must beNone
    }

    "not sign in with another sign-in's state" in testDbApp { implicit app =>
      createContests(10L)
      verified("alice@example.com")
      google.profile = googleProfile("g-1", "alice@example.com")
      val exchanges = google.exchanges

      val result = signIn("google", _ => "code=good-code&state=forged")

      flash(result).get(OAuthController.FlashError) === Some("oauth.failed")
      sessionOf(result).get(Secured.AccountId) must beNone
      UserIdentityJdbc.find("google", "g-1") must beNone
      // the code was never traded
      google.exchanges === exchanges
    }

    "not sign in without a sign-in in progress (a callback replayed in another browser)" in testDbApp { implicit app =>
      createContests(10L)
      verified("alice@example.com")
      google.profile = googleProfile("g-1", "alice@example.com")

      val result = controller.callback("google")(get("/oauth/google/callback?code=good-code&state=x"))

      flash(result).get(OAuthController.FlashError) === Some("oauth.failed")
      sessionOf(result).get(Secured.AccountId) must beNone
    }

    "not sign in a sign-in that took too long" in testDbApp { implicit app =>
      createContests(10L)
      verified("alice@example.com")
      google.profile = googleProfile("g-1", "alice@example.com")
      val started = controller.login("google")(get("/oauth/google/login"))
      val old     = sessionOf(started) + (OAuthController.StartedAt -> (System.currentTimeMillis - 3600000).toString)

      val result = controller.callback("google")(get(s"/oauth/google/callback?${good(old.data)}", session = old))

      flash(result).get(OAuthController.FlashError) === Some("oauth.failed")
      sessionOf(result).get(Secured.AccountId) must beNone
    }

    "not sign in with another provider's sign-in" in testDbApp { implicit app =>
      createContests(10L)
      verified("alice@example.com")
      val started = controller.login("wikimedia")(get("/oauth/wikimedia/login"))
      val s       = sessionOf(started)

      val result = controller.callback("google")(get(s"/oauth/google/callback?${good(s.data)}", session = s))

      flash(result).get(OAuthController.FlashError) === Some("oauth.failed")
      sessionOf(result).get(Secured.AccountId) must beNone
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
      redirectLocation(result) === Some(routes.LoginController.login(None).url)
      flash(result).get(OAuthController.FlashError) === Some("oauth.failed")
      OAuthController.FlowKeys.flatMap(sessionOf(result).get) must beEmpty
    }

    "refuse a callback when every turn to exchange is taken" in testDbApp { implicit app =>
      createContests(10L)
      verified("alice@example.com")
      google.profile = googleProfile("g-1", "alice@example.com")
      val busy    = controllerWith(new OAuthProviders(Seq(google, wikimedia), 0))
      val started = busy.login("google")(get("/oauth/google/login"))
      val s       = sessionOf(started)

      val result = busy.callback("google")(get(s"/oauth/google/callback?${good(s.data)}", session = s))

      flash(result).get(OAuthController.FlashError) === Some("oauth.busy")
      sessionOf(result).get(Secured.AccountId) must beNone
    }

    "not sign in to a deleted account" in testDbApp { implicit app =>
      createContests(10L)
      val gone = verified("gone@example.com")
      UserIdentityJdbc.create(UserIdentity("google", "g-1", gone.getId))
      DB.autoCommit { implicit s => sql"UPDATE users SET deleted_at = NOW() WHERE id = ${gone.getId}".update.apply() }
      google.profile = googleProfile("g-1", "gone@example.com")

      val result = signIn("google", good)

      flash(result).get(OAuthController.FlashError) === Some("oauth.no.account")
      sessionOf(result).get(Secured.AccountId) must beNone
    }

    "give a deactivated member no more than a password sign-in does: the denial page" in testDbApp { implicit app =>
      createContests(10L)
      // deactivated in the contest: the edit form's "no" role (an inactive membership)
      verified("juror@example.com", role = Some(User.NO_ROLE))
      google.profile = googleProfile("g-1", "juror@example.com")

      val result = signIn("google", good)

      status(result) === FORBIDDEN
      val user = Secured.sessionUserOf(FakeRequest().withSession(sessionOf(result).data.toSeq: _*))
      user.exists(_.hasAnyRole(Secured.ViewerRoles)) must beFalse
    }

    "make a session that an auth version bump ends (sign out everywhere, a password change)" in testDbApp { implicit app =>
      createContests(10L)
      val alice = verified("alice@example.com")
      google.profile = googleProfile("g-1", "alice@example.com")
      val s = sessionOf(signIn("google", good))
      actsAs(s) === Some(alice.getId)

      User.endSessions(alice.getId)

      actsAs(s) must beNone
      // signing in again with Google gives a session of the new version
      actsAs(sessionOf(signIn("google", good))) === Some(alice.getId)
    }

    "refuse an account whose only login name another account has too" in testDbApp { implicit app =>
      // a session is read by its name (User.byUserName): a shared wiki account names no one
      User.create(User("other", "other@example.com", password = Some("hash"), wikiAccount = Some("Twin")))
      val twin = User.create(User("twin", "", password = None, wikiAccount = Some("Twin")))
      UserIdentityJdbc.create(UserIdentity("wikimedia", "777", twin.getId))
      wikimedia.profile = ExternalProfile("wikimedia", "777", username = Some("Twin"))

      val result = signIn("wikimedia", good)

      flash(result).get(OAuthController.FlashError) === Some("oauth.ambiguous")
      sessionOf(result).get(Secured.AccountId) must beNone
    }
  }

  "link [J-32]" should {
    "link the account signed in with to the signed-in user's own" in testDbApp { implicit app =>
      createContests(10L)
      val alice = verified("alice@example.com")
      google.profile = googleProfile("g-9", "alice.personal@gmail.com")

      val started = linkStart("google", alice)
      status(started) === SEE_OTHER
      val s = sessionOf(started)
      s.get(OAuthController.LinkUser) === Some(alice.getId.toString)

      val result = controller.callback("google")(get(s"/oauth/google/callback?${good(s.data)}", session = s))

      status(result) === SEE_OTHER
      redirectLocation(result) === Some(routes.UserController.editUser(alice.getId).url)
      flash(result).get(OAuthController.FlashSuccess) === Some("oauth.linked")
      actsAs(sessionOf(result)) === Some(alice.getId)
      OAuthController.FlowKeys.flatMap(sessionOf(result).get) must beEmpty
      UserIdentityJdbc.find("google", "g-9").map(_.userId) === Some(alice.getId)

      // and then signs in to it, its email being another
      actsAs(sessionOf(signIn("google", good))) === Some(alice.getId)
    }

    "not link for a session whose user changed meanwhile" in testDbApp { implicit app =>
      createContests(10L)
      val alice = verified("alice@example.com")
      val bob   = verified("bob@example.com")
      google.profile = googleProfile("g-9", "x@gmail.com")
      val started = linkStart("google", alice)
      val s       = Session(sessionOf(started).data ++ signedInAs(bob).data)

      val result = controller.callback("google")(get(s"/oauth/google/callback?${good(s.data)}", session = s))

      flash(result).get(OAuthController.FlashError) === Some("oauth.failed")
      UserIdentityJdbc.find("google", "g-9") must beNone
    }

    "not link once the session was ended meanwhile (an auth version bump)" in testDbApp { implicit app =>
      createContests(10L)
      val alice = verified("alice@example.com")
      google.profile = googleProfile("g-9", "x@gmail.com")
      val started = linkStart("google", alice)
      val s       = sessionOf(started)
      User.endSessions(alice.getId)

      val result = controller.callback("google")(get(s"/oauth/google/callback?${good(s.data)}", session = s))

      flash(result).get(OAuthController.FlashError) === Some("oauth.failed")
      UserIdentityJdbc.find("google", "g-9") must beNone
    }

    "not link an identity linked to another user" in testDbApp { implicit app =>
      createContests(10L)
      val alice = verified("alice@example.com")
      val bob   = verified("bob@example.com")
      UserIdentityJdbc.create(UserIdentity("google", "g-9", bob.getId))
      google.profile = googleProfile("g-9", "x@gmail.com")
      val started = linkStart("google", alice)
      val s       = sessionOf(started)

      val result = controller.callback("google")(get(s"/oauth/google/callback?${good(s.data)}", session = s))

      flash(result).get(OAuthController.FlashError) === Some("oauth.linked.other")
      redirectLocation(result) === Some(routes.UserController.editUser(alice.getId).url)
      UserIdentityJdbc.find("google", "g-9").map(_.userId) === Some(bob.getId)
    }

    "ask before setting an empty own wiki account from a Wikimedia link, then set it, verified (B4)" in testDbApp {
      implicit app =>
        createContests(10L)
        val alice = verified("alice@example.com")
        val other = signedInAs(alice)
        wikimedia.profile = ExternalProfile("wikimedia", "42", username = Some("Alice_W"))

        val result = linkWith("wikimedia", alice)

        // linked, but the name is not set by the link: a page asks
        redirectLocation(result) === Some(routes.OAuthController.wikiAccountPage("42").url)
        User.findById(alice.getId).flatMap(_.wikiAccount) must beNone
        val here = sessionOf(result)
        val page = call(controller.wikiAccountPage("42"), get("/oauth/wiki-account?subject=42", session = here).withCSRFToken)
        status(page) === OK
        contentAsString(page) must contain("Alice W")

        // the POST names the identity only; a name in the form is ignored
        val set = call(controller.setWikiAccount(), FakeRequest(POST, "/oauth/wiki-account")
          .withSession(here.data.toSeq: _*).withFormUrlEncodedBody("subject" -> "42", "wikiAccount" -> "Someone Else"))
        flash(set).get(OAuthController.FlashSuccess) === Some("oauth.linked.wiki.set")
        val reloaded = User.findById(alice.getId).get
        reloaded.wikiAccount === Some("Alice W")
        reloaded.wikiAccountVerified must beTrue
        // this browser stays signed in with the new version; the other session ends
        actsAs(Session(here.data ++ sessionOf(set).data)) === Some(alice.getId)
        actsAs(other) must beNone
    }

    "not offer another name than an organizer-set wiki account; verify the same one (B4)" in testDbApp { implicit app =>
      createContests(10L)
      val alice = verified("alice@example.com", wikiAccount = Some("Alice Admin Set"))
      wikimedia.profile = ExternalProfile("wikimedia", "42", username = Some("Alice W"))

      flash(linkWith("wikimedia", alice)).get(OAuthController.FlashSuccess) === Some("oauth.linked.wiki.other")
      User.findById(alice.getId).flatMap(_.wikiAccount) === Some("Alice Admin Set")
      // nor through the page or its POST
      val here = signedInAs(alice)
      flash(call(controller.wikiAccountPage("42"), get("/oauth/wiki-account?subject=42", session = here)))
        .get(OAuthController.FlashError) === Some("oauth.wiki.ask.organizer")
      flash(call(controller.setWikiAccount(), FakeRequest(POST, "/oauth/wiki-account").withSession(here.data.toSeq: _*)
        .withFormUrlEncodedBody("subject" -> "42"))).get(OAuthController.FlashError) === Some("oauth.wiki.ask.organizer")
      User.findById(alice.getId).flatMap(_.wikiAccount) === Some("Alice Admin Set")

      val bob = verified("bob@example.com", wikiAccount = Some("Bob W"))
      wikimedia.profile = ExternalProfile("wikimedia", "43", username = Some("Bob_W"))
      flash(linkWith("wikimedia", bob)).get(OAuthController.FlashSuccess) === Some("oauth.linked")
      User.findById(bob.getId).get.wikiAccountVerified must beTrue
    }

    "not set a wiki account another account has, nor another user's identity's name" in testDbApp { implicit app =>
      createContests(10L)
      val alice = verified("alice@example.com")
      val bob   = verified("bob@example.com", wikiAccount = Some("Alice W"))
      wikimedia.profile = ExternalProfile("wikimedia", "42", username = Some("Alice W"))
      val here = sessionOf(linkWith("wikimedia", alice))
      UserIdentityJdbc.create(UserIdentity("wikimedia", "77", bob.getId, username = Some("Bob Other")))

      val set = call(controller.setWikiAccount(), FakeRequest(POST, "/oauth/wiki-account")
        .withSession(here.data.toSeq: _*).withFormUrlEncodedBody("subject" -> "42"))
      flash(set).get(OAuthController.FlashError) === Some("oauth.linked.wiki.taken")
      val others = call(controller.setWikiAccount(), FakeRequest(POST, "/oauth/wiki-account")
        .withSession(here.data.toSeq: _*).withFormUrlEncodedBody("subject" -> "77"))
      flash(others).get(OAuthController.FlashError) === Some("oauth.failed")
      User.findById(alice.getId).flatMap(_.wikiAccount) must beNone
    }

    "start only from the own profile: its nonce, and not another site's request (M3)" in testDbApp { implicit app =>
      createContests(10L)
      val alice = verified("alice@example.com")
      val s = Session(signedInAs(alice).data + (OAuthController.LinkNonceKey -> "profile-nonce"))

      def started(n: Option[String], site: Option[String]) = {
        val request = get("/oauth/google/link", session = s)
        call(controller.link("google", n), site.fold(request)(v => request.withHeaders("Sec-Fetch-Site" -> v)))
      }
      redirectLocation(started(None, None)) === Some(routes.UserController.editUser(alice.getId).url)
      redirectLocation(started(Some("guessed"), None)) === Some(routes.UserController.editUser(alice.getId).url)
      redirectLocation(started(Some("profile-nonce"), Some("cross-site"))) === Some(routes.UserController.editUser(alice.getId).url)
      redirectLocation(started(Some("profile-nonce"), Some("same-origin"))).get must startWith("https://google.example/")
    }

    "refuse a link while the account has a password and an unverified email (B1)" in testDbApp { implicit app =>
      createContests(10L)
      val claimed = account("claimed@example.com", Some(10L), "jury", verified = false)
      google.profile = googleProfile("g-9", "attacker@gmail.com")

      flash(linkStart("google", claimed)).get(OAuthController.FlashError) === Some("oauth.link.unverified")
      UserIdentityJdbc.find("google", "g-9") must beNone
    }

    "let root link from its profile, then sign in through that identity (B2)" in testDbApp { implicit app =>
      val root = account("root@example.com", None, "jury", verified = true, root = true)
      google.profile = googleProfile("g-root", "root.personal@gmail.com")

      flash(linkWith("google", root)).get(OAuthController.FlashSuccess) === Some("oauth.linked")
      UserIdentityJdbc.find("google", "g-root").map(_.userId) === Some(root.getId)
      actsAs(sessionOf(signIn("google", good))) === Some(root.getId)
    }

    "need a signed-in user" in testDbApp { implicit app =>
      val result = call(controller.link("google"), get("/oauth/google/link"))
      status(result) === SEE_OTHER
      redirectLocation(result).get must startWith(routes.LoginController.login(None).url)
    }
  }

  "unlink [J-32]" should {
    "unlink only the user's own identity" in testDbApp { implicit app =>
      createContests(10L)
      val alice = verified("alice@example.com")
      val bob   = verified("bob@example.com")
      UserIdentityJdbc.create(UserIdentity("google", "a-1", alice.getId))
      UserIdentityJdbc.create(UserIdentity("google", "b-1", bob.getId))

      def unlink(subject: String) =
        call(
          controller.unlink("google"),
          FakeRequest(POST, "/oauth/google/unlink")
            .withSession(signedInAs(alice).data.toSeq: _*)
            .withFormUrlEncodedBody("subject" -> subject)
        )

      flash(unlink("b-1")).get(OAuthController.FlashError) === Some("oauth.failed")
      UserIdentityJdbc.find("google", "b-1") must beSome

      val other = signedInAs(User.findById(alice.getId).get)
      val done  = unlink("a-1")
      flash(done).get(OAuthController.FlashSuccess) === Some("oauth.unlinked")
      UserIdentityJdbc.find("google", "a-1") must beNone
      // every other session ends (B5); this browser keeps the new version
      actsAs(other) must beNone
      actsAs(Session(signedInAs(alice).data ++ sessionOf(done).data)) === Some(alice.getId)
    }
  }

  "an unlinked identity [J-04]" should {
    "not be linked again by a first sign-in's email, only from the profile (F2)" in testDbApp { implicit app =>
      createContests(10L)
      val alice = verified("alice@example.com")
      google.profile = googleProfile("g-1", "alice@example.com")
      actsAs(sessionOf(signIn("google", good))) === Some(alice.getId)

      val unlinked = call(controller.unlink("google"), FakeRequest(POST, "/oauth/google/unlink")
        .withSession(signedInAs(alice).data.toSeq: _*).withFormUrlEncodedBody("subject" -> "g-1"))
      flash(unlinked).get(OAuthController.FlashSuccess) === Some("oauth.unlinked")

      // whoever holds that Google account (a compromised one, say) can't sign back in by the email
      val again = signIn("google", good)
      flash(again).get(OAuthController.FlashError) === Some("oauth.unlinked.before")
      sessionOf(again).get(Secured.AccountId) must beNone
      UserIdentityJdbc.find("google", "g-1") must beNone

      // its holder links it again from the profile; then it signs in
      val reloaded = User.findById(alice.getId).get
      flash(linkWith("google", reloaded)).get(OAuthController.FlashSuccess) === Some("oauth.linked")
      actsAs(sessionOf(signIn("google", good))) === Some(alice.getId)
    }
  }

  "the login page [J-04]" should {
    "offer the providers configured for the host, keeping returnTo" in testDbApp { implicit app =>
      verified("alice@example.com", role = None)

      val here = contentAsString(loginPageController.login(Some("/round/stat"))(get("/auth").withCSRFToken))
      here must contain("""id="oauth-wikimedia"""")
      here must contain("""id="oauth-google"""")
      here must contain("/oauth/google/login?returnTo=%2Fround%2Fstat")

      val elsewhere = contentAsString(loginPageController.login(None)(get("/auth", host = Other).withCSRFToken))
      elsewhere must not(contain("oauth-google"))
      elsewhere must not(contain("oauth-wikimedia"))
    }

    "show why a sign-in failed, by message key only" in testDbApp { implicit app =>
      verified("alice@example.com", role = None)

      val page = loginPageController.login(None)(get("/auth").withFlash(OAuthController.FlashError -> "oauth.no.account").withCSRFToken)
      contentAsString(page) must contain("No jury account matches this account")

      val forged = loginPageController.login(None)(get("/auth").withFlash(OAuthController.FlashError -> "<b>text</b>").withCSRFToken)
      contentAsString(forged) must not(contain("oauth-error"))
    }
  }

  "the own profile [J-32]" should {
    "show the linked accounts and the providers to link" in testDbApp { implicit app =>
      createContests(10L)
      val alice = verified("alice@example.com")
      UserIdentityJdbc.create(UserIdentity("google", "g-1", alice.getId, email = Some("alice@gmail.com")))

      val page = contentAsString(call(profileController.editUser(alice.getId),
        get(s"/users/edit/${alice.getId}", session = signedInAs(alice)).withCSRFToken))

      page must contain("linked-accounts")
      page must contain("alice@gmail.com")
      page must contain("/oauth/wikimedia/link")
      page must contain("/oauth/google/unlink")
    }

    "keep the linked accounts on a refused save (the original's review fix), the own wiki account read-only" in
      testDbApp { implicit app =>
        createContests(10L)
        val alice = verified("alice@example.com")
        UserIdentityJdbc.create(UserIdentity("google", "g-1", alice.getId, email = Some("alice@gmail.com")))

        val result = call(profileController.saveUser(),
          FakeRequest(POST, "/users/save").withHeaders(HOST -> Host).withSession(signedInAs(alice).data.toSeq: _*)
            .withFormUrlEncodedBody("id" -> alice.getId.toString, "fullname" -> alice.fullname, "email" -> alice.email,
              "wikiAccount" -> "Someone Else").withCSRFToken)

        status(result) === BAD_REQUEST
        contentAsString(result) must contain("linked-accounts")
        User.findById(alice.getId).flatMap(_.wikiAccount) must beNone
      }

    "unlink every identity of an invitee whose email an admin changes" in testDbApp { implicit app =>
      createContests(10L)
      val admin   = verified("admin@example.com", role = Some("admin"))
      val invitee = User.create(User("Ivy", "typo@example.com", password = None))
      UserContestJdbc.createMembership(invitee.getId, 10L, "jury")
      UserIdentityJdbc.create(UserIdentity("google", "g-typo", invitee.getId))

      val result = call(profileController.saveUser(),
        FakeRequest(POST, "/users/save").withSession(signedInAs(admin).data.toSeq :+ (Secured.CurrentContestId -> "10"): _*)
          .withFormUrlEncodedBody("id" -> invitee.getId.toString, "fullname" -> invitee.fullname,
            "email" -> "ivy@example.com", "wikiAccount" -> "", "roles" -> "jury", "contest" -> "10").withCSRFToken)

      status(result) === SEE_OTHER
      User.findById(invitee.getId).map(_.email) === Some("ivy@example.com")
      UserIdentityJdbc.findByUser(invitee.getId) must beEmpty
    }

    "unlink every identity when an invitation or a password link is accepted (B1)" in testDbApp { implicit app =>
      createContests(10L)
      val root    = account("root@example.com", None, "jury", verified = true, root = true)
      val claimed = account("victim@example.com", Some(10L), "jury", verified = false)
      // a pre-S2 pre-claimer's Google account
      UserIdentityJdbc.create(UserIdentity("google", "g-attacker", claimed.getId))
      val (_, token) = services.AccountTokenService.create(claimed.getId, db.scalikejdbc.AccountTokenJdbc.Invitation,
        "victim@example.com", Some(10L), Some("jury"), root.id)

      services.AccountTokenService.acceptPasswordLink(token, hash("victims-password")).isRight must beTrue

      UserIdentityJdbc.findByUser(claimed.getId) must beEmpty
      google.profile = googleProfile("g-attacker", "attacker@gmail.com")
      sessionOf(signIn("google", good)).get(Secured.AccountId) must beNone
    }

    "unlink every identity when root sends a password link (B1)" in testDbApp { implicit app =>
      createContests(10L)
      val root  = account("root@example.com", None, "jury", verified = true, root = true)
      val alice = verified("alice@example.com")
      UserIdentityJdbc.create(UserIdentity("google", "g-1", alice.getId))

      val result = call(profileController.resetPassword(alice.getId),
        FakeRequest(POST, s"/users/${alice.getId}/password").withSession(signedInAs(root).data.toSeq: _*).withCSRFToken)

      status(result) === SEE_OTHER
      UserIdentityJdbc.findByUser(alice.getId) must beEmpty
    }

    "end the account's sessions with the identities root's password link unlinks" in testDbApp { implicit app =>
      createContests(10L)
      val root   = account("root@example.com", None, "jury", verified = true, root = true)
      val alice  = verified("alice@example.com")
      UserIdentityJdbc.create(UserIdentity("google", "g-1", alice.getId))
      val before = User.findById(alice.getId).get.authVersion

      val result = call(profileController.resetPassword(alice.getId),
        FakeRequest(POST, s"/users/${alice.getId}/password").withSession(signedInAs(root).data.toSeq: _*).withCSRFToken)

      status(result) === SEE_OTHER
      UserIdentityJdbc.findByUser(alice.getId) must beEmpty
      User.findById(alice.getId).get.authVersion !== before
    }

    "keep the only sign-in of an account without an email, which no link can reach" in testDbApp { implicit app =>
      createContests(10L)
      val root  = account("root@example.com", None, "jury", verified = true, root = true)
      val alice = verified("alice@example.com")
      UserIdentityJdbc.create(UserIdentity("wikimedia", "777", alice.getId, username = Some("Alice")))
      DB.autoCommit(implicit s => sql"UPDATE users SET email = '', password = NULL WHERE id = ${alice.getId}".update.apply())
      val before = User.findById(alice.getId).get.authVersion

      val result = call(profileController.resetPassword(alice.getId),
        FakeRequest(POST, s"/users/${alice.getId}/password").withSession(signedInAs(root).data.toSeq: _*).withCSRFToken)

      status(result) === SEE_OTHER
      UserIdentityJdbc.find("wikimedia", "777").map(_.userId) must beSome(alice.getId)
      User.findById(alice.getId).get.authVersion === before
    }

    "unlink a Wikimedia account when an admin changes the wiki account" in testDbApp { implicit app =>
      createContests(10L)
      val admin = verified("admin@example.com", role = Some("admin"))
      val jane  = verifyWiki(verified("jane@example.com", role = Some("jury"), wikiAccount = Some("Jane Doe")))
      UserIdentityJdbc.create(UserIdentity("wikimedia", "777", jane.getId, username = Some("Jane Doe")))
      UserIdentityJdbc.create(UserIdentity("google", "g-1", jane.getId))

      val result = call(profileController.saveUser(),
        FakeRequest(POST, "/users/save").withSession(signedInAs(admin).data.toSeq :+ (Secured.CurrentContestId -> "10"): _*)
          .withFormUrlEncodedBody("id" -> jane.getId.toString, "fullname" -> jane.fullname, "email" -> jane.email,
            "wikiAccount" -> "Jane Roe", "roles" -> "jury", "contest" -> "10").withCSRFToken)

      status(result) === SEE_OTHER
      User.findById(jane.getId).flatMap(_.wikiAccount) === Some("Jane Roe")
      UserIdentityJdbc.find("wikimedia", "777") must beNone
      UserIdentityJdbc.find("google", "g-1") must beSome
    }
  }
}
