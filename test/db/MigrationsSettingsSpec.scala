package db

import org.specs2.mutable.Specification
import play.api.Configuration

/** db.default.migration.* as [[Migrations.Settings.fromConfig]] reads them (production's:
  * deployment.ProductionConfigSpec).
  */
class MigrationsSettingsSpec extends Specification {

  private def read(pairs: (String, Any)*) =
    Migrations.Settings.fromConfig(Configuration(pairs.map { case (k, v) => s"db.default.migration.$k" -> v }: _*))

  "the migration settings" should {
    "default to validating, no baseline, nothing ignored and no automatic start migration" in {
      Migrations.Settings.fromConfig(Configuration.empty) mustEqual Migrations.Settings()
      Migrations.Settings() mustEqual Migrations.Settings(auto = false, validateOnMigrate = true,
        baselineOnMigrate = false, outOfOrder = false, ignoreMigrationPatterns = Nil, cleanDisabled = true)
    }

    "map initOnMigrate to baselineOnMigrate, as flyway-play did" in {
      read("initOnMigrate" -> true).baselineOnMigrate must beTrue
    }

    "refuse an unknown key instead of ignoring it" in {
      read("auto" -> true, "baselineVersion" -> "44") must throwA[IllegalArgumentException]("baselineVersion")
    }

    "refuse cleanOnValidationError = true, accept false" in {
      read("cleanOnValidationError" -> true) must throwA[IllegalArgumentException]
      read("cleanOnValidationError" -> false) mustEqual Migrations.Settings()
    }
  }
}
