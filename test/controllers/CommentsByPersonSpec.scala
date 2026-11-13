package controllers

import com.github.tototoshi.csv.CSVReader
import db.scalikejdbc._
import org.intracer.wmua._
import org.jsoup.Jsoup
import org.jsoup.nodes.{Document, Element}
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import java.io.StringReader
import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** The comments report's filter by author ([[RoundList.commenter]], `commenter=<user id>`): only
  * the files with a comment by that account in the scope, counted, ordered by the latest of
  * theirs and exported by it, combined with the list's juror, tab, region, scope and order, kept
  * by its links and the large view's neighbours; a chip naming them that removes it; each
  * comment's icon links to the filter and to its author's one-juror gallery of the comment's
  * round (a juror of it the viewer may see); the organisers' only, as the report.
  */
class CommentsByPersonSpec extends PlaySpecification with PlayTestDb {

  sequential

  private case class Fixture(
      contest: ContestJury,
      earlier: Round,
      round: Round,
      roundB: Round,
      juror1: User,
      juror2: User,
      prescreener: User,
      organizer: User
  )

  /** A name with HTML and quotes: juror 2's latest comment is signed with it. */
  private val EvilName = "Ev\"il <b>x</b> 'q'"

  /** Contest A's binary Round 2 with juror 1 on files 1-6 (1, 2 selected; 3, 4 unrated; 5, 6
    * rejected) and juror 2 on files 5-8 (5, 7 selected; 6, 8 unrated); its earlier round
    * Pre-screening with the pre-screener on files 1-8 (no juror of Round 2); contest B's round with
    * juror 1 on file 2. Files 4 and 7 in region 80, the others in 12. Comments, in the order written:
    *   1. juror 1 on 5 (Round 2)            2. pre-screener on 3 (Pre-screening)
    *   3. juror 1 on 1 (Round 2)            4. juror 2 on 7 (Round 2)
    *   5. organizer on 1 (Round 2)          6. juror 1 on 2 (contest B: never here)
    *   7. pre-screener on 5 (Pre-screening) 8. juror 2, signed [[EvilName]], on 4 (Round 2)
    *   9. no account (user_id 0) on 6       10. juror 1 on 7 (Round 2)
    *   11. organizer on 5 (Round 2)
    * Round 2's: files 1, 4, 5, 6, 7; by the latest first 5, 7, 6, 4, 1. Juror 1's there: 1 (3),
    * 5 (1), 7 (10); by their latest first 7, 1, 5.
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
    val prescreener = user("Pre", "jury")
    val organizer = user("Org", "organizer")
    val images = (1L to 8L).map { id =>
      val region = if (id == 4 || id == 7) "80" else "12"
      Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(s"$region-345-$id"))
    }
    imageDao.batchInsert(images)
    round.addUsers(Seq(juror1, juror2).map(j => RoundUser(round.getId, j.getId, "jury", active = true)))
    earlier.addUsers(Seq(RoundUser(earlier.getId, prescreener.getId, "jury", active = true)))
    selectionDao.batchInsert(
      images.take(6).zip(Seq(1, 1, 0, 0, -1, -1)).map { case (i, r) => Vote(i, juror1, round, r) } ++
        images.drop(4).zip(Seq(1, 0, 1, 0)).map { case (i, r) => Vote(i, juror2, round, r) } ++
        images.map(i => Vote(i, prescreener, earlier, 1)) :+ Vote(images(1), juror1, roundB, 0))
    def comment(by: User, r: Round, pageId: Long, text: String, name: Option[String] = None) =
      CommentJdbc.create(by.getId, name.getOrElse(by.fullname), r.getId, Some(r.contestId), pageId, text)
    comment(juror1, round, 5, "j1 on five")
    comment(prescreener, earlier, 3, "pre on three")
    comment(juror1, round, 1, "j1 on one")
    comment(juror2, round, 7, "j2 on seven")
    comment(organizer, round, 1, "org on one")
    comment(juror1, roundB, 2, "another contest")
    comment(prescreener, earlier, 5, "pre on five")
    comment(juror2, round, 4, "j2 on four", Some(EvilName))
    CommentJdbc.create(0, "Legacy", round.getId, Some(a.getId), 6, "legacy on six")
    comment(juror1, round, 7, "j1 on seven")
    comment(organizer, round, 5, "org on five")
    Fixture(a, earlier, round, roundB, juror1, juror2, prescreener, organizer)
  }

  private def report(f: Fixture, commenter: Option[Long], asUserId: Long = 0,
                     scope: CommentScope = CommentScope.ThisRound, order: ListOrder = ListOrder.LatestComment,
                     filter: VoteFilter = VoteFilter.All, region: Option[String] = None) =
    RoundList(f.round, asUserId, region = region, filter = filter, order = order, view = GalleryView.Comments,
      commentScope = scope, commenter = commenter)

  private def pageIds(l: RoundList): Seq[Long] = l.files(Pager(1, 0)).map(_.pageId)

  private def get(app: Application, viewer: User, path: String, headers: (String, String)*): Future[Result] =
    route(app, FakeRequest(GET, path).withSession(Secured.UserName -> viewer.email).withHeaders(headers: _*)
      .withCSRFToken).get

  private def page(app: Application, viewer: User, path: String, headers: (String, String)*): Document = {
    val result = get(app, viewer, path, headers: _*)
    status(result) aka path must_== OK
    Jsoup.parse(contentAsString(result))
  }

  private def rows(doc: Document): Seq[Long] = doc.select("article.wlx-comments-row").asScala.map(_.id.toLong).toSeq

  private def path(f: Fixture, asUserId: Long, query: String) =
    s"/comments/round/${f.round.getId}/user/$asUserId/page/1$query"

  /** A row's comments shown (not folded), and those folded under them, by their texts. */
  private def shown(row: Element): (Seq[String], Seq[String]) = {
    val folded = row.select("details.wlx-comments-others .wlx-comment-body").asScala.map(_.text).toSeq
    (row.select(".wlx-comment-body").asScala.map(_.text).toSeq.filterNot(folded.contains), folded)
  }

