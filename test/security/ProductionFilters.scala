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

  /** `body` with the JVM-wide app that has the release's filters and their settings (CSRF on),
    * on the shared database, truncated first: started at its first use, like
    * [[db.scalikejdbc.SharedPlayApp]], and never stopped by a spec (a shutdown hook stops it).
    * So `body` must not stop it: no `running(app)`, no `app.stop()`, no `TestServer` (whose stop
    * stops the app). Its app-level state lives as long as the JVM: the pre-S3 upgrade window
    * ([[modules.SessionUpgradeWindow]]) is read once, at the first old cookie, so an example
    * that moves the window's row needs a fresh app ([[withApp]]); so does one that binds its own
    * modules or needs other config ([[withAppWith]], [[OwnApp]]).
    */
  def withSharedApp[T](body: Application => T): T = {
    val app = shared
    SharedTestDb.truncateAll()
    body(app)
  }

  private lazy val shared: Application = {
    SharedTestDb.init()
    val app = build(Map.empty, Nil)
    // as SharedPlayApp: the hasManyThrough association round.users needs
    db.scalikejdbc.Round.usersRef
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      scala.util.Try(Await.result(app.stop(), Duration(30, "seconds")))
    }))
    app
  }

  /** `body` with a fresh app that has the release's filters and their settings (CSRF on), on the
    * shared, truncated database, stopped after it: for an example that the shared app
    * ([[withSharedApp]]) can't serve. */
  def withApp[T](body: Application => T): T = withAppWith(Map.empty)(body)

  /** [[withApp]] with more `config` and binding `overrides`. */
  def withAppWith[T](config: Map[String, Any], overrides: GuiceableModule*)(body: Application => T): T = {
    SharedTestDb.init()
    SharedTestDb.truncateAll()
    val app = build(config, overrides)
    try body(app)
    finally stop(app)
  }

  /** An app with the release's filters and `config` that a spec's examples share: started at
    * its first use, the shared database truncated before each, stopped by [[stop]] (the spec's
    * `afterAll`). For examples that need the same config of their own, so that it starts once
    * per spec instead of once per example.
    */
  final class OwnApp(config: Map[String, Any]) {
    private var app: Option[Application] = None

    def apply[T](body: Application => T): T = {
      val started = synchronized {
        app.getOrElse {
          SharedTestDb.init()
          val built = build(config, Nil)
          app = Some(built)
          built
        }
      }
      SharedTestDb.truncateAll()
      body(started)
    }

    def stop(): Unit = synchronized {
      app.foreach(ProductionFilters.stop)
      app = None
    }
  }

  private def build(config: Map[String, Any], overrides: Seq[GuiceableModule]): Application =
    new GuiceApplicationBuilder()
      .configure(settings)
      .configure(config ++ Map(
        "db.default.driver"   -> SharedTestDb.driverClassName,
        "db.default.username" -> SharedTestDb.username,
        "db.default.password" -> SharedTestDb.password,
        "db.default.url"      -> SharedTestDb.jdbcUrl
      ))
      .overrides(overrides: _*)
      .build()

  /** Stops `app`; its pool was ScalikeJDBC's default, so the shared database's is put back. */
  private def stop(app: Application): Unit =
    try Await.result(app.stop(), Duration(30, "seconds"))
    finally SharedTestDb.reregisterDefault()
}
