package db

import db.scalikejdbc.SharedTestDb
import org.flywaydb.core.api.MigrationVersion
import org.specs2.mutable.Specification
import org.specs2.specification.{AfterAll, BeforeAll}
import org.testcontainers.utility.MountableFile
import play.api.{Configuration, Mode}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.sql.{Connection, DriverManager}
import scala.collection.mutable
import scala.io.Source
import scala.util.Using

/** Flyway as the app runs it ([[Migrations]]), on databases of its own on the shared test
  * server (dropped after the spec):
  *  - a fresh database: every migration, V1 onward, no baseline;
  *  - production's state: test/resources/db/flyway-9.16.3-v70.sql, a database Flyway 9.16.3
  *    built up to V70 (schema, seeded rows and its flyway_schema_history), as production's
  *    history was written by 9.16.3 (and older). With production's settings it validates, the
  *    history rows 9.16.3 wrote stay as they are, and it reaches the same schema as a fresh one;
  *  - a fresh database Flyway 9.16.3 built (test/resources/db/flyway-9.16.3-fresh-v82.sql, all
  *    migrations up to V82): validates, keeps its history, migrates any later ones, and reaches
  *    the same schema;
  *  - production's settings refuse to start (Prod without `auto`) while a migration is pending.
  *
  * Each run writes the schemas and histories it saw to target/flyway-evidence/<Flyway
  * version>/, to compare runs of two Flyway versions by hand (docs/plans/2026-11-03-flyway.md;
  * test/resources/db/flyway-9.16.3-fresh-history.txt is 9.16.3's fresh history, kept for
  * comparing production's checksums). `-Dgenerate.flywayFixture=true` (with Flyway 9.16.3
  * only) rewrote both fixtures; regenerating them needs a checkout with Flyway 9.16.3.
  */
class FlywayUpgradeSpec extends Specification with BeforeAll with AfterAll {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()

  private val databases = mutable.LinkedHashSet.empty[String]

  override def afterAll(): Unit = if (databases.nonEmpty) Using.resource(connect("")) { c =>
    run(c, databases.toSeq.map(name => s"DROP DATABASE IF EXISTS $name"))
  }

  /** The Flyway version on the classpath. */
  private val flywayVersion: String =
    Using.resource(Source.fromInputStream(
      classOf[org.flywaydb.core.Flyway].getClassLoader.getResourceAsStream("org/flywaydb/core/internal/version.txt"),
      "UTF-8"))(_.mkString.trim)

  private val fixture = Paths.get("test/resources/db/flyway-9.16.3-v70.sql")
  private val evidence = Paths.get("target/flyway-evidence", flywayVersion)
  private val oldFreshDump = Paths.get("test/resources/db/flyway-9.16.3-fresh-v82.sql")

  /** production.conf's db.default.migration (deployment.ProductionConfigSpec checks they're these). */
  private val production = Migrations.Settings(auto = true)

  private def jdbcUrl(db: String): String = SharedTestDb.jdbcUrl.replaceFirst("/wlxjury(\\?|$)", s"/$db$$1")

  private def connect(db: String): Connection =
    DriverManager.getConnection(jdbcUrl(db), "root", SharedTestDb.password)

  private def run(c: Connection, sqls: Seq[String]): Unit =
    Using.resource(c.createStatement())(st => sqls.foreach(st.execute))

  private def rows[A](c: Connection, sql: String)(f: java.sql.ResultSet => A): List[A] =
    Using.resource(c.createStatement()) { st =>
      Using.resource(st.executeQuery(sql))(rs => Iterator.continually(rs).takeWhile(_.next()).map(f).toList)
    }

  private def emptyDb(name: String): Unit = {
    require(name != "wlxjury", "the shared test database")
    databases += name
    Using.resource(connect(""))(c => run(c, Seq(s"DROP DATABASE IF EXISTS $name", s"CREATE DATABASE $name")))
  }

  private def flyway(db: String, settings: Migrations.Settings = production) =
    Migrations.flyway(jdbcUrl(db), "root", SharedTestDb.password, settings)

  /** Runs a client in the shared container, as root. */
  private def inContainer(command: String): String = {
    val result = SharedTestDb.container.container.execInContainer("sh", "-c", command)
    require(result.getExitCode == 0, s"$command: ${result.getStderr}")
    result.getStdout
  }

  private def dump(db: String): String =
    inContainer(s"mariadb-dump --skip-dump-date --skip-lock-tables --no-tablespaces -uroot -p${SharedTestDb.password} $db")

