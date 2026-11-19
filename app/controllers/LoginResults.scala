package controllers

import db.scalikejdbc.User
import play.api.i18n.{I18nSupport, Lang}
import play.api.mvc._

/** Where a sign-in leads, for the password ([[LoginController]]) and the Google and Wikimedia
  * sign-ins ([[OAuthController]]): one session model (S2/S3) for both.
  */
trait LoginResults { this: Secured with I18nSupport =>

  def themeCookies: ThemeCookies

  def noStore(result: Result): Result =
    result.withHeaders(CACHE_CONTROL -> "no-store")

  /** The user's start page: a page of their role, else a page saying why there is none. */
  def indexRedirect(user: User)(implicit request: RequestHeader): Result = {
    user match {
      case u if u.hasAnyRole(User.ORG_COM_ROLES) =>
        Redirect(routes.RoundController.currentRoundStat())
      case u if u.hasAnyRole(User.JURY_ROLES) =>
        // an open round of the current contest, else of the juror's latest other contest with
        // one (last year's membership has none): the session goes on in that contest
        JurorContests
          .firstOpenRound(u)
          .fold {
            // a state, not an error: the juror waits for a round
            JurorContests.noRoundPage(u)
          } { round =>
            val inRound = Secured.inContest(u, round.contestId)
            // all the juror's files, in their order
            Secured.switchingContest(u, inRound)(Redirect(RoundList.landing(round, inRound).pageUrl(1)))
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

  /** A new session of `user` (as read when the sign-in was checked: its auth version is the one
    * the session carries), named `login`, in `contestId` if they are an active member of it,
    * else in the latest contest where they may see a page; issued now, persistent if `keep`
    * ([[SessionLifetime]]). It replaces the whole session: nothing of a signed-out one (an OAuth
    * sign-in's flow keys, another account's sign-in) stays.
    */
  def signedIn(user: User, login: String, returnTo: Option[String], keep: Boolean,
               contestId: Option[Long] = None)(implicit request: RequestHeader): Result =
    // an account an account join retires (J-2) signs in no more while the join runs: its sessions
    // ended with the join's first step, and its votes would stay behind on it
    if (user.id.exists(id => db.scalikejdbc.AccountJoinJdbc.retiring(Seq(id)).nonEmpty)) {
      LoginResults.accountLogger.info(s"sign_in_refused accountId=${user.getId} reason=account_join")
      noStore(Forbidden(views.html.errorPage(None, "error.title", "account.join.signin.refused")(
        messagesApi.preferred(request), request)))
    } else startSession(user, login, returnTo, keep, contestId)

  private def startSession(user: User, login: String, returnTo: Option[String], keep: Boolean,
               contestId: Option[Long])(implicit request: RequestHeader): Result = {
    val latest = Secured.inCurrentContest(user, contestId.map(_.toString))
    // defensive: inCurrentContest already picks an active membership; kept should its rules change
    val inContest = Secured.permitted(latest, _.hasAnyRole(Secured.ViewerRoles)).getOrElse(latest)
    // adds from other admins' search the owner hasn't dismissed in this browser: a bar on every
    // page leads to them (services.AccountNotices)
    val notices = Secured.orOnFailure("account_notices_unavailable", user, "-", false)(
      services.AccountNotices.pendingFor(request, user).nonEmpty)
    val sessionData = Secured.signedInSession(user, login, keep) ++
      inContest.contestId.map(id => Secured.CurrentContestId -> id.toString) ++
      Option.when(notices)(services.AccountNotices.SessionKey -> "1")
    // always a redirect (303), never a page in answer to the sign-in's POST: reloading such a
    // page posted the form again, and its own forms (Sign out, a contest switch) failed CSRF,
    // since this session has no token until a GET renders one. The start page (index) runs
    // indexRedirect in the new session
    // never back to "already signed in" (a signed-out visit to it keeps it as the returnTo)
    val nextPage = Redirect(Secured.safeLocalPath(returnTo)
      .filterNot(_ == routes.LoginController.signedInPage.url)
      .getOrElse(routes.LoginController.index.url))
    val result = nextPage.withSession(sessionData: _*)
    // the user's colour mode, if they chose one, follows them to this browser
    val withTheme = themeCookies.setStored(result, user.theme)
    user.lang.fold(withTheme)(l => withTheme.withLang(Lang(l)))
  }
}

object LoginResults {
  private[controllers] val accountLogger = play.api.Logger("security.account")
}
