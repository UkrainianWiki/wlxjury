package gatling.setup

import controllers.{GalleryView, ListOrder, RoundList, VoteFilter}
import db.scalikejdbc.{ImageJdbc, Round}
import db.scalikejdbc.rewrite.ImageDbNew.{Limit, SelectionQuery}
import org.intracer.wmua.{BinaryOrder, ImageWithRating, Selection}
import org.intracer.wmua.ImageWithRating.Ranks
import scalikejdbc._

import java.io.{File, PrintWriter}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.collection.mutable.ListBuffer

/** The ranks of the by-rate gallery and the file list (docs/plans/2026-11-01-open-followups.md,
  * batch B11) on the Gatling fixture DB: the queries of a whole page view with today's
  * [[SelectionQuery.keyCounts]] (A), with the pager's total taken from its counts (K), with the
  * ranks as window functions in the page query (W), and W with the list's total as one more
  * window (WC); for information, a narrow grouped page (D: the page's ids, aggregates and
  * ranks without the images join, then the page's images; DK: the same without the windows,
  * ranked and counted by keyCounts) and a file list id query without the images join (WCn).
  *
  * First it checks that W's ranks are today's on whole lists and on pages (ties spanning
  * pages, the last page, past the end), filters, regions, binary orders and jurors' lists.
  * Then per case: `bench.warmup` page views of each variant, then `bench.samples` rounds of
  * all the variants, their order rotated each round (paired, interleaved), times per query.
  * Then `ANALYZE FORMAT=JSON` of the variants' queries.
  *
  * {{{
  * sbt 'set Test / javaOptions ++= Seq("-Xmx1024m", "-Dfile.encoding=UTF-8",
  *        "-Dscalikejdbc.global.loggingSQLAndTime.enabled=false")' \
  *     'Test/runMain gatling.setup.RankQueryBenchmark'
  * }}}
  * `-Dbench.samples` (15), `-Dbench.warmup` (2), `-Dbench.cases` (a regex of the case labels),
  * `-Dbench.out` (target/gatling/rank-bench-<stamp>). Not a spec: timings in CI are noise.
  */
object RankQueryBenchmark {
  private def prop(key: String, default: String) = sys.props.getOrElse(s"bench.$key", default)
  private val samples = prop("samples", "15").toInt
  private val warmup = prop("warmup", "2").toInt
  private val cases = prop("cases", "").r
  private val stamp = LocalDateTime.now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
  private val outDir = new File(prop("out", s"target/gatling/rank-bench-$stamp"))

  implicit private val session: DBSession = AutoSession

  private val i = ImageJdbc.syntax("i")

  /** A page view's result: the list's total, the page's ids and ranks, and each query's ms. */
  final case class View(total: Int, ids: Seq[Long], ranks: Seq[String], parts: Seq[(String, Double)]) {
    def ms: Double = parts.map(_._2).sum
  }

  private final class Clock {
    val parts = ListBuffer.empty[(String, Double)]
    def apply[T](name: String)(f: => T): T = {
      val t0 = System.nanoTime()
      val r = f
      parts += name -> (System.nanoTime() - t0) / 1e6
      r
    }
  }

  private def rankString(start: Int, n: Int) = if (n == 1) start.toString else s"$start-${start + n - 1}"

  /** The rank columns of a key: its rank (the first of its files' places), the number of its
    * files and (`total`) the list's; `flat` for one row per file (a juror's list), no sum.
    */
  private def windows(key: Seq[(String, Int)], total: Boolean, flat: Boolean = false): String = {
    val cols = key.map { case (e, d) => (if (flat) e.replace("sum(", "(") else e, d) }
    val order = cols.map { case (e, d) => s"$e ${if (d < 0) "desc" else "asc"}" }.mkString(", ")
    s", rank() over (order by $order) as rank_start, count(*) over (partition by ${cols.map(_._1).mkString(", ")}) as rank_n" +
      (if (total) ", count(*) over () as list_total" else "")
  }

  private val From = " from selection s"

  private def insertAt(sql: SQLSyntax, marker: String, text: String): SQLSyntax = {
    val at = sql.value.indexOf(marker)
    require(at > 0, s"no '$marker' in ${sql.value}")
    SQLSyntax.createUnsafely(sql.value.substring(0, at) + text + sql.value.substring(at), sql.parameters)
  }

