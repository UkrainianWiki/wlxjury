package db.scalikejdbc

import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap

/** An international contest's files' countries, by page id (services.places.PlaceScheme.Countries,
  * from [[ImageJdbc.categoryCountryLinks]]): cached per category and its country categories'
  * prefixes, as [[RegionCountCache]] caches the region counts, since every page with the country
  * filter or menu needs them and the read is of all the category's members' links.
  *
  * They change only when files join the category or its files join country categories: an import
  * invalidates them AFTER its transaction commits ([[invalidate]]; [[RegionCountCache.invalidateCategory]]
  * of the category too). A version read before a load and compared after it discards a load that
  * ran while such a write committed. The 10-minute TTL bounds how stale they get from writers
  * outside this JVM; one app instance is assumed, as for [[RegionCountCache]].
  */
object CountryFilesCache {

  private final case class Entry(byFile: Map[Long, String], at: Long, version: Long)

  private val TtlMs = 10 * 60 * 1000L

  /** For tests: the clock. */
  @volatile private[db] var now: () => Long = () => System.currentTimeMillis()

  /** How many loads ran: for tests. */
  val loads = new AtomicLong

  private val entries = TrieMap.empty[(Long, Seq[String]), Entry]
  private val versions = TrieMap.empty[Long, AtomicLong]

  private def version(categoryId: Long): AtomicLong = versions.getOrElseUpdate(categoryId, new AtomicLong)

  /** The category's files' countries for these prefixes, loaded with `load` if missing or stale. */
  def countries(categoryId: Long, prefixes: Seq[String])(load: => Map[Long, String]): Map[Long, String] = {
    val key = (categoryId, prefixes)
    val at = now()
    entries.get(key).filter(e => at - e.at < TtlMs && e.version == version(categoryId).get).fold {
      val before = version(categoryId).get // read BEFORE querying
      loads.incrementAndGet()
      val byFile = load
      if (version(categoryId).get == before) entries.put(key, Entry(byFile, at, before))
      byFile
    }(_.byFile)
  }

  /** Drops the categories' countries (files joined them or their country categories). */
  def invalidate(categoryIds: Long*): Unit = categoryIds.foreach { id =>
    version(id).incrementAndGet()
    entries.filterInPlace { case ((c, _), _) => c != id }
  }

  /** Forgets everything: for tests that truncate the tables, and so reuse category ids. */
  def clear(): Unit = {
    entries.clear()
    versions.values.foreach(_.incrementAndGet())
  }
}
