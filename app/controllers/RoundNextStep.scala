package controllers

import db.scalikejdbc.{Round, RoundImageJdbc}
import services.distribution.RoundDistributionHandler
import services.jobs.Job

/** The next step of a round being set up (#186, UI-path plan §0 row 7): saved, its matching files
  * being found or found, not yet distributed. Jurors can't see it yet. Shown on its Edit page and
  * its review page with three actions: "Change the settings", "Check the files" and "Distribute
  * to jurors", the last disabled with its reason ([[State.reasonKey]]) while the matching runs,
  * when no file matches, or when a re-apply didn't finish. The same states refuse Distribute
  * ([[RoundReviewController.distribute]], the jurors form), so the action is offered exactly when
  * it is allowed.
  */
final case class RoundNextStep(round: Round, state: RoundNextStep.State) {

  /** "Distribute to jurors" is offered, enabled (the jurors form, then its Distribute). */
  def distributeAllowed: Boolean = state == RoundNextStep.Ready
}

object RoundNextStep {

  sealed abstract class State(val id: String, val reasonKey: Option[String])

  /** Its files are being matched (a fill or a re-apply runs, or the creation's hasn't started). */
  case object Matching extends State("matching", Some("round.next.matching"))

  /** The matching ended with no file. */
  case object NoFiles extends State("no-files", Some("round.next.no.files"))

  /** A re-apply failed or was cancelled: its files half chosen (the distribution refuses). */
  case object Unfinished extends State("unfinished", Some("round.review.reapply.unfinished"))

  /** Its files are matched: the jurors next. */
  case object Ready extends State("ready", None)

  /** The step of `round` with its latest distribution job `job`, if it is being set up; None for a
    * round distributed or being distributed to its jurors, or whose creation's matching failed
    * (its job's Retry is the way on). `inCount`: its matching files, read only when needed.
    */
  def of(round: Round, job: Option[Job], inCount: => Int): Option[RoundNextStep] = {
    val active = job.exists(_.isActive)
    val matching = job.exists(j => j.isActive && !RoundDistributionHandler.distributeMode(j)) ||
      (round.distributing && !round.inReview && job.forall(_.isActive) && !job.exists(RoundDistributionHandler.distributeMode))
    val state =
      if (!round.distributing) None
      else if (matching) Some(Matching)
      else if (!round.inReview || active) None
      else if (job.exists(j => RoundDistributionHandler.reapplyMode(j) && !j.isDone)) Some(Unfinished)
      else if (inCount == 0) Some(NoFiles)
      else Some(Ready)
    state.map(RoundNextStep(round, _))
  }

  /** The round's files that matched (`in`). */
  def inCount(round: Round): Int = RoundImageJdbc.statusCounts(round.getId).getOrElse(RoundImageJdbc.Status.In, 0)
}
