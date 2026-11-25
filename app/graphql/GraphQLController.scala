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
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/** The GraphQL endpoint's limits (`wlxjury.graphql` in `conf/application.conf`). */
final case class GraphQLSettings(
    maxBodyBytes: Long,
    limits: QueryLimits.Limits,
    maxComplexity: Double,
    timeout: FiniteDuration,
    maxInFlightPerCaller: Int
)

object GraphQLSettings {

  /** The release's values, as `conf/application.conf` sets them; a config without `wlxjury.graphql`
    * (the tests', `test/resources/application.conf`) gets these.
    */
  val Default: GraphQLSettings = GraphQLSettings(
    maxBodyBytes = 65536,
    limits = QueryLimits.Limits(maxDepth = 8, maxIntrospectionDepth = 15, maxAliases = 30, maxFields = 1000),
    maxComplexity = 5000,
    timeout = FiniteDuration(20, "seconds"),
    maxInFlightPerCaller = 2
  )

  def apply(configuration: Configuration): GraphQLSettings = {
    val c = configuration.getOptional[Configuration]("wlxjury.graphql").getOrElse(Configuration.empty)
    val d = Default
    GraphQLSettings(
      maxBodyBytes = c.getOptional[Long]("maxBodyBytes").getOrElse(d.maxBodyBytes),
      limits = QueryLimits.Limits(
        maxDepth = c.getOptional[Int]("maxDepth").getOrElse(d.limits.maxDepth),
        maxIntrospectionDepth = c.getOptional[Int]("maxIntrospectionDepth").getOrElse(d.limits.maxIntrospectionDepth),
        maxAliases = c.getOptional[Int]("maxAliases").getOrElse(d.limits.maxAliases),
        maxFields = c.getOptional[Int]("maxFields").getOrElse(d.limits.maxFields)
      ),
      maxComplexity = c.getOptional[Double]("maxComplexity").getOrElse(d.maxComplexity),
      timeout = c.getOptional[FiniteDuration]("timeout").getOrElse(d.timeout),
      maxInFlightPerCaller = c.getOptional[Int]("maxInFlightPerCaller").getOrElse(d.maxInFlightPerCaller)
    )
  }
}

