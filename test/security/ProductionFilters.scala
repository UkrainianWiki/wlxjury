package security

import com.typesafe.config.{Config, ConfigFactory, ConfigResolveOptions}
import db.scalikejdbc.SharedTestDb
import play.api.inject.guice.{GuiceApplicationBuilder, GuiceableModule}
import play.api.{Application, Configuration}
import play.filters.csp.CSPConfig

import java.io.File
import scala.concurrent.Await
import scala.concurrent.duration.Duration

/** The release's filter settings (`conf/application.conf`; the tests' config,
  * `test/resources/application.conf`, runs without the content security policy and with the
  * CSRF filter off): which filters run in which order, the content security policy
  * (`play.filters.csp`), the other security headers and the CSRF filter's settings.
  */
object ProductionFilters {

  /** The paths taken from the release's config. */
  val Paths: Seq[String] = Seq(
    "play.filters.enabled", "play.filters.disabled", "play.filters.csp", "play.filters.headers",
    "play.filters.csrf", "wlxjury.headers"
  )

  /** `conf/application.conf` over Play's reference config; its database settings stay
    * unresolved (they come from the environment), nothing here reads them. */
  lazy val release: Config =
    ConfigFactory.parseFile(new File("conf/application.conf"))
      .withFallback(ConfigFactory.defaultReference())
      .resolve(ConfigResolveOptions.noSystem().setAllowUnresolved(true))

  /** [[Paths]] of [[release]], to lay over an app's config. */
  lazy val settings: Configuration =
    Configuration(Paths.map(release.withOnlyPath).reduce(_ withFallback _))

  /** The `Content-Security-Policy` header the release sends, as Play's CSPFilter builds it. */
  lazy val cspHeader: String = {
    val config = CSPConfig.fromConfiguration(Configuration(release))
    config.directives.map(d => s"${d.name} ${d.value}").mkString("; ")
  }

  /** The policy's directives, by name. */
  lazy val cspDirectives: Map[String, Seq[String]] =
    CSPConfig.fromConfiguration(Configuration(release)).directives
      .map(d => d.name -> d.value.trim.split("\\s+").toSeq).toMap

  /** `body` with an app that has the release's filters and their settings (CSRF on), on the
    * shared, truncated database. */
  def withApp[T](body: Application => T): T = withAppWith(Map.empty)(body)

  /** [[withApp]] with more `config` and binding `overrides`. */
  def withAppWith[T](config: Map[String, Any], overrides: GuiceableModule*)(body: Application => T): T = {
    SharedTestDb.init()
    SharedTestDb.truncateAll()
    val app = new GuiceApplicationBuilder()
      .configure(settings)
      .configure(config ++ Map(
        "db.default.driver"   -> SharedTestDb.driverClassName,
        "db.default.username" -> SharedTestDb.username,
        "db.default.password" -> SharedTestDb.password,
        "db.default.url"      -> SharedTestDb.jdbcUrl
      ))
      .overrides(overrides: _*)
      .build()
    try body(app)
    finally {
      Await.result(app.stop(), Duration(30, "seconds"))
      SharedTestDb.reregisterDefault()
    }
  }
}
