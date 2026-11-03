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
      (1 -> "addIn: tests for the unique index on a criterion's name, answered by a message key")
  )

  private val Message = """\.get(?:Localized)?Message\b""".r
  private val LogCall = """\b(?:logger|log|Logger)\.(?:info|warn|error|debug|trace)\(""".r

  "a throwable's message" should {
    "be read only in a log call or at a reviewed site" in {
      val counts = for {
        source <- SourceScan.files("app", ".scala", ".html")
        lines = source.text.split("\n", -1)
        outside = Message.findAllMatchIn(source.text).count { m =>
          // the log call is on the same line or opened on one of the 2 lines before
          val line = source.lineOf(m.start) - 1
          !(math.max(0, line - 2) to line).exists(i => LogCall.findFirstIn(lines(i)).nonEmpty)
        }
        if outside > 0
      } yield source.path -> outside
      counts.filterNot { case (path, n) => Allowed.get(path).exists(_._1 >= n) } must beEmpty
    }

    "never be stored as a job's error" in {
      val scheduler = SourceScan.files("app/services/jobs", ".scala")
      SourceScan.hits(scheduler, """JobJdbc\.update\([^)]*getMessage""".r) must beEmpty
    }
  }
}
