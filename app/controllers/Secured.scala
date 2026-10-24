package controllers

import controllers.Secured.{CurrentContestId, UserName}
import db.scalikejdbc.{Round, User, UserContestJdbc}
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

/** Base trait for secured controllers
  */
abstract class Secured(cc: ControllerComponents) extends AbstractController(cc) {

  type Permission = User => Boolean

  /** @param request
    *   HTTP request with username set in session
    * @return
    *   optional user from database, in the session's current contest
    */
  def userFromRequest(request: RequestHeader): Option[User] =
    request.session.get(UserName).flatMap(Secured.sessionUser(_, request.session.get(CurrentContestId)))

  def onUnAuthenticated(request: RequestHeader): Result =
    Results.Redirect(routes.LoginController.login())

  def onUnAuthorized(user: User): Result =
    Results.Redirect(routes.LoginController.error("You don't have permission to access this page"))

  def withAuth(
      permission: Permission = rolePermission(Secured.ViewerRoles)
  )(f: => User => Request[AnyContent] => Result): EssentialAction = {
    Security.Authenticated(userFromRequest, onUnAuthenticated) { user =>
      Action { request =>
        permitted(user, permission).fold(onUnAuthorized(user)) { permittedUser =>
          Secured.switchingContest(user, permittedUser)(f(permittedUser)(request))(request)
        }
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
          permitted(user, permission).fold(onUnAuthorized(user)) { permittedUser =>
            Secured.switchingContest(user, permittedUser)(f(permittedUser)(request))(request)
          }
        }
      }(ec)
    }

  private def permitted(user: User, permission: Permission): Option[User] = Secured.permitted(user, permission)

  def rolePermission(roles: Set[String])(user: User): Boolean = user.hasAnyRole(roles)

  def isRoot(user: User): Boolean = rolePermission(Set(User.ROOT_ROLE))(user)

  def contestPermission(roles: Set[String], contestId: Option[Long])(user: User): Boolean =
    user.hasContestRole(roles, contestId)

  def roundPermission(roles: Set[String], roundId: Long)(user: User): Boolean =
    Round.findById(roundId).exists { round =>
      contestPermission(roles, Some(round.contestId))(user)
    }
}

object Secured {
  val UserName         = "username"
  val CurrentContestId = "current_contest_id"

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
    * `sessionUser`'s.
    */
  def switchingContest(sessionUser: User, switchedTo: User)(result: => Result)(implicit
      request: RequestHeader
  ): Result =
    switchedTo.contestId
      .filterNot(id => sessionUser.contestId.contains(id))
      .fold(result)(id => result.addingToSession(CurrentContestId -> id.toString))
}
