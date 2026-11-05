package gatling.setup

import db.scalikejdbc.{RoundCounts, RoundWrites, VoteJdbc, WriteTx}
import scalikejdbc._
import services.counts.RoundCountsRecompute

import java.io.{File, PrintWriter}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable.ListBuffer
import scala.util.Random

/** P2a's costs on the Gatling fixture DB (docs/plans/2026-11-02-round-images-two-step.md §16):
  *  1. a juror's vote before (A: the base commit's statements, copied below: the vote's id read
  *     plainly, its row locked by key, `round_user` and `round_criterion` read shared, the rate
  *     written) and after (B: the app's [[VoteJdbc.rate]], which also marks the file dirty),
  *     one committed transaction each, paired and interleaved (order alternating), the rate
  *     always changed, `bench.samples` pairs after `bench.warmup`;
  *  2. the recompute: a full recount of the rated round (every file, windows of
  *     `bench.window`), `bench.recounts` times, and a per-file pass after `bench.marked` votes;
  *  3. B's votes alone against B's votes while full recounts of their round run on another
  *     thread (the worst case for a vote: its file's entry in a window being recounted).
  * Run with the fixture's own recompute off (`-Dgatling.roundCounts.mode=manual`) and SQL logging
  * off, as for any benchmark:
  * {{{
  * sbt 'set Test / javaOptions ++= Seq("-Xmx1024m", "-Dfile.encoding=UTF-8", "-Dgatling.roundCounts.mode=manual",
  *        "-Dscalikejdbc.global.loggingSQLAndTime.enabled=false")' \
  *     'Test/runMain gatling.setup.RoundCountsBenchmark'
  * }}}
  * Results to `bench.out` (target/gatling/round-counts-bench-<stamp>/summary.txt). Not a spec.
  */
object RoundCountsBenchmark {
  private def prop(key: String, default: String) = sys.props.getOrElse(s"bench.$key", default)
  private val samples = prop("samples", "200").toInt
  private val warmup = prop("warmup", "20").toInt
  private val recounts = prop("recounts", "3").toInt
  private val marked = prop("marked", "50").toInt
  private val window = prop("window", RoundCounts.DefaultWindow.toString).toInt
  private val stamp = LocalDateTime.now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
  private val outDir = new File(prop("out", s"target/gatling/round-counts-bench-$stamp"))

  implicit private val session: DBSession = AutoSession

  private val problems = ListBuffer.empty[String]

  def main(args: Array[String]): Unit = {
    val status = try { body(); if (problems.isEmpty) 0 else 2 } catch { case e: Throwable => e.printStackTrace(); 1 }
    sys.exit(status)
  }

  private def ms[T](f: => T): (T, Double) = { val t0 = System.nanoTime(); val r = f; r -> (System.nanoTime() - t0) / 1e6 }
  private def pct(xs: Seq[Double], q: Double): Double =
    if (xs.isEmpty) 0 else xs.sorted.apply(math.min(xs.size - 1, (q * xs.size).toInt))
  private def median(xs: Seq[Double]): Double = {
    val s = xs.sorted
    if (s.isEmpty) 0 else if (s.size % 2 == 1) s(s.size / 2) else (s(s.size / 2 - 1) + s(s.size / 2)) / 2
  }
  private def f2(x: Double) = f"$x%.2f"
  private def spread(xs: Seq[Double]) = s"${f2(median(xs))} (p95 ${f2(pct(xs, 0.95))}, ${f2(xs.min)}-${f2(xs.max)})"

  /** The base commit's (04c1d310) juror vote: [[VoteJdbc.rate]] as it was, without the mark. */
  private def baseVote(pageId: Long, juryId: Long, roundId: Long, rate: Int): Int = WriteTx.write { implicit session =>
    sql"SELECT id FROM vote WHERE page_id = $pageId AND jury_id = $juryId AND round_id = $roundId".map(_.long(1)).single()
      .fold(0) { id =>
        val locked = sql"SELECT id FROM vote WHERE id = $id AND jury_id = $juryId AND round_id = $roundId FOR UPDATE"
          .map(_.long(1)).single().isDefined
        def stopped = sql"""SELECT 1 FROM round_user WHERE round_id = $roundId AND user_id = $juryId AND active = FALSE
                            LOCK IN SHARE MODE""".map(_ => ()).single().isDefined
        def criteria = sql"SELECT 1 FROM round_criterion WHERE round_id = $roundId LIMIT 1 LOCK IN SHARE MODE"
          .map(_ => ()).single().isDefined
        if (!locked || stopped || criteria) 0
        else sql"UPDATE vote SET rate = $rate WHERE id = $id".update()
      }
  }

