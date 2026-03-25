package db.scalikejdbc

import com.dimafeng.testcontainers.MariaDBContainer
import org.specs2.mutable.Specification
import org.testcontainers.utility.DockerImageName
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers.running

import java.io.File
import java.nio.file.Files

class SchemaBaselineSpec extends Specification {

  "schema baseline generator" should {
    "generate B44 baseline migration" in {
      if (sys.props.get("generate.baseline").isEmpty)
        skipped("Pass -Dgenerate.baseline=true to regenerate the baseline")

      val container = MariaDBContainer(
        dockerImageName = DockerImageName.parse("mariadb:10.6.22"),
        dbName = "wlxjury",
        dbUsername = "WLXJURY_DB_USER",
        dbPassword = "WLXJURY_DB_PASSWORD"
      )

      container.start()
      try {
        val app = new GuiceApplicationBuilder()
          .configure(Map(
            "db.default.driver"   -> container.driverClassName,
            "db.default.username" -> container.username,
            "db.default.password" -> container.password,
            "db.default.url"      -> container.jdbcUrl
          ))
          .build()

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
          val outputFile = new File("conf/db/migration/default/B44__Baseline.sql")
          Files.writeString(outputFile.toPath, sql)

          sql must not(beEmpty)
        }
      } finally {
        container.stop()
      }
    }
  }
}
