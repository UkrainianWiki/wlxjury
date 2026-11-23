// test/graphql/UserResolverSpec.scala
package graphql

import db.scalikejdbc.{ContestJuryJdbc, SharedTestDb, User}
import graphql.resolvers.UserResolver
import munit.FunSuite
import play.api.libs.json._
import sangria.execution.Executor
import sangria.marshalling.playJson._
import sangria.parser.QueryParser
import sangria.schema._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class UserResolverSpec extends FunSuite {

  override def beforeAll(): Unit = SharedTestDb.init()
  override def beforeEach(c: BeforeEach): Unit = SharedTestDb.truncateAll()

  private val schema = Schema(
    ObjectType("Query", fields[GraphQLContext, Unit](
      UserResolver.usersField,
      UserResolver.userField,
      UserResolver.meField
    )),
    Some(ObjectType("Mutation", fields[GraphQLContext, Unit](
      UserResolver.createUserField,
      UserResolver.updateUserField
    )))
  )

  private def exec(q: String, ctx: GraphQLContext = GraphQLContext(None)): JsValue =
    Await.result(
      Executor.execute(schema, QueryParser.parse(q).get, ctx,
        exceptionHandler = GraphQLContext.exceptionHandler),
      5.seconds)

  test("me returns null when unauthenticated") {
    val r = exec("{ me { id email } }")
    assertEquals((r \ "data" \ "me").get, JsNull)
  }

  test("me returns current user") {
    val u = User.create(User("Alice", "alice@test.com", roles = Set("jury")))
    val r = exec("{ me { fullname email } }", GraphQLContext(Some(u)))
    assertEquals((r \ "data" \ "me" \ "fullname").as[String], "Alice")
    assertEquals((r \ "data" \ "me" \ "email").as[String], "alice@test.com")
  }

  test("users returns empty list") {
    val c = ContestJuryJdbc.create(None, "WLM", 2024, "Ukraine", None, None, None, None)
    val r = exec(s"""{ users(contestId: "${c.getId}") { id fullname } }""")
    assertEquals((r \ "data" \ "users").as[JsArray], JsArray.empty)
  }

  test("createUser raises UNAUTHENTICATED") {
    val r = exec("""mutation { createUser(input: { fullname: "Bob", email: "b@b.com", roles: ["jury"] }) { id } }""")
    assertEquals((r \ "errors" \ 0 \ "extensions" \ "code").as[String], "UNAUTHENTICATED")
  }

  test("createUser creates user for admin") {
    val c     = ContestJuryJdbc.create(None, "WLM", 2024, "Ukraine", None, None, None, None)
    val admin = User.create(User("Admin", "admin@test.com", roles = Set("admin"), contestId = c.id))
    val r     = exec(
      """mutation { createUser(input: { fullname: "Juror", email: "j@j.com", roles: ["jury"] }) { fullname email roles } }""",
      GraphQLContext(Some(admin))
    )
    assertEquals((r \ "data" \ "createUser" \ "fullname").as[String], "Juror")
    assert((r \ "data" \ "createUser" \ "roles").as[Seq[String]].contains("jury"))
  }
}
