package controllers

import db.scalikejdbc.{ContestChangeJdbc, ContestJuryJdbc, SeriesAdminJdbc, SeriesGrant, SeriesJdbc, User}
import modules.BlockingExecutionContext
import org.scalawiki.wlx.dto.ContestType
import play.api.Configuration
import play.api.i18n.{I18nSupport, Messages}
import play.api.mvc.{AnyContent, ControllerComponents, EssentialAction, Request, Result}
import services.series.{ContestReuse, ContestSeries, ReuseReport, ReuseScanHandler, SeriesAdmins, SeriesIdentity,
  SeriesProposals}

import javax.inject.Inject

/** Root's contest series pages (contest series plan §3.1, §3.5, §5.3; PR CY-1): `/admin/series`
  * (the series, with create, rename, retire and un-retire; the bulk assignments with Undo), the
  * proposals (`/admin/series/assign`, confirmed in bulk), a series' page, and a contest's series
  * page (assign, move, unassign, retire, un-retire, its change log). Root only: everyone else is
  * denied (403, logged on `security.authz`); signed out, the login page or a JSON 401. Every POST
  * carries the CSRF token; each answers with a redirect and a flash naming the outcome.
  *
  * CY-2 adds the series admins (§3.3, §5.2, §5.3): the series page `/series/:id` for root and the
  * series' admins ([[seriesHome]]), with "Add a series admin" ([[grantPage]], [[grant]]: root and
  * the series' admins, CA19, CA22); root's revoke with the chained revoke ([[revokeGrant]]) and
  * start-year edit ([[setGrantFromYear]]) on the series' root page, its grants of the last 24
  * hours and the grants series admins added on `/admin/series`; the affected-grants list of an
  * assignment ([[affectedGrants]]). Root and the series' admins only; everyone else is denied (403,
  * logged), a series that doesn't exist is a 404.
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

  /** The series admins who gain reach by assigning `contestIds` to the series (§3.5): each live
    * grant's account with the contests it reaches, derived Admin from its start year on (with
    * account reach, CA17) and Organizer below it (CA20).
    */
  def affectedGrants(seriesId: Option[Long], contestIds: Seq[Long])(implicit messages: Messages): Seq[String] =
    seriesId.toSeq.flatMap(SeriesAdminJdbc.liveBySeries(_)) match {
      case Nil => Nil
      case grants =>
        val contests = ContestJuryJdbc.findAllBy(scalikejdbc.sqls.in(ContestJuryJdbc.defaultAlias.id, contestIds))
          .sortBy(c => -c.year)
        val names = SeriesAdmins.names(grants.map(_.userId))
        grants.map { g =>
          val reach = contests.map(c =>
            s"${c.year}: ${Messages(if (c.year >= g.adminFromYear) "role.admin" else "role.organizer")}").mkString(", ")
          s"${names.getOrElse(g.userId, s"#${g.userId}")} ($reach)"
        }
    }

  /** Grants as a tree along `granted_by` (root's view, §5.3): each live grant with its depth, those
    * added by root (or by an account with no live grant on the series) at depth 0, each followed by
    * the grants its account added.
    */
  def grantTree(grants: Seq[SeriesGrant]): Seq[(SeriesGrant, Int)] = {
    val live = grants.filterNot(_.revoked)
    val holders = live.map(_.userId).toSet
    val byGranter = live.groupBy(_.grantedBy)
    def below(g: SeriesGrant, depth: Int, seen: Set[Long]): Seq[(SeriesGrant, Int)] =
      (g, depth) +: byGranter.getOrElse(g.userId, Nil).filterNot(c => seen(c.userId))
        .flatMap(c => below(c, depth + 1, seen + c.userId))
    live.filterNot(g => holders(g.grantedBy) && g.grantedBy != g.userId).flatMap(g => below(g, 0, Set(g.userId)))
  }

  // ── /admin/series ─────────────────────────────────────────────────────────

  def list(): EssentialAction = withAuth(root) { user => implicit request =>
    val series = SeriesJdbc.findAll()
    val contests = ContestJuryJdbc.findAll()
    val batches = ContestChangeJdbc.recentBatches(20)
    // root's view of each series' grants of the last 24 hours (CA22) and the in-app notices of the
    // grants series admins added (CA19)
    val recent = SeriesAdminJdbc.recentBySeries(series.map(_.id), SeriesController.RecentGrantHours)
    val bySeriesAdmins = SeriesAdminJdbc.recentBySeriesAdmins(SeriesController.NoticeDays, SeriesController.NoticesShown)
    val names = User.findByIds((batches.map(_.actorId) ++ series.flatMap(_.createdBy) ++
      (recent ++ bySeriesAdmins).flatMap(g => Seq(g.userId, g.grantedBy))).distinct).map(u => u.getId -> u.fullname).toMap
    Ok(views.html.series.adminSeries(user, series, contests.groupBy(_.seriesId.getOrElse(0L)), batches, names,
      (ContestType.all.map(SeriesIdentity.of) ++ series.flatMap(_.contestType).map(SeriesIdentity.typeOfCode)).distinctBy(_.code),
      contests.count(c => c.seriesId.isEmpty && !c.retired), recent.groupBy(_.seriesId), bySeriesAdmins))
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
      val grants = SeriesAdminJdbc.findBySeries(id)
      val names = User.findByIds((changes.map(_.actorId) ++ s.createdBy.toSeq ++
        grants.flatMap(g => Seq(g.userId, g.grantedBy) ++ g.revokedBy)).distinct).map(u => u.getId -> u.fullname).toMap
      val thisYear = java.time.Year.now().getValue
      // each live grant's recomputed default start year (useful after a join, CA20)
      val defaults = grants.filterNot(_.revoked).map(g => g.id -> SeriesAdminJdbc.defaultFromYear(g.userId, id, thisYear)).toMap
      Ok(views.html.series.seriesPage(user, s, contests, ContestSeries.previousIn(contests, contests), changes, names,
        grantTree(grants), grants.filter(_.revoked).reverse, defaults, thisYear))
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
    // and those the latest finished reuse scan reports (§3.5; CY-0), however stale: root assigns them
    // one by one; a running, failed or cancelled scan's partial results are never read
    val reported = ReuseScanHandler.latestDone().toSeq.flatMap { job =>
      ReuseScanHandler.stateOf(job).contests.filter(c => ReuseReport.Verdict.KeptOut(c.verdict))
        .map(c => c.contestId -> ContestReuse.Report(job.id, c.verdict))
    }.toMap
    val all = (flagged.keySet ++ reported.keySet).map(id => id -> (flagged.getOrElse(id, Nil) ++ reported.get(id))).toMap
    SeriesProposals.of(contests, SeriesJdbc.findAll(), all)
  }

  // ── series admins (CY-2) ──────────────────────────────────────────────────

  private def seriesNotFound(user: User)(implicit request: Request[AnyContent]): Result =
    NotFound(views.html.errorPage(Some(user), "error.title", "series.not.found"))

  /** The series page (§5.2) for root and the series' admins: its live contests by year, the newest
    * first, each with the viewer's role there (real, or derived by their grant: Admin from its
    * start year on, Organizer below it) and a button to switch to it; its series admins, with who
    * added each, when and their start year; "Add a series admin" while the series is live. Anyone
    * else is denied (CY-3 opens a part of it to the Admins of its recent contests).
    */
  def seriesHome(id: Long): EssentialAction = withAuth(Secured.SeriesPermission(id)) { user => implicit request =>
    SeriesJdbc.findById(id).fold(seriesNotFound(user)) { s =>
      val contests = ContestSeries.contestsOf(id).filterNot(_.retired)
      val grants = SeriesAdminJdbc.liveBySeries(id)
      val admins = contests.flatMap(_.id).map(c => c -> User.findByContestAndRoles(c, Seq(User.ADMIN_ROLE))).toMap
      val names = SeriesAdmins.names(grants.flatMap(g => Seq(g.userId, g.grantedBy)))
      val reach = Secured.seriesReachOf(user).map(sc => sc.contestId -> sc.role).toMap
      val real = user.userContests.filter(_.opens).map(m => m.contestId -> m.role).toMap
      Ok(views.html.series.seriesHome(user, s, contests, admins, grants, names, reach, real,
        s.live && (isRoot(user) || Secured.seriesGrantedTo(user).contains(id)),
        if (s.live) SeriesAdmins.candidates(user, id) else Nil))
        .withHeaders(CACHE_CONTROL -> "no-store")
    }
  }

  /** "Add a series admin" (§3.3, CA19, CA20): the account picked on the series page, the start year
    * pre-filled with the default (the grantee's earliest real Admin year in the series, capped at
    * last year, else last year; for a series admin raised to their own start year) and editable
    * within the granter's bounds, the full list of the series' contests with what the grant gives in
    * each from that year (`fromYear` shows it again for another year), and the contests where the
    * account holds a Juror row (CA7). The grant checks everything again.
    */
  def grantPage(id: Long, account: Option[Long], fromYear: Option[Int]): EssentialAction =
    withAuth(Secured.SeriesPermission(id)) { user => implicit request =>
      SeriesJdbc.findById(id).fold(seriesNotFound(user)) { s =>
        val thisYear = java.time.Year.now().getValue
        account.flatMap(User.findById).filter(_.deletedAt.isEmpty) match {
          case None => NotFound(views.html.errorPage(Some(user), "error.title", "error.user.not.found"))
          case Some(grantee) =>
            val (from, to) = SeriesAdmins.bounds(user, id, thisYear).getOrElse(thisYear - 1 -> (thisYear + 1))
            val default = SeriesAdmins.defaultYear(user, id, grantee.getId, thisYear)
            val year = fromYear.filter(y => y >= from && y <= to).getOrElse(default)
            Ok(views.html.series.grantPage(user, s, grantee, year, default, from, to,
              SeriesAdmins.reachOf(id, grantee.getId, year)))
              .withHeaders(CACHE_CONTROL -> "no-store")
        }
      }
    }

  /** The grant (a POST with the CSRF token): [[SeriesAdmins.grant]] as the user, who must be root or
    * a series admin of the series (read again under the series' lock); a refusal goes back to the
    * series page with its reason.
    */
  def grant(id: Long): EssentialAction = withAuth(Secured.SeriesPermission(id)) { user => implicit request =>
    SeriesJdbc.findById(id).fold(seriesNotFound(user)) { _ =>
      val year = field(request, "fromYear").flatMap(_.toIntOption)
      field(request, "account").flatMap(_.toLongOption) match {
        case None => BadRequest(views.html.errorPage(Some(user), "error.title", "error.bad.request"))
        case Some(account) =>
          SeriesAdmins.grant(user, id, account, year, field(request, "comment")) match {
            case Left("series.admin.refused.not.admin") =>
              onUnAuthorized(user, request, Secured.SeriesPermission(id).denial)
            case Left(key) => Redirect(routes.SeriesController.seriesHome(id)).flashing(ErrorFlash -> key)
            case Right(_)  => Redirect(routes.SeriesController.seriesHome(id)).flashing(DoneFlash -> "series.admin.granted")
          }
      }
    }
  }

  /** Root's revoke of a grant (a POST with the CSRF token), with `chain` the grants its account added
    * to any depth (CA22). Root only.
    */
  def revokeGrant(grantId: Long): EssentialAction = withAuth(root) { user => implicit request =>
    SeriesAdminJdbc.findById(grantId).fold[Result](
      NotFound(views.html.errorPage(Some(user), "error.title", "series.admin.not.found"))
    ) { g =>
      outcome(routes.SeriesController.series(g.seriesId),
        SeriesAdmins.revoke(user, grantId, field(request, "chain").contains("true")), "series.admin.revoked")
    }
  }

  /** Root's change of a grant's start year (CA20; a POST with the CSRF token). Root only. */
  def setGrantFromYear(grantId: Long): EssentialAction = withAuth(root) { user => implicit request =>
    SeriesAdminJdbc.findById(grantId).fold[Result](
      NotFound(views.html.errorPage(Some(user), "error.title", "series.admin.not.found"))
    ) { g =>
      field(request, "fromYear").flatMap(_.toIntOption) match {
        case None => Redirect(routes.SeriesController.series(g.seriesId)).flashing(ErrorFlash -> "series.admin.refused.year")
        case Some(year) =>
          outcome(routes.SeriesController.series(g.seriesId), SeriesAdmins.setFromYear(user, grantId, year),
            "series.admin.from.year.done")
      }
    }
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

object SeriesController {

  /** Root's view of each series' grants: those of the last 24 hours (CA22). */
  val RecentGrantHours = 24

  /** Root's notices of the grants series admins added (CA19): the last 30 days, at most 50. */
  val NoticeDays = 30
  val NoticesShown = 50
}
