package controllers

import play.api.Application
import security.ProductionFilters

/** An app with the CSRF filter on, as in production: the test config
  * (`test/resources/application.conf`) disables it for the Gatling simulations.
  */
object CsrfTestApp {

  /** `body` with the JVM-wide app that has the release's filters (CSRF on), on the shared,
    * truncated database ([[ProductionFilters.withSharedApp]], whose limits apply: `body` must
    * not stop it, and an example that changes app-level state needs an app of its own,
    * [[ProductionFilters.withApp]]). */
  def withCsrfApp[T](body: Application => T): T = ProductionFilters.withSharedApp(body)

  /** A CSRF token the app accepts, to put in the session as a browser's has it. */
  def token(app: Application): String =
    app.injector.instanceOf[play.filters.csrf.CSRF.TokenProvider].generateToken
}
