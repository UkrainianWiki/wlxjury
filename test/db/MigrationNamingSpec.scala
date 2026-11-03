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
  }
}
