import controllers.Secured
import db.scalikejdbc.User
import play.api.http.DefaultHttpErrorHandler
import play.api.http.Status.{BAD_REQUEST, NOT_FOUND}
import play.api.i18n._
import play.api.mvc.Results._
import play.api.mvc._
import play.api.{Configuration, Environment, Logging}

import javax.inject.Inject
import scala.concurrent._
import scala.util.Try

/** Errors outside the controllers' own pages: a page with a message from conf/messages,
  * never the error's text (a client error's message or an exception's), and for a server
  * error the reference of its log line.
  */
class ErrorHandler @Inject() (
    environment: Environment,
    configuration: Configuration,
    val langs: Langs,
    val messagesApi: MessagesApi
) extends DefaultHttpErrorHandler(environment, configuration, sourceMapper = None, router = None)
    with I18nSupport
    with Logging {

  override def onClientError(
      request: RequestHeader,
      statusCode: Int,
      message: String
  ): Future[Result] = {
    implicit val impReq: RequestHeader = request
    val line = s"client_error method=${request.method} path=${Secured.logValue(request.path)} status=$statusCode " +
      s"message=${Secured.logValue(message)}"
    if (statusCode == NOT_FOUND) logger.debug(line) else logger.warn(line)
    val messageKey = statusCode match {
      case NOT_FOUND   => "error.not.found"
      case BAD_REQUEST => "error.bad.request"
      case _           => "error.client"
    }
    Future.successful(
      Status(statusCode)(views.html.errorPage(sessionUser(request), "error.title", messageKey, Seq(statusCode)))
    )
  }

  override def onServerError(request: RequestHeader, exception: Throwable): Future[Result] = {
    implicit val impReq: RequestHeader = request
    val reference = Secured.newReference()
    logger.error(s"server_error ref=$reference method=${request.method} path=${Secured.logValue(request.path)}", exception)
    Future.successful(
      InternalServerError(
        views.html.errorPage(sessionUser(request), "error.title", "error.server", Seq(reference),
          Some(reference), Some(Secured.referenceTime()))
      )
    )
  }

  /** The signed-in user for the page's navigation, if the database can tell. */
  private def sessionUser(request: RequestHeader): Option[User] =
    Try(request.session.get(Secured.UserName).flatMap(Secured.sessionUser(_, request.session.get(Secured.CurrentContestId))))
      .toOption
      .flatten
}
