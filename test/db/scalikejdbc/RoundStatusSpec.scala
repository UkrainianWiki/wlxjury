package db.scalikejdbc

import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._
import services.TestRoundServices

/** `round.status` (V105) written in step with `active` and `distributing` (round lineage §2.6, the
  * round-editing plan's P3-2): Round.create from its flags; a start or a stop (who too) only where
  * the round isn't being created nor cancelled, a frozen round's by the flag its freeze saved;
  * closing a previous round; never a freeze or a plain release; a creation's job `draft`, then
  * `active`. A round without a status (a rollback's) reads its flags.
  */
class RoundStatusSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private def exec(statement: SQLSyntax): Unit = DB.autoCommit(implicit s => sql"$statement".update(): Unit)

  private def stored(id: Long): (Option[String], Option[Long], Boolean) = DB.readOnly { implicit s =>
    sql"SELECT status, status_changed_by, status_changed_at IS NOT NULL FROM round WHERE id = $id"
      .map(rs => (rs.stringOpt(1), rs.longOpt(2), rs.boolean(3))).single().get
  }

  private def status(id: Long): Option[String] = stored(id)._1

  "the status" should {

    "be written by Round.create from the flags" in {
      implicit val contest: ContestJury = createContests(1).head
      val draft = roundDao.create(Round(None, 1, None, contest.getId, distributing = true)).getId
      val active = roundDao.create(Round(None, 2, None, contest.getId, active = true)).getId
      val paused = roundDao.create(Round(None, 3, None, contest.getId)).getId
      (Seq(draft, active, paused).map(status) === Seq("draft", "active", "paused").map(Some(_))) and
        (stored(active)._3 must beTrue)
    }

    "follow a start and a stop, with who, but not on a round being created or cancelled" in {
      implicit val contest: ContestJury = createContests(1).head
      val r = roundDao.create(Round(None, 1, None, contest.getId)).getId
      val creating = roundDao.create(Round(None, 2, None, contest.getId, distributing = true)).getId
      val cancelled = roundDao.create(Round(None, 3, None, contest.getId, active = true)).getId
      exec(sqls"UPDATE round SET status = 'cancelled', active = 0 WHERE id = $cancelled")
      (Round.setActiveGuarded(r, active = true, by = Some(7L)) must beTrue) and (stored(r) === ((Some("active"), Some(7L), true))) and
        { Round.setActiveGuarded(r, active = false); status(r) === Some("paused") } and
        // refused start, and a stop of a round being created (setCurrentRound's): still draft
        (Round.setActiveGuarded(creating, active = true) must beFalse) and
        { Round.setActiveGuarded(creating, active = false); status(creating) === Some("draft") } and
        { Round.setActiveGuarded(cancelled, active = false); status(cancelled) === Some("cancelled") }
    }

    "follow the flag a freeze saved, and change on closing a previous round, not on a plain release" in {
      implicit val contest: ContestJury = createContests(1).head
      val r = roundDao.create(Round(None, 1, None, contest.getId, active = true)).getId
      val creating = roundDao.create(Round(None, 2, None, contest.getId, distributing = true)).getId
      DB.localTx { implicit s => Round.lock(Seq(r, creating)); Round.freeze(Seq(r, creating)) }
      val frozen = status(r)
      DB.localTx { implicit s => Round.lock(Seq(r)); Round.release(Seq(r), close = false) }
      val released = status(r)
      DB.localTx { implicit s => Round.lock(Seq(r)); Round.freeze(Seq(r)) }
      Round.setActiveGuarded(r, active = false) // a stop while frozen: the saved flag, and the status
      val stoppedFrozen = status(r)
      Round.setActiveGuarded(r, active = true)
      DB.localTx { implicit s => Round.lock(Seq(r, creating)); Round.release(Seq(r, creating), close = true) }
      (frozen === Some("active")) and (released === Some("active")) and (stoppedFrozen === Some("paused")) and
        (status(r) === Some("paused")) and (roundDao.findById(r).get.active must beFalse) and
        (status(creating) === Some("draft"))
    }

    "be draft while a creation's job runs and active when it completes" in {
      implicit val contest: ContestJury = createContests(10).head
      val jurors = createUsers("jury", 1, 2)
      val images = (1L to 3L).map(i => Image(i, s"File:Image$i.jpg", None, None, 640, 480, Some(s"07-101-000$i")))
      imageDao.batchInsert(images)
      CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, images)
      val seen = scala.collection.mutable.Buffer.empty[Option[String]]
      val handler = new services.distribution.RoundDistributionHandler(new org.intracer.wmua.cmd.DistributeImages(ImageJdbc),
        TestRoundServices.config(services.distribution.RoundDistributionHandler.DefaultChunkRows), TestRoundServices.emptyCommons) {
        // the round's status as each chunk sees it, in its transaction
        override protected def beforeInsert(round: Round, images: Seq[Image])(implicit session: DBSession): Unit =
          seen += sql"SELECT status FROM round WHERE id = ${round.getId}".map(_.stringOpt(1)).single().flatten: Unit
      }
      val created = TestRoundServices.roundService(handler)
        .createNewRound(Round(None, 1, Some("R"), contest.getId, distribution = 1, active = true), jurors.map(_.getId))
      // draft while its fill runs; held (paused) while the distribute it queued runs (D4)
      (seen.toSeq.distinct === Seq(Some("draft"), Some("paused"))) and (status(created.getId) === Some("active")) and
        (roundDao.findById(created.getId).get.lifecycle === Round.Status.Active)
    }

    "be read from the flags when not stored" in {
      (Round(None, 1, None, 1L, distributing = true).lifecycle === "draft") and
        (Round(None, 1, None, 1L, active = true).lifecycle === "active") and
        (Round(None, 1, None, 1L).lifecycle === "paused") and
        (Round(None, 1, None, 1L, distributing = true, status = Some("paused")).inReview must beTrue) and
        (Round(None, 1, None, 1L, status = Some("paused")).inReview must beFalse)
    }
  }
}
