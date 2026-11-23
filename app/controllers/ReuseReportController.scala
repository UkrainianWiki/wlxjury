package controllers

import db.scalikejdbc.{ContestJuryJdbc, SeriesJdbc, User}
import modules.BlockingExecutionContext
import play.api.Configuration
import play.api.i18n.I18nSupport
import play.api.mvc.{ControllerComponents, EssentialAction, Result}
import scalikejdbc.DB
import services.jobs.{Job, JobScheduler}
import services.series.{ReuseReport, ReuseScanHandler}

import javax.inject.Inject

/** Root's reuse dry-run report (contest series plan §4.3, CY-0): `/admin/contests/reuse`, the
  * latest finished `contest_reuse_scan` job's results (cached in its state) with its totals and the
  * contests it reports, the latest scan's progress, and "Scan every contest" (a POST with the CSRF
  * token, queuing the job; one at a time); and one contest's report, `/admin/contests/:id/reuse`,
  * read now (one contest: bounded) with the latest finished scan's eras. The pages read in
  * `DB.readOnly` sessions; the scan's only write is its own job row. Nothing is moved, whatever it
  * proposes (CY-4). Root only: everyone else is denied (403, logged on `security.authz`); signed
  * out, the login page. On the blocking pool: a contest's report reads its rounds' files' categories.
  */
class ReuseReportController @Inject() (cc: ControllerComponents, blocking: BlockingExecutionContext, jobs: JobScheduler,
    config: Configuration)
    extends Secured(cc)
    with I18nSupport {

  private val root = rolePermission(Set(User.ROOT_ROLE))
  private val settings = ReuseReport.Settings(config)

  def list(): EssentialAction = withAuthOn(blocking)(root) { user => implicit request =>
    val (scan, done, series) = DB.readOnly { implicit s =>
      (ReuseScanHandler.latest(), ReuseScanHandler.latestDone(), SeriesJdbc.findAll().map(x => x.id -> x.name).toMap)
    }
    Ok(views.html.series.reuseReport(user, scan, done, done.map(ReuseScanHandler.stateOf), series))
      .withHeaders(CACHE_CONTROL -> "no-store")
  }

  def scan(): EssentialAction = withAuthOn(blocking)(root) { user => implicit request =>
    val back = Redirect(routes.ReuseReportController.list())
    db.scalikejdbc.WriteTx.write(implicit session => jobs.enqueue(ReuseScanHandler.newJob(user))) match {
      case Right(queued) =>
        jobs.started(queued.id)
        back.flashing("success" -> messagesApi.preferred(request)("reuse.scan.queued", queued.id))
      case Left(active: Job) =>
        back.flashing("error" -> messagesApi.preferred(request)("reuse.scan.running", active.id))
    }
  }

  def contest(contestId: Long): EssentialAction = withAuthOn(blocking)(root) { user => implicit request =>
    ContestJuryJdbc.findById(contestId).fold[Result](
      NotFound(views.html.errorPage(Some(user), "error.title", "error.contest.not.found"))
    ) { contest =>
      val (report, series) = DB.readOnly { implicit s =>
        (ReuseReport.report(contest, ReuseScanHandler.latestEras(), settings), SeriesJdbc.findAll().map(x => x.id -> x.name).toMap)
      }
      Ok(views.html.series.reuseContest(user, report, series)).withHeaders(CACHE_CONTROL -> "no-store")
    }
  }
}
