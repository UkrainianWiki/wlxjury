package api

import db.scalikejdbc.User
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.intracer.wmua.ContestJury
import play.api.{Configuration, Logging}
import play.api.http.HttpConfiguration
import play.api.i18n.MessagesApi
import play.api.mvc.{PlayBodyParsers, RequestHeader}
import play.api.routing.Router.Routes
import services.ContestService
import services.diagnostics.{ContestHealth, RecentActivity}
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
class Api @Inject() (
    contestService: ContestService,
    actorSystem: ActorSystem,
    httpConfiguration: HttpConfiguration,
    bodyParsers: PlayBodyParsers,
    messagesApi: MessagesApi,
    configuration: Configuration
)(
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

  /** An endpoint, named `name` (its id in [[ApiAuth.Endpoints]] and the OpenAPI document), the
    * caller must be let in to by `permission`: resolved and checked ([[ApiAuth.authorize]]) on the
    * blocking dispatcher, before the body is read.
    */
  private def secured(name: String, permission: User => Boolean) =
    endpoint
      .name(name)
      .securityIn(extractFromRequest(_.underlying.asInstanceOf[RequestHeader]))
      .errorOut(failureOutput)
      .serverSecurityLogic[ApiAuth.Caller, Future](request => blocking(ApiAuth.authorize(request, permission, name)))

  /** An endpoint of one contest (`/contests/{id}/...`) the caller must be let in to by
    * `permission(id)`: as [[secured]], the id a security input, so that the check can name the
    * contest.
    */
  private def securedContest(name: String, permission: Long => User => Boolean) =
    endpoint
      .name(name)
      .securityIn(contests / path[Long]("id"))
      .securityIn(extractFromRequest(_.underlying.asInstanceOf[RequestHeader]))
      .errorOut(failureOutput)
      .serverSecurityLogic[(Long, ApiAuth.Caller), Future] { case (id, request) =>
        blocking(ApiAuth.authorize(request, permission(id), name).map(id -> _))
      }

  private val contests = "contests"

  private val createContest = secured("createContest", rootOnly).post
    .in(contests)
    .in(jsonBody[ContestJury])
    .out(jsonBody[ContestJury])
    .description("Creates a contest; an id in the body is ignored.")
    .serverLogicSuccess(caller => contest => blocking(contestService.createContest(contest.copy(id = None), Some(caller.user.getId))))

  private val getContest = secured("getContest", rootOnly).get
    .in(contests / path[Long]("id"))
    .out(jsonBody[ContestJury])
    .serverLogic(_ => id => blocking(contestService.getContest(id).toRight(ApiFailure.NotFound)))

  private val updateContest = secured("updateContest", rootOnly).put
    .in(contests)
    .in(jsonBody[ContestJury])
    .description("Updates the contest of the body's id.")
    .serverLogic(caller =>
      contest =>
        blocking(contest.id match {
          case None                                              => Left(ApiFailure.BadRequest)
          case Some(id) if contestService.getContest(id).isEmpty => Left(ApiFailure.NotFound)
          // a series contest whose new type, country or year no longer fits its series (plan §3.1)
          case Some(_) => contestService.updateContest(contest, Some(caller.user.getId)).left.map(_ => ApiFailure.BadRequest)
        })
    )

  private val listContests = secured("listContests", rootOnly).get
    .in(contests)
    .out(jsonBody[List[ContestJury]])
    .serverLogicSuccess(_ => _ => blocking(contestService.findContests()))

  private val healthSettings = ContestHealth.Settings(configuration)

  /** The diagnostics' texts: English, whatever the JVM's locale, beside their message keys. */
  private def english: play.api.i18n.Messages = messagesApi.preferred(Seq(play.api.i18n.Lang("en")))

  /** The health page's permission (`ContestHealthController`, DG2): root, or an admin of the
    * contest as an active member of it. A retired contest's membership opens nothing, so root
    * alone reads a retired contest's report, as the page.
    */
  private def contestAdmins(contestId: Long): User => Boolean =
    controllers.Secured.ContestPermission(User.ADMIN_ROLES, Some(contestId))

  private val contestHealth = securedContest("contestHealth", contestAdmins).get
    .in("health")
    .out(jsonBody[DiagnosticsJson.Health])
    .description("The contest's health report, as its health page shows it (support diagnostics, D-1a): " +
      "root and the contest's admins. Titles and hints' texts are in English, beside their message keys.")
    .serverLogic { case (id, caller) =>
      _ =>
        blocking(contestService.getContest(id).toRight(ApiFailure.NotFound).map { contest =>
          val report = ContestHealth.report(contest, caller.user, healthSettings)
          DiagnosticsJson.health(report)(english)
        })
    }

  private val recentActivity = secured("recentActivity", rootOnly).get
    .in("diagnostics" / "recent")
    .in(query[Option[Int]]("hours").description(
      s"The window in hours, ${RecentActivity.MinHours} to ${RecentActivity.MaxHours}; ${RecentActivity.DefaultHours} by default"))
    .out(jsonBody[DiagnosticsJson.Recent])
    .description("Every contest's jobs and round status changes of the window, the worst first, and the jobs of " +
      "no contest: root only. The log's denials are not included.")
    .serverLogic(_ =>
      hours =>
        hours.getOrElse(RecentActivity.DefaultHours) match {
          case h if h < RecentActivity.MinHours || h > RecentActivity.MaxHours => Future.successful(Left(ApiFailure.BadRequest))
          case h =>
            blocking(Right(DiagnosticsJson.recent(RecentActivity.report(h, healthSettings.distributingMinutes))(
              english)))
        }
    )

  /** Every API endpoint; each is named and classified for read-only tokens in
    * [[ApiAuth.Endpoints]] (`api.ApiEndpointScopeSpec` fails on one that isn't).
    */
  private[api] val apiEndpoints: List[ServerEndpoint[Any, Future]] =
    List(createContest, getContest, updateContest, listContests, contestHealth, recentActivity)

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
              description = Some("A personal API token (wlxj_...), created on your profile page. A write " +
                "with neither it nor the session gets 403 {\"error\": \"csrf\"}.")
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

  /** Swagger UI's initializer, served ahead of the stock one ([[docs]]): the same settings
    * (Tapir's), and `validatorUrl: null`, so that the page loads no badge from
    * validator.swagger.io with the document's URL.
    */
  private val swaggerInitializer: ServerEndpoint[Any, Future] =
    endpoint.get
      .in("docs" / "swagger-initializer.js")
      .out(stringBodyUtf8AnyFormat(Codec.string.format(CodecFormat.TextJavascript())))
      .serverLogicSuccessPure[Future](_ => Api.SwaggerInitializer)

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
      // the app's parsers, whose limits answer through its error handler (JSON under /api)
      .copy(playBodyParsers = bodyParsers)

  /** The docs, the API, and [[notFound]] for any other path; tried in this order. */
  val routes: Routes =
    PlayServerInterpreter(serverOptions).toRoutes((swaggerInitializer +: docs) ++ apiEndpoints :+ notFound)
}

object Api {

  /** Swagger UI's `swagger-initializer.js` as Tapir serves it (relative paths, no extensions
    * shown), with the validator badge off.
    */
  val SwaggerInitializer: String =
    """window.onload = function() {
      |  window.ui = SwaggerUIBundle({
      |    url: "./docs.yaml",
      |    dom_id: '#swagger-ui',
      |    deepLinking: true,
      |    presets: [
      |      SwaggerUIBundle.presets.apis,
      |      SwaggerUIStandalonePreset
      |    ],
      |    plugins: [
      |      SwaggerUIBundle.plugins.DownloadUrl
      |    ],
      |    layout: "StandaloneLayout",
      |    showExtensions: false,
      |    validatorUrl: null
      |  });
      |};
      |""".stripMargin
}
