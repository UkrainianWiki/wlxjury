package controllers

import db.scalikejdbc.{PlayTestDb, Round, User}
import org.intracer.wmua.ContestJury
import org.jsoup.Jsoup
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test._
import scalikejdbc._
import services.diagnostics.HealthFixtures
import services.jobs.JobStatus
import services.series.{ReuseReport, ReuseScanHandler}

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** Root's reuse dry-run report (contest series plan §4.3, CY-0): root only (signed out: the login
  * page, or no scan queued; a juror, an organizer and a contest admin: 403, no scan queued); root's
  * scan runs every contest (jobs run inline in the tests) and the list shows its totals and the
  * contests it reports; a contest's page shows its clusters; the series proposals leave out the
  * contests it reports; the scan writes nothing but its job.
  */
class ReuseReportControllerSpec extends PlaySpecification with PlayTestDb {

  sequential

  private def send(method: String, path: String, by: Option[User])(implicit app: Application): Future[Result] = {
    val base = FakeRequest(method, path).withHeaders(ACCEPT -> "text/html")
    val withSession = by.fold(base)(u => base.withSession(Seq(Secured.UserName -> u.email) ++
      u.contestId.map(Secured.CurrentContestId -> _.toString): _*))
    route(app, withSession.withFormUrlEncodedBody().withCSRFToken).get
  }

  private def rootUser = User.create(User("Root", s"root${System.nanoTime()}@example.org", None, Set(User.ROOT_ROLE), isRoot = true))
  private def member(role: String, contest: ContestJury) =
    User.create(User(role, s"$role${System.nanoTime()}@example.org", None, Set(role), Some("hash"), contest.id))

  private def scans: Long = DB.readOnly(implicit s =>
    sql"SELECT COUNT(*) FROM job WHERE kind = ${ReuseScanHandler.Kind}".map(_.long(1)).single.apply().getOrElse(0L))

  /** A contest reused for another year: its 2020 round and a 2021 round filtered by 2021's category. */
  private def reused(): (ContestJury, Round) = {
    val c = HealthFixtures.contestWithFiles("Wiki Loves Earth", 2020, "Nepal", None, (1L to 10L) ++ (101L to 110L))
    HealthFixtures.inCountries("Category:Images from Wiki Loves Earth 2020 in ", "Nepal" -> (1L to 10L))
    HealthFixtures.inCountries("Category:Images from Wiki Loves Earth 2021 in ", "Nepal" -> (101L to 110L))
    HealthFixtures.round(c, 1, "2020", status = Round.Status.Paused, files = 1L to 10L)
    val r2021 = HealthFixtures.round(c, 2, "2021", status = Round.Status.Paused,
      category = Some("Category:Images from Wiki Loves Earth 2021 in Nepal"), files = 101L to 110L)
    (c, r2021)
  }

