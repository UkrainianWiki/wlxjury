// test/graphql/CommentResolverSpec.scala
package graphql

import db.scalikejdbc.{ContestJuryJdbc, Round, SharedTestDb, User}
import graphql.resolvers.CommentResolver
import munit.FunSuite
import play.api.libs.json._
import sangria.execution.Executor
import sangria.marshalling.playJson._
import sangria.parser.QueryParser
import sangria.schema._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class CommentResolverSpec extends FunSuite {
  override def beforeAll(): Unit = SharedTestDb.init()
  override def beforeEach(c: BeforeEach): Unit = SharedTestDb.truncateAll()

  private val schema = Schema(
    ObjectType("Query", fields[GraphQLContext, Unit](CommentResolver.commentsField)),
    Some(ObjectType("Mutation", fields[GraphQLContext, Unit](CommentResolver.addCommentField)))
  )

  private def exec(q: String, ctx: GraphQLContext = GraphQLContext(None)): JsValue =
    Await.result(Executor.execute(schema, QueryParser.parse(q).get, ctx,
      exceptionHandler = GraphQLContext.exceptionHandler), 5.seconds)

  test("comments returns empty list") {
    val c   = ContestJuryJdbc.create(None, "WLM", 2024, "Ukraine", None, None, None, None)
    val rnd = Round.create(Round(None, 1, None, c.getId, active = true))
    val r   = exec(s"""{ comments(roundId: "${rnd.getId}") { id body } }""")
    assertEquals((r \ "data" \ "comments").as[JsArray], JsArray.empty)
  }

  test("addComment requires authentication") {
    val r = exec("""mutation { addComment(roundId: "1", imagePageId: "1", body: "hi") { id } }""")
    assertEquals((r \ "errors" \ 0 \ "extensions" \ "code").as[String], "UNAUTHENTICATED")
  }

  test("addComment creates comment") {
    val c    = ContestJuryJdbc.create(None, "WLM", 2024, "Ukraine", None, None, None, None)
    val rnd  = Round.create(Round(None, 1, None, c.getId, active = true))
    val user = User.create(User("Alice", "a@test.com", roles = Set("jury"), contestId = c.id))
    val r    = exec(
      s"""mutation { addComment(roundId: "${rnd.getId}", imagePageId: "42", body: "Nice photo!") { body imagePageId username } }""",
      GraphQLContext(Some(user))
    )
    assertEquals((r \ "data" \ "addComment" \ "body").as[String], "Nice photo!")
    assertEquals((r \ "data" \ "addComment" \ "imagePageId").as[String], "42")
  }
}
