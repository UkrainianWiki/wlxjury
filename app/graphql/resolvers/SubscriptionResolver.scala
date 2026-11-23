// app/graphql/resolvers/SubscriptionResolver.scala
package graphql.resolvers

import graphql.{GraphQLContext, SubscriptionEventBus}
import graphql.SubscriptionEventBus.{ImageRatedEvent, PekkoSource, PekkoSubscriptionStream}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import sangria.schema._
import sangria.schema.Value

import scala.concurrent.ExecutionContext.Implicits.global

object SubscriptionResolver {

  private lazy val streamSystem: ActorSystem = ActorSystem("sangria-stream")
  private implicit lazy val streamMat: Materializer = Materializer(streamSystem)
  private implicit lazy val pekkoSS: PekkoSubscriptionStream = new PekkoSubscriptionStream()

  lazy val ImageRatedEventType: ObjectType[GraphQLContext, ImageRatedEvent] = ObjectType(
    "ImageRatedEvent",
    fields[GraphQLContext, ImageRatedEvent](
      Field("roundId", StringType, resolve = e => e.value.roundId.toString),
      Field("pageId",  StringType, resolve = e => e.value.pageId.toString),
      Field("juryId",  StringType, resolve = e => e.value.juryId.toString),
      Field("rate",    IntType,    resolve = _.value.rate)
    )
  )

  val imageRatedField: Field[GraphQLContext, Unit] = Field.subs(
    "imageRated", ImageRatedEventType,
    arguments = Argument("roundId", IDType) :: Nil,
    resolve = (c: Context[GraphQLContext, Unit]) => {
      val roundId = c.arg[String]("roundId").toLong
      c.ctx.eventBus.imageRatedSource
        .filter(_.roundId == roundId)
        .map(e => Value[GraphQLContext, ImageRatedEvent](e))
        .asInstanceOf[PekkoSource[Action[GraphQLContext, ImageRatedEvent]]]
    }
  )

  val roundProgressField: Field[GraphQLContext, Unit] = Field.subs(
    "roundProgress", ImageRatedEventType,
    arguments = Argument("roundId", IDType) :: Nil,
    resolve = (c: Context[GraphQLContext, Unit]) => {
      val roundId = c.arg[String]("roundId").toLong
      c.ctx.eventBus.imageRatedSource
        .filter(_.roundId == roundId)
        .map(e => Value[GraphQLContext, ImageRatedEvent](e))
        .asInstanceOf[PekkoSource[Action[GraphQLContext, ImageRatedEvent]]]
    }
  )
}
