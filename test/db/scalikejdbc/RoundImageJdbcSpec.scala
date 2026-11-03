package db.scalikejdbc

import db.scalikejdbc.RoundImageJdbc.{Origin, Status, SyncResult}
import org.intracer.wmua.{ContestJury, Image, Vote}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._
import services.imports.DeletedFiles

/** round_image (V84) kept in step with the votes by every writer of vote and vote_aside
  * (docs/plans/2026-11-02-round-images-two-step.md, §5, P1), and [[RoundImageInvariants]]
  * finding each kind of drift. Service-level writers use auto-commit sessions: truncation,
  * not AutoRollback.
  */
class RoundImageJdbcSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private def image(id: Long, monumentId: Option[String] = None) =
    Image(id, s"File:Image$id.jpg", None, None, 640, 480, monumentId.orElse(Some(s"07-101-000$id")))

  /** Images 1-3; two open rounds; two jurors; every image to both jurors in round 1, images 1
    * and 2 to juror 1 in round 2.
    */
  private def setUp(): (Long, Long, Seq[Long]) = {
    implicit val contest: ContestJury = createContests(10).head
    val r1 = roundDao.create(Round(None, 1, None, contest.getId, active = true)).getId
    val r2 = roundDao.create(Round(None, 2, None, contest.getId, active = true)).getId
    val jurors = createUsers("jury", 1, 2).map(_.getId)
    val images = (1L to 3L).map(image(_))
    imageDao.batchInsert(images)
    for (img <- images; j <- jurors) selectionDao.create(img.pageId, rate = 0, juryId = j, roundId = r1, monumentId = img.monumentId)
    for (p <- Seq(1L, 2L)) selectionDao.create(p, rate = 0, juryId = jurors.head, roundId = r2)
    (r1, r2, jurors)
  }

  private def statuses(roundId: Long): Seq[(Long, String)] = RoundImageJdbc.byRound(roundId).map(r => r.pageId -> r.status)

  private def clean(rounds: Long*) = forall(rounds)(r => RoundImageInvariants.violations(r) must beEmpty)

  private def exec(statement: SQLSyntax): Unit = DB.autoCommit(implicit s => sql"$statement".update(): Unit)

  "a vote insert" should {

    "add its file to the round, 'in', with the image's monument id, in the vote's transaction" in {
      val (r1, r2, _) = setUp()
      val row = RoundImageJdbc.find(r2, 1L).get
      (statuses(r1) === Seq(1L -> "in", 2L -> "in", 3L -> "in")) and
        (statuses(r2) === Seq(1L -> "in", 2L -> "in")) and
        ((row.addedHow, row.addedByJobId, row.monumentId, row.region) === (("manual", None, Some("07-101-0001"), Some("07")))) and
        (DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM round_image WHERE added_at IS NULL".map(_.int(1)).single()) === Some(0)) and
        clean(r1, r2)
    }

    "add nothing when its transaction rolls back, and record a batch's origin" in {
      val (r1, _, jurors) = setUp()
      imageDao.batchInsert(Seq(image(4L)))
      DB.localTx { implicit s =>
        VoteJdbc.batchInsert(Seq(Vote(4L, jurors.head, r1, 0)), Origin(RoundImageJdbc.AddedHow.Fill, Some(77L)))
        s.connection.rollback()
      }
      val rolledBack = RoundImageJdbc.find(r1, 4L)
      VoteJdbc.batchInsert(Seq(Vote(4L, jurors.head, r1, 0), Vote(4L, jurors(1), r1, 0)),
        Origin(RoundImageJdbc.AddedHow.NewFiles, Some(78L)))
      (rolledBack must beNone) and
        (RoundImageJdbc.find(r1, 4L).map(r => (r.status, r.addedHow, r.addedByJobId)) === Some(("in", "new_files", Some(78L)))) and
        clean(r1)
    }
  }

  "the bulk writers" should {

    "remove a file's row with its votes (removeImage), and only then" in {
      val (r1, r2, _) = setUp()
      TestVoteWrites.removeImage(1L, r1)
      (statuses(r1) === Seq(2L -> "in", 3L -> "in")) and (statuses(r2) === Seq(1L -> "in", 2L -> "in")) and clean(r1, r2)
    }

    "keep the files with a rated vote left when the unrated votes go (removeUnrated)" in {
      val (r1, _, jurors) = setUp()
      VoteJdbc.rate(2L, jurors.head, r1, 1)
      TestVoteWrites.removeUnrated(r1)
      (statuses(r1) === Seq(2L -> "in")) and clean(r1)
    }

    "move a file from one round to the other (setRound): out of one, into the other" in {
      val (r1, r2, _) = setUp()
      TestVoteWrites.setRound(3L, r1, r2)
      val moved = RoundImageJdbc.find(r2, 3L)
      (statuses(r1) === Seq(1L -> "in", 2L -> "in")) and
        (statuses(r2) === Seq(1L -> "in", 2L -> "in", 3L -> "in")) and
        (moved.map(_.addedHow) === Some("manual")) and (moved.flatMap(_.monumentId) === Some("07-101-0003")) and
        {
          TestVoteWrites.setRound(1L, r1, r2) // into a round that has the file: stays in
          (statuses(r1) === Seq(2L -> "in")) and (statuses(r2).map(_._1) === Seq(1L, 2L, 3L)) and clean(r1, r2)
        }
    }

    "mark a file deleted on Commons 'deleted' in the open rounds only, and 'in' again when restored" in {
      val (r1, r2, _) = setUp()
      exec(sqls"UPDATE round SET active = 0 WHERE id = $r2")
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L)))
      val deleted = (statuses(r1), statuses(r2))
      DB.localTx(implicit s => DeletedFiles.restore(Seq(1L)))
      (deleted === ((Seq(1L -> "deleted", 2L -> "in", 3L -> "in"), Seq(1L -> "in", 2L -> "in")))) and
        (statuses(r1) === Seq(1L -> "in", 2L -> "in", 3L -> "in")) and clean(r1, r2)
    }

    "mark it 'deleted' when a round that kept its votes opens" in {
      val (r1, r2, _) = setUp()
      exec(sqls"UPDATE round SET active = 0 WHERE id = $r2")
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(2L)))
      Round.setActiveGuarded(r2, active = true)
      (statuses(r2) === Seq(1L -> "in", 2L -> "deleted")) and clean(r1, r2)
    }

    "drop the rows of a round deleted while a file's votes were aside, on restore" in {
      val (r1, r2, _) = setUp()
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L)))
      exec(sqls"DELETE FROM round WHERE id = $r2")
      DB.localTx(implicit s => DeletedFiles.restore(Seq(1L)))
      (statuses(r2) must beEmpty) and (statuses(r1).map(_._2).distinct === Seq("in")) and clean(r1)
    }

    "follow a monument id change" in {
      val (r1, r2, _) = setUp()
      ImageJdbc.updateMonumentId(1L, "46-101-0042")
      (RoundImageJdbc.find(r1, 1L).map(r => (r.monumentId, r.region)) === Some((Some("46-101-0042"), Some("46")))) and
        (RoundImageJdbc.find(r2, 1L).flatMap(_.region) === Some("46")) and clean(r1, r2)
    }

    "go with a deleted round or image (the foreign keys)" in {
      val (r1, r2, _) = setUp()
      exec(sqls"DELETE FROM round WHERE id = $r2")
      exec(sqls"DELETE FROM images WHERE page_id = 3")
      (statuses(r2) must beEmpty) and (statuses(r1) === Seq(1L -> "in", 2L -> "in")) and clean(r1)
    }
  }

  "sync" should {

    "set each pair's row from its votes: in, deleted (dq kept), none; add a missing one as its origin" in {
      val (r1, _, _) = setUp()
      imageDao.batchInsert(Seq(image(4L), image(5L)))
      exec(sqls"INSERT INTO vote_aside SELECT * FROM vote WHERE round_id = $r1 AND page_id = 1")
      exec(sqls"DELETE FROM vote WHERE round_id = $r1 AND page_id IN (1, 2)")
      exec(sqls"INSERT INTO vote_aside (page_id, jury_id, round_id, rate) VALUES (4, 1, $r1, 0), (5, 1, $r1, 0)")
      exec(sqls"INSERT INTO round_image (round_id, page_id, status) VALUES ($r1, 5, 'dq')")
      val result = DB.localTx { implicit s =>
        RoundWrites.lock(Seq(r1))
        RoundImageJdbc.sync(r1, Seq(1L, 2L, 3L, 4L, 5L, 6L), Origin(RoundImageJdbc.AddedHow.Repair, Some(5L)))
      }
      (result === SyncResult(added = 1, statusChanged = 1, removed = 1)) and
        (statuses(r1) === Seq(1L -> "deleted", 3L -> "in", 4L -> "deleted", 5L -> "dq")) and
        (RoundImageJdbc.find(r1, 4L).map(r => (r.addedHow, r.addedByJobId)) === Some(("repair", Some(5L)))) and
        (DB.localTx { implicit s => RoundWrites.lock(Seq(r1)); RoundImageJdbc.sync(r1, Seq(1L, 2L, 3L, 4L, 5L)) } === SyncResult())
    }

    "add no row in a round that doesn't exist" in {
      val (r1, _, _) = setUp()
      exec(sqls"INSERT INTO vote (page_id, jury_id, round_id, rate) VALUES (1, 1, 999, 0)")
      DB.localTx { implicit s => RoundWrites.lock(Seq(999L)); RoundImageJdbc.sync(999L, Seq(1L)) } === SyncResult()
    }
  }

  "the reads" should {
    "count the files in, and all of them" in {
      val (r1, r2, _) = setUp()
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L)))
      (RoundImageJdbc.countsIn(Seq(r1, r2, 999L)) === Map(r1 -> 2, r2 -> 1)) and
        (RoundImageJdbc.countAll(r1) === 3L) and (RoundImageJdbc.countIn(r1) === 2L) and
        (RoundImageJdbc.isIn(r1, 1L) must beFalse) and (RoundImageJdbc.isIn(r1, 2L) must beTrue) and
        (ImageJdbc.imageCountByRounds(Seq(r1, r2)) === Map(r1 -> 2, r2 -> 1))
    }
  }

  "the invariants" should {

    def found(roundId: Long) = RoundImageInvariants.violations(roundId).map(v => v.invariant -> v.pageId)

    "find a file with votes and no 'in' row (1)" in {
      val (r1, _, _) = setUp()
      exec(sqls"DELETE FROM round_image WHERE round_id = $r1 AND page_id = 2")
      found(r1) === Seq(1 -> 2L)
    }

    "find a pair aside with no 'deleted' or 'dq' row (2), and a 'deleted' row with votes (3)" in {
      val (r1, _, _) = setUp()
      exec(sqls"INSERT INTO vote_aside SELECT * FROM vote WHERE round_id = $r1 AND page_id = 1")
      exec(sqls"DELETE FROM vote WHERE round_id = $r1 AND page_id = 1")
      exec(sqls"UPDATE round_image SET status = 'deleted' WHERE round_id = $r1 AND page_id = 2")
      exec(sqls"UPDATE images SET deleted_at = NOW(3) WHERE page_id = 2")
      // 2 has votes and no 'in' row (1); 1 is 'in' with no votes, 2 'deleted' with none aside (8)
      found(r1) === Seq(1 -> 2L, 2 -> 1L, 3 -> 2L, 8 -> 1L, 8 -> 2L)
    }

    "find a 'deleted' row of a file not marked deleted (4) and a wrong monument id (6)" in {
      val (r1, _, _) = setUp()
      exec(sqls"INSERT INTO vote_aside SELECT * FROM vote WHERE round_id = $r1 AND page_id = 1")
      exec(sqls"DELETE FROM vote WHERE round_id = $r1 AND page_id = 1")
      exec(sqls"UPDATE round_image SET status = 'deleted' WHERE round_id = $r1 AND page_id = 1")
      exec(sqls"UPDATE round_image SET monument_id = NULL WHERE round_id = $r1 AND page_id = 3")
      found(r1) === Seq(4 -> 1L, 6 -> 3L)
    }

    "find a row with no votes (8) and a criteria rate of no vote (7)" in {
      val (r1, _, _) = setUp()
      exec(sqls"DELETE FROM vote WHERE round_id = $r1 AND page_id = 3")
      exec(sqls"INSERT INTO criteria_rate (vote_id, criterion_id, rate) VALUES (99999, 6, 1)")
      (found(r1) === Seq(8 -> 3L)) and (RoundImageInvariants.criteriaOrphans().size === 1) and
        (RoundImageInvariants.all().map(_.invariant).sorted === Seq(7, 8))
    }

    "ignore the votes of a round that doesn't exist" in {
      val (r1, _, _) = setUp()
      exec(sqls"INSERT INTO vote (page_id, jury_id, round_id, rate) VALUES (1, 1, 999, 0)")
      (RoundImageInvariants.violations(999L) must beEmpty) and clean(r1)
    }
  }
}
