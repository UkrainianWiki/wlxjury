package graphql

import api.{ApiAuth, ApiFailure}
import controllers.Secured
import modules.BlockingExecutionContext
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.util.ByteString
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.mvc._
import play.api.routing.Router.Routes
import play.api.routing.sird._
import play.api.{Configuration, Logger}
import sangria.ast
import sangria.execution._
import sangria.marshalling.ResultMarshaller
import sangria.parser.{QueryParser, SyntaxError}
import sangria.validation.{AstNodeLocation, QueryValidator, Violation}

import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, TimeoutException}
import scala.util.{Failure, Success, Try}

/** The GraphQL endpoint's limits (`wlxjury.graphql` in `conf/application.conf`). */
final case class GraphQLSettings(
    maxBodyBytes: Long,
    limits: QueryLimits.Limits,
    maxComplexity: Double,
    timeout: FiniteDuration
)

object GraphQLSettings {
  def apply(configuration: Configuration): GraphQLSettings = {
    val c = configuration.get[Configuration]("wlxjury.graphql")
    GraphQLSettings(
      maxBodyBytes = c.get[Long]("maxBodyBytes"),
      limits = QueryLimits.Limits(
        maxDepth = c.get[Int]("maxDepth"),
        maxIntrospectionDepth = c.get[Int]("maxIntrospectionDepth"),
        maxAliases = c.get[Int]("maxAliases"),
        maxFields = c.get[Int]("maxFields")
      ),
      maxComplexity = c.get[Double]("maxComplexity"),
      timeout = c.get[FiniteDuration]("timeout")
    )
  }
}

/** `POST /api/graphql` (plan §4), inside the API's router ([[api.ApiRouter]]), so behind the same
  * filters as the REST API: the allowed hosts (a `Host` not of the deployment gets its 400 first)
  * and CSRF (every POST with the session cookie needs the `Csrf-Token` header; only a Bearer request
  * without the session cookie skips it, [[ApiAuth.bearerWithoutSession]]). Then, in this order:
  *
  *  1. any other method: 405, nothing read or run;
  *  2. the caller ([[ApiAuth.caller]], on the blocking pool, before the body is read): a personal
  *     token or the session, the token deciding when both come (a cookie never adds to a token's
  *     rights); none, 401; a read-only, server-only token is refused as on the REST API's writes
  *     ([[ApiAuth.scopeRefusal]], `token_read_only`), logged;
  *  3. `Content-Type: application/json`, else 415; the body at most `maxBodyBytes`, else 413;
  *  4. one JSON object `{query, operationName?, variables?}`: an array (a batch) or anything else is
  *     400; a document with more than one operation is refused (no batching);
  *  5. a token caller's mutation or subscription: 403, logged on `security.authz` (tokens are
  *     queries only until a write scope exists, decision Q3);
  *  6. the document's shape ([[QueryLimits]]), Sangria's validation, then the run, on the blocking
  *     pool, with the complexity budget and a timeout.
  *
  * Introspection needs a caller (step 2), so it is refused signed out. Errors are GraphQL's
  * `errors` with a message key (never request or exception text), `extensions.code` and, for a
  * denial or a server error, `extensions.reference`; the HTTP failures (401, 403, 400, 405) also
  * carry the REST API's `error` field ([[ApiFailure]]).
  */
