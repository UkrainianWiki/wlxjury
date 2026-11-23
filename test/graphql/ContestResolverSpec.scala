// test/graphql/ContestResolverSpec.scala
package graphql

import db.scalikejdbc.{ContestJuryJdbc, SharedTestDb, User}
import graphql.resolvers.ContestResolver
import munit.FunSuite
import play.api.libs.json._
import sangria.execution.Executor
import sangria.marshalling.playJson._
import sangria.parser.QueryParser
import sangria.schema._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class ContestResolverSpec extends FunSuite {

  override def beforeAll(): Unit = SharedTestDb.init()
  override def beforeEach(c: BeforeEach): Unit = SharedTestDb.truncateAll()

  private val schema = Schema(
    ObjectType("Query", fields[GraphQLContext, Unit](
      ContestResolver.contestsField,
      ContestResolver.contestField
    )),
    Some(ObjectType("Mutation", fields[GraphQLContext, Unit](
      ContestResolver.createContestField,
      ContestResolver.updateContestField,
      ContestResolver.deleteContestField
    )))
  )

  private def exec(q: String, ctx: GraphQLContext = GraphQLContext(None)): JsValue =
    Await.result(
      Executor.execute(schema, QueryParser.parse(q).get, ctx,
        exceptionHandler = GraphQLContext.exceptionHandler),
      5.seconds)

  test("contests returns empty list") {
    val r = exec("{ contests { id name year country } }")
    assertEquals((r \ "data" \ "contests").as[JsArray], JsArray.empty)
  }

  test("contests returns created contest") {
    ContestJuryJdbc.create(None, "WLM", 2024, "Ukraine", Some("Category:Foo"), None, None, None)
    val r = exec("{ contests { name year country } }")
    val arr = (r \ "data" \ "contests").as[JsArray]
    assertEquals(arr.value.size, 1)
    assertEquals((arr.value.head \ "name").as[String], "WLM")
    assertEquals((arr.value.head \ "year").as[Int], 2024)
  }

  test("contest(id) returns None for missing id") {
    val r = exec("{ contest(id: \"999\") { id name } }")
    assertEquals((r \ "data" \ "contest").get, JsNull)
  }

  test("createContest raises UNAUTHENTICATED without user") {
    val r = exec("""mutation { createContest(input: { name: "WLM", year: 2024, country: "Ukraine" }) { id } }""")
    assert((r \ "errors").isDefined)
    assertEquals((r \ "errors" \ 0 \ "extensions" \ "code").as[String], "UNAUTHENTICATED")
  }

  test("createContest creates contest for root user") {
    val root = User.create(User("Root", "root@test.com", roles = Set("root")))
    val r = exec(
      """mutation { createContest(input: { name: "WLE", year: 2024, country: "Poland" }) { name year country } }""",
      GraphQLContext(Some(root))
    )
    assertEquals((r \ "data" \ "createContest" \ "name").as[String], "WLE")
    assertEquals((r \ "data" \ "createContest" \ "year").as[Int], 2024)
  }

  test("deleteContest returns true") {
    val c = ContestJuryJdbc.create(None, "WLM", 2024, "Ukraine", None, None, None, None)
    val root = User.create(User("Root", "root@test.com", roles = Set("root")))
    val r = exec(
      s"""mutation { deleteContest(id: "${c.getId}") }""",
      GraphQLContext(Some(root))
    )
    assertEquals((r \ "data" \ "deleteContest").as[Boolean], true)
    assertEquals(ContestJuryJdbc.findById(c.getId), None)
  }
}
