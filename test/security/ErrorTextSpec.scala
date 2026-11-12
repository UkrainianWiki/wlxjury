package security

import org.specs2.mutable.Specification

/** G13 (N9, P1 6): users see message keys and a logged reference, never an exception's text.
  * A throwable's message (`getMessage`, `getLocalizedMessage`) in `app/` is read only in a log
  * call or at a reviewed site that tests it and never shows it.
  */
class ErrorTextSpec extends Specification {

  /** File, and how many of its reads of a message are not in a log call, with why. */
  private val Allowed: Map[String, (Int, String)] = Map(
    "app/controllers/RoundController.scala" ->
      (1 -> "refusal: the text of our own `require` on the round's settings (no driver text), shown on the form"),
    "app/services/jobs/Job.scala" -> (1 -> "isDuplicateActive: tests for the dedupe index's name"),
    "app/db/scalikejdbc/DbErrors.scala" ->
      (1 -> "isDeadlock / lock wait timeout: tests the driver's text, for JobScheduler.isTransient and CriteriaRate"),
    "app/services/RoundService.scala" -> (1 -> "submit: tests for the submit token's unique index"),
    "app/db/scalikejdbc/CriterionJdbc.scala" ->
      (1 -> "addIn: tests for the unique index on a criterion's name, answered by a message key"),
    "app/controllers/UserController.scala" ->
      (1 -> "isDuplicateKey: tests for users.email's unique key (1062, key `email`), answered by a message key")
  )

  private val Message = """\.get(?:Localized)?Message\b""".r
  private val LogCall = """\b(?:logger|log|Logger)\.(?:info|warn|error|debug|trace)\(""".r

  /** How many reads of a message in `text` are not inside a log call's arguments. */
  private def outsideLogCalls(text: String): Int = {
    val spans = LogCall.findAllMatchIn(text).map(c => c.end -> (c.end + SourceScan.callText(text, c.end).length)).toList
    Message.findAllMatchIn(text).count(m => !spans.exists { case (from, to) => m.start >= from && m.start < to })
  }

  "a throwable's message" should {
    "be read only in a log call or at a reviewed site" in {
      val counts = for {
        source <- SourceScan.files("app", ".scala", ".html")
        outside = outsideLogCalls(source.text)
        if outside > 0
      } yield source.path -> outside
      counts.filterNot { case (path, n) => Allowed.get(path).exists(_._1 >= n) } must beEmpty
    }

    "count a read as logged only inside the log call's arguments (R227-2)" in {
      outsideLogCalls("""logger.warn(s"failed: ${e.getMessage}")""") === 0
      outsideLogCalls("logger.warn(\n  s\"failed: \" +\n    e.getMessage)") === 0
      outsideLogCalls("""logger.info("x"); val m = e.getMessage""") === 1
      outsideLogCalls("logger.info(\"x\")\nval m = e.getLocalizedMessage") === 1
    }

    "never be stored as a job's error" in {
      val scheduler = SourceScan.files("app/services/jobs", ".scala")
      SourceScan.hits(scheduler, """JobJdbc\.update\([^)]*getMessage""".r) must beEmpty
    }
  }
}
