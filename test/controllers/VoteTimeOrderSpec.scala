package controllers

import db.scalikejdbc._
import db.scalikejdbc.rewrite.ImageDbNew.Limit
import org.intracer.wmua.{ContestJury, Image, Vote}
import org.jsoup.Jsoup
import org.specs2.matcher.ThrownExpectations
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._

import java.time.{ZoneId, ZonedDateTime}
import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** A juror's gallery by vote time ([[ListOrder.LatestVote]], [[ListOrder.OldestVote]], V97's
  * `vote.voted_at`): the timed votes by their time, the untimed ones (voted before V97) older than
  * any timed one, the unrated last, ties in file order; the same order on every page, in the
  * large view's places and through the router; the order menu's entries per tab. Thrown
  * expectations: the helpers' status checks count.
  */
class VoteTimeOrderSpec extends PlaySpecification with ThrownExpectations with PlayTestDb {

  sequential

  private case class Fixture(contest: ContestJury, juror: User, round: Round)

  private def time(seconds: Int) = Some(ZonedDateTime.of(2026, 11, 1, 10, 0, seconds, 250000000, ZoneId.systemDefault()))

  /** Files 1..8 in file order (monument ids ascending), the juror's votes:
    * 1 timed +5 s, 2 unrated, 3 timed +1 s, 4 untimed, 5 timed +5 s (a tie with 1), 6 unrated,
    * 7 untimed, 8 timed +3 s. `rate(i)` is file i's rate.
    */
  private def fixture(rates: Rates, rate: Int => Int): Fixture = {
    val contest = contestDao.create(None, "Wiki Loves Monuments", 2026, "Ukraine")
    val juror = userDao.create(User("Juror", "juror@example.com", None, Set("jury"), contestId = contest.id))
    val round = roundDao.create(Round(None, 1, None, contest.getId, rates = rates, active = true))
    round.addUsers(Seq(RoundUser(round.getId, juror.getId, "jury", active = true)))
    val images = (1 to 8).map(i => Image(i.toLong, s"File:Image$i.jpg", None, None, 640, 480, Some(f"80-361-$i%04d")))
    imageDao.batchInsert(images)
    val times = Map(1 -> time(5), 3 -> time(1), 5 -> time(5), 8 -> time(3))
    selectionDao.batchInsert(images.map(image =>
      Vote(image, juror, round, rate(image.pageId.toInt)).copy(votedAt = times.get(image.pageId.toInt).flatten)))
    // the untimed votes: rated before V97
    DB.autoCommit { implicit s =>
      sql"UPDATE vote SET voted_at = NULL WHERE round_id = ${round.getId} AND page_id IN (4, 7)".update()
    }
    Fixture(contest, juror, round)
  }

  private val starRates: Int => Int = Map(1 -> 3, 2 -> 0, 3 -> 5, 4 -> 2, 5 -> 4, 6 -> 0, 7 -> 1, 8 -> 5)

  /** Yes: 1, 3, 4, 8; no: 5, 7; unrated: 2, 6. */
  private val binaryRates: Int => Int = Map(1 -> 1, 2 -> 0, 3 -> 1, 4 -> 1, 5 -> -1, 6 -> 0, 7 -> -1, 8 -> 1)

  private def list(f: Fixture, order: ListOrder, filter: VoteFilter = VoteFilter.All) =
    RoundList(f.round, f.juror.getId, filter = filter, order = order)

  private def ids(l: RoundList): Seq[Long] = l.query.list().map(_.pageId)

  /** The list read a page of 3 at a time, as the gallery's pager reads it. */
  private def paged(l: RoundList): Seq[Long] =
    (0 until 3).flatMap(p => l.query.copy(limit = Some(Limit(Some(3), Some(p * 3)))).list().map(_.pageId))

