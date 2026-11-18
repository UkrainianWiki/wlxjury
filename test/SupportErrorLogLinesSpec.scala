import controllers.{LogCapture, Secured}
import db.scalikejdbc.{PlayTestDb, User}
import play.api.test.{FakeRequest, PlaySpecification}

/** `server_error` and `csrf_failure` (support diagnostics plan, D-0b): with the session's account
  * and contest ids, never the email. ErrorHandler and CsrfErrorHandler are in the root package.
  */
class SupportErrorLogLinesSpec extends PlaySpecification with PlayTestDb {

  sequential

  "server_error and csrf_failure" should {
    "carry the session's account and contest ids, never the email" in {
      testDbApp { implicit app =>
        val contest = contestDao.create(None, "WLE", 2024, "Ukraine")
        val admin = userDao.create(User("Admin", "admin@example.com", None, Set("admin"), contestId = contest.id))
        def signedIn(method: String, path: String) =
          FakeRequest(method, path).withSession(Secured.UserName -> admin.email,
            Secured.AccountId -> admin.getId.toString, Secured.CurrentContestId -> contest.getId.toString)
        val errors = app.injector.instanceOf[ErrorHandler]
        val csrf = app.injector.instanceOf[CsrfErrorHandler]

        val (_, logs) = LogCapture("ErrorHandler", "CsrfErrorHandler") {
          status(errors.onServerError(signedIn(GET, "/rounds"), new RuntimeException("boom")))
          status(errors.onServerError(FakeRequest(GET, "/rounds"), new RuntimeException("boom")))
          status(csrf.handle(signedIn(POST, "/rate/round/1/pageid/2/select/1"), "No CSRF token found"))
          status(csrf.handle(FakeRequest(POST, "/logout"), "No CSRF token found"))
        }
        val Seq(serverSignedIn, serverSignedOut) = logs("ErrorHandler")
        // server_error: logged first, from the signed session's ids, before the user's lookup
        serverSignedIn must beMatching(s"""server_error ref=[0-9a-f]{8} method=GET path="/rounds" """ +
          s"accountId=${admin.getId} contestId=${contest.getId} accountFrom=session")
        serverSignedOut must endWith("""path="/rounds" accountId=- contestId=- accountFrom=none""")
        val Seq(csrfSignedIn, csrfSignedOut) = logs("CsrfErrorHandler")
        csrfSignedIn must contain(
          s"signedIn=true accountId=${admin.getId} contestId=${contest.getId} accountFrom=user message=")
        csrfSignedOut must contain("signedIn=false accountId=- contestId=- accountFrom=none message=")
        forall(logs.values.flatten)(_ must not(contain(admin.email)))
      }
    }

  }
}
