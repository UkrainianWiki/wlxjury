// test/graphql/SubscriptionSpec.scala
package graphql

import graphql.SubscriptionEventBus.ImageRatedEvent
import munit.FunSuite
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Sink

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class SubscriptionSpec extends FunSuite {

  implicit val system: ActorSystem = ActorSystem("SubscriptionSpecSystem")
  implicit val mat: Materializer   = Materializer(system)

  override def afterAll(): Unit = system.terminate()

  test("SubscriptionEventBus delivers ImageRatedEvent to subscribers") {
    val bus = new SubscriptionEventBus()

    val collected = bus.imageRatedSource
      .take(1)
      .runWith(Sink.seq)

    bus.publishImageRated(ImageRatedEvent(roundId = 1L, pageId = 42L, juryId = 7L, rate = 1))

    val events = Await.result(collected, 3.seconds)
    assertEquals(events.size, 1)
    assertEquals(events.head.pageId, 42L)
    assertEquals(events.head.rate, 1)
  }

  test("imageRatedSource filters by roundId") {
    val bus = new SubscriptionEventBus()

    val collected = bus.imageRatedSource
      .filter(_.roundId == 10L)
      .take(1)
      .runWith(Sink.seq)

    bus.publishImageRated(ImageRatedEvent(roundId = 99L, pageId = 1L, juryId = 1L, rate = 1))
    bus.publishImageRated(ImageRatedEvent(roundId = 10L, pageId = 2L, juryId = 2L, rate = 1))

    val events = Await.result(collected, 3.seconds)
    assertEquals(events.head.roundId, 10L)
  }
}
