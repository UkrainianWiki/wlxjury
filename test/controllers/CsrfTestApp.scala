package controllers

import db.scalikejdbc.SharedTestDb
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder

import scala.concurrent.Await
import scala.concurrent.duration.Duration

/** An app with the CSRF filter on, as in production: the test config
  * (`test/resources/application.conf`) disables it for the Gatling simulations.
  */
object CsrfTestApp {

  /** `body` with an app that has the CSRF filter, on the shared, truncated database. */
  def withCsrfApp[T](body: Application => T): T = {
    SharedTestDb.init()
    SharedTestDb.truncateAll()
    val csrfApp = new GuiceApplicationBuilder()
      .configure(
        "db.default.driver"     -> SharedTestDb.driverClassName,
        "db.default.username"   -> SharedTestDb.username,
        "db.default.password"   -> SharedTestDb.password,
        "db.default.url"        -> SharedTestDb.jdbcUrl,
        "play.filters.disabled" -> Seq("play.filters.hosts.AllowedHostsFilter")
      )
      .build()
    try body(csrfApp)
    finally {
      Await.result(csrfApp.stop(), Duration(30, "seconds"))
      SharedTestDb.reregisterDefault()
    }
  }

  /** A CSRF token the app accepts, to put in the session as a browser's has it. */
  def token(app: Application): String =
    app.injector.instanceOf[play.filters.csrf.CSRF.TokenProvider].generateToken
}
