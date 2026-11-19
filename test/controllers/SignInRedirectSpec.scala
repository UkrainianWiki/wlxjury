package controllers

import db.scalikejdbc.{PlayTestDb, Round, RoundUser, User}
import play.api.Application
import play.api.mvc.{Result, Session}
import play.api.test.{FakeRequest, PlaySpecification}

import scala.concurrent.Future

/** A sign-in always answers with a redirect (303) to the start page, or to its `returnTo`, never
  * with a page: a page in answer to the sign-in's POST (a juror's "no round" page) was posted again
  * on a reload, and its own forms (Sign out, a contest switch) failed CSRF, since the new session
  * had no token until a GET rendered one (refs ba1126f9, 92ca61d7, 2026-11-19). A signed-in
  * user's sign-in form posted again without a valid token changes nothing and goes (303) to a
  * page naming the signed-in account (Continue, Sign out), logged as any refused token. Through the router with the release's filters (CSRF on).
  */
class SignInRedirectSpec extends PlaySpecification with PlayTestDb with AccountTestSupport {

  sequential

  private val email = "juror@example.com"

  private val TokenField = """name="csrfToken" value="([^"]+)"""".r

  private def formToken(html: String): String = TokenField.findFirstMatchIn(html).map(_.group(1)).get

  private def request(method: String, path: String, session: Session) =
    FakeRequest(method, path)
      .withHeaders(ACCEPT -> "text/html", COOKIE -> "PLAY_SESSION=from-the-page")
      .withSession(session.data.toSeq: _*)

  /** The sign-in as a browser posts it: the sign-in page's token in its session and its form. */
  private def signIn(app: Application, extra: (String, String)*): Future[Result] = {
    val page = route(app, FakeRequest(GET, "/auth").withHeaders(ACCEPT -> "text/html")).get
    route(app, request(POST, "/auth", session(page))
      .withFormUrlEncodedBody(Seq("login" -> email, "password" -> "secret",
        "csrfToken" -> formToken(contentAsString(page))) ++ extra: _*)).get
  }

  "a password sign-in" should {
    "redirect a juror without an open round to the start page, whose forms then work" in {
      CsrfTestApp.withCsrfApp { implicit app =>
        createContests(10)
        account(email, Some(10), "jury", verified = true)
        val signedIn = signIn(app)
        (status(signedIn) === SEE_OTHER) and
          (redirectLocation(signedIn) must beSome("/")) and
          (session(signedIn).get(Secured.UserName) must beSome(email)) and {
            // the start page: the no-round page, with a CSRF token in the session for its forms
            val start = route(app, request(GET, "/", session(signedIn))).get
            val html = contentAsString(start)
            (status(start) === OK) and
              (html must contain("No round configured yet.")) and
              (session(start).get("csrfToken") must beSome) and {
                // Sign out with the page's own token works
                val logout = route(app, request(POST, "/logout", session(start))
                  .withFormUrlEncodedBody("csrfToken" -> formToken(html))).get
                (status(logout) === SEE_OTHER) and
                  (redirectLocation(logout) must beSome(startWith("/auth"))) and
                  (session(logout).get(Secured.UserName) must beNone)
              }
          }
      }
    }

    "redirect a juror with an open round to the start page, which goes on to the round" in {
      CsrfTestApp.withCsrfApp { implicit app =>
        createContests(10)
        val juror = account(email, Some(10), "jury", verified = true)
        val round = Round.create(Round(None, 1, contestId = 10, active = true))
        round.addUsers(Seq(RoundUser(round.getId, juror.getId, "jury", active = true)))
        val signedIn = signIn(app)
        val start = route(app, request(GET, "/", session(signedIn))).get
        (status(signedIn) === SEE_OTHER) and
          (redirectLocation(signedIn) must beSome("/")) and
          (status(start) === SEE_OTHER) and
          (redirectLocation(start) must beSome(contain(s"/round/${round.getId}/")))
      }
    }

    "redirect an organizer to the start page too" in {
      CsrfTestApp.withCsrfApp { implicit app =>
        createContests(10)
        account(email, Some(10), "organizer", verified = true)
        val signedIn = signIn(app)
        (status(signedIn) === SEE_OTHER) and (redirectLocation(signedIn) must beSome("/"))
      }
    }

    "go to the start page for a returnTo of another site" in {
      CsrfTestApp.withCsrfApp { implicit app =>
        createContests(10)
        account(email, Some(10), "jury", verified = true)
        forall(Seq("//evil.example/", "/\\evil.example", "https://evil.example/")) { r =>
          val signedIn = signIn(app, "returnTo" -> r)
          (status(signedIn) aka r must_== SEE_OTHER) and (redirectLocation(signedIn) aka r must beSome("/"))
        }
      }
    }

    "still go to its returnTo" in {
      CsrfTestApp.withCsrfApp { implicit app =>
        createContests(10)
        val juror = account(email, Some(10), "jury", verified = true)
        val signedIn = signIn(app, "returnTo" -> s"/users/edit/${juror.getId}")
        (status(signedIn) === SEE_OTHER) and
          (redirectLocation(signedIn) must beSome(s"/users/edit/${juror.getId}"))
      }
    }
  }

