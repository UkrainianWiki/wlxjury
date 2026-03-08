# Play Image Proxy Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Serve cached images directly from the Play app (no Apache required), using an in-memory file registry for O(1) cache lookups instead of expensive directory scans.

**Architecture:** `LocalImageCacheService` gains a `ConcurrentHashMap[String, Unit]` registry populated at startup by a single background scan, then kept in sync as files are written. A new `ImageProxyController` handles `GET /wikipedia/commons/thumb/*path`: registry hit → stream file from disk; miss → download source from Wikimedia, resize all target sizes from memory (skipping any already in registry), return the requested size bytes to the browser while saving other sizes to disk in background. Setting `wlxjury.thumbs.host` to the Play app's own host routes all `<img>` URLs through this controller.

**Tech Stack:** Scala 2.13, Play Framework 3.0, specs2, ScalikeJDBC (`ImageJdbc`), Pekko HTTP (already used in `LocalImageCacheService`), `javax.imageio.ImageIO`, `java.util.concurrent.ConcurrentHashMap`.

**Key files to read before starting:**
- `app/services/LocalImageCacheService.scala` — all existing methods; understand `saveResized`, `allSizesCached`, `runDownloadImpl`, `localFileSet`, `downloadAndResize`, `wikiThumbUrl`, `localFile`
- `test/services/LocalImageCacheServiceSpec.scala` — `mkService`, `mkImage`, `withServer` helpers; test structure
- `app/controllers/ImageController.scala` — how `withAuth`/`contestPermission` and injection work
- `conf/routes` — where to add the new proxy route (must be **before** the `GET /assets/*file` line)
- `conf/application.conf` — `wlxjury.thumbs.host` default (line 41)

---

### Task 1: Replace `localFileSet` scan with in-memory registry

**Files:**
- Modify: `app/services/LocalImageCacheService.scala`
- Modify: `test/services/LocalImageCacheServiceSpec.scala`

**Background:**
Currently `runDownloadImpl` calls `localFileSet()` (walks the whole directory tree) every time a batch download starts. `allSizesCached(image, existing: Set[File])` checks membership in that set. With the registry these become O(1) lookups. The two `allSizesCached` overloads merge into one (both callers get the same registry-backed check).

**Step 1: Write the failing tests**

In `test/services/LocalImageCacheServiceSpec.scala`, add a new test group after the `"startDownloadForRound"` group:

```scala
"registry" should {

  "be empty before any files are saved" in {
    val tmpDir = Files.createTempDirectory("registry-test").toFile
    val svc = mkService(tmpDir)
    svc.registrySize mustEqual 0
    tmpDir.delete()
    ok
  }

  "contain a file after saveResized writes it" in {
    val tmpDir = Files.createTempDirectory("registry-save").toFile
    val svc    = mkService(tmpDir)
    val img    = mkImage()
    val src    = ImageIO.read(new ByteArrayInputStream(jpegBytes(800, 600)))

    svc.saveResized(img, src, 800)

    val expectedFile = svc.localFile(img, neededWidths.head)
    svc.registryContains(expectedFile) must beTrue
    tmpDir.delete()
    ok
  }

  "be populated by initRegistry from existing files on disk" in {
    val tmpDir = Files.createTempDirectory("registry-init").toFile
    val svc1   = mkService(tmpDir)
    val img    = mkImage()
    val src    = ImageIO.read(new ByteArrayInputStream(jpegBytes(800, 600)))
    svc1.saveResized(img, src, 800)

    // New service instance — should find the same files via startup scan
    val svc2 = mkService(tmpDir)
    Await.result(svc2.initRegistry(), 10.seconds)

    val expectedFile = svc2.localFile(img, neededWidths.head)
    svc2.registryContains(expectedFile) must beTrue
    tmpDir.delete()
    ok
  }
}
```

Add these imports at the top of the spec if not already present:
```scala
import scala.concurrent.Await
import scala.concurrent.duration._
import javax.imageio.ImageIO
import java.io.ByteArrayInputStream
```

**Step 2: Add test-visible accessors to `LocalImageCacheService` and `initRegistry`**

In `LocalImageCacheService`, add at the top of the class (after the existing `progressMap`/`roundProgressMap` fields):

