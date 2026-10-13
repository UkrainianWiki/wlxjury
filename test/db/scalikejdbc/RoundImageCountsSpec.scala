package db.scalikejdbc

import org.specs2.mutable.Specification

import scala.collection.mutable

class RoundImageCountsSpec extends Specification {

  sequential

  /** A loader that records which rounds it was asked for. */
  private class Loader(counts: Map[Long, Int]) {
    val calls = mutable.Buffer.empty[Seq[Long]]
    def apply(ids: Seq[Long]): Map[Long, Int] = {
      calls += ids
      counts.view.filterKeys(ids.contains).toMap
    }
  }

  "RoundImageCounts.get" should {

    "load the missing rounds once, then serve them from the cache" in {
      RoundImageCounts.clear()
      val load = new Loader(Map(1L -> 10, 2L -> 20))
      RoundImageCounts.get(Seq(1L, 2L))(load.apply) === Map(1L -> 10, 2L -> 20)
      RoundImageCounts.get(Seq(1L, 2L))(load.apply) === Map(1L -> 10, 2L -> 20)
      load.calls.toSeq === Seq(Seq(1L, 2L))
    }

    "load only the rounds it doesn't have" in {
      RoundImageCounts.clear()
      val load = new Loader(Map(1L -> 10, 2L -> 20, 3L -> 30))
      RoundImageCounts.get(Seq(1L))(load.apply)
      RoundImageCounts.get(Seq(1L, 2L, 3L))(load.apply) === Map(1L -> 10, 2L -> 20, 3L -> 30)
      load.calls.toSeq === Seq(Seq(1L), Seq(2L, 3L))
    }

    "count a round the loader doesn't return (no images) as 0" in {
      RoundImageCounts.clear()
      RoundImageCounts.get(Seq(7L))(_ => Map.empty) === Map(7L -> 0)
    }
  }

  "RoundImageCounts.invalidate" should {

    "make the next get reload the round" in {
      RoundImageCounts.clear()
      RoundImageCounts.get(Seq(1L, 2L))(new Loader(Map(1L -> 10, 2L -> 20)).apply)
      RoundImageCounts.invalidate(1L)
      val load = new Loader(Map(1L -> 11, 2L -> 99))
      RoundImageCounts.get(Seq(1L, 2L))(load.apply) === Map(1L -> 11, 2L -> 20)
      load.calls.toSeq === Seq(Seq(1L))
    }

    "discard a load that ran while the round was written" in {
      RoundImageCounts.clear()
      // the write commits and invalidates while the (now stale) count is being loaded
      val stale = RoundImageCounts.get(Seq(1L)) { _ =>
        RoundImageCounts.invalidate(1L)
        Map(1L -> 10)
      }
      stale === Map(1L -> 10) // this caller gets what it loaded ...
      val load = new Loader(Map(1L -> 12))
      RoundImageCounts.get(Seq(1L))(load.apply) === Map(1L -> 12) // ... but it isn't cached
      load.calls.toSeq === Seq(Seq(1L))
    }
  }

  "RoundImageCounts.refresh" should {

    "cache the count it returns" in {
      RoundImageCounts.clear()
      RoundImageCounts.refresh(5L)(42) === 42
      val load = new Loader(Map(5L -> 0))
      RoundImageCounts.get(Seq(5L))(load.apply) === Map(5L -> 42)
      load.calls must beEmpty
    }

    "not cache a count the round was invalidated during" in {
      RoundImageCounts.clear()
      RoundImageCounts.refresh(5L) { RoundImageCounts.invalidate(5L); 42 } === 42
      RoundImageCounts.get(Seq(5L))(_ => Map(5L -> 43)) === Map(5L -> 43)
    }
  }
}
