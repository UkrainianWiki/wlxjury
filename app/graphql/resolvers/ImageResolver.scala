package graphql.resolvers

import db.scalikejdbc.rewrite.ImageDbNew.{Limit, SelectionQuery}
import db.scalikejdbc.{ImageJdbc, SelectionJdbc}
import graphql.GraphQLContext
import org.intracer.wmua.{Image, ImageWithRating, Selection}
import sangria.schema.InputObjectType.DefaultInput
import sangria.schema._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object ImageResolver {

  lazy val ImageType: ObjectType[GraphQLContext, Image] = ObjectType(
    "Image",
    fields[GraphQLContext, Image](
      Field("pageId",     StringType,             resolve = i => i.value.pageId.toString),
      Field("title",      StringType,             resolve = _.value.title),
      Field("url",        OptionType(StringType), resolve = _.value.url),
      Field("pageUrl",    OptionType(StringType), resolve = _.value.pageUrl),
      Field("width",      IntType,                resolve = _.value.width),
      Field("height",     IntType,                resolve = _.value.height),
      Field("monumentId", OptionType(StringType), resolve = _.value.monumentId),
      Field("author",     OptionType(StringType), resolve = _.value.author),
      Field("mime",       OptionType(StringType), resolve = _.value.mime),
      Field("mpx",        OptionType(FloatType),  resolve = i => Some(i.value.mpx)),
      Field("isVideo",    BooleanType,            resolve = _.value.isVideo)
    )
  )

  lazy val SelectionType: ObjectType[GraphQLContext, Selection] = ObjectType(
    "Selection",
    fields[GraphQLContext, Selection](
      Field("id",         OptionType(StringType), resolve = s => s.value.id.map(_.toString)),
      Field("pageId",     StringType,             resolve = s => s.value.pageId.toString),
      Field("juryId",     StringType,             resolve = s => s.value.juryId.toString),
      Field("roundId",    StringType,             resolve = s => s.value.roundId.toString),
      Field("rate",       IntType,                resolve = _.value.rate),
      Field("criteriaId", OptionType(IntType),    resolve = _.value.criteriaId),
      Field("monumentId", OptionType(StringType), resolve = _.value.monumentId),
      Field("createdAt",  OptionType(StringType), resolve = s => s.value.createdAt.map(_.toString))
    )
  )

  lazy val ImageWithRatingType: ObjectType[GraphQLContext, ImageWithRating] = ObjectType(
    "ImageWithRating",
    fields[GraphQLContext, ImageWithRating](
      Field("image",      ImageType,               resolve = _.value.image),
      Field("selections", ListType(SelectionType), resolve = _.value.selection.toList),
      Field("totalRate",  FloatType,               resolve = iwr => {
        val sels = iwr.value.selection
        if (sels.isEmpty) 0.0
        else sels.map(_.rate).sum.toDouble / sels.count(_.rate > 0).max(1)
      }),
      Field("rateSum",    IntType,                 resolve = _.value.rateSum),
      Field("rank",       OptionType(IntType),     resolve = _.value.rank)
    )
  )

  val imagesField: Field[GraphQLContext, Unit] = Field(
    "images", ListType(ImageWithRatingType),
    arguments =
      Argument("roundId",  IDType) ::
      Argument("userId",   OptionInputType(IDType)) ::
      Argument("page",     OptionInputType(IntType)) ::
      Argument("pageSize", OptionInputType(IntType)) ::
      Argument("rate",     OptionInputType(IntType)) ::
      Argument("region",   OptionInputType(StringType)) :: Nil,
    resolve = c => {
      val roundId  = c.arg[String]("roundId").toLong
      val userId   = c.argOpt[String]("userId").map(_.toLong)
      val page     = c.argOpt[Int]("page").getOrElse(1)
      val pageSize = c.argOpt[Int]("pageSize").getOrElse(20)
      val offset   = (page - 1) * pageSize
      Future(SelectionQuery(
        roundId = Some(roundId),
        userId  = userId,
        rate    = c.argOpt[Int]("rate"),
        grouped = userId.isEmpty,
        limit   = Some(Limit(Some(pageSize), Some(offset)))
      ).list())
    }
  )

  val imageField: Field[GraphQLContext, Unit] = Field(
    "image", OptionType(ImageType),
    arguments = Argument("pageId", IDType) :: Nil,
    resolve = c => Future(ImageJdbc.findById(c.arg[String]("pageId").toLong))
  )

  val rateImageField: Field[GraphQLContext, Unit] = Field(
    "rateImage", SelectionType,
    arguments =
      Argument("roundId",    IDType) ::
      Argument("pageId",     IDType) ::
      Argument("rate",       IntType) ::
      Argument("criteriaId", OptionInputType(IntType)) :: Nil,
    resolve = c => {
      val user    = c.ctx.requireAuth()
      val roundId = c.arg[String]("roundId").toLong
      val pageId  = c.arg[String]("pageId").toLong
      val rate    = c.arg[Int]("rate")
      Future {
        SelectionJdbc.findBy(pageId, user.getId, roundId) match {
          case Some(_) =>
            SelectionJdbc.rate(pageId, user.getId, roundId, rate)
            SelectionJdbc.findBy(pageId, user.getId, roundId).get
          case None =>
            SelectionJdbc.create(pageId, rate, user.getId, roundId,
              monumentId = c.argOpt[String]("criteriaId").flatMap(_ => None))
        }
      }
    }
  )

  val rateImageBulkField: Field[GraphQLContext, Unit] = Field(
    "rateImageBulk", ListType(SelectionType),
    arguments =
      Argument("roundId", IDType) ::
      Argument("ratings", ListInputType(InputTypes.RatingInputType)) :: Nil,
    resolve = c => {
      val user    = c.ctx.requireAuth()
      val roundId = c.arg[String]("roundId").toLong
      val ratings = c.arg[Seq[DefaultInput]]("ratings")
      Future(ratings.toList.map { m =>
        val pageId = InputTypes.str(m, "pageId").toLong
        val rate   = InputTypes.int(m, "rate")
        SelectionJdbc.findBy(pageId, user.getId, roundId) match {
          case Some(_) =>
            SelectionJdbc.rate(pageId, user.getId, roundId, rate)
            SelectionJdbc.findBy(pageId, user.getId, roundId).get
          case None =>
            SelectionJdbc.create(pageId, rate, user.getId, roundId)
        }
      })
    }
  )

  val setRoundImagesField: Field[GraphQLContext, Unit] = Field(
    "setRoundImages", BooleanType,
    arguments = Argument("roundId", IDType) :: Argument("category", StringType) :: Nil,
    resolve = c => {
      c.ctx.requireRole("organizer")
      val roundId  = c.arg[String]("roundId").toLong
      val category = c.arg[String]("category")
      Future {
        db.scalikejdbc.ContestJuryJdbc.setImagesSource(roundId, Some(category))
        true
      }
    }
  )
}
