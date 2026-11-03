package db

import _root_.scalikejdbc._
import db.scalikejdbc.{SharedTestDb, TestDb}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}

import java.nio.file.{Files, Paths}

/** V78 archives (criteria_rate_orphan) and deletes the criteria rates whose vote is in
  * neither selection nor selection_deleted (vote and vote_aside since V82).
  */
class CriteriaRateOrphansMigrationSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  /** V78's statements, run against copies of the tables (t_...): other specs use the shared
    * ones meanwhile.
    */
  private val v78: Seq[String] = {
    val text = new String(
      Files.readAllBytes(Paths.get("conf/db/migration/default/V78__Criteria_rate_orphans.sql")), "UTF-8")
    text.linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n")
      .split(";\\s*\n").map(_.trim).filter(_.nonEmpty).toSeq
      .map(_.replaceAll("\\b(FROM|JOIN|EXISTS|LIKE|INTO)(\\s+)(criteria_rate_orphan|criteria_rate|selection_deleted|selection)\\b",
        "$1$2t_$3"))
  }

  private val copies = Seq("t_criteria_rate_orphan", "t_criteria_rate", "t_selection", "t_selection_deleted")

  private def dropCopies(): Unit = DB.autoCommit { implicit s =>
    copies.foreach(t => SQL(s"DROP TABLE IF EXISTS $t").execute.apply())
  }

  private def runV78(): Unit = DB.autoCommit { implicit s => v78.foreach(st => SQL(st).execute.apply()) }

  private def rows(table: String, order: String = "id"): List[(Long, Long, Long, Int)] = DB.readOnly { implicit s =>
    SQL(s"SELECT id, selection, criteria, rate FROM $table ORDER BY $order")
      .map(rs => (rs.long(1), rs.long(2), rs.long(3), rs.int(4))).list()
  }

  "V78" should {

    "run on the copies only" in {
      v78 must haveSize(3)
      forall(v78)(_ must not(beMatching("(?s).*\\b(FROM|JOIN|EXISTS|LIKE|INTO)\\s+(criteria_rate|selection)\\b.*")))
    }

    "archive and delete the rates of no vote, keep those of votes and of votes moved aside, and do nothing again" in {
      dropCopies()
      try {
        DB.autoCommit { implicit s =>
          // the tables as V78 found them: V82 renamed them and criteria_rate's column
          sql"CREATE TABLE t_criteria_rate LIKE criteria_rate".execute.apply()
          sql"ALTER TABLE t_criteria_rate RENAME COLUMN vote_id TO selection".execute.apply()
          sql"CREATE TABLE t_selection LIKE vote".execute.apply()
          sql"CREATE TABLE t_selection_deleted LIKE vote_aside".execute.apply()
          sql"""INSERT INTO t_selection (id, jury_id, round_id, page_id, rate) VALUES
                (1, 10, 1, 1, 7), (2, 10, 1, 2, 0)""".execute.apply()
          sql"INSERT INTO t_selection_deleted (id, jury_id, round_id, page_id, rate) VALUES (3, 10, 1, 3, 5)"
            .execute.apply()
          // rates of vote 1 and 2, of vote 3 moved aside, and of the votes 99 and 100, gone
          sql"""INSERT INTO t_criteria_rate (id, selection, criteria, rate) VALUES
                (1, 1, 6, 3), (2, 1, 7, 4), (3, 2, 6, 0), (4, 3, 6, 5), (5, 99, 6, 2), (6, 100, 7, 0)"""
            .execute.apply()
        }

        runV78()

        val kept = List((1L, 1L, 6L, 3), (2L, 1L, 7L, 4), (3L, 2L, 6L, 0), (4L, 3L, 6L, 5))
        val archived = List((5L, 99L, 6L, 2), (6L, 100L, 7L, 0))
        def archive = rows("t_criteria_rate_orphan", order = "archive_id")
        rows("t_criteria_rate") === kept
        archive === archived

        // idempotent: a second run finds none, archives nothing twice
        runV78()
        rows("t_criteria_rate") === kept
        archive === archived

        // an orphan under an id archived before (criteria_rate ids are reused) is archived too
        DB.autoCommit(implicit s => sql"INSERT INTO t_criteria_rate (id, selection, criteria, rate) VALUES (5, 101, 6, 1)"
          .execute.apply())
        runV78()
        rows("t_criteria_rate") === kept
        archive === archived :+ ((5L, 101L, 6L, 1))
      } finally dropCopies()
    }

    "have left the migrated database with its archive table" in {
      DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM criteria_rate_orphan".map(_.long(1)).single()) === Some(0L)
    }
  }
}