  private def oneLine(sql: SQLSyntax): String = sql.value.replaceAll("\\s+", " ").trim

  private def paged(list: RoundList, page: Int): SelectionQuery =
    list.query.copy(limit = Some(Limit(Some(list.pageSize), Some((page - 1) * list.pageSize))))

  private def idQuery(q: SelectionQuery): SelectionQuery = q.copy(grouped = true, groupWithDetails = false)

  // ---- the variants' SQL

  /** A grouped by-rate page with its ranks. */
  private def wPageSql(list: RoundList, q: SelectionQuery, total: Boolean): SQLSyntax =
    insertAt(q.query(), From, windows(list.rankKey.columns, total))

  /** A juror's by-rate page (the deferred join) with its ranks, from the inner query. */
  private def wJurorSql(list: RoundList, q: SelectionQuery): SQLSyntax = {
    val inner = insertAt(q.query(), From, windows(list.rankKey.columns, total = false, flat = true))
    insertAt(inner, " from (select s.id", ", k.rank_start, k.rank_n")
  }

  /** A file list's page ids with their ranks. */
  private def wIdSql(list: RoundList, q: SelectionQuery, total: Boolean): SQLSyntax =
    insertAt(idQuery(q).query(idOnly = true), From, windows(list.rankKey.columns, total))

  /** [[wIdSql]] without the images join (no monument join only). */
  private def wIdNarrowSql(list: RoundList, q: SelectionQuery): SQLSyntax = {
    val sql = wIdSql(list, q, total = true)
    val join = """\s+STRAIGHT_JOIN images i\s+on i.page_id = s.page_id""".r
    require(join.findFirstIn(sql.value).isDefined && !sql.value.contains("join monument"), sql.value)
    SQLSyntax.createUnsafely(join.replaceFirstIn(sql.value, "").replace("i.page_id as pi_on_i", "s.page_id as pi_on_i"),
      sql.parameters)
  }

  /** A narrow grouped by-rate page: aggregates and ranks from selection alone (filter All, no
    * monument join), the page's images read after.
    */
  private def dPageSql(list: RoundList, q: SelectionQuery, ranked: Boolean = true): SQLSyntax = {
    require(list.filter == VoteFilter.All && list.region.isEmpty)
    val w = if (ranked) windows(list.rankKey.columns, total = true) else ""
    SQLSyntax.createUnsafely(
      s"select s.page_id, sum(s.rate) as rate, count(s.rate) as rate_count, sum(s.rate > 0) as approvals, " +
        s"sum(s.rate < 0) as rejections$w from selection s"
    ) + q.where() + SQLSyntax.createUnsafely(s" group by s.page_id ${q.orderBy()} ${q.limitSql()}")
  }

  // ---- the page views

  private def keyCountRanks(list: RoundList, c: Clock): Ranks[Seq[Int]] =
    c("keyCounts")(Ranks(list.query.keyCounts(list.rankKey.columns)))

  private def byRateA(list: RoundList, page: Int, withCount: Boolean = true): View = {
    val c = new Clock
    val total = if (withCount) c("count")(list.query.count()) else -1
    val files = c("page")(paged(list, page).list())
    val ranks = keyCountRanks(list, c)
    View(total, files.map(_.pageId), files.map(f => ranks(list.rankKey.values(f))), c.parts.toSeq)
  }

  private def byRateK(list: RoundList, page: Int): View = {
    val c = new Clock
    val files = c("page")(paged(list, page).list())
    val ranks = keyCountRanks(list, c)
    View(ranks.counts.map(_._2).sum, files.map(_.pageId), files.map(f => ranks(list.rankKey.values(f))), c.parts.toSeq)
  }

  private def readW(sql: SQLSyntax, total: Boolean)(read: WrappedResultSet => Long): Seq[(Long, String, Option[Int])] =
    sql"$sql".map(rs => (read(rs), rankString(rs.int("rank_start"), rs.int("rank_n")),
      if (total) Some(rs.int("list_total")) else None)).list()

