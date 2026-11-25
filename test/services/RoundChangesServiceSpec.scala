package services

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import play.api.libs.json.{JsBoolean, JsNull, JsNumber, JsObject, JsString, JsValue}
import scalikejdbc._

import scala.util.Try

/** The round's settings log (RoundChanges, V115) on each write path besides the round form's edit
  * (controllers.RoundChangesSpec): a new round, "Re-apply filters", the jurors form's jurors per
  * file, a start or stop by an account that doesn't judge the round. Each writes its entry in the
  * transaction of its save, with the old and new values of only what changed, none when nothing
  * changed or no account did it, and none when the save rolls back. Jobs run inline; truncation.
  */
class RoundChangesServiceSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  /** Files 1-4 small (0.8 Mpx), 5-8 large (6 Mpx), in the contest's category; three jurors, an admin. */
  private def setUp(): (ContestJury, Seq[User], User) = {
    implicit val contest: ContestJury = createContests(10).head
    val imgs = (1L to 8L).map(i =>
      if (i <= 4) Image(i, s"File:Image$i.jpg", None, None, 1000, 800, Some(s"14-101-$i"))
      else Image(i, s"File:Image$i.jpg", None, None, 3000, 2000, Some(s"14-101-$i")))
    imageDao.batchInsert(imgs)
    CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, imgs)
    (contest, createUsers("jury", 1, 2, 3).sortBy(_.getId), createUsers("admin", 9).head)
  }

  private def newRound(contest: ContestJury, name: String) =
    Round(None, 0, Some(name), contest.getId, distribution = 0, active = true)

  private def round(id: Long): Round = roundDao.findById(id).get

  private def entries(r: Round): Seq[ContestChange] =
    ContestChangeJdbc.findByRound(r.contestId, r.getId, 50)
      .filter(c => ContestChangeJdbc.RoundSettingKinds.contains(c.kind)).reverse

  private def changed(c: ContestChange): Map[String, (JsValue, JsValue)] =
    (c.detail \ "changed").as[JsObject].fields.map { case (f, v) => f -> ((v \ "from").get, (v \ "to").get) }.toMap

  private def path(c: ContestChange): String = (c.detail \ "path").as[String]

  "a new round" should {

    "log its settings once, from none, by who created it; none without an account or for a resubmission" in {
      val (contest, _, admin) = setUp()
      val service = TestRoundServices.roundService()
      val r = service.submit(newRound(contest, "R").copy(minMpx = Some(1)), Nil, Some("token-1"), Some(admin.getId),
        review = true).round
      val again = service.submit(newRound(contest, "R").copy(minMpx = Some(1)), Nil, Some("token-1"), Some(admin.getId),
        review = true)
      val anonymous = service.submit(newRound(contest, "Q"), Nil, None, None, review = true).round
      val logged = entries(r)
      (again must beAnInstanceOf[RoundService.Resubmitted]) and
        (logged.map(e => (e.kind, e.actorId, path(e))) === Seq((ContestChangeJdbc.RoundCreated, admin.getId, "create"))) and
        ((logged.head.detail \ "number").as[Long] === round(r.getId).number) and
        (changed(logged.head) === Map("name" -> (JsNull, JsString("R")), "rates" -> (JsNull, JsString("Yes/No")),
          "minMpx" -> (JsNull, JsNumber(1)))) and
        (entries(anonymous) must beEmpty)
    }

    "be rolled back with its round" in {
      val (contest, _, admin) = setUp()
      val failed = Try(DB.localTx { implicit s =>
        val r = Round.create(Round(None, 1, Some("Gone"), contest.getId))
        RoundChanges.recordCreated(r.getId, Some(admin.getId))
        throw new IllegalStateException("rolled back")
      })
      (failed.isFailure must beTrue) and (Round.findByContest(contest.getId) must beEmpty) and
        (ContestChangeJdbc.findByContest(contest.getId, 10) must beEmpty)
    }
  }

  "re-applying filters" should {

    "log the filters and the form's other settings it changed in one entry; none for the same ones; none rolled back" in {
      val (contest, jurors, admin) = setUp()
      val service = TestRoundServices.roundService()
      val r = service.submit(newRound(contest, "R"), jurors.map(_.getId), None, review = true).round.getId
      val stored = round(r)
      val started = service.reapplyFilters(stored.copy(minMpx = Some(1)), Some(admin.getId),
        alsoWrite = s => Round.updateRound(r, stored.copy(name = Some("R2")))(s))
      val same = service.reapplyFilters(round(r), Some(admin.getId))
      val failed = Try(service.reapplyFilters(round(r).copy(minMpx = Some(2)), Some(admin.getId),
        alsoWrite = _ => throw new IllegalStateException("refused")))
      val logged = entries(round(r))
      (started must beAnInstanceOf[RoundService.ReviewDistribution.Started]) and
        (same must beAnInstanceOf[RoundService.ReviewDistribution.Started]) and
        (failed.isFailure must beTrue) and (round(r).minMpx === Some(1)) and
        (logged.map(e => (e.kind, e.actorId, path(e))) ===
          Seq((ContestChangeJdbc.RoundFiltersReapplied, admin.getId, "reapply"))) and
        (changed(logged.head) === Map("name" -> (JsString("R"), JsString("R2")), "minMpx" -> (JsNull, JsNumber(1))))
    }
  }

  "the jurors form of a round in review" should {

    "log the jurors per file it sets; nothing when refused" in {
      val (contest, jurors, admin) = setUp()
      val service = TestRoundServices.roundService()
      val r = service.submit(newRound(contest, "R"), Nil, None, review = true).round.getId
      val refused = service.distributeReviewed(r, Some(admin.getId), jurors = Some(Seq(jurors.head.getId)), distribution = Some(2))
      val started = service.distributeReviewed(r, Some(admin.getId), jurors = Some(jurors.map(_.getId)), distribution = Some(2))
      val logged = entries(round(r))
      (refused must beAnInstanceOf[RoundService.ReviewDistribution.Refused]) and
        (started must beAnInstanceOf[RoundService.ReviewDistribution.Started]) and
        (logged.map(e => (e.kind, path(e))) === Seq((ContestChangeJdbc.RoundChanged, "jurors"))) and
        (changed(logged.head) === Map("distribution" -> (JsNumber(0), JsNumber(2))))
    }
  }

  "a start or a stop" should {

    "be logged for an account that doesn't judge the round, once per change; a juror's stays a juror's entry" in {
      val (contest, jurors, admin) = setUp()
      val r = roundDao.create(Round(None, 1, Some("R"), contest.getId, active = true))
      r.addUsers(Seq(RoundUser(r.getId, jurors.head.getId, "jury", active = true)))
      Round.setActiveGuarded(r.getId, active = false, Some(admin.getId))
      Round.setActiveGuarded(r.getId, active = false, Some(admin.getId))
      Round.setActiveGuarded(r.getId, active = true, None)
      Round.setActiveGuarded(r.getId, active = false, Some(jurors.head.getId))
      Round.setActiveGuarded(r.getId, active = true, Some(admin.getId))
      val logged = entries(r)
      val all = ContestChangeJdbc.findByRound(r.contestId, r.getId, 50).reverse.map(_.kind)
      (logged.map(e => (e.actorId, path(e))) === Seq(admin.getId -> "stop", admin.getId -> "start")) and
        (changed(logged.head).get("active") === Some(JsBoolean(true) -> JsBoolean(false))) and
        (changed(logged.head).keySet === Set("active", "status")) and
        (changed(logged(1)).get("active") === Some(JsBoolean(false) -> JsBoolean(true))) and
        (all === Seq(ContestChangeJdbc.RoundChanged, ContestChangeJdbc.RoundJurorStopped, ContestChangeJdbc.RoundChanged))
    }
  }
}
