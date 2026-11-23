package graphql.resolvers

import db.scalikejdbc.User
import graphql.{AuthenticationError, GraphQLContext}
import pdi.jwt.{JwtAlgorithm, JwtClaim, JwtJson}
import play.api.libs.json.Json
import sangria.schema._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object AuthResolver {

  lazy val AuthPayloadType: ObjectType[GraphQLContext, (String, User)] = ObjectType(
    "AuthPayload",
    fields[GraphQLContext, (String, User)](
      Field("token", StringType,           resolve = _.value._1),
      Field("user",  UserResolver.UserType, resolve = _.value._2)
    )
  )

  def loginField(jwtSecret: String): Field[GraphQLContext, Unit] = Field(
    "login", AuthPayloadType,
    arguments = Argument("email", StringType) :: Argument("password", StringType) :: Nil,
    resolve = c => {
      val email    = c.arg[String]("email")
      val password = c.arg[String]("password")
      Future {
        val userOpt = User.findByEmail(email).headOption
        val authed  = userOpt.filter(_.password.exists(_ == User.sha1(password)))
        val user    = authed.getOrElse(throw AuthenticationError("Invalid email or password"))
        val claim = JwtClaim(
          content    = Json.obj("userId" -> user.getId).toString,
          expiration = Some(System.currentTimeMillis() / 1000 + 86400 * 30),
          issuedAt   = Some(System.currentTimeMillis() / 1000)
        )
        val token = JwtJson.encode(claim, jwtSecret, JwtAlgorithm.HS256)
        (token, user)
      }
    }
  )
}
