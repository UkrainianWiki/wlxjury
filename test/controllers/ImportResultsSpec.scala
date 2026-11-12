package controllers

import db.scalikejdbc.User
import org.specs2.mutable.Specification

/** An import's result between its redirect and the import page: once, for its account and
  * contest only, not after [[ImportResults.TtlMillis]], at most [[ImportResults.MaxEntries]].
  */
class ImportResultsSpec extends Specification {

  sequential

  private val result = ImportResults.Result(
    Seq(UserController.ImportProblem(2, User("", "x@example.com"), Some("abcd1234"))), "jury", sendInvitation = false)

  "an import result" should {
    "be read once, by its account in its contest only" in {
      ImportResults.clear()
      val key = ImportResults.put(1L, 10L, result, now = 1000L)
      (ImportResults.take(key, 2L, 10L, now = 1001L) must beNone) and
        (ImportResults.take(key, 1L, 11L, now = 1001L) must beNone) and
        (ImportResults.take(key, 1L, 10L, now = 1002L) must beSome(result)) and
        (ImportResults.take(key, 1L, 10L, now = 1003L) must beNone) and
        (ImportResults.take("no-such-key", 1L, 10L) must beNone)
    }

    "expire" in {
      ImportResults.clear()
      val key = ImportResults.put(1L, 10L, result, now = 0L)
      ImportResults.take(key, 1L, 10L, now = ImportResults.TtlMillis + 1) must beNone
    }

    "make way, the oldest first, past the most kept at once" in {
      ImportResults.clear()
      val first = ImportResults.put(1L, 10L, result, now = 0L)
      val rest = (1 to ImportResults.MaxEntries).map(i => ImportResults.put(1L, 10L, result, now = i.toLong))
      (ImportResults.take(first, 1L, 10L, now = 600L) must beNone) and
        (ImportResults.take(rest.last, 1L, 10L, now = 600L) must beSome(result)) and
        (rest.distinct.size === rest.size)
    }
  }
}
