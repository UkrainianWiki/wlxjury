package api

import controllers.{LogCapture, Secured}
import db.scalikejdbc.{ApiTokenScope, PlayTestDb, Round, User}
import play.api.Application
import play.api.libs.json.{JsArray, JsValue}
import play.api.mvc.Result
import play.api.mvc.request.RemoteConnection
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._
import services.ApiTokenService
import services.diagnostics.HealthFixtures
import services.jobs.JobScheduler
import services.series.ReuseScanHandler

import scala.concurrent.Future

/** The reuse dry-run report's endpoints (contest series plan §4.3, CY-0) for `wlxjury-diag reuse`:
  * `GET /api/diagnostics/reuse` (the latest scan's totals and contests) and
  * `GET /api/diagnostics/reuse/{id}` (one contest's clusters), root's only, by session or by root's
  * read-only, server-only token from the server itself: refused signed out (401), to a contest's
  * admin (403), and to that token from elsewhere or through a proxy (403); no juror's name in them.
  */
class ReuseApiSpec extends PlaySpecification with PlayTestDb {

  sequential

  private def root(): User = User.create(User("Root", s"root${System.nanoTime()}@example.org", None, Set(User.ROOT_ROLE), isRoot = true))

  private def get(app: Application, path: String, as: Option[User] = None, token: Option[String] = None,
      headers: Seq[(String, String)] = Nil, from: Option[RemoteConnection] = None): Future[Result] = {
    val base = FakeRequest(GET, path).withHeaders(headers: _*)
    val signedIn = as.fold(base)(u => base.withSession(Seq(Secured.UserName -> u.email) ++
      u.contestId.map(Secured.CurrentContestId -> _.toString): _*))
    val withToken = token.fold(signedIn)(t => signedIn.withHeaders(AUTHORIZATION -> s"Bearer $t"))
    val result = route(app, from.fold(withToken)(withToken.withConnection)).get
    scala.concurrent.Await.ready(result, scala.concurrent.duration.Duration(30, "seconds"))
    result
  }

  private def json(r: Future[Result]): JsValue = contentAsJson(r)

  "the reuse endpoints" should {

    "give root the latest scan and a contest's clusters, by session and by the read-only token from the server" in {
      testDbApp { app =>
        db.scalikejdbc.SharedTestDb.truncateAll()
        val c = HealthFixtures.contestWithFiles("Wiki Loves Earth", 2020, "Nepal", None, (1L to 10L) ++ (101L to 110L))
        HealthFixtures.inCountries("Category:Images from Wiki Loves Earth 2021 in ", "Nepal" -> (101L to 110L))
        val juror = User.create(User("Secret Juror", "secret.juror@example.org"))
        HealthFixtures.round(c, 1, "2020", status = Round.Status.Paused, files = 1L to 10L)
        val r2021 = HealthFixtures.round(c, 2, "2021", category = Some("Category:Images from Wiki Loves Earth 2021 in Nepal"),
          files = 101L to 110L, jurors = Seq(juror))
        val rootUser = root()

        val none = json(get(app, "/api/diagnostics/reuse", as = Some(rootUser)))
        (none \ "scan").toOption must beNone
        (none \ "contests").as[JsArray].value must beEmpty

        val scheduler = app.injector.instanceOf[JobScheduler]
        val queued = DB.localTx(implicit s => scheduler.enqueue(ReuseScanHandler.newJob(rootUser))).toOption.get
        scheduler.started(queued.id)

        val r = get(app, "/api/diagnostics/reuse", as = Some(rootUser))
        status(r) === OK
        val body = json(r)
        (body \ "scan" \ "jobId").as[Long] === queued.id
        (body \ "scan" \ "status").as[String] === "done"
        (body \ "totals" \ "scanned").as[Int] === 1
        (body \ "totals" \ "moves").as[Int] === 1
        (body \ "totals" \ "activeToMove").as[Int] === 1
        val contests = (body \ "contests").as[JsArray].value.toSeq
        contests.map(x => (x \ "contestId").as[Long]) === Seq(c.getId)
        (contests.head \ "verdict").as[String] === "move"
        (contests.head \ "activeToMove").as[Seq[Long]] === Seq(r2021.getId)

        val one = get(app, s"/api/diagnostics/reuse/${c.getId}", as = Some(rootUser))
        status(one) === OK
        val clusters = (json(one) \ "clusters").as[JsArray].value.toSeq
        clusters.map(x => ((x \ "kind").as[String], (x \ "action").as[String])) === Seq(("unknown", "stay"), ("r1", "move"))
        (clusters(1) \ "target" \ "seriesName").as[String] === "Wiki Loves Earth in Nepal"
        (clusters(1) \ "target" \ "year").as[Int] === 2021
        (clusters(1) \ "jurors" \ "total").as[Int] === 1
        (clusters(1) \ "jurors" \ "notHostMembers").as[Int] === 1
        (clusters(1) \ "activeRounds").as[Seq[Long]] === Seq(r2021.getId)
        (clusters(1) \ "rounds" \ 0 \ "category").as[String] === "Category:Images from Wiki Loves Earth 2021 in Nepal"
        contentAsString(one) must not(contain("Secret Juror"))
        contentAsString(one) must not(contain("@example.org"))

        val token = ApiTokenService.create(rootUser.getId, "diag", Some(90), scope = ApiTokenScope.LocalRead)
        status(get(app, "/api/diagnostics/reuse", token = Some(token))) === OK
        status(get(app, s"/api/diagnostics/reuse/${c.getId}", token = Some(token))) === OK
        status(get(app, "/api/diagnostics/reuse/999999", as = Some(rootUser))) === NOT_FOUND
      }
    }

    "refuse signed out, a contest's admin, and the read-only token from elsewhere or through a proxy" in {
      testDbApp { app =>
        db.scalikejdbc.SharedTestDb.truncateAll()
        val c = HealthFixtures.contestWithFiles("Wiki Loves Earth", 2020, "Nepal", None, 1L to 5L)
        val paths = Seq("/api/diagnostics/reuse", s"/api/diagnostics/reuse/${c.getId}")
        paths.map(p => status(get(app, p))).distinct === Seq(UNAUTHORIZED)

        val admin = User.create(User("Admin", "admin@example.org", None, Set("admin"), contestId = c.id))
        val (statuses, logs) = LogCapture("security.authz")(paths.map(p => status(get(app, p, as = Some(admin)))))
        statuses.distinct === Seq(FORBIDDEN)
        logs("security.authz").count(_.contains("requiredRoles=\"root\"")) === 2
        // the admin's own read-only token: the account decides
        val adminToken = ApiTokenService.create(admin.getId, "diag", Some(90), scope = ApiTokenScope.LocalRead)
        paths.map(p => status(get(app, p, token = Some(adminToken)))).distinct === Seq(FORBIDDEN)

        val token = ApiTokenService.create(root().getId, "diag", Some(90), scope = ApiTokenScope.LocalRead)
        val remote = RemoteConnection("203.0.113.9", secure = false, clientCertificateChain = None)
        val (refused, lines) = LogCapture("security.authz") {
          paths.flatMap(p => Seq(
            status(get(app, p, token = Some(token), from = Some(remote))),
            status(get(app, p, token = Some(token), headers = Seq("X-Forwarded-For" -> "127.0.0.1")))))
        }
        refused.distinct === Seq(FORBIDDEN)
        lines("security.authz").count(_.contains("reason=token_not_local")) === 4
      }
    }
  }
}
