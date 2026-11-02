package controllers

import controllers.CsrfTestApp.{token, withCsrfApp}
import db.scalikejdbc.{ApiTokenJdbc, PlayTestDb, User}
import play.api.Application
import play.api.http.HeaderNames
import play.api.mvc.{CookieHeaderEncoding, FlashCookieBaker, Result, SessionCookieBaker}
import play.api.test.{FakeRequest, PlaySpecification}
import services.ApiTokenService

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/** Security batch S3 (O3, N2): sessions end on the server. Through the router and the filters,
  * the CSRF filter on, as in production: an account's session version ends every session of it
  * ("sign out everywhere", a password change, root's "end sessions"); a session ends past its
  * absolute or idle limit; a session from before S3 is upgraded in place; "Keep me signed in"
  * makes the cookie persistent; API tokens survive a password change and are revoked together on
  * the profile.
  */
class SessionsSpec extends PlaySpecification with PlayTestDb with AccountTestSupport {

  sequential

  private val day = 24 * 3600L

  private def nowSec: Long = SessionLifetime.nowSeconds()

  /** A session of `user` as a sign-in since S3 makes it, with its times `issuedAgo` and
    * `seenAgo` seconds ago.
    */
  private def signedIn(user: User, issuedAgo: Long = 0, seenAgo: Long = 0, keep: Boolean = false,
                       version: Option[Int] = None): Seq[(String, String)] =
    Seq(Secured.UserName -> user.email, Secured.AccountId -> user.getId.toString,
      Secured.AuthVersion -> version.getOrElse(user.authVersion).toString,
      SessionLifetime.IssuedAt -> (nowSec - issuedAgo).toString, SessionLifetime.LastSeen -> (nowSec - seenAgo).toString) ++
      Option.when(keep)(SessionLifetime.Keep -> "1")

  /** A browser's request with `session` (and a session cookie, which makes the CSRF filter
    * check a POST); the form's CSRF token if `csrf`.
    */
  private def send(app: Application, method: String, path: String, session: Seq[(String, String)],
                   csrf: Option[String] = None, body: Seq[(String, String)] = Nil): Future[Result] =
    route(app, FakeRequest(method, path)
      .withHeaders(COOKIE -> "PLAY_SESSION=signed-in", ACCEPT -> "text/html")
      .withSession(session: _*)
      .withFormUrlEncodedBody(body ++ csrf.map("csrfToken" -> _): _*)).get

  /** Whether `session` is signed in as `user`: their own profile opens. */
  private def signedInAs(app: Application, user: User, session: Seq[(String, String)]): Boolean = {
    val page = send(app, GET, s"/users/edit/${user.getId}", session)
    status(page) match {
      case OK        => true
      case SEE_OTHER => redirectLocation(page).exists(_.startsWith("/auth")) must beTrue; false
      case other     => failure(s"unexpected status $other"); false
    }
  }

  /** A session with the CSRF token as the browser has it. */
  private def withToken(session: Seq[(String, String)], csrf: String) = session :+ ("csrfToken" -> csrf)

  /** A verified member of contest 10 with the role and password. */
  private def contestMember(email: String, role: String = "jury", password: String = "secret"): User =
    account(email, Some(10), role, password = Some(password), verified = true)

  /** The Set-Cookie header the server would send for the result. */
  private def setCookie(app: Application, result: Future[Result]): String =
    Await.result(result, 10.seconds).bakeCookies(app.injector.instanceOf[CookieHeaderEncoding],
      app.injector.instanceOf[SessionCookieBaker], app.injector.instanceOf[FlashCookieBaker])
      .header.headers.getOrElse(HeaderNames.SET_COOKIE, "")

  /** The session the result's session cookie holds (the filter's persistent one, or Play's). */
  private def sessionCookie(app: Application, result: Future[Result]): Map[String, String] = {
    val baker = app.injector.instanceOf[SessionCookieBaker]
    cookies(result).get(baker.COOKIE_NAME).fold(session(result).data)(c => baker.decodeFromCookie(Some(c)).data)
  }

  /** Moves the pre-S3 upgrade window's end (V80) to `days` from now. */
  private def setWindow(days: Int): Unit =
    scalikejdbc.DB.autoCommit { implicit s =>
      scalikejdbc.SQL(s"UPDATE session_upgrade_window SET until_at = NOW(3) + INTERVAL $days DAY WHERE id = 1")
        .update.apply()
    }

