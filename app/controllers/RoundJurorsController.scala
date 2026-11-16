package controllers

import db.scalikejdbc.{Round, User}
import modules.BlockingExecutionContext
import play.api.data.Form
import play.api.data.Forms.{longNumber, nonEmptyText, seq, tuple}
import play.api.mvc.{AnyContent, ControllerComponents, EssentialAction, Request, Result}
import services.distribution.{RoundFreezes, RoundRebalanceHandler, RoundRebalanceService}
import services.jobs.JobError

import javax.inject.Inject

/** Adding a juror to a running round (docs/plans/2026-11-16-round-editing.md, §5): the page
  * (GET, `juror` once chosen: the figures and the confirm form) and its POST, which queues a
  * [[RoundRebalanceHandler]] job; and root's Release of a round left frozen with no job
  * ([[RoundFreezes]]).
  *
  * The round's contest's admins and root, the round checked in its own contest
  * ([[Secured.inContest]], never switching the session's contest on the POST); organizers and
  * jurors get the 403 page, a missing round the 404 page. POSTs carry the CSRF token.
  */
class RoundJurorsController @Inject() (
    cc: ControllerComponents,
    rebalance: RoundRebalanceService,
    blocking: BlockingExecutionContext
) extends Secured(cc)
    with play.api.i18n.I18nSupport {

  import RoundRebalanceService._

  def addPage(id: Long, juror: Option[Long]): EssentialAction =
    withAuthOn(blocking)(rolePermission(User.ADMIN_ROLES)) { sessionUser => implicit request =>
      withAdminRound(sessionUser, id) { (user, round) =>
        // the donors ticked, once the page's form was sent ("picked"); else the default (D1)
        val donors = Option.when(request.queryString.contains("picked"))(
          request.queryString.getOrElse("donor", Nil).flatMap(_.toLongOption))
        Secured.switchingContest(sessionUser, user)(Ok(page(user, round, juror, notice = None, donors)))
      }
    }

  /** Queues the add of the posted juror (`juror`) if the round's jury is still the one the page
    * showed (`jury`, [[RoundRebalanceService.signature]]); otherwise the page again, with fresh
    * figures. To the rounds list, whose job column shows its progress.
    */
  def add(id: Long): EssentialAction =
    withAuthOn(blocking)(rolePermission(User.ADMIN_ROLES)) { sessionUser => implicit request =>
      withAdminRound(sessionUser, id) { (user, round) =>
        val messages = messagesApi.preferred(request)
        addForm.bindFromRequest().fold(
          _ => BadRequest(page(user, round, None, notice = Some(messages("error.invalid")))),
          { case (jurorId, shownJury, donors) =>
            rebalance.add(round, jurorId, user, shownJury, donors) match {
              case Queued(job) =>
                val name = User.findById(jurorId).map(_.fullname).getOrElse(jurorId.toString)
                val text =
                  if (job.isDone) messages("round.juror.add.done", name, round.number, RoundRebalanceHandler.filesGiven(job))
                  else messages("round.juror.add.queued", name, round.number)
                Redirect(routes.RoundController.rounds(Some(round.contestId))).flashing("success" -> text)
              case JuryChanged =>
                Conflict(page(user, round, Some(jurorId), notice = Some(messages("round.juror.add.jury.changed")),
                  Some(donors)))
              case Busy(_) =>
                Conflict(page(user, round, Some(jurorId),
                  notice = Some(JobError(RoundRebalanceHandler.BusyKey, Seq(round.number.toString)).message(messages)),
                  Some(donors)))
              case Refused(error) =>
                Conflict(page(user, round, Some(jurorId), notice = Some(error.message(messages)), Some(donors)))
            }
          }
        )
      }
    }

  /** Root's Release of a round left frozen with no job holding it ([[RoundFreezes.release]]):
    * logged; back to the rounds list.
    */
  def releaseFreeze(id: Long): EssentialAction =
    withAuthOn(blocking)(rolePermission(Set(User.ROOT_ROLE))) { user => implicit request =>
      Round.findById(id) match {
        case None => NotFound(views.html.errorPage(Some(user), "error.title", "error.round.not.found"))
        case Some(round) =>
          val messages = messagesApi.preferred(request)
          val released = RoundFreezes.release(round.getId)
          RoundRebalanceHandler.adminLog.info(s"action=freeze_release accountId=${user.getId} contestId=${round.contestId} " +
            s"roundId=${round.getId} released=$released")
          Redirect(routes.RoundController.rounds(Some(round.contestId))).flashing(
            if (released > 0) "success" -> messages("round.freeze.released", round.number)
            else "error" -> messages("round.freeze.none", round.number))
      }
    }

  private def page(user: User, round: Round, juror: Option[Long], notice: Option[String],
      donors: Option[Seq[Long]] = None)(implicit
      request: Request[AnyContent]
  ): play.twirl.api.Html = {
    val preview = juror.map(rebalance.preview(round, _, donors))
    val jury = preview.fold(rebalance.jury(round))(_.jury)
    views.html.round.addJuror(user, round, jury, preview, notice, RoundRebalanceService.signature(jury.jurors),
      Round.isPaused(round.getId))
  }

  /** The round, if `user` administers its contest in it (root needs no membership): `f` with the
    * user in that contest; the 404 page for no round, the 403 page (logged) otherwise.
    */
  private def withAdminRound(user: User, id: Long)(f: (User, Round) => Result)(implicit
      request: Request[AnyContent]
  ): Result =
    Round.findById(id) match {
      case None =>
        NotFound(views.html.errorPage(Some(user), "error.title", "error.round.not.found")(messagesApi.preferred(request), request))
      case Some(round) =>
        if (Secured.administersRound(user, round)) f(Secured.inContest(user, round.contestId), round)
        else onUnAuthorized(user, request, Secured.Denial.round(round, User.ADMIN_ROLES))
    }

  private val addForm: Form[(Long, String, Seq[Long])] = Form(tuple(
    "juror" -> longNumber,
    "jury" -> nonEmptyText(maxLength = 20000),
    "donor" -> seq(longNumber).verifying("error.invalid", _.size <= 1000)
  ))
}
