package db

import org.specs2.mutable.Specification

import java.io.File

/** Flyway skips, without an error, a migration whose name it can't parse: the
  * V45a-V45g index migrations never ran because "45a" is not a version.
  */
class MigrationNamingSpec extends Specification {

  // V<version>__<description>.sql or B<version>__..., the version numeric parts
  // separated by '.' or '_'
  private val flywayName = """(V|B)\d+([._]\d+)*__.+\.sql""".r

  "every migration" should {
    "have a name Flyway runs" in {
      val names = new File("conf/db/migration/default").listFiles().map(_.getName).toSeq
      names must not(beEmpty)
      names.filterNot(flywayName.matches) must beEmpty
    }
  }
}
