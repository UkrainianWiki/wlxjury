package controllers

import play.api.http.HttpErrorHandler
import play.api.mvc._
import play.utils.UriEncoding

import java.net.URI
import javax.inject.Inject

/** The webjars the pages load (layout.scala.html), served from their jars on the classpath,
  * and no others: the classpath holds more (Tapir's Swagger UI, for one), which no page needs.
  * Add a webjar to `WebJars.Served` to serve it.
  */
class WebJars @Inject() (assets: Assets, errorHandler: HttpErrorHandler, cc: ControllerComponents)
    extends AbstractController(cc) {

  def at(file: String): Action[AnyContent] =
    if (WebJars.served(file)) assets.at(WebJars.Path, file)
    else Action.async(request => errorHandler.onClientError(request, NOT_FOUND, "Not found"))
}

object WebJars {
  val Path = "/META-INF/resources/webjars"

  /** The webjars' names, the first segment of their files' paths. */
  val Served: Set[String] = Set("bootstrap", "bootstrap-icons", "jquery", "tom-select")

  /** Whether `file` (as routed, maybe URL-encoded) is in a served webjar once decoded and
    * normalised, as Assets resolves it. */
  def served(file: String): Boolean = {
    val decoded = UriEncoding.decodePath(file, "utf-8")
    val normalised =
      try new URI(null, null, "/" + decoded.replace('\\', '/'), null).normalize().getPath
      catch { case _: Exception => "" }
    !normalised.split('/').contains("..") &&
    normalised.split('/').filter(_.nonEmpty).headOption.exists(Served.contains)
  }
}
