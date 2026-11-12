package views

import play.api.i18n.Messages
import services.distribution.{ImageCheckState, RoundImageCheckHandler}
import services.jobs.Job

import java.time.{Instant, ZoneOffset}

/** A finished `round_image_check` job's outcome as one line, from what
  * [[RoundImageCheckHandler]] keeps in the job's state (message keys only, no raw JSON):
  * "Image check (job 29, 2026-11-12): 1,823 rounds checked, no mismatches".
  */
object ImageCheckSummary {

  /** The summary; None if the job's state can't be read (not shown as "no mismatches"). */
  def text(job: Job)(implicit messages: Messages): Option[String] =
    job.state.validate[ImageCheckState].asOpt.map(summary(job, _))

  private def summary(job: Job, st: ImageCheckState)(implicit messages: Messages): String = {
    val all = RoundImageCheckHandler.roundOf(job).isEmpty
    val counts = Seq(
      "added" -> st.added,
      "status.fixed" -> st.statusFixed,
      "removed" -> st.removed,
      "monument.fixed" -> st.monumentFixed,
      "orphans" -> st.orphansArchived,
      "not.repaired" -> (st.notMarkedDeleted + st.dqWithVotes),
      "skipped" -> st.skipped.size.toLong
    ).filter(_._2 > 0).map { case (key, n) => messages(s"round.images.check.$key", n) }
    val outcome = (if (all) Seq(messages("round.images.check.rounds", st.rounds)) else Nil) :+
      (if (counts.isEmpty) messages("round.images.check.clean") else counts.mkString(", "))
    messages("round.images.check.result", job.id.toString, date(job.finishedAt.getOrElse(job.createdAt)),
      outcome.mkString(", "))
  }

  private def date(at: Instant): String = at.atZone(ZoneOffset.UTC).toLocalDate.toString
}
