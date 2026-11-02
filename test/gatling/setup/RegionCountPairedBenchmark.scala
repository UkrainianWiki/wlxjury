package gatling.setup

import controllers.{GalleryController, GalleryView, RoundList, Secured, VoteFilter}
import db.scalikejdbc.Round
import db.scalikejdbc.rewrite.ImageDbNew.regionOf
import modules.BlockingExecutionContext
import play.api.Configuration
import play.api.libs.json.JsObject
import play.api.mvc.ControllerComponents
import play.api.test.{FakeRequest, Helpers}
import play.api.test.Helpers.writeableOf_AnyContentAsEmpty
import scalikejdbc.{AutoSession, DBSession, SQL}
import services.GalleryService

import java.io.{File, PrintWriter}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.{Callable, Executors}
import scala.jdk.CollectionConverters._
import scala.concurrent.duration._

/** The region menu's counts, A against B: alternating AB/BA pairs on identical inputs, in
  * repeated blocks on one warmed fixture (docs/plans/2026-11-01-open-followups.md, batch B10;
  * the method of docs/plans/2026-10-25-region-improvements.md).
  *
  *  - `sql-round`: the whole round's regions, `byRegionStat` (A, `gallery.regionCounts=off`)
  *    against `regionCounts` (B, `all`), both rounds, each of their tabs' filters.
  *  - `sql-juror`: a juror's counts, the one filter's `regionCounts` (A) against
  *    `regionRateCounts` and [[controllers.JurorCounts.regions]] (B, what the menu runs), every
  *    juror, both rounds, each filter.
  *  - `menu-round`: the organisers' region menu (`GalleryController.regions`, user 0, by rate)
  *    from a controller with counts off (A) and one with `all` (B).
  *  - `menu-juror`: a juror's own region menu, off (A) and `all` (B). Both run the same query
  *    (only the numbers in the JSON differ): the control, whose paired delta should be ~0.
  *
  * The menu pairs include authentication, the gallery's permission checks, the queries and the
  * JSON, but not the socket, the client or HTTP filters (Helpers.call runs none). Before
  * timing, every input of the chosen cases is checked once (the variants' regions and counts
  * agree, and the counts match an independent query): the run fails rather than time a wrong
  * answer, an error page or an unintended setting. Each timed menu call must return 200.
  * Writes every pair; timings are evidence, never a CI assertion.
  *
  * {{{
  * sbt 'set Test / javaOptions ++= Seq("-Xmx768m", "-Dfile.encoding=UTF-8",
  *       "-Dscalikejdbc.global.loggingSQLAndTime.enabled=false")' \
  *     'Test/runMain gatling.setup.RegionCountPairedBenchmark'
  * }}}
  * `-Dbench.blocks` (3), `-Dbench.pairs` (100, juror cases), `-Dbench.roundPairs` (40, round
  * cases), `-Dbench.warmup` (20), `-Dbench.threads` (1,5), `-Dbench.cases` (all four).
  * Writes target/gatling/region-pairs-<timestamp>.csv (every pair) and .txt (per block).
  */
object RegionCountPairedBenchmark {
  private def prop(key: String, default: String) = sys.props.getOrElse(s"bench.$key", default)
  private val blocks = prop("blocks", "3").toInt
  private val samples = prop("pairs", "100").toInt
  private val roundSamples = prop("roundPairs", "40").toInt
  private val warmup = prop("warmup", "20").toInt
  private val threads = prop("threads", "1,5").split(",").map(_.trim.toInt).toSeq
  private val allCases = Seq("sql-round", "sql-juror", "menu-round", "menu-juror")
  private val cases = prop("cases", allCases.mkString(",")).split(",").map(_.trim).toSeq

  /** What each case's A and B are, for the log. */
  private val variants = Map(
    "sql-round" -> ("byRegionStat", "regionCounts"),
    "sql-juror" -> ("regionCounts", "regionRateCounts"),
    "menu-round" -> ("counts off", "counts all"),
    "menu-juror" -> ("counts off", "counts all")
  )

  /** A region menu's list: the round, the filter, and a juror's own votes or (None) all the
    * jurors' together by rate, seen by an organiser.
    */
  private final case class Input(round: Round, filter: VoteFilter, juror: Option[(Long, String)]) {
    def list: RoundList =
      RoundList(round, juror.fold(0L)(_._1), filter = filter,
        view = if (juror.isEmpty) GalleryView.ByRate else GalleryView.Gallery)
    override def toString: String =
      s"round ${round.getId} filter ${filter.param} ${juror.fold("all jurors")(j => s"juror ${j._1}")}"
  }

