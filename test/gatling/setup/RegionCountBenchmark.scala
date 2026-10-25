package gatling.setup

import scalikejdbc._

import java.io.{File, PrintWriter}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.{Callable, Executors, TimeUnit}
import scala.jdk.CollectionConverters._

/** Times the region count queries (docs/plans/2026-10-25-region-improvements.md, item 4) on
  * the Gatling fixture DB (D1: 38,367 files in the contest's category, 20 jurors, a binary and
  * a rated round, 10 jurors per image), and optionally on a category three times as large
  * (D2, `-Dbench.d2=true`).
  *
  * Per query: `bench.warmup` runs, then `bench.runs` timed ones (or as many as fit in
  * `bench.maxSeconds`) with each of `bench.threads` threads; p50/p95/p99/max. Then one
  * `ANALYZE FORMAT=JSON` and the `Handler_read%` deltas of one run.
  *
  * It marks 1% of the files deleted on Commons (`MOD(page_id, 100) = 0`) for the deleted
  * lists' queries: on the fixture container only, after its dump is restored or saved, so
  * the change doesn't persist. D2's copies are added the same way.
  *
  * Not a spec: timings in CI are noise. Run, as bench/README.md §1-2 says (mains power,
  * nothing else heavy running), and compare only runs made back to back:
  * {{{
  * sbt "Test/runMain gatling.setup.RegionCountBenchmark"
  * sbt -Dbench.d2=true -Dbench.threads=1,5,20 "Test/runMain gatling.setup.RegionCountBenchmark"
  * }}}
  * Writes target/gatling/region-counts-<timestamp>.txt and .json.
  */
object RegionCountBenchmark {

  private def prop(name: String, default: String): String = sys.props.getOrElse(s"bench.$name", default)

  private val warmup = prop("warmup", "20").toInt
  private val runs = prop("runs", "100").toInt
  private val maxSeconds = prop("maxSeconds", "60").toInt
  private val threadCounts = prop("threads", "1,5").split(",").map(_.trim.toInt).toSeq
  private val d2 = prop("d2", "false").toBoolean
  private val only = prop("only", "").split(",").map(_.trim).filter(_.nonEmpty).toSet

  /** A query to time: `run` executes it once on the session, for the n-th run. */
  final case class Bench(id: String, dataset: String, title: String, sql: Int => String)(val run: (DBSession, Int) => Unit)

  final case class Timing(id: String, dataset: String, title: String, threads: Int, n: Int, p50: Double, p95: Double,
      p99: Double, max: Double, mean: Double)

  private def regionOf(col: String) = db.scalikejdbc.rewrite.ImageDbNew.regionOf(col)

  /** A literal query, read to the end. */
  private def literal(id: String, dataset: String, title: String)(sql: Int => String): Bench =
    Bench(id, dataset, title, sql) { (s, n) =>
      implicit val session: DBSession = s
      SQL(sql(n)).map(_ => ()).list(): Unit
    }

