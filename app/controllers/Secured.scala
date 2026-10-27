package controllers

import controllers.Secured.{CurrentContestId, UserName}
import db.scalikejdbc.{ContestJuryJdbc, Round, User, UserContestJdbc}
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
  def userFromRequest(request: RequestHeader): Option[User] =
    request.session.get(UserName).flatMap(Secured.sessionUser(_, request.session.get(CurrentContestId)))

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
    * and the reference of the denial's log line ([[Secured.logDenial]]).
    */
  def onUnAuthorized(user: User, request: RequestHeader, denial: Secured.Denial = Secured.Denial.empty): Result = {
    val reference = Secured.logDenial(user, request, denial)
    val suggestion = suggestedAccess(user)
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
        Secured.referenceTime()
      )(request.flash, messages)
    )
  }

  /** [[onUnAuthorized]] for JSON endpoints: the same log line, a JSON body, no suggestion. */
  def onUnAuthorizedJson(user: User, request: RequestHeader, denial: Secured.Denial = Secured.Denial.empty): Result = {
    val reference = Secured.logDenial(user, request, denial)
    Results.Forbidden(Json.obj("error" -> "forbidden", "reference" -> reference))
  }

  /** A useful destination from an active membership, the current contest's first: the
    * contest's files if the user may view them, else a gallery of one of its active rounds.
    */
  private def suggestedAccess(user: User): Option[Secured.AccessSuggestion] = {
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
          val url = if (round.isBinary)
            routes.GalleryController.list(user.getId, 1, "all", round.getId, Some(0)).url
          else
            routes.GalleryController.byRate(user.getId, 1, "all", round.getId).url
          Secured.AccessSuggestion(url, "access.denied.suggested.gallery", Some(membership.contestId), Some(round), rounds)
        }
      }
    }.nextOption()
  }

  def withAuth(
      permission: Permission = rolePermission(Secured.ViewerRoles)
  )(f: => User => Request[AnyContent] => Result): EssentialAction = {
    Security.Authenticated(userFromRequest, onUnAuthenticated) { user =>
      Action { request =>
        permitted(user, permission).fold(onUnAuthorized(user, request, Secured.denialOf(permission))) {
          permittedUser =>
            Secured.switchingContest(user, permittedUser)(f(permittedUser)(request))(request)
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
      userFromRequest(request).fold[Result](Results.Unauthorized(Json.obj("error" -> "unauthenticated"))) { user =>
        permitted(user, permission).fold(onUnAuthorizedJson(user, request, Secured.denialOf(permission))) {
          permittedUser => f(permittedUser)(request)
        }
      }
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
        userFromRequest(request).fold(onUnAuthenticated(request)) { user =>
          permitted(user, permission).fold(onUnAuthorized(user, request, Secured.denialOf(permission))) {
            permittedUser =>
              Secured.switchingContest(user, permittedUser)(f(permittedUser)(request))(request)
          }
        }
      }(ec)
    }

  private def permitted(user: User, permission: Permission): Option[User] = Secured.permitted(user, permission)

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

  /** Root, or one of the roles in the contest ([[User.hasContestRole]]). */
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

  /** What the permission requires, if it says; else nothing (`requiredRoles=unknown`). */
  def denialOf(permission: User => Boolean): Denial = permission match {
    case described: DescribedPermission => described.denial
    case _                              => Denial.empty
  }

  private val authzLogger = play.api.Logger("security.authz")

  /** Logs the denial at WARN and returns the line's reference, for the page to show. */
  def logDenial(user: User, request: RequestHeader, denial: Denial): String = {
    val reference = newReference()
    authzLogger.warn(denialLogLine(user, request, denial, reference))
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
  def denialLogLine(user: User, request: RequestHeader, denial: Denial, reference: String): String = {
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
    s"authorization_denied ref=$reference accountId=${user.getId} account=${logValue(user.emailLo)} " +
      s"method=${request.method} path=${logValue(request.path)} " +
      s"currentContestId=${user.contestId.getOrElse("none")} " +
      s"targetContestId=${denial.contestId.getOrElse("unknown")} " +
      s"resource=${denial.resource.fold("unknown")(logValue)} " +
      s"requiredRoles=${logValue(roleList(denial.requiredRoles, "unknown"))} " +
      s"accountRoles=${logValue(roleList(user.roles, "none"))} " +
      s"targetContestMembershipState=$targetMembershipState " +
      s"targetContestRoles=${logValue(targetContestRoles)} reason=${denialReason(user, denial)}"
  }

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

  /** The session's user name as users are looked up by: the login stores it as typed. */
  def normalizeUserName(userName: String): String = userName.trim.toLowerCase

  /** The session's user ([[Secured.userFromRequest]]), in `currentContestId` as
    * [[inCurrentContest]] picks it.
    */
  def sessionUser(userName: String, currentContestId: Option[String]): Option[User] =
    User.byUserName(normalizeUserName(userName)).map(inCurrentContest(_, currentContestId))

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
  def mayView(userName: String): Boolean =
    sessionUser(userName, None).exists(permitted(_, _.hasAnyRole(ViewerRoles)).isDefined)

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
