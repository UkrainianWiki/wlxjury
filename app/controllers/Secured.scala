package controllers

import controllers.Secured.{CurrentContestId, UserName}
import db.scalikejdbc.{ContestJuryJdbc, Round, SeriesAdminJdbc, User, UserContest, UserContestJdbc}
import org.intracer.wmua.ContestJury
import modules.RequestTiming
import play.api.Logging
import play.api.libs.json.Json
import play.api.mvc._

import java.net.URI
import java.time.format.DateTimeFormatter
import java.time.{ZoneOffset, ZonedDateTime}
import java.util.UUID
import scala.util.Try

import scala.concurrent.{ExecutionContext, Future}

/** Base trait for secured controllers
  */
abstract class Secured(cc: ControllerComponents) extends AbstractController(cc) with Logging {

  type Permission = User => Boolean

  /** @param request
    *   HTTP request with username set in session
    * @return
    *   optional user from database, in the session's current contest
    */
  def userFromRequest(request: RequestHeader): Option[User] = Secured.sessionUserOf(request)

  /** To the login page, which comes back here after signing in: a GET's page only. A
    * request sent by script ([[Secured.isScriptRequest]]) gets a JSON 401 instead, so that
    * a fragment loaded into a page is not replaced by the login page.
    */
  def onUnAuthenticated(request: RequestHeader): Result =
    if (Secured.isScriptRequest(request)) Results.Unauthorized(Json.obj("error" -> "unauthenticated"))
    else
      Results.Redirect(
        routes.LoginController.login(Secured.safeLocalPath(Option.when(request.method == "GET")(request.uri)))
      )

  /** The denial page (403): what the page needs, what the user has, a useful destination
    * and the reference of the denial's log line ([[Secured.logDenial]]); `notice`, what the
    * request did before it was refused; `back`, a local page to go back to (a refusal that is no
    * matter of access: a stopped round's comment, back to the file's large view).
    */
  def onUnAuthorized(user: User, request: RequestHeader, denial: Secured.Denial = Secured.Denial.empty,
      notice: Option[String] = None, back: Option[String] = None): Result = {
    val reference = Secured.logDenial(user, request, denial)
    // a destination is a help, not the page: none if it can't be found (a database error)
    val suggestion = Secured.orOnFailure[Option[Secured.AccessSuggestion]](
      "denial_suggestion_unavailable", user, reference, None)(suggestedAccess(user)(request))
    val safeReturnTo = Secured.safeLocalPath(Option.when(request.method == "GET")(request.uri))
    val messages = cc.messagesApi.preferred(request)
    val reason = Secured.denialReason(user, denial)
    // a stopped round's page says nothing of roles
    val roles =
      if (reason == "round_stopped") Secured.DeniedRoles.Unknown
      else Secured.deniedRolesOrUnknown(user, denial, reference, deniedContests)
    Results.Forbidden(
      views.html.accessDenied(
        user,
        suggestion,
        safeReturnTo,
        denial.copy(reason = Some(reason)),
        roles,
        reference,
        Secured.referenceTime(),
        notice,
        back.flatMap(url => Secured.safeLocalPath(Some(url)))
      )(request.flash, messages, request)
    )
  }

  /** The contests of these ids, for the denial page's roles ([[Secured.deniedRoles]]). */
  protected def deniedContests(ids: Seq[Long]): Seq[ContestJury] = Secured.contestsByIds(ids)

  /** [[onUnAuthorized]] for JSON endpoints: the same log line, a JSON body, no suggestion. */
  def onUnAuthorizedJson(user: User, request: RequestHeader, denial: Secured.Denial = Secured.Denial.empty): Result = {
    val reference = Secured.logDenial(user, request, denial)
    Results.Forbidden(Json.obj("error" -> "forbidden", "reference" -> reference))
  }

  /** The answer to a round's view with a user's votes that [[services.GalleryService.viewAs]]
    * refuses: another user's votes (or all jurors' together) a denial (403); an organiser's
    * view as a user who is not a juror of the round, not found (404).
    */
  def viewAsRefused(user: User, round: Round, refusal: services.GalleryService.ViewAs, json: Boolean)(implicit
      request: RequestHeader
  ): Result =
    refusal match {
      case services.GalleryService.ViewAs.NotRoundJuror =>
        if (json) Results.NotFound(Json.obj("error" -> "juror_not_found"))
        else
          Results.NotFound(
            views.html.errorPage(Some(user), "error.title", "error.juror.not.found")(
              cc.messagesApi.preferred(request), request)
          )
      case _ =>
        val denial = Secured.Denial
          .round(round, User.ADMIN_ROLES ++ User.ORG_COM_ROLES)
          .copy(reason = Some(services.GalleryService.OtherUsersVotesReason))
        if (json) onUnAuthorizedJson(user, request, denial) else onUnAuthorized(user, request, denial)
    }

