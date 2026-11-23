package graphql.resolvers

import graphql.GraphQLContext
import org.intracer.wmua.{Comment, CommentJdbc}
import sangria.schema._

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object CommentResolver {

  lazy val CommentType: ObjectType[GraphQLContext, Comment] = ObjectType(
    "Comment",
    fields[GraphQLContext, Comment](
      Field("id",          StringType,             resolve = c => c.value.id.toString),
      Field("userId",      StringType,             resolve = c => c.value.userId.toString),
      Field("username",    StringType,             resolve = _.value.username),
      Field("roundId",     StringType,             resolve = c => c.value.roundId.toString),
      Field("contestId",   OptionType(StringType), resolve = c => c.value.contestId.map(_.toString)),
      Field("imagePageId", StringType,             resolve = c => c.value.room.toString),
      Field("body",        StringType,             resolve = _.value.body),
      Field("createdAt",   StringType,             resolve = _.value.createdAt)
    )
  )

  val commentsField: Field[GraphQLContext, Unit] = Field(
    "comments", ListType(CommentType),
    arguments =
      Argument("roundId",     IDType) ::
      Argument("imagePageId", OptionInputType(IDType)) :: Nil,
    resolve = c => {
      val roundId = c.arg[String]("roundId").toLong
      Future(
        c.argOpt[String]("imagePageId").map(_.toLong) match {
          case Some(pageId) => CommentJdbc.findByRoundAndSubject(roundId, pageId)
          case None         => CommentJdbc.findByRound(roundId)
        }
      )
    }
  )

  val addCommentField: Field[GraphQLContext, Unit] = Field(
    "addComment", CommentType,
    arguments =
      Argument("roundId",     IDType) ::
      Argument("imagePageId", IDType) ::
      Argument("body",        StringType) :: Nil,
    resolve = c => {
      val user        = c.ctx.requireAuth()
      val roundId     = c.arg[String]("roundId").toLong
      val imagePageId = c.arg[String]("imagePageId").toLong
      val body        = c.arg[String]("body")
      Future(CommentJdbc.create(user.getId, user.fullname, roundId, user.contestId, imagePageId, body,
        createdAt = LocalDateTime.now.toString))
    }
  )
}
