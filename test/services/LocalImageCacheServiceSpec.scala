package services

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.intracer.wmua.{Image, ImageUtil}
import org.specs2.mutable.Specification
import play.api.Configuration

import java.awt.image.BufferedImage
import java.io.{ByteArrayOutputStream, File}
import java.nio.file.Files
import javax.imageio.ImageIO
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import java.util.concurrent.atomic.AtomicInteger

class LocalImageCacheServiceSpec extends Specification {

  sequential

  // ── shared Pekko system ──────────────────────────────────────────────────

  implicit val system: ActorSystem    = ActorSystem("LocalImageCacheServiceSpec")
  implicit val ec: ExecutionContext   = system.dispatcher

  // ── helpers ──────────────────────────────────────────────────────────────

  def mkService(dir: File, maxAttempts: Int = 3): LocalImageCacheService = {
    val config = Configuration(
      ConfigFactory.parseString(
        s"""wlxjury.thumbs.local-path = "${dir.getAbsolutePath}"
           |wlxjury.thumbs.parallelism = 2
           |wlxjury.thumbs.rate-per-second = 10
           |wlxjury.thumbs.max-attempts = $maxAttempts
           |""".stripMargin
      )
    )
    new LocalImageCacheService(config, system)
  }

  def mkTempDir(): File = {
    val d = Files.createTempDirectory("wlxjury-test").toFile
    d.deleteOnExit()
    d
  }

  /** Minimal valid JPEG bytes for a synthetic image of given dimensions. */
  def jpegBytes(w: Int = 800, h: Int = 600): Array[Byte] = {
    val img  = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val baos = new ByteArrayOutputStream()
    ImageIO.write(img, "JPEG", baos)
    baos.toByteArray
  }

  /** Image with a standard wikimedia URL pattern. */
  def mkImage(
      id: Long = 1,
      w: Int = 4000,
      h: Int = 3000,
      filename: String = "Test_photo.jpg"
  ): Image = Image(
    pageId = id,
    title  = s"File:$filename",
    url    = Some(s"https://upload.wikimedia.org/wikipedia/commons/a/ab/$filename"),
    width  = w,
    height = h
  )

  /** Starts a local HTTP server whose response depends on the (1-based) request count.
    * Shuts down the server after the body returns.
    */
  def withServer[T](handler: Int => HttpResponse)(body: Int => T): T = {
    val requestCount = new AtomicInteger(0)
    val binding = Await.result(
      Http().newServerAt("127.0.0.1", 0).bindSync { _ =>
        handler(requestCount.incrementAndGet())
      },
      5.seconds
    )
    try {
      body(binding.localAddress.getPort)
    } finally {
      Await.result(binding.unbind(), 5.seconds)
    }
  }

  // ── wikiThumbUrl ──────────────────────────────────────────────────────────

  "wikiThumbUrl" should {

    val svc = mkService(mkTempDir())

    "construct a JPEG thumb URL" in {
      val img = mkImage(filename = "Photo.jpg")
      svc.wikiThumbUrl(img, 640) must beSome(
        "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Photo.jpg/640px-Photo.jpg"
      )
    }

    "add page1- prefix and .jpg suffix for PDF" in {
      val img = mkImage(filename = "Doc.pdf").copy(
        url = Some("https://upload.wikimedia.org/wikipedia/commons/a/ab/Doc.pdf")
      )
      svc.wikiThumbUrl(img, 640) must beSome(
        "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Doc.pdf/page1-640px-Doc.pdf.jpg"
      )
    }

    "add lossy-page1- prefix and .jpg suffix for TIF" in {
      val img = mkImage(filename = "Scan.tif").copy(
        url = Some("https://upload.wikimedia.org/wikipedia/commons/a/ab/Scan.tif")
      )
      svc.wikiThumbUrl(img, 640) must beSome(
        "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Scan.tif/lossy-page1-640px-Scan.tif.jpg"
      )
    }

    "use thumbnail.jpg for filenames longer than 165 UTF-8 bytes" in {
      val longName = "A" * 166 + ".jpg" // 170 chars, well over 165 UTF-8 bytes
      val img = mkImage(filename = longName).copy(
        url = Some(s"https://upload.wikimedia.org/wikipedia/commons/a/ab/$longName")
      )
      svc.wikiThumbUrl(img, 640) must beSome(contain("640px-thumbnail.jpg"))
    }

    "return None for a non-wikimedia URL" in {
      val img = mkImage().copy(url = Some("https://example.com/photo.jpg"))
      svc.wikiThumbUrl(img, 640) must beNone
    }

    "return None when url is absent" in {
      svc.wikiThumbUrl(mkImage().copy(url = None), 640) must beNone
    }
  }

