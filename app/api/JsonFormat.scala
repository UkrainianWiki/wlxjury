package api

import controllers.Greeting
import org.intracer.wmua.ContestJury
import play.api.libs.json.{Json, OFormat}

trait JsonFormat {
  implicit val greetingFormat: OFormat[Greeting] = Json.format[Greeting]
  // with defaults: a body without the series fields (V109: seriesId, retired), which the API
  // never writes, reads as before
  implicit val contestFormat: OFormat[ContestJury] = Json.using[Json.WithDefaultValues].format[ContestJury]
  implicit val apiErrorFormat: OFormat[ApiError] = Json.format[ApiError]
}
