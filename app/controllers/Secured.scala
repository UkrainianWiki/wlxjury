package controllers

import controllers.Secured.{CurrentContestId, UserName}
import db.scalikejdbc.{ContestJuryJdbc, Round, User, UserContestJdbc}
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
    val suggestion = suggestedAccess(user)(request)
    val safeReturnTo = Secured.safeLocalPath(Option.when(request.method == "GET")(request.uri))
    val messages = cc.messagesApi.preferred(request)
    val currentContest = user.contestId.flatMap(ContestJuryJdbc.findById)
    Results.Forbidden(
      views.html.accessDenied(
        user,
        suggestion,
        safeReturnTo,
        denial.copy(reason = Some(Secured.denialReason(user, denial))),
        currentContest,
        reference,
        Secured.referenceTime(),
        notice,
        back.flatMap(url => Secured.safeLocalPath(Some(url)))
      )(request.flash, messages, request)
    )
  }

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

  /** A useful destination from an active membership, the current contest's first: the
    * contest's files if the user may view them, else a gallery of one of its active rounds.
    */
  private def suggestedAccess(user: User)(implicit request: RequestHeader): Option[Secured.AccessSuggestion] = {
    val memberships = user.userContests
      .filter(_.active)
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
  }

  def withAuth(
      permission: Permission = rolePermission(Secured.ViewerRoles)
  )(f: => User => Request[AnyContent] => Result): EssentialAction = {
    Security.Authenticated(r => RequestTiming.within(r)(userFromRequest(r)), onUnAuthenticated) { user =>
      Action { request =>
        RequestTiming.within(request) {
          checked(user, permission).fold(
            onUnAuthorized(user, request, _),
            permittedUser => Secured.switchingContest(user, permittedUser)(f(permittedUser)(request))(request)
          )
        }
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

  def rolePermission(roles: Set[String]): Secured.RolePermission = Secured.RolePermission(roles)

  def isRoot(user: User): Boolean = rolePermission(Set(User.ROOT_ROLE))(user)

  def contestPermission(roles: Set[String], contestId: Option[Long]): Secured.ContestPermission =
    Secured.ContestPermission(roles, contestId)

  def roundPermission(roles: Set[String], roundId: Long): Secured.RoundPermission =
    Secured.RoundPermission(roles, roundId)
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

  /** A permission that tells its denial what it requires. */
  sealed trait DescribedPermission extends (User => Boolean) {
    def denial: Denial
  }

  /** One of the roles, in the user's contest ([[permitted]] tries their others too). */
  final case class RolePermission(roles: Set[String]) extends DescribedPermission {
    def apply(user: User): Boolean = user.hasAnyRole(roles)
    def denial: Denial = Denial(requiredRoles = roles)
  }

  /** Root, or one of the roles in the contest ([[User.hasContestRole]]); a request's without
    * a contest is checked in the session's current one ([[forUser]]).
    */
  final case class ContestPermission(roles: Set[String], contestId: Option[Long]) extends DescribedPermission {
    def apply(user: User): Boolean = user.hasContestRole(roles, contestId)
    def denial: Denial = Denial.contest(contestId, roles)
  }

  /** Root, or one of the roles in the round's contest; the round is loaded once. */
  final case class RoundPermission(roles: Set[String], roundId: Long) extends DescribedPermission {
    private lazy val round = Round.findById(roundId)
    def apply(user: User): Boolean = round.exists(r => user.hasContestRole(roles, Some(r.contestId)))
    def denial: Denial = Denial(round.map(_.contestId), Some(s"round:$roundId"), roles)
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
    permitted(user, userPermission).toRight(denialOf(userPermission))
  }

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
            case Some(m) if required.nonEmpty && m.roles.intersect(required).isEmpty => "missing_role"
            case Some(_) if !user.contestId.contains(id)                            => "resource_in_other_contest"
            case Some(_)                                                            => "permission_check_failed"
          }
        case None =>
          val active = user.userContests.filter(_.active)
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

  /** Escape untrusted text so log fields stay single-line and key-value parseable. */
  /** A request path for a log line: [[logValue]], with the token of a mailed link
    * (`/invitation/<token>`, `/email/confirm/<token>`) masked.
    */
  def logPath(path: String): String =
    logValue(path.replaceAll("^/(invitation|email/confirm|oauth/choose/confirm)/[^/]+", "/$1/***"))

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
      .map(inCurrentContest(_, currentContestId))

  /** `permission` as checked for the session's user: a [[ContestPermission]] without a
    * contest (a page whose optional `contestId` is missing) in their current contest, the
    * one such a page shows, and only there, as if the page named it; denied without a
    * current contest, root aside.
    */
  def forUser(user: User, permission: User => Boolean): User => Boolean = permission match {
    case ContestPermission(roles, None) => ContestPermission(roles, user.contestId)
    case other                          => other
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
        .filter(m => m.active && !user.contestId.contains(m.contestId))
        .map(user.inContest)
        .find(permission)

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
    val active = memberships.filter(_.active)
    val requested = currentContestId.flatMap(_.toLongOption)
    requested
      .flatMap(id => active.find(_.contestId == id))
      .orElse(active.headOption)
      .fold(withMemberships)(withMemberships.inContest)
  }

  /** The user in the contest, if they are an active member of it; else unchanged. */
  def inContest(user: User, contestId: Long): User =
    if (user.contestId.contains(contestId)) user
    else user.userContests.find(m => m.contestId == contestId && m.active).fold(user)(user.inContest)

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
