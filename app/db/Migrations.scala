package db

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import play.api.{Configuration, Mode}

/** The one place that configures and runs Flyway: the app at start
  * ([[modules.DatabaseMigrations]], so also the Gatling fixture's and the specs' Play apps), the
  * shared test database (`SharedTestDb`) and the migration specs that build a database up to an
  * older version.
  *
  * A database is built by running the versioned migrations (`V1__` onward) in
  * `conf/db/migration/default`; there is no baseline script. The `B44__Baseline.sql` that was
  * there until 2026-11 never ran anywhere: Flyway 9.16.3 has no `B` (baseline migration) prefix
  * at all and skipped the file, while from 9.21.0 Flyway Community applies a `B` file to an
  * empty database instead of the migrations up to its version, and B44, edited by hand, didn't
  * match V1..V44 (V51 then failed). See docs/plans/2026-11-03-flyway.md.
  *
  * The settings are the `db.default.migration.*` keys flyway-play read before (production.conf
  * sets them): an unknown key fails the start instead of being ignored.
  */
object Migrations {

  /** conf/db/migration/default, on the classpath (the app's jar; the test classpath has conf). */
  val DefaultLocation = "classpath:db/migration/default"

  /** @param auto                    Prod and Dev: migrate at start (else refuse to start with pending migrations)
    * @param validateOnMigrate       validate the applied migrations' checksums before migrating
    * @param validateOnStart         Prod and Dev without `auto`: validate at start too
    * @param baselineOnMigrate       `initOnMigrate`: baseline a non-empty schema without history
    * @param outOfOrder              run a migration older than the latest applied one
    * @param ignoreMigrationPatterns validation patterns to ignore; none by default, as flyway-play set
    *                                (Flyway's own default ignores `*:future`)
    * @param target                  migrate up to this version only (the migration specs)
    */
  final case class Settings(
      auto: Boolean = false,
      validateOnMigrate: Boolean = true,
      validateOnStart: Boolean = false,
      baselineOnMigrate: Boolean = false,
      outOfOrder: Boolean = false,
      ignoreMigrationPatterns: Seq[String] = Nil,
      cleanDisabled: Boolean = true,
      locations: Seq[String] = Seq(DefaultLocation),
      target: Option[String] = None
  )

  object Settings {

    private val Known = Set(
      "auto", "validateOnMigrate", "validateOnStart", "initOnMigrate", "outOfOrder",
      "ignoreMigrationPatterns", "cleanDisabled", "cleanOnValidationError")

    /** `db.<db>.migration.*`. `cleanOnValidationError` is accepted only as false: Flyway 10
      * dropped the option, and a database is never cleaned (dropped) by the app.
      */
    def fromConfig(config: Configuration, db: String = "default"): Settings = {
      val sub = config.getOptional[Configuration](s"db.$db.migration").getOrElse(Configuration.empty)
      val unknown = sub.subKeys -- Known
      require(unknown.isEmpty, s"db.$db.migration: unsupported settings ${unknown.toSeq.sorted.mkString(", ")}")
      require(!sub.getOptional[Boolean]("cleanOnValidationError").getOrElse(false),
        s"db.$db.migration.cleanOnValidationError = true is not supported")
      val defaults = Settings()
      Settings(
        auto = sub.getOptional[Boolean]("auto").getOrElse(defaults.auto),
        validateOnMigrate = sub.getOptional[Boolean]("validateOnMigrate").getOrElse(defaults.validateOnMigrate),
        validateOnStart = sub.getOptional[Boolean]("validateOnStart").getOrElse(defaults.validateOnStart),
        baselineOnMigrate = sub.getOptional[Boolean]("initOnMigrate").getOrElse(defaults.baselineOnMigrate),
        outOfOrder = sub.getOptional[Boolean]("outOfOrder").getOrElse(defaults.outOfOrder),
        ignoreMigrationPatterns = sub.getOptional[Seq[String]]("ignoreMigrationPatterns").getOrElse(defaults.ignoreMigrationPatterns),
        cleanDisabled = sub.getOptional[Boolean]("cleanDisabled").getOrElse(defaults.cleanDisabled)
      )
    }
  }