```scala
private val registry = new ConcurrentHashMap[String, Unit]()

// Exposed for tests only
private[services] def registrySize: Int = registry.size()
private[services] def registryContains(file: File): Boolean =
  registry.containsKey(file.getAbsolutePath)

/** Scans the local cache directory and loads all files into the registry.
  * Called once at startup in a background Future. Returns a Future so tests can Await it.
  */
private[services] def initRegistry(): Future[Unit] = Future {
  val root = new File(localPath)
  if (root.exists()) {
    Files.walk(root.toPath).iterator().asScala
      .filter(p => Files.isRegularFile(p))
      .foreach(p => registry.put(p.toAbsolutePath.toString, ()))
    logger.info(s"Registry initialized with ${registry.size()} cached files")
  }
}
```

**Step 3: Run the failing tests**

```
sbt "testOnly services.LocalImageCacheServiceSpec -- -ex registry"
```
Expected: compilation failure — `registrySize`, `registryContains`, `initRegistry` do not exist yet.
(Add the accessor stubs with `???` bodies first if you want a runtime failure instead.)

**Step 4: Trigger registry init at construction time**

At the end of the class body (after the `initRegistry()` definition), add:

```scala
initRegistry()
```

This fires the background scan when the service is created. Tests that call `initRegistry()` explicitly get a fresh Future they can `Await`.

**Step 5: Update `saveResized` to use the registry**

Replace the existing `saveResized`:

```scala
private[services] def saveResized(image: Image, sourceImg: BufferedImage, sourcePx: Int): Unit =
  targetHeights.foreach { h =>
    val px = ImageUtil.resizeTo(image.width, image.height, h)
    if (px > 0 && px < image.width && px <= sourcePx) {
      val file = localFile(image, px)
      if (!registry.containsKey(file.getAbsolutePath)) {
        file.getParentFile.mkdirs()
        val resized = scale(sourceImg, px)
        ImageIO.write(resized, "JPEG", file)
        registry.put(file.getAbsolutePath, ())
      }
    }
  }
```

**Step 6: Merge the two `allSizesCached` overloads into one registry-backed version**

Delete the `allSizesCached(image: Image, existing: Set[File])` bulk variant. Replace `allSizesCached(image: Image)` with:

```scala
private[services] def allSizesCached(image: Image): Boolean =
  image.url.exists(_.contains("//upload.wikimedia.org/wikipedia/commons/")) &&
  targetHeights.forall { h =>
    val px = ImageUtil.resizeTo(image.width, image.height, h)
    px >= image.width || registry.containsKey(localFile(image, px).getAbsolutePath)
  }
```

**Step 7: Update `runDownloadImpl` to drop `localFileSet()`**

In `runDownloadImpl`, replace:
```scala
val existing   = localFileSet()
val toDownload = images.filterNot(allSizesCached(_, existing))
```
with:
```scala
val toDownload = images.filterNot(allSizesCached)
```

Delete the `localFileSet()` method entirely (no longer called anywhere).

**Step 8: Run all LocalImageCacheService tests**

```
sbt "testOnly services.LocalImageCacheServiceSpec"
```
Expected: 35 examples, 0 failures. (32 existing + 3 new registry tests.)

**Step 9: Commit**

```bash
git add app/services/LocalImageCacheService.scala test/services/LocalImageCacheServiceSpec.scala
git commit -m "feat: replace localFileSet scan with in-memory registry in LocalImageCacheService"
```

---

### Task 2: Add `fileIfCached` and `fetchAndCacheAll` to `LocalImageCacheService`

**Files:**
- Modify: `app/services/LocalImageCacheService.scala`
- Modify: `test/services/LocalImageCacheServiceSpec.scala`

**Background:**
The proxy controller needs two operations:
1. **`fileIfCached(image, px)`** — O(1) check; returns `Some(file)` if the file is in the registry (controller serves it with `Ok.sendFile`).
2. **`fetchAndCacheAll(image, requestedPx)`** — downloads source from Wikimedia, decodes in memory, resizes the requested size and returns its bytes to the controller *immediately*, then saves all target sizes to disk in a background `Future` (skipping any already in registry via `saveResized`).

**Step 1: Write the failing tests**

Add a new group `"fetchAndCacheAll"` in `LocalImageCacheServiceSpec.scala`:

