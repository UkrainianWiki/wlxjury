package views

import java.time.Instant

/** How the account pages show a link's times: UTC, to the minute ("2026-11-05 14:30 UTC"), as
  * the database clock keeps them.
  */
object LinkTimes {
  def utc(at: Instant): String = at.toString.take(16).replace('T', ' ') + " UTC"
}
