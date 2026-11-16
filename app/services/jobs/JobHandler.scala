package services.jobs

import play.api.libs.json.JsObject
import scalikejdbc.DBSession

import scala.collection.mutable

/** The transaction a step, a cancel or a retry runs in, with the job's row locked. Work
  * to do once it has committed (e.g. invalidating a cache) goes to [[afterCommit]].
  */
/** @param by who acts, where the step is a user's action (a Cancel: [[JobScheduler.cancel]]); None for
  *           the scheduler's own steps
  */
final class StepContext(val session: DBSession, val by: Option[Long] = None) {
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
  /** @param followUp a job the scheduler queues in the same transaction, once this one is done (its
    *                 dedupe key freed): e.g. a round's distribution after its fill (P3, D4)
    * @param onFollowUpQueued run in that transaction once the follow-up is queued, never if it isn't
    *                 (another job holds its key): what holds only with it (a distribute's freeze)
    */
  final case class Done(state: JsObject, progress: Progress, followUp: Option[NewJob] = None,
      onFollowUpQueued: DBSession => Unit = _ => ()) extends StepResult

  /** Finished with a failure the step detected itself (e.g. a count check). Its state and
    * the handler's [[JobHandler.onFailed]] are committed with it; a Retry resumes it.
    */
  final case class Failed(error: JobError, state: JsObject, progress: Progress) extends StepResult
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

  /** Its steps are claimed before other kinds' ([[JobScheduler]]'s turns): a job that holds a
    * round paused for its jurors (`round_rebalance`) would otherwise take turns with imports and
    * distributions on the few workers. Its steps must be short.
    */
  def priority: Boolean = false

  /** A short human description of the work, for the status display. */
  def title(job: Job): String = kind

  /** Kind-specific figures for the status display, e.g. {"imagesAdded": 1200}. */
  def details(job: Job): JsObject = JsObject.empty

  /** Runs before each step, outside any transaction: builds what the steps need and
    * isn't in the job's state (it is lost on a restart). Cache it per job id; [[forget]]
    * drops it. A handler that calls an external API before every step is a
    * [[FetchingJobHandler]] instead: the scheduler fetches on its fetch lane
    * (`jobs.fetchWorkers`), not on a worker, and gives the job its step once it is done.
    */
  def prepare(job: Job): Unit = ()

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
