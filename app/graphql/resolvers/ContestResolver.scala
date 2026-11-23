// app/graphql/resolvers/ContestResolver.scala
package graphql.resolvers

import db.scalikejdbc.{ContestJuryJdbc, Round}
import graphql.{GraphQLContext, NotFoundError}
import org.intracer.wmua.ContestJury
import sangria.schema.InputObjectType.DefaultInput
import sangria.schema._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object ContestResolver {

  lazy val ContestType: ObjectType[GraphQLContext, ContestJury] = ObjectType(
    "Contest",
    () => fields[GraphQLContext, ContestJury](
      Field("id",                 StringType,             resolve = c => c.value.id.fold("")(_.toString)),
      Field("name",               StringType,             resolve = _.value.name),
      Field("year",               IntType,                resolve = _.value.year),
      Field("country",            StringType,             resolve = _.value.country),
      Field("images",             OptionType(StringType), resolve = _.value.images),
      Field("currentRound",       OptionType(StringType), resolve = c => c.value.currentRound.map(_.toString)),
      Field("monumentIdTemplate", OptionType(StringType), resolve = _.value.monumentIdTemplate),
      Field("campaign",           OptionType(StringType), resolve = _.value.campaign),
      Field("rounds",             ListType(RoundResolver.RoundType),
        resolve = c => Future(Round.findByContest(c.value.getId)))
    )
  )

  val contestsField: Field[GraphQLContext, Unit] = Field(
    "contests", ListType(ContestType),
    resolve = _ => Future(ContestJuryJdbc.findAll())
  )

  val contestField: Field[GraphQLContext, Unit] = Field(
    "contest", OptionType(ContestType),
    arguments = Argument("id", IDType) :: Nil,
    resolve = c => Future(ContestJuryJdbc.findById(c.arg[String]("id").toLong))
  )

  val createContestField: Field[GraphQLContext, Unit] = Field(
    "createContest", ContestType,
    arguments = Argument("input", InputTypes.ContestInputType) :: Nil,
    resolve = c => {
      c.ctx.requireRole("root")
      val m = c.arg[DefaultInput]("input")
      Future(ContestJuryJdbc.create(
        None,
        InputTypes.str(m, "name"),
        InputTypes.int(m, "year"),
        InputTypes.str(m, "country"),
        InputTypes.optStr(m, "images"),
        None, None,
        InputTypes.optStr(m, "monumentIdTemplate"),
        InputTypes.optStr(m, "campaign")
      ))
    }
  )

  val updateContestField: Field[GraphQLContext, Unit] = Field(
    "updateContest", ContestType,
    arguments = Argument("id", IDType) :: Argument("input", InputTypes.ContestInputType) :: Nil,
    resolve = c => {
      c.ctx.requireRole("root")
      val id = c.arg[String]("id").toLong
      val m  = c.arg[DefaultInput]("input")
      Future {
        ContestJuryJdbc.updateById(id).withAttributes(
          "name"               -> InputTypes.str(m, "name"),
          "year"               -> InputTypes.int(m, "year"),
          "country"            -> InputTypes.str(m, "country"),
          "images"             -> InputTypes.optStr(m, "images").orNull,
          "campaign"           -> InputTypes.optStr(m, "campaign").orNull,
          "monumentIdTemplate" -> InputTypes.optStr(m, "monumentIdTemplate").orNull
        )
        ContestJuryJdbc.findById(id).getOrElse(throw NotFoundError(s"Contest $id not found"))
      }
    }
  )

  val deleteContestField: Field[GraphQLContext, Unit] = Field(
    "deleteContest", BooleanType,
    arguments = Argument("id", IDType) :: Nil,
    resolve = c => {
      c.ctx.requireRole("root")
      val id = c.arg[String]("id").toLong
      Future {
        ContestJuryJdbc.deleteById(id)
        true
      }
    }
  )
}
