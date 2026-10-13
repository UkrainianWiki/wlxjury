package controllers

import modules.BlockingExecutionContext
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import services.ReadinessCheck

import javax.inject.Inject
import scala.concurrent.Future
import scala.util.control.NonFatal

class ReadinessController @Inject()(
    cc: ControllerComponents,
    check: ReadinessCheck,
    ec: BlockingExecutionContext
) extends AbstractController(cc) {
  private val identity = {
    val stream = getClass.getClassLoader.getResourceAsStream("wlxjury-build.json")
    require(stream != null, "Missing packaged release identity")
    try Json.parse(stream) finally stream.close()
  }

  def ready: Action[AnyContent] = Action.async {
    Future {
      // No JDBC URLs, credentials, or exception messages in this public response.
      try {
        val expected = (identity \ "schemaVersion").as[String]
        if (check.schemaVersion().contains(expected)) Ok(identity).withHeaders(CACHE_CONTROL -> "no-store")
        else ServiceUnavailable(Json.obj("status" -> "unavailable")).withHeaders(CACHE_CONTROL -> "no-store")
      } catch {
        case NonFatal(_) =>
          ServiceUnavailable(Json.obj("status" -> "unavailable")).withHeaders(CACHE_CONTROL -> "no-store")
      }
    }(ec)
  }
}
