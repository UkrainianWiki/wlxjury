package db.scalikejdbc

import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap

/** A category's files' counts per region ([[ImageJdbc.categoryRegionCounts]]), cached: the
  * contest files page's region nav and pager (`GalleryController.contestFiles`), whose
  * count reads every member's monument id (0.1-0.15 s for a 38k-file contest).
  *
  * The counts change only when files join the category or a file's monument id changes:
  * those writes invalidate them, AFTER their transaction commits ([[invalidateCategory]],
  * [[invalidateMonumentIds]]). The versions are read before a load and compared after it,
  * so a load that ran while a write committed is discarded, as in [[RoundImageCounts]]. A
  * monument id change bumps one global version (they are rare: "Update monuments", an
  * import of changed files). The 10-minute TTL only bounds how stale counts can get from
  * writers outside this JVM (scripts, manual SQL); like [[RoundImageCounts]], it assumes one
  * app instance.
  *
  * Raw counts, not named regions: contests sharing a category may name them differently.
  * Being deleted on Commons doesn't change them (the list has the deleted files too).
  */
object RegionCountCache {

  /** The files in each region, and those without one. */
  final case class Counts(byRegion: Map[String, Int], noRegion: Int) {
    def total: Int = byRegion.values.sum + noRegion
  }

  private final case class Entry(counts: Counts, at: Long, version: Long, monumentVersion: Long)

  private val TtlMs = 10 * 60 * 1000L

  /** For tests: the clock. */
  @volatile private[db] var now: () => Long = () => System.currentTimeMillis()

  /** How many loads ran: for tests and the benchmark. */
  val loads = new AtomicLong

  private val entries = TrieMap.empty[Long, Entry]
  private val versions = TrieMap.empty[Long, AtomicLong]
  private val monumentVersion = new AtomicLong

  private def version(categoryId: Long): AtomicLong = versions.getOrElseUpdate(categoryId, new AtomicLong)

  /** The category's counts, loaded with `load` if they are missing or stale. */
  def category(categoryId: Long)(load: => Counts): Counts = {
    val at = now()
    val cached = entries.get(categoryId).filter { e =>
      at - e.at < TtlMs && e.version == version(categoryId).get && e.monumentVersion == monumentVersion.get
    }
    cached.fold {
      val (before, monumentsBefore) = (version(categoryId).get, monumentVersion.get) // read BEFORE querying
      loads.incrementAndGet()
      val counts = load
      // keep the result only if no write invalidated it meanwhile
      if (version(categoryId).get == before && monumentVersion.get == monumentsBefore)
        entries.put(categoryId, Entry(counts, at, before, monumentsBefore))
      counts
    }(_.counts)
  }

  /** Drops the categories' counts (files joined them), and any load of them still running. */
  def invalidateCategory(categoryIds: Long*): Unit = categoryIds.foreach { id =>
    version(id).incrementAndGet()
    entries.remove(id)
  }

  /** Drops every category's counts: a file's monument id changed. */
  def invalidateMonumentIds(): Unit = {
    monumentVersion.incrementAndGet()
    entries.clear()
  }

  /** Forgets everything: for tests that truncate the tables, and so reuse category ids. */
  def clear(): Unit = {
    entries.clear()
    versions.clear()
    monumentVersion.incrementAndGet()
  }
}
