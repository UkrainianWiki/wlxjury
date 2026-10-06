package gatling.setup

import scalikejdbc._

/** Prints the query plans (EXPLAIN, and ANALYZE where it's cheap enough) of the
  * selection queries behind the slow pages, on the Gatling fixture DB.
  *
  * Both the queries before and after the October 2026 performance work are listed
  * (docs/plans/2026-10-05-performance-and-memory.md), as literal SQL, so a run against
  * an older schema shows the old plans too.
  *
  * Run: sbt "Test/runMain gatling.setup.ExplainQueries"
  * (restores or builds the fixture DB first, like a Gatling simulation)
  */
object ExplainQueries {

  def main(args: Array[String]): Unit = {
    val f = GatlingTestFixture
    implicit val session: DBSession = AutoSession

    val binary = f.roundBinaryId
    val rated  = f.roundRatingId
    val juror  = f.jurors(11)._1 // the plan's examples use juror 12
    val region = "07"

    val indexes = sql"SHOW INDEX FROM selection".map(rs => rs.string("Key_name")).list().distinct
    println(s"[explain] selection indexes: ${indexes.mkString(", ")}")
    println(s"[explain] binary round $binary, rated round $rated, juror $juror, region $region")

    val galleryOrder = "ORDER BY s.rate DESC, s.monument_id ASC, s.page_id ASC"

    val statements: Seq[(String, String, Boolean)] = Seq(
      // (title, sql, also ANALYZE)
      ("T4 old roundRateStat",
        s"""SELECT rate, count(1) FROM (SELECT DISTINCT s.page_id, s.rate FROM selection s
            WHERE s.round_id = $binary) t GROUP BY rate""", true),
      ("T4 new selectedImageCount",
        s"SELECT COUNT(DISTINCT s.page_id) FROM selection s WHERE s.round_id = $binary AND s.rate = 1", true),
      ("T4 total (COUNT DISTINCT of a round)",
        s"SELECT COUNT(DISTINCT s.page_id) FROM selection s WHERE s.round_id = $binary", true),
      ("T5 old roundsStat",
        s"""SELECT r.id, count(DISTINCT s.page_id) FROM rounds r JOIN selection s ON r.id = s.round_id
            WHERE r.contest_id = ${f.contestId} GROUP BY r.id LIMIT 2""", true),
      ("T5 new imageCountByRounds",
        s"""SELECT s.round_id, COUNT(DISTINCT s.page_id) FROM selection s
            WHERE s.round_id IN ($binary, $rated) GROUP BY s.round_id""", true),
      ("T6 old findByRoundSelection",
        s"""SELECT u.* FROM users u JOIN selection s ON u.id = s.jury_id
            WHERE s.round_id = $binary GROUP BY u.id ORDER BY u.id""", true),
      ("T6 new findByRoundSelection (jury ids)",
        s"SELECT DISTINCT jury_id FROM selection WHERE round_id = $binary", true),
      ("T7 edit page's imagesByRound (byRoundMerged of the round)",
        s"""SELECT sum(s.rate), count(s.rate), i.* FROM selection s STRAIGHT_JOIN images i
            ON i.page_id = s.page_id WHERE s.round_id = $binary GROUP BY s.page_id ORDER BY 1 DESC""", true),
      ("T9 old gallery page",
        s"""SELECT i.*, s.* FROM selection s STRAIGHT_JOIN images i ON i.page_id = s.page_id
            WHERE s.jury_id = $juror AND s.round_id = $rated $galleryOrder LIMIT 15 OFFSET 60""", true),
      ("T9 new gallery page (deferred join)",
        s"""SELECT i.*, s.* FROM (SELECT s.id FROM selection s
            STRAIGHT_JOIN images i ON i.page_id = s.page_id WHERE s.jury_id = $juror AND s.round_id = $rated $galleryOrder LIMIT 15 OFFSET 60) k
            STRAIGHT_JOIN selection s ON s.id = k.id
            STRAIGHT_JOIN images i ON i.page_id = s.page_id $galleryOrder""", true),
      ("T9 old region gallery page",
        s"""SELECT i.*, s.* FROM selection s STRAIGHT_JOIN images i ON i.page_id = s.page_id
            WHERE s.jury_id = $juror AND s.round_id = $rated AND i.monument_id LIKE '$region%'
            $galleryOrder LIMIT 15 OFFSET 0""", true),
      ("T9 new region gallery page (deferred join)",
        s"""SELECT i.*, s.* FROM (SELECT s.id FROM selection s
            STRAIGHT_JOIN images i ON i.page_id = s.page_id WHERE s.jury_id = $juror AND s.round_id = $rated AND s.monument_id LIKE '$region%'
            $galleryOrder LIMIT 15 OFFSET 0) k
            STRAIGHT_JOIN selection s ON s.id = k.id
            STRAIGHT_JOIN images i ON i.page_id = s.page_id $galleryOrder""", true),
      ("T9 old gallery count",
        s"SELECT COUNT(DISTINCT s.page_id) FROM selection s WHERE s.jury_id = $juror AND s.round_id = $rated", true),
      ("T9 new gallery count",
        s"SELECT COUNT(*) FROM selection s WHERE s.jury_id = $juror AND s.round_id = $rated", true),
      ("T9 old region gallery count",
        s"""SELECT count(t.pi_on_i) FROM (SELECT i.page_id AS pi_on_i FROM selection s
            STRAIGHT_JOIN images i ON i.page_id = s.page_id
            WHERE s.jury_id = $juror AND s.round_id = $rated AND i.monument_id LIKE '$region%') t""", true),
      ("T9 new region gallery count",
        s"""SELECT COUNT(*) FROM selection s
            WHERE s.jury_id = $juror AND s.round_id = $rated AND s.monument_id LIKE '$region%'""", true),
      ("T9 byRegionStat (juror)",
        s"""SELECT DISTINCT m.adm0 FROM selection s JOIN monument m ON m.id = s.monument_id
            WHERE m.adm0 IS NOT NULL AND s.jury_id = $juror AND s.round_id = $rated""", true)
    )

    statements.foreach { case (title, stmt, analyze) =>
      val oneLine = stmt.replaceAll("\\s+", " ").trim
      println(s"\n[explain] ==== $title\n[explain] $oneLine")
      try {
        val rows = SQL(s"EXPLAIN $oneLine").map(_.toMap()).list()
        rows.foreach { r =>
          val cols = Seq("id", "select_type", "table", "type", "possible_keys", "key", "key_len", "ref", "rows", "Extra")
          println("[explain]   " + cols.map(c => s"$c=${r.getOrElse(c, "")}").mkString(" | "))
        }
        if (analyze) {
          val t0 = System.nanoTime()
          SQL(oneLine).map(_ => ()).list()
          val warmMs = (System.nanoTime() - t0) / 1000000
          val json = SQL(s"ANALYZE FORMAT=JSON $oneLine").map(_.string(1)).single().getOrElse("")
          val time = """"r_total_time_ms":\s*([\d.]+)""".r.findFirstMatchIn(json).map(_.group(1)).getOrElse("?")
          val rRows = """"r_rows":\s*([\d.]+)""".r.findAllMatchIn(json).map(_.group(1)).mkString(",")
          val filesort = json.contains("filesort")
          val temporary = json.contains("temporary_table")
          println(s"[explain]   ANALYZE: r_total_time_ms=$time r_rows=[$rRows] filesort=$filesort " +
            s"temporary_table=$temporary (warm run ${warmMs}ms)")
        }
      } catch {
        case e: Exception => println(s"[explain]   FAILED: ${e.getMessage}")
      }
    }
    sys.exit(0)
  }
}