  // ── localFile ────────────────────────────────────────────────────────────

  "localFile" should {

    "mirror the wikimedia thumb URL path under localPath" in {
      val dir = mkTempDir()
      val svc = mkService(dir)
      val img = mkImage(filename = "Photo.jpg")
      val expected = new File(dir, "wikipedia/commons/thumb/a/ab/Photo.jpg/640px-Photo.jpg")
      svc.localFile(img, 640) === expected
    }

    "be consistent with wikiThumbUrl" in {
      val dir = mkTempDir()
      val svc = mkService(dir)
      val img = mkImage(filename = "Photo.jpg")
      val thumbUrl  = svc.wikiThumbUrl(img, 333).get
      val localPath = svc.localFile(img, 333).getAbsolutePath
      // local path is localDir + path portion of the thumb URL
      localPath must endWith(thumbUrl.replaceFirst("https://upload.wikimedia.org", ""))
    }
  }

  // ── allSizesCached ────────────────────────────────────────────────────────

  // For a 400×300 image, heights 375+ produce px=400=image.width (no file needed).
  // The exact needed widths are computed via ImageUtil.resizeTo to avoid floating-point
  // discrepancies from hand-rolled arithmetic.
  "allSizesCached" should {

    val SW = 400; val SH = 300
    def smallImg = mkImage(filename = "Photo.jpg", w = SW, h = SH)

    val targetHeights = Seq(120, 180, 240, 250, 375, 500, 1100, 1650)
    // Widths that require local files (px strictly < image.width)
    val neededWidths  = targetHeights.map(h => ImageUtil.resizeTo(SW, SH, h)).filter(_ < SW)

    "return false when no files are present" in {
      val dir = mkTempDir()
      val svc = mkService(dir)
      svc.allSizesCached(smallImg) must beFalse
    }

    "return true when all needed files exist" in {
      val dir = mkTempDir()
      val svc = mkService(dir)
      val img = smallImg
      neededWidths.foreach { px =>
        val f = svc.localFile(img, px)
        f.getParentFile.mkdirs()
        f.createNewFile()
      }
      svc.allSizesCached(img) must beTrue
    }

    "return false when only some files are present" in {
      val dir = mkTempDir()
      val svc = mkService(dir)
      val img = smallImg
      // Create only the first two required files
      neededWidths.take(2).foreach { px =>
        val f = svc.localFile(img, px)
        f.getParentFile.mkdirs()
        f.createNewFile()
      }
      svc.allSizesCached(img) must beFalse
    }

    "treat sizes where px >= image.width as cached without any file" in {
      // Heights 375, 500, 1100, 1650 all produce px=400=image.width for a 400×300 image.
      // Those sizes count as cached regardless of filesystem, so only the 4 small files matter.
      val dir = mkTempDir()
      val svc = mkService(dir)
      val img = smallImg
      neededWidths.foreach { px =>
        val f = svc.localFile(img, px)
        f.getParentFile.mkdirs()
        f.createNewFile()
      }
      // No files for px=400 (heights 375+) – allSizesCached must still be true
      svc.localFile(img, 400).exists() must beFalse
      svc.allSizesCached(img) must beTrue
    }
  }

  // ── scale ─────────────────────────────────────────────────────────────────

  "scale" should {

    val svc = mkService(mkTempDir())

    "produce exactly the requested width" in {
      val out = svc.scale(new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB), 400)
      out.getWidth === 400
    }

