package controllers

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image, Vote}
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
    selectionDao.batchInsert(images.map(image => Vote(image, juror, round, 0)))
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
    """(?:data-pageId|class="gallerybox" id)="(\d+)"""".r.findAllMatchIn(page).map(_.group(1).toLong).toList

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
        VoteJdbc.rate(f.pageIds(0), f.juror.getId, f.round.getId, 5)
        val first = page(gallery(f), f)
        first must contain("1–15 of 20")
        first must contain(s"""href="/gallery/round/${f.round.getId}/user/${f.juror.getId}/page/2?sort=file" rel="next"""")
        first must not contain "rel=\"prev\""
        val second = page(gallery(f).replace("/page/1", "/page/2"), f)
        second must contain("16–20 of 20")
        second must contain("rel=\"prev\"")
        second must not contain "rel=\"next\""
        // the first and the last page, and a page by its number (pager-jump.js)
        val pages = s"/gallery/round/${f.round.getId}/user/${f.juror.getId}/page"
        first must contain(s"""href="$pages/2?sort=file" rel="last"""")
        first must not contain "rel=\"first\""
        first must contain(s"""data-page-url="$pages/{page}?sort=file"""")
        first must contain("""max="2"""")
        first must contain("""style="width: calc(2ch + 14px)"""") // the last page's one digit, and one more
        second must contain(s"""href="$pages/1?sort=file" rel="first"""")
        second must not contain "rel=\"last\""
        // the Rated tab's list: the files the filter lists, not all the juror's
        page(gallery(f, "?filter=rated"), f) must not contain "id=\"pagenav\""
        page(gallery(f, "?filter=unrated"), f) must contain("1–15 of 19")
      }
    }

    "see a file they rate keep its place, which by rate moved to the top" in {
      testDbApp { implicit app =>
        val f = fixture()
        VoteJdbc.rate(f.pageIds(9), f.juror.getId, f.round.getId, 5)

        tiles(page(gallery(f), f)) === f.pageIds.take(15).toList
        tiles(page(gallery(f, "?sort=rate"), f)).head === f.pageIds(9)
      }
    }

    "have their chosen order remembered, and kept when the URL names none" in {
      testDbApp { implicit app =>
        val f = fixture()
        VoteJdbc.rate(f.pageIds(9), f.juror.getId, f.round.getId, 5)

        val chosen = get(gallery(f, "?sort=rate"), f)
        val cookie = cookies(chosen).get(RoundList.SortCookie)
        cookie.map(_.value) === Some("rate")
        cookie.flatMap(_.maxAge) must beSome(be_>(0))
        cookie.map(_.secure) === Some(false) // as the session cookie in the tests

        tiles(page(gallery(f), f, cookie.get)).head === f.pageIds(9)
        // the URL's order wins, and becomes the remembered one
        cookies(get(gallery(f, "?sort=file"), f, cookie.get)).get(RoundList.SortCookie).map(_.value) === Some("file")
        // nothing to remember without a chosen order, or the same one
        cookies(get(gallery(f), f)).get(RoundList.SortCookie) must beNone
        cookies(get(gallery(f, "?sort=rate"), f, cookie.get)).get(RoundList.SortCookie) must beNone
      }
    }

    "have their order remembered in a Secure cookie when the session cookie is (production)" in {
      implicit val cfg: Map[String, String] = Map("play.http.session.secure" -> "true")
      testDbApp { implicit app =>
        // a fresh app with this config: the shared one's truncation doesn't run
        SharedTestDb.truncateAll()
        val f = fixture()
        val cookie = cookies(get(gallery(f, "?sort=rate"), f)).get(RoundList.SortCookie)
        cookie.map(_.value) === Some("rate")
        cookie.map(_.secure) === Some(true)
      }
    }

    "have tabs for all, unrated and rated files, and a choice of order, keeping the order" in {
      testDbApp { implicit app =>
        val f = fixture()
        VoteJdbc.rate(f.pageIds(9), f.juror.getId, f.round.getId, 5)
        val html = page(gallery(f, "?filter=unrated"), f).replace("&amp;", "&")
        val base = s"/gallery/round/${f.round.getId}/user/${f.juror.getId}/page/1"

        html must contain(s"""href="$base?sort=file">All (20)</a>""")
        html must contain(s"""href="$base?filter=unrated&sort=file">Unrated (19)</a>""")
        html must contain(s"""href="$base?filter=rated&sort=file">Rated (1)</a>""")
        // the unrated tab is the page's
        html must contain(s"""<a class="nav-link active" aria-current="page" href="$base?filter=unrated&sort=file">""")
        html must contain(s"""href="$base?filter=unrated&sort=rate"""")
      }
    }

    "have a binary round's tabs: all, unrated, selected and rejected" in {
      testDbApp { implicit app =>
        val f = fixture(Round.binaryRound)
        VoteJdbc.rate(f.pageIds(0), f.juror.getId, f.round.getId, 1)
        VoteJdbc.rate(f.pageIds(1), f.juror.getId, f.round.getId, -1)
        val html = page(gallery(f), f).replace("&amp;", "&")
        val base = s"/gallery/round/${f.round.getId}/user/${f.juror.getId}/page/1"

        html must contain(s"""href="$base?sort=file">All (20)</a>""")
        html must contain(s"""href="$base?filter=unrated&sort=file">Unrated (18)</a>""")
        html must contain(s"""href="$base?filter=selected&sort=file">Selected (1)</a>""")
        html must contain(s"""href="$base?filter=rejected&sort=file">Rejected (1)</a>""")
      }
    }

    "switch rounds of the other kind with one of the round's tabs active, all files for the other kind's filter" in {
      testDbApp { implicit app =>
        val f = fixture()
        val binary = roundDao.create(Round(None, 2, None, f.contest.getId, rates = Round.binaryRound, active = true))
        binary.addUsers(Seq(RoundUser(binary.getId, f.juror.getId, "jury", active = true)))
        selectionDao.batchInsert(f.pageIds.map(id => Vote(id, f.juror.getId, binary.getId)))
        VoteJdbc.rate(f.pageIds(0), f.juror.getId, binary.getId, 1)
        VoteJdbc.rate(f.pageIds(0), f.juror.getId, f.round.getId, 5)
        def base(r: Round) = s"/gallery/round/${r.getId}/user/${f.juror.getId}/page/1"
        // the own tabs shown active: their names
        def activeTabs(html: String) =
          """<a class="nav-link active" aria-current="page" href="[^"]*">(\w+) \(""".r.findAllMatchIn(html).map(_.group(1)).toList
        // the round menu's link to the round
        def menuLink(html: String, r: Round) =
          s"""href="(${base(r)}[^"]*)"""".r.findAllMatchIn(html).map(_.group(1)).toList

        val fromBinary = page(base(binary) + "?filter=selected", f).replace("&amp;", "&")
        activeTabs(fromBinary) === List("Selected")
        menuLink(fromBinary, f.round) === List(base(f.round) + "?sort=file")
        activeTabs(page(base(f.round) + "?sort=file", f)) === List("All")

        val fromRated = page(base(f.round) + "?filter=rated", f).replace("&amp;", "&")
        activeTabs(fromRated) === List("Rated")
        menuLink(fromRated, binary) === List(base(binary) + "?sort=file")
        activeTabs(page(base(binary) + "?sort=file", f)) === List("All")

        // a URL with the other kind's filter
        activeTabs(page(base(f.round) + "?filter=selected", f)) === List("All")
        activeTabs(page(base(binary) + "?filter=rated", f)) === List("All")
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

  "the pages with a juror's votes" should {
    // Back after a vote loads them again instead of showing the old votes from a cache
    "never be cached: the galleries, the file list and the large view" in {
      testDbApp { implicit app =>
        val f = fixture()
        val (r, u, p) = (f.round.getId, f.juror.getId, f.pageIds.head)
        val binary = {
          val round = roundDao.create(Round(None, 2, None, f.contest.getId, rates = Round.binaryRound, active = true))
          round.addUsers(Seq(RoundUser(round.getId, u, "jury", active = true)))
          selectionDao.batchInsert(Seq(Vote(p, u, round.getId, 0)))
          round.getId
        }
        Seq(
          s"/gallery/round/$binary/user/$u/page/1",
          s"/gallery/round/$r/user/$u/page/1",
          s"/byrate/round/$r/user/$u/page/1",
          s"/query?module=byrate&round=$r&user=$u",
          s"/filelist/round/$r/user/$u/page/1",
          s"/large/round/$r/user/$u/pageid/$p",
          s"/large/round/$binary/user/$u/pageid/$p"
        ).map { path =>
          val result = get(path, f)
          (path, status(result), header(CACHE_CONTROL, result)) === ((path, OK, Some("no-store")))
        }.reduce(_ and _)
      }
    }
  }
}
