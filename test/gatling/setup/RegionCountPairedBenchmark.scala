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
  *    `regionRateCounts` and [[controllers.JurorCounts.regions]] (B, what the menu runs), each
  *    juror in both rounds with each filter.
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
  * Each case walks all its inputs (7 for the whole round, 140 for the jurors: 20 jurors x 7
  * round filters) with a stride coprime to their number, the blocks continuing the walk, so
  * `blocks x pairs` at least the number of inputs times each one. An input's order alternates
  * (AB, BA, ...) at each of its occurrences, and about half of a block's pairs are BA. The
  * fixture votes on every file (no rate 0), so the unrated filter's counts are all 0; every
  * other filter must count some files, or the check fails.
  *
  * {{{
  * sbt 'set Test / javaOptions ++= Seq("-Xmx768m", "-Dfile.encoding=UTF-8",
  *       "-Dscalikejdbc.global.loggingSQLAndTime.enabled=false")' \
  *     'Test/runMain gatling.setup.RegionCountPairedBenchmark'
  * }}}
  * Options, which reach the forked JVM only inside `set Test / javaOptions ++= Seq(...)` as
  * above (build.sbt forwards no `bench.*` from sbt's own `-D`): `-Dbench.blocks` (3),
  * `-Dbench.pairs` (100 a block, juror cases), `-Dbench.roundPairs` (40, round cases),
  * `-Dbench.warmup` (20), `-Dbench.threads` (1,5), `-Dbench.cases` (all four).
  * Writes target/gatling/region-pairs-<timestamp>.csv (every pair, with its input) and .txt
  * (per block, and pooled per case and worker count: medians, min-max, how often B was
  * faster, the AB/BA split and the inputs timed).
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
    def inputs(kind: String): IndexedSeq[Input] =
      if (kind.endsWith("round")) wholeRound.toIndexedSeq
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

    /** Checks the input; true if its counts are all 0 (the unrated filter on this fixture). */
    def verify(kind: String, in: Input): Boolean = {
      val expected = literalCounts(in)
      require(expected.nonEmpty, s"$kind: $in has no regions, so it would time nothing")
      require(in.filter == VoteFilter.Unrated || expected.values.exists(_ > 0),
        s"$kind: $in counts no files, so its counts are checked only as zeros")
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
      expected.values.forall(_ == 0)
    }

    val stamp = LocalDateTime.now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val dir = new File("target/gatling")
    dir.mkdirs()
    val raw = new PrintWriter(new File(dir, s"region-pairs-$stamp.csv"), "UTF-8")
    val summary = new PrintWriter(new File(dir, s"region-pairs-$stamp.txt"), "UTF-8")
    def log(s: String): Unit = { println(s"[pairs] $s"); summary.println(s); summary.flush() }
    raw.println("case,threads,block,pair,input,order,a_ms,b_ms")
    log(s"$stamp blocks=$blocks pairs=$samples roundPairs=$roundSamples warmup=$warmup threads=${threads.mkString(",")} " +
      s"cases=${cases.mkString(",")} files=${f.imagePageIds.size} jurors=${jurors.size} " +
      s"rounds=${rounds.map(_.getId).mkString(",")}; menu pairs include auth, queries and JSON, exclude HTTP filters/network")
    cases.foreach(kind => log(s"$kind: A = ${variants(kind)._1}, B = ${variants(kind)._2}"))

    def elapsed(body: => Unit): Double = { val t = System.nanoTime(); body; (System.nanoTime() - t) / 1e6 }
    /** The nearest-rank percentile (p95). */
    def pct(xs: Seq[Double], p: Double): Double = {
      val sorted = xs.sorted
      sorted(math.max(0, math.ceil(p * sorted.size).toInt - 1))
    }
    def median(xs: Seq[Double]): Double = {
      val s = xs.sorted
      if (s.size % 2 == 1) s(s.size / 2) else (s(s.size / 2 - 1) + s(s.size / 2)) / 2
    }
    def mean(xs: Seq[Double]): Double = xs.sum / xs.size
    def sd(xs: Seq[Double]): Double =
      if (xs.size < 2) 0 else { val m = mean(xs); math.sqrt(xs.map(x => (x - m) * (x - m)).sum / (xs.size - 1)) }

    /** A timed pair: its place in the walk, its input's index, B first, A and B in ms. */
    final case class Pair(g: Int, input: Int, bFirst: Boolean, a: Double, b: Double) {
      def delta: Double = b - a
    }
    def describe(rows: Seq[Pair]): String = {
      val (a, b, d) = (rows.map(_.a), rows.map(_.b), rows.map(_.delta))
      f"A median=${median(a)}%.2f p95=${pct(a, .95)}%.2f mean=${mean(a)}%.2f (${a.min}%.1f-${a.max}%.1f); " +
        f"B median=${median(b)}%.2f p95=${pct(b, .95)}%.2f mean=${mean(b)}%.2f (${b.min}%.1f-${b.max}%.1f); " +
        f"paired B-A median=${median(d)}%.2f mean=${mean(d)}%.2f sd=${sd(d)}%.2f (${d.min}%.1f..${d.max}%.1f) ms; " +
        s"B faster ${d.count(_ < 0)}/${d.size}; AB/BA ${rows.count(!_.bFirst)}/${rows.count(_.bFirst)}"
    }

    try {
      for (kind <- cases) {
        val all = inputs(kind)
        var zeros = 0
        val ms = elapsed(all.foreach(in => if (verify(kind, in)) zeros += 1))
        log(f"$kind%-10s checked ${all.size} inputs in ${ms / 1000}%.1f s: the variants agree with each other and " +
          s"a literal count; $zeros all 0 (unrated: the fixture has no rate 0)")
      }
      for (kind <- cases; workers <- threads) {
        val all = inputs(kind)
        val size = all.size
        // the walk: a stride coprime to the inputs' number visits each once a lap
        val stride = Iterator.from(37).find(s => BigInt(s).gcd(BigInt(size)) == 1).get
        // the g-th pair's input, and whether B runs first: by the position's and the lap's
        // parity, so an input alternates from lap to lap and a block's pairs alternate
        def step(g: Int): (Int, Boolean) = {
          val (position, lap) = (g % size, g / size)
          ((position.toLong * stride % size).toInt, (position + lap) % 2 == 1)
        }
        val sampleCount = if (kind.endsWith("round")) roundSamples else samples
        val pool = Executors.newFixedThreadPool(workers)
        val pooled = Seq.newBuilder[Pair]
        try {
          (0 until warmup).foreach { g => val in = all(step(g)._1); run(kind, false, in); run(kind, true, in) }
          for (block <- 1 to blocks) {
            val next = new java.util.concurrent.atomic.AtomicInteger
            val start = (block - 1) * sampleCount
            val tasks = (0 until workers).map { _ => new Callable[Seq[Pair]] {
              def call(): Seq[Pair] = {
                val rows = Seq.newBuilder[Pair]
                var n = next.getAndIncrement()
                while (n < sampleCount) {
                  val g = start + n
                  val (index, bFirst) = step(g)
                  val in = all(index)
                  val first = elapsed(run(kind, bFirst, in))
                  val second = elapsed(run(kind, !bFirst, in))
                  rows += Pair(g, index, bFirst, if (bFirst) second else first, if (bFirst) first else second)
                  n = next.getAndIncrement()
                }
                rows.result()
              }
            }}
            val rows = pool.invokeAll(tasks.asJava).asScala.toSeq.flatMap(_.get()).sortBy(_.g)
            rows.foreach { p =>
              raw.println(s"$kind,$workers,$block,${p.g},${p.input},${if (p.bFirst) "BA" else "AB"},${p.a},${p.b}")
            }
            raw.flush()
            pooled ++= rows
            log(f"$kind%-10s threads=$workers block=$block n=${rows.size} ${describe(rows)}")
          }
        } finally pool.shutdown()
        val rows = pooled.result()
        val byInput = rows.groupBy(_.input)
        log(f"$kind%-10s threads=$workers pooled n=${rows.size} ${describe(rows)}; inputs timed ${byInput.size}/$size, " +
          s"${byInput.count(_._2.map(_.bFirst).distinct.size == 2)} of them in both orders (stride $stride)")
      }
    } finally { raw.close(); summary.close() }
  }
}
