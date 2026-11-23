package graphql.resolvers

import db.scalikejdbc.MonumentJdbc
import graphql.GraphQLContext
import org.scalawiki.wlx.dto.Monument
import sangria.schema._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object MonumentResolver {

  lazy val MonumentType: ObjectType[GraphQLContext, Monument] = ObjectType(
    "Monument",
    fields[GraphQLContext, Monument](
      Field("id",          StringType,             resolve = _.value.id),
      Field("name",        StringType,             resolve = _.value.name),
      Field("description", OptionType(StringType), resolve = _.value.description),
      Field("year",        OptionType(StringType), resolve = _.value.year),
      Field("city",        OptionType(StringType), resolve = _.value.city),
      Field("place",       OptionType(StringType), resolve = _.value.place),
      Field("lat",         OptionType(StringType), resolve = _.value.lat),
      Field("lon",         OptionType(StringType), resolve = _.value.lon),
      Field("typ",         OptionType(StringType), resolve = _.value.typ),
      Field("subType",     OptionType(StringType), resolve = _.value.subType),
      Field("photo",       OptionType(StringType), resolve = _.value.photo),
      Field("contestId",   OptionType(StringType), resolve = m => m.value.contest.map(_.toString))
    )
  )

  val monumentsField: Field[GraphQLContext, Unit] = Field(
    "monuments", ListType(MonumentType),
    arguments = Argument("contestId", IDType) :: Nil,
    resolve = c => {
      val contestId = c.arg[String]("contestId").toLong
      Future(MonumentJdbc.findAll().filter(_.contest.contains(contestId)))
    }
  )

  val monumentField: Field[GraphQLContext, Unit] = Field(
    "monument", OptionType(MonumentType),
    arguments = Argument("id", IDType) :: Nil,
    resolve = c => Future(MonumentJdbc.find(c.arg[String]("id")))
  )
}
