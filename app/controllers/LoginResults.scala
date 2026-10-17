package controllers

import db.scalikejdbc.{Round, User}
import play.api.data.Form
import play.api.data.Forms.{nonEmptyText, tuple}
import play.api.i18n.{I18nSupport, Lang}
import play.api.mvc._
import services.oauth.OAuthSettings

/** The login page and where a login leads, for the password and the Google and Wikimedia
  * logins.
  */
trait LoginResults { this: Secured with I18nSupport =>

  def oauthSettings: OAuthSettings

  val loginForm: Form[(String, String)] = Form(
    tuple(
      "login" -> nonEmptyText,
      "password" -> nonEmptyText
    ) verifying ("invalid.user.or.password", { case (l, p) =>
      User.login(l, p).isDefined
    })
  )

  def noStore(result: Result): Result =
    result.withHeaders("Cache-Control" -> "no-store")

  /** The login page, with the Google and Wikimedia buttons this host offers. */
  def loginPage(form: Form[(String, String)], status: Status = Ok)(implicit request: RequestHeader): Result =
    noStore(status(views.html.index(form, oauthSettings.providersFor(request.host))))

  /** The user, just logged in: a new session and their start page, in their language. */
  def loggedIn(user: User, login: String): Result = {
    val (inContest, session) = Secured.loginSession(user, login)
    val result = indexRedirect(inContest).withSession(session)
    user.lang.fold(result)(l => result.withLang(Lang(l))(messagesApi))
  }

  def indexRedirect(user: User): Result = {
    user match {
      case u if u.hasAnyRole(User.ORG_COM_ROLES) =>
        Redirect(routes.RoundController.currentRoundStat())
      case u if u.hasAnyRole(User.JURY_ROLES) =>
        Round
          .activeRounds(u)
          .headOption
          .fold {
            Redirect(routes.LoginController.error("no.round.yet"))
          } { round =>
            if (round.isBinary) {
              Redirect(
                routes.GalleryController.list(u.getId, 1, "all", round.getId, rate = Some(0))
              )
            } else {
              Redirect(routes.GalleryController.byRate(u.getId, 0, "all", 0))
            }
          }
      case u if u.hasRole(User.ROOT_ROLE) =>
        Redirect(routes.ContestController.list())
      case u if u.hasAnyRole(User.ADMIN_ROLES) =>
        Redirect(routes.UserController.users(u.contestId))
      case _ =>
        Redirect(routes.LoginController.error("You don't have permission to access this page"))
    }
  }
}
