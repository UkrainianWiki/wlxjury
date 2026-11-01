package controllers

import db.scalikejdbc.{Round, User}
import modules.SignInExecutionContext
import play.api.data.Forms._
import play.api.data._
import play.api.i18n.{I18nSupport, Lang}
import play.api.mvc._
import services.{PasswordHasher, UserService}

import javax.inject.Inject
import scala.concurrent.Future

/** Sign-in and sign-up check or compute a bcrypt hash, and may wait for a turn to
  * ([[PasswordHasher]]): they run on `signIn`, never on Play's default dispatcher.
  */
class LoginController @Inject() (
    val admin: UserService,
    cc: ControllerComponents,
    signIn: SignInExecutionContext,
    themeCookies: ThemeCookies = ThemeCookies.default
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
            // all the juror's files, in their order
            Redirect(RoundList.landing(round, u).pageUrl(1))
          }
      case u if u.hasRole(User.ROOT_ROLE) =>
        Redirect(routes.ContestController.list())
      case u if u.hasAnyRole(User.ADMIN_ROLES) =>
        Redirect(routes.UserController.users(u.contestId))
      case _ =>
        // the reason (not a member, an inactive membership, no role) is derived
        onUnAuthorized(user, request, Secured.denialOf(rolePermission(Secured.ViewerRoles)))
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

  def auth(): Action[AnyContent] = Action.async { implicit request =>
    // the wait for a turn to check the password counts from here, the time queued included
    val deadline = PasswordHasher.default.deadline()
    Future(signInResult(deadline))(signIn)
  }

  /** The sign-in's result; `deadline` ends the wait for a turn to check the password. */
  private[controllers] def signInResult(deadline: Long)(implicit request: Request[AnyContent]): Result = {
    val returnTo = Secured.safeLocalPath(
      request.body.asFormUrlEncoded.flatMap(_.get("returnTo").flatMap(_.headOption))
    )
    val bound = loginForm.bindFromRequest()
    bound.fold(
      formWithErrors => noStore(BadRequest(views.html.index(formWithErrors, returnTo))),
      { case (login, password) =>
        try {
          User
            .login(login, password, deadline)
            .map(signedIn(_, login, returnTo))
            .getOrElse(
              noStore(BadRequest(views.html.index(bound.withGlobalError("invalid.user.or.password"), returnTo)))
            )
        } catch {
          // too many sign-ins at once: the page asks to try again
          case _: PasswordHasher.Busy =>
            noStore(ServiceUnavailable(views.html.index(bound.withGlobalError("signin.busy"), returnTo)))
              .withHeaders(RETRY_AFTER -> "5")
        }
      }
    )
  }

  private def signedIn(user: User, login: String, returnTo: Option[String])(implicit request: RequestHeader): Result = {
    val latest = Secured.inCurrentContest(user, None)
    // the latest contest where the user may see a page, else the latest one
    // defensive: inCurrentContest already picks an active membership; kept should its rules change
    val inContest = Secured.permitted(latest, _.hasAnyRole(Secured.ViewerRoles)).getOrElse(latest)
    val sessionData = Seq(Secured.UserName -> login) ++
      inContest.contestId.map(id => Secured.CurrentContestId -> id.toString)
    val nextPage = returnTo.fold(indexRedirect(inContest))(url => Redirect(url))
    val result = nextPage.withSession(sessionData: _*)
    // the user's colour mode, if they chose one, follows them to this browser
    val withTheme = themeCookies.setStored(result, user.theme)
    user.lang.fold(withTheme)(l => withTheme.withLang(Lang(l)))
  }

  def signUpView(): Action[AnyContent] = Action { implicit request =>
    noStore(Ok(views.html.signUp(signUpForm)))
  }

  /** Creates the account (the first one is root), hashing its password: on `signIn`. */
  def signUp(): Action[AnyContent] = Action.async { implicit request =>
    Future(signUpResult)(signIn)
  }

  private def signUpResult(implicit request: Request[AnyContent]): Result = {
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

  /** The sign-out page: a button that signs out (POST, [[logout]]), for old links and
    * bookmarks to `GET /logout`, which no longer sign out by themselves.
    */
  def logoutPage(returnTo: Option[String] = None): Action[AnyContent] = Action { implicit request =>
    noStore(Ok(views.html.logout(Secured.safeLocalPath(returnTo))))
  }

  /** Signs out (a POST with the CSRF token): a new session, and the login page, which
    * comes back to `returnTo` (a local path only) after signing in again.
    */
  def logout(): Action[AnyContent] = Action { implicit request =>
    val returnTo = Secured.safeLocalPath(
      request.body.asFormUrlEncoded.flatMap(_.get("returnTo").flatMap(_.headOption))
    )
    Redirect(routes.LoginController.login(returnTo)).withNewSession
  }

  /** Old links to the error page: only known message keys, never the link's text. */
  def error(message: String): EssentialAction = withAuth() { user => implicit request =>
    val messageKey = if (LoginController.ErrorPageKeys.contains(message)) message else "error.unknown"
    Ok(views.html.errorPage(Some(user), "error.title", messageKey))
  }

  /** The password is checked once, by [[auth]] ([[User.login]]), not by the form. */
  val loginForm: Form[(String, String)] = Form(
    tuple(
      "login" -> nonEmptyText,
      "password" -> nonEmptyText
    )
  )

  /** The login is an email: a session's non-email login is read as a wiki account
    * ([[User.byUserName]]), which would be someone else's.
    */
  val signUpForm: Form[(String, String, String)] = Form(
    tuple(
      "login" -> email.transform[String](_.trim.toLowerCase, identity),
      // not blank, at most 72 bytes (bcrypt's limit)
      "password" -> nonEmptyText.verifying(PasswordHasher.newPassword),
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
