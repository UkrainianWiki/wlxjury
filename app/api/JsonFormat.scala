package api

import controllers.Greeting
import org.intracer.wmua.ContestJury
import play.api.libs.json.{Json, OFormat}

trait JsonFormat {
  implicit val greetingFormat: OFormat[Greeting] = Json.format[Greeting]
  implicit val contestFormat: OFormat[ContestJury] = Json.format[ContestJury]
}
