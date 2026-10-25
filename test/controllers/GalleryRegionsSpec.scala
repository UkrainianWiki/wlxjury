package controllers

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image, Selection}
import play.api.Application
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import java.net.URLEncoder

/** A round's gallery's regions (regionNav): named by the contest's kind, and each region's
  * link listing the files the region has.
  */
class GalleryRegionsSpec extends PlaySpecification with PlayTestDb {

  sequential

  private case class Fixture(contest: ContestJury, admin: User, juror: User, round: Round)

  /** A contest with a binary round whose juror has a file of each of `monumentIds`, page ids 1, 2, ... */
  private def fixture(name: String, country: String, year: Int, template: Option[String], monumentIds: Seq[String])
      : Fixture = {
    val contest = contestDao.create(None, name, year, country, monumentIdTemplate = template)
    def user(role: String) = userDao.create(User(role, s"$role@example.com", None, Set(role), contestId = contest.id))
    val Seq(admin, juror) = Seq("admin", "jury").map(user)
    val round = roundDao.create(Round(None, 1, None, contest.getId, rates = Round.binaryRound, active = true))
    val images = monumentIds.zipWithIndex.map { case (id, i) =>
      Image(i + 1L, s"File:Image${i + 1}.jpg", None, None, 640, 480, Some(id))
    }
    imageDao.batchInsert(images)
    selectionDao.batchInsert(images.map(image => Selection(image, juror, round, 0)))
    Fixture(contest, admin, juror, round)
  }

  private def get(path: String, user: User)(implicit app: Application): String = {
    val result = route(app, FakeRequest(GET, path).withSession(Secured.UserName -> user.email).withCSRFToken).get
    status(result) === OK
    contentAsString(result)
  }

  private def gallery(f: Fixture, region: String = "all"): String =
    s"/gallery/round/${f.round.getId}/user/${f.juror.getId}/" +
      (if (region == "all") "" else s"region/${URLEncoder.encode(region, "UTF-8")}/") + "page/1"

  /** The names the region nav shows, in order. */
  private def regionNames(page: String): List[String] = {
    val nav = page.substring(page.indexOf("id=\"regionnav\""))
    """<a href="[^"]*" >\s*([^<]*?)\s*</a>""".r.findAllMatchIn(nav.substring(0, nav.indexOf("</ul>")))
      .map(_.group(1).trim).toList.tail // without "*"
  }

  private def tiles(page: String): List[Long] =
    """data-pageId="(\d+)"""".r.findAllMatchIn(page).map(_.group(1).toLong).toList

  "a round's gallery" should {

    "name a Wiki Loves Earth contest's regions as Ukraine's" in {
      testDbApp { implicit app =>
        val f = fixture("Wiki Loves Earth", "Ukraine", 2025, Some("UkrainianNaturalHeritageSite"),
          Seq("26-103-0021", "26-103-0022", "61-101-0001"))
        regionNames(get(gallery(f), f.admin)) === List("Ivano-Frankivsk O.", "Ternopil O.")
      }
    }

    "not name another country's regions as Ukraine's" in {
      testDbApp { implicit app =>
        val f = fixture("Wiki Loves Monuments", "Germany", 2025, Some("Denkmal"), Seq("07-101-0001", "14-101-0001"))
        regionNames(get(gallery(f), f.admin)) === List("07", "14")
      }
    }

    "list a three-character region's files, as many as its count" in {
      testDbApp { implicit app =>
        val f = fixture("Wiki Loves Earth", "Ukraine", 2025, Some("UkrainianNaturalHeritageSite"),
          Seq("26-103-0021", "Печера Ювілейна", "Печера Оптимістична", "7-101-0001", "71-101-0001"))
        tiles(get(gallery(f, "Печ"), f.admin)).sorted === List(2L, 3L)
        tiles(get(gallery(f, "7"), f.admin)) === List(4L) // not 71's file
        tiles(get(gallery(f, "71"), f.admin)) === List(5L)
      }
    }
  }
}
