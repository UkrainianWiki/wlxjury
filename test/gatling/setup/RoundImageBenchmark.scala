package gatling.setup

import db.scalikejdbc.RoundImageJdbc
import db.scalikejdbc.rewrite.ImageDbNew.SelectionQuery
import scalikejdbc._
import services.distribution.DistributionSource
import services.distribution.RoundDistributionHandler.PrevVotes

import java.io.{File, PrintWriter}
import java.nio.file.{Files, Paths}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.collection.mutable.ListBuffer
import scala.util.Random

/** round_image (V84, docs/plans/2026-11-02-round-images-two-step.md, P1) on the Gatling
  * fixture DB:
  *  1. V84's backfill: round_image emptied, then its two INSERT ... SELECT statements timed
  *     (`bench.backfillRuns` times), the rows checked against the GROUP BY over the votes;
  *  2. the "which images" reads P1 moved, the replaced vote query (A) against the round_image
  *     one (B), paired and interleaved (order alternating), `bench.samples` pairs after
  *     `bench.warmup`, each pair's results compared;
  *  3. a distribution chunk's write: `bench.chunks` chunks of 750 files x 20 jurors (15000
  *     vote rows, the default chunk), the votes' batch insert and round_image's insert
  *     (RoundImageJdbc.addVoted) timed apart in one transaction per chunk, rolled back.
  *
  * {{{
  * sbt 'set Test / javaOptions ++= Seq("-Xmx1024m", "-Dfile.encoding=UTF-8",
  *        "-Dscalikejdbc.global.loggingSQLAndTime.enabled=false")' \
  *     'Test/runMain gatling.setup.RoundImageBenchmark'
  * }}}
  * Results to `bench.out` (target/gatling/round-image-bench-<stamp>/summary.txt). Not a spec.
  */
object RoundImageBenchmark {
  private def prop(key: String, default: String) = sys.props.getOrElse(s"bench.$key", default)
  private val samples = prop("samples", "20").toInt
  private val warmup = prop("warmup", "3").toInt
  private val backfillRuns = prop("backfillRuns", "3").toInt
  private val chunks = prop("chunks", "5").toInt
  private val stamp = LocalDateTime.now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
  private val outDir = new File(prop("out", s"target/gatling/round-image-bench-$stamp"))

  implicit private val session: DBSession = AutoSession

  private val problems = ListBuffer.empty[String]

  def main(args: Array[String]): Unit = {
    val status = try { body(); if (problems.isEmpty) 0 else 2 } catch { case e: Throwable => e.printStackTrace(); 1 }
    sys.exit(status)
  }

  private def ms[T](f: => T): (T, Double) = {
    val t0 = System.nanoTime()
    val r = f
    r -> (System.nanoTime() - t0) / 1e6
  }

  private def median(xs: Seq[Double]): Double = {
    val s = xs.sorted
    if (s.isEmpty) 0 else if (s.size % 2 == 1) s(s.size / 2) else (s(s.size / 2 - 1) + s(s.size / 2)) / 2
  }

  private def f1(x: Double) = f"$x%.1f"

  private val v84: Seq[String] = {
    val text = new String(Files.readAllBytes(Paths.get("conf/db/migration/default/V84__Round_image.sql")), "UTF-8")
    text.linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n").split(";\\s*\n").map(_.trim).filter(_.nonEmpty).toSeq
  }

