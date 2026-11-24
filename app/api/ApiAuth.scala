package api

import controllers.{ReadinessController, Secured}
import db.scalikejdbc.{ApiTokenScope, User}
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

  /** A personal API token, by its id, and its scope ([[ApiTokenScope]]). */
  final case class ByToken(tokenId: Long, scope: ApiTokenScope = ApiTokenScope.Full) extends Via {
    def logFields: Seq[(String, String)] =
      Seq("auth" -> "token", "tokenId" -> tokenId.toString) ++
        Option.when(scope != ApiTokenScope.Full)("scope" -> scope.value)
  }

  final case class Caller(user: User, via: Via)

  /** Every API endpoint by its name (`Api.secured`), and whether a read-only, server-only token
    * ([[ApiTokenScope.LocalRead]]) may call it: an allowlist, so that a new endpoint is closed to
    * such tokens until it is classified here. Only GET endpoints that only read may be `true`
    * (`api.ApiEndpointScopeSpec` checks both, and that every endpoint is listed).
    */
  val Endpoints: Map[String, Boolean] = Map(
    "listContests"   -> true,
    "getContest"     -> true,
    "contestHealth"  -> true,
    "recentActivity" -> true,
    "reuseReport"    -> true,
    "reuseContest"   -> true,
    "createContest"  -> false,
    "updateContest"  -> false
  )

  /** The endpoints a read-only, server-only token may call. */
  val LocalReadEndpoints: Set[String] = Endpoints.collect { case (name, true) => name }.toSet

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
    * otherwise; another scheme is logged at DEBUG only.
    */
  def caller(request: RequestHeader): Option[Caller] =
    request.headers.getAll(HeaderNames.AUTHORIZATION) match {
      case Seq() => Secured.sessionUserOf(request).map(Caller(_, BySession))
      case Seq(authorization) =>
        bearerToken(authorization) match {
          case None =>
            logger.debug("api_auth_refused reason=not_bearer")
            None
          case Some(token) =>
            ApiTokenService.authenticate(token).flatMap { case (found, user) =>
              // authenticate refuses a token of an unknown scope; never taken as full here either
              found.scope.map(scope => Caller(user, ByToken(found.id, scope)))
            }
        }
      case _ =>
        logger.debug("api_auth_refused reason=several_authorization_headers")
        None
    }

  /** The caller, permitted by `permission` as an action's user is ([[Secured.checked]]): else
    * 401, or 403 with the reference of the denial's log line ([[Secured.logDenial]]), which
    * also says how the caller came in. A read-only, server-only token ([[ApiTokenScope.LocalRead]])
    * is refused first, before the permission, on anything but a GET of an allowed `endpoint` from
    * the server itself ([[scopeRefusal]]); that line also has the client's address as Play
    * resolved it (`remote=`, behind Apache the forwarded one): where a leaked token is used.
    */
  def authorize(request: RequestHeader, permission: User => Boolean, endpoint: String): Either[ApiFailure, Caller] =
    caller(request).toRight(ApiFailure.Unauthorized).flatMap { found =>
      def forbidden(denial: Secured.Denial, extra: Seq[(String, String)] = Nil) =
        ApiFailure.forbidden(Secured.logDenial(found.user, request, denial, found.via.logFields ++ extra))
      scopeRefusal(found.via, request, endpoint) match {
        case Some(reason) =>
          Left(forbidden(Secured.Denial(resource = Some(s"api:$endpoint"), reason = Some(reason)),
            Seq("remote" -> Secured.logValue(request.remoteAddress))))
        case None =>
          Secured
            .checked(found.user, permission)
            .left.map(forbidden(_))
            .map(permitted => found.copy(user = permitted))
      }
    }

  /** Why the token's scope refuses the request, if it does (the denial's `reason`): a
    * [[ApiTokenScope.LocalRead]] token is taken on GET only (`token_read_only`), on the endpoints
    * of [[LocalReadEndpoints]] only (`token_endpoint`), and only from the server itself
    * (`token_not_local`, [[fromServerItself]]). The session and full tokens are not limited here.
    */
  def scopeRefusal(via: Via, request: RequestHeader, endpoint: String): Option[String] = via match {
    case ByToken(_, ApiTokenScope.LocalRead) =>
      if (request.method != "GET") Some("token_read_only")
      else if (!LocalReadEndpoints.contains(endpoint)) Some("token_endpoint")
      else if (!fromServerItself(request)) Some("token_not_local")
      else None
    case _ => None
  }

  /** A request from the server itself, not through Apache (support diagnostics plan §6, "one
    * test", shared with a later loopback-only route): a literal loopback address
    * ([[ReadinessController.isLocal]]) **and** no forwarding header (`Forwarded`, or any
    * `X-Forwarded-*`). Apache adds `X-Forwarded-For` to every request it proxies, and Play takes
    * the client's address from it only for a trusted proxy on loopback; the header's presence
    * alone refuses, whatever its value, so a forwarded request never passes either way. Nothing
    * here trusts a header: headers can only refuse.
    */
  def fromServerItself(request: RequestHeader): Boolean =
    ReadinessController.isLocal(request) &&
      !request.headers.keys.exists { name =>
        val lower = name.toLowerCase(java.util.Locale.ROOT)
        lower == "forwarded" || lower.startsWith("x-forwarded-")
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
