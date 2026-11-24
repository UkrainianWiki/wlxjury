package graphql

import db.scalikejdbc.{User, UserContest}
import org.apache.pekko.util.ByteString
import org.specs2.mutable.Specification
import play.api.libs.json.{JsObject, Json}
import sangria.parser.QueryParser

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}

/** The GraphQL schema and the endpoint's checks that need no database: the checked-in SDL is the
  * served schema's, the document limits, the email rule, the body's shape and the error bodies.
  */
class GraphQLSchemaSpec extends Specification {

  private val limits = QueryLimits.Limits(maxDepth = 8, maxIntrospectionDepth = 15, maxAliases = 30, maxFields = 1000)

  private def check(query: String): Option[String] = QueryLimits.check(QueryParser.parse(query).get, limits)

  "the checked-in SDL" should {
    "be the served schema's (regenerate it with `Test/runMain graphql.RenderSchema`)" in {
      val file = new String(Files.readAllBytes(Paths.get(GraphQLSchema.SdlPath)), UTF_8).replace("\r\n", "\n")
      file === GraphQLSchema.sdl
    }

    "have queries only: no mutation and no subscription type" in {
      GraphQLSchema.schema.mutation must beNone
      GraphQLSchema.schema.subscription must beNone
    }
  }

  "the document limits" should {
    "let the standard introspection query and the slice's queries through" in {
      check(RenderSchema.IntrospectionQuery) must beNone
      check("{ me { id email } contests(limit: 200) { totalCount items { id name rounds { id title } } } }") must beNone
    }

    "refuse a query nested deeper than maxDepth, before validation (the fields need not exist)" in {
      check("{ a { b { c { d { e { f { g { h } } } } } } } }") must beNone
      check("{ a { b { c { d { e { f { g { h { i } } } } } } } } }") === Some("query_too_deep")
    }

    "count fragments where they are spread" in {
      val nested = "fragment F on X { d { e { f { g { h { i } } } } } } { a { b { c { ...F } } } }"
      check(nested) === Some("query_too_deep")
      check("fragment F on X { ...F } { a { ...F } }") must beNone // a cycle: validation's to refuse
    }

    "refuse more than maxAliases aliases" in {
      check((1 to 30).map(i => s"a$i: me { id }").mkString("{ ", " ", " }")) must beNone
      check((1 to 31).map(i => s"a$i: me { id }").mkString("{ ", " ", " }")) === Some("too_many_aliases")
    }

    "refuse more than maxFields fields, a fragment bomb stopping early" in {
      val spreads = (0 until 12).map(i => s"fragment F$i on X { ...F${i + 1} ...F${i + 1} x }").mkString(" ") +
        " fragment F12 on X { y } { ...F0 }"
      check(spreads) === Some("query_too_large")
      // spreads alone, no field anywhere, the last one unknown or back to the first: still stopped
      forall(Seq("Nope", "F0")) { last =>
        val bare = (0 until 40).map(i => s"fragment F$i on Query { ...F${i + 1} ...F${i + 1} }").mkString(" ") +
          s" fragment F40 on Query { ...$last } query { ...F0 }"
        check(bare) === Some("query_too_large")
      }
    }

    "account for every definition: no unused or duplicate fragment, nothing but operations and fragments" in {
      val unusedBomb = (0 until 40).map(i => s"fragment F$i on Query { ...F${i + 1} ...F${i + 1} }").mkString(" ") +
        " fragment F40 on Query { me { id } } query { me { id } }"
      check(unusedBomb) === Some("unused_fragment")
      check("fragment F on Query { me { id } } query { me { id } }") === Some("unused_fragment")
      check("fragment F on Query { me { id } } fragment F on Query { me { isRoot } } query { ...F }") ===
        Some("duplicate_fragment")
      check("type T { a: Int } query { me { id } }") === Some("non_executable_definition")
      check("fragment F on Query { me { id } } query { ...F }") must beNone
    }

    "refuse brackets nested deeper than MaxNesting before parsing, outside strings and comments" in {
      QueryLimits.nestingExceeds("{a" * 30000, QueryLimits.MaxNesting) must beTrue
      QueryLimits.nestingExceeds("{ contest(id: " + "[" * 30000 + ") { id } }", QueryLimits.MaxNesting) must beTrue
      QueryLimits.nestingExceeds("{ contest(id: \"" + "{" * 100 + "\") { id } }", QueryLimits.MaxNesting) must beFalse
      QueryLimits.nestingExceeds("# " + "{" * 100 + "\n{ me { id } }", QueryLimits.MaxNesting) must beFalse
      QueryLimits.nestingExceeds("{ a(s: \"\"\"" + "{" * 100 + "\"\"\") }", QueryLimits.MaxNesting) must beFalse
      // an escaped triple quote does not end a block string, so what follows the real end is counted
      val escaped = "{ a(s: \"\"\"\\\"\"\" \"\"\") " + "{" * 100 + " }"
      QueryLimits.nestingExceeds(escaped, QueryLimits.MaxNesting) must beTrue
      QueryLimits.nestingExceeds(RenderSchema.IntrospectionQuery, QueryLimits.MaxNesting) must beFalse
    }

    "refuse introspection that nests its type lists more than twice, or deeper than maxIntrospectionDepth" in {
      check("{ __type(name: \"Query\") { fields { type { fields { name } } } } }") must beNone
      val nested = "{ __type(name: \"Query\") { fields { type { fields { type { fields { name } } } } } } }"
      check(nested) === Some("introspection_too_deep")
      val ofTypes = (1 to 15).foldLeft("name")((inner, _) => s"ofType { $inner }")
      check(s"{ __schema { types { $ofTypes } } }") === Some("introspection_too_deep")
    }
  }

