package controllers

import controllers.Secured.{CurrentContestId, UserName}
import db.scalikejdbc.{Round, User, UserContestJdbc}
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

/** Base trait for secured controllers
  */
abstract class Secured(cc: ControllerComponents) extends AbstractController(cc) {

  type Permission = User => Boolean

  def userFromRequest(request: RequestHeader): Option[User] = {
    request.session
      .get(UserName)
      .map(_.trim.toLowerCase)
      .flatMap(User.byUserName)
      .map { user =>
        if (user.isRoot) {
          user.copy(userContests = Nil)
        } else {
          val memberships = UserContestJdbc.findByUser(user.getId)
          val currentContestId: Option[Long] =
            Option(request.session.get(CurrentContestId)).flatten
              .flatMap(s => scala.util.Try(s.toLong).toOption)
              .filter(cid => memberships.exists(_.contestId == cid))
              .orElse(memberships.headOption.map(_.contestId))

          currentContestId match {
            case Some(cid) =>
              val contestRole = memberships.find(_.contestId == cid).map(_.role).getOrElse("jury")
              user.copy(
                contestId    = Some(cid),
                roles        = user.roles ++ Set(contestRole),
                userContests = memberships
              )
            case None =>
              user.copy(userContests = memberships)
          }
        }
      }
  }

  def onUnAuthenticated(request: RequestHeader): Result =
    Results.Redirect(routes.LoginController.login())

  def onUnAuthorized(user: User): Result =
    Results.Redirect(routes.LoginController.error("You don't have permission to access this page"))

  def withAuth(
      permission: Permission = rolePermission(User.ADMIN_ROLES ++ Set("jury", "organizer"))
  )(f: => User => Request[AnyContent] => Result): EssentialAction = {
    Security.Authenticated(userFromRequest, onUnAuthenticated) { user =>
      Action { request =>
        if (permission(user))
          f(user)(request)
        else
          onUnAuthorized(user)
      }
    }
  }

  /** [[withAuth]] for actions that run slow blocking JDBC work: the authentication, the
    * permission check and the action run on `ec` (the blocking dispatcher) instead of
    * Play's default dispatcher, whose few threads every other request needs.
    */
  def withAuthOn(ec: ExecutionContext)(
      permission: Permission = rolePermission(User.ADMIN_ROLES ++ Set("jury", "organizer"))
  )(f: => User => Request[AnyContent] => Result): EssentialAction =
    Action.async { request =>
      Future {
        userFromRequest(request) match {
          case None                           => onUnAuthenticated(request)
          case Some(user) if permission(user) => f(user)(request)
          case Some(user)                     => onUnAuthorized(user)
        }
      }(ec)
    }

  def rolePermission(roles: Set[String])(user: User): Boolean = user.hasAnyRole(roles)

  def isRoot(user: User): Boolean = rolePermission(Set(User.ROOT_ROLE))(user)

  def contestPermission(roles: Set[String], contestId: Option[Long])(user: User): Boolean = {
    isRoot(user) ||
    (user.hasAnyRole(roles) && user.isInContest(contestId))
  }

  def roundPermission(roles: Set[String], roundId: Long)(user: User): Boolean =
    Round.findById(roundId).exists { round =>
      contestPermission(roles, Some(round.contestId))(user)
    }
}

object Secured {
  val UserName         = "username"
  val CurrentContestId = "current_contest_id"
}
