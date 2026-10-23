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
    val round = roundDao.create(Round(None, 1, None, contest.getId, createdAt = now, active = true)).getId
    val juror = userDao.create(User("Juror", "juror@example.org", None, Set("jury"), contestId = contest.id)).getId
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

    "be served to a session user name with spaces or capitals, as the pages are" in {
      store(commonsImage(1L, "New_file"))
      val (proxy, _, check) = controller(Nil)

      status(proxy.serve(thumb("New_file"))(FakeRequest().withSession(Secured.UserName -> " Juror@Example.org"))) === OK
      check.stop()
      ok
    }

    "look the user up once for the thumbnails a gallery asks for at once" in {
      val lookups = new java.util.concurrent.atomic.AtomicInteger()
      val proxy = new ImageProxyController(stubControllerComponents(), new LocalImageCacheService(thumbsConfig(), system),
        () => System.currentTimeMillis(), { _ => lookups.incrementAndGet(); Thread.sleep(200); true })

      val results = (1 to 20).map(n => proxy.serve(s"x/xx/Missing$n.jpg/250px-Missing$n.jpg")(signedIn))
      results.map(status(_)).distinct === Seq(SEE_OTHER)
      lookups.get === 1
    }

    "look a refused user up again sooner than an allowed one, as they may have just been added" in {
      val clock = new java.util.concurrent.atomic.AtomicLong(0L)
      val allowed = new java.util.concurrent.atomic.AtomicBoolean(false)
      val proxy = new ImageProxyController(stubControllerComponents(), new LocalImageCacheService(thumbsConfig(), system),
        () => clock.get, _ => allowed.get)
      val missing = "x/xx/Missing.jpg/250px-Missing.jpg"

      status(proxy.serve(missing)(signedIn)) === UNAUTHORIZED
      allowed.set(true)
      status(proxy.serve(missing)(signedIn)) === UNAUTHORIZED // within the refusal's few seconds
      clock.set(ImageProxyController.RefusedTtl.toMillis)
      status(proxy.serve(missing)(signedIn)) === SEE_OTHER

      allowed.set(false)
      clock.set(ImageProxyController.RefusedTtl.toMillis * 2)
      status(proxy.serve(missing)(signedIn)) === SEE_OTHER // an access is kept the whole ViewerTtl
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

    "redirect to the file's new name on Wikimedia when that can't be fetched either" in {
      val old = commonsImage(1L, "Old_name")
      store(old)
      val (proxy, _, check) = controller(Seq(renamed(old, "Error_new_name")))

      val result = proxy.serve(thumb("Old_name"))(signedIn)
      status(result) === SEE_OTHER
      check.stop()
      // 333 wide is not one of Wikimedia's sizes, which it only serves
      redirectLocation(result) must beSome(endWith("/wikipedia/commons/thumb/c/cd/Error_new_name.jpg/500px-Error_new_name.jpg"))
    }

    "redirect to a renamed file's large view size on Wikimedia, without fetching it" in {
      val old = commonsImage(1L, "Old_name")
      store(old)
      // New_ files are on this Wikimedia: the redirect is not for a failed fetch
      val (proxy, _, check) = controller(Seq(renamed(old, "New_name").copy(width = 4000, height = 3000)))

      val result = proxy.serve("a/ab/Old_name.jpg/1280px-Old_name.jpg")(signedIn)
      status(result) === SEE_OTHER
      check.stop()
      redirectLocation(result) must beSome(endWith("/wikipedia/commons/thumb/c/cd/New_name.jpg/1280px-New_name.jpg"))
    }

    "be no wider than the file now is when it was uploaded again smaller" in {
      val old = commonsImage(1L, "Reuploaded_small")
      store(old)
      val (proxy, _, check) = controller(Seq(old.copy(width = 200, height = 150, size = Some(1000))))

      // Wikimedia doesn't have its original here: redirected to it, not to a 333px thumbnail
      val result = proxy.serve(thumb("Reuploaded_small"))(signedIn)
      status(result) === SEE_OTHER
      check.stop()
      redirectLocation(result) must beSome("https://upload.wikimedia.org/wikipedia/commons/a/ab/Reuploaded_small.jpg")
    }

    "redirect to a thumbnail of a renamed PDF that can't be fetched, not to the file itself" in {
      val old = commonsImage(1L, "Old_name")
      store(old)
      val pdf = old.copy(title = "File:Error new name.pdf",
        url = Some("https://upload.wikimedia.org/wikipedia/commons/c/cd/Error_new_name.pdf"), width = 300, height = 400)
      val (proxy, _, check) = controller(Seq(pdf))

      val result = proxy.serve(thumb("Old_name"))(signedIn)
      status(result) === SEE_OTHER
      check.stop()
      redirectLocation(result) must beSome(
        endWith("/wikipedia/commons/thumb/c/cd/Error_new_name.pdf/page1-500px-Error_new_name.pdf.jpg"))
    }

    "refuse a session whose user is no longer in any contest" in {
      val gone = commonsImage(1L, "Gone")
      store(gone)
      DB.autoCommit(implicit s => sql"DELETE FROM user_contest".update())
      val (proxy, source, check) = controller(Nil)

      status(proxy.serve(thumb("Gone"))(signedIn)) === UNAUTHORIZED
      check.stop()
      source.detailCalls.get === 0
    }

    "be served for the file's new size when it was uploaded again on Commons, and stored so" in {
      val old = commonsImage(1L, "Reuploaded_file")
      store(old)
      val (proxy, _, check) = controller(Seq(reuploaded(old)))

      val result = proxy.serve(thumb("Reuploaded_file"))(signedIn)
      status(result) === OK
      contentType(result) must beSome("image/jpeg")
      check.stop()
      ImageJdbc.findById(1L).map(i => (i.width, i.height)) must beSome((8000, 6000))
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
