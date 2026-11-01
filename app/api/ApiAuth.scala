package api

import controllers.Secured
import db.scalikejdbc.User
import play.api.Logging
import play.api.http.HeaderNames
import play.api.mvc.RequestHeader
import services.ApiTokenService

/** Who calls the REST API (`/api/...`, [[ApiRouter]]): a personal API token or the session.
  * Tokens are accepted here only, never on the page routes, the votes or `/theme`, which read
  * the session alone ([[Secured.userFromRequest]]).
  */
object ApiAuth extends Logging {

  /** How the caller came in, for the denial's log line. */
  sealed trait Via {
    def logFields: Seq[(String, String)]
  }

  case object BySession extends Via {
    def logFields: Seq[(String, String)] = Seq("auth" -> "session")
  }

  final case class ByToken(tokenId: Long) extends Via {
    def logFields: Seq[(String, String)] = Seq("auth" -> "token", "tokenId" -> tokenId.toString)
  }

  final case class Caller(user: User, via: Via)

  /** Where the API is served ([[ApiRouter]] in `conf/routes`). */
  val PathPrefix = "/api/"

  /** The token of an `Authorization` header of the Bearer scheme. */
  def bearerToken(authorization: String): Option[String] =
    authorization.trim.split("\\s+", 2) match {
      case Array(scheme, token) if scheme.equalsIgnoreCase("Bearer") && token.trim.nonEmpty => Some(token.trim)
      case _                                                                               => None
    }

  /** The caller: with an `Authorization` header, only a usable token of the Bearer scheme
    * ([[ApiTokenService.authenticate]]), the session ignored, so that a request never mixes
    * the two; without one, the session user in the session's current contest. None (401)
    * otherwise.
    */
  def caller(request: RequestHeader): Option[Caller] =
    request.headers.getAll(HeaderNames.AUTHORIZATION) match {
      case Seq() => Secured.sessionUserOf(request).map(Caller(_, BySession))
      case Seq(authorization) =>
        bearerToken(authorization) match {
          case None =>
            logger.warn("api_auth_refused reason=not_bearer")
            None
          case Some(token) =>
            ApiTokenService.authenticate(token).map { case (found, user) => Caller(user, ByToken(found.id)) }
        }
      case _ =>
        logger.warn("api_auth_refused reason=several_authorization_headers")
        None
    }

  /** The caller, permitted by `permission` as an action's user is ([[Secured.checked]]): else
    * 401, or 403 with the reference of the denial's log line ([[Secured.logDenial]]), which
    * also says how the caller came in.
    */
  def authorize(request: RequestHeader, permission: User => Boolean): Either[ApiFailure, Caller] =
    caller(request).toRight(ApiFailure.Unauthorized).flatMap { found =>
      Secured
        .checked(found.user, permission)
        .left.map(denial => ApiFailure.forbidden(Secured.logDenial(found.user, request, denial, found.via.logFields)))
        .map(permitted => found.copy(user = permitted))
    }

  /** A request under `/api/` with a Bearer token and without the session: the one kind of
    * write that needs no CSRF token ([[modules.CsrfModule]]). A browser never adds such a
    * header on its own, and it carries no session to ride on.
    */
  def bearerWithoutSession(request: RequestHeader, sessionCookieName: String): Boolean =
    request.path.startsWith(PathPrefix) &&
      (request.headers.getAll(HeaderNames.AUTHORIZATION) match {
        case Seq(authorization) => bearerToken(authorization).isDefined
        case _                  => false
      }) &&
      request.cookies.get(sessionCookieName).isEmpty &&
      request.session.isEmpty
}
