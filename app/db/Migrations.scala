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
      warnIfGalleryIndexOutdated(url, user, password)
    }

  private val logger = play.api.Logger(getClass)

  /** The juror gallery index's names (V82's, V85's with rate descending) and its columns since
    * V99, in order.
    */
  val GalleryIndexes: Seq[String] = Seq("vote_jury_round_rate_mon_page_idx", "vote_jury_round_ratedesc_mon_page_idx")
  val GalleryIndexColumns: String = "jury_id,round_id,rate,monument_id,page_id,voted_at"

  /** vote's gallery index as the start finds it: whether the server stores descending index keys
    * (MariaDB 10.8+) while a gallery index still has `rate` ascending (V85 and V99 ran before
    * 10.8, and V99 wasn't run again after the move), and whether a gallery index lacks V99's
    * trailing `voted_at` (V99's statement undone, e.g. by V85 run again by hand after V99). Both
    * false when there is no `vote` table or gallery index yet.
    */
  final case class GalleryIndexState(leftAscending: Boolean, withoutVotedAt: Boolean)

  def galleryIndexState(url: String, user: String, password: String): GalleryIndexState =
    scala.util.Using.resource(java.sql.DriverManager.getConnection(url, user, password)) { c =>
      scala.util.Using.resource(c.createStatement()) { st =>
        val names = GalleryIndexes.map(n => s"'$n'").mkString(", ")
        scala.util.Using.resource(st.executeQuery(
          s"""SELECT VERSION(),
                (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vote' AND COLUMN_NAME = 'rate'
                   AND INDEX_NAME IN ($names) AND COLLATION = 'A'),
                (SELECT COUNT(*) FROM (SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
                   FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vote' AND INDEX_NAME IN ($names)
                   GROUP BY INDEX_NAME) g WHERE g.cols <> '$GalleryIndexColumns')""")) { rs =>
          rs.next()
          val version = rs.getString(1).split("[.-]").take(2).map(_.toInt)
          GalleryIndexState(
            leftAscending = version(0) * 1000 + version(1) >= 10008 && rs.getInt(2) > 0,
            withoutVotedAt = rs.getInt(3) > 0)
        }
      }
    }

  /** [[galleryIndexState]]'s `leftAscending`: run V99 again (docs/deployment.md, "MariaDB 10.11:
    * the vote gallery index (V85, V99)").
    */
  def galleryIndexLeftAscending(url: String, user: String, password: String): Boolean =
    galleryIndexState(url, user, password).leftAscending

  /** The start's backstop for V99's re-run: a WARN, never a failed start. */
  private def warnIfGalleryIndexOutdated(url: String, user: String, password: String): Unit =
    scala.util.Try(galleryIndexState(url, user, password)) match {
      case scala.util.Success(state) =>
        val problems = Seq(
          state.leftAscending -> "has rate ascending on a server with descending index keys",
          state.withoutVotedAt -> "lacks voted_at as its last column").collect { case (true, p) => p }
        if (problems.nonEmpty)
          logger.warn(s"vote's gallery index ${problems.mkString(" and ")}: run V99 again " +
            "(docs/deployment.md, \"MariaDB 10.11: the vote gallery index (V85, V99)\")")
      case scala.util.Failure(e) => logger.warn(s"Could not check vote's gallery index: ${e.getClass.getSimpleName}")
    }
}
