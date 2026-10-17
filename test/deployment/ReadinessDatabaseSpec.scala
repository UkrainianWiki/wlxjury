package deployment

import db.scalikejdbc.{SharedPlayApp, SharedTestDb}
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import services.ReadinessCheck
import scalikejdbc._

class ReadinessDatabaseSpec extends Specification {
  sequential
  "readiness using the actual application pool" should {
    "report the migrated version and refuse a failed Flyway entry" in {
      SharedPlayApp.init()
      implicit val session: DBSession = AutoSession
      val check = SharedPlayApp.app.injector.instanceOf[ReadinessCheck]
      val stream = getClass.getClassLoader.getResourceAsStream("wlxjury-build.json")
      val expected = try (Json.parse(stream) \ "schemaVersion").as[String] finally stream.close()
      check.schemaVersion() must beSome(expected)
      val rank = sql"SELECT MAX(installed_rank) FROM flyway_schema_history".map(_.int(1)).single.apply().get + 1
      try {
        sql"""INSERT INTO flyway_schema_history (installed_rank, version, description, type, script,
          installed_by, execution_time, success) VALUES ($rank, '999', 'test failure', 'SQL',
          'V999__test_failure.sql', 'test', 0, 0)""".update.apply()
        check.schemaVersion() must beNone
      } finally {
        sql"DELETE FROM flyway_schema_history WHERE installed_rank = $rank".update.apply()
        SharedTestDb.reregisterDefault()
      }
    }
  }
}
