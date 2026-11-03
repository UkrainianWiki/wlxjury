# Docker Image Cache Tests Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** End-to-end Docker tests verifying Apache serves cached/proxied images and the jury tool's
HTTP cache endpoints trigger correct downloads.

**Architecture:** `docker-compose.test.yml` defines Apache + MariaDB; tests use
`DockerComposeContainer` to start them; Play runs in-process with `GuiceApplicationBuilder` pointing
at the containerized services; FakeRequests call the real cache HTTP endpoints; Apache URL
verification uses `java.net.HttpURLConnection` against the Apache container.

**Tech Stack:** Scala 2.13, specs2, testcontainers-scala 0.41.0, Pekko HTTP, Play Framework 3,
Apache httpd:2.4, MariaDB 10.6

---

### Task 1: Extract `CommonsImageFetcher` shared trait

**Files:**
- Create: `test/services/CommonsImageFetcher.scala`
- Modify: `test/services/LocalImageCacheServiceIntegrationSpec.scala`

**Step 1: Create the trait**

`fetchImagesFromCommons` currently lives inline in `LocalImageCacheServiceIntegrationSpec`. Move it
to a shared trait so the Docker spec can reuse it without duplication.

Create `test/services/CommonsImageFetcher.scala`:

```scala
package services

import org.intracer.wmua.Image
import org.scalawiki.MwBot
import org.scalawiki.dto.Namespace

import scala.concurrent.duration._
import scala.concurrent.Await

trait CommonsImageFetcher {

  private val imageInfoProps = Set("size", "url", "mime")

  /** Fetch up to `limit` eligible images from a Commons category via scalawiki. */
  def fetchImagesFromCommons(category: String, limit: Int): Seq[Image] = {
    val bot   = MwBot.fromHost("commons.wikimedia.org")
    val pages = Await.result(
      bot
        .page(category)
        .imageInfoByGenerator(
          "categorymembers",
          "cm",
          namespaces  = Set(Namespace.FILE),
          props       = imageInfoProps,
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
}
```

**Step 2: Update `LocalImageCacheServiceIntegrationSpec` to use the trait**

In `test/services/LocalImageCacheServiceIntegrationSpec.scala`:

- Add `with CommonsImageFetcher` to the class declaration
- Remove the entire `private val imageInfoProps` field and `def fetchImagesFromCommons` method
  (they now come from the trait)

```scala
class LocalImageCacheServiceIntegrationSpec extends Specification with CommonsImageFetcher {
```

**Step 3: Compile**

```
sbt compile
```
Expected: success.

**Step 4: Verify integration spec still skips correctly (no network)**

```
sbt "testOnly services.LocalImageCacheServiceIntegrationSpec"
```
Expected: 1 example skipped, fast, no network calls.

**Step 5: Commit**

```bash
git add test/services/CommonsImageFetcher.scala \
        test/services/LocalImageCacheServiceIntegrationSpec.scala
git commit -m "refactor: extract CommonsImageFetcher trait for reuse across test specs"
```

---

### Task 2: Add `Json.reads` to `CacheProgress`

**Files:**
- Modify: `app/services/LocalImageCacheService.scala:27-29`

The Docker test parses the JSON returned by `GET /contest/:id/images/cache/status` to poll
progress. `CacheProgress` currently only has `Json.writes`. Add `Json.reads` so the test can
deserialize the response.

**Step 1: Add reads to the companion object**

Replace the companion object (lines 27–29):

```scala
object CacheProgress {
  implicit val writes: Writes[CacheProgress] = Json.writes[CacheProgress]
  implicit val reads: play.api.libs.json.Reads[CacheProgress] =
    play.api.libs.json.Json.reads[CacheProgress]
}
```

**Step 2: Compile and run unit tests**

```
sbt "testOnly services.LocalImageCacheServiceSpec"
```
Expected: 30 examples, 0 failures.

**Step 3: Commit**

```bash
git add app/services/LocalImageCacheService.scala
git commit -m "feat: add Json.reads to CacheProgress for test deserialization"
```

---

### Task 3: Create Apache Dockerfile and docker-compose.test.yml

