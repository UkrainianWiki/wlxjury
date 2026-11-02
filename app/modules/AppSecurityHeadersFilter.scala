package modules

import play.api.Configuration
import play.api.mvc.{RequestHeader, Result}
import play.filters.headers.{SecurityHeadersConfig, SecurityHeadersFilter}

import javax.inject.{Inject, Singleton}

/** Play's security headers (`play.filters.headers.*`: `X-Frame-Options`,
  * `X-Content-Type-Options`, `Referrer-Policy`, ...) on every response, which `application.conf`
  * enables in place of [[play.filters.headers.SecurityHeadersFilter]], with two changes
  * (security batch S7, P4):
  *
  *  - `Permissions-Policy` from `wlxjury.headers.permissionsPolicy` (unset or empty: none):
  *    the browser features no page uses (camera, microphone, geolocation, payment, ...) off;
  *  - a page whose URL holds a secret (a mailed link's token, `LoginController.linkPage`) keeps
  *    its own `Referrer-Policy: no-referrer`. Play's filter would replace it with the configured
  *    one, and `allowActionSpecificHeaders` would let an action replace any of the headers; here
  *    only that stricter value is kept, every other header is the configured one.
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
}

object AppSecurityHeadersFilter {
  val PermissionsPolicyKey = "wlxjury.headers.permissionsPolicy"
  val PermissionsPolicy    = "Permissions-Policy"
  val ReferrerPolicy       = "Referrer-Policy"
  val NoReferrer           = "no-referrer"
}
