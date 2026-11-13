package controllers

import com.github.tototoshi.csv.CSVReader
import db.scalikejdbc._
import org.intracer.wmua._
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import play.api.Application
import play.api.libs.json.Json
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import java.io.StringReader
import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** The organisers' comments report ([[GalleryView.Comments]]) and its CSV
  * ([[GalleryView.CommentsCsv]]): for those who may view the round's organiser info only; the
  * list's files with a comment in its scope ([[CommentScope]]: this round's by default, or all the
  * contest's), each with those comments; the list's juror, filter, region and pager kept; ordered
  * by the list's own order or by the latest comment in the scope.
  */
class CommentsReportSpec extends PlaySpecification with PlayTestDb {

  sequential

  private case class Fixture(
      contest: ContestJury,
      earlier: Round,
      round: Round,
      roundB: Round,
      juror1: User,
      juror2: User,
      organizer: User,
      admin: User
  )

  /** Text a comment's author and body may carry: HTML, a formula, quotes and a line break. */
  private val EvilName = "@evil \"<b>name</b>\""
  private val EvilBody = "=HYPERLINK(\"http://x\",\"a\")\n<img src=x onerror=alert(1)> \"quoted\", too"

  /** Contest A's binary round (Round 2) with juror 1 on files 1-6 (1, 2 selected; 3, 4 unrated;
    * 5, 6 rejected) and juror 2 on files 5-8 (5, 7 selected; 6, 8 unrated), an earlier round of A
    * (Pre-screening), and contest B's round. Files 4 and 7 are in region 80, the others in 12.
    * Comments, in the order written (so by id):
    *   1. juror 1 on 5 (this round)          2. organizer on 3 (the earlier round)
    *   3. juror 1 on 1 (this round)          4. juror 2 on 7 (this round)
    *   5. organizer on 1 (this round)        6. juror 1 on 2 (contest B: never here)
    *   7. organizer on 5 (the earlier round) 8. [[EvilName]] on 4 (this round): [[EvilBody]]
    * This round's: files 1 (2), 4, 5, 7 (1 each); by the latest first 4, 1, 7, 5. All the
    * contest's: 1, 3, 4, 5 (2), 7; by the latest first 4, 5, 1, 7, 3. In file order (by monument
    * id) region 12's files come before region 80's: 1, 2, 3, 5, 6, 8, 4, 7.
    */
  private def fixture(): Fixture = {
    val a = contestDao.create(None, "WLM", 2026, "Ukraine")
    val b = contestDao.create(None, "WLE", 2026, "Ukraine")
    val earlier = roundDao.create(Round(None, 1, Some("Pre-screening"), contestId = a.getId, rates = Round.binaryRound,
      active = false))
    val round = roundDao.create(Round(None, 2, Some("Round 2"), contestId = a.getId, rates = Round.binaryRound,
      active = true))
    val roundB = roundDao.create(Round(None, 1, Some("B"), contestId = b.getId, rates = Round.binaryRound, active = true))
    def user(name: String, role: String) =
      userDao.create(User(name, s"${name.toLowerCase.replace(" ", "")}@example.com", None, Set(role), contestId = a.id))
    val juror1 = user("Juror 1", "jury")
    val juror2 = user("Juror 2", "jury")
    val organizer = user("Org", "organizer")
    val admin = user("Adm", "admin")
    val images = (1L to 8L).map { id =>
      val region = if (id == 4 || id == 7) "80" else "12"
      Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(s"$region-345-$id"))
    }
    imageDao.batchInsert(images)
    round.addUsers(Seq(juror1, juror2).map(j => RoundUser(round.getId, j.getId, "jury", active = true)))
    selectionDao.batchInsert(
      images.take(6).zip(Seq(1, 1, 0, 0, -1, -1)).map { case (i, r) => Vote(i, juror1, round, r) } ++
        images.drop(4).zip(Seq(1, 0, 1, 0)).map { case (i, r) => Vote(i, juror2, round, r) })
    def comment(by: String, r: Round, pageId: Long, text: String, at: String = "2026-11-12T14:05:00+02:00") =
      CommentJdbc.create(1, by, r.getId, Some(r.contestId), pageId, text, at)
    comment("Juror 1", round, 5, "five, this round")
    comment("Org", earlier, 3, "three, earlier", "2016-10-05T12:34:56+03:00[Europe/Kiev]")
    comment("Juror 1", round, 1, "one, first")
    comment("Juror 2", round, 7, "seven")
    comment("Org", round, 1, "one, second")
    comment("Juror 1", roundB, 2, "another contest")
    comment("Org", earlier, 5, "five, earlier")
    comment(EvilName, round, 4, EvilBody, "not a time")
    Fixture(a, earlier, round, roundB, juror1, juror2, organizer, admin)
  }

  private def report(f: Fixture, asUserId: Long = 0, scope: CommentScope = CommentScope.ThisRound,
                     order: ListOrder = ListOrder.FileOrder, filter: VoteFilter = VoteFilter.All,
                     region: Option[String] = None) =
    RoundList(f.round, asUserId, region = region, filter = filter, order = order, view = GalleryView.Comments,
      commentScope = scope)

  private def pageIds(l: RoundList): Seq[Long] = l.files(Pager(1, 0)).map(_.pageId)

  private def get(app: Application, viewer: User, path: String, headers: (String, String)*): Future[Result] =
    route(app, FakeRequest(GET, path).withSession(Secured.UserName -> viewer.email).withHeaders(headers: _*)
      .withCSRFToken).get

  private def page(app: Application, viewer: User, path: String): Document = {
    val result = get(app, viewer, path)
    status(result) aka path must_== OK
    Jsoup.parse(contentAsString(result))
  }

  private def rows(doc: Document): Seq[Long] = doc.select("article.wlx-comments-row").asScala.map(_.id.toLong).toSeq

  private def csvOf(app: Application, viewer: User, path: String): List[List[String]] = {
    val result = get(app, viewer, path)
    status(result) aka path must_== OK
    contentType(result) must beSome("text/csv")
    val text = contentAsString(result)(defaultAwaitTimeout, app.materializer)
    text must startWith("﻿")
    CSVReader.open(new StringReader(text.stripPrefix("﻿"))).all()
  }

  private def path(f: Fixture, asUserId: Long, query: String = "") =
    s"/comments/round/${f.round.getId}/user/$asUserId/page/1$query"

  "the comments report's files" should {

    "be the list's files with a comment in this round by default, or in any round of the contest" in {
      testDbApp { _ =>
        val f = fixture()
        report(f).commentScope === CommentScope.ThisRound
        pageIds(report(f)) === Seq(1L, 5L, 4L, 7L)
        report(f).count() === 4
        pageIds(report(f, scope = CommentScope.AllRounds)) === Seq(1L, 3L, 5L, 4L, 7L)
        // their comments in the scope, each file's in the order written
        val files = report(f).files(Pager(1, 0))
        report(f).commentsOf(files).view.mapValues(_.map(_.body)).toMap ===
          Map(1L -> Seq("one, first", "one, second"), 4L -> Seq(EvilBody), 5L -> Seq("five, this round"),
            7L -> Seq("seven"))
        report(f, scope = CommentScope.AllRounds).commentsOf(files).apply(5L).map(_.body) ===
          Seq("five, this round", "five, earlier")
        // the totals of all the pages
        report(f).commentCount() === 5
        report(f, scope = CommentScope.AllRounds).commentCount() === 7
      }
    }

    "combine with a juror, a tab and a region" in {
      testDbApp { _ =>
        val f = fixture()
        pageIds(report(f, f.juror1.getId)) === Seq(1L, 5L, 4L)
        pageIds(report(f, f.juror2.getId)) === Seq(5L, 7L)
        pageIds(report(f, f.juror2.getId, CommentScope.AllRounds)) === Seq(5L, 7L)
        // selected by any juror: 1, 2, 5, 7; with this round's comments 1, 5, 7
        pageIds(report(f, filter = VoteFilter.Rate(1))) === Seq(1L, 5L, 7L)
        pageIds(report(f, f.juror1.getId, filter = VoteFilter.Unrated)) === Seq(4L)
        pageIds(report(f, f.juror1.getId, CommentScope.AllRounds, filter = VoteFilter.Unrated)) === Seq(3L, 4L)
        pageIds(report(f, region = Some("80"))) === Seq(4L, 7L)
        report(f, region = Some("80")).commentCount() === 2
        // the juror's counts (pager, tabs, region nav) in the scope
        report(f, f.juror1.getId).jurorCounts().map(_.total(VoteFilter.All, None)) === Some(3)
        report(f, f.juror1.getId, CommentScope.AllRounds).jurorCounts().map(_.total(VoteFilter.All, None)) === Some(4)
        report(f).regionCounts() === Map("12" -> 2, "80" -> 2)
      }
    }

    "be ordered by the latest comment in the scope, then in file order" in {
      testDbApp { _ =>
        val f = fixture()
        pageIds(report(f, order = ListOrder.LatestComment)) === Seq(4L, 1L, 7L, 5L)
        // file 5's latest comment is the earlier round's: later than file 1's in all the rounds
        pageIds(report(f, scope = CommentScope.AllRounds, order = ListOrder.LatestComment)) === Seq(4L, 5L, 1L, 7L, 3L)
        pageIds(report(f, f.juror1.getId, order = ListOrder.LatestComment)) === Seq(4L, 1L, 5L)
        // pages of 2 at an offset: the same order
        val l = report(f, scope = CommentScope.AllRounds, order = ListOrder.LatestComment)
        (l.files(Pager(1, 5, 2)) ++ l.files(Pager(2, 5, 2)) ++ l.files(Pager(3, 5, 2))).map(_.pageId) ===
          Seq(4L, 5L, 1L, 7L, 3L)
      }
    }

    "move a large view among them by the latest comment, by keyset for a juror and by rank for all" in {
      testDbApp { _ =>
        val f = fixture()
        // juror 1's in all the rounds: 4 (8), 5 (7), 1 (5), 3 (2); the files before 1 read by keyset
        val juror = report(f, f.juror1.getId, CommentScope.AllRounds, ListOrder.LatestComment)
        juror.query.keyed must beTrue
        val hood = juror.around(1).get
        (hood.files.map(_.pageId), hood.position, hood.next.map(_.pageId)) === ((Seq(4L, 5L, 1L, 3L), 2, Some(3L)))
        juror.position(3) === Some(3)
        // a file without a comment in this round (a typed URL): itself, last (its latest is 0)
        val own = report(f, f.juror1.getId, order = ListOrder.LatestComment)
        own.around(3).map(h => (h.files.map(_.pageId), h.file.pageId, h.position)) === Some((Seq(1L, 5L, 3L), 3L, 3))
        own.around(2).map(_.files.map(_.pageId)) === Some(Seq(1L, 5L, 2L))
        // all the jurors' by rank
        val all = report(f, order = ListOrder.LatestComment)
        all.around(7).map(h => (h.files.map(_.pageId), h.position)) === Some((Seq(4L, 1L, 7L, 5L), 2))
        all.position(5) === Some(3)
      }
    }

    "be paged by 20 files, every comment of a file on its page" in {
      testDbApp { _ =>
        val f = fixture()
        // 22 more files of juror 2 with a comment each in this round: 26 in all
        val more = (101L to 122L).map(id => Image(id, s"File:More$id.jpg", None, None, 640, 480, Some(s"12-999-$id")))
        imageDao.batchInsert(more)
        selectionDao.batchInsert(more.map(i => Vote(i, f.juror2, f.round, 0)))
        more.foreach(i => CommentJdbc.create(1, "Juror 2", f.round.getId, Some(f.contest.getId), i.pageId, s"on ${i.pageId}"))
        val l = report(f)
        l.pageSize === 20
        val pager = Pager(2, l.count(), l.pageSize)
        (pager.count, pager.pages) === ((26, 2))
        l.files(Pager(1, 26, 20)).size === 20
        // file order: 1, 5, the 22 (12-999-…), then region 80's 4 and 7
        l.files(pager).map(_.pageId) === Seq(119L, 120L, 121L, 122L, 4L, 7L)
        l.commentCount() === 27
      }
    }
  }

  "the comments report's URLs" should {

    "keep its scope, order and view, and read them from a request" in {
      testDbApp { _ =>
        val f = fixture()
        val l = report(f, scope = CommentScope.AllRounds, order = ListOrder.LatestComment, filter = VoteFilter.Rate(1))
        l.pageUrl(2) must startWith(s"/comments/round/${f.round.getId}/user/0/page/2?")
        forall(Seq(l.pageUrl(2), l.largeUrl(1), l.atUrl(1), l.regionsUrl, l.commentUrl(1),
          l.withView(GalleryView.CommentsCsv).pageUrl(1))) { url =>
          url must contain("scope=contest")
          url must contain("sort=latest-comment")
          url must contain("filter=selected")
        }
        l.largeUrl(1) must contain("module=comments")
        l.atUrl(1) must startWith(s"/comments/round/${f.round.getId}/user/0/region/all/pageid/1?")
        l.withView(GalleryView.CommentsCsv).pageUrl(1) must startWith(s"/comments/round/${f.round.getId}/user/0/region/all/csv?")
        // this round's scope is the default, not in the URL; nor the comments toggle, which the report doesn't need
        report(f).pageUrl(1) must not(contain("scope="))
        report(f).pageUrl(1) must not(contain("comments="))
        // out of the report: no scope, and by rate instead of by the latest comment
        val gallery = l.withView(GalleryView.ByRate)
        (gallery.pageUrl(1) must not(contain("scope="))) and (gallery.order === ListOrder.ByRate)
        gallery.query.commentsIn must beNone

        def of(query: String, viewer: User = f.organizer, view: GalleryView = GalleryView.Comments) =
          RoundList.of(f.round, viewer, 0, "all", view)(FakeRequest(GET, "/x" + query))
        of("?scope=contest&sort=latest-comment").commentScope === CommentScope.AllRounds
        of("?sort=latest-comment").order === ListOrder.LatestComment
        of("?scope=other").commentScope === CommentScope.ThisRound
        // by the latest comment only in the report; a large view of it (module) for the organisers only
        of("?sort=latest-comment", view = GalleryView.ByRate).order must not(be_==(ListOrder.LatestComment))
        GalleryView.of(FakeRequest(GET, "/?module=comments")) === GalleryView.Comments
        GalleryView.of(FakeRequest(GET, "/?module=comments-csv")) === GalleryView.Gallery
        val juror = RoundList.of(f.round, f.juror1, f.juror1.getId, "all", GalleryView.Comments)(
          FakeRequest(GET, "/x?sort=latest-comment&scope=round"))
        (juror.view, juror.query.commentsIn, juror.order) === ((GalleryView.Gallery, None, ListOrder.FileOrder))
      }
    }
  }

  "the comments report's default order" should {

    "be the latest comment first when the URL names none: all the jurors' and one juror's, both scopes, the CSV too" in {
      testDbApp { _ =>
        val f = fixture()
        def of(asUserId: Long, query: String, view: GalleryView = GalleryView.Comments) =
          RoundList.of(f.round, f.organizer, asUserId, "all", view)(FakeRequest(GET, "/x" + query))
        forall(for (who <- Seq(0L, f.juror1.getId); q <- Seq("", "?scope=round", "?scope=contest");
                    v <- Seq(GalleryView.Comments, GalleryView.CommentsCsv)) yield (who, q, v)) { case (who, q, v) =>
          (who, q, v, of(who, q, v).order) === ((who, q, v, ListOrder.LatestComment))
        } and
          // an explicit order stays; the other views keep theirs
          (of(0, "?sort=file").order === ListOrder.FileOrder) and
          (of(0, "?sort=approvals-only").order === ListOrder.Binary(BinaryOrder.ApprovalsOnly)) and
          (of(f.juror1.getId, "?sort=oldest-vote").order === ListOrder.OldestVote) and
          (of(0, "", GalleryView.ByRate).order must not(be_==(ListOrder.LatestComment))) and
          // the large view opened from the report, and its neighbours: by the latest comment
          (GalleryView.of(FakeRequest(GET, "/?module=comments")) === GalleryView.Comments) and
          (of(0, "?module=comments").around(4).map(h => (h.files.map(_.pageId), h.position)) ===
            Some((Seq(4L, 1L, 7L, 5L), 0)))
      }
    }

    "show it chosen in the order menu, its rows and every link by it, and an explicit order kept" in {
      testDbApp { app =>
        val f = fixture()
        val all = page(app, f.organizer, path(f, 0))
        val juror = page(app, f.organizer, path(f, f.juror1.getId, "?scope=contest"))
        val chosen = page(app, f.organizer, path(f, 0, "?sort=file"))
        (rows(all) === Seq(4L, 1L, 7L, 5L)) and
          (all.select("#comments-report-order .dropdown-toggle").text must contain("Latest comment first")) and
          (all.select("#comments-report-order a.dropdown-item.active").text === "Latest comment first") and
          (all.selectFirst("#comments-report-csv").attr("href") must contain("sort=latest-comment")) and
          (all.selectFirst("h2 a").attr("href") must
            startWith(s"/large/round/${f.round.getId}/user/0/pageid/4?module=comments&sort=latest-comment")) and
          (rows(juror) === Seq(4L, 5L, 1L, 3L)) and
          (juror.select("#comments-report-order a.dropdown-item.active").text === "Latest comment first") and
          (rows(chosen) === Seq(1L, 5L, 4L, 7L)) and
          (chosen.select("#comments-report-order a.dropdown-item.active").text must not(contain("Latest comment")))
      }
    }

    "open from the view menu and the round statistics by the latest comment, and leave it by the list's order" in {
      testDbApp { app =>
        val f = fixture()
        val r = f.round.getId
        val gallery = page(app, f.organizer, s"/gallery/round/$r/user/0/page/1?sort=approvals-only")
        val menuLink = gallery.selectFirst(s"a.dropdown-item[href^=/comments/round/$r/user/0/page/1]").attr("href")
        val stat = page(app, f.organizer, s"/roundstat/$r")
        val report = page(app, f.organizer, path(f, 0))
        val back = report.selectFirst(s"a.dropdown-item[href^=/byrate/round/$r/user/0/page/1]").attr("href")
        (menuLink must endWith("sort=latest-comment")) and
          (stat.selectFirst("#round-comments-link a").attr("href") must endWith("sort=latest-comment")) and
          (back must endWith("sort=rate"))
      }
    }

    "open by the latest comment from every entry with a remembered yes/no order, and leave it by that order" in {
      testDbApp { app =>
        val f = fixture()
        val r = f.round.getId
        // choosing "approvals only" in all the jurors' gallery remembers it (RoundList.BinarySortCookie)
        val chosen = get(app, f.organizer, s"/gallery/round/$r/user/0/page/1?sort=approvals-only")
        status(chosen) must_== OK
        val remembered = cookies(chosen).get(RoundList.BinarySortCookie).toSeq
        def withRemembered(path: String): (Document, Option[String]) = {
          val result = route(app, FakeRequest(GET, path).withSession(Secured.UserName -> f.organizer.email)
            .withCookies(remembered: _*).withCSRFToken).get
          status(result) aka path must_== OK
          (Jsoup.parse(contentAsString(result)), cookies(result).get(RoundList.BinarySortCookie).map(_.value))
        }
        def href(doc: Document, css: String): String = Option(doc.selectFirst(css)).fold("")(_.attr("href"))
        def sorts(doc: Document, css: String): List[String] =
          doc.select(css).asScala.map(_.attr("href")).toList.map(h => h.substring(h.indexOf("sort=") + 5))
        val (gallery, _) = withRemembered(s"/gallery/round/$r/user/0/page/1")
        val (stat, _) = withRemembered(s"/roundstat/$r")
        val entries = Seq(
          "view menu" -> href(gallery, s"a.dropdown-item[href^=/comments/round/$r/user/0/page/1]"),
          "round statistics" -> href(stat, "#round-comments-link a"),
          "no order" -> path(f, 0))
        (remembered.map(_.value) === Seq(BinaryOrder.ApprovalsOnly.param)) and
          // the gallery without an order is in the remembered one
          (sorts(gallery, "a.dropdown-item.active") must contain("approvals-only")) and
          forall(entries) { case (entry, url) =>
            val (doc, set) = withRemembered(url)
            (entry, url, rows(doc), doc.select("#comments-report-order .dropdown-toggle").text,
              sorts(doc, "#comments-report-order a.dropdown-item"),
              sorts(doc, "#comments-report-order a.dropdown-item.active"), set) ===
              ((entry, url, Seq(4L, 1L, 7L, 5L), "Order: Latest comment first",
                List("latest-comment", "approvals", "difference", "approvals-only", "file"), List("latest-comment"), None))
          } and
          (entries.take(2).map(_._2) must contain(endWith("sort=latest-comment")).forall) and {
            // leaving the report: the gallery's link by rate is in the remembered order again
            val (report, _) = withRemembered(path(f, 0))
            val back = href(report, s"a.dropdown-item[href^=/gallery/round/$r/user/0/page/1]")
            val (left, set) = withRemembered(back)
            (back must endWith("sort=rate")) and (set must beNone) and
              (sorts(left, "a.dropdown-item.active") must contain("approvals-only")) and
              (sorts(left, "a.dropdown-item.active") must not(contain("rate")))
          } and {
            // an order chosen in the report stays in it
            val (own, _) = withRemembered(path(f, 0, "?sort=difference"))
            sorts(own, "#comments-report-order a.dropdown-item.active") === List("difference")
          }
      }
    }

    "be offered first in the order menu; an order it hasn't shows as the list's own first, not as it" in {
      testDbApp { _ =>
        val f = fixture()
        (report(f).commentOrderChoices.head === ListOrder.LatestComment) and
          (report(f, f.juror1.getId).commentOrderChoices.head === ListOrder.LatestComment) and
          (report(f, order = ListOrder.LatestComment).shownCommentOrder === ListOrder.LatestComment) and
          // all the jurors' votes on a yes/no round: a juror's order by vote time is none of theirs
          (report(f, order = ListOrder.OldestVote).shownCommentOrder === ListOrder.Binary(BinaryOrder.all.head)) and
          (report(f, order = ListOrder.FileOrder).shownCommentOrder === ListOrder.FileOrder)
      }
    }

    "stay when the round menu opens all the jurors' votes in a round the juror is not a juror of" in {
      testDbApp { _ =>
        val f = fixture()
        val juror = report(f, f.juror1.getId, order = ListOrder.OldestVote)
        val other = juror.inRound(f.earlier, f.organizer.getId, jurorOfRound = false)
        val gallery = juror.withView(GalleryView.Gallery).withOrder(ListOrder.OldestVote)
          .inRound(f.earlier, f.organizer.getId, jurorOfRound = false)
        ((other.asUserId, other.view, other.order) === ((0L, GalleryView.Comments, ListOrder.LatestComment))) and
          (other.pageUrl(1) must endWith("sort=latest-comment")) and
          ((gallery.asUserId, gallery.view, gallery.order) === ((0L, GalleryView.Gallery, ListOrder.ByRate))) and
          // a juror of the round, or all the jurors' votes: the order stays
          (juror.inRound(f.earlier, f.organizer.getId, jurorOfRound = true).order === ListOrder.OldestVote) and
          (report(f, order = ListOrder.FileOrder).inRound(f.earlier, f.organizer.getId, jurorOfRound = false).order ===
            ListOrder.FileOrder)
      }
    }

    "make the large view without an order move among the report's files by the latest comment" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.organizer, s"/large/round/${f.round.getId}/user/0/pageid/4?module=comments")
        doc.select(s"a[href^='/large/round/${f.round.getId}/user/0/pageid/1?module=comments&sort=latest-comment']").size must be_>(0)
      }
    }
  }

  "the comments report page" should {

    "show the organisers each file with its thumbnail, result and this round's comments" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.organizer, path(f, 0, "?sort=file"))
        rows(doc) === Seq(1L, 5L, 4L, 7L)
        doc.selectFirst("#comments-report-count").text === "· 4 files, 5 comments"
        val one = doc.getElementById("1")
        one.selectFirst("h2 a").text === "File:Image1.jpg"
        one.selectFirst("h2 a").attr("href") must startWith(s"/large/round/${f.round.getId}/user/0/pageid/1?")
        one.selectFirst("h2 a").attr("href") must contain("module=comments")
        one.selectFirst("a.wlx-comments-thumb img").attr("src") must not(beEmpty)
        // all the jurors' tally: file 1 selected by juror 1
        one.selectFirst(".wlx-comments-result .wlx-approvals .visually-hidden").text must contain("1")
        one.select(".msg .wlx-comment-body").asScala.map(_.text) === Seq("one, first", "one, second")
        one.select(".msg .wlx-comment-meta strong").asScala.map(_.text) === Seq("Juror 1", "Org")
        one.select(".msg time.wlx-comment-time").asScala.map(_.attr("datetime")) ===
          Seq("2026-11-12T12:05:00Z", "2026-11-12T12:05:00Z")
        doc.select(".wlx-comment-round").size === 0
        // the scope switch: this round pressed, all the rounds kept the list
        val scopes = doc.select("#comments-report-scope a").asScala
        scopes.map(_.hasClass("active")) === Seq(true, false)
        scopes.head.attr("aria-current") === "true"
        scopes(1).attr("href") must contain("scope=contest")
        // the order menu: the latest comment first, then the binary orders and file order
        doc.select("#comments-report-order a.dropdown-item").asScala.map(_.attr("href")).toList.map(h =>
          h.substring(h.indexOf("sort=") + 5)) === List("latest-comment", "approvals", "difference", "approvals-only", "file")
        doc.selectFirst("#comments-report-csv").attr("href") must startWith(
          s"/comments/round/${f.round.getId}/user/0/region/all/csv")
        // no comments toggle: the report has only such files
        doc.select("a.wlx-comments-toggle").size === 0
      }
    }

    "show all the contest's comments in that scope, another round's with its name" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.organizer, path(f, 0, "?scope=contest&sort=latest-comment"))
        rows(doc) === Seq(4L, 5L, 1L, 7L, 3L)
        doc.selectFirst("#comments-report-count").text === "· 5 files, 7 comments"
        val five = doc.getElementById("5").select(".msg").asScala
        five.map(_.selectFirst(".wlx-comment-body").text) === Seq("five, this round", "five, earlier")
        five.map(_.select(".wlx-comment-round [aria-hidden=true]").text) === Seq("", "Pre-screening")
        five.map(_.select(".wlx-comment-round .visually-hidden").text) === Seq("", "Written in round Pre-screening")
        five.map(_.hasClass("wlx-comment-other-round")) === Seq(false, true)
        doc.getElementById("3").selectFirst("time.wlx-comment-time").attr("datetime") === "2016-10-05T09:34:56Z"
        doc.select("#comments-report-scope a.active").text === "All rounds of the contest"
        doc.select("#comments-report-order .dropdown-toggle").text must contain("Latest comment first")
      }
    }

    "escape its authors and texts, keeping a text's line breaks" in {
      testDbApp { app =>
        val f = fixture()
        val html = contentAsString(get(app, f.organizer, path(f, 0)))
        val doc = Jsoup.parse(html)
        doc.select("img[onerror]").size === 0
        doc.getElementById("4").select("b").size === 0
        html must not(contain("<img src=x"))
        val msg = doc.getElementById("4").selectFirst(".msg")
        msg.selectFirst(".wlx-comment-meta strong").text === EvilName
        msg.selectFirst(".wlx-comment-body").wholeText === EvilBody
        // a time that can't be read: none shown
        msg.select("time").size === 0
      }
    }

    "show one juror's votes and comments, and the list's tab and region, paged" in {
      testDbApp { app =>
        val f = fixture()
        val juror2 = page(app, f.organizer, path(f, f.juror2.getId))
        // by the latest comment (by default): 7 (comment 4), then 5 (comment 1)
        rows(juror2) === Seq(7L, 5L)
        // one juror's vote, not a tally: file 7 selected by juror 2
        juror2.getElementById("7").select(".wlx-comments-result .wlx-approvals").size === 0
        rows(page(app, f.organizer, path(f, 0, "?filter=selected&sort=file"))) === Seq(1L, 5L, 7L)
        // by the binary order (approvals, then rejections) when chosen
        rows(page(app, f.organizer, path(f, 0, "?filter=selected&sort=approvals"))) === Seq(1L, 7L, 5L)
        rows(page(app, f.organizer, s"/comments/round/${f.round.getId}/user/0/region/80/page/1?sort=file")) === Seq(4L, 7L)
        // the page with a file: a redirect to its page, scrolled to it
        val at = get(app, f.organizer, s"/comments/round/${f.round.getId}/user/0/region/all/pageid/7?sort=latest-comment")
        status(at) === SEE_OTHER
        redirectLocation(at).get must startWith(s"/comments/round/${f.round.getId}/user/0/page/1?")
        redirectLocation(at).get must endWith("#7")
      }
    }

    "say when no file has a comment in the scope" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.organizer, path(f, f.juror1.getId, "?filter=selected&scope=round"))
        // juror 1's selected: 1 (this round's comments), 2 (another contest's only)
        rows(doc) === Seq(1L)
        val empty = page(app, f.organizer, s"/comments/round/${f.earlier.getId}/user/0/page/1")
        rows(empty) === Nil
        empty.getElementById("comments-report-empty").text must contain("written in this round")
      }
    }

    "be open to the contest's admins and root too" in {
      testDbApp { app =>
        val f = fixture()
        val root = User.create(User("root", "root@example.com", None, Set(User.ROOT_ROLE), Some("hash")))
        rows(page(app, f.admin, path(f, 0, "?sort=file"))) === Seq(1L, 5L, 4L, 7L)
        rows(page(app, root, path(f, 0, "?sort=file"))) === Seq(1L, 5L, 4L, 7L)
        csvOf(app, f.admin, s"/comments/round/${f.round.getId}/user/0/region/all/csv").size === 6
      }
    }
  }

  "the comments report's results" should {

    /** A rated round (5 stars) and a criteria round (10 stars, 2 criteria) of a contest, juror 1
      * and juror 2 on files 1 and 2, each file with a comment in each round. Rated: file 1 4 and 2
      * stars, file 2 5 and unrated. Criteria: file 1 8 + 6 by juror 1 (Σ 14, 7 a criterion) and
      * 6 + 9 by juror 2; file 2 5 + 5 by juror 1.
      */
    def results() = {
      val a = contestDao.create(None, "WLE", 2026, "Ukraine")
      val rated = roundDao.create(Round(None, 1, Some("Rated"), contestId = a.getId, rates = Round.ratesById(5),
        active = true))
      val criteria = roundDao.create(Round(None, 2, Some("Criteria"), contestId = a.getId, rates = Round.ratesById(10),
        active = true, criterionIds = Seq(1L, 2L)))
      def user(name: String, role: String) =
        userDao.create(User(name, s"${name.toLowerCase.replace(" ", "")}@example.com", None, Set(role), contestId = a.id))
      val Seq(juror1, juror2) = Seq("Juror 1", "Juror 2").map(user(_, "jury"))
      val organizer = user("Org", "organizer")
      val images = (1L to 2L).map(id => Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(s"12-345-$id")))
      imageDao.batchInsert(images)
      for (r <- Seq(rated, criteria)) {
        r.addUsers(Seq(juror1, juror2).map(j => RoundUser(r.getId, j.getId, "jury", active = true)))
        images.foreach(i => CommentJdbc.create(1, "Org", r.getId, Some(a.getId), i.pageId, s"on ${i.pageId}"))
      }
      selectionDao.batchInsert(Seq(Vote(images(0), juror1, rated, 4), Vote(images(0), juror2, rated, 2),
        Vote(images(1), juror1, rated, 5), Vote(images(1), juror2, rated, 0)) ++
        (for (j <- Seq(juror1, juror2); i <- images) yield Vote(i, j, criteria, 0)))
      def rate(image: Image, juror: User, rates: (Long, Int)*): Unit = {
        val vote = VoteJdbc.findBy(image.pageId, juror.getId, criteria.getId).get
        rates.foreach { case (criterion, r) => CriteriaRate.updateRate(vote.getId, criterion, r) must beTrue }
      }
      rate(images(0), juror1, 1L -> 8, 2L -> 6)
      rate(images(0), juror2, 1L -> 6, 2L -> 9)
      rate(images(1), juror1, 1L -> 5, 2L -> 5)
      (rated, roundDao.findById(criteria.getId).get, juror1, organizer)
    }

    /** Each file's result in the report: its text and its stars' icons, by page id. */
    def reportResults(doc: Document): Map[Long, (String, Seq[String])] =
      doc.select("article.wlx-comments-row").asScala.map { row =>
        val stars = row.selectFirst(".wlx-comments-result .rate-stars")
        row.id.toLong -> (stars.text, stars.select("i.wlx-star").asScala.map(_.className).toSeq)
      }.toMap

    /** The same of the by-rate view, the reference. */
    def byRateResults(doc: Document): Map[Long, (String, Seq[String])] =
      doc.select("li.gallerybox").asScala.map { box =>
        val stars = box.selectFirst(".rank-line .rate-stars")
        box.id.toLong -> (stars.text, stars.select("i.wlx-star").asScala.map(_.className).toSeq)
      }.toMap

    "be the by-rate view's in a rated and a criteria round, of one juror and of all the jurors" in {
      testDbApp { app =>
        val (rated, criteria, juror1, organizer) = results()
        forall(Seq(rated, criteria)) { round =>
          forall(Seq(juror1.getId, 0L)) { asUserId =>
            val report = reportResults(page(app, organizer, s"/comments/round/${round.getId}/user/$asUserId/page/1"))
            val byRate = byRateResults(page(app, organizer, s"/byrate/round/${round.getId}/user/$asUserId/page/1"))
            report.keySet === Set(1L, 2L)
            report aka s"round ${round.description}, user $asUserId" must_== byRate.view.filterKeys(report.keySet).toMap
          }
        }
        // juror 1's 8 + 6 of 10 stars in two criteria: 7 a criterion, Σ 14; never 14 stars
        val one = reportResults(page(app, organizer, s"/comments/round/${criteria.getId}/user/${juror1.getId}/page/1"))(1L)
        one._1 must contain("Σ 14")
        one._1 must not(startWith("14"))
      }
    }
  }

  "the comments report's access" should {

    "be refused to a juror, of their own files too, as a page or JSON, the CSV too" in {
      testDbApp { app =>
        val f = fixture()
        val paths = Seq(path(f, f.juror1.getId), path(f, 0), s"/query?module=comments&round=${f.round.getId}&user=${f.juror1.getId}",
          s"/comments/round/${f.round.getId}/user/${f.juror1.getId}/region/all/csv",
          s"/comments/round/${f.round.getId}/user/${f.juror1.getId}/region/all/pageid/1",
          s"/query?module=comments-csv&round=${f.round.getId}&user=${f.juror1.getId}")
        forall(paths) { p =>
          val result = get(app, f.juror1, p)
          status(result) aka p must_== FORBIDDEN
          val body = contentAsString(result)
          body must not(contain("one, first"))
          body must not(contain("five, this round"))
        }
        val json = get(app, f.juror1, path(f, f.juror1.getId), "X-Requested-With" -> "XMLHttpRequest")
        status(json) === FORBIDDEN
        (Json.parse(contentAsString(json)) \ "error").as[String] === "forbidden"
        (Json.parse(contentAsString(json)) \ "reference").as[String] must not(beEmpty)
      }
    }

    "be refused for another contest's round, to its organizers and admins" in {
      testDbApp { app =>
        val f = fixture()
        CommentJdbc.create(1, "B", f.roundB.getId, Some(f.roundB.contestId), 2, "secret of B")
        forall(Seq(f.organizer, f.admin)) { viewer =>
          forall(Seq(s"/comments/round/${f.roundB.getId}/user/0/page/1",
            s"/comments/round/${f.roundB.getId}/user/0/region/all/csv")) { p =>
            val result = get(app, viewer, p)
            status(result) aka p must_== FORBIDDEN
            contentAsString(result) must not(contain("secret of B"))
          }
        }
      }
    }

    "make a juror's large view naming it their gallery's, never the report" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.juror1, s"/large/round/${f.round.getId}/user/${f.juror1.getId}/pageid/1?module=comments&sort=latest-comment")
        doc.select("a[href*=/comments/]").size === 0
        doc.select("a[href*=module=comments]").size === 0
      }
    }

    "offer it in the view menu and round statistics to the organisers only" in {
      testDbApp { app =>
        val f = fixture()
        val org = page(app, f.organizer, s"/byrate/round/${f.round.getId}/user/0/page/1")
        org.select(s"a[href^=/comments/round/${f.round.getId}/user/0/page/1]").size === 1
        val stat = page(app, f.organizer, s"/roundstat/${f.round.getId}")
        stat.selectFirst("#round-comments-link a").attr("href") must startWith(s"/comments/round/${f.round.getId}/user/0/page/1")
        val juror = contentAsString(get(app, f.juror1, s"/gallery/round/${f.round.getId}/user/${f.juror1.getId}/page/1"))
        juror must not(contain("/comments/round/"))
      }
    }
  }

  "the comments CSV" should {

    "have a row per comment in the scope, its file, author, time, round and text, formula-safe" in {
      testDbApp { app =>
        val f = fixture()
        val csv = csvOf(app, f.organizer, s"/comments/round/${f.round.getId}/user/0/region/all/csv?sort=latest-comment")
        csv.head === Csv.CommentHeader.toList
        csv.tail.map(r => (r.head, r(1), r(5), r(6))) === List(
          ("File:Image4.jpg", "4", "Round 2", "'" + EvilBody),
          ("File:Image1.jpg", "1", "Round 2", "one, first"),
          ("File:Image1.jpg", "1", "Round 2", "one, second"),
          ("File:Image7.jpg", "7", "Round 2", "seven"),
          ("File:Image5.jpg", "5", "Round 2", "five, this round"))
        val evil = csv(1)
        // as the contest files page links it (Global.commonsPageUrl)
        evil(2) === "https://commons.wikimedia.org/wiki/File%3AImage4.jpg"
        evil(3) === "'" + EvilName
        // a time that can't be read: as stored
        evil(4) === "not a time"
        csv(2)(4) === "2026-11-12T12:05:00Z"
      }
    }

    "follow the scope and the list's filters" in {
      testDbApp { app =>
        val f = fixture()
        val all = csvOf(app, f.organizer, s"/comments/round/${f.round.getId}/user/0/region/all/csv?scope=contest&sort=file")
        all.tail.map(r => (r(1), r(5))) === List("1" -> "Round 2", "1" -> "Round 2", "3" -> "Pre-screening",
          "5" -> "Round 2", "5" -> "Pre-screening", "4" -> "Round 2", "7" -> "Round 2")
        val juror2 = csvOf(app, f.organizer,
          s"/comments/round/${f.round.getId}/user/${f.juror2.getId}/region/80/csv?filter=selected")
        juror2.tail.map(_(1)) === List("7")
      }
    }
  }
}
