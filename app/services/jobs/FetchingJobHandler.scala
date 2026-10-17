package services.jobs

import play.api.libs.json.JsValue

import scala.collection.concurrent.TrieMap

/** A [[JobHandler]] whose every step needs data from an external API: a page of a
  * Commons category's files, a few monument list pages. The call can't run in the step's
  * transaction (a slow API would hold the job's row lock and a connection), so each step
  * comes in two parts:
  *  - [[fetch]], outside any transaction, on the scheduler's fetch lane, for the cursor
  *    in the job's state;
  *  - [[write]], in the step's transaction, with what was fetched; its state carries the
  *    next cursor.
  *
  * A step writes the fetched data only if the locked job row still has the cursor it was
  * fetched for (a retry may have moved it); otherwise it writes nothing and the next turn
  * fetches again. A crash between the fetch and the commit fetches the same cursor again,
  * so [[write]] must be idempotent.
  *
  * Held in memory per job: what one fetch returned, until its step commits.
  */
trait FetchingJobHandler[A] extends JobHandler {

  /** The part of the job's state a fetch depends on, e.g. the phase and the API's continue
    * token.
    */
  def cursorOf(job: Job): JsValue

  /** Fetches what the next step writes, outside any transaction. */
  def fetch(job: Job): A

  /** Writes what [[fetch]] got, in the step's transaction with the job's row locked. */
  def write(job: Job, fetched: A, ctx: StepContext): StepResult

  private val fetched = TrieMap.empty[Long, (JsValue, A)]

  final override def fetchesExternally: Boolean = true

  final override def prepare(job: Job): Unit = {
    val cursor = cursorOf(job)
    if (!fetched.get(job.id).exists(_._1 == cursor)) fetched.put(job.id, cursor -> fetch(job))
  }

  final def step(job: Job, ctx: StepContext): StepResult =
    fetched.get(job.id).filter(_._1 == cursorOf(job)) match {
      case Some(entry @ (_, data)) =>
        // kept until the step commits: a step retried after a deadlock writes it again
        ctx.afterCommit(fetched.remove(job.id, entry))
        write(job, data, ctx)
      case None =>
        StepResult.Continue(job.state, JobScheduler.progressOf(job))
    }

  override def forget(jobId: Long): Unit = fetched.remove(jobId)
}