  /** A juror's gallery or large view of a round not open to them now
    * ([[services.GalleryService.RoundState]], DG7 of the support diagnostics plan): a 200 page
    * saying so, with links to their open rounds (`openRounds`), and a Reload button for a state
    * that passes shortly (to `back`, else to this page if it was a GET); `commentLost`: from a
    * comment's send, which says the comment was not saved; to a script, the vote path's 409 `round_closed` (what vote-errors.js
    * reads) with the state's `reason`. A state, not a denial: no `security.authz` line, but one
    * INFO line on `round.access` ([[services.GalleryService.accessRefusedLine]]).
    */
  def roundStateResult(user: User, round: Round, state: services.GalleryService.RoundState, openRounds: Seq[Round],
      json: Boolean, back: Option[String] = None, commentLost: Boolean = false)(implicit request: RequestHeader): Result = {
    services.GalleryService.accessLogger.info(services.GalleryService.accessRefusedLine(state, round, user))
    if (json) Results.Conflict(Json.obj("error" -> "round_closed", "reason" -> state.reason))
    else {
      // the page to try again: `back` (a comment's file in the large view), else this page if a GET
      val reload = Option.when(services.GalleryService.RoundState.Transient(state))(
        back.orElse(Option.when(request.method == "GET")(request.uri))).flatten
      Results.Ok(views.html.roundState(user, round, state.reason, openRounds.filterNot(_.id == round.id), reload,
        commentLost)(
        cc.messagesApi.preferred(request), request))
    }
  }

  /** A useful destination from an active membership, the current contest's first: the
    * contest's files if the user may view them, else a gallery of one of its active rounds.
    */
  protected def suggestedAccess(user: User)(implicit request: RequestHeader): Option[Secured.AccessSuggestion] = {
    val memberships = user.userContests
      .filter(_.opens)
      .sortBy(membership => if (user.contestId.contains(membership.contestId)) 0 else 1)

    memberships.iterator.flatMap { membership =>
      val memberUser = user.inContest(membership)
      if (memberUser.canViewContestFiles(membership.contestId))
        Some(
          Secured.AccessSuggestion(
            routes.GalleryController.contestFiles(membership.contestId).url,
            "access.denied.suggested.files",
            Some(membership.contestId),
            None,
            Seq.empty
          )
        )
      else {
        val rounds = Round.activeRounds(memberUser)
        rounds.headOption.map { round =>
          val url = RoundList.landing(round, memberUser).pageUrl(1)
          Secured.AccessSuggestion(url, "access.denied.suggested.gallery", Some(membership.contestId), Some(round), rounds)
        }
      }
    }.nextOption()
      // a series admin: their series page (contest series plan §3.3, "Menu and hints")
      .orElse(Secured.seriesLanding(user).map(id =>
        Secured.AccessSuggestion(routes.SeriesController.seriesHome(id).url, "access.denied.suggested.series", None, None,
          Seq.empty)))
  }

  def withAuth(
      permission: Permission = rolePermission(Secured.ViewerRoles)
  )(f: => User => Request[AnyContent] => Result): EssentialAction =
    authenticated(permission, onUnAuthorized(_, _, _))(f)

  /** [[withAuth]] for a form that a script may post as well (async-form.js, `data-async-form`):
    * a denial of a script request ([[Secured.isScriptRequest]]) is a JSON 403
    * ([[onUnAuthorizedJson]]) instead of the denial page; signed out it is a JSON 401 already
    * ([[onUnAuthenticated]]). A form posted without script gets [[withAuth]]'s answers.
    */
  def withAuthForm(
      permission: Permission = rolePermission(Secured.ViewerRoles)
  )(f: => User => Request[AnyContent] => Result): EssentialAction =
    authenticated(permission, (user, request, denial) =>
      if (Secured.isScriptRequest(request)) onUnAuthorizedJson(user, request, denial)
      else onUnAuthorized(user, request, denial))(f)

  private def authenticated(permission: Permission, denied: (User, RequestHeader, Secured.Denial) => Result)(
      f: => User => Request[AnyContent] => Result): EssentialAction =
    Security.Authenticated(r => RequestTiming.within(r)(userFromRequest(r)), onUnAuthenticated) { user =>
      Action { request =>
        RequestTiming.within(request) {
          checked(user, permission).fold(
            denied(user, request, _),
            permittedUser => Secured.switchingContest(user, permittedUser)(f(permittedUser)(request))(request)
          )
        }
      }
    }

  /** [[withAuth]] for JSON endpoints polled by script: signed out is a JSON 401 and a
    * denial a JSON 403 ([[onUnAuthorizedJson]]) instead of the login redirect and the
    * denial page, and the session's contest is never switched.
    */
  def withAuthJson(
      permission: Permission = rolePermission(Secured.ViewerRoles)
  )(f: => User => Request[AnyContent] => Result): EssentialAction =
    Action { request =>
      RequestTiming.within(request) {
        userFromRequest(request).fold[Result](Results.Unauthorized(Json.obj("error" -> "unauthenticated"))) { user =>
          checked(user, permission).fold(onUnAuthorizedJson(user, request, _), permittedUser => f(permittedUser)(request))
        }
      }
    }

  /** [[withAuthJson]] for script requests that run slow blocking JDBC work: on `ec` (the
    * blocking dispatcher), as [[withAuthOn]]; never switching the session's contest.
    */
  def withAuthJsonOn(ec: ExecutionContext)(
      permission: Permission = rolePermission(Secured.ViewerRoles)
  )(f: => User => Request[AnyContent] => Result): EssentialAction =
    Action.async { request =>
      Future {
        RequestTiming.dequeued(request)
        RequestTiming.within(request) {
          userFromRequest(request).fold[Result](Results.Unauthorized(Json.obj("error" -> "unauthenticated"))) { user =>
            checked(user, permission).fold(onUnAuthorizedJson(user, request, _), permittedUser => f(permittedUser)(request))
          }
        }
      }(ec)
    }