```scala
"fetchAndCacheAll" should {

  "return JPEG bytes for the requested px" in {
    val tmpDir = Files.createTempDirectory("fetch-test").toFile
    val img    = mkImage()

    withServer { port =>
      // serve a valid JPEG for any URL
      HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, ByteString(jpegBytes(800, 600))))
    } { port =>
      val svc   = mkService(tmpDir, serverPort = Some(port))
      val px    = neededWidths.head
      val bytes = Await.result(svc.fetchAndCacheAll(img, px), 30.seconds)

      bytes must not be empty
      // Should decode as a JPEG
      ImageIO.read(new ByteArrayInputStream(bytes)) must not beNull
    }

    tmpDir.delete()
    ok
  }

  "add all target sizes to the registry after fetching" in {
    val tmpDir = Files.createTempDirectory("fetch-registry").toFile
    val img    = mkImage()

    withServer { _ =>
      HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, ByteString(jpegBytes(800, 600))))
    } { port =>
      val svc = mkService(tmpDir, serverPort = Some(port))
      val px  = neededWidths.head
      Await.result(svc.fetchAndCacheAll(img, px), 30.seconds)

      // Give background save time to complete
      Thread.sleep(500)
      neededWidths.foreach { w =>
        svc.registryContains(svc.localFile(img, w)) must beTrue
      }
    }

    tmpDir.delete()
    ok
  }

  "skip saving sizes already in registry" in {
    val tmpDir = Files.createTempDirectory("fetch-skip").toFile
    val img    = mkImage()
    val svc1   = mkService(tmpDir)

    // Pre-cache all sizes
    val src = ImageIO.read(new ByteArrayInputStream(jpegBytes(800, 600)))
    svc1.saveResized(img, src, 800)

    withServer { _ =>
      HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, ByteString(jpegBytes(800, 600))))
    } { port =>
      val svc2 = mkService(tmpDir, serverPort = Some(port))
      Await.result(svc2.initRegistry(), 5.seconds)

      // Pre-seed the registry as if files already existed
      neededWidths.foreach { w =>
        svc2.saveResized(img, src, 800)  // all already in registry now
      }

      // fetchAndCacheAll should still return bytes for the requested px
      val bytes = Await.result(svc2.fetchAndCacheAll(img, neededWidths.head), 30.seconds)
      bytes must not be empty
    }

    tmpDir.delete()
    ok
  }
}
```

You'll also need to update `mkService` in the spec to accept an optional `serverPort` so `wikiThumbUrl` can be redirected to the test server. In `LocalImageCacheService`, `wikiThumbUrl` always uses `upload.wikimedia.org`. For testing `fetchAndCacheAll`, the easiest approach is to override `downloadWithRetry` in a test subclass or inject the URL — but the simplest approach matching existing test patterns is to add a `baseUrl` config key for tests.

**Simpler test approach:** Instead of rewiring Wikimedia URLs, test `fetchAndCacheAll` via the existing `withServer` pattern. Add a `private[services] val wikiBaseUrl` field to the service (defaulting to `"https://upload.wikimedia.org"`, overrideable via config `wlxjury.thumbs.wiki-base-url` for tests), and use it in `wikiThumbUrl` and `downloadAndResize`.

Update `wikiThumbUrl` in the service to use `wikiBaseUrl`:
```scala
private[services] val wikiBaseUrl =
  config.getOptional[String]("wlxjury.thumbs.wiki-base-url")
    .getOrElse("https://upload.wikimedia.org")

private[services] def wikiThumbUrl(image: Image, px: Int): Option[String] =
  image.url.filter(_.contains("//upload.wikimedia.org/wikipedia/commons/")).map { url =>
    val lower    = image.title.toLowerCase
    val isPdf    = lower.endsWith(".pdf")
    val isTif    = lower.endsWith(".tif") || lower.endsWith(".tiff")
    val lastSlash = url.lastIndexOf("/")
    val utf8Size  = image.title.getBytes("UTF-8").length
    val thumbStr  = if (utf8Size > 165) "thumbnail.jpg" else url.substring(lastSlash + 1)
    url.replace(
      "//upload.wikimedia.org/wikipedia/commons/",
      s"//${wikiBaseUrl.replaceFirst("https?://", "")}/wikipedia/commons/thumb/"
    ) + "/" +
      (if (isPdf) "page1-" else if (isTif) "lossy-page1-" else "") +
      px + "px-" + thumbStr +
      (if (isPdf || isTif) ".jpg" else "")
  }
```

