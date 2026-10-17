package services.jobs

import play.api.libs.json.JsObject
import scalikejdbc.DBSession

import scala.collection.mutable

/** The transaction a step, a cancel or a retry runs in, with the job's row locked. Work
  * to do once it has committed (e.g. invalidating a cache) goes to [[afterCommit]].
  */
final class StepContext(val session: DBSession) {
  private[jobs] val afterCommitActions = mutable.Buffer.empty[() => Unit]
  def afterCommit(f: => Unit): Unit = afterCommitActions += (() => f)
}

/** The outcome of one step. Its state and progress are written in the step's own
  * transaction, with its work: a later step, a retry or another instance after a crash
  * continues from exactly what committed.
  */
sealed trait StepResult {
  def state: JsObject
  def progress: Progress
}

object StepResult {

  /** More to do: the scheduler gives the job another turn. */
  final case class Continue(state: JsObject, progress: Progress) extends StepResult

  /** Finished. */
  final case class Done(state: JsObject, progress: Progress) extends StepResult

  /** Finished with a failure the step detected itself (e.g. a count check). Its state and
    * the handler's [[JobHandler.onFailed]] are committed with it; a Retry resumes it.
    */
  final case class Failed(error: String, state: JsObject, progress: Progress) extends StepResult
}

/** Does the work of one kind of [[Job]], one bounded step at a time.
  *
  * A step should be short (a chunk: well under a second or two), so that jobs take turns
  * on the few worker threads, a cancel (which waits for the job's row lock) answers
  * quickly, and a failure loses at most one step. Anything slow that must not hold a
  * transaction open (calls to an external API) belongs in [[prepare]], or in its own
  * steps that record their progress per call.
  */
trait JobHandler {

  /** The `job.kind` this handler runs, e.g. "round_distribution". */
  def kind: String

  /** A short human description of the work, for the status display. */
  def title(job: Job): String = kind

  /** Kind-specific figures for the status display, e.g. {"imagesAdded": 1200}. */
  def details(job: Job): JsObject = JsObject.empty

  /** Runs before each step, outside any transaction: builds what the steps need and
    * isn't in the job's state (it is lost on a restart), e.g. data from an external API.
    * Cache it per job id; [[forget]] drops it.
    */
  def prepare(job: Job): Unit = ()

  /** [[prepare]] calls an external API before every step: the scheduler runs it on its
    * fetch lane (`jobs.fetchWorkers`), not on a worker, and gives the job its step once it
    * is done. See [[FetchingJobHandler]].
    */
  def fetchesExternally: Boolean = false

  /** One step of the job, in the transaction of `ctx` with the job's row locked. An
    * exception rolls the step back and fails the job (a transient database error, such
    * as a deadlock, is retried first).
    */
  def step(job: Job, ctx: StepContext): StepResult

  /** The job has failed: undo what must not outlast it (e.g. frozen rounds), in the
    * same transaction as the status change. Returns the job's new state.
    */
  def onFailed(job: Job, ctx: StepContext): JsObject = job.state

  /** The job is cancelled (it may have been queued, running or failed). Returns its new
    * state.
    */
  def onCancelled(job: Job, ctx: StepContext): JsObject = job.state

  /** A failed job is queued again (Retry): redo what [[onFailed]] undid. Returns its new
    * state; it resumes from it.
    */
  def onRetry(job: Job, ctx: StepContext): JsObject = job.state

  /** Drops what [[prepare]] cached for the job. */
  def forget(jobId: Long): Unit = ()
}
