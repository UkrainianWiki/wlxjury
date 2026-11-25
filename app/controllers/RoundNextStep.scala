package controllers

import db.scalikejdbc.{Round, RoundImageJdbc}
import services.distribution.{RoundDistributionHandler, RoundStartBlock}
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

  /** The round's setup step now (UI-path plan §4 step 1, the maintainer's decision of 2026-11-25):
    * [[RoundNextStep.CheckFiles]] while its matching files are being found, when none matched or a
    * re-apply didn't finish; [[RoundNextStep.Jurors]] once they are matched, when choosing the jurors
    * and distributing is what is left (offered exactly when [[distributeAllowed]]). Step 1, the
    * settings, is behind it: the round is saved.
    */
  def step: Int = if (distributeAllowed) RoundNextStep.Jurors else RoundNextStep.CheckFiles
}

object RoundNextStep {

  /** The setup steps (the step bar, round/setupSteps.scala.html): the settings form, the review
    * page, the jurors form; then the round is open to its jurors.
    */
  val WhichFiles = 1
  val CheckFiles = 2
  val Jurors = 3
  val Steps = 3

  sealed abstract class State(val id: String, val reasonKey: Option[String])

  /** Its files are being matched: a fill held for review (a creation's, a re-apply's) runs. */
  case object Matching extends State("matching", Some("round.next.matching"))

  /** The matching ended with no file. */
  case object NoFiles extends State("no-files", Some("round.next.no.files"))

  /** A re-apply failed or was cancelled: its files half chosen (the distribution refuses). */
  case object Unfinished extends State("unfinished", Some("round.review.reapply.unfinished"))

  /** Its files are matched: the jurors next. */
  case object Ready extends State("ready", None)

  /** The step of `round` with its latest distribution job `job`, if it is being set up; None for a
    * round distributed or being distributed to its jurors, one whose job opens it to them when done
    * (a distribute, a one-pass job from before P3, a creation's fill that queues the distribute:
    * [[RoundStartBlock.opensWhenDone]], "being distributed" on the Rounds page), and one whose
    * creation failed, was cancelled or didn't finish with no job running (its Retry, "Distribute new
    * files" or "Find the matching files again" is the way on, as the Rounds page says). So this
    * state and the Rounds page's ([[RoundStartBlock]]: `Selecting` is [[Matching]], `NotDistributed`
    * a step) never contradict each other. `inCount`: its matching files, read only when needed.
    */
  def of(round: Round, job: Option[Job], inCount: => Int): Option[RoundNextStep] = {
    val state =
      if (!round.distributing) None
      else if (job.exists(j => j.isActive && RoundStartBlock.opensWhenDone(j))) None
      // a fill held for review (a creation's, a re-apply's) running
      else if (job.exists(_.isActive)) Some(Matching)
      else if (!round.inReview) None
      else if (job.exists(j => RoundDistributionHandler.reapplyMode(j) && !j.isDone)) Some(Unfinished)
      else if (inCount == 0) Some(NoFiles)
      else Some(Ready)
    state.map(RoundNextStep(round, _))
  }

  /** The round's files that matched (`in`). */
  def inCount(round: Round): Int = RoundImageJdbc.statusCounts(round.getId).getOrElse(RoundImageJdbc.Status.In, 0)
}
