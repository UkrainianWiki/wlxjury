# Local Image Cache — Integration Tests & Time Tracking Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add time tracking to `CacheProgress` (rate, ETA, elapsed) and implement integration tests
that download ~50 real images from Wikimedia Commons and verify all thumbnail sizes are cached.

**Architecture:** `CacheProgress` grows three computed fields populated by `startDownload`; the
frontend JS is updated to display them. Integration tests live in the `services` package (accessing
`private[services]` methods directly), are gated by `-Dintegration=true`, and use scalawiki's
`MwBot` to fetch real image metadata from a known Commons category.

**Tech Stack:** Scala 3, Play Framework, Apache Pekko Streams, scalawiki `MwBot`, specs2 mutable

---

### Task 1: Update `CacheProgress` with time-tracking fields

**Files:**
- Modify: `app/services/LocalImageCacheService.scala:25-29` (CacheProgress + companion)
- Modify: `app/services/LocalImageCacheService.scala:64-96` (progress default + startDownload)

**Step 1: Update the `CacheProgress` case class**

Replace lines 25–29 in `app/services/LocalImageCacheService.scala`:

```scala
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
}
```

**Step 2: Update the default returned by `progress()`**

Line 65 becomes:
```scala
Option(progressMap.get(contestId)).getOrElse(CacheProgress(0, 0, 0, running = false))
```
No change needed — new fields have defaults of 0.

**Step 3: Update `startDownload` to record start time and compute derived fields**

Replace the body of `startDownload` (lines 67–97):

```scala
def startDownload(contestId: Long): Unit = {
  if (progress(contestId).running) return

  val images = ImageJdbc
    .findByContestId(contestId)
    .filter(img => img.isImage && img.url.isDefined && img.width > 0 && img.height > 0)
  val total   = images.size
  val done    = new AtomicInteger(0)
  val errs    = new AtomicInteger(0)
  val startMs = System.currentTimeMillis()

  def mkProgress(d: Int, running: Boolean): CacheProgress = {
    val elapsed = System.currentTimeMillis() - startMs
    val rate    = if (elapsed > 0) d * 1000.0 / elapsed else 0.0
    val eta     = if (rate > 0) ((total - d) / rate).toLong else 0L
    CacheProgress(d, total, errs.get, running,
      startedAtMs = startMs, elapsedMs = elapsed, ratePerSec = rate, etaSeconds = eta)
  }

  progressMap.put(contestId, mkProgress(0, running = true))

  Source(images)
    .filterNot(allSizesCached)
    .throttle(ratePerSec, 1.second)
    .mapAsyncUnordered(parallelism) { image =>
      downloadAndResize(image)
        .recover { case ex =>
          logger.warn(s"Failed to cache ${image.title}: ${ex.getMessage}")
          errs.incrementAndGet()
        }
        .map { _ =>
          val d = done.incrementAndGet()
          progressMap.put(contestId, mkProgress(d, running = true))
        }
    }
    .runWith(Sink.ignore)
    .onComplete { _ =>
      progressMap.put(contestId, mkProgress(done.get, running = false))
    }
}
```

**Step 4: Compile**

```
sbt compile
```
Expected: success, no errors.

**Step 5: Run existing unit tests to confirm nothing broke**

```
sbt "testOnly services.LocalImageCacheServiceSpec"
```
Expected: 30 examples, 0 failures.

**Step 6: Commit**

```bash
git add app/services/LocalImageCacheService.scala
git commit -m "feat: add time tracking (rate, ETA, elapsed) to CacheProgress"
```

---

### Task 2: Update frontend to display rate and ETA

**Files:**
- Modify: `app/views/contest_images.scala.html:58-67` (the `.then(function(p) {...})` block)

**Step 1: Replace the status display block**

The current block (lines 58–67) formats only `done/total/pct/errors`. Replace it with:

```javascript
.then(function(p) {
    var el = document.getElementById("cache-status");
    if (p.total > 0) {
        var pct    = Math.round(100 * p.done / p.total);
        var errStr = p.errors > 0 ? ", " + p.errors + " errors" : "";
        var rate   = p.ratePerSec > 0 ? " – " + p.ratePerSec.toFixed(1) + " img/s" : "";
        var eta    = "";
        if (p.running && p.etaSeconds > 0) {
            eta = p.etaSeconds >= 60
                ? " – ETA " + Math.floor(p.etaSeconds / 60) + "m " + (p.etaSeconds % 60) + "s"
                : " – ETA " + p.etaSeconds + "s";
        }
        el.textContent = p.done + " / " + p.total + " downloaded (" + pct + "%" + errStr + ")"
            + rate + eta + (p.running ? " – running…" : " – done");
    } else if (p.running) {
        el.textContent = "Starting…";
    }
    if (p.running) { setTimeout(update, 3000); }
})
```

**Step 2: Compile templates**

```
sbt compile
```
Expected: success.

**Step 3: Commit**

```bash
git add app/views/contest_images.scala.html
git commit -m "feat: show download rate and ETA in image cache status UI"
```

---

### Task 3: Write the integration test spec

**Files:**
- Create: `test/services/LocalImageCacheServiceIntegrationSpec.scala`

The test must be in package `services` to access `private[services]` methods. It uses:
- `org.scalawiki.MwBot` + `org.scalawiki.dto.{Namespace, Page}` to query Commons
- The same `ActorSystem` / `ExecutionContext` pattern as the unit spec
- `skipAllUnless` to gate on `-Dintegration=true`

