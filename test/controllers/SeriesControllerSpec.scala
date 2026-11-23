package controllers

import db.scalikejdbc.{ContestJuryJdbc, PlayTestDb, SeriesJdbc, User, UserContestJdbc}
import org.jsoup.Jsoup
import org.intracer.wmua.ContestJury
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test._
import services.series.ContestSeries

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** Root's contest series pages (contest series plan §5.3, §6; CY-1): root only (signed out: the
  * login page or nothing written; a juror, an organizer and a contest admin: 403, nothing written);
  * root creates a series, confirms proposals in bulk and undoes the batch, assigns, retires and
  * un-retires a contest; the contests page's Series and Status menus and its Manage series link
  * (root's only, the series pages' way in); the contest menu grouped by
  * series (§5.1); a retired contest's member can't switch to it.
  */
class SeriesControllerSpec extends PlaySpecification with PlayTestDb {

  sequential

  private def send(method: String, path: String, by: Option[User], form: (String, String)*)(implicit
      app: Application): Future[Result] = {
    val base = FakeRequest(method, path).withHeaders(ACCEPT -> "text/html")
    val withSession = by.fold(base)(u => base.withSession(Seq(Secured.UserName -> u.email) ++
      u.contestId.map(Secured.CurrentContestId -> _.toString): _*))
    route(app, withSession.withFormUrlEncodedBody(form: _*).withCSRFToken).get
  }

  private def rootUser = User.create(User("Root", s"root${System.nanoTime()}@example.org", None, Set(User.ROOT_ROLE), isRoot = true))
  private def member(role: String, contest: ContestJury) =
    User.create(User(role, s"$role${System.nanoTime()}@example.org", None, Set(role), Some("hash"), contest.id))