And update `mkService` in the spec to accept a port:
```scala
def mkService(dir: File, maxAttempts: Int = 3, serverPort: Option[Int] = None): LocalImageCacheService = {
  val wikiBase = serverPort.map(p => s"http://localhost:$p").getOrElse("https://upload.wikimedia.org")
  val config = Configuration(
    ConfigFactory.parseString(
      s"""wlxjury.thumbs.local-path = "${dir.getAbsolutePath}"
         |wlxjury.thumbs.parallelism = 2
         |wlxjury.thumbs.rate-per-second = 10
         |wlxjury.thumbs.max-attempts = $maxAttempts
         |wlxjury.thumbs.wiki-base-url = "$wikiBase"
         |""".stripMargin
    )
  )
  new LocalImageCacheService(config, system)
}
```

**Step 2: Run tests to confirm failure**

```
sbt "testOnly services.LocalImageCacheServiceSpec -- -ex fetchAndCacheAll"
```
Expected: compilation failure — `fetchAndCacheAll`, `fileIfCached` not defined yet.

**Step 3: Implement `fileIfCached` and `fetchAndCacheAll`**

Add to `LocalImageCacheService`:

```scala
/** Returns the local file if it is in the registry (O(1) check). */
def fileIfCached(image: Image, px: Int): Option[File] = {
  val file = localFile(image, px)
  if (registry.containsKey(file.getAbsolutePath)) Some(file) else None
}

/** Downloads the source image from Wikimedia, resizes to `requestedPx` and
  * returns those bytes immediately. Saves all target sizes to disk in a
  * background Future (skipping any already in the registry).
  */
def fetchAndCacheAll(image: Image, requestedPx: Int): Future[Array[Byte]] = {
  val neededPx = ImageUtil.resizeTo(image.width, image.height, sourceHeight)
  val sourcePx = wikiThumbnailSteps.find(_ >= neededPx).getOrElse(image.width)
  val urlOpt   = if (sourcePx >= image.width) image.url else wikiThumbUrl(image, sourcePx)

  urlOpt match {
    case None =>
      Future.failed(new Exception(s"No cacheable URL for ${image.title}"))
    case Some(url) =>
      downloadWithRetry(url, attempt = 1).flatMap {
        case None =>
          Future.failed(new Exception(s"Failed to download $url"))
        case Some(bytes) =>
          val sourceImg = ImageIO.read(new ByteArrayInputStream(bytes))
          if (sourceImg == null)
            return Future.failed(new Exception(s"Could not decode image from $url"))

          // Resize the requested size in memory and return to caller immediately
          val requestedImg = scale(sourceImg, requestedPx)
          val baos = new java.io.ByteArrayOutputStream()
          ImageIO.write(requestedImg, "JPEG", baos)
          val resultBytes = baos.toByteArray

          // Save all target sizes in the background (skips sizes already in registry)
          Future(saveResized(image, sourceImg, sourcePx))

          Future.successful(resultBytes)
      }
  }
}
```

**Step 4: Run tests**

```
sbt "testOnly services.LocalImageCacheServiceSpec"
```
Expected: all examples pass (0 failures).

**Step 5: Commit**

```bash
git add app/services/LocalImageCacheService.scala test/services/LocalImageCacheServiceSpec.scala
git commit -m "feat: add fileIfCached and fetchAndCacheAll to LocalImageCacheService"
```

---

### Task 3: Add `ImageJdbc.findByFilename`

**Files:**
- Read: `db/scalikejdbc/ImageJdbc.scala` — check if a method already exists to look up an image by its `title` field (stored as `File:filename.jpg`)
- Modify: `db/scalikejdbc/ImageJdbc.scala` — add `findByFilename` if absent
- Modify: `test/db/scalikejdbc/ImageJdbcSpec.scala` — add test

**Background:**
The proxy controller extracts the filename from the URL path (e.g., `Corallus.jpg` from `.../Corallus.jpg/250px-Corallus.jpg`) and needs to look up the corresponding `Image` record. The `title` column in the images table stores the value as `File:Corallus.jpg`.

**Step 1: Check what already exists**

Read `db/scalikejdbc/ImageJdbc.scala` and search for any `findBy` methods. Run:
```
sbt "testOnly db.scalikejdbc.ImageJdbcSpec"
```
to see existing tests and understand the test DB helpers.

**Step 2: Write the failing test**

In `test/db/scalikejdbc/ImageJdbcSpec.scala`, add:

```scala
"findByFilename" should {
  "return the image whose title matches File:filename" in withDb {
    val img = Image(pageId = 1L, title = "File:Corallus.jpg",
      url = Some("https://upload.wikimedia.org/wikipedia/commons/a/ab/Corallus.jpg"),
      width = 800, height = 600)
    imageDao.batchInsert(Seq(img))

    val found = ImageJdbc.findByFilename("Corallus.jpg")
    found must beSome
    found.get.title mustEqual "File:Corallus.jpg"
  }

  "return None when no image with that filename exists" in withDb {
    ImageJdbc.findByFilename("nosuchfile.jpg") must beNone
  }
}
```

**Step 3: Run to confirm failure**

```
sbt "testOnly db.scalikejdbc.ImageJdbcSpec -- -ex findByFilename"
```
Expected: compilation failure — `findByFilename` not defined.

**Step 4: Implement `findByFilename`**

In `db/scalikejdbc/ImageJdbc.scala`, add to the `ImageJdbc` object:

```scala
def findByFilename(filename: String): Option[Image] =
  findBy(sqls.eq(column.title, s"File:$filename"))
```

(`findBy` and `column` are provided by the `CRUDMapper` trait already used in this file.)

**Step 5: Run tests**

```
sbt "testOnly db.scalikejdbc.ImageJdbcSpec"
```
Expected: all tests pass.

**Step 6: Commit**

```bash
git add db/scalikejdbc/ImageJdbc.scala test/db/scalikejdbc/ImageJdbcSpec.scala
git commit -m "feat: add ImageJdbc.findByFilename"
```

---

### Task 4: `ImageProxyController` and route

**Files:**
- Create: `app/controllers/ImageProxyController.scala`
- Modify: `conf/routes`
- Modify: `test/controllers/ImageProxyControllerSpec.scala` (create if absent)

**Background:**
The controller handles `GET /wikipedia/commons/thumb/*path`. It:
1. Parses the URL path to extract the original filename and requested pixel width.
2. Looks up the image in the DB via `ImageJdbc.findByFilename`.
3. If found + cached: streams the local file with `Ok.sendFile`.
4. If found + not cached: calls `fetchAndCacheAll`, returns bytes as `image/jpeg`.
5. If not found in DB: redirects to Wikimedia (browser follows transparently for `<img>` tags).

**URL path anatomy:**
```
path = "wikipedia/commons/thumb/9/9e/Corallus.jpg/250px-Corallus.jpg"
segments = ["wikipedia","commons","thumb","9","9e","Corallus.jpg","250px-Corallus.jpg"]
filename = segments(segments.length - 2)   →  "Corallus.jpg"   (URL-encoded, decode it)
lastSeg  = segments.last                   →  "250px-Corallus.jpg"
px       = lastSeg.takeWhile(_ != 'p').toInt  (before the first "px")  →  250
```

Note: `path` in Play's `*path` routing does NOT include the leading `/`, and special characters may be percent-encoded.

**Step 1: Write the failing controller test**

Create `test/controllers/ImageProxyControllerSpec.scala`:

```scala
package controllers

import db.scalikejdbc.ImageJdbc
import org.specs2.mutable.Specification
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._
import play.api.test.FakeRequest

class ImageProxyControllerSpec extends Specification {

  sequential

  def buildApp() = new GuiceApplicationBuilder()
    .configure(
      "db.default.driver"   -> "org.h2.Driver",
      "db.default.url"      -> "jdbc:h2:mem:proxy-test;DB_CLOSE_DELAY=-1;MODE=MySQL",
      "wlxjury.thumbs.local-path" -> "/tmp/proxy-spec-cache",
      "wlxjury.thumbs.parallelism" -> "1",
      "wlxjury.thumbs.rate-per-second" -> "10",
      "wlxjury.thumbs.max-attempts" -> "1"
    ).build()

  "ImageProxyController" should {

    "return 404 for a path not matching the thumb pattern" in {
      running(buildApp()) {
        val result = route(buildApp(),
          FakeRequest("GET", "/wikipedia/commons/thumb/bad")).get
        status(result) must beOneOf(NOT_FOUND, BAD_REQUEST)
      }
    }

    "redirect to Wikimedia when image is not in DB" in {
      running(buildApp()) {
        val result = route(buildApp(),
          FakeRequest("GET",
            "/wikipedia/commons/thumb/x/xx/NoSuchFile.jpg/250px-NoSuchFile.jpg")).get
        status(result) mustEqual SEE_OTHER
        header("Location", result) must beSome(contain("upload.wikimedia.org"))
      }
    }
  }
}
```