  private def restore(file: Path, db: String): Unit = {
    emptyDb(db)
    SharedTestDb.container.container.copyFileToContainer(MountableFile.forHostPath(file.toAbsolutePath.toString), s"/tmp/$db.sql")
    inContainer(s"mariadb -uroot -p${SharedTestDb.password} $db < /tmp/$db.sql")
    ()
  }

  /** The schema, without the history table: each table's and view's CREATE statement (without
    * AUTO_INCREMENT counters or definers), the triggers, routines and events, and each table's
    * row count (the rows migrations seed).
    */
  private def schema(db: String): String = Using.resource(connect(db)) { c =>
    val tables = rows(c, "SELECT TABLE_NAME, TABLE_TYPE FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() " +
      "AND TABLE_NAME <> 'flyway_schema_history' ORDER BY TABLE_NAME")(rs => (rs.getString(1), rs.getString(2)))
    val creates = tables.map { case (t, kind) =>
      val create = rows(c, s"SHOW CREATE ${if (kind == "VIEW") "VIEW" else "TABLE"} `$t`")(_.getString(2)).head
      create.replaceAll(" AUTO_INCREMENT=\\d+", "").replaceAll(" DEFINER=`[^`]*`@`[^`]*`", "")
    }
    val counts = tables.collect { case (t, "BASE TABLE") =>
      s"$t: ${rows(c, s"SELECT COUNT(*) FROM `$t`")(_.getLong(1)).head} rows"
    }
    val others = rows(c, "SELECT CONCAT('trigger ', TRIGGER_NAME, ' ', ACTION_TIMING, ' ', EVENT_MANIPULATION, ' ', " +
      "EVENT_OBJECT_TABLE, ': ', ACTION_STATEMENT) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE() " +
      "UNION ALL SELECT CONCAT(ROUTINE_TYPE, ' ', ROUTINE_NAME, ': ', ROUTINE_DEFINITION) FROM information_schema.ROUTINES " +
      "WHERE ROUTINE_SCHEMA = DATABASE() UNION ALL SELECT CONCAT('event ', EVENT_NAME, ': ', EVENT_DEFINITION) " +
      "FROM information_schema.EVENTS WHERE EVENT_SCHEMA = DATABASE()")(_.getString(1)).sorted
    (creates ++ others ++ counts).mkString("", ";\n\n", ";\n")
  }

  /** flyway_schema_history without its times: rank, version, description, type, script, checksum, success. */
  private def history(db: String): List[String] = Using.resource(connect(db)) { c =>
    rows(c, "SELECT installed_rank, version, description, type, script, checksum, success FROM flyway_schema_history " +
      "ORDER BY installed_rank")(rs => (1 to 7).map(rs.getString).mkString(" | "))
  }

  private def write(name: String, text: String): Unit = {
    Files.createDirectories(evidence)
    Files.write(evidence.resolve(name), text.getBytes(StandardCharsets.UTF_8))
    ()
  }

  /** The versions of conf/db/migration/default, as Flyway orders them (V55_1 is 55.1). */
  private val versions: List[String] =
    new java.io.File("conf/db/migration/default").listFiles().map(_.getName).toList
      .collect { case n if n.startsWith("V") => MigrationVersion.fromVersion(n.drop(1).split("__").head) }
      .sorted.map(_.getVersion)

  /** The versions up to `last`, included, whatever migrations come after it. */
  private def upTo(last: String): List[String] =
    versions.filter(v => MigrationVersion.fromVersion(v).compareTo(MigrationVersion.fromVersion(last)) <= 0)

  private lazy val freshSchema: String = {
    emptyDb("fw_fresh")
    flyway("fw_fresh").migrate()
    val s = schema("fw_fresh")
    write("fresh-schema.sql", s)
    write("fresh-history.txt", history("fw_fresh").mkString("\n"))
    s
  }

  "the V70 fixture" should {
    "be regenerated only with Flyway 9.16.3 (-Dgenerate.flywayFixture=true)" in {
      if (sys.props.get("generate.flywayFixture").isEmpty) skipped("pass -Dgenerate.flywayFixture=true to regenerate")
      flywayVersion === "9.16.3"
      emptyDb("fw_gen")
      flyway("fw_gen", production.copy(target = Some("70"))).migrate()
      Files.write(fixture, dump("fw_gen").getBytes(StandardCharsets.UTF_8))
      freshSchema must not(beEmpty)
      Files.write(oldFreshDump, dump("fw_fresh").getBytes(StandardCharsets.UTF_8))
      history("fw_gen").last must startWith(s"${versions.indexOf("70") + 1} | 70 |")
    }
  }

