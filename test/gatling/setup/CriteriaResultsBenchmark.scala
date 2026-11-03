package gatling.setup

import controllers.{GalleryView, Pager, RoundList}
import db.scalikejdbc.{Round, RoundCriterion, SharedTestDb}
import org.intracer.wmua.{CriteriaRate, CriteriaResults}
import scalikejdbc._

import java.io.{File, PrintWriter}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.util.Random

/** C3's per-criterion reads (docs/plans/2026-11-02-criteria-rounds.md, "C3 results") on a
  * seeded criteria round, a small paired pilot: the Gatling fixture has no criteria round.
  *
  * Seeds (once, in the test container): a criteria round of `bench.files` files x
  * `bench.jurors` jurors (every file to every juror) x `bench.criteria` criteria, 90% of the
  * votes rated in every criterion, 5% partly, 5% not, and a plain rated round of
  * `bench.noiseFiles` x 5 jurors beside it, so vote's indexes have other rounds' rows. Then,
  * paired and interleaved (order alternating), `bench.samples` pairs after `bench.warmup`:
  *  1. the file list's page (1,000 files, all the jurors): its read before C3 (A, `list.files`)
  *     and C3's addition (B, [[CriteriaResults.of]]: the round's criteria and their averages);
  *  2. the by-rate gallery's page (15 files), the same pair;
  *  3. the CSV: all the round's files, by 1,000, their votes (A, `withVotes`) and their
  *     averages (B, [[CriteriaRate.averages]]);
  *  4. the large view for all the jurors: the file's grouped row is read anyway; B its
  *     averages and each juror's rates ([[CriteriaRate.ratesOfFile]]);
  * and EXPLAIN of each new query.
  *
  * {{{
  * sbt 'set Test / javaOptions ++= Seq("-Xmx1024m", "-Dfile.encoding=UTF-8")' \
  *     'Test/runMain gatling.setup.CriteriaResultsBenchmark'
  * }}}
  * SQL debug logging off. Results to target/gatling/criteria-results-bench-<stamp>/summary.txt.
  * Not a spec.
  */
object CriteriaResultsBenchmark {
  private def prop(key: String, default: String) = sys.props.getOrElse(s"bench.$key", default)
  private val files = prop("files", "5000").toInt
  private val jurors = prop("jurors", "10").toInt
  private val criteriaCount = prop("criteria", "4").toInt
  private val noiseFiles = prop("noiseFiles", "20000").toInt
  private val samples = prop("samples", "20").toInt
  private val warmup = prop("warmup", "3").toInt
  private val stamp = LocalDateTime.now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
  private val outDir = new File(prop("out", s"target/gatling/criteria-results-bench-$stamp"))

  implicit private val session: DBSession = AutoSession

  def main(args: Array[String]): Unit = {
    val status = try { body(); 0 } catch { case e: Throwable => e.printStackTrace(); 1 }
    sys.exit(status)
  }

  private def ms[T](f: => T): (T, Double) = {
    val t0 = System.nanoTime()
    val r = f
    r -> (System.nanoTime() - t0) / 1e6
  }

  private def pct(xs: Seq[Double], p: Double): Double = {
    val s = xs.sorted
    if (s.isEmpty) 0 else s(math.min(s.size - 1, math.floor(p * (s.size - 1) + 0.5).toInt))
  }

  private def f1(x: Double) = f"$x%.1f"

  /** Inserts `rows` in batches of 5,000. */
  private def batch(statement: String, rows: Seq[Seq[Any]]): Unit =
    rows.grouped(5000).foreach(chunk => SQL(statement).batch(chunk: _*).apply())

