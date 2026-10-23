package services

import db.scalikejdbc._
import org.specs2.mutable.Specification
import org.specs2.specification.{AfterAll, BeforeAll, BeforeEach}
import scalikejdbc._
import services.imports.{CommonsFileCheck, DeletedFiles, FakeCommonsSource, FakeFile}

import scala.concurrent.Await
import scala.concurrent.duration._

/** The thumbnail download job asks Commons ([[CommonsFileCheck]]) about the files whose
  * thumbnails Wikimedia doesn't have. Wikimedia is a local server ([[FakeWikimedia]]);
  * Commons is a fake.
  */
class LocalImageCacheFileCheckSpec
    extends Specification
    with TestDb
    with FakeWikimedia
    with BeforeAll
    with BeforeEach
    with AfterAll {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()
  override def afterAll(): Unit = stopWikimedia()

  "the thumbnail download job" should {

    "download a renamed file under its new name, a re-uploaded one for its new size, and count deleted files apart from the errors" in {
      val old = commonsImage(1L, "Old_name")
      val deleted = commonsImage(2L, "Gone")
      val failing = commonsImage(3L, "Same")
      val broken = commonsImage(4L, "Error_file")
      val again = commonsImage(5L, "Reuploaded_file")
      imageDao.batchInsert(Seq(old, deleted, failing, broken, again))
      val source = new FakeCommonsSource(Seq(renamed(old, "New_name"), failing, reuploaded(again)).map(FakeFile(_)))
      val check = new CommonsFileCheck(source, batchWindow = 20.millis)
      val cache = new LocalImageCacheService(thumbsConfig(), system, Some(check))

      Await.result(cache.runDownload(1L, Seq(old, deleted, failing, broken, again)), 30.seconds)
      check.stop()

      val progress = cache.progress(1L)
      (progress.done, progress.renamed, progress.deleted, progress.errors, progress.running) === (5, 1, 1, 2, false)
      ImageJdbc.findById(1L).map(_.title) must beSome("File:New name.jpg")
      ImageJdbc.findById(5L).map(_.width) must beSome(8000)
      DB.readOnly { implicit s =>
        sql"SELECT page_id FROM images WHERE deleted_at IS NOT NULL".map(_.long(1)).list()
      } === List(2L)
      // the four 404s, one at a time, and whether the absent one is missing; not the 500
      source.detailCalls.get === 5
    }
  }

  "the contest's images" should {
    "leave out the files deleted on Commons, in the list and the count" in {
      val contest = createContests(10).head
      val images = Seq(commonsImage(1L, "Kept"), commonsImage(2L, "Gone"))
      imageDao.batchInsert(images)
      DB.localTx { implicit s =>
        val categoryId = sql"INSERT INTO category (title) VALUES ('Category:Test')".updateAndReturnGeneratedKey()
        sql"UPDATE contest_jury SET category_id = $categoryId WHERE id = ${contest.getId}".update()
        images.foreach(i => sql"INSERT INTO category_members (category_id, page_id) VALUES ($categoryId, ${i.pageId})".update())
        DeletedFiles.markDeleted(Seq(2L))
      }
      ImageJdbc.findByContestId(contest.getId).map(_.pageId) === List(1L)
      ImageJdbc.countByContest(contestDao.findById(contest.getId).get) === 1L
    }
  }
}
