package db

import com.dimafeng.testcontainers.MariaDBContainer
import org.flywaydb.core.Flyway
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
  *    Flyway's order, as a deploy runs them;
  *  - V65 adds category_members' foreign keys, which production lacks (MyISAM ignored
  *    V24's), after archiving the members whose image or category is gone, and is a no-op
  *    on a second run and on the B44 baseline, which has the keys.
  */
class ConvertToInnoDbMigrationSpec extends Specification with AfterAll {

  sequential

  private val migrations = Paths.get("conf/db/migration/default")

  // afterAll stops the container only if it started: evaluating `container` after a failed
  // start would start it again
  @volatile private var started = false

  private lazy val container = {
    val c = MariaDBContainer(
      dockerImageName = DockerImageName.parse("mariadb:10.6.22"),
      dbName = "wlxjury", dbUsername = "wlxjury", dbPassword = "wlxjury")
    c.container.withCommand("--max-allowed-packet=16M")
    db.TestDocker.start("the migration database", c)
    started = true
    c
  }

  override def afterAll(): Unit = if (started) container.stop()

  /** Root, so each example can have a database of its own. */
  private def connect(db: String): Connection =
    DriverManager.getConnection(jdbcUrl(db), "root", container.password)

  private def jdbcUrl(db: String): String = container.jdbcUrl.replaceFirst("/wlxjury(\\?|$)", s"/$db$$1")

  /** Flyway on a database, as a deploy runs it, up to a version. */
  private def flywayTo(db: String, version: String): Unit =
    Flyway.configure().dataSource(jdbcUrl(db), "root", container.password)
      .locations("filesystem:conf/db/migration/default").target(version).load().migrate()

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

  /** The migrations after V53, in Flyway's order. */
  private lazy val afterV53: Seq[String] = new File(migrations.toString).listFiles().map(_.getName)
    .filter(_.matches("V\\d+(_\\d+)?__.*\\.sql"))
    .map(n => (n.drop(1).takeWhile(_ != '_' ).toInt, n.drop(1).split("__").head.split("_").drop(1).headOption.map(_.toInt).getOrElse(0), n))
    .filter(_._1 > 53).sortBy(m => (m._1, m._2)).map(_._3).toSeq

  private val v65 = "V65__Category_members_foreign_keys.sql"

  /** category_members' foreign keys: name -> (referenced table, delete rule). */
  private def memberKeys(c: Connection): Map[String, (String, String)] =
    rows(c, """SELECT CONSTRAINT_NAME, REFERENCED_TABLE_NAME, DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS
               WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'category_members'""")(rs =>
      rs.getString(1) -> ((rs.getString(2), rs.getString(3)))).toMap

  private val cascadingMemberKeys = Map(
    "FK_category_member_page_id" -> (("images", "CASCADE")),
    "FK_category_member_category" -> (("category", "CASCADE")))

  private def members(c: Connection, table: String): List[(Long, Long)] =
    rows(c, s"SELECT category_id, page_id FROM $table ORDER BY category_id, page_id")(rs => (rs.getLong(1), rs.getLong(2)))

  private def createStatement(c: Connection, table: String): String =
    rows(c, s"SHOW CREATE TABLE $table")(_.getString(2)).head

  private def emptyDb(name: String): Connection = {
    Using.resource(connect("")) { c =>
      run(c, Seq(s"DROP DATABASE IF EXISTS $name", s"CREATE DATABASE $name"))
    }
    connect(name)
  }