  /** [[withAuth]] for actions that run slow blocking JDBC work: the authentication, the
    * permission check and the action run on `ec` (the blocking dispatcher) instead of
    * Play's default dispatcher, whose few threads every other request needs.
    */
  def withAuthOn(ec: ExecutionContext)(
      permission: Permission = rolePermission(Secured.ViewerRoles)
  )(f: => User => Request[AnyContent] => Result): EssentialAction =
    Action.async { request =>
      Future {
        RequestTiming.dequeued(request)
        RequestTiming.within(request) {
          userFromRequest(request).fold(onUnAuthenticated(request)) { user =>
            checked(user, permission).fold(
              onUnAuthorized(user, request, _),
              permittedUser => Secured.switchingContest(user, permittedUser)(f(permittedUser)(request))(request)
            )
          }
        }
      }(ec)
    }

  /** `action` with `Cache-Control: no-store`, for the pages that show votes (the galleries and
    * the large view): Back after a vote must load the page again, not bring it back with the
    * votes it had from the back/forward cache, or from the HTTP cache, which Back uses even
    * when stale. This keeps these pages out of the back/forward cache; reloading a page
    * restored from it (`pageshow`) instead would flash the old votes first, and miss a page
    * Back takes from the HTTP cache.
    */
  def noStore(action: EssentialAction): EssentialAction = EssentialAction { request =>
    action(request).map(_.withHeaders(CACHE_CONTROL -> "no-store"))(ExecutionContext.parasitic)
  }

  /** The permitted user ([[Secured.permitted]]), or the denial of the permission as checked
    * for them ([[Secured.forUser]]).
    */
  private def checked(user: User, permission: Permission): Either[Secured.Denial, User] =
    Secured.checked(user, permission)

  /** One of `roles` in the user's contest; `derived = true` lets a series grant's derived role count
    * ([[Secured.RolePermission]]).
    */
  def rolePermission(roles: Set[String], derived: Boolean = false): Secured.RolePermission =
    Secured.RolePermission(roles, derived)

  def isRoot(user: User): Boolean = rolePermission(Set(User.ROOT_ROLE))(user)

  /** Root, or one of `roles` in the contest by a real membership only, unless the action opts in
    * (contest series plan §3.3): `derived = true`, a series grant's derived role counts (the guard
    * spec lists every such action); `accountReach = true`, a contest the user's grants reach for the
    * account rules counts as a real Admin row (CA17).
    */
  def contestPermission(roles: Set[String], contestId: Option[Long], derived: Boolean = false,
      accountReach: Boolean = false): Secured.ContestPermission =
    Secured.ContestPermission(roles, contestId, derived, accountReach)

  def roundPermission(roles: Set[String], roundId: Long, derived: Boolean = false): Secured.RoundPermission =
    Secured.RoundPermission(roles, roundId, derived)

  /** The contest's Admins, real or by account reach (CA17): the permission of the actions the plan
    * puts in "Real Admin row needed, or a series contest with year >= the grant's start year".
    */
  def accountAdminPermission(contestId: Option[Long]): Secured.ContestPermission =
    contestPermission(User.ADMIN_ROLES, contestId, accountReach = true)
}

object Secured {
  final case class AccessSuggestion(
      url: String,
      labelKey: String,
      contestId: Option[Long],
      round: Option[Round],
      rounds: Seq[Round]
  )

  val UserName         = "username"
  val CurrentContestId = "current_contest_id"

  /** The account's users.auth_version when the session was signed in (absent: 0, a session from
    * before V77): a session of another version is refused ([[sessionUser]]). It goes up, ending
    * every session of the account, on a password change or link, an email change, "sign out
    * everywhere" and root's "end sessions" (S3, [[User.endSessions]]).
    */
  val AuthVersion      = "auth_version"

  /** Whether a session with `sessionVersion` (its [[AuthVersion]]) may still act as `user`:
    * only with the account's current version; a missing one counts as 0, a malformed one never
    * matches.
    */
  def authVersionMatches(user: User, sessionVersion: Option[String]): Boolean =
    sessionVersion.fold(user.authVersion == 0)(_.toIntOption.contains(user.authVersion))

  /** The id of the account the session was signed in to (since S2). A session's login name
    * could later name another account (an address freed by an email change, a recreated
    * account): such a session is refused. A session from before S2 has none; it still resolves
    * by name, but only to an account of auth version 0, which no account created or re-addressed
    * since has ([[services.UserService.createAccount]], [[User.setEmail]], [[User.setWikiAccount]]).
    */
  val AccountId        = "account_id"

  /** Whether a session with this [[AuthVersion]] and [[AccountId]] may act as `user`. */
  def sessionMatches(user: User, sessionVersion: Option[String], sessionAccountId: Option[String]): Boolean =
    authVersionMatches(user, sessionVersion) && sessionAccountId.forall(_.toLongOption == user.id)

  /** The session keys of a new sign-in of `user`: its login name (one that names it, as
    * [[sessionUser]] resolves it: [[LoginController]]'s `sessionLogin`), account id and version, and
    * the sign-in's times ([[SessionLifetime.signedIn]]), with a persistent cookie if `keep`.
    */
  def signedInSession(user: User, login: String, keep: Boolean): Seq[(String, String)] =
    Seq(UserName -> login, AccountId -> user.getId.toString, AuthVersion -> user.authVersion.toString) ++
      SessionLifetime.signedIn(keep)

  /** What a denied request asked for, as far as its call site knows: the contest of the
    * resource (`round:123`, `job:45`, `contest:603`), the roles that would have let the
    * user in, and a reason more specific than the one [[denialReason]] derives.
    */
  final case class Denial(
      contestId: Option[Long] = None,
      resource: Option[String] = None,
      requiredRoles: Set[String] = Set.empty,
      reason: Option[String] = None
  )

  object Denial {
    val empty: Denial = Denial()

