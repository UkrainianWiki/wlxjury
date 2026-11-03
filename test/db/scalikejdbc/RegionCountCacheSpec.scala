package db.scalikejdbc

import db.scalikejdbc.RegionCountCache.Counts
import org.specs2.mutable.Specification

class RegionCountCacheSpec extends Specification {

  sequential

  private def counts(n: Int) = Counts(Map("07" -> n), noRegion = 1)

  /** Loads that count their calls. */
  private class Loader(n: Int) {
    var calls = 0
    def apply(): Counts = { calls += 1; counts(n) }
  }

  private def fresh(): Unit = {
    RegionCountCache.now = () => System.currentTimeMillis()
    RegionCountCache.clear()
  }

  "RegionCountCache.category" should {

    "load once, then serve the counts from the cache" in {
      fresh()
      val load = new Loader(3)
      RegionCountCache.category(1L)(load()) === counts(3)
      RegionCountCache.category(1L)(load()) === counts(3)
      load.calls === 1
    }

    "count the files without a region in the total" in {
      counts(3).total === 4
    }

    "load again after the TTL" in {
      fresh()
      var clock = 0L
      RegionCountCache.now = () => clock
      val load = new Loader(3)
      RegionCountCache.category(1L)(load())
      clock += 9 * 60 * 1000L
      RegionCountCache.category(1L)(load())
      load.calls === 1
      clock += 2 * 60 * 1000L
      RegionCountCache.category(1L)(load())
      load.calls === 2
    }
  }

  "RegionCountCache.invalidateCategory" should {

    "drop that category only" in {
      fresh()
      val (one, two) = (new Loader(1), new Loader(2))
      RegionCountCache.category(1L)(one())
      RegionCountCache.category(2L)(two())
      RegionCountCache.invalidateCategory(1L)
      RegionCountCache.category(1L)(one())
      RegionCountCache.category(2L)(two())
      (one.calls, two.calls) === ((2, 1))
    }

    "discard a load that ran while the category was written" in {
      fresh()
      val stale = RegionCountCache.category(1L) {
        RegionCountCache.invalidateCategory(1L) // the write commits during the load
        counts(1)
      }
      stale === counts(1) // this caller gets what it loaded ...
      val load = new Loader(2)
      RegionCountCache.category(1L)(load()) === counts(2) // ... but it isn't cached
      load.calls === 1
    }
  }

  "RegionCountCache.invalidateMonumentIds" should {

    "drop every category, and a load running meanwhile" in {
      fresh()
      val (one, two) = (new Loader(1), new Loader(2))
      RegionCountCache.category(1L)(one())
      RegionCountCache.category(2L) { RegionCountCache.invalidateMonumentIds(); two() }
      RegionCountCache.category(1L)(one())
      RegionCountCache.category(2L)(two())
      (one.calls, two.calls) === ((2, 2))
    }
  }

  "RegionCountCache.clear" should {

    "forget everything" in {
      fresh()
      val load = new Loader(1)
      RegionCountCache.category(1L)(load())
      RegionCountCache.clear()
      RegionCountCache.category(1L)(load())
      load.calls === 2
    }
  }
}
