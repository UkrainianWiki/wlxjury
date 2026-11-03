package db

import com.dimafeng.testcontainers.MariaDBContainer
import org.specs2.mutable.Specification
import org.specs2.specification.AfterAll
import org.testcontainers.utility.DockerImageName

import java.io.File
import java.nio.file.{Files, Paths}
import java.sql.{Connection, DriverManager, SQLException}
import scala.util.Using

/** V55.1 converts production's MyISAM tables to InnoDB.
  *
  * The rest of the test suite runs on the B44 baseline, which has always been InnoDB, so
  * it can't show what production does: production's jury tables are still MyISAM until
  * V55.1 runs (they were created before InnoDB became the default). This spec starts from
  * production's schema at V53 (test/resources/db/prod-v53-myisam-schema.sql, MyISAM,
  * utf8mb3 / utf8mb4_unicode_ci) in a MariaDB of its own, and checks that:
  *  - V56 (ALGORITHM = INPLACE, LOCK = NONE) fails on MyISAM, as a deploy without V55.1 would;
  *  - V55.1 converts every live table (InnoDB, DYNAMIC, utf8mb4_general_ci), keeping the
  *    rows, the AUTO_INCREMENT counters and the unique keys, and leaves legacy tables alone;
  *  - a second run is a no-op (no table is rebuilt);
  *  - every migration after V53 in conf/db/migration/default, V55.1 included, then runs in
  *    Flyway's order, as a deploy runs them.
  */
class ConvertToInnoDbMigrationSpec extends Specification with AfterAll {

  sequential

  private val migrations = Paths.get("conf/db/migration/default")

  private lazy val container = {
    val c = MariaDBContainer(
      dockerImageName = DockerImageName.parse("mariadb:10.6.22"),
      dbName = "wlxjury", dbUsername = "wlxjury", dbPassword = "wlxjury")
    c.container.withCommand("--max-allowed-packet=16M")
    c.start()
    c
  }

  override def afterAll(): Unit = container.stop()

  /** Root, so each example can have a database of its own. */
  private def connect(db: String): Connection =
    DriverManager.getConnection(container.jdbcUrl.replaceFirst("/wlxjury(\\?|$)", s"/$db$$1"), "root", container.password)

  private def statements(sql: String): Seq[String] =
    sql.linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n")
      .split(";\\s*(\n|$)").map(_.trim).filter(_.nonEmpty).toSeq

  private def script(name: String): Seq[String] =
    statements(new String(Files.readAllBytes(migrations.resolve(name)), "UTF-8"))

  private val v55_1 = "V55_1__Convert_to_InnoDB.sql"

  private def run(c: Connection, sqls: Seq[String]): Unit =
    Using.resource(c.createStatement())(st => sqls.foreach(st.execute))

  private def rows[A](c: Connection, sql: String)(f: java.sql.ResultSet => A): List[A] =
    Using.resource(c.createStatement()) { st =>
      Using.resource(st.executeQuery(sql)) { rs =>
        Iterator.continually(rs).takeWhile(_.next()).map(f).toList
      }
    }

  /** A fresh database with production's V53 MyISAM schema and a few rows. */
  private def prodLikeDb(name: String): Connection = {
    Using.resource(connect("")) { c =>
      run(c, Seq(s"DROP DATABASE IF EXISTS $name", s"CREATE DATABASE $name"))
    }
    val c = connect(name)
    run(c, statements(new String(Files.readAllBytes(Paths.get("test/resources/db/prod-v53-myisam-schema.sql")), "UTF-8")))
    run(c, Seq(
      "INSERT INTO users (id, fullname, email, password, roles, contest_id) VALUES " +
        "(1, 'Юрій Журі', 'juror1@bench.test', 'x', 'jury', 7), (5, 'Organizer', 'org5@bench.test', 'x', 'admin', 7)",
      "INSERT INTO contest_jury (id, name, country, year, category_id) VALUES (7, 'Wiki Loves Monuments', 'Ukraine', 2025, 3)",
      "INSERT INTO category (id, title) VALUES (3, 'Images from Wiki Loves Monuments 2025 in Ukraine')",
      "INSERT INTO rounds (id, name, number, contest_id, rates, active) VALUES (40, 'Відбір', 1, 7, 1, 1)",
      "INSERT INTO round_user (user_id, round_id, role, active) VALUES (1, 40, 'jury', 1)",
      "INSERT INTO images (page_id, title, monument_id, width, height) VALUES " +
        "(100, 'File:Київ.jpg', '80-361-0001', 4000, 3000), (101, 'File:Lviv 😀.jpg', '46-101-0002', 4000, 3000)",
      "INSERT INTO category_members (category_id, page_id) VALUES (3, 100), (3, 101)",
      "INSERT INTO monument (id, name, adm0) VALUES ('80-361-0001', 'Софійський собор', '80')",
      // a gap in the ids, as deleted rows leave: AUTO_INCREMENT must stay past it
      "INSERT INTO selection (id, page_id, rate, round_id, jury_id, monument_id) VALUES " +
        "(10, 100, 1, 40, 1, '80-361-0001'), (11, 101, -1, 40, 1, '46-101-0002')",
      "ALTER TABLE selection AUTO_INCREMENT = 5000",
      "INSERT INTO comment (user_id, username, round_id, body, contest_id) VALUES (1, 'Juror 1', 40, 'Добре фото', 7)",
      "INSERT INTO criteria_rate (selection, criteria, rate) VALUES (10, 1, 5)",
      "INSERT INTO selection_backup (page_id, rate, round, jury_id) VALUES (100, 1, 40, 1)"
    ))
    c
  }