  /** A fresh database with production's V53 MyISAM schema and a few rows. */
  private def prodLikeDb(name: String): Connection = {
    val c = emptyDb(name)
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
      "INSERT INTO selection_backup (page_id, rate, round, jury_id) VALUES (100, 1, 40, 1)",
      // the legacy user_contest's rows, as production has 813 of them: roles the old code
      // never read, a second role in one contest, a soft-deleted row
      "INSERT INTO user_contest (id, user_id, contest_id, role, deleted_at) VALUES " +
        "(1, 1, 7, 'jury', NULL), (2, 1, 7, 'no', NULL), (3, 5, 7, 'jury1', NULL), " +
        "(4, 1, 99, 'organizer', '2019-05-01 00:00:00')"
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
      afterV53 must contain(v55_1)
      Using.resource(prodLikeDb("chain")) { c =>
        afterV53.foreach(m => run(c, script(m)))
        tableStatus(c)("selection")._1 === "InnoDB"
        rows(c, """SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS
                   WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'FK_selection_page_id'""")(_.getInt(1)) === List(1)
        memberKeys(c) === cascadingMemberKeys
      }
    }

    "V65 archive the category members whose image or category is gone, then add their foreign keys" in {
      Using.resource(prodLikeDb("members")) { c =>
        // MyISAM let these in: page 102 has no image, category 9 no row
        run(c, Seq("INSERT INTO category_members (category_id, page_id) VALUES (3, 102), (9, 100)"))
        afterV53.takeWhile(_ != v65).foreach(m => run(c, script(m)))
        memberKeys(c) must beEmpty // V55.1 changed the engine only
        run(c, script(v65))

        memberKeys(c) === cascadingMemberKeys
        members(c, "category_members") === List((3L, 100L), (3L, 101L))
        members(c, "category_members_orphans") === List((3L, 102L), (9L, 100L))

        // a second run archives nothing twice and rebuilds nothing
        val before = tableStatus(c)
        Thread.sleep(1100) // CREATE_TIME has a resolution of one second
        run(c, script(v65))
        tableStatus(c) === before
        members(c, "category_members_orphans") === List((3L, 102L), (9L, 100L))

        // enforced: no new orphans, and deleting an image or a category deletes its members
        run(c, Seq("INSERT INTO category_members (category_id, page_id) VALUES (3, 404)")) must throwA[SQLException]
        run(c, Seq("INSERT INTO category_members (category_id, page_id) VALUES (404, 100)")) must throwA[SQLException]
        run(c, Seq(
          "INSERT INTO category (id, title) VALUES (4, 'Images from Wiki Loves Earth 2025 in Ukraine')",
          "INSERT INTO category_members (category_id, page_id) VALUES (4, 100), (4, 101)"))
        run(c, Seq("DELETE FROM images WHERE page_id = 101"))
        members(c, "category_members") === List((3L, 100L), (4L, 100L))
        run(c, Seq("DELETE FROM category WHERE id = 4"))
        members(c, "category_members") === List((3L, 100L))
      }
    }

    "V65 be a no-op on the B44 baseline, which has category_members' keys" in {
      // a fresh database, as the test suite's: Flyway applies B44, then the migrations after it
      Using.resource(emptyDb("baseline")) { c =>
        flywayTo("baseline", "64")
        memberKeys(c) === cascadingMemberKeys
        val before = (createStatement(c, "category_members"), tableStatus(c)("category_members"))
        Thread.sleep(1100)
        flywayTo("baseline", "65")
        rows(c, "SELECT success FROM flyway_schema_history WHERE version = '65'")(_.getBoolean(1)) === List(true)
        (createStatement(c, "category_members"), tableStatus(c)("category_members")) === before
        members(c, "category_members_orphans") must beEmpty
      }
    }

