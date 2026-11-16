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
  * by its links and the large view's neighbours; chosen in the navbar's "Comments by" menu, which
  * lists every author of the report's comments with the number the list filtered by them counts,
  * or by the funnel after each comment's author (the same link; on the filtered author's comments
  * filled and current, removing the filter); each comment's icon link to its author's one-juror gallery of the comment's round (a juror of it
  * the viewer may see); the organisers' only, as the report.
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

  /** The Comments menu's "Comments by" section's items: (name, count, href, current). */
  private def menu(doc: Document): Seq[(String, Int, String, Boolean)] =
    doc.select("#comments-menu .wlx-comments-by-item").asScala.map { a =>
      // the count shown (a person's in its pill: its number, aria-hidden, after their words for screen readers)
      val count = Option(a.selectFirst(".wlx-menu-count [aria-hidden=true]")).getOrElse(a.selectFirst(".wlx-menu-count"))
      (a.ownText.trim, count.text.toInt, a.attr("href"), a.attr("aria-current") == "true")
    }.toSeq

  /** The Comments button's text, as screen readers read it ("Comments list · Juror 1"). */
  private def menuButton(doc: Document): String = doc.selectFirst("#comments-menu > button").text

  /** The number of comments the report's heading counts ("· 3 files, 4 comments[ by …]"). */
  private def headingComments(doc: Document): Int =
    """, (\d+) comments?""".r.findFirstMatchIn(doc.selectFirst("#comments-report-count").text).map(_.group(1).toInt).getOrElse(-1)

  /** The comments a report shows unfolded: with the filter by author, theirs. */
  private def unfolded(doc: Document): Int =
    doc.select("article.wlx-comments-row .msg").asScala.count(_.parents.asScala.forall(_.tagName != "details"))

  /** The icon links after a comment's author: (kind, href, title, aria-label, text). */
  private def links(msg: Element): Seq[(String, String, String, String, String)] =
    msg.select("a.wlx-comment-author-link").asScala.map { a =>
      (if (a.hasClass("wlx-comment-filter-link")) "filter" else "gallery", a.attr("href"), a.attr("title"),
        a.attr("aria-label"), a.text)
    }.toSeq

  /** A comment's funnel after its author: (href, title, aria-label, current, filled, text). */
  private def funnel(msg: Element): Option[(String, String, String, Boolean, Boolean, String)] =
    Option(msg.selectFirst("a.wlx-comment-filter-link")).map { a =>
      (a.attr("href"), a.attr("title"), a.attr("aria-label"), a.attr("aria-current") == "true",
        a.hasClass("active") && a.select("i.bi-funnel-fill[aria-hidden=true]").size == 1 && a.select("i.bi-funnel").isEmpty,
        a.text)
    }

  /** The Comments menu's "Comments by" item's link for a name ("Everyone" for everyone's). */
  private def menuHref(doc: Document, name: String): String = menu(doc).find(_._1 == name).map(_._3).get

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
        // a juror's own report (or its large view): the report, the filter ignored (decision 6 = A);
        // another juror's: their gallery, refused before it is listed
        val juror = of(s"?commenter=${f.juror2.getId}&scope=contest", f.juror1, GalleryView.Comments, f.juror1.getId)
        (juror.view, juror.commenter, juror.query.commentsIn, juror.query.commentsBy) ===
          ((GalleryView.Comments, None, Some(f.contest.getId), None))
        val other = of(s"?commenter=${f.juror2.getId}", f.juror1, GalleryView.Comments, f.juror2.getId)
        (other.view, other.commenter, other.query.commentsBy) === ((GalleryView.Gallery, None, None))
      }
    }
  }

  "the comments report page" should {

    "link each author with an account by icons: the filter, and their gallery of a round they are a juror of" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.organizer, path(f, 0, "?scope=contest"))
        val j1 = links(msgOf(doc, "j1 on one"))
        // the funnel (quick access to the Comments menu's "Comments by"), then the gallery
        j1.map(_._1) === Seq("filter", "gallery")
        j1(1)._2 === s"/gallery/round/${f.round.getId}/user/${f.juror1.getId}/page/1"
        // icons only: the words in title and aria-label
        forall(j1)(l => (l._5 === "") and (l._3 === l._4))
        j1.head._3 === "Show only Juror 1's comments"
        j1(1)._3 === "Gallery of juror Juror 1 in round Round 2"
        msgOf(doc, "j1 on one").select("a.wlx-comment-author-link i[aria-hidden=true]").size === 2
        // the funnel: outlined, not current, the list filtered by them with the scope and order kept,
        // the same link as their item in the Comments menu
        val (href, _, _, current, filled, _) = funnel(msgOf(doc, "j1 on one")).get
        current must beFalse
        filled must beFalse
        msgOf(doc, "j1 on one").select("a.wlx-comment-filter-link i.bi-funnel").size === 1
        href must startWith(s"/comments/round/${f.round.getId}/user/0/page/1?")
        forall(Seq(s"commenter=${f.juror1.getId}", "scope=contest", "sort=latest-comment"))(href must contain(_))
        href === menuHref(doc, "Juror 1")
        rows(page(app, f.organizer, href)) === Seq(7L, 1L, 5L)
        // every funnel the same link as its author's menu item, none current; no chip
        forall(Seq("j2 on seven" -> EvilName, "pre on three" -> "Pre", "org on one" -> "Org")) { case (body, name) =>
          funnel(msgOf(doc, body)).map(l => (l._1, l._4)) must_== Some((menuHref(doc, name), false))
        }
        doc.select(".wlx-comment-filter-link.active, .bi-funnel-fill, .wlx-comment-filter-link[aria-current]").size === 0
        doc.select("#comments-report-commenter, .wlx-commenter-chip").size === 0
        // the author's name stays text
        msgOf(doc, "j1 on one").select(".wlx-comment-meta strong a").size === 0
        msgOf(doc, "j1 on one").selectFirst(".wlx-comment-meta strong").text === "Juror 1"
        // the pre-screener's gallery is the earlier round's, where they are a juror
        links(msgOf(doc, "pre on three")).filter(_._1 == "gallery").map(l => l._2 -> l._3) === Seq(
          s"/gallery/round/${f.earlier.getId}/user/${f.prescreener.getId}/page/1" -> "Gallery of juror Pre in round Pre-screening")
        // an organizer who commented: the funnel alone; a comment without an account: none
        links(msgOf(doc, "org on one")).map(l => (l._1, l._3)) === Seq("filter" -> "Show only Org's comments")
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

    "show the filter in the Comments by menu, their comments first and the others folded" in {
      testDbApp { app =>
        val f = fixture()
        // the organizer's second comment on file 1: three comments on two files, so the menu's
        // count (comments, not files) tells them apart
        CommentJdbc.create(f.organizer.getId, "Org", f.round.getId, Some(f.contest.getId), 1, "org again on one")
        val doc = page(app, f.organizer, path(f, 0, s"?scope=contest&filter=selected&commenter=${f.juror1.getId}"))
        // the selected files (1, 2, 5, 7) with a comment of juror 1's
        rows(doc) === Seq(7L, 1L, 5L)
        doc.selectFirst("#comments-report-count").text === "· 3 files, 3 comments by Juror 1"
        // the Comments menu's "Comments by" section (no menu of its own: the maintainer's decision
        // of 2026-11-15), after a divider and its header: the author current; the button naming
        // them after its words (cut short if long, whole in its title)
        doc.select("#commenters-menu").size === 0
        doc.select("#comments-menu .dropdown-header").asScala.map(_.text) === Seq("Comments by")
        doc.select("#comments-menu .dropdown-header").first.parent.previousElementSibling.select("hr.dropdown-divider").size === 1
        menuButton(doc) === "Comments list · Juror 1"
        doc.selectFirst("#comments-menu > button").attr("title") === "Comments list · Juror 1"
        doc.select("#comments-menu > button .wlx-comments-by-name.wlx-truncate").text === "Juror 1"
        doc.select("#comments-menu > button > i.bi[aria-hidden=true]").size === 1
        val items = menu(doc)
        items.filter(_._4).map(_._1) === Seq("Juror 1")
        doc.select("#comments-menu .wlx-comments-by-item.active").size === 1
        // a group named by its header; its current item aria-current="true", the menu's own list
        // item the one "page" (one page, one current page)
        doc.select("#comments-menu [role=group][aria-labelledby=comments-by-header] > ul > li > .wlx-comments-by-item").size === items.size
        doc.select("#comments-menu [aria-current=page]").asScala.map(_.text) === Seq("Recent comments list")
        doc.select("#comments-menu .wlx-comments-by-item[aria-current]").asScala.map(_.attr("aria-current")) === Seq("true")
        // each item's text as read: everyone's the words and the count; a person's (a name may end
        // in digits) the name, a comma and their comments in words ("Juror 1, 3 comments", not
        // "Juror 13"), shown as a pill of its own (snippets/count `spoken`)
        def read(e: org.jsoup.nodes.Element) = { val c = e.clone(); c.select("[aria-hidden=true]").remove(); c.text }
        doc.select("#comments-menu .wlx-comments-by-item").asScala.map(read).toSeq ===
          items.zipWithIndex.map { case ((name, n, _, _), i) =>
            if (i == 0) s"$name $n" else s"$name, $n ${if (n == 1) "comment" else "comments"}" }
        items.find(_._1 == "Org").map(_._2) === Some(3)
        doc.select("#comments-menu .wlx-comments-by-item").asScala.drop(1).forall(_.select("> .wlx-menu-count.wlx-count-pill").size == 1) must beTrue
        // the menu's own states: the list current, as before
        NavMenus.comments(doc).map(i => (i.name, i.active)) ===
          Seq(("All files", false), ("Only with comments", false), ("Recent comments list", true))
        // everyone's: the filter removed, the scope, votes choice and order kept
        val everyone = items.head
        everyone._1 === "Everyone"
        everyone._3 must not(contain("commenter="))
        forall(Seq("scope=contest", "filter=selected", "sort=latest-comment"))(everyone._3 must contain(_))
        // the latest comment first: the organizer's second on file 1 (written above), then 5 and 7
        rows(page(app, f.organizer, everyone._3)) === Seq(1L, 5L, 7L)
        // juror 1's comments shown, the others folded under them, each in the order written
        shown(doc.getElementById("7")) === ((Seq("j1 on seven"), Seq("j2 on seven")))
        shown(doc.getElementById("5")) === ((Seq("j1 on five"), Seq("pre on five", "org on five")))
        doc.getElementById("5").selectFirst("details.wlx-comments-others summary").text === "2 other comments on this file"
        doc.getElementById("5").select("details[open]").size === 0
        // no chip: the menu and its button show the filter
        doc.select("#comments-report-commenter, .wlx-commenter-chip").size === 0
        // juror 1's funnels: filled and current, named for what they do, linking the list without
        // the filter, as the menu's "Everyone" (its scope, votes choice and order kept)
        val j1Funnels = Seq("j1 on seven", "j1 on one", "j1 on five").map(b => funnel(msgOf(doc, b)).get)
        forall(j1Funnels) { case (href, title, label, current, filled, text) =>
          (current must beTrue) and (filled must beTrue) and (text === "") and
            (title === "Show all authors' comments") and (label === title) and (href === everyone._3)
        }
        doc.select("a.wlx-comment-filter-link.active").size === 3
        doc.select("a.wlx-comment-filter-link[aria-current=true]").size === 3
        // the menu agrees: juror 1's item is the current one, "Everyone" the funnels' link
        items.find(_._4).map(_._1) === Some("Juror 1")
        // another author's funnel (folded under theirs) switches to them, as their menu item (named
        // by the comment's own signature, beside it; the menu by their latest comment's)
        funnel(msgOf(doc, "j2 on seven")).map(l => (l._1, l._2, l._4, l._5)) ===
          Some((menuHref(doc, EvilName), "Show only Juror 2's comments", false, false))
        funnel(msgOf(doc, "org on five")).map(_._1) === Some(menuHref(doc, "Org"))
        menu(page(app, f.organizer, funnel(msgOf(doc, "j2 on seven")).get._1)).find(_._4).map(_._1) === Some(EvilName)
        // the order and scope menus, the CSV, the region menu and the files' large views keep it
        forall(Seq("#comments-report-order a", "#comments-report-scope a", "#comments-report-csv",
          "article.wlx-comments-row h2 a")) { sel =>
          forall(doc.select(sel).asScala.map(_.attr("href")))(_ must contain(s"commenter=${f.juror1.getId}"))
        }
        // without the filter: everyone current, nothing folded
        val plain = page(app, f.organizer, path(f, 0, "?scope=contest"))
        // on everyone's: the button's words alone
        menuButton(plain) must startWith("Comments list")
        plain.select("#comments-menu > button .wlx-comments-by-name").size === 0
        // the comments list's results bar marked, for its tighter compact form (main.css), the gallery's not
        plain.select("nav.navbar.wlx-nav-results.wlx-nav-comments").size === 1
        page(app, f.organizer, s"/gallery/round/${f.round.getId}/user/0/page/1").select("nav.navbar.wlx-nav-comments").size === 0
        menu(plain).filter(_._4).map(_._1) === Seq("Everyone")
        plain.select("details.wlx-comments-others").size === 0
        // unfiltered: no funnel current
        plain.select("a.wlx-comment-filter-link.active, a.wlx-comment-filter-link[aria-current], .bi-funnel-fill").size === 0
      }
    }

    "count all the report's comments as its authors' together, and one author's as theirs" in {
      testDbApp { _ =>
        val f = fixture()
        val lists = Seq(report(f, None), report(f, None, scope = CommentScope.AllRounds),
          report(f, None, scope = CommentScope.AllRounds, filter = VoteFilter.Rate(1)), report(f, None, filter = VoteFilter.Rate(-1)),
          report(f, None, asUserId = f.juror2.getId), report(f, None, scope = CommentScope.AllRounds, region = Some("80")))
        forall(lists) { l =>
          val authors = l.commentAuthors()
          (authors.map(_._2).sum aka s"${l.query}" must_== l.commentCount()) and
            forall(authors.filter(_._1 > 0)) { case (id, n, _) => l.withCommenter(Some(id)).commentCount() must_== n }
        }
      }
    }

    "list every author in the menu with the number the list filtered by them counts, the most first" in {
      testDbApp { app =>
        val f = fixture()
        val urls = Seq(path(f, 0, ""), path(f, 0, "?scope=contest"), path(f, 0, "?scope=contest&filter=selected"),
          path(f, 0, "?filter=rejected"), path(f, f.juror2.getId, ""),
          s"/comments/round/${f.round.getId}/user/0/region/80/page/1?scope=contest")
        forall(urls) { url =>
          val doc = page(app, f.organizer, url)
          val items = menu(doc)
          // everyone's: all the report's comments, a comment without an account included
          (items.head._2 aka s"$url everyone" must_== headingComments(doc)) and
            // each author: what the list filtered by them counts and shows, the most first, then by name
            forall(items.tail) { case (name, n, href, _) =>
              val filtered = page(app, f.organizer, href)
              (headingComments(filtered) aka s"$url $name" must_== n) and
                (unfolded(filtered) aka s"$url $name shown" must_== n) and
                (menu(filtered).find(_._4).map(_._1) must_== Some(name))
            } and
            (items.tail.map(i => (-i._2, i._1.toLowerCase)) must_== items.tail.map(i => (-i._2, i._1.toLowerCase)).sorted)
        }
        // this round's: juror 1 (3), juror 2 (2, under their latest comment's name), the organizer,
        // who has no files of their own (2); not the pre-screener (theirs are the earlier
        // round's); the comment without an account in everyone's only
        menu(page(app, f.organizer, path(f, 0, ""))).map(i => (i._1, i._2)) ===
          Seq("Everyone" -> 8, "Juror 1" -> 3, EvilName -> 2, "Org" -> 2)
        // all the contest's: the pre-screener too
        menu(page(app, f.organizer, path(f, 0, "?scope=contest"))).map(i => (i._1, i._2)) ===
          Seq("Everyone" -> 10, "Juror 1" -> 3, EvilName -> 2, "Org" -> 2, "Pre" -> 2)
        // juror 2's list (files 5-8): every author's comments on them
        menu(page(app, f.organizer, path(f, f.juror2.getId, ""))).map(i => (i._1, i._2)) ===
          Seq("Everyone" -> 5, "Juror 1" -> 2, EvilName -> 1, "Org" -> 1)
      }
    }

    "escape the author's name in the menu and the links' titles" in {
      testDbApp { app =>
        val f = fixture()
        val html = contentAsString(get(app, f.organizer, path(f, 0, s"?commenter=${f.juror2.getId}")))
        html must not(contain("<b>x</b>"))
        val doc = Jsoup.parse(html)
        doc.select("#comments-menu b").size === 0
        // juror 2's latest comment is signed with it
        menuButton(doc) === s"Comments list · $EvilName"
        doc.selectFirst("#comments-menu > button").attr("title") === s"Comments list · $EvilName"
        menu(doc).filter(_._4).map(_._1) === Seq(EvilName)
        val evil = msgOf(page(app, f.organizer, path(f, 0, "")), "j2 on four")
        evil.selectFirst("a.wlx-comment-gallery-link").attr("title") === s"Gallery of juror $EvilName in round Round 2"
        funnel(evil).map(l => (l._2, l._3)) === Some((s"Show only $EvilName's comments", s"Show only $EvilName's comments"))
        evil.select(".wlx-comment-meta b").size === 0
      }
    }

    "say in the page's language what the menu and the icons do" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.organizer, path(f, 0, s"?commenter=${f.juror1.getId}"), "Accept-Language" -> "uk")
        menuButton(doc) === "Список коментарів · Juror 1"
        doc.select("#comments-menu .dropdown-header").text === "Коментарі від"
        menu(doc).head._1 === "Усіх"
        menuButton(page(app, f.organizer, path(f, 0, ""), "Accept-Language" -> "uk")) must startWith("Список коментарів")
        doc.selectFirst("#comments-report-count").text === "· 3 файли, 3 коментарі, автор: Juror 1"
        val msg = msgOf(doc, "j2 on seven")
        links(msg).map(l => (l._3, l._4, l._5)) === Seq(
          ("Показати лише коментарі автора Juror 2", "Показати лише коментарі автора Juror 2", ""),
          ("Галерея члена журі Juror 2 у раунді Round 2", "Галерея члена журі Juror 2 у раунді Round 2", ""))
        funnel(msgOf(doc, "j1 on seven")).map(l => (l._2, l._3)) ===
          Some(("Показати коментарі всіх авторів", "Показати коментарі всіх авторів"))
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
        // still named in the menu, current, with none here
        menuButton(pre) === "Comments list · Pre"
        menu(pre).filter(_._4).map(i => (i._1, i._2)) === Seq("Pre" -> 0)
        // an account with no comment in the contest: never named from the users table
        val unknown = page(app, f.organizer, path(f, 0, s"?scope=contest&commenter=${f.organizer.getId + 1000}"))
        menuButton(unknown) === s"Comments list · account #${f.organizer.getId + 1000}"
        unknown.selectFirst("#comments-report-empty").text must contain("in this contest")
        // a real account of contest B, whose comments are all B's: not named from the users table
        val other = userDao.create(User("Other Person", "other@example.com", None, Set("jury"),
          contestId = Some(f.roundB.contestId)))
        CommentJdbc.create(other.getId, "Other Person", f.roundB.getId, Some(f.roundB.contestId), 2, "only in B")
        val html = contentAsString(get(app, f.organizer, path(f, 0, s"?scope=contest&commenter=${other.getId}")))
        menuButton(Jsoup.parse(html)) === s"Comments list · account #${other.getId}"
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

    "be the organisers': refused to a juror with all the jurors' report and the CSV, ignored in their own report, gallery and large view" in {
      testDbApp { app =>
        val f = fixture()
        forall(Seq(path(f, 0, s"?commenter=${f.juror2.getId}"),
          s"/comments/round/${f.round.getId}/user/${f.juror1.getId}/region/all/csv?commenter=${f.juror1.getId}")) { p =>
          val result = get(app, f.juror1, p)
          status(result) aka p must_== FORBIDDEN
          contentAsString(result) must not(contain("j2 on seven"))
        }
        // their own report: the filter ignored, no "Comments by" menu, no filter links
        val own = page(app, f.juror1, path(f, f.juror1.getId, s"?commenter=${f.juror1.getId}"))
        own.select("#commenters-menu, #comments-by-header, .wlx-comments-by-item, .wlx-comments-by-name, #comments-report-commenter, .wlx-comment-filter-link, .bi-funnel, .bi-funnel-fill, a[href*=commenter=]").size === 0
        // nor without the parameter: jurors never get funnels
        val ownPlain = page(app, f.juror1, path(f, f.juror1.getId, ""))
        ownPlain.select(".wlx-comment-author-link").size === 0
        ownPlain.select(".wlx-comment-filter-link, .bi-funnel, .bi-funnel-fill, a[href*=commenter=]").size === 0
        val gallery = page(app, f.juror1, s"/gallery/round/${f.round.getId}/user/${f.juror1.getId}/page/1?commenter=${f.juror2.getId}")
        gallery.select("a[href*=commenter=]").size === 0
        val large = page(app, f.juror1,
          s"/large/round/${f.round.getId}/user/${f.juror1.getId}/pageid/1?module=comments&commenter=${f.juror2.getId}")
        large.select("a[href*=commenter=]").size === 0
        large.select("a[href*=/comments/]").asScala.forall(
          _.attr("href").startsWith(s"/comments/round/${f.round.getId}/user/${f.juror1.getId}/")) must beTrue
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
