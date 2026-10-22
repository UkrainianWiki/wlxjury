package services

import com.typesafe.config.ConfigFactory
import db.scalikejdbc._
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import org.intracer.wmua.Image
import org.specs2.mutable.Specification
import org.specs2.specification.{AfterAll, BeforeAll, BeforeEach}
import play.api.Configuration
import scalikejdbc._
import services.imports.{CommonsFileCheck, FakeCommonsSource, FakeFile}

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

/** The thumbnail download job asks Commons ([[CommonsFileCheck]]) about the files it can't
  * download. Wikimedia is a local server that has only the files named `New_…`; Commons is
  * a fake.
  */
class LocalImageCacheFileCheckSpec extends Specification with TestDb with BeforeAll with BeforeEach with AfterAll {

  sequential

  implicit val system: ActorSystem = ActorSystem("LocalImageCacheFileCheckSpec")
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

  private def service(commons: Seq[Image]): (LocalImageCacheService, CommonsFileCheck) = {
    val dir = Files.createTempDirectory("cache-file-check").toFile
    dir.deleteOnExit()
    val config = Configuration(ConfigFactory.parseString(
      s"""wlxjury.thumbs.local-path = "${dir.getAbsolutePath.replace("\\", "/")}"
         |wlxjury.thumbs.max-attempts = 1
         |wlxjury.thumbs.wiki-base-url = "http://127.0.0.1:${wikimedia.localAddress.getPort}"
         |""".stripMargin))
    val check = new CommonsFileCheck(new FakeCommonsSource(commons.map(FakeFile(_))), batchWindow = 20.millis)
    (new LocalImageCacheService(config, system, Some(check)), check)
  }

  "the thumbnail download job" should {

    "download a renamed file under its new name, and count deleted files apart from the errors" in {
      val renamed = image(1L, "Old_name")
      val deleted = image(2L, "Gone")
      val failing = image(3L, "Same")
      imageDao.batchInsert(Seq(renamed, deleted, failing))
      val commons = Seq(
        renamed.copy(title = "File:New name.jpg", url = Some("https://upload.wikimedia.org/wikipedia/commons/c/cd/New_name.jpg")),
        failing
      )
      val (cache, check) = service(commons)

      Await.result(cache.runDownload(1L, Seq(renamed, deleted, failing)), 30.seconds)
      check.stop()

      val progress = cache.progress(1L)
      (progress.done, progress.renamed, progress.deleted, progress.errors, progress.running) === (3, 1, 1, 1, false)
      ImageJdbc.findById(1L).map(_.title) must beSome("File:New name.jpg")
      DB.readOnly { implicit s =>
        sql"SELECT page_id FROM images WHERE deleted_at IS NOT NULL".map(_.long(1)).list()
      } === List(2L)
    }
  }

  "the contest's images" should {
    "leave out the files deleted on Commons" in {
      val contest = createContests(10).head
      val images = Seq(image(1L, "Kept"), image(2L, "Gone"))
      imageDao.batchInsert(images)
      DB.autoCommit { implicit s =>
        val categoryId = sql"INSERT INTO category (title) VALUES ('Category:Test')".updateAndReturnGeneratedKey()
        sql"UPDATE contest_jury SET category_id = $categoryId WHERE id = ${contest.getId}".update()
        images.foreach(i => sql"INSERT INTO category_members (category_id, page_id) VALUES ($categoryId, ${i.pageId})".update())
        ImageJdbc.markDeleted(Seq(2L))
      }
      ImageJdbc.findByContestId(contest.getId).map(_.pageId) === List(1L)
    }
  }
}