  private def seed(): Round = {
    val rnd = new Random(42)
    db.scalikejdbc.ContestJuryJdbc.create(Some(1), "Bench", 2026, "Ukraine")
    val users = (1 to jurors).map { i =>
      db.scalikejdbc.User.create(db.scalikejdbc.User(s"Juror $i", s"juror$i@example.com", None, Set("jury"),
        contestId = Some(1))).getId
    }
    val criteria = (1 to criteriaCount).map(_.toLong)
    val round = Round(None, 1, Some("Criteria"), 1, rates = Round.ratesById(10), active = true, criterionIds = criteria)
    val created = db.scalikejdbc.Round.create(round)
    val plain = db.scalikejdbc.Round.create(Round(None, 2, Some("Plain"), 1, rates = Round.ratesById(10), active = true))
    val pageIds = (1L to (files + noiseFiles).toLong)
    pageIds.grouped(5000).foreach(chunk => db.scalikejdbc.ImageJdbc.batchInsert(chunk.map(p =>
      org.intracer.wmua.Image(p, s"File:Bench $p.jpg", None, None, 1000, 800, Some(s"${10 + p % 20}-101-$p")))))
    // the criteria round: every file to every juror; the vote's rate the criteria's sum (U5)
    val rated = for (p <- 1L to files.toLong; u <- users) yield {
      val kind = rnd.nextInt(20) // 0: unrated, 1: partly, else rated
      val rates = criteria.map(_ => 1 + rnd.nextInt(10))
      val rated0 = if (kind == 0) Nil else if (kind == 1) rates.take(1 + rnd.nextInt(criteriaCount - 1)) else rates
      (p, u, if (rated0.size == criteriaCount) rated0.sum else 0, rated0)
    }
    batch("INSERT INTO vote (page_id, jury_id, round_id, rate, monument_id) VALUES (?, ?, ?, ?, ?)",
      rated.map { case (p, u, sum, _) => Seq(p, u, created.getId, sum, s"${10 + p % 20}-101-$p") })
    val voteIds = sql"SELECT page_id, jury_id, id FROM vote WHERE round_id = ${created.getId}"
      .map(rs => (rs.long(1), rs.long(2)) -> rs.long(3)).list().toMap
    batch("INSERT INTO criteria_rate (vote_id, criterion_id, rate) VALUES (?, ?, ?)",
      rated.flatMap { case (p, u, _, rs) => rs.zipWithIndex.map { case (r, i) => Seq(voteIds(p -> u), criteria(i), r) } })
    batch("INSERT INTO vote (page_id, jury_id, round_id, rate, monument_id) VALUES (?, ?, ?, ?, ?)",
      for (p <- (files + 1L) to (files + noiseFiles).toLong; u <- users.take(5))
        yield Seq(p, u, plain.getId, rnd.nextInt(11), s"${10 + p % 20}-101-$p"))
    sql"ANALYZE TABLE vote, criteria_rate, round_criterion, images".execute()
    created
  }

