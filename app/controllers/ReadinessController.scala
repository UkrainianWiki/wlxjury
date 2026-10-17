package controllers

import modules.BlockingExecutionContext
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents, RequestHeader}
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

  def ready: Action[AnyContent] = Action.async { request =>
    // Only the deployment tool on the server asks. Requests through Apache carry the
    // client in X-Forwarded-For, which Play resolves for its trusted local proxy, so
    // they get no build details and cost no DB query.
    if (!ReadinessController.isLocal(request)) Future.successful(NotFound)
    else Future {
      // No JDBC URLs, credentials, or exception messages in this response.
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

object ReadinessController {
  /** Literal loopback addresses only: never resolve a forwarded name. */
  def isLocal(request: RequestHeader): Boolean = {
    val address = request.remoteAddress
    address.startsWith("127.") || address == "::1" || address == "0:0:0:0:0:0:0:1"
  }
}
