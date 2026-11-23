// test/graphql/GraphQLRouteSpec.scala
package graphql

import com.github.pjfanning.pekkohttpplayjson.PlayJsonSupport._
import db.scalikejdbc.{ContestJuryJdbc, SharedTestDb, User}
import graphql.SubscriptionEventBus
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.testkit.Specs2RouteTest
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll
import play.api.Configuration
import play.api.libs.json._

class GraphQLRouteSpec extends Specification with Specs2RouteTest with BeforeAll {

  sequential

  private val jwtSecret = "test-secret"
  private val config    = Configuration("graphql.jwt.secret" -> jwtSecret)
  private val route     = new GraphQLRoute(config, SubscriptionEventBus.noop).route

  override def beforeAll(): Unit = SharedTestDb.init()

  def body(query: String): JsValue = Json.obj("query" -> query)

  "GraphQLRoute POST /graphql" should {
    "return contests list" in {
      SharedTestDb.truncateAll()
      ContestJuryJdbc.create(None, "WLM", 2024, "Ukraine", None, None, None, None)
      Post("/graphql", body("{ contests { name year } }")) ~> route ~> check {
        val r = responseAs[JsValue]
        (r \ "data" \ "contests" \ 0 \ "name").as[String] must_== "WLM"
      }
    }

    "return UNAUTHENTICATED for createContest without auth" in {
      Post("/graphql", body("""mutation { createContest(input:{name:"X",year:2024,country:"Y"}) { id } }""")) ~>
        route ~> check {
          val r = responseAs[JsValue]
          (r \ "errors" \ 0 \ "extensions" \ "code").as[String] must_== "UNAUTHENTICATED"
        }
    }

    "accept X-Authenticated-User proxy header from localhost" in {
      SharedTestDb.truncateAll()
      User.create(User("Root", "root@test.com", roles = Set("root")))
      Post("/graphql", body("{ me { email } }")) ~>
        addHeader(RawHeader("X-Authenticated-User", "root@test.com")) ~>
        route ~> check {
          val r = responseAs[JsValue]
          (r \ "data" \ "me" \ "email").as[String] must_== "root@test.com"
        }
    }
  }
}
