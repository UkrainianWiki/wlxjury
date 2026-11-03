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
  * @param now           milliseconds
  * @param viewerMayView whether the session's user may see the galleries ([[Secured.mayView]])
  */
@Singleton
class ImageProxyController(
    cc: ControllerComponents,
    cache: LocalImageCacheService,
    now: () => Long,
    viewerMayView: String => Boolean
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  @Inject() def this(cc: ControllerComponents, cache: LocalImageCacheService)(implicit ec: ExecutionContext) =
    this(cc, cache, () => System.currentTimeMillis(), Secured.mayView)

  import ImageProxyController._

  private val logger = play.api.Logger(getClass)

  // Hard cap matching the largest Wikimedia thumbnail step ($wgThumbnailSteps max = 3840).
  private val MaxAllowedPx = 3840

  /** Private: a shared cache must not serve a thumbnail to someone not signed in. */
  private val CacheControl = "Cache-Control" -> "private, max-age=2592000"

  /** Whether each session user may see thumbnails ([[Secured.mayView]]), or the lookup
    * under way, and when it started.
    */
  private val viewers = new ConcurrentHashMap[String, (Future[Boolean], Long)]()

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

  /** One lookup per user at a time: a gallery's thumbnails, asked for at once, share it. A
    * failed lookup is forgotten; a refusal is kept only [[RefusedTtl]], so a user just
    * added to a contest sees the thumbnails at once.
    */
  private def mayView(name: String): Future[Boolean] = {
    val user = Secured.normalizeUserName(name)
    val at = now()
    if (viewers.size >= MaxViewers) viewers.clear()
    def ttl(allowed: Future[Boolean]) =
      if (allowed.value.contains(scala.util.Success(false))) RefusedTtl else ViewerTtl
    var started: Option[(Future[Boolean], Long)] = None
    val (allowed, _) = viewers.compute(
      user,
      (_, last) =>
        if (last != null && at - last._2 < ttl(last._1).toMillis) last
        else {
          val entry = (Future(scala.concurrent.blocking(viewerMayView(user))), at)
          started = Some(entry)
          entry
        }
    )
    started.foreach(entry => entry._1.failed.foreach(_ => viewers.remove(user, entry)))
    allowed
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
      px         <- LocalImageCacheService.thumbnailPx(lastSeg)
      if px <= MaxAllowedPx
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
            // A JPEG scaled here, or as Wikimedia sent it (the large view's)
            Ok(bytes).as(LocalImageCacheService.contentType(bytes))
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
    * is served right away ([[serveCurrent]]), a re-uploaded one's other cached sizes dropped
    * ([[LocalImageCacheService.evict]]); a deleted file's is 404. Other failures and
    * answers, or none in time, redirect to Wikimedia as before.
    */
  private def afterFailedFetch(image: Image, ex: Throwable, px: Int, path: String): Future[Result] =
    cache.checkOnCommons(image, ex).flatMap {
      case FileCheck.Renamed(current) => serveCurrent(current, px, path)
      case FileCheck.Changed(current) =>
        cache.evict(image)
        serveCurrent(current, px, path)
      case FileCheck.Deleted => Future.successful(NotFound)
      case _                 => Future.successful(wikimedia(path))
    }

  /** The thumbnail of a renamed or re-uploaded file as now stored, no wider than the file
    * now is (unless it is only shown by thumbnails, [[Image.thumbnailOnly]]); if that fails
    * too, a redirect to it on Wikimedia, under its new name: the requested path is the old
    * file's. A size not cached (the large view's) is that redirect right away: the browser
    * loads it from Wikimedia, and nothing is fetched or scaled here.
    */
  private def serveCurrent(current: Image, px: Int, path: String): Future[Result] = {
    val width = if (current.width > 0 && !current.thumbnailOnly) math.min(px, current.width) else px
    // At one of Wikimedia's sizes: it refuses others
    def onWikimedia = cache.wikimediaUrl(current, Global.wikimediaStep(width)).fold[Result](NotFound)(Redirect(_, SEE_OTHER))
    if (!cache.cacheable(current, width)) Future.successful(onWikimedia)
    else serveImage(current, width, path)(_ => Future.successful(onWikimedia))
  }

  private def wikimedia(path: String): Result =
    Redirect(s"https://upload.wikimedia.org/wikipedia/commons/thumb/$path", SEE_OTHER)
}

object ImageProxyController {

  /** How long a user's access to the thumbnails is taken as read: a removed juror loses it within that. */
  val ViewerTtl: FiniteDuration = 1.minute

  /** How long a refusal is: a gallery's burst of thumbnails shares it. */
  val RefusedTtl: FiniteDuration = 5.seconds

  /** Users' access kept at most; past it, all is read again. */
  val MaxViewers = 10000
}
