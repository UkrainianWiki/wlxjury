package graphql.sangria
import db.scalikejdbc.{Round, User}
import org.intracer.wmua.ContestJury
import sangria.macros.derive._
import sangria.schema.{Argument, Field, InterfaceType, ObjectType, OptionType, PossibleInterface, Projector, StringType}

class JuryRepo {

}


object SchemaDefinition {

  val UserType = deriveObjectType[JuryRepo, User](
    ObjectTypeName("User"),
    ObjectTypeDescription("A user of the Jury Tool."),
    Interfaces(PossibleInterface(InterfaceType()))
  )
  val ContestType = deriveObjectType[JuryRepo, ContestJury](
    ObjectTypeName("Contest"),
    ObjectTypeDescription("Contest in the Jury Tool.")
  )
  val RoundType = deriveObjectType[JuryRepo, Round](
    ObjectTypeName("Round"),
    ObjectTypeDescription("Round in the contest.")
  )
  val ID = Argument("id", StringType, description = "id of the entity")

  val Query = ObjectType(
    "Query",
    Field("user", OptionType(UserType),
      arguments = ID :: Nil,
      resolve = ctx => ctx.ctx.getHuman(ctx arg ID)),
    Field("droid", Droid,
      arguments = ID :: Nil,
      resolve = Projector((ctx, f) => ctx.ctx.getDroid(ctx arg ID).get))
  )

}