    "V62 move users' contest and roles into user_contest and is_root" in {
      Using.resource(prodLikeDb("memberships")) { c =>
        run(c, Seq(
          "INSERT INTO contest_jury (id, name, country, year) VALUES (8, 'Wiki Loves Earth', 'Ukraine', 2025)",
          "INSERT INTO users (id, fullname, email, password, roles, contest_id) VALUES " +
            "(10, 'Root', 'root@bench.test', 'x', 'root', 7), " +
            "(11, 'Two roles', 'two@bench.test', 'x', 'jury, organizer', 8), " +
            "(12, 'Gone contest', 'gone@bench.test', 'x', 'jury', 99), " +
            "(13, 'No contest', 'none@bench.test', 'x', 'jury', NULL), " +
            "(14, 'No role', 'norole@bench.test', 'x', '', 8), " +
            "(15, 'Juror+WLE', 'juror1+wle@bench.test', 'x', 'jury', 8)"
        ))
        afterV53.foreach(m => run(c, script(m)))

        rows(c, "SELECT user_id, contest_id, role FROM user_contest ORDER BY user_id")(rs =>
          (rs.getLong(1), rs.getLong(2), rs.getString(3))) === List(
          (1L, 7L, "jury"), (5L, 7L, "admin"), (11L, 8L, "organizer"), (15L, 8L, "jury"))
        // the legacy table is set aside with its rows, not reused
        rows(c, "SELECT id FROM user_contest_legacy ORDER BY id")(_.getLong(1)) === List(1L, 2L, 3L, 4L)
        tableStatus(c)("user_contest")._1 === "InnoDB"
        rows(c, """SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY ORDINAL_POSITION) FROM information_schema.KEY_COLUMN_USAGE
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_contest' AND CONSTRAINT_NAME = 'PRIMARY'""")(
          _.getString(1)) === List("user_id,contest_id")
        rows(c, "SELECT id FROM users WHERE is_root ORDER BY id")(_.getLong(1)) === List(10L)
        rows(c, """SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'users' AND COLUMN_NAME IN ('roles', 'contest_id')""")(_.getInt(1)) === List(0)
        // every account stays; deleting one deletes its memberships
        rows(c, "SELECT COUNT(*) FROM users")(_.getInt(1)) === List(8)
        run(c, Seq("DELETE FROM users WHERE id = 11"))
        rows(c, "SELECT COUNT(*) FROM user_contest WHERE user_id = 11")(_.getInt(1)) === List(0)
      }
    }

    "V62 add memberships from round assignments and turn off rounds that stop being current" in {
      Using.resource(prodLikeDb("assignments")) { c =>
        run(c, Seq(
          // 9 is a later WLM Ukraine than 7; 10 has no later contest, from an old year
          "INSERT INTO contest_jury (id, name, country, year) VALUES " +
            "(8, 'Wiki Loves Earth', 'Ukraine', 2025), (9, 'Wiki Loves Monuments', 'Ukraine', 2026), " +
            "(10, 'Wiki Loves Folklore', 'Ukraine', 2021)",
          "INSERT INTO users (id, fullname, email, password, roles, contest_id) VALUES " +
            "(16, 'WLM 2026 juror', 'wlm26@bench.test', 'x', 'jury', 9), " +
            "(17, 'Folklore juror', 'folk@bench.test', 'x', 'jury', 10)",
          "INSERT INTO rounds (id, name, number, contest_id, rates, active) VALUES " +
            "(41, 'WLE 1', 1, 8, 1, 1), (42, 'WLE 2', 2, 8, 1, 0), (43, 'Now', 1, 9, 1, 1), (44, 'WLE 3', 3, 8, 1, 0), " +
            "(45, 'Folklore', 1, 10, 1, 1), (46, 'Folklore voted', 2, 10, 1, 1)",
          // the marker the old code could write as a role, an organizer assignment, a
          // round_user role that must not make an admin, and a deleted user's assignment
          "INSERT INTO round_user (user_id, round_id, role, active) VALUES " +
            "(1, 41, 'USER_ID_1', 0), (5, 40, 'jury', 1), (5, 41, 'jury', 1), (5, 42, 'organizer', 1), " +
            "(1, 43, 'admin', 1), (16, 43, 'jury', 1), (77, 41, 'jury', 1), (17, 45, 'jury', 1)",
          // a legacy round: a vote, no round_user row
          "INSERT INTO selection (id, page_id, rate, round_id, jury_id) VALUES (12, 100, 1, 46, 17)"
        ))
        afterV53.filterNot(_.startsWith("V62")).foreach(m => run(c, script(m)))
        // a previous round of a running distribution: frozen, with its saved flag on
        run(c, Seq("UPDATE rounds SET freeze_count = 1, active_before_freeze = 1 WHERE id = 44"))
        run(c, script("V62__Migrate_roles_to_user_contest.sql"))

        rows(c, "SELECT user_id, contest_id, role FROM user_contest ORDER BY user_id, contest_id")(rs =>
          (rs.getLong(1), rs.getLong(2), rs.getString(3))) === List(
          (1L, 7L, "jury"), (1L, 8L, "jury"), (1L, 9L, "jury"), (5L, 7L, "admin"), (5L, 8L, "organizer"),
          (16L, 9L, "jury"), (17L, 10L, "jury"))
        // 40: a later WLM Ukraine exists; 41: none of its jurors was in WLE 2025.
        // 43 and 45, 46: current, their jurors there, whatever the year
        rows(c, "SELECT id FROM rounds WHERE active ORDER BY id")(_.getLong(1)) === List(43L, 45L, 46L)
        rows(c, "SELECT COALESCE(active_before_freeze, -1) FROM rounds WHERE id = 44")(_.getInt(1)) === List(0)
        rows(c, "SELECT round_id, active, COALESCE(active_before_freeze, -1) FROM v62_rounds_turned_off ORDER BY round_id")(rs =>
          (rs.getLong(1), rs.getInt(2), rs.getInt(3))) === List((40L, 1, -1), (41L, 1, -1), (44L, 0, 1))
      }
    }

