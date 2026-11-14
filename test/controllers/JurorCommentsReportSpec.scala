package controllers

import db.scalikejdbc._
import org.intracer.wmua._
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import play.api.Application
import play.api.libs.json.Json
import play.api.mvc.{Cookie, Result}
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** A juror's comments report of their own list (the round filters proposal v4 §2, PR-3;
  * decisions 5 and 6 = A): every comment of the contest on their files in the round, as their
  * large view shows them, without round names, scope, CSV, filter by person or gallery icons;
  * another juror's, all the jurors' and the CSV refused (a page or JSON 403, never a contest
  * switch for a script); "Recent comments" and "Show in the gallery" between the report and the
  * gallery with "Only with comments"; and the tabs counting what each opens (decision 2 = A),
  * with and without the comments filter, in the report and with the monument filter.
  */
class JurorCommentsReportSpec extends PlaySpecification with PlayTestDb {

  sequential

  private case class Fixture(
      contest: ContestJury,
      contestB: ContestJury,
      earlier: Round,
      round: Round,
      roundB: Round,
      juror1: User,
      juror2: User,
      jurorB: User,
      former: User,
      organizer: User
  )

  /** A monument with three of juror 1's files: 1 and 2 (selected) and 3 (unrated). */
  private val Monument = "12-345-100"