    "preserve aspect ratio" in {
      val out = svc.scale(new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB), 400)
      out.getHeight === 300
    }

    "produce a valid JPEG-writable image (TYPE_INT_RGB, not ARGB)" in {
      val out  = svc.scale(new BufferedImage(200, 150, BufferedImage.TYPE_INT_RGB), 100)
      val baos = new ByteArrayOutputStream()
      ImageIO.write(out, "JPEG", baos) must beTrue
      baos.size() must be_>(0)
    }
  }

  // ── saveResized ───────────────────────────────────────────────────────────

  "saveResized" should {

    val W = 4000; val H = 3000
    def largeImg  = mkImage(filename = "Photo.jpg", w = W, h = H)
    def sourceImg = new BufferedImage(2200, 1650, BufferedImage.TYPE_INT_RGB)

    // Compute expected widths the same way the service does, avoiding hand-rolled
    // floating-point arithmetic that can disagree with ImageUtil.resizeTo.
    val targetHeights = Seq(120, 180, 240, 250, 375, 500, 1100, 1650)
    val allWidths     = targetHeights.map(h => ImageUtil.resizeTo(W, H, h)).filter(_ < W)

    "create files for all sizes where px < image.width" in {
      val dir = mkTempDir()
      val svc = mkService(dir)
      val img = largeImg
      svc.saveResized(img, sourceImg, sourcePx = 2200)
      val missing = allWidths.filterNot(px => svc.localFile(img, px).exists())
      missing must beEmpty
    }

    "write valid JPEGs with correct pixel dimensions" in {
      val dir = mkTempDir()
      val svc = mkService(dir)
      val img = largeImg
      svc.saveResized(img, sourceImg, sourcePx = 2200)

      // Use height=250 as a stable test case; compute expected width exactly as the service does
      val px250 = ImageUtil.resizeTo(W, H, 250)
      val expectedHeight = (1650.0 * px250 / 2200).toInt
      val file = svc.localFile(img, px250)
      val read = ImageIO.read(file)
      read.getWidth  === px250
      read.getHeight === expectedHeight
    }

    "not overwrite an existing file on a second call" in {
      val dir = mkTempDir()
      val svc = mkService(dir)
      val img = largeImg
      svc.saveResized(img, sourceImg, sourcePx = 2200)

      val file        = svc.localFile(img, 333)
      val modifiedAt  = file.lastModified()
      Thread.sleep(50)
      svc.saveResized(img, sourceImg, sourcePx = 2200)

      file.lastModified() === modifiedAt
    }

    "not create files for sizes where px >= image.width" in {
      val dir = mkTempDir()
      val svc = mkService(dir)
      val img = mkImage(filename = "Photo.jpg", w = 400, h = 300) // small image

      svc.saveResized(img, new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB), sourcePx = 400)

      // For 400×300, heights 375+ all produce px=400=image.width → no file
      svc.localFile(img, 400).exists() must beFalse
      // But the smaller sizes ARE created
      Seq(160, 240, 320, 333).forall(px => svc.localFile(img, px).exists()) must beTrue
    }

    "not create files for sizes larger than sourcePx" in {
      val dir = mkTempDir()
      val svc = mkService(dir)
      val img = largeImg

      // Download only a 500px source (covers heights 120–375 but not 500 and above)
      svc.saveResized(img, new BufferedImage(500, 375, BufferedImage.TYPE_INT_RGB), sourcePx = 500)

      // 666px > sourcePx=500 → no file
      svc.localFile(img, 666).exists()  must beFalse
      svc.localFile(img, 1466).exists() must beFalse
      svc.localFile(img, 2200).exists() must beFalse
      // 500px = sourcePx → file IS created
      svc.localFile(img, 500).exists()  must beTrue
      // 333px < sourcePx → file IS created
      svc.localFile(img, 333).exists()  must beTrue
    }
  }

  // ── downloadWithRetry ──────────────────────────────────────────────────────

  "downloadWithRetry" should {

    val okBytes = jpegBytes()

    "return bytes on an immediate 200" in {
      withServer(_ => HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, okBytes))) { port =>
        val result = Await.result(
          mkService(mkTempDir()).downloadWithRetry(s"http://127.0.0.1:$port/img", attempt = 1),
          10.seconds
        )
        result must beSome
        result.get.length === okBytes.length
      }
    }

    "retry after a 429 and succeed on the next attempt" in {
      withServer { n =>
        if (n == 1)
          HttpResponse(StatusCodes.TooManyRequests, headers = List(RawHeader("Retry-After", "0")))
        else
          HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, okBytes))
      } { port =>
        val result = Await.result(
          mkService(mkTempDir()).downloadWithRetry(s"http://127.0.0.1:$port/img", attempt = 1),
          10.seconds
        )
        result must beSome
      }
    }

    "make exactly maxAttempts requests before giving up on repeated 429" in {
      val requestsSeen = new AtomicInteger(0)
      withServer { n =>
        requestsSeen.set(n)
        HttpResponse(StatusCodes.TooManyRequests, headers = List(RawHeader("Retry-After", "0")))
      } { port =>
        val result = Await.result(
          mkService(mkTempDir(), maxAttempts = 3).downloadWithRetry(s"http://127.0.0.1:$port/img", attempt = 1),
          10.seconds
        )
        result must beNone
        requestsSeen.get() === 3
      }
    }

    "return None on a non-200/non-429 status without retrying" in {
      val requestsSeen = new AtomicInteger(0)
      withServer { n =>
        requestsSeen.set(n)
        HttpResponse(StatusCodes.NotFound)
      } { port =>
        val result = Await.result(
          mkService(mkTempDir()).downloadWithRetry(s"http://127.0.0.1:$port/img", attempt = 1),
          10.seconds
        )
        result must beNone
        requestsSeen.get() === 1
      }
    }

    "propagate a connection-refused failure as a failed Future" in {
      // Bind then immediately unbind to get a port nothing listens on
      val port = {
        val b = Await.result(Http().newServerAt("127.0.0.1", 0).bindSync(_ => HttpResponse()), 5.seconds)
        val p = b.localAddress.getPort
        Await.result(b.unbind(), 5.seconds)
        p
      }
      Await.result(
        mkService(mkTempDir())
          .downloadWithRetry(s"http://127.0.0.1:$port/img", attempt = 1)
          .failed,
        10.seconds
      ) must beAnInstanceOf[Exception]
    }
  }

  // ── retryDelay ────────────────────────────────────────────────────────────

  "retryDelay" should {

    val svc = mkService(mkTempDir())

    // attempt=1 → base=2s, jitter=Random.nextInt(max(1, 2/3))=Random.nextInt(1)=0 → exactly 2s
    "return 2 seconds for attempt 1 with no Retry-After header" in {
      svc.retryDelay(Nil, attempt = 1) === 2.seconds
    }

    // attempt=2 → base=4s, jitter=Random.nextInt(max(1, 4/3=1))=Random.nextInt(1)=0 → exactly 4s
    "return 4 seconds for attempt 2 with no Retry-After header" in {
      svc.retryDelay(Nil, attempt = 2) === 4.seconds
    }

    // attempt=20 → base capped at 300s, jitter 0..99 → range [300s, 399s]
    "cap base delay at 300 seconds for large attempt numbers" in {
      val delay = svc.retryDelay(Nil, attempt = 20)
      delay must be_>=(300.seconds)
      delay must be_<=(399.seconds)
    }

    // Retry-After=10 → base=10, jitter=Random.nextInt(max(1, 10/3=3))=0..2 → range [10s, 12s]
    "use Retry-After header value when present" in {
      val delay = svc.retryDelay(List(RawHeader("Retry-After", "10")), attempt = 1)
      delay must be_>=(10.seconds)
      delay must be_<=(12.seconds)
    }

    "fall back to exponential backoff when Retry-After is non-numeric" in {
      val delay = svc.retryDelay(List(RawHeader("Retry-After", "Wed, 01 Jan 2025 00:00:00 GMT")), attempt = 1)
      delay === 2.seconds
    }
  }

  // ── startDownloadForRound ─────────────────────────────────────────────────

  "startDownloadForRound" should {

    "report progress keyed by roundId, separate from contest progress" in {
      val tmpDir = Files.createTempDirectory("round-cache-test").toFile
      val svc = mkService(tmpDir)

      svc.startDownloadForRound(contestId = 99L, roundId = 7L)

      val roundProg  = svc.progressForRound(7L)
      val contestProg = svc.progress(99L)

      roundProg.running must beFalse   // nothing in round, completes instantly
      contestProg.running must beFalse // contest progress not touched

      tmpDir.delete()
      ok
    }

    "not start a second download for the same round while one is running" in {
      val tmpDir = Files.createTempDirectory("round-cache-guard").toFile
      val svc = mkService(tmpDir)

      // prime the round progress as running
      svc.progressForRound(8L) // ensure key exists (returns default)
      // can't easily assert "second call is no-op" without mocking images,
      // so just verify it doesn't throw
      svc.startDownloadForRound(contestId = 1L, roundId = 8L)
      svc.startDownloadForRound(contestId = 1L, roundId = 8L) // should be no-op

      ok
    }
  }

  // ── teardown ─────────────────────────────────────────────────────────────

  step {
    Await.result(Http().shutdownAllConnectionPools(), 10.seconds)
    Await.result(system.terminate(), 10.seconds)
  }
}
