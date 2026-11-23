// test/graphql/ImageResolverSpec.scala
package graphql

import db.scalikejdbc.{ContestJuryJdbc, ImageJdbc, Round, SharedTestDb, User}
import graphql.resolvers.ImageResolver
import munit.FunSuite
import org.intracer.wmua.Image
import play.api.libs.json._
import sangria.execution.Executor
import sangria.marshalling.playJson._
import sangria.parser.QueryParser
import sangria.schema._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class ImageResolverSpec extends FunSuite {

  override def beforeAll(): Unit = SharedTestDb.init()
  override def beforeEach(c: BeforeEach): Unit = SharedTestDb.truncateAll()

  private val schema = Schema(
    ObjectType("Query", fields[GraphQLContext, Unit](
      ImageResolver.imagesField,
      ImageResolver.imageField
    )),
    Some(ObjectType("Mutation", fields[GraphQLContext, Unit](
      ImageResolver.rateImageField
    )))
  )

  private def exec(q: String, ctx: GraphQLContext = GraphQLContext(None)): JsValue =
    Await.result(
      Executor.execute(schema, QueryParser.parse(q).get, ctx,
        exceptionHandler = GraphQLContext.exceptionHandler),
      5.seconds)

  test("images returns empty list for round with no images") {
    val c   = ContestJuryJdbc.create(None, "WLM", 2024, "Ukraine", None, None, None, None)
    val rnd = Round.create(Round(None, 1, None, c.getId, active = true))
    val r   = exec(s"""{ images(roundId: "${rnd.getId}") { image { pageId title } } }""")
    assertEquals((r \ "data" \ "images").as[JsArray], JsArray.empty)
  }

  test("image(pageId) returns None for missing image") {
    val r = exec("{ image(pageId: \"999\") { pageId title } }")
    assertEquals((r \ "data" \ "image").get, JsNull)
  }

  test("rateImage requires authentication") {
    val r = exec("""mutation { rateImage(roundId: "1", pageId: "1", rate: 1) { rate } }""")
    assertEquals((r \ "errors" \ 0 \ "extensions" \ "code").as[String], "UNAUTHENTICATED")
  }

  test("rateImage records a selection") {
    val c    = ContestJuryJdbc.create(None, "WLM", 2024, "Ukraine", None, None, None, None)
    val rnd  = Round.create(Round(None, 1, None, c.getId, active = true))
    val img  = Image(pageId = 42L, title = "File:Test.jpg")
    ImageJdbc.batchInsert(Seq(img))
    val jury = User.create(User("Juror", "j@test.com", roles = Set("jury"), contestId = c.id))
    val r    = exec(
      s"""mutation { rateImage(roundId: "${rnd.getId}", pageId: "42", rate: 1) { rate pageId roundId } }""",
      GraphQLContext(Some(jury))
    )
    assertEquals((r \ "data" \ "rateImage" \ "rate").as[Int], 1)
    assertEquals((r \ "data" \ "rateImage" \ "pageId").as[String], "42")
  }
}
