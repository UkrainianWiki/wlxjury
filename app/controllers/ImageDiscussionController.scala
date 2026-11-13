package controllers

import db.scalikejdbc.{Round, VoteJdbc, User}
import org.intracer.wmua.CommentJdbc
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.I18nSupport
import play.api.mvc.{AnyContent, ControllerComponents, EssentialAction, Request, Result}
import services.GalleryService

import javax.inject.Inject

case class CommentBody(id: Long, text: String)

class ImageDiscussionController @Inject()(cc: ControllerComponents, galleryService: GalleryService)
    extends Secured(cc)
      with I18nSupport {

  val editCommentForm = Form(
    mapping(
      "id" -> longNumber(),
      "text" -> text()
    )(CommentBody.apply)(CommentBody.unapply)
  )

  /** Comments on a file of a round, as its large view shows them: for those who may view
    * the round ([[GalleryService.mayViewRound]]) and the file in it, in the round's
    * contest ([[Secured.inContest]]); the session's contest is not switched (a POST). Only
    * while the round is open (`active`), as the large view offers the form: a stopped round's
    * is refused to everyone, staff included ([[ImageDiscussionController.RoundStopped]]).
    */
  def addComment(pageId: Long,
                 region: String = "all",
                 round: Option[Long],
                 contestId: Option[Long],
                 asUserId: Option[Long] = None): EssentialAction = withAuth() {
    sessionUser => implicit request =>
      val requested = round.flatMap(Round.findById)
      val commenterRoles = User.ADMIN_ROLES ++ User.JURY_ROLES ++ User.ORG_COM_ROLES
      requested
        .filter(targetRound => contestId.forall(_ == targetRound.contestId))
        .fold(onUnAuthorized(sessionUser, request, Secured.Denial(
          requested.map(_.contestId).orElse(contestId), round.map(id => s"round:$id"), commenterRoles))) { targetRound =>
          val user = Secured.inContest(sessionUser, targetRound.contestId)
          if (!mayComment(user, targetRound, pageId, commenterRoles))
            onUnAuthorized(user, request, Secured.Denial.round(targetRound, commenterRoles))
          else if (!targetRound.active) {
            val denial = Secured.Denial.round(targetRound, commenterRoles)
              .copy(reason = Some(ImageDiscussionController.RoundStopped))
            if (Secured.isScriptRequest(request)) onUnAuthorizedJson(user, request, denial)
            else onUnAuthorized(user, request, denial,
              back = Some(listOf(user, targetRound, region, asUserId).largeUrl(pageId)))
          } else addCommentTo(user, targetRound, pageId, region, asUserId)
        }
  }

  /** Whether the user may see the file's comments in the round's large view: one of the
    * roles in the round's contest, may view the round, and the file is in their view of
    * it (all the round's files for those who see its organiser info, else their own).
    */
  private def mayComment(user: User, round: Round, pageId: Long, roles: Set[String]): Boolean =
    contestPermission(roles, Some(round.contestId))(user) &&
      galleryService.mayViewRound(user, round) &&
      VoteJdbc.inRound(round.getId, pageId, Option.unless(user.canViewOrgInfo(round))(user.getId))

  /** The list the comment was made in ([[RoundList.commentUrl]]): its votes' user (`asUserId`,
    * another juror's or 0 for all of them) if the user may view the round so
    * ([[GalleryService.viewAs]], as the large view checks it), else the user's own; its
    * region, view, filter, comments toggle and order from the request.
    */
  private def listOf(user: User, round: Round, region: String, asUserId: Option[Long])(implicit
      request: Request[AnyContent]
  ): RoundList = {
    val viewed = asUserId.filter(id => galleryService.viewAs(user, round, id) == GalleryService.ViewAs.Allowed)
    RoundList.of(round, user, viewed.getOrElse(user.getId), region, GalleryView.of(request))
  }

  /** Adds the comment, then goes back to the file's large view in the list it was made in
    * ([[listOf]]).
    */
  private def addCommentTo(user: User, targetRound: Round, pageId: Long, region: String, asUserId: Option[Long])(implicit
      request: Request[AnyContent]
  ): Result = {
    val roundId = targetRound.getId
    val list = listOf(user, targetRound, region, asUserId)

    editCommentForm.bindFromRequest().fold(
      _ => Redirect(list.largeUrl(pageId)),
      commentBody => {
        CommentJdbc.create(
          user.getId,
          user.fullname,
          roundId,
          Some(targetRound.contestId),
          pageId,
          commentBody.text
        )
        Redirect(list.largeUrl(pageId) + "#comments")
      }
    )
  }

}

object ImageDiscussionController {

  /** The denial reason of a comment on a stopped round's file ([[Secured.Denial]]). */
  val RoundStopped = "round_stopped"
}
