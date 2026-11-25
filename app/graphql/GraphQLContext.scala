package graphql

import api.ApiAuth
import controllers.Secured
import db.scalikejdbc.User
import org.intracer.wmua.ContestJury
import play.api.Logger
import play.api.mvc.RequestHeader

import scala.concurrent.duration.Deadline

/** What every resolver knows: the caller ([[ApiAuth.caller]]: a session or a personal token) and
  * the request, for the denials' log lines. The resolvers never take a contest id from the query
  * as a permission: each loads the resource, then judges the caller in the resource's contest
  * ([[asMemberOf]]).
  */
final case class GraphQLContext(caller: ApiAuth.Caller, request: RequestHeader, deadline: Deadline) {

  def user: User = caller.user

  /** Stops a run whose answer has timed out (its caller has had `timeout`) before its next query:
    * each resolver that reads the database calls it first.
    */
  def checkDeadline(): Unit =
    if (deadline.isOverdue()) {
      val reference = Secured.newReference()
      GraphQLContext.logger.info(s"graphql_deadline ref=$reference ${Secured.accountFields(request, Some(user))}")
      throw GraphQLError("timeout", GraphQLError.Code.InternalError, Some(reference))
    }

  /** The request's first denial's reference, and how many more followed: one `security.authz` line
    * per request, however many fields are denied ([[contestDenied]], [[logRepeatedDenials]]).
    */
  private val firstDenial = new java.util.concurrent.atomic.AtomicReference[String](null)
  private val moreDenials = new java.util.concurrent.atomic.AtomicInteger(0)

  /** The caller is root (a caller check, not a target's: `User.isProtectedTarget` is for targets). */
  def callerIsRoot: Boolean = user.hasRole(User.ROOT_ROLE)

  /** The caller as a member of the contest, with their role there, as the web UI's contest and round
    * pages judge them (contest series plan §3.3): [[GraphQLContext.access]], checked by
    * `Secured.checked`, the check of every action. A real membership counts if it is active and its
    * contest not retired; for a session, so does a contest series grant's derived role (Admin from
    * the grant's start year on, Organizer below it, CA20), except in a contest where the account holds
    * a real Juror row (CA7: the series doesn't apply there; `SeriesAdminJdbc.seriesContests`). A
    * personal token never gets series reach (`ApiTokenService` resolves the account without it).
    * Root in any contest. None when they have no access role there: not a member, only an inactive
    * role ("no", board, staff, auditor), or a retired contest.
    */
  def asMemberOf(contestId: Long): Option[User] =
    if (callerIsRoot) {
      val there = Secured.inContest(user, contestId)
      // root needs no membership; without one there, no contest role either (not the current contest's)
      Some(if (there.contestId.contains(contestId)) there
           else user.copy(contestId = Some(contestId), roles = user.roles -- user.contestRoles))
    } else
      Secured.checked(user, GraphQLContext.access(contestId)).toOption
        .filter(there => there.contestId.contains(contestId) && there.contestRoles.exists(User.ACCESS_ROLES))

  /** The contests the caller's series grants reach (a session's only: a token's user has none), with
    * the derived role there; [[asMemberOf]] judges each.
    */
  def seriesContestIds: Seq[Long] = Secured.seriesContestIds(user)

  /** A contest the caller may not see, as a missing one ([[GraphQLError.notFound]]). The request's
    * first denial is logged on `security.authz` with the reference the error carries; a later one
    * carries the same reference and is counted ([[logRepeatedDenials]]), so that one query with many
    * aliases writes one line, not one per field.
    */
  def contestDenied(contest: ContestJury): GraphQLError =
    denied(Secured.Denial.contest(contest.id, User.ACCESS_ROLES))

  /** A denial ([[Secured.Denial]]: a contest's, a round's) answered as `error` with the reference,
    * a missing resource ([[GraphQLError.notFound]]) by default, else e.g. [[GraphQLError.forbidden]]
    * for a resource the caller sees but may not use so (another juror's votes, a round's statistics
    * for its juror). The request's first denial is logged on `security.authz`; a later one carries
    * the same reference and is counted ([[logRepeatedDenials]]).
    */
  def denied(denial: Secured.Denial, error: String => GraphQLError = GraphQLError.notFound): GraphQLError =
    Option(firstDenial.get) match {
      case Some(reference) =>
        moreDenials.incrementAndGet()
        error(reference)
      case None =>
        val reference = Secured.logDenial(user, request, denial, caller.via.logFields)
        if (firstDenial.compareAndSet(null, reference)) error(reference)
        else {
          moreDenials.incrementAndGet()
          error(firstDenial.get)
        }
    }

  /** After the run: the denials after the first, counted on the first's line's logger. */
  def logRepeatedDenials(): Unit = {
    val more = moreDenials.get
    if (more > 0)
      GraphQLContext.authzLogger.warn(s"authorization_denied_repeated ref=${firstDenial.get} more=$more " +
        s"resource=${Secured.logValue("api:graphql")} ${caller.via.logFields.map { case (k, v) => s"$k=$v" }.mkString(" ")}")
  }
}

object GraphQLContext {

  /** The permission a contest's data needs: an access role there, a series grant's derived role
    * counting (an opt-in, listed in `security.SeriesReachGuardSpec`), as on the web UI's round and
    * gallery pages (contest series plan §3.3, "Every action, classified": rounds, galleries and
    * statistics take the derived role).
    */
  def access(contestId: Long): Secured.ContestPermission =
    Secured.ContestPermission(User.ACCESS_ROLES, Some(contestId), derived = true)

  private val logger = Logger("graphql")
  private val authzLogger = Logger("security.authz")
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
    val TooManyRequests = "TOO_MANY_REQUESTS"
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

  /** A resource the caller sees but may not use so, with the reference of its denial's log line. */
  def forbidden(reference: String): GraphQLError = GraphQLError("forbidden", Code.Forbidden, Some(reference))
}