  /** A region of the menu's JSON. */
  private final case class Place(id: String, count: Option[Int], url: String)

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
    require(cases.forall(allCases.contains), s"bench.cases: one or more of ${allCases.mkString(",")}")
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

    val rounds = Seq(f.roundBinaryId, f.roundRatingId).map(id =>
      Round.findById(id).getOrElse(sys.error(s"the fixture has no round $id")))
    // every round with each of its tabs' filters: 4 (yes/no) + 3 (rated)
    val roundFilters = for (round <- rounds; filter <- VoteFilter.ofRound(round)) yield (round, filter)
    val jurors = f.jurors.map(j => (j._1, j._2)).toIndexedSeq
    val wholeRound = roundFilters.map { case (round, filter) => Input(round, filter, None) }
    // all jurors and all filters occur in a block of 100: juror n, the filter shifted each lap
    def input(kind: String, n: Int): Input =
      if (kind.endsWith("round")) wholeRound(n % wholeRound.size)
      else {
        val (round, filter) = roundFilters((n + n / jurors.size) % roundFilters.size)
        Input(round, filter, Some(jurors(n % jurors.size)))
      }
    def inputs(kind: String): Seq[Input] =
      if (kind.endsWith("round")) wholeRound
      else for (j <- jurors; (round, filter) <- roundFilters) yield Input(round, filter, Some(j))

    /** The region menu's JSON for the input from the controller, at the URL its page has. */
    def menu(controller: GalleryController, in: Input): Seq[Place] = {
      val list = in.list
      val email = in.juror.fold(f.organizer._2)(_._2)
      val result = Helpers.call(controller.regions(list.asUserId, "all", list.roundId),
        FakeRequest("GET", list.regionsUrl)
          .withHeaders("X-Requested-With" -> "XMLHttpRequest")
          .withSession(Secured.UserName -> email, Secured.CurrentContestId -> f.contestId.toString))
      val code = Helpers.status(result)
      require(code == 200, s"the region menu of $in returned $code: ${Helpers.contentAsString(result).take(200)}")
      (Helpers.contentAsJson(result) \ "regions").as[Seq[JsObject]].map(r =>
        Place((r \ "id").as[String], (r \ "count").asOpt[Int], (r \ "url").as[String]))
    }

    def run(kind: String, b: Boolean, in: Input): Unit = kind match {
      case "sql-round" =>
        val q = in.list.query.copy(regions = Set.empty)
        if (b) q.regionCounts(): Unit else q.byRegionStat(): Unit
      case "sql-juror" =>
        val list = in.list
        if (b) list.jurorCounts().get.regions(list.filter): Unit
        else list.query.copy(regions = Set.empty).regionCounts(): Unit
      case _ => menu(if (b) on else off, in): Unit
    }

    /** The input's counts per region, independently of the application's queries: the
      * round's files (each once) with a vote the filter takes, or the juror's; every region of
      * the round's (the juror's) files, 0 where none, without files that have no monument id.
      */
    def literalCounts(in: Input): Map[String, Int] = {
      val hit = in.filter.rate.map(r => s"s.rate = $r")
        .orElse(in.filter.rated.map(r => if (r) "s.rate > 0" else "s.rate = 0"))
        .getOrElse("TRUE")
      val juror = in.juror.fold("")(j => s"AND s.jury_id = ${j._1}")
      val region = regionOf("s.monument_id")
      implicit val session: DBSession = AutoSession
      SQL(s"""SELECT $region, COUNT(DISTINCT IF($hit, s.page_id, NULL)) FROM selection s
             |WHERE s.round_id = ${in.round.getId} $juror AND $region IS NOT NULL GROUP BY 1""".stripMargin)
        .map(rs => rs.string(1) -> rs.int(2)).list().toMap
    }