  private def body(): Unit = {
    require(!GatlingConfig.external, "Use the disposable fixture, never an external target")
    val f = GatlingTestFixture
    GlobalSettings.loggingSQLAndTime = LoggingSQLAndTimeSettings(enabled = false)
    outDir.mkdirs()
    val log = new PrintWriter(new File(outDir, "summary.txt"), "UTF-8")
    def say(line: String): Unit = { println(s"[round_image] $line"); log.println(line); log.flush() }
    try {
      val rounds = Seq(f.roundBinaryId, f.roundRatingId)
      val version = sql"SELECT VERSION()".map(_.string(1)).single().get
      val votes = sql"SELECT COUNT(*) FROM vote".map(_.long(1)).single().get
      val pairs = sql"SELECT COUNT(*) FROM (SELECT DISTINCT round_id, page_id FROM vote) p".map(_.long(1)).single().get
      val sizes = sql"""SELECT TABLE_NAME, ROUND((DATA_LENGTH + INDEX_LENGTH) / 1048576) FROM information_schema.TABLES
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('vote', 'round_image')"""
        .map(rs => s"${rs.string(1)} ${rs.long(2)} MB").list()
      say(s"RoundImageBenchmark $stamp: MariaDB $version, rounds ${rounds.mkString(", ")}, vote $votes rows, " +
        s"$pairs (round, page) pairs, ${sizes.mkString(", ")}; samples $samples, warmup $warmup; SQL logging off")

      // ── 1. the backfill ─────────────────────────────────────────────────
      val expected = sql"""SELECT round_id, page_id FROM (SELECT DISTINCT round_id, page_id FROM vote) p
                           ORDER BY round_id, page_id""".map(rs => rs.long(1) -> rs.long(2)).list()
      (1 to backfillRuns).foreach { run =>
        sql"DELETE FROM round_image".update()
        val times = v84.map(st => ms(SQL(st).execute.apply())._2)
        val rows = sql"SELECT round_id, page_id FROM round_image ORDER BY round_id, page_id"
          .map(rs => rs.long(1) -> rs.long(2)).list()
        if (rows != expected) problems += s"backfill run $run: ${rows.size} rows, ${expected.size} expected"
        say(s"backfill run $run: create ${f1(times.head)} ms, from vote ${f1(times(1))} ms, from vote_aside " +
          s"${f1(times(2))} ms; ${rows.size} rows (expected ${expected.size})")
      }
      sql"ANALYZE TABLE round_image".map(_ => ()).list()

      // ── 2. the reads, A (vote) against B (round_image) ──────────────────
      val random = new Random(7)
      val allPages = sql"SELECT page_id FROM images ORDER BY page_id".map(_.long(1)).list().toIndexedSeq
      val prev = Seq(f.roundRatingId)
      def walk(window: (Long, Int) => (Int, Long)): (Int, Int) = {
        var cursor = 0L; var n = 0; var windows = 0; var size = 1
        while (size > 0) { val (s, last) = window(cursor, 750); size = s; cursor = last; n += s; if (s > 0) windows += 1 }
        n -> windows
      }
      val cases: Seq[(String, () => Any, () => Any)] = Seq(
        ("imageCountByRounds", () =>
          sql"""SELECT s.round_id, COUNT(DISTINCT s.page_id) FROM vote s WHERE s.round_id IN ($rounds)
                GROUP BY s.round_id""".map(rs => rs.long(1) -> rs.int(2)).list().toMap,
          () => RoundImageJdbc.countsIn(rounds)),
        ("count check (rated)", () =>
          sql"SELECT COUNT(DISTINCT page_id) FROM vote WHERE round_id = ${f.roundRatingId}".map(_.long(1)).single().get +
            sql"""SELECT COUNT(DISTINCT d.page_id) FROM vote_aside d WHERE d.round_id = ${f.roundRatingId}
                  AND NOT EXISTS (SELECT 1 FROM vote s WHERE s.round_id = ${f.roundRatingId} AND s.page_id = d.page_id)"""
              .map(_.long(1)).single().get,
          () => RoundImageJdbc.countAll(f.roundRatingId)),
        ("byRegionStat whole round (rated)", () =>
          sql"""SELECT DISTINCT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, '-', 1), 3), '') r FROM vote s
                WHERE s.round_id = ${f.roundRatingId} HAVING r IS NOT NULL""".map(_.string(1)).list().toSet,
          () => SelectionQuery(roundId = Some(f.roundRatingId)).byRegionStat().keySet),
        ("byRegionStat whole round, sub-regions (rated)", () =>
          sql"""SELECT DISTINCT m.adm1 FROM vote s JOIN monument m ON m.id = s.monument_id
                WHERE m.adm1 IS NOT NULL AND s.round_id = ${f.roundRatingId}""".map(_.string(1)).list().toSet,
          () => SelectionQuery(roundId = Some(f.roundRatingId), subRegions = true).byRegionStat().keySet),
        ("previous-round windows, a full walk (rated)", () =>
          walk((c, n) => sql"""SELECT COUNT(*), MAX(page_id) FROM (SELECT DISTINCT s.page_id FROM vote s
                                WHERE s.round_id IN ($prev) AND s.page_id > $c ORDER BY s.page_id LIMIT $n) w"""
            .map(rs => rs.int(1) -> rs.longOpt(2).getOrElse(c)).single().get),
          () => walk((c, n) => { val w = DistributionSource.previousWindow(prev, PrevVotes(), c, n, Nil); w.size -> w.last })),
        ("open rounds of 200 files (DeletedFiles)", () => {
          val ps = Seq.fill(200)(allPages(random.nextInt(allPages.size))).distinct
          sql"SELECT DISTINCT round_id FROM vote WHERE page_id IN ($ps)".map(_.long(1)).list().toSet
        }, () => {
          val ps = Seq.fill(200)(allPages(random.nextInt(allPages.size))).distinct
          sql"""SELECT DISTINCT round_id FROM round_image WHERE page_id IN ($ps) AND status = 'in'"""
            .map(_.long(1)).list().toSet
        })
      )
      say("case | n | A (vote) median ms (min-max) | B (round_image) median ms (min-max) | paired B-A median ms (min..max) | B faster")
      cases.foreach { case (label, a, b) =>
        (1 to warmup).foreach { _ => a(); b() }
        val ra = a(); val rb = b()
        if (ra != rb) problems += s"$label: A $ra, B $rb"
        val timed = (1 to samples).map { k =>
          if (k % 2 == 0) { val (_, ta) = ms(a()); val (_, tb) = ms(b()); ta -> tb }
          else { val (_, tb) = ms(b()); val (_, ta) = ms(a()); ta -> tb }
        }
        val (as, bs) = (timed.map(_._1), timed.map(_._2))
        val d = timed.map { case (x, y) => y - x }
        say(s"$label | $samples | ${f1(median(as))} (${f1(as.min)}-${f1(as.max)}) | ${f1(median(bs))} " +
          s"(${f1(bs.min)}-${f1(bs.max)}) | ${f1(median(d))} (${f1(d.min)}..${f1(d.max)}) | ${d.count(_ < 0)}/$samples")
      }

      // ── 3. a distribution chunk's write ─────────────────────────────────
      val jurors = f.jurors.map(_._1).take(20)
      val contestId = f.contestId
      val chunkTimes = (1 to chunks).map { k =>
        DB.localTx { implicit session =>
          val roundId = sql"INSERT INTO round (number, contest_id, rates, active) VALUES (${100 + k}, $contestId, 1, 0)"
            .updateAndReturnGeneratedKey.apply()
          val pages = allPages.slice(k * 750, k * 750 + 750)
          val params = for (p <- pages; j <- jurors) yield Seq(p, 0, j, roundId)
          val (_, tVotes) = ms(params.grouped(5000).foreach(batch =>
            sql"INSERT INTO vote (page_id, rate, jury_id, round_id) VALUES (?, ?, ?, ?)".batch(batch: _*).apply()))
          val (_, tRows) = ms(RoundImageJdbc.addVoted(pages.map(roundId -> _), RoundImageJdbc.Origin.Manual))
          session.connection.rollback()
          tVotes -> tRows
        }
      }
      val (tv, tr) = (chunkTimes.map(_._1), chunkTimes.map(_._2))
      say(s"distribution chunk (750 files x ${jurors.size} jurors, ${750 * jurors.size} vote rows), $chunks chunks: " +
        s"votes ${f1(median(tv))} ms (${f1(tv.min)}-${f1(tv.max)}), round_image ${f1(median(tr))} ms " +
        s"(${f1(tr.min)}-${f1(tr.max)}), +${f1(100 * median(tr) / median(tv))}% of the votes' insert")
      if (problems.nonEmpty) say(s"PROBLEMS: ${problems.mkString("; ")}")
    } finally log.close()
  }
}