  "the series pages" should {

    "refuse everyone but root, writing nothing; signed out, the login page" in {
      testDbApp { implicit app =>
        db.scalikejdbc.SharedTestDb.truncateAll()
        val contest = ContestJuryJdbc.create(None, "Wiki Loves Monuments", 2025, "Nepal")
        val root = rootUser
        val seriesId = ContestSeries.createSeriesTx("Wiki Loves Monuments in Nepal", Some("wlm"), Some("Nepal"), None, root.getId).toOption.get
        val gets = Seq("/admin/series", "/admin/series/assign", s"/admin/series/$seriesId", s"/admin/contests/${contest.getId}/series")
        val posts = Seq(
          "/admin/series" -> Seq("name" -> "Sneaky"),
          "/admin/series/assign" -> Seq("p" -> "wlm:np", "c.wlm:np" -> contest.getId.toString),
          "/admin/series/batches/1/undo" -> Nil,
          s"/admin/series/$seriesId/rename" -> Seq("name" -> "Sneaky"),
          s"/admin/series/$seriesId/retire" -> Nil,
          s"/admin/series/$seriesId/unretire" -> Nil,
          s"/admin/contests/${contest.getId}/series" -> Seq("seriesId" -> seriesId.toString),
          s"/admin/contests/${contest.getId}/retire" -> Nil,
          s"/admin/contests/${contest.getId}/unretire" -> Nil)

        val signedOut = gets.map(p => send(GET, p, None))
        signedOut.map(status(_)).distinct === Seq(SEE_OTHER)
        signedOut.flatMap(redirectLocation(_)).forall(_.startsWith("/auth")) must beTrue
        posts.foreach { case (p, f) => status(send(POST, p, None, f: _*)) must be_!=(OK) }

        for (role <- Seq("jury", "organizer", "admin")) {
          val user = member(role, contest)
          gets.map(p => status(send(GET, p, Some(user)))).distinct === Seq(FORBIDDEN)
          posts.map { case (p, f) => status(send(POST, p, Some(user), f: _*)) }.distinct === Seq(FORBIDDEN)
        }
        // nothing written
        SeriesJdbc.findAll().map(_.name) === Seq("Wiki Loves Monuments in Nepal")
        SeriesJdbc.findById(seriesId).exists(_.retired) must beFalse
        ContestJuryJdbc.findById(contest.getId).map(c => (c.seriesId, c.retired)) must beSome((None, false))

        gets.map(p => status(send(GET, p, Some(root)))).distinct === Seq(OK)
      }
    }

    "be reached from the Manage series link next to the contests page's Series menu, root's only" in {
      testDbApp { implicit app =>
        db.scalikejdbc.SharedTestDb.truncateAll()
        val contest = ContestJuryJdbc.create(None, "Wiki Loves Monuments", 2025, "Nepal")
        val root = rootUser
        val page = Jsoup.parse(contentAsString(send(GET, "/contests", Some(root))))
        val link = page.select("#contests-manage-series a")
        // the maintainer's decision of 2026-11-20: next to the Series menu, not in root's pages
        (link.attr("href") === "/admin/series") and
          (link.text() === "Manage series") and
          (page.selectFirst("#contests-series-menu").nextElementSibling().id() === "contests-manage-series") and
          (page.select("#menu-series, #page-series").size === 0) and
          // the only way there: CY-1's link under the title is gone (the maintainer, 2026-11-20)
          (page.select("#contests-series-link").size === 0) and
          (page.select("a[href=/admin/series]").size === 1) and
          forall(Seq("organizer", "admin")) { role =>
            val res = send(GET, "/contests", Some(member(role, contest)))
            (status(res) === FORBIDDEN) and
              (Jsoup.parse(contentAsString(res)).select("#contests-manage-series, a[href=/admin/series]").size === 0)
          }
      }
    }

    "let root create a series, confirm proposals as a batch, undo it, and retire a contest" in {
      testDbApp { implicit app =>
        db.scalikejdbc.SharedTestDb.truncateAll()
        val root = rootUser
        val y24 = ContestJuryJdbc.create(None, "Wiki Loves Monuments", 2024, "Ukraine")
        val y25 = ContestJuryJdbc.create(None, "Wiki Loves Monuments", 2025, "Ukraine")
        val created = send(POST, "/admin/series", Some(root), "name" -> "Wiki Loves Earth in Ukraine", "contestType" -> "wle",
          "country" -> "Ukraine")
        status(created) === SEE_OTHER
        SeriesJdbc.findAll().map(_.name) === Seq("Wiki Loves Earth in Ukraine")

        val page = Jsoup.parse(contentAsString(send(GET, "/admin/series/assign", Some(root))))
        page.select("fieldset.series-proposal").asScala.map(_.attr("data-proposal")).toSeq === Seq("wlm:ua")
        page.select("input[name=existing.wlm:ua]").attr("value") === "none"
        page.select("input[name=c.wlm:ua]").asScala.map(_.attr("value")).toSet === Set(y24.getId.toString, y25.getId.toString)

        // y24 left out by unticking it
        // what root didn't see is refused, nothing written: a contest not in the proposal, another series offered
        val other = ContestJuryJdbc.create(None, "Wiki Loves Earth", 2025, "Nepal")
        for (form <- Seq(
            Seq("p" -> "wlm:ua", "existing.wlm:ua" -> "none", "c.wlm:ua" -> other.getId.toString),
            Seq("p" -> "wlm:ua", "existing.wlm:ua" -> "7", "c.wlm:ua" -> y25.getId.toString),
            Seq("p" -> "wle:np", "existing.wle:np" -> "none", "c.wle:np" -> other.getId.toString, "c.wle:np" -> y25.getId.toString))) {
          val refused = send(POST, "/admin/series/assign", Some(root), form: _*)
          flash(refused).get("series-error") must beSome("series.assign.changed")
        }
        ContestJuryJdbc.findAll().flatMap(_.seriesId) must beEmpty
        SeriesJdbc.findAll().map(_.name) === Seq("Wiki Loves Earth in Ukraine")
        ContestJuryJdbc.deleteById(other.getId)

        val confirmed = send(POST, "/admin/series/assign", Some(root), "p" -> "wlm:ua", "name.wlm:ua" -> "WLM in Ukraine",
          "existing.wlm:ua" -> "none", "c.wlm:ua" -> y25.getId.toString)
        status(confirmed) === SEE_OTHER
        flash(confirmed).get("series-done") must beSome("series.assign.done")
        val series = SeriesJdbc.findAll().find(_.name == "WLM in Ukraine").get
        ContestJuryJdbc.findById(y25.getId).flatMap(_.seriesId) must beSome(series.id)
        ContestJuryJdbc.findById(y24.getId).flatMap(_.seriesId) must beNone

        val batch = flash(confirmed).get("series-batch").get.toLong
        val list = contentAsString(send(GET, "/admin/series", Some(root)))
        list must contain(s"/admin/series/batches/$batch/undo")
        status(send(POST, s"/admin/series/batches/$batch/undo", Some(root))) === SEE_OTHER
        ContestJuryJdbc.findById(y25.getId).flatMap(_.seriesId) must beNone

        // a contest's series page: assign, retire, un-retire
        status(send(POST, s"/admin/contests/${y24.getId}/series", Some(root), "seriesId" -> series.id.toString)) === SEE_OTHER
        ContestJuryJdbc.findById(y24.getId).flatMap(_.seriesId) must beSome(series.id)
        val retired = send(POST, s"/admin/contests/${y24.getId}/retire", Some(root), "comment" -> "a test")
        flash(retired).get("series-done") must beSome("contest.retired.done")
        ContestJuryJdbc.findById(y24.getId).exists(_.retired) must beTrue
        val contestPage = contentAsString(send(GET, s"/admin/contests/${y24.getId}/series", Some(root)))
        contestPage must contain("a test")
        "series\\.[a-z.]+".r.findAllIn(contestPage.replaceAll("data-[a-z]+=\"[^\"]*\"", "")).toSeq must beEmpty

        // the contests page: retired ones only behind the Status menu, the series column and menu
        val contests = Jsoup.parse(contentAsString(send(GET, "/contests?year=all", Some(root))))
        contests.select("tr.contest-row").asScala.map(_.attr("data-contest")).toSeq === Seq(y25.getId.toString)
        val retiredOnly = Jsoup.parse(contentAsString(send(GET, "/contests?year=all&status=retired", Some(root))))
        retiredOnly.select("tr.contest-row").asScala.map(_.attr("data-contest")).toSeq === Seq(y24.getId.toString)
        retiredOnly.select("tr.contest-row td.contest-series a").text() === "WLM in Ukraine"
        val bySeries = Jsoup.parse(contentAsString(send(GET, s"/contests?year=all&status=all&series=${series.id}", Some(root))))
        bySeries.select("tr.contest-row").asScala.map(_.attr("data-contest")).toSeq === Seq(y24.getId.toString)
        bySeries.select("#contests-series-menu").size === 1
      }
    }

    "confirm a proposal beside a same-year contest flagged by its round files only" in {
      testDbApp { implicit app =>
        db.scalikejdbc.SharedTestDb.truncateAll()
        val root = rootUser
        val ticked = ContestJuryJdbc.create(None, "Wiki Loves Monuments", 2025, "Ukraine")
        // the same type, country and year, flagged only by its round's files (Armenia's)
        val host = services.diagnostics.HealthFixtures.contestWithFiles("Wiki Loves Monuments", 2025, "Ukraine",
          None, 1L to 10L)
        services.diagnostics.HealthFixtures.inCountries("Category:Images from Wiki Loves Monuments 2025 in ", "Armenia" -> (1L to 10L))
        services.diagnostics.HealthFixtures.round(host, 1, "Armenia", files = 1L to 10L,
          status = db.scalikejdbc.Round.Status.Paused)
        val page = Jsoup.parse(contentAsString(send(GET, "/admin/series/assign", Some(root))))
        page.select("input[name=c.wlm:ua]").asScala.map(_.attr("value")).toSeq === Seq(ticked.getId.toString)
        page.select(s"tr.series-left-row[data-contest=${host.getId}]").attr("data-reason") === "reuse"
        val confirmed = send(POST, "/admin/series/assign", Some(root), "p" -> "wlm:ua", "name.wlm:ua" -> "WLM in Ukraine",
          "existing.wlm:ua" -> "none", "c.wlm:ua" -> ticked.getId.toString)
        flash(confirmed).get("series-error") must beNone
        flash(confirmed).get("series-done") must beSome("series.assign.done")
        ContestJuryJdbc.findById(ticked.getId).flatMap(_.seriesId) must beSome
        ContestJuryJdbc.findById(host.getId).flatMap(_.seriesId) must beNone
      }
    }

    "group the contest menu by series, and keep a retired contest out of it and of switching" in {
      testDbApp { implicit app =>
        db.scalikejdbc.SharedTestDb.truncateAll()
        val root = rootUser
        val year = java.time.Year.now().getValue
        val now = ContestJuryJdbc.create(None, "Wiki Loves Monuments", year, "Nepal")
        val last = ContestJuryJdbc.create(None, "Wiki Loves Monuments", year - 1, "Nepal")
        val old = ContestJuryJdbc.create(None, "Wiki Loves Monuments", year - 4, "Nepal")
        val loose = ContestJuryJdbc.create(None, "Wiki Loves Earth", year, "Nepal")
        val gone = ContestJuryJdbc.create(None, "Wiki Loves Earth", year - 1, "Nepal")
        val s = ContestSeries.createSeriesTx("Wiki Loves Monuments in Nepal", Some("wlm"), Some("Nepal"), None, root.getId).toOption.get
        Seq(now, last, old).foreach(c => ContestSeries.setSeries(c.getId, Some(s), root.getId, None))
        val admin = member("admin", now)
        Seq(last, old, loose, gone).foreach(c => UserContestJdbc.createMembership(admin.getId, c.getId, "admin"))
        ContestSeries.retireContest(gone.getId, root.getId, None)

        val profile = send(GET, s"/users/edit/${admin.getId}", Some(admin))
        status(profile) === OK
        val html = Jsoup.parse(contentAsString(profile))
        val items = html.select("ul.dropdown-menu").asScala.find(_.select("form[action^=/switch-contest/]").size > 0).get
          .select("h6.contest-menu-heading, form[action^=/switch-contest/]").asScala.map { e =>
            if (e.tagName == "h6") "# " + e.text() else e.attr("action").stripPrefix("/switch-contest/")
          }.toSeq
        items === Seq("# Series: Wiki Loves Monuments in Nepal", now.getId.toString, last.getId.toString,
          "# Earlier years", old.getId.toString, "# Other contests", loose.getId.toString)
        // switching to the retired contest is refused
        status(send(POST, s"/switch-contest/${gone.getId}", Some(admin))) === FORBIDDEN
      }
    }
  }

  "the menu's groups" should {
    "be one group without headings when no entry is in a live series" in {
      val a = db.scalikejdbc.UserContest(1, 1, "admin") -> ContestJury(Some(1), "Wiki Loves Earth", 2025, "Nepal", None)
      val b = db.scalikejdbc.UserContest(1, 2, "admin") -> ContestJury(Some(2), "Wiki Loves Earth", 2024, "Nepal", None, seriesId = Some(5))
      val retired = db.scalikejdbc.Series(5, "Old", None, None, None, None, java.time.Instant.EPOCH, Some(java.time.Instant.EPOCH), None)
      JurorContests.groups(Seq(a, b), _ => Seq(retired), 2025) === Seq(JurorContests.MenuGroup(None, Seq(a, b)))
      JurorContests.groups(Nil, _ => Nil, 2025) must beEmpty
    }
  }
}
