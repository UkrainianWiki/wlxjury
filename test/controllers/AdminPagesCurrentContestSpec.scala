package controllers

import db.scalikejdbc.{ContestJuryJdbc, PlayTestDb, Round, User, UserContestJdbc}
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import scala.concurrent.Future

/** The admin pages whose `contestId` is optional (rounds, users, import, greeting): without
  * it, the session's current contest, checked as if the page named it.
  */
class AdminPagesCurrentContestSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val pages = Seq("/admin/rounds", "/admin/users", "/admin/importusers", "/admin/greeting")

  private def member(email: String, memberships: (Long, String)*): User = {
    val created = User.create(User(email, email, None, Set.empty, Some("hash")))
    memberships.foreach { case (contest, role) => UserContestJdbc.createMembership(created.getId, contest, role) }
    created
  }

  /** Contests 10 and 20, each with a named round and its admin. */
  private def fixture(): (User, User) = {
    createContests(10, 20, 30)
    Round.create(Round(None, 1, Some("Round of ten"), 10, active = true))
    Round.create(Round(None, 1, Some("Round of twenty"), 20, active = true))
    (member("ann@example.com", 10L -> "admin"), member("bob@example.com", 20L -> "admin"))
  }

  private def get(path: String, user: User, currentContest: Option[Long], headers: (String, String)*)(implicit
      app: Application
  ): Future[Result] =
    route(app,
      FakeRequest(GET, path)
        .withSession(Seq(Secured.UserName -> user.email) ++ currentContest.map(Secured.CurrentContestId -> _.toString): _*)
        .withHeaders(headers: _*)
        .withCSRFToken
    ).get

  /** `f`'s result and the denials ([[Secured.logDenial]]) logged on security.authz meanwhile. */
  private def withAuthzLog[T](f: => T): (T, Seq[String]) = {
    val logger = org.slf4j.LoggerFactory.getLogger("security.authz").asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    appender.start()
    logger.addAppender(appender)
    try {
      val result = f
      import scala.jdk.CollectionConverters._
      (result, appender.list.asScala.toSeq.map(_.getFormattedMessage))
    } finally logger.detachAppender(appender)
  }

  /** The log lines with all of `parts`. */
  private def lines(log: Seq[String], parts: String*): Int = log.count(line => parts.forall(line.contains))

  /** Each page without `contestId` is denied, logged with the session's contest as the target. */
  private def deniedInCurrentContest(user: User, currentContest: Long, reason: String)(implicit app: Application) =
    pages.foreach { path =>
      val (result, log) = withAuthzLog {
        val result = get(path, user, Some(currentContest))
        status(result)
        result
      }
      status(result) aka path mustEqual FORBIDDEN
      session(result).get(Secured.CurrentContestId) aka path must beNone
      lines(log, s"currentContestId=$currentContest targetContestId=$currentContest resource=\"contest:$currentContest\"",
        s"reason=$reason") aka path mustEqual 1
    }

  "an admin page without contestId" should {
    "show the contest's admin their session's current contest" in {
      testDbApp { implicit app =>
        val (ann, _) = fixture()
        pages.foreach(path => status(get(path, ann, Some(10))) aka path mustEqual OK)

        val rounds = contentAsString(get("/admin/rounds", ann, Some(10)))
        rounds must contain("Round of ten")
        rounds must not contain "Round of twenty"
        val users = contentAsString(get("/admin/users", ann, Some(10)))
        users must contain("ann@example.com")
        users must not contain "bob@example.com"
      }
    }

    "show a member of another contest that contest, never the one the cookie names" in {
      testDbApp { implicit app =>
        val (_, bob) = fixture()
        // not a member of 10: the session user is in their own contest, 20
        val rounds = get("/admin/rounds", bob, Some(10))
        status(rounds) mustEqual OK
        contentAsString(rounds) must contain("Round of twenty")
        contentAsString(rounds) must not contain "Round of ten"
        contentAsString(get("/admin/users", bob, Some(10))) must not contain "ann@example.com"
      }
    }

    "save the greeting of the session's current contest" in {
      testDbApp { implicit app =>
        val (ann, _) = fixture()
        val result = route(app,
          FakeRequest(POST, "/admin/greeting")
            .withSession(Secured.UserName -> ann.email, Secured.CurrentContestId -> "10")
            .withFormUrlEncodedBody("greetingtemplate" -> "Welcome", "use" -> "true")
            .withCSRFToken
        ).get
        status(result) mustEqual SEE_OTHER
        ContestJuryJdbc.findById(10).flatMap(_.greeting.text) === Some("Welcome")
        ContestJuryJdbc.findById(20).flatMap(_.greeting.text) === None
      }
    }

    "deny a juror and an organizer of the contest" in {
      testDbApp { implicit app =>
        fixture()
        deniedInCurrentContest(member("jury@example.com", 10L -> "jury"), 10, "missing_role")
        deniedInCurrentContest(member("org@example.com", 10L -> "organizer"), 10, "missing_role")
        ok
      }
    }

    "deny a juror of the current contest who admins another, without switching to it" in {
      testDbApp { implicit app =>
        fixture()
        deniedInCurrentContest(member("carl@example.com", 10L -> "jury", 20L -> "admin"), 10, "missing_role")
        ok
      }
    }

    "not save the greeting for a juror" in {
      testDbApp { implicit app =>
        fixture()
        val juror = member("jury@example.com", 10L -> "jury")
        val result = route(app,
          FakeRequest(POST, "/admin/greeting")
            .withSession(Secured.UserName -> juror.email, Secured.CurrentContestId -> "10")
            .withFormUrlEncodedBody("greetingtemplate" -> "Hacked", "use" -> "true")
            .withCSRFToken
        ).get
        status(result) mustEqual FORBIDDEN
        ContestJuryJdbc.findById(10).flatMap(_.greeting.text) === None
      }
    }

    "deny a user in no contest, logging no target contest" in {
      testDbApp { implicit app =>
        fixture()
        val nobody = member("nobody@example.com")
        pages.foreach { path =>
          val (result, log) = withAuthzLog {
            val result = get(path, nobody, None)
            status(result)
            result
          }
          status(result) aka path mustEqual FORBIDDEN
          lines(log, "targetContestId=unknown", "reason=not_member") aka path mustEqual 1
        }
        ok
      }
    }

    "send root without a current contest to pick one, as before" in {
      testDbApp { implicit app =>
        fixture()
        val root = User.create(User("root", "root@example.com", None, Set(User.ROOT_ROLE), Some("hash")))
        pages.foreach(path => status(get(path, root, None)) aka path mustEqual SEE_OTHER)
        status(get("/admin/rounds?contestId=20", root, None)) mustEqual OK
      }
    }

    "send a signed-out page to the login page, and a script a JSON 401" in {
      testDbApp { implicit app =>
        fixture()
        pages.foreach { path =>
          val page = route(app, FakeRequest(GET, path)).get
          status(page) aka path mustEqual SEE_OTHER
          redirectLocation(page) aka path must beSome(startWith("/auth"))
          status(route(app, FakeRequest(GET, path).withHeaders("X-Requested-With" -> "XMLHttpRequest")).get) aka
            path mustEqual UNAUTHORIZED
        }
        ok
      }
    }
  }

  "an admin page naming a contest the user is not in" should {
    "be denied although the session's contest is one they admin" in {
      testDbApp { implicit app =>
        val (ann, _) = fixture()
        pages.foreach { path =>
          val (result, log) = withAuthzLog {
            val result = get(s"$path?contestId=30", ann, Some(10))
            status(result)
            result
          }
          status(result) aka path mustEqual FORBIDDEN
          session(result).get(Secured.CurrentContestId) aka path must beNone
          lines(log, "currentContestId=10 targetContestId=30", "reason=not_member") aka path mustEqual 1
        }
        ok
      }
    }

    "show the named contest to its admin" in {
      testDbApp { implicit app =>
        val (_, bob) = fixture()
        contentAsString(get("/admin/rounds?contestId=20", bob, Some(20))) must contain("Round of twenty")
      }
    }
  }
}
