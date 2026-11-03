package controllers

import db.scalikejdbc.{Round, User}
import modules.BlockingExecutionContext
import play.api.mvc.{AnyContent, ControllerComponents, EssentialAction, Request, Result}
import scalikejdbc.DB
import services.distribution.RoundImageCheckHandler
import services.jobs.{Job, JobScheduler, NewJob}

import javax.inject.Inject

/** Queues `round_image_check` jobs ([[RoundImageCheckHandler]]): one round's, by an admin of
  * its contest (checked in that contest, which may be another of the user's than the
  * session's; the session stays as it is), or every round's and the criteria rates', by root.
  * POSTs with the CSRF token (the forms' `csrfField`); a check already queued or running is
  * not queued again (its dedupe key).
  */
class RoundImageCheckController @Inject() (
    cc: ControllerComponents,
    jobs: JobScheduler,
    blocking: BlockingExecutionContext
) extends Secured(cc) {

  /** "Check round images" on a round's edit page. */
  def checkRound(id: Long): EssentialAction =
    withAuthOn(blocking)(rolePermission(User.ADMIN_ROLES)) { user => implicit request =>
      Round.findById(id) match {
        case None =>
          NotFound(views.html.errorPage(Some(user), "error.title", "error.round.not.found")(messagesApi.preferred(request), request))
        case Some(round) if !contestPermission(User.ADMIN_ROLES, Some(round.contestId))(Secured.inContest(user, round.contestId)) =>
          onUnAuthorized(user, request, Secured.Denial.round(round, User.ADMIN_ROLES))
        case Some(round) =>
          queue(RoundImageCheckHandler.roundJob(round.getId, round.contestId, user),
            routes.RoundController.editRound(Some(round.getId), round.contestId).url)
      }
    }

  /** "Check every round's images" on the rounds page: root only. */
  def checkAll(): EssentialAction =
    withAuthOn(blocking)(rolePermission(Set(User.ROOT_ROLE))) { user => implicit request =>
      queue(RoundImageCheckHandler.allJob(user), routes.RoundController.rounds(None).url)
    }

  private def queue(job: NewJob, fallback: String)(implicit request: Request[AnyContent]): Result = {
    val messages = messagesApi.preferred(request)
    val back = Redirect(
      Secured.safeLocalPath(request.body.asFormUrlEncoded.flatMap(_.get("returnTo")).flatMap(_.headOption))
        .getOrElse(fallback))
    DB.localTx(implicit session => jobs.enqueue(job)) match {
      case Right(queued) =>
        jobs.started(queued.id)
        back.flashing("success" -> messages("round.images.check.queued", queued.id))
      case Left(active: Job) =>
        back.flashing("error" -> messages("round.images.check.running", active.id))
    }
  }
}
