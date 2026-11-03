package controllers

import db.scalikejdbc.{Round, User}
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

  /** A new session of `user` (as read when the sign-in was checked: its auth version is the one
    * the session carries), named `login`, in `contestId` if they are an active member of it,
    * else in the latest contest where they may see a page; issued now, persistent if `keep`
    * ([[SessionLifetime]]). It replaces the whole session: nothing of a signed-out one (an OAuth
    * sign-in's flow keys, another account's sign-in) stays.
    */
  def signedIn(user: User, login: String, returnTo: Option[String], keep: Boolean,
               contestId: Option[Long] = None)(implicit request: RequestHeader): Result = {
    val latest = Secured.inCurrentContest(user, contestId.map(_.toString))
    // defensive: inCurrentContest already picks an active membership; kept should its rules change
    val inContest = Secured.permitted(latest, _.hasAnyRole(Secured.ViewerRoles)).getOrElse(latest)
    val sessionData = Secured.signedInSession(user, login, keep) ++
      inContest.contestId.map(id => Secured.CurrentContestId -> id.toString)
    val nextPage = Secured.safeLocalPath(returnTo).fold(indexRedirect(inContest))(url => Redirect(url))
    val result = nextPage.withSession(sessionData: _*)
    // the user's colour mode, if they chose one, follows them to this browser
    val withTheme = themeCookies.setStored(result, user.theme)
    user.lang.fold(withTheme)(l => withTheme.withLang(Lang(l)))
  }
}
