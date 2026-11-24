package api

import graphql.GraphQLController
import modules.BlockingExecutionContext
import org.apache.pekko.stream.Materializer
import play.api.libs.streams.Accumulator
import play.api.mvc.{EssentialAction, RequestHeader, Result}
import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** The REST API ([[Api]]) and the GraphQL endpoint ([[GraphQLController]], `/graphql`), mounted at
  * `/api` in `conf/routes`: Play strips the prefix, so the endpoints' paths start below it
  * (`/contests`, `/docs`, `/graphql`). Both take the same callers ([[ApiAuth.caller]]).
  *
  * Only two kinds of route are mounted here:
  *
  *  - Tapir's ([[Api.routes]]), each endpoint authorized by `Api.secured` ([[ApiAuth.authorize]]);
  *  - the others, each a [[CallerRoutes]], whose handlers take the caller: [[ApiRouter.guarded]]
  *    resolves it first ([[ApiAuth.caller]]) and answers the group's 401 without one, so the handler
  *    never runs.
  *
  * `api.ApiRouterGuardSpec` is a tripwire, not a proof: it fails if the router takes another kind of
  * dependency, if [[routes]] is not this composition, if `Api.routes` is not Tapir's interpreter
  * alone, or if `conf/routes` routes anything else under `/api`. A reviewer still checks a new route.
  */
class ApiRouter @Inject() (api: Api, graphql: GraphQLController, blocking: BlockingExecutionContext)(implicit
    ec: ExecutionContext,
    mat: Materializer
) extends SimpleRouter {

  /** Every route group that is not Tapir's: each mounted through [[ApiRouter.guarded]] only. */
  val callerRoutes: Seq[CallerRoutes] = Seq(graphql)

  // the guarded groups first: Tapir's routes end with a catch-all 404; built once
  private lazy val guardedRoutes: Routes =
    callerRoutes.map(ApiRouter.guarded(_, ApiAuth.caller, blocking)).reduce(_ orElse _).orElse(api.routes)

  final override def routes: Routes = guardedRoutes
}

object ApiRouter {

  /** `group`'s routes, each handler run only with the request's caller (`resolve`, [[ApiAuth.caller]]
    * as served, on `blocking`: it reads the database), before anything of the request is read: none,
    * the group's 401 ([[CallerRoutes.noCaller]]); a failure to resolve it,
    * [[CallerRoutes.callerUnavailable]].
    */
  def guarded(group: CallerRoutes, resolve: RequestHeader => Option[ApiAuth.Caller], blocking: ExecutionContext)(
      implicit
      ec: ExecutionContext,
      mat: Materializer
  ): Routes =
    group.callerRoutes.andThen { handler =>
      EssentialAction { request =>
        Accumulator.flatten(
          Future(resolve(request))(blocking)
            .map[Either[Throwable, Option[ApiAuth.Caller]]](Right(_))
            .recover { case NonFatal(e) => Left(e) }
            .map {
              case Left(e)             => Accumulator.done(group.callerUnavailable(e))
              case Right(None)         => Accumulator.done(group.noCaller)
              case Right(Some(caller)) => handler(caller)(request)
            }
        )
      }
    }
}

/** A group of API routes that are not Tapir's, mounted by [[ApiRouter]]: its handlers take the caller,
  * so they run only after [[ApiAuth.caller]] found one ([[ApiRouter.guarded]]). A handler still
  * judges what the caller may do (a token's scope, a resource's contest).
  */
trait CallerRoutes {

  /** The handler of each request the group serves (its path below `/api`), given the request's caller. */
  def callerRoutes: PartialFunction[RequestHeader, ApiAuth.Caller => EssentialAction]

  /** The answer to such a request without a caller: a 401. */
  def noCaller: Result

  /** The answer when the caller couldn't be resolved (a database error): a server error, logged. */
  def callerUnavailable(e: Throwable): Result
}
