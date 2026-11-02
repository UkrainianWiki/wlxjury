package db

import _root_.scalikejdbc._
import db.scalikejdbc.{LegacyPasswordRetirementJdbc, SharedTestDb, TestDb}
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll

import java.nio.file.{Files, Paths}

/** V81 records the end of the legacy password hashes' grace period (P8): 3 months after it ran
  * (the deploy), never moved by running again; and the table of the accounts whose hash was
  * replaced.
  */
class LegacyPasswordRetirementMigrationSpec extends Specification with TestDb with BeforeAll {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()

  /** V81's statements, run against copies of its tables (t_...): other specs use the real ones. */
  private val v81: Seq[String] = {
    val text = new String(
      Files.readAllBytes(Paths.get("conf/db/migration/default/V81__Legacy_password_retirement.sql")), "UTF-8")
    text.linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n")
      .split(";\\s*\n").map(_.trim).filter(_.nonEmpty).toSeq
      .map(_.replaceAll("\\b(legacy_password_retire(?:ment|d))\\b", "t_$1"))
  }

  private def dropCopies(): Unit = DB.autoCommit { implicit s =>
    Seq("t_legacy_password_retirement", "t_legacy_password_retired")
      .foreach(t => SQL(s"DROP TABLE IF EXISTS $t").execute.apply())
  }

  private def runV81(): Unit = DB.autoCommit { implicit s => v81.foreach(st => SQL(st).execute.apply()) }

  private def daysLeft(table: String): List[Long] = DB.readOnly { implicit s =>
    SQL(s"SELECT TIMESTAMPDIFF(DAY, NOW(3), retire_at) FROM $table").map(_.long(1)).list.apply()
  }

  "V81" should {

    "run on the copies only" in {
      v81 must haveSize(3)
      forall(v81)(_ must not(beMatching("(?s).*\\s(legacy_password_retire(ment|d))\\b.*")))
    }

    "have ended the grace period about 3 months after it ran, on this database" in {
      daysLeft("legacy_password_retirement") must beLike { case List(days) => days must beBetween(88L, 92L) }
      LegacyPasswordRetirementJdbc.over() must beFalse
      LegacyPasswordRetirementJdbc.secondsLeft() must beSome(beGreaterThan(0L))
    }

    "create the tables and one end, which running it again never moves" in {
      dropCopies()
      try {
        runV81()
        daysLeft("t_legacy_password_retirement") must beLike { case List(days) => days must beBetween(88L, 92L) }
        DB.autoCommit { implicit s =>
          sql"UPDATE t_legacy_password_retirement SET retire_at = NOW(3) - INTERVAL 1 DAY WHERE id = 1".update.apply()
          sql"INSERT INTO t_legacy_password_retired (user_id, retired_at) VALUES (5, NOW(3))".update.apply()
        }

        runV81()
        daysLeft("t_legacy_password_retirement") === List(-1L)
        DB.readOnly { implicit s =>
          sql"SELECT user_id FROM t_legacy_password_retired".map(_.long(1)).list.apply()
        } === List(5L)
      } finally dropCopies()
    }
  }
}