@Singleton
class GraphQLController @Inject() (
    parsers: PlayBodyParsers,
    blocking: BlockingExecutionContext,
    actorSystem: ActorSystem,
    configuration: Configuration
)(implicit ec: ExecutionContext, mat: Materializer) {

  import GraphQLController._
  import PlayJsonMarshalling._

  val settings: GraphQLSettings = GraphQLSettings(configuration)

  val routes: Routes = { case p"/graphql" => endpoint }

  private def blockingFuture[T](f: => T): Future[T] = Future(f)(blocking)

  val endpoint: EssentialAction = EssentialAction { request =>
    if (request.method != "POST")
      Accumulator.done(failure(Results.MethodNotAllowed, "method_not_allowed", "method_not_allowed", Code.BadInput)
        .withHeaders("Allow" -> "POST"))
    else
      Accumulator.flatten(blockingFuture(authorize(request)).map {
        case Left(result) => Accumulator.done(result)
        case Right(caller) =>
          if (!request.contentType.exists(_.equalsIgnoreCase("application/json")))
            Accumulator.done(failure(Results.UnsupportedMediaType, "unsupported_media_type", "unsupported_media_type",
              Code.BadInput))
          else
            parsers.byteString(settings.maxBodyBytes)(request).mapFuture {
              case Left(tooLarge) => Future.successful(tooLarge)
              case Right(body)    => handle(GraphQLContext(caller, request), body)
            }
      })
  }

  /** Step 2: the caller, or the 401 / 403 to answer. */
  private def authorize(request: RequestHeader): Either[Result, ApiAuth.Caller] =
    ApiAuth.caller(request) match {
      case None => Left(unauthenticated)
      case Some(caller) =>
        ApiAuth.scopeRefusal(caller.via, request, Endpoint) match {
          case Some(reason) =>
            val reference = Secured.logDenial(caller.user, request,
              Secured.Denial(resource = Some(s"api:$Endpoint"), reason = Some(reason)),
              caller.via.logFields :+ ("remote" -> Secured.logValue(request.remoteAddress)))
            Left(forbidden(reference))
          case None => Right(caller)
        }
    }

  /** Steps 4 to 6. */
  private def handle(ctx: GraphQLContext, body: ByteString): Future[Result] =
    parseBody(body) match {
      case Left(key) => Future.successful(failure(Results.BadRequest, "bad_request", key, Code.BadInput))
      case Right(GraphQLRequest(query, operationName, variables)) =>
        QueryParser.parse(query) match {
          case Failure(e: SyntaxError) =>
            val position = e.originalError.position
            Future.successful(errorsOnly(error("query_syntax", Code.BadInput, locations = Seq(position.line -> position.column))))
          case Failure(_) => Future.successful(errorsOnly(error("query_syntax", Code.BadInput)))
          case Success(document) =>
            val operations = document.definitions.collect { case op: ast.OperationDefinition => op }
            if (operations.sizeIs > 1) Future.successful(errorsOnly(error("one_operation_only", Code.BadInput)))
            else if (operations.isEmpty) Future.successful(errorsOnly(error("no_operation", Code.BadInput)))
            else if (operations.head.operationType != ast.OperationType.Query && isToken(ctx.caller)) {
              val reference = Secured.logDenial(ctx.user, ctx.request,
                Secured.Denial(resource = Some(s"api:$Endpoint"), reason = Some("token_query_only")),
                ctx.caller.via.logFields)
              Future.successful(forbidden(reference))
            } else
              QueryLimits.check(document, settings.limits) match {
                case Some(key) => Future.successful(errorsOnly(error(key, Code.BadInput)))
                case None      => run(ctx, document, operationName, variables)
              }
        }
    }

  /** Validation and the run, on the blocking pool, within the timeout. */
  private def run(ctx: GraphQLContext, document: ast.Document, operationName: Option[String],
                  variables: JsObject): Future[Result] = {
    val execution = blockingFuture {
      val violations = QueryValidator.default.validateQuery(GraphQLSchema.schema, document, Some(MaxViolations))
      if (violations.nonEmpty) Future.successful(errorsOnly(violations.map(violationError): _*))
      else
        Executor.execute(
          GraphQLSchema.schema,
          document,
          userContext = ctx,
          operationName = operationName,
          variables = variables: JsValue,
          queryValidator = QueryValidator.empty,
          exceptionHandler = exceptionHandler,
          queryReducers = List(QueryReducer.rejectComplexQueries[GraphQLContext](settings.maxComplexity,
            (_, _) => TooComplex))
        )(blocking, JsonResultMarshaller, JsonInputUnmarshaller, ExecutionScheme.Default)
          .map(json => Results.Ok(json))
          .recover {
            case e: QueryReducingError if e.cause == TooComplex => errorsOnly(error("query_too_complex", Code.BadInput))
            case e: WithViolations => errorsOnly(e.violations.map(violationError): _*)
            case _: QueryAnalysisError => errorsOnly(error("invalid_query", Code.BadInput))
            case e: Throwable =>
              val reference = Secured.newReference()
              logger.error(s"graphql_error ref=$reference", e)
              errorsOnly(error("internal_error", Code.InternalError, reference = Some(reference)))
          }
    }.flatten
    withTimeout(execution, settings.timeout).recover {
      case _: TimeoutException =>
        val reference = Secured.newReference()
        logger.warn(s"graphql_timeout ref=$reference timeout=${settings.timeout} ${Secured.accountFields(ctx.request, Some(ctx.user))}")
        errorsOnly(error("timeout", Code.InternalError, reference = Some(reference)))
    }
  }

  /** `future`, or a [[TimeoutException]] after `timeout`. The work itself goes on to its end on its
    * thread (JDBC can't be interrupted safely); the caller gets its answer.
    */
  def withTimeout[T](future: Future[T], timeout: FiniteDuration): Future[T] =
    Future.firstCompletedOf(Seq(
      future,
      org.apache.pekko.pattern.after(timeout, actorSystem.scheduler)(
        Future.failed(new TimeoutException("graphql request timed out")))
    ))
}

object GraphQLController {

  /** The endpoint's name in the denials' log lines (`resource=api:graphql`). */
  val Endpoint = "graphql"

  val Code: GraphQLError.Code.type = GraphQLError.Code

  /** At most this many validation errors are answered. */
  val MaxViolations = 10

  private val logger = Logger("graphql")