    def contest(contestId: Option[Long], roles: Set[String]): Denial =
      Denial(contestId, contestId.map(id => s"contest:$id"), roles)

    def round(round: Round, roles: Set[String]): Denial =
      Denial(Some(round.contestId), Some(s"round:${round.getId}"), roles)
  }

  /** What the denial page says about the account's roles ([[deniedRoles]]). */
  sealed trait DeniedRoles

  object DeniedRoles {

    /** The account's role in the contest the page was checked in: the denied resource's, or,
      * for a page of no particular contest, the session's current one.
      */
    final case class In(contest: ContestJury, roles: Set[String]) extends DeniedRoles

    /** The page belongs to a contest the account has no role in (never a member of it, or no
      * longer an active one: `inactive`). That contest is not named: contest names are shown
      * only to their members and root (the contest list is root's), and a page of a contest
      * that does not exist reads the same. The account's own contests are, with its role in each.
      */
    final case class OutsideContests(contests: Seq[In], inactive: Boolean) extends DeniedRoles

    /** Nothing to say: no contest to name (root's denials, a page of no contest without a
      * current one).
      */
    case object Unknown extends DeniedRoles
  }

  /** The account's roles as the denial page puts them. A page of a contest the account has
    * no role in (a link to a round of a contest it was never added to) says so, rather than
    * "this page is for: Admin" beside "your role in <its own contest>: Admin"; a page of
    * another of its contests gives its role there, not in the session's current contest. Root,
    * which needs no membership, is never outside a contest: its denials (a vote, which needs
    * a juror) keep the required roles.
    */
  def deniedRoles(user: User, denial: Denial,
      contestsByIds: Seq[Long] => Seq[ContestJury] = contestsByIds): DeniedRoles = {
    val active = user.userContests.filter(_.opens)
    // the contests named, in one query
    def named(memberships: Seq[(Long, Set[String])]): Seq[DeniedRoles.In] =
      if (memberships.isEmpty) Nil
      else {
        val contests = contestsByIds(memberships.map(_._1).distinct)
        memberships.flatMap { case (id, roles) => contests.find(_.id.contains(id)).map(DeniedRoles.In(_, roles)) }
      }
    def rolesIn(membership: UserContest) = membership.contestId -> (membership.roles -- User.INACTIVE_ROLES)

    denial.contestId match {
      case Some(id) =>
        active.find(_.contestId == id) match {
          case Some(membership) => named(Seq(rolesIn(membership))).headOption.getOrElse(DeniedRoles.Unknown)
          case None if user.hasRole(User.ROOT_ROLE) => DeniedRoles.Unknown
          case None =>
            // the current contest first, then the latest
            val ordered = active.sortBy(m => (!user.contestId.contains(m.contestId), -m.contestId))
            DeniedRoles.OutsideContests(named(ordered.map(rolesIn)), inactive = user.userContests.exists(_.contestId == id))
        }
      case None =>
        val roles = user.contestRoles - User.NO_ROLE
        user.contestId
          .filter(_ => roles.nonEmpty)
          .flatMap(id => named(Seq(id -> roles)).headOption)
          .getOrElse(DeniedRoles.Unknown)
    }
  }

  /** [[deniedRoles]], or, if they can't be loaded (a database error), [[DeniedRoles.Unknown]]:
    * the generic denial page, logged at WARN with the denial's reference. A denial page is
    * never a server error.
    */
  def deniedRolesOrUnknown(user: User, denial: Denial, reference: String,
      contestsByIds: Seq[Long] => Seq[ContestJury] = contestsByIds): DeniedRoles =
    orOnFailure("denial_roles_unavailable", user, reference, DeniedRoles.Unknown: DeniedRoles)(
      deniedRoles(user, denial, contestsByIds))

  /** `part` of a denial page, or `fallback` if it fails (a database error), logged at WARN on
    * security.authz as `event` with the denial's reference and the exception's class (the
    * exception attached, its message not in the line): a denial page is never a server error.
    */
  def orOnFailure[T](event: String, user: User, reference: String, fallback: T)(part: => T): T =
    try part
    catch {
      case scala.util.control.NonFatal(e) =>
        authzLogger.warn(s"$event ref=$reference accountId=${user.getId} error=${logValue(e.getClass.getName)}", e)
        fallback
    }

  /** The contests of these ids, in one query. */
  def contestsByIds(ids: Seq[Long]): Seq[ContestJury] =
    if (ids.isEmpty) Nil
    else ContestJuryJdbc.findAllBy(scalikejdbc.sqls.in(ContestJuryJdbc.defaultAlias.id, ids))


  /** A permission that tells its denial what it requires. */
  sealed trait DescribedPermission extends (User => Boolean) {
    def denial: Denial

    /** Whether the action opts in to a series grant's derived role or account reach (contest series
      * plan §3.3): then the permitted user carries the merged role ([[withDerivedRoles]]).
      */
    def optsIn: Boolean = false
  }

  /** Whether the user's series grants give one of `roles` in the contest, the user's current one (a
    * derived role: contest series plan §3.3, CA20). A session's only ([[withSeries]]).
    */
  private def derivedRoleIn(user: User, roles: Set[String], contestId: Option[Long]): Boolean =
    contestId.exists(c => user.isInContest(Some(c)) && user.derivedRoles.getOrElse(c, Set.empty).exists(roles))

