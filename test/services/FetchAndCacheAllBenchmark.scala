package services

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ActorSystem
import org.intracer.wmua.ImageUtil
import org.openjdk.jmh.annotations._
import play.api.Configuration

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import scala.concurrent.ExecutionContext.Implicits.global

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
class FetchAndCacheAllBenchmark {

  /** Each param value is one of the target heights from LocalImageCacheService#targetHeights. */
  @Param(Array("120", "180", "240", "250", "375", "500", "1100", "1650"))
  var targetHeightStr: String = _

  var system: ActorSystem = _
  var svc: LocalImageCacheService = _
  var sourceImg: BufferedImage = _
  var requestedPx: Int = _
  var cascadeWidths: Seq[Int] = _  // target widths from largest down to requestedPx (for cascade later)

  val imageW = 4000
  val imageH = 3000

  @Setup(Level.Trial)
  def setup(): Unit = {
    system = ActorSystem("FetchAndCacheAllBench")
    val dir = Files.createTempDirectory("bench-fetch").toFile
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
    sourceImg = new BufferedImage(2200, 1650, BufferedImage.TYPE_INT_RGB)
    requestedPx = ImageUtil.resizeTo(imageW, imageH, targetHeightStr.toInt)
    // cascadeWidths: all target widths from the largest down to and including requestedPx,
    // sorted descending. This mirrors the cascade path in production saveResized when
    // the cascade reaches the requested size. takeWhile(_ >= requestedPx) keeps widths
    // >= requestedPx (i.e. from the largest target down to requestedPx inclusive), so
    // cascadeScale(sourceImg, cascadeWidths) starts from sourceImg and cascades through
    // the same intermediate steps as it would in production.
    cascadeWidths = Seq(120, 180, 240, 250, 375, 500, 1100, 1650)
      .map(h => ImageUtil.resizeTo(imageW, imageH, h))
      .filter(px => px > 0 && px < imageW)
      .distinct
      .sorted(Ordering[Int].reverse)    // largest first
      .takeWhile(_ >= requestedPx)      // from largest down to requestedPx inclusive
  }

  @TearDown(Level.Trial)
  def teardown(): Unit = {
    import scala.concurrent.Await
    import scala.concurrent.duration._
    Await.result(system.terminate(), 30.seconds)
  }

  private def encodeJpeg(img: BufferedImage): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    ImageIO.write(img, "JPEG", baos)
    baos.toByteArray
  }

  /** Current approach: scale sourceImg directly to requestedPx. */
  @Benchmark
  def fromLargest(): Array[Byte] =
    encodeJpeg(svc.scale(sourceImg, requestedPx))

  /** Cascade approach: cascade from largest to requestedPx, then JPEG encode. */
  @Benchmark
  def cascaded(): Array[Byte] = {
    val results = svc.cascadeScale(sourceImg, cascadeWidths)
    val img = results.find(_._1 == requestedPx).map(_._2).getOrElse(sourceImg)
    encodeJpeg(img)
  }
}
