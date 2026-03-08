package services

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification
import play.api.Configuration

import java.io.File
import java.nio.file.Files
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

class LocalImageCacheServiceIntegrationSpec extends Specification with CommonsImageFetcher {

  sequential
  skipAllUnless(sys.props.get("integration").contains("true"))

  implicit lazy val system: ActorSystem  = ActorSystem("LocalImageCacheIntegrationSpec")
  implicit lazy val ec: ExecutionContext = system.dispatcher

  // ── helpers ────────────────────────────────────────────────────────────────

  lazy val tempDir: File = {
    val d = Files.createTempDirectory("wlxjury-integration").toFile
    d.deleteOnExit()
    d
  }

  lazy val svc: LocalImageCacheService = {
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

  // ── tests ──────────────────────────────────────────────────────────────────

  "LocalImageCacheService integration" should {

    "download and resize all images from Category:Images from Wiki Loves Earth 2025 in Bolivia" in {
      val category = "Category:Images from Wiki Loves Earth 2025 in Bolivia"
      val images   = fetchImagesFromCommons(category, limit = 5)

      images must not(beEmpty)

      val startMs = System.currentTimeMillis()
      Await.result(svc.runDownload(contestId = 1L, images), 60.seconds)
      val elapsed = System.currentTimeMillis() - startMs

      val p = svc.progress(1L)
      println(f"Integration test: ${p.done}/${images.size} images processed in ${elapsed / 1000.0}%.1fs (${p.ratePerSec}%.1f img/s)")

      p.done mustEqual images.size

      images.foldLeft(ok: MatchResult[Any]) { (acc, img) =>
        acc and (svc.allSizesCached(img) must beTrue.updateMessage(m => s"${img.title}: $m"))
      }
    }
  }

  step {
    Await.result(Http().shutdownAllConnectionPools(), 10.seconds)
    Await.result(system.terminate(), 30.seconds)
    ()
  }
}
