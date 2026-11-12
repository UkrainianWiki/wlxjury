package controllers

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

/** The lines an import didn't add, kept on the server between the import's POST and the import
  * page's GET it redirects to (post/redirect/get: a reload of the result is a GET and imports
  * nothing again). Its flash carries only a random key; the lines (emails the admin typed) stay
  * here, for the same account and contest only, read once, for [[TtlMillis]] at most. In memory: one
  * app instance (docs/deployment.md, "One app instance"); after a restart the page says the
  * list is gone, with how many lines it had ([[UserController.ImportResultCount]]).
  */
object ImportResults {

  /** The lines not added (at most [[MaxProblems]]), how many more there were (`notListed`), and
    * the role and mail choice to import them again with.
    */
  final case class Result(problems: Seq[UserController.ImportProblem], role: String, sendInvitation: Boolean,
                          notListed: Int = 0)

  private final case class Entry(accountId: Long, contestId: Long, storedAt: Long, result: Result)

  /** At most this many lines not added are kept and listed per import; the rest are counted. */
  val MaxProblems = 1000

  /** How long a result waits for its page. */
  val TtlMillis: Long = 10 * 60 * 1000L

  /** At most this many results wait at once; the oldest give way to a new one. */
  val MaxEntries = 500

  private val entries = new ConcurrentHashMap[String, Entry]()
  private val random = new SecureRandom()

  private def newKey(): String = {
    val bytes = new Array[Byte](16)
    random.nextBytes(bytes)
    bytes.map("%02x".format(_)).mkString
  }

  /** Keeps `result` for `accountId` in `contestId`, its first [[MaxProblems]] lines (the others
    * counted); the key for the flash.
    */
  def put(accountId: Long, contestId: Long, result: Result, now: Long = System.currentTimeMillis()): String = {
    prune(now)
    val key = newKey()
    val (kept, rest) = result.problems.splitAt(MaxProblems)
    entries.put(key, Entry(accountId, contestId, now,
      result.copy(problems = kept.toVector, notListed = result.notListed + rest.size)))
    key
  }

  /** The result under `key`, once: only for the account and contest that stored it, and not
    * expired. Another account's key is left as it is (nobody else can use or spend it).
    */
  def take(key: String, accountId: Long, contestId: Long, now: Long = System.currentTimeMillis()): Option[Result] =
    Option(entries.get(key))
      .filter(e => e.accountId == accountId && e.contestId == contestId)
      .filter(e => entries.remove(key, e))
      .filter(e => now - e.storedAt <= TtlMillis)
      .map(_.result)

  private def prune(now: Long): Unit = {
    entries.asScala.foreach { case (k, e) => if (now - e.storedAt > TtlMillis) entries.remove(k, e) }
    val over = entries.size - MaxEntries + 1
    if (over > 0)
      entries.asScala.toSeq.sortBy(_._2.storedAt).take(over).foreach { case (k, e) => entries.remove(k, e) }
  }

  /** For tests: forget everything. */
  private[controllers] def clear(): Unit = entries.clear()
}
