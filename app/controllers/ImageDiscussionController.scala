package controllers

import db.scalikejdbc.{Round, User}
import org.intracer.wmua.CommentJdbc
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.I18nSupport
import play.api.mvc.{ControllerComponents, EssentialAction}

import javax.inject.Inject

case class CommentBody(id: Long, text: String)

class ImageDiscussionController @Inject()(cc: ControllerComponents)
    extends Secured(cc)
      with I18nSupport {

  val editCommentForm = Form(
    mapping(
      "id" -> longNumber(),
      "text" -> text()
    )(CommentBody.apply)(CommentBody.unapply)
  )

  def addComment(pageId: Long,
                 region: String = "all",
                 rate: Option[Int],
                 module: String,
                 round: Option[Long],
                 contestId: Option[Long]): EssentialAction = withAuth() {
    user => implicit request =>
      round
        .flatMap(Round.findById)
        .filter(targetRound => contestId.forall(_ == targetRound.contestId))
        .filter(targetRound =>
          roundPermission(
            User.ADMIN_ROLES ++
              User.JURY_ROLES ++
              User.ORG_COM_ROLES,
            targetRound.getId
          )(user)
        )
        .fold(onUnAuthorized(user)) { targetRound =>
          val roundId = targetRound.getId

          editCommentForm.bindFromRequest().fold(
            _ =>
              Redirect(
                routes.LargeViewController
                  .large(user.getId, pageId, region, roundId, rate, module)
              ),
            commentBody => {
              CommentJdbc.create(
                user.getId,
                user.fullname,
                roundId,
                Some(targetRound.contestId),
                pageId,
                commentBody.text
              )
              Redirect(
                routes.LargeViewController
                  .large(user.getId, pageId, region, roundId, rate, module)
                  .url
                  .concat("#comments")
              )
            }
          )
        }
  }

}
