package controllers

import db.scalikejdbc.User
import modules.BlockingExecutionContext
import play.api.mvc.{ControllerComponents, EssentialAction}
import services.imports.AuthorNormalizeHandler
import services.jobs.{Job, JobScheduler}

import javax.inject.Inject

/** Queues the `author_normalize` job ([[AuthorNormalizeHandler]]): "Normalize stored authors"
  * on the rounds page, root only (it writes every contest's files). A POST with the CSRF
  * token; a run already queued or running is not queued again (its dedupe key). Its status,
  * Cancel and Retry are [[JobController]]'s (a job of no contest: root only).
  */
class AuthorNormalizeController @Inject() (
    cc: ControllerComponents,
    jobs: JobScheduler,
    blocking: BlockingExecutionContext
) extends Secured(cc) {

  def start(): EssentialAction =
    withAuthOn(blocking)(rolePermission(Set(User.ROOT_ROLE))) { user => implicit request =>
      val messages = messagesApi.preferred(request)
      val back = Redirect(
        Secured.safeLocalPath(request.body.asFormUrlEncoded.flatMap(_.get("returnTo")).flatMap(_.headOption))
          .getOrElse(routes.RoundController.rounds(None).url))
      db.scalikejdbc.WriteTx.write(implicit session => jobs.enqueue(AuthorNormalizeHandler.newJob(user))) match {
        case Right(queued) =>
          jobs.started(queued.id)
          back.flashing("success" -> messages("authors.normalize.queued", queued.id))
        case Left(active: Job) =>
          back.flashing("error" -> messages("authors.normalize.running", active.id))
      }
    }
}
