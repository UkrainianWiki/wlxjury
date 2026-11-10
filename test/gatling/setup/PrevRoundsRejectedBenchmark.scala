package gatling.setup

import db.scalikejdbc.{Round, RoundUser}
import org.intracer.wmua.RejectedMode
import scalikejdbc._
import services.distribution.{DistributionSource, RoundDistributionHandler, Window}
import services.distribution.RoundDistributionHandler.PrevVotes

import java.io.{File, PrintWriter}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.collection.mutable.ListBuffer

/** "Rejected by at least N" from two yes/no previous rounds (triage R196-1, the review's
  * performance finding) on the Gatling fixture DB: the streamed distribution's queries before
  * the triage fix (`f0dd6a8e`: the rejections of all the rounds together, `COUNT(*)`), as
  * reviewed (`f17f848f`: distinct rejecting jurors, `COUNT(DISTINCT jury_id)`) and now (each
  * round on its own, correlated to the row's round: the app's own [[DistributionSource]]).
  *
  * The fixture's binary round and a copy of its votes (a second round, a third of the files'
  * rates flipped) are the previous rounds; the target is an empty round. First `EXPLAIN` of each
  * variant's queries; then a paired pilot: `bench.warmup` walks of each variant, then
  * `bench.samples` rounds of all of them, their order rotated each round. A walk is what a
  * job's Distribute phase reads: the count, then each window and its candidates (no filters, no
  * inserts); "top page" is a top-N's first ranked page of yes/no rounds (1000 files). For the
  * end-to-end path of today's code, `countNew` of the target (context, windows, candidates,
  * filters) is timed too.
  *
  * {{{
  * sbt 'set Test / javaOptions ++= Seq("-Xmx1024m", "-Dfile.encoding=UTF-8",
  *        "-Dscalikejdbc.global.loggingSQLAndTime.enabled=false")' \
  *     'Test/runMain gatling.setup.PrevRoundsRejectedBenchmark'
  * }}}
  * `-Dbench.samples` (7), `-Dbench.warmup` (2), `-Dbench.n` (2: rejected by at least n).
  * Not a spec: timings in CI are noise.
  */
object PrevRoundsRejectedBenchmark {
  private def prop(key: String, default: String) = sys.props.getOrElse(s"bench.$key", default)
  private val samples = prop("samples", "7").toInt
  private val warmup = prop("warmup", "2").toInt
  private val n = prop("n", "2").toInt
  private val windowSize = 15000
  private val stamp = LocalDateTime.now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
  private val outFile = new File(s"target/gatling/prev-rounds-bench-$stamp.txt")

  implicit private val session: DBSession = AutoSession

  private val out = ListBuffer.empty[String]
  private def say(line: String): Unit = { println(s"[bench] $line"); out += line }

  /** One variant's queries: the rejection condition over a vote row `s`, and a top page's. */
  final case class Variant(name: String, rejected: SQLSyntax, topGroup: SQLSyntax, topHaving: SQLSyntax)

  private def variants(prev: Seq[Long]): Seq[Variant] = Seq(
    Variant("before (pooled COUNT(*))",
      sqls"""NOT EXISTS (SELECT 1 FROM vote r WHERE r.page_id = s.page_id AND r.round_id IN ($prev) AND r.rate < 0
             HAVING COUNT(*) >= $n)""",
      sqls"s.page_id", sqls"SUM(s.rate < 0) < $n"),
    Variant("reviewed (COUNT DISTINCT jury_id)",
      sqls"""NOT EXISTS (SELECT 1 FROM vote r WHERE r.page_id = s.page_id AND r.round_id IN ($prev) AND r.rate < 0
             HAVING COUNT(DISTINCT r.jury_id) >= $n)""",
      sqls"s.page_id", sqls"COUNT(DISTINCT CASE WHEN s.rate < 0 THEN s.jury_id END) < $n"),
    Variant("now (per round)",
      sqls"""NOT EXISTS (SELECT 1 FROM vote r WHERE r.round_id = s.round_id AND r.page_id = s.page_id AND r.rate < 0
             HAVING COUNT(*) >= $n)""",
      sqls"s.page_id, s.round_id", sqls"SUM(s.rate < 0) < $n")
  )

