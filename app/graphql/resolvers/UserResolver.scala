package graphql.resolvers

import db.scalikejdbc.{ContestJuryJdbc, User}
import graphql.{GraphQLContext, NotFoundError}
import sangria.schema.InputObjectType.DefaultInput
import sangria.schema._
import scalikejdbc.sqls

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object UserResolver {

  lazy val UserType: ObjectType[GraphQLContext, User] = ObjectType(
    "User",
    fields[GraphQLContext, User](
      Field("id",          StringType,              resolve = u => u.value.id.fold("")(_.toString)),
      Field("fullname",    StringType,              resolve = _.value.fullname),
      Field("email",       StringType,              resolve = _.value.email),
      Field("roles",       ListType(StringType),    resolve = u => u.value.roles.toList),
      Field("contestId",   OptionType(StringType),  resolve = u => u.value.contestId.map(_.toString)),
      Field("lang",        OptionType(StringType),  resolve = _.value.lang),
      Field("wikiAccount", OptionType(StringType),  resolve = _.value.wikiAccount),
      Field("active",      OptionType(BooleanType), resolve = _.value.active)
    )
  )

  val meField: Field[GraphQLContext, Unit] = Field(
    "me", OptionType(UserType),
    resolve = c => Future.successful(c.ctx.currentUser)
  )

  val usersField: Field[GraphQLContext, Unit] = Field(
    "users", ListType(UserType),
    arguments = Argument("contestId", OptionInputType(IDType)) :: Nil,
    resolve = c => Future {
      c.argOpt[String]("contestId") match {
        case Some(id) => User.findAllBy(sqls.eq(User.u.contestId, id.toLong))
        case None     => User.findAll()
      }
    }
  )

  val userField: Field[GraphQLContext, Unit] = Field(
    "user", OptionType(UserType),
    arguments = Argument("id", IDType) :: Nil,
    resolve = c => Future(User.findById(c.arg[String]("id").toLong))
  )

  val createUserField: Field[GraphQLContext, Unit] = Field(
    "createUser", UserType,
    arguments = Argument("input", InputTypes.UserInputType) :: Nil,
    resolve = c => {
      c.ctx.requireRole("admin")
      val m = c.arg[DefaultInput]("input")
      Future(User.create(User(
        fullname  = InputTypes.str(m, "fullname"),
        email     = InputTypes.str(m, "email"),
        roles     = InputTypes.strList(m, "roles").toSet,
        contestId = InputTypes.optStr(m, "contestId").map(_.toLong),
        lang      = InputTypes.optStr(m, "lang"),
        wikiAccount = InputTypes.optStr(m, "wikiAccount")
      )))
    }
  )

  val updateUserField: Field[GraphQLContext, Unit] = Field(
    "updateUser", UserType,
    arguments = Argument("id", IDType) :: Argument("input", InputTypes.UserInputType) :: Nil,
    resolve = c => {
      c.ctx.requireRole("admin")
      val id = c.arg[String]("id").toLong
      val m  = c.arg[DefaultInput]("input")
      Future {
        val existing = User.findById(id).getOrElse(throw NotFoundError(s"User $id not found"))
        User.updateById(id).withAttributes(
          "fullname"    -> InputTypes.str(m, "fullname"),
          "email"       -> InputTypes.str(m, "email"),
          "roles"       -> InputTypes.strList(m, "roles").mkString(","),
          "contestId"   -> InputTypes.optStr(m, "contestId").map(_.toLong).orElse(existing.contestId).orNull,
          "lang"        -> InputTypes.optStr(m, "lang").orElse(existing.lang).orNull,
          "wikiAccount" -> InputTypes.optStr(m, "wikiAccount").orElse(existing.wikiAccount).orNull
        )
        User.findById(id).get
      }
    }
  )
}
