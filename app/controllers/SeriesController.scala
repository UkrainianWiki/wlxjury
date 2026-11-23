package controllers

import db.scalikejdbc.{ContestChangeJdbc, ContestJuryJdbc, SeriesJdbc, User}
import modules.BlockingExecutionContext
import org.scalawiki.wlx.dto.ContestType
import play.api.Configuration
import play.api.i18n.I18nSupport
import play.api.mvc.{AnyContent, ControllerComponents, EssentialAction, Request, Result}
import services.series.{ContestReuse, ContestSeries, SeriesIdentity, SeriesProposals}

import javax.inject.Inject

/** Root's contest series pages (contest series plan §3.1, §3.5, §5.3; PR CY-1): `/admin/series`
  * (the series, with create, rename, retire and un-retire; the bulk assignments with Undo), the
  * proposals (`/admin/series/assign`, confirmed in bulk), a series' page, and a contest's series
  * page (assign, move, unassign, retire, un-retire, its change log). Root only: everyone else is
  * denied (403, logged on `security.authz`); signed out, the login page or a JSON 401. Every POST
  * carries the CSRF token; each answers with a redirect and a flash naming the outcome.
  *
  * CY-2 adds series admins' grants here; the affected-grants list of an assignment
  * ([[affectedGrants]]) is empty until then.
  */