/** `POST /api/graphql` (plan §4), inside the API's router ([[api.ApiRouter]]), so behind the same
  * filters as the REST API: the allowed hosts (a `Host` not of the deployment gets its 400 first)
  * and CSRF (every POST with the session cookie needs the `Csrf-Token` header; only a Bearer request
  * without the session cookie skips it, [[ApiAuth.bearerWithoutSession]]). Then, in this order:
  *
  *  1. the caller ([[ApiAuth.caller]], resolved by the router's guard, [[api.ApiRouter.guarded]], on
  *     the blocking pool, before anything else): a personal token or the session, the token deciding
  *     when both come (a cookie never adds to a token's rights); none, 401 ([[noCaller]]), whatever
  *     the method;
  *  2. any method but POST: 405, nothing read or run; a read-only, server-only token is refused as on
  *     the REST API's writes ([[ApiAuth.scopeRefusal]], `token_read_only`), logged;
  *  3. `Content-Type: application/json`, else 415; the body at most `maxBodyBytes`, else 413;
  *  4. one JSON object `{query, operationName?, variables?}`: an array (a batch) or anything else is
  *     400; a document with more than one operation is refused (no batching);
  *  5. a token caller's mutation or subscription: 403, logged on `security.authz` (tokens are
  *     queries only until a write scope exists, decision Q3);
  *  6. on the blocking pool, all within the timeout (a task that starts past it does nothing): the
  *     document's nesting before it is parsed, its shape ([[QueryLimits]]), Sangria's validation,
  *     then the run, with the complexity budget; the resolvers check the deadline too.
  *
  * Introspection needs a caller (step 1), so it is refused signed out (in production a POST with
  * neither the session cookie nor a Bearer token gets the CSRF filter's 403 first). Errors are GraphQL's
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
)(implicit ec: ExecutionContext, mat: Materializer) extends api.CallerRoutes {

  import GraphQLController._
  import PlayJsonMarshalling._

  val settings: GraphQLSettings = GraphQLSettings(configuration)

  /** Mounted by [[api.ApiRouter]] behind its guard: the handler runs only with a caller. */
  val callerRoutes: PartialFunction[RequestHeader, ApiAuth.Caller => EssentialAction] = {
    case p"/graphql" => endpoint
  }

  def noCaller: Result = unauthenticated

  /** The credentials couldn't be read (the database): a referenced server error, as GraphQL's. */
  def callerUnavailable(e: Throwable): Result = {
    val reference = Secured.newReference()
    logger.error(s"graphql_auth_error ref=$reference", e)
    failure(Results.InternalServerError, "server_error", "internal_error", Code.InternalError, Some(reference))
  }

  private def blockingFuture[T](f: => T): Future[T] = Future(f)(blocking)

  /** Steps 2 and 3, for a request whose caller the router's guard resolved (step 1). */
  def endpoint(caller: ApiAuth.Caller): EssentialAction = EssentialAction { request =>
    if (request.method != "POST")
      Accumulator.done(failure(Results.MethodNotAllowed, "method_not_allowed", "method_not_allowed", Code.BadInput)
        .withHeaders("Allow" -> "POST"))
    else
      scopeRefusal(caller, request) match {
        case Some(result) => Accumulator.done(result)
        case None =>
          if (!request.contentType.exists(_.equalsIgnoreCase("application/json")))
            Accumulator.done(failure(Results.UnsupportedMediaType, "unsupported_media_type", "unsupported_media_type",
              Code.BadInput))
          else
            parsers.byteString(settings.maxBodyBytes)(request).mapFuture {
              case Left(tooLarge) => Future.successful(tooLarge)
              case Right(body)    => handle(caller, request, body)
            }
      }
  }

  /** Step 2: the 403 of a token whose scope refuses the endpoint, logged; None for anyone else. */
  private def scopeRefusal(caller: ApiAuth.Caller, request: RequestHeader): Option[Result] =
    ApiAuth.scopeRefusal(caller.via, request, Endpoint).map { reason =>
      val reference = Secured.logDenial(caller.user, request,
        Secured.Denial(resource = Some(s"api:$Endpoint"), reason = Some(reason)),
        caller.via.logFields :+ ("remote" -> Secured.logValue(request.remoteAddress)))
      forbidden(reference)
    }

  /** At most `maxInFlightPerCaller` runs per account at once ([[InFlightLimiter]]): the blocking
    * pool is shared with the galleries and the statistics, so one caller can't fill it.
    */
  val inFlight = new InFlightLimiter(settings.maxInFlightPerCaller)

  /** Steps 4 to 6: the body read here; the rest on the blocking pool, within the timeout. */
  private def handle(caller: ApiAuth.Caller, request: RequestHeader, body: ByteString): Future[Result] =
    parseBody(body) match {
      case Left(key) => Future.successful(failure(Results.BadRequest, "bad_request", key, Code.BadInput))
      case Right(graphQLRequest) =>
        val account = caller.user.getId
        if (!inFlight.tryAcquire(account))
          Future.successful(failure(Results.TooManyRequests, "too_many_requests", "too_many_requests", Code.TooManyRequests))
        else {
          val ctx = GraphQLContext(caller, request, settings.timeout.fromNow)
          val work = blockingFuture {
            // a task that waited for a thread past the deadline: its caller has had the timeout
            if (ctx.deadline.isOverdue()) Future.successful(timedOut(ctx)) else process(ctx, graphQLRequest)
          }.flatten.recover {
            case NonFatal(e) => internalError(e)
          }
          // the slot is freed when the run ends, not when its caller gets the timeout
          work.onComplete(_ => inFlight.release(account))
          withTimeout(work, settings.timeout).recover { case _: TimeoutException => timedOut(ctx) }
        }
    }

  /** The document: its nesting before parsing, the parse, one operation, a token's mutation refused,
    * the limits, Sangria's validation, then the run with the complexity budget.
    */
  private def process(ctx: GraphQLContext, graphQLRequest: GraphQLRequest): Future[Result] = {
    val GraphQLRequest(query, operationName, variables) = graphQLRequest
    val checked: Either[Result, ast.Document] =
      try {
        if (QueryLimits.nestingExceeds(query, QueryLimits.MaxNesting)) Left(errorsOnly(error("query_too_deep", Code.BadInput)))
        else
          QueryParser.parse(query) match {
            case Failure(e: SyntaxError) =>
              val position = e.originalError.position
              Left(errorsOnly(error("query_syntax", Code.BadInput, locations = Seq(position.line -> position.column))))
            case Failure(_) => Left(errorsOnly(error("query_syntax", Code.BadInput)))
            case Success(document) =>
              val operations = document.definitions.collect { case op: ast.OperationDefinition => op }
              if (operations.sizeIs > 1) Left(errorsOnly(error("one_operation_only", Code.BadInput)))
              else if (operations.isEmpty) Left(errorsOnly(error("no_operation", Code.BadInput)))
              else if (operations.head.operationType != ast.OperationType.Query && isToken(ctx.caller)) {
                val reference = Secured.logDenial(ctx.user, ctx.request,
                  Secured.Denial(resource = Some(s"api:$Endpoint"), reason = Some("token_query_only")),
                  ctx.caller.via.logFields)
                Left(forbidden(reference))
              } else
                QueryLimits.check(document, settings.limits) match {
                  case Some(key) => Left(errorsOnly(error(key, Code.BadInput)))
                  case None =>
                    val violations = QueryValidator.default.validateQuery(GraphQLSchema.schema, document, Some(MaxViolations))
                    if (violations.nonEmpty) Left(errorsOnly(violations.map(violationError): _*)) else Right(document)
                }
          }
      } catch {
        // the nesting check keeps documents shallow; this is the second line, so that a deep one never
        // takes the JVM down (Pekko exits on a fatal error that reaches it)
        case _: StackOverflowError => Left(errorsOnly(error("query_too_deep", Code.BadInput)))
      }
    checked match {
      case Left(result) => Future.successful(result)
      case Right(document) =>
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
          .map { json =>
            ctx.logRepeatedDenials()
            Results.Ok(json)
          }
          .recover {
            case e: QueryReducingError if e.cause == TooComplex => errorsOnly(error("query_too_complex", Code.BadInput))
            case e: WithViolations                              => errorsOnly(e.violations.take(MaxViolations).map(violationError): _*)
            case _: QueryAnalysisError                          => errorsOnly(error("invalid_query", Code.BadInput))
          }
    }
  }

  private def timedOut(ctx: GraphQLContext): Result = {
    val reference = Secured.newReference()
    logger.warn(s"graphql_timeout ref=$reference timeout=${settings.timeout} ${Secured.accountFields(ctx.request, Some(ctx.user))}")
    errorsOnly(error("timeout", Code.InternalError, reference = Some(reference)))
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

  /** A server error: `internal_error`, logged with its reference, never the exception's text. */
  def internalError(e: Throwable): Result = {
    val reference = Secured.newReference()
    logger.error(s"graphql_error ref=$reference", e)
    errorsOnly(error("internal_error", Code.InternalError, reference = Some(reference)))
  }

  /** A resolver's error: a [[GraphQLError]] as it is; a violation (a variable or an argument that
    * doesn't fit its type) `invalid_query`; anything else, Sangria's execution errors included (a
    * null for a non-null field is a server bug), `internal_error`, logged with its reference. Never
    * the exception's text.
    */
  val exceptionHandler: ExceptionHandler = ExceptionHandler(
    onException = {
      case (m, e: GraphQLError) => handled(m, e.message, e.code, e.reference, e.reason)
      case (m, e)               => internal(m, e)
    },
    onViolation = { case (m, _) => handled(m, "invalid_query", Code.BadInput, None) },
    onUserFacingError = {
      case (m, _: WithViolations) => handled(m, "invalid_query", Code.BadInput, None)
      case (m, e: Throwable)      => internal(m, e)
      case (m, e)                 => internal(m, new IllegalStateException(e.getClass.getName))
    }
  )

  private def internal(m: ResultMarshaller, e: Throwable): HandledException = {
    val reference = Secured.newReference()
    logger.error(s"graphql_resolver_error ref=$reference", e)
    handled(m, "internal_error", Code.InternalError, Some(reference))
  }

  /** One error: the key, `extensions.code`, the reference, and a round state's `reason`. */
  private def handled(m: ResultMarshaller, message: String, code: String, reference: Option[String],
      reason: Option[String] = None): HandledException =
    HandledException(message,
      Map("code" -> m.scalarNode(code, "String", Set.empty)) ++
        reference.map(r => "reference" -> m.scalarNode(r, "String", Set.empty)) ++
        reason.map(r => "reason" -> m.scalarNode(r, "String", Set.empty)))
}

/** How many GraphQL runs each account has at once, at most `max` (`wlxjury.graphql.maxInFlightPerCaller`):
  * a token and a session of the same account count together. A run holds its slot until it ends,
  * past its caller's timeout too.
  */
final class InFlightLimiter(max: Int) {
  private val running = new java.util.concurrent.ConcurrentHashMap[Long, Integer]()

  /** Takes a slot for the account, if it has one free. */
  def tryAcquire(account: Long): Boolean = {
    var taken = false
    running.compute(account, (_, n) => {
      val now = if (n == null) 0 else n.intValue
      if (now < max) { taken = true; Integer.valueOf(now + 1) } else n
    })
    taken
  }

  def release(account: Long): Unit =
    running.computeIfPresent(account, (_, n) => if (n.intValue <= 1) null else Integer.valueOf(n.intValue - 1))

  /** The account's runs now (for tests). */
  def inFlight(account: Long): Int = Option(running.get(account)).fold(0)(_.intValue)
}
