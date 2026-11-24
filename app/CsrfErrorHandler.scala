import api.ApiFailure
import controllers.Secured
import db.scalikejdbc.User
import play.api.Logging
import play.api.i18n.{I18nSupport, MessagesApi}
import play.api.libs.json.Json
import play.api.mvc.Results.{Forbidden, Redirect}
import play.api.mvc.{RequestHeader, Result}
import play.filters.csrf.CSRF

import javax.inject.Inject
import scala.concurrent.Future
import scala.util.Try

/** A request the CSRF filter refuses (`play.filters.csrf.errorHandler`): a form or vote of a
  * page whose token is stale, or whose session is gone (signed out, expired). Not a denial:
  * the user is told to reload the page (and to sign in if signed out); a script, and any
  * request under `/api/` (the REST API's callers), gets JSON `{error: "csrf", reference}`
  * (the gallery's votes, vote-errors.js); under `/api/` with a fixed hint
  * ([[CsrfErrorHandler.ApiHint]]): a tool's write is refused here when it has no Bearer token.
  * A signed-in user's refused sign-in POST (the form posted again) goes to a page saying which
  * account is signed in (303, [[controllers.LoginController.signedInPage]]).
  * Logged at WARN with the reference the page shows.
  */
class CsrfErrorHandler @Inject() (val messagesApi: MessagesApi) extends CSRF.ErrorHandler with I18nSupport with Logging {

  def handle(request: RequestHeader, msg: String): Future[Result] = {
    implicit val impReq: RequestHeader = request
    val reference = Secured.newReference()
    val user = sessionUser(request)
    logger.warn(s"csrf_failure ref=$reference method=${request.method} path=${Secured.logPath(request.path)} " +
      s"signedIn=${user.isDefined} ${Secured.accountFields(request, user)} message=${Secured.logValue(msg)}")
    Future.successful(
      if (request.path == CsrfErrorHandler.GraphQLPath)
        // the GraphQL endpoint's clients read `errors` (graphql.GraphQLController); the REST fields too
        Forbidden(Json.obj("error" -> "csrf", "reference" -> reference, "hint" -> CsrfErrorHandler.ApiHint,
          "errors" -> Json.arr(Json.obj("message" -> "csrf",
            "extensions" -> Json.obj("code" -> "CSRF", "reference" -> reference)))))
      else if (ApiFailure.isApiRequest(request))
        Forbidden(Json.obj("error" -> "csrf", "reference" -> reference, "hint" -> CsrfErrorHandler.ApiHint))
      else if (Secured.isScriptRequest(request)) Forbidden(Json.obj("error" -> "csrf", "reference" -> reference))
      // the sign-in form posted again by a signed-in user (a stale sign-in tab, a reload or Back
      // to a page that answered a sign-in before 26.11.13): nothing changes, no sign-in and no
      // contest switch; a page says which account is signed in, to go on as it or sign out
      // first (never the start page as it, which another person at a shared computer would use)
      else if (user.isDefined && CsrfErrorHandler.isSignInPost(request))
        Redirect(controllers.routes.LoginController.signedInPage)
      else
        Forbidden(views.html.errorPage(user, "error.title", "error.csrf", Seq.empty, Some(reference),
          Some(Secured.referenceTime()), signIn = user.isEmpty))
    )
  }

  /** The signed-in user for the page's navigation, if the database can tell. */
  private def sessionUser(request: RequestHeader): Option[User] =
    Try(Secured.sessionUserOf(request))
      .toOption.flatten
}

object CsrfErrorHandler {

  /** The GraphQL endpoint ([[graphql.GraphQLController]]), whose refusal also has GraphQL's `errors`. */
  val GraphQLPath = "/api/graphql"

  /** The password sign-in's POST ([[controllers.LoginController.auth]]). */
  def isSignInPost(request: RequestHeader): Boolean =
    request.method == "POST" && request.path == controllers.routes.LoginController.auth().url

  /** What an API caller refused here should send: a tool without the session, its token; a
    * signed-in browser, the session's CSRF token. */
  val ApiHint: String =
    "Send a personal API token (Authorization: Bearer <token>), or with the session cookie the Csrf-Token header."
}
