package controllers

import db.scalikejdbc.ImageJdbc
import org.intracer.wmua.Image
import play.api.mvc._
import services.LocalImageCacheService
import services.imports.FileCheck

import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/** Thumbnails, for signed-in users only: only they see the galleries.
  *
  * @param now milliseconds
  */
@Singleton
class ImageProxyController(
    cc: ControllerComponents,
    cache: LocalImageCacheService,
    now: () => Long
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  @Inject() def this(cc: ControllerComponents, cache: LocalImageCacheService)(implicit ec: ExecutionContext) =
    this(cc, cache, () => System.currentTimeMillis())

  import ImageProxyController._

  private val logger = play.api.Logger(getClass)

  // Matches standard JPEG thumbs (250px-foo.jpg) and PDF/TIFF variants
  // (page1-250px-foo.pdf.jpg, lossy-page1-250px-foo.tif.jpg).
  private val pxPattern = """^(?:(?:lossy-)?page\d+-)?(\d+)px-.*""".r

  // Hard cap matching the largest Wikimedia thumbnail step ($wgThumbnailSteps max = 3840).
  private val MaxAllowedPx = 3840

  /** Private: a shared cache must not serve a thumbnail to someone not signed in. */
  private val CacheControl = "Cache-Control" -> "private, max-age=2592000"

  /** Whether each session user name may see thumbnails ([[Secured.mayView]]), and when that was read. */
  private val viewers = new ConcurrentHashMap[String, (Boolean, Long)]()

  /** 401 unless the session's user may see the galleries ([[Secured.mayView]]): as
    * [[Secured.withAuth]] would let them in, read at most once per [[ViewerTtl]] per user,
    * as a gallery asks for hundreds of thumbnails at once.
    */
  def serve(path: String): Action[AnyContent] = Action.async { request =>
    request.session.get(Secured.UserName) match {
      case None       => Future.successful(Unauthorized)
      case Some(name) =>
        mayView(name).flatMap(allowed => if (allowed) serveThumbnail(path) else Future.successful(Unauthorized))
    }
  }

  private def mayView(name: String): Future[Boolean] =
    Option(viewers.get(name)).filter { case (_, at) => now() - at < ViewerTtl.toMillis } match {
      case Some((allowed, _)) => Future.successful(allowed)
      case None =>
        Future(scala.concurrent.blocking(Secured.mayView(name))).map { allowed =>
          if (viewers.size >= MaxViewers) viewers.clear()
          viewers.put(name, (allowed, now()))
          allowed
        }
    }

  private def serveThumbnail(path: String): Future[Result] = {
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
        // Wrap the blocking JDBC call so it doesn't starve the Play dispatcher
        Future(scala.concurrent.blocking(
          // Also under a former name: a page rendered before a rename still links it
          Try(ImageJdbc.findForThumbnail(filename))
            .recover { case ex =>
              logger.error(s"DB error while looking up filename '$filename': ${ex.getMessage}", ex)
              None
            }
            .getOrElse(None)
        ))
          .flatMap {
            case Some((image, direct)) =>
              serveImage(image, px, path) { ex =>
                if (direct) afterFailedFetch(image, ex, px, path) else Future.successful(wikimedia(path))
              }

            case None =>
              // Not in DB: redirect to Wikimedia; browser follows transparently for <img>
              Future.successful(wikimedia(path))
          }
    }
  }

  /** The thumbnail from the local cache, else fetched from Wikimedia and cached; else what
    * `onFailure` makes of the failure.
    */
  private def serveImage(image: Image, px: Int, path: String)(onFailure: Throwable => Future[Result]): Future[Result] =
    // Guard against stale registry entries where the file was deleted on disk
    cache.fileIfCached(image, px) match {
      case Some(file) if file.exists() =>
        Future.successful(Ok.sendFile(file, inline = true)
          .as("image/jpeg")
          .withHeaders(CacheControl))

      case Some(_) | None =>
        // File missing from disk or not cached — fetch from Wikimedia
        cache.fetchAndCacheAll(image, px)
          .map { bytes =>
            Ok(bytes).as("image/jpeg")
              .withHeaders(CacheControl)
          }
          .recoverWith { case ex =>
            logger.warn(s"Failed to fetch $path: ${ex.getMessage}")
            onFailure(ex)
          }
    }

  /** A stored file's thumbnail could not be fetched: if Wikimedia doesn't have it, perhaps
    * the file was renamed, re-uploaded or deleted on Commons
    * ([[LocalImageCacheService.checkOnCommons]]). A renamed or re-uploaded file's thumbnail
    * is served right away ([[serveCurrent]]); a deleted file's is 404. Other failures and
    * answers, or none in time, redirect to Wikimedia as before.
    */
  private def afterFailedFetch(image: Image, ex: Throwable, px: Int, path: String): Future[Result] =
    cache.checkOnCommons(image, ex).flatMap {
      case FileCheck.Renamed(current) => serveCurrent(current, px, path)
      case FileCheck.Changed(current) => serveCurrent(current, px, path)
      case FileCheck.Deleted          => Future.successful(NotFound)
      case _                          => Future.successful(wikimedia(path))
    }

  /** The thumbnail of a renamed or re-uploaded file as now stored, no wider than the file
    * now is; if that fails too, a redirect to it on Wikimedia, under its new name: the
    * requested path is the old file's.
    */
  private def serveCurrent(current: Image, px: Int, path: String): Future[Result] = {
    val width = if (current.width > 0) math.min(px, current.width) else px
    serveImage(current, width, path) { _ =>
      Future.successful(cache.wikimediaUrl(current, width).fold[Result](NotFound)(Redirect(_, SEE_OTHER)))
    }
  }

  private def wikimedia(path: String): Result =
    Redirect(s"https://upload.wikimedia.org/wikipedia/commons/thumb/$path", SEE_OTHER)
}

object ImageProxyController {

  /** How long a user's access to the thumbnails is taken as read: a removed juror loses it within that. */
  val ViewerTtl: FiniteDuration = 1.minute

  /** Users' access kept at most; past it, all is read again. */
  val MaxViewers = 10000
}
