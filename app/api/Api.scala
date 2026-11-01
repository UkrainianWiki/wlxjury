package api

import db.scalikejdbc.User
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.intracer.wmua.ContestJury
import play.api.Logging
import play.api.http.HttpConfiguration
import play.api.mvc.RequestHeader
import play.api.routing.Router.Routes
import services.ContestService
import sttp.apispec.SecurityScheme
import sttp.apispec.openapi.{Components, OpenAPI}
import sttp.model.HeaderNames
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.play._
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.decodefailure.DefaultDecodeFailureHandler
import sttp.tapir.server.interceptor.exception.ExceptionHandler
import sttp.tapir.server.model.ValuedEndpointOutput
import sttp.tapir.server.play.{PlayServerInterpreter, PlayServerOptions}
import sttp.tapir.swagger.SwaggerUIOptions
import sttp.tapir.swagger.bundle.SwaggerInterpreter

import javax.inject.{Inject, Singleton}
import scala.collection.immutable.ListMap
import scala.concurrent.{ExecutionContext, Future}

/** The REST API, served under `/api` ([[ApiRouter]]) by Tapir's Play interpreter, so through
  * Play's filters (CSRF, security headers) like every page. A caller signs in with the
  * session (the SPA; a write then needs the `Csrf-Token` header) or a personal API token
  * ([[ApiAuth]]). The contest endpoints are root's only, as the contest pages are. Errors
  * are JSON ([[ApiFailure]]); Swagger UI and the OpenAPI document are public at `/api/docs`.
  */
@Singleton
class Api @Inject() (contestService: ContestService, actorSystem: ActorSystem, httpConfiguration: HttpConfiguration)(
    implicit mat: Materializer, ec: ExecutionContext
) extends JsonFormat
    with Logging {

  private val blockingDispatcher: ExecutionContext = actorSystem.dispatchers.lookup("blocking-dispatcher")

  private def blocking[T](f: => T): Future[T] = Future(f)(blockingDispatcher)

  /** The contest pages' permission (`ContestController`). */
  private val rootOnly = controllers.Secured.RolePermission(Set(User.ROOT_ROLE))

  private val failureOutput: EndpointOutput[ApiFailure] =
    statusCode
      .and(header[Option[String]](HeaderNames.WwwAuthenticate))
      .and(jsonBody[ApiError])
      .mapTo[ApiFailure]

  /** An endpoint the caller must be let in to by `permission`: resolved and checked
    * ([[ApiAuth.authorize]]) on the blocking dispatcher, before the body is read.
    */
  private def secured(permission: User => Boolean) =
    endpoint
      .securityIn(extractFromRequest(_.underlying.asInstanceOf[RequestHeader]))
      .errorOut(failureOutput)
      .serverSecurityLogic[ApiAuth.Caller, Future](request => blocking(ApiAuth.authorize(request, permission)))

  private val contests = "contests"

  private val createContest = secured(rootOnly).post
    .in(contests)
    .in(jsonBody[ContestJury])
    .out(jsonBody[ContestJury])
    .description("Creates a contest; an id in the body is ignored.")
    .serverLogicSuccess(_ => contest => blocking(contestService.createContest(contest.copy(id = None))))

  private val getContest = secured(rootOnly).get
    .in(contests / path[Long]("id"))
    .out(jsonBody[ContestJury])
    .serverLogic(_ => id => blocking(contestService.getContest(id).toRight(ApiFailure.NotFound)))

  private val updateContest = secured(rootOnly).put
    .in(contests)
    .in(jsonBody[ContestJury])
    .description("Updates the contest of the body's id.")
    .serverLogic(_ =>
      contest =>
        blocking(contest.id match {
          case None                                              => Left(ApiFailure.BadRequest)
          case Some(id) if contestService.getContest(id).isEmpty => Left(ApiFailure.NotFound)
          case Some(_)                                           => Right(contestService.updateContest(contest))
        })
    )

  private val listContests = secured(rootOnly).get
    .in(contests)
    .out(jsonBody[List[ContestJury]])
    .serverLogicSuccess(_ => _ => blocking(contestService.findContests()))

  private val apiEndpoints: List[ServerEndpoint[Any, Future]] =
    List(createContest, getContest, updateContest, listContests)

  /** Any other path under `/api`: a JSON 404, not the HTML page of the app's error handler. */
  private val notFound: ServerEndpoint[Any, Future] =
    endpoint
      .in(paths)
      .errorOut(failureOutput)
      .serverLogicPure[Future](_ => Left(ApiFailure.NotFound))

  private val sessionCookieName = httpConfiguration.session.cookieName

  /** Both ways in, for Swagger UI: the two security schemes, either one on every operation. */
  private def withSecuritySchemes(openApi: OpenAPI): OpenAPI =
    openApi
      .components(
        openApi.components
          .getOrElse(Components())
          .addSecurityScheme(
            "bearerToken",
            SecurityScheme(
              `type` = "http",
              scheme = Some("bearer"),
              description = Some("A personal API token (wlxj_...), created on your profile page.")
            )
          )
          .addSecurityScheme(
            "sessionCookie",
            SecurityScheme(
              `type` = "apiKey",
              name = Some(sessionCookieName),
              in = Some("cookie"),
              description = Some("The signed-in session; a write needs the Csrf-Token header too.")
            )
          )
      )
      .security(List(ListMap("bearerToken" -> Vector.empty), ListMap("sessionCookie" -> Vector.empty)))

  private val docs: List[ServerEndpoint[Any, Future]] =
    SwaggerInterpreter(
      customiseDocsModel = withSecuritySchemes,
      swaggerUIOptions = SwaggerUIOptions.default.contextPath(List("api"))
    ).fromServerEndpoints[Future](apiEndpoints, "WLX Jury", "1.0")

  private def failure(value: ApiFailure): ValuedEndpointOutput[_] = ValuedEndpointOutput(failureOutput, value)

  private val serverOptions: PlayServerOptions =
    PlayServerOptions
      .customiseInterceptors()
      // the status only: the default message names the input and may quote it
      .decodeFailureHandler(
        DefaultDecodeFailureHandler[Future].copy[Future](response = (status, _, _) => failure(ApiFailure.undecodable(status)))
      )
      .exceptionHandler(ExceptionHandler.pure[Future] { ctx =>
        val reference = controllers.Secured.newReference()
        logger.error(
          s"api_error ref=$reference method=${ctx.request.method} " +
            s"path=${controllers.Secured.logValue(ctx.request.pathSegments.mkString("/", "/", ""))}",
          ctx.e
        )
        Some(failure(ApiFailure.serverError(reference)))
      })
      .options

  /** The docs, the API, and [[notFound]] for any other path. */
  val routes: Routes = PlayServerInterpreter(serverOptions).toRoutes(docs ++ apiEndpoints :+ notFound)
}
