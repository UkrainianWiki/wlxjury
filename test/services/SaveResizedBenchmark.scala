package services

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.intracer.wmua.ImageUtil
import org.openjdk.jmh.annotations._
import play.api.Configuration

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext.Implicits.global

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
class SaveResizedBenchmark {

  var system: ActorSystem = _
  var svc: LocalImageCacheService = _
  var sourceImg: BufferedImage = _
  var targetWidths: Seq[Int] = _

  val imageW = 4000
  val imageH = 3000
  // Heights mirroring LocalImageCacheService#targetHeights
  val targetHeights: Seq[Int] = Seq(120, 180, 240, 250, 375, 500, 1100, 1650)

  @Setup(Level.Trial)
  def setup(): Unit = {
    system = ActorSystem("SaveResizedBench")
    val dir = Files.createTempDirectory("bench-saveresized").toFile
    val config = Configuration(ConfigFactory.parseString(
      s"""wlxjury.thumbs.local-path = "${dir.getAbsolutePath}"
         |wlxjury.thumbs.parallelism = 1
         |wlxjury.thumbs.rate-per-second = 100
         |wlxjury.thumbs.max-attempts = 1
         |""".stripMargin
    ))
    // LocalImageCacheService takes an implicit ExecutionContext as its second parameter list.
    // `scala.concurrent.ExecutionContext.Implicits.global` (imported above) satisfies it.
    svc = new LocalImageCacheService(config, system)
    // Simulated downloaded source: 2200x1650 (typical largest Wikimedia thumbnail)
    sourceImg = new BufferedImage(2200, 1650, BufferedImage.TYPE_INT_RGB)
    targetWidths = targetHeights
      .map(h => ImageUtil.resizeTo(imageW, imageH, h))
      .filter(px => px > 0 && px < imageW)
      .distinct
      .sorted(Ordering[Int].reverse)   // largest first (needed for cascade later)
  }

  @TearDown(Level.Trial)
  def teardown(): Unit = {
    import scala.concurrent.Await
    import scala.concurrent.duration._
    Await.result(system.terminate(), 30.seconds)
  }

  /** Current approach: each target scaled independently from sourceImg. */
  @Benchmark
  def fromLargest(): Seq[BufferedImage] =
    targetWidths.map(px => svc.scale(sourceImg, px))

  /** Cascade approach: each target scaled from the previous larger output. */
  @Benchmark
  def cascaded(): Seq[(Int, BufferedImage)] =
    svc.cascadeScale(sourceImg, targetWidths)
}
