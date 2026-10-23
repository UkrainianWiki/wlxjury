package services.imports

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._

/** Files deleted on Commons and restored: their votes in the open rounds moved aside, and
  * all of them back; the other rounds' votes moved aside when they open.
  */
class DeletedFilesSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private def image(id: Long) = Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(s"07-101-000$id"))

  /** Images 1 and 2, each voted on by two jurors in two active rounds. */
  private def setUp(): (Seq[Long], Seq[Long]) = {
    implicit val contest: ContestJury = createContests(10).head
    val rounds = (1 to 2).map(n => roundDao.create(Round(None, n, None, contest.getId, createdAt = now, active = true)).getId)
    val jurors = createUsers("jury", 1, 2).map(_.getId)
    val images = Seq(image(1L), image(2L))
    imageDao.batchInsert(images)
    for (img <- images; r <- rounds; j <- jurors)
      selectionDao.create(img.pageId, rate = 1, juryId = j, roundId = r, monumentId = img.monumentId)
    (rounds, jurors)
  }

  private def setRound(roundId: Long, set: SQLSyntax): Unit =
    DB.autoCommit(implicit s => sql"UPDATE rounds SET $set WHERE id = $roundId".update())

  private def rows(table: String, pageId: Long, roundId: Long): Seq[Int] = DB.readOnly { implicit s =>
    SQL(s"SELECT rate FROM $table WHERE page_id = ? AND round_id = ? ORDER BY jury_id")
      .bind(pageId, roundId).map(_.int(1)).list()
  }

  private def monumentIds(table: String, pageId: Long): Seq[Option[String]] = DB.readOnly { implicit s =>
    SQL(s"SELECT monument_id FROM $table WHERE page_id = ?").bind(pageId).map(_.stringOpt(1)).list()
  }

  private def deletedAt(pageId: Long) = DB.readOnly { implicit s =>
    sql"SELECT deleted_at FROM images WHERE page_id = $pageId".map(_.timestampOpt(1)).single().flatten
  }

  "markDeleted" should {

    "move aside the votes in the open rounds only" in {
      val (Seq(r1, r2), _) = setUp()
      setRound(r2, sqls"active = 0") // finished, or not started yet

      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L))) === Seq(r1)

      deletedAt(1L) must beSome
      rows("selection", 1L, r1) must beEmpty
      rows("selection_deleted", 1L, r1) === Seq(1, 1)
      rows("selection", 1L, r2) === Seq(1, 1)
      rows("selection", 2L, r1) === Seq(1, 1)
    }

    "count a round being created, or frozen while active, as open" in {
      val (Seq(r1, r2), _) = setUp()
      setRound(r1, sqls"active = 0, distributing = 1")
      setRound(r2, sqls"active = 0, freeze_count = 1, active_before_freeze = 1")

      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L))).toSet === Set(r1, r2)
      rows("selection", 1L, r1) must beEmpty
      rows("selection", 1L, r2) must beEmpty
    }

    "keep the current votes of a file deleted again, not those moved aside before" in {
      val (Seq(r1, r2), jurors) = setUp()
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L)))
      DB.localTx(implicit s => DeletedFiles.restore(Seq(1L)))
      jurors.foreach(j => SelectionJdbc.rate(1L, j, r2, 5))

      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L))).toSet === Set(r1, r2)

      rows("selection", 1L, r2) must beEmpty
      rows("selection_deleted", 1L, r2) === Seq(5, 5)
      rows("selection_deleted", 1L, r1) === Seq(1, 1)
    }

    "keep the monument id of the votes moved aside in step with the image" in {
      setUp()
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L)))
      ImageJdbc.updateMonumentId(1L, "07-101-0099")

      monumentIds("selection_deleted", 1L).distinct === Seq(Some("07-101-0099"))
      DB.localTx(implicit s => DeletedFiles.restore(Seq(1L)))
      monumentIds("selection", 1L).distinct === Seq(Some("07-101-0099"))
    }
  }

  "a round that opens" should {

    "move aside the votes of the files deleted while it was closed" in {
      val (Seq(r1, r2), _) = setUp()
      setRound(r2, sqls"active = 0")
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L)))

      Round.setActiveGuarded(r2, active = true) must beTrue

      rows("selection", 1L, r2) must beEmpty
      rows("selection_deleted", 1L, r2) === Seq(1, 1)
      rows("selection", 2L, r2) === Seq(1, 1)
      RoundImageCounts.get(Seq(r2))(ImageJdbc.imageCountByRounds)(r2) === 1
      rows("selection_deleted", 1L, r1) === Seq(1, 1)
    }

    "count as opening when it is frozen, to be active when unfrozen" in {
      val (Seq(_, r2), _) = setUp()
      setRound(r2, sqls"active = 0, freeze_count = 1, active_before_freeze = 0")
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L)))
      rows("selection", 1L, r2) === Seq(1, 1)

      Round.setActiveGuarded(r2, active = true)
      rows("selection", 1L, r2) must beEmpty
    }
  }

  "votes moved into an open round" should {

    "be moved aside there when a deleted file's are merged in from a finished round" in {
      val (Seq(r1, r2), _) = setUp()
      setRound(r2, sqls"active = 0") // finished: keeps the deleted file's votes
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L)))
      rows("selection", 1L, r2) === Seq(1, 1)

      DB.localTx(implicit s => SelectionJdbc.mergeRounds(targetRoundId = r1, sourceRoundId = r2))
      Round.settleDeletedFiles(r1) // as RoundService.mergeRounds does
      rows("selection", 1L, r1) must beEmpty
      rows("selection_deleted", 1L, r1) === Seq(1, 1)
      rows("selection", 2L, r1) === Seq(1, 1)
    }

    "be moved aside there when a deleted file's are set to it from a finished round" in {
      val (Seq(_, r2), _) = setUp()
      setRound(r2, sqls"active = 0")
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L)))
      implicit val contest: ContestJury = ContestJuryJdbc.findAll().head
      val r3 = roundDao.create(Round(None, 3, None, contest.getId, createdAt = now, active = true)).getId

      SelectionJdbc.setRound(1L, r2, r3)
      rows("selection", 1L, r3) must beEmpty
      rows("selection_deleted", 1L, r3) === Seq(1, 1)
    }

    "stay in a round that isn't open" in {
      val (Seq(r1, r2), _) = setUp()
      setRound(r2, sqls"active = 0")
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L)))
      setRound(r1, sqls"active = 0")
      DB.localTx(implicit s => DeletedFiles.restore(Seq(1L)))
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L))) must beEmpty

      SelectionJdbc.setRound(1L, r2, r1)
      rows("selection", 1L, r1) === Seq(1, 1) // r1's own votes win over r2's
      rows("selection", 1L, r2) must beEmpty
    }

    "keep a juror's vote in the target round over the moved one, also those moved aside" in {
      val (Seq(r1, r2), Seq(j1, _)) = setUp()
      SelectionJdbc.rate(1L, j1, r2, 5)
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L))) // both rounds open: all aside

      SelectionJdbc.setRound(1L, r1, r2)
      rows("selection_deleted", 1L, r2) === Seq(5, 1)
      rows("selection_deleted", 1L, r1) must beEmpty
    }

    "be moved aside by settle when moved after markDeleted read the rounds" in {
      val (Seq(r1, r2), _) = setUp()
      setRound(r2, sqls"active = 0")
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L)))
      // Moved into the open r1 by a transaction that committed before markDeleted did
      DB.autoCommit { implicit s =>
        sql"DELETE FROM selection_deleted WHERE page_id = 1".update()
        sql"UPDATE selection SET round_id = $r1 WHERE page_id = 1 AND round_id = $r2".update()
      }

      DeletedFiles.settle(Seq(1L)) === Seq(r1)
      rows("selection", 1L, r1) must beEmpty
      rows("selection_deleted", 1L, r1) === Seq(1, 1)
    }

    "not be moved aside by settle when the file was restored meanwhile" in {
      val (Seq(r1, _), _) = setUp()
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L)))
      DB.localTx(implicit s => DeletedFiles.restore(Seq(1L)))

      DeletedFiles.settle(Seq(1L)) must beEmpty
      rows("selection", 1L, r1) === Seq(1, 1)
    }
  }

  "restore" should {

    "clear the mark and move the votes back in every round, also those no longer open" in {
      val (Seq(r1, r2), _) = setUp()
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L)))
      Round.setActiveGuarded(r2, active = false)

      DB.localTx(implicit s => DeletedFiles.restore(Seq(1L, 2L))).toSet === Set(r1, r2)

      deletedAt(1L) must beNone
      rows("selection", 1L, r1) === Seq(1, 1)
      rows("selection", 1L, r2) === Seq(1, 1)
      DB.localTx(implicit s => ImageJdbc.deletedOrArchived(Seq(1L))) must beEmpty
    }

    "bring back the votes in the round an image was moved to" in {
      val (Seq(r1, r2), _) = setUp()
      implicit val contest: ContestJury = ContestJuryJdbc.findAll().head
      val r3 = roundDao.create(Round(None, 3, None, contest.getId, createdAt = now, active = true)).getId
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L)))
      SelectionJdbc.setRound(1L, r1, r3)

      DB.localTx(implicit s => DeletedFiles.restore(Seq(1L)))
      rows("selection", 1L, r1) must beEmpty
      rows("selection", 1L, r3) === Seq(1, 1)
      rows("selection", 1L, r2) === Seq(1, 1)
    }

    "not bring back the votes an admin removed meanwhile" in {
      val (Seq(r1, r2), Seq(j1, _)) = setUp()
      DB.autoCommit(implicit s => sql"UPDATE selection SET rate = 0 WHERE page_id = 1 AND round_id = $r2 AND jury_id = $j1".update())
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L)))
      SelectionJdbc.removeImage(1L, r1)
      SelectionJdbc.removeUnrated(r2)

      DB.localTx(implicit s => DeletedFiles.restore(Seq(1L)))
      rows("selection", 1L, r1) must beEmpty
      rows("selection", 1L, r2) === Seq(1)
      rows("selection_deleted", 1L, r2) must beEmpty
    }

    "bring the votes of a merged round back into the round it was merged into" in {
      val (Seq(r1, r2), _) = setUp()
      implicit val contest: ContestJury = ContestJuryJdbc.findAll().head
      val j3 = createUsers("jury", 3).head.getId
      selectionDao.create(1L, rate = 3, juryId = j3, roundId = r2)
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L)))
      DB.localTx(implicit s => SelectionJdbc.mergeRounds(targetRoundId = r1, sourceRoundId = r2))

      DB.localTx(implicit s => DeletedFiles.restore(Seq(1L)))
      rows("selection", 1L, r1) === Seq(1, 1, 3) // j1 and j2 of r1 win over theirs of r2
      rows("selection", 1L, r2) must beEmpty
      rows("selection_deleted", 1L, r1) must beEmpty
      rows("selection_deleted", 1L, r2) must beEmpty
    }

    "drop the votes of a round deleted meanwhile" in {
      val (Seq(r1, r2), _) = setUp()
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L)))
      DB.autoCommit(implicit s => sql"DELETE FROM rounds WHERE id = $r2".update())

      DB.localTx(implicit s => DeletedFiles.restore(Seq(1L)))
      rows("selection", 1L, r1) === Seq(1, 1)
      rows("selection", 1L, r2) must beEmpty
      rows("selection_deleted", 1L, r2) must beEmpty
    }

    "let the juror's vote win over the rows distributed meanwhile, and keep nothing aside" in {
      val (Seq(r1, _), Seq(j1, j2)) = setUp()
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L)))
      // Distributed again meanwhile: j1 hasn't voted yet, j2 has
      selectionDao.create(1L, rate = 0, juryId = j1, roundId = r1)
      selectionDao.create(1L, rate = -1, juryId = j2, roundId = r1)

      DB.localTx(implicit s => DeletedFiles.restore(Seq(1L))) must contain(r1)

      rows("selection", 1L, r1) === Seq(1, -1)
      rows("selection_deleted", 1L, r1) must beEmpty
      DB.localTx(implicit s => ImageJdbc.deletedOrArchived(Seq(1L))) must beEmpty
    }

    "change nothing for files never deleted" in {
      val (Seq(r1, _), _) = setUp()
      DB.localTx(implicit s => DeletedFiles.restore(Seq(1L, 2L))) must beEmpty
      rows("selection", 1L, r1) === Seq(1, 1)
    }
  }
}
