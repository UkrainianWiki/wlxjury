package api

import controllers.{LogCapture, Secured}
import db.scalikejdbc.{ApiTokenScope, PlayTestDb, User, UserContestJdbc}
import play.api.Application
import play.api.libs.json.{JsArray, JsValue}
import play.api.mvc.Result
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._
import services.ApiTokenService
import services.diagnostics.HealthFixtures
import services.imports.ImportState
import services.jobs.{JobError, JobStatus}

import scala.concurrent.Future

/** The diagnostics endpoints (support diagnostics plan §3.4, D-1b) for `wlxjury-diag`:
  *
  *  - `GET /api/contests/{id}/health`, the health page's report as JSON, for root and the
  *    contest's admins (DG2) only: organizers, jurors, inactive members and other contests' admins
  *    get 403 (logged on `security.authz`), a retired contest is root's only (as the page), and a
  *    contest that doesn't exist is a 404 for root;
  *  - `GET /api/diagnostics/recent`, every contest's jobs and round status changes of the window,
  *    the worst first: root only; a window out of range is a 400.
  */
class DiagnosticsApiSpec extends PlaySpecification with PlayTestDb {

  sequential

  private def root(): User = User.create(User("Root", "root@example.org", None, Set(User.ROOT_ROLE), isRoot = true))

  private def get(app: Application, path: String, as: Option[User] = None, token: Option[String] = None,
      current: Option[Long] = None): Future[Result] = {
    val base = FakeRequest(GET, path)
    val signedIn = as.fold(base)(u => base.withSession(
      Seq(Secured.UserName -> u.email) ++ current.map(Secured.CurrentContestId -> _.toString): _*))
    val request = token.fold(signedIn)(t => signedIn.withHeaders(AUTHORIZATION -> s"Bearer $t"))
    val result = route(app, request).get
    scala.concurrent.Await.ready(result, scala.concurrent.duration.Duration(30, "seconds"))
    result
  }

  private def json(r: Future[Result]): JsValue = contentAsJson(r)

  private def hintKeys(health: JsValue): Seq[String] =
    (health \ "checks").as[JsArray].value.toSeq.flatMap(c => (c \ "hints").as[JsArray].value.map(h => (h \ "key").as[String]))

  "the contest health endpoint" should {

    "give the Nepal case's report to the contest's admin, and to root's read-only token" in {
      testDbApp { app =>
        val n = HealthFixtures.nepal()
        val id = n.contest.getId
        val r = get(app, s"/api/contests/$id/health", as = Some(n.admin), current = n.contest.id)
        status(r) === OK
        val body = json(r)
        (body \ "contest" \ "id").as[Long] === id
        (body \ "outcome").as[String] === "fail"
        (body \ "files").as[Long] === 32L
        hintKeys(body) must contain(allOf("health.h1.warn", "health.h2.warn", "health.h4.notincountries", "health.h7.empty"))
        val h7 = (body \ "checks").as[JsArray].value.find(c => (c \ "code").as[String] == "H7").get
        (h7 \ "title").as[String] === "Empty or tiny rounds"
        ((h7 \ "hints")(0) \ "text").as[String] === "Round 2 has no files. Its source and filters are under Rounds below."
        (body \ "rounds").as[JsArray].value.map(r => (r \ "number").as[Long]) === Seq(1L, 2L)
        (body \ "imports").as[JsArray].value.map(i => (i \ "jobId").as[Long]) must contain(n.winnersImport)
        contentAsString(r) must not(contain("@example.org"))

        val rootUser = root()
        val token = ApiTokenService.create(rootUser.getId, "diag", Some(90), scope = ApiTokenScope.LocalRead)
        val byToken = get(app, s"/api/contests/$id/health", token = Some(token))
        status(byToken) === OK
        hintKeys(json(byToken)) must contain("health.h7.empty")
      }
    }

    "refuse organizers, jurors, inactive members and other contests' admins; signed out 401" in {
      testDbApp { app =>
        val n = HealthFixtures.nepal()
        val id = n.contest.getId
        status(get(app, s"/api/contests/$id/health")) === UNAUTHORIZED

        def member(role: String) = User.create(User(role, s"$role@example.org", None, Set(role), contestId = n.contest.id))
        val otherContest = HealthFixtures.contestWithFiles("Wiki Loves Earth", 2026, "", None, Seq(800L))
        val otherAdmin = User.create(User("Other", "other.admin@example.org", None, Set("admin"), contestId = otherContest.id))
        val refused = Seq("organizer", "jury", "no").map(member).map(_ -> n.contest.id) ++
          Seq(otherAdmin -> otherContest.id, otherAdmin -> n.contest.id)
        val (_, logs) = LogCapture("security.authz") {
          forall(refused) { case (user, current) =>
            val r = get(app, s"/api/contests/$id/health", as = Some(user), current = current)
            status(r) === FORBIDDEN
            (json(r) \ "error").as[String] === "forbidden"
            contentAsString(r) must not(contain(HealthFixtures.NepalCategory))
          }
        }
        logs("security.authz").count(l => l.contains(s"targetContestId=$id") && l.contains("auth=session")) === refused.size
        // another contest's admin by token: the token's account decides, as in a browser
        val token = ApiTokenService.create(otherAdmin.getId, "diag", Some(90), scope = ApiTokenScope.LocalRead)
        status(get(app, s"/api/contests/$id/health", token = Some(token))) === FORBIDDEN
        status(get(app, s"/api/contests/${otherContest.getId}/health", token = Some(token))) === OK
      }
    }

    "treat a retired contest as the app does: root only" in {
      testDbApp { app =>
        val n = HealthFixtures.nepal()
        val id = n.contest.getId
        DB.autoCommit(implicit s => sql"UPDATE contest_jury SET retired_at = NOW(3) WHERE id = $id".update.apply())
        val (r, logs) = LogCapture("security.authz")(get(app, s"/api/contests/$id/health", as = Some(n.admin), current = n.contest.id))
        status(r) === FORBIDDEN
        logs("security.authz").exists(_.contains("reason=contest_retired")) must beTrue
        val asRoot = get(app, s"/api/contests/$id/health", as = Some(root()))
        status(asRoot) === OK
        (json(asRoot) \ "contest" \ "retired").as[Boolean] must beTrue
      }
    }

    "answer 404 for root on a contest that doesn't exist, and 400 for an id that isn't one" in {
      testDbApp { app =>
        val rootUser = root()
        status(get(app, "/api/contests/999999/health", as = Some(rootUser))) === NOT_FOUND
        (json(get(app, "/api/contests/999999/health", as = Some(rootUser))) \ "error").as[String] === "not_found"
        status(get(app, "/api/contests/abc/health", as = Some(rootUser))) must beOneOf(BAD_REQUEST, NOT_FOUND)
      }
    }
  }

