package services.series

import org.intracer.wmua.ContestJury
import play.api.libs.json.Json
import scalikejdbc._

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}

/** The reuse scan over a production copy, read-only (contest series plan §4.7, CY-0): what the
  * `contest_reuse_scan` job does (every contest's era sample, then every contest's report), run
  * against a copy at a schema before V109 (no series, no `contest_jury.series_id`), so the contests
  * are read with the columns every version has and passed in ([[ReuseReport.Catalog]]). The session
  * is READ ONLY on the server (the pool's init SQL, checked): any write fails. Not a spec; run by hand:
  *
  * {{{
  * WLXJURY_COPY_URL=jdbc:mariadb://127.0.0.1:3318/wlxjury WLXJURY_COPY_USER=root WLXJURY_COPY_PASSWORD=... \
  *   sbt "Test/runMain services.series.ReuseCopyHarness target/reuse-copy"
  * }}}
  *
  * Writes `summaries.json` (every contest's summary and its step time), `eras.json`, and
  * `contest-<id>.json` (the DiagnosticsJson form) of each contest reported, under the directory given.
  */
object ReuseCopyHarness {

  def main(args: Array[String]): Unit = {
    val out = Paths.get(args.headOption.getOrElse("target/reuse-copy"))
    Files.createDirectories(out)
    val config = new com.zaxxer.hikari.HikariConfig()
    config.setJdbcUrl(sys.env("WLXJURY_COPY_URL"))
    config.setUsername(sys.env("WLXJURY_COPY_USER"))
    config.setPassword(sys.env("WLXJURY_COPY_PASSWORD"))
    config.setMaximumPoolSize(1)
    config.setReadOnly(true)
    // every connection READ ONLY on the server before its first use: any write fails
    config.setConnectionInitSql("SET SESSION TRANSACTION READ ONLY")
    val dataSource = new com.zaxxer.hikari.HikariDataSource(config)
    ConnectionPool.singleton(new DataSourceConnectionPool(dataSource, closer = new DataSourceCloser {
      def close(): Unit = dataSource.close()
    }))
    GlobalSettings.loggingSQLAndTime = LoggingSQLAndTimeSettings(enabled = false)
    val settings = ReuseReport.Settings()
    try {
      DB.readOnly { implicit s =>
        // a read-only session (ScalikeJDBC refuses updates in it) on a connection READ ONLY on the server
        require(sql"SELECT @@session.tx_read_only".map(_.int(1)).single.apply().contains(1), "the session is not read-only")
        val contests = sql"SELECT id, name, year, country, images, category_id, campaign FROM contest_jury ORDER BY id"
          .map(rs => ContestJury(Some(rs.long(1)), rs.string(2), rs.int(3), rs.stringOpt(4).getOrElse(""), rs.stringOpt(5),
            rs.longOpt(6), campaign = rs.stringOpt(7)))
          .list.apply()
        val catalog = ReuseReport.Catalog(contests, Nil)
        val t0 = System.nanoTime()
        val samples = contests.flatMap(c => ReuseReport.eraSample(c, settings))
        val eras = ReuseReport.Eras.of(samples)
        val erasMs = (System.nanoTime() - t0) / 1000000
        Files.write(out.resolve("eras.json"), Json.prettyPrint(Json.toJson(eras.years.map(y =>
          Json.obj("year" -> y.year, "page" -> y.page, "round" -> y.round)))).getBytes(UTF_8))
        val lines = contests.map { c =>
          val start = System.nanoTime()
          val report = ReuseReport.report(c, eras, settings, Some(catalog))
          val ms = (System.nanoTime() - start) / 1000000
          val summary = ReuseReport.summary(report)
          if (summary.verdict != ReuseReport.Verdict.Clean)
            Files.write(out.resolve(s"contest-${c.getId}.json"),
              Json.prettyPrint(Json.toJson(api.DiagnosticsJson.reuseContest(report))).getBytes(UTF_8))
          Json.obj("ms" -> ms, "summary" -> Json.toJson(summary))
        }
        Files.write(out.resolve("summaries.json"), Json.prettyPrint(Json.obj("erasMs" -> erasMs, "samples" -> samples.size,
          "contests" -> lines)).getBytes(UTF_8))
        println(s"eras: ${samples.size} samples in $erasMs ms; ${contests.size} contests reported to $out")
      }
    } finally ConnectionPool.closeAll()
  }
}