**Files:**
- Create: `conf/apache/Dockerfile`
- Create: `docker-compose.test.yml`

**Step 1: Create `conf/apache/Dockerfile`**

The `httpd:2.4` image has `mod_proxy`, `mod_proxy_http`, `mod_rewrite`, and `mod_headers`
compiled in but commented out in `httpd.conf`. Uncomment them and include `jury-images.conf`.

```dockerfile
FROM httpd:2.4

RUN sed -i \
    -e 's/#LoadModule proxy_module/LoadModule proxy_module/' \
    -e 's/#LoadModule proxy_http_module/LoadModule proxy_http_module/' \
    -e 's/#LoadModule rewrite_module/LoadModule rewrite_module/' \
    -e 's/#LoadModule headers_module/LoadModule headers_module/' \
    /usr/local/apache2/conf/httpd.conf \
 && echo 'Include /usr/local/apache2/conf/extra/jury-images.conf' \
    >> /usr/local/apache2/conf/httpd.conf \
 && echo 'ServerName localhost' >> /usr/local/apache2/conf/httpd.conf
```

**Step 2: Create `docker-compose.test.yml`**

The shared bind-mount path `/tmp/wlxjury-jury-images` is used by both Apache (to serve files) and
the test JVM (via `LocalImageCacheService`, which writes downloaded images there).

```yaml
services:
  apache:
    build:
      context: .
      dockerfile: conf/apache/Dockerfile
    volumes:
      - ./conf/apache/jury-images.conf:/usr/local/apache2/conf/extra/jury-images.conf:ro
      - /tmp/wlxjury-jury-images:/var/www/jury-images
    ports:
      - "80"

  mariadb:
    image: mariadb:10.6
    environment:
      MARIADB_DATABASE: wlxjury_test
      MARIADB_USER: wlxjury_user
      MARIADB_PASSWORD: wlxjury_password
      MARIADB_RANDOM_ROOT_PASSWORD: "yes"
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      timeout: 20s
      retries: 10
```

**Step 3: Verify the compose file builds**

```bash
docker compose -f docker-compose.test.yml build
```
Expected: Apache image builds with no errors.

**Step 4: Verify the stack starts and Apache responds**

```bash
docker compose -f docker-compose.test.yml up -d
# Wait a few seconds for Apache to start
curl -v http://localhost/wikipedia/commons/thumb/a/ab/Test.jpg/120px-Test.jpg
# Expected: 200 or redirect from wikimedia (proxy is working)
docker compose -f docker-compose.test.yml down
```

**Step 5: Commit**

```bash
git add conf/apache/Dockerfile docker-compose.test.yml
git commit -m "feat: add Apache Dockerfile and docker-compose.test.yml for image cache tests"
```

---

### Task 4: Write `LocalImageCacheServiceDockerSpec`

**Files:**
- Create: `test/services/LocalImageCacheServiceDockerSpec.scala`

**Key design decisions:**
- Gated by `-Ddocker.tests=true` — skipped in normal CI
- `DockerComposeContainer` manages Apache + MariaDB lifecycle
- Play app runs in-process via `GuiceApplicationBuilder` (same pattern as `TestDb.testDbApp`)
- `FakeRequest` + `route()` calls the real cache HTTP endpoints through Play's router
- Auth uses a root user inserted into DB + Play session with `"username"` key
- Apache URL verification uses `java.net.HttpURLConnection`
- Images are linked to a contest via `CategoryJdbc.addToCategory` so `startDownload` finds them

**Step 1: Write the spec**

Create `test/services/LocalImageCacheServiceDockerSpec.scala`:

