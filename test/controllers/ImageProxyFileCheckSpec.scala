package controllers

import com.typesafe.config.ConfigFactory
import db.scalikejdbc._
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{AfterAll, BeforeAll, BeforeEach}
import play.api.Configuration
import play.api.libs.concurrent.DefaultFutures
import play.api.test.FakeRequest
import play.api.test.Helpers._
import scalikejdbc._
import services.LocalImageCacheService
import services.imports.{CommonsFileCheck, FakeCommonsSource, FakeFile}

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

/** A thumbnail request for a stored file that Wikimedia no longer serves asks Commons
  * ([[CommonsFileCheck]]) whether the file was renamed or deleted. Wikimedia is a local
  * server that has only the files named `New_…`; Commons is a fake.
  */
class ImageProxyFileCheckSpec extends Specification with TestDb with BeforeAll with BeforeEach with AfterAll {

  sequential

  implicit val system: ActorSystem = ActorSystem("ImageProxyFileCheckSpec")
  implicit val ec: ExecutionContext = system.dispatcher

  private val jpeg: Array[Byte] = {
    val out = new ByteArrayOutputStream()
    ImageIO.write(new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB), "JPEG", out)
    out.toByteArray
  }

  private lazy val wikimedia = Await.result(
    Http().newServerAt("127.0.0.1", 0).bindSync { request =>
      if (request.uri.path.toString.contains("New_")) HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, jpeg))
      else HttpResponse(StatusCodes.NotFound)
    },
    5.seconds
  )

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()
  override def afterAll(): Unit = {
    Await.result(wikimedia.unbind(), 5.seconds)
    Await.result(system.terminate(), 10.seconds)
  }

  private def image(id: Long, name: String) =
    Image(id, s"File:${name.replace('_', ' ')}.jpg", Some(s"https://upload.wikimedia.org/wikipedia/commons/a/ab/$name.jpg"),
      None, 800, 600, Some(s"07-101-000$id"))

  private def thumb(name: String) = s"a/ab/$name.jpg/333px-$name.jpg"

  /** The controller, with an empty local cache and the files Commons has. */
  private def controller(commons: Seq[Image]): (ImageProxyController, FakeCommonsSource, CommonsFileCheck) = {
    val dir = Files.createTempDirectory("proxy-file-check").toFile
    dir.deleteOnExit()
    val config = Configuration(ConfigFactory.parseString(
      s"""wlxjury.thumbs.local-path = "${dir.getAbsolutePath.replace("\\", "/")}"
         |wlxjury.thumbs.max-attempts = 1
         |wlxjury.thumbs.wiki-base-url = "http://127.0.0.1:${wikimedia.localAddress.getPort}"
         |""".stripMargin))
    val source = new FakeCommonsSource(commons.map(FakeFile(_)))
    val check = new CommonsFileCheck(source, batchWindow = 20.millis)
    val proxy = new ImageProxyController(stubControllerComponents(), new LocalImageCacheService(config, system), check,
      new DefaultFutures(system))
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

  "a thumbnail Wikimedia doesn't serve" should {

    "be served under the file's new name when Commons renamed it, and stored so" in {
      val old = image(1L, "Old_name")
      store(old)
      val renamed = old.copy(title = "File:New name.jpg", url = Some("https://upload.wikimedia.org/wikipedia/commons/c/cd/New_name.jpg"))
      val (proxy, _, check) = controller(Seq(renamed))

      val result = proxy.serve(thumb("Old_name"))(FakeRequest())
      status(result) === OK
      contentType(result) must beSome("image/jpeg")
      ImageJdbc.findById(1L).map(_.title) must beSome("File:New name.jpg")

      // a page rendered before the rename still links the old name
      status(proxy.serve(thumb("Old_name"))(FakeRequest())) === OK
      check.stop()
      ok
    }

    "be 404 when Commons deleted the file, which no longer shows in the galleries" in {
      val gone = image(1L, "Gone")
      store(gone)
      val (proxy, _, check) = controller(Nil)

      status(proxy.serve(thumb("Gone"))(FakeRequest())) === NOT_FOUND
      check.stop()
      count("SELECT COUNT(*) FROM images WHERE page_id = 1 AND deleted_at IS NOT NULL") === 1
      count("SELECT COUNT(*) FROM selection WHERE page_id = 1") === 0
      count("SELECT COUNT(*) FROM selection_deleted WHERE page_id = 1") === 1
    }

    "redirect to Wikimedia as before when Commons has the file as stored" in {
      val same = image(1L, "Same")
      store(same)
      val (proxy, _, check) = controller(Seq(same))

      val result = proxy.serve(thumb("Same"))(FakeRequest())
      status(result) === SEE_OTHER
      redirectLocation(result) must beSome(s"https://upload.wikimedia.org/wikipedia/commons/thumb/${thumb("Same")}")
      check.stop()
      count("SELECT COUNT(*) FROM images WHERE page_id = 1 AND deleted_at IS NULL") === 1
      count("SELECT COUNT(*) FROM selection WHERE page_id = 1") === 1
    }
  }

  "a thumbnail Wikimedia serves" should {
    "not ask Commons" in {
      val img = image(1L, "New_file")
      store(img)
      val (proxy, source, check) = controller(Nil)

      status(proxy.serve(thumb("New_file"))(FakeRequest())) === OK
      check.stop()
      source.detailCalls.get === 0
      count("SELECT COUNT(*) FROM images WHERE deleted_at IS NOT NULL") === 0
    }
  }
}
