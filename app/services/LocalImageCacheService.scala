package services

import controllers.Global
import db.scalikejdbc.ImageJdbc
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.pattern.after
import org.apache.pekko.stream.scaladsl._
import org.intracer.wmua.Image
import play.api.libs.json.{Json, Reads, Writes}
import play.api.{Configuration, Logging}
import services.imports.{CommonsFileCheck, FileCheck}

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, File}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import scala.jdk.CollectionConverters._
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Random, Try}

case class CacheProgress(
    done: Int,
    total: Int,
    errors: Int,
    running: Boolean,
    startedAtMs: Long  = 0L,
    elapsedMs: Long    = 0L,
    ratePerSec: Double = 0.0,
    etaSeconds: Long   = 0L,
    renamed: Int       = 0, // renamed on Commons, downloaded under the new name (in done)
    deleted: Int       = 0  // deleted on Commons (in done, not in errors)
)

/** Wikimedia has no file at `url` (HTTP 404): perhaps the file was renamed or deleted on
  * Commons ([[CommonsFileCheck]]). Other failures (429s, decode errors, no Commons url)
  * say nothing of that.
  */
final class ThumbnailNotFound(val url: String) extends Exception(s"Not found: $url")

object CacheProgress {
  implicit val writes: Writes[CacheProgress] = Json.writes[CacheProgress]
  implicit val reads: play.api.libs.json.Reads[CacheProgress] =
    play.api.libs.json.Json.reads[CacheProgress]
}