  private def byRateW(list: RoundList, page: Int, total: Boolean): View = {
    val c = new Clock
    val q = paged(list, page)
    val count = if (total) 0 else c("count")(list.query.count())
    val rows = c("page+w")(sql"${wPageSql(list, q, total)}".map { rs =>
      (q.Readers.groupedReader(q.tally)(rs), rankString(rs.int("rank_start"), rs.int("rank_n")),
        if (total) rs.int("list_total") else count)
    }.list())
    val n = rows.headOption.map(_._3).getOrElse(if (total) c("count")(list.query.count()) else count)
    View(n, rows.map(_._1.pageId), rows.map(_._2), c.parts.toSeq)
  }

  /** D without the windows: the narrow page, its images, and the ranks and total from keyCounts. */
  private def byRateDK(list: RoundList, page: Int): View = {
    val c = new Clock
    val q = paged(list, page)
    val rows = c("ids")(sql"${dPageSql(list, q, ranked = false)}".map { rs =>
      (rs.long(1), rs.int(2), rs.int(3), rs.int(4), rs.int(5))
    }.list())
    val ids = rows.map(_._1)
    val images = c("images")(if (ids.isEmpty) Map.empty[Long, org.intracer.wmua.Image]
      else sql"select ${i.result.*} from images i where i.page_id in ($ids)".map(ImageJdbc(i)(_)).list()
        .map(im => im.pageId -> im).toMap)
    val files = rows.flatMap { case (id, sum, n, a, r) =>
      images.get(id).map(im => ImageWithRating(im, Seq(Selection(id, juryId = 0, roundId = 0, rate = sum)), n, Some(a), Some(r)))
    }
    val ranks = keyCountRanks(list, c)
    View(ranks.counts.map(_._2).sum, files.map(_.pageId), files.map(f => ranks(list.rankKey.values(f))), c.parts.toSeq)
  }

  private def byRateD(list: RoundList, page: Int): View = {
    val c = new Clock
    val q = paged(list, page)
    val rows = c("ids+w")(sql"${dPageSql(list, q)}".map { rs =>
      (rs.long(1), rs.int(2), rs.int(3), rs.int(4), rs.int(5), rankString(rs.int("rank_start"), rs.int("rank_n")),
        rs.int("list_total"))
    }.list())
    val ids = rows.map(_._1)
    val images = c("images")(if (ids.isEmpty) Map.empty[Long, org.intracer.wmua.Image]
      else sql"select ${i.result.*} from images i where i.page_id in ($ids)".map(ImageJdbc(i)(_)).list()
        .map(im => im.pageId -> im).toMap)
    val files = rows.flatMap { case (id, sum, n, a, r, _, _) =>
      images.get(id).map(im => ImageWithRating(im, Seq(Selection(id, juryId = 0, roundId = 0, rate = sum)), n, Some(a), Some(r)))
    }
    val total = rows.headOption.map(_._7).getOrElse(c("count")(list.query.count()))
    View(total, files.map(_.pageId), rows.map(_._6), c.parts.toSeq)
  }

  private def jurorW(list: RoundList, page: Int): View = {
    val c = new Clock
    val q = paged(list, page)
    val rows = c("page+w")(sql"${wJurorSql(list, q)}".map { rs =>
      (q.Readers.rowReader(rs), rankString(rs.int("rank_start"), rs.int("rank_n")))
    }.list())
    View(-1, rows.map(_._1.pageId), rows.map(_._2), c.parts.toSeq)
  }

  private def fileListA(list: RoundList, page: Int, withCount: Boolean): View = {
    val c = new Clock
    val q = paged(list, page)
    val total = if (withCount) c("count")(list.query.count()) else 0
    val ids = c("ids")(sql"${idQuery(q).query(idOnly = true)}".map(_.long(1)).list())
    val files = c("withVotes")(q.withVotes(ids))
    val ranks = keyCountRanks(list, c)
    val n = if (withCount) total else ranks.counts.map(_._2).sum
    View(n, files.map(_.pageId), files.map(f => ranks(list.rankKey.values(f))), c.parts.toSeq)
  }

