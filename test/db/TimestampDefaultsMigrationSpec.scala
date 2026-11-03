package db

import db.scalikejdbc.SharedTestDb
import org.specs2.mutable.Specification
import org.specs2.specification.{AfterAll, BeforeAll}

import java.sql.{Connection, DriverManager}
import scala.util.Using

/** V86 gives round.created_at, users.created_at and account_token.expires_at 10.6's definitions
  * on a database created on MariaDB 10.10+, where explicit_defaults_for_timestamp is on by
  * default and V2's, V3's and V77's columns get none (docs/plans/2026-11-03-flyway.md,
  * section 4), and changes nothing on one created on 10.6, production's.
  *
  * Both server behaviours are made in scratch databases with the session's
  * explicit_defaults_for_timestamp, so the examples run on 10.6 and 10.11 alike.
  */
class TimestampDefaultsMigrationSpec extends Specification with BeforeAll with AfterAll {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()

  private val databases = Seq("v86_explicit", "v86_implicit")

  override def afterAll(): Unit = Using.resource(root("")) { c =>
    databases.foreach(d => exec(c, s"DROP DATABASE IF EXISTS $d"))
  }

  private def root(database: String): Connection =
    DriverManager.getConnection(
      SharedTestDb.jdbcUrl.replaceFirst("/wlxjury(\\?|$)", s"/$database$$1"), "root", SharedTestDb.password)

  private def exec(c: Connection, sql: String): Unit = Using.resource(c.createStatement())(_.execute(sql))

  private def query[A](c: Connection, sql: String)(row: java.sql.ResultSet => A): List[A] =
    Using.resource(c.createStatement()) { st =>
      Using.resource(st.executeQuery(sql))(rs => Iterator.continually(rs).takeWhile(_.next()).map(row).toList)
    }

  /** (table, IS_NULLABLE, COLUMN_DEFAULT, EXTRA) of the three columns. */
  private def createdAt(c: Connection, database: String): List[(String, String, String, String)] =
    query(c,
      s"""SELECT TABLE_NAME, IS_NULLABLE, COLUMN_DEFAULT, EXTRA FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = '$database' AND ((TABLE_NAME IN ('round', 'users') AND COLUMN_NAME = 'created_at')
                 OR (TABLE_NAME = 'account_token' AND COLUMN_NAME = 'expires_at'))
          ORDER BY TABLE_NAME""")(rs => (rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))

  private def showCreate(c: Connection, table: String): String =
    query(c, s"SHOW CREATE TABLE $table")(_.getString(2)).mkString

  private val as10_6 = List(
    ("account_token", "NO", "'0000-00-00 00:00:00.000'", ""),
    ("round", "NO", "current_timestamp()", "on update current_timestamp()"),
    ("users", "NO", "current_timestamp()", ""))

  private val tables = Seq("account_token", "round", "users")

  /** V3's, V2's and V77's columns, created with the session's explicit_defaults_for_timestamp. */
  private def scratch(database: String, explicitDefaults: Boolean): Connection = {
    Using.resource(root("")) { c =>
      exec(c, s"DROP DATABASE IF EXISTS $database")
      exec(c, s"CREATE DATABASE $database")
    }
    val c = root(database)
    exec(c, s"SET SESSION explicit_defaults_for_timestamp = ${if (explicitDefaults) "ON" else "OFF"}")
    exec(c, "CREATE TABLE round (id SERIAL PRIMARY KEY, created_at TIMESTAMP NOT NULL, deleted_at TIMESTAMP NULL)")
    exec(c, "CREATE TABLE users (id SERIAL PRIMARY KEY, created_at timestamp DEFAULT CURRENT_TIMESTAMP, deleted_at timestamp NULL)")
    exec(c, "CREATE TABLE account_token (id SERIAL PRIMARY KEY, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), " +
      "expires_at TIMESTAMP(3) NOT NULL, used_at TIMESTAMP(3) NULL DEFAULT NULL)")
    c
  }

  "V86" should {

    "leave the test database's columns as 10.6 makes them" in {
      Using.resource(root("wlxjury"))(createdAt(_, "wlxjury")) === as10_6
    }

    "give a database created on 10.10+ 10.6's definitions, keeping its users" in {
      Using.resource(scratch("v86_explicit", explicitDefaults = true)) { c =>
        val before = createdAt(c, "v86_explicit")
        exec(c, "INSERT INTO users (id, created_at) VALUES (1, NULL), (2, '2020-01-02 03:04:05')")
        MigrationScript.run(c, "V86__Timestamp_defaults_as_on_10_6.sql")
        val after = createdAt(c, "v86_explicit")
        val users = query(c, "SELECT id, created_at IS NOT NULL, CAST(created_at AS CHAR) FROM users ORDER BY id")(rs =>
          (rs.getLong(1), rs.getBoolean(2), rs.getString(3)))
        val schema = tables.map(showCreate(c, _))
        MigrationScript.run(c, "V86__Timestamp_defaults_as_on_10_6.sql")
        (before must not(beEqualTo(as10_6))) and (after === as10_6) and
          (users.map(_._2) === List(true, true)) and (users(1)._3 === "2020-01-02 03:04:05") and
          (tables.map(showCreate(c, _)) === schema)
      }
    }

    "change nothing on a database created on 10.6" in {
      Using.resource(scratch("v86_implicit", explicitDefaults = false)) { c =>
        // production's users.created_at also updates on update (an older schema's): kept
        exec(c, "ALTER TABLE users MODIFY created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP")
        val before = tables.map(showCreate(c, _))
        MigrationScript.run(c, "V86__Timestamp_defaults_as_on_10_6.sql")
        (createdAt(c, "v86_implicit").take(2) === as10_6.take(2)) and
          (tables.map(showCreate(c, _)) === before)
      }
    }
  }
}
