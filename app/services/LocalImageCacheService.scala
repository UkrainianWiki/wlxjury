package services

import controllers.Global
import db.scalikejdbc.ImageJdbc
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.pattern.after
import org.apache.pekko.stream.scaladsl._
import org.intracer.wmua.{Image, ImageUtil}
import play.api.libs.json.{Json, Reads, Writes}
import play.api.{Configuration, Logging}

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, File}
import java.nio.file.Files
import scala.jdk.CollectionConverters._
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

case class CacheProgress(
    done: Int,
    total: Int,
    errors: Int,
    running: Boolean,
    startedAtMs: Long  = 0L,
    elapsedMs: Long    = 0L,
    ratePerSec: Double = 0.0,
    etaSeconds: Long   = 0L
)

object CacheProgress {
  implicit val writes: Writes[CacheProgress] = Json.writes[CacheProgress]
  implicit val reads: play.api.libs.json.Reads[CacheProgress] =
    play.api.libs.json.Json.reads[CacheProgress]
}

@Singleton
class LocalImageCacheService @Inject() (
    config: Configuration,
    actorSystem: ActorSystem
)(implicit ec: ExecutionContext)
    extends Logging {

  implicit private val system: ActorSystem = actorSystem

  private val localPath   = config.get[String]("wlxjury.thumbs.local-path")
  private val parallelism = config.getOptional[Int]("wlxjury.thumbs.parallelism").getOrElse(1)
  private val ratePerSec  = config.getOptional[Int]("wlxjury.thumbs.rate-per-second").getOrElse(10)
  private val maxAttempts = config.getOptional[Int]("wlxjury.thumbs.max-attempts").getOrElse(3)

  // All thumbnail heights used by the jury UI (1x, 1.5x and 2x for each size slot)
  private val targetHeights: Seq[Int] = Seq(
    Global.thumbSizeY,                     // 120  – thumbs bar 1x
    (Global.thumbSizeY * 1.5).toInt,       // 180  – thumbs bar 1.5x
    Global.thumbSizeY * 2,                 // 240  – thumbs bar 2x
    Global.gallerySizeY,                   // 250  – gallery 1x
    (Global.gallerySizeY * 1.5).toInt,     // 375  – gallery 1.5x
    Global.gallerySizeY * 2,               // 500  – gallery 2x
    Global.largeSizeY,                     // 1100 – large view 1x
    (Global.largeSizeY * 1.5).toInt        // 1650 – large view 1.5x
  ).distinct.sorted

  // The source image downloaded from wikimedia covers all target heights
  private val sourceHeight = (Global.largeSizeY * 1.5).toInt // 1650

  // Wikimedia only serves thumbnails at these fixed widths ($wgThumbnailSteps)
  private val wikiThumbnailSteps = Seq(20, 40, 60, 120, 250, 330, 500, 960, 1280, 1920, 3840)

  private val userAgent = headers.RawHeader("User-Agent", "WLXJury/1.0 (https://commons.wikimedia.org/wiki/Commons:WLX_Jury_Tool; intracer@gmail.com)")

  private val progressMap = new ConcurrentHashMap[Long, CacheProgress]()
  private val roundProgressMap = new ConcurrentHashMap[Long, CacheProgress]()

  def progress(contestId: Long): CacheProgress =
    Option(progressMap.get(contestId)).getOrElse(CacheProgress(0, 0, 0, running = false))

  def progressForRound(roundId: Long): CacheProgress =
    Option(roundProgressMap.get(roundId)).getOrElse(CacheProgress(0, 0, 0, running = false))

  def startDownload(contestId: Long): Unit = {
    if (progress(contestId).running) return
    val images = ImageJdbc
      .findByContestId(contestId)
      .filter(img => img.isImage && img.url.isDefined && img.width > 0 && img.height > 0)
    runDownload(contestId, images)
  }

  def startDownloadForRound(contestId: Long, roundId: Long): Unit = {
    if (progressForRound(roundId).running) return
    val images = scala.util.Try(ImageJdbc.byRound(roundId)) match {
      case scala.util.Success(imgs) => imgs
      case scala.util.Failure(ex) =>
        logger.warn(s"Failed to load images for round $roundId: ${ex.getMessage}")
        Seq.empty
    }
    val filtered = images.filter(img => img.isImage && img.url.isDefined && img.width > 0 && img.height > 0)
    if (filtered.isEmpty) return  // nothing to do, progress stays at default (running=false)
    runDownloadForRound(contestId, roundId, filtered)
  }

  private[services] def runDownload(contestId: Long, images: Seq[Image]): Future[Unit] = {
    logger.info(s"Download config: localPath=$localPath, parallelism=$parallelism, ratePerSec=$ratePerSec, maxAttempts=$maxAttempts")
    runDownloadImpl(images, progressMap, contestId, s"Contest $contestId")
  }

  private def runDownloadForRound(contestId: Long, roundId: Long, images: Seq[Image]): Future[Unit] =
    runDownloadImpl(images, roundProgressMap, roundId, s"Contest $contestId, Round $roundId")

  private def runDownloadImpl(
      images: Seq[Image],
      tracker: ConcurrentHashMap[Long, CacheProgress],
      key: Long,
      logLabel: String
  ): Future[Unit] = {
    // Scan the cache directory once to build a set of existing files.
    // This is far faster than calling File.exists() per image × per size (320k+ stat calls).
    val existing   = localFileSet()
    val toDownload = images.filterNot(allSizesCached(_, existing))
    logger.info(s"$logLabel: ${images.size} images total, ${images.size - toDownload.size} already cached, ${toDownload.size} to download")

    // Start the clock only after the scan, so elapsed/rate/ETA reflect download time only.
    val startMs = System.currentTimeMillis()
    val total   = toDownload.size
    val done    = new AtomicInteger(0)
    val errs    = new AtomicInteger(0)

    def mkProgress(d: Int, running: Boolean): CacheProgress = {
      val elapsed = System.currentTimeMillis() - startMs
      val rate    = if (elapsed > 0) d * 1000.0 / elapsed else 0.0
      val eta     = if (rate > 0) ((total - d) / rate).toLong else 0L
      CacheProgress(d, total, errs.get, running,
        startedAtMs = startMs, elapsedMs = elapsed, ratePerSec = rate, etaSeconds = eta)
    }

    tracker.put(key, mkProgress(0, running = true))

    Source(toDownload)
      .throttle(ratePerSec, 1.second)
      .mapAsyncUnordered(parallelism) { image =>
        downloadAndResize(image)
          .recover { case ex =>
            logger.warn(s"Failed to cache ${image.title}: ${ex.getMessage}")
            errs.incrementAndGet()
          }
          .map { _ =>
            val d = done.incrementAndGet()
            tracker.put(key, mkProgress(d, running = true))
          }
      }
      .runWith(Sink.ignore)
      .map { _ =>
        tracker.put(key, mkProgress(done.get, running = false))
      }
  }

  /** Scans the local cache directory once and returns the set of all cached files. */
  private def localFileSet(): Set[File] = {
    val root = new File(localPath)
    if (!root.exists()) return Set.empty
    Files.walk(root.toPath).iterator().asScala
      .filter(p => Files.isRegularFile(p))
      .map(_.toFile)
      .toSet
  }

  /** Bulk variant: checks against a pre-built file set instead of calling File.exists() per file. */
  private def allSizesCached(image: Image, existing: Set[File]): Boolean =
    image.url.exists(_.contains("//upload.wikimedia.org/wikipedia/commons/")) &&
    targetHeights.forall { h =>
      val px = ImageUtil.resizeTo(image.width, image.height, h)
      px >= image.width || existing.contains(localFile(image, px))
    }

  /** Single-image variant for status checks and tests (uses File.exists()). */
  private[services] def allSizesCached(image: Image): Boolean =
    image.url.exists(_.contains("//upload.wikimedia.org/wikipedia/commons/")) &&
    targetHeights.forall { h =>
      val px = ImageUtil.resizeTo(image.width, image.height, h)
      px >= image.width || localFile(image, px).exists()
    }

  // Mirrors the URL construction in Global.legacyThumbUlr, always using upload.wikimedia.org
  private[services] def wikiThumbUrl(image: Image, px: Int): Option[String] =
    image.url.filter(_.contains("//upload.wikimedia.org/wikipedia/commons/")).map { url =>
      val lower     = image.title.toLowerCase
      val isPdf     = lower.endsWith(".pdf")
      val isTif     = lower.endsWith(".tif") || lower.endsWith(".tiff")
      val lastSlash = url.lastIndexOf("/")
      val utf8Size  = image.title.getBytes("UTF-8").length
      val thumbStr  = if (utf8Size > 165) "thumbnail.jpg" else url.substring(lastSlash + 1)
      url.replace(
        "//upload.wikimedia.org/wikipedia/commons/",
        "//upload.wikimedia.org/wikipedia/commons/thumb/"
      ) + "/" +
        (if (isPdf) "page1-" else if (isTif) "lossy-page1-" else "") +
        px + "px-" + thumbStr +
        (if (isPdf || isTif) ".jpg" else "")
    }

  // Local path mirrors the URL path, so Apache can serve it under the same URL structure.
  // URL-decode the path so Cyrillic/Unicode filenames stay under the 255-byte OS limit
  // (each encoded char is 6 ASCII bytes but only 2 UTF-8 bytes when decoded).
  // Apache URL-decodes request paths before file lookup, so it finds the decoded files.
  private[services] def localFile(image: Image, px: Int): File = {
    val path = wikiThumbUrl(image, px)
      .getOrElse("")
      .replaceFirst("https?://upload\\.wikimedia\\.org", "")
    val decoded = java.net.URLDecoder.decode(path, "UTF-8")
    new File(localPath + decoded)
  }

  private def downloadAndResize(image: Image): Future[Unit] = {
    val neededPx = ImageUtil.resizeTo(image.width, image.height, sourceHeight)
    // Snap up to the nearest Wikimedia thumbnail step; fall back to original if none is large enough
    val sourcePx = wikiThumbnailSteps.find(_ >= neededPx).getOrElse(image.width)
    // Use original URL if snapped step meets or exceeds the full image width
    val urlOpt = if (sourcePx >= image.width) image.url else wikiThumbUrl(image, sourcePx)

    urlOpt match {
      case None =>
        logger.warn(s"No cacheable URL for ${image.title} (url=${image.url})")
        Future.successful(())
      case Some(url) =>
        downloadWithRetry(url, attempt = 1).map {
          case Some(bytes) =>
            val sourceImg = ImageIO.read(new ByteArrayInputStream(bytes))
            if (sourceImg != null) saveResized(image, sourceImg, sourcePx)
          case None => ()
        }
    }
  }

  /** Downloads a URL, retrying on 429 with exponential backoff + jitter.
    * Respects the Retry-After header if present. Gives up after maxAttempts.
    */
  private[services] def downloadWithRetry(url: String, attempt: Int): Future[Option[Array[Byte]]] =
    Http()
      .singleRequest(HttpRequest(uri = url, headers = List(userAgent)))
      .flatMap {
        case HttpResponse(StatusCodes.OK, _, entity, _) =>
          entity.toStrict(60.seconds).map(e => Some(e.data.toArray))

        case HttpResponse(StatusCodes.TooManyRequests, respHeaders, entity, _) =>
          entity.toStrict(10.seconds).flatMap { strict =>
            val body = strict.data.utf8String
            if (attempt >= maxAttempts) {
              logger.warn(s"Giving up on $url after $attempt attempts (429): $body")
              Future.successful(None)
            } else {
              val delay = retryDelay(respHeaders, attempt)
              logger.info(s"429 on attempt $attempt for $url, retrying in ${delay.toSeconds}s: $body")
              after(delay)(downloadWithRetry(url, attempt + 1))
            }
          }

        case resp =>
          resp.entity.discardBytes()
          logger.warn(s"HTTP ${resp.status} for $url")
          Future.successful(None)
      }

  /** Delay before next retry: Retry-After header value if present, otherwise
    * exponential backoff (2^attempt seconds) capped at 5 minutes, with jitter.
    */
  private[services] def retryDelay(respHeaders: Seq[HttpHeader], attempt: Int): FiniteDuration = {
    val retryAfterSeconds = respHeaders
      .find(_.name.toLowerCase == "retry-after")
      .flatMap(h => scala.util.Try(h.value.trim.toInt).toOption)

    val baseSeconds = retryAfterSeconds.getOrElse {
      math.min(math.pow(2, attempt).toInt, 300) // cap at 5 min
    }
    // Add up to 30% jitter to avoid thundering herd when many images retry simultaneously
    val jitter = Random.nextInt(math.max(1, baseSeconds / 3))
    (baseSeconds + jitter).seconds
  }

  private[services] def saveResized(image: Image, sourceImg: BufferedImage, sourcePx: Int): Unit =
    targetHeights.foreach { h =>
      val px = ImageUtil.resizeTo(image.width, image.height, h)
      if (px > 0 && px < image.width && px <= sourcePx) {
        val file = localFile(image, px)
        if (!file.exists()) {
          file.getParentFile.mkdirs()
          val resized = scale(sourceImg, px)
          ImageIO.write(resized, "JPEG", file)
        }
      }
    }

  private[services] def scale(src: BufferedImage, targetWidth: Int): BufferedImage = {
    val targetHeight = (src.getHeight.toDouble * targetWidth / src.getWidth).toInt
    val out = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
    val g   = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.drawImage(src, 0, 0, targetWidth, targetHeight, null)
    g.dispose()
    out
  }
}