  private def fileListW(list: RoundList, page: Int, total: Boolean, narrow: Boolean = false): View = {
    val c = new Clock
    val q = paged(list, page)
    val count = if (total) 0 else c("count")(list.query.count())
    val sql = if (narrow) wIdNarrowSql(list, q) else wIdSql(list, q, total)
    val rows = c("ids+w")(readW(sql, total)(_.long(1)))
    val files = c("withVotes")(q.withVotes(rows.map(_._1)))
    val n = if (!total) count else rows.headOption.flatMap(_._3).getOrElse(c("count")(list.query.count()))
    View(n, files.map(_.pageId), rows.map(_._2), c.parts.toSeq)
  }

  // ---- correctness

  private val problems = ListBuffer.empty[String]

  private def check(label: String, ok: Boolean, detail: => String): Unit =
    if (!ok) { problems += s"$label: $detail"; println(s"[ranks] MISMATCH $label: $detail") }

  // only the checks whose label matches (`-Dbench.checks=^file list`), all by default
  private val checkLabels = prop("checks", "").r

  private def guarded(label: String)(f: => Any): Unit =
    if (checkLabels.findFirstIn(label).isDefined) try { f; () } catch { case e: Exception => check(label, ok = false, s"failed: ${e.getMessage}") }

  private def firstDiff(a: Seq[(Long, String)], b: Seq[(Long, String)]): String =
    s"sizes ${a.size}/${b.size}, first diff at ${a.zip(b).indexWhere { case (x, y) => x != y }}: " +
      a.zip(b).find { case (x, y) => x != y }.fold("-")(d => s"${d._1} vs ${d._2}")

  /** The pages to compare: the first two, the first page a tie spans into, the middle, the last
    * and past the end.
    */
  private def pagesOf(expected: Seq[(Long, String)], pageSize: Int): Seq[Int] = {
    val pages = (expected.size + pageSize - 1) / pageSize
    val spanning = (1 until pages).find(p => expected(p * pageSize - 1)._2 == expected(p * pageSize)._2 &&
      expected(p * pageSize)._2.contains("-")).map(_ + 1)
    (Seq(1, 2, pages / 2, pages, pages + 1) ++ spanning).filter(_ >= 1).distinct.sorted
  }

  private def checkByRate(label: String, list: RoundList): Int = {
    val full = list.query.list()
    val kc = Ranks(list.query.keyCounts(list.rankKey.columns))
    val expected = full.map(f => f.pageId -> kc(list.rankKey.values(f)))
    check(s"$label today's ranks all set", expected.forall(_._2.nonEmpty),
      s"${expected.count(_._2.isEmpty)} files without a rank, e.g. ${expected.find(_._2.isEmpty)}")
    val juror = list.asUserId != 0
    val all: Seq[(Long, String)] =
      if (juror) {
        val q = list.query.copy(limit = Some(Limit(Some(1000000), Some(0))))
        sql"${wJurorSql(list, q)}".map(rs => (rs.long(i.resultName.pageId.value), rankString(rs.int("rank_start"), rs.int("rank_n"))))
          .list()
      } else readW(wPageSql(list, list.query, total = false), total = false)(rs => rs.long(i.resultName.pageId.value))
        .map(r => r._1 -> r._2)
    check(s"$label whole list", all == expected, firstDiff(all, expected))
    val pages = pagesOf(expected, list.pageSize)
    pages.foreach { p =>
      val slice = expected.slice((p - 1) * list.pageSize, p * list.pageSize)
      val v = if (juror) jurorW(list, p) else byRateW(list, p, total = true)
      val got = v.ids.zip(v.ranks)
      check(s"$label page $p", got == slice, firstDiff(got, slice))
      if (!juror) check(s"$label page $p total", v.total == expected.size, s"${v.total} vs ${expected.size}")
      if (!juror && list.filter == VoteFilter.All && list.region.isEmpty) {
        val d = byRateD(list, p)
        check(s"$label page $p D", d.ids.zip(d.ranks) == slice && d.total == expected.size,
          firstDiff(d.ids.zip(d.ranks), slice) + s" total ${d.total}")
        val dk = byRateDK(list, p)
        check(s"$label page $p DK", dk.ids.zip(dk.ranks) == slice && dk.total == expected.size,
          firstDiff(dk.ids.zip(dk.ranks), slice) + s" total ${dk.total}")
      }
      if (!juror) {
        val k = byRateK(list, p)
        check(s"$label page $p K", k.total == expected.size && k.ids.zip(k.ranks) == slice, s"${k.total} vs ${expected.size}")
      }
    }
    println(s"[ranks] $label: ${expected.size} files, ${kc.counts.size} keys, pages ${pages.mkString(",")}")
    expected.size
  }

