package controllers

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image, Vote}
import org.scalawiki.wlx.dto.Monument
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._

import scala.concurrent.Future

/** A round's lists by monument through the router (`monument=`, the round filters proposal v4,
  * PR-2): the chip, the tabs within it, no region menu, the large view's chip and its monument
  * card's line, votes that stay within it, and the refusals, which are the gallery's as without it.
  */
class RoundMonumentFilterSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val M = "07-101-0001"

  private case class Fixture(contest: ContestJury, round: Round, juror: User, juror2: User, organizer: User) {
    def gallery(user: Long, query: String = ""): String = s"/gallery/round/${round.getId}/user/$user/page/1$query"
    def large(user: Long, pageId: Long, query: String = ""): String =
      s"/large/round/${round.getId}/user/$user/pageid/$pageId$query"
  }

  /** A contest of monuments with an open yes/no round. Monument M (a row in the monument list):
    * files 1, 2 and 3 the juror's (2 selected), 7 the second juror's; 4 of 07-101-0002 and 5, 6 of
    * 07-101-9999 (neither in a monument list), the juror's; the second juror also has 1.
    */
  private def fixture(): Fixture = {
    implicit val contest: ContestJury = createContests(10).head
    def user(name: String, role: String) =
      userDao.create(User(name, s"$name@example.com", None, Set(role), contestId = contest.id))
    val (juror, juror2, organizer) = (user("juror", "jury"), user("juror2", "jury"), user("organizer", "organizer"))
    DB.autoCommit { implicit s =>
      sql"UPDATE contest_jury SET monument_id_template = 'UkrainianMonument' WHERE id = ${contest.getId}".update()
      MonumentJdbc.batchInsert(Seq(Monument(page = "Lists", id = M, name = "Castle")))
    }
    val monuments = Map(1L -> M, 2L -> M, 3L -> M, 7L -> M, 4L -> "07-101-0002", 5L -> "07-101-9999", 6L -> "07-101-9999")
    val images = (1L to 7L).map(id => id -> Image(id, s"File:Image$id.jpg", None, None, 640, 480, monuments.get(id))).toMap
    imageDao.batchInsert(images.values.toSeq.sortBy(_.pageId))
    val round = roundDao.create(Round(None, 1, Some("Main"), contest.getId, rates = Round.binaryRound, active = true))
    round.addUsers(Seq(juror, juror2).map(j => RoundUser(round.getId, j.getId, "jury", active = true)))
    // each vote with its file's monument id, as the distribution writes it (vote.monument_id, V48)
    selectionDao.batchInsert(Seq(1L -> 0, 2L -> 1, 3L -> 0, 4L -> 0, 5L -> 0, 6L -> 0).map { case (p, r) =>
      Vote(images(p), juror, round, r) } ++ Seq(Vote(images(1L), juror2, round, 0), Vote(images(7L), juror2, round, 0)))
    Fixture(contest, round, juror, juror2, organizer)
  }

  private def get(path: String, user: User)(implicit app: Application): Future[Result] =
    route(app, FakeRequest(GET, path).withSession(Secured.UserName -> user.email).withHeaders(ACCEPT -> "text/html")).get

  private def page(path: String, user: User)(implicit app: Application): String = {
    val result = get(path, user)
    status(result) === OK
    contentAsString(result)
  }

  private def tiles(page: String): List[Long] =
    """data-pageId="(\d+)"""".r.findAllMatchIn(page).map(_.group(1).toLong).toList.distinct

  private def href(page: String, id: String): Option[String] =
    s"""id="$id"[^>]*href="([^"]*)"""".r.findFirstMatchIn(page).map(_.group(1).replace("&amp;", "&"))
      .orElse(s"""href="([^"]*)"[^>]*id="$id"""".r.findFirstMatchIn(page).map(_.group(1).replace("&amp;", "&")))

  "a juror's gallery of one monument" should {

    "list only their files of it under a chip, the tabs counting within it, without the region menu" in {
      testDbApp { implicit app =>
        val f = fixture()
        val body = page(f.gallery(f.juror.getId, s"?monument=$M"), f.juror)
        tiles(body) === List(1L, 2L, 3L)
        body must contain("Monument: 07-101-0001 (3)")
        href(body, "monument-filter-remove") must beSome(f.gallery(f.juror.getId, "?sort=file"))
        href(body, "monument-page-link") must beSome(s"/monuments/id/$M?contest=${f.contest.getId}&round=${f.round.getId}")
        body must contain("All (3)")
        body must contain("Unrated (2)")
        body must contain("Selected (1)")
        body must contain(s"filter=unrated&amp;monument=$M&amp;sort=file")
        body must not contain """id="regionmenu""""
        // the same gallery without the filter has the region menu, and all the juror's files
        val plain = page(f.gallery(f.juror.getId), f.juror)
        plain must contain("""id="regionmenu"""")
        plain must contain("All (6)")
        // a region in the path is dropped by the filter
        tiles(page(s"/gallery/round/${f.round.getId}/user/${f.juror.getId}/region/14/page/1?monument=$M", f.juror)) ===
          List(1L, 2L, 3L)
        // a monument without a monument row: the chip without the monument page's link
        val bare = page(f.gallery(f.juror.getId, "?monument=07-101-9999"), f.juror)
        tiles(bare) === List(5L, 6L)
        bare must not contain "monument-page-link"
      }
    }

    "refuse it as without it: another juror's votes, all the jurors', another contest's round, a round being created" in {
      testDbApp { implicit app =>
        val f = fixture()
        val q = s"?monument=$M"
        val other = createContests(20).head
        val foreign = roundDao.create(Round(None, 1, None, other.getId, active = true))
        status(get(f.gallery(f.juror2.getId, q), f.juror)) === FORBIDDEN
        status(get(f.gallery(0, q), f.juror)) === FORBIDDEN
        status(get(s"/gallery/round/${foreign.getId}/user/${f.juror.getId}/page/1$q", f.juror)) === FORBIDDEN
        status(get(f.large(f.juror2.getId, 7L, q), f.juror)) === FORBIDDEN
        // never another juror's file of the monument in their own list
        tiles(page(f.gallery(f.juror.getId, q), f.juror)) must not contain 7L
        DB.autoCommit { implicit s => sql"UPDATE round SET distributing = 1 WHERE id = ${f.round.getId}".update() }
        status(get(f.gallery(f.juror.getId, q), f.juror)) === FORBIDDEN
      }
    }

    "list all the jurors' files of it for an organizer, and refuse a user who isn't a juror of the round as without it" in {
      testDbApp { implicit app =>
        val f = fixture()
        val body = page(f.gallery(0, s"?monument=$M"), f.organizer)
        tiles(body).sorted === List(1L, 2L, 3L, 7L)
        body must contain("Monument: 07-101-0001 (4)")
        tiles(page(f.gallery(f.juror.getId, s"?monument=$M"), f.organizer)).sorted === List(1L, 2L, 3L)
        status(get(f.gallery(f.organizer.getId, s"?monument=$M"), f.organizer)) ===
          status(get(f.gallery(f.organizer.getId), f.organizer))
      }
    }
  }

  "a large view in a list of one monument" should {

    "show the compact chip, move and vote within it by paths" in {
      testDbApp { implicit app =>
        val f = fixture()
        val body = page(f.large(f.juror.getId, 1L, s"?monument=$M&sort=file"), f.juror)
        body must contain("""id="monument-filter"""")
        body must contain("Monument: 07-101-0001")
        href(body, "monument-filter-remove") must beSome(f.large(f.juror.getId, 1L, "?sort=file"))
        // no line into the list it already is
        body must not contain "monument-round-list"
        body must contain(s"""data-nav="down" href="${f.large(f.juror.getId, 2L, s"?monument=$M&amp;sort=file")}"""")
        // a vote for the monument's last file goes on to the one before it, by a path
        val vote = route(app, FakeRequest(POST, s"/large/round/${f.round.getId}/pageid/3/select/1?monument=$M&sort=file")
          .withSession(Secured.UserName -> f.juror.email).withCSRFToken).get
        redirectLocation(vote) must beSome(f.large(f.juror.getId, 2L, s"?monument=$M&sort=file"))
        // a file of another monument: the filtered list's first page
        val outside = get(f.large(f.juror.getId, 4L, s"?monument=$M&sort=file"), f.juror)
        status(outside) === SEE_OTHER
        redirectLocation(outside) must beSome(f.gallery(f.juror.getId, s"?monument=$M&sort=file"))
      }
    }

    "link the list of its file's monument from the card, a minimal card without a monument row" in {
      testDbApp { implicit app =>
        val f = fixture()
        val body = page(f.large(f.juror.getId, 1L, "?sort=file"), f.juror)
        href(body, "monument-round-list") must beSome(f.gallery(f.juror.getId, s"?monument=$M&sort=file"))
        body must contain("This monument in the list (3)")
        href(body, "monument-page") must beSome(s"/monuments/id/$M?contest=${f.contest.getId}&round=${f.round.getId}")
        // a juror's card links the round's list and the monument page, never the contest's files
        body must not contain "/contest/"
        // the tab is kept: on the unrated tab, 2 (selected) is off it
        val unrated = page(f.large(f.juror.getId, 1L, "?filter=unrated&sort=file"), f.juror)
        href(unrated, "monument-round-list") must beSome(f.gallery(f.juror.getId, s"?filter=unrated&monument=$M&sort=file"))
        unrated must contain("This monument in the list (2)")
        // without a monument row: the id and the line, no monument page
        val bare = page(f.large(f.juror.getId, 5L, "?sort=file"), f.juror)
        bare must contain("Monument 07-101-9999")
        href(bare, "monument-round-list") must beSome(f.gallery(f.juror.getId, "?monument=07-101-9999&sort=file"))
        bare must not contain """id="monument-page""""
        // the only file of its monument in the list: plain text
        page(f.large(f.juror.getId, 4L, "?sort=file"), f.juror) must
          contain("""<span id="monument-round-list">The only file of this monument in the list</span>""")
      }
    }
  }
}
