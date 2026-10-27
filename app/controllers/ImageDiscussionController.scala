package controllers

import db.scalikejdbc.{Round, SelectionJdbc, User}
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
    * contest ([[Secured.inContest]]); the session's contest is not switched (a POST).
    */
  def addComment(pageId: Long,
                 region: String = "all",
                 round: Option[Long],
                 contestId: Option[Long]): EssentialAction = withAuth() {
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
          else addCommentTo(user, targetRound, pageId, region)
        }
  }

  /** Whether the user may see the file's comments in the round's large view: one of the
    * roles in the round's contest, may view the round, and the file is in their view of
    * it (all the round's files for those who see its organiser info, else their own).
    */
  private def mayComment(user: User, round: Round, pageId: Long, roles: Set[String]): Boolean =
    contestPermission(roles, Some(round.contestId))(user) &&
      galleryService.mayViewRound(user, round) &&
      SelectionJdbc.inRound(round.getId, pageId, Option.unless(user.canViewOrgInfo(round))(user.getId))

  /** Adds the comment, then goes back to the file's large view in the user's list it was
    * made in ([[RoundList.commentUrl]]).
    */
  private def addCommentTo(user: User, targetRound: Round, pageId: Long, region: String)(implicit
      request: Request[AnyContent]
  ): Result = {
    val roundId = targetRound.getId
    val list = RoundList.of(targetRound, user.getId, region, GalleryView.of(request))

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
