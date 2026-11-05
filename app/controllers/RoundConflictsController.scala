package controllers

import db.scalikejdbc.{Round, RoundConflicts, User}
import modules.BlockingExecutionContext
import play.api.i18n.Messages
import play.api.mvc.{AnyContent, ControllerComponents, EssentialAction, Request, RequestHeader, Result}
import services.distribution.RoundDistributionHandler

import javax.inject.Inject

/** A round's conflicts of interest (docs/plans/2026-11-02-round-images-two-step.md, §7 and
  * P1b): the page lists the files in the round that are conflicts now (the re-check), and the
  * disqualified ones with their reason and whether they still match; its forms disqualify and
  * requalify ([[RoundConflicts]]).
  *
  * The page: the round's contest's admins and organizers (organizers read only), root; the
  * round checked in its own contest ([[Secured.inContest]]), 404 if there is none. The forms:
  * its contest's admins and root, POSTs with the CSRF token, never switching the session's
  * contest; refused while the round is open to its jurors (W7) or being distributed.
  */
class RoundConflictsController @Inject() (
    cc: ControllerComponents,
    blocking: BlockingExecutionContext
) extends Secured(cc)
    with play.api.i18n.I18nSupport {

  /** Who may open the page, in the round's contest: [[User.canViewRoundConflicts]], the one
    * check the statistics page's link to it uses too; its roles name a refusal. */
  private val ViewRoles = User.RoundConflictsRoles
  private def mayView(user: User, round: Round): Boolean = user.canViewRoundConflicts(round.contestId)

  /** Who may disqualify and requalify: the round's contest's admins, and root. */
  private def mayEdit(user: User, round: Round): Boolean =
    contestPermission(User.ADMIN_ROLES, Some(round.contestId))(user)

  def page(id: Long): EssentialAction =
    withAuthOn(blocking)(rolePermission(ViewRoles)) { sessionUser => implicit request =>
      withRound(sessionUser, id, ViewRoles, mayView) { (user, round) =>
        val review = RoundConflicts.review(round)
        Secured.switchingContest(sessionUser, user)(
          Ok(views.html.round.conflicts(user, round, review, mayEdit(user, round), RoundConflicts.RequalifyReason.All))
        )
      }
    }

  /** "Disqualify" of the files ticked (`pageId`): those still conflicts of interest. */
  def disqualify(id: Long): EssentialAction =
    withAuthOn(blocking)(rolePermission(User.ADMIN_ROLES)) { sessionUser => implicit request =>
      withRound(sessionUser, id, User.ADMIN_ROLES, mayEdit) { (user, round) =>
        val pageIds = longs("pageId")
        val messages = messagesApi.preferred(request)
        if (pageIds.isEmpty) back(round).flashing("error" -> messages("coi.none.selected"))
        else
          RoundConflicts.disqualify(round.getId, user, RoundDistributionHandler.dedupeKey(round.getId),
            only = Some(pageIds.toSet)) match {
            case RoundConflicts.Outcome.Disqualified(files) =>
              back(round).flashing("success" -> messages("coi.disqualified", files.size, round.number))
            case refused => refusal(round, refused)(messages)
          }
      }
    }

  /** "Requalify" of the files ticked (`pageId`), with a reason (`reason`, a code). */
  def requalify(id: Long): EssentialAction =
    withAuthOn(blocking)(rolePermission(User.ADMIN_ROLES)) { sessionUser => implicit request =>
      withRound(sessionUser, id, User.ADMIN_ROLES, mayEdit) { (user, round) =>
        val pageIds = longs("pageId")
        val reason = field("reason").filter(RoundConflicts.RequalifyReason.All.contains)
        val messages = messagesApi.preferred(request)
        (pageIds, reason) match {
          case (Nil, _)  => back(round).flashing("error" -> messages("coi.none.selected"))
          case (_, None) => back(round).flashing("error" -> messages("coi.requalify.reason.missing"))
          case (_, Some(code)) =>
            RoundConflicts.requalify(round.getId, pageIds, code, user, RoundDistributionHandler.dedupeKey(round.getId)) match {
              case RoundConflicts.Outcome.Requalified(files) =>
                back(round).flashing("success" -> messages("coi.requalified", files.size, round.number))
              case refused => refusal(round, refused)(messages)
            }
        }
      }
    }

  /** The round, checked in its own contest by `allowed` (root needs no membership): `f` with
    * the user in that contest; 404 for no round, 403 (logged, naming `roles`) otherwise.
    */
  private def withRound(user: User, id: Long, roles: Set[String], allowed: (User, Round) => Boolean)(
      f: (User, Round) => Result)(implicit request: Request[AnyContent]): Result =
    Round.findById(id) match {
      case None =>
        NotFound(views.html.errorPage(Some(user), "error.title", "error.round.not.found")(messagesApi.preferred(request), request))
      case Some(round) =>
        val inRound = Secured.inContest(user, round.contestId)
        if (allowed(inRound, round)) f(inRound, round)
        else onUnAuthorized(user, request, Secured.Denial.round(round, roles))
    }

  private def refusal(round: Round, outcome: RoundConflicts.Outcome)(messages: Messages)(implicit
      request: RequestHeader
  ): Result = {
    val text = outcome match {
      case RoundConflicts.Outcome.Open         => messages("coi.action.open", round.number)
      case RoundConflicts.Outcome.Frozen       => messages("coi.disqualify.frozen", round.number)
      case RoundConflicts.Outcome.Distributing => messages("coi.remove.distributing", round.number)
      case _                                   => messages("error.round.not.found")
    }
    back(round).flashing("error" -> text)
  }

  private def back(round: Round): Result = Redirect(routes.RoundConflictsController.page(round.getId))

  private def form(implicit request: Request[AnyContent]): Map[String, Seq[String]] =
    request.body.asFormUrlEncoded.getOrElse(Map.empty)

  private def field(name: String)(implicit request: Request[AnyContent]): Option[String] =
    form.get(name).flatMap(_.headOption).map(_.trim).filter(_.nonEmpty)

  /** The form's page ids: `name` and `name[]`, numbers only, at most [[MaxFiles]]. */
  private def longs(name: String)(implicit request: Request[AnyContent]): Seq[Long] =
    (form.getOrElse(name, Nil) ++ form.getOrElse(s"$name[]", Nil)).flatMap(_.trim.toLongOption).distinct
      .take(RoundConflictsController.MaxFiles)
}

object RoundConflictsController {

  /** Files per action: a round's conflicts are tens, not thousands. */
  val MaxFiles = 2000
}