  private def checkFileList(label: String, list: RoundList): Int = {
    val kc = Ranks(list.query.keyCounts(list.rankKey.columns))
    val keys = list.query.pageKeys(list.rankKey.columns.map(_._1))
    val expected = keys.map { case (id, v) => id -> kc(v) }
    check(s"$label today's ranks all set", expected.forall(_._2.nonEmpty), s"${expected.count(_._2.isEmpty)} unranked")
    val all = readW(wIdSql(list, list.query, total = false), total = false)(_.long(1)).map(r => r._1 -> r._2)
    check(s"$label whole list", all == expected, firstDiff(all, expected))
    val pages = pagesOf(expected, list.pageSize)
    pages.foreach { p =>
      val slice = expected.slice((p - 1) * list.pageSize, p * list.pageSize)
      val a = fileListA(list, p, withCount = true)
      check(s"$label page $p today", a.ids.zip(a.ranks) == slice, firstDiff(a.ids.zip(a.ranks), slice))
      val w = fileListW(list, p, total = true)
      check(s"$label page $p W", w.ids.zip(w.ranks) == slice && w.total == expected.size,
        firstDiff(w.ids.zip(w.ranks), slice) + s" total ${w.total}")
      locally {
        val n = fileListW(list, p, total = true, narrow = true)
        check(s"$label page $p WCn", n.ids.zip(n.ranks) == slice && n.total == expected.size,
          firstDiff(n.ids.zip(n.ranks), slice) + s" total ${n.total}")
      }
    }
    println(s"[ranks] $label: ${expected.size} files, ${kc.counts.size} keys, pages ${pages.mkString(",")}")
    expected.size
  }

  // ---- timing

  final case class Case(label: String, variants: Seq[(String, () => View)])

  final case class Sample(label: String, variant: String, sample: Int, position: Int, view: View)

  private def median(xs: Seq[Double]): Double = {
    val s = xs.sorted
    if (s.isEmpty) 0 else if (s.size % 2 == 1) s(s.size / 2) else (s(s.size / 2 - 1) + s(s.size / 2)) / 2
  }

  private def run(c: Case): Seq[Sample] = {
    (0 until warmup).foreach(_ => c.variants.foreach(_._2()))
    val n = c.variants.size
    val out = for {
      s <- 0 until samples
      rotated = c.variants.drop(s % n) ++ c.variants.take(s % n)
      ((name, f), pos) <- rotated.zipWithIndex
    } yield Sample(c.label, name, s, pos, f())
    // every variant ranks the page as today's does
    out.groupBy(_.sample).values.foreach { round =>
      val a = round.find(_.variant == "A").get.view
      round.filter(_.variant != "A").foreach { o =>
        check(s"${c.label} ${o.variant} sample ${o.sample}", o.view.ids == a.ids && o.view.ranks == a.ranks &&
          (o.view.total < 0 || a.total < 0 || o.view.total == a.total), s"ids/ranks/total differ (${o.view.total} vs ${a.total})")
      }
    }
    println(s"[ranks] timed ${c.label}")
    out
  }

  // ---- ANALYZE