  "a fresh database" should {
    "run every migration from V1, without a baseline" in {
      freshSchema must not(beEmpty)
      val h = history("fw_fresh")
      h.map(_.split(" \\| ")(1)) === versions
      h.map(_.split(" \\| ")(3)).distinct === List("SQL")
      h.map(_.split(" \\| ").last).distinct === List("1")
    }
  }

  "a database whose history Flyway 9.16.3 wrote, at production's V70" should {
    "validate, keep that history and reach the schema of a fresh database" in {
      restore(fixture, "fw_v70")
      val before = history("fw_v70")
      before.map(_.split(" \\| ")(1)) === upTo("70")

      val fw = flyway("fw_v70")
      fw.migrate().success must beTrue
      fw.validate() must not(throwA[Throwable])

      val after = history("fw_v70")
      after.take(before.size) === before
      after.map(_.split(" \\| ")(1)) === versions
      after.drop(before.size).map(_.split(" \\| ").last).distinct === List("1")

      val s = schema("fw_v70")
      write("upgraded-from-9.16.3-v70-schema.sql", s)
      write("upgraded-from-9.16.3-v70-history.txt", after.mkString("\n"))
      s === freshSchema
    }
  }

  "a fresh database built by Flyway 9.16.3" should {
    "validate with this Flyway, keep its history and reach the schema of a fresh database" in {
      restore(oldFreshDump, "fw_old_fresh")
      val before = history("fw_old_fresh")
      before.map(_.split(" \\| ")(1)) === upTo("82")
      val fw = flyway("fw_old_fresh")
      fw.migrate().success must beTrue
      fw.validate() must not(throwA[Throwable])
      history("fw_old_fresh").take(before.size) === before
      schema("fw_old_fresh") === freshSchema
    }
  }

  /** V84's rollback SQL after reopen (docs/plans/2026-11-02-round-images-two-step.md, P1 results). */
  private val v84Rollback = Seq("DROP TABLE round_image", "DELETE FROM flyway_schema_history WHERE version = '84'")