  /** One of the roles, in the user's contest ([[permitted]] tries their others too); with
    * `derived`, a series grant's derived role there counts.
    */
  final case class RolePermission(roles: Set[String], derived: Boolean = false) extends DescribedPermission {
    def apply(user: User): Boolean = user.hasAnyRole(roles) || derived && derivedRoleIn(user, roles, user.contestId)
    def denial: Denial = Denial(requiredRoles = roles)
    override def optsIn: Boolean = derived
  }

  /** Root, or one of the roles in the contest ([[User.hasContestRole]]); a request's without
    * a contest is checked in the session's current one ([[forUser]]). Real memberships only, unless
    * `derived` (a series grant's derived role counts) or `accountReach` (for the Admin role, a
    * contest the user's live grants reach for the account rules counts, CA17: read from the
    * database by [[SeriesAdminJdbc.seriesAccountContests]], for a session's user only).
    */
  final case class ContestPermission(roles: Set[String], contestId: Option[Long], derived: Boolean = false,
      accountReach: Boolean = false) extends DescribedPermission {
    def apply(user: User): Boolean =
      user.hasContestRole(roles, contestId) ||
        derived && derivedRoleIn(user, roles, contestId) ||
        accountReach && roles.contains(User.ADMIN_ROLE) && reaches(user, contestId)
    def denial: Denial = Denial.contest(contestId, roles)
    override def optsIn: Boolean = derived || accountReach
  }

  /** Whether the session user's live grants reach the contest, their current one, for the account
    * rules (CA17).
    */
  private def reaches(user: User, contestId: Option[Long]): Boolean =
    user.seriesGranted.nonEmpty && contestId.exists(c =>
      user.isInContest(Some(c)) && SeriesAdminJdbc.seriesAccountContests(user.getId).contains(c))

  /** Root, or one of the roles in the round's contest; the round is loaded once. With `derived`, a
    * series grant's derived role there counts.
    */
  final case class RoundPermission(roles: Set[String], roundId: Long, derived: Boolean = false)
      extends DescribedPermission {
    private lazy val round = Round.findById(roundId)
    def apply(user: User): Boolean = round.exists(r =>
      user.hasContestRole(roles, Some(r.contestId)) || derived && derivedRoleIn(user, roles, Some(r.contestId)))
    def denial: Denial = Denial(round.map(_.contestId), Some(s"round:$roundId"), roles)
    override def optsIn: Boolean = derived
  }

  /** A vote in the round, by any path (the gallery's tiles and stars, the large view's buttons
    * and keys, a criterion's rate): only by a juror of the round's contest ([[User.mayVoteIn]]),
    * the one role the vote controls are drawn for. Organizers (read-only), admins and root
    * without that role never vote; a juror made an organizer keeps their round assignment and
    * earlier votes, but votes no more. A vote changes only the voter's own vote rows, which only
    * the round's jurors have ([[db.scalikejdbc.VoteJdbc.rate]]): a file not theirs is a 404.
    */
  final case class VotePermission(roundId: Long) extends DescribedPermission {
    private lazy val round = Round.findById(roundId)
    def apply(user: User): Boolean = round.exists(user.mayVoteIn)
    def denial: Denial = Denial(round.map(_.contestId), Some(s"round:$roundId"), User.VoterRoles)
  }

  /** Any of `permissions` (the denial names the first's): an own page or an Admin's
    * ([[UserController.editUser]]).
    */
  final case class AnyOf(permissions: Seq[DescribedPermission]) extends DescribedPermission {
    def apply(user: User): Boolean = permissions.exists(_(user))
    def denial: Denial = permissions.headOption.fold(Denial.empty)(_.denial)
    override def optsIn: Boolean = permissions.exists(_.optsIn)
  }

  /** A series admin of any live series (a session's): the start page, which sends them to their
    * series page ([[seriesLanding]]). Its denial names no role: [[AnyOf]] puts another first.
    */
  case object AnySeries extends DescribedPermission {
    def apply(user: User): Boolean = user.seriesGranted.nonEmpty
    def denial: Denial = Denial.empty
  }

  object AnyOf {
    def apply(first: DescribedPermission, others: DescribedPermission*): AnyOf = AnyOf(first +: others)
  }

  /** The role name of a series admin (contest series plan §3.3, CA3): not a contest role, never
    * stored in `user_contest`; only a denial names it (`role.series_admin`).
    */
  val SeriesAdminRole = "series_admin"

  /** Root, or a series admin of the series (a live grant, the session's: [[withSeries]], so a
    * revocation takes effect on the next request): the series page and its grant form.
    */
  final case class SeriesPermission(seriesId: Long) extends DescribedPermission {
    def apply(user: User): Boolean = user.hasRole(User.ROOT_ROLE) || user.seriesGranted.contains(seriesId)
    def denial: Denial = Denial(None, Some(s"series:$seriesId"), Set(User.ROOT_ROLE, SeriesAdminRole), Some("not_series_admin"))
  }

  /** Whether `user` administers the round's contest, as an active member of it (root needs
    * none): who may pause or resume a juror's voting in it ([[RoundController.setRoundUser]]),
    * and so who the statistics page shows that button to.
    */
  def administersRound(user: User, round: Round): Boolean =
    ContestPermission(User.ADMIN_ROLES, Some(round.contestId))(inContest(user, round.contestId))

  /** What the permission requires, if it says; else nothing (`requiredRoles=unknown`). */
  def denialOf(permission: User => Boolean): Denial = permission match {
    case described: DescribedPermission => described.denial
    case _                              => Denial.empty
  }

  private val authzLogger = play.api.Logger("security.authz")