    def verify(kind: String, in: Input): Unit = {
      val expected = literalCounts(in)
      require(expected.nonEmpty, s"$kind: $in has no regions, so it would time nothing")
      def same[A](what: String, a: A, b: A): Unit = require(a == b, s"$kind: $in: $what differ:\n  $a\n  $b")
      kind match {
        case "sql-round" =>
          val q = in.list.query.copy(regions = Set.empty)
          val counts = q.regionCounts()
          same("byRegionStat's and regionCounts' regions", q.byRegionStat().keySet, counts.keySet)
          same("regionCounts and the literal counts", counts, expected)
        case "sql-juror" =>
          val list = in.list
          val jurorCounts = list.jurorCounts().get.regions(list.filter)
          same("regionCounts and the juror's counts", list.query.copy(regions = Set.empty).regionCounts(), jurorCounts)
          same("the juror's counts and the literal counts", jurorCounts, expected)
        case _ =>
          val (a, b) = (menu(off, in), menu(on, in))
          same("the menus' regions and links", a.map(p => (p.id, p.url)), b.map(p => (p.id, p.url)))
          require(a.forall(_.count.isEmpty), s"$kind: $in: counts with gallery.regionCounts=off")
          same("the menu's counts and the literal counts", b.map(p => p.id -> p.count).toMap,
            expected.map { case (id, n) => id -> Option(n) })
      }
    }

    val stamp = LocalDateTime.now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val dir = new File("target/gatling")
    dir.mkdirs()
    val raw = new PrintWriter(new File(dir, s"region-pairs-$stamp.csv"), "UTF-8")
    val summary = new PrintWriter(new File(dir, s"region-pairs-$stamp.txt"), "UTF-8")
    def log(s: String): Unit = { println(s"[pairs] $s"); summary.println(s); summary.flush() }
    raw.println("case,threads,block,pair,order,a_ms,b_ms")
    log(s"$stamp blocks=$blocks pairs=$samples roundPairs=$roundSamples warmup=$warmup threads=${threads.mkString(",")} " +
      s"cases=${cases.mkString(",")} files=${f.imagePageIds.size} jurors=${jurors.size} " +
      s"rounds=${rounds.map(_.getId).mkString(",")}; menu pairs include auth, queries and JSON, exclude HTTP filters/network")
    cases.foreach(kind => log(s"$kind: A = ${variants(kind)._1}, B = ${variants(kind)._2}"))

    def elapsed(body: => Unit): Double = { val t = System.nanoTime(); body; (System.nanoTime() - t) / 1e6 }
    def pct(xs: Seq[Double], p: Double): Double = {
      val sorted = xs.sorted
      sorted(math.max(0, math.ceil(p * sorted.size).toInt - 1))
    }
    try {
      for (kind <- cases) {
        val all = inputs(kind)
        val ms = elapsed(all.foreach(verify(kind, _)))
        log(f"$kind%-10s checked ${all.size} inputs in ${ms / 1000}%.1f s: the variants agree with each other and a literal count")
      }
      for (kind <- cases; workers <- threads) {
        val sampleCount = if (kind.endsWith("round")) roundSamples else samples
        val pool = Executors.newFixedThreadPool(workers)
        try {
          (0 until warmup).foreach { n => run(kind, false, input(kind, n)); run(kind, true, input(kind, n)) }
          for (block <- 1 to blocks) {
            val next = new java.util.concurrent.atomic.AtomicInteger
            val tasks = (0 until workers).map { _ => new Callable[Seq[(Int, Double, Double)]] {
              def call(): Seq[(Int, Double, Double)] = {
                val rows = Seq.newBuilder[(Int, Double, Double)]
                var n = next.getAndIncrement()
                while (n < sampleCount) {
                  val in = input(kind, n)
                  val bFirst = (n + block) % 2 == 0
                  val first = elapsed(run(kind, bFirst, in))
                  val second = elapsed(run(kind, !bFirst, in))
                  rows += ((n, if (bFirst) second else first, if (bFirst) first else second))
                  n = next.getAndIncrement()
                }
                rows.result()
              }
            }}
            val rows = pool.invokeAll(tasks.asJava).asScala.toSeq.flatMap(_.get()).sortBy(_._1)
            rows.foreach { case (n, a, b) =>
              raw.println(s"$kind,$workers,$block,$n,${if ((n + block) % 2 == 0) "BA" else "AB"},$a,$b")
            }
            raw.flush()
            val a = rows.map(_._2); val b = rows.map(_._3); val delta = rows.map(r => r._3 - r._2)
            log(f"$kind%-10s threads=$workers block=$block n=${rows.size} " +
              f"A p50=${pct(a, .5)}%.2f p95=${pct(a, .95)}%.2f mean=${a.sum / a.size}%.2f; " +
              f"B p50=${pct(b, .5)}%.2f p95=${pct(b, .95)}%.2f mean=${b.sum / b.size}%.2f; " +
              f"paired B-A mean=${delta.sum / delta.size}%.2f median=${pct(delta, .5)}%.2f ms")
          }
        } finally pool.shutdown()
      }
    } finally { raw.close(); summary.close() }
  }
}
