package services.distribution

import db.scalikejdbc.Round
import services.jobs.{Job, JobJdbc}

/** Why a round can't be started yet: the Rounds page shows its state in place of Start, and
  * RoundController.setRound refuses a start with its own message (never one message for all:
  * a round waiting in review isn't "being distributed").
  *
  * @param reason     the state's name, in its message keys and the page's `data-state`
  * @param stateKey   the Rounds page's state text
  * @param refusedKey a refused start's message, with the round's number and name
  */
sealed abstract class RoundStartBlock(val reason: String, val stateKey: String, val refusedKey: String)

object RoundStartBlock {

  /** In review (P3) with no job: nobody has its images yet; its jurors form distributes it. */
  case object NotDistributed
      extends RoundStartBlock("not.distributed", "round.start.state.not.distributed", "round.start.refused.not.distributed")

  /** A job distributing it, which opens it to its jurors when it ends (RoundDistributionHandler.complete). */
  case object Distributing
      extends RoundStartBlock("distributing", "round.start.state.distributing", "round.still.distributing")

  /** A job selecting its images that then holds it in review: it doesn't start by itself. */
  case object Selecting
      extends RoundStartBlock("selecting", "round.start.state.selecting", "round.start.refused.selecting")

  /** Its creation cancelled (RoundDistributionHandler.onCancelled): no Retry for a cancelled job;
    * the round form's "Distribute new files" reinstates it (RoundService.distributeNewFiles).
    */
  case object Cancelled
      extends RoundStartBlock("cancelled", "round.start.state.cancelled", "round.start.refused.cancelled")

  /** Its creation's last job failed: its Retry, in the round's row (jobStatus). */
  case object Failed
      extends RoundStartBlock("failed", "round.start.state.failed", "round.start.refused.failed")

  /** Being created with no job running, none failed nor cancelled: the round form's "Distribute
    * new files" completes it.
    */
  case object Incomplete
      extends RoundStartBlock("incomplete", "round.start.state.incomplete", "round.start.refused.incomplete")

  val All: Seq[RoundStartBlock] = Seq(NotDistributed, Distributing, Selecting, Cancelled, Failed, Incomplete)

  /** Whether a job, once done, opens the round to its jurors: a distribute, a one-pass job from
    * before P3, or a fill that queues the distribute (not one queued from the review, which leaves
    * the round in review).
    */
  def opensWhenDone(job: Job): Boolean = {
    import RoundDistributionHandler._
    distributeMode(job) || modeOf(job).isEmpty || (fillOnly(job) && distributeAfter(job) && !fromReview(job))
  }

  /** The block of a round, None if it may be started (not `distributing`).
    *
    * @param latestJob the round's latest distribution job (the Rounds page's; any other kind is ignored)
    */
  def of(round: Round, latestJob: Option[Job]): Option[RoundStartBlock] =
    if (!round.distributing) None
    else {
      val job = latestJob.filter(_.kind == RoundDistributionHandler.Kind)
      job.filter(_.isActive) match {
        case Some(j) if opensWhenDone(j)                       => Some(Distributing)
        case Some(_)                                           => Some(Selecting)
        case None if round.inReview                            => Some(NotDistributed)
        case None if round.lifecycle == Round.Status.Cancelled => Some(Cancelled)
        case None if job.exists(_.isFailed)                    => Some(Failed)
        case None                                              => Some(Incomplete)
      }
    }

  /** [[of]] with the round's latest distribution job read now. */
  def of(round: Round): Option[RoundStartBlock] =
    if (!round.distributing) None
    else of(round, round.id.flatMap(id =>
      JobJdbc.latestBySubjects(RoundDistributionHandler.Kind, RoundDistributionHandler.SubjectType, Seq(id)).get(id)))
}
