package db

import _root_.scalikejdbc._
import db.scalikejdbc.{SharedTestDb, TestDb}
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll

import java.nio.file.{Files, Paths}

/** V101 adds comment's (contest_id, room) index, where it isn't there: rerunnable. Run on a copy
  * of the table (t_comment) for the add, as other specs read comment meanwhile.
  */
class CommentIndexMigrationSpec extends Specification with TestDb with BeforeAll {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()

  private def statements(table: String): Seq[String] = {
    val text = new String(
      Files.readAllBytes(Paths.get("conf/db/migration/default/V101__Comment_contest_room_index.sql")), "UTF-8")
    text.linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n")
      .split(""";\s*\n""").map(_.trim).filter(_.nonEmpty).toSeq
      .map(_.replace("TABLE_NAME = 'comment'", s"TABLE_NAME = '$table'").replace("ALTER TABLE comment ", s"ALTER TABLE $table "))
  }

  private def run(table: String): Unit = DB.autoCommit { implicit s => statements(table).foreach(st => SQL(st).execute.apply()) }

  /** The table's indexes with their columns in order. */
  private def indexes(table: String): Map[String, String] = DB.readOnly { implicit s =>
    SQL("""SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? GROUP BY INDEX_NAME""")
      .bind(table).map(rs => rs.string(1) -> rs.string(2)).list().toMap
  }

  "V101" should {

    "leave the migrated database with comment's (contest_id, room) index" in {
      indexes("comment").get("comment_contest_room_idx") === Some("contest_id,room")
    }

    "add the index where it is missing, and change nothing when run again" in {
      DB.autoCommit { implicit s =>
        SQL("DROP TABLE IF EXISTS t_comment").execute.apply()
        SQL("CREATE TABLE t_comment LIKE comment").execute.apply()
        SQL("ALTER TABLE t_comment DROP INDEX comment_contest_room_idx").execute.apply()
      }
      try {
        indexes("t_comment").get("comment_contest_room_idx") must beNone
        run("t_comment")
        val once = indexes("t_comment")
        once.get("comment_contest_room_idx") === Some("contest_id,room")
        run("t_comment")
        indexes("t_comment") === once
        // the shared table's run is a no-op too
        run("comment")
        indexes("comment").get("comment_contest_room_idx") === Some("contest_id,room")
      } finally DB.autoCommit { implicit s => SQL("DROP TABLE IF EXISTS t_comment").execute.apply() }
    }
  }
}
