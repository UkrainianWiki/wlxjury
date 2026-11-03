package controllers

import db.scalikejdbc.User
import modules.BlockingExecutionContext
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.{AnyContent, ControllerComponents, EssentialAction, Request, Result}
import services.jobs.{Job, JobJdbc, JobScheduler, JobStatus}

import javax.inject.Inject

/** Status, Cancel and Retry of background jobs (services.jobs), for any job kind: the
  * admins of the job's contest only (a job without a contest: root).
  */
class JobController @Inject() (cc: ControllerComponents, jobs: JobScheduler, blocking: BlockingExecutionContext)
    extends Secured(cc) {

  /** The job's status as JSON, polled by public/javascripts/job-status.js: a few primary
    * key lookups, so on Play's default dispatcher, not queued behind slow gallery pages on
    * the blocking one (measured: 494 ms mean there during a 10-admin run).
    */
  def status(id: Long): EssentialAction =
    withAuth(rolePermission(User.ADMIN_ROLES)) { user => _ =>
      withJob(user, id)(job => Ok(JobController.json(job, jobs)))
    }

  def cancel(id: Long): EssentialAction =
    withAuthOn(blocking)(rolePermission(User.ADMIN_ROLES)) { user => implicit request =>
      withJob(user, id) { job =>
        jobs.cancel(job.id)
        back(request, job)
      }
    }

  def retry(id: Long): EssentialAction =
    withAuthOn(blocking)(rolePermission(User.ADMIN_ROLES)) { user => implicit request =>
      withJob(user, id) { job =>
        jobs.retry(job.id) match {
          case Right(_)     => back(request, job)
          case Left(reason) => back(request, job).flashing("error" -> s"Can't retry: $reason")
        }
      }
    }

  private def withJob(user: User, id: Long)(f: Job => Result): Result =
    JobJdbc.findById(id) match {
      case None => NotFound(Json.obj("error" -> "job not found"))
      case Some(job) if contestPermission(User.ADMIN_ROLES, job.contestId)(user) => f(job)
      case Some(_) => onUnAuthorized(user)
    }

  /** Back to the page the button was on (a local path only), else the contest's rounds. */
  private def back(request: Request[AnyContent], job: Job): Result = {
    val returnTo = request.body.asFormUrlEncoded
      .flatMap(_.get("returnTo"))
      .flatMap(_.headOption)
      .filter(p => p.startsWith("/") && !p.startsWith("//"))
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
