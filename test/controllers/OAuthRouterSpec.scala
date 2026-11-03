package controllers

import db.scalikejdbc.{PlayTestDb, User, UserContestJdbc, UserIdentity, UserIdentityJdbc}
import play.api.Application
import play.api.inject.bind
import play.api.mvc.{Result, Session, SessionCookieBaker}
import play.api.test.{FakeRequest, PlaySpecification}
import security.ProductionFilters
import services.oauth._

import java.net.URI
import scala.concurrent.Future

/** The Google sign-in through the router and the release's filters ([[ProductionFilters]]: CSP,
  * allowed hosts, S3's session filter, CSRF on), with a fake provider (no call leaves the JVM):
  * the start leaves under the unchanged policy, the callback's session is S3's persistent one and
  * an auth version bump ends it; unlinking needs the CSRF token.
  */
class OAuthRouterSpec extends PlaySpecification with PlayTestDb with AccountTestSupport {

  sequential

  private val Host = "jury.example.org"

  private object FakeGoogle extends OAuthProvider {
    @volatile var profile: ExternalProfile = ExternalProfile(OAuthProviders.Google, "nobody")
    val id: String = OAuthProviders.Google
    def authorizationUri(client: OAuthClient, redirectUri: URI, flow: OAuthFlow): URI =
      URI.create(s"https://accounts.example/authorize?state=${flow.state.getValue}")
    def exchange(client: OAuthClient, redirectUri: URI, code: String, flow: OAuthFlow): ExternalProfile =
      if (code == "good-code") profile else throw new OAuthException("token error: invalid_grant")
  }

  /** An app with the release's filters, Google offered on [[Host]], and the fake provider bound
    * in place of the real ones (whose first use would fetch Google's discovery document).
    */
  private def withOAuthApp[T](body: Application => T): T =
    ProductionFilters.withAppWith(
      Map(
        "wlxjury.allowedHosts"      -> Host,
        "oauth.google.clientId"     -> "google-client",
        "oauth.google.clientSecret" -> "google-secret",
        "oauth.google.hosts"        -> Seq(Host)
      ),
      bind[OAuthProviders].toInstance(new OAuthProviders(Seq(FakeGoogle)))
    )(body)

  private def request(method: String, path: String, s: Session = Session()) =
    FakeRequest(method, path).withHeaders(HOST -> Host, ACCEPT -> "text/html").withSession(s.data.toSeq: _*)

  /** The session the response sets: S3's persistent cookie (the session filter's), or Play's. */
  private def sessionSet(result: Future[Result])(implicit app: Application): Session = {
    val baker = app.injector.instanceOf[SessionCookieBaker]
    cookies(result).get(baker.COOKIE_NAME).map(c => baker.decodeFromCookie(Some(c))).getOrElse(session(result))
  }

  "a Google sign-in through the release's filters" should {
    "leave under the release's CSP, sign in with a persistent S3 session, and end with an auth version bump" in {
      withOAuthApp { implicit app =>
        createContests(10L)
        val alice = account("alice@example.com", None, "jury", verified = true)
        UserContestJdbc.createMembership(alice.getId, 10L, "organizer")
        FakeGoogle.profile = ExternalProfile("google", "g-1", Some("alice@example.com"), emailVerified = true,
          hostedDomain = Some("example.com"))

        val started = route(app, request(GET, "/oauth/google/login")).get
        status(started) === SEE_OTHER
        redirectLocation(started).get must startWith("https://accounts.example/authorize")
        header("Content-Security-Policy", started) === Some(ProductionFilters.cspHeader)
        ProductionFilters.cspDirectives("form-action") === Seq("'self'")
        val flow = sessionSet(started)
        val state = flow.get(OAuthController.StateKey).get

        val callback = route(app, request(GET, s"/oauth/google/callback?code=good-code&state=$state", flow)).get
        status(callback) === SEE_OTHER
        val baker = app.injector.instanceOf[SessionCookieBaker]
        cookies(callback).get(baker.COOKIE_NAME).flatMap(_.maxAge) must beSome(be_>(0))
        val signedIn = sessionSet(callback)
        signedIn.get(Secured.AccountId) === Some(alice.getId.toString)

        val home = route(app, request(GET, "/", signedIn)).get
        redirectLocation(home) === Some(routes.RoundController.currentRoundStat().url)

        User.endSessions(alice.getId)
        val ended = route(app, request(GET, "/", signedIn)).get
        redirectLocation(ended).get must startWith("/auth")
      }
    }

    "refuse a forged state, an unconfigured provider or host, and an unlink without the CSRF token" in withOAuthApp { implicit app =>
      createContests(10L)
      val alice = account("alice@example.com", Some(10L), "organizer", verified = true)

      val started  = route(app, request(GET, "/oauth/google/login")).get
      val flow     = sessionSet(started)
      val callback = route(app, request(GET, "/oauth/google/callback?code=good-code&state=forged", flow)).get
      status(callback) === SEE_OTHER
      redirectLocation(callback).get must startWith("/auth")
      sessionSet(callback).get(Secured.AccountId) must beNone

      // the Wikimedia sign-in has no client here
      status(route(app, request(GET, "/oauth/wikimedia/login")).get) === NOT_FOUND
      // a host not allowed at all: the allowed-hosts filter's 400
      status(route(app, FakeRequest(GET, "/oauth/google/login").withHeaders(HOST -> "evil.example")).get) === BAD_REQUEST

      UserIdentityJdbc.create(UserIdentity("google", "g-1", alice.getId))
      val signedIn = Session(Secured.signedInSession(alice, alice.emailLo, keep = false).toMap)
      val refused = route(app, request(POST, "/oauth/google/unlink", signedIn).withFormUrlEncodedBody("subject" -> "g-1")).get
      status(refused) === FORBIDDEN
      UserIdentityJdbc.find("google", "g-1") must beSome

      // the positive control: with the session's token, unlinked
      val token = CsrfTestApp.token(app)
      val withToken = Session(signedIn.data + ("csrfToken" -> token))
      val unlinked = route(app, request(POST, "/oauth/google/unlink", withToken).withHeaders(COOKIE -> "PLAY_SESSION=signed-in")
        .withFormUrlEncodedBody("subject" -> "g-1", "csrfToken" -> token)).get
      status(unlinked) === SEE_OTHER
      UserIdentityJdbc.find("google", "g-1") must beNone

      // the account chooser's POSTs need the token too
      forall(Seq("/oauth/choose" -> Seq("flow" -> "f", "option" -> "o", "proof" -> "direct"),
        "/oauth/choose/confirm" -> Seq("token" -> "t"))) { case (path, form) =>
        status(route(app, request(POST, path).withFormUrlEncodedBody(form: _*)).get) aka path must_== FORBIDDEN
      }

      // a host the app allows but Google is not listed for: no sign-in there
      status(route(app, FakeRequest(GET, "/oauth/google/login").withHeaders(HOST -> "localhost")).get) === NOT_FOUND
    }
  }
}
