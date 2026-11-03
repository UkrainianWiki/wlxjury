package controllers

import db.scalikejdbc.ImageJdbc
import play.api.mvc._
import services.LocalImageCacheService

import java.net.URLDecoder
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class ImageProxyController @Inject() (
    cc: ControllerComponents,
    cache: LocalImageCacheService
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  private val logger = play.api.Logger(getClass)

  private val pxPattern = """^(\d+)px-.*""".r

  def serve(path: String): Action[AnyContent] = Action.async { _ =>
    val segments = path.split("/").toSeq
    // Expect at least: X/XX/filename/Npx-filename (or page1-Npx-filename for PDF/TIF)
    val parsed = for {
      rawFilename <- if (segments.length >= 2) Some(segments(segments.length - 2)) else None
      filename    = URLDecoder.decode(rawFilename, "UTF-8").replace('_', ' ')
      lastSeg    <- segments.lastOption
      px         <- lastSeg match {
                      case pxPattern(n) => Try(n.toInt).toOption
                      case _            => None
                    }
      if px > 0
    } yield (filename, px)

    parsed match {
      case None =>
        Future.successful(NotFound)

      case Some((filename, px)) =>
        // Fix 1: wrap blocking JDBC call so it doesn't starve the Play dispatcher
        Future(scala.concurrent.blocking(Try(ImageJdbc.findByFilename(filename)).getOrElse(None)))
          .flatMap {
            case None =>
              // Not in DB: redirect to Wikimedia; browser follows transparently for <img>
              Future.successful(
                Redirect(s"https://upload.wikimedia.org/wikipedia/commons/thumb/$path", SEE_OTHER)
              )

            case Some(image) =>
              // Fix 3: guard against stale registry entries where the file was deleted on disk
              cache.fileIfCached(image, px) match {
                case Some(file) if file.exists() =>
                  Future.successful(Ok.sendFile(file, inline = true)
                    .as("image/jpeg")
                    .withHeaders("Cache-Control" -> "public, max-age=2592000"))

                case Some(_) | None =>
                  // File missing from disk or not cached — fetch from Wikimedia
                  cache.fetchAndCacheAll(image, px)
                    .map { bytes =>
                      Ok(bytes).as("image/jpeg")
                        .withHeaders("Cache-Control" -> "public, max-age=2592000")
                    }
                    .recover { case ex =>
                      logger.warn(s"Failed to fetch $path: ${ex.getMessage}")
                      Redirect(s"https://upload.wikimedia.org/wikipedia/commons/thumb/$path", SEE_OTHER)
                    }
              }
          }
    }
  }
}
