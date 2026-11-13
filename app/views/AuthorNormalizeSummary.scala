package views

import play.api.i18n.Messages
import services.imports.{AuthorNormalizeHandler, AuthorNormalizeState}
import services.jobs.Job

import java.time.{Instant, ZoneOffset}

/** A finished `author_normalize` job's outcome as one line, from what
  * [[AuthorNormalizeHandler]] keeps in the job's state (message keys only, no raw JSON):
  * "Authors normalized (job 31, 2026-11-13): 160,492 changed, 12 without an author".
  */
object AuthorNormalizeSummary {

  /** The summary; None if the job's state can't be read. */
  def text(job: Job)(implicit messages: Messages): Option[String] =
    job.state.validate[AuthorNormalizeState].asOpt.map(summary(job, _))

  private def summary(job: Job, st: AuthorNormalizeState)(implicit messages: Messages): String = {
    val counts = Seq(
      "changed" -> st.changed,
      "uploader" -> st.fromUploader,
      "unresolved" -> st.unresolved,
      "restored" -> st.restored,
      "not.restored" -> st.notRestored,
      "unchanged" -> st.unchanged,
      "skipped" -> st.skipped
    ).filter(_._2 > 0).map { case (key, n) => messages(s"authors.normalize.$key", n) }
    messages("authors.normalize.result", job.id.toString, date(job.finishedAt.getOrElse(job.createdAt)),
      if (counts.isEmpty) messages("authors.normalize.clean") else counts.mkString(", "))
  }

  private def date(at: Instant): String = at.atZone(ZoneOffset.UTC).toLocalDate.toString
}
