package controllers

import db.scalikejdbc.User
import modules.BlockingExecutionContext
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.{AnyContent, ControllerComponents, EssentialAction, Request, RequestHeader, Result}
import services.jobs.{Job, JobJdbc, JobScheduler, JobStatus}

import javax.inject.Inject

/** Status, Cancel and Retry of background jobs (services.jobs), for any job kind: the
  * admins of the job's contest only (a job without a contest: root).
  */
class JobController @Inject() (cc: ControllerComponents, jobs: JobScheduler, blocking: BlockingExecutionContext)
    extends Secured(cc) {

  /** The job's status as JSON, polled by public/javascripts/job-status.js: a few primary
    * key lookups, so on Play's default dispatcher, not queued behind slow gallery pages on
    * the blocking one (measured: 494 ms mean there during a 10-admin run). Errors are JSON
    * too, and the poll never switches the session's contest.
    */
  def status(id: Long): EssentialAction =
    Action { request =>
      userFromRequest(request).fold[Result](Unauthorized(Json.obj("error" -> "unauthenticated"))) { user =>
        withJob(user, id, request, json = true)(job => Ok(JobController.json(job, jobs)))
      }
    }

  def cancel(id: Long): EssentialAction =
    withAuthOn(blocking)(rolePermission(User.ADMIN_ROLES)) { user => implicit request =>
      withJob(user, id, request) { job =>
        jobs.cancel(job.id)
        back(request, job)
      }
    }

  def retry(id: Long): EssentialAction =
    withAuthOn(blocking)(rolePermission(User.ADMIN_ROLES)) { user => implicit request =>
      withJob(user, id, request) { job =>
        jobs.retry(job.id) match {
          case Right(_)     => back(request, job)
          case Left(reason) => back(request, job).flashing("error" -> s"Can't retry: $reason")
        }
      }
    }

  /** Runs `f` with the job if `user` administers its contest, checked in that contest (one
    * of the user's others than the session's, too); the session's contest stays as it is.
    * A job of a contest the user does not administer is answered exactly as a missing one,
    * so that its id says nothing of it; the denial is still logged ([[Secured.logDenial]]).
    */
  private def withJob(user: User, id: Long, request: RequestHeader, json: Boolean = false)(f: Job => Result): Result =
    JobJdbc.findById(id) match {
      case Some(job) if adminOf(user, job.contestId) => f(job)
      case Some(job) =>
        Secured.logDenial(user, request, Secured.Denial(job.contestId, Some(s"job:${job.id}"), User.ADMIN_ROLES))
        notFound(user, request, json)
      case None => notFound(user, request, json)
    }

  /** No such job: JSON for the status poll, else the error page. */
  private def notFound(user: User, request: RequestHeader, json: Boolean): Result =
    if (json) NotFound(Json.obj("error" -> "job not found"))
    else NotFound(views.html.errorPage(Some(user), "error.title", "error.not.found")(cc.messagesApi.preferred(request), request))

  private def adminOf(user: User, contestId: Option[Long]): Boolean =
    contestPermission(User.ADMIN_ROLES, contestId)(contestId.fold(user)(Secured.inContest(user, _)))

  /** Back to the page the button was on (a local path only), else the contest's rounds. */
  private def back(request: Request[AnyContent], job: Job): Result = {
    val returnTo = Secured.safeLocalPath(
      request.body.asFormUrlEncoded.flatMap(_.get("returnTo")).flatMap(_.headOption)
    )
    Redirect(returnTo.getOrElse(routes.RoundController.rounds(job.contestId).url))
  }
}

object JobController {

  def json(job: Job, jobs: JobScheduler): JsObject =
    Json.obj(
      "id" -> job.id,
      "kind" -> job.kind,
      "title" -> jobs.handler(job.kind).fold(job.kind)(_.title(job)),
      "status" -> job.status,
      "active" -> job.isActive,
      "progressDone" -> job.progressDone,
      "progressTotal" -> job.progressTotal,
      "progressUnit" -> job.progressUnit,
      "error" -> job.error,
      "queuePosition" -> (if (job.status == JobStatus.Queued) Some(JobJdbc.queuePosition(job.id)) else None),
      "subjectType" -> job.subjectType,
      "subjectId" -> job.subjectId,
      "createdAt" -> job.createdAt.toString,
      "startedAt" -> job.startedAt.map(_.toString),
      "finishedAt" -> job.finishedAt.map(_.toString),
      "details" -> jobs.handler(job.kind).fold(JsObject.empty)(_.details(job))
    )
}
