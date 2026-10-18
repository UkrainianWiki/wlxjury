package controllers

import db.scalikejdbc.User
import play.api.data.Forms._
import play.api.data._
import play.api.i18n.I18nSupport
import play.api.mvc._
import services.UserService
import services.oauth.OAuthSettings

import javax.inject.Inject

class LoginController @Inject() (
    val admin: UserService,
    val oauthSettings: OAuthSettings,
    cc: ControllerComponents
) extends Secured(cc)
    with I18nSupport
    with LoginResults {

  def index: EssentialAction = withAuth() { user => _ =>
    indexRedirect(user)
  }

  def login: Action[AnyContent] = Action { implicit request =>
    if (User.count() > 0) loginPage(loginForm)
    else noStore(Ok(views.html.signUp(signUpForm)))
  }

  def auth(): Action[AnyContent] = Action { implicit request =>
    loginForm
      .bindFromRequest()
      .fold(
        formWithErrors => loginPage(formWithErrors, BadRequest),
        { case (login, password) =>
          User
            .login(login, password)
            .map(loggedIn(_, login))
            .getOrElse(loginPage(loginForm.withGlobalError("invalid.user.or.password"), BadRequest))
        }
      )
  }

  def signUpView(): Action[AnyContent] = Action { implicit request =>
    noStore(Ok(views.html.signUp(signUpForm)))
  }

  def signUp(): Action[AnyContent] = Action { implicit request =>
    signUpForm
      .bindFromRequest()
      .fold(
        formWithErrors => noStore(BadRequest(views.html.signUp(formWithErrors))),
        { case (login, password, _) =>
          val roles = if (User.count() > 0) Set.empty[String] else Set(User.ROOT_ROLE)
          val newUser =
            new User(fullname = "", email = login, password = Some(password), roles = roles)
          // always a new account: signing up never hands out an existing one
          loggedIn(admin.createAccount(newUser, newUser, None), login)
        }
      )
  }

  /** Logout and clean the session.
    *
    * @return
    *   Index page
    */
  def logout: Action[AnyContent] = Action {
    Redirect(routes.LoginController.login()).withNewSession
  }

  def error(message: String): EssentialAction = withAuth() { user => implicit request =>
    Ok(views.html.error(message, user, user.getId))
  }

  /** The login is an email: a session's non-email login is read as a wiki account
    * ([[User.byUserName]]), which would be someone else's.
    */
  val signUpForm: Form[(String, String, String)] = Form(
    tuple(
      "login" -> email.transform[String](_.trim.toLowerCase, identity),
      "password" -> nonEmptyText,
      "repeat.password" -> nonEmptyText
    ) verifying ("invalid.user.or.password", fields =>
      fields match {
        case (_, p1, p2) => p1 == p2
      }) verifying ("signup.email.taken", fields =>
      fields match {
        case (login, _, _) => User.findByEmail(login).isEmpty
      })
  )
}