  "the recent activity endpoint" should {

    "list the window's contests, the one with a failed job first, with its jobs and round changes" in {
      testDbApp { app =>
        val n = HealthFixtures.nepal()
        val quiet = HealthFixtures.contestWithFiles("Wiki Loves Earth", 2026, "Ukraine", None, Seq(900L))
        HealthFixtures.importJob(quiet, "Category:Quiet", ImportState(seen = 1, inserted = 1))
        val failed = HealthFixtures.importJob(n.contest, "Category:Broken", ImportState(), status = JobStatus.Failed,
          error = Some(JobError("job.error.unexpected", reference = Some("1a2b3c4d")).encode))
        val old = HealthFixtures.contestWithFiles("Wiki Loves Earth", 2019, "", None, Seq(950L))
        HealthFixtures.importJob(old, "Category:Old", ImportState(), daysAgo = 40)
        DB.autoCommit(implicit s => sql"UPDATE job SET finished_at = created_at WHERE contest_id = ${old.getId}".update.apply())
        DB.autoCommit { implicit s =>
          sql"UPDATE round SET status_changed_at = NOW(3) WHERE id = ${n.round1974.getId}".update.apply()
          sql"UPDATE round SET status_changed_at = NOW(3) - INTERVAL 3 DAY WHERE id = ${n.round1975.getId}".update.apply()
        }

        val rootUser = root()
        val r = get(app, "/api/diagnostics/recent", as = Some(rootUser))
        status(r) === OK
        val body = json(r)
        (body \ "hours").as[Int] === 24
        val contests = (body \ "contests").as[JsArray].value.toSeq
        contests.map(c => (c \ "contest" \ "id").as[Long]) === Seq(n.contest.getId, quiet.getId)
        (contests.head \ "outcome").as[String] === "fail"
        (contests(1) \ "outcome").as[String] === "pass"
        val failedLine = (contests.head \ "jobs").as[JsArray].value.find(j => (j \ "id").as[Long] == failed).get
        (failedLine \ "status").as[String] === "failed"
        (failedLine \ "reference").as[String] === "1a2b3c4d"
        (failedLine \ "superseded").as[Boolean] must beFalse
        (contests.head \ "roundChanges").as[JsArray].value.map(c => (c \ "number").as[Long]) === Seq(1L)
        (contests.head \ "contest" \ "name").as[String] === "Wiki Loves Earth"

        // a wider window takes the older round change
        val week = json(get(app, "/api/diagnostics/recent?hours=168", as = Some(rootUser)))
        ((week \ "contests")(0) \ "roundChanges").as[JsArray].value.map(c => (c \ "number").as[Long]) === Seq(1L, 2L)
      }
    }

    "be root's only, and refuse a window out of range" in {
      testDbApp { app =>
        val n = HealthFixtures.nepal()
        status(get(app, "/api/diagnostics/recent")) === UNAUTHORIZED
        val (r, logs) = LogCapture("security.authz")(get(app, "/api/diagnostics/recent", as = Some(n.admin), current = n.contest.id))
        status(r) === FORBIDDEN
        logs("security.authz").exists(_.contains("requiredRoles=\"root\"")) must beTrue
        val rootUser = root()
        forall(Seq("0", "721", "-1", "x")) { hours =>
          status(get(app, s"/api/diagnostics/recent?hours=$hours", as = Some(rootUser))) === BAD_REQUEST
        }
        status(get(app, "/api/diagnostics/recent?hours=720", as = Some(rootUser))) === OK
      }
    }
  }
}
