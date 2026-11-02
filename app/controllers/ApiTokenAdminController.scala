package controllers

import db.scalikejdbc.{ApiTokenJdbc, User}
import play.api.i18n.I18nSupport
import play.api.mvc.{ControllerComponents, EssentialAction, RequestHeader, Result}
import services.ApiTokenService

import javax.inject.Inject

/** Root's view of another user's personal API tokens (P13): the list, and revoking one or all
  * of them (a leaked token, an account being closed). Root only; the revokes are POSTs with
  * the CSRF token, logged with ids only ([[ApiTokenService.revokeByRoot]]). The user's own
  * tokens are managed on their profile ([[UserController.createApiToken]]).
  */
class ApiTokenAdminController @Inject() (cc: ControllerComponents) extends Secured(cc) with I18nSupport {

  private val rootOnly = rolePermission(Set(User.ROOT_ROLE))

  private def userNotFound(user: User)(implicit request: RequestHeader): Result =
    NotFound(views.html.errorPage(Some(user), "error.title", "error.user.not.found")(cc.messagesApi.preferred(request), request))

  private def tokenNotFound(user: User)(implicit request: RequestHeader): Result =
    NotFound(views.html.errorPage(Some(user), "error.title", "error.api.token.not.found")(cc.messagesApi.preferred(request), request))

  def tokens(userId: Long): EssentialAction = withAuth(rootOnly) { user => implicit request =>
    User.findById(userId).fold(userNotFound(user)) { owner =>
      Ok(views.html.userApiTokens(user, owner, ApiTokenJdbc.findByUser(userId)))
    }
  }

  def revoke(userId: Long, id: Long): EssentialAction = withAuth(rootOnly) { user => implicit request =>
    ApiTokenJdbc.find(id).filter(_.userId == userId).fold(tokenNotFound(user)) { _ =>
      val revoked = ApiTokenService.revokeByRoot(user.getId, userId, Some(id))
      Redirect(routes.ApiTokenAdminController.tokens(userId)).flashing("api-tokens-revoked" -> revoked.toString)
    }
  }

  def revokeAll(userId: Long): EssentialAction = withAuth(rootOnly) { user => implicit request =>
    User.findById(userId).fold(userNotFound(user)) { _ =>
      val revoked = ApiTokenService.revokeByRoot(user.getId, userId)
      Redirect(routes.ApiTokenAdminController.tokens(userId)).flashing("api-tokens-revoked" -> revoked.toString)
    }
  }
}