  "a juror's files by vote time" should {

    "be the latest first, the untimed after the timed, the unrated last, ties in file order" in {
      testDbApp { _ =>
        val f = fixture(Round.ratesById(5), starRates)
        val expected = Seq(1L, 5L, 8L, 3L, 4L, 7L, 2L, 6L)
        val l = list(f, ListOrder.LatestVote)
        (ids(l) === expected) and (paged(l) === expected) and
          (expected.map(l.position) === expected.indices.map(Some(_))) and
          (expected.flatMap(p => l.around(p).map(_.file.pageId)) === expected)
      }
    }

    "be the oldest first: the untimed first, the unrated still last" in {
      testDbApp { _ =>
        val f = fixture(Round.ratesById(5), starRates)
        val expected = Seq(4L, 7L, 3L, 8L, 1L, 5L, 2L, 6L)
        val l = list(f, ListOrder.OldestVote)
        (ids(l) === expected) and (paged(l) === expected) and
          (expected.map(l.position) === expected.indices.map(Some(_)))
      }
    }

    "order a rated round's rated tab and a yes/no round's selected and rejected tabs the same way" in {
      testDbApp { _ =>
        val rated = fixture(Round.ratesById(5), starRates)
        val ratedTab = ids(list(rated, ListOrder.LatestVote, VoteFilter.Rated))
        SharedTestDb.truncateAll()
        val f = fixture(Round.binaryRound, binaryRates)
        (ratedTab === Seq(1L, 5L, 8L, 3L, 4L, 7L)) and
          (ids(list(f, ListOrder.LatestVote, VoteFilter.Rate(1))) === Seq(1L, 8L, 3L, 4L)) and
          (ids(list(f, ListOrder.OldestVote, VoteFilter.Rate(1))) === Seq(4L, 3L, 8L, 1L)) and
          (ids(list(f, ListOrder.LatestVote, VoteFilter.Rate(-1))) === Seq(5L, 7L)) and
          (ids(list(f, ListOrder.OldestVote, VoteFilter.Rate(-1))) === Seq(7L, 5L)) and
          // the unrated tab: file order whatever the order
          (ids(list(f, ListOrder.LatestVote, VoteFilter.Unrated)) === Seq(2L, 6L)) and
          (ids(list(f, ListOrder.LatestVote)) === Seq(1L, 5L, 8L, 3L, 4L, 7L, 2L, 6L))
      }
    }

    "move a file voted on now to the top of the latest first" in {
      testDbApp { _ =>
        val f = fixture(Round.ratesById(5), starRates)
        val l = list(f, ListOrder.LatestVote)
        val before = l.position(7L).get
        VoteJdbc.rate(7L, f.juror.getId, f.round.getId, 4)
        // the same rate again keeps its time and place
        VoteJdbc.rate(1L, f.juror.getId, f.round.getId, 3)
        (ids(l).take(3) === Seq(7L, 1L, 5L)) and
          (l.voteMoved(7L, before, 4) === Some(MovedVote(7L, out = false, 4))) and
          (l.voteMoved(1L, 1, 3) must beNone)
      }
    }
  }

