package api

import sttp.model.StatusCode

/** An error's JSON body: a fixed code, never request or exception text (CLAUDE.md, "Errors"),
  * and for a denial or a server error the reference of its log line.
  */
final case class ApiError(error: String, reference: Option[String] = None)

/** An error answer of the API: its status, the `WWW-Authenticate` challenge of a 401, the body. */
final case class ApiFailure(status: StatusCode, challenge: Option[String], body: ApiError)

object ApiFailure {
  /** `unauthenticated`, the code the JSON endpoints' 401 has (`Secured.withAuthJson`). */
  val Unauthorized: ApiFailure = ApiFailure(StatusCode.Unauthorized, Some("Bearer"), ApiError("unauthenticated"))
  val NotFound: ApiFailure = ApiFailure(StatusCode.NotFound, None, ApiError("not_found"))
  val BadRequest: ApiFailure = ApiFailure(StatusCode.BadRequest, None, ApiError("bad_request"))

  def forbidden(reference: String): ApiFailure =
    ApiFailure(StatusCode.Forbidden, None, ApiError("forbidden", Some(reference)))

  def serverError(reference: String): ApiFailure =
    ApiFailure(StatusCode.InternalServerError, None, ApiError("server_error", Some(reference)))

  /** A request that could not be decoded (a bad body or path id): its status, no detail. */
  def undecodable(status: StatusCode): ApiFailure = status match {
    case StatusCode.Unauthorized         => Unauthorized
    case StatusCode.PayloadTooLarge      => ApiFailure(status, None, ApiError("payload_too_large"))
    case StatusCode.UnsupportedMediaType => ApiFailure(status, None, ApiError("unsupported_media_type"))
    case _                               => BadRequest
  }

  /** The body of an error Play answers before or around Tapir ([[ErrorHandler]]): a body over
    * the parser's limit (413), and the other client errors by their status.
    */
  def clientErrorBody(status: Int): ApiError = status match {
    case 404 => ApiError("not_found")
    case 413 => ApiError("payload_too_large")
    case 415 => ApiError("unsupported_media_type")
    case _   => ApiError("bad_request")
  }

  /** The API's answers to a request under `/api/` ([[ApiAuth.PathPrefix]]) are JSON, also those
    * of Play's error handlers (CSRF, client and server errors).
    */
  def isApiRequest(request: play.api.mvc.RequestHeader): Boolean = request.path.startsWith(ApiAuth.PathPrefix)
}
