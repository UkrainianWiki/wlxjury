package db

import org.specs2.mutable.Specification

import java.io.File

/** Flyway skips, without an error, a migration whose name it can't parse: the
  * V45a-V45g index migrations never ran because "45a" is not a version.
  */
class MigrationNamingSpec extends Specification {

  // V<version>__<description>.sql, the version numeric parts separated by '.' or '_'
  // (no B baseline script: the example below)
  private val flywayName = """V\d+([._]\d+)*__.+\.sql""".r

  "every migration" should {
    "have a name Flyway runs" in {
      val names = new File("conf/db/migration/default").listFiles().map(_.getName).toSeq
      names must not(beEmpty)
      names.filterNot(flywayName.matches) must beEmpty
    }

    // Flyway (Community, from 9.21.0) builds an empty database from the newest B (baseline)
    // script instead of the V migrations up to its version: one that doesn't match them exactly
    // breaks every fresh database (B44 did, at V51). A baseline is cut deliberately, with a
    // schema check against the replay (docs/plans/2026-11-03-flyway.md), and lifts this.
    "be a versioned migration, no baseline script" in {
      val names = new File("conf/db/migration/default").listFiles().map(_.getName).toSeq
      names.filter(_.startsWith("B")) must beEmpty
    }

    // A TIMESTAMP column that is neither NULL nor NOT NULL with a DEFAULT gets the rest from the
    // server: MariaDB 10.10 turned explicit_defaults_for_timestamp on, so a database created on
    // 10.11 would differ from production's, built on 10.6 (V86 fixed V2's, V3's and V77's).
    // FlywayUpgradeSpec can't see it for a new migration: both of its sides run it on one server.
    "state a TIMESTAMP column's NULL, or NOT NULL and DEFAULT, from V85 on" in {
      val files = new File("conf/db/migration/default").listFiles().toSeq
        .filter(f => MigrationNamingSpec.version(f.getName).exists(_ >= 85))
      files must not(beEmpty)
      files.flatMap { f =>
        val text = new String(java.nio.file.Files.readAllBytes(f.toPath), java.nio.charset.StandardCharsets.UTF_8)
        MigrationNamingSpec.implicitTimestamps(text).map(line => s"${f.getName}: $line")
      } must beEmpty
    }

    "find a TIMESTAMP column whose definition is left to the server" in {
      MigrationNamingSpec.implicitTimestamps(
        """CREATE TABLE t (
          |  a TIMESTAMP NOT NULL,
          |  b timestamp(3) DEFAULT CURRENT_TIMESTAMP(3),
          |  `c` TIMESTAMP NULL,
          |  d TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
          |  e TIMESTAMP NULL DEFAULT NULL,
          |  f TIMESTAMP
          |  -- g TIMESTAMP in a comment
          |);
          |UPDATE t SET d = CAST(e AS TIMESTAMP);
          |ALTER TABLE t MODIFY h TIMESTAMP(3) NOT NULL;""".stripMargin) === Seq(
        "a TIMESTAMP NOT NULL,",
        "b timestamp(3) DEFAULT CURRENT_TIMESTAMP(3),",
        "f TIMESTAMP",
        "ALTER TABLE t MODIFY h TIMESTAMP(3) NOT NULL;")
    }
  }
}

object MigrationNamingSpec {

  /** V85__x.sql's 85 (the first numeric part); None for a name that isn't a V migration. */
  def version(name: String): Option[Int] = """V(\d+)[._]""".r.findPrefixMatchOf(name).map(_.group(1).toInt)

  private val timestampColumn = """(?i)`?\b(\w+)`?\s+timestamp(?:\(\d+\))?(?![\w(])(.*)""".r.unanchored

  /** The non-comment lines that define a TIMESTAMP column the server completes: one is complete
    * with NULL or NOT NULL and a DEFAULT, or as `NULL` alone (NULL DEFAULT NULL on every
    * server). `CAST(… AS TIMESTAMP)` isn't a column.
    */
  def implicitTimestamps(sql: String): Seq[String] =
    sql.linesIterator.map(_.trim).filterNot(_.startsWith("--")).filter {
      case timestampColumn(name, rest) if !name.equalsIgnoreCase("as") =>
        val r = rest.toLowerCase
        val notNull = """\bnot\s+null\b""".r.findFirstIn(r).isDefined
        val nullable = !notNull && """\bnull\b""".r.findFirstIn(r).isDefined
        val default = """\bdefault\b""".r.findFirstIn(r).isDefined
        !(nullable || (notNull && default))
      case _ => false
    }.toSeq
}
