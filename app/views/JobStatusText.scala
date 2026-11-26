package views

import play.api.i18n.Messages
import services.jobs.{Job, JobStatus}

import java.text.NumberFormat
import java.util.Locale

/** A background job's status line as `app/views/snippets/jobStatus.scala.html` first renders it, in
  * the words public/javascripts/job-status.js (`formatJobStatus`) replaces it with on its first poll:
  * only the progress ("1,034 / 38,248 files (2%)…"), never the job's title, which the partial shows
  * once as its label. The counts in the job's details come with the poll.
  */
object JobStatusText {

  private def count(n: Long): String = NumberFormat.getIntegerInstance(Locale.US).format(n)

  /** "1,034 / 38,248 files (2%)", "260 list pages", or "" before the first step reports any. */
  def progress(job: Job): String = {
    val unit = job.progressUnit.filter(_.nonEmpty).fold("")(" " + _)
    (job.progressDone, job.progressTotal) match {
      case (Some(done), Some(total)) if total > 0 =>
        s"${count(done)} / ${count(total)}$unit (${math.min(100L, 100L * done / total)}%)"
      case (Some(done), _) if done > 0 => s"${count(done)}$unit"
      case _                           => ""
    }
  }

  def text(job: Job)(implicit messages: Messages): String = {
    val p = progress(job)
    val at = if (p.isEmpty) "" else s" at $p"
    job.status match {
      case JobStatus.Queued    => "Queued"
      case JobStatus.Running   => (if (p.isEmpty) "Starting" else p) + "…"
      case JobStatus.Failed    => s"Failed$at: " + job.failure.fold("unknown error")(_.message)
      case JobStatus.Cancelled => s"Cancelled$at"
      case JobStatus.Done      => "Done"
      case other               => other
    }
  }
}