  private def analyze(name: String, sql: SQLSyntax, json: PrintWriter): String = {
    val text = oneLine(sql)
    val bound = sql.parameters.foldLeft(text) { (acc, p) =>
      val v = p match {
        case s: String => "'" + s.replace("'", "''") + "'"
        case other     => other.toString
      }
      acc.replaceFirst("\\?", java.util.regex.Matcher.quoteReplacement(v))
    }
    try {
      val plan = SQL(s"ANALYZE FORMAT=JSON $bound").map(_.string(1)).single().getOrElse("")
      json.println(s"==== $name\n$bound\n$plan\n")
      val time = """"r_total_time_ms":\s*([\d.]+)""".r.findFirstMatchIn(plan).map(_.group(1)).getOrElse("?")
      def count(s: String) = s.r.findAllMatchIn(plan).size
      val sorts = """"filesort":""".r.findAllMatchIn(plan).size
      val sortRows = """"r_used_priority_queue":\s*(\w+)""".r.findAllMatchIn(plan).map(_.group(1)).mkString(",")
      f"$name%-28s ${time}%9s ms  filesort=$sorts (priority queue: $sortRows) temporary=${count("temporary_table")} " +
        s"window_functions=${plan.contains("window_functions_computation")} keys=" +
        """"key":\s*"([^"]+)"""".r.findAllMatchIn(plan).map(_.group(1)).toSeq.distinct.mkString(",")
    } catch { case e: Exception => s"$name ANALYZE failed: ${e.getMessage}" }
  }

  def main(args: Array[String]): Unit = {
    val status = try { body(); 0 } catch { case e: Throwable => e.printStackTrace(); 1 }
    sys.exit(status)
  }

