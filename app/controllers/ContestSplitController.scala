package controllers

import db.scalikejdbc.{ContestJuryJdbc, ContestSplitJdbc, User}
import modules.BlockingExecutionContext
import play.api.Configuration
import play.api.i18n.I18nSupport
import play.api.mvc.{ControllerComponents, EssentialAction, Result}
import scalikejdbc.{DB, DBSession}
import services.jobs.{JobJdbc, JobScheduler}
import services.series.{ContestSplit, ReuseReport, ReuseScanHandler}

import javax.inject.Inject

/** Root's split of a reused contest (contest series plan §4.4–§4.6; PR CY-4; CA9–CA13, CA23):
  * `/admin/contests/:id/split`, the confirmation (the contest's reuse report as an offer: each
  * cluster to move, to choose or to leave, each target's series, year and contest, the active rounds
  * to pause and the people to give the Admin role, none ticked), its POST (the plan recorded and its
  * job queued), and `/admin/splits/:id`, a split's progress, what it changed and Undo. Root only:
  * everyone else is denied (403, logged on `security.authz`); signed out, the login page. Every POST
  * carries the CSRF token and answers with a redirect and a flash. On the blocking pool: the offer
  * reads the contest's rounds' files' categories.
  */
class ContestSplitController @Inject() (cc: ControllerComponents, blocking: BlockingExecutionContext, jobs: JobScheduler,
    config: Configuration)
    extends Secured(cc)
    with I18nSupport {

  private val root = rolePermission(Set(User.ROOT_ROLE))
  private val settings = ReuseReport.Settings(config)

  private def proposal(contest: org.intracer.wmua.ContestJury)(implicit session: DBSession) = {
    val catalog = ReuseReport.Catalog.load()
    val report = ReuseReport.report(contest, ReuseScanHandler.latestEras(), settings, Some(catalog))
    (ContestSplit.propose(report, catalog), catalog)
  }

  private def notFound(user: User)(implicit request: play.api.mvc.RequestHeader): Result =
    NotFound(views.html.errorPage(Some(user), "error.title", "error.not.found"))

  def confirmPage(contestId: Long): EssentialAction = withAuthOn(blocking)(root) { user => implicit request =>
    ContestJuryJdbc.findById(contestId).fold(notFound(user)) { contest =>
      val (offer, catalog, splits) = DB.readOnly { implicit s =>
        val (offer, catalog) = proposal(contest)
        (offer, catalog, ContestSplitJdbc.findByContest(contestId))
      }
      val names = catalog.contests.flatMap(c => c.id.map(_ -> c.fullName)).toMap
      Ok(views.html.series.splitConfirm(user, offer, splits, names)).withHeaders(CACHE_CONTROL -> "no-store")
    }
  }

  def confirm(contestId: Long): EssentialAction = withAuthOn(blocking)(root) { user => implicit request =>
    ContestJuryJdbc.findById(contestId).fold(notFound(user)) { contest =>
      val form = request.body.asFormUrlEncoded.getOrElse(Map.empty)
      val messages = messagesApi.preferred(request)
      val planned = DB.readOnly { implicit s =>
        val (offer, catalog) = proposal(contest)
        ContestSplit.plan(offer, form, catalog)
      }
      val dump = form.get("dump").flatMap(_.headOption)
      planned.flatMap(plan => ContestSplit.confirm(plan, dump, user, jobs)) match {
        case Left((key, args)) =>
          Redirect(routes.ContestSplitController.confirmPage(contestId)).flashing("error" -> messages(key, args: _*))
        case Right((splitId, job)) =>
          jobs.started(job.id)
          Redirect(routes.ContestSplitController.split(splitId)).flashing("success" -> messages("split.queued", splitId.toString))
      }
    }
  }

  def split(splitId: Long): EssentialAction = withAuthOn(blocking)(root) { user => implicit request =>
    ContestSplitJdbc.findById(splitId).fold(notFound(user)) { s =>
      val (items, job, refusal) = DB.readOnly { implicit session =>
        (ContestSplitJdbc.items(splitId), s.jobId.flatMap(JobJdbc.findById(_)), ContestSplit.undoRefusal(s))
      }
      val people = User.findByIds((items.filter(i => i.kind == ContestSplitJdbc.Kind.Membership ||
        i.kind == ContestSplitJdbc.Kind.Skipped).flatMap(_.subjectId) :+ s.createdBy).distinct)
        .map(u => u.getId -> u.fullname).toMap
      val contestIds = (s.contestId +: items.flatMap(_.contestId)).distinct
      val contests = ContestJuryJdbc.findAllBy(scalikejdbc.sqls.in(ContestJuryJdbc.defaultAlias.id, contestIds))
        .map(c => c.getId -> c).toMap
      Ok(views.html.series.splitPage(user, s, ContestSplit.planOf(s), items, job, refusal, people, contests))
        .withHeaders(CACHE_CONTROL -> "no-store")
    }
  }

  def undo(splitId: Long): EssentialAction = withAuthOn(blocking)(root) { user => implicit request =>
    ContestSplitJdbc.findById(splitId).fold(notFound(user)) { _ =>
      val messages = messagesApi.preferred(request)
      val back = Redirect(routes.ContestSplitController.split(splitId))
      ContestSplit.undo(splitId, user) match {
        case Left((key, args)) => back.flashing("error" -> messages(key, args: _*))
        case Right(u) =>
          back.flashing("success" -> messages("split.undo.done", u.rounds, u.removed, u.keptMembers.size, u.retired.size))
      }
    }
  }
}
