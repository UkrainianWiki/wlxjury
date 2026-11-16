package controllers

import db.scalikejdbc.{ImageJdbc, MonumentJdbc, Round, RoundImageJdbc, RoundStatLinks, User}
import modules.BlockingExecutionContext
import play.api.mvc.{AnyContent, ControllerComponents, EssentialAction, Request, Result}
import services.RoundService
import services.distribution.RoundDistributionHandler
import services.jobs.JobJdbc
import services.regions.RegionScheme

import javax.inject.Inject

/** A round's review before its files go to its jurors (P3 of
  * docs/plans/2026-11-02-round-images-two-step.md, §6 "UI"; P3-3 of
  * docs/plans/2026-11-16-round-editing.md): the round's files (`round_image`) laid out as the
  * contest files page ([[ImageJdbc.FileList.RoundImages]]: tiles, pager, region menu, large view),
  * by status (in, deleted on Commons, disqualified), with a summary and the Distribute action.
  *
  * Authorization (D20): the pages for the round's contest's admins and organizers (organizers read
  * only) and root, the round checked in its own contest ([[Secured.inContest]]), 404 if there is
  * none; Distribute for its admins and root, a POST with the CSRF token, never switching the
  * session's contest, refused unless the round is in review ([[Round.inReview]]) with no job.
  * Jurors never see a round in review: it is `distributing`, hidden from them everywhere.
  */