**Step 2: Run to confirm failure**

```
sbt "testOnly controllers.ImageProxyControllerSpec"
```
Expected: compilation failure — `ImageProxyController` not defined.

**Step 3: Implement `ImageProxyController`**

Create `app/controllers/ImageProxyController.scala`:

```scala
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

  def serve(path: String): Action[AnyContent] = Action.async { _ =>
    val segments = path.split("/").toSeq
    // Expect at least: wikipedia/commons/thumb/X/XX/filename/Npx-filename
    val parsed = for {
      rawFilename <- if (segments.length >= 2) Some(segments(segments.length - 2)) else None
      filename    = URLDecoder.decode(rawFilename, "UTF-8")
      lastSeg    <- segments.lastOption
      px         <- Try(lastSeg.takeWhile(_ != 'p').toInt).toOption
      if px > 0
    } yield (filename, px)

    parsed match {
      case None =>
        Future.successful(NotFound)

      case Some((filename, px)) =>
        ImageJdbc.findByFilename(filename) match {
          case None =>
            // Not in DB: redirect to Wikimedia; browser follows transparently for <img>
            Future.successful(
              Redirect(s"https://upload.wikimedia.org/$path", MOVED_PERMANENTLY)
            )

          case Some(image) =>
            cache.fileIfCached(image, px) match {
              case Some(file) =>
                Future.successful(Ok.sendFile(file, inline = true)
                  .as("image/jpeg")
                  .withHeaders("Cache-Control" -> "public, max-age=2592000"))

              case None =>
                cache.fetchAndCacheAll(image, px)
                  .map { bytes =>
                    Ok(bytes).as("image/jpeg")
                      .withHeaders("Cache-Control" -> "public, max-age=2592000")
                  }
                  .recover { case ex =>
                    logger.warn(s"Failed to fetch $path: ${ex.getMessage}")
                    Redirect(s"https://upload.wikimedia.org/$path")
                  }
            }
        }
    }
  }

  private val logger = play.api.Logger(getClass)
}
```

**Step 4: Add the route**

In `conf/routes`, add this line **before** `GET /assets/*file` (so it matches first):

```
GET         /wikipedia/commons/thumb/*path                                          @controllers.ImageProxyController.serve(path: String)
```

**Step 5: Compile**

```
sbt compile
```
Expected: success (routes regenerated, controller type-checks).

**Step 6: Run controller tests**

```
sbt "testOnly controllers.ImageProxyControllerSpec"
```
Expected: all tests pass.

**Step 7: Commit**

```bash
git add app/controllers/ImageProxyController.scala conf/routes test/controllers/ImageProxyControllerSpec.scala
git commit -m "feat: add ImageProxyController to serve cached images without Apache"
```

---

### Task 5: Update default config and run full test suite

**Files:**
- Modify: `conf/application.conf`
- Run: `sbt test`

**Background:**
`wlxjury.thumbs.host` currently defaults to `upload.wikimedia.org`. For production use without Apache, operators set it to the Play app's own hostname (e.g., `localhost:9000` or the public host). The default stays as `upload.wikimedia.org` so the app works out-of-the-box without local caching configured; the proxy controller is available but only used when the host is pointed at the Play app.

Add a comment to `conf/application.conf` next to the thumbs config explaining this:

```
# Image thumbnail host. Set to this server's host:port to serve cached images
# directly from the Play app (via ImageProxyController) without Apache.
# Default: upload.wikimedia.org (images served directly from Wikimedia).
wlxjury.thumbs.host=upload.wikimedia.org
```

**Step 1: Update the config comment**

Edit line 41 of `conf/application.conf` to add the comment block above (keep the value unchanged).

**Step 2: Run all tests**

```
sbt test
```
Expected: all unit and DB tests pass. Docker spec is skipped (not run without `-Ddocker.tests=true`). JS tests pass.

**Step 3: Commit**

```bash
git add conf/application.conf
git commit -m "docs: document wlxjury.thumbs.host config for Play image proxy"
```

---

## Running the proxy locally

1. Start the app: `sbt run`
2. Set in `conf/application.conf`: `wlxjury.thumbs.host=localhost:9000`
3. Trigger a cache download for a contest or round (admin UI)
4. Image URLs like `http://localhost:9000/wikipedia/commons/thumb/X/XX/file.jpg/250px-file.jpg` will be served from the local cache or fetched on-demand from Wikimedia and cached for next time.