  def main(args: Array[String]): Unit = {
    val f = GatlingTestFixture
    implicit val session: DBSession = AutoSession

    val categoryId = sql"SELECT category_id FROM contest_jury WHERE id = ${f.contestId}".map(_.longOpt(1)).single().flatten
      .getOrElse(sys.error("the fixture contest has no category"))
    val binary = f.roundBinaryId
    val rated = f.roundRatingId
    val jurors = f.jurors.map(_._1).toIndexedSeq

    // 1% of the files deleted on Commons, for the deleted lists (throwaway container only)
    val deleted = sql"UPDATE images SET deleted_at = NOW(3) WHERE MOD(page_id, 100) = 0 AND deleted_at IS NULL".update()
    println(s"[bench] marked $deleted files deleted")

    val d2Category = if (d2) Some(buildD2(categoryId)) else None

    def categorySql(cat: Long): Seq[Bench] = {
      val ds = if (d2Category.contains(cat)) "D2" else "D1"
      Seq(
        literal("Q1", ds, "category region counts (all files)") { _ =>
          s"""SELECT ${regionOf("r.monument_id")} AS region, COUNT(*) FROM category_members m
              STRAIGHT_JOIN images r FORCE INDEX (images_page_monument_idx) ON r.page_id = m.page_id
              WHERE m.category_id = $cat GROUP BY region"""
        },
        literal("Q1c", ds, "categoryFileCounts (all and deleted COUNTs)") { _ =>
          s"""SELECT (SELECT COUNT(*) FROM category_members WHERE category_id = $cat),
                     (SELECT COUNT(*) FROM images i STRAIGHT_JOIN category_members cm
                        ON cm.category_id = $cat AND cm.page_id = i.page_id WHERE i.deleted_at IS NOT NULL)"""
        },
        literal("Q2", ds, "deleted list region counts + deleted count (1% deleted)") { _ =>
          s"""SELECT ${regionOf("i.monument_id")} AS region, COUNT(*) FROM images i
              STRAIGHT_JOIN category_members cm ON cm.category_id = $cat AND cm.page_id = i.page_id
              WHERE i.deleted_at IS NOT NULL GROUP BY region"""
        }
      )
    }

    val r = regionOf("s.monument_id")
    def juror(n: Int) = jurors(n % jurors.size)
    def round(n: Int) = if ((n / jurors.size) % 2 == 0) binary else rated

    val selectionSql: Seq[Bench] = Seq(
      literal("Q4j", "D1", "today's byRegionStat DISTINCT, a juror (every juror, both rounds)") { n =>
        s"SELECT DISTINCT $r FROM selection s WHERE $r IS NOT NULL AND s.jury_id = ${juror(n)} AND s.round_id = ${round(n)}"
      },
      literal("Q5", "D1", "juror region counts, every rate filter in one scan") { n =>
        s"""SELECT $r AS region, COUNT(*), SUM(s.rate = 1), SUM(s.rate = -1), SUM(s.rate = 0), SUM(s.rate > 0)
            FROM selection s WHERE s.jury_id = ${juror(n)} AND s.round_id = ${round(n)} GROUP BY region"""
      },
      literal("Q4r", "D1", "today's byRegionStat DISTINCT, the whole round (binary, rated)") { n =>
        s"SELECT DISTINCT $r FROM selection s WHERE $r IS NOT NULL AND s.round_id = ${if (n % 2 == 0) binary else rated}"
      },
      literal("Q6a", "D1", "whole round, COUNT(DISTINCT page_id) on selection") { n =>
        s"""SELECT $r AS region, COUNT(DISTINCT s.page_id) FROM selection s
            WHERE s.round_id = ${if (n % 2 == 0) binary else rated} GROUP BY region"""
      },
      literal("Q6b", "D1", "whole round, distinct pages joined to images (page_id, monument_id)") { n =>
        s"""SELECT ${regionOf("i.monument_id")} AS region, COUNT(*)
            FROM (SELECT DISTINCT page_id FROM selection WHERE round_id = ${if (n % 2 == 0) binary else rated}) p
            JOIN images i FORCE INDEX (images_page_monument_idx) ON i.page_id = p.page_id GROUP BY region"""
      },
      literal("Q6b+", "D1", "Q6b with rate > 0 (organiser's rated filter)") { n =>
        s"""SELECT ${regionOf("i.monument_id")} AS region, COUNT(*)
            FROM (SELECT DISTINCT page_id FROM selection WHERE round_id = ${if (n % 2 == 0) binary else rated}
                  AND rate > 0) p
            JOIN images i FORCE INDEX (images_page_monument_idx) ON i.page_id = p.page_id GROUP BY region"""
      }
    )

    val benches = (categorySql(categoryId) ++ d2Category.toSeq.flatMap(categorySql) ++ selectionSql ++ extra(categoryId))
      .filter(b => only.isEmpty || only.contains(b.id))

    val out = new StringBuilder
    def log(line: String): Unit = { println(s"[bench] $line"); out ++= line + "\n" }

    log(s"RegionCountBenchmark ${LocalDateTime.now}: warmup $warmup, runs $runs (max ${maxSeconds}s), threads " +
      s"${threadCounts.mkString(",")}; category $categoryId (${count(s"SELECT COUNT(*) FROM category_members WHERE category_id = $categoryId")} files)" +
      d2Category.fold("")(c => s", D2 category $c (${count(s"SELECT COUNT(*) FROM category_members WHERE category_id = $c")} files)") +
      s"; selection ${count("SELECT COUNT(*) FROM selection")} rows; rounds binary $binary, rated $rated; ${jurors.size} jurors")
    log(f"${"id"}%-5s ${"ds"}%-3s ${"thr"}%3s ${"n"}%5s ${"p50"}%8s ${"p95"}%8s ${"p99"}%8s ${"max"}%8s ${"mean"}%8s  title")

    val timings = for {
      b <- benches
      threads <- threadCounts
    } yield {
      val t = time(b, threads)
      log(f"${t.id}%-5s ${t.dataset}%-3s ${t.threads}%3d ${t.n}%5d ${t.p50}%8.1f ${t.p95}%8.1f ${t.p99}%8.1f ${t.max}%8.1f ${t.mean}%8.1f  ${t.title}")
      t
    }

    log("")
    benches.foreach { b =>
      val plan = analyze(b.sql(0))
      val handlers = handlerReads(b)
      log(s"${b.id} ${b.dataset}: $plan; $handlers")
    }

    val stamp = LocalDateTime.now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val dir = new File("target/gatling")
    dir.mkdirs()
    write(new File(dir, s"region-counts-$stamp.txt"), out.toString)
    write(new File(dir, s"region-counts-$stamp.json"), timings.map { t =>
      s"""{"id":"${t.id}","dataset":"${t.dataset}","threads":${t.threads},"n":${t.n},"p50":${t.p50},"p95":${t.p95},""" +
        s""""p99":${t.p99},"max":${t.max},"mean":${t.mean},"title":"${t.title.replace("\"", "'")}"}"""
    }.mkString("[\n", ",\n", "\n]\n"))
    println(s"[bench] written target/gatling/region-counts-$stamp.txt")
    sys.exit(0)
  }

