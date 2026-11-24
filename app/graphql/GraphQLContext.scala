package graphql

import api.ApiAuth
import controllers.Secured
import db.scalikejdbc.User
import org.intracer.wmua.ContestJury
import play.api.Logger
import play.api.mvc.RequestHeader

/** What every resolver knows: the caller ([[ApiAuth.caller]]: a session or a personal token) and
  * the request, for the denials' log lines. The resolvers never take a contest id from the query
  * as a permission: each loads the resource, then judges the caller in the resource's contest
  * ([[asMemberOf]]).
  */
final case class GraphQLContext(caller: ApiAuth.Caller, request: RequestHeader) {

  def user: User = caller.user

  def isRoot: Boolean = user.hasRole(User.ROOT_ROLE)

  /** The caller as a member of the contest (`Secured.inContest`: an active membership of a contest
    * that is not retired), with their role there; root in any contest. None when they have no
    * access role there: not a member, only an inactive role ("no", board, staff, auditor), a
    * retired contest, or (a session's) only a contest series grant's reach, whose derived role
    * counts only in an action that opts in to it (none here; contest series plan §3.3).
    */
  def asMemberOf(contestId: Long): Option[User] =
    if (isRoot) Some(user.copy(contestId = Some(contestId)))
    else {
      val there = Secured.inContest(user, contestId)
      Option.when(there.contestId.contains(contestId) && there.contestRoles.exists(User.ACCESS_ROLES))(there)
    }

  /** A contest the caller may not see, as a missing one ([[GraphQLError.notFound]]), logged as a
    * denial on `security.authz` with the reference the error carries.
    */
  def contestDenied(contest: ContestJury): GraphQLError = {
    val reference = Secured.logDenial(user, request,
      Secured.Denial.contest(contest.id, User.ACCESS_ROLES), caller.via.logFields)
    GraphQLError.notFound(reference)
  }
}

/** An error a resolver or the endpoint answers with: a message key (never request or exception
  * text), its `extensions.code` and, for a denial or a server error, the reference of its log line.
  */
final case class GraphQLError(message: String, code: String, reference: Option[String] = None)
    extends Exception(message, null, false, false)

object GraphQLError {

  /** The codes of `extensions.code` (plan §6, "Errors"). */
  object Code {
    val Unauthenticated = "UNAUTHENTICATED"
    val Forbidden = "FORBIDDEN"
    val NotFound = "NOT_FOUND"
    val BadInput = "BAD_INPUT"
    val InternalError = "INTERNAL_ERROR"
  }

  private val logger = Logger("graphql")

  /** No such resource, or one the caller may not see: the same answer, both with a reference, so
    * that the two can't be told apart. A missing one is logged at DEBUG only (a denial is logged
    * by the caller, [[GraphQLContext.contestDenied]]).
    */
  def notFound(reference: String): GraphQLError = GraphQLError("not_found", Code.NotFound, Some(reference))

  def missing(what: String): GraphQLError = {
    val reference = Secured.newReference()
    logger.debug(s"graphql_not_found ref=$reference resource=$what")
    notFound(reference)
  }

  def badInput(message: String): GraphQLError = GraphQLError(message, Code.BadInput)
}
