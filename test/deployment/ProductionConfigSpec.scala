package deployment

import com.typesafe.config.{ConfigException, ConfigFactory, ConfigResolveOptions}
import org.specs2.mutable.Specification

import java.io.File
import scala.jdk.CollectionConverters._

class ProductionConfigSpec extends Specification {
  private def production = ConfigFactory.parseFile(new File("conf/wlxjury/production.conf"))
    .withFallback(ConfigFactory.defaultReference())
  private val variables = Map(
    "WLXJURY_DB_HOST" -> "localhost:3307", "WLXJURY_DB" -> "jury_copy",
    "WLXJURY_DB_USER" -> "jury", "WLXJURY_DB_PASSWORD" -> "space $ # ` \\ \" password",
    "PLAY_HTTP_SECRET_KEY" -> "an-existing-signing-key-at-least-32-bytes")
  private def resolved(vars: Map[String, String]) = ConfigFactory.parseMap(vars.asJava)
    .withFallback(production).resolve(ConfigResolveOptions.noSystem())

  "the external production configuration" should {
    "require the signing key even though the included app has a development default" in {
      resolved(variables - "PLAY_HTTP_SECRET_KEY") must throwA[ConfigException.UnresolvedSubstitution]
    }
    "require DB credentials and preserve special characters without shell expansion" in {
      resolved(variables).getString("db.default.password") mustEqual variables("WLXJURY_DB_PASSWORD")
      resolved(variables - "WLXJURY_DB_PASSWORD") must throwA[ConfigException.UnresolvedSubstitution]
    }
    "inherit release defaults, migrate automatically, validate history and prohibit baselining or cleaning" in {
      val config = resolved(variables)
      config.getInt("db.default.hikaricp.maximumPoolSize") mustEqual 30
      config.getBoolean("db.default.migration.auto") must beTrue
      config.getBoolean("db.default.migration.validateOnMigrate") must beTrue
      config.getBoolean("db.default.migration.initOnMigrate") must beFalse
      config.getBoolean("db.default.migration.cleanDisabled") must beTrue
      config.getBoolean("db.default.migration.cleanOnValidationError") must beFalse
      config.getStringList("db.default.migration.ignoreMigrationPatterns").asScala must beEmpty
    }
  }
}