  /** The permitted user ([[permitted]]), or the denial of the permission as checked for
    * them ([[forUser]]): the check of every action, the API's ([[api.ApiAuth]]) included.
    */
  def checked(user: User, permission: User => Boolean): Either[Denial, User] = {
    val userPermission = forUser(user, permission)
    val optsIn = userPermission match {
      case p: DescribedPermission => p.optsIn
      case _                      => false
    }
    permitted(user, userPermission).map(u => if (optsIn) withDerivedRoles(u) else u).toRight(denialOf(userPermission))
  }

  /** The user as an action that opts in to derived roles sees them (contest series plan §3.3):
    * [[User.roles]] with the merged role in the current contest (real roles add on top, the higher
    * wins), and [[User.derivedOptIn]] set, so that [[inContest]] merges it in the contest it moves
    * to. Only for a permission with `derived = true` or `accountReach = true` ([[checked]]).
    */
  def withDerivedRoles(user: User): User =
    user.copy(derivedOptIn = true,
      roles = user.roles ++ user.contestId.flatMap(user.derivedRoles.get).getOrElse(Set.empty))

  /** Logs the denial at WARN and returns the line's reference, for the page to show.
    * `fields`: trusted key-value fields appended to the line (the API's `auth=token
    * tokenId=12`).
    */
  def logDenial(user: User, request: RequestHeader, denial: Denial, fields: Seq[(String, String)] = Nil): String = {
    val reference = newReference()
    authzLogger.warn(denialLogLine(user, request, denial, reference, fields))
    reference
  }

  /** 8 hex characters that find a page's log line. */
  def newReference(): String = UUID.randomUUID().toString.replace("-", "").take(8)

  /** The time shown with a reference, to the minute. */
  def referenceTime(): String =
    ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'"))

  /** Why the user was denied, unless the call site says: from their membership of the
    * target contest, else of any contest (only inactive ones: `membership_inactive`).
    */
  def denialReason(user: User, denial: Denial): String =
    denial.reason.getOrElse {
      val required = denial.requiredRoles
      denial.contestId match {
        case Some(id) =>
          user.userContests.find(_.contestId == id) match {
            case None                                                               => "not_member"
            case Some(m) if !m.active                                               => "membership_inactive"
            case Some(m) if m.contestRetired                                        => "contest_retired"
            case Some(m) if required.nonEmpty && m.roles.intersect(required).isEmpty => "missing_role"
            case Some(_) if !user.contestId.contains(id)                            => "resource_in_other_contest"
            case Some(_)                                                            => "permission_check_failed"
          }
        case None =>
          val active = user.userContests.filter(_.opens)
          if (active.isEmpty && !user.hasRole(User.ROOT_ROLE)) {
            if (user.userContests.nonEmpty) "membership_inactive" else "not_member"
          }
          else if (required.nonEmpty && !active.exists(_.roles.intersect(required).nonEmpty)) "missing_role"
          else "permission_check_failed"
      }
    }

  /** The denial's log line: key-value fields, untrusted text quoted ([[logValue]]). */
  def denialLogLine(user: User, request: RequestHeader, denial: Denial, reference: String,
                    fields: Seq[(String, String)] = Nil): String = {
    val targetMembership = denial.contestId.flatMap(id => user.userContests.find(_.contestId == id))
    val targetMembershipState = denial.contestId match {
      case None    => "not_applicable"
      case Some(_) => targetMembership.fold("missing")(m => if (m.active) "active" else "inactive")
    }
    val targetContestRoles = denial.contestId match {
      case None    => "not_applicable"
      case Some(_) => targetMembership.fold("none")(_.roles.toSeq.sorted.mkString(","))
    }
    def roleList(roles: Set[String], ifNone: String): String =
      roles.filterNot(_.startsWith(User.USER_ID_PREFIX)).toSeq.sorted match {
        case sorted if sorted.nonEmpty => sorted.mkString(",")
        case _                         => ifNone
      }
    s"authorization_denied ref=$reference accountId=${user.getId} " +
      s"method=${request.method} path=${logPath(request.path)} " +
      s"currentContestId=${user.contestId.getOrElse("none")} " +
      s"targetContestId=${denial.contestId.getOrElse("unknown")} " +
      s"resource=${denial.resource.fold("unknown")(logValue)} " +
      s"requiredRoles=${logValue(roleList(denial.requiredRoles, "unknown"))} " +
      s"accountRoles=${logValue(roleList(user.roles, "none"))} " +
      s"targetContestMembershipState=$targetMembershipState " +
      s"targetContestRoles=${logValue(targetContestRoles)} reason=${denialReason(user, denial)}" +
      fields.map { case (key, value) => s" $key=$value" }.mkString
  }

  /** The account and contest fields of a request's `server_error` and `csrf_failure` lines,
    * and where they come from (`accountFrom`): `user`, the session's user as the database told
    * it ([[sessionUserOf]]), with their current contest; else `session`, the signed session's
    * own ids, unchecked (an ended or another account's session, or the database not asked);
    * `none` without any (signed out: `-`). Ids only, never the email.
    */
  def accountFields(request: RequestHeader, user: Option[User]): String = {
    def fromSession(key: String) = request.session.get(key).filter(_.nonEmpty).filter(_.forall(_.isDigit))
    user.flatMap(_.id) match {
      case Some(id) => s"accountId=$id contestId=${user.flatMap(_.contestId).getOrElse("-")} accountFrom=user"
      case None =>
        val accountId = fromSession(AccountId)
        val contestId = fromSession(CurrentContestId)
        s"accountId=${accountId.getOrElse("-")} contestId=${contestId.getOrElse("-")} " +
          s"accountFrom=${if (accountId.isDefined) "session" else "none"}"
    }
  }

