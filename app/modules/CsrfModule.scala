package modules

import api.ApiAuth
import play.api.http.HttpConfiguration
import play.api.inject.{Binding, Module}
import play.api.libs.crypto.{CSRFTokenSigner, CSRFTokenSignerProvider}
import play.api.{Configuration, Environment}
import play.filters.csrf.{CSRF, CSRFConfig, CSRFFilter}

import javax.inject.{Inject, Provider, Singleton}

/** Play's CSRF module (`play.filters.csrf.CSRFModule`, which `application.conf` disables in
  * its favour), the same bindings with two changes, in what the filter checks and when it
  * creates a token ([[ApiCsrfConfigProvider]]). The filter stays `play.filters.csrf.CSRFFilter`.
  */
class CsrfModule extends Module {
  def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] =
    Seq(
      bind[play.libs.crypto.CSRFTokenSigner].to(classOf[play.libs.crypto.DefaultCSRFTokenSigner]),
      bind[CSRFTokenSigner].toProvider[CSRFTokenSignerProvider],
      bind[CSRFConfig].toProvider[ApiCsrfConfigProvider],
      bind[CSRF.TokenProvider].toProvider[CSRF.TokenProviderProvider],
      bind[CSRFFilter].toSelf
    ) ++ CSRF.ErrorHandler.bindingsFromConfiguration(environment, configuration)
}

/** The configured CSRF check (`play.filters.csrf.*`), except for an API request with a Bearer
  * token and without the session ([[ApiAuth.bearerWithoutSession]]): a tool's, which has no
  * CSRF token to send and no session a forged request could use. Any request with the session
  * cookie is checked as before, a Bearer header or not.
  *
  * A request the filter doesn't check (GET, HEAD, OPTIONS) gets a token if it has none, whatever its
  * Accept header: Play creates one by default only for a request that accepts HTML, but every
  * page renders HTML, and its forms (the sign-in form, the navbar's sign-out) need the token. A
  * client sending `Accept: application/json` or `text/plain` got a 500 from the sign-in page
  * (26.11.3). The token reaches the session only when a page renders it (Play's filter keeps an
  * unrendered one out of an HttpOnly session), so a JSON answer still sets no cookie.
  */
@Singleton
class ApiCsrfConfigProvider @Inject() (configuration: Configuration, httpConfiguration: HttpConfiguration)
    extends Provider[CSRFConfig] {
  lazy val get: CSRFConfig = {
    val configured = CSRFConfig.fromConfiguration(configuration)
    val sessionCookieName = httpConfiguration.session.cookieName
    configured.copy(
      shouldProtect = request =>
        configured.shouldProtect(request) && !ApiAuth.bearerWithoutSession(request, sessionCookieName),
      createIfNotFound = request => !configured.checkMethod(request.method)
    )
  }
}
