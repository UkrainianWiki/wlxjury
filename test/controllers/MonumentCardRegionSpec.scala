package controllers

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image}
import org.scalawiki.wlx.dto.Monument
import play.api.Application
import play.api.i18n.{Messages, MessagesApi}
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._
import services.TestRoundServices

/** The region a file's monument card names ([[services.regions.RegionScheme.monumentRegion]]):
  * for a special nomination's own monument (production's `92-232-4801`, the Bulgarian heritage
  * list of Zaporizhzhia Oblast, 2026-11-26) the nomination and the oblast its id carries, on the
  * round review's file page (the reported page) and in a round's large view; any other monument's
  * region as before.
  */
class MonumentCardRegionSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val contestId = 10L
  private val Nominated = "92-232-4801"
  private val Regional = "23-248-0004"

  /** A WLM Ukraine 2026 contest with files 1 (the nomination's monument) and 2 (a regional one)
    * in its category, both monuments stored; its admin and juror; a round in review and an open
    * round with the juror's votes on both files.
    */
  private def setUp(): (Round, Round, User) = {
    val contest: ContestJury = ContestJuryJdbc.create(Some(contestId), "Wiki Loves Monuments", 2026, "Ukraine")
    TestDb.setSource(contestId, Some("Images from Wiki Loves Monuments 2026 in Ukraine"))
    val imgs = Seq(Image(1L, "File:Radolivka.jpg", None, None, 640, 480, Some(Nominated)),
      Image(2L, "File:Church.jpg", None, None, 640, 480, Some(Regional)))
    imageDao.batchInsert(imgs)
    CategoryLinkJdbc.addToCategory(contestDao.findById(contestId).get.categoryId.get, imgs)
    MonumentJdbc.batchInsert(Seq(
      Monument(page = "Вікіпедія:Вікі любить пам'ятки/Болгарська спадщина/Запорізька область", id = Nominated,
        name = "Стара церква", city = Some("[[Радолівка]]")),
      Monument(page = "Вікіпедія:Вікі любить пам'ятки/Запорізька область/Приморський район", id = Regional,
        name = "Церква", city = Some("[[Приморськ]]"))
    ))
    def member(email: String, role: String) =
      User.create(User(email, email, None, Set(role), Some(services.PasswordHasher.default.hash("secret")), contest.id))
    val juror = member("juror@example.com", "jury")
    member("admin@example.com", "admin")
    val review = TestRoundServices.roundService()
      .submit(Round(None, 0, Some("R"), contestId, distribution = 1, active = true), Seq(juror.getId), None, review = true)
      .round
    val open = roundDao.create(Round(None, 1, Some("Open"), contestId, active = true))
    open.addUsers(Seq(RoundUser(open.getId, juror.getId, "jury", active = true)))
    Seq(1L, 2L).foreach(p => selectionDao.create(p, rate = 0, juryId = juror.getId, roundId = open.getId))
    (roundDao.findById(review.getId).get, open, juror)
  }

  private def get(email: String, path: String)(implicit app: Application): String = {
    val request = FakeRequest(GET, path)
      .withSession(Secured.UserName -> email, Secured.CurrentContestId -> contestId.toString).withCSRFToken
    val result = route(app, request).get
    if (status(result) != OK) sys.error(s"$path: ${status(result)}")
    contentAsString(result)
  }

  /** The card's region: its first line's text before the city's link, spaces joined. */
  private def regionLine(body: String): String =
    """(?s)id="monument-card">\s*(.*?), <a """.r.findFirstMatchIn(body).map(_.group(1))
      .getOrElse(sys.error("no monument card")).replaceAll("\\s+", " ").trim

  private def messages(implicit app: Application): Messages =
    app.injector.instanceOf[MessagesApi].preferred(FakeRequest())

  private def oblast(implicit app: Application): String = messages.apply("23").replaceAll("\\s+", " ")

  "a monument card" should {

    "name a nomination's own monument by the nomination and its oblast on the review's file page" in {
      testDbApp { implicit app =>
        val (review, _, _) = setUp()
        regionLine(get("admin@example.com", s"/admin/rounds/${review.getId}/review/file/1")) ===
          s"Болгарська спадщина, $oblast"
        // a regional monument as before: its region alone
        regionLine(get("admin@example.com", s"/admin/rounds/${review.getId}/review/file/2")) ===
          oblast
      }
    }

    "name it the same way in a round's large view" in {
      testDbApp { implicit app =>
        val (_, open, juror) = setUp()
        regionLine(get("juror@example.com", s"/large/round/${open.getId}/user/${juror.getId}/pageid/1")) ===
          s"Болгарська спадщина, $oblast"
        regionLine(get("juror@example.com", s"/large/round/${open.getId}/user/${juror.getId}/pageid/2")) ===
          oblast
      }
    }
  }
}
