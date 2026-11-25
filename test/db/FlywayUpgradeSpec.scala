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

  /** V91's rollback SQL after reopen (docs/plans/2026-11-02-round-images-two-step.md, §18): back
    * to nullable columns without a default. Not INSTANT: MariaDB makes a column nullable instantly
    * only in ROW_FORMAT=REDUNDANT tables (ours are DYNAMIC, V55.1), so it is an online rebuild too.
    */
  private val v91Rollback = Seq(
    "ALTER TABLE vote MODIFY rate INT NULL DEFAULT NULL, ALGORITHM = INPLACE, LOCK = NONE",
    "ALTER TABLE vote_aside MODIFY rate INT NULL DEFAULT NULL, ALGORITHM = INPLACE, LOCK = NONE",
    "DELETE FROM flyway_schema_history WHERE version = '91'")

  private def statements(file: String): Seq[String] =
    new String(Files.readAllBytes(Paths.get("conf/db/migration/default", file)), "UTF-8")
      .linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n").split(";").map(_.trim).filter(_.nonEmpty).toSeq

  "V91's NOT NULL rates" should {
    "set NULL rates to 0, make both columns NOT NULL DEFAULT 0, rerun as a no-op, and roll back to V90's schema" in {
      emptyDb("fw_v90")
      flyway("fw_v90", production.copy(target = Some("90"))).migrate()
      emptyDb("fw_v91_back")
      flyway("fw_v91_back", production.copy(target = Some("90"))).migrate().success must beTrue
      Using.resource(connect("fw_v91_back"))(c => run(c, Seq(
        "INSERT INTO images (page_id, title) VALUES (1, 'File:1.jpg'), (2, 'File:2.jpg'), (3, 'File:3.jpg')",
        "INSERT INTO vote (id, page_id, jury_id, round_id, rate) VALUES (1, 1, 1, 1, NULL), (2, 2, 1, 1, 7), (3, 3, 1, 1, -1)",
        "INSERT INTO vote_aside (id, page_id, jury_id, round_id, rate) VALUES (4, 1, 2, 1, NULL), (5, 2, 2, 1, 1)")))
      flyway("fw_v91_back", production.copy(target = Some("91"))).migrate().success must beTrue
      val once = schema("fw_v91_back")
      val migrated = Using.resource(connect("fw_v91_back")) { c =>
        val rates = rows(c, "SELECT id, rate FROM vote UNION ALL SELECT id, rate FROM vote_aside ORDER BY 1")(rs =>
          rs.getLong(1) -> rs.getInt(2))
        val columns = rows(c, "SELECT TABLE_NAME, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS " +
          "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('vote', 'vote_aside') AND COLUMN_NAME = 'rate' " +
          "ORDER BY TABLE_NAME")(rs => (rs.getString(1), rs.getString(2), rs.getString(3)))
        // a vote written without a rate is unrated; a NULL one is refused (strict mode)
        run(c, Seq("INSERT INTO vote (id, page_id, jury_id, round_id) VALUES (6, 3, 2, 1)"))
        val default = rows(c, "SELECT rate FROM vote WHERE id = 6")(_.getInt(1))
        val nullRefused = scala.util.Try(run(c, Seq("UPDATE vote SET rate = NULL WHERE id = 6"))).isFailure
        run(c, Seq("DELETE FROM vote WHERE id = 6"))
        (rates, columns, default, nullRefused)
      }
      (migrated._1 === List(1L -> 0, 2L -> 7, 3L -> -1, 4L -> 0, 5L -> 1)) and
        (migrated._2 === List(("vote", "NO", "0"), ("vote_aside", "NO", "0"))) and
        (migrated._3 === List(0)) and (migrated._4 must beTrue) and {
          // a rerun of V91's statements on a database that has them rebuilds nothing, changes nothing
          Using.resource(connect("fw_v91_back"))(c => run(c, statements("V91__Vote_rate_not_null.sql")))
          schema("fw_v91_back") === once
        } and {
          Using.resource(connect("fw_v91_back"))(c => run(c, v91Rollback))
          def tablesOnly(s: String) = s.split(";\n\n").filterNot(_.matches("(?s)^\\w+: \\d+ rows.*")).mkString(";\n\n")
          (history("fw_v91_back").map(_.split(" \\| ")(1)) === upTo("90")) and
            (tablesOnly(schema("fw_v91_back")) === tablesOnly(schema("fw_v90"))) and {
              // the previous release may write a NULL rate meanwhile; forward again makes it 0
              Using.resource(connect("fw_v91_back"))(c => run(c, Seq("UPDATE vote SET rate = NULL WHERE id = 2")))
              flyway("fw_v91_back", production.copy(target = Some("91"))).migrate().success must beTrue
              (schema("fw_v91_back") === once) and
                (Using.resource(connect("fw_v91_back"))(c => rows(c, "SELECT rate FROM vote WHERE id = 2")(_.getInt(1))) === List(0))
            } and {
              // V91's ALTERs add strict mode for themselves (SET STATEMENT): back once more, then the
              // script on a session without strict mode makes both columns NOT NULL all the same
              Using.resource(connect("fw_v91_back")) { c =>
                run(c, v91Rollback.init)
                run(c, Seq("SET SESSION sql_mode = ''") ++ statements("V91__Vote_rate_not_null.sql"))
                rows(c, "SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() " +
                  "AND TABLE_NAME IN ('vote', 'vote_aside') AND COLUMN_NAME = 'rate'")(_.getString(1)) === List("NO", "NO")
              } and (schema("fw_v91_back") === once)
            }
        }
    }
  }

  /** V92's rollback SQL after reopen (docs/plans/2026-11-02-round-images-two-step.md, §18). */
  private val v92Rollback = Seq(
    "ALTER TABLE round DROP COLUMN worst_mark, ALGORITHM = INSTANT",
    "DELETE FROM flyway_schema_history WHERE version = '92'")

  "V92's worst mark" should {
    "add the option, off for every round, rerun as a no-op, and roll back to V91's schema" in {
      emptyDb("fw_v91")
      flyway("fw_v91", production.copy(target = Some("91"))).migrate()
      emptyDb("fw_v92_back")
      flyway("fw_v92_back", production.copy(target = Some("91"))).migrate().success must beTrue
      Using.resource(connect("fw_v92_back"))(c => run(c, Seq(
        "INSERT INTO round (id, number, contest_id, rates, created_at) VALUES (1, 1, 1, 10, '2020-01-01 00:00:00')")))
      flyway("fw_v92_back", production.copy(target = Some("92"))).migrate().success must beTrue
      val once = schema("fw_v92_back")
      val stored = Using.resource(connect("fw_v92_back"))(c =>
        rows(c, "SELECT worst_mark, CAST(created_at AS CHAR) FROM round")(rs => rs.getBoolean(1) -> rs.getString(2)))
      (stored === List(false -> "2020-01-01 00:00:00")) and {
        Using.resource(connect("fw_v92_back"))(c => run(c, statements("V92__Round_worst_mark.sql")))
        schema("fw_v92_back") === once
      } and {
        Using.resource(connect("fw_v92_back"))(c => run(c, v92Rollback))
        def tablesOnly(s: String) = s.split(";\n\n").filterNot(_.matches("(?s)^\\w+: \\d+ rows.*")).mkString(";\n\n")
        (history("fw_v92_back").map(_.split(" \\| ")(1)) === upTo("91")) and
          (tablesOnly(schema("fw_v92_back")) === tablesOnly(schema("fw_v91"))) and
          (flyway("fw_v92_back", production.copy(target = Some("92"))).migrate().success must beTrue) and
          (schema("fw_v92_back") === once)
      }
    }
  }

  /** V93's rollback SQL after reopen (docs/plans/2026-11-02-round-images-two-step.md, §18). */
  private val v93Rollback = Seq(
    "ALTER TABLE round DROP CONSTRAINT round_prev_rejected_mode_chk",
    "ALTER TABLE round DROP COLUMN prev_rejected_mode, ALGORITHM = INSTANT",
    "DELETE FROM flyway_schema_history WHERE version = '93'")

  "V93's rejected-by mode" should {
    "keep an existing round's filter excluding, refuse other values, rerun as a no-op, and roll back to V92's schema" in {
      emptyDb("fw_v92")
      flyway("fw_v92", production.copy(target = Some("92"))).migrate()
      emptyDb("fw_v93_back")
      flyway("fw_v93_back", production.copy(target = Some("92"))).migrate().success must beTrue
      Using.resource(connect("fw_v93_back"))(c => run(c, Seq(
        "INSERT INTO round (id, number, contest_id, rates, prev_rejected_by, created_at) VALUES " +
          "(1, 1, 1, 1, 2, '2020-01-01 00:00:00')")))
      flyway("fw_v93_back", production.copy(target = Some("93"))).migrate().success must beTrue
      val once = schema("fw_v93_back")
      val (stored, refused) = Using.resource(connect("fw_v93_back")) { c =>
        val stored = rows(c, "SELECT prev_rejected_by, prev_rejected_mode, CAST(created_at AS CHAR) FROM round")(rs =>
          (rs.getInt(1), rs.getString(2), rs.getString(3)))
        val refused = Seq("'ONLY'", "'all'").map(v =>
          scala.util.Try(run(c, Seq(s"UPDATE round SET prev_rejected_mode = $v WHERE id = 1"))).isFailure)
        run(c, Seq("UPDATE round SET prev_rejected_mode = 'only', created_at = created_at WHERE id = 1"))
        (stored, refused)
      }
      (stored === List((2, "exclude", "2020-01-01 00:00:00"))) and (refused === Seq(true, true)) and {
        Using.resource(connect("fw_v93_back"))(c => run(c, statements("V93__Round_prev_rejected_mode.sql")))
        schema("fw_v93_back") === once
      } and {
        Using.resource(connect("fw_v93_back"))(c => run(c, v93Rollback))
        def tablesOnly(s: String) = s.split(";\n\n").filterNot(_.matches("(?s)^\\w+: \\d+ rows.*")).mkString(";\n\n")
        (history("fw_v93_back").map(_.split(" \\| ")(1)) === upTo("92")) and
          (tablesOnly(schema("fw_v93_back")) === tablesOnly(schema("fw_v92"))) and
          (flyway("fw_v93_back", production.copy(target = Some("93"))).migrate().success must beTrue) and
          (schema("fw_v93_back") === once)
      }
    }
  }

  /** V94's rollback SQL after reopen (CHANGELOG, shareable set-password links): the live shared
    * links revoked first, since an older release would take one for a mailed one, whose
    * acceptance verifies the email.
    */
  private val v94Rollback = Seq(
    "UPDATE account_token SET revoked_at = NOW(3) WHERE shared AND used_at IS NULL AND revoked_at IS NULL",
    "ALTER TABLE account_token DROP COLUMN shared, ALGORITHM = INSTANT",
    "DELETE FROM flyway_schema_history WHERE version = '94'")

  "V94's shared links" should {
    "mark every existing link mailed, rerun as a no-op, and roll back to the previous schema" in {
      // the migration before V94, whichever it is once the batches below merge
      val before = versions.takeWhile(_ != "94").last
      emptyDb("fw_v93")
      flyway("fw_v93", production.copy(target = Some(before))).migrate()
      emptyDb("fw_v94_back")
      flyway("fw_v94_back", production.copy(target = Some(before))).migrate().success must beTrue
      Using.resource(connect("fw_v94_back"))(c => run(c, Seq(
        "INSERT INTO users (id, fullname, email) VALUES (1, 'A', 'a@example.com')",
        "INSERT INTO account_token (id, user_id, purpose, token_hash, email, created_at, expires_at) " +
          s"VALUES (1, 1, 'invitation', '${"a" * 64}', 'a@example.com', '2020-01-01 00:00:00', '2020-01-08 00:00:00')")))
      flyway("fw_v94_back", production.copy(target = Some("94"))).migrate().success must beTrue
      val once = schema("fw_v94_back")
      val stored = Using.resource(connect("fw_v94_back"))(c =>
        rows(c, "SELECT shared, CAST(created_at AS CHAR) FROM account_token")(rs => rs.getBoolean(1) -> rs.getString(2)))
      (stored === List(false -> "2020-01-01 00:00:00.000")) and {
        Using.resource(connect("fw_v94_back"))(c => run(c, statements("V94__Account_token_shared.sql")))
        schema("fw_v94_back") === once
      } and {
        Using.resource(connect("fw_v94_back"))(c => run(c, v94Rollback))
        def tablesOnly(s: String) = s.split(";\n\n").filterNot(_.matches("(?s)^\\w+: \\d+ rows.*")).mkString(";\n\n")
        (history("fw_v94_back").map(_.split(" \\| ")(1)) === upTo(before)) and
          (tablesOnly(schema("fw_v94_back")) === tablesOnly(schema("fw_v93"))) and
          (flyway("fw_v94_back", production.copy(target = Some("94"))).migrate().success must beTrue) and
          (schema("fw_v94_back") === once)
      }
    }
  }

  /** V95's rollback SQL after reopen (CHANGELOG, "Leave out files that are in round(s)"). */
  private val v95Rollback = Seq(
    "ALTER TABLE round DROP CONSTRAINT round_exclude_rounds_chk",
    "ALTER TABLE round DROP COLUMN exclude_rounds, ALGORITHM = INSTANT",
    "DELETE FROM flyway_schema_history WHERE version = '95'")

  "V95's rounds to leave out" should {
    "leave every existing round none, take ids only, rerun as a no-op, and roll back to the previous schema" in {
      // the migration before V95, whichever it is once the batches below merge
      val before = versions.takeWhile(_ != "95").last
      emptyDb("fw_v95_prev")
      flyway("fw_v95_prev", production.copy(target = Some(before))).migrate()
      emptyDb("fw_v95_back")
      flyway("fw_v95_back", production.copy(target = Some(before))).migrate().success must beTrue
      Using.resource(connect("fw_v95_back"))(c => run(c, Seq(
        "INSERT INTO round (id, number, contest_id, rates, previous, created_at) VALUES " +
          "(1, 1, 1, 1, NULL, '2020-01-01 00:00:00'), (2, 2, 1, 1, '1', '2020-01-02 00:00:00')")))
      flyway("fw_v95_back", production.copy(target = Some("95"))).migrate().success must beTrue
      val once = schema("fw_v95_back")
      val (stored, refused, taken) = Using.resource(connect("fw_v95_back")) { c =>
        val stored = rows(c, "SELECT id, exclude_rounds, CAST(created_at AS CHAR) FROM round ORDER BY id")(rs =>
          (rs.getLong(1), Option(rs.getString(2)), rs.getString(3)))
        val refused = Seq("''", "'1,'", "',1'", "'1, 2'", "'01'", "'-1'", "'1,,2'", "'a'",
          // a trailing line break, which PCRE's $ would let through: V95 refuses any byte but digits and commas
          "'1\\n'").map(v =>
          scala.util.Try(run(c, Seq(s"UPDATE round SET exclude_rounds = $v, created_at = created_at WHERE id = 2")))
            .isFailure)
        run(c, Seq("UPDATE round SET exclude_rounds = '1,10', created_at = created_at WHERE id = 2"))
        (stored, refused, rows(c, "SELECT exclude_rounds FROM round WHERE id = 2")(_.getString(1)))
      }
      (stored === List((1L, None, "2020-01-01 00:00:00"), (2L, None, "2020-01-02 00:00:00"))) and
        (refused === Seq.fill(9)(true)) and (taken === List("1,10")) and {
          Using.resource(connect("fw_v95_back"))(c => run(c, Seq("UPDATE round SET exclude_rounds = NULL")))
          val reset = schema("fw_v95_back")
          Using.resource(connect("fw_v95_back"))(c => run(c, statements("V95__Round_exclude_rounds.sql")))
          schema("fw_v95_back") === reset
        } and {
          // rerun under NO_BACKSLASH_ESCAPES: the same check (no backslash in its patterns), still
          // refusing a trailing line break (written without an escape) and taking ids
          val (refusedNbe, takenNbe) = Using.resource(connect("fw_v95_back")) { c =>
            run(c, Seq("SET SESSION sql_mode = CONCAT(@@SESSION.sql_mode, ',NO_BACKSLASH_ESCAPES')"))
            run(c, statements("V95__Round_exclude_rounds.sql"))
            val refused = Seq("CONCAT('1', CHAR(10))", "'1,'", "'a'").map(v =>
              scala.util.Try(run(c, Seq(s"UPDATE round SET exclude_rounds = $v, created_at = created_at WHERE id = 2")))
                .isFailure)
            run(c, Seq("UPDATE round SET exclude_rounds = '1,10', created_at = created_at WHERE id = 2"))
            val taken = rows(c, "SELECT exclude_rounds FROM round WHERE id = 2")(_.getString(1))
            run(c, Seq("UPDATE round SET exclude_rounds = NULL"))
            (refused, taken)
          }
          (schema("fw_v95_back") === once) and (refusedNbe === Seq(true, true, true)) and (takenNbe === List("1,10"))
        } and {
          Using.resource(connect("fw_v95_back"))(c => run(c, v95Rollback))
          def tablesOnly(s: String) = s.split(";\n\n").filterNot(_.matches("(?s)^\\w+: \\d+ rows.*")).mkString(";\n\n")
          (history("fw_v95_back").map(_.split(" \\| ")(1)) === upTo(before)) and
            (tablesOnly(schema("fw_v95_back")) === tablesOnly(schema("fw_v95_prev"))) and
            (flyway("fw_v95_back", production.copy(target = Some("95"))).migrate().success must beTrue) and
            (tablesOnly(schema("fw_v95_back")) === tablesOnly(once))
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


  "V104's author index, with V103 reserved for round lineage" should {

    "run after V102 with no V103, build the index, rerun as a no-op, and roll back to V102's schema" in {
      emptyDb("fw_v104_prev")
      flyway("fw_v104_prev", production.copy(target = Some("102"))).migrate()
      emptyDb("fw_v104")
      flyway("fw_v104", production.copy(target = Some("104"))).migrate().success must beTrue
      val once = schema("fw_v104")
      def indexes(db: String) = Using.resource(connect(db)) { c =>
        rows(c, "SELECT INDEX_NAME, NON_UNIQUE, COLUMN_NAME, SUB_PART FROM information_schema.STATISTICS " +
          "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'images' AND INDEX_NAME = 'images_author_idx'")(rs =>
          (rs.getString(1), rs.getInt(2), rs.getString(3), Option(rs.getString(4))))
      }
      def tablesOnly(s: String) = s.split(";\n\n").filterNot(_.matches("(?s)^\\w+: \\d+ rows.*")).mkString(";\n\n")
      // the history goes from V102 to V104 while there is no V103 file (Flyway needs no
      // consecutive numbers); a full-column, non-unique index
      (history("fw_v104").map(_.split(" \\| ")(1)) === upTo("104")) and
        (versions.contains("103") || !history("fw_v104").exists(_.split(" \\| ")(1) == "103")) and
        (indexes("fw_v104") === List(("images_author_idx", 1, "author", None))) and {
          Using.resource(connect("fw_v104"))(c => run(c, statements("V104__Images_author_index.sql")))
          schema("fw_v104") === once
        } and {
          Using.resource(connect("fw_v104"))(c => run(c, Seq(
            "ALTER TABLE images DROP INDEX images_author_idx, ALGORITHM = INPLACE, LOCK = NONE",
            "DELETE FROM flyway_schema_history WHERE version = '104'")))
          (tablesOnly(schema("fw_v104")) === tablesOnly(schema("fw_v104_prev"))) and
            (flyway("fw_v104", production.copy(target = Some("104"))).migrate().success must beTrue) and
            (schema("fw_v104") === once)
        }
    }

    "refuse a V103 added after V104 ran (production's settings: outOfOrder off), so the second to deploy is renumbered" in {
      if (versions.contains("103")) skipped("V103 exists now: the reserved number is taken")
      else {
        // a stand-in for round lineage's V103, in a location of its own beside the real ones
        val dir = Paths.get("target", "flyway-v103-check").toAbsolutePath
        Files.createDirectories(dir)
        Files.write(dir.resolve("V103__Reserved_round_status.sql"), "DO 0;\n".getBytes(StandardCharsets.UTF_8))
        val withV103 = production.copy(locations = Seq(Migrations.DefaultLocation, s"filesystem:$dir"))
        emptyDb("fw_v103_late")
        flyway("fw_v103_late", production.copy(target = Some("104"))).migrate().success must beTrue
        val before = history("fw_v103_late")
        (flyway("fw_v103_late", withV103).validate() must throwA[org.flywaydb.core.api.exception.FlywayValidateException](
          message = "103")) and
          (flyway("fw_v103_late", withV103).migrate() must throwA[org.flywaydb.core.api.exception.FlywayValidateException]) and
          (history("fw_v103_late") === before).toResult
      }
    }
  }

  /** V105's rollback SQL after reopen (round.status, P3-2 of the round-editing plan): the CHECK,
    * then the columns. Finish or cancel rounds in review first (an older binary has no review).
    */
  private val v105Rollback = Seq(
    "ALTER TABLE round DROP CONSTRAINT IF EXISTS round_status_chk",
    "ALTER TABLE round DROP COLUMN status, DROP COLUMN status_changed_at, DROP COLUMN status_changed_by",
    "DELETE FROM flyway_schema_history WHERE version = '105'")

  "V105's round status" should {
    "run after V104 (V103 retired), rerun as a no-op, and roll back to V104's schema" in {
      emptyDb("fw_v105_prev")
      flyway("fw_v105_prev", production.copy(target = Some("104"))).migrate()
      emptyDb("fw_v105")
      flyway("fw_v105", production.copy(target = Some("105"))).migrate().success must beTrue
      val once = schema("fw_v105")
      def tablesOnly(s: String) = s.split(";\n\n").filterNot(_.matches("(?s)^\\w+: \\d+ rows.*")).mkString(";\n\n")
      (history("fw_v105").map(_.split(" \\| ")(1)) === upTo("105")) and {
        Using.resource(connect("fw_v105"))(c => run(c, statements("V105__Round_status.sql")))
        schema("fw_v105") === once
      } and {
        Using.resource(connect("fw_v105"))(c => run(c, v105Rollback))
        (tablesOnly(schema("fw_v105")) === tablesOnly(schema("fw_v105_prev"))) and
          (flyway("fw_v105", production.copy(target = Some("105"))).migrate().success must beTrue) and
          (schema("fw_v105") === once)
      }
    }
  }

  /** V106's rollback SQL after reopen (the account join plan's J-1, as the script's last comment
    * gives it): the `account_joined` rows (none before J-2), V96's field CHECK back, the table
    * dropped (the pairs kept apart forgotten).
    */
  private val v106Rollback = Seq(
    "DELETE FROM account_change WHERE field = 'account_joined'",
    "ALTER TABLE account_change DROP CONSTRAINT IF EXISTS account_change_field_chk, ADD CONSTRAINT account_change_field_chk " +
      "CHECK (BINARY field IN ('email', 'password', 'role', 'name', 'wiki_account', 'root', 'account_created', " +
      "'invitation', 'invitation_cancelled', 'password_link', 'share_link', 'sessions_ended'))",
    "DROP TABLE IF EXISTS account_join_request",
    "DELETE FROM flyway_schema_history WHERE version = '106'")

  "V106's account join requests" should {
    "run after V105, keep a pair apart once, take account_joined, refuse other values, rerun as a no-op, and roll back" in {
      emptyDb("fw_v106_prev")
      flyway("fw_v106_prev", production.copy(target = Some("105"))).migrate()
      emptyDb("fw_v106")
      flyway("fw_v106", production.copy(target = Some("105"))).migrate().success must beTrue
      Using.resource(connect("fw_v106"))(c => run(c, Seq(
        "INSERT INTO users (id, fullname, email) VALUES (1, 'A', 'a@example.com'), (2, 'B', 'b@example.com'), " +
          "(3, 'C', 'c@example.com')")))
      flyway("fw_v106", production.copy(target = Some("106"))).migrate().success must beTrue
      val once = schema("fw_v106")
      def tablesOnly(s: String) = s.split(";\n\n").filterNot(_.matches("(?s)^\\w+: \\d+ rows.*")).mkString(";\n\n")
      def refused(sql: String): Boolean = Using.resource(connect("fw_v106"))(c => scala.util.Try(run(c, Seq(sql))).isFailure)
      val insert = "INSERT INTO account_join_request (first_account_id, second_account_id, state, requested_by) VALUES "
      Using.resource(connect("fw_v106"))(c => run(c, Seq(
        insert + "(1, 2, 'kept_apart', 1)", insert + "(1, 2, 'pending', 1)", insert + "(1, 2, 'cancelled', 1)",
        insert + "(1, 3, 'kept_apart', 2)",
        "UPDATE account_join_request SET first_proof = 'mail_link', second_proof = 'session' WHERE state = 'pending'",
        "INSERT INTO account_change (user_id, actor_id, field, comment) VALUES (1, 3, 'account_joined', '2')")))
      (history("fw_v106").map(_.split(" \\| ")(1)) === upTo("106")) and
        // a pair kept apart once; ordered; the states and proofs as listed, byte for byte
        (refused(insert + "(1, 2, 'kept_apart', 3)") must beTrue) and
        (refused(insert + "(2, 1, 'pending', 1)") must beTrue) and
        (refused(insert + "(2, 2, 'pending', 1)") must beTrue) and
        (refused(insert + "(1, 2, 'Kept_apart', 1)") must beTrue) and
        (refused(insert + "(1, 2, 'merged', 1)") must beTrue) and
        (refused("UPDATE account_join_request SET first_proof = 'mail' WHERE id = 1") must beTrue) and
        (refused(insert + "(1, 9, 'pending', 1)") must beTrue) and
        (refused("INSERT INTO account_change (user_id, actor_id, field) VALUES (1, 3, 'Account_joined')") must beTrue) and {
          Using.resource(connect("fw_v106"))(c => run(c, Seq("DELETE FROM account_join_request",
            "DELETE FROM account_change")))
          val reset = schema("fw_v106")
          Using.resource(connect("fw_v106"))(c => run(c, statements("V106__Account_join_request.sql")))
          schema("fw_v106") === reset
        } and {
          Using.resource(connect("fw_v106"))(c => run(c, v106Rollback))
          (history("fw_v106").map(_.split(" \\| ")(1)) === upTo("105")) and
            (tablesOnly(schema("fw_v106")) === tablesOnly(schema("fw_v106_prev"))) and
            (flyway("fw_v106", production.copy(target = Some("106"))).migrate().success must beTrue) and
            (tablesOnly(schema("fw_v106")) === tablesOnly(once))
        }
    }
  }

  /** V107's rollback SQL after reopen (the existing accounts plan's A-4, as the script's last
    * comment gives it): the `account_retired` rows, and V106's field CHECK back (the retired accounts
    * stay retired).
    */
  private val v107Rollback = Seq(
    "DELETE FROM account_change WHERE field = 'account_retired'",
    "ALTER TABLE account_change DROP CONSTRAINT IF EXISTS account_change_field_chk, ADD CONSTRAINT account_change_field_chk " +
      "CHECK (BINARY field IN ('email', 'password', 'role', 'name', 'wiki_account', 'root', 'account_created', " +
      "'invitation', 'invitation_cancelled', 'password_link', 'share_link', 'sessions_ended', 'account_joined'))",
    "DELETE FROM flyway_schema_history WHERE version = '107'")

  "V107's account_retired field" should {
    "run after V106, take account_retired and keep account_joined, refuse other values, rerun as a no-op, and roll back" in {
      emptyDb("fw_v107_prev")
      flyway("fw_v107_prev", production.copy(target = Some("106"))).migrate()
      emptyDb("fw_v107")
      flyway("fw_v107", production.copy(target = Some("106"))).migrate().success must beTrue
      def refused(sql: String): Boolean = Using.resource(connect("fw_v107"))(c => scala.util.Try(run(c, Seq(sql))).isFailure)
      Using.resource(connect("fw_v107"))(c => run(c, Seq(
        "INSERT INTO users (id, fullname, email) VALUES (1, 'A', 'a@example.com'), (2, 'B', 'b@example.com')",
        // rows of fields V106 allowed, kept by the new CHECK as it is added
        "INSERT INTO account_change (user_id, actor_id, field, comment) VALUES (1, 2, 'account_joined', '#2')",
        "INSERT INTO account_change (user_id, actor_id, field, old_value) VALUES (1, 2, 'email', 'a@example.com')")))
      // V106's CHECK refuses the new field
      val before = refused("INSERT INTO account_change (user_id, actor_id, field) VALUES (1, 2, 'account_retired')")
      flyway("fw_v107", production.copy(target = Some("107"))).migrate().success must beTrue
      val once = schema("fw_v107")
      def tablesOnly(s: String) = s.split(";\n\n").filterNot(_.matches("(?s)^\\w+: \\d+ rows.*")).mkString(";\n\n")
      Using.resource(connect("fw_v107"))(c => run(c, Seq(
        "UPDATE users SET deleted_at = NOW(3), email = NULL WHERE id = 1",
        "INSERT INTO account_change (user_id, actor_id, field, old_value, comment) VALUES (1, 2, 'account_retired', " +
          "'a@example.com', '#2')",
        "INSERT INTO account_change (user_id, actor_id, field, comment) VALUES (2, 2, 'account_joined', '#1')")))
      (before must beTrue) and
        (history("fw_v107").map(_.split(" \\| ")(1)) === upTo("107")) and
        // byte for byte: the field as listed only
        (refused("INSERT INTO account_change (user_id, actor_id, field) VALUES (1, 2, 'Account_retired')") must beTrue) and
        (refused("INSERT INTO account_change (user_id, actor_id, field) VALUES (1, 2, 'account_retire')") must beTrue) and
        (refused("INSERT INTO account_change (user_id, actor_id, field) VALUES (1, 2, 'retired')") must beTrue) and {
          Using.resource(connect("fw_v107"))(c => run(c, Seq("DELETE FROM account_change")))
          val reset = schema("fw_v107")
          Using.resource(connect("fw_v107"))(c => run(c, statements("V107__Account_retired.sql")))
          schema("fw_v107") === reset
        } and {
          Using.resource(connect("fw_v107"))(c => run(c, Seq(
            "INSERT INTO account_change (user_id, actor_id, field) VALUES (1, 2, 'account_retired')",
            "INSERT INTO account_change (user_id, actor_id, field) VALUES (1, 2, 'account_joined')")))
          Using.resource(connect("fw_v107"))(c => run(c, v107Rollback))
          (history("fw_v107").map(_.split(" \\| ")(1)) === upTo("106")) and
            (refused("INSERT INTO account_change (user_id, actor_id, field) VALUES (1, 2, 'account_retired')") must beTrue) and
            (tablesOnly(schema("fw_v107")) === tablesOnly(schema("fw_v107_prev"))) and
            (flyway("fw_v107", production.copy(target = Some("107"))).migrate().success must beTrue) and
            (tablesOnly(schema("fw_v107")) === tablesOnly(once))
        }
    }
  }

  /** V108's rollback SQL after reopen (the script's last comment): the column nullable again (the
    * rows set to FALSE stay FALSE).
    */
  private val v108Rollback = Seq(
    "ALTER TABLE round_user MODIFY active BOOLEAN NULL DEFAULT NULL, ALGORITHM = INPLACE, LOCK = NONE",
    "DELETE FROM flyway_schema_history WHERE version = '108'")

  "V108's NOT NULL round_user.active" should {
    "set a NULL active to FALSE (stopped), keep the others, make it NOT NULL DEFAULT TRUE with its index, rerun, roll back" in {
      emptyDb("fw_v108_prev")
      flyway("fw_v108_prev", production.copy(target = Some("107"))).migrate()
      emptyDb("fw_v108")
      flyway("fw_v108", production.copy(target = Some("107"))).migrate().success must beTrue
      Using.resource(connect("fw_v108"))(c => run(c, Seq(
        "INSERT INTO users (id, fullname, email) VALUES (1, 'A', 'a@example.com'), (2, 'B', 'b@example.com'), " +
          "(3, 'C', 'c@example.com'), (4, 'D', 'd@example.com')",
        "INSERT INTO round (id, number, contest_id, rates, created_at) VALUES (1, 1, 1, 1, NOW())",
        "INSERT INTO round_user (user_id, round_id, role, active) VALUES (1, 1, 'jury', NULL), (2, 1, 'jury', TRUE), " +
          "(3, 1, 'jury', FALSE)")))
      flyway("fw_v108", production.copy(target = Some("108"))).migrate().success must beTrue
      val once = schema("fw_v108")
      def tablesOnly(s: String) = s.split(";\n\n").filterNot(_.matches("(?s)^\\w+: \\d+ rows.*")).mkString(";\n\n")
      val migrated = Using.resource(connect("fw_v108")) { c =>
        val actives = rows(c, "SELECT user_id, active FROM round_user ORDER BY user_id")(rs => rs.getLong(1) -> rs.getBoolean(2))
        val column = rows(c, "SELECT IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = " +
          "DATABASE() AND TABLE_NAME = 'round_user' AND COLUMN_NAME = 'active'")(rs => (rs.getString(1), rs.getString(2)))
        val index = rows(c, "SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() " +
          "AND TABLE_NAME = 'round_user' AND INDEX_NAME = 'idx_round_user_round_active' ORDER BY SEQ_IN_INDEX")(_.getString(1))
        // a row written without `active` is active; a NULL one is refused (strict mode)
        run(c, Seq("INSERT INTO round_user (user_id, round_id, role) VALUES (4, 1, 'jury')"))
        val default = rows(c, "SELECT active FROM round_user WHERE user_id = 4")(_.getBoolean(1))
        val nullRefused = scala.util.Try(run(c, Seq("UPDATE round_user SET active = NULL WHERE user_id = 4"))).isFailure
        run(c, Seq("DELETE FROM round_user WHERE user_id = 4"))
        (actives, column, index, default, nullRefused)
      }
      (history("fw_v108").map(_.split(" \\| ")(1)) === upTo("108")) and
        (migrated._1 === List(1L -> false, 2L -> true, 3L -> false)) and
        (migrated._2 === List(("NO", "1"))) and
        (migrated._3 === List("round_id", "active")) and
        (migrated._4 === List(true)) and (migrated._5 must beTrue) and {
          Using.resource(connect("fw_v108"))(c => run(c, statements("V108__Round_user_active_not_null.sql")))
          schema("fw_v108") === once
        } and {
          Using.resource(connect("fw_v108"))(c => run(c, v108Rollback))
          (history("fw_v108").map(_.split(" \\| ")(1)) === upTo("107")) and
            (tablesOnly(schema("fw_v108")) === tablesOnly(schema("fw_v108_prev"))) and
            (flyway("fw_v108", production.copy(target = Some("108"))).migrate().success must beTrue) and
            (tablesOnly(schema("fw_v108")) === tablesOnly(once))
        }
    }
  }

  /** V109's rollback SQL after reopen (the script's last comment): the series columns, the log and
    * the series dropped.
    */
  private val v109Rollback = Seq(
    "ALTER TABLE contest_jury DROP FOREIGN KEY IF EXISTS contest_jury_series_id_fk, " +
      "DROP KEY IF EXISTS contest_jury_live_series_year_uidx, DROP KEY IF EXISTS contest_jury_series_id_idx, " +
      "DROP COLUMN IF EXISTS live_series_id, DROP COLUMN IF EXISTS series_id, DROP COLUMN IF EXISTS created_at, " +
      "DROP COLUMN IF EXISTS created_by, DROP COLUMN IF EXISTS retired_at, DROP COLUMN IF EXISTS retired_by",
    "DROP TABLE IF EXISTS contest_change",
    "DROP TABLE IF EXISTS series",
    "DELETE FROM flyway_schema_history WHERE version = '109'")

  "V109's series" should {
    "keep the contests, take one live contest per series and year, refuse unknown kinds, rerun, and roll back" in {
      emptyDb("fw_v109_prev")
      flyway("fw_v109_prev", production.copy(target = Some("108"))).migrate()
      emptyDb("fw_v109")
      flyway("fw_v109", production.copy(target = Some("108"))).migrate().success must beTrue
      Using.resource(connect("fw_v109"))(c => run(c, Seq(
        "INSERT INTO contest_jury (id, name, year, country) VALUES (1, 'Wiki Loves Monuments', 2025, 'Nepal'), " +
          "(2, 'Wiki Loves Monuments', 2025, 'Nepal'), (3, 'Wiki Loves Monuments', 2026, 'Nepal')")))
      flyway("fw_v109", production.copy(target = Some("109"))).migrate().success must beTrue
      val once = schema("fw_v109")
      def tablesOnly(s: String) = s.split(";\n\n").filterNot(_.matches("(?s)^\\w+: \\d+ rows.*")).mkString(";\n\n")
      def refused(sql: String): Boolean = Using.resource(connect("fw_v109"))(c => scala.util.Try(run(c, Seq(sql))).isFailure)
      val migrated = Using.resource(connect("fw_v109")) { c =>
        val kept = rows(c, "SELECT id, series_id IS NULL, created_at IS NULL, retired_at IS NULL FROM contest_jury ORDER BY id")(rs =>
          (rs.getLong(1), rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4)))
        run(c, Seq("INSERT INTO series (id, name) VALUES (1, 'Wiki Loves Monuments in Nepal')",
          "UPDATE contest_jury SET series_id = 1 WHERE id IN (1, 3)"))
        // a created_at written once stays: no ON UPDATE
        run(c, Seq("UPDATE contest_jury SET created_at = '2020-01-01 00:00:00' WHERE id = 1",
          "UPDATE contest_jury SET name = 'Wiki Loves Monuments ' WHERE id = 1"))
        val createdKept = rows(c, "SELECT created_at = '2020-01-01 00:00:00' FROM contest_jury WHERE id = 1")(_.getBoolean(1))
        kept -> createdKept
      }
      // contest 2 is 2025 too: the key refuses it while 1 is live, takes it once 1 is retired
      val keyRefused = refused("UPDATE contest_jury SET series_id = 1 WHERE id = 2")
      Using.resource(connect("fw_v109"))(c => run(c, Seq(
        "UPDATE contest_jury SET retired_at = NOW(3) WHERE id = 1",
        "UPDATE contest_jury SET series_id = 1 WHERE id = 2",
        "INSERT INTO contest_change (contest_id, series_id, actor_id, kind, batch_id, detail) VALUES (2, 1, 9, 'series_assigned', 1, '{\"to\":1}')")))
      (history("fw_v109").map(_.split(" \\| ")(1)) === upTo("109")) and
        (migrated._1 === List((1L, true, true, true), (2L, true, true, true), (3L, true, true, true))) and
        (migrated._2 === List(true)) and (keyRefused must beTrue) and
        // the series can't go while contests are in it; unknown kinds and invalid JSON are refused
        (refused("DELETE FROM series WHERE id = 1") must beTrue) and
        (refused("INSERT INTO contest_change (actor_id, kind) VALUES (9, 'Series_assigned')") must beTrue) and
        (refused("INSERT INTO contest_change (series_id, actor_id, kind, detail) VALUES (1, 9, 'created', 'not json')") must beTrue) and
        // every row is about a contest or a series
        (refused("INSERT INTO contest_change (actor_id, kind) VALUES (9, 'created')") must beTrue) and
        // one live series per name, case aside; a retired one may share it
        (refused("INSERT INTO series (name) VALUES ('wiki loves monuments in nepal')") must beTrue) and
        (refused("INSERT INTO series (name, retired_at) VALUES ('Wiki Loves Monuments in Nepal', NOW(3))") must beFalse) and {
          Using.resource(connect("fw_v109"))(c => run(c, Seq("DELETE FROM contest_change", "UPDATE contest_jury SET series_id = NULL",
            "DELETE FROM series", "ALTER TABLE series AUTO_INCREMENT = 1", "UPDATE contest_jury SET retired_at = NULL, created_at = NULL")))
          val reset = schema("fw_v109")
          Using.resource(connect("fw_v109"))(c => run(c, statements("V109__Series.sql")))
          schema("fw_v109") === reset
        } and {
          Using.resource(connect("fw_v109"))(c => run(c, v109Rollback))
          (history("fw_v109").map(_.split(" \\| ")(1)) === upTo("108")) and
            (tablesOnly(schema("fw_v109")) === tablesOnly(schema("fw_v109_prev"))) and
            (flyway("fw_v109", production.copy(target = Some("109"))).migrate().success must beTrue) and
            (tablesOnly(schema("fw_v109")) === tablesOnly(once))
        }
    }
  }

  /** V110's rollback SQL after reopen (the script's last comment): the read-only tokens revoked
    * first, so that none gains writes on an older binary; the column and its check dropped.
    */
  private val v110Rollback = Seq(
    "UPDATE api_token SET revoked_at = NOW(3) WHERE scope <> 'full' AND revoked_at IS NULL",
    "ALTER TABLE api_token DROP CONSTRAINT IF EXISTS api_token_scope_chk, DROP COLUMN IF EXISTS scope",
    "DELETE FROM flyway_schema_history WHERE version = '110'")

  "V110's API token scope" should {
    "make every existing token full, take local_read, refuse any other value, rerun, and roll back" in {
      emptyDb("fw_v110_prev")
      flyway("fw_v110_prev", production.copy(target = Some("109"))).migrate()
      emptyDb("fw_v110")
      flyway("fw_v110", production.copy(target = Some("109"))).migrate().success must beTrue
      Using.resource(connect("fw_v110"))(c => run(c, Seq(
        "INSERT INTO users (id, fullname, email) VALUES (1, 'A', 'a@example.com')",
        s"INSERT INTO api_token (id, user_id, name, token_hash, prefix) VALUES (1, 1, 'old', '${"a" * 64}', 'wlxj_aaaaaa')")))
      flyway("fw_v110", production.copy(target = Some("110"))).migrate().success must beTrue
      val once = schema("fw_v110")
      def tablesOnly(s: String) = s.split(";\n\n").filterNot(_.matches("(?s)^\\w+: \\d+ rows.*")).mkString(";\n\n")
      def refused(sql: String): Boolean = Using.resource(connect("fw_v110"))(c => scala.util.Try(run(c, Seq(sql))).isFailure)
      def insert(id: Int, scope: String) =
        s"INSERT INTO api_token (id, user_id, name, token_hash, prefix, scope) VALUES ($id, 1, 't$id', '${"b" * 63}$id', 'wlxj_b', '$scope')"
      val existing = Using.resource(connect("fw_v110"))(c => rows(c, "SELECT scope FROM api_token")(_.getString(1)))
      // a rerun changes nothing
      Using.resource(connect("fw_v110"))(c => run(c, statements("V110__Api_token_scope.sql")))
      val rerun = schema("fw_v110")
      (history("fw_v110").map(_.split(" \\| ")(1)) === upTo("110")) and
        (existing === List("full")) and (rerun === once) and
        (refused(insert(2, "local_read")) must beFalse) and
        // exactly the two values: no other case, no padding, no other word
        (refused(insert(3, "Full")) must beTrue) and (refused(insert(4, "local_read ")) must beTrue) and
        (refused(insert(5, "read")) must beTrue) and {
          Using.resource(connect("fw_v110"))(c => run(c, v110Rollback))
          val revoked = Using.resource(connect("fw_v110"))(c =>
            rows(c, "SELECT id, revoked_at IS NOT NULL FROM api_token ORDER BY id")(rs => rs.getLong(1) -> rs.getBoolean(2)))
          (revoked === List(1L -> false, 2L -> true)) and
            (history("fw_v110").map(_.split(" \\| ")(1)) === upTo("109")) and
            (tablesOnly(schema("fw_v110")) === tablesOnly(schema("fw_v110_prev"))) and
            (flyway("fw_v110", production.copy(target = Some("110"))).migrate().success must beTrue) and
            (tablesOnly(schema("fw_v110")) === tablesOnly(once))
        }
    }
  }

  /** V112's rollback SQL after reopen (the script's last comment): the series fields' log rows, the
    * rounds' juror entries and the grants go, the CHECKs as V107 and V109 left them.
    */
  private val v112Rollback = Seq(
    "DELETE FROM account_change WHERE field IN ('series_admin_granted', 'series_admin_revoked', 'series_admin_from_year', " +
      "'series_admin_confirmed')",
    "DELETE FROM contest_change WHERE kind IN ('round_juror_stopped', 'round_juror_reopened', 'round_juror_closed')",
    "ALTER TABLE contest_change DROP CONSTRAINT IF EXISTS contest_change_kind_chk, " +
      "DROP CONSTRAINT IF EXISTS contest_change_detail_chk, DROP CONSTRAINT IF EXISTS contest_change_subject_chk, " +
      "ADD CONSTRAINT contest_change_kind_chk CHECK (kind IN ('created', 'identity', 'series_assigned', 'series_unassigned', " +
      "'series_changed', 'split_out', 'split_in', 'split_undone', 'retired', 'unretired')), " +
      "ADD CONSTRAINT contest_change_detail_chk CHECK (detail IS NULL OR JSON_VALID(detail)), " +
      "ADD CONSTRAINT contest_change_subject_chk CHECK (contest_id IS NOT NULL OR series_id IS NOT NULL)",
    "ALTER TABLE account_change DROP CONSTRAINT IF EXISTS account_change_field_chk, ADD CONSTRAINT account_change_field_chk " +
      "CHECK (BINARY field IN ('email', 'password', 'role', 'name', 'wiki_account', 'root', 'account_created', 'invitation', " +
      "'invitation_cancelled', 'password_link', 'share_link', 'sessions_ended', 'account_joined', 'account_retired'))",
    "DROP TABLE IF EXISTS series_admin",
    "DELETE FROM flyway_schema_history WHERE version = '112'")

  "V112's series admins" should {
    "run after V110 (V111 held by CY-4), keep one live grant per account and series, take the series fields and the " +
      "rounds' juror kinds, rerun, roll back" in {
      emptyDb("fw_v112_prev")
      flyway("fw_v112_prev", production.copy(target = Some("110"))).migrate()
      emptyDb("fw_v112")
      flyway("fw_v112", production.copy(target = Some("110"))).migrate().success must beTrue
      Using.resource(connect("fw_v112"))(c => run(c, Seq(
        "INSERT INTO users (id, fullname, email) VALUES (1, 'A', 'a@example.com'), (2, 'B', 'b@example.com')",
        "INSERT INTO series (id, name) VALUES (1, 'Wiki Loves Monuments in Nepal')",
        "INSERT INTO account_change (user_id, actor_id, field) VALUES (1, 2, 'account_retired')")))
      flyway("fw_v112", production.copy(target = Some("112"))).migrate().success must beTrue
      val once = schema("fw_v112")
      def tablesOnly(s: String) = s.split(";\n\n").filterNot(_.matches("(?s)^\\w+: \\d+ rows.*")).mkString(";\n\n")
      def refused(sql: String): Boolean = Using.resource(connect("fw_v112"))(c => scala.util.Try(run(c, Seq(sql))).isFailure)
      def grant(user: Int) =
        s"INSERT INTO series_admin (series_id, user_id, admin_from_year, granted_by) VALUES (1, $user, 2025, 2)"
      val firstTaken = !refused(grant(1))
      // a second live grant of the account on the series: the key; once revoked, a new one fits
      val secondRefused = refused(grant(1))
      Using.resource(connect("fw_v112"))(c => run(c, Seq("UPDATE series_admin SET revoked_at = NOW(3) WHERE user_id = 1")))
      val regranted = !refused(grant(1))
      val fields = Seq("series_admin_granted", "series_admin_revoked", "series_admin_from_year", "series_admin_confirmed")
        .map(f => !refused(s"INSERT INTO account_change (user_id, actor_id, field, comment) VALUES (1, 2, '$f', 'series #1')"))
      val kinds = Seq("round_juror_stopped", "round_juror_reopened", "round_juror_closed", "series_assigned")
        .map(k => !refused(s"INSERT INTO contest_change (series_id, actor_id, kind, detail) VALUES (1, 2, '$k', '{\"round\":1}')"))
      (history("fw_v112").map(_.split(" \\| ")(1)) === upTo("112")) and
        (history("fw_v112").map(_.split(" \\| ")(1)) must not(contain("111"))) and
        (firstTaken must beTrue) and (secondRefused must beTrue) and (regranted must beTrue) and
        (fields === Seq(true, true, true, true)) and
        // V109's kinds kept; the new ones in this case only
        (kinds === Seq(true, true, true, true)) and
        (refused("INSERT INTO contest_change (series_id, actor_id, kind) VALUES (1, 2, 'Round_juror_stopped')") must beTrue) and
        // V107's field kept; another value, or another case, refused
        (refused("INSERT INTO account_change (user_id, actor_id, field) VALUES (1, 2, 'account_retired')") must beFalse) and
        (refused("INSERT INTO account_change (user_id, actor_id, field) VALUES (1, 2, 'Series_admin_granted')") must beTrue) and
        // a series with grants can't be deleted
        (refused("DELETE FROM series WHERE id = 1") must beTrue) and {
          Using.resource(connect("fw_v112"))(c => run(c, Seq(
            "DELETE FROM account_change WHERE field LIKE 'series_admin%'", "DELETE FROM series_admin",
            "ALTER TABLE series_admin AUTO_INCREMENT = 1", "DELETE FROM contest_change",
            "ALTER TABLE contest_change AUTO_INCREMENT = 1")))
          val reset = schema("fw_v112")
          Using.resource(connect("fw_v112"))(c => run(c, statements("V112__Series_admin.sql")))
          schema("fw_v112") === reset
        } and {
          Using.resource(connect("fw_v112"))(c => run(c, v112Rollback))
          (history("fw_v112").map(_.split(" \\| ")(1)) === upTo("110")) and
            (tablesOnly(schema("fw_v112")) === tablesOnly(schema("fw_v112_prev"))) and
            (flyway("fw_v112", production.copy(target = Some("112"))).migrate().success must beTrue) and
            (tablesOnly(schema("fw_v112")) === tablesOnly(once))
        }
    }
  }

  /** V115's rollback SQL after reopen (the script's last comment), on a schema without V114 (the
    * list repair, unmerged): the rounds' settings entries, then V112's kind CHECK.
    */
  private val v115Rollback = Seq(
    "DELETE FROM contest_change WHERE kind IN ('round_created', 'round_changed', 'round_filters_reapplied')",
    "ALTER TABLE contest_change DROP CONSTRAINT IF EXISTS contest_change_kind_chk, " +
      "ADD CONSTRAINT contest_change_kind_chk CHECK (kind IN ('created', 'identity', 'series_assigned', " +
      "'series_unassigned', 'series_changed', 'split_out', 'split_in', 'split_undone', 'retired', 'unretired', " +
      "'round_juror_stopped', 'round_juror_reopened', 'round_juror_closed'))",
    "DELETE FROM flyway_schema_history WHERE version = '115'")

  "V115's round change log" should {
    "take the rounds' settings kinds and V114's, keep the earlier ones, refuse others, rerun, and roll back" in {
      val before = versions.takeWhile(_ != "115").last
      emptyDb("fw_v115_prev")
      flyway("fw_v115_prev", production.copy(target = Some(before))).migrate()
      emptyDb("fw_v115")
      flyway("fw_v115", production.copy(target = Some(before))).migrate().success must beTrue
      flyway("fw_v115", production.copy(target = Some("115"))).migrate().success must beTrue
      val once = schema("fw_v115")
      def tablesOnly(s: String) = s.split(";\n\n").filterNot(_.matches("(?s)^\\w+: \\d+ rows.*")).mkString(";\n\n")
      def refused(sql: String): Boolean = Using.resource(connect("fw_v115"))(c => scala.util.Try(run(c, Seq(sql))).isFailure)
      def entry(kind: String) =
        s"INSERT INTO contest_change (contest_id, actor_id, kind, detail) VALUES (1, 2, '$kind', '{\"round\":1}')"
      val kinds = Seq("round_created", "round_changed", "round_filters_reapplied", "files_repaired", "files_repair_undone",
        "round_juror_stopped", "series_assigned").map(k => !refused(entry(k)))
      (history("fw_v115").map(_.split(" \\| ")(1)) === upTo("115")) and
        (kinds === Seq.fill(7)(true)) and
        (refused(entry("Round_changed")) must beTrue) and (refused(entry("round_renamed")) must beTrue) and {
          Using.resource(connect("fw_v115"))(c => run(c, Seq("DELETE FROM contest_change",
            "ALTER TABLE contest_change AUTO_INCREMENT = 1")))
          val reset = schema("fw_v115")
          Using.resource(connect("fw_v115"))(c => run(c, statements("V115__Round_change_log.sql")))
          schema("fw_v115") === reset
        } and {
          Using.resource(connect("fw_v115"))(c => run(c, v115Rollback))
          (history("fw_v115").map(_.split(" \\| ")(1)) === upTo(before)) and
            (tablesOnly(schema("fw_v115")) === tablesOnly(schema("fw_v115_prev"))) and
            (flyway("fw_v115", production.copy(target = Some("115"))).migrate().success must beTrue) and
            (tablesOnly(schema("fw_v115")) === tablesOnly(once))
        }
    }
  }

  /** V96's rollback SQL after reopen (CHANGELOG, the account change log): the live unsent
    * invitations revoked first, since an older release would show one as mailed; the log dropped.
    */
  private val v96Rollback = Seq(
    "UPDATE account_token SET revoked_at = NOW(3) WHERE unsent AND used_at IS NULL AND revoked_at IS NULL",
    "ALTER TABLE account_token DROP COLUMN unsent, ALGORITHM = INSTANT",
    "ALTER TABLE users DROP COLUMN email_set_by_admin_at, ALGORITHM = INSTANT",
    "DROP TABLE account_change",
    "DELETE FROM flyway_schema_history WHERE version = '96'")

  "V96's account change log" should {
    "mark every existing link sent, take an entry, rerun as a no-op, and roll back to the previous schema" in {
      val before = versions.takeWhile(_ != "96").last
      emptyDb("fw_v96_prev")
      flyway("fw_v96_prev", production.copy(target = Some(before))).migrate()
      emptyDb("fw_v96_back")
      flyway("fw_v96_back", production.copy(target = Some(before))).migrate().success must beTrue
      Using.resource(connect("fw_v96_back"))(c => run(c, Seq(
        "INSERT INTO users (id, fullname, email) VALUES (1, 'A', 'a@example.com')",
        "INSERT INTO account_token (id, user_id, purpose, token_hash, email, created_at, expires_at) " +
          s"VALUES (1, 1, 'invitation', '${"a" * 64}', 'a@example.com', '2020-01-01 00:00:00', '2020-01-08 00:00:00')")))
      flyway("fw_v96_back", production.copy(target = Some("96"))).migrate().success must beTrue
      val once = schema("fw_v96_back")
      val (stored, refused) = Using.resource(connect("fw_v96_back")) { c =>
        run(c, Seq("INSERT INTO account_change (user_id, actor_id, contest_id, field, old_value, new_value, mail_sent) " +
          "VALUES (1, 2, NULL, 'email', 'a@example.com', 'b@example.com', 1)"))
        val refused = scala.util.Try(run(c, Seq(
          "INSERT INTO account_change (user_id, actor_id, field) VALUES (1, 2, 'Email')"))).isFailure
        (rows(c, "SELECT unsent FROM account_token")(_.getBoolean(1)) ++
          rows(c, "SELECT email_set_by_admin_at IS NOT NULL FROM users")(_.getBoolean(1)), refused)
      }
      (stored === List(false, false)) and (refused must beTrue) and {
        Using.resource(connect("fw_v96_back"))(c => run(c, Seq("DELETE FROM account_change")))
        val reset = schema("fw_v96_back")
        Using.resource(connect("fw_v96_back"))(c => run(c, statements("V96__Account_change_log.sql")))
        schema("fw_v96_back") === reset
      } and {
        Using.resource(connect("fw_v96_back"))(c => run(c, v96Rollback))
        def tablesOnly(s: String) = s.split(";\n\n").filterNot(_.matches("(?s)^\\w+: \\d+ rows.*")).mkString(";\n\n")
        (history("fw_v96_back").map(_.split(" \\| ")(1)) === upTo(before)) and
          (tablesOnly(schema("fw_v96_back")) === tablesOnly(schema("fw_v96_prev"))) and
          (flyway("fw_v96_back", production.copy(target = Some("96"))).migrate().success must beTrue) and
          (tablesOnly(schema("fw_v96_back")) === tablesOnly(once))
      }
    }
  }

}
