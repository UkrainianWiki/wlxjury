package gatling.setup

import controllers.{GalleryController, Secured}
import db.scalikejdbc.rewrite.ImageDbNew.SelectionQuery
import modules.BlockingExecutionContext
import play.api.Configuration
import play.api.mvc.ControllerComponents
import play.api.test.{FakeRequest, Helpers}
import play.api.test.Helpers.{defaultAwaitTimeout => _, _}
import services.GalleryService

import java.io.{File, PrintWriter}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.{Callable, Executors}
import scala.jdk.CollectionConverters._
import scala.concurrent.duration._

/** Alternating A/B pairs on identical inputs, in repeated blocks on one warmed fixture.
  * Includes authentication, pager, gallery queries and HTML rendering, but excludes the
  * socket, browser assets and thumbnails. No HTTP filters are run by Helpers.call.
  * Writes every measurement; timings are evidence, never a CI assertion.
  */
object RegionCountPairedBenchmark {
  private def prop(key: String, default: String) = sys.props.getOrElse(s"bench.$key", default)
  private val blocks = prop("blocks", "3").toInt
  private val samples = prop("pairs", "100").toInt
  private val warmup = prop("warmup", "20").toInt
  private val threads = prop("threads", "1,5").split(",").map(_.toInt).toSeq
  private val cases = prop("cases", "sql-juror,page-juror,sql-round,sql-round-rated,page-round,page-round-rated")
    .split(",").toSeq

  def main(args: Array[String]): Unit = {
    val status = try { runBenchmark(); 0 } catch {
      // This CLI owns a fixture with a running Play server: terminate the fork on failure
      // too, so the fixture's shutdown hook runs instead of leaving a failed run hanging.
      case error: Throwable => error.printStackTrace(); 1
    }
    sys.exit(status)
  }