  /** Queries of the app's own methods, added once they exist. */
  private def extra(categoryId: Long): Seq[Bench] = Nil

  private def count(sql: String)(implicit session: DBSession): Long = SQL(sql).map(_.long(1)).single().getOrElse(0L)

  /** D2: a category with three copies of each of the contest's files (page ids + k * 10^10,
    * titles suffixed, the same monument ids and deleted marks).
    */
  private def buildD2(categoryId: Long)(implicit session: DBSession): Long = {
    val columns = sql"""SELECT column_name FROM information_schema.columns
                        WHERE table_schema = DATABASE() AND table_name = 'images' ORDER BY ordinal_position"""
      .map(_.string(1)).list()
    val d2 = sql"INSERT INTO category (title) VALUES ('Category:Region count benchmark D2')".updateAndReturnGeneratedKey()
    (0 to 2).foreach { k =>
      val offset = k * 10000000000L
      if (k > 0) {
        val select = columns.map {
          case "page_id" => s"page_id + $offset"
          case "title"   => s"CONCAT(title, ' (copy $k)')"
          case c         => c
        }
        SQL(s"""INSERT INTO images (${columns.mkString(", ")})
                SELECT ${select.mkString(", ")} FROM images i
                WHERE EXISTS (SELECT 1 FROM category_members m WHERE m.category_id = $categoryId AND m.page_id = i.page_id)""")
          .update()
      }
      SQL(s"""INSERT INTO category_members (category_id, page_id)
              SELECT $d2, page_id + $offset FROM category_members WHERE category_id = $categoryId""").update()
    }
    sql"ANALYZE TABLE images, category_members".map(_ => ()).list()
    d2
  }