  "the endpoint's settings" should {
    "be the release config's (conf/application.conf), the defaults a config without them gets" in {
      GraphQLSettings(play.api.Configuration(security.ProductionFilters.release)) === GraphQLSettings.Default
      GraphQLSettings(play.api.Configuration.empty) === GraphQLSettings.Default
    }
  }

  "the per-account limit of runs" should {
    "give each account at most max slots, freed as runs end" in {
      val limiter = new InFlightLimiter(2)
      limiter.tryAcquire(1) must beTrue
      limiter.tryAcquire(1) must beTrue
      limiter.tryAcquire(1) must beFalse
      limiter.tryAcquire(2) must beTrue // another account
      limiter.release(1)
      limiter.inFlight(1) === 1
      limiter.tryAcquire(1) must beTrue
      limiter.release(1)
      limiter.release(1)
      limiter.inFlight(1) === 0
      limiter.release(1) // more releases than slots: no negative count
      limiter.inFlight(1) === 0
    }
  }

  "the deadline" should {
    "stop a resolver once the answer has timed out" in {
      val caller = api.ApiAuth.Caller(User("u", "u@example.com", Some(1L)), api.ApiAuth.BySession)
      val request = play.api.test.FakeRequest()
      GraphQLContext(caller, request, scala.concurrent.duration.Deadline.now + scala.concurrent.duration.Duration(1, "minute"))
        .checkDeadline() must not(throwA[Throwable])
      GraphQLContext(caller, request, scala.concurrent.duration.Deadline.now - scala.concurrent.duration.Duration(1, "second"))
        .checkDeadline() must throwA[GraphQLError]
    }
  }

  "contests' page size" should {
    "be clamped to 1..200" in {
      GraphQLSchema.pageSize(1000) === 200
      GraphQLSchema.pageSize(0) === 1
      GraphQLSchema.pageSize(-5) === 1
      GraphQLSchema.pageSize(50) === 50
    }
  }

  "the email rule" should {
    def member(contestId: Long, role: String, retired: Boolean = false) =
      UserContest(1L, contestId, role, contestRetired = retired)
    def viewer(memberships: UserContest*) = User("v", "v@example.com", Some(1L), userContests = memberships)

    "show it to root and to an Admin of a contest the account is a member of" in {
      GraphQLSchema.emailVisible(User("r", "r@example.com", Some(1L), roles = Set(User.ROOT_ROLE)), Nil) must beTrue
      GraphQLSchema.emailVisible(viewer(member(10, User.ADMIN_ROLE)), Seq(10L)) must beTrue
      GraphQLSchema.emailVisible(viewer(member(10, User.ADMIN_ROLE), member(20, User.JURY_ROLE)), Seq(20L, 10L)) must beTrue
    }

    "hide it from an Admin of another contest, an organizer, a juror, and an Admin of a retired contest" in {
      GraphQLSchema.emailVisible(viewer(member(10, User.ADMIN_ROLE)), Seq(20L)) must beFalse
      GraphQLSchema.emailVisible(viewer(member(10, "organizer")), Seq(10L)) must beFalse
      GraphQLSchema.emailVisible(viewer(member(10, User.JURY_ROLE)), Seq(10L)) must beFalse
      GraphQLSchema.emailVisible(viewer(member(10, User.ADMIN_ROLE, retired = true)), Seq(10L)) must beFalse
      GraphQLSchema.emailVisible(viewer(), Seq(10L)) must beFalse
    }
  }

  "the request body" should {
    def parse(s: String) = GraphQLController.parseBody(ByteString(s))

    "be one object with a string query, an optional operationName and variables" in {
      parse("""{"query": "{ me { id } }"}""") must beRight
      parse("""{"query": "{ me { id } }", "operationName": null, "variables": null, "extensions": {}}""") must beRight
      parse("""{"query": "query Q($id: ID!) { contest(id: $id) { id } }", "operationName": "Q", "variables": {"id": "1"}}""")
        .map(_.variables) === Right(Json.obj("id" -> "1"))
    }

    "refuse a batch (an array), and anything else" in {
      parse("""[{"query": "{ me { id } }"}]""") === Left("batch_not_supported")
      forall(Seq("", "null", "\"{ me { id } }\"", """{"query": 1}""", """{"query": " "}""",
        """{"query": "{ me { id } }", "variables": [1]}""", """{"query": "{ me { id } }", "operationName": 1}""",
        "{not json")) { body =>
        parse(body) === Left("bad_request")
      }
    }
  }

  "the error bodies" should {
    "carry a message key, the code and a reference, never an exception's or the query's text" in {
      val failing = new RuntimeException("SELECT secret FROM users")
      val handled = GraphQLController.exceptionHandler.onException((PlayJsonMarshalling.JsonResultMarshaller, failing))
      handled match {
        case sangria.execution.SingleHandledException(message, fields, _, _, _) =>
          message === "internal_error"
          fields.keySet === Set("code", "reference")
          fields("code") === Json.toJson("INTERNAL_ERROR")
        case other => ko(s"unexpected $other")
      }
    }

    "name a validation error's rule and locations, not its text" in {
      val document = QueryParser.parse("{ secretField }").get
      val violations = sangria.validation.QueryValidator.default.validateQuery(GraphQLSchema.schema, document, None)
      violations must not(beEmpty)
      val body: JsObject = GraphQLController.violationError(violations.head)
      (body \ "message").as[String] === "invalid_query"
      (body \ "extensions" \ "code").as[String] === "BAD_INPUT"
      (body \ "extensions" \ "rule").as[String] === "UndefinedField"
      body.toString must not(contain("secretField"))
    }
  }
}