  /** The icon links after a comment's author: (kind, href, title, aria-label, text). */
  private def links(msg: Element): Seq[(String, String, String, String, String)] =
    msg.select("a.wlx-comment-author-link").asScala.map { a =>
      (if (a.hasClass("wlx-comment-filter-link")) "filter" else "gallery", a.attr("href"), a.attr("title"),
        a.attr("aria-label"), a.text)
    }.toSeq

  private def msgOf(doc: Document, body: String): Element =
    doc.select(".msg").asScala.find(_.selectFirst(".wlx-comment-body").text == body).get

  "the comments report filtered by an author" should {

    "list the files with their comments in the scope, counted and ordered by their latest" in {
      testDbApp { _ =>
        val f = fixture()
        val j1 = Some(f.juror1.getId)
        pageIds(report(f, None)) === Seq(5L, 7L, 6L, 4L, 1L)
        pageIds(report(f, j1)) === Seq(7L, 1L, 5L)
        report(f, j1).count() === 3
        report(f, j1).commentCount() === 3
        pageIds(report(f, j1, order = ListOrder.FileOrder)) === Seq(1L, 5L, 7L)
        // the pre-screener's: none in Round 2, files 3 and 5 in all the contest's rounds
        pageIds(report(f, Some(f.prescreener.getId))) === Nil
        pageIds(report(f, Some(f.prescreener.getId), scope = CommentScope.AllRounds)) === Seq(5L, 3L)
        report(f, Some(f.prescreener.getId), scope = CommentScope.AllRounds).commentCount() === 2
        // juror 1's comment in contest B is never here
        pageIds(report(f, j1, scope = CommentScope.AllRounds)) === Seq(7L, 1L, 5L)
        // an account without comments: none
        pageIds(report(f, Some(f.organizer.getId + 1000))) === Nil
      }
    }

    "combine with a juror's list, a tab and a region" in {
      testDbApp { _ =>
        val f = fixture()
        val j1 = Some(f.juror1.getId)
        // juror 2's files 5-8 with juror 1's comments: 7, 5
        pageIds(report(f, j1, f.juror2.getId)) === Seq(7L, 5L)
        pageIds(report(f, j1, f.juror2.getId, filter = VoteFilter.Rate(1))) === Seq(7L, 5L)
        pageIds(report(f, j1, f.juror2.getId, filter = VoteFilter.Unrated)) === Nil
        pageIds(report(f, j1, region = Some("80"))) === Seq(7L)
        report(f, j1).regionCounts() === Map("12" -> 2, "80" -> 1)
        // juror 1's own list (keyed): its tabs counted by the filter
        report(f, j1, f.juror1.getId).jurorCounts().get.total(VoteFilter.All, None) === 2
        pageIds(report(f, j1, f.juror1.getId)) === Seq(1L, 5L)
      }
    }

    "move a large view among its files, by rank for all the jurors and by keyset for one" in {
      testDbApp { _ =>
        val f = fixture()
        val all = report(f, Some(f.juror1.getId))
        all.around(1).map(h => (h.files.map(_.pageId), h.position, h.next.map(_.pageId))) ===
          Some((Seq(7L, 1L, 5L), 1, Some(5L)))
        all.position(5) === Some(2)
        val juror2 = report(f, Some(f.juror1.getId), f.juror2.getId)
        juror2.query.keyed must beTrue
        juror2.around(5).map(h => (h.files.map(_.pageId), h.position)) === Some((Seq(7L, 5L), 1))
        // a file without their comment (a typed URL): itself, last
        juror2.around(6).map(h => (h.files.map(_.pageId), h.position)) === Some((Seq(7L, 5L, 6L), 2))
      }
    }
  }