  private def time(b: Bench, threads: Int): Timing = {
    val pool = Executors.newFixedThreadPool(threads)
    try {
      // warm-up on one connection per thread
      runOn(pool, threads, warmup, deadline = Long.MaxValue, b)
      val deadline = System.nanoTime() + maxSeconds * 1000000000L
      val ms = runOn(pool, threads, runs, deadline, b).sorted
      def pct(p: Double) = if (ms.isEmpty) 0.0 else ms(math.min(ms.size - 1, math.ceil(p * ms.size).toInt - 1))
      Timing(b.id, b.dataset, b.title, threads, ms.size, pct(0.5), pct(0.95), pct(0.99), ms.lastOption.getOrElse(0.0),
        if (ms.isEmpty) 0.0 else ms.sum / ms.size)
    } finally {
      pool.shutdown()
      pool.awaitTermination(1, TimeUnit.MINUTES)
    }
  }

  /** `total` runs of `b` shared by `threads` threads, each on its own connection, stopping at
    * the deadline: their times in ms.
    */
  private def runOn(pool: java.util.concurrent.ExecutorService, threads: Int, total: Int, deadline: Long, b: Bench)
      : Seq[Double] = {
    val next = new java.util.concurrent.atomic.AtomicInteger
    val tasks = (0 until threads).map { _ =>
      new Callable[Seq[Double]] {
        def call(): Seq[Double] = DB.readOnly { s =>
          val times = Seq.newBuilder[Double]
          var n = next.getAndIncrement()
          while (n < total && System.nanoTime() < deadline) {
            val t0 = System.nanoTime()
            b.run(s, n)
            times += (System.nanoTime() - t0) / 1e6
            n = next.getAndIncrement()
          }
          times.result()
        }
      }
    }
    pool.invokeAll(tasks.asJava).asScala.toSeq.flatMap(_.get())
  }

  private def analyze(sql: String)(implicit session: DBSession): String =
    try {
      val oneLine = sql.replaceAll("\\s+", " ").trim
      val json = SQL(s"ANALYZE FORMAT=JSON $oneLine").map(_.string(1)).single().getOrElse("")
      val time = """"r_total_time_ms":\s*([\d.]+)""".r.findFirstMatchIn(json).map(_.group(1)).getOrElse("?")
      val keys = """"key":\s*"([^"]+)"""".r.findAllMatchIn(json).map(_.group(1)).toSeq.distinct
      val rows = """"r_rows":\s*([\d.]+)""".r.findAllMatchIn(json).map(_.group(1)).mkString(",")
      val indexOnly = json.contains("\"using_index\": true")
      s"ANALYZE ${time}ms keys=[${keys.mkString(",")}] r_rows=[$rows] using_index=$indexOnly " +
        s"filesort=${json.contains("filesort")} temporary=${json.contains("temporary_table")}"
    } catch { case e: Exception => s"ANALYZE failed: ${e.getMessage}" }

  /** The `Handler_read%` counters one run of `b` adds, on one connection. */
  private def handlerReads(b: Bench): String = DB.readOnly { implicit s =>
    def read(): Map[String, Long] =
      SQL("SHOW SESSION STATUS LIKE 'Handler_read%'").map(rs => rs.string(1) -> rs.long(2)).list().toMap
    val before = read()
    b.run(s, 0)
    val after = read()
    after.toSeq.sortBy(_._1).map { case (k, v) => k.stripPrefix("Handler_read_") -> (v - before.getOrElse(k, 0L)) }
      .filter(_._2 != 0).map { case (k, v) => s"$k=$v" }.mkString("handler reads: ", " ", "")
  }

  private def write(file: File, text: String): Unit = {
    val w = new PrintWriter(file, "UTF-8")
    try w.write(text) finally w.close()
  }
}