  "the reuse report pages" should {

    "refuse everyone but root, queuing nothing; signed out, the login page" in {
      testDbApp { implicit app =>
        db.scalikejdbc.SharedTestDb.truncateAll()
        val (contest, _) = reused()
        val gets = Seq("/admin/contests/reuse", s"/admin/contests/${contest.getId}/reuse")
        val signedOut = gets.map(p => send(GET, p, None))
        signedOut.map(status(_)).distinct === Seq(SEE_OTHER)
        signedOut.flatMap(redirectLocation(_)).forall(_.startsWith("/auth")) must beTrue
        status(send(POST, "/admin/contests/reuse/scan", None)) must be_!=(OK)
        for (role <- Seq("jury", "organizer", "admin")) {
          val user = member(role, contest)
          val (results, logs) = LogCapture("security.authz") {
            (gets.map(p => status(send(GET, p, Some(user)))), status(send(POST, "/admin/contests/reuse/scan", Some(user))))
          }
          results._1.distinct === Seq(FORBIDDEN)
          results._2 === FORBIDDEN
          logs("security.authz").count(_.contains("requiredRoles=\"root\"")) === 3
        }
        scans === 0L
        val root = rootUser
        gets.map(p => status(send(GET, p, Some(root)))).distinct === Seq(OK)
      }
    }

    "scan every contest for root, show the totals and the contests reported, and a contest's clusters" in {
      testDbApp { implicit app =>
        db.scalikejdbc.SharedTestDb.truncateAll()
        val (contest, r2021) = reused()
        // contests of their own years: the eras' file ids (S1) lie near theirs
        val clean = HealthFixtures.contestWithFiles("Wiki Loves Monuments", 2025, "Ukraine", None, 5001L to 5005L)
        HealthFixtures.round(clean, 1, "First", files = 5001L to 5005L)
        val clean2021 = HealthFixtures.contestWithFiles("Wiki Loves Earth", 2021, "Ukraine", None, 111L to 115L)
        HealthFixtures.round(clean2021, 1, "First", files = 111L to 115L)
        // no sign the inline check reads, but a cluster of 2021's files in a 2025 contest (S1): root's choice
        val eraOnly = HealthFixtures.contestWithFiles("Wiki Loves Earth", 2025, "Poland", None, (5006L to 5010L) ++ (116L to 120L))
        HealthFixtures.round(eraOnly, 1, "This year", files = 5006L to 5010L)
        HealthFixtures.round(eraOnly, 2, "Old files", files = 116L to 120L)
        val root = rootUser
        val before = DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM round_image".map(_.long(1)).single.apply())

        val empty = Jsoup.parse(contentAsString(send(GET, "/admin/contests/reuse", Some(root))))
        empty.select("#reuse-scan-status").text() must contain("No scan yet")
        empty.select("#reuse-totals").isEmpty must beTrue

        val queued = send(POST, "/admin/contests/reuse/scan", Some(root))
        status(queued) === SEE_OTHER
        redirectLocation(queued) must beSome("/admin/contests/reuse")
        val job = ReuseScanHandler.latest().get
        job.status === JobStatus.Done
        job.contestId must beNone
        val st = ReuseScanHandler.stateOf(job)
        (st.scanned, st.clean) === ((4, 2))
        st.contests.map(c => (c.contestId, c.verdict, c.moves)) ===
          Seq((contest.getId, ReuseReport.Verdict.Move, 1), (eraOnly.getId, ReuseReport.Verdict.Choose, 0))
        st.eras.map(_.year) === Seq(2020, 2021, 2025)

        val page = Jsoup.parse(contentAsString(send(GET, "/admin/contests/reuse", Some(root))))
        page.select("[data-total=scanned]").text() === "4"
        page.select("[data-total=moves]").text() === "1"
        page.select("tr.reuse-contest").asScala.map(_.attr("data-contest")) === Seq(eraOnly.getId.toString, contest.getId.toString)
        page.select(s"tr.reuse-contest a[href=/admin/contests/${contest.getId}/reuse]").size === 1

        val one = Jsoup.parse(contentAsString(send(GET, s"/admin/contests/${contest.getId}/reuse", Some(root))))
        one.select("section.reuse-cluster").asScala.map(s => (s.attr("data-kind"), s.attr("data-action"))) ===
          Seq(("own", "stay"), ("r1", "move"))
        one.select(s"#reuse-cluster-2 tr[data-round=${r2021.getId}]").size === 1
        one.select("#reuse-cluster-2 .reuse-target").text() must contain("Wiki Loves Earth in Nepal")
        one.select("#reuse-no-eras").isEmpty must beTrue

        // the series proposals leave out what the scan reports, even with no sign of the inline check
        val proposals = Jsoup.parse(contentAsString(send(GET, "/admin/series/assign", Some(root))))
        proposals.select(s"tr.series-left-row[data-contest=${eraOnly.getId}]").attr("data-reason") === "reuse"
        proposals.select(s"tr.series-left-row[data-contest=${eraOnly.getId}] .series-left-signs").text() === "the reuse report"

        // a second scan while none runs is queued again; nothing but the job is written
        status(send(POST, "/admin/contests/reuse/scan", Some(root))) === SEE_OTHER
        scans === 2L
        DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM round_image".map(_.long(1)).single.apply()) === before
        DB.readOnly(implicit s => sql"SELECT contest_id FROM round WHERE id = ${r2021.getId}".map(_.long(1)).single.apply()) ===
          contest.id
        status(send(GET, "/admin/contests/999999/reuse", Some(root))) === NOT_FOUND

        // a scan queued or running after a finished one: its progress shows, the finished scan's
        // results stay what the page, the eras and the proposals read
        val finished = ReuseScanHandler.latestDone().get
        val pending = DB.localTx(implicit s => services.jobs.JobJdbc.insert(ReuseScanHandler.newJob(root)
          .copy(state = play.api.libs.json.Json.toJsObject(services.series.ReuseScanState(phase = "scan", total = 4))), None))
        ReuseScanHandler.latest().map(_.id) must beSome(pending)
        ReuseScanHandler.latestEras().years.map(_.year) === Seq(2020, 2021, 2025)
        val during = Jsoup.parse(contentAsString(send(GET, "/admin/contests/reuse", Some(root))))
        during.select("#reuse-scan-status").text() must contain(s"Scan $pending")
        during.select("#reuse-scan-results").text() must contain(s"scan ${finished.id}")
        during.select("tr.reuse-contest").size === 2
        val stillOut = Jsoup.parse(contentAsString(send(GET, "/admin/series/assign", Some(root))))
        stillOut.select(s"tr.series-left-row[data-contest=${eraOnly.getId}]").attr("data-reason") === "reuse"
      }
    }

    "refuse the scan without the CSRF token" in {
      controllers.CsrfTestApp.withCsrfApp { app =>
        implicit val a: Application = app
        val root = rootUser
        val sessionToken = controllers.CsrfTestApp.token(app)
        val refused = route(app, FakeRequest(POST, "/admin/contests/reuse/scan")
          .withHeaders(COOKIE -> "PLAY_SESSION=x", ACCEPT -> "text/html")
          .withSession(Secured.UserName -> root.email, "csrfToken" -> sessionToken)).get
        status(refused) === FORBIDDEN
        scans === 0L
      }
    }

    "link to the report from the series pages" in {
      testDbApp { implicit app =>
        db.scalikejdbc.SharedTestDb.truncateAll()
        val (contest, _) = reused()
        val root = rootUser
        Jsoup.parse(contentAsString(send(GET, "/admin/series", Some(root)))).select("#series-reuse-link").attr("href") ===
          "/admin/contests/reuse"
        Jsoup.parse(contentAsString(send(GET, s"/admin/contests/${contest.getId}/series", Some(root))))
          .select("#contest-reuse-report-link").attr("href") === s"/admin/contests/${contest.getId}/reuse"
      }
    }
  }
}