  private def body(): Unit = {
    require(!GatlingConfig.external, "Use the disposable fixture, never an external target")
    val f = GatlingTestFixture
    GlobalSettings.loggingSQLAndTime = LoggingSQLAndTimeSettings(enabled = false)
    outDir.mkdirs()
    val log = new PrintWriter(new File(outDir, "summary.txt"), "UTF-8")
    def say(line: String): Unit = { println(s"[ranks] $line"); log.println(line); log.flush() }

    val rated = Round.findById(f.roundRatingId).get
    val binary = Round.findById(f.roundBinaryId).get
    val version = sql"select version()".map(_.string(1)).single().get
    val regions = sql"""select ${SQLSyntax.createUnsafely(db.scalikejdbc.rewrite.ImageDbNew.regionOf("s.monument_id"))} r,
                        count(distinct s.page_id) n from selection s where s.round_id = ${rated.getId}
                        group by r having r is not null order by n desc limit 2""".map(_.string(1)).list()
    val juror = f.jurors.map(_._1).maxBy(j =>
      sql"select count(*) from selection where jury_id = $j and round_id = ${rated.getId}".map(_.int(1)).single().get)
    val bJuror = f.jurors.map(_._1).maxBy(j =>
      sql"select count(*) from selection where jury_id = $j and round_id = ${binary.getId}".map(_.int(1)).single().get)
    say(s"RankQueryBenchmark $stamp: MariaDB $version, rated round ${rated.getId}, binary round ${binary.getId}, " +
      s"selection ${sql"select count(*) from selection".map(_.long(1)).single().get} rows, regions ${regions.mkString(",")}, " +
      s"jurors $juror (rated), $bJuror (binary); warmup $warmup, samples $samples; SQL logging off")
    val rateVals = sql"select rate, count(*) from selection where round_id = ${rated.getId} group by rate"
      .map(rs => rs.int(1) -> rs.int(2)).list()
    say(s"rated round's votes by rate: ${rateVals.mkString(", ")}")

    def byRate(round: Round, order: ListOrder = ListOrder.ByRate, filter: VoteFilter = VoteFilter.All,
        region: Option[String] = None, user: Long = 0) =
      RoundList(round, user, region, filter, if (round.isBinary && order == ListOrder.ByRate && user == 0)
        ListOrder.Binary(BinaryOrder.Default) else order, GalleryView.ByRate)
    def fileList(round: Round, order: ListOrder = ListOrder.ByRate, filter: VoteFilter = VoteFilter.All,
        region: Option[String] = None, user: Long = 0) =
      RoundList(round, user, region, filter, order, GalleryView.FileList)

    // 1. the ranks
    if (prop("check", "true").toBoolean) {
      val checks: Seq[(String, RoundList)] = Seq(
        "rated all" -> byRate(rated),
        "rated rated" -> byRate(rated, filter = VoteFilter.Rated),
        "rated unrated" -> byRate(rated, filter = VoteFilter.Unrated),
        "rated region" -> byRate(rated, region = regions.headOption),
        "rated rated region" -> byRate(rated, filter = VoteFilter.Rated, region = regions.headOption),
        "binary approvals" -> byRate(binary),
        "binary difference" -> byRate(binary, ListOrder.Binary(BinaryOrder.Difference)),
        "binary approvals-only" -> byRate(binary, ListOrder.Binary(BinaryOrder.ApprovalsOnly)),
        "binary selected" -> byRate(binary, filter = VoteFilter.Rate(1)),
        "binary rejected" -> byRate(binary, filter = VoteFilter.Rate(-1)),
        "binary unrated" -> byRate(binary, filter = VoteFilter.Unrated),
        "binary region" -> byRate(binary, region = regions.headOption),
        "juror rated" -> byRate(rated, user = juror),
        "juror rated rated" -> byRate(rated, filter = VoteFilter.Rated, user = juror),
        "juror binary" -> byRate(binary, ListOrder.Binary(BinaryOrder.Default), user = bJuror)
      )
      checks.foreach { case (label, list) => guarded(label)(checkByRate(label, list)) }
      // several regions need the monument join, which a RoundList's one region doesn't have
      if (regions.size == 2) guarded("rated two regions") {
        val list = byRate(rated)
        val adm0 = sql"""select m.adm0 from selection s join monument m on m.id = s.monument_id
                          where s.round_id = ${rated.getId} group by m.adm0 order by count(*) desc limit 2"""
          .map(_.string(1)).list()
        println(s"[ranks] two regions: adm0 ${adm0.mkString(",")}")
        val q = list.query.copy(regions = adm0.toSet)
        val kc = Ranks(q.keyCounts(list.rankKey.columns))
        val expected = q.list().map(f => f.pageId -> kc(list.rankKey.values(f)))
        val got = readW(wPageSql(list, q, total = false), total = false)(rs => rs.long(i.resultName.pageId.value))
          .map(r => r._1 -> r._2)
        check("rated two regions (monument join) whole list", got == expected && expected.nonEmpty, firstDiff(got, expected))
        println(s"[ranks] rated two regions: ${expected.size} files")
      }
      Seq(
        "file list rated" -> fileList(rated),
        "file list rated rated region" -> fileList(rated, filter = VoteFilter.Rated, region = regions.headOption),
        "file list binary" -> fileList(binary),
        "file list binary difference selected" ->
          fileList(binary, ListOrder.Binary(BinaryOrder.Difference), VoteFilter.Rate(1)),
        "file list juror rated" -> fileList(rated, user = juror)
      ).foreach { case (label, list) => guarded(label)(checkFileList(label, list)) }
      say(s"rank checks: ${if (problems.isEmpty) "all equal" else s"${problems.size} mismatches"}")
      problems.take(30).foreach(p => say(s"  $p"))
    }

    // 2. the timings
    val r2 = byRate(rated)
    val b1 = byRate(binary)
    val deep = (r2.query.count() + r2.pageSize - 1) / r2.pageSize / 2
    def byRateCase(label: String, list: RoundList, page: Int) = Case(label, Seq(
      "A" -> (() => byRateA(list, page)),
      "K" -> (() => byRateK(list, page)),
      "W" -> (() => byRateW(list, page, total = false)),
      "WC" -> (() => byRateW(list, page, total = true)),
      "D" -> (() => byRateD(list, page)),
      "DK" -> (() => byRateDK(list, page))
    ))
    def fileListCase(label: String, list: RoundList) = Case(label, Seq(
      "A" -> (() => fileListA(list, 1, withCount = true)),
      "K" -> (() => fileListA(list, 1, withCount = false)),
      "W" -> (() => fileListW(list, 1, total = false)),
      "WC" -> (() => fileListW(list, 1, total = true)),
      "WCn" -> (() => fileListW(list, 1, total = true, narrow = true))
    ))
    def jurorCase(label: String, list: RoundList) = Case(label, Seq(
      "A" -> (() => byRateA(list, 1, withCount = false)),
      "W" -> (() => jurorW(list, 1))
    ))
    val jr = byRate(rated, user = juror)
    val jb = byRate(binary, ListOrder.Binary(BinaryOrder.Default), user = bJuror)
    val all = Seq(
      byRateCase("rated-byrate-p1", r2, 1),
      byRateCase(s"rated-byrate-p$deep", r2, deep),
      byRateCase("binary-byrate-p1", b1, 1),
      byRateCase(s"binary-byrate-p$deep", b1, deep),
      fileListCase("rated-filelist-p1", fileList(rated)),
      fileListCase("binary-filelist-p1", fileList(binary)),
      jurorCase("juror-rated-byrate-p1", jr),
      jurorCase("juror-binary-byrate-p1", jb)
    ).filter(c => cases.findFirstIn(c.label).isDefined)

    val csv = new PrintWriter(new File(outDir, "samples.csv"), "UTF-8")
    csv.println("case,variant,sample,position,ms,parts")
    say("")
    say(f"${"case"}%-26s ${"var"}%-4s ${"median"}%8s ${"min"}%8s ${"max"}%8s ${"Δ vs A med"}%11s ${"Δ min"}%8s ${"Δ max"}%8s  per-query medians")
    all.foreach { c =>
      val ss = run(c)
      ss.foreach(s => csv.println(f"${s.label},${s.variant},${s.sample},${s.position},${s.view.ms}%.2f," +
        s.view.parts.map { case (k, v) => f"$k=$v%.2f" }.mkString(";")))
      csv.flush()
      val a = ss.filter(_.variant == "A").sortBy(_.sample).map(_.view.ms)
      c.variants.map(_._1).foreach { v =>
        val xs = ss.filter(_.variant == v).sortBy(_.sample)
        val ms = xs.map(_.view.ms)
        val deltas = ms.zip(a).map { case (x, y) => x - y }
        val parts = xs.flatMap(_.view.parts).groupBy(_._1).map { case (k, p) => k -> median(p.map(_._2)) }
        val order = xs.head.view.parts.map(_._1).distinct
        say(f"${c.label}%-26s $v%-4s ${median(ms)}%8.1f ${ms.min}%8.1f ${ms.max}%8.1f ${median(deltas)}%+11.1f " +
          f"${deltas.min}%+8.1f ${deltas.max}%+8.1f  " + order.map(k => f"$k ${parts(k)}%.1f").mkString(", "))
      }
    }
    csv.close()

    // 3. the plans
    if (prop("explain", "true").toBoolean) {
      val json = new PrintWriter(new File(outDir, "analyze.json.txt"), "UTF-8")
      say("")
      val p1 = paged(r2, 1)
      val b = paged(b1, 1)
      val fl = fileList(rated)
      val flq = paged(fl, 1)
      val plans = Seq(
        "rated count" -> r2.query.query(count = true),
        "rated page (A)" -> p1.query(),
        "rated page W" -> wPageSql(r2, p1, total = false),
        "rated page WC" -> wPageSql(r2, p1, total = true),
        "rated page D" -> dPageSql(r2, p1),
        "rated page DK (no windows)" -> dPageSql(r2, p1, ranked = false),
        "binary page D" -> dPageSql(b1, b),
        "binary page DK (no windows)" -> dPageSql(b1, b, ranked = false),
        "rated deep page (A)" -> paged(r2, deep).query(),
        "rated deep page WC" -> wPageSql(r2, paged(r2, deep), total = true),
        "binary page (A)" -> b.query(),
        "binary page WC" -> wPageSql(b1, b, total = true),
        "file list ids (A)" -> idQuery(flq).query(idOnly = true),
        "file list ids WC" -> wIdSql(fl, flq, total = true),
        "file list ids WCn" -> wIdNarrowSql(fl, flq),
        "juror page (A)" -> paged(jr, 1).query(),
        "juror page W" -> wJurorSql(jr, paged(jr, 1))
      )
      // keyCounts' SQL isn't exposed: the same text as SelectionQuery.keyCounts builds
      val kcSql = SQLSyntax.createUnsafely("select k0, count(*) from (select sum(s.rate) as k0 from selection s") +
        r2.query.where() + SQLSyntax.createUnsafely(" group by s.page_id ) t group by k0 order by k0 desc")
      (plans :+ ("rated keyCounts" -> kcSql)).foreach { case (n, s) => say(analyze(n, s, json)) }
      json.close()
    }
    log.close()
    println(s"[ranks] written $outDir")
  }
}
