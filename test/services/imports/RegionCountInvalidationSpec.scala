package services.imports

import db.scalikejdbc.RegionCountCache.Counts
import db.scalikejdbc.{ImageJdbc, RegionCountCache, SharedTestDb, TestDb}
import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc.DB
import services.TestImportServices

/** The writes that change a category's region counts invalidate their cache
  * ([[RegionCountCache]]): an import adding files, "Update monuments" changing ids; and those
  * that don't, leave it.
  */
class RegionCountInvalidationSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private val template = "MonumentId"

  private def contest(id: Long): ContestJury =
    contestDao.create(Some(id), "WLE", 2015, "Ukraine", images = Some(s"Category:Files $id"),
      monumentIdTemplate = Some(template))

  /** A file whose text has its monument id. */
  private def file(id: Long, monumentId: String) =
    FakeFile(Image(id, s"File:Image$id.jpg", Some(s"url$id"), Some(s"pageUrl$id"), 640, 480, None, size = Some(1234)),
      Some(s"{{Information|description={{$template|$monumentId}}}}"))

  private def importFiles(c: ContestJury, files: FakeFile*): Unit =
    new TestImportServices(new FakeCommonsSource(files)).imageService.importFiles(c, c.images.get, "")

  /** The contest's counts as the files page reads them: from the cache. */
  private def counts(c: ContestJury): Counts = {
    val categoryId = contestDao.findById(c.getId).get.categoryId.get
    RegionCountCache.category(categoryId)(ImageJdbc.categoryRegionCounts(categoryId))
  }

  private def loads = RegionCountCache.loads.get

  "the region count cache" should {

    "count an import's new files, reloading once" in {
      val c = contest(1)
      importFiles(c, file(11, "07-101-0011"), file(12, "07-101-0012"))
      counts(c) === Counts(Map("07" -> 2), 0)
      val before = loads
      counts(c)
      loads === before // a hit

      importFiles(c, file(13, "14-101-0013"))
      counts(c) === Counts(Map("07" -> 2, "14" -> 1), 0)
      counts(c)
      loads === before + 1
    }

    "reflect Update monuments moving a file to another region" in {
      val c = contest(1)
      val files = Seq(file(11, "07-101-0011"), file(12, "07-101-0012"))
      importFiles(c, files: _*)
      counts(c) === Counts(Map("07" -> 2), 0)

      val moved = new FakeCommonsSource(Seq(file(11, "14-101-0011"), files(1)))
      new TestImportServices(moved).imageService.updateMonuments(c, c.images.get)
      counts(c) === Counts(Map("07" -> 1, "14" -> 1), 0)
    }

    "keep the counts of a file deleted on Commons, and their cache" in {
      val c = contest(1)
      importFiles(c, file(11, "07-101-0011"), file(12, "07-101-0012"))
      val before = counts(c)
      val loaded = loads
      DB.localTx { implicit s => DeletedFiles.markDeleted(Seq(11L)) }
      counts(c) === before
      loads === loaded
    }

    "keep another contest's counts when files are imported into one" in {
      val (a, b) = (contest(1), contest(2))
      importFiles(a, file(11, "07-101-0011"))
      importFiles(b, file(21, "14-101-0021"))
      counts(a)
      counts(b)
      val loaded = loads
      importFiles(a, file(12, "07-101-0012"))
      counts(b) === Counts(Map("14" -> 1), 0)
      loads === loaded // b's from the cache
      counts(a) === Counts(Map("07" -> 2), 0)
      loads === loaded + 1
    }

    "count from the database again after a restart" in {
      val c = contest(1)
      importFiles(c, file(11, "07-101-0011"), file(12, "14-101-0012"))
      val before = counts(c)
      RegionCountCache.clear()
      val loaded = loads
      counts(c) === before
      loads === loaded + 1
    }
  }
}
