package db.scalikejdbc

import com.dimafeng.testcontainers.MariaDBContainer
import org.specs2.mutable.Specification
import org.testcontainers.utility.DockerImageName
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers.running

import java.io.File
import java.nio.file.Files

/** Dumps the schema a fresh database reaches (every migration) to target/schema-baseline.sql,
  * as a starting point for a baseline script. It is not a migration: a baseline is cut, checked
  * against replaying the migrations and wired in deliberately (docs/plans/2026-11-03-flyway.md);
  * the B44__Baseline.sql this once wrote into conf/db/migration/default never ran anywhere.
  */
class SchemaBaselineSpec extends Specification {

  "schema baseline generator" should {
    "dump the migrated schema" in {
      if (sys.props.get("generate.baseline").isEmpty)
        skipped("Pass -Dgenerate.baseline=true to regenerate the baseline")

      val container = SharedTestDb.forTests(MariaDBContainer(
        dockerImageName = DockerImageName.parse("mariadb:10.6.22"),
        dbName = "wlxjury",
        dbUsername = "WLXJURY_DB_USER",
        dbPassword = "WLXJURY_DB_PASSWORD"
      ))

      db.TestDocker.start("the baseline database", container)
      try {
        val app = new GuiceApplicationBuilder()
          .configure(Map(
            "db.default.driver"   -> container.driverClassName,
            "db.default.username" -> container.username,
            "db.default.password" -> container.password,
            "db.default.url"      -> container.jdbcUrl
          ))
          .build()

        // stopping the app closes its pool, which PlayDBApiAdapterModule made ScalikeJDBC's
        // default: give the shared test database's back to the specs after this one
        try {
          running(app) {
            val result = container.container.execInContainer(
              "mysqldump",
              "--no-data",
              "--skip-lock-tables",
              "--skip-add-drop-table",
              "--no-tablespaces",
              "--ignore-table=wlxjury.flyway_schema_history",
              "-u", "WLXJURY_DB_USER",
              "-pWLXJURY_DB_PASSWORD",
              "wlxjury"
            )

            val sql = result.getStdout
            val outputFile = new File("target/schema-baseline.sql")
            Files.createDirectories(outputFile.toPath.getParent)
            Files.writeString(outputFile.toPath, sql)

            sql must not(beEmpty)
          }
        } finally {
          if (SharedTestDb.isStarted) SharedTestDb.reregisterDefault()
        }
      } finally {
        container.stop()
      }
    }
  }
}
