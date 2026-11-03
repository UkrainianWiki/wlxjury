package controllers

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image, Selection}
import play.api.Application
import play.api.mvc.{Cookie, Result}
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import scala.concurrent.Future

/** A juror's own gallery: where it starts, its order (file order unless the juror chose by
  * rate, remembered in a cookie) and its tabs by vote. Through the router, as the browser opens
  * it.
  */
class GalleryOrderSpec extends PlaySpecification with PlayTestDb {

  sequential

  private case class Fixture(contest: ContestJury, juror: User, round: Round, pageIds: Seq[Long])

  /** A round of `rates` with 20 of the juror's files, unrated, page ids 1..20 in file order. */
  private def fixture(rates: Rates = Round.ratesById(5)): Fixture = {
    val contest = contestDao.create(None, "Wiki Loves Monuments", 2026, "Ukraine")
    val juror = userDao.create(User("Juror", "juror@example.com", None, Set("jury"), contestId = contest.id))
    val round = roundDao.create(Round(None, 1, None, contest.getId, rates = rates, active = true))
    round.addUsers(Seq(RoundUser(round.getId, juror.getId, "jury", active = true)))
    val images = (1 to 20).map(i => Image(i.toLong, s"File:Image$i.jpg", None, None, 640, 480, Some(f"80-361-$i%04d")))
    imageDao.batchInsert(images)
    selectionDao.batchInsert(images.map(image => Selection(image, juror, round, 0)))
    Fixture(contest, juror, round, images.map(_.pageId))
  }

  private def get(path: String, f: Fixture, cookies: Cookie*)(implicit app: Application): Future[Result] =
    route(app, FakeRequest(GET, path).withSession(Secured.UserName -> f.juror.email)
      .withCookies(cookies: _*).withHeaders(ACCEPT -> "text/html").withCSRFToken).get

  private def page(path: String, f: Fixture, cookies: Cookie*)(implicit app: Application): String = {
    val result = get(path, f, cookies: _*)
    status(result) === OK
    contentAsString(result)
  }

  private def tiles(page: String): List[Long] =
    """(?:data-pageId|<h1 id)="(\d+)"""".r.findAllMatchIn(page).map(_.group(1).toLong).toList

  private def gallery(f: Fixture, query: String = "") = s"/gallery/round/${f.round.getId}/user/${f.juror.getId}/page/1$query"

