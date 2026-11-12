package db

import db.scalikejdbc.SharedTestDb
import org.specs2.mutable.Specification
import org.specs2.specification.{AfterAll, BeforeAll}

import java.sql.{Connection, DriverManager}
import scala.util.Using

/** V97's `voted_at` on `vote` and `vote_aside`: nullable, default NULL, no ON UPDATE, in the
  * test database and in a scratch database migrated with explicit_defaults_for_timestamp off
  * (MariaDB 10.6's default, production's), where a TIMESTAMP column declared without NULL would
  * get the implicit CURRENT_TIMESTAMP behaviours; rerun, it changes nothing.
  */
class VoteVotedAtMigrationSpec extends Specification with BeforeAll with AfterAll {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()

  private val scratch = "v97_implicit"

  override def afterAll(): Unit = Using.resource(root(""))(exec(_, s"DROP DATABASE IF EXISTS $scratch"))

  private def root(database: String): Connection =
    DriverManager.getConnection(
      SharedTestDb.jdbcUrl.replaceFirst("/wlxjury(\\?|$)", s"/$database$$1"), "root", SharedTestDb.password)

  private def exec(c: Connection, sql: String): Unit = Using.resource(c.createStatement())(_.execute(sql))

  private def query[A](c: Connection, sql: String)(row: java.sql.ResultSet => A): List[A] =
    Using.resource(c.createStatement()) { st =>
      Using.resource(st.executeQuery(sql))(rs => Iterator.continually(rs).takeWhile(_.next()).map(row).toList)
    }

  /** (table, column, IS_NULLABLE, COLUMN_DEFAULT, EXTRA) of the timestamp columns of interest. */
  private def columns(c: Connection, database: String, column: String): List[(String, String, String, String)] =
    query(c,
      s"""SELECT TABLE_NAME, IS_NULLABLE, COLUMN_DEFAULT, EXTRA FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = '$database' AND TABLE_NAME IN ('vote', 'vote_aside') AND COLUMN_NAME = '$column'
          ORDER BY TABLE_NAME""")(rs => (rs.getString(1), rs.getString(2), Option(rs.getString(3)).getOrElse("NULL"),
      rs.getString(4)))

  private def index(c: Connection, database: String): List[String] =
    query(c,
      s"""SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = '$database'
          AND TABLE_NAME = 'vote' AND INDEX_NAME = 'vote_jury_round_voted_at_idx' ORDER BY SEQ_IN_INDEX""")(_.getString(1))

  private val nullable = List(("vote", "YES", "NULL", ""), ("vote_aside", "YES", "NULL", ""))

  "V97" should {

    "give vote and vote_aside a nullable voted_at without a default or ON UPDATE, and the juror's index" in {
      Using.resource(root("wlxjury")) { c =>
        (columns(c, "wlxjury", "voted_at") === nullable) and
          (index(c, "wlxjury") === List("jury_id", "round_id", "voted_at")) and
          // vote_aside takes vote's rows by SELECT *: the same columns in the same order
          (query(c, """SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY ORDINAL_POSITION) FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = 'wlxjury' AND TABLE_NAME = 'vote'""")(_.getString(1)) ===
            query(c, """SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY ORDINAL_POSITION) FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = 'wlxjury' AND TABLE_NAME = 'vote_aside'""")(_.getString(1)))
      }
    }

    "do the same with explicit_defaults_for_timestamp off, and change nothing when run again" in {
      Using.resource(root("")) { c =>
        exec(c, s"DROP DATABASE IF EXISTS $scratch")
        exec(c, s"CREATE DATABASE $scratch")
      }
      Using.resource(root(scratch)) { c =>
        exec(c, "SET SESSION explicit_defaults_for_timestamp = OFF")
        // V1's vote columns: its first TIMESTAMP gets the implicit behaviours with the setting off
        Seq("vote", "vote_aside").foreach { t =>
          exec(c, s"""CREATE TABLE $t (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, page_id BIGINT, jury_id BIGINT,
                      round_id BIGINT, rate INT NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL, deleted_at TIMESTAMP NULL)
                      ENGINE = InnoDB DEFAULT CHARSET = utf8mb4""")
        }
        val implicitCreatedAt = columns(c, scratch, "created_at").map(_._4)
        val first = MigrationScript.run(c, "V97__Vote_voted_at.sql")
        val after = columns(c, scratch, "voted_at")
        val again = MigrationScript.run(c, "V97__Vote_voted_at.sql")
        exec(c, "INSERT INTO vote (page_id, jury_id, round_id, rate) VALUES (1, 1, 1, 0)")
        exec(c, "UPDATE vote SET rate = 2 WHERE page_id = 1")
        val untouched = query(c, "SELECT voted_at FROM vote WHERE page_id = 1")(rs => Option(rs.getTimestamp(1)))

        // the setting took effect: V1's created_at got ON UPDATE
        (implicitCreatedAt must contain(beMatching("(?i)on update.*")).forall) and
          (first must beEmpty) and
          (after === nullable) and
          (columns(c, scratch, "voted_at") === nullable) and
          (index(c, scratch) === List("jury_id", "round_id", "voted_at")) and
          // the rerun only notes what is there already (MariaDB's "Duplicate column name" / "key name")
          (again.forall(w => w.toLowerCase.contains("duplicate") || w.toLowerCase.contains("exist")) must beTrue) and
          (untouched === List(None))
      }
    }
  }
}
