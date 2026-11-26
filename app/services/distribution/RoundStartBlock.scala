package services.distribution

import db.scalikejdbc.Round
import services.jobs.{Job, JobJdbc, JobStatus}

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
    * the round form's "Distribute ? new files" reinstates it (RoundService.distributeNewFiles).
    */
  case object Cancelled
      extends RoundStartBlock("cancelled", "round.start.state.cancelled", "round.start.refused.cancelled")

  /** Its creation's last job failed: its Retry, in the round's row (jobStatus). */
  case object Failed
      extends RoundStartBlock("failed", "round.start.state.failed", "round.start.refused.failed")

  /** Held in review, its filters' re-apply (P3-5) failed: its files only partly chosen, and its jurors form
    * refuses to distribute it ([[services.RoundService.ReapplyUnfinishedKey]]) until its job is retried (in
    * its row) or the filters are re-applied again. Step 2 of its setup (controllers.RoundNextStep.Unfinished).
    */
  case object ReapplyFailed
      extends RoundStartBlock("reapply.failed", "round.start.state.reapply.failed", "round.start.refused.reapply.failed")

  /** Held in review, its filters' re-apply (P3-5) cancelled: its files only partly chosen, and its jurors form
    * refuses to distribute it ([[services.RoundService.ReapplyUnfinishedKey]]) until the filters are re-applied
    * again. No Retry for a cancelled job. Step 2 of its setup (controllers.RoundNextStep.Unfinished).
    */
  case object ReapplyCancelled
      extends RoundStartBlock("reapply.cancelled", "round.start.state.reapply.cancelled", "round.start.refused.reapply.cancelled")

  /** Being created with no job running, none failed nor cancelled: the round form's "Distribute
    * ? new files" completes it.
    */
  case object Incomplete
      extends RoundStartBlock("incomplete", "round.start.state.incomplete", "round.start.refused.incomplete")

  val All: Seq[RoundStartBlock] =
    Seq(NotDistributed, Distributing, Selecting, Cancelled, Failed, ReapplyFailed, ReapplyCancelled, Incomplete)

  /** Whether a job, once done, opens the round to its jurors: a distribute, a one-pass job from
    * before P3, or a fill that queues the distribute (not one queued from the review, which leaves
    * the round in review).
    */
  def opensWhenDone(job: Job): Boolean = {
    import RoundDistributionHandler._
    distributeMode(job) || modeOf(job).isEmpty || (fillOnly(job) && distributeAfter(job) && !fromReview(job))
  }

  /** The block of a round, None if it may be started (not `distributing`): the one source of a round's
    * state, which the Rounds page shows, a refused start says, and controllers.RoundNextStep (the setup step
    * on the round's other pages) is derived from. A round in review whose latest job is a re-apply (P3-5) that
    * failed is [[ReapplyFailed]] (its Retry in its row) and one that was cancelled [[ReapplyCancelled]]: its
    * jurors form refuses to distribute either (RoundService.reviewJury). Any other failed or cancelled job of a
    * round in review (its distribute, a fill queued from the review) leaves it [[NotDistributed]]: the review's
    * Distribute distributes it again (RoundService: "retried … by the review's Distribute"); a failed one's
    * Retry is in its row too.
    *
    * @param latestJob the round's latest distribution job (the Rounds page's; any other kind is ignored)
    */
  def of(round: Round, latestJob: Option[Job]): Option[RoundStartBlock] =
    if (!round.distributing) None
    else {
      val job = latestJob.filter(_.kind == RoundDistributionHandler.Kind)
      def reapply(status: String) = job.exists(j => j.status == status && RoundDistributionHandler.reapplyMode(j))
      job.filter(_.isActive) match {
        case Some(j) if opensWhenDone(j)                       => Some(Distributing)
        case Some(_)                                           => Some(Selecting)
        case None if round.lifecycle == Round.Status.Cancelled => Some(Cancelled)
        case None if round.inReview && reapply(JobStatus.Failed)    => Some(ReapplyFailed)
        case None if round.inReview && reapply(JobStatus.Cancelled) => Some(ReapplyCancelled)
        case None if round.inReview                            => Some(NotDistributed)
        case None if job.exists(_.isFailed)                    => Some(Failed)
        case None                                              => Some(Incomplete)
      }
    }

  /** [[of]] with the round's latest job read now, as the Rounds page reads it ([[latestJobs]]). */
  def of(round: Round): Option[RoundStartBlock] =
    if (!round.distributing) None
    else of(round, round.id.flatMap(id => latestJobs(Seq(id)).get(id)))

  /** The latest of each round's distribution and juror-add jobs (they share the round's key): the
    * Rounds page's jobs column and states, and a refused start's reason, from one source.
    */
  def latestJobs(roundIds: Seq[Long]): Map[Long, Job] = {
    def latest(kind: String) = JobJdbc.latestBySubjects(kind, RoundDistributionHandler.SubjectType, roundIds)
    val distributions = latest(RoundDistributionHandler.Kind)
    val rebalances = latest(RoundRebalanceHandler.Kind)
    (distributions.keySet ++ rebalances.keySet).map { id =>
      id -> (distributions.get(id).toSeq ++ rebalances.get(id)).maxBy(_.id)
    }.toMap
  }

  /** A refused start's message key: a cancelled or unfinished creation is finished by the round form's
    * "Distribute ? new files", which is read only while the round has no jurors (editRound.scala.html);
    * and a round not in review gets none (its jurors form and a juror's add choose them in review only).
    * Without jurors, one meant to be held in review (`reviewFirst`) has "Find the matching files again"
    * in its row ([[mayFindFilesAgain]]); one meant to be distributed after its fill can't be finished
    * (it needs jurors, which a round outside review doesn't get): a new round is set up instead.
    */
  def refusedKey(block: RoundStartBlock, hasJurors: => Boolean, reviewFirst: => Boolean): String = block match {
    case Cancelled | Incomplete if !hasJurors =>
      if (reviewFirst) block.refusedKey + ".no.jurors" else block.refusedKey + ".no.jurors.new"
    case _ => block.refusedKey
  }

  /** Whether a creation's fill is held in review when it ends ("Review images first"), by its latest
    * distribution job, as [[services.RoundService.distributeNewFiles]] decides it
    * ([[services.RoundService.creationIntent]] false). A job from before P3 (no mode) distributed in one
    * pass: not review first, as that path takes it too (it would need jurors).
    */
  def reviewFirst(latestJob: Option[Job]): Boolean =
    !services.RoundService.creationIntent(latestJob.filter(_.kind == RoundDistributionHandler.Kind))

  /** "Find the matching files again" (issue #194): a round whose setup was cancelled or didn't finish
    * ([[Cancelled]], [[Incomplete]]), meant to be held in review after its fill ([[reviewFirst]]), with
    * no jurors. Its round form's "Distribute ? new files" is read only without jurors, but a fill held in
    * review needs none: [[services.RoundService.distributeNewFiles]] fills it again and holds it in
    * review (step 2, checking the matching files), as it reinstates a cancelled creation. The Rounds
    * page offers it, and RoundController.findFilesAgain allows it, exactly then.
    *
    * @param latestJob the round's latest distribution job
    * @param hasJurors the round has jurors (`User.findRoundJurors`, as the round form counts them)
    */
  def mayFindFilesAgain(round: Round, latestJob: Option[Job], hasJurors: => Boolean): Boolean =
    of(round, latestJob).exists(b => b == Cancelled || b == Incomplete) && reviewFirst(latestJob) && !hasJurors

  /** [[mayFindFilesAgain]] with the round's latest distribution job and jurors read now. */
  def mayFindFilesAgain(round: Round): Boolean =
    round.id.exists { id =>
      mayFindFilesAgain(round, JobJdbc.latestBySubjects(RoundDistributionHandler.Kind, RoundDistributionHandler.SubjectType,
        Seq(id)).get(id), db.scalikejdbc.User.findRoundJurors(id).nonEmpty)
    }
}
