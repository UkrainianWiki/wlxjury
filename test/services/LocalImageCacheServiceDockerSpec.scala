package services

import com.dimafeng.testcontainers.{DockerComposeContainer, ExposedService}
import db.scalikejdbc.{CategoryLinkJdbc, ContestJuryJdbc, ImageJdbc, SharedTestDb, User}
import org.specs2.mutable.Specification
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.test.CSRFTokenHelper._
import play.api.test.Helpers._
import play.api.test.FakeRequest
import org.testcontainers.containers.wait.strategy.Wait

import java.io.File
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
    exposedServices = Seq(ExposedService("mariadb", 3306, Wait.forHealthcheck()))
  )

  lazy val imagesDir = new File("/tmp/wlxjury-jury-images")

  lazy val mariadbHost: String = compose.getServiceHost("mariadb", 3306)
  lazy val mariadbPort: Int    = compose.getServicePort("mariadb", 3306)

  // ── helpers ─────────────────────────────────────────────────────────────────

  /** Build Play app pointing at compose MariaDB. */
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
    db.TestDocker.start("the image cache database container", compose)
  }

  // ── jury tool end-to-end ──────────────────────────────────────────

  "Jury tool image cache endpoints" should {

    "download all images and cache them at all sizes" in {
      images must not(beEmpty)

      val app = buildApp()
      try running(app) {
        val contestId  = 43L
        val adminEmail = "e2e-admin@test.com"

        // Create admin user
        User.create(User("E2E Admin", adminEmail,
          password = Some(services.PasswordHasher.default.hash("pass")), roles = Set(User.ROOT_ROLE)))(User.session)

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

        // All sizes must be cached on disk, where the app's proxy (ImageProxyController) serves them.
        images.foldLeft(ok: org.specs2.matcher.MatchResult[Any]) { (acc, img) =>
          acc and (svc.allSizesCached(img) must beTrue.updateMessage(
            m => s"${img.title}: $m"))
        }
      } finally {
        // running(app) stops Play which closes ALL ScalikeJDBC pools.
        // Restore the shared pool so subsequent test specs can still connect.
        if (SharedTestDb.initialized) SharedTestDb.reregisterDefault()
      }
    }
  }

  step { compose.stop() }
}
