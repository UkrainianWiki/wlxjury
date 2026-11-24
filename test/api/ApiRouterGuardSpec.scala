package api

import db.scalikejdbc.{PlayTestDb, User}
import modules.BlockingExecutionContext
import org.apache.pekko.stream.Materializer
import play.api.mvc.{EssentialAction, RequestHeader, Result, Results}
import play.api.test.{FakeRequest, PlaySpecification}

import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext

/** A tripwire for routes under `/api` reached without a caller ([[ApiRouter]]), not a proof: the
  * router takes only Tapir's endpoints ([[Api]], each authorized by `Api.secured`,
  * `api.ApiEndpointScopeSpec`) and route groups whose handlers take the caller ([[CallerRoutes]]),
  * mounted through [[ApiRouter.guarded]], which resolves the caller first and never runs the handler
  * without one. It fails on the ways around that found so far: another kind of dependency of the
  * router, another composition of its routes, a non-Tapir route in `Api.routes`, another
  * `conf/routes` entry under `/api`. A reviewer still checks a new route.
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

  private def read(path: String): String = new String(Files.readAllBytes(Paths.get(path)), UTF_8)

  /** The Scala source without comments, its whitespace runs as one space. */
  private def code(path: String): String =
    read(path).replaceAll("""(?s)/\*.*?\*/""", " ").replaceAll("""(?m)//.*$""", " ").replaceAll("""\s+""", " ")

  /** Each occurrence of `token` in `text`. */
  private def occurrences(text: String, token: String): Int = text.sliding(token.length).count(_ == token)

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

    "compose its routes as the guarded groups, then Tapir's, and nothing else" in {
      val router = code("app/api/ApiRouter.scala")
      (router must contain("private lazy val guardedRoutes: Routes = " +
        "callerRoutes.map(ApiRouter.guarded(_, ApiAuth.caller, blocking)).reduce(_ orElse _).orElse(api.routes)")) and
        (router must contain("final override def routes: Routes = guardedRoutes")) and
        (router must contain("val callerRoutes: Seq[CallerRoutes] = Seq(graphql)")) and
        // no inline route: the two orElse above only, no routing DSL, no other route value
        (occurrences(router, "orElse") === 2) and
        (router must not(contain("sird"))) and (router must not(contain("p" + '"'))) and
        (occurrences(router, ": Routes =") === 3) // guardedRoutes, routes, and guarded's result
    }

    "build Api.routes from Tapir's interpreter alone" in {
      val api = code("app/api/Api.scala")
      (api must contain("val routes: Routes = " +
        "PlayServerInterpreter(serverOptions).toRoutes((swaggerInitializer +: docs) ++ apiEndpoints :+ notFound)")) and
        (occurrences(api, "orElse") === 0) and (api must not(contain("sird"))) and
        (occurrences(api, ": Routes") === 1)
    }

    "be conf/routes' only entry under /api" in {
      val underApi = read("conf/routes").linesIterator.map(_.trim)
        .filterNot(l => l.isEmpty || l.startsWith("#"))
        .map(_.split("""\s+""").toSeq)
        // any path starting with /api (a dynamic part included), not only /api/...
        .filter(fields => fields.lift(1).exists(_.startsWith("/api")))
        .toSeq
      underApi === Seq(Seq("->", "/api", "api.ApiRouter"))
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
