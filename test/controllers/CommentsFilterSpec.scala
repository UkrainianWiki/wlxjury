package controllers

import db.scalikejdbc._
import org.intracer.wmua._
import org.jsoup.Jsoup
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** The comments badge and the list's toggle of only the files with comments ([[RoundList.comments]],
  * `comments=1`): the files with a comment in the round's contest (any round's, any author's, as
  * the large view shows them), within what the viewer may already see; the toggle combined with
  * the tabs and kept by every URL of the list; a page's badges from one grouped query.
  */
class CommentsFilterSpec extends PlaySpecification with PlayTestDb {

  sequential

  private case class Fixture(
      contest: ContestJury,
      earlier: Round,
      round: Round,
      juror1: User,
      juror2: User,
      organizer: User
  )

  /** Contest A's binary round with juror 1 on files 1-6 (1, 2 selected; 3, 4 unrated; 5, 6
    * rejected) and juror 2 on files 5-8 (5, 7 selected; 6, 8 unrated), and an earlier round of
    * A. Comments in A: two on file 1 (this round), one on 3 (the earlier round), one on 5 and one
    * on 7 (juror 2's only); one on 2 in another contest, which counts nowhere here.
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
    val images = (1L to 8L).map(id => Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(s"12-345-$id")))
    imageDao.batchInsert(images)
    round.addUsers(Seq(juror1, juror2).map(j => RoundUser(round.getId, j.getId, "jury", active = true)))
    val rates1 = Seq(1, 1, 0, 0, -1, -1)
    val rates2 = Seq(1, 0, 1, 0)
    selectionDao.batchInsert(
      images.take(6).zip(rates1).map { case (i, r) => Vote(i, juror1, round, r) } ++
        images.drop(4).zip(rates2).map { case (i, r) => Vote(i, juror2, round, r) })
    def comment(by: User, r: Round, pageId: Long, text: String, at: String = "2026-11-12T14:05:00+02:00") =
      CommentJdbc.create(by.getId, by.fullname, r.getId, Some(r.contestId), pageId, text, at)
    comment(juror1, round, 1, "first")
    comment(organizer, round, 1, "second")
    comment(organizer, earlier, 3, "from the earlier round", "2016-10-05T12:34:56+03:00[Europe/Kiev]")
    comment(juror2, round, 5, "on five")
    comment(juror2, round, 7, "juror 2's own")
    comment(juror1, roundB, 2, "another contest")
    Fixture(a, earlier, round, juror1, juror2, organizer)
  }

  private def list(f: Fixture, asUserId: Long, filter: VoteFilter = VoteFilter.All, order: ListOrder = ListOrder.FileOrder,
                   view: GalleryView = GalleryView.Gallery) =
    RoundList(f.round, asUserId, filter = filter, order = order, view = view, comments = true)

  private def pageIds(l: RoundList): Seq[Long] = l.files(Pager(1, 0)).map(_.pageId)

  private def get(app: Application, viewer: User, path: String): Future[Result] =
    route(app, FakeRequest(GET, path).withSession(Secured.UserName -> viewer.email).withCSRFToken).get

  "the list of only the files with comments" should {

    "have a juror's own commented files, never another juror's or another contest's" in {
      testDbApp { _ =>
        val f = fixture()
        val own = list(f, f.juror1.getId)
        pageIds(own) === Seq(1L, 3L, 5L)
        own.count() === 3
        // file 7 (juror 2's only) and 2 (commented in another contest) are in no view of juror 1's
        pageIds(list(f, f.juror2.getId)) === Seq(5L, 7L)
      }
    }

    "combine with each tab, counted within it" in {
      testDbApp { _ =>
        val f = fixture()
        def of(filter: VoteFilter) = list(f, f.juror1.getId, filter)
        (pageIds(of(VoteFilter.Rate(1))), pageIds(of(VoteFilter.Unrated)), pageIds(of(VoteFilter.Rate(-1)))) ===
          ((Seq(1L), Seq(3L), Seq(5L)))
        // the toggle's number, on or off: the tab's files with comments, from the juror's counts
        forall(Seq(VoteFilter.All -> 3, VoteFilter.Rate(1) -> 1, VoteFilter.Unrated -> 1, VoteFilter.Rate(-1) -> 1)) {
          case (filter, n) =>
            of(filter).commentedCount() === n
            of(filter).withComments(false).commentedCount() === n
            of(filter).jurorCounts().map(_.total(filter, None)) === Some(n)
        }
        // the tabs' own counts, without the toggle, are unchanged
        list(f, f.juror1.getId).withComments(false).jurorCounts().map(_.rates) ===
          Some(Map(1 -> 2, 0 -> 2, -1 -> 2))
      }
    }

    "count the region nav's files with comments, every region kept" in {
      testDbApp { _ =>
        val f = fixture()
        list(f, f.juror1.getId).jurorCounts().map(_.regions(VoteFilter.All)) === Some(Map("12" -> 3))
        list(f, f.juror1.getId, VoteFilter.Rate(1)).jurorCounts().map(_.regions(VoteFilter.Rate(1))) === Some(Map("12" -> 1))
        list(f, 0).regionCounts() === Map("12" -> 4)
        list(f, 0, VoteFilter.Rate(1)).regionCounts() === Map("12" -> 3)
      }
    }

    "have all the jurors' commented files together, by any juror's matching vote, for the organisers' views" in {
      testDbApp { _ =>
        val f = fixture()
        val all = list(f, 0, order = ListOrder.Binary(BinaryOrder.Default))
        pageIds(all).sorted === Seq(1L, 3L, 5L, 7L)
        all.count() === 4
        all.commentedCount() === 4
        // selected by a juror: 1, 2 (juror 1), 5, 7 (juror 2); of those commented: 1, 5, 7
        pageIds(list(f, 0, VoteFilter.Rate(1))).sorted === Seq(1L, 5L, 7L)
        list(f, 0, VoteFilter.Rate(1)).withComments(false).commentedCount() === 3
        // never from round_image's counts, which have no comments
        all.withCountsRead(true).countsQuery must beNone
      }
    }

    "rank the by-rate view and a file list among the commented files only" in {
      testDbApp { _ =>
        val f = fixture()
        val byRate = list(f, 0, view = GalleryView.ByRate)
        byRate.rankCounts().map(_._2).sum === 4
        val fileList = list(f, 0, view = GalleryView.FileList)
        fileList.count() === 4
        pageIds(fileList).sorted === Seq(1L, 3L, 5L, 7L)
        // a juror's own by-rate view
        pageIds(list(f, f.juror1.getId, order = ListOrder.ByRate, view = GalleryView.ByRate)) === Seq(1L, 3L, 5L)
      }
    }

    "keep a juror's order by vote time, among the commented files" in {
      testDbApp { _ =>
        val f = fixture()
        VoteJdbc.rate(pageId = 5, juryId = f.juror1.getId, roundId = f.round.getId, rate = 1)
        Thread.sleep(1100)
        VoteJdbc.rate(pageId = 1, juryId = f.juror1.getId, roundId = f.round.getId, rate = -1)
        // the latest vote first, the unrated (3) last; 2, 4, 6 never
        pageIds(list(f, f.juror1.getId, order = ListOrder.LatestVote)) === Seq(1L, 5L, 3L)
        pageIds(list(f, f.juror1.getId, order = ListOrder.OldestVote)) === Seq(5L, 1L, 3L)
      }
    }

    "move the large view among the commented files, a file without comments shown in its place" in {
      testDbApp { _ =>
        val f = fixture()
        val own = list(f, f.juror1.getId)
        val hood = own.around(3).get
        hood.files.map(_.pageId) === Seq(1L, 3L, 5L)
        hood.file.pageId === 3L
        hood.next.map(_.pageId) === Some(5L)
        // a file without comments opened with the toggle on (a typed URL): itself among them
        own.around(2).map(h => (h.files.map(_.pageId), h.file.pageId)) === Some((Seq(1L, 2L, 3L, 5L), 2L))
        // all the jurors' (by rank, at an offset)
        list(f, 0, order = ListOrder.Binary(BinaryOrder.Default)).around(5).map(_.files.map(_.pageId).sorted) ===
          Some(Seq(1L, 3L, 5L, 7L))
        // all the jurors' list at a file without comments: itself among them too
        list(f, 0, order = ListOrder.Binary(BinaryOrder.Default)).around(2).map(_.files.map(_.pageId).sorted) ===
          Some(Seq(1L, 2L, 3L, 5L, 7L))
        // another juror's file is not in juror 1's list
        own.around(7) must beNone
      }
    }

    "tell a vote that moves its file, or takes it out of the tab, with the toggle on" in {
      testDbApp { _ =>
        val f = fixture()
        // by rate: 1 (selected), 3 (unrated), 5 (rejected); 5 selected moves before 3
        val byRate = list(f, f.juror1.getId, order = ListOrder.ByRate)
        val before = byRate.around(5).get.position
        before === 2
        VoteJdbc.rate(pageId = 5, juryId = f.juror1.getId, roundId = f.round.getId, rate = 1)
        byRate.voteMoved(5, before, 1) === Some(MovedVote(5, out = false, 1))
        // the selected with comments: 1 and now 5; 1 rejected leaves the tab
        val selected = list(f, f.juror1.getId, VoteFilter.Rate(1))
        val at = selected.around(1).get.position
        VoteJdbc.rate(pageId = 1, juryId = f.juror1.getId, roundId = f.round.getId, rate = -1)
        selected.voteMoved(1, at, -1) === Some(MovedVote(1, out = true, -1))
      }
    }
  }

  "the list's URLs" should {

    "all keep the toggle, and a request's comments=1 turn it on" in {
      testDbApp { _ =>
        val f = fixture()
        val l = list(f, f.juror1.getId, VoteFilter.Rate(1))
        forall(Seq(l.pageUrl(2), l.atUrl(1), l.largeUrl(1), l.voteUrl(1, 1), l.voteQuery(), l.commentUrl(1),
          l.regionsUrl, l.withView(GalleryView.ByRate).pageUrl(1), l.withRegion("12").pageUrl(1),
          l.withOrder(ListOrder.LatestVote).pageUrl(1), l.withFilter(VoteFilter.Unrated).pageUrl(1))) { url =>
          url must contain("comments=1")
        }
        l.withComments(false).largeUrl(1) must not(contain("comments="))
        def of(query: String) =
          RoundList.of(f.round, f.juror1, f.juror1.getId, "all", GalleryView.Gallery)(FakeRequest(GET, "/x" + query)).comments
        (of("?comments=1"), of(""), of("?comments=0"), of("?filter=selected&comments=1")) === ((true, false, false, true))
      }
    }
  }

  "the page's comment badges" should {

    "count each file's comments in the contest, in one query" in {
      testDbApp { _ =>
        val f = fixture()
        CommentJdbc.countsByRoom(f.contest.getId, (1L to 8L)) === Map(1L -> 2, 3L -> 1, 5L -> 1, 7L -> 1)
        CommentJdbc.countsByRoom(f.contest.getId, Nil) === Map.empty
      }
    }
  }

  "a round's gallery with the toggle" should {

    "list a juror's own commented files, the toggle pressed with its number, and badges on the tiles" in {
      testDbApp { app =>
        val f = fixture()
        val page = contentAsString(get(app, f.juror1, s"/gallery/round/${f.round.getId}/user/${f.juror1.getId}/page/1?comments=1"))
        val doc = Jsoup.parse(page)
        doc.select("#gallery img").asScala.map(_.attr("data-pageId").toLong) === Seq(1L, 3L, 5L)
        val toggle = doc.selectFirst("a.wlx-comments-toggle")
        toggle.hasClass("active") must beTrue
        toggle.attr("aria-current") === "true"
        toggle.text must contain("(3)")
        toggle.attr("href") must not(contain("comments="))
        doc.select("#gallery img").asScala.map(i => i.attr("data-pageId") -> i.attr("data-comments")) ===
          Seq("1" -> "2", "3" -> "1", "5" -> "1")
        doc.selectFirst("#gallery img").attr("data-comments-label") === "2 comments"
        // the tabs keep the toggle, with their own counts
        val selected = doc.select("a.nav-link").asScala.find(_.attr("href").contains("filter=selected")).get
        selected.attr("href") must contain("comments=1")
        selected.text must contain("(2)")
      }
    }

    "offer the toggle off with the tab's number of commented files" in {
      testDbApp { app =>
        val f = fixture()
        val doc = Jsoup.parse(contentAsString(
          get(app, f.juror1, s"/gallery/round/${f.round.getId}/user/${f.juror1.getId}/page/1?filter=selected")))
        doc.select("#gallery img").asScala.map(_.attr("data-pageId").toLong) === Seq(1L, 2L)
        val toggle = doc.selectFirst("a.wlx-comments-toggle")
        toggle.hasClass("active") must beFalse
        toggle.text must contain("(1)")
        toggle.attr("href") must contain("comments=1")
        toggle.attr("href") must contain("filter=selected")
      }
    }

    "refuse a juror another juror's list, with the toggle as without [J-27]" in {
      testDbApp { app =>
        val f = fixture()
        val result = get(app, f.juror1, s"/gallery/round/${f.round.getId}/user/${f.juror2.getId}/page/1?comments=1")
        status(result) === FORBIDDEN
        contentAsString(result) must not(contain("File:Image7.jpg"))
        status(get(app, f.juror1, s"/byrate/round/${f.round.getId}/user/0/page/1?comments=1")) === FORBIDDEN
      }
    }

    "show the organisers a juror's and all the jurors' commented files" in {
      testDbApp { app =>
        val f = fixture()
        def ids(path: String) = Jsoup.parse(contentAsString(get(app, f.organizer, path)))
        val juror2 = ids(s"/gallery/round/${f.round.getId}/user/${f.juror2.getId}/page/1?comments=1")
        juror2.select("#gallery img").asScala.map(_.attr("data-pageId").toLong) === Seq(5L, 7L)
        juror2.selectFirst("a.wlx-comments-toggle").text must contain("(2)")
        val all = ids(s"/byrate/round/${f.round.getId}/user/0/page/1?comments=1")
        all.select("li.gallerybox").asScala.map(_.id.toLong).sorted === Seq(1L, 3L, 5L, 7L)
        // the by-rate tiles' badges, their words for screen readers
        all.select("li.gallerybox .wlx-comment-badge .visually-hidden").asScala.map(_.text).sorted ===
          Seq("1 comment", "1 comment", "1 comment", "2 comments")
        all.selectFirst("a.wlx-comments-toggle").text must contain("(4)")
      }
    }
  }

  "the large view with the toggle" should {

    "link its neighbours, strip, vote and comment with the toggle, and show the comments' author, time and round" in {
      testDbApp { app =>
        val f = fixture()
        val doc = Jsoup.parse(contentAsString(
          get(app, f.juror1, s"/large/round/${f.round.getId}/user/${f.juror1.getId}/pageid/3?comments=1")))
        val links = doc.select("a[href*=/large/]").asScala.map(_.attr("href"))
        links must not(beEmpty)
        forall(links)(_ must contain("comments=1"))
        doc.select("a[href*=/pageid/1?]").size must be_>(0)
        doc.select("a[href*=/pageid/5?]").size must be_>(0)
        doc.select("a[href*=/pageid/2?], a[href*=/pageid/4?]").size === 0
        doc.selectFirst("form#addComment").attr("action") must contain("comments=1")
        // the strip's file 5, with its badge
        doc.select(".gallerybox .wlx-comment-badge .visually-hidden").asScala.map(_.text) === Seq("1 comment")
        // file 3's one comment: from the earlier round, its stored time (a zone id) read
        val comment = doc.selectFirst(".large-comments .msg")
        comment.selectFirst(".wlx-comment-meta strong").text === "Org"
        comment.selectFirst("time.wlx-comment-time").attr("datetime") === "2016-10-05T09:34:56Z"
        comment.selectFirst(".wlx-comment-round").text must contain("Pre-screening")
        comment.selectFirst(".wlx-comment-body").text === "from the earlier round"
      }
    }

    "show no round badge on this round's comments" in {
      testDbApp { app =>
        val f = fixture()
        val doc = Jsoup.parse(contentAsString(
          get(app, f.juror1, s"/large/round/${f.round.getId}/user/${f.juror1.getId}/pageid/1")))
        doc.select(".large-comments .msg").asScala.map(_.selectFirst(".wlx-comment-body").text) === Seq("first", "second")
        doc.select(".large-comments .wlx-comment-round").size === 0
        doc.select(".large-comments time.wlx-comment-time").asScala.map(_.attr("datetime")) ===
          Seq("2026-11-12T12:05:00Z", "2026-11-12T12:05:00Z")
      }
    }

    "go back after a comment to the list it was made in: a juror's or all the jurors' for the organisers, the viewer's own for a juror" in {
      testDbApp { app =>
        val f = fixture()
        def commentFrom(viewer: User, l: RoundList, pageId: Long) = {
          val result = route(app, FakeRequest(POST, l.commentUrl(pageId))
            .withSession(Secured.UserName -> viewer.email)
            .withFormUrlEncodedBody("id" -> "0", "text" -> "a comment")
            .withCSRFToken).get
          status(result) === SEE_OTHER
          redirectLocation(result).get
        }
        val fromJuror2 = commentFrom(f.organizer, list(f, f.juror2.getId), 5)
        fromJuror2 must startWith(s"/large/round/${f.round.getId}/user/${f.juror2.getId}/pageid/5?")
        fromJuror2 must contain("comments=1")
        val fromAll = commentFrom(f.organizer, list(f, 0, VoteFilter.Rate(1), ListOrder.Binary(BinaryOrder.Default),
          GalleryView.ByRate), 7)
        fromAll must startWith(s"/large/round/${f.round.getId}/user/0/pageid/7?")
        forall(Seq("module=byrate", "filter=selected", "comments=1"))(p => fromAll must contain(p))
        // a juror naming another juror's list: their comment on their own file is added, and they go
        // back to their own list, never the other's
        val juror = commentFrom(f.juror1, list(f, f.juror2.getId), 5)
        juror must startWith(s"/large/round/${f.round.getId}/user/${f.juror1.getId}/pageid/5?")
        CommentJdbc.findBySubjectAndContest(5, f.contest.getId).count(_.userId == f.juror1.getId) === 1
      }
    }

    "go on to the next commented file after a vote, keeping the toggle" in {
      testDbApp { app =>
        val f = fixture()
        val l = list(f, f.juror1.getId)
        val result = route(app, FakeRequest(POST, l.voteUrl(3, 1))
          .withSession(Secured.UserName -> f.juror1.email).withCSRFToken).get
        status(result) === SEE_OTHER
        val location = redirectLocation(result).get
        location must contain("/pageid/5")
        location must contain("comments=1")
      }
    }
  }
}
