// app/graphql/GraphQLContext.scala
package graphql

import db.scalikejdbc.User
import sangria.execution.{ExceptionHandler, HandledException}

case class GraphQLContext(
  currentUser: Option[User],
  eventBus: SubscriptionEventBus = SubscriptionEventBus.noop
) {

  def requireAuth(): User =
    currentUser.getOrElse(throw AuthenticationError("Not authenticated"))

  def requireRole(role: String): User = {
    val user = requireAuth()
    if (!user.hasRole(role) && !user.hasRole("root"))
      throw AuthorizationError(s"Requires role: $role")
    user
  }
}

case class AuthenticationError(message: String) extends Exception(message)
case class AuthorizationError(message: String)  extends Exception(message)
case class NotFoundError(message: String)        extends Exception(message)
case class BadInputError(message: String)        extends Exception(message)

object GraphQLContext {
  val exceptionHandler: ExceptionHandler = ExceptionHandler {
    case (m, e: AuthenticationError) =>
      HandledException(e.getMessage,
        Map("code" -> m.scalarNode("UNAUTHENTICATED", "String", Set.empty)))
    case (m, e: AuthorizationError) =>
      HandledException(e.getMessage,
        Map("code" -> m.scalarNode("FORBIDDEN", "String", Set.empty)))
    case (m, e: NotFoundError) =>
      HandledException(e.getMessage,
        Map("code" -> m.scalarNode("NOT_FOUND", "String", Set.empty)))
    case (m, e: BadInputError) =>
      HandledException(e.getMessage,
        Map("code" -> m.scalarNode("BAD_INPUT", "String", Set.empty)))
    case (m, _) =>
      HandledException("Internal error",
        Map("code" -> m.scalarNode("INTERNAL_ERROR", "String", Set.empty)))
  }
}