  /** Contest A's binary round (Round 2) with juror 1 on files 1-6 (1, 2 selected; 3, 4 unrated;
    * 5, 6 rejected), juror 2 on files 5-8 (5, 7 selected; 6, 8 unrated) and a former member of A
    * (no role in A now, a juror of B) still listed with file 8; an earlier round of A
    * (Pre-screening) and contest B's round with juror B. Files 1-3 are of [[Monument]].
    * Comments, in the order written:
    *   1. juror 1 on 1 (this round)       2. organizer on 3 (the earlier round)
    *   3. juror 2 on 7 (this round)       4. organizer on 5 (the earlier round)
    *   5. organizer on 1 (this round)     6. juror 1 on 2 (contest B: never here)
    * Juror 1's files with comments in A: 1, 3, 5; by the latest comment 1, 5, 3.
    */
  private def fixture(specialNomination: Option[String] = None): Fixture = {
    val a = contestDao.create(None, "WLM", 2026, "Ukraine")
    val b = contestDao.create(None, "WLE", 2026, "Ukraine")
    val earlier = roundDao.create(Round(None, 1, Some("Pre-screening"), contestId = a.getId, rates = Round.binaryRound,
      active = false))
    val round = roundDao.create(Round(None, 2, Some("Round 2"), contestId = a.getId, rates = Round.binaryRound,
      active = true, specialNomination = specialNomination))
    val roundB = roundDao.create(Round(None, 1, Some("B"), contestId = b.getId, rates = Round.binaryRound, active = true))
    def user(name: String, role: String, contest: ContestJury = a) =
      userDao.create(User(name, s"${name.toLowerCase.replace(" ", "")}@example.com", None, Set(role), contestId = contest.id))
    val juror1 = user("Juror 1", "jury")
    val juror2 = user("Juror 2", "jury")
    val organizer = user("Org", "organizer")
    val jurorB = user("Juror B", "jury", b)
    val former = user("Former", "jury", b)
    UserContestJdbc.createMembership(former.getId, a.getId, User.NO_ROLE)
    val images = (1L to 8L).map { id =>
      Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(if (id <= 3) Monument else s"12-345-$id"))
    }
    imageDao.batchInsert(images)
    round.addUsers(Seq(juror1, juror2, former).map(j => RoundUser(round.getId, j.getId, "jury", active = true)))
    roundB.addUsers(Seq(RoundUser(roundB.getId, jurorB.getId, "jury", active = true)))
    selectionDao.batchInsert(
      images.take(6).zip(Seq(1, 1, 0, 0, -1, -1)).map { case (i, r) => Vote(i, juror1, round, r) } ++
        images.drop(4).zip(Seq(1, 0, 1, 0)).map { case (i, r) => Vote(i, juror2, round, r) } ++
        Seq(Vote(images(7), former, round, 0), Vote(images(1), jurorB, roundB, 0)))
    def comment(by: User, r: Round, pageId: Long, text: String) =
      CommentJdbc.create(by.getId, by.fullname, r.getId, Some(r.contestId), pageId, text, "2026-11-12T14:05:00+02:00")
    comment(juror1, round, 1, "one by juror 1")
    comment(organizer, earlier, 3, "three, earlier")
    comment(juror2, round, 7, "seven, juror 2's file")
    comment(organizer, earlier, 5, "five, earlier")
    comment(organizer, round, 1, "one by org")
    comment(juror1, roundB, 2, "another contest")
    Fixture(a, b, earlier, round, roundB, juror1, juror2, jurorB, former, organizer)
  }

  private def get(app: Application, viewer: User, path: String, headers: (String, String)*): Future[Result] =
    route(app, FakeRequest(GET, path).withSession(Secured.UserName -> viewer.email).withHeaders(headers: _*)
      .withCSRFToken).get

  private def page(app: Application, viewer: User, path: String): Document = {
    val result = get(app, viewer, path)
    status(result) aka path must_== OK
    Jsoup.parse(contentAsString(result))
  }

  private def rows(doc: Document): Seq[Long] = doc.select("article.wlx-comments-row").asScala.map(_.id.toLong).toSeq

  private def galleryFiles(doc: Document): Seq[Long] =
    doc.select("#gallery img[data-pageId]").asScala.map(_.attr("data-pageId").toLong).toSeq

  private def report(f: Fixture, asUserId: Long, query: String = "") =
    s"/comments/round/${f.round.getId}/user/$asUserId/page/1$query"

  private val Tab = """^(All|Unrated|Selected|Rejected) \((\d+)\)$""".r

  /** The navbar's own tabs: name, number, link. */
  private def tabs(doc: Document): Seq[(String, Int, String)] =
    doc.select("nav a.nav-link").asScala.toSeq.flatMap(a => a.text.trim match {
      case Tab(name, n) => Some((name, n.toInt, a.attr("href")))
      case _            => None
    })

  /** Each tab's number, and that it is the number of files its link opens (`files` of the page). */
  private def tabsOpening(app: Application, viewer: User, doc: Document, files: Document => Seq[Long]): Map[String, Int] = {
    val all = tabs(doc)
    all must not(beEmpty)
    forall(all) { case (name, n, href) => files(page(app, viewer, href)).size aka s"$name: $href" must_== n }
    all.map { case (name, n, _) => name -> n }.toMap
  }

  "a juror's own comments report" should {

    "list exactly the comments their large view shows, on their own files, latest comment first" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.juror1, report(f, f.juror1.getId))
        rows(doc) === Seq(1L, 5L, 3L)
        def commentIds(d: Document, scope: String) = d.select(s"$scope .msg").asScala.map(_.id).toSeq
        // file by file, the comments of its large view, none for a file without
        forall(1L to 6L) { pageId =>
          val large = page(app, f.juror1, s"/large/round/${f.round.getId}/user/${f.juror1.getId}/pageid/$pageId")
          val inReport = Option(doc.getElementById(pageId.toString)).fold(Seq.empty[String])(row =>
            row.select(".msg").asScala.map(_.id).toSeq)
          inReport aka s"file $pageId" must_== commentIds(large, ".large-comments")
        }
        val html = doc.outerHtml
        html must contain("one by org")
        html must contain("three, earlier")
        // another juror's file and another contest's comment: never
        html must not(contain("seven, juror 2's file"))
        html must not(contain("another contest"))
        doc.select("#comments-report-note").text must contain("from all rounds of the contest")
      }
    }

    "show no round badges or names, no scope, CSV, filter by person or gallery icons" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.juror1, report(f, f.juror1.getId))
        doc.select(".wlx-comment-round, .wlx-comment-other-round").size === 0
        doc.outerHtml must not(contain("Pre-screening"))
        doc.select("#comments-report-csv, #comments-report-scope, .wlx-comment-filter-link, .wlx-comment-gallery-link")
          .size === 0
        doc.select("a[href*=/csv], a[href*=commenter=], a[href*=scope=round]").size === 0
        // the organisers' report of the same list has them
        val org = page(app, f.organizer, report(f, f.juror1.getId, "?scope=contest"))
        org.select(".wlx-comment-round").size must be_>(0)
        org.select("#comments-report-csv, #comments-report-scope").size === 2
        org.select("#comments-report-note").size === 0
      }
    }

    "ignore a scope of this round and a filter by person" in {
      testDbApp { app =>
        val f = fixture()
        val plain = page(app, f.juror1, report(f, f.juror1.getId))
        forall(Seq("?scope=round", s"?commenter=${f.organizer.getId}", s"?scope=round&commenter=${f.juror1.getId}")) { q =>
          val doc = page(app, f.juror1, report(f, f.juror1.getId, q))
          (rows(doc) aka q must_== Seq(1L, 5L, 3L)) and
            (doc.select("#comments-report-count").text must_== plain.select("#comments-report-count").text) and
            (doc.select("#comments-report-commenter").size must_== 0)
        }
      }
    }

    "move its large view, keys and votes among the juror's commented files, by path" in {
      testDbApp { app =>
        val f = fixture()
        val large = page(app, f.juror1,
          s"/large/round/${f.round.getId}/user/${f.juror1.getId}/pageid/1?module=comments&sort=latest-comment")
        val links = large.select("a[href*=/large/]").asScala.map(_.attr("href"))
        links must not(beEmpty)
        forall(links)(_ must contain("module=comments"))
        large.select("a[href*=/pageid/5?]").size must be_>(0)
        large.select("a[href*=/pageid/2?], a[href*=/pageid/4?], a[href*=/pageid/6?]").size === 0
        large.select(".wlx-comment-round").size === 0
        val list = RoundList(f.round, f.juror1.getId, order = ListOrder.LatestComment, view = GalleryView.Comments,
          commentScope = CommentScope.AllRounds)
        val vote = route(app, FakeRequest(POST, list.voteUrl(1, -1))
          .withSession(Secured.UserName -> f.juror1.email).withCSRFToken).get
        status(vote) === SEE_OTHER
        val location = redirectLocation(vote).get
        location must startWith("/large/")
        location must contain("/pageid/5")
        location must contain("module=comments")
      }
    }

    "link back to the gallery with only the files with comments, in the juror's own order" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.juror1, report(f, f.juror1.getId, "?filter=unrated"))
        doc.selectFirst("#comments-report-gallery").attr("href") ===
          s"/gallery/round/${f.round.getId}/user/${f.juror1.getId}/page/1?filter=unrated&comments=1&sort=file"
        val remembered = route(app, FakeRequest(GET, report(f, f.juror1.getId))
          .withSession(Secured.UserName -> f.juror1.email).withCookies(Cookie(RoundList.SortCookie, "latest-vote"))
          .withCSRFToken).get
        Jsoup.parse(contentAsString(remembered)).selectFirst("#comments-report-gallery").attr("href") ===
          s"/gallery/round/${f.round.getId}/user/${f.juror1.getId}/page/1?comments=1&sort=latest-vote"
        // the organisers' report: the gallery's default order (by rate), the scope not kept
        val org = page(app, f.organizer, report(f, 0))
        org.selectFirst("#comments-report-gallery").attr("href") ===
          s"/gallery/round/${f.round.getId}/user/0/page/1?comments=1&sort=rate"
      }
    }
  }

  "a juror's own comments report, as the round and the votes change" should {

    "drop a file whose vote was removed, its comments kept" in {
      testDbApp { app =>
        val f = fixture()
        scalikejdbc.DB.autoCommit { implicit s =>
          import scalikejdbc._
          sql"delete from vote where page_id = 5 and jury_id = ${f.juror1.getId} and round_id = ${f.round.getId}".update.apply()
        }
        rows(page(app, f.juror1, report(f, f.juror1.getId))) === Seq(1L, 3L)
        // the comment is still the contest's: the organisers' report of juror 2's files has it
        rows(page(app, f.organizer, report(f, f.juror2.getId, "?scope=contest"))) must contain(5L)
      }
    }

    "be refused in a round stopped or still being distributed, as a page or JSON" in {
      testDbApp { app =>
        val f = fixture()
        def refused(): org.specs2.execute.Result = {
          val html = get(app, f.juror1, report(f, f.juror1.getId))
          val json = get(app, f.juror1, report(f, f.juror1.getId), "X-Requested-With" -> "XMLHttpRequest")
          (status(html) must_== FORBIDDEN) and (contentType(html) must beSome("text/html")) and
            (contentAsString(html) must not(contain("one by org"))) and
            (status(json) must_== FORBIDDEN) and
            ((Json.parse(contentAsString(json)) \ "error").as[String] must_== "forbidden") and
            ((Json.parse(contentAsString(json)) \ "reference").as[String] must not(beEmpty))
        }
        Round.setActive(f.round.getId, false)
        val stopped = refused()
        Round.setActive(f.round.getId, true)
        scalikejdbc.DB.autoCommit { implicit s =>
          import scalikejdbc._
          sql"update round set distributing = 1 where id = ${f.round.getId}".update.apply()
        }
        stopped and refused()
      }
    }

    "go back after a comment to the juror's own report" in {
      testDbApp { app =>
        val f = fixture()
        val list = RoundList(f.round, f.juror1.getId, order = ListOrder.LatestComment, view = GalleryView.Comments,
          commentScope = CommentScope.AllRounds)
        val result = route(app, FakeRequest(POST, list.commentUrl(3))
          .withSession(Secured.UserName -> f.juror1.email)
          .withFormUrlEncodedBody("id" -> "0", "text" -> "a juror's note")
          .withCSRFToken).get
        status(result) === SEE_OTHER
        val location = redirectLocation(result).get
        location must startWith(s"/large/round/${f.round.getId}/user/${f.juror1.getId}/pageid/3")
        location must contain("module=comments")
        location must contain("scope=contest")
        // the new comment makes file 3 the latest
        rows(page(app, f.juror1, report(f, f.juror1.getId))) === Seq(3L, 1L, 5L)
      }
    }

    "give its region menu for the juror's own list only: another's refused, the CSV's their gallery's" in {
      testDbApp { app =>
        val f = fixture()
        def regions(asUserId: Long, module: String) =
          route(app, FakeRequest(GET, s"/regions/round/${f.round.getId}/user/$asUserId/region/all?module=$module")
            .withSession(Secured.UserName -> f.juror1.email).withHeaders("X-Requested-With" -> "XMLHttpRequest")).get
        val own = regions(f.juror1.getId, "comments")
        status(own) === OK
        (contentAsJson(own) \ "all" \ "url").as[String] must
          startWith(s"/comments/round/${f.round.getId}/user/${f.juror1.getId}/")
        forall(Seq(f.juror2.getId, 0L)) { other =>
          val refused = regions(other, "comments")
          (status(refused) must_== FORBIDDEN) and ((contentAsJson(refused) \ "error").as[String] must_== "forbidden")
        }
        val csv = regions(f.juror1.getId, "comments-csv")
        status(csv) === OK
        (contentAsJson(csv) \ "all" \ "url").as[String] must
          startWith(s"/gallery/round/${f.round.getId}/user/${f.juror1.getId}/")
        status(regions(f.juror2.getId, "comments-csv")) === FORBIDDEN
      }
    }
  }

  "the gallery with only the files with comments" should {

    "link Recent comments to the report of the same files, only while the toggle is on" in {
      testDbApp { app =>
        val f = fixture()
        val gallery = s"/gallery/round/${f.round.getId}/user/${f.juror1.getId}/page/1"
        page(app, f.juror1, gallery).select("#comments-report-open").size === 0
        val on = page(app, f.juror1, gallery + "?comments=1&filter=selected&monument=" + Monument)
        val href = on.selectFirst("#comments-report-open").attr("href")
        href === report(f, f.juror1.getId, s"?filter=selected&scope=contest&monument=$Monument&sort=latest-comment")
        // the same files: the gallery's are the report's
        galleryFiles(on).sorted === rows(page(app, f.juror1, href)).sorted
        // the organisers', all the jurors' votes
        val org = page(app, f.organizer, s"/gallery/round/${f.round.getId}/user/0/page/1?comments=1")
        val orgHref = org.selectFirst("#comments-report-open").attr("href")
        orgHref === report(f, 0, "?scope=contest&sort=latest-comment")
        galleryFiles(org).sorted === rows(page(app, f.organizer, orgHref)).sorted
      }
    }
  }

  "a juror's comments report access" should {

    "refuse another juror's report, all the jurors', the CSV, as a page or JSON, never switching the contest" in {
      testDbApp { app =>
        val f = fixture()
        val r = f.round.getId
        // juror 1 also a juror of B, the session in B: a refusal sent by script stays in B
        UserContestJdbc.createMembership(f.juror1.getId, f.contestB.getId, "jury")
        val paths = Seq(
          report(f, f.juror2.getId),
          report(f, 0),
          s"/comments/round/$r/user/${f.juror2.getId}/region/all/pageid/7",
          s"/comments/round/$r/user/0/region/all/pageid/1",
          s"/comments/round/$r/user/${f.juror1.getId}/region/all/csv",
          s"/comments/round/$r/user/${f.juror2.getId}/region/all/csv",
          s"/comments/round/$r/user/0/region/all/csv",
          s"/query?module=comments&round=$r&user=${f.juror2.getId}",
          s"/query?module=comments-csv&round=$r&user=${f.juror1.getId}"
        )
        forall(paths) { p =>
          val html = get(app, f.juror1, p)
          val json = route(app, FakeRequest(GET, p).withHeaders("X-Requested-With" -> "XMLHttpRequest")
            .withSession(Secured.UserName -> f.juror1.email, Secured.CurrentContestId -> f.contestB.getId.toString)
            .withCSRFToken).get
          val fetch = get(app, f.juror1, p, "Sec-Fetch-Mode" -> "cors")
          (status(html) aka p must_== FORBIDDEN) and
            (contentType(html) must beSome("text/html")) and
            (contentAsString(html) must not(contain("seven, juror 2's file"))) and
            (contentAsString(html) must not(contain("one by org"))) and
            (status(json) aka p must_== FORBIDDEN) and
            ((Json.parse(contentAsString(json)) \ "error").as[String] must_== "forbidden") and
            ((Json.parse(contentAsString(json)) \ "reference").as[String] must not(beEmpty)) and
            (session(json).get(Secured.CurrentContestId) must beNone) and
            (status(fetch) must_== FORBIDDEN) and
            (contentType(fetch) must beSome("application/json"))
        }
      }
    }

    "refuse a juror of another contest and a former member, and leave the large view's refusal as it was" in {
      testDbApp { app =>
        val f = fixture()
        forall(Seq(f.jurorB, f.former)) { viewer =>
          forall(Seq(report(f, viewer.getId), s"/comments/round/${f.round.getId}/user/${viewer.getId}/region/all/csv")) { p =>
            val result = get(app, viewer, p)
            (status(result) aka s"${viewer.fullname} $p" must_== FORBIDDEN) and
              (contentAsString(result) must not(contain("one by org")))
          }
        }
        // sent by script: JSON 403, for the round's viewers' refusal as for the report's
        forall(Seq(f.jurorB, f.former)) { viewer =>
          val json = get(app, viewer, report(f, viewer.getId), "X-Requested-With" -> "XMLHttpRequest")
          (status(json) must_== FORBIDDEN) and (contentType(json) must beSome("application/json")) and
            ((Json.parse(contentAsString(json)) \ "error").as[String] must_== "forbidden") and
            (session(json).get(Secured.CurrentContestId) must beNone)
        }
        val gallery = get(app, f.jurorB, s"/gallery/round/${f.round.getId}/user/${f.jurorB.getId}/page/1",
          "Sec-Fetch-Mode" -> "cors")
        status(gallery) === FORBIDDEN
        contentType(gallery) must beSome("application/json")
        // juror B's own report of B's round: theirs, without A's comments
        rows(page(app, f.jurorB, s"/comments/round/${f.roundB.getId}/user/${f.jurorB.getId}/page/1")) === Seq(2L)
      }
    }
  }

  "the tabs" should {

    "count what each opens: the gallery, with the toggle, in the report, with the monument filter and both" in {
      testDbApp { app =>
        val f = fixture()
        val j = f.juror1
        val gallery = s"/gallery/round/${f.round.getId}/user/${j.getId}/page/1"
        def counts(path: String, files: Document => Seq[Long] = galleryFiles) =
          tabsOpening(app, j, page(app, j, path), files)
        counts(gallery) === Map("All" -> 6, "Unrated" -> 2, "Selected" -> 2, "Rejected" -> 2)
        counts(gallery + "?comments=1") === Map("All" -> 3, "Unrated" -> 1, "Selected" -> 1, "Rejected" -> 1)
        counts(report(f, j.getId), rows) === Map("All" -> 3, "Unrated" -> 1, "Selected" -> 1, "Rejected" -> 1)
        counts(gallery + "?monument=" + Monument) === Map("All" -> 3, "Unrated" -> 1, "Selected" -> 2, "Rejected" -> 0)
        counts(gallery + "?comments=1&monument=" + Monument) ===
          Map("All" -> 2, "Unrated" -> 1, "Selected" -> 1, "Rejected" -> 0)
        counts(report(f, j.getId, "?monument=" + Monument), rows) ===
          Map("All" -> 2, "Unrated" -> 1, "Selected" -> 1, "Rejected" -> 0)
        // the toggle's number: the current tab's files with comments, its list's total when on
        page(app, j, gallery + "?filter=selected").selectFirst("a.wlx-comments-toggle").text must contain("(1)")
        page(app, j, gallery + "?comments=1").selectFirst("a.wlx-comments-toggle").text must contain("(3)")
      }
    }

    "stay, counting 0, on a tab without files with comments, with the order menu" in {
      testDbApp { app =>
        val f = fixture()
        // juror 2's files with comments: 5 and 7, both selected
        val j2 = page(app, f.juror2,
          s"/gallery/round/${f.round.getId}/user/${f.juror2.getId}/page/1?comments=1&filter=rejected")
        tabs(j2).map { case (name, n, _) => name -> n }.toMap ===
          Map("All" -> 2, "Unrated" -> 0, "Selected" -> 2, "Rejected" -> 0)
        galleryFiles(j2) === Nil
        j2.select("#comments-report-open").size === 1
        // none of the files in the list has a comment: the tabs and the order menu stay, at 0
        val none = page(app, f.juror2,
          s"/gallery/round/${f.round.getId}/user/${f.juror2.getId}/page/1?comments=1&monument=12-345-6")
        tabs(none).map { case (name, n, _) => name -> n }.toMap ===
          Map("All" -> 0, "Unrated" -> 0, "Selected" -> 0, "Rejected" -> 0)
        none.select("nav a[href*=sort=latest-vote]").size must be_>(0)
      }
    }

    "count within the toggle and the report in a round of sub-regions (Віа Регіа), from one scan" in {
      testDbApp { app =>
        val f = fixture(Some("Віа Регіа"))
        val own = RoundList(f.round, f.juror1.getId, comments = true)
        own.jurorCounts() must beNone
        own.tabCounts().map(_.rates) must beSome(Map(1 -> 1, 0 -> 1, -1 -> 1))
        RoundList(f.round, f.juror1.getId, view = GalleryView.Comments, commentScope = CommentScope.AllRounds)
          .tabCounts().map(_.rates) must beSome(Map(1 -> 1, 0 -> 1, -1 -> 1))
        RoundList(f.round, f.juror1.getId).tabCounts() must beNone // no filter: all the juror's files, as before
        val j = f.juror1
        tabsOpening(app, j, page(app, j, s"/gallery/round/${f.round.getId}/user/${j.getId}/page/1?comments=1"), galleryFiles) ===
          Map("All" -> 3, "Unrated" -> 1, "Selected" -> 1, "Rejected" -> 1)
        tabsOpening(app, j, page(app, j, report(f, j.getId)), rows) ===
          Map("All" -> 3, "Unrated" -> 1, "Selected" -> 1, "Rejected" -> 1)
      }
    }
  }
}
