package db

import db.scalikejdbc.SharedTestDb
import org.specs2.mutable.Specification
import org.specs2.specification.{AfterAll, BeforeAll}

import java.sql.{Connection, DriverManager}
import scala.util.Using

/** V102's `monument.wikidata`: a nullable VARCHAR(16) in the test database; on a scratch copy of
  * production's monument table (its DDL on the rehearsal copy, 2026-11-13) with a row, added
  * without touching the row, and run again, nothing changes but a note.
  */
class MonumentWikidataMigrationSpec extends Specification with BeforeAll with AfterAll {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()

  private val scratch = "v102_monument"

  override def afterAll(): Unit = Using.resource(root(""))(exec(_, s"DROP DATABASE IF EXISTS $scratch"))

  private def root(database: String): Connection =
    DriverManager.getConnection(
      SharedTestDb.jdbcUrl.replaceFirst("/wlxjury(\\?|$)", s"/$database$$1"), "root", SharedTestDb.password)

  private def exec(c: Connection, sql: String): Unit = Using.resource(c.createStatement())(_.execute(sql))

  private def query[A](c: Connection, sql: String)(row: java.sql.ResultSet => A): List[A] =
    Using.resource(c.createStatement()) { st =>
      Using.resource(st.executeQuery(sql))(rs => Iterator.continually(rs).takeWhile(_.next()).map(row).toList)
    }

  /** (COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT) of monument.wikidata. */
  private def column(c: Connection, database: String): List[(String, String, String)] =
    query(c,
      s"""SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = '$database' AND TABLE_NAME = 'monument' AND COLUMN_NAME = 'wikidata'""")(rs =>
      (rs.getString(1), rs.getString(2), Option(rs.getString(3)).getOrElse("NULL")))

  private val expected = List(("varchar(16)", "YES", "NULL"))

  "V102" should {

    "give monument a nullable wikidata column" in {
      Using.resource(root("wlxjury"))(c => column(c, "wlxjury") === expected)
    }

    "add it to production's monument table, keeping its rows, and change nothing when run again" in {
      Using.resource(root("")) { c =>
        exec(c, s"DROP DATABASE IF EXISTS $scratch")
        exec(c, s"CREATE DATABASE $scratch")
      }
      Using.resource(root(scratch)) { c =>
        exec(c,
          """CREATE TABLE monument (
            |  id varchar(11) NOT NULL DEFAULT '', name varchar(512) NOT NULL, place mediumtext DEFAULT NULL,
            |  photo varchar(400) DEFAULT NULL, gallery varchar(400) DEFAULT NULL, page varchar(400) DEFAULT NULL,
            |  typ varchar(255) DEFAULT NULL, sub_type varchar(255) DEFAULT NULL, user varchar(400) DEFAULT NULL,
            |  area varchar(400) DEFAULT NULL, resolution varchar(400) DEFAULT NULL, lat varchar(32) DEFAULT NULL,
            |  lon varchar(32) DEFAULT NULL, year varchar(255) DEFAULT NULL, city varchar(255) DEFAULT NULL,
            |  contest bigint(20) DEFAULT 14, adm0 varchar(3) DEFAULT NULL, adm1 varchar(6) DEFAULT NULL,
            |  description varchar(1) DEFAULT NULL,
            |  PRIMARY KEY (id), UNIQUE KEY monument_id_unique (id), KEY adm0_index (adm0), KEY adm1_index (adm1),
            |  KEY monument_id_adm0_index (id, adm0)
            |) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC""".stripMargin)
        exec(c, "INSERT INTO monument (id, name, page, gallery) VALUES ('07-101-0001', 'Castle', 'Lists', 'Castle cat')")
        val first = MigrationScript.run(c, "V102__Monument_wikidata.sql")
        val after = column(c, scratch)
        val again = MigrationScript.run(c, "V102__Monument_wikidata.sql")
        val row = query(c, "SELECT id, name, page, gallery, wikidata FROM monument")(rs =>
          (rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), Option(rs.getString(5))))

        (first must beEmpty) and
          (after === expected) and
          (column(c, scratch) === expected) and
          // the rerun only notes the column is there (MariaDB's "Duplicate column name")
          (again must not(beEmpty)) and
          (again.forall(_.toLowerCase.contains("duplicate column")) must beTrue) and
          (row === List(("07-101-0001", "Castle", "Lists", "Castle cat", None)))
      }
    }
  }
}
