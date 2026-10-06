package db

import db.scalikejdbc.{SharedTestDb, TestDb}
import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import _root_.scalikejdbc._

import java.nio.file.{Files, Paths}

/** V57 copies images.monument_id into the selection rows that drifted from it. */
class MonumentIdResyncMigrationSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private val v57 = new String(
    Files.readAllBytes(Paths.get("conf/db/migration/default/V57__Resync_selection_monument_id.sql")),
    "UTF-8")
  private val statement = v57.linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n").trim.stripSuffix(";")

  "V57" should {
    "copy the image's monument id into drifted selection rows only" in {
      implicit val contest: ContestJury = createContests(10).head
      val juror = createUsers("jury", 1).head
      imageDao.batchInsert(Seq(
        Image(1L, "File:1.jpg", None, None, 640, 480, Some("14-101-0001")),
        Image(2L, "File:2.jpg", None, None, 640, 480, Some("07-101-0002")),
        Image(3L, "File:3.jpg", None, None, 640, 480, None)))
      selectionDao.create(1L, 0, juror.getId, 1L, monumentId = Some("07-101-0001")) // drifted
      selectionDao.create(2L, 0, juror.getId, 1L, monumentId = Some("07-101-0002")) // in step
      selectionDao.create(3L, 0, juror.getId, 1L, monumentId = Some("07-101-0003")) // image lost its id
      selectionDao.create(4L, 0, juror.getId, 1L, monumentId = Some("07-101-0004")) // no image: kept

      DB.autoCommit(implicit s => SQL(statement).update.apply()) === 2
      selectionDao.findAll().sortBy(_.pageId).map(_.monumentId) ===
        Seq(Some("14-101-0001"), Some("07-101-0002"), None, Some("07-101-0004"))
      DB.autoCommit(implicit s => SQL(statement).update.apply()) === 0
    }
  }
}