  private def countSql(prev: Seq[Long], v: Variant) =
    sqls"SELECT COUNT(DISTINCT s.page_id) FROM vote s WHERE s.round_id IN ($prev) AND ${v.rejected}"

  private def windowSql(prev: Seq[Long], v: Variant, cursor: Long) =
    sqls"""SELECT COUNT(*), MAX(page_id) FROM (SELECT DISTINCT s.page_id FROM vote s
           WHERE s.round_id IN ($prev) AND ${v.rejected} AND s.page_id > $cursor
           ORDER BY s.page_id LIMIT $windowSize) w"""

  private def candidatesSql(prev: Seq[Long], v: Variant, from: Long, to: Long, target: Long) =
    sqls"""SELECT g.page_id, g.round_id, g.rs, g.c, g.v, i.* FROM (
             SELECT s.page_id, s.round_id, SUM(s.rate) rs, COUNT(*) c, SUM(s.rate > 0) v FROM vote s
             WHERE s.round_id IN ($prev) AND ${v.rejected} AND s.page_id > $from AND s.page_id <= $to
               AND NOT EXISTS (SELECT 1 FROM round_image xr WHERE xr.round_id = $target AND xr.page_id = s.page_id)
               AND NOT EXISTS (SELECT 1 FROM vote x WHERE x.round_id = $target AND x.page_id = s.page_id)
               AND NOT EXISTS (SELECT 1 FROM vote_aside xd WHERE xd.round_id = $target AND xd.page_id = s.page_id)
             GROUP BY s.page_id, s.round_id) g
           JOIN images i ON i.page_id = g.page_id
           WHERE i.deleted_at IS NULL ORDER BY g.page_id, g.round_id"""

  private def topSql(prev: Seq[Long], v: Variant) =
    sqls"""SELECT s.page_id, SUM(s.rate > 0) approvals, SUM(s.rate < 0) rejections, COUNT(*) cnt FROM vote s
           WHERE s.round_id IN ($prev) GROUP BY ${v.topGroup} HAVING ${v.topHaving}
           ORDER BY SUM(s.rate > 0) DESC, SUM(s.rate < 0) ASC, s.page_id ASC LIMIT 1000 OFFSET 0"""

  /** A walk of the literal SQL: (files counted, windows, candidate rows). */
  private def walkSql(prev: Seq[Long], v: Variant, target: Long): (Long, Int, Int) = {
    val count = sql"${countSql(prev, v)}".map(_.long(1)).single().getOrElse(0L)
    var cursor = 0L
    var windows = 0
    var rows = 0
    var done = false
    while (!done) {
      val w = sql"${windowSql(prev, v, cursor)}".map(rs => Window(rs.int(1), rs.longOpt(2).getOrElse(cursor))).single()
        .getOrElse(Window.empty)
      if (w.size == 0) done = true
      else {
        rows += sql"${candidatesSql(prev, v, cursor, w.last, target)}".map(_.long(1)).list().size
        windows += 1
        cursor = w.last
      }
    }
    (count, windows, rows)
  }

  /** The same walk through the app's own queries (today's code). */
  private def walkApp(prev: Seq[Long], target: Long): (Long, Int, Int) = {
    val votes = PrevVotes(rejectedBy = Some(n), rejectedMode = RejectedMode.Exclude)
    val count = DistributionSource.previousCount(prev, votes)
    var cursor = 0L
    var windows = 0
    var rows = 0
    var done = false
    while (!done) {
      val w = DistributionSource.previousWindow(prev, votes, cursor, windowSize)
      if (w.size == 0) done = true
      else {
        rows += DistributionSource.previousCandidates(prev, votes, cursor, w.last, target).size
        windows += 1
        cursor = w.last
      }
    }
    (count, windows, rows)
  }

  private def explain(title: String, query: SQLSyntax): Unit = {
    say(s"EXPLAIN $title")
    sql"EXPLAIN $query".map { rs =>
      Seq("id", "select_type", "table", "type", "key", "rows", "Extra").map(c => s"$c=${rs.stringOpt(c).getOrElse("")}")
        .mkString(" ")
    }.list().foreach(l => say(s"  $l"))
  }

