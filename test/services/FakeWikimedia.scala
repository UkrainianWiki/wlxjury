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

/** Wikimedia for specs: a local server that has only the files named `New_…`, and the
  * thumbnails (not the originals) of those named `Reuploaded_…`, answers 500 for those named
  * `Error_…` and 404 for the rest; and a thumbnail cache over it. As the real one, it answers
  * a thumbnail at a width not among Wikimedia's sizes with 400 ([[FakeWikimedia.refused]]).
  * The paths asked for are in [[wikimediaRequests]].
  */
trait FakeWikimedia {

  /** The paths this Wikimedia was asked for, in order. */
  val wikimediaRequests = new java.util.concurrent.ConcurrentLinkedQueue[String]()

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
      wikimediaRequests.add(path)
      if (FakeWikimedia.refused(path)) HttpResponse(StatusCodes.BadRequest)
      else if (path.contains("New_") || (path.contains("Reuploaded_") && path.contains("/thumb/")))
        HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, jpeg))
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

  /** `image` uploaded again on Commons, larger: its thumbnails are no longer its original. */
  def reuploaded(image: Image): Image = image.copy(width = 8000, height = 6000, size = Some(5000000))

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

object FakeWikimedia {

  /** Whether Wikimedia refuses `path` (400): a thumbnail at a width not among its sizes
    * ([[controllers.Global.wikimediaSteps]]). Since 2026-05-13 it serves thumbnails only at those
    * (https://phabricator.wikimedia.org/T414805); an original is served as it is.
    */
  def refused(path: String): Boolean =
    path.contains("/thumb/") &&
      !LocalImageCacheService.thumbnailPx(path.substring(path.lastIndexOf('/') + 1))
        .exists(controllers.Global.wikimediaSteps.contains)

  /** The width of the thumbnail `url` (a path or a full url), if it is one; None for an original. */
  def thumbnailWidth(url: String): Option[Int] =
    Option.when(url.contains("/thumb/"))(url.substring(url.lastIndexOf('/') + 1)).flatMap(LocalImageCacheService.thumbnailPx)
}
