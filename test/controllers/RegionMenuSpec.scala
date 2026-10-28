package controllers

import db.scalikejdbc._
import db.scalikejdbc.rewrite.ImageDbNew.SelectionQuery
import org.intracer.wmua.{ContestJury, Image, Selection}
import org.scalawiki.wlx.dto.Monument
import play.api.Application
import play.api.libs.json.JsObject
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._

import scala.collection.mutable
import scala.concurrent.Future

/** A round gallery's region menu: the page shows the region it is in (or all) and reads its
  * regions with their counts only when the menu is opened (GalleryController.regions), by the
  * gallery's rules.
  */
class RegionMenuSpec extends PlaySpecification with PlayTestDb {

  sequential

  private case class Fixture(contest: ContestJury, admin: User, juror: User, other: User, binary: Round, rated: Round)

  /** A contest of monuments with a binary and a rated round; two jurors voting on files in 07,
    * 14 and the nomination prefix 99 and one without a monument id, at different rates.
    */
  private def fixture(name: String = "Wiki Loves Monuments", country: String = "Ukraine",
                      specialNomination: Option[String] = None): Fixture = {
    val contest = contestDao.create(None, name, 2026, country)
    def user(role: String, n: Int = 0) =
      userDao.create(User(s"$role$n", s"$role$n@example.com", None, Set(role), contestId = contest.id))
    val (admin, juror, other) = (user("admin"), user("jury", 1), user("jury", 2))
    def round(n: Int, rates: Rates) = roundDao.create(Round(None, n, None, contest.getId, rates = rates, active = true,
      specialNomination = specialNomination))
    val (binary, rated) = (round(1, Round.binaryRound), round(2, Round.ratesById(5)))
    for (r <- Seq(binary, rated))
      r.addUsers(Seq(juror, other).map(u => RoundUser(r.getId, u.getId, "jury", active = true)))
    val monuments = Seq("07-101-0001", "07-101-0002", "07-102-0003", "14-101-0004", "14-101-0005", "99-141-2306", "")
    val images = monuments.zipWithIndex.map { case (m, i) =>
      Image(i + 1L, s"File:Image${i + 1}.jpg", None, None, 640, 480, Some(m).filter(_.nonEmpty))
    }
    imageDao.batchInsert(images)
    def votes(r: Round, u: User, shift: Int) = images.zipWithIndex.map { case (image, i) =>
      val rate = if (r.isBinary) Seq(-1, 0, 1)((i + shift) % 3) else (i + shift) % 6
      Selection(image.pageId, u.getId, r.getId, rate, monumentId = image.monumentId)
    }
    selectionDao.batchInsert(Seq(binary, rated).flatMap(r => votes(r, juror, 0) ++ votes(r, other, 1).take(5)))
    Fixture(contest, admin, juror, other, binary, rated)
  }

  private def request(path: String, user: Option[User], script: Boolean)(implicit app: Application): Future[Result] = {
    val r = FakeRequest(GET, path)
    val withHeaders = if (script) r.withHeaders("X-Requested-With" -> "XMLHttpRequest") else r.withHeaders(ACCEPT -> "text/html")
    route(app, user.fold(withHeaders)(u => withHeaders.withSession(Secured.UserName -> u.email)).withCSRFToken).get
  }

  private def page(path: String, user: User)(implicit app: Application): String = {
    val result = request(path, Some(user), script = false)
    status(result) === OK
    contentAsString(result)
  }

  private def regionsUrl(page: String): String =
    """data-regions-url="([^"]*)"""".r.findFirstMatchIn(page).get.group(1).replace("&amp;", "&")

  private def regions(url: String, user: User)(implicit app: Application): Seq[JsObject] = {
    val result = request(url, Some(user), script = true)
    status(result) === OK
    (contentAsJson(result) \ "regions").as[Seq[JsObject]]
  }