  "a juror's gallery page" should {

    def get(path: String, f: Fixture)(implicit app: Application): Future[Result] =
      route(app, FakeRequest(GET, path).withSession(Secured.UserName -> f.juror.email)
        .withHeaders(ACCEPT -> "text/html").withCSRFToken).get

    def page(path: String, f: Fixture)(implicit app: Application): String = {
      val result = get(path, f)
      status(result) === OK
      contentAsString(result)
    }

    def tiles(page: String): List[Long] =
      """(?:data-pageId|class="gallerybox" id)="(\d+)"""".r.findAllMatchIn(page).map(_.group(1).toLong).toList

    /** The order menu's entries, and the one shown chosen; or the fixed text in its place. */
    def menu(html: String): (List[String], String) = {
      val nav = Jsoup.parse(html).select("nav")
      val items = nav.select("a.dropdown-item[href*=sort=]").asScala.filter(_.attr("href").contains("/gallery/"))
        .map(_.text).toList.filter(t => Set("In file order", "By my rating", "By rating", "Newest votes first", "Oldest votes first")(t))
      val active = nav.select("a.dropdown-item.active[href*=sort=]").asScala.map(_.text)
        .find(t => items.contains(t)).getOrElse(nav.select("span.gallery-order-fixed").text)
      (items, active)
    }

    def gallery(f: Fixture, query: String) = s"/gallery/round/${f.round.getId}/user/${f.juror.getId}/page/1$query"

    "list the files by vote time, and offer the orders each tab has" in {
      testDbApp { implicit app =>
        val f = fixture(Round.binaryRound, binaryRates)
        val all = Seq("In file order", "By my rating", "Newest votes first", "Oldest votes first")
        val oneRate = Seq("In file order", "Newest votes first", "Oldest votes first")
        (tiles(page(gallery(f, "?sort=latest-vote"), f)) === List(1L, 5L, 8L, 3L, 4L, 7L, 2L, 6L)) and
          (tiles(page(gallery(f, "?sort=oldest-vote"), f)) === List(4L, 7L, 3L, 8L, 1L, 5L, 2L, 6L)) and
          (menu(page(gallery(f, "?sort=latest-vote"), f)) === ((all, "Newest votes first"))) and
          (menu(page(gallery(f, "?filter=selected&sort=oldest-vote"), f)) === ((oneRate, "Oldest votes first"))) and
          (menu(page(gallery(f, "?filter=rejected&sort=rate"), f)) === ((oneRate, "In file order"))) and
          (menu(page(gallery(f, "?filter=unrated&sort=latest-vote"), f)) === ((Nil, "In file order"))) and
          // the older values still work
          (tiles(page(gallery(f, "?sort=rate"), f)).take(4) === List(1L, 3L, 4L, 8L)) and
          (menu(page(gallery(f, "?sort=file"), f)) === ((all, "In file order")))
      }
    }

    "remember the order by vote time" in {
      testDbApp { implicit app =>
        val f = fixture(Round.ratesById(5), starRates)
        cookies(get(gallery(f, "?sort=latest-vote"), f)).get(RoundList.SortCookie).map(_.value) === Some("latest-vote")
      }
    }

    /** Another juror of the round, voting on every file at other times (2 and 6 their latest), and
      * an organiser of the contest.
      */
    def others(f: Fixture): (User, User) = {
      val other = userDao.create(User("Other", "other@example.com", None, Set("jury"), contestId = f.contest.id))
      f.round.addUsers(Seq(RoundUser(f.round.getId, other.getId, "jury", active = true)))
      selectionDao.batchInsert((1 to 8).map(p =>
        Vote(p.toLong, other.getId, f.round.getId, if (p % 2 == 0) 1 else -1,
          votedAt = time(if (p == 2) 59 else if (p == 6) 58 else p))))
      val organizer = userDao.create(User("Organizer", "org@example.com", None, Set("organizer"), contestId = f.contest.id))
      (other, organizer)
    }

    def getAs(path: String, viewer: User)(implicit app: Application): Future[Result] =
      route(app, FakeRequest(GET, path).withSession(Secured.UserName -> viewer.email)
        .withHeaders(ACCEPT -> "text/html").withCSRFToken).get

    "order one juror's votes viewed by an organiser by that juror's vote times, with By rating" in {
      testDbApp { implicit app =>
        val f = fixture(Round.binaryRound, binaryRates)
        val (_, organizer) = others(f)
        def html(query: String) = {
          val result = getAs(gallery(f, query), organizer)
          status(result) === OK
          contentAsString(result)
        }
        val all = List("In file order", "By rating", "Newest votes first", "Oldest votes first")
        val oneRate = List("In file order", "Newest votes first", "Oldest votes first")
        (tiles(html("?sort=latest-vote")) === List(1L, 5L, 8L, 3L, 4L, 7L, 2L, 6L)) and
          (tiles(html("?sort=oldest-vote")) === List(4L, 7L, 3L, 8L, 1L, 5L, 2L, 6L)) and
          (menu(html("?sort=latest-vote")) === ((all, "Newest votes first"))) and
          (menu(html("?sort=rate")) === ((all, "By rating"))) and
          (html("?sort=rate") must not(contain("By my rating"))) and
          (menu(html("?filter=selected&sort=oldest-vote")) === ((oneRate, "Oldest votes first"))) and
          (tiles(html("?filter=selected&sort=oldest-vote")) === List(4L, 3L, 8L, 1L)) and
          (menu(html("?filter=unrated&sort=latest-vote")) === ((Nil, "In file order"))) and
          // the menu's links stay in that juror's list
          (Jsoup.parse(html("?sort=file")).select("a.dropdown-item[href*=sort=latest-vote]").asScala.map(_.attr("href"))
            .filter(_.startsWith("/gallery/")) === Seq(gallery(f, "?sort=latest-vote")))
      }
    }

    "not let a juror see another juror's votes by naming an order" in {
      testDbApp { implicit app =>
        val f = fixture(Round.binaryRound, binaryRates)
        val (other, _) = others(f)
        val path = s"/gallery/round/${f.round.getId}/user/${other.getId}/page/1"
        val plain = getAs(path, f.juror)
        val ordered = getAs(path + "?sort=latest-vote", f.juror)
        (status(plain) must not(beEqualTo(OK))) and (status(ordered) === status(plain)) and
          (contentAsString(ordered) must not(contain("gallerybox")))
      }
    }

    "refuse an organiser of another contest, and a non-juror of the round, as without the order" in {
      testDbApp { implicit app =>
        val f = fixture(Round.binaryRound, binaryRates)
        val (_, organizer) = others(f)
        val elsewhere = contestDao.create(None, "Wiki Loves Earth", 2026, "Ukraine")
        val stranger = userDao.create(User("Stranger", "stranger@example.com", None, Set("organizer"), contestId = elsewhere.id))
        // a juror of the contest, not of the round
        val outsider = userDao.create(User("Outsider", "outsider@example.com", None, Set("jury"), contestId = f.contest.id))
        def both(viewer: User, asUser: Long) = {
          val path = s"/gallery/round/${f.round.getId}/user/$asUser/page/1"
          val plain = getAs(path, viewer)
          val ordered = getAs(path + "?sort=latest-vote", viewer)
          (status(plain), status(ordered), contentAsString(ordered).contains("gallerybox"))
        }
        val (strangerPlain, strangerOrdered, strangerFiles) = both(stranger, f.juror.getId)
        val (outsiderPlain, outsiderOrdered, outsiderFiles) = both(organizer, outsider.getId)
        val (selfPlain, selfOrdered, selfFiles) = both(f.juror, outsider.getId)
        (strangerPlain must not(beEqualTo(OK))) and (strangerOrdered === strangerPlain) and (strangerFiles must beFalse) and
          (outsiderPlain === NOT_FOUND) and (outsiderOrdered === outsiderPlain) and (outsiderFiles must beFalse) and
          (selfPlain must not(beEqualTo(OK))) and (selfOrdered === selfPlain) and (selfFiles must beFalse)
      }
    }

    "leave all the jurors' votes together without an order by vote time" in {
      testDbApp { implicit app =>
        val f = fixture(Round.binaryRound, binaryRates)
        val (_, organizer) = others(f)
        def html(query: String) = {
          val result = getAs(s"/byrate/round/${f.round.getId}/user/0/page/1$query", organizer)
          status(result) === OK
          contentAsString(result)
        }
        val byTime = html("?sort=latest-vote")
        (tiles(byTime) === tiles(html(""))) and
          (byTime must not(contain("Newest votes first"))) and (byTime must not(contain("By rating<")))
      }
    }

    def iso(seconds: Int): String = time(seconds).get.toInstant.toString

    "show the vote times on the tiles only in an order by vote time, none recorded said so" in {
      testDbApp { implicit app =>
        val f = fixture(Round.binaryRound, binaryRates)
        val byTime = page(gallery(f, "?sort=latest-vote"), f)
        def votedAt(html: String) = """data-voted-at="([^"]*)"""".r.findAllMatchIn(html).map(_.group(1)).toList
        // latest first: 1, 5, 8, 3 timed, 4 and 7 untimed, the unrated 2 and 6 none (every tile has
        // the attribute, so an in-page vote can show its time)
        (votedAt(byTime) === List(iso(5), iso(5), iso(3), iso(1), "", "", "", "")) and
          ("""data-voted-none="Time not recorded"""".r.findAllMatchIn(byTime).size === 2) and
          (votedAt(page(gallery(f, "?sort=file"), f)) must beEmpty) and
          (votedAt(page(gallery(f, "?sort=rate"), f)) must beEmpty)
      }
    }

    "show one juror's vote times to an organiser in the by-rate view by vote time, X's own" in {
      testDbApp { implicit app =>
        val f = fixture(Round.binaryRound, binaryRates)
        val (_, organizer) = others(f)
        def html(query: String) = contentAsString(getAs(s"/byrate/round/${f.round.getId}/user/${f.juror.getId}/page/1$query", organizer))
        def times(h: String) = """<time class="wlx-vote-time" datetime="([^"]*)"""".r.findAllMatchIn(h).map(_.group(1)).toList
        val byTime = html("?sort=oldest-vote")
        (times(byTime) === List(iso(1), iso(3), iso(5), iso(5))) and
          (byTime must contain("Time not recorded")) and
          (times(html("?sort=rate")) must beEmpty) and (html("?sort=rate") must not(contain("Time not recorded")))
      }
    }

    "show the vote time in the large view of one juror's votes in any order, never of all the jurors'" in {
      testDbApp { implicit app =>
        val f = fixture(Round.binaryRound, binaryRates)
        val (_, organizer) = others(f)
        val r = f.round.getId
        def large(viewer: User, asUser: Long, pageId: Long, query: String = "?sort=file") = {
          val result = getAs(s"/large/round/$r/user/$asUser/pageid/$pageId$query", viewer)
          status(result) === OK
          contentAsString(result)
        }
        val own = large(f.juror, f.juror.getId, 1L)
        val ownUntimed = large(f.juror, f.juror.getId, 4L, "?sort=rate")
        val ownUnrated = large(f.juror, f.juror.getId, 2L)
        val asOrganizer = large(organizer, f.juror.getId, 8L, "?sort=latest-vote")
        val all = large(organizer, 0L, 1L, "")
        (own must beMatching(s"""(?s).*Voted\\s*<time class="wlx-vote-time" datetime="${java.util.regex.Pattern.quote(iso(5))}">.*""")) and
          (ownUntimed must contain("""<span class="wlx-vote-time-none text-body-secondary">Time not recorded</span>""")) and
          (ownUnrated must not(contain("wlx-large-vote-time"))) and
          (asOrganizer must contain(s"""<time class="wlx-vote-time" datetime="${iso(3)}">""")) and
          (all must not(contain("wlx-large-vote-time"))) and (all must not(contain("wlx-vote-time")))
      }
    }

    "show one juror's yes/no vote with its time read-only in the large view's side bar, no controls, none in the others" in {
      testDbApp { implicit app =>
        val f = fixture(Round.binaryRound, binaryRates)
        val (_, organizer) = others(f)
        val r = f.round.getId
        def large(viewer: User, asUser: Long, pageId: Long) = {
          val result = getAs(s"/large/round/$r/user/$asUser/pageid/$pageId?sort=file", viewer)
          status(result) === OK
          Jsoup.parse(contentAsString(result))
        }
        /** The side bar's vote block: its mark's class and words, its time, if any. */
        def block(doc: org.jsoup.nodes.Document) =
          Option(doc.selectFirst(".large-side .wlx-large-viewed-vote")).map { b =>
            val mark = b.selectFirst(".wlx-viewed-vote-mark")
            (mark.className, mark.text, Option(b.selectFirst(".wlx-large-vote-time")).map(_.text))
          }
        def controls(doc: org.jsoup.nodes.Document) = doc.select(".large-side [data-vote-url]").size
        val yes = large(organizer, f.juror.getId, 8L)
        val no = large(organizer, f.juror.getId, 5L)
        val untimed = large(organizer, f.juror.getId, 4L)
        val unrated = large(organizer, f.juror.getId, 2L)
        (block(yes) === Some(("wlx-viewed-vote-mark wlx-vote-selected", "Selected", Some(s"· Voted ${iso(3)}")))) and
          (block(no) === Some(("wlx-viewed-vote-mark wlx-vote-rejected", "Rejected", Some(s"· Voted ${iso(5)}")))) and
          (block(untimed) === Some(("wlx-viewed-vote-mark wlx-vote-selected", "Selected", Some("· Time not recorded")))) and
          (block(unrated) === Some(("wlx-viewed-vote-mark text-body-secondary", "Unrated", None))) and
          (forall(Seq(yes, no, untimed, unrated))(doc => controls(doc) === 0)) and
          // whose vote it is: the large view has no navbar juror menu
          (forall(Seq(yes, no, untimed, unrated))(doc => doc.select(".large-side .wlx-viewed-juror").text === "Juror")) and
          (large(f.juror, f.juror.getId, 8L).select(".wlx-viewed-juror").size === 0) and
          (large(organizer, 0L, 8L).select(".wlx-viewed-juror").size === 0) and
          // the juror's own view: their vote buttons and the time after them, as before
          (block(large(f.juror, f.juror.getId, 8L)) must beNone) and
          (controls(large(f.juror, f.juror.getId, 8L)) must be_>(0)) and
          (large(f.juror, f.juror.getId, 8L).select(".large-side .wlx-large-vote-time").text === s"Voted ${iso(3)}") and
          // all the jurors' votes together: no one juror's vote or time
          (block(large(organizer, 0L, 8L)) must beNone) and
          (large(organizer, 0L, 8L).select(".wlx-large-vote-time").size === 0)
      }
    }

    "show one juror's rate read-only with its time in a rated round's large view side bar, the unrated without one" in {
      testDbApp { implicit app =>
        val f = fixture(Round.ratesById(5), starRates)
        val (_, organizer) = others(f)
        def large(pageId: Long) = {
          val result = getAs(s"/large/round/${f.round.getId}/user/${f.juror.getId}/pageid/$pageId?sort=file", organizer)
          status(result) === OK
          Jsoup.parse(contentAsString(result)).selectFirst(".large-side")
        }
        val rated = large(8L)
        val unrated = large(2L)
        (rated.select(".wlx-viewed-juror").text === "Juror") and
          (rated.selectFirst("#rate-number").text === "5") and
          (rated.selectFirst("#rate-image").attr("data-readonly") === "true") and
          (rated.select(".wlx-large-vote-time").text === s"Voted ${iso(3)}") and
          (rated.select("[data-vote-url], [data-vote-prefix]").size === 0) and
          (unrated.selectFirst("#rate-number").text === "–") and
          (unrated.select(".wlx-large-vote-time").size === 0) and {
            // a juror without a name: no empty line for it
            DB.autoCommit { implicit s => sql"UPDATE users SET fullname = '' WHERE id = ${f.juror.getId}".update() }
            large(8L).select(".wlx-viewed-juror").size === 0
          }
      }
    }

    /** #gallery's attribute `name`, and each tile's in file order. */
    def galleryAttr(html: String, name: String): Option[String] =
      Option(Jsoup.parse(html).getElementById("gallery")).map(_.attr(name)).filter(_.nonEmpty)
    def tileAttrs(html: String, name: String): List[String] =
      Jsoup.parse(html).select("#gallery img").asScala.map(_.attr(name)).toList

    "show one juror's yes/no votes read-only to an organiser, never in the all-jurors view" in {
      testDbApp { implicit app =>
        val f = fixture(Round.binaryRound, binaryRates)
        val (_, organizer) = others(f)
        val oneJuror = contentAsString(getAs(gallery(f, "?sort=file"), organizer))
        val all = contentAsString(getAs(s"/gallery/round/${f.round.getId}/user/0/page/1", organizer))
        val own = page(gallery(f, "?sort=file"), f)
        (galleryAttr(oneJuror, "data-view-votes") === Some("""{"binary":true}""")) and
          // the viewed juror's votes on the tiles, in file order: yes, unrated, yes, yes, no, unrated, no, yes
          (tileAttrs(oneJuror, "data-rate") === List("1", "0", "1", "1", "-1", "0", "-1", "1")) and
          // nothing to vote with: the tiles aren't the viewer's
          (galleryAttr(oneJuror, "data-jury") === Some("false")) and
          (galleryAttr(all, "data-view-votes") must beNone) and
          // the juror's own tiles vote, as before
          (galleryAttr(own, "data-view-votes") must beNone) and (galleryAttr(own, "data-jury") === Some("true"))
      }
    }

    "show one juror's stars read-only to an organiser in a rated round, as before (its by-rate list)" in {
      testDbApp { implicit app =>
        val f = fixture(Round.ratesById(5), starRates)
        val (_, organizer) = others(f)
        val oneJuror = contentAsString(getAs(gallery(f, "?sort=file"), organizer))
        val doc = Jsoup.parse(oneJuror)
        // a line of stars per file, static (no juror's own stars to click: wlx-byrate-stars)
        (doc.select("li.gallerybox .rate-stars").size === 8) and
          (doc.select(".wlx-byrate-stars").size === 0) and
          (galleryAttr(oneJuror, "data-view-votes") must beNone)
      }
    }
  }
}
