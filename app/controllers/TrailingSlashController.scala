package controllers

import play.api.http.HttpErrorHandler
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents, RequestHeader}

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import scala.util.Try

/** A page GET (or HEAD) whose path ends in `/` and that no other route takes (the last route,
  * a wildcard path then a slash): e.g. `/auth/`, typed or pasted, is sent to `/auth`, the query kept. A
  * redirect only, so no sign-in: the target's own action decides (a signed-out `/rounds/` lands
  * on the sign-in page with its returnTo). Never off the site: the target is a local path
  * ([[Secured.safeLocalPath]]), not one that decodes to `//host` or a backslash; otherwise, and
  * for a path of slashes only, the usual 404 page.
  */
class TrailingSlashController @Inject() (cc: ControllerComponents, errorHandler: HttpErrorHandler)
    extends AbstractController(cc) {

  def untrail(path: String): Action[AnyContent] = Action.async { request =>
    TrailingSlashController.target(request) match {
      case Some(location) => scala.concurrent.Future.successful(Redirect(location, FOUND))
      case None           => errorHandler.onClientError(request, NOT_FOUND, "")
    }
  }
}

object TrailingSlashController {

  /** The request's raw path without its trailing slashes and with its raw query, if that is a
    * local path ([[Secured.safeLocalPath]]) that stays one decoded (no `//` start, no
    * backslash) and differs from the request's.
    */
  def target(request: RequestHeader): Option[String] = {
    val trimmed = request.path.reverse.dropWhile(_ == '/').reverse
    val decoded = Try(URLDecoder.decode(trimmed.replace("+", "%2B"), StandardCharsets.UTF_8)).toOption
    val query = request.rawQueryString
    Some(trimmed)
      .filter(p => p.nonEmpty && p != request.path)
      .filter(_ => decoded.exists(d => d.startsWith("/") && !d.startsWith("//") && !d.contains('\\')))
      .flatMap(p => Secured.safeLocalPath(Some(if (query.isEmpty) p else p + "?" + query)))
  }
}