/** @param fileCheck asks Commons about a file whose thumbnail can't be downloaded */
@Singleton
class LocalImageCacheService(
    config: Configuration,
    actorSystem: ActorSystem,
    fileCheck: Option[CommonsFileCheck]
)(implicit ec: ExecutionContext)
    extends Logging {

  @Inject() def this(config: Configuration, actorSystem: ActorSystem, fileCheck: CommonsFileCheck)(implicit
      ec: ExecutionContext
  ) = this(config, actorSystem, Some(fileCheck))

  /** Without asking Commons (specs). */
  def this(config: Configuration, actorSystem: ActorSystem)(implicit ec: ExecutionContext) =
    this(config, actorSystem, None)

  implicit private val system: ActorSystem = actorSystem

  private val localPath   = config.get[String]("wlxjury.thumbs.local-path")
  private val parallelism = config.getOptional[Int]("wlxjury.thumbs.parallelism").getOrElse(1)
  private val ratePerSec  = config.getOptional[Int]("wlxjury.thumbs.rate-per-second").getOrElse(10)
  private val maxAttempts = config.getOptional[Int]("wlxjury.thumbs.max-attempts").getOrElse(3)

  // Wikimedia only serves thumbnails at these fixed widths ($wgThumbnailSteps)
  private val wikiThumbnailSteps = Seq(20, 40, 60, 120, 250, 330, 500, 960, 1280, 1920, 3840)

  private val userAgent = headers.RawHeader("User-Agent", "WLXJury/1.0 (https://commons.wikimedia.org/wiki/Commons:WLX_Jury_Tool; intracer@gmail.com)")

  private[services] val wikiBaseUrl =
    config.getOptional[String]("wlxjury.thumbs.wiki-base-url")
      .getOrElse("https://upload.wikimedia.org")

  private val progressMap = new ConcurrentHashMap[Long, CacheProgress]()
  private[services] val roundProgressMap = new ConcurrentHashMap[Long, CacheProgress]()

  private val registry = new ConcurrentHashMap[String, Unit]()
  private val inFlight = new ConcurrentHashMap[String, Future[Array[Byte]]]()

  private[services] def registrySize: Int = registry.size()
  private[services] def registryContains(file: File): Boolean =
    registry.containsKey(file.getAbsolutePath)

  def fileIfCached(image: Image, px: Int): Option[File] = {
    val file = localFile(image, px)
    if (registry.containsKey(file.getAbsolutePath)) Some(file) else None
  }

  /** The image `requestedPx` wide, caching all target sizes on the way: scaled from the
    * widest of them if cached ([[localSource]]), else from Wikimedia. A size wider than all
    * of them (the large view's) is fetched on its own and not cached.
    */
  def fetchAndCacheAll(image: Image, requestedPx: Int): Future[Array[Byte]] = {
    val widths    = targetWidths(image)
    val cacheable = widths.exists(_ >= requestedPx)
    val (sourcePx, urlOpt) = source(image, if (cacheable) widths.max else requestedPx)

    def resized(sourceImg: BufferedImage, sourcePx: Int): Array[Byte] = {
      // Run cascade synchronously: saves fire async inside saveResized, cascade CPU returns
      // the requested image immediately. A try/catch preserves the current behavior where
      // scaling errors (e.g. OOM) are logged and fall back to a direct scale rather than
      // propagating a failed Future to the caller.
      val imgOpt = if (!cacheable) None else try {
        saveResized(image, sourceImg, sourcePx, Some(requestedPx))
      } catch {
        case ex: Exception =>
          logger.warn(s"Cascade failed for ${image.title}: ${ex.getMessage}")
          None
      }
      val requestedImg = imgOpt.getOrElse(scale(sourceImg, requestedPx))
      val baos = new java.io.ByteArrayOutputStream()
      ImageIO.write(requestedImg, "JPEG", baos)
      baos.toByteArray
    }

    (if (cacheable) localSource(image) else None, urlOpt) match {
      case (Some((localPx, localImg)), _) =>
        Future(resized(localImg, localPx))
      case (None, None) =>
        Future.failed(new Exception(s"No cacheable URL for ${image.title}"))
      case (None, Some(url)) =>
        // Deduplicate concurrent downloads of the same URL: only one HTTP request fires;
        // all concurrent callers share the resulting Future.
        val download: Future[Array[Byte]] =
          inFlight.computeIfAbsent(url, (_: String) =>
            downloadWithRetry(url, attempt = 1)
              .flatMap {
                case None    => Future.failed(new Exception(s"Failed to download $url"))
                case Some(b) => Future.successful(b)
              }
              .andThen { case _ => inFlight.remove(url) }
          )
        download.flatMap { bytes =>
          val sourceImg = ImageIO.read(new ByteArrayInputStream(bytes))
          if (sourceImg == null)
            Future.failed(new Exception(s"Could not decode image from $url"))
          else
            Future.successful(resized(sourceImg, sourcePx))
        }
    }
  }

  /** The widest target size of the image, if cached: every other target size can be scaled
    * from it, without asking Wikimedia.
    */
  private def localSourceFile(image: Image): Option[(Int, File)] =
    targetWidths(image).lastOption.flatMap(px => fileIfCached(image, px).filter(_.exists()).map(px -> _))

  /** [[localSourceFile]], decoded. None if it can't be read. */
  private def localSource(image: Image): Option[(Int, BufferedImage)] =
    localSourceFile(image).flatMap { case (px, file) =>
      Try(ImageIO.read(file)).toOption.flatMap(Option(_)).map(px -> _)
    }

  private[services] def initRegistry(): Future[Unit] = Future {
    val root = new File(localPath)
    if (root.exists()) {
      val stream = Files.walk(root.toPath)
      try stream.iterator().asScala
            .filter(p => Files.isRegularFile(p))
            .foreach { p =>
              val name = p.getFileName.toString
              if (name.startsWith(".tmp-"))
                Files.deleteIfExists(p) // stale temp file from a prior crash
              else if (name != SizesMarker)
                registry.put(p.toAbsolutePath.toString, ())
            }
      finally stream.close()
      logger.info(s"Registry initialized with ${registry.size()} cached files")
    }
  }

  /** The file in the cache's root naming the sizes cached ([[cachedSizes]]) once the files of
    * other sizes are deleted ([[cleanUpUnusedSizes]]). In the cache, not in the config or the
    * database, it goes with the files: blue/green slots share it, a restored or new cache
    * has its own.
    */
  private[services] val SizesMarker = ".cache-sizes"

  /** The sizes cached ([[Global.thumbnailBoxes]]): when they change, the files of the old
    * ones are deleted once.
    */
  private[services] val cachedSizes: String =
    Global.thumbnailBoxes.map { case (x, y) => s"${x.fold("*")(_.toString)}x$y" }.mkString(" ")

  /** Deletes the files of sizes no longer cached ([[unusedFiles]]), unless the
    * [[SizesMarker]] says it was done for these sizes; then writes the marker. One image
    * directory at a time, after [[initRegistry]], not delaying it. Interrupted, it runs
    * again at the next start.
    */
  private[services] def cleanUpUnusedSizes(): Future[Unit] = Future(scala.concurrent.blocking {
    val root   = new File(localPath)
    val marker = new File(root, SizesMarker)
    val done   = Try(new String(Files.readAllBytes(marker.toPath), UTF_8).trim).toOption.contains(cachedSizes)
    if (root.exists() && !done) {
      logger.info(s"Deleting the cached files of sizes no longer cached: other than $cachedSizes")
      val startMs = System.currentTimeMillis()
      val dirs = {
        val stream = Files.walk(root.toPath)
        try stream.iterator().asScala.filter(p => Files.isDirectory(p)).toVector
        finally stream.close()
      }
      val deleted = dirs.map { dir =>
        val files = {
          val stream = Files.list(dir)
          try stream.iterator().asScala.filter(p => Files.isRegularFile(p)).toVector
          finally stream.close()
        }
        unusedFiles(files).count { p =>
          val removed = Try(Files.deleteIfExists(p)).getOrElse(false)
          if (removed) registry.remove(p.toAbsolutePath.toString)
          removed
        }
      }.sum
      val tmp = File.createTempFile(".tmp-", ".marker", root)
      Files.write(tmp.toPath, cachedSizes.getBytes(UTF_8))
      // A failed move (on Windows, when another instance replaces the marker at once) leaves no temp file
      try Files.move(tmp.toPath, marker.toPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      finally Files.deleteIfExists(tmp.toPath)
      logger.info(
        s"Deleted $deleted cached files of sizes no longer cached in ${dirs.size} directories, " +
          s"in ${(System.currentTimeMillis() - startMs) / 1000}s"
      )
    }
  })

  /** The files of widths the views don't show their image at ([[Global.thumbnailWidths]]):
    * the large view's, and other sizes cached before. A directory holds the sizes of one
    * image, so the dimensions of its widest file give those widths, within rounding: one
    * header read per image. A directory whose widest file can't be read is kept.
    */
  private def unusedFiles(files: Seq[Path]): Set[Path] =
    files
      .flatMap(p => thumbnailPx(p.getFileName.toString).map(p -> _))
      .groupBy(_._1.getParent)
      .values
      .flatMap { sizes =>
        imageSize(sizes.maxBy(_._2)._1).toSeq.flatMap { case (width, height) =>
          val used = Global.thumbnailWidths(width, height)
          sizes.collect { case (p, px) if !used.exists(w => math.abs(w - px) <= 2 + w / 100) => p }
        }
      }
      .toSet

  // A thumbnail's file name: 250px-foo.jpg, or for a PDF or TIFF page1-250px-foo.pdf.jpg
  // and lossy-page1-250px-foo.tif.jpg.
  private val pxPattern = """^(?:(?:lossy-)?page\d+-)?(\d+)px-.*""".r

  /** The width in a thumbnail's file name. */
  private def thumbnailPx(fileName: String): Option[Int] =
    fileName match {
      case pxPattern(n) => n.toIntOption.filter(_ > 0)
      case _            => None
    }

  /** The width and height in an image file's header, without decoding the image. */
  private def imageSize(path: Path): Option[(Int, Int)] =
    Try {
      val in = ImageIO.createImageInputStream(path.toFile)
      try {
        val readers = ImageIO.getImageReaders(in)
        if (!readers.hasNext) None
        else {
          val reader = readers.next()
          try {
            reader.setInput(in, true, true)
            Some((reader.getWidth(0), reader.getHeight(0)))
          } finally reader.dispose()
        }
      } finally in.close()
    }.toOption.flatten

  def progress(contestId: Long): CacheProgress =
    Option(progressMap.get(contestId)).getOrElse(CacheProgress(0, 0, 0, running = false))

  def progressForRound(roundId: Long): CacheProgress =
    Option(roundProgressMap.get(roundId)).getOrElse(CacheProgress(0, 0, 0, running = false))

  def startDownload(contestId: Long): Unit = {
    val shouldStart = new java.util.concurrent.atomic.AtomicBoolean(false)
    progressMap.compute(contestId, (_, prev) => {
      val cur = Option(prev).getOrElse(CacheProgress(0, 0, 0, running = false))
      if (cur.running) cur else { shouldStart.set(true); cur.copy(running = true) }
    })
    if (!shouldStart.get()) return
    val images = ImageJdbc
      .findByContestId(contestId)
      .filter(img => img.isImage && img.url.isDefined && img.width > 0 && img.height > 0)
    runDownload(contestId, images)
  }

  def startDownloadForRound(contestId: Long, roundId: Long): Unit = {
    val shouldStart = new java.util.concurrent.atomic.AtomicBoolean(false)
    roundProgressMap.compute(roundId, (_, prev) => {
      val cur = Option(prev).getOrElse(CacheProgress(0, 0, 0, running = false))
      if (cur.running) cur else { shouldStart.set(true); cur.copy(running = true) }
    })
    if (!shouldStart.get()) return
    val images = scala.util.Try(ImageJdbc.byRound(roundId)) match {
      case scala.util.Success(imgs) => imgs
      case scala.util.Failure(ex) =>
        logger.warn(s"Failed to load images for round $roundId: ${ex.getMessage}")
        Seq.empty
    }
    val filtered = images.filter(img => img.isImage && img.url.isDefined && img.width > 0 && img.height > 0)
    if (filtered.isEmpty) {
      roundProgressMap.put(roundId, CacheProgress(0, 0, 0, running = false))
      return
    }
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
  ): Future[Unit] = registryReady.flatMap { _ =>
    val toDownload = images.filterNot(allSizesCached)
    logger.info(s"$logLabel: ${images.size} images total, ${images.size - toDownload.size} already cached, ${toDownload.size} to download")

    // Start the clock only after the scan, so elapsed/rate/ETA reflect download time only.
    val startMs = System.currentTimeMillis()
    val total   = toDownload.size
    val done    = new AtomicInteger(0)
    val counts  = new FailureCounts

    def mkProgress(d: Int, running: Boolean): CacheProgress = {
      val elapsed = System.currentTimeMillis() - startMs
      val rate    = if (elapsed > 0) d * 1000.0 / elapsed else 0.0
      val eta     = if (rate > 0) ((total - d) / rate).toLong else 0L
      CacheProgress(d, total, counts.errors.get, running,
        startedAtMs = startMs, elapsedMs = elapsed, ratePerSec = rate, etaSeconds = eta,
        renamed = counts.renamed.get, deleted = counts.deleted.get)
    }

    tracker.put(key, mkProgress(0, running = true))

    // Those scaled from a cached size don't ask Wikimedia: first, and not throttled.
    val (local, remote) = toDownload.partition(image => localSourceFile(image).isDefined)
    Source(local)
      .concat(Source(remote).throttle(ratePerSec, 1.second))
      .mapAsyncUnordered(parallelism) { image =>
        downloadAndResize(image)
          .recoverWith { case ex => afterFailedDownload(image, ex, counts) }
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

  private final class FailureCounts {
    val errors  = new AtomicInteger(0)
    val renamed = new AtomicInteger(0)
    val deleted = new AtomicInteger(0)
  }

  /** A thumbnail that Wikimedia doesn't have: perhaps its file was renamed, re-uploaded or
    * deleted on Commons ([[checkOnCommons]]). A renamed file is downloaded again under its
    * new name, a re-uploaded one for its new size; a deleted one is counted apart from the
    * errors.
    */
  private def afterFailedDownload(image: Image, ex: Throwable, counts: FailureCounts): Future[Unit] = {
    def error(e: Throwable): Unit = {
      logger.warn(s"Failed to cache ${image.title}: ${e.getMessage}")
      counts.errors.incrementAndGet()
    }
    checkOnCommons(image, ex)
      .flatMap {
        case FileCheck.Renamed(current) =>
          downloadAndResize(current)
            .map(_ => counts.renamed.incrementAndGet(): Unit)
            .recover { case e => error(e) }
        case FileCheck.Changed(current) =>
          downloadAndResize(current).recover { case e => error(e) }
        case FileCheck.Deleted =>
          counts.deleted.incrementAndGet()
          Future.unit
        case _ =>
          Future.successful(error(ex))
      }
  }

  /** What Commons says of a file whose thumbnail failed with `ex` ([[CommonsFileCheck]],
    * which stores what it finds): asked only when Wikimedia doesn't have the thumbnail,
    * [[FileCheck.Unknown]] otherwise, or after [[CommonsFileCheck.Wait]]. For the download
    * job and for [[controllers.ImageProxyController]].
    */
  def checkOnCommons(image: Image, ex: Throwable): Future[FileCheck] =
    (ex, fileCheck) match {
      case (_: ThumbnailNotFound, Some(check)) =>
        Future.fromTry(Try(check.check(image))).flatten.recover { case _ => FileCheck.Unknown }
      case _                                   => Future.successful(FileCheck.Unknown)
    }

  /** The widths cached: those the views show the image at ([[Global.thumbnailWidths]]), if
    * narrower than the file, narrowest first. The large view's sizes are not cached: they
    * would take most of the disk.
    */
  private def targetWidths(image: Image): Seq[Int] =
    Global.thumbnailWidths(image.width, image.height).filter(px => px > 0 && px < image.width)

  /** The Wikimedia image to scale down to `px`, and its width: the thumbnail of the nearest
    * step no narrower, or the file itself if that is no wider.
    */
  private def source(image: Image, px: Int): (Int, Option[String]) = {
    val sourcePx = wikiThumbnailSteps.find(_ >= px).getOrElse(image.width)
    val url =
      if (sourcePx >= image.width) image.url.map(_.replaceFirst("https?://upload\\.wikimedia\\.org", wikiBaseUrl))
      else wikiThumbUrl(image, sourcePx)
    (sourcePx, url)
  }

  private[services] def allSizesCached(image: Image): Boolean =
    CommonsFileCheck.isCommonsFile(image) &&
    targetWidths(image).forall(px => registry.containsKey(localFile(image, px).getAbsolutePath))

  /** Where Wikimedia serves the file `px` wide: its thumbnail, or the file itself if it is
    * no wider and a browser can show it ([[Image.thumbnailOnly]], as
    * [[controllers.Global.resizeTo]] does). None without a Commons url.
    */
  def wikimediaUrl(image: Image, px: Int): Option[String] =
    if (image.width > 0 && px >= image.width && !image.thumbnailOnly) image.url.filter(CommonsFileCheck.isCommonsUrl)
    else wikiThumbUrl(image, px)

  // Mirrors the URL construction in Global.legacyThumbUlr, using wikiBaseUrl as host
  private[services] def wikiThumbUrl(image: Image, px: Int): Option[String] =
    image.url.filter(CommonsFileCheck.isCommonsUrl).map { rawUrl =>
      val lower     = image.title.toLowerCase
      val isPdf     = lower.endsWith(".pdf")
      val isTif     = lower.endsWith(".tif") || lower.endsWith(".tiff")
      // Commons imageinfo API appends UTM tracking params to `url` (e.g. "?utm_source=..."),
      // which must be stripped before treating the trailing segment as a filename.
      val url       = rawUrl.takeWhile(_ != '?')
      val lastSlash = url.lastIndexOf("/")
      val utf8Size  = image.title.getBytes("UTF-8").length
      val thumbStr  = if (utf8Size > 165) "thumbnail.jpg" else url.substring(lastSlash + 1)
      val scheme = wikiBaseUrl.takeWhile(_ != ':')
      val host   = wikiBaseUrl.replaceFirst("https?://", "")
      url.replaceFirst("https?://upload\\.wikimedia\\.org/wikipedia/commons/",
        s"$scheme://$host/wikipedia/commons/thumb/"
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
      .replaceFirst("https?://[^/]+", "")
    // Use replace("+", "%2B") before URLDecoder so literal '+' in path segments is not
    // mistakenly decoded as a space (URLDecoder follows query-param rules, not path rules).
    val decoded = java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
    new File(localPath + decoded)
  }

  /** Caches the target sizes of the image: scaled from the widest of them if cached
    * ([[localSource]]), else downloaded from Wikimedia.
    */
  private def downloadAndResize(image: Image): Future[Unit] =
    Future(localSource(image)).flatMap {
      case Some((localPx, localImg)) =>
        saveResized(image, localImg, localPx)
        Future.unit
      case None =>
        downloadFromWikimedia(image)
    }

  private def downloadFromWikimedia(image: Image): Future[Unit] = {
    val widest             = targetWidths(image).lastOption.getOrElse(image.width)
    val (sourcePx, urlOpt) = source(image, widest)

    urlOpt match {
      case None =>
        logger.warn(s"No cacheable URL for ${image.title} (url=${image.url})")
        Future.failed(new Exception(s"No cacheable URL for ${image.title}"))
      case Some(url) =>
        downloadWithRetry(url, attempt = 1).flatMap {
          case None =>
            Future.failed(new Exception(s"Download failed for $url"))
          case Some(bytes) =>
            val sourceImg = ImageIO.read(new ByteArrayInputStream(bytes))
            if (sourceImg != null) {
              saveResized(image, sourceImg, sourcePx)
              Future.successful(())
            } else {
              Future.failed(new Exception(s"Could not decode image from $url"))
            }
        }
    }
  }

  /** Downloads a URL, retrying on 429 with exponential backoff + jitter.
    * Respects the Retry-After header if present. Gives up after maxAttempts.
    * None on another failed status, but a 404: [[ThumbnailNotFound]].
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

        case resp if resp.status == StatusCodes.NotFound =>
          resp.entity.discardBytes()
          logger.warn(s"HTTP ${resp.status} for $url")
          Future.failed(new ThumbnailNotFound(url))

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

  /** Generates all target sizes via cascaded downscaling and saves them asynchronously.
    *
    * @param requestedPx if Some(px), returns the BufferedImage for that width if generated
    * @return Some(img) for the requested width if it was generated; None otherwise
    */
  private[services] def saveResized(
    image: Image,
    sourceImg: BufferedImage,
    sourcePx: Int,
    requestedPx: Option[Int] = None
  ): Option[BufferedImage] = {
    val toGenerate = targetWidths(image)
      .filter(_ <= sourcePx)
      .distinct
      .sorted(Ordering[Int].reverse)  // largest first for cascade

    val results = cascadeScale(sourceImg, toGenerate)

    results.foreach { case (px, img) =>
      val file     = localFile(image, px)
      val filePath = file.getAbsolutePath
      // Re-write if not in registry OR if registry claims it but file was deleted from disk.
      if (!registry.containsKey(filePath) || !file.exists()) {
        registry.put(filePath, ())   // claim before async write
        file.getParentFile.mkdirs()
        Future {
          // Write to a temp file then atomically rename, so a reader (Apache or the
          // sibling blue/green instance sharing this directory) never sees a partial JPEG.
          val tmp = File.createTempFile(".tmp-", ".jpg", file.getParentFile)
          try {
            ImageIO.write(img, "JPEG", tmp)
            Files.move(tmp.toPath, file.toPath,
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING)
          } catch {
            case ex: Throwable =>
              tmp.delete()
              throw ex
          }
        }.recover { case ex =>
          logger.warn(s"Failed to write ${file.getName}: ${ex.getMessage}")
          registry.remove(filePath)  // release claim on failure
        }
      }
    }

    requestedPx.flatMap(rpx => results.find(_._1 == rpx).map(_._2))
  }

  private[services] def scale(src: BufferedImage, targetWidth: Int): BufferedImage =
    scale(src, targetWidth, src)

  /** @param proportions the image whose proportions give the height */
  private def scale(src: BufferedImage, targetWidth: Int, proportions: BufferedImage): BufferedImage = {
    val targetHeight = (proportions.getHeight.toDouble * targetWidth / proportions.getWidth).toInt
    val out = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
    val g   = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.drawImage(src, 0, 0, targetWidth, targetHeight, null)
    g.dispose()
    out
  }

  /** Scales sourceImg down through targetWidths in order (largest first).
    * Each step uses the previous output as source, reducing the downscale ratio
    * and the number of pixels read per step. Each height follows from sourceImg's
    * proportions, so rounding doesn't add up over the steps. Pure CPU — no IO.
    *
    * @param sourceImg   the starting (largest) image
    * @param targetWidths widths to generate, sorted descending (largest first)
    * @return (width, image) pairs in the same order as targetWidths
    */
  private[services] def cascadeScale(
    sourceImg: BufferedImage,
    targetWidths: Seq[Int]
  ): Seq[(Int, BufferedImage)] = {
    require(
      targetWidths.isEmpty || targetWidths == targetWidths.sorted(Ordering[Int].reverse),
      "targetWidths must be sorted descending (largest first)"
    )
    var prev = sourceImg
    targetWidths.map { px =>
      val scaled = scale(prev, px, sourceImg)
      prev = scaled
      (px, scaled)
    }
  }

  private val registryReady: Future[Unit] = initRegistry()

  private[services] val unusedSizesCleanedUp: Future[Unit] =
    registryReady.flatMap(_ => cleanUpUnusedSizes()).recover { case ex =>
      logger.warn(s"Failed to delete the cached files of sizes no longer cached: ${ex.getMessage}", ex)
    }
}
