import api.ApiFailure
import controllers.Secured
import db.scalikejdbc.User
import play.api.http.DefaultHttpErrorHandler
import play.api.http.Status.{BAD_REQUEST, NOT_FOUND}
import play.api.i18n._
import play.api.libs.json.Json
import play.api.mvc.Results._
import play.api.mvc._
import play.api.{Configuration, Environment, Logging}

import modules.ResponseSecurityHeaders

import javax.inject.Inject
import scala.concurrent._
import scala.util.Try

/** Errors outside the controllers' own pages: a page with a message from conf/messages,
  * never the error's text (a client error's message or an exception's), and for a server
  * error the reference of its log line. Under `/api/` the same as the API's JSON
  * ([[ApiFailure]]): e.g. a body over the parser's limit, `{"error": "payload_too_large"}`.
  *
  * Every answer has the security headers and the content security policy
  * ([[ResponseSecurityHeaders]]): Play's server calls `onServerError` for an exception that leaves
  * the filters, and `onClientError` for a request it can't parse, outside them.
  */
class ErrorHandler @Inject() (
    environment: Environment,
    configuration: Configuration,
    val langs: Langs,
    val messagesApi: MessagesApi
) extends DefaultHttpErrorHandler(environment, configuration, sourceMapper = None, router = None)
    with I18nSupport
    with Logging {

  private val securityHeaders = new ResponseSecurityHeaders(configuration)

  override def onClientError(
      unparsed: RequestHeader,
      statusCode: Int,
      message: String
  ): Future[Result] = {
    val request = ErrorHandler.withCookieCells(unparsed)
    implicit val impReq: RequestHeader = request
    val line = s"client_error method=${request.method} path=${Secured.logPath(request.path)} status=$statusCode " +
      s"message=${Secured.logValue(message)}"
    if (statusCode == NOT_FOUND) logger.debug(line) else logger.warn(line)
    val messageKey = statusCode match {
      case NOT_FOUND   => "error.not.found"
      case BAD_REQUEST => "error.bad.request"
      case _           => "error.client"
    }
    Future.successful(securityHeaders(request,
      if (ApiFailure.isApiRequest(request)) Status(statusCode)(Json.obj("error" -> ApiFailure.clientErrorBody(statusCode).error))
      else Status(statusCode)(views.html.errorPage(sessionUser(request), "error.title", messageKey, Seq(statusCode)))
    ))
  }

  override def onServerError(unparsed: RequestHeader, exception: Throwable): Future[Result] = {
    val request = ErrorHandler.withCookieCells(unparsed)
    implicit val impReq: RequestHeader = request
    val reference = Secured.newReference()
    logger.error(s"server_error ref=$reference method=${request.method} path=${Secured.logPath(request.path)}", exception)
    Future.successful(securityHeaders(request,
      if (ApiFailure.isApiRequest(request))
        InternalServerError(Json.obj("error" -> ApiFailure.serverError(reference).body.error, "reference" -> reference))
      else
        InternalServerError(
          views.html.errorPage(sessionUser(request), "error.title", "error.server", Seq(reference),
            Some(reference), Some(Secured.referenceTime()))
        )
    ))
  }

  /** The signed-in user for the page's navigation, if the database can tell. */
  private def sessionUser(request: RequestHeader): Option[User] =
    Try(Secured.sessionUserOf(request))
      .toOption
      .flatten
}

object ErrorHandler {

  /** `request`, with no cookies, session or flash where Play's server has none to give: a request
    * it can't parse (e.g. a malformed URI) reaches `onClientError` without them, and the page's
    * messages (the language cookie) and navigation would fail on it, leaving the client no answer.
    */
  def withCookieCells(request: RequestHeader): RequestHeader = {
    import play.api.mvc.request.{Cell, RequestAttrKey}
    val withCookies =
      if (request.attrs.contains(RequestAttrKey.Cookies)) request
      else request.addAttr(RequestAttrKey.Cookies, Cell(Cookies(Seq.empty)))
    val withSession =
      if (withCookies.attrs.contains(RequestAttrKey.Session)) withCookies
      else withCookies.addAttr(RequestAttrKey.Session, Cell(Session()))
    if (withSession.attrs.contains(RequestAttrKey.Flash)) withSession
    else withSession.addAttr(RequestAttrKey.Flash, Cell(Flash()))
  }
}