  private def counts(regions: Seq[JsObject]): Map[String, Option[Int]] =
    regions.map(r => (r \ "id").as[String] -> (r \ "count").asOpt[Int]).toMap

  /** The statements a block runs. */
  private def statements[A](block: => A): (A, Seq[String]) = {
    val seen = mutable.Buffer.empty[String]
    val previous = GlobalSettings.queryCompletionListener
    GlobalSettings.queryCompletionListener = (sql, params, ms) => { seen.synchronized(seen += sql); previous(sql, params, ms) }
    try (block, seen.synchronized(seen.toList))
    finally GlobalSettings.queryCompletionListener = previous
  }

  "a round's gallery" should {

    "show the region menu with the region it is in, without reading the region counts" in {
      testDbApp { implicit app =>
        val f = fixture()
        val paths = Seq(
          f.juror -> s"/gallery/round/${f.binary.getId}/user/${f.juror.getId}/page/1",
          f.juror -> s"/gallery/round/${f.rated.getId}/user/${f.juror.getId}/region/07/page/1?filter=rated",
          f.admin -> s"/byrate/round/${f.binary.getId}/user/0/page/1",
          f.admin -> s"/byrate/round/${f.rated.getId}/user/0/region/14/page/1")
        forall(paths) { case (user, path) =>
          val (html, sqls) = statements(page(path, user))
          html must contain("""id="regionmenu"""")
          html must not contain "id=\"regionnav\""
          // neither regionCounts (a juror's or the round's) nor byRegionStat
          sqls.filter(s => s.contains("AS region, SUM(") || s.contains("SELECT DISTINCT NULLIF")) must beEmpty
          html must contain(s"""<a href="${if (user == f.admin) s"/byrate/round/" else "/gallery/round/"}""")
        }
        // the region shown, and all of them, without script
        val in07 = page(s"/gallery/round/${f.binary.getId}/user/${f.juror.getId}/region/07/page/1", f.juror)
        in07 must contain("<b>Volyn O.</b>")
        in07 must contain(s"""<a href="/gallery/round/${f.binary.getId}/user/${f.juror.getId}/page/1?sort=file">All regions</a>""")
        in07 must contain(s"""<li class="active"><a href="/gallery/round/${f.binary.getId}/user/${f.juror.getId}/region/07/page/1?sort=file">Volyn O.</a>""")
        in07 must contain("<noscript>")
      }
    }

    "list a list's regions with the counts the region nav had, for jurors and organisers, binary and rated, by filter" in {
      testDbApp { implicit app =>
        val f = fixture()
        val filters = Seq(VoteFilter.All, VoteFilter.Rated, VoteFilter.Unrated, VoteFilter.Rate(1), VoteFilter.Rate(-1),
          VoteFilter.Rate(3))
        val cases = for (round <- Seq(f.binary, f.rated); (viewer, asUserId) <- Seq(f.juror -> f.juror.getId,
          f.admin -> 0L, f.admin -> f.other.getId); filter <- filters) yield {
          val query = if (filter == VoteFilter.All) "" else s"?filter=${filter.param}"
          val json = counts(regions(s"/regions/round/${round.getId}/user/$asUserId/region/all$query", viewer))
          val before = SelectionQuery(userId = Some(asUserId).filter(_ != 0), roundId = round.id, rate = filter.rate,
            rated = filter.rated).regionCounts()
          (round.getId, asUserId, filter, json) -> (round.getId, asUserId, filter, before.map { case (k, v) => k -> Option(v) })
        }
        cases.map(_._1) === cases.map(_._2)
      }
    }

    "link each region's first page in the same list, as paths" in {
      testDbApp { implicit app =>
        val f = fixture()
        val html = page(s"/byrate/round/${f.rated.getId}/user/${f.juror.getId}/page/2?filter=rated&sort=rate", f.admin)
        val url = regionsUrl(html)
        url === s"/regions/round/${f.rated.getId}/user/${f.juror.getId}/region/all?module=byrate&filter=rated&sort=rate"
        val volyn = regions(url, f.admin).find(r => (r \ "id").as[String] == "07").get
        (volyn \ "url").as[String] === s"/byrate/round/${f.rated.getId}/user/${f.juror.getId}/region/07/page/1?filter=rated&sort=rate"
      }
    }

    "refuse another juror's regions to a juror, as the gallery refuses their list" in {
      testDbApp { implicit app =>
        val f = fixture()
        val result = request(s"/regions/round/${f.binary.getId}/user/${f.other.getId}/region/all", Some(f.juror), script = true)
        status(result) === FORBIDDEN
        (contentAsJson(result) \ "error").as[String] === "forbidden"
        status(request(s"/regions/round/${f.binary.getId}/user/0/region/all", Some(f.juror), script = true)) === FORBIDDEN
        status(request(s"/gallery/round/${f.binary.getId}/user/${f.other.getId}/page/1", Some(f.juror), script = false)) ===
          FORBIDDEN
      }
    }

    "answer signed out with JSON 401" in {
      testDbApp { implicit app =>
        val f = fixture()
        val result = request(s"/regions/round/${f.binary.getId}/user/${f.juror.getId}/region/all", None, script = true)
        status(result) === UNAUTHORIZED
        (contentAsJson(result) \ "error").as[String] === "unauthenticated"
      }
    }

    "refuse a round of a contest the user isn't in, and never switch the session's contest" in {
      testDbApp { implicit app =>
        val f = fixture()
        val elsewhere = contestDao.create(None, "Wiki Loves Earth", 2026, "Ukraine")
        val outsider = userDao.create(User("outsider", "outsider@example.com", None, Set("jury"), contestId = elsewhere.id))
        val result = request(s"/regions/round/${f.binary.getId}/user/${outsider.getId}/region/all", Some(outsider), script = true)
        status(result) === FORBIDDEN
        (contentAsJson(result) \ "error").as[String] === "forbidden"
        val own = request(s"/regions/round/${f.binary.getId}/user/${f.juror.getId}/region/all", Some(f.juror), script = true)
        status(own) === OK
        session(own).get(Secured.CurrentContestId) must beNone
        status(request(s"/regions/round/999999/user/${f.juror.getId}/region/all", Some(f.juror), script = true)) === NOT_FOUND
      }
    }

    "show no region menu in a contest whose files have no monument ids, and list no regions" in {
      testDbApp { implicit app =>
        val f = fixture(name = "Photo contest", country = "Nowhere")
        page(s"/gallery/round/${f.binary.getId}/user/${f.juror.getId}/page/1", f.juror) must not contain "regionmenu"
        regions(s"/regions/round/${f.binary.getId}/user/${f.juror.getId}/region/all", f.juror) must beEmpty
      }
    }

    "list a Віа Регіа round's sub-regions, without counts" in {
      testDbApp { implicit app =>
        val f = fixture(specialNomination = Some("Віа Регіа"))
        monumentDao.batchInsert(Seq("07-101-0001", "07-101-0002", "07-102-0003", "14-101-0004", "14-101-0005", "99-141-2306")
          .map(id => new Monument(id = id, name = s"Monument $id", page = "Test")))
        DB.autoCommit { implicit s => sql"UPDATE monument SET adm1 = SUBSTR(id, 1, 6)".update() }
        val listed = counts(regions(s"/regions/round/${f.binary.getId}/user/${f.juror.getId}/region/all", f.juror))
        val before = SelectionQuery(userId = f.juror.id, roundId = f.binary.id, subRegions = true).byRegionStat()
        listed.keySet === before.keySet
        listed.keySet must contain("07-101")
        listed.values.flatten must beEmpty
        page(s"/gallery/round/${f.binary.getId}/user/${f.juror.getId}/page/1", f.juror) must contain("""id="regionmenu"""")
      }
    }
  }
}
