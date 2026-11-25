package controllers

import db.scalikejdbc.Round
import play.api.data.Form
import services.RoundService
import services.distribution.{RoundDistributionHandler, RoundRebalanceHandler}
import services.jobs.{Job, JobJdbc}

/** A round form refused because it has to wait for another round's job (UI-path plan §5 Q7, the
  * maintainer's decision of 2026-11-25): a round to leave out is still being distributed
  * (`error.exclude.rounds.busy`), or a previous round has a juror being added or a juror's files
  * reassigned (`error.previous.round.rebalancing`). The save stays refused; the form, kept as
  * entered, shows that job's live progress at the top (round/edit/waitBox.scala.html, polled by
  * round-form-wait.js from [[JobController.status]], the jobs' JSON for their contest's admins), and
  * says when it has finished, Save enabled again.
  *
  * @param round       the round whose job the save waits for
  * @param job         that job, as read when the form was refused
  * @param rebalancing a juror add or reassign of a previous round (else a distribution of a round to leave out)
  */
final case class RoundFormWait(round: Round, job: Job, rebalancing: Boolean)

object RoundFormWait {

  /** The refusals that are waits for another round's job. */
  val Keys: Set[String] = Set(RoundDistributionHandler.ExcludeRoundsBusy, RoundDistributionHandler.PreviousRebalancing)

  /** The job a refused form of contest `contestId` waits for, if one of its errors is a wait: the
    * first of the posted rounds to leave out that is being distributed, with its latest
    * distribution job; else the first posted previous round with a juror add or reassign queued or
    * running. None if the job can't be found (finished meanwhile: the next save may pass).
    */
  def of(form: Form[_], contestId: Long, waitKey: Option[String] = None): Option[RoundFormWait] = {
    // `waitKey`: a wait named apart from the form's errors (a saved edit's refused distribution,
    // whose error is "Could not distribute new files: ...")
    val keys = form.errors.map(FormRefusal.messageKey).toSet ++ waitKey
    if (!keys.exists(Keys)) None
    else {
      def ids(field: String): Seq[Long] =
        form.data.toSeq.sortBy(_._1).collect { case (k, v) if k == field || k.startsWith(field + "[") => v }
          .flatMap(_.trim.toLongOption).distinct
      lazy val rounds = Round.findByContest(contestId)
      def roundOf(id: Long) = rounds.find(_.id.contains(id))
      def busy = Option.when(keys(RoundDistributionHandler.ExcludeRoundsBusy))(
        ids("excludeRounds").flatMap(roundOf).filter(RoundDistributionHandler.distributingNow)
          .flatMap(r => RoundService.latestJob(r).map(RoundFormWait(r, _, rebalancing = false))).headOption).flatten
      def rebalancing = Option.when(keys(RoundDistributionHandler.PreviousRebalancing))(
        ids("previousRound").flatMap(id => JobJdbc.activeByDedupeKey(RoundDistributionHandler.dedupeKey(id))
          .filter(_.kind == RoundRebalanceHandler.Kind).flatMap(j => roundOf(id).map(RoundFormWait(_, j, rebalancing = true))))
          .headOption).flatten
      busy.orElse(rebalancing)
    }
  }
}
