// test/graphql/MonumentResolverSpec.scala
package graphql

import db.scalikejdbc.{ContestJuryJdbc, SharedTestDb}
import graphql.resolvers.MonumentResolver
import munit.FunSuite
import play.api.libs.json._
import sangria.execution.Executor
import sangria.marshalling.playJson._
import sangria.parser.QueryParser
import sangria.schema._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class MonumentResolverSpec extends FunSuite {
  override def beforeAll(): Unit = SharedTestDb.init()
  override def beforeEach(c: BeforeEach): Unit = SharedTestDb.truncateAll()

  private val schema = Schema(ObjectType("Query", fields[GraphQLContext, Unit](
    MonumentResolver.monumentsField,
    MonumentResolver.monumentField
  )))

  private def exec(q: String): JsValue =
    Await.result(Executor.execute(schema, QueryParser.parse(q).get, GraphQLContext(None),
      exceptionHandler = GraphQLContext.exceptionHandler), 5.seconds)

  test("monuments returns empty list") {
    val c = ContestJuryJdbc.create(None, "WLM", 2024, "Ukraine", None, None, None, None)
    val r = exec(s"""{ monuments(contestId: "${c.getId}") { id name } }""")
    assertEquals((r \ "data" \ "monuments").as[JsArray], JsArray.empty)
  }

  test("monument(id) returns None for missing id") {
    val r = exec("""{ monument(id: "nonexistent") { id name } }""")
    assertEquals((r \ "data" \ "monument").get, JsNull)
  }
}
