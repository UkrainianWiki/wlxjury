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

  // Matches standard JPEG thumbs (250px-foo.jpg) and PDF/TIFF variants
  // (page1-250px-foo.pdf.jpg, lossy-page1-250px-foo.tif.jpg).
  private val pxPattern = """^(?:(?:lossy-)?page\d+-)?(\d+)px-.*""".r

  // Hard cap matching the largest Wikimedia thumbnail step ($wgThumbnailSteps max = 3840).
  private val MaxAllowedPx = 3840

  def serve(path: String): Action[AnyContent] = Action.async { _ =>
    val segments = path.split("/").toSeq
    // Expect at least: X/XX/filename/Npx-filename (or page1-Npx-filename for PDF/TIF)
    val parsed = for {
      rawFilename <- if (segments.length >= 2) Some(segments(segments.length - 2)) else None
      // Replace '+' before URLDecoding: '+' in path segments is a literal plus,
      // not a space (URLDecoder follows query-param convention by default).
      filename    = URLDecoder.decode(rawFilename.replace("+", "%2B"), "UTF-8").replace('_', ' ')
      lastSeg    <- segments.lastOption
      px         <- lastSeg match {
                      case pxPattern(n) => Try(n.toInt).toOption
                      case _            => None
                    }
      if px > 0 && px <= MaxAllowedPx
    } yield (filename, px)

    parsed match {
      case None =>
        Future.successful(NotFound)

      case Some((filename, px)) =>
        Future(scala.concurrent.blocking(
          Try(ImageJdbc.findByFilename(filename))
            .recover { case ex =>
              logger.error(s"DB error while looking up filename '$filename': ${ex.getMessage}", ex)
              None
            }
            .getOrElse(None)
        ))
          .flatMap {
            case None =>
              // Not in DB: redirect to Wikimedia; browser follows transparently for <img>
              Future.successful(
                Redirect(s"https://upload.wikimedia.org/wikipedia/commons/thumb/$path", SEE_OTHER)
              )

            case Some(image) =>
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