class RoundReviewController @Inject() (
    cc: ControllerComponents,
    blocking: BlockingExecutionContext,
    roundsService: RoundService
) extends Secured(cc)
    with play.api.i18n.I18nSupport {

  import RoundReviewController._

  private def mayView(user: User, round: Round): Boolean =
    contestPermission(ViewRoles, Some(round.contestId))(user)

  private def mayDistribute(user: User, round: Round): Boolean = Secured.administersRound(user, round)

  /** The review page: the round's files of `status`, a page of them, in a region or all. */
  def review(id: Long, status: String, page: Int, region: String): EssentialAction =
    withAuthOn(blocking)(rolePermission(ViewRoles)) { sessionUser => implicit request =>
      withRound(sessionUser, id) { (user, round) =>
        reviewView(round, status, region).fold(notFound(user, _), { view =>
          val counts = RoundImageJdbc.statusCounts(round.getId)
          val list = view.list.get
          val listCount = view.region.fold(counts.getOrElse(view.reviewStatus.get, 0))(r =>
            ImageJdbc.regionCounts(list).getOrElse(r, 0))
          val pager = Pager(math.max(page, 1), listCount)
          val files = ImageJdbc.listFiles(list, view.region, pager.pageSize, pager.offset)
          val job = JobJdbc.latestBySubjects(RoundDistributionHandler.Kind, RoundDistributionHandler.SubjectType,
            Seq(round.getId)).get(round.getId)
          val summary = RoundReview(
            round = round,
            statusCounts = counts,
            addedHow = RoundImageJdbc.addedHowCounts(round.getId),
            conflicts = RoundStatLinks.counts(round).conflicts,
            job = job,
            mayDistribute = mayDistribute(user, round) && round.inReview && !job.exists(_.isActive),
            mayEdit = mayDistribute(user, round)
          )
          Secured.switchingContest(sessionUser, user)(
            Ok(views.html.contestFiles(user, files, pager, view, Some(summary))))
        })
      }
    }

  /** The review list's regions with its files in each (the region menu's, region-menu.js). */
  def regions(id: Long, status: String): EssentialAction =
    withAuthJsonOn(blocking)(rolePermission(ViewRoles)) { sessionUser => implicit request =>
      Round.findById(id) match {
        case None => NotFound(play.api.libs.json.Json.obj("error" -> "round_not_found"))
        case Some(round) =>
          val user = Secured.inContest(sessionUser, round.contestId)
          if (!mayView(user, round)) onUnAuthorizedJson(sessionUser, request, Secured.Denial.round(round, ViewRoles))
          else reviewView(round, status, "all").fold(_ => NotFound(play.api.libs.json.Json.obj("error" -> "not_found")), { view =>
            val messages = messagesApi.preferred(request)
            val regions =
              if (!view.byMonuments) Seq.empty[org.intracer.wmua.Region]
              else RegionScheme.of(view.contest).regions(
                ImageJdbc.regionCounts(view.list.get).map { case (r, n) => r -> Some(n) })(messages)
            import play.api.libs.json.Json
            Ok(Json.obj(
              "current" -> Option.empty[String],
              "all" -> Json.obj("name" -> messages("region.all"), "url" -> view.regionUrl("all")),
              "regions" -> regions.map(r => Json.obj("id" -> r.id, "name" -> r.name, "count" -> r.count,
                "kind" -> r.kind.toString, "url" -> view.regionUrl(r.id)))
            ))
          })
      }
    }

  /** The large view of a file of the review list, among its neighbours, read only. */
  def file(id: Long, pageId: Long, status: String, region: String): EssentialAction =
    withAuthOn(blocking)(rolePermission(ViewRoles)) { sessionUser => implicit request =>
      withRound(sessionUser, id) { (user, round) =>
        reviewView(round, status, region).fold(notFound(user, _), { view =>
          val list = view.list.get
          val offset = math.max(0, ImageJdbc.fileRank(list, view.region, pageId) - Neighbourhood.Side)
          val read = ImageJdbc.listFiles(list, view.region, Neighbourhood.Side + 1 + Neighbourhood.Following, offset)
          val files = read.take(Neighbourhood.Window)
          val index = files.indexWhere(_.image.pageId == pageId)
          if (index < 0) Redirect(view.pageUrl(1))
          else {
            val monument = files(index).image.monumentId.filter(_.trim.nonEmpty).flatMap(MonumentJdbc.find(_))
            val following = read.drop(index + 1).take(Neighbourhood.Following)
            Secured.switchingContest(sessionUser, user)(
              Ok(views.html.large.contestFile(user, view, files, index, following, Pager.pageOf(offset + index), monument)))
          }
        })
      }
    }

  /** Distribute: the round's files to its jurors, as a job (`round_distribution`, mode
    * `distribute`); the round opens to them when it ends. Back to the review page with what
    * happened.
    */
  def distribute(id: Long): EssentialAction =
    withAuthOn(blocking)(rolePermission(User.ADMIN_ROLES)) { sessionUser => implicit request =>
      Round.findById(id) match {
        case None => notFound(sessionUser, "error.round.not.found")
        case Some(round) =>
          val user = Secured.inContest(sessionUser, round.contestId)
          val messages = messagesApi.preferred(request)
          val back = Redirect(routes.RoundReviewController.review(round.getId))
          if (!mayDistribute(user, round)) onUnAuthorized(sessionUser, request, Secured.Denial.round(round, User.ADMIN_ROLES))
          else roundsService.distributeReviewed(round.getId, user.id) match {
            case RoundService.ReviewDistribution.Started(_) =>
              back.flashing("success" -> messages("round.review.distribute.started", round.number))
            case RoundService.ReviewDistribution.Running(_) =>
              back.flashing("error" -> messages("round.distribute.running", round.number))
            case RoundService.ReviewDistribution.Refused(key, args) =>
              back.flashing("error" -> messages(key, args: _*))
            case RoundService.ReviewDistribution.NotInReview =>
              back.flashing("error" -> messages("round.review.distribute.not.in.review", round.number))
          }
      }
    }

  /** The round, checked in its own contest: `f` with the user in that contest; 404 for no round,
    * 403 (logged) for one the user may not view.
    */
  private def withRound(user: User, id: Long)(f: (User, Round) => Result)(implicit request: Request[AnyContent]): Result =
    Round.findById(id) match {
      case None => notFound(user, "error.round.not.found")
      case Some(round) =>
        val inRound = Secured.inContest(user, round.contestId)
        if (mayView(inRound, round)) f(inRound, round)
        else onUnAuthorized(user, request, Secured.Denial.round(round, ViewRoles))
    }

  private def notFound(user: User, key: String)(implicit request: Request[AnyContent]): Result =
    NotFound(views.html.errorPage(Some(user), "error.title", key))

  /** The review list of the round's files of `status` (one of [[Statuses]]), in the region
    * ("all": every region); the message key of what isn't found otherwise.
    */
  private def reviewView(round: Round, status: String, region: String): Either[String, ContestFilesView] =
    if (!Statuses.contains(status)) Left("error.not.found")
    else
      db.scalikejdbc.ContestJuryJdbc.findById(round.contestId).toRight("error.contest.not.found").map { contest =>
        ContestFilesView(contest, deletedOnly = false, Some(round), Some(region).filter(_ != "all"),
          reviewStatus = Some(status))
      }
}

object RoundReviewController {

  /** Who may open the review pages, in the round's contest (D20): its admins and organizers. */
  val ViewRoles: Set[String] = User.ADMIN_ROLES ++ User.ORG_COM_ROLES

  /** The review list's tabs: the round's files `in` it, deleted on Commons, disqualified. */
  val Statuses: Seq[String] = Seq(RoundImageJdbc.Status.In, RoundImageJdbc.Status.Deleted, RoundImageJdbc.Status.Dq)
}

/** The review page's summary: the round's files by status and by how they were added, its
  * conflicts of interest, its latest distribution job, and whether the viewer may distribute it
  * now.
  */
final case class RoundReview(
    round: Round,
    statusCounts: Map[String, Int],
    addedHow: Map[String, Int],
    conflicts: Int,
    job: Option[services.jobs.Job],
    mayDistribute: Boolean,
    mayEdit: Boolean = false
) {
  def count(status: String): Int = statusCounts.getOrElse(status, 0)
}
