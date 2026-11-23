// app/graphql/SubscriptionEventBus.scala
package graphql

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{BroadcastHub, Keep, Sink, Source, SourceQueue}
import org.apache.pekko.stream.{Materializer, OverflowStrategy}
import sangria.streaming.SubscriptionStream

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

object SubscriptionEventBus {
  case class ImageRatedEvent(roundId: Long, pageId: Long, juryId: Long, rate: Int)

  type PekkoSource[T] = Source[T, NotUsed]

  class PekkoSubscriptionStream(implicit mat: Materializer, ec: ExecutionContext)
      extends SubscriptionStream[PekkoSource] {

    def supported[T[_]](other: SubscriptionStream[T]): Boolean =
      other.isInstanceOf[PekkoSubscriptionStream]

    def single[T](value: T): PekkoSource[T] = Source.single(value)

    def singleFuture[T](value: Future[T]): PekkoSource[T] = Source.future(value)

    def first[T](s: PekkoSource[T]): Future[T] = s.runWith(Sink.head)

    def failed[T](e: Throwable): PekkoSource[T] = Source.failed(e)

    def onComplete[Ctx, Res](result: PekkoSource[Res])(op: => Unit): PekkoSource[Res] =
      result.watchTermination()((_, done) => { done.onComplete(_ => op); NotUsed })

    def flatMapFuture[Ctx, Res, T](future: Future[T])(
        resultFn: T => PekkoSource[Res]): PekkoSource[Res] =
      Source.future(future).flatMapConcat(resultFn)

    def mapFuture[A, B](source: PekkoSource[A])(fn: A => Future[B]): PekkoSource[B] =
      source.mapAsync(1)(fn)

    def map[A, B](source: PekkoSource[A])(fn: A => B): PekkoSource[B] = source.map(fn)

    def merge[T](streams: Vector[PekkoSource[T]]): PekkoSource[T] =
      if (streams.isEmpty) Source.empty
      else if (streams.size == 1) streams.head
      else Source.combine(streams.head, streams(1), streams.drop(2): _*)(
        org.apache.pekko.stream.scaladsl.Merge(_))

    def recover[T](stream: PekkoSource[T])(fn: Throwable => T): PekkoSource[T] =
      stream.recover { case e => fn(e) }
  }

  class NoopEventBus extends SubscriptionEventBus()(
    Materializer.matFromSystem(ActorSystem("noop-subscription"))) {
    override def publishImageRated(event: ImageRatedEvent): Unit = ()
  }
  lazy val noop: SubscriptionEventBus = new NoopEventBus
}

@Singleton
class SubscriptionEventBus @Inject()(implicit mat: Materializer) {
  import SubscriptionEventBus._

  private val (queue, broadcastSource): (SourceQueue[ImageRatedEvent], PekkoSource[ImageRatedEvent]) =
    Source.queue[ImageRatedEvent](bufferSize = 256, overflowStrategy = OverflowStrategy.dropHead)
      .toMat(BroadcastHub.sink(bufferSize = 256))(Keep.both)
      .run()

  def imageRatedSource: PekkoSource[ImageRatedEvent] = broadcastSource

  def publishImageRated(event: ImageRatedEvent): Unit = queue.offer(event)
}
