package controllers

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{AfterAll, BeforeAll, BeforeEach}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import scalikejdbc._
import services.imports.{CommonsFileCheck, FakeCommonsSource, FakeFile}
import services.{FakeWikimedia, LocalImageCacheService}

import scala.concurrent.duration._

/** A thumbnail request for a stored file that Wikimedia doesn't have asks Commons
  * ([[CommonsFileCheck]]) whether the file was renamed or deleted. Wikimedia is a local
  * server ([[FakeWikimedia]]); Commons is a fake.
  */
class ImageProxyFileCheckSpec
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

  private def thumb(name: String) = s"a/ab/$name.jpg/333px-$name.jpg"

  private val signedIn = FakeRequest().withSession(Secured.UserName -> "juror@example.org")

  /** The controller, with an empty local cache and the files Commons has. */
  private def controller(commons: Seq[Image]): (ImageProxyController, FakeCommonsSource, CommonsFileCheck) = {
    val source = new FakeCommonsSource(commons.map(FakeFile(_)))
    val check = new CommonsFileCheck(source, batchWindow = 20.millis)
    val proxy = new ImageProxyController(stubControllerComponents(), new LocalImageCacheService(thumbsConfig(), system, Some(check)))
    (proxy, source, check)
  }

  /** The image, voted on by one juror in one round. */
  private def store(img: Image): Unit = {
    implicit val contest: ContestJury = createContests(10).head
    val round = roundDao.create(Round(None, 1, None, contest.getId, createdAt = now)).getId
    val juror = createUsers("jury", 1).head.getId
    imageDao.batchInsert(Seq(img))
    selectionDao.create(img.pageId, rate = 1, juryId = juror, roundId = round, monumentId = img.monumentId)
  }

  private def count(sqlText: String): Int = DB.readOnly { implicit s => SQL(sqlText).map(_.int(1)).single().getOrElse(0) }

  "a thumbnail request" should {
    "be refused without signing in, without fetching or asking anything" in {
      val gone = commonsImage(1L, "Gone")
      store(gone)
      val (proxy, source, check) = controller(Nil)

      status(proxy.serve(thumb("Gone"))(FakeRequest())) === UNAUTHORIZED
      check.stop()
      source.detailCalls.get === 0
      count("SELECT COUNT(*) FROM images WHERE deleted_at IS NOT NULL") === 0
    }
  }

  "a thumbnail Wikimedia doesn't have" should {

    "be served under the file's new name when Commons renamed it, and stored so" in {
      val old = commonsImage(1L, "Old_name")
      store(old)
      val (proxy, _, check) = controller(Seq(renamed(old, "New_name")))

      val result = proxy.serve(thumb("Old_name"))(signedIn)
      status(result) === OK
      contentType(result) must beSome("image/jpeg")
      header("Cache-Control", result) must beSome(startWith("private"))
      ImageJdbc.findById(1L).map(_.title) must beSome("File:New name.jpg")
      check.stop()

      // a page rendered before the rename still links the old name, also after a restart
      val (restarted, source, again) = controller(Nil)
      status(restarted.serve(thumb("Old_name"))(signedIn)) === OK
      again.stop()
      source.detailCalls.get === 0
    }

    "be 404 when Commons deleted the file, which no longer shows in the galleries" in {
      val gone = commonsImage(1L, "Gone")
      store(gone)
      val (proxy, _, check) = controller(Nil)

      status(proxy.serve(thumb("Gone"))(signedIn)) === NOT_FOUND
      check.stop()
      count("SELECT COUNT(*) FROM images WHERE page_id = 1 AND deleted_at IS NOT NULL") === 1
      count("SELECT COUNT(*) FROM selection WHERE page_id = 1") === 0
      count("SELECT COUNT(*) FROM selection_deleted WHERE page_id = 1") === 1
    }

    "redirect to Wikimedia as before when Commons has the file as stored" in {
      val same = commonsImage(1L, "Same")
      store(same)
      val (proxy, _, check) = controller(Seq(same))

      val result = proxy.serve(thumb("Same"))(signedIn)
      status(result) === SEE_OTHER
      redirectLocation(result) must beSome(s"https://upload.wikimedia.org/wikipedia/commons/thumb/${thumb("Same")}")
      check.stop()
      count("SELECT COUNT(*) FROM images WHERE page_id = 1 AND deleted_at IS NULL") === 1
      count("SELECT COUNT(*) FROM selection WHERE page_id = 1") === 1
    }
  }

  "a thumbnail Wikimedia fails to serve for another reason" should {
    "not ask Commons" in {
      val img = commonsImage(1L, "Error_file")
      store(img)
      val (proxy, source, check) = controller(Nil)

      status(proxy.serve(thumb("Error_file"))(signedIn)) === SEE_OTHER
      check.stop()
      source.detailCalls.get === 0
      count("SELECT COUNT(*) FROM images WHERE deleted_at IS NOT NULL") === 0
    }
  }

  "a thumbnail Wikimedia serves" should {
    "not ask Commons" in {
      val img = commonsImage(1L, "New_file")
      store(img)
      val (proxy, source, check) = controller(Nil)

      status(proxy.serve(thumb("New_file"))(signedIn)) === OK
      check.stop()
      source.detailCalls.get === 0
      count("SELECT COUNT(*) FROM images WHERE deleted_at IS NOT NULL") === 0
    }
  }
}