  /** A request path for a log line: [[logValue]], with the token of a mailed link
    * (`/invitation/<token>`, `/email/confirm/<token>`) masked.
    */
  def logPath(path: String): String =
    logValue(path.replaceAll("^/(invitation|email/confirm|oauth/choose/confirm)/[^/]+", "/$1/***"))

  /** Escape untrusted text so log fields stay single-line and key-value parseable. */
  def logValue(value: String): String =
    "\"" + value.flatMap {
      case '\\' => "\\\\"
      case '"'  => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c if Character.isISOControl(c) => "?"
      case c => c.toString
    } + "\""

  /** A return location is accepted only as a local absolute path, never an authority or URL. */
  def safeLocalPath(value: Option[String]): Option[String] =
    value
      .filter(_.length <= 2048)
      .flatMap(raw => Try(new URI(raw)).toOption)
      .filter(uri =>
        !uri.isAbsolute && uri.getRawAuthority == null &&
          Option(uri.getRawPath).exists(path => path.startsWith("/") && !path.startsWith("//") && !path.contains('\\'))
      )
      .map(_.toASCIIString)

  /** The roles [[Secured.withAuth]] lets in by default: any page's. */
  val ViewerRoles: Set[String] = User.ADMIN_ROLES ++ Set("jury", "organizer")

  /** The session's user name as users are looked up by: trimmed (ASCII) and lowercased. Not
    * [[org.intracer.wmua.LoginText]]: a session from before S3 is still resolved by its name,
    * which must name the same account as before; a sign-in stores a clean name.
    */
  def normalizeUserName(userName: String): String = userName.trim.toLowerCase

  /** The request's session user, in the session's current contest
    * ([[Secured.userFromRequest]]); none if the session is past its absolute or idle limit
    * ([[SessionLifetime.live]]; [[modules.SessionLifetimeFilter]] also takes such a session's
    * sign-in out of the request and the browser).
    */
  def sessionUserOf(request: RequestHeader): Option[User] =
    request.session.get(UserName).filter(_ => SessionLifetime.live(request.session)).flatMap(
      sessionUser(_, request.session.get(CurrentContestId), request.session.get(AuthVersion),
        request.session.get(AccountId)))

  /** The session's user ([[Secured.userFromRequest]]), in `currentContestId` as
    * [[inCurrentContest]] picks it; none if the session's `authVersion` is not the account's
    * ([[authVersionMatches]]: an accepted password link or an email change ended it), or if
    * it was signed in to another account ([[AccountId]]). Both values are the session's, None
    * when it has none: no default, so that no caller forgets them.
    */
  def sessionUser(userName: String, currentContestId: Option[String], authVersion: Option[String],
                  accountId: Option[String]): Option[User] =
    User.byUserName(normalizeUserName(userName))
      .filter(sessionMatches(_, authVersion, accountId))
      // an account an account join retires (J-2) is signed out from the join's start, not only
      // once its first step ends the sessions: an in-flight session votes, makes tokens or writes
      // nothing more
      .filterNot(u => db.scalikejdbc.AccountJoinJdbc.retiring(u.id.toSeq).nonEmpty)
      .map(withSeries(_, currentContestId))

  /** `permission` as checked for the session's user: a [[ContestPermission]] without a
    * contest (a page whose optional `contestId` is missing) in their current contest, the
    * one such a page shows, and only there, as if the page named it; denied without a
    * current contest, root aside.
    */
  def forUser(user: User, permission: User => Boolean): User => Boolean = permission match {
    case p @ ContestPermission(_, None, _, _) => p.copy(contestId = user.contestId)
    case AnyOf(permissions) =>
      AnyOf(permissions.map(p => forUser(user, p) match {
        case described: DescribedPermission => described
        case _                              => p
      }))
    case other                                => other
  }

  /** The user if `permission` lets them in, in the session's current contest; else in
    * the first other of their contests where it does, so that a link into another of
    * their contests (a bookmark, a link in an email) opens it instead of failing until
    * they switch by hand. That contest then becomes the session's current one.
    */
  def permitted(user: User, permission: User => Boolean): Option[User] =
    if (permission(user)) Some(user)
    else
      user.userContests.iterator
        .filter(m => m.opens && !user.contestId.contains(m.contestId))
        .map(user.inContest)
        .find(permission)
        // then the contests the user's series grants reach (a session's only, [[withSeries]]),
        // which a real-only permission never lets in
        .orElse(seriesOnly(user).iterator.map(user.inSeriesContest).find(permission))

  /** The contests the user's series grants reach where no real membership opens, but the current one. */
  private def seriesOnly(user: User): Seq[Long] =
    user.seriesContests.map(_.contestId)
      .filterNot(id => user.contestId.contains(id) || user.userContests.exists(m => m.contestId == id && m.opens))

  /** Whether the session's user may see a page of [[Secured.withAuth]]'s default
    * permission, in any of their contests.
    */
  def mayView(userName: String, authVersion: Option[String], accountId: Option[String]): Boolean =
    sessionUser(userName, None, authVersion, accountId).exists(permitted(_, _.hasAnyRole(ViewerRoles)).isDefined)

  /** The user with their memberships, in `currentContestId` (the session's) if they are an
    * active member of it, else in their latest contest where they are. A root user needs
    * no membership.
    */
  def inCurrentContest(user: User, currentContestId: Option[String]): User = {
    val memberships = UserContestJdbc.findByUser(user.getId)
    val withMemberships = user.copy(userContests = memberships)
    // a retired contest's membership opens nothing (contest series plan §3.1)
    val active = memberships.filter(_.opens)
    val requested = currentContestId.flatMap(_.toLongOption)
    requested
      .flatMap(id => active.find(_.contestId == id))
      .orElse(active.headOption)
      .fold(withMemberships)(withMemberships.inContest)
  }

