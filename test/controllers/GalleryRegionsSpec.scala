package controllers

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image, Selection}
import play.api.Application
import play.api.libs.json.JsObject
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._

import java.net.URLEncoder

/** A round's gallery's regions (its region menu, read when opened: GalleryController.regions):
  * named by the contest's kind, and each region's link listing the files the region has.
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

  /** The regions of the list at `path`, as its region menu reads them when opened: its
    * `data-regions-url`, sent by script.
    */
  private def regions(path: String, user: User)(implicit app: Application): Seq[JsObject] = {
    val page = get(path, user)
    val url = """data-regions-url="([^"]*)"""".r.findFirstMatchIn(page).get.group(1).replace("&amp;", "&")
    val result = route(app, FakeRequest(GET, url).withSession(Secured.UserName -> user.email)
      .withHeaders("X-Requested-With" -> "XMLHttpRequest")).get
    status(result) === OK
    (contentAsJson(result) \ "regions").as[Seq[JsObject]]
  }

  /** The names the region menu shows, in order, with their counts. */
  private def regionNames(path: String, user: User)(implicit app: Application): List[String] =
    regions(path, user).map { r =>
      (r \ "name").as[String] + (r \ "count").asOpt[Int].fold("")(n => s" ($n)")
    }.toList

  private def tiles(page: String): List[Long] =
    """(?:data-pageId|<h1 id)="(\d+)"""".r.findAllMatchIn(page).map(_.group(1).toLong).toList

  "a round's gallery" should {

    "name a Wiki Loves Earth contest's regions as Ukraine's" in {
      testDbApp { implicit app =>
        val f = fixture("Wiki Loves Earth", "Ukraine", 2025, Some("UkrainianNaturalHeritageSite"),
          Seq("26-103-0021", "26-103-0022", "61-101-0001"))
        regionNames(gallery(f), f.admin) === List("Ivano-Frankivsk O. (2)", "Ternopil O. (1)")
      }
    }

    "name a special nomination's prefix after it, after the regions" in {
      testDbApp { implicit app =>
        val f = fixture("Wiki Loves Monuments", "Ukraine", 2025, None,
          Seq("99-141-2306", "14-101-0001", "07-101-0001", "91-101-0001"))
        regionNames(gallery(f), f.admin) === List("Volyn O. (1)", "Donetsk O. (1)", "Єврейська спадщина (1)", "Other (91) (1)")
        regions(gallery(f), f.admin).map(r => (r \ "kind").as[String]) === List("Geographic", "Geographic", "Nomination", "Other")
      }
    }

    "show a juror's regions' counts with gallery.regionCounts on, for the rate filter" in {
      implicit val cfg: Map[String, String] = Map("gallery.regionCounts" -> "juror")
      testDbApp { implicit app =>
        SharedTestDb.truncateAll() // a per-test app doesn't
        val f = fixture("Wiki Loves Monuments", "Ukraine", 2025, None, Seq("07-101-0001", "07-101-0002", "14-101-0001"))
        regionNames(gallery(f), f.admin) === List("Volyn O. (2)", "Donetsk O. (1)")
        DB.autoCommit { implicit s =>
          sql"UPDATE selection SET rate = 1 WHERE page_id = 1".update()
        }
        regionNames(gallery(f) + "?rate=1", f.admin) === List("Volyn O. (1)", "Donetsk O. (0)")
      }
    }

    "count each organiser file once with all enabled, including the active rate filter" in {
      implicit val cfg: Map[String, String] = Map("gallery.regionCounts" -> "all")
      testDbApp { implicit app =>
        SharedTestDb.truncateAll()
        val f = fixture("Wiki Loves Monuments", "Ukraine", 2025, None,
          Seq("07-101-0001", "07-101-0002", "14-101-0001"))
        val second = userDao.create(User("Second", "second@example.com", None, Set("jury"), contestId = f.contest.id))
        val image = imageDao.findById(1L).get
        selectionDao.batchInsert(Seq(Selection(image, second, f.round, 1)))
        val url = s"/byrate/round/${f.round.getId}/user/0/page/1"
        regionNames(url, f.admin) === List("Volyn O. (2)", "Donetsk O. (1)")
        regionNames(url + "?rated=true", f.admin) === List("Volyn O. (1)", "Donetsk O. (0)")
      }
    }

    "keep the rated filter when following a counted region link" in {
      testDbApp { implicit app =>
        val f = fixture("Wiki Loves Monuments", "Ukraine", 2025, None,
          Seq("07-101-0001", "07-101-0002", "14-101-0001"))
        DB.autoCommit { implicit session => sql"UPDATE selection SET rate = 1 WHERE page_id = 1".update() }
        val paths = Seq(
          s"/byrate/round/${f.round.getId}/user/${f.juror.getId}/page/1?rated=true",
          s"/query?module=gallery&round=${f.round.getId}&user=${f.juror.getId}&rated=true")
        forall(paths) { path =>
          val volyn = regions(path, f.admin).find(r => (r \ "name").as[String] == "Volyn O.").get
          (volyn \ "count").as[Int] === 1
          val link = (volyn \ "url").as[String]
          link must contain("filter=rated")
          tiles(get(link, f.admin)) === List(1L)
        }
      }
    }

    "leave organiser counts off in juror mode" in {
      implicit val cfg: Map[String, String] = Map("gallery.regionCounts" -> "juror")
      testDbApp { implicit app =>
        SharedTestDb.truncateAll()
        val f = fixture("Wiki Loves Monuments", "Ukraine", 2025, None, Seq("07-101-0001", "14-101-0001"))
        regionNames(s"/byrate/round/${f.round.getId}/user/0/page/1", f.admin) === List("Volyn O.", "Donetsk O.")
      }
    }

    "show no counts with gallery.regionCounts off" in {
      implicit val cfg: Map[String, String] = Map("gallery.regionCounts" -> "off")
      testDbApp { implicit app =>
        SharedTestDb.truncateAll()
        val f = fixture("Wiki Loves Monuments", "Ukraine", 2025, None, Seq("07-101-0001", "14-101-0001"))
        regionNames(gallery(f), f.admin) === List("Volyn O.", "Donetsk O.")
      }
    }

    "not name another country's regions as Ukraine's" in {
      testDbApp { implicit app =>
        val f = fixture("Wiki Loves Monuments", "Germany", 2025, Some("Denkmal"), Seq("07-101-0001", "14-101-0001"))
        regionNames(gallery(f), f.admin) === List("07 (1)", "14 (1)")
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
