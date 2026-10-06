package db

import _root_.scalikejdbc._
import db.scalikejdbc.{SharedTestDb, TestDb}
import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}

import java.nio.file.{Files, Paths}

/** V58 archives and deletes the selection rows whose image is gone, and adds the foreign
  * key FK_selection_page_id (ON DELETE CASCADE) to images.
  */
class SelectionImageForeignKeySpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  /** V58's statements, run against copies of the tables (t_selection, t_images): the
    * shared ones already have the key, and other specs use them meanwhile.
    */
  private val v58: Seq[String] = {
    val text = new String(
      Files.readAllBytes(Paths.get("conf/db/migration/default/V58__Selection_page_id_foreign_key.sql")), "UTF-8")
    text.linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n")
      .split(";\\s*\n").map(_.trim).filter(_.nonEmpty).toSeq
      .map(_.replace("selection_orphans", "t_selection_orphans")
        .replace("FK_selection_page_id", "FK_t_selection_page_id")
        .replaceAll("\\bselection\\b", "t_selection")
        .replaceAll("\\bimages\\b", "t_images"))
  }

  private def dropCopies(): Unit = DB.autoCommit { implicit s =>
    Seq("t_selection_orphans", "t_selection", "t_images").foreach(t => SQL(s"DROP TABLE IF EXISTS $t").execute.apply())
  }

  private def runV58(): Unit = DB.autoCommit { implicit s => v58.foreach(st => SQL(st).execute.apply()) }

  private def count(sql: String): Long = DB.readOnly(implicit s => SQL(sql).map(_.long(1)).single().get)

  "V58" should {

    "archive and delete the orphan rows, then add the foreign key" in {
      dropCopies()
      try {
        DB.autoCommit { implicit s =>
          sql"CREATE TABLE t_images LIKE images".execute.apply()
          sql"CREATE TABLE t_selection LIKE selection".execute.apply()
          sql"INSERT INTO t_images (page_id, title, width, height) VALUES (1, 'File:1.jpg', 1, 1), (2, 'File:2.jpg', 1, 1)".execute.apply()
          sql"""INSERT INTO t_selection (jury_id, round_id, page_id, rate, monument_id) VALUES
                (10, 1, 1, 1, '07-101-0001'), (10, 1, 2, 0, NULL), (10, 1, 3, -1, '07-101-0003'),
                (11, 2, 4, 1, NULL), (12, 2, NULL, 0, NULL)""".execute.apply()
        }

        runV58()

        count("SELECT COUNT(*) FROM t_selection") === 3 // pages 1, 2 and the NULL page id
        count("SELECT COUNT(*) FROM t_selection WHERE page_id IN (3, 4)") === 0
        DB.readOnly(implicit s =>
          sql"SELECT jury_id, round_id, page_id, rate, monument_id FROM t_selection_orphans ORDER BY page_id"
            .map(rs => (rs.long(1), rs.long(2), rs.long(3), rs.int(4), rs.stringOpt(5))).list()) ===
          List((10L, 1L, 3L, -1, Some("07-101-0003")), (11L, 2L, 4L, 1, None))
        count("""SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS
                 WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'FK_t_selection_page_id'
                   AND REFERENCED_TABLE_NAME = 't_images' AND DELETE_RULE = 'CASCADE'""") === 1

        // idempotent: a second run archives nothing twice and keeps one key
        runV58()
        count("SELECT COUNT(*) FROM t_selection_orphans") === 2
        count("SELECT COUNT(*) FROM t_selection") === 3

        // enforced: no new orphans, and deleting an image deletes its rows
        DB.autoCommit(implicit s =>
          sql"INSERT INTO t_selection (jury_id, round_id, page_id, rate) VALUES (10, 1, 5, 0)".execute.apply()) must
          throwA[java.sql.SQLException]
        DB.autoCommit(implicit s => sql"DELETE FROM t_images WHERE page_id = 1".execute.apply())
        count("SELECT COUNT(*) FROM t_selection WHERE page_id = 1") === 0
      } finally dropCopies()
    }
  }

  "the selection table" should {

    "delete an image's selection rows with the image" in {
      implicit val contest: ContestJury = createContests(10).head
      val juror = createUsers("jury", 1).head
      imageDao.batchInsert(Seq(1L, 2L).map(id => Image(id, s"File:$id.jpg", None, None, 1, 1, None)))
      Seq(1L, 2L).foreach(p => selectionDao.create(p, rate = 0, juryId = juror.getId, roundId = 1L))
      Seq(1L, 2L).foreach(p => selectionDao.create(p, rate = 1, juryId = juror.getId, roundId = 2L))

      DB.autoCommit(implicit s => sql"DELETE FROM images WHERE page_id = 1".execute.apply())

      selectionDao.findAll().map(_.pageId).toSet === Set(2L)
    }

    "refuse a selection row for an image that doesn't exist" in {
      selectionDao.create(404L, rate = 0, juryId = 1L, roundId = 1L) must throwA[java.sql.SQLException]
      selectionDao.findAll() must beEmpty
    }
  }
}