    "V62 stopped before its ALTER, run again, keep the round flags the first run saw" in {
      Using.resource(prodLikeDb("rerun")) { c =>
        run(c, Seq("INSERT INTO contest_jury (id, name, country, year) VALUES (9, 'Wiki Loves Monuments', 'Ukraine', 2026)"))
        afterV53.filterNot(_.startsWith("V62")).foreach(m => run(c, script(m)))
        val v62 = script("V62__Migrate_roles_to_user_contest.sql")
        run(c, v62.filterNot(_.startsWith("ALTER TABLE users")))
        run(c, v62)

        rows(c, "SELECT round_id, active FROM v62_rounds_turned_off")(rs => (rs.getLong(1), rs.getInt(2))) ===
          List((40L, 1))
        rows(c, "SELECT user_id, contest_id, role FROM user_contest ORDER BY user_id")(rs =>
          (rs.getLong(1), rs.getLong(2), rs.getString(3))) === List((1L, 7L, "jury"), (5L, 7L, "admin"))
      }
    }

    "the alias account report run on production's V53 schema" in {
      Using.resource(prodLikeDb("aliases")) { c =>
        run(c, Seq(
          "INSERT INTO contest_jury (id, name, country, year) VALUES (8, 'Wiki Loves Earth', 'Ukraine', 2025)",
          "INSERT INTO users (id, fullname, email, password, roles, contest_id) VALUES " +
            "(20, 'Ann', 'ann@bench.test', 'x', 'jury', 7), " +
            "(21, 'Ann', 'Ann+WLE@bench.test', 'x', 'jury', 8), " +
            "(22, 'Bo', 'b.o+wlm@gmail.com', 'x', 'jury', 7), " +
            "(23, 'Bo', 'bo+wle@gmail.com', 'x', 'jury', 7)"
        ))
        val report = statements(new String(Files.readAllBytes(Paths.get("scripts/report-alias-accounts.sql")), "UTF-8"))
        report.size === 3

        rows(c, report(0))(rs => (rs.getString("person"), rs.getLong("id"))) ===
          List(("ann@bench.test", 20L), ("ann@bench.test", 21L), ("bo@gmail.com", 22L), ("bo@gmail.com", 23L))
        rows(c, report(1))(rs => (rs.getLong("account1"), rs.getLong("account2"), rs.getString("shared_contests"))) ===
          List((22L, 23L, "7"))
        rows(c, report(2))(rs => (rs.getInt(1), rs.getInt(2), rs.getInt(3))) === List((2, 4, 1))
      }
    }
  }
}
