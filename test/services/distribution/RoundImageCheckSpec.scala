package services.distribution

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import play.api.libs.json.Json
import scalikejdbc._
import services.TestRoundServices
import services.jobs.{Job, JobJdbc, JobStatus, NewJob}

/** The round_image_check job (RoundImageCheckHandler): drift injected by raw SQL, as an older
  * release or a hand edit would leave it, repaired window by window; what it can't repair
  * reported; criteria rates of no vote archived; a round being distributed left out.
  */
class RoundImageCheckSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  // windows of 3 rows: several per round
  private val handler = new RoundImageCheckHandler(windowRows = 3, orphanWindow = 2)
  private val distribution = TestRoundServices.handler()
  private val scheduler = TestRoundServices.inlineScheduler(handler, distribution)

  private def exec(statement: SQLSyntax): Unit = DB.autoCommit(implicit s => sql"$statement".update(): Unit)

  private def run(job: NewJob): Job = {
    val queued = DB.localTx(implicit s => scheduler.enqueue(job)).toOption.get
    scheduler.started(queued.id)
    JobJdbc.findById(queued.id).get
  }

  private def details(job: Job) = handler.details(job)

  /** Who queued the job: its created_by only (the controller checks the roles). */
  private def root: User = User("root", "root@example.com", id = Some(1L))

  /** Images 1-12, two rounds of two jurors: round 1 all twelve, round 2 images 1-4. */
  private def setUp(): (Long, Long) = {
    implicit val contest: ContestJury = createContests(10).head
    val r1 = roundDao.create(Round(None, 1, None, contest.getId, active = true)).getId
    val r2 = roundDao.create(Round(None, 2, None, contest.getId, active = true)).getId
    val images = (1L to 12L).map(i => Image(i, s"File:Image$i.jpg", None, None, 640, 480, Some(s"07-101-$i")))
    imageDao.batchInsert(images)
    for (p <- 1L to 12L; j <- Seq(1L, 2L)) selectionDao.create(p, rate = 1, juryId = j, roundId = r1)
    for (p <- 1L to 4L) selectionDao.create(p, rate = 1, juryId = 1L, roundId = r2)
    (r1, r2)
  }

  /** What an older release would leave in round 1: votes added (files 13, 14), moved aside
    * (file 5, deleted on Commons) and deleted (file 6), without round_image; a row of no votes (file 7), a
    * stale monument id (file 8), a 'deleted' row of a file restored by hand (file 9); and a
    * criteria rate of no vote.
    */
  private def drift(r1: Long): Unit = {
    imageDao.batchInsert(Seq(13L, 14L).map(i => Image(i, s"File:Image$i.jpg", None, None, 640, 480, Some(s"46-101-$i"))))
    exec(sqls"INSERT INTO vote (page_id, jury_id, round_id, rate) VALUES (13, 1, $r1, 0), (14, 1, $r1, 0)")
    exec(sqls"INSERT INTO vote_aside SELECT * FROM vote WHERE round_id = $r1 AND page_id IN (5, 9)")
    exec(sqls"DELETE FROM vote WHERE round_id = $r1 AND page_id IN (5, 6, 7, 9)")
    exec(sqls"INSERT INTO vote (page_id, jury_id, round_id, rate) VALUES (7, 1, $r1, 0)")
    exec(sqls"DELETE FROM vote WHERE round_id = $r1 AND page_id = 7")
    exec(sqls"UPDATE images SET monument_id = '80-361-0008' WHERE page_id = 8")
    exec(sqls"UPDATE images SET deleted_at = NOW(3) WHERE page_id = 5")
    exec(sqls"UPDATE round_image SET status = 'deleted' WHERE round_id = $r1 AND page_id = 9")
    exec(sqls"INSERT INTO criteria_rate (vote_id, criterion_id, rate) VALUES (99998, 6, 3), (99999, 7, 2)")
  }

  "the round_image_check job" should {

    "repair every round's drift, report a 'deleted' row of a file not marked deleted, archive rates of no vote" in {
      val (r1, r2) = setUp()
      drift(r1)
      val before = RoundImageInvariants.all().map(v => v.invariant -> v.pageId)
      val job = run(RoundImageCheckHandler.allJob(root))
      val d = details(job)
      val after = RoundImageInvariants.all().map(v => v.invariant -> v.pageId)
      (before must containAllOf(Seq(1 -> 13L, 1 -> 14L, 2 -> 5L, 6 -> 8L, 4 -> 9L, 8 -> 6L, 8 -> 7L))) and
        (job.status === JobStatus.Done) and
        // 13, 14 added; 5 and 9 'deleted'... 9 was 'deleted' already, 5 now; 6, 7 removed; 8's monument id
        ((d \ "added").as[Long] === 2L) and ((d \ "statusFixed").as[Long] === 1L) and ((d \ "removed").as[Long] === 2L) and
        ((d \ "monumentFixed").as[Long] === 1L) and ((d \ "notMarkedDeleted").as[Long] === 1L) and
        ((d \ "orphansArchived").as[Long] === 2L) and ((d \ "rounds").as[Long] === 2L) and
        ((d \ "drift").as[Seq[String]].size === 1) and
        (after === Seq(4 -> 9L)) and
        (RoundImageJdbc.find(r1, 13L).map(r => (r.status, r.addedHow, r.addedByJobId, r.region)) ===
          Some(("in", "repair", Some(job.id), Some("46")))) and
        (RoundImageJdbc.find(r1, 5L).map(_.status) === Some("deleted")) and
        (RoundImageJdbc.byRound(r2).size === 4) and
        (DB.readOnly(implicit s => sql"SELECT vote_id, criteria, rate, reason FROM criteria_rate_orphan ORDER BY vote_id"
          .map(rs => (rs.long(1), rs.long(2), rs.int(3), rs.string(4))).list()) ===
          List((99998L, 6L, 3, "orphan"), (99999L, 7L, 2, "orphan")))
    }

    "find nothing to do on a clean database, and nothing twice" in {
      val (r1, _) = setUp()
      drift(r1)
      run(RoundImageCheckHandler.allJob(root))
      val second = details(run(RoundImageCheckHandler.allJob(root)))
      Seq("added", "statusFixed", "removed", "monumentFixed", "orphansArchived").map(k => (second \ k).as[Long]) ===
        Seq(0L, 0L, 0L, 0L, 0L)
    }

    "check only its own round" in {
      val (r1, r2) = setUp()
      drift(r1)
      exec(sqls"DELETE FROM round_image WHERE round_id = $r2 AND page_id = 1")
      val d = details(run(RoundImageCheckHandler.roundJob(r2, 10L, root)))
      ((d \ "added").as[Long] === 1L) and ((d \ "rounds").as[Long] === 1L) and
        (RoundImageInvariants.violations(r2) must beEmpty) and
        (RoundImageInvariants.violations(r1) must not(beEmpty)) and
        ((d \ "orphansArchived").as[Long] === 0L)
    }

    "leave out a round whose distribution is queued or running" in {
      val (r1, r2) = setUp()
      drift(r1)
      DB.localTx(implicit s => scheduler.enqueue(NewJob(kind = RoundDistributionHandler.Kind, contestId = Some(10L),
        subjectType = Some("round"), subjectId = Some(r1), dedupeKey = Some(RoundDistributionHandler.dedupeKey(r1)),
        params = Json.obj("roundId" -> r1))))
      val d = details(run(RoundImageCheckHandler.allJob(root)))
      ((d \ "skipped").as[Seq[Long]] === Seq(r1)) and ((d \ "rounds").as[Long] === 1L) and
        (RoundImageInvariants.violations(r1) must not(beEmpty)) and (RoundImageInvariants.violations(r2) must beEmpty)
    }
  }
}