  "the filter's URLs" should {

    "be kept by every link of the list, in the report only, and read from a request" in {
      testDbApp { _ =>
        val f = fixture()
        val l = report(f, Some(f.juror1.getId), scope = CommentScope.AllRounds, filter = VoteFilter.Rate(1))
        forall(Seq(l.pageUrl(2), l.largeUrl(1), l.atUrl(1), l.regionsUrl, l.commentUrl(1), l.voteQuery(),
          l.withView(GalleryView.CommentsCsv).pageUrl(1), l.withOrder(ListOrder.FileOrder).pageUrl(1),
          l.withScope(CommentScope.ThisRound).pageUrl(1), l.withRegion("80").pageUrl(1), l.withViewer(f.juror2.getId).pageUrl(1))) { url =>
          url must contain(s"commenter=${f.juror1.getId}")
        }
        val removed = l.withCommenter(None).pageUrl(1)
        removed must not(contain("commenter="))
        forall(Seq("scope=contest", "filter=selected", "sort=latest-comment"))(removed must contain(_))
        // out of the report: no filter by author
        l.withView(GalleryView.ByRate).pageUrl(1) must not(contain("commenter="))
        l.withView(GalleryView.ByRate).query.commentsBy must beNone

        def of(query: String, viewer: User = f.organizer, view: GalleryView = GalleryView.Comments, asUserId: Long = 0) =
          RoundList.of(f.round, viewer, asUserId, "all", view)(FakeRequest(GET, "/x" + query))
        of(s"?commenter=${f.juror1.getId}").commentsBy === Some(f.juror1.getId)
        forall(Seq("?commenter=x", "?commenter=0", "?commenter=-3", ""))(q => of(q).commenter must beNone)
        of(s"?commenter=${f.juror1.getId}", view = GalleryView.Gallery).commenter must beNone
        // a juror's large view naming the report: their gallery, the filter ignored
        val juror = of(s"?commenter=${f.juror2.getId}&scope=contest", f.juror1, GalleryView.Comments, f.juror1.getId)
        (juror.view, juror.commenter, juror.query.commentsIn, juror.query.commentsBy) ===
          ((GalleryView.Gallery, None, None, None))
      }
    }
  }