  private val live = Seq("users", "contest_jury", "rounds", "round_user", "category", "category_members",
    "comment", "criteria", "criteria_rate", "monument", "images", "selection")

  private def tableStatus(c: Connection): Map[String, (String, String, String, String)] =
    rows(c, "SELECT TABLE_NAME, ENGINE, ROW_FORMAT, TABLE_COLLATION, CREATE_TIME FROM information_schema.TABLES " +
      "WHERE TABLE_SCHEMA = DATABASE()")(rs => rs.getString(1) -> ((rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)))).toMap

  "On production's MyISAM schema" should {

    "V56 fail without the conversion (ALGORITHM = INPLACE, LOCK = NONE)" in {
      Using.resource(prodLikeDb("myisam_v56")) { c =>
        run(c, script("V54__Alter_images_add_media_type.sql") ++ script("V55__Create_skipped_V45_indexes.sql"))
        run(c, script("V56__idx_selection_round_page_rate.sql")) must throwA[SQLException](message = "not supported")
      }
    }

    "V55.1 convert every live table, keeping rows, counters and unique keys" in {
      Using.resource(prodLikeDb("conv_db")) { c =>
        run(c, script(v55_1))
        val status = tableStatus(c)
        live.map(t => t -> status(t)._1).filterNot(_._2 == "InnoDB") must beEmpty
        live.map(t => t -> status(t)._2).filterNot(_._2 == "Dynamic") must beEmpty
        live.map(t => t -> status(t)._3).filterNot(_._2 == "utf8mb4_general_ci") must beEmpty
        status("selection_backup")._1 === "MyISAM"

        rows(c, "SELECT title FROM images ORDER BY page_id")(_.getString(1)) === List("File:Київ.jpg", "File:Lviv 😀.jpg")
        rows(c, "SELECT fullname FROM users WHERE id = 1")(_.getString(1)) === List("Юрій Журі")
        rows(c, "SELECT body FROM comment")(_.getString(1)) === List("Добре фото")
        rows(c, "SELECT COUNT(*) FROM selection")(_.getInt(1)) === List(2)
        rows(c, "SELECT AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'selection'")(
          _.getLong(1)) === List(5000L)

        // unique keys still hold; a rollback is a rollback now
        run(c, Seq("INSERT INTO selection (page_id, rate, round_id, jury_id) VALUES (100, 0, 40, 1)")) must throwA[SQLException]
        run(c, Seq("INSERT INTO users (fullname, email, password) VALUES ('X', 'JUROR1@bench.test', 'x')")) must throwA[SQLException]
        c.setAutoCommit(false)
        run(c, Seq("UPDATE selection SET rate = 0 WHERE id = 10"))
        c.rollback()
        c.setAutoCommit(true)
        rows(c, "SELECT rate FROM selection WHERE id = 10")(_.getInt(1)) === List(1)
      }
    }

    "V55.1 be a no-op when the tables are already converted" in {
      Using.resource(prodLikeDb("twice")) { c =>
        run(c, script(v55_1))
        val before = tableStatus(c)
        Thread.sleep(1100) // CREATE_TIME has a resolution of one second
        run(c, script(v55_1))
        tableStatus(c) === before
      }
    }

    "every migration after V53 then run in Flyway's order" in {
      val pending = new File(migrations.toString).listFiles().map(_.getName)
        .filter(_.matches("V\\d+(_\\d+)?__.*\\.sql"))
        .map(n => (n.drop(1).takeWhile(_ != '_' ).toInt, n.drop(1).split("__").head.split("_").drop(1).headOption.map(_.toInt).getOrElse(0), n))
        .filter(_._1 > 53).sortBy(m => (m._1, m._2)).map(_._3).toSeq
      pending must contain(v55_1)
      Using.resource(prodLikeDb("chain")) { c =>
        pending.foreach(m => run(c, script(m)))
        tableStatus(c)("selection")._1 === "InnoDB"
        rows(c, """SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'FK_selection_page_id'""")(_.getInt(1)) === List(1)
      }
    }
  }
}
