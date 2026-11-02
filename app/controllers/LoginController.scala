package controllers

import db.scalikejdbc.{AccountTokenJdbc, Round, User}
import modules.SignInExecutionContext
import play.api.Logger
import play.api.data.Forms._
import play.api.data._
import play.api.i18n.{I18nSupport, Lang}
import play.api.mvc._
import services.{AccountTokenService, PasswordHasher, UserService}

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import javax.inject.Inject
import scala.concurrent.Future
import scala.util.control.NonFatal

/** Sign-in, and the pages a link mailed to an account opens: an invitation or a password link
  * (set the password, [[acceptInvitation]]), an email change ([[emailConfirm]]); and the first
  * account of an empty installation ([[setup]]). There is no sign-up: accounts are created by an
  * organizer's add, which invites the person ([[UserService.addToContest]]).
  *
  * Checking or computing a bcrypt hash may wait for a turn ([[PasswordHasher]]): those actions
  * run on `signIn`, never on Play's default dispatcher.
  */
class LoginController @Inject() (
    val admin: UserService,
    cc: ControllerComponents,
    signIn: SignInExecutionContext,
    themeCookies: ThemeCookies = ThemeCookies.default
) extends Secured(cc)
    with I18nSupport {

  private val accountLogger = Logger("security.account")

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

  /** A page opened from a mailed link: never cached, and its URL (which holds the token) never
    * sent as a referrer.
    */
  private def linkPage(result: Result): Result =
    result.withHeaders("Cache-Control" -> "no-store", "Referrer-Policy" -> "no-referrer")

  /** Every refused link: the same page, whatever the reason (N7). */
  private def linkInvalid(implicit request: RequestHeader): Result =
    linkPage(NotFound(views.html.accountLinkInvalid()))

  def login(returnTo: Option[String] = None): Action[AnyContent] = Action { implicit request =>
    val safeReturnTo = Secured.safeLocalPath(returnTo)
    // an empty installation: the page says to set it up (P20); there is no sign-up
    noStore(Ok(views.html.index(loginForm, safeReturnTo, noAccounts = User.count() == 0)))
  }

  def auth(): Action[AnyContent] = Action.async { implicit request =>
    // the wait for a turn to check the password counts from here, the time queued included
    val deadline = PasswordHasher.default.deadline()
    admitted(busy(views.html.index(loginForm.bindFromRequest().withGlobalError("signin.busy"), returnTo)))(
      signInResult(deadline))
  }

  private def returnTo(implicit request: Request[AnyContent]): Option[String] =
    Secured.safeLocalPath(request.body.asFormUrlEncoded.flatMap(_.get("returnTo").flatMap(_.headOption)))

  /** "Keep me signed in" (the form's checkbox, checked by default): a persistent cookie. A form
    * without it (unchecked, or a client that never sends it) gets a browser-session cookie.
    */
  private def keepSignedIn(implicit request: Request[AnyContent]): Boolean =
    request.body.asFormUrlEncoded.flatMap(_.get(LoginController.KeepField)).exists(_.exists(_.nonEmpty))

  /** 503 with the page, asking to try again: too many sign-ins at once. */
  private def busy(page: play.twirl.api.Html): Result =
    noStore(ServiceUnavailable(page)).withHeaders(RETRY_AFTER -> "5")

  /** `result` on `signIn`, unless [[PasswordHasher.Settings.maxQueued]] sign-ins and
    * password settings are already in flight: then `busyResult` at once, so that a flood never
    * builds a queue that outlasts it.
    */
  private def admitted(busyResult: => Result)(result: => Result): Future[Result] = {
    val hasher = PasswordHasher.default
    if (!hasher.admit()) Future.successful(busyResult)
    else
      try Future(try result finally hasher.leave())(signIn)
      catch { case NonFatal(e) => hasher.leave(); Future.failed(e) }
  }

  /** The sign-in's result; `deadline` ends the wait for a turn to check the password. */
  private[controllers] def signInResult(deadline: Long)(implicit request: Request[AnyContent]): Result = {
    val returnTo = this.returnTo
    val bound = loginForm.bindFromRequest()
    bound.fold(
      formWithErrors => noStore(BadRequest(views.html.index(formWithErrors, returnTo))),
      { case (login, password) =>
        try {
          User
            .login(login, password, deadline)
            .map(signedIn(_, login, returnTo, keepSignedIn))
            .getOrElse(
              noStore(BadRequest(views.html.index(bound.withGlobalError("invalid.user.or.password"), returnTo)))
            )
        } catch {
          // too many sign-ins at once: the page asks to try again
          case _: PasswordHasher.Busy => busy(views.html.index(bound.withGlobalError("signin.busy"), returnTo))
        }
      }
    )
  }

  /** A new session of `user` (as read when the password was checked: its auth version is the
    * one the session carries), in `contestId` if they are an active member of it, else in the
    * latest contest where they may see a page; issued now, persistent if `keep`
    * ([[SessionLifetime]]).
    */
  private def signedIn(user: User, login: String, returnTo: Option[String], keep: Boolean,
                       contestId: Option[Long] = None)(implicit request: RequestHeader): Result = {
    val latest = Secured.inCurrentContest(user, contestId.map(_.toString))
    // defensive: inCurrentContest already picks an active membership; kept should its rules change
    val inContest = Secured.permitted(latest, _.hasAnyRole(Secured.ViewerRoles)).getOrElse(latest)
    val sessionData = Secured.signedInSession(user, login, keep) ++
      inContest.contestId.map(id => Secured.CurrentContestId -> id.toString)
    val nextPage = returnTo.fold(indexRedirect(inContest))(url => Redirect(url))
    val result = nextPage.withSession(sessionData: _*)
    // the user's colour mode, if they chose one, follows them to this browser
    val withTheme = themeCookies.setStored(result, user.theme)
    user.lang.fold(withTheme)(l => withTheme.withLang(Lang(l)))
  }

  /** An invitation's or a password link's page: the form that sets the password, if the link
    * is live; else the same "invalid link" page for any reason (N7).
    */
  def invitation(token: String): Action[AnyContent] = Action { implicit request =>
    AccountTokenService.check(token, AccountTokenJdbc.SetsPassword) match {
      case Right((link, user)) =>
        linkPage(Ok(views.html.invitation(setPasswordForm.fill((token, "", "")), user.password.isDefined, link.purpose)))
      case Left(_) => linkInvalid
    }
  }

  /** Sets the password with an invitation or a password link (a POST with the CSRF token):
    * proves the email, ends the account's other sessions and API tokens, gives the roles of
    * its pending invitations ([[AccountTokenService.acceptPasswordLink]]), and signs in. The
    * hash is computed on `signIn` before anything is written.
    */
  def acceptInvitation(): Action[AnyContent] = Action.async { implicit request =>
    val deadline = PasswordHasher.default.deadline()
    val bound = setPasswordForm.bindFromRequest()
    admitted(busy(views.html.invitation(bound.withGlobalError("signin.busy"), hadPassword = false,
      AccountTokenJdbc.Invitation)))(acceptInvitationResult(deadline))
  }

  private[controllers] def acceptInvitationResult(deadline: Long)(implicit request: Request[AnyContent]): Result = {
    val bound = setPasswordForm.bindFromRequest()
    val token = bound("token").value.getOrElse("")
    AccountTokenService.check(token, AccountTokenJdbc.SetsPassword) match {
      case Left(_) => linkInvalid
      case Right((link, user)) =>
        bound.fold(
          formWithErrors =>
            linkPage(BadRequest(views.html.invitation(formWithErrors, user.password.isDefined, link.purpose))),
          { case (_, password, _) =>
            try {
              val hash = PasswordHasher.default.hash(password, deadline)
              AccountTokenService.acceptPasswordLink(token, hash) match {
                case Left(_) => linkInvalid
                case Right((accepted, account)) =>
                  // to the start page by a redirect: a reload must not post the form again
                  // no checkbox on this form: kept signed in, the sign-in form's default
                  linkPage(signedIn(account, account.emailLo, Some(routes.LoginController.index.url), keep = true,
                    accepted.contestId))
              }
            } catch {
              case _: PasswordHasher.Busy =>
                busy(views.html.invitation(bound.withGlobalError("signin.busy"), user.password.isDefined, link.purpose))
            }
          }
        )
    }
  }

  /** An email change's page: a button that confirms it (a POST, [[emailConfirm]]), so that a
    * mail scanner opening the link changes nothing.
    */
  def emailConfirmPage(token: String): Action[AnyContent] = Action { implicit request =>
    AccountTokenService.check(token, Seq(AccountTokenJdbc.EmailChange)) match {
      case Right((link, _)) => linkPage(Ok(views.html.emailConfirm(Some(token), link.email)))
      case Left(_)          => linkInvalid
    }
  }

  /** Confirms an email change (a POST with the CSRF token): the account's email becomes the new
    * address, verified; every session of the account ends, this browser's too, which then
    * signs in with the new address.
    */
  def emailConfirm(): Action[AnyContent] = Action { implicit request =>
    val token = request.body.asFormUrlEncoded.flatMap(_.get("token")).flatMap(_.headOption).getOrElse("")
    AccountTokenService.confirmEmailChange(token) match {
      case Right((oldEmail, user)) =>
        // the former address hears of it (user decision): no link, the new address masked
        admin.notifyEmailChanged(user, oldEmail)
        linkPage(Ok(views.html.emailConfirm(None, user.emailLo)).withNewSession)
      case Left(_)     => linkInvalid
    }
  }

  /** The setup page of an empty installation (P20): only while no account exists and a setup
    * token is configured; otherwise there is no such page.
    */
  def setupView(): Action[AnyContent] = Action { implicit request =>
    if (admin.setupToken.isEmpty || User.count() > 0) NotFound(views.html.errorPage(None, "error.title", "error.not.found"))
    else noStore(Ok(views.html.setup(setupForm)))
  }

  /** Creates the first account, root, with the setup token (compared in constant time) and the
    * email and password given; signs in. Of two concurrent submits, one creates it
    * ([[User.createFirstRoot]]); the other, as any submit once an account exists, gets 404.
    */
  def setup(): Action[AnyContent] = Action.async { implicit request =>
    val deadline = PasswordHasher.default.deadline()
    admitted(busy(views.html.setup(setupForm.withGlobalError("signin.busy"))))(setupResult(deadline))
  }

  private[controllers] def setupResult(deadline: Long)(implicit request: Request[AnyContent]): Result = {
    def notFound = NotFound(views.html.errorPage(None, "error.title", "error.not.found"))
    admin.setupToken match {
      case None                       => notFound
      case Some(_) if User.count() > 0 => notFound
      case Some(expected) =>
        val bound = setupForm.bindFromRequest()
        bound.fold(
          formWithErrors => noStore(BadRequest(views.html.setup(formWithErrors))),
          { case (token, email, password, _) =>
            if (!sameToken(token, expected)) {
              accountLogger.warn("setup_refused reason=token")
              noStore(BadRequest(views.html.setup(bound.withGlobalError("setup.token.invalid"))))
            } else
              try {
                User.createFirstRoot(email, PasswordHasher.default.hash(password, deadline)) match {
                  case None => notFound
                  case Some(root) =>
                    accountLogger.info(s"setup_created_root accountId=${root.getId}")
                    signedIn(root, root.emailLo, Some(routes.LoginController.index.url), keep = true)
                }
              } catch {
                case _: PasswordHasher.Busy => busy(views.html.setup(bound.withGlobalError("signin.busy")))
              }
          }
        )
    }
  }

  /** Compares the tokens' SHA-256 in constant time. */
  private def sameToken(given: String, expected: String): Boolean = {
    def sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.trim.getBytes(UTF_8))
    MessageDigest.isEqual(sha(given), sha(expected))
  }

  /** The sign-out page: a button that signs out (POST, [[logout]]), for old links and
    * bookmarks to `GET /logout`, which no longer sign out by themselves.
    */
  def logoutPage(returnTo: Option[String] = None): Action[AnyContent] = Action { implicit request =>
    noStore(Ok(views.html.logout(Secured.safeLocalPath(returnTo))))
  }

  /** Signs out this browser (a POST with the CSRF token): a new session, and the login page,
    * which comes back to `returnTo` (a local path only) after signing in again. Other sessions
    * of the account stay ([[logoutEverywhere]] ends them).
    */
  def logout(): Action[AnyContent] = Action { implicit request =>
    val returnTo = Secured.safeLocalPath(
      request.body.asFormUrlEncoded.flatMap(_.get("returnTo").flatMap(_.headOption))
    )
    Redirect(routes.LoginController.login(returnTo)).withNewSession
  }

  /** Signs out everywhere (a POST with the CSRF token, from the own profile): the account's
    * session version goes up ([[User.endSessions]]), so every session of it ends, this one
    * included, which then goes to the login page. API tokens are not sessions: they stay
    * (the profile revokes them).
    */
  def logoutEverywhere(): EssentialAction = withAuth(_ => true) { user => implicit request =>
    User.endSessions(user.getId)
    accountLogger.info(s"sessions_ended accountId=${user.getId} by=self")
    Redirect(routes.LoginController.login(None)).withNewSession
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

  /** A new password: not blank, at most 72 bytes (bcrypt's limit). */
  private def newPassword = nonEmptyText.verifying(PasswordHasher.newPassword)

  /** The invitation's form: the link's token and the new password, twice. */
  val setPasswordForm: Form[(String, String, String)] = Form(
    tuple(
      "token" -> text,
      "password" -> newPassword,
      "repeat.password" -> nonEmptyText
    ).verifying("passwords.differ", f => f._2 == f._3)
  )

  /** The setup's form: the setup token, the first account's email and password, twice. */
  val setupForm: Form[(String, String, String, String)] = Form(
    tuple(
      "token" -> nonEmptyText,
      "login" -> email.transform[String](_.trim.toLowerCase, identity),
      "password" -> newPassword,
      "repeat.password" -> nonEmptyText
    ).verifying("passwords.differ", f => f._3 == f._4)
  )
}

object LoginController {

  /** The sign-in form's "Keep me signed in" checkbox. */
  val KeepField = "remember"

  /** The messages `/error?message=` shows; any other text shows error.unknown. */
  val ErrorPageKeys: Set[String] =
    Set("no.round.yet", "error.not.found", "error.contest.not.found", "error.round.not.found")
}