  /** Flyway on this database with these settings. Flyway opens its own connections (not the
    * app's pool): a script's session settings never reach a pooled connection.
    */
  def flyway(url: String, user: String, password: String, settings: Settings = Settings()): Flyway = {
    val conf = Flyway.configure(getClass.getClassLoader)
      .dataSource(url, user, password)
      .locations(settings.locations: _*)
      .validateOnMigrate(settings.validateOnMigrate)
      .baselineOnMigrate(settings.baselineOnMigrate)
      .outOfOrder(settings.outOfOrder)
      .ignoreMigrationPatterns(settings.ignoreMigrationPatterns: _*)
      .cleanDisabled(settings.cleanDisabled)
    settings.target.foreach(t => conf.target(t))
    conf.load()
  }

  def migrate(url: String, user: String, password: String, settings: Settings = Settings()): MigrateResult =
    flyway(url, user, password, settings).migrate()

  /** The pending migrations refused a start: their versions and scripts, no SQL. */
  final class PendingMigrations(pending: Seq[String])
      extends IllegalStateException(
        s"The database has ${pending.size} pending migration(s): ${pending.mkString(", ")}. " +
          "Set db.default.migration.auto = true (e.g. -Ddb.default.migration.auto=true) to apply them at start.")

  /** What the app does at start, by mode, as flyway-play's PlayInitializer did, except in Dev:
    * Test always migrates; Prod and Dev migrate with `auto`, and otherwise refuse to start while
    * a migration is pending (Dev had flyway-play's "apply" page instead).
    */
  def onStart(config: Configuration, mode: Mode): Unit =
    config.getOptional[String]("db.default.url").foreach { url =>
      val settings = Settings.fromConfig(config)
      val user = config.getOptional[String]("db.default.username").orNull
      val password = config.getOptional[String]("db.default.password").orNull
      val fw = flyway(url, user, password, settings)
      if (mode == Mode.Test || settings.auto) fw.migrate()
      else {
        val pending = fw.info().pending().toSeq
        if (pending.nonEmpty)
          throw new PendingMigrations(pending.map(m => s"${Option(m.getVersion).getOrElse("R")} (${m.getScript})"))
        if (settings.validateOnStart) fw.validate()
      }
      warnIfGalleryIndexAscending(url, user, password)
    }

  private val logger = play.api.Logger(getClass)

  /** Whether the server stores descending index keys (MariaDB 10.8+) while vote's gallery index
    * still has `rate` ascending: V85 ran before 10.8, and its ALTER wasn't run again after the
    * move (docs/deployment.md, "MariaDB 10.11: V85's gallery index"). False when there is no
    * `vote` table yet.
    */
  def galleryIndexLeftAscending(url: String, user: String, password: String): Boolean =
    scala.util.Using.resource(java.sql.DriverManager.getConnection(url, user, password)) { c =>
      scala.util.Using.resource(c.createStatement()) { st =>
        scala.util.Using.resource(st.executeQuery(
          """SELECT VERSION(), (SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vote' AND COLUMN_NAME = 'rate'
                 AND INDEX_NAME IN ('vote_jury_round_rate_mon_page_idx', 'vote_jury_round_ratedesc_mon_page_idx')
                 AND COLLATION = 'A')""")) { rs =>
          rs.next()
          val version = rs.getString(1).split("[.-]").take(2).map(_.toInt)
          version(0) * 1000 + version(1) >= 10008 && rs.getInt(2) > 0
        }
      }
    }

  /** The start's backstop for V85's re-run: a WARN, never a failed start. */
  private def warnIfGalleryIndexAscending(url: String, user: String, password: String): Unit =
    scala.util.Try(galleryIndexLeftAscending(url, user, password)) match {
      case scala.util.Success(true) =>
        logger.warn("vote's gallery index has rate ascending on a server with descending index keys: " +
          "run V85's ALTER again (docs/deployment.md, \"MariaDB 10.11: V85's gallery index\")")
      case scala.util.Success(false) => ()
      case scala.util.Failure(e) => logger.warn(s"Could not check vote's gallery index: ${e.getClass.getSimpleName}")
    }
}