  private def runBenchmark(): Unit = {
    require(!GatlingConfig.external, "Use the disposable fixture, never an external target")
    val f = GatlingTestFixture
    val app = f.application
    implicit val mat: org.apache.pekko.stream.Materializer = app.materializer
    implicit val timeout: org.apache.pekko.util.Timeout =
      org.apache.pekko.util.Timeout(prop("requestTimeoutSeconds", "120").toInt.seconds)
    val injector = app.injector
    def controller(mode: String) = new GalleryController(injector.instanceOf[GalleryService],
      injector.instanceOf[ControllerComponents], injector.instanceOf[BlockingExecutionContext],
      Configuration("gallery.regionCounts" -> mode))
    val off = controller("off")
    val on = controller("all")
    val stamp = LocalDateTime.now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val dir = new File("target/gatling")
    dir.mkdirs()
    val raw = new PrintWriter(new File(dir, s"region-pairs-$stamp.csv"), "UTF-8")
    val summary = new PrintWriter(new File(dir, s"region-pairs-$stamp.txt"), "UTF-8")
    def log(s: String): Unit = { println(s"[pairs] $s"); summary.println(s); summary.flush() }
    raw.println("case,threads,block,pair,order,off_ms,on_ms")
    log(s"$stamp blocks=$blocks pairs=$samples warmup=$warmup threads=${threads.mkString(",")} " +
      s"files=${f.imagePageIds.size} jurors=${f.jurors.size}; controller includes DB and rendered HTML, excludes network/assets")

    // All rate filters and jurors occur in each block, with identical inputs in each pair.
    def query(n: Int, whole: Boolean, positive: Boolean): SelectionQuery = {
      val filters: Seq[(Long, Option[Int], Option[Boolean])] = Seq(
        (f.roundBinaryId, None, None), (f.roundBinaryId, Some(1), None),
        (f.roundBinaryId, Some(-1), None), (f.roundBinaryId, Some(0), None),
        (f.roundRatingId, None, None), (f.roundRatingId, None, Some(true)),
        (f.roundRatingId, None, Some(false)))
      val (round, rate, rated) = if (whole)
        (if (n % 2 == 0) f.roundBinaryId else f.roundRatingId, Option.empty[Int], Option.when(positive)(true))
      else filters((n + n / f.jurors.size) % filters.size)
      SelectionQuery(userId = Option.unless(whole)(f.jurors(n % f.jurors.size)._1),
        roundId = Some(round), rate = rate, rated = rated)
    }
    def run(kind: String, enabled: Boolean, n: Int): Unit = {
      val whole = kind.contains("round")
      val q = query(n, whole, kind.endsWith("rated"))
      if (kind.startsWith("sql")) {
        if (enabled) q.regionCounts(): Unit else q.byRegionStat(): Unit
      } else {
        val email = if (whole) f.organizer._2 else f.jurors(n % f.jurors.size)._2
        val action = (if (enabled) on else off).query(
          if (whole || q.roundId.contains(f.roundRatingId)) "byrate" else "gallery", q.userId, page = n % 3 + 1,
          roundId = q.roundId.get)
        // the filter as a list URL's query has it (RoundList)
        val filter = q.rate.map(r => s"rate=$r").orElse(q.rated.map(r => s"rated=$r")).fold("")("?" + _)
        val result = Helpers.call(action, FakeRequest("GET", "/query" + filter).withSession(
          Secured.UserName -> email, Secured.CurrentContestId -> f.contestId.toString))
        require(Helpers.status(result) == 200, s"$kind did not return a gallery")
        val html = Helpers.contentAsString(result)
        require(html.contains("id=\"regionnav\""), s"$kind has no region navigation")
        require(html.contains("Volyn O. (") == enabled, s"$kind did not apply region counts=$enabled")
      }
    }
    def elapsed(body: => Unit): Double = { val t = System.nanoTime(); body; (System.nanoTime() - t) / 1e6 }
    def pct(xs: Seq[Double], p: Double): Double = {
      val sorted = xs.sorted
      sorted(math.max(0, math.ceil(p * sorted.size).toInt - 1))
    }
    try for (kind <- cases; workers <- threads) {
      val sampleCount = if (kind.contains("round")) prop("roundPairs", "40").toInt else samples
      val pool = Executors.newFixedThreadPool(workers)
      try {
        (0 until warmup).foreach(n => { run(kind, false, n); run(kind, true, n) })
        for (block <- 1 to blocks) {
          val next = new java.util.concurrent.atomic.AtomicInteger
          val tasks = (0 until workers).map { _ => new Callable[Seq[(Int, Double, Double)]] {
            def call(): Seq[(Int, Double, Double)] = {
              val rows = Seq.newBuilder[(Int, Double, Double)]
              var n = next.getAndIncrement()
              while (n < sampleCount) {
                val onFirst = (n + block) % 2 == 0
                val first = elapsed(run(kind, onFirst, n))
                val second = elapsed(run(kind, !onFirst, n))
                rows += ((n, if (onFirst) second else first, if (onFirst) first else second))
                n = next.getAndIncrement()
              }
              rows.result()
            }
          }}
          val rows = pool.invokeAll(tasks.asJava).asScala.toSeq.flatMap(_.get()).sortBy(_._1)
          rows.foreach { case (n, a, b) => raw.println(s"$kind,$workers,$block,$n,${if ((n + block) % 2 == 0) "BA" else "AB"},$a,$b") }
          raw.flush()
          val a = rows.map(_._2); val b = rows.map(_._3); val delta = rows.map(r => r._3 - r._2)
          log(f"$kind%-16s threads=$workers block=$block n=${rows.size} " +
            f"off p50=${pct(a, .5)}%.2f p95=${pct(a, .95)}%.2f mean=${a.sum/a.size}%.2f; " +
            f"on p50=${pct(b, .5)}%.2f p95=${pct(b, .95)}%.2f mean=${b.sum/b.size}%.2f; " +
            f"paired delta mean=${delta.sum/delta.size}%.2f median=${pct(delta, .5)}%.2f ms")
        }
      } finally pool.shutdown()
    } finally { raw.close(); summary.close() }
  }
}