  "a juror" should {

    "start on all their files, in file order" in {
      testDbApp { implicit app =>
        val f = fixture()
        val start = route(app, FakeRequest(GET, "/").withSession(Secured.UserName -> f.juror.email)).get
        redirectLocation(start) === Some(gallery(f, "?sort=file"))
      }
    }

    "page through their files by previous and next, with the files shown of how many" in {
      testDbApp { implicit app =>
        val f = fixture()
        SelectionJdbc.rate(f.pageIds(0), f.juror.getId, f.round.getId, 5)
        val first = page(gallery(f), f)
        first must contain("1–15 of 20")
        first must contain(s"""href="/gallery/round/${f.round.getId}/user/${f.juror.getId}/page/2?sort=file" rel="next"""")
        first must not contain "rel=\"prev\""
        val second = page(gallery(f).replace("/page/1", "/page/2"), f)
        second must contain("16–20 of 20")
        second must contain("rel=\"prev\"")
        second must not contain "rel=\"next\""
        // the Rated tab's list: the files the filter lists, not all the juror's
        page(gallery(f, "?filter=rated"), f) must not contain "id=\"pagenav\""
        page(gallery(f, "?filter=unrated"), f) must contain("1–15 of 19")
      }
    }

    "see a file they rate keep its place, which by rate moved to the top" in {
      testDbApp { implicit app =>
        val f = fixture()
        SelectionJdbc.rate(f.pageIds(9), f.juror.getId, f.round.getId, 5)

        tiles(page(gallery(f), f)) === f.pageIds.take(15).toList
        tiles(page(gallery(f, "?sort=rate"), f)).head === f.pageIds(9)
      }
    }

    "have their chosen order remembered, and kept when the URL names none" in {
      testDbApp { implicit app =>
        val f = fixture()
        SelectionJdbc.rate(f.pageIds(9), f.juror.getId, f.round.getId, 5)

        val chosen = get(gallery(f, "?sort=rate"), f)
        val cookie = cookies(chosen).get(RoundList.SortCookie)
        cookie.map(_.value) === Some("rate")
        cookie.flatMap(_.maxAge) must beSome(be_>(0))

        tiles(page(gallery(f), f, cookie.get)).head === f.pageIds(9)
        // the URL's order wins, and becomes the remembered one
        cookies(get(gallery(f, "?sort=file"), f, cookie.get)).get(RoundList.SortCookie).map(_.value) === Some("file")
        // nothing to remember without a chosen order, or the same one
        cookies(get(gallery(f), f)).get(RoundList.SortCookie) must beNone
        cookies(get(gallery(f, "?sort=rate"), f, cookie.get)).get(RoundList.SortCookie) must beNone
      }
    }

    "have tabs for all, unrated and rated files, and a choice of order, keeping the order" in {
      testDbApp { implicit app =>
        val f = fixture()
        SelectionJdbc.rate(f.pageIds(9), f.juror.getId, f.round.getId, 5)
        val html = page(gallery(f, "?filter=unrated"), f).replace("&amp;", "&")
        val base = s"/gallery/round/${f.round.getId}/user/${f.juror.getId}/page/1"

        html must contain(s"""<a href="$base?sort=file">All (20)</a>""")
        html must contain(s"""<a href="$base?filter=unrated&sort=file">Unrated (19)</a>""")
        html must contain(s"""<a href="$base?filter=rated&sort=file">Rated (1)</a>""")
        // the unrated tab is the page's
        html must beMatching(s"""(?s).*<li role="presentation" class="active">\\s*<a href="\\Q$base?filter=unrated&sort=file\\E">.*""")
        html must contain(s"""href="$base?filter=unrated&sort=rate"""")
      }
    }

    "have a binary round's tabs: all, unrated, selected and rejected" in {
      testDbApp { implicit app =>
        val f = fixture(Round.binaryRound)
        SelectionJdbc.rate(f.pageIds(0), f.juror.getId, f.round.getId, 1)
        SelectionJdbc.rate(f.pageIds(1), f.juror.getId, f.round.getId, -1)
        val html = page(gallery(f), f).replace("&amp;", "&")
        val base = s"/gallery/round/${f.round.getId}/user/${f.juror.getId}/page/1"

        html must contain(s"""<a href="$base?sort=file">All (20)</a>""")
        html must contain(s"""<a href="$base?filter=unrated&sort=file">Unrated (18)</a>""")
        html must contain(s"""<a href="$base?filter=selected&sort=file">Selected (1)</a>""")
        html must contain(s"""<a href="$base?filter=rejected&sort=file">Rejected (1)</a>""")
      }
    }
  }

  "an organiser who has no files in the round" should {
    "see a juror's gallery without tabs or an order of their own" in {
      testDbApp { implicit app =>
        val f = fixture()
        val organizer = userDao.create(User("Organizer", "org@example.com", None, Set("organizer"), contestId = f.contest.id))
        val result = route(app, FakeRequest(GET, gallery(f)).withSession(Secured.UserName -> organizer.email)
          .withHeaders(ACCEPT -> "text/html").withCSRFToken).get
        status(result) === OK
        val html = contentAsString(result)
        html must not(contain("All (0)"))
        html must not(contain("In file order"))
      }
    }
  }

  "a juror's vote in the large view" should {

    def vote(f: Fixture, pageId: Long, rate: Int, query: String)(implicit app: Application): Future[Result] =
      route(app, FakeRequest(POST, s"/large/round/${f.round.getId}/pageid/$pageId/select/$rate$query")
        .withSession(Secured.UserName -> f.juror.email).withCSRFToken).get

    "say nothing in file order, where it moves nothing" in {
      testDbApp { implicit app =>
        val f = fixture()
        val result = vote(f, f.pageIds(9), 4, "?sort=file")
        redirectLocation(result) === Some(s"/large/round/${f.round.getId}/user/${f.juror.getId}/pageid/${f.pageIds(10)}?sort=file")
        flash(result).get(MovedVote.Key) must beNone
      }
    }

    "say by rate that the file moved, the next large view linking it, not as a dialog" in {
      testDbApp { implicit app =>
        val f = fixture()
        val result = vote(f, f.pageIds(9), 4, "?sort=rate")
        val next = redirectLocation(result).get
        next === s"/large/round/${f.round.getId}/user/${f.juror.getId}/pageid/${f.pageIds(10)}?sort=rate"
        val moved = flash(result).get(MovedVote.Key)
        moved === Some(s"${f.pageIds(9)}:moved:4")

        val html = contentAsString(route(app, FakeRequest(GET, next).withSession(Secured.UserName -> f.juror.email)
          .withFlash(MovedVote.Key -> moved.get).withHeaders(ACCEPT -> "text/html").withCSRFToken).get)
        html must contain("id=\"vote-moved\"")
        html must contain("The image you rated (★ 4) moved to its place by rating.")
        html.replace("&amp;", "&") must contain(
          s"""href="/large/round/${f.round.getId}/user/${f.juror.getId}/pageid/${f.pageIds(9)}?sort=rate">View it</a>""")
        html must not(contain("modal"))
      }
    }
  }
}