  private def ms[T](f: => T): (T, Double) = {
    val t0 = System.nanoTime()
    val r = f
    (r, (System.nanoTime() - t0) / 1e6)
  }

  private def stats(xs: Seq[Double]): String = {
    val s = xs.sorted
    f"median ${s(s.size / 2)}%.0f ms, min ${s.head}%.0f, max ${s.last}%.0f (n=${s.size})"
  }

  def main(args: Array[String]): Unit = {
    val f = GatlingTestFixture
    GlobalSettings.loggingSQLAndTime = LoggingSQLAndTimeSettings(enabled = false)
    val a = f.roundBinaryId
    val b = Round.create(Round(None, 2, Some("Binary copy"), f.contestId, rates = Round.binaryRound, active = false)).getId
    sql"""INSERT INTO vote (page_id, rate, jury_id, round_id, monument_id)
          SELECT page_id, IF(page_id % 3 = 0, -rate, rate), jury_id, $b, monument_id FROM vote WHERE round_id = $a"""
      .update()
    val prev = Seq(a, b)
    val target = Round.create(Round(None, 3, Some("Target"), f.contestId, rates = Round.binaryRound, active = true,
      distribution = 1, previous = Some(prev.mkString(",")), prevRejectedBy = Some(n))).getId
    val juror = f.jurors.head._1
    Round.findById(target).get.addUsers(Seq(RoundUser(target, juror, "jury", active = true)))
    val votes = prev.map(r => r -> sql"SELECT COUNT(*) FROM vote WHERE round_id = $r".map(_.long(1)).single().get)
    say(s"previous rounds ${votes.map { case (r, c) => s"$r ($c votes)" }.mkString(", ")}, target $target, " +
      s"rejected by at least $n, window $windowSize, MariaDB ${sql"SELECT VERSION()".map(_.string(1)).single().get}")
    say(s"vote indexes: ${sql"SHOW INDEX FROM vote".map(rs => rs.string("Key_name") + ":" + rs.string("Column_name"))
      .list().mkString(", ")}")

    val vs = variants(prev)
    val firstWindow = DistributionSource.previousWindow(prev, PrevVotes(rejectedBy = Some(n)), 0L, windowSize)
    vs.foreach { v =>
      explain(s"${v.name}: count", countSql(prev, v))
      explain(s"${v.name}: candidates of the first window", candidatesSql(prev, v, 0L, firstWindow.last, target))
      explain(s"${v.name}: top page", topSql(prev, v))
    }

    // the literal SQL of "now" is the app's
    val appWalk = walkApp(prev, target)
    val nowWalk = walkSql(prev, vs.last, target)
    say(s"walks: ${vs.map(v => s"${v.name} ${walkSql(prev, v, target)}").mkString("; ")}; app $appWalk " +
      s"(files, windows, candidate rows)")
    require(appWalk == nowWalk, s"the literal per-round SQL differs from the app's: $nowWalk vs $appWalk")

    val handler = f.application.injector.instanceOf[RoundDistributionHandler]
    type Run = (String, () => Any)
    val runs: Seq[Run] = vs.map(v => (s"walk ${v.name}", () => walkSql(prev, v, target))) ++
      Seq[Run](("walk now, app code", () => walkApp(prev, target)), ("countNew now (end to end)", () => handler.countNew(target))) ++
      vs.map(v => (s"top page ${v.name}", () => sql"${topSql(prev, v)}".map(_.long(1)).list().size))
    (1 to warmup).foreach(_ => runs.foreach(_._2()))
    val times = runs.map(_._1 -> ListBuffer.empty[Double]).toMap
    (0 until samples).foreach { k =>
      val order = runs.drop(k % runs.size) ++ runs.take(k % runs.size)
      order.foreach { case (name, run) => times(name) += ms(run())._2 }
    }
    runs.foreach { case (name, _) => say(s"$name: ${stats(times(name).toSeq)}") }
    say(s"countNew now: ${handler.countNew(target)} files")

    outFile.getParentFile.mkdirs()
    val w = new PrintWriter(outFile, "UTF-8")
    try out.foreach(w.println) finally w.close()
    println(s"[bench] written to $outFile")
    sys.exit(0)
  }
}
