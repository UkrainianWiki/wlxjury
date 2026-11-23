// test/graphql/AuthSpec.scala
package graphql

import db.scalikejdbc.{ContestJuryJdbc, SharedTestDb, User}
import graphql.resolvers.AuthResolver
import munit.FunSuite
import pdi.jwt.{JwtAlgorithm, JwtJson}
import play.api.libs.json._
import sangria.execution.Executor
import sangria.marshalling.playJson._
import sangria.parser.QueryParser
import sangria.schema._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class AuthSpec extends FunSuite {
  override def beforeAll(): Unit = SharedTestDb.init()
  override def beforeEach(c: BeforeEach): Unit = SharedTestDb.truncateAll()

  private val jwtSecret = "test-secret"

  private val schema = Schema(
    ObjectType("Query", fields[GraphQLContext, Unit](
      Field("_dummy", StringType, resolve = _ => "ok")
    )),
    Some(ObjectType("Mutation", fields[GraphQLContext, Unit](
      AuthResolver.loginField(jwtSecret)
    )))
  )

  private def exec(q: String): JsValue =
    Await.result(Executor.execute(schema, QueryParser.parse(q).get, GraphQLContext(None),
      exceptionHandler = GraphQLContext.exceptionHandler), 5.seconds)

  test("login fails for unknown email") {
    val r = exec("""mutation { login(email: "nobody@test.com", password: "x") { token } }""")
    assertEquals((r \ "errors" \ 0 \ "extensions" \ "code").as[String], "UNAUTHENTICATED")
  }

  test("login fails for wrong password") {
    val c = ContestJuryJdbc.create(None, "WLM", 2024, "Ukraine", None, None, None, None)
    User.create(User("Alice", "a@test.com", roles = Set("jury"), contestId = c.id,
      password = Some(User.sha1("correct"))))
    val r = exec("""mutation { login(email: "a@test.com", password: "wrong") { token } }""")
    assertEquals((r \ "errors" \ 0 \ "extensions" \ "code").as[String], "UNAUTHENTICATED")
  }

  test("login returns JWT token and user for valid credentials") {
    val c    = ContestJuryJdbc.create(None, "WLM", 2024, "Ukraine", None, None, None, None)
    val user = User.create(User("Alice", "a@test.com", roles = Set("jury"), contestId = c.id,
      password = Some(User.sha1("secret"))))
    val r = exec("""mutation { login(email: "a@test.com", password: "secret") { token user { id } } }""")
    val token = (r \ "data" \ "login" \ "token").as[String]
    assert(token.nonEmpty)
    val decoded = JwtJson.decodeJson(token, jwtSecret, Seq(JwtAlgorithm.HS256))
    assert(decoded.isSuccess)
    val userId = (decoded.get \ "userId").as[Long]
    assertEquals(userId, user.getId)
  }
}
