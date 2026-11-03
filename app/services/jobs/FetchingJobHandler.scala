package services.jobs

import play.api.libs.json.JsValue

/** A [[JobHandler]] whose every step needs data from an external API: a page of a
  * Commons category's files, a few monument list pages. The call can't run in the step's
  * transaction (a slow API would hold the job's row lock and a connection), so each step
  * comes in two parts:
  *  - [[fetch]], outside any transaction, on the scheduler's fetch lane, for the cursor
  *    in the job's state;
  *  - [[write]], in the step's transaction, with what was fetched; its state carries the
  *    next cursor.
  *
  * The scheduler holds the step [[fetchStep]] returns until it runs, and runs it again if
  * it is retried after a deadlock. It writes the fetched data only if the locked job row
  * still has the cursor it was fetched for; otherwise it writes nothing and the next turn
  * fetches again. A crash between the fetch and the commit fetches the same cursor again,
  * so [[write]] must be idempotent.
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

  /** Fetches for the job's next step: the step, which writes what was fetched. */
  final def fetchStep(job: Job): FetchingJobHandler.Step = {
    val cursor = cursorOf(job)
    val fetched = fetch(job)
    (locked, ctx) =>
      if (cursorOf(locked) == cursor) write(locked, fetched, ctx)
      else StepResult.Continue(locked.state, JobScheduler.progressOf(locked))
  }

  /** The scheduler runs the step [[fetchStep]] returns instead. */
  final def step(job: Job, ctx: StepContext): StepResult =
    throw new UnsupportedOperationException(s"$kind: a step writes what fetchStep fetched")
}

object FetchingJobHandler {

  /** A step of a job, given its locked row. */
  type Step = (Job, StepContext) => StepResult
}
