package controllers

import db.scalikejdbc.{Round, User}
import play.api.data.Forms._
import play.api.data._
import play.api.i18n.{I18nSupport, Lang}
import play.api.mvc._
import services.UserService

import javax.inject.Inject

class LoginController @Inject() (
    val admin: UserService,
    cc: ControllerComponents
) extends Secured(cc)
    with I18nSupport {

  def index: EssentialAction = withAuth() { user => implicit request =>
    indexRedirect(user)
  }

  /** The user's start page: a page of their role, else a page saying why there is none. */
  private def indexRedirect(user: User)(implicit request: RequestHeader): Result = {
    user match {
      case u if u.hasAnyRole(User.ORG_COM_ROLES) =>
        Redirect(routes.RoundController.currentRoundStat())
      case u if u.hasAnyRole(User.JURY_ROLES) =>
        Round
          .activeRounds(u)
          .headOption
          .fold {
            // a state, not an error: the juror waits for a round
            Ok(views.html.errorPage(Some(u), "error.title", "no.round.yet"))
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
        onUnAuthorized(user, request, Secured.Denial(reason = Some("not_member")))
    }
  }

  private def noStore(result: Result): Result =
    result.withHeaders("Cache-Control" -> "no-store")

  def login(returnTo: Option[String] = None): Action[AnyContent] = Action { implicit request =>
    val safeReturnTo = Secured.safeLocalPath(returnTo)
    val result =
      if (User.count() > 0) Ok(views.html.index(loginForm, safeReturnTo))
      else Ok(views.html.signUp(signUpForm))
    noStore(result)
  }

  def auth(): Action[AnyContent] = Action { implicit request =>
    val returnTo = Secured.safeLocalPath(
      request.body.asFormUrlEncoded.flatMap(_.get("returnTo").flatMap(_.headOption))
    )
    loginForm
      .bindFromRequest()
      .fold(
        formWithErrors => noStore(BadRequest(views.html.index(formWithErrors, returnTo))),
        { case (login, password) =>
          User
            .login(login, password)
            .map { user =>
              val inContest = Secured.inCurrentContest(user, None)
              val sessionData = Seq(Secured.UserName -> login) ++
                inContest.contestId.map(id => Secured.CurrentContestId -> id.toString)
              val nextPage = returnTo.fold(indexRedirect(inContest))(url => Redirect(url))
              val result = nextPage.withSession(sessionData: _*)
              user.lang.fold(result)(l => result.withLang(Lang(l)))
            }
            .getOrElse(
              noStore(BadRequest(views.html.index(loginForm.withGlobalError("invalid.user.or.password"), returnTo)))
            )
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
          val user = admin.createAccount(newUser, newUser, None)
          val result = indexRedirect(user).withSession(Secured.UserName -> user.emailLo)
          user.lang.fold(result)(l => result.withLang(Lang(l)))
        }
      )
  }

  /** Logout and clean the session.
    *
    * @return
    *   Index page
    */
  def logout(returnTo: Option[String] = None): Action[AnyContent] = Action {
    Redirect(routes.LoginController.login(Secured.safeLocalPath(returnTo))).withNewSession
  }

  /** Old links to the error page: only known message keys, never the link's text. */
  def error(message: String): EssentialAction = withAuth() { user => implicit request =>
    val messageKey = if (LoginController.ErrorPageKeys.contains(message)) message else "error.unknown"
    Ok(views.html.errorPage(Some(user), "error.title", messageKey))
  }

  val loginForm: Form[(String, String)] = Form(
    tuple(
      "login" -> nonEmptyText,
      "password" -> nonEmptyText
    ) verifying ("invalid.user.or.password", { case (l, p) =>
      User.login(l, p).isDefined
    })
  )

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

object LoginController {

  /** The messages `/error?message=` shows; any other text shows error.unknown. */
  val ErrorPageKeys: Set[String] =
    Set("no.round.yet", "error.not.found", "error.contest.not.found", "error.round.not.found")
}