**Step 1: Write the spec**

Create `test/services/LocalImageCacheServiceIntegrationSpec.scala`:

```scala
package services

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.intracer.wmua.{Image, ImageUtil}
import org.scalawiki.MwBot
import org.scalawiki.dto.Namespace
import org.specs2.mutable.Specification
import play.api.Configuration

import java.io.{ByteArrayInputStream, File}
import java.nio.file.Files
import javax.imageio.ImageIO
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class LocalImageCacheServiceIntegrationSpec extends Specification {

  sequential
  skipAllUnless(sys.props.get("integration").contains("true"))

  implicit val system: ActorSystem  = ActorSystem("LocalImageCacheIntegrationSpec")
  implicit val ec: ExecutionContext = system.dispatcher

  // ── helpers ────────────────────────────────────────────────────────────────

  val tempDir: File = Files.createTempDirectory("wlxjury-integration").toFile

  val svc: LocalImageCacheService = {
    val config = Configuration(
      ConfigFactory.parseString(
        s"""wlxjury.thumbs.local-path = "${tempDir.getAbsolutePath}"
           |wlxjury.thumbs.parallelism = 4
           |wlxjury.thumbs.rate-per-second = 5
           |wlxjury.thumbs.max-attempts = 5
           |""".stripMargin
      )
    )
    new LocalImageCacheService(config, system)
  }

  // source height mirrors LocalImageCacheService.sourceHeight
  private val sourceHeight = (controllers.Global.largeSizeY * 1.5).toInt // 1650

  private val imageInfoProps = Set("timestamp", "user", "size", "url", "mime")

  /** Fetch up to `limit` eligible images from a Commons category via scalawiki. */
  def fetchImagesFromCommons(category: String, limit: Int): Seq[Image] = {
    val bot = MwBot.fromHost("commons.wikimedia.org")
    val pages = Await.result(
      bot
        .page(category)
        .imageInfoByGenerator(
          "categorymembers",
          "cm",
          namespaces = Set(Namespace.FILE),
          props      = imageInfoProps,
          titlePrefix = None
        )
        .map(_.toSeq),
      60.seconds
    )
    pages
      .flatMap { page =>
        page.images.headOption.flatMap { ii =>
          for {
            id  <- page.id
            url <- ii.url
            w   <- ii.width
            h   <- ii.height
          } yield Image(id, page.title, Some(url), ii.pageUrl, w, h, mime = ii.mime)
        }
      }
      .filter(img => img.isImage && img.url.isDefined && img.width > 0 && img.height > 0)
      .take(limit)
  }

  /** Run download+resize for a single image. Returns true on success. */
  def processImage(image: Image): Future[Boolean] = {
    val sourcePx = math.min(
      ImageUtil.resizeTo(image.width, image.height, sourceHeight),
      image.width
    )
    val urlOpt = if (sourcePx >= image.width) image.url else svc.wikiThumbUrl(image, sourcePx)
    urlOpt match {
      case None => Future.successful(false)
      case Some(url) =>
        svc.downloadWithRetry(url, attempt = 1).map {
          case Some(bytes) =>
            val sourceImg = ImageIO.read(new ByteArrayInputStream(bytes))
            if (sourceImg != null) {
              svc.saveResized(image, sourceImg, sourcePx)
              true
            } else false
          case None => false
        }
    }
  }

  // ── tests ──────────────────────────────────────────────────────────────────

  "LocalImageCacheService integration" should {

    "download and resize all images from Category:Images from Wiki Loves Earth 2021 in Armenia" in {

      val category = "Category:Images from Wiki Loves Earth 2021 in Armenia"
      val images   = fetchImagesFromCommons(category, limit = 50)

      images must not(beEmpty)

      val startMs = System.currentTimeMillis()

      val results: Seq[Boolean] = Await.result(
        Source(images)
          .throttle(5, 1.second)
          .mapAsyncUnordered(4)(processImage)
          .runWith(Sink.seq),
        60.seconds
      )

      val elapsed = System.currentTimeMillis() - startMs
      val succeeded = results.count(identity)
      println(f"Integration test: $succeeded/${images.size} images processed in ${elapsed / 1000.0}%.1fs")

      // All images must succeed — this feature exists to eliminate missing images
      succeeded mustEqual images.size

      // Every processed image must have all thumbnail sizes on disk
      images.foreach { img =>
        svc.allSizesCached(img) must beTrue
      }
    }
  }

  step {
    Await.result(system.terminate(), 30.seconds)
    ()
  }
}
```

**Step 2: Compile**

```
sbt compile
```
Expected: success.

**Step 3: Run the integration test**

```
sbt -Dintegration=true "testOnly services.LocalImageCacheServiceIntegrationSpec"
```
Expected: 1 example, 0 failures. Observe the printed timing line.

Verify the test is skipped when the property is absent:
```
sbt "testOnly services.LocalImageCacheServiceIntegrationSpec"
```
Expected: 0 examples run (all skipped).

**Step 4: Commit**

```bash
git add test/services/LocalImageCacheServiceIntegrationSpec.scala
git commit -m "test: integration tests for LocalImageCacheService against real Wikimedia Commons"
```

---

### Task 4: Run full test suite to confirm nothing regressed

**Step 1:**

```
sbt test
```
Expected: all existing unit tests pass; integration spec shows 0 examples (skipped).

**Step 2: Commit if any fixes were needed; otherwise done.**