  "the comments report page" should {

    "link each author with an account by icons: the filter, and their gallery of a round they are a juror of" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.organizer, path(f, 0, "?scope=contest"))
        val j1 = links(msgOf(doc, "j1 on one"))
        j1.map(_._1) === Seq("filter", "gallery")
        j1.head._2 === report(f, Some(f.juror1.getId), scope = CommentScope.AllRounds).pageUrl(1)
        j1.head._2 must startWith(s"/comments/round/${f.round.getId}/user/0/page/1?")
        j1(1)._2 === s"/gallery/round/${f.round.getId}/user/${f.juror1.getId}/page/1"
        // icons only: the words in title and aria-label
        forall(j1)(l => (l._5 === "") and (l._3 === l._4))
        j1.head._3 === "Only the comments by Juror 1"
        j1(1)._3 === "Gallery of juror Juror 1 in round Round 2"
        msgOf(doc, "j1 on one").select("a.wlx-comment-author-link i[aria-hidden=true]").size === 2
        // the author's name stays text
        msgOf(doc, "j1 on one").select(".wlx-comment-meta strong a").size === 0
        msgOf(doc, "j1 on one").selectFirst(".wlx-comment-meta strong").text === "Juror 1"
        // the pre-screener's gallery is the earlier round's, where they are a juror
        links(msgOf(doc, "pre on three")).map(l => l._1 -> l._2) === Seq(
          "filter" -> report(f, Some(f.prescreener.getId), scope = CommentScope.AllRounds).pageUrl(1),
          "gallery" -> s"/gallery/round/${f.earlier.getId}/user/${f.prescreener.getId}/page/1")
        links(msgOf(doc, "pre on three"))(1)._3 === "Gallery of juror Pre in round Pre-screening"
        // an organizer who commented: the filter only; a comment without an account: neither
        links(msgOf(doc, "org on one")).map(_._1) === Seq("filter")
        links(msgOf(doc, "legacy on six")) === Nil
        msgOf(doc, "legacy on six").selectFirst(".wlx-comment-meta strong").text === "Legacy"
      }
    }

    "link no gallery of a round the viewer may not see, nor of one its author is no juror of" in {
      testDbApp { app =>
        val f = fixture()
        // a comment of contest A written in contest B's round, where juror 1 is a juror
        CommentJdbc.create(f.juror1.getId, "Juror 1", f.roundB.getId, Some(f.contest.getId), 2, "j1 in B's round")
        // juror 2 on a file of the earlier round, which they are no juror of
        CommentJdbc.create(f.juror2.getId, "Juror 2", f.earlier.getId, Some(f.contest.getId), 3, "j2 in pre")
        val doc = page(app, f.organizer, path(f, 0, "?scope=contest"))
        links(msgOf(doc, "j1 in B's round")).map(_._1) === Seq("filter")
        links(msgOf(doc, "j2 in pre")).map(_._1) === Seq("filter")
        doc.select(s"a[href^=/gallery/round/${f.roundB.getId}/]").size === 0
      }
    }

    "show the filter as a chip removing it, their comments first and the others folded" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.organizer, path(f, 0, s"?scope=contest&filter=selected&commenter=${f.juror1.getId}"))
        // the selected files (1, 2, 5, 7) with a comment of juror 1's
        rows(doc) === Seq(7L, 1L, 5L)
        doc.selectFirst("#comments-report-count").text === "· 3 files, 3 comments by Juror 1"
        val chip = doc.selectFirst("#comments-report-commenter")
        chip.ownText.trim === "Comments by Juror 1"
        val remove = chip.selectFirst("a.wlx-commenter-chip-remove")
        remove.attr("aria-label") === "Show everyone's comments"
        remove.attr("title") === "Show everyone's comments"
        remove.attr("href") must not(contain("commenter="))
        forall(Seq("scope=contest", "filter=selected", "sort=latest-comment"))(remove.attr("href") must contain(_))
        rows(page(app, f.organizer, remove.attr("href"))) === Seq(5L, 7L, 1L)
        // juror 1's comments shown, the others folded under them, each in the order written
        shown(doc.getElementById("7")) === ((Seq("j1 on seven"), Seq("j2 on seven")))
        shown(doc.getElementById("5")) === ((Seq("j1 on five"), Seq("pre on five", "org on five")))
        doc.getElementById("5").selectFirst("details.wlx-comments-others summary").text === "2 other comments on this file"
        doc.getElementById("5").select("details[open]").size === 0
        // the filtered author's comments have no filter link, only their gallery's
        links(msgOf(doc, "j1 on five")).map(_._1) === Seq("gallery")
        links(msgOf(doc, "org on five")).map(_._1) === Seq("filter")
        // the order and scope menus, the CSV, the region menu and the files' large views keep it
        forall(Seq("#comments-report-order a", "#comments-report-scope a", "#comments-report-csv",
          "article.wlx-comments-row h2 a")) { sel =>
          forall(doc.select(sel).asScala.map(_.attr("href")))(_ must contain(s"commenter=${f.juror1.getId}"))
        }
        // without the filter: no chip, nothing folded
        val plain = page(app, f.organizer, path(f, 0, "?scope=contest"))
        plain.select("#comments-report-commenter").size === 0
        plain.select("details.wlx-comments-others").size === 0
      }
    }

    "escape the author's name in the chip and the links' titles" in {
      testDbApp { app =>
        val f = fixture()
        val html = contentAsString(get(app, f.organizer, path(f, 0, s"?commenter=${f.juror2.getId}")))
        html must not(contain("<b>x</b>"))
        val doc = Jsoup.parse(html)
        doc.select("#comments-report-commenter b").size === 0
        // juror 2's latest comment is signed with it
        doc.selectFirst("#comments-report-commenter").ownText.trim === s"Comments by $EvilName"
        val evil = msgOf(page(app, f.organizer, path(f, 0, "")), "j2 on four")
        evil.selectFirst("a.wlx-comment-filter-link").attr("title") === s"Only the comments by $EvilName"
        evil.select(".wlx-comment-meta b").size === 0
      }
    }

    "say in the page's language what the icons and the chip do" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.organizer, path(f, 0, s"?commenter=${f.juror1.getId}"), "Accept-Language" -> "uk")
        doc.selectFirst("#comments-report-commenter").ownText.trim === "Коментарі: Juror 1"
        doc.selectFirst("#comments-report-count").text === "· 3 файли, 3 коментарі, автор: Juror 1"
        doc.selectFirst("a.wlx-commenter-chip-remove").attr("aria-label") === "Показати коментарі всіх"
        val msg = msgOf(doc, "j2 on seven")
        links(msg).map(l => (l._3, l._4, l._5)) === Seq(
          ("Лише коментарі: Juror 2", "Лише коментарі: Juror 2", ""),
          ("Галерея члена журі Juror 2 у раунді Round 2", "Галерея члена журі Juror 2 у раунді Round 2", ""))
        doc.getElementById("7").selectFirst("details summary").text === "Ще 1 коментар до цього файлу"
      }
    }

    "say when the author has no comment in the scope, naming them, or the account without one" in {
      testDbApp { app =>
        val f = fixture()
        val pre = page(app, f.organizer, path(f, 0, s"?commenter=${f.prescreener.getId}"))
        rows(pre) === Nil
        pre.selectFirst("#comments-report-empty").text ===
          "No file in this list has a comment by Pre written in this round."
        pre.selectFirst("#comments-report-commenter").ownText.trim === "Comments by Pre"
        // an account with no comment in the contest: never named from the users table
        val unknown = page(app, f.organizer, path(f, 0, s"?scope=contest&commenter=${f.organizer.getId + 1000}"))
        unknown.selectFirst("#comments-report-commenter").ownText.trim === s"Comments by account #${f.organizer.getId + 1000}"
        unknown.selectFirst("#comments-report-empty").text must contain("in this contest")
        // a real account of contest B, whose comments are all B's: not named from the users table
        val other = userDao.create(User("Other Person", "other@example.com", None, Set("jury"),
          contestId = Some(f.roundB.contestId)))
        CommentJdbc.create(other.getId, "Other Person", f.roundB.getId, Some(f.roundB.contestId), 2, "only in B")
        val html = contentAsString(get(app, f.organizer, path(f, 0, s"?scope=contest&commenter=${other.getId}")))
        Jsoup.parse(html).selectFirst("#comments-report-commenter").ownText.trim === s"Comments by account #${other.getId}"
        html must not(contain("Other Person"))
        html must not(contain("only in B"))
      }
    }

    "make the large view opened from it move among the filtered files" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.organizer,
          s"/large/round/${f.round.getId}/user/0/pageid/1?module=comments&commenter=${f.juror1.getId}")
        val large = doc.select(s"a[href^=/large/round/${f.round.getId}/]").asScala.map(_.attr("href")).toSeq
        large must not(beEmpty)
        forall(large)(_ must contain(s"commenter=${f.juror1.getId}"))
        // the files around 1 are 7 and 5, never another author's only (4, 6)
        large.exists(_.contains("/pageid/7")) must beTrue
        large.exists(_.contains("/pageid/5")) must beTrue
        large.exists(h => h.contains("/pageid/4?") || h.contains("/pageid/6?")) must beFalse
      }
    }
  }

  "the filter's access" should {

    "be the report's: refused to a juror as a page, the CSV too, and ignored in their gallery and large view" in {
      testDbApp { app =>
        val f = fixture()
        forall(Seq(path(f, f.juror1.getId, s"?commenter=${f.juror1.getId}"), path(f, 0, s"?commenter=${f.juror2.getId}"),
          s"/comments/round/${f.round.getId}/user/${f.juror1.getId}/region/all/csv?commenter=${f.juror1.getId}")) { p =>
          val result = get(app, f.juror1, p)
          status(result) aka p must_== FORBIDDEN
          contentAsString(result) must not(contain("j2 on seven"))
        }
        val gallery = page(app, f.juror1, s"/gallery/round/${f.round.getId}/user/${f.juror1.getId}/page/1?commenter=${f.juror2.getId}")
        gallery.select("a[href*=commenter=]").size === 0
        val large = page(app, f.juror1,
          s"/large/round/${f.round.getId}/user/${f.juror1.getId}/pageid/1?module=comments&commenter=${f.juror2.getId}")
        large.select("a[href*=commenter=]").size === 0
        large.select("a[href*=/comments/]").size === 0
      }
    }
  }

  "the comments CSV filtered by an author" should {

    "have their comments only, in the report's order" in {
      testDbApp { app =>
        val f = fixture()
        val result = get(app, f.organizer,
          s"/comments/round/${f.round.getId}/user/0/region/all/csv?scope=contest&commenter=${f.juror1.getId}")
        status(result) === OK
        val csv = CSVReader.open(new StringReader(contentAsString(result)(defaultAwaitTimeout, app.materializer)
          .stripPrefix("﻿"))).all()
        csv.tail.map(r => (r(1), r(3), r(6))) === List(
          ("7", "Juror 1", "j1 on seven"), ("1", "Juror 1", "j1 on one"), ("5", "Juror 1", "j1 on five"))
      }
    }
  }
}
