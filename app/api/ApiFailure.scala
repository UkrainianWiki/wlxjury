package api

import sttp.model.StatusCode

/** An error's JSON body: a fixed code, never request or exception text (CLAUDE.md, "Errors"),
  * and for a denial or a server error the reference of its log line.
  */
final case class ApiError(error: String, reference: Option[String] = None)

/** An error answer of the API: its status, the `WWW-Authenticate` challenge of a 401, the body. */
final case class ApiFailure(status: StatusCode, challenge: Option[String], body: ApiError)

object ApiFailure {
  val Unauthorized: ApiFailure = ApiFailure(StatusCode.Unauthorized, Some("Bearer"), ApiError("unauthorized"))
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
}
