package db.scalikejdbc

import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap

/** The number of distinct images per round, cached.
  *
  * A round's image set changes only when images are distributed to it, removed from it
  * or merged into it, never on a vote (a vote updates `rate` on an existing selection
  * row), so the count is invalidated explicitly by those writes. Invalidate AFTER the
  * writing transaction commits: a load that starts before the commit and ends after
  * the invalidation is discarded through the version check. The TTL only bounds how
  * stale a count can get from writers outside this JVM (scripts, manual SQL).
  *
  * It lives in the db package so DAO code can invalidate it. It assumes one app
  * instance: a second live process would need a shared store or a short TTL.
  */
object RoundImageCounts {

  private final case class Entry(count: Int, at: Long, version: Long)

  private val TtlMs = 10 * 60 * 1000L

  private val entries = TrieMap.empty[Long, Entry]
  private val versions = TrieMap.empty[Long, AtomicLong]

  private def version(roundId: Long): AtomicLong =
    versions.getOrElseUpdate(roundId, new AtomicLong)

  /** The counts of `roundIds`, loading the missing or stale ones with `load` (which
    * leaves out rounds without images: they count 0).
    */
  def get(roundIds: Seq[Long])(load: Seq[Long] => Map[Long, Int]): Map[Long, Int] = {
    val now = System.currentTimeMillis()
    val fresh = roundIds.flatMap { id =>
      entries
        .get(id)
        .filter(e => now - e.at < TtlMs && e.version == version(id).get)
        .map(e => id -> e.count)
    }.toMap
    val missing = roundIds.distinct.filterNot(fresh.contains)
    if (missing.isEmpty) fresh
    else {
      val before = missing.map(id => id -> version(id).get).toMap // read BEFORE querying
      val loaded = load(missing)
      missing.foreach { id =>
        // keep the result only if no write invalidated the round meanwhile
        if (version(id).get == before(id)) entries.put(id, Entry(loaded.getOrElse(id, 0), now, before(id)))
      }
      fresh ++ missing.map(id => id -> loaded.getOrElse(id, 0))
    }
  }

  /** Counts a round's images now (after its writes committed) with `count`, caches the
    * result unless the round was invalidated meanwhile, and returns it.
    */
  def refresh(roundId: Long)(count: => Int): Int = {
    val now = System.currentTimeMillis()
    val before = version(roundId).get
    val result = count
    if (version(roundId).get == before) entries.put(roundId, Entry(result, now, before))
    result
  }

  /** Drops the rounds' counts, and any load of them still running. */
  def invalidate(roundIds: Long*): Unit = roundIds.foreach { id =>
    version(id).incrementAndGet()
    entries.remove(id)
  }

  /** Forgets everything: for tests that truncate the tables, which reuses round ids. */
  def clear(): Unit = {
    entries.clear()
    versions.clear()
  }
}