  "the sign-in form posted again by a signed-in user without a valid token" should {
    "change nothing and lead to a page naming the signed-in account, whose actions work" in {
      CsrfTestApp.withCsrfApp { implicit app =>
        createContests(10, 20)
        val first = User.create(User("Juror One", email, None, Set("jury"), Some(hash("secret")), Some(10L),
          emailVerifiedAt = Some(java.time.ZonedDateTime.now)))
        val second = account("other@example.com", Some(20), "jury", password = Some("other-secret"), verified = true)
        val signedIn = signIn(app)
        // a stale sign-in tab on a shared computer: another person's credentials, with that
        // page's token in the form and none in the session the first sign-in made
        val (repost, logs) = LogCapture("CsrfErrorHandler") {
          val result = route(app, request(POST, "/auth", session(signedIn))
            .withFormUrlEncodedBody("login" -> second.email, "password" -> "other-secret",
              "csrfToken" -> "stale")).get
          (status(result), redirectLocation(result), session(result))
        }
        val (repostStatus, location, after) = repost
        (repostStatus === SEE_OTHER) and
          (location must beSome("/auth/signed-in")) and
          // the session is still the first account's, in its contest
          (after.get(Secured.UserName) must beSome(email)) and
          (after.get(Secured.AccountId) must beSome(first.getId.toString)) and
          (after.get(Secured.CurrentContestId) must beSome("10")) and
          (logs("CsrfErrorHandler") must contain(beMatching(
            """csrf_failure ref=[0-9a-f]{8} method=POST path="/auth" signedIn=true .*"""))) and {
            val page = route(app, request(GET, "/auth/signed-in", after)).get
            val html = contentAsString(page)
            (status(page) === OK) and
              (html must contain("You are already signed in as Juror One.")) and
              (html must contain("To sign in with another account, sign out first.")) and
              (html must not(contain("other@example.com"))) and
              (html must contain("""id="signed-in-continue" href="/"""")) and
              (session(page).get("csrfToken") must beSome) and
              (session(page).get(Secured.UserName) must beSome(email)) and {
                val logout = route(app, request(POST, "/logout", session(page))
                  .withFormUrlEncodedBody("csrfToken" -> formToken(html))).get
                (status(logout) === SEE_OTHER) and
                  (redirectLocation(logout) must beSome(startWith("/auth"))) and
                  (session(logout).get(Secured.UserName) must beNone)
              }
          }
      }
    }

    "send a signed-out visitor of the signed-in page to the sign-in page, which never returns there" in {
      CsrfTestApp.withCsrfApp { implicit app =>
        createContests(10)
        account(email, Some(10), "jury", verified = true)
        val page = route(app, FakeRequest(GET, "/auth/signed-in").withHeaders(ACCEPT -> "text/html")).get
        val location = redirectLocation(page).map(java.net.URI.create)
        val signedIn = signIn(app, "returnTo" -> "/auth/signed-in")
        (status(page) === SEE_OTHER) and
          (location.map(_.getPath) must beSome("/auth")) and
          (location.flatMap(l => Option(l.getQuery)) must beSome("returnTo=/auth/signed-in")) and
          // signed in from there: the start page, not "already signed in"
          (status(signedIn) === SEE_OTHER) and (redirectLocation(signedIn) must beSome("/"))
      }
    }

    "while other refused forms of a signed-in user, and a signed-out sign-in, keep the error page" in {
      CsrfTestApp.withCsrfApp { implicit app =>
        createContests(10)
        account(email, Some(10), "jury", verified = true)
        val signedIn = signIn(app)
        val logout = route(app, request(POST, "/logout", session(signedIn))
          .withFormUrlEncodedBody("csrfToken" -> "stale")).get
        val signedOut = route(app, request(POST, "/auth", Session())
          .withFormUrlEncodedBody("login" -> email, "password" -> "secret", "csrfToken" -> "stale")).get
        (status(logout) === FORBIDDEN) and (status(signedOut) === FORBIDDEN) and
          (session(signedOut).get(Secured.UserName) must beNone)
      }
    }
  }
}
