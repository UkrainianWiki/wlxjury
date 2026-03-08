package services

import com.dimafeng.testcontainers.{DockerComposeContainer, ExposedService}
import db.scalikejdbc.{CategoryLinkJdbc, ContestJuryJdbc, ImageJdbc, User}
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
import scala.concurrent.ExecutionContext

class LocalImageCacheServiceDockerSpec extends Specification with CommonsImageFetcher {

  implicit val ec: ExecutionContext = ExecutionContext.global

  sequential
  skipAllUnless(sys.props.get("docker.tests").contains("true"))

  // ── shared images (fetched once) ────────────────────────────────────────────

  lazy val images = fetchImagesFromCommons(
    "Category:Images from Wiki Loves Earth 2025 in Bolivia",
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

  lazy val imagesDir = new File("/tmp/wlxjury-jury-images")

  lazy val apacheHost: String = compose.getServiceHost("apache", 80)
  lazy val apachePort: Int    = compose.getServicePort("apache", 80)
  lazy val mariadbHost: String = compose.getServiceHost("mariadb", 3306)
  lazy val mariadbPort: Int    = compose.getServicePort("mariadb", 3306)

  // ── helpers ─────────────────────────────────────────────────────────────────

  /** Simple blocking HTTP GET; returns (statusCode, contentType, X-Jury-Cache header value). */
  def httpGet(url: String): (Int, Option[String], Option[String]) = {
    val conn = new java.net.URL(url).openConnection().asInstanceOf[HttpURLConnection]
    conn.setConnectTimeout(10_000)
    conn.setReadTimeout(30_000)
    try (conn.getResponseCode,
         Option(conn.getContentType),
         Option(conn.getHeaderField("X-Jury-Cache")))
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
        "commons.host"                   -> "commons.wikimedia.org",
        "db.default.driver"              -> "org.mariadb.jdbc.Driver",
        "db.default.url"                 -> (
          s"jdbc:mariadb://$mariadbHost:$mariadbPort/wlxjury_test" +
          "?autoReconnect=true&useUnicode=true&characterEncoding=UTF-8" +
          "&useSSL=false&allowPublicKeyRetrieval=true"),
        "db.default.username"            -> "wlxjury_user",
        "db.default.password"            -> "wlxjury_password",
        "wlxjury.thumbs.local-path"      -> imagesDir.getAbsolutePath,
        "wlxjury.thumbs.parallelism"     -> "1",
        "wlxjury.thumbs.rate-per-second" -> "5",
        "wlxjury.thumbs.max-attempts"    -> "3"
      ) ++ extraConfig)
      .build()

  /** Poll GET /contest/:id/images/cache/status until running == false. */
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
    // Clean any cached files from a previous run so allSizesCached doesn't skip downloads
    if (imagesDir.exists()) {
      java.nio.file.Files.walk(imagesDir.toPath)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach { p => if (p.toFile != imagesDir) java.nio.file.Files.delete(p) }
    } else {
      imagesDir.mkdirs()
    }
    compose.start()
  }

  // ── test (a): Apache serving ────────────────────────────────────────────────

  "Apache" should {

    "proxy an uncached file from Wikimedia Commons" in {
      images must not(beEmpty)
      val image = images.head
      val px = org.intracer.wmua.ImageUtil.resizeTo(image.width, image.height, 250)
      // Build a thumb URL using the same pattern as LocalImageCacheService.wikiThumbUrl
      val wikiUrlOpt = image.url.filter(_.contains("//upload.wikimedia.org/wikipedia/commons/")).map { url =>
        val base     = url.replace(
          "//upload.wikimedia.org/wikipedia/commons/",
          "//upload.wikimedia.org/wikipedia/commons/thumb/"
        )
        val thumbFile = url.substring(url.lastIndexOf("/") + 1)
        s"$base/${px}px-$thumbFile"
      }
      wikiUrlOpt must beSome
      val apacheUrl = toApacheUrl(wikiUrlOpt.get)
      val (st, _, xCache) = httpGet(apacheUrl)
      (st mustEqual 200) and (xCache must beSome("proxied"))
    }

    "return non-200 for a completely bogus path" in {
      val (st, _, _) = httpGet(
        s"http://$apacheHost:$apachePort/wikipedia/commons/thumb/x/xx/nosuchfile.jpg/999px-nosuchfile.jpg"
      )
      st must be_>=(400)
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

        // Create contest + link images so startDownload finds them
        ContestJuryJdbc.create(Some(contestId), "WLE E2E", 2025, "Bolivia")
        ContestJuryJdbc.setImagesSource(contestId,
          Some("Category:Images from Wiki Loves Earth 2025 in Bolivia"))
        val updatedContest = ContestJuryJdbc.findById(contestId).get
        val categoryId = updatedContest.categoryId.get
        ImageJdbc.batchInsert(images)
        CategoryLinkJdbc.addToCategory(categoryId, images)

        // POST to start endpoint
        val startResult = route(app,
          addCSRFToken(
            FakeRequest("POST", s"/contest/$contestId/images/cache/start")
              .withSession("username" -> adminEmail)
          )
        ).get
        status(startResult) must beOneOf(SEE_OTHER, OK)

        // Poll GET status endpoint until done
        val startMs  = System.currentTimeMillis()
        val progress = pollCacheStatus(contestId, app, adminEmail)
        val elapsed  = System.currentTimeMillis() - startMs
        println(f"Docker test: ${progress.done}/${images.size} images in ${elapsed / 1000.0}%.1fs " +
                f"(${progress.ratePerSec}%.1f img/s)")

        progress.done mustEqual images.size

        val svc = app.injector.instanceOf[LocalImageCacheService]

        // All sizes must be cached on disk
        images.foldLeft(ok: org.specs2.matcher.MatchResult[Any]) { (acc, img) =>
          acc and (svc.allSizesCached(img) must beTrue.updateMessage(
            m => s"${img.title}: $m"))
        } and {
          // Spot-check a sample of images: assert Apache serves them from local disk.
          // X-Cache: local  → file found in DocumentRoot, served without proxying  ✓
          // X-Cache: proxied → file missing from container, Apache fetched from Wikimedia ✗
          // Using X-Cache avoids false passes (local file ≈ proxied file both return 200)
          // and avoids 429s from Wikimedia caused by making many rapid proxy requests.
          val checkHeights = Seq(120, 250, 1100)
          images.take(3).foldLeft(ok: org.specs2.matcher.MatchResult[Any]) { (acc, img) =>
            checkHeights.foldLeft(acc) { (acc2, h) =>
              val px = org.intracer.wmua.ImageUtil.resizeTo(img.width, img.height, h)
              if (px < img.width) {
                svc.wikiThumbUrl(img, px) match {
                  case None => acc2
                  case Some(wikiUrl) =>
                    val apacheUrl = toApacheUrl(wikiUrl)
                    val (st, _, xCache) = httpGet(apacheUrl)
                    acc2 and
                      (st mustEqual 200).updateMessage(m => s"${img.title} ${px}px status: $m") and
                      (xCache must beSome("local")).updateMessage(
                        m => s"${img.title} ${px}px not served from local cache (X-Cache=$xCache): $m")
                }
              } else acc2
            }
          }
        }
      }
    }
  }

  step { compose.stop() }
}