  /** The lines a `security.auth` logger wrote while `f` ran. */
  private def authLog[T](f: => T): (T, Seq[String]) = {
    import scala.jdk.CollectionConverters._
    val logger = org.slf4j.LoggerFactory.getLogger("security.auth").asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    val level = logger.getLevel
    logger.setLevel(ch.qos.logback.classic.Level.DEBUG)
    appender.start()
    logger.addAppender(appender)
    try {
      val result = f
      (result, appender.list.asScala.toSeq.map(_.getFormattedMessage))
    } finally {
      logger.detachAppender(appender)
      logger.setLevel(level)
    }
  }

  "sign out everywhere" should {

    "end every session of the account, this one and a second browser's" in {
      withCsrfApp { implicit app =>
        createContests(10)
        val juror = contestMember("juror@example.com")
        val csrf = token(app)
        val here = withToken(signedIn(juror), csrf)
        val elsewhere = signedIn(juror, issuedAgo = 3600)
        signedInAs(app, juror, elsewhere) must beTrue

        val (result, lines) = accountLog { val r = send(app, POST, "/logout/everywhere", here, Some(csrf)); status(r); r }
        status(result) === SEE_OTHER
        redirectLocation(result) === Some(routes.LoginController.login(None).url)
        session(result).get(Secured.UserName) must beNone
        lines must contain(s"sessions_ended accountId=${juror.getId} by=self")

        signedInAs(app, juror, here) must beFalse
        signedInAs(app, juror, elsewhere) must beFalse
        User.findById(juror.getId).get.authVersion !== juror.authVersion
      }
    }

    "need the CSRF token" in {
      withCsrfApp { implicit app =>
        createContests(10)
        val juror = contestMember("juror@example.com")
        val csrf = token(app)
        status(send(app, POST, "/logout/everywhere", withToken(signedIn(juror), csrf))) === FORBIDDEN
        status(send(app, POST, "/logout/everywhere", withToken(signedIn(juror), csrf), Some("forged"))) === FORBIDDEN
        User.findById(juror.getId).get.authVersion === juror.authVersion
        signedInAs(app, juror, signedIn(juror)) must beTrue
      }
    }

    "be offered on the own profile as a POST form with the token" in {
      withCsrfApp { implicit app =>
        createContests(10)
        val juror = contestMember("juror@example.com")
        val html = contentAsString(send(app, GET, s"/users/edit/${juror.getId}", signedIn(juror)))
        html must contain("""<form action="/logout/everywhere" method="POST"""")
        html must contain("Sign out everywhere")
      }
    }
  }

  "root's end sessions" should {

    "end every session of the account, not root's" in {
      withCsrfApp { implicit app =>
        createContests(10)
        val juror = contestMember("juror@example.com")
        val root = account("root@example.com", None, "no", verified = true, root = true)
        val csrf = token(app)
        val jurorSession = signedIn(juror)
        val rootSession = withToken(signedIn(root), csrf)
        signedInAs(app, juror, jurorSession) must beTrue

        val (result, lines) = accountLog { val r = send(app, POST, s"/users/${juror.getId}/sessions/end", rootSession, Some(csrf)); status(r); r }
        status(result) === SEE_OTHER
        redirectLocation(result) === Some(routes.UserController.editUser(juror.getId).url)
        lines must contain(s"sessions_ended accountId=${juror.getId} byAccountId=${root.getId}")

        signedInAs(app, juror, jurorSession) must beFalse
        signedInAs(app, root, rootSession) must beTrue
        // root's edit page of another account offers it
        contentAsString(send(app, GET, s"/users/edit/${juror.getId}", rootSession)) must
          contain(s"""action="/users/${juror.getId}/sessions/end"""")
      }
    }

    "be refused to anyone but root (403), and without the CSRF token" in {
      withCsrfApp { implicit app =>
        createContests(10)
        val juror = contestMember("juror@example.com")
        val admin = contestMember("admin@example.com", role = "admin")
        val root = account("root@example.com", None, "no", verified = true, root = true)
        val csrf = token(app)

        status(send(app, POST, s"/users/${juror.getId}/sessions/end", withToken(signedIn(admin), csrf), Some(csrf))) ===
          FORBIDDEN
        status(send(app, POST, s"/users/${juror.getId}/sessions/end", withToken(signedIn(juror), csrf), Some(csrf))) ===
          FORBIDDEN
        status(send(app, POST, s"/users/${juror.getId}/sessions/end", withToken(signedIn(root), csrf))) === FORBIDDEN
        User.findById(juror.getId).get.authVersion === juror.authVersion
        signedInAs(app, juror, signedIn(juror)) must beTrue

        // an admin's edit page of a member has no such button
        contentAsString(send(app, GET, s"/users/edit/${juror.getId}", signedIn(admin))) must
          not(contain("/sessions/end"))
        // no such account: not found
        status(send(app, POST, "/users/999999/sessions/end", withToken(signedIn(root), csrf), Some(csrf))) === NOT_FOUND
      }
    }
  }

  "one's own password change" should {

    "end the account's other sessions, keep this one with the new version, and keep the API tokens" in {
      withCsrfApp { implicit app =>
        createContests(10)
        val juror = contestMember("juror@example.com", password = "old-password")
        val tool = ApiTokenService.create(juror.getId, "tool", None)
        val csrf = token(app)
        val here = withToken(signedIn(juror), csrf)
        val elsewhere = signedIn(juror)

        val form = Seq("id" -> juror.getId.toString, "fullname" -> juror.fullname, "email" -> juror.email,
          "wikiAccount" -> "", "password" -> "new-password", "currentPassword" -> "old-password")
        val (result, lines) = accountLog { val r = send(app, POST, "/users/save", here, Some(csrf), form); status(r); r }
        status(result) === SEE_OTHER
        lines must contain(s"password_changed accountId=${juror.getId} by=self sessionsEnded=true")

        val newVersion = User.findById(juror.getId).get.authVersion
        newVersion !== juror.authVersion
        session(result).get(Secured.AuthVersion) === Some(newVersion.toString)
        signedInAs(app, juror, session(result).data.toSeq) must beTrue
        signedInAs(app, juror, elsewhere) must beFalse
        signedInAs(app, juror, here) must beFalse

        ApiTokenService.authenticate(tool) must beSome
      }
    }
  }

  "revoke all tokens" should {

    "revoke every token of the user only, with the CSRF token" in {
      withCsrfApp { implicit app =>
        createContests(10)
        val juror = contestMember("juror@example.com")
        val other = contestMember("other@example.com")
        val first = ApiTokenService.create(juror.getId, "one", None)
        val second = ApiTokenService.create(juror.getId, "two", Some(30))
        val others = ApiTokenService.create(other.getId, "theirs", None)
        val csrf = token(app)
        val here = withToken(signedIn(juror), csrf)

        val profile = contentAsString(send(app, GET, s"/users/edit/${juror.getId}", here))
        profile must contain("""action="/users/tokens/revoke-all"""")

        status(send(app, POST, "/users/tokens/revoke-all", here)) === FORBIDDEN
        ApiTokenService.authenticate(first) must beSome

        val result = send(app, POST, "/users/tokens/revoke-all", here, Some(csrf))
        status(result) === SEE_OTHER
        redirectLocation(result) === Some(routes.UserController.editUser(juror.getId).url)
        flash(result).get("api-tokens-revoked-all") === Some("2")
        ApiTokenService.authenticate(first) must beNone
        ApiTokenService.authenticate(second) must beNone
        ApiTokenJdbc.findByUser(juror.getId).forall(_.revoked) must beTrue
        ApiTokenService.authenticate(others) must beSome
      }
    }
  }

  "keep me signed in" should {

    "be a checkbox on the sign-in form, checked by default" in {
      withCsrfApp { implicit app =>
        val html = contentAsString(route(app, FakeRequest(GET, "/auth")).get)
        html must contain("""name="remember" id="keep-signed-in" value="true" checked""")
      }
    }

    "make a persistent cookie when checked, a browser-session cookie when not" in {
      withCsrfApp { implicit app =>
        createContests(10)
        val juror = contestMember("juror@example.com")
        def signIn(fields: (String, String)*) =
          route(app, FakeRequest(POST, "/auth")
            .withFormUrlEncodedBody(Seq("login" -> juror.email, "password" -> "secret",
              "returnTo" -> s"/users/edit/${juror.getId}") ++ fields: _*)).get

        val kept = signIn("remember" -> "true")
        status(kept) === SEE_OTHER
        val keptCookie = setCookie(app, kept)
        keptCookie must contain("PLAY_SESSION=")
        keptCookie must contain(s"Max-Age=${30 * day}")
        val keptSession = sessionCookie(app, kept)
        keptSession.get(SessionLifetime.Keep) === Some("1")
        keptSession.get(Secured.AccountId) === Some(juror.getId.toString)
        keptSession.get(SessionLifetime.IssuedAt).map(_.toLong) must beSome(beCloseTo(nowSec, 5L))

        val browser = signIn()
        status(browser) === SEE_OTHER
        val browserCookie = setCookie(app, browser)
        browserCookie must contain("PLAY_SESSION=")
        browserCookie must not(contain("Max-Age"))
        browserCookie must not(contain("Expires"))
        val browserSession = sessionCookie(app, browser)
        browserSession.get(SessionLifetime.Keep) must beNone
        browserSession.get(SessionLifetime.IssuedAt) must beSome
      }
    }
  }

  "a session's lifetime" should {

    "end a session past the absolute limit, keeping the CSRF token" in {
      withCsrfApp { implicit app =>
        createContests(10)
        val juror = contestMember("juror@example.com")
        val csrf = token(app)
        val old = withToken(signedIn(juror, issuedAgo = 366 * day, seenAgo = 60), csrf)
        val result = send(app, GET, s"/users/edit/${juror.getId}", old)
        status(result) === SEE_OTHER
        redirectLocation(result).get must startWith("/auth")
        session(result).get(Secured.UserName) must beNone
        session(result).get(Secured.AccountId) must beNone
        session(result).get("csrfToken") === Some(csrf)
        signedInAs(app, juror, signedIn(juror, issuedAgo = 364 * day, seenAgo = 60)) must beTrue
      }
    }

    "end a session idle past the limit" in {
      withCsrfApp { implicit app =>
        createContests(10)
        val juror = contestMember("juror@example.com")
        signedInAs(app, juror, signedIn(juror, issuedAgo = 40 * day, seenAgo = 31 * day)) must beFalse
        signedInAs(app, juror, signedIn(juror, issuedAgo = 40 * day, seenAgo = 29 * day)) must beTrue
      }
    }

    "refresh the last request time only once it is a few minutes old" in {
      withCsrfApp { implicit app =>
        createContests(10)
        val juror = contestMember("juror@example.com")
        val csrf = token(app)
        val recent = withToken(signedIn(juror, seenAgo = 60), csrf)
        val recentSeen = recent.toMap.apply(SessionLifetime.LastSeen)
        session(send(app, GET, s"/users/edit/${juror.getId}", recent)).get(SessionLifetime.LastSeen) must
          beNone.or(beSome(recentSeen))

        val stale = withToken(signedIn(juror, seenAgo = 600), csrf)
        session(send(app, GET, s"/users/edit/${juror.getId}", stale)).get(SessionLifetime.LastSeen)
          .map(_.toLong) must beSome(beCloseTo(nowSec, 5L))
      }
    }

    "refresh a persistent session's cookie with Max-Age, never past the absolute limit" in {
      withCsrfApp { implicit app =>
        createContests(10)
        val juror = contestMember("juror@example.com")
        val young = send(app, GET, s"/users/edit/${juror.getId}", signedIn(juror, seenAgo = 600, keep = true))
        status(young) === OK
        cookies(young).get("PLAY_SESSION").flatMap(_.maxAge).map(_.toLong) must beSome(beCloseTo(30 * day, 5L))
        sessionCookie(app, young).get(SessionLifetime.LastSeen).map(_.toLong) must beSome(beCloseTo(nowSec, 5L))

        val old = send(app, GET, s"/users/edit/${juror.getId}", signedIn(juror, issuedAgo = 364 * day, seenAgo = 600,
          keep = true))
        cookies(old).get("PLAY_SESSION").flatMap(_.maxAge).map(_.toLong) must beSome(beCloseTo(day, 5L))
      }
    }

    "upgrade a session from before S3 in place: still signed in, its fields stamped" in {
      withCsrfApp { implicit app =>
        createContests(10)
        val juror = contestMember("juror@example.com")
        juror.authVersion === 0
        val old = Seq(Secured.UserName -> juror.email, Secured.CurrentContestId -> "10")
        val result = send(app, GET, s"/users/edit/${juror.getId}", old)
        status(result) === OK
        val upgraded = session(result)
        upgraded.get(Secured.AccountId) === Some(juror.getId.toString)
        upgraded.get(Secured.AuthVersion) === Some("0")
        upgraded.get(SessionLifetime.IssuedAt).map(_.toLong) must beSome(beCloseTo(nowSec, 5L))
        upgraded.get(SessionLifetime.LastSeen).map(_.toLong) must beSome(beCloseTo(nowSec, 5L))
        upgraded.get(SessionLifetime.Keep) must beNone
        signedInAs(app, juror, upgraded.data.toSeq) must beTrue
      }
    }

    "refuse a session from before S3 of another account or an older version" in {
      withCsrfApp { implicit app =>
        createContests(10)
        val juror = contestMember("juror@example.com")
        val other = contestMember("other@example.com")
        val csrf = token(app)
        val mismatch = withToken(Seq(Secured.UserName -> juror.email, Secured.AccountId -> other.getId.toString), csrf)
        val refused = send(app, GET, s"/users/edit/${juror.getId}", mismatch)
        status(refused) === SEE_OTHER
        session(refused).get(Secured.UserName) must beNone
        session(refused).get("csrfToken") === Some(csrf)

        User.endSessions(juror.getId)
        signedInAs(app, juror, Seq(Secured.UserName -> juror.email)) must beFalse
      }
    }

    "upgrade the same pre-S3 cookie again when it is replayed, but only within the window (M1)" in {
      withCsrfApp { implicit app =>
        createContests(10)
        val juror = contestMember("juror@example.com")
        val old = Seq(Secured.UserName -> juror.email, Secured.CurrentContestId -> "10")
        // the cookie as the browser had it before S3, sent twice (never the upgraded value)
        status(send(app, GET, s"/users/edit/${juror.getId}", old)) === OK
        status(send(app, GET, s"/users/edit/${juror.getId}", old)) === OK
      }
      // after the window (a fresh app reads it): the old cookie is signed out, an S3 one is not
      withCsrfApp { implicit app =>
        createContests(10)
        val juror = contestMember("juror@example.com")
        setWindow(-1)
        try {
          val old = Seq(Secured.UserName -> juror.email, Secured.CurrentContestId -> "10", "csrfToken" -> token(app))
          val (refused, lines) = authLog { val r = send(app, GET, s"/users/edit/${juror.getId}", old); status(r); r }
          status(refused) === SEE_OTHER
          redirectLocation(refused).get must startWith("/auth")
          session(refused).get(Secured.UserName) must beNone
          lines must contain("session_expired reason=old_cookie_expired accountId=none")
          signedInAs(app, juror, signedIn(juror)) must beTrue
        } finally setWindow(30)
      }
    }

    "write an upgrade or a refresh only from a page or a POST, not a script's request (L1)" in {
      withCsrfApp { implicit app =>
        createContests(10)
        val juror = contestMember("juror@example.com")
        def script(session: Seq[(String, String)]) =
          route(app, FakeRequest(GET, s"/users/edit/${juror.getId}")
            .withHeaders(COOKIE -> "PLAY_SESSION=signed-in", "X-Requested-With" -> "XMLHttpRequest")
            .withSession(session: _*)).get

        // the profile itself puts its form's nonce in the session: that write keeps last_seen as sent
        val staleSession = withToken(signedIn(juror, seenAgo = 600), token(app))
        val stale = script(staleSession)
        status(stale) === OK
        sessionCookie(app, stale).get(SessionLifetime.LastSeen) must
          beNone.or(beSome(staleSession.toMap.apply(SessionLifetime.LastSeen)))

        val keptSession = withToken(signedIn(juror, seenAgo = 600, keep = true), token(app))
        val kept = script(keptSession)
        status(kept) === OK
        sessionCookie(app, kept).get(SessionLifetime.LastSeen) must
          beNone.or(beSome(keptSession.toMap.apply(SessionLifetime.LastSeen)))

        val old = script(Seq(Secured.UserName -> juror.email, "csrfToken" -> token(app)))
        status(old) === OK
        session(old).get(SessionLifetime.IssuedAt) must beNone

        // a sign-out for the limits is written by any response
        val expired = script(withToken(signedIn(juror, issuedAgo = 400 * day), token(app)))
        status(expired) === UNAUTHORIZED
        session(expired).get(Secured.UserName) must beNone
      }
    }

    "refuse a session of an older version" in {
      withCsrfApp { implicit app =>
        createContests(10)
        val juror = contestMember("juror@example.com")
        val before = signedIn(juror)
        User.endSessions(juror.getId)
        signedInAs(app, juror, before) must beFalse
        signedInAs(app, juror, signedIn(User.findById(juror.getId).get)) must beTrue
      }
    }
  }
}
