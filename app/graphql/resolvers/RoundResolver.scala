package graphql.resolvers

import db.scalikejdbc.rewrite.ImageDbNew.{Limit, SelectionQuery}
import db.scalikejdbc.{Round, RoundUser, User}
import graphql.{GraphQLContext, NotFoundError}
import sangria.schema.InputObjectType.DefaultInput
import sangria.schema._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object RoundResolver {

  lazy val RoundType: ObjectType[GraphQLContext, Round] = ObjectType(
    "Round",
    () => fields[GraphQLContext, Round](
      Field("id",           StringType,             resolve = r => r.value.id.fold("")(_.toString)),
      Field("number",       IntType,                resolve = r => r.value.number.toInt),
      Field("name",         OptionType(StringType), resolve = _.value.name),
      Field("contestId",    StringType,             resolve = r => r.value.contestId.toString),
      Field("active",       BooleanType,            resolve = _.value.active),
      Field("distribution", IntType,                resolve = _.value.distribution),
      Field("minMpx",       OptionType(IntType),    resolve = _.value.minMpx),
      Field("category",     OptionType(StringType), resolve = _.value.category),
      Field("regions",      OptionType(StringType), resolve = _.value.regions),
      Field("mediaType",    OptionType(StringType), resolve = _.value.mediaType),
      Field("hasCriteria",  BooleanType,            resolve = _.value.hasCriteria),
      Field("jurors",       ListType(UserResolver.UserType),
        resolve = r => Future(User.findByRoundSelection(r.value.getId))),
      Field("images",       ListType(ImageResolver.ImageWithRatingType),
        arguments =
          Argument("page",     OptionInputType(IntType), defaultValue = 1) ::
          Argument("pageSize", OptionInputType(IntType), defaultValue = 20) ::
          Argument("rate",     OptionInputType(IntType)) ::
          Argument("region",   OptionInputType(StringType)) :: Nil,
        resolve = r => {
          val page     = r.argOpt[Int]("page").getOrElse(1)
          val pageSize = r.argOpt[Int]("pageSize").getOrElse(20)
          val offset   = (page - 1) * pageSize
          Future(SelectionQuery(
            roundId = r.value.id,
            rate    = r.argOpt[Int]("rate"),
            grouped = true,
            limit   = Some(Limit(Some(pageSize), Some(offset)))
          ).list())
        }
      )
    )
  )

  lazy val JurorStatType: ObjectType[GraphQLContext, (User, Int, Int)] = ObjectType(
    "JurorStat",
    fields[GraphQLContext, (User, Int, Int)](
      Field("user",     UserResolver.UserType, resolve = _.value._1),
      Field("rated",    IntType,               resolve = _.value._2),
      Field("selected", IntType,               resolve = _.value._3)
    )
  )

  lazy val RoundStatType: ObjectType[GraphQLContext, (Long, Int, Int, Seq[(User, Int, Int)])] = ObjectType(
    "RoundStat",
    fields[GraphQLContext, (Long, Int, Int, Seq[(User, Int, Int)])](
      Field("roundId",     StringType,              resolve = r => r.value._1.toString),
      Field("totalImages", IntType,                 resolve = _.value._2),
      Field("ratedImages", IntType,                 resolve = _.value._3),
      Field("jurorStats",  ListType(JurorStatType), resolve = _.value._4)
    )
  )

  val roundField: Field[GraphQLContext, Unit] = Field(
    "round", OptionType(RoundType),
    arguments = Argument("id", IDType) :: Nil,
    resolve = c => Future(Round.findById(c.arg[String]("id").toLong))
  )

  val roundsField: Field[GraphQLContext, Unit] = Field(
    "rounds", ListType(RoundType),
    arguments = Argument("contestId", IDType) :: Nil,
    resolve = c => Future(Round.findByContest(c.arg[String]("contestId").toLong))
  )

  val roundStatField: Field[GraphQLContext, Unit] = Field(
    "roundStat", RoundStatType,
    arguments = Argument("roundId", IDType) :: Nil,
    resolve = c => {
      val roundId = c.arg[String]("roundId").toLong
      Future {
        Round.findById(roundId).getOrElse(throw NotFoundError(s"Round $roundId not found"))
        val total  = SelectionQuery(roundId = Some(roundId), grouped = true).count()
        val rated  = SelectionQuery(roundId = Some(roundId), rate = Some(1), grouped = true).count()
        val jurors = User.findByRoundSelection(roundId)
        val stats  = jurors.map { u =>
          val r = SelectionQuery(roundId = Some(roundId), userId = u.id, grouped = true).count()
          val s = SelectionQuery(roundId = Some(roundId), userId = u.id, rate = Some(1), grouped = true).count()
          (u, r, s)
        }
        (roundId, total, rated, stats)
      }
    }
  )

  val createRoundField: Field[GraphQLContext, Unit] = Field(
    "createRound", RoundType,
    arguments = Argument("input", InputTypes.RoundInputType) :: Nil,
    resolve = c => {
      c.ctx.requireRole("organizer")
      val m = c.arg[DefaultInput]("input")
      Future(Round.create(Round(
        id           = None,
        number       = 0,
        name         = InputTypes.optStr(m, "name"),
        contestId    = InputTypes.str(m, "contestId").toLong,
        distribution = InputTypes.optInt(m, "distribution").getOrElse(0),
        minMpx       = InputTypes.optInt(m, "minMpx"),
        category     = InputTypes.optStr(m, "category"),
        regions      = InputTypes.optStr(m, "regions"),
        mediaType    = InputTypes.optStr(m, "mediaType"),
        hasCriteria  = InputTypes.optBool(m, "hasCriteria").getOrElse(false),
        previous     = InputTypes.optStr(m, "previousRoundId").map(_.toLong),
        prevSelectedBy = InputTypes.optInt(m, "prevSelectedBy")
      )))
    }
  )

  val updateRoundField: Field[GraphQLContext, Unit] = Field(
    "updateRound", RoundType,
    arguments = Argument("id", IDType) :: Argument("input", InputTypes.RoundInputType) :: Nil,
    resolve = c => {
      c.ctx.requireRole("organizer")
      val id = c.arg[String]("id").toLong
      val m  = c.arg[DefaultInput]("input")
      Future {
        val existing = Round.findById(id).getOrElse(throw NotFoundError(s"Round $id not found"))
        Round.updateById(id).withAttributes(
          "name"        -> InputTypes.optStr(m, "name").orElse(existing.name).orNull,
          "category"    -> InputTypes.optStr(m, "category").orElse(existing.category).orNull,
          "regions"     -> InputTypes.optStr(m, "regions").orElse(existing.regions).orNull,
          "mediaType"   -> InputTypes.optStr(m, "mediaType").orElse(existing.mediaType).orNull,
          "hasCriteria" -> InputTypes.optBool(m, "hasCriteria").getOrElse(existing.hasCriteria),
          "minMpx"      -> InputTypes.optInt(m, "minMpx").orElse(existing.minMpx).orNull
        )
        Round.findById(id).get
      }
    }
  )

  val setActiveRoundField: Field[GraphQLContext, Unit] = Field(
    "setActiveRound", RoundType,
    arguments = Argument("id", IDType) :: Nil,
    resolve = c => {
      c.ctx.requireRole("organizer")
      val id = c.arg[String]("id").toLong
      Future {
        val round = Round.findById(id).getOrElse(throw NotFoundError(s"Round $id not found"))
        Round.setActive(id, active = true)
        round.copy(active = true)
      }
    }
  )

  val addJurorsToRoundField: Field[GraphQLContext, Unit] = Field(
    "addJurorsToRound", RoundType,
    arguments = Argument("roundId", IDType) :: Argument("userIds", ListInputType(IDType)) :: Nil,
    resolve = c => {
      c.ctx.requireRole("organizer")
      val roundId = c.arg[String]("roundId").toLong
      val userIds = c.arg[Seq[String]]("userIds").map(_.toLong)
      Future {
        val round = Round.findById(roundId).getOrElse(throw NotFoundError(s"Round $roundId not found"))
        round.addUsers(userIds.map(uid => RoundUser(roundId, uid, "jury", active = true)))
        Round.findById(roundId).get
      }
    }
  )
}
