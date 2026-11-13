package db.scalikejdbc

import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._

/** The round-first rule's run-time guard ([[RoundWrites]], W2 of
  * docs/plans/2026-11-03-write-path-simplification.md): a structural writer that hasn't
  * locked its rounds (or a file's rounds) first in its transaction fails before it writes, and
  * writes nothing; with the lock it runs.
  */
class RoundWritesGuardSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private def setUp(): (Long, Long) = {
    implicit val contest: ContestJury = createContests(10).head
    val Seq(juror) = createUsers("jury", 1)
    val round = roundDao.create(Round(None, 1, None, contest.getId, active = true)).getId
    imageDao.batchInsert(Seq(Image(1L, "File:Image1.jpg", None, None, 640, 480, Some("07-101-0001"))))
    selectionDao.create(1L, rate = 0, juryId = juror.getId, roundId = round)
    (round, juror.getId)
  }

  private def votes(round: Long): Int = VoteJdbc.byRound(round).size

  "a vote delete" should {
    "fail without its round's lock, and delete nothing" in {
      val (round, _) = setUp()
      (DB.localTx(implicit s => VoteJdbc.deleteVotes(sqls"v.round_id = $round")) must throwA[IllegalStateException](
        "without its round lock")) and (votes(round) === 1)
    }
    "fail with another round's lock only" in {
      val (round, _) = setUp()
      DB.localTx { implicit s =>
        RoundWrites.lock(Seq(round + 1))
        VoteJdbc.deleteVotes(sqls"v.round_id = $round")
      } must throwA[IllegalStateException]
    }
    "run with it" in {
      val (round, _) = setUp()
      (DB.localTx { implicit s => RoundWrites.lock(Seq(round)); VoteJdbc.deleteVotes(sqls"v.round_id = $round") } === 1) and
        (votes(round) === 0) and (RoundImageInvariants.violations(round) must beEmpty)
    }
    "not take a lock of an earlier transaction on the pool for this one's" in {
      val (round, _) = setUp()
      DB.localTx(implicit s => RoundWrites.lock(Seq(round)))
      DB.localTx(implicit s => VoteJdbc.deleteVotes(sqls"v.round_id = $round")) must throwA[IllegalStateException]
    }
  }

  "a monument id change" should {
    "fail without the file's rounds locked, and change nothing" in {
      val (round, _) = setUp()
      (DB.localTx(implicit s => ImageJdbc.updateMonumentIdIn(1L, "46-101-0001")) must throwA[IllegalStateException](
        "without its round lock")) and (ImageJdbc.findById(1L).flatMap(_.monumentId) === Some("07-101-0001")) and
        (VoteJdbc.byRound(round).flatMap(_.monumentId) must beEmpty)
    }
    "run with them" in {
      val (round, _) = setUp()
      DB.localTx { implicit s => RoundWrites.lockForFiles(Seq(1L)); ImageJdbc.updateMonumentIdIn(1L, "46-101-0001") }
      (VoteJdbc.byRound(round).flatMap(_.monumentId) === Seq("46-101-0001")) and
        (RoundImageInvariants.violations(round) must beEmpty)
    }
  }

  "round_image's sync and a move aside" should {
    "fail without the round's lock" in {
      val (round, _) = setUp()
      (DB.localTx(implicit s => RoundImageJdbc.sync(round, Seq(1L))) must throwA[IllegalStateException]) and
        (DB.localTx(implicit s => ImageJdbc.archiveVotes(Seq(1L), Set(round))) must throwA[IllegalStateException]) and
        (votes(round) === 1)
    }
    "outside a transaction too" in {
      val (round, _) = setUp()
      RoundImageJdbc.sync(round, Seq(1L))(AutoSession) must throwA[IllegalStateException]("outside a transaction")
    }
  }

  "an auto-commit session (DB.autoCommit)" should {
    "be refused by the round lock, and give a vote's insert a transaction of its own" in {
      val (round, juror) = setUp()
      imageDao.batchInsert(Seq(Image(2L, "File:Image2.jpg", None, None, 640, 480, Some("07-101-0002"))))
      (DB.autoCommit(implicit s => RoundWrites.lock(Seq(round))) must throwA[IllegalStateException]("auto-commit")) and {
        DB.autoCommit(implicit s => selectionDao.create(2L, rate = 0, juryId = juror, roundId = round))
        (votes(round) === 2) and (RoundImageJdbc.find(round, 2L).map(_.status) === Some("in")) and
          (RoundImageInvariants.violations(round) must beEmpty)
      }
    }
    "not count a lock that ended with its statement for a structural write" in {
      val (round, _) = setUp()
      // as the round locks outside RoundWrites record theirs (Round.isOpen, RoundConflicts)
      (DB.autoCommit { implicit s =>
        sql"SELECT id FROM round WHERE id = $round FOR UPDATE".map(_.long(1)).single()
        RoundWrites.recordRounds(Seq(round))
        VoteJdbc.deleteVotes(sqls"v.round_id = $round")
      } must throwA[IllegalStateException]("outside a transaction")) and (votes(round) === 1)
    }
  }

  "disqualifying and requalifying (P1b)" should {
    "fail without the round's lock, and write nothing" in {
      val (round, _) = setUp()
      (DB.localTx(implicit s => ImageJdbc.moveVotesAsideInRound(round, Seq(1L))) must throwA[IllegalStateException]) and
        (DB.localTx(implicit s => ImageJdbc.moveVotesBackInRound(round, Seq(1L))) must throwA[IllegalStateException]) and
        (DB.localTx(implicit s =>
          RoundImageJdbc.disqualify(round, Map(1L -> "x"), RoundImageJdbc.DqCode.Coi, None, Map(1L -> "in"))) must
          throwA[IllegalStateException]) and
        (votes(round) === 1) and (RoundImageJdbc.find(round, 1L).map(_.status) === Some("in"))
    }
    "run with it, and the restore from Commons leave the votes aside" in {
      val (round, _) = setUp()
      DB.localTx { implicit s =>
        RoundWrites.lock(Seq(round))
        val prior = RoundImageJdbc.statuses(round, Seq(1L))
        ImageJdbc.moveVotesAsideInRound(round, Seq(1L))
        RoundImageJdbc.disqualify(round, Map(1L -> "x"), RoundImageJdbc.DqCode.Coi, None, prior)
      } === 1
      val restored = DB.localTx { implicit s =>
        ImageJdbc.lockRoundsOfArchived(Seq(1L)); ImageJdbc.lockFiles(Seq(1L)); ImageJdbc.restoreVotes(Seq(1L))
      }
      (restored must beEmpty) and (votes(round) === 0) and
        (RoundImageJdbc.find(round, 1L).map(_.status) === Some("dq")) and
        (DB.localTx(implicit s => ImageJdbc.deletedOrArchived(Seq(1L))) must beEmpty) and
        (RoundImageInvariants.violations(round) must beEmpty)
    }
  }

  "a restore (W2's finding 3)" should {
    "lock a round whose votes aside its caller's lookup missed, and restore them" in {
      val (r1, juror) = setUp()
      val contestId = roundDao.findById(r1).get.contestId
      val r2 = roundDao.create(Round(None, 2, None, contestId, active = true)).getId
      selectionDao.create(1L, rate = 0, juryId = juror, roundId = r2)
      // both rounds' votes aside, as a deletion on Commons leaves them
      DB.localTx { implicit s => RoundWrites.lock(Seq(r1, r2)); ImageJdbc.archiveVotes(Seq(1L), Set(r1, r2)) }
      // the caller locked r1 only (its lookup ran before r2's rows were aside)
      val restored = DB.localTx { implicit s =>
        RoundWrites.lock(Seq(r1))
        ImageJdbc.lockFiles(Seq(1L))
        ImageJdbc.restoreVotes(Seq(1L))
      }
      (restored.sorted === Seq(r1, r2)) and (votes(r1) === 1) and (votes(r2) === 1) and
        (RoundImageInvariants.violations(r1) must beEmpty) and (RoundImageInvariants.violations(r2) must beEmpty)
    }
  }

  "a juror's vote" should {
    "need no round lock (row-level)" in {
      val (round, juror) = setUp()
      VoteJdbc.rate(1L, juror, round, 1) === 1
    }
  }
}