class SeriesController @Inject() (cc: ControllerComponents, blocking: BlockingExecutionContext, config: Configuration)
    extends Secured(cc)
    with I18nSupport {

  private val root = rolePermission(Set(User.ROOT_ROLE))
  private val reuseSettings = ContestReuse.Settings(config)

  /** The flash keys: a done outcome's message key, a refusal's, and its contest. */
  val DoneFlash = "series-done"
  val ErrorFlash = "series-error"
  val ErrorContestFlash = "series-error-contest"

  private def form(request: Request[AnyContent]): Map[String, Seq[String]] =
    request.body.asFormUrlEncoded.getOrElse(Map.empty)

  private def field(request: Request[AnyContent], name: String): Option[String] =
    form(request).get(name).flatMap(_.headOption).map(_.trim).filter(_.nonEmpty)

  private def outcome(to: play.api.mvc.Call, result: Either[String, _], done: String): Result =
    result.fold(key => Redirect(to).flashing(ErrorFlash -> key), _ => Redirect(to).flashing(DoneFlash -> done))

  /** CY-2: the series admins who gain or lose reach by an assignment or move (§3.5). None before
    * there are grants (no `series_admin` table yet).
    */
  def affectedGrants(seriesId: Option[Long], contestIds: Seq[Long]): Seq[String] = Nil

  // ── /admin/series ─────────────────────────────────────────────────────────

  def list(): EssentialAction = withAuth(root) { user => implicit request =>
    val series = SeriesJdbc.findAll()
    val contests = ContestJuryJdbc.findAll()
    val batches = ContestChangeJdbc.recentBatches(20)
    val names = User.findByIds((batches.map(_.actorId) ++ series.flatMap(_.createdBy)).distinct).map(u => u.getId -> u.fullname).toMap
    Ok(views.html.series.adminSeries(user, series, contests.groupBy(_.seriesId.getOrElse(0L)), batches, names,
      (ContestType.all.map(SeriesIdentity.of) ++ series.flatMap(_.contestType).map(SeriesIdentity.typeOfCode)).distinctBy(_.code),
      contests.count(c => c.seriesId.isEmpty && !c.retired)))
      .withHeaders(CACHE_CONTROL -> "no-store")
  }

  def create(): EssentialAction = withAuth(root) { user => request =>
    val result = ContestSeries.createSeriesTx(field(request, "name").getOrElse(""), field(request, "contestType"),
      field(request, "country"), field(request, "campaign"), user.getId)
    result.fold(key => Redirect(routes.SeriesController.list()).flashing(ErrorFlash -> key),
      id => Redirect(routes.SeriesController.series(id)).flashing(DoneFlash -> "series.created"))
  }

  def series(id: Long): EssentialAction = withAuth(root) { user => implicit request =>
    SeriesJdbc.findById(id).fold[Result](
      NotFound(views.html.errorPage(Some(user), "error.title", "series.not.found"))
    ) { s =>
      val contests = ContestSeries.contestsOf(id)
      val changes = ContestChangeJdbc.findBySeries(id, 50)
      val names = User.findByIds((changes.map(_.actorId) ++ s.createdBy.toSeq).distinct).map(u => u.getId -> u.fullname).toMap
      Ok(views.html.series.seriesPage(user, s, contests, ContestSeries.previousIn(contests, contests), changes, names))
        .withHeaders(CACHE_CONTROL -> "no-store")
    }
  }

  def rename(id: Long): EssentialAction = withAuth(root) { user => request =>
    outcome(routes.SeriesController.series(id), ContestSeries.rename(id, field(request, "name").getOrElse(""), user.getId),
      "series.renamed")
  }

  def retire(id: Long): EssentialAction = withAuth(root) { user => request =>
    outcome(routes.SeriesController.series(id), ContestSeries.retireSeries(id, user.getId, field(request, "comment")),
      "series.retired.done")
  }

  def unretire(id: Long): EssentialAction = withAuth(root) { user => request =>
    outcome(routes.SeriesController.series(id), ContestSeries.unretireSeries(id, user.getId, field(request, "comment")),
      "series.unretired.done")
  }

  // ── proposals ─────────────────────────────────────────────────────────────

  /** The proposals as they are now. The reuse check reads every unassigned live contest's import
    * sources and rounds, and the files of the rounds of `filesOf` (None: of every one; on the
    * production copy, 2026-11-20, 912 such rounds' 1.35 million files took 4.4 s, so the page runs
    * on the blocking pool and the confirmation reads only the ticked contests' files).
    */
  private def proposals(filesOf: Option[Set[String]]): SeriesProposals.Result = {
    val contests = ContestJuryJdbc.findAll()
    val candidates = contests.filter(c => !c.retired && c.seriesId.isEmpty)
    val flagged = filesOf match {
      case None => ContestReuse.checkAll(candidates, reuseSettings)
      case Some(keys) =>
        // every contest of a ticked proposal's type and country gets the full check, as the page
        // did: one flagged by its round files only must stay out of the group (else a same-year
        // ticked contest would read as a duplicate)
        val (inTicked, others) = candidates.partition(c => SeriesProposals.keyOf(c).exists(keys))
        ContestReuse.checkAll(others, reuseSettings, files = false) ++ ContestReuse.checkAll(inTicked, reuseSettings)
    }
    SeriesProposals.of(contests, SeriesJdbc.findAll(), flagged)
  }

  def proposalsPage(): EssentialAction = withAuthOn(blocking)(root) { user => implicit request =>
    val result = proposals(None)
    Ok(views.html.series.seriesProposals(user, result, p => affectedGrants(p.existing.map(_.id), p.contests.flatMap(_.id))))
      .withHeaders(CACHE_CONTROL -> "no-store")
  }

  /** The bulk confirmation, all or nothing against what root saw: each ticked proposal (`p`), into
    * the existing series the page offered (`existing.<key>`, its id or `none`; `target.<key>` =
    * `new` or that id) or a new one of the name typed (`name.<key>`), with the contests ticked
    * (`c.<key>`). The proposals are computed again (the round files of the contests of the ticked
    * proposals' types and countries read again, the others' sources and rounds only):
    * a proposal gone, an offered series changed, or a ticked contest no longer in its proposal
    * (assigned, retired, flagged or grouped otherwise meanwhile) refuses the whole confirmation,
    * naming the contest; nothing is written ([[ContestSeries.confirm]] then refuses a contest
    * changed under its locks the same way).
    */
  def confirm(): EssentialAction = withAuthOn(blocking)(root) { user => request =>
    val data = form(request)
    def one(name: String) = data.get(name).flatMap(_.headOption).map(_.trim)
    val tickedKeys = data.getOrElse("p", Nil).distinct
    val tickedContests = tickedKeys.map(k => k -> data.getOrElse(s"c.$k", Nil).flatMap(_.toLongOption).distinct).toMap
    val current = proposals(Some(tickedKeys.toSet)).proposals.map(p => p.key -> p).toMap
    val checked: Either[(String, Option[Long]), Seq[ContestSeries.Choice]] =
      tickedKeys.foldLeft[Either[(String, Option[Long]), Seq[ContestSeries.Choice]]](Right(Nil)) { (acc, key) =>
        acc.flatMap { done =>
          current.get(key) match {
            case None => Left(("series.assign.changed", tickedContests.getOrElse(key, Nil).headOption))
            case Some(p) =>
              val offered = p.existing.map(_.id.toString).getOrElse("none")
              val target = one(s"target.$key").getOrElse(offered)
              val shown = p.contests.flatMap(_.id).toSet
              val ticked = tickedContests.getOrElse(key, Nil)
              if (!one(s"existing.$key").contains(offered) || (target != "new" && target != offered))
                Left(("series.assign.changed", ticked.headOption))
              else ticked.find(id => !shown(id)) match {
                case Some(gone) => Left(("series.assign.changed", Some(gone)))
                case None =>
                  Right(done :+ ContestSeries.Choice(p.existing.filter(_ => target != "new").map(_.id),
                    one(s"name.$key").getOrElse(p.name), Some(p.contestType.code), Some(p.countryName), p.campaign, ticked))
              }
          }
        }
      }
    checked.flatMap(choices => ContestSeries.confirm(choices, user.getId)).fold(
      { case (key, contest) =>
        Redirect(routes.SeriesController.proposalsPage())
          .flashing(Seq(ErrorFlash -> key) ++ contest.map(c => ErrorContestFlash -> c.toString): _*)
      },
      batch => Redirect(routes.SeriesController.list()).flashing(DoneFlash -> "series.assign.done", "series-batch" -> batch.toString)
    )
  }

  def undoBatch(batchId: Long): EssentialAction = withAuth(root) { user => request =>
    ContestSeries.undoBatch(batchId, user.getId).fold(
      key => Redirect(routes.SeriesController.list()).flashing(ErrorFlash -> key),
      { case (undone, left) =>
        Redirect(routes.SeriesController.list()).flashing(DoneFlash -> "series.batch.undo.done",
          "series-undone" -> undone.size.toString, "series-left" -> left.mkString(", "))
      }
    )
  }

  // ── a contest's series ────────────────────────────────────────────────────

  def contestPage(contestId: Long): EssentialAction = withAuthOn(blocking)(root) { user => implicit request =>
    ContestJuryJdbc.findById(contestId).fold[Result](
      NotFound(views.html.errorPage(Some(user), "error.title", "error.contest.not.found"))
    ) { contest =>
      val all = SeriesJdbc.findAll()
      val changes = ContestChangeJdbc.findByContest(contestId, 50)
      val names = User.findByIds(changes.map(_.actorId).distinct).map(u => u.getId -> u.fullname).toMap
      val reuse = scalikejdbc.DB.readOnly(implicit s => ContestReuse.check(contest, reuseSettings))
      Ok(views.html.series.contestSeries(user, contest, all, ContestSeries.previous(contest),
        all.filter(s => s.live && !SeriesIdentity.mismatch(s, contest)).map(_.id).toSet, reuse, changes, names,
        ContestSeries.liveRoundRefusal(contestId)))
        .withHeaders(CACHE_CONTROL -> "no-store")
    }
  }

  def setContestSeries(contestId: Long): EssentialAction = withAuth(root) { user => request =>
    val target = field(request, "seriesId").flatMap(_.toLongOption)
    outcome(routes.SeriesController.contestPage(contestId),
      ContestSeries.setSeries(contestId, target, user.getId, field(request, "comment")),
      if (target.isEmpty) "series.contest.unassigned" else "series.contest.assigned")
  }

  def retireContest(contestId: Long): EssentialAction = withAuth(root) { user => request =>
    outcome(routes.SeriesController.contestPage(contestId),
      ContestSeries.retireContest(contestId, user.getId, field(request, "comment")), "contest.retired.done")
  }

  def unretireContest(contestId: Long): EssentialAction = withAuth(root) { user => request =>
    outcome(routes.SeriesController.contestPage(contestId),
      ContestSeries.unretireContest(contestId, user.getId, field(request, "comment")), "contest.unretired.done")
  }
}