```scala
package services

import com.dimafeng.testcontainers.{DockerComposeContainer, ExposedService}
import db.scalikejdbc.{CategoryJdbc, ContestJuryJdbc, ImageJdbc, User}
import org.specs2.mutable.Specification
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.test.CSRFTokenHelper._
import play.api.test.Helpers._
import play.api.test.FakeRequest
import org.testcontainers.containers.wait.strategy.Wait

import java.io.File
import java.net.HttpURLConnection
import scala.annotation.tailrec

class LocalImageCacheServiceDockerSpec extends Specification with CommonsImageFetcher {

  sequential
  skipAllUnless(sys.props.get("docker.tests").contains("true"))

  // ── shared images (fetched once) ────────────────────────────────────────────

  lazy val images = fetchImagesFromCommons(
    "Category:Images from Wiki Loves Earth 2021 in Armenia",
    limit = 50
  )

  // ── Docker Compose ──────────────────────────────────────────────────────────

  lazy val compose: DockerComposeContainer = DockerComposeContainer(
    composeFiles = Seq(new File("docker-compose.test.yml")),
    exposedServices = Seq(
      ExposedService("apache",  80,   Wait.forListeningPort()),
      ExposedService("mariadb", 3306, Wait.forHealthcheck())
    )
  )

  // Ensure shared directory exists before containers start
  lazy val imagesDir = new File("/tmp/wlxjury-jury-images")

  lazy val apacheHost: String = { compose.getServiceHost("apache", 80) }
  lazy val apachePort: Int    = { compose.getServicePort("apache", 80) }
  lazy val mariadbHost: String = compose.getServiceHost("mariadb", 3306)
  lazy val mariadbPort: Int    = compose.getServicePort("mariadb", 3306)

  // ── helpers ─────────────────────────────────────────────────────────────────

  /** Simple blocking HTTP GET; returns (statusCode, contentType). */
  def httpGet(url: String): (Int, Option[String]) = {
    val conn = new java.net.URL(url).openConnection().asInstanceOf[HttpURLConnection]
    conn.setConnectTimeout(10_000)
    conn.setReadTimeout(30_000)
    try (conn.getResponseCode, Option(conn.getContentType))
    finally conn.disconnect()
  }

  /** Build an Apache URL from a Wikimedia Commons thumb URL. */
  def toApacheUrl(wikiUrl: String): String =
    wikiUrl.replaceFirst("https?://upload\\.wikimedia\\.org",
                         s"http://$apacheHost:$apachePort")

  /** Build Play app pointing at compose MariaDB and Apache. */
  def buildApp(extraConfig: Map[String, String] = Map.empty) =
    new GuiceApplicationBuilder()
      .configure(Map(
        "db.default.driver"           -> "org.mariadb.jdbc.Driver",
        "db.default.url"              ->
          s"jdbc:mariadb://$mariadbHost:$mariadbPort/wlxjury_test" +
          "?autoReconnect=true&useUnicode=true&characterEncoding=UTF-8" +
          "&useSSL=false&allowPublicKeyRetrieval=true",
        "db.default.username"         -> "wlxjury_user",
        "db.default.password"         -> "wlxjury_password",
        "wlxjury.thumbs.host"         -> s"$apacheHost:$apachePort",
        "wlxjury.thumbs.local-path"   -> imagesDir.getAbsolutePath,
        "wlxjury.thumbs.parallelism"  -> "4",
        "wlxjury.thumbs.rate-per-second" -> "5",
        "wlxjury.thumbs.max-attempts" -> "5"
      ) ++ extraConfig)
      .build()

  /** Poll GET /contest/:id/images/cache/status until running == false.
    * Returns the final CacheProgress.
    */
  def pollCacheStatus(contestId: Long, app: play.api.Application,
                      adminEmail: String, timeoutMs: Long = 90_000L): CacheProgress = {
    val deadline = System.currentTimeMillis() + timeoutMs
    @tailrec def loop(): CacheProgress = {
      if (System.currentTimeMillis() > deadline)
        throw new RuntimeException(s"Timed out waiting for cache download (${timeoutMs}ms)")
      val result = route(app,
        FakeRequest("GET", s"/contest/$contestId/images/cache/status")
          .withSession("username" -> adminEmail)
      ).get
      val p = contentAsJson(result).as[CacheProgress]
      if (p.running) { Thread.sleep(2_000); loop() }
      else p
    }
    loop()
  }

  // ── startup / shutdown ───────────────────────────────────────────────────────

  step {
    imagesDir.mkdirs()
    compose.start()
  }

  step { compose.stop() }

  // ── test (a): Apache serving ────────────────────────────────────────────────

  "Apache" should {

    "proxy an uncached file from Wikimedia Commons" in {
      images must not(beEmpty)
      val image = images.head
      // Use the 250px thumbnail height as the representative size
      val px = org.intracer.wmua.ImageUtil.resizeTo(image.width, image.height, 250)
      val wikiUrl = // construct directly — same logic as LocalImageCacheService.wikiThumbUrl
        image.url.filter(_.contains("//upload.wikimedia.org/wikipedia/commons/")).map { url =>
          val lastSlash = url.lastIndexOf("/")
          val thumbStr  = url.substring(lastSlash + 1)
          url.replace(
            "//upload.wikimedia.org/wikipedia/commons/",
            "//upload.wikimedia.org/wikipedia/commons/thumb/"
          ) + s"/${px}px-$thumbStr"
        }
      wikiUrl must beSome
      val apacheUrl = toApacheUrl(wikiUrl.get)
      val (status, _) = httpGet(apacheUrl)
      status mustEqual 200
    }

    "serve cached files locally with Cache-Control header" in {
      // This test runs after test (b) has populated the cache
      val app = buildApp()
      running(app) {
        val contestId = 42L
        // Create contest + insert images (same as test (b) setup, idempotent via unique contestId)
        val adminEmail = "apache-test@test.com"
        User.create(User("Apache Admin", adminEmail,
          password = Some(User.sha1("pass")), roles = Set(User.ROOT_ROLE)))

        val contest = ContestJuryJdbc.create(Some(contestId), "WLE Apache", 2021, "Armenia")
        ContestJuryJdbc.setImagesSource(contestId, Some("Category:Images from Wiki Loves Earth 2021 in Armenia"))
        val updatedContest = ContestJuryJdbc.findById(contestId).get
        val categoryId = updatedContest.categoryId.get
        ImageJdbc.batchInsert(images)
        CategoryJdbc.addToCategory(categoryId, images)

        // Trigger download via HTTP endpoint
        val startResult = route(app,
          CSRFTokenHelper.addCSRFToken(
            FakeRequest("POST", s"/contest/$contestId/images/cache/start")
              .withSession("username" -> adminEmail)
          )
        ).get
        status(startResult) must beOneOf(SEE_OTHER, OK)

        // Wait for completion
        val progress = pollCacheStatus(contestId, app, adminEmail)
        progress.done mustEqual images.size

        // Pick one cached file and verify Apache serves it with Cache-Control header
        val image = images.head
        val svc = app.injector.instanceOf[LocalImageCacheService]
        val px = org.intracer.wmua.ImageUtil.resizeTo(image.width, image.height, 250)
        val wikiUrl = svc.wikiThumbUrl(image, px)
        wikiUrl must beSome
        val apacheUrl = toApacheUrl(wikiUrl.get)

        val conn = new java.net.URL(apacheUrl).openConnection().asInstanceOf[HttpURLConnection]
        conn.setConnectTimeout(10_000)
        conn.setReadTimeout(30_000)
        try {
          conn.getResponseCode mustEqual 200
          Option(conn.getContentType) must beSome(contain("image/jpeg"))
          Option(conn.getHeaderField("Cache-Control")) must beSome(contain("max-age=2592000"))
        } finally conn.disconnect()
      }
    }

    "return non-200 for a completely bogus path" in {
      val (status, _) = httpGet(
        s"http://$apacheHost:$apachePort/wikipedia/commons/thumb/x/xx/nosuchfile.jpg/999px-nosuchfile.jpg"
      )
      status must be_>=(400)
    }
  }

  // ── test (b): jury tool end-to-end ──────────────────────────────────────────

  "Jury tool image cache endpoints" should {

    "download all images and serve them at all sizes via Apache" in {
      images must not(beEmpty)

      val app = buildApp()
      running(app) {
        val contestId  = 43L
        val adminEmail = "e2e-admin@test.com"

        // Create admin user
        User.create(User("E2E Admin", adminEmail,
          password = Some(User.sha1("pass")), roles = Set(User.ROOT_ROLE)))

        // Create contest + link images so startDownload finds them via findByContestId
        val contest = ContestJuryJdbc.create(Some(contestId), "WLE E2E", 2021, "Armenia")
        ContestJuryJdbc.setImagesSource(contestId,
          Some("Category:Images from Wiki Loves Earth 2021 in Armenia"))
        val updatedContest = ContestJuryJdbc.findById(contestId).get
        val categoryId = updatedContest.categoryId.get
        ImageJdbc.batchInsert(images)
        CategoryJdbc.addToCategory(categoryId, images)

        // POST to start endpoint — triggers LocalImageCacheService.startDownload via controller
        val startResult = route(app,
          CSRFTokenHelper.addCSRFToken(
            FakeRequest("POST", s"/contest/$contestId/images/cache/start")
              .withSession("username" -> adminEmail)
          )
        ).get
        status(startResult) must beOneOf(SEE_OTHER, OK)

        // Poll GET status endpoint until running == false (max 90 seconds)
        val startMs  = System.currentTimeMillis()
        val progress = pollCacheStatus(contestId, app, adminEmail)
        val elapsed  = System.currentTimeMillis() - startMs
        println(f"Docker test: ${progress.done}/${images.size} images in ${elapsed / 1000.0}%.1fs " +
                f"(${progress.ratePerSec}%.1f img/s)")

        // 100% of images must succeed
        progress.done mustEqual images.size

        // Get the Guice-managed service to check cached files and construct URLs
        val svc = app.injector.instanceOf[LocalImageCacheService]

        // All sizes must be cached on disk
        images.foldLeft(ok: org.specs2.matcher.MatchResult[Any]) { (acc, img) =>
          acc and (svc.allSizesCached(img) must beTrue.updateMessage(
            m => s"${img.title}: $m"))
        }

        // Spot-check three representative sizes via Apache for all images
        val checkHeights = Seq(120, 250, 1100)
        images.foldLeft(ok: org.specs2.matcher.MatchResult[Any]) { (acc, img) =>
          checkHeights.foldLeft(acc) { (acc2, h) =>
            val px = org.intracer.wmua.ImageUtil.resizeTo(img.width, img.height, h)
            if (px < img.width) {
              svc.wikiThumbUrl(img, px) match {
                case None => acc2
                case Some(wikiUrl) =>
                  val apacheUrl = toApacheUrl(wikiUrl)
                  val (st, ct) = httpGet(apacheUrl)
                  acc2 and
                    (st mustEqual 200).updateMessage(m => s"${img.title} ${px}px status: $m") and
                    (ct must beSome(contain("image")).updateMessage(
                      m => s"${img.title} ${px}px content-type: $m"))
              }
            } else acc2
          }
        }
      }
    }
  }
}
```

**Step 2: Compile**

```
sbt compile
```
Expected: success.

**Step 3: Verify spec is skipped without property**

```
sbt "testOnly services.LocalImageCacheServiceDockerSpec"
```
Expected: examples skipped, fast, no network or Docker activity.

**Step 4: Commit**

```bash
git add test/services/LocalImageCacheServiceDockerSpec.scala
git commit -m "test: docker-based end-to-end tests for LocalImageCacheService with Apache"
```

---

### Task 5: Verify full test suite compiles and non-docker tests pass

**Step 1: Run full test suite without docker flag**

```
sbt test
```
Expected: all unit and DB tests pass; integration and docker specs show 0 examples (skipped).
The pre-existing `controllers.AppendImagesSpec` error (unrelated to our work) may still appear.

**Step 2: Commit any fixes needed; otherwise done.**

---

## Running the Docker tests

```bash
sbt -Ddocker.tests=true "testOnly services.LocalImageCacheServiceDockerSpec"
```

## Running the compose stack manually

```bash
docker compose -f docker-compose.test.yml up --build
```
Then configure the jury tool with `wlxjury.thumbs.host=localhost:80` and
`wlxjury.thumbs.local-path=/tmp/wlxjury-jury-images` to use the stack.