  private def body(): Unit = {
    require(!GatlingConfig.external, "Use the disposable fixture, never an external target")
    require(sys.props.get("gatling.roundCounts.mode").contains("manual"),
      "run with -Dgatling.roundCounts.mode=manual: the fixture app's own recompute would run beside the measurements")
    val f = GatlingTestFixture
    GlobalSettings.loggingSQLAndTime = LoggingSQLAndTimeSettings(enabled = false)
    outDir.mkdirs()
    val log = new PrintWriter(new File(outDir, "summary.txt"), "UTF-8")
    def say(line: String): Unit = { println(s"[round-counts] $line"); log.println(line); log.flush() }
    try {
      val rated = f.roundRatingId
      val version = sql"SELECT VERSION()".map(_.string(1)).single().get
      val files = sql"SELECT COUNT(*) FROM round_image WHERE round_id = $rated".map(_.long(1)).single().get
      val votes = sql"SELECT COUNT(*) FROM vote WHERE round_id = $rated".map(_.long(1)).single().get
      val recompute = new RoundCountsRecompute(RoundCountsRecompute.Settings(background = false, window = window))
      val start = recompute.untilClean()
      say(s"RoundCountsBenchmark $stamp: MariaDB $version, rated round $rated: $files files, $votes votes; " +
        s"samples $samples, warmup $warmup, window $window; SQL logging off, the app's recompute off; " +
        s"recounted at the start: ${start.roundsRecounted} rounds, ${start.files} files")

      // ── 0. V89 on the fixture: its rollback SQL, then its statements timed, then the backfill
      // (every round dirty: the recompute recounts the whole fixture) ───────
      val rollback = Seq("DROP TABLE round_image_dirty",
        "ALTER TABLE round DROP COLUMN counts_gen, DROP COLUMN counts_gen_done, ALGORITHM = INSTANT",
        "ALTER TABLE round_image DROP COLUMN assigned, DROP COLUMN voted, DROP COLUMN approvals, " +
          "DROP COLUMN rejections, DROP COLUMN rate_sum, ALGORITHM = INSTANT")
      val v89 = new String(java.nio.file.Files.readAllBytes(
        java.nio.file.Paths.get("conf/db/migration/default/V89__Round_image_counts.sql")), "UTF-8")
        .linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n").split(";").map(_.trim).filter(_.nonEmpty).toSeq
      val tBack = rollback.map(st => ms(SQL(st).execute.apply())._2)
      val tForward = v89.map(st => ms(SQL(st).execute.apply())._2)
      val (backfill, tBackfill) = ms(recompute.untilClean())
      val allRows = sql"SELECT COUNT(*) FROM round_image".map(_.long(1)).single().get
      say(s"V89 rollback SQL ${tBack.map(f2).mkString(" / ")} ms; V89 forward ${tForward.map(f2).mkString(" / ")} ms " +
        s"(round_image ALTER, round ALTER, CREATE TABLE); the backfill recount of every round: ${f2(tBackfill)} ms, " +
        s"${backfill.roundsRecounted} rounds, ${backfill.windows} windows, ${backfill.files} of $allRows rows, " +
        s"${backfill.changed} changed")
      Seq(f.roundBinaryId, rated).foreach { r =>
        if (RoundCounts.drift(r).nonEmpty || !RoundCounts.isClean(r)) problems += s"round $r after the backfill: not clean"
      }

      val rnd = new Random(11)
      val pool = sql"SELECT page_id, jury_id, rate FROM vote WHERE round_id = $rated ORDER BY RAND(11) LIMIT ${(samples + warmup) * 5 + marked}"
        .map(rs => (rs.long(1), rs.long(2), rs.int(3))).list().toIndexedSeq
      var next = 0
      def pick(): (Long, Long, Int) = { val v = pool(next % pool.size); next += 1; v }
      // a rate that differs from the vote's current one (1..5 stars), so B always marks
      def changed(rate: Int) = if (rate >= 5) 1 else rate + 1
      def a(): Double = { val (p, j, r) = pick(); ms(baseVote(p, j, rated, changed(r)))._2 }
      def b(): Double = { val (p, j, r) = pick(); ms(VoteJdbc.rate(p, j, rated, changed(r)))._2 }

      // ── 1. the vote, A (base) against B (marked) ────────────────────────
      (1 to warmup).foreach { _ => a(); b() }
      val pairs = (1 to samples).map(k => if (k % 2 == 0) { val ta = a(); val tb = b(); ta -> tb } else { val tb = b(); val ta = a(); ta -> tb })
      val (as, bs) = (pairs.map(_._1), pairs.map(_._2))
      val d = pairs.map { case (x, y) => y - x }
      say(s"vote A (base) ms: ${spread(as)}")
      say(s"vote B (marks its file) ms: ${spread(bs)}")
      say(s"paired B-A ms: median ${f2(median(d))} (p05 ${f2(pct(d, 0.05))}..p95 ${f2(pct(d, 0.95))}), B slower in " +
        s"${d.count(_ > 0)}/$samples")
      val dirtyAfterVotes = RoundCounts.state(rated).map(_.dirtyFiles).getOrElse(0L)

      // ── 2. the recompute ────────────────────────────────────────────────
      val (perFile, tPerFile) = ms(recompute.untilClean())
      say(s"per-file pass after the votes above ($dirtyAfterVotes files marked, A's votes unmarked): " +
        s"${f2(tPerFile)} ms, ${perFile.windows} windows, ${perFile.changed} rows changed")
      val full = (1 to recounts).map { _ =>
        DB.localTx { implicit session => RoundWrites.lock(Seq(rated)); sql"UPDATE round SET counts_gen = counts_gen + 1, created_at = created_at WHERE id = $rated".update() }
        val (sum, t) = ms(recompute.untilClean())
        say(s"full recount: ${f2(t)} ms, ${sum.windows} windows (${f2(t / math.max(1, sum.windows))} ms each), " +
          s"${sum.files} files, ${sum.changed} rows changed")
        t
      }
      if (RoundCounts.drift(rated).nonEmpty) problems += s"drift after the recount: ${RoundCounts.drift(rated).size} rows"
      (1 to marked).foreach(_ => b())
      val (small, tSmall) = ms(recompute.untilClean())
      say(s"per-file pass after $marked votes: ${f2(tSmall)} ms, ${small.windows} windows, ${small.files} files")

      // ── 3. B's votes alone, then while full recounts run ────────────────
      val alone = (1 to samples).map(_ => b())
      val stop = new AtomicBoolean(false)
      var busy = 0
      val recounter = new Thread(() => {
        while (!stop.get()) {
          DB.localTx { implicit session => RoundWrites.lock(Seq(rated)); sql"UPDATE round SET counts_gen = counts_gen + 1, created_at = created_at WHERE id = $rated".update() }
          recompute.untilClean()
          busy += 1
        }
      })
      recounter.setDaemon(true)
      recounter.start()
      val during = (1 to samples).map { _ => Thread.sleep(rnd.nextInt(5).toLong); b() }
      stop.set(true)
      recounter.join(60000)
      recompute.untilClean()
      say(s"vote B alone ms: ${spread(alone)}")
      say(s"vote B during full recounts ($busy recounts) ms: ${spread(during)}; delta of medians " +
        s"${f2(median(during) - median(alone))}, of p95 ${f2(pct(during, 0.95) - pct(alone, 0.95))}")
      if (RoundCounts.drift(rated).nonEmpty || !RoundCounts.isClean(rated))
        problems += s"after the concurrent phase: drift ${RoundCounts.drift(rated).size}, clean ${RoundCounts.isClean(rated)}"
      say(s"full recounts (median of $recounts): ${f2(median(full))} ms")
      if (problems.nonEmpty) say(s"PROBLEMS: ${problems.mkString("; ")}")
    } finally log.close()
  }
}
