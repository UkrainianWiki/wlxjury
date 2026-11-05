package gatling.setup

import scalikejdbc._

import java.io.{File, PrintWriter}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** V91's cost on the Gatling fixture DB (docs/plans/2026-11-02-round-images-two-step.md §18):
  * its rollback SQL (both columns back to nullable, metadata only), then V91's statements
  * timed one by one (the UPDATEs of NULL rates, the online rebuild of `vote`, then of
  * `vote_aside`), `bench.runs` times; and whether the columns end NOT NULL DEFAULT 0. The fixture
  * is built at the latest migration, so each run starts from the rollback. Run with SQL logging
  * off and the fixture's recompute off:
  * {{{
  * sbt 'set Test / javaOptions ++= Seq("-Xmx1024m", "-Dfile.encoding=UTF-8", "-Dgatling.roundCounts.mode=manual",
  *        "-Dscalikejdbc.global.loggingSQLAndTime.enabled=false")' \
  *     'Test/runMain gatling.setup.VoteRateNotNullBenchmark'
  * }}}
  * Results to target/gatling/vote-rate-not-null-<stamp>.txt. Not a spec. Production needs its own
  * timing on a copy of its size.
  */
object VoteRateNotNullBenchmark {
  private val runs = sys.props.getOrElse("bench.runs", "3").toInt
  private val stamp = LocalDateTime.now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

  implicit private val session: DBSession = AutoSession

  private val rollback = Seq(
    "ALTER TABLE vote MODIFY rate INT NULL DEFAULT NULL, ALGORITHM = INSTANT",
    "ALTER TABLE vote_aside MODIFY rate INT NULL DEFAULT NULL, ALGORITHM = INSTANT")

  private val v91: Seq[String] =
    new String(java.nio.file.Files.readAllBytes(
      java.nio.file.Paths.get("conf/db/migration/default/V91__Vote_rate_not_null.sql")), "UTF-8")
      .linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n").split(";").map(_.trim).filter(_.nonEmpty).toSeq

  private def ms(f: => Unit): Double = { val t0 = System.nanoTime(); f; (System.nanoTime() - t0) / 1e6 }

  def main(args: Array[String]): Unit = {
    val status = try { body() } catch { case e: Throwable => e.printStackTrace(); 1 }
    sys.exit(status)
  }

  private def body(): Int = {
    require(!GatlingConfig.external, "Use the disposable fixture, never an external target")
    GatlingTestFixture.roundRatingId // restores (or builds) the fixture DB
    GlobalSettings.loggingSQLAndTime = LoggingSQLAndTimeSettings(enabled = false)
    new File("target/gatling").mkdirs()
    val log = new PrintWriter(new File(s"target/gatling/vote-rate-not-null-$stamp.txt"), "UTF-8")
    def say(line: String): Unit = { println(s"[v91] $line"); log.println(line); log.flush() }
    def count(table: String) = SQL(s"SELECT COUNT(*) FROM $table").map(_.long(1)).single().get
    def columns = sql"""SELECT TABLE_NAME, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('vote', 'vote_aside') AND COLUMN_NAME = 'rate'
                        ORDER BY TABLE_NAME""".map(rs => s"${rs.string(1)} ${rs.string(2)} ${rs.string(3)}").list()
    try {
      val version = sql"SELECT VERSION()".map(_.string(1)).single().get
      val mode = sql"SELECT @@SESSION.sql_mode".map(_.string(1)).single().get
      say(s"VoteRateNotNullBenchmark $stamp: MariaDB $version, sql_mode $mode; vote ${count("vote")} rows, " +
        s"vote_aside ${count("vote_aside")} rows; $runs runs; SQL logging off")
      val results = (1 to runs).map { run =>
        val back = rollback.map(st => ms(SQL(st).execute.apply()))
        val forward = v91.map(st => ms(SQL(st).execute.apply()))
        say(s"run $run: rollback ${back.map(t => f"$t%.0f").mkString(" / ")} ms; V91 ${forward.map(t => f"$t%.0f").mkString(" / ")} ms " +
          s"(${v91.size} statements: UPDATE vote, UPDATE vote_aside, SET, vote's ALTER, SET, vote_aside's ALTER, SET)")
        forward
      }
      say(s"after: ${columns.mkString(", ")}")
      say(s"V91 total per run: ${results.map(r => f"${r.sum}%.0f").mkString(", ")} ms")
      if (columns == List("vote NO 0", "vote_aside NO 0")) 0 else 2
    } finally log.close()
  }
}