  /** The user in the contest, if they are an active member of it, or (a session's) if their series
    * grants reach it: there with no real role, and the derived one only in an action that opted in
    * ([[User.derivedOptIn]]); else unchanged.
    */
  def inContest(user: User, contestId: Long): User =
    if (user.contestId.contains(contestId)) user
    else user.userContests.find(m => m.contestId == contestId && m.opens).map(user.inContest)
      .orElse(Option.when(user.seriesContests.exists(_.contestId == contestId))(user.inSeriesContest(contestId)))
      .getOrElse(user)

  /** [[inContest]] by real memberships only, never a series grant or a merged role (contest series
    * plan §3.3): for the checks that need a real Admin row whatever the action
    * (`JobController.adminOf`, `AccountJoinController.mayManage`). Outside a real membership, the
    * user with no contest role.
    */
  def inContestReal(user: User, contestId: Long): User = {
    val real = user.copy(derivedOptIn = false, seriesContests = Nil)
    user.userContests.find(m => m.contestId == contestId && m.opens)
      .fold(real.copy(roles = real.roles -- real.contestRoles, contestId = Some(contestId)))(real.inContest)
  }

  /** The session's user with their series reach (contest series plan §3.3): [[inCurrentContest]]
    * (raw), plus the contests their live grants reach ([[User.seriesContests]], with the derived
    * role in each) and the series granted. The current contest is resolved over the real contests
    * first, then the series' (a contest switched to through [[maySwitchTo]] sticks): without a
    * real membership of `currentContestId`, the series contest it names. A user with only series
    * reach and no such contest has none. Called only from [[sessionUser]] and [[LoginResults]] (a
    * guard spec): API tokens and root's edit form never get it.
    */
  def withSeries(user: User, currentContestId: Option[String]): User = {
    val real = inCurrentContest(user, currentContestId)
    val granted = SeriesAdminJdbc.liveByUser(user.getId).map(_.seriesId)
    if (granted.isEmpty) real
    else {
      val reach = SeriesAdminJdbc.seriesContests(user.getId)
      val withReach = real.copy(seriesContests = reach, seriesGranted = granted)
      currentContestId.flatMap(_.toLongOption)
        .filterNot(id => withReach.contestId.contains(id))
        .filter(id => reach.exists(_.contestId == id) && !withReach.userContests.exists(m => m.contestId == id && m.opens))
        .fold(withReach)(withReach.inSeriesContest)
    }
  }

  /** Whether the user may make the contest their session's current one ([[UserController.switchContest]]):
    * a real membership that opens it, or (a session's) a contest their series grants reach.
    */
  def maySwitchTo(user: User, contestId: Long): Boolean =
    user.userContests.exists(m => m.contestId == contestId && m.opens) || user.seriesContests.exists(_.contestId == contestId)

  /** A series admin's series page, where the start page sends a session with no real role in its
    * current contest (contest series plan §3.3, "Signing in"): the current contest's series if the
    * grants reach it, else the first series granted. None for anyone else.
    */
  def seriesLanding(user: User): Option[Long] =
    if (user.seriesGranted.isEmpty) None
    else user.contestId.flatMap(c => user.seriesContests.find(_.contestId == c)).map(_.seriesId)
      .orElse(user.seriesGranted.headOption)

  /** The series the user is a series admin of (a session's, [[withSeries]]). */
  def seriesGrantedTo(user: User): Seq[Long] = user.seriesGranted

  /** What the user's series grants give in the contests they reach (a session's), by contest id. */
  def seriesReachOf(user: User): Seq[db.scalikejdbc.SeriesContest] = user.seriesContests

  /** `result`, with `switchedTo`'s contest as the session's current one if it is not
    * `sessionUser`'s and the request is a page the browser opened ([[isPageRequest]]).
    */
  def switchingContest(sessionUser: User, switchedTo: User)(result: => Result)(implicit
      request: RequestHeader
  ): Result =
    switchedTo.contestId
      .filterNot(id => sessionUser.contestId.contains(id))
      .filter(_ => isPageRequest(request))
      .fold(result)(id => result.addingToSession(CurrentContestId -> id.toString))

  /** A full page opened in the browser, which may switch the session's contest: a GET that
    * is not a JSON poll, a fragment loaded by script ([[isScriptRequest]]) or a prefetch
    * (`Sec-Purpose` or `Purpose: prefetch`), which the user may never open.
    */
  def isPageRequest(request: RequestHeader): Boolean =
    request.method == "GET" && !isScriptRequest(request) && !isPrefetch(request)

  /** A request sent by a script, not a page or form the browser opens: scripts here send
    * `X-Requested-With`; browsers also say so with `Sec-Fetch-Mode` (`navigate` only for a
    * page they open).
    */
  def isScriptRequest(request: RequestHeader): Boolean =
    request.headers.get("X-Requested-With").contains("XMLHttpRequest") ||
      request.headers.get("Sec-Fetch-Mode").exists(!_.equalsIgnoreCase("navigate"))

  private def isPrefetch(request: RequestHeader): Boolean =
    Seq("Sec-Purpose", "Purpose").exists(name =>
      request.headers.getAll(name).exists(_.toLowerCase(java.util.Locale.ROOT).contains("prefetch"))
    )
}
