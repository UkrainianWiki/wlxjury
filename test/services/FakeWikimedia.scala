package services

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import org.intracer.wmua.Image
import play.api.Configuration

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

/** Wikimedia for specs: a local server that has only the files named `New_…`, answers 500
  * for those named `Error_…` and 404 for the rest; and a thumbnail cache over it.
  */
trait FakeWikimedia {

  implicit lazy val system: ActorSystem = ActorSystem(getClass.getSimpleName)
  implicit lazy val ec: ExecutionContext = system.dispatcher

  private lazy val jpeg: Array[Byte] = {
    val out = new ByteArrayOutputStream()
    ImageIO.write(new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB), "JPEG", out)
    out.toByteArray
  }

  private lazy val wikimedia = Await.result(
    Http().newServerAt("127.0.0.1", 0).bindSync { request =>
      val path = request.uri.path.toString
      if (path.contains("New_")) HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, jpeg))
      else if (path.contains("Error_")) HttpResponse(StatusCodes.InternalServerError)
      else HttpResponse(StatusCodes.NotFound)
    },
    5.seconds
  )

  def stopWikimedia(): Unit = {
    Await.result(wikimedia.unbind(), 5.seconds)
    Await.result(system.terminate(), 10.seconds)
  }

  /** A Commons file whose url and title come from `name` (underscores in the url). */
  def commonsImage(id: Long, name: String): Image =
    Image(id, s"File:${name.replace('_', ' ')}.jpg", Some(s"https://upload.wikimedia.org/wikipedia/commons/a/ab/$name.jpg"),
      None, 800, 600, Some(s"07-101-000$id"))

  /** `image` renamed on Commons to `name`. */
  def renamed(image: Image, name: String): Image =
    image.copy(title = s"File:${name.replace('_', ' ')}.jpg",
      url = Some(s"https://upload.wikimedia.org/wikipedia/commons/c/cd/$name.jpg"))

  /** An empty thumbnail cache that downloads from this Wikimedia, once per file. */
  def thumbsConfig(): Configuration = {
    val dir = Files.createTempDirectory("thumbs").toFile
    dir.deleteOnExit()
    Configuration(ConfigFactory.parseString(
      s"""wlxjury.thumbs.local-path = "${dir.getAbsolutePath.replace("\\", "/")}"
         |wlxjury.thumbs.max-attempts = 1
         |wlxjury.thumbs.wiki-base-url = "http://127.0.0.1:${wikimedia.localAddress.getPort}"
         |""".stripMargin))
  }
}