  private def body(): Unit = {
    SharedTestDb.init()
    GlobalSettings.loggingSQLAndTime = LoggingSQLAndTimeSettings(enabled = false)
    outDir.mkdirs()
    val log = new PrintWriter(new File(outDir, "summary.txt"), "UTF-8")
    def say(line: String): Unit = { println(s"[criteria-results] $line"); log.println(line); log.flush() }
    try {
      val (round, seedMs) = ms(seed())
      val n = RoundCriterion.countOf(round.getId)
      val votes = sql"SELECT COUNT(*) FROM vote".map(_.long(1)).single().get
      val rates = sql"SELECT COUNT(*) FROM criteria_rate".map(_.long(1)).single().get
      val version = sql"SELECT VERSION()".map(_.string(1)).single().get
      say(s"MariaDB $version; round ${round.getId}: $files files x $jurors jurors x $n criteria; " +
        s"vote $votes rows, criteria_rate $rates rows; seeded in ${f1(seedMs / 1000)} s; SQL logging off")
      say(s"samples $samples after $warmup warm-up, pairs interleaved, order alternating")

      val fileList = RoundList(round, 0, view = GalleryView.FileList)
      val byRate = RoundList(round, 0, view = GalleryView.ByRate)
      val allIds = fileList.query.pageKeys(Seq("sum(s.rate)")).map(_._1)
      val rnd = new Random(7)

      /** `a` and `b` paired, interleaved; their medians and p90 and B's share. */
      def paired(name: String, a: () => Any, b: () => Any): Unit = {
        val as = Seq.newBuilder[Double]
        val bs = Seq.newBuilder[Double]
        (0 until warmup + samples).foreach { i =>
          val (ta, tb) =
            if (i % 2 == 0) { val x = ms(a())._2; x -> ms(b())._2 } else { val y = ms(b())._2; ms(a())._2 -> y }
          if (i >= warmup) { as += ta; bs += tb }
        }
        val (ra, rb) = (as.result(), bs.result())
        say(f"$name%-48s A median ${f1(pct(ra, 0.5))}%7s ms (p10 ${f1(pct(ra, 0.1))}, p90 ${f1(pct(ra, 0.9))})" +
          f" | B median ${f1(pct(rb, 0.5))}%7s ms (p10 ${f1(pct(rb, 0.1))}, p90 ${f1(pct(rb, 0.9))})" +
          f" | B/A ${pct(rb, 0.5) / math.max(pct(ra, 0.5), 0.001) * 100}%.0f%%")
      }

      paired("file list page (1000 files), A files, B C3",
        () => fileList.files(Pager(1 + rnd.nextInt(files / 1000), files, 1000)),
        () => { val from = 1000 * rnd.nextInt(files / 1000); CriteriaResults.of(round, allIds.slice(from, from + 1000), 0) })
      paired("by-rate page (15 files), A files, B C3",
        () => byRate.files(Pager(1 + rnd.nextInt(files / 15), files, 15)),
        () => { val from = 15 * rnd.nextInt(files / 15); CriteriaResults.of(round, allIds.slice(from, from + 15), 0) })
      paired("CSV, all files by 1000, A withVotes, B averages",
        () => allIds.grouped(1000).foreach(fileList.query.withVotes(_)),
        () => allIds.grouped(1000).foreach(CriteriaRate.averages(round.getId, _)))
      paired("large view (1 file), A grouped row, B averages + juror rates",
        () => { val p = allIds(rnd.nextInt(allIds.size)); fileList.query.withVotes(Seq(p)) },
        () => { val p = allIds(rnd.nextInt(allIds.size)); CriteriaRate.averages(round.getId, Seq(p)); CriteriaRate.ratesOfFile(round.getId, p) })

      def explain(name: String, q: SQLSyntax): Unit = {
        say(s"EXPLAIN $name:")
        sql"EXPLAIN $q".map(rs => (1 to rs.metaData.getColumnCount).map(i => s"${rs.metaData.getColumnLabel(i)}=${rs.stringOpt(i).getOrElse("NULL")}").mkString(" "))
          .list().foreach(l => say("  " + l))
      }
      val page = allIds.take(1000)
      val rid = round.getId
      explain("averages, 1000 files", sqls"""SELECT v.page_id, cr.criterion_id, AVG(cr.rate) FROM vote v
            STRAIGHT_JOIN criteria_rate cr ON cr.vote_id = v.id
            STRAIGHT_JOIN round_criterion rc ON rc.round_id = v.round_id AND rc.criterion_id = cr.criterion_id
            WHERE v.round_id = $rid AND v.page_id IN ($page) AND v.rate > 0 AND cr.rate > 0
            GROUP BY v.page_id, cr.criterion_id""")
      explain("averages, 15 files, one juror", sqls"""SELECT v.page_id, cr.criterion_id, AVG(cr.rate) FROM vote v
            STRAIGHT_JOIN criteria_rate cr ON cr.vote_id = v.id
            STRAIGHT_JOIN round_criterion rc ON rc.round_id = v.round_id AND rc.criterion_id = cr.criterion_id
            WHERE v.round_id = $rid AND v.page_id IN (${page.take(15)}) AND v.rate > 0 AND v.jury_id = 1 AND cr.rate > 0
            GROUP BY v.page_id, cr.criterion_id""")
      explain("ratesOfFile", sqls"""SELECT v.id, cr.criterion_id, cr.rate FROM vote v
            STRAIGHT_JOIN criteria_rate cr ON cr.vote_id = v.id
            STRAIGHT_JOIN round_criterion rc ON rc.round_id = v.round_id AND rc.criterion_id = cr.criterion_id
            WHERE v.round_id = $rid AND v.page_id = ${page.head} AND cr.rate > 0""")
    } finally log.close()
  }
}
