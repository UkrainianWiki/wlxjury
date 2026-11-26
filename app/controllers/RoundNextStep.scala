package controllers

import db.scalikejdbc.{Round, RoundImageJdbc}
import services.distribution.RoundStartBlock
import services.jobs.Job

/** The next step of a round being set up (#186, UI-path plan §0 row 7): saved, its matching files
  * being found or found, not yet distributed. Jurors can't see it yet. Shown on its Edit page and
  * its review page with three actions: "Change the settings", "Check the files" and "Distribute
  * to jurors", the last disabled with its reason ([[State.reasonKey]]) while the matching runs,
  * when no file matches, or when a re-apply didn't finish. The same states refuse Distribute
  * ([[RoundReviewController.distribute]], the jurors form), so the action is offered exactly when
  * it is allowed.
  */
final case class RoundNextStep(round: Round, state: RoundNextStep.State, block: RoundStartBlock) {

  /** "Distribute to jurors" is offered, enabled (the jurors form, then its Distribute). */
  def distributeAllowed: Boolean = state == RoundNextStep.Ready

  /** The round's setup step now (UI-path plan §4 step 1, the maintainer's decision of 2026-11-25):
    * [[RoundNextStep.CheckFiles]] while its matching files are being found, when none matched or a
    * re-apply didn't finish; [[RoundNextStep.Jurors]] once they are matched, when choosing the jurors
    * and distributing is what is left (offered exactly when [[distributeAllowed]]). Step 1, the
    * settings, is behind it: the round is saved.
    */
  def step: Int = if (distributeAllowed) RoundNextStep.Jurors else RoundNextStep.CheckFiles

  /** Its state in one line, the same on every page (the Rounds page, Edit round, statistics, the conflicts
    * page): "Setting up – step 2 of 3: check files" or "… step 3 of 3: choose jurors"; a re-apply that
    * failed or was cancelled, its block's own line ("… step 2 of 3: re-applying its filters failed").
    */
  def stateKey: String = state match {
    case RoundNextStep.Unfinished                 => block.stateKey
    case _ if step == RoundNextStep.Jurors        => "round.setup.state.3"
    case _                                        => "round.setup.state.2"
  }
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

  /** The step of `round` with its latest distribution job `job`, if it is being set up, derived from its
    * [[RoundStartBlock]] (the Rounds page's state, the one source), so that no two pages say different
    * things: `Selecting` (a fill held for review running) is [[Matching]]; `NotDistributed` (in review, its
    * distribute not running, also after a failed or cancelled distribute, which its Distribute does again)
    * [[Ready]], or [[NoFiles]] with no matching file; `ReapplyFailed` and `ReapplyCancelled` [[Unfinished]].
    * None for a round open or stopped, being distributed or whose job opens it when done (`Distributing`),
    * and one whose creation failed, was cancelled or didn't finish (`Failed`, `Cancelled`, `Incomplete`: its
    * Retry, "Distribute new files" or "Find the matching files again" is the way on, as the Rounds page
    * says). `inCount`: its matching files, read only when needed.
    */
  def of(round: Round, job: Option[Job], inCount: => Int): Option[RoundNextStep] =
    RoundStartBlock.of(round, job).flatMap { block =>
      val state = block match {
        case RoundStartBlock.Selecting                                     => Some(Matching)
        case RoundStartBlock.ReapplyFailed | RoundStartBlock.ReapplyCancelled => Some(Unfinished)
        case RoundStartBlock.NotDistributed                                => Some(if (inCount == 0) NoFiles else Ready)
        case _                                                             => None
      }
      state.map(RoundNextStep(round, _, block))
    }

  /** The round's files that matched (`in`). */
  def inCount(round: Round): Int = RoundImageJdbc.statusCounts(round.getId).getOrElse(RoundImageJdbc.Status.In, 0)
}
