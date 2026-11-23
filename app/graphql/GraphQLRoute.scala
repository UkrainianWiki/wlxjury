// app/graphql/GraphQLRoute.scala
package graphql

import com.github.pjfanning.pekkohttpplayjson.PlayJsonSupport._
import db.scalikejdbc.User
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import pdi.jwt.{JwtAlgorithm, JwtJson}
import play.api.Configuration
import play.api.libs.json._
import sangria.execution.Executor
import sangria.marshalling.playJson._
import sangria.parser.QueryParser

import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

class GraphQLRoute @Inject()(config: Configuration, eventBus: SubscriptionEventBus)(implicit ec: ExecutionContext) {

  private val jwtSecret = config.get[String]("graphql.jwt.secret")
  private val schema    = SchemaDefinition.schema(jwtSecret)

  private def resolveUser(
    authHeader: Option[String],
    proxyUser:  Option[String]
  ): Option[User] =
    proxyUser
      .flatMap(email => User.findByEmail(email).headOption)
      .orElse {
        authHeader.filter(_.startsWith("Bearer ")).flatMap { h =>
          JwtJson.decodeJson(h.drop(7), jwtSecret, Seq(JwtAlgorithm.HS256)).toOption
            .flatMap(j => (j \ "userId").asOpt[Long])
            .flatMap(User.findById)
        }
      }

  val route: Route =
    path("graphql") {
      post {
        optionalHeaderValueByName("Authorization") { authHeader =>
          optionalHeaderValueByName("X-Authenticated-User") { proxyUser =>
            entity(as[JsValue]) { body =>
              val query  = (body \ "query").as[String]
              val vars   = (body \ "variables").asOpt[JsObject].getOrElse(JsObject.empty)
              val opName = (body \ "operationName").asOpt[String]
              val ctx    = GraphQLContext(resolveUser(authHeader, proxyUser), eventBus)

              QueryParser.parse(query) match {
                case Success(ast) =>
                  complete(Executor.execute(schema, ast, ctx,
                    variables        = vars,
                    operationName    = opName,
                    exceptionHandler = GraphQLContext.exceptionHandler))
                case Failure(e) =>
                  complete(StatusCodes.BadRequest,
                    Json.obj("errors" -> Json.arr(Json.obj("message" -> e.getMessage))))
              }
            }
          }
        }
      }
    }
}