  /** The complexity budget's refusal. */
  private case object TooComplex extends Exception("query_too_complex", null, false, false)

  final case class GraphQLRequest(query: String, operationName: Option[String], variables: JsObject)

  /** The request body: one JSON object with a string `query`, an optional string `operationName` and
    * an optional object `variables` (null for none); else the 400's message key, `batch_not_supported`
    * for an array. Other members (a client's `extensions`) are ignored.
    */
  def parseBody(body: ByteString): Either[String, GraphQLRequest] =
    Try(Json.parse(body.toArrayUnsafe())).toOption match {
      case Some(_: JsArray) => Left("batch_not_supported")
      case Some(o: JsObject) =>
        val query = (o \ "query").toOption
        val operationName = (o \ "operationName").toOption
        val variables = (o \ "variables").toOption
        (query, operationName, variables) match {
          case (Some(JsString(q)), op, vars) if q.trim.nonEmpty =>
            val name = op match {
              case None | Some(JsNull) => Right(None)
              case Some(JsString(n))   => Right(Some(n))
              case _                   => Left("bad_request")
            }
            val vs = vars match {
              case None | Some(JsNull) => Right(JsObject.empty)
              case Some(v: JsObject)   => Right(v)
              case _                   => Left("bad_request")
            }
            for (n <- name; v <- vs) yield GraphQLRequest(q, n, v)
          case _ => Left("bad_request")
        }
      case _ => Left("bad_request")
    }

  private def isToken(caller: ApiAuth.Caller): Boolean = caller.via match {
    case _: ApiAuth.ByToken => true
    case ApiAuth.BySession  => false
  }

  /** One GraphQL error: the key, `extensions.code`, the reference, the locations (line, column). */
  def error(message: String, code: String, reference: Option[String] = None,
            locations: Seq[(Int, Int)] = Nil): JsObject =
    Json.obj("message" -> message, "extensions" -> (Json.obj("code" -> code) ++
      reference.fold(Json.obj())(r => Json.obj("reference" -> r)))) ++
      (if (locations.isEmpty) Json.obj()
       else Json.obj("locations" -> locations.map { case (line, column) => Json.obj("line" -> line, "column" -> column) }))

  /** A validation error: `invalid_query`, the rule broken (the violation's class, e.g.
    * `UndefinedField`) and its locations; never the violation's text, which repeats the query's.
    */
  def violationError(violation: Violation): JsObject = {
    val rule = violation.getClass.getSimpleName.stripSuffix("$").stripSuffix("Violation")
    val locations = violation match {
      case located: AstNodeLocation => located.locations.map(l => l.line -> l.column)
      case _                        => Nil
    }
    error("invalid_query", Code.BadInput, locations = locations) ++
      Json.obj("extensions" -> Json.obj("code" -> Code.BadInput, "rule" -> rule))
  }

  /** A 200 with only `errors` (the document was not run). */
  def errorsOnly(errors: JsObject*): Result = Results.Ok(Json.obj("errors" -> errors))

  /** An HTTP failure: the REST API's `error` field (and `reference`) with GraphQL's `errors`. */
  def failure(status: Results.Status, restError: String, message: String, code: String,
              reference: Option[String] = None): Result =
    status(Json.obj("error" -> restError) ++ reference.fold(Json.obj())(r => Json.obj("reference" -> r)) ++
      Json.obj("errors" -> Seq(error(message, code, reference))))

  /** No caller: as the REST API's 401 ([[ApiFailure.Unauthorized]]). */
  val unauthenticated: Result =
    failure(Results.Unauthorized, ApiFailure.Unauthorized.body.error, "unauthenticated", Code.Unauthenticated)
      .withHeaders("WWW-Authenticate" -> "Bearer")

  def forbidden(reference: String): Result =
    failure(Results.Forbidden, "forbidden", "forbidden", Code.Forbidden, Some(reference))

  /** A resolver's error: a [[GraphQLError]] as it is; anything else `internal_error`, logged with
    * its reference. Never the exception's text.
    */
  val exceptionHandler: ExceptionHandler = ExceptionHandler(
    onException = {
      case (m, e: GraphQLError) => handled(m, e.message, e.code, e.reference)
      case (m, e) =>
        val reference = Secured.newReference()
        logger.error(s"graphql_resolver_error ref=$reference", e)
        handled(m, "internal_error", Code.InternalError, Some(reference))
    },
    onViolation = { case (m, _) => handled(m, "invalid_query", Code.BadInput, None) },
    onUserFacingError = { case (m, _) => handled(m, "invalid_query", Code.BadInput, None) }
  )

  private def handled(m: ResultMarshaller, message: String, code: String, reference: Option[String]): HandledException =
    HandledException(message,
      Map("code" -> m.scalarNode(code, "String", Set.empty)) ++
        reference.map(r => "reference" -> m.scalarNode(r, "String", Set.empty)))
}
