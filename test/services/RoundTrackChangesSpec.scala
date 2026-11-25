package services

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import play.api.libs.json.{JsNull, JsObject, JsString, JsValue}

/** A track label's change in the round's settings log (round lineage slice A §8.1, V115): an entry
  * of its own kind, `round_track_changed`, beside the save's other entry, on a new round and a
  * re-apply (the round form's edit: controllers.RoundTrackSpec); none when the label is unchanged.
  * Jobs run inline; truncation.
  */
class RoundTrackChangesSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private def setUp(): (ContestJury, Seq[User], User) = {
    implicit val contest: ContestJury = createContests(10).head
    val imgs = (1L to 4L).map(i => Image(i, s"File:Image$i.jpg", None, None, 3000, 2000, Some(s"14-101-$i")))
    imageDao.batchInsert(imgs)
    CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, imgs)
    (contest, createUsers("jury", 1, 2).sortBy(_.getId), createUsers("admin", 9).head)
  }

  private def entries(r: Long, contest: ContestJury): Seq[ContestChange] =
    ContestChangeJdbc.findByRound(contest.getId, r, 50)
      .filter(c => ContestChangeJdbc.RoundSettingKinds.contains(c.kind)).reverse

  private def changed(c: ContestChange): Map[String, (JsValue, JsValue)] =
    (c.detail \ "changed").as[JsObject].fields.map { case (f, v) => f -> ((v \ "from").get, (v \ "to").get) }.toMap

  private def path(c: ContestChange): String = (c.detail \ "path").as[String]

  "a new round with a label" should {
    "log its settings, and its label in an entry of its own" in {
      val (contest, _, admin) = setUp()
      val r = TestRoundServices.roundService().submit(
        Round(None, 0, Some("R"), contest.getId, active = true, track = Some("Main")), Nil, None, Some(admin.getId),
        review = true).round.getId
      val logged = entries(r, contest)
      (logged.map(e => (e.kind, e.actorId, path(e))) === Seq(
        (ContestChangeJdbc.RoundCreated, admin.getId, "create"), (ContestChangeJdbc.RoundTrackChanged, admin.getId, "create"))) and
        (changed(logged.head).keySet must not(contain(RoundChanges.TrackField))) and
        (changed(logged(1)) === Map("track" -> (JsNull, JsString("Main"))))
    }
  }

  "re-applying filters" should {
    "log a changed label in its own entry beside the filters'; none when the label is the same" in {
      val (contest, jurors, admin) = setUp()
      val service = TestRoundServices.roundService()
      val r = service.submit(Round(None, 0, Some("R"), contest.getId, active = true), jurors.map(_.getId), None,
        review = true).round.getId
      def stored = roundDao.findById(r).get
      service.reapplyFilters(stored.copy(minMpx = Some(1)), Some(admin.getId),
        alsoWrite = s => Round.setTrack(r, Some("Second look"))(s))
      service.reapplyFilters(stored.copy(minMpx = Some(2)), Some(admin.getId),
        alsoWrite = s => Round.setTrack(r, Some("Second look"))(s))
      val logged = entries(r, contest)
      (logged.map(e => (e.kind, path(e))) === Seq(
        (ContestChangeJdbc.RoundFiltersReapplied, "reapply"), (ContestChangeJdbc.RoundTrackChanged, "reapply"),
        (ContestChangeJdbc.RoundFiltersReapplied, "reapply"))) and
        (changed(logged(1)) === Map("track" -> (JsNull, JsString("Second look"))))
    }
  }
}