  "V84's rollback SQL after reopen" should {
    "give back V83's schema and history, and V84 run again rebuild round_image from the votes" in {
      emptyDb("fw_v83")
      flyway("fw_v83", production.copy(target = Some("83"))).migrate()
      // the release that brought V84: the later migrations (V85 on) are not part of it
      emptyDb("fw_v84_back")
      flyway("fw_v84_back", production.copy(target = Some("84"))).migrate().success must beTrue
      Using.resource(connect("fw_v84_back"))(c => run(c, v84Rollback))
      history("fw_v84_back").map(_.split(" \\| ")(1)) === upTo("83")
      schema("fw_v84_back") === schema("fw_v83")
      // what the previous release writes meanwhile: votes, with no round_image
      Using.resource(connect("fw_v84_back"))(c => run(c, Seq(
        // created_at given: a database created on MariaDB 10.10+ has no default for it before V86
        "INSERT INTO round (id, number, contest_id, rates, created_at) VALUES (1, 1, 1, 1, NOW())",
        "INSERT INTO images (page_id, title, monument_id) VALUES (1, 'File:1.jpg', '07-101-1'), (2, 'File:2.jpg', NULL)",
        "INSERT INTO vote (page_id, jury_id, round_id, rate) VALUES (1, 1, 1, 0), (2, 1, 1, 1), (2, 2, 1, -1)",
        "INSERT INTO vote_aside (page_id, jury_id, round_id, rate) VALUES (1, 2, 1, 1)")))
      flyway("fw_v84_back", production.copy(target = Some("84"))).migrate().success must beTrue
      history("fw_v84_back").map(_.split(" \\| ")(1)) === upTo("84")
      Using.resource(connect("fw_v84_back")) { c =>
        rows(c, "SELECT round_id, page_id, status, added_how, region FROM round_image ORDER BY 1, 2")(rs =>
          (rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), Option(rs.getString(5)))) ===
          List((1L, 1L, "in", "legacy", Some("07")), (1L, 2L, "in", "legacy", None))
      }
    }
  }

  /** V88's rollback SQL after reopen (docs/plans/2026-11-02-round-images-two-step.md, §15). */
  private val v88Rollback = Seq("DROP TABLE round_image_event", "DELETE FROM flyway_schema_history WHERE version = '88'")

  "V88's rollback SQL after reopen" should {
    "give back V87's schema and history, and V88 run again create round_image_event, empty" in {
      emptyDb("fw_v87")
      flyway("fw_v87", production.copy(target = Some("87"))).migrate()
      emptyDb("fw_v88_back")
      flyway("fw_v88_back", production.copy(target = Some("88"))).migrate().success must beTrue
      Using.resource(connect("fw_v88_back"))(c => run(c, v88Rollback))
      history("fw_v88_back").map(_.split(" \\| ")(1)) === upTo("87")
      schema("fw_v88_back") === schema("fw_v87")
      // a rerun of V88 alone on a database that has it is a no-op (IF NOT EXISTS)
      val v88 = new String(java.nio.file.Files.readAllBytes(
        java.nio.file.Paths.get("conf/db/migration/default/V88__Round_image_event.sql")), "UTF-8")
        .linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n").trim.stripSuffix(";")
      flyway("fw_v88_back", production.copy(target = Some("88"))).migrate().success must beTrue
      history("fw_v88_back").map(_.split(" \\| ")(1)) === upTo("88")
      val once = schema("fw_v88_back")
      Using.resource(connect("fw_v88_back")) { c =>
        run(c, Seq(v88))
        rows(c, "SELECT COUNT(*) FROM round_image_event")(_.getInt(1)) === List(0)
      }
      schema("fw_v88_back") === once
    }
  }

  /** V89's rollback SQL after reopen (docs/plans/2026-11-02-round-images-two-step.md, §16). */
  private val v89Rollback = Seq(
    "DROP TABLE round_image_dirty",
    "ALTER TABLE round DROP COLUMN counts_gen, DROP COLUMN counts_gen_done, ALGORITHM = INSTANT",
    "ALTER TABLE round_image DROP COLUMN assigned, DROP COLUMN voted, DROP COLUMN approvals, " +
      "DROP COLUMN rejections, DROP COLUMN rate_sum, ALGORITHM = INSTANT",
    "DELETE FROM flyway_schema_history WHERE version = '89'")

  "V89's rollback SQL after reopen" should {
    "give back V88's schema and history, and V89 run again mark every round dirty" in {
      emptyDb("fw_v88")
      flyway("fw_v88", production.copy(target = Some("88"))).migrate()
      emptyDb("fw_v89_back")
      flyway("fw_v89_back", production.copy(target = Some("89"))).migrate().success must beTrue
      // counts written and a round recounted before the rollback
      Using.resource(connect("fw_v89_back"))(c => run(c, Seq(
        "INSERT INTO round (id, number, contest_id, rates, created_at) VALUES (1, 1, 1, 1, NOW())",
        "INSERT INTO images (page_id, title) VALUES (1, 'File:1.jpg')",
        "INSERT INTO vote (page_id, jury_id, round_id, rate) VALUES (1, 1, 1, 1)",
        "INSERT INTO round_image (round_id, page_id, assigned, voted, approvals, rate_sum) VALUES (1, 1, 1, 1, 1, 1)",
        "INSERT INTO round_image_dirty (round_id, page_id) VALUES (1, 1)",
        "UPDATE round SET counts_gen_done = 1 WHERE id = 1")))
      Using.resource(connect("fw_v89_back"))(c => run(c, v89Rollback))
      history("fw_v89_back").map(_.split(" \\| ")(1)) === upTo("88")
      val back = schema("fw_v89_back")
      // the rows' counts aside (the tables' row counts differ), the schema is V88's
      def tablesOnly(s: String) = s.split(";\n\n").filterNot(_.matches("(?s)^\\w+: \\d+ rows.*")).mkString(";\n\n")
      tablesOnly(back) === tablesOnly(schema("fw_v88"))
      // the previous release writes a vote meanwhile, which no mark records
      Using.resource(connect("fw_v89_back"))(c => run(c, Seq("UPDATE vote SET rate = -1 WHERE page_id = 1")))
      flyway("fw_v89_back", production.copy(target = Some("89"))).migrate().success must beTrue
      history("fw_v89_back").map(_.split(" \\| ")(1)) === upTo("89")
      val once = schema("fw_v89_back")
      Using.resource(connect("fw_v89_back")) { c =>
        // every round dirty again, its counts back to 0: the recompute recounts them from the votes
        (rows(c, "SELECT counts_gen, counts_gen_done FROM round")(rs => rs.getLong(1) -> rs.getLong(2)) === List(1L -> 0L)) and
          (rows(c, "SELECT assigned, rate_sum FROM round_image")(rs => rs.getInt(1) -> rs.getInt(2)) === List(0 -> 0)) and {
            // a rerun of V89's statements on a database that has them changes nothing (IF NOT EXISTS)
            val v89 = new String(java.nio.file.Files.readAllBytes(
              java.nio.file.Paths.get("conf/db/migration/default/V89__Round_image_counts.sql")), "UTF-8")
              .linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n").split(";").map(_.trim).filter(_.nonEmpty).toSeq
            run(c, v89)
            schema("fw_v89_back") === once
          }
      }
    }
  }

  /** V90's rollback SQL after reopen (docs/plans/2026-11-02-round-images-two-step.md, §17). */
  private val v90Rollback = Seq(
    "ALTER TABLE round DROP CONSTRAINT round_average_rule_chk",
    "ALTER TABLE round DROP COLUMN average_rule, ALGORITHM = INSTANT",
    "DELETE FROM flyway_schema_history WHERE version = '90'")

  "V90's average rule" should {
    "make an optional-rate round a sum round, the others rated, refuse other values, and roll back to V89's schema" in {
      emptyDb("fw_v89")
      flyway("fw_v89", production.copy(target = Some("89"))).migrate()
      emptyDb("fw_v90_back")
      flyway("fw_v90_back", production.copy(target = Some("89"))).migrate().success must beTrue
      Using.resource(connect("fw_v90_back"))(c => run(c, Seq(
        "INSERT INTO round (id, number, contest_id, rates, optional_rate, created_at) VALUES " +
          "(1, 1, 1, 10, 1, '2020-01-01 00:00:00'), (2, 2, 1, 10, NULL, '2020-01-01 00:00:00'), " +
          "(3, 3, 1, 1, 0, '2020-01-01 00:00:00')")))
      flyway("fw_v90_back", production.copy(target = Some("90"))).migrate().success must beTrue
      val migrated = Using.resource(connect("fw_v90_back")) { c =>
        val rules = rows(c, "SELECT id, average_rule, CAST(created_at AS CHAR) FROM round ORDER BY id")(rs =>
          (rs.getLong(1), rs.getString(2), rs.getString(3)))
        // the check: an unknown value and another case refused
        val refused = Seq("'average'", "'SUM'").map(v =>
          scala.util.Try(run(c, Seq(s"UPDATE round SET average_rule = $v WHERE id = 2"))).isFailure)
        (rules, refused)
      }
      (migrated._1 === List((1L, "sum", "2020-01-01 00:00:00"), (2L, "rated", "2020-01-01 00:00:00"),
        (3L, "rated", "2020-01-01 00:00:00"))) and (migrated._2 === Seq(true, true)) and {
        val once = schema("fw_v90_back")
        // a rerun of V90's statements on a database that has them changes nothing
        val v90 = new String(java.nio.file.Files.readAllBytes(
          java.nio.file.Paths.get("conf/db/migration/default/V90__Round_average_rule.sql")), "UTF-8")
          .linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n").split(";").map(_.trim).filter(_.nonEmpty).toSeq
        Using.resource(connect("fw_v90_back"))(c => run(c, v90))
        schema("fw_v90_back") === once
      } and {
        Using.resource(connect("fw_v90_back"))(c => run(c, v90Rollback))
        def tablesOnly(s: String) = s.split(";\n\n").filterNot(_.matches("(?s)^\\w+: \\d+ rows.*")).mkString(";\n\n")
        (history("fw_v90_back").map(_.split(" \\| ")(1)) === upTo("89")) and
          (tablesOnly(schema("fw_v90_back")) === tablesOnly(schema("fw_v89"))) and {
            // forward again: the optional-rate round a sum round again
            flyway("fw_v90_back", production.copy(target = Some("90"))).migrate().success must beTrue
            Using.resource(connect("fw_v90_back"))(c =>
              rows(c, "SELECT average_rule FROM round ORDER BY id")(_.getString(1))) === List("sum", "rated", "rated")
          }
      }
    }
  }

  "the app's start with production's settings but without auto" should {
    "refuse while a migration is pending, without changing the database" in {
      emptyDb("fw_pending")
      val config = Configuration(
        "db.default.url" -> jdbcUrl("fw_pending"), "db.default.username" -> "root",
        "db.default.password" -> SharedTestDb.password, "db.default.migration.auto" -> false,
        "db.default.migration.validateOnMigrate" -> true, "db.default.migration.initOnMigrate" -> false,
        "db.default.migration.cleanDisabled" -> true, "db.default.migration.cleanOnValidationError" -> false,
        "db.default.migration.ignoreMigrationPatterns" -> Seq.empty[String])
      Migrations.onStart(config, Mode.Prod) must throwA[Migrations.PendingMigrations]
      Using.resource(connect("fw_pending")) { c =>
        rows(c, "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()")(_.getInt(1)) === List(0)
      }
    }
  }

}
