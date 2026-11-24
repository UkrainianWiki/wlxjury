package api

import db.scalikejdbc.{PlayTestDb, User}
import modules.BlockingExecutionContext
import org.apache.pekko.stream.Materializer
import play.api.mvc.{EssentialAction, RequestHeader, Result, Results}
import play.api.test.{FakeRequest, PlaySpecification}

import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext

/** Nothing under `/api` is reached without a caller ([[ApiRouter]]): the router takes only Tapir's
  * endpoints ([[Api]], each authorized by `Api.secured`, `api.ApiEndpointScopeSpec`) and route groups
  * whose handlers take the caller ([[CallerRoutes]]), mounted through [[ApiRouter.guarded]], which
  * resolves the caller first and never runs the handler without one. A new non-Tapir route has to be a
  * `CallerRoutes` to be mounted at all: a router of another type injected here fails this spec.
  */
class ApiRouterGuardSpec extends PlaySpecification with PlayTestDb {

  /** A group whose handler records the caller it ran with, and answers 200. */
  private class Recording extends CallerRoutes {
    val ranWith = new AtomicReference[Option[ApiAuth.Caller]](None)
    def callerRoutes: PartialFunction[RequestHeader, ApiAuth.Caller => EssentialAction] = {
      case r if r.path == "/probe" => caller => EssentialAction { _ =>
        ranWith.set(Some(caller))
        play.api.libs.streams.Accumulator.done(Results.Ok("ran"))
      }
    }
    def noCaller: Result = Results.Unauthorized("no caller")
    def callerUnavailable(e: Throwable): Result = Results.InternalServerError("unavailable")
  }

  private val caller = ApiAuth.Caller(User("u", "u@example.com", Some(7L)), ApiAuth.BySession)

  "the API router" should {

    "take only Tapir's API and CallerRoutes groups (and its execution contexts), its routes final" in {
      val allowed = Set[Class[_]](classOf[Api], classOf[BlockingExecutionContext], classOf[ExecutionContext],
        classOf[Materializer])
      val parameters = classOf[ApiRouter].getConstructors.toSeq.flatMap(_.getParameterTypes.toSeq)
      val others = parameters.filterNot(t => allowed(t) || classOf[CallerRoutes].isAssignableFrom(t))
      (others must beEmpty) and
        (parameters.exists(classOf[CallerRoutes].isAssignableFrom) must beTrue) and
        (Modifier.isFinal(classOf[ApiRouter].getMethod("routes").getModifiers) must beTrue)
    }

    "mount every CallerRoutes group it takes, GraphQL's included" in {
      testDbApp { app =>
        val router = app.injector.instanceOf[ApiRouter]
        router.callerRoutes.map(_.getClass) === Seq(classOf[graphql.GraphQLController])
      }
    }

    "run a group's handler only with a caller: none is the group's 401, a failure its server error" in {
      testDbApp { app =>
        implicit val mat: Materializer = app.materializer
        implicit val ec: ExecutionContext = app.actorSystem.dispatcher
        def run(resolve: RequestHeader => Option[ApiAuth.Caller]) = {
          val group = new Recording
          val routes = ApiRouter.guarded(group, resolve, ec)
          val request = FakeRequest(POST, "/probe")
          val result = routes(request) match {
            case action: EssentialAction => action(request).run()
            case other                   => throw new IllegalStateException(s"not an action: $other")
          }
          (status(result), contentAsString(result), group.ranWith.get)
        }
        (run(_ => None) === ((UNAUTHORIZED, "no caller", None))) and
          (run(_ => throw new RuntimeException("db down")) === ((INTERNAL_SERVER_ERROR, "unavailable", None))) and
          (run(_ => Some(caller)) === ((OK, "ran", Some(caller)))) and
          // a path the group doesn't serve is not taken
          (ApiRouter.guarded(new Recording, _ => Some(caller), ec).isDefinedAt(FakeRequest(GET, "/other")) must beFalse)
      }
    }
  }
}
