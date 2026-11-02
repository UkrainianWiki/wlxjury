package modules

import play.api.Configuration
import play.api.http.HeaderNames
import play.api.mvc.{RequestHeader, Result}
import play.filters.csp.{CSPConfig, CSPFilter, CSPProcessor}
import play.filters.headers.{SecurityHeadersConfig, SecurityHeadersFilter}

import javax.inject.{Inject, Singleton}

/** Play's security headers (`play.filters.headers.*`: `X-Frame-Options`,
  * `X-Content-Type-Options`, `Referrer-Policy`, ...) on every response that passes the filters,
  * which `application.conf` enables in place of [[play.filters.headers.SecurityHeadersFilter]],
  * with two changes (security batch S7, P4):
  *
  *  - `Permissions-Policy` from `wlxjury.headers.permissionsPolicy` (unset or empty: none):
  *    the browser features no page uses (camera, microphone, geolocation, payment, ...) off;
  *  - a page whose URL holds a secret (a mailed link's token, `LoginController.linkPage`) keeps
  *    its own `Referrer-Policy: no-referrer`. Play's filter would replace it with the configured
  *    one, and `allowActionSpecificHeaders` would let an action replace any of the headers; here
  *    only that stricter value is kept, every other header is the configured one.
  *
  * A response Play's server makes outside the filters (a server error, a request it can't route)
  * gets the same headers from the error handler ([[ResponseSecurityHeaders]]).
  */
@Singleton
class AppSecurityHeadersFilter @Inject() (config: SecurityHeadersConfig, configuration: Configuration)
    extends SecurityHeadersFilter(config) {

  private val permissionsPolicy: Option[String] =
    configuration.getOptional[String](AppSecurityHeadersFilter.PermissionsPolicyKey).map(_.trim).filter(_.nonEmpty)

  override protected def headers(request: RequestHeader, result: Result): Seq[(String, String)] = {
    val keepsNoReferrer = result.header.headers.get(AppSecurityHeadersFilter.ReferrerPolicy)
      .exists(_.trim.equalsIgnoreCase(AppSecurityHeadersFilter.NoReferrer))
    val configured = super.headers(request, result)
      .filterNot { case (name, _) => keepsNoReferrer && name.equalsIgnoreCase(AppSecurityHeadersFilter.ReferrerPolicy) }
    configured ++ permissionsPolicy.map(AppSecurityHeadersFilter.PermissionsPolicy -> _)
  }

  /** The headers this filter sets on `result`. */
  def headersFor(request: RequestHeader, result: Result): Seq[(String, String)] = headers(request, result)
}

object AppSecurityHeadersFilter {
  val PermissionsPolicyKey = "wlxjury.headers.permissionsPolicy"
  val PermissionsPolicy    = "Permissions-Policy"
  val ReferrerPolicy       = "Referrer-Policy"
  val NoReferrer           = "no-referrer"
}

/** The headers of the two header filters ([[AppSecurityHeadersFilter]] and Play's [[CSPFilter]]),
  * each only if `play.filters.enabled` has it, from the same configuration and the same code, for
  * a response made outside the filters: Play's server calls the error handler's `onServerError`
  * for an exception that leaves the filters, and `onClientError` for a request it can't route at
  * all (`ErrorHandler`). On a response that then passes the filters they set the same values
  * again.
  */
final class ResponseSecurityHeaders(configuration: Configuration) {

  private val enabled: Set[String] =
    configuration.get[Seq[String]]("play.filters.enabled").toSet -- configuration.get[Seq[String]]("play.filters.disabled")

  private val headersFilter: Option[AppSecurityHeadersFilter] =
    Option.when(enabled.contains(classOf[AppSecurityHeadersFilter].getName))(
      new AppSecurityHeadersFilter(SecurityHeadersConfig.fromConfiguration(configuration), configuration))

  private val csp: Option[CSPProcessor] =
    Option.when(enabled.contains(classOf[CSPFilter].getName))(CSPProcessor(CSPConfig.fromConfiguration(configuration)))

  /** `result` with the headers. */
  def apply(request: RequestHeader, result: Result): Result = {
    val policy = csp.flatMap(_.process(request)).toSeq.map { r =>
      (if (r.reportOnly) HeaderNames.CONTENT_SECURITY_POLICY_REPORT_ONLY else HeaderNames.CONTENT_SECURITY_POLICY) ->
        r.directives
    }
    result.withHeaders(headersFilter.toSeq.flatMap(_.headersFor(request, result)) ++ policy: _*)
  }
}
