package services.imports

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._
import services.distribution.RoundDistributionHandler
import services.jobs.{JobJdbc, NewJob}

/** Files deleted on Commons and restored: their votes moved aside and back, but those of
  * the rounds a distribution is filling.
  */
class DeletedFilesSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private def image(id: Long) = Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(s"07-101-000$id"))

  /** Images 1 and 2, each voted on by two jurors in two rounds. */
  private def setUp(): (Seq[Long], Seq[Long]) = {
    implicit val contest: ContestJury = createContests(10).head
    val rounds = (1 to 2).map(n => roundDao.create(Round(None, n, None, contest.getId, createdAt = now)).getId)
    val jurors = createUsers("jury", 1, 2).map(_.getId)
    val images = Seq(image(1L), image(2L))
    imageDao.batchInsert(images)
    for (img <- images; r <- rounds; j <- jurors)
      selectionDao.create(img.pageId, rate = 1, juryId = j, roundId = r, monumentId = img.monumentId)
    (rounds, jurors)
  }

  /** An active distribution job for the round; returns a function that ends it. */
  private def distributing(roundId: Long): () => Unit = {
    val id = DB.localTx { implicit s =>
      JobJdbc.insert(
        NewJob(RoundDistributionHandler.Kind, subjectType = Some(RoundDistributionHandler.SubjectType),
          subjectId = Some(roundId), dedupeKey = Some(RoundDistributionHandler.dedupeKey(roundId))),
        owner = None)
    }
    () => DB.autoCommit(implicit s => sql"UPDATE job SET status = 'done' WHERE id = $id".update())
  }

  private def rows(table: String, pageId: Long, roundId: Long): Seq[Int] = DB.readOnly { implicit s =>
    SQL(s"SELECT rate FROM $table WHERE page_id = ? AND round_id = ? ORDER BY jury_id")
      .bind(pageId, roundId).map(_.int(1)).list()
  }

  private def deletedAt(pageId: Long) = DB.readOnly { implicit s =>
    sql"SELECT deleted_at FROM images WHERE page_id = $pageId".map(_.timestampOpt(1)).single().flatten
  }

  "markDeleted" should {

    "leave the votes in a round being distributed, which its end moves aside" in {
      val (Seq(r1, r2), _) = setUp()
      val end = distributing(r2)

      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L))) === Seq(r1)

      deletedAt(1L) must beSome
      rows("selection", 1L, r1) must beEmpty
      rows("selection_deleted", 1L, r1) === Seq(1, 1)
      rows("selection", 1L, r2) === Seq(1, 1)
      rows("selection", 2L, r1) === Seq(1, 1)

      end()
      DB.localTx(implicit s => ImageJdbc.archiveDeletedVotesInRound(r2)) === Seq(1L)
      rows("selection", 1L, r2) must beEmpty
      rows("selection_deleted", 1L, r2) === Seq(1, 1)
      rows("selection", 2L, r2) === Seq(1, 1)
    }

    "keep the current votes of a file deleted again, not those moved aside before" in {
      val (Seq(r1, r2), jurors) = setUp()
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L)))
      val end = distributing(r2)
      DB.localTx(implicit s => DeletedFiles.restore(Seq(1L))) === Seq(r1)
      rows("selection_deleted", 1L, r2) === Seq(1, 1) // its round was being distributed
      end()
      // "Distribute new files" gives the restored file to the round's jurors again, who vote
      jurors.foreach(j => selectionDao.create(1L, rate = 5, juryId = j, roundId = r2))

      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L))).toSet === Set(r1, r2)

      rows("selection", 1L, r2) must beEmpty
      rows("selection_deleted", 1L, r2) === Seq(5, 5)
      rows("selection_deleted", 1L, r1) === Seq(1, 1)
    }
  }

  "restore" should {

    "clear the mark and move the votes back, but those of a round being distributed" in {
      val (Seq(r1, r2), _) = setUp()
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L)))
      val end = distributing(r2)

      DB.localTx(implicit s => DeletedFiles.restore(Seq(1L, 2L))) === Seq(r1)

      deletedAt(1L) must beNone
      rows("selection", 1L, r1) === Seq(1, 1)
      rows("selection_deleted", 1L, r1) must beEmpty
      rows("selection", 1L, r2) must beEmpty
      rows("selection_deleted", 1L, r2) === Seq(1, 1)

      end()
      DB.localTx(implicit s => DeletedFiles.restore(Seq(1L))) === Seq(r2)
      rows("selection", 1L, r2) === Seq(1, 1)
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
