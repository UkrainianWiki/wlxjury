package db

import db.scalikejdbc.{Round, SharedTestDb, TestDb}
import org.intracer.wmua.ContestJury
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import _root_.scalikejdbc._

import java.nio.file.{Files, Paths}

/** V105: `round.status` (round lineage §2.6, built as P3-2 of the round-editing plan), its CHECK,
  * and the backfill from the flags and the round's latest round_distribution job: a row of the
  * backfill's table each, and its rerun (after a rollback and forward again) leaving a `cancelled`
  * round alone. The shared database has V105 already; the script is rerun on rounds whose status
  * is cleared, as a database before V105 has them.
  */
class RoundStatusMigrationSpec extends Specification with TestDb with BeforeAll with BeforeEach {
  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private val Script = "conf/db/migration/default/V105__Round_status.sql"

  private def statements: Seq[String] =
    new String(Files.readAllBytes(Paths.get(Script)), "UTF-8").linesIterator
      .filterNot(_.trim.startsWith("--")).mkString("\n")
      .split(";").map(_.trim).filter(_.nonEmpty).toSeq

  private def migrate(): Unit = DB.autoCommit { implicit s => statements.foreach(st => SQL(st).execute.apply()) }

  private def exec(statement: SQLSyntax): Unit = DB.autoCommit(implicit s => sql"$statement".update(): Unit)

  /** A round with these flags and no status, as before V105. */
  private def round(n: Int, distributing: Boolean = false, active: Boolean = false, frozenActive: Boolean = false)(
      implicit contest: ContestJury): Long = {
    val id = roundDao.create(Round(None, n, Some(s"R$n"), contest.getId, active = active, distributing = distributing)).getId
    if (frozenActive) exec(sqls"UPDATE round SET active = 0, freeze_count = 1, active_before_freeze = 1 WHERE id = $id")
    exec(sqls"UPDATE round SET status = NULL, status_changed_at = NULL WHERE id = $id")
    id
  }

  private def job(roundId: Long, status: String, params: String): Unit =
    exec(sqls"""INSERT INTO job (kind, subject_type, subject_id, status, params, state)
                VALUES ('round_distribution', 'round', $roundId, $status, $params, '{}')""")

  private def statusOf(id: Long): Option[String] =
    DB.readOnly(implicit s => sql"SELECT status FROM round WHERE id = $id".map(_.stringOpt(1)).single().flatten)

  "V105" should {

    "add the columns and a CHECK of the five values" in {
      val columns = DB.readOnly { implicit s =>
        sql"""SELECT COLUMN_NAME, IS_NULLABLE FROM information_schema.columns
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'round'
                AND COLUMN_NAME IN ('status', 'status_changed_at', 'status_changed_by') ORDER BY COLUMN_NAME"""
          .map(rs => rs.string(1) -> rs.string(2)).list()
      }
      implicit val contest: ContestJury = createContests(1).head
      val r = round(1)
      (columns === List("status" -> "YES", "status_changed_at" -> "YES", "status_changed_by" -> "YES")) and
        (exec(sqls"UPDATE round SET status = 'open' WHERE id = $r") must throwA[java.sql.SQLException]) and
        (exec(sqls"UPDATE round SET status = 'Paused' WHERE id = $r") must throwA[java.sql.SQLException])
    }

    "backfill each row of its table, and leave a cancelled round alone when rerun" in {
      implicit val contest: ContestJury = createContests(1).head
      val running = round(1, distributing = true)
      job(running, "running", """{"roundId": 0, "completingCreation": true}""")
      val failed = round(2, distributing = true)
      job(failed, "done", """{"roundId": 0}""")
      job(failed, "failed", """{"roundId": 0, "completingCreation": true}""")
      val cancelled = round(3, distributing = true)
      job(cancelled, "cancelled", """{"roundId": 0, "completingCreation": true}""")
      val noJob = round(4, distributing = true)
      val reviewed = round(5, distributing = true)
      job(reviewed, "done", """{"roundId": 0, "mode": "fill", "completingCreation": true}""")
      val distributingReview = round(6, distributing = true)
      job(distributingReview, "failed", """{"roundId": 0, "mode": "distribute", "completingCreation": true}""")
      val refilling = round(7, distributing = true)
      job(refilling, "running", """{"roundId": 0, "mode": "fill", "fromReview": true, "completingCreation": true}""")
      val fillFailed = round(8, distributing = true)
      job(fillFailed, "failed", """{"roundId": 0, "mode": "fill", "completingCreation": true}""")
      val open = round(9, active = true)
      val frozenOpen = round(10, frozenActive = true)
      val stopped = round(11)

      migrate()
      val first = Seq(running, failed, cancelled, noJob, reviewed, distributingReview, refilling, fillFailed, open,
        frozenOpen, stopped).map(statusOf)
      // a rerun after a rollback: the cancelled round stays cancelled, whatever its job says now;
      // one an older binary finished meanwhile (distributing = 0, active) takes its flags' status
      job(cancelled, "running", """{"roundId": 0, "completingCreation": true}""")
      val finished = round(12, distributing = true)
      exec(sqls"UPDATE round SET status = 'cancelled', distributing = 0, active = 1 WHERE id = $finished")
      migrate()
      (first === Seq("draft", "draft", "cancelled", "draft", "paused", "paused", "paused", "draft", "active", "active",
        "paused").map(Some(_))) and
        (statusOf(cancelled) === Some("cancelled")) and (statusOf(running) === Some("draft")) and
        (statusOf(finished) === Some("active"))
    }
  }
}
