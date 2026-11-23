// test/graphql/RoundResolverSpec.scala
package graphql

import db.scalikejdbc.{ContestJuryJdbc, Round, SharedTestDb, User}
import graphql.resolvers.RoundResolver
import munit.FunSuite
import play.api.libs.json._
import sangria.execution.Executor
import sangria.marshalling.playJson._
import sangria.parser.QueryParser
import sangria.schema._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class RoundResolverSpec extends FunSuite {

  override def beforeAll(): Unit = SharedTestDb.init()
  override def beforeEach(c: BeforeEach): Unit = SharedTestDb.truncateAll()

  private val schema = Schema(
    ObjectType("Query", fields[GraphQLContext, Unit](
      RoundResolver.roundField,
      RoundResolver.roundsField
    )),
    Some(ObjectType("Mutation", fields[GraphQLContext, Unit](
      RoundResolver.setActiveRoundField
    )))
  )

  private def exec(q: String, ctx: GraphQLContext = GraphQLContext(None)): JsValue =
    Await.result(
      Executor.execute(schema, QueryParser.parse(q).get, ctx,
        exceptionHandler = GraphQLContext.exceptionHandler),
      5.seconds)

  private def createContest() =
    ContestJuryJdbc.create(None, "WLM", 2024, "Ukraine", None, None, None, None)

  private def createRound(contestId: Long) =
    Round.create(Round(None, 1, Some("Round 1"), contestId, active = false))

  test("rounds returns empty list for contest with no rounds") {
    val c = createContest()
    val r = exec(s"""{ rounds(contestId: "${c.getId}") { id number name } }""")
    assertEquals((r \ "data" \ "rounds").as[JsArray], JsArray.empty)
  }

  test("round(id) returns round") {
    val c   = createContest()
    val rnd = createRound(c.getId)
    val r   = exec(s"""{ round(id: "${rnd.getId}") { number name active } }""")
    assertEquals((r \ "data" \ "round" \ "number").as[Int], 1)
    assertEquals((r \ "data" \ "round" \ "active").as[Boolean], false)
  }

  test("setActiveRound requires organizer role") {
    val c   = createContest()
    val rnd = createRound(c.getId)
    val r   = exec(s"""mutation { setActiveRound(id: "${rnd.getId}") { id active } }""")
    assertEquals((r \ "errors" \ 0 \ "extensions" \ "code").as[String], "UNAUTHENTICATED")
  }

  test("setActiveRound activates round") {
    val c   = createContest()
    val rnd = createRound(c.getId)
    val org = User.create(User("Org", "org@test.com", roles = Set("organizer"), contestId = c.id))
    val r   = exec(
      s"""mutation { setActiveRound(id: "${rnd.getId}") { id active } }""",
      GraphQLContext(Some(org))
    )
    assertEquals((r \ "data" \ "setActiveRound" \ "active").as[Boolean], true)
  }
}
