package controllers

import db.scalikejdbc.{ContestJuryJdbc, ImageJdbc, User}
import org.intracer.wmua.ContestJury
import org.intracer.wmua.cmd.FetchImageInfo
import org.scalawiki.MwBot
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.I18nSupport
import play.api.mvc.{ControllerComponents, EssentialAction}
import play.api.libs.json.Json
import services.{ImageService, LocalImageCacheService}
import spray.util.pimpFuture

import javax.inject.Inject
import scala.concurrent.ExecutionContext

class ImageController @Inject() (
    val commons: MwBot,
    cc: ControllerComponents,
    imageService: ImageService,
    localImageCacheService: LocalImageCacheService
)(implicit ec: ExecutionContext)
    extends Secured(cc)
    with I18nSupport {

  /** Shows contest images view
    */
  def images(contestId: Long, inProgress: Boolean = false): EssentialAction =
    withAuth(contestPermission(User.ADMIN_ROLES, Some(contestId))) { user => implicit request =>
      val contest = ContestJuryJdbc.findById(contestId).get

      val sourceImageNum = getNumberOfImages(contest)
      val dbImagesNum = ImageJdbc.countByContest(contest)

      val filledForm =
        importImagesForm.fill((contest.images.getOrElse(""), "", ""))
      Ok(
        views.html
          .contest_images(filledForm, contest, user, sourceImageNum, dbImagesNum, inProgress)
      )
    }

  def getNumberOfImages(contest: ContestJury): Long = {
    contest.images.fold(0L) { images =>
      FetchImageInfo(images, Seq.empty, contest, commons).numberOfImages.await
    }
  }

  /** Imports images from commons
    */
  def importImages(contestId: Long): EssentialAction =
    withAuth(contestPermission(User.ADMIN_ROLES, Some(contestId))) { user => implicit request =>
      val contest = ContestJuryJdbc.findById(contestId).get
      importImagesForm
        .bindFromRequest()
        .fold(
          formWithErrors =>
            BadRequest(
              views.html
                .contest_images(formWithErrors, contest, user, 0, ImageJdbc.countByContest(contest))
            ),
          { case (source, list, action) =>
            val withNewImages = contest.copy(images = Some(source))

            if (action == "import.files") {
              imageService.appendImages(source, list, withNewImages)
            } else if (action == "update.monuments") {
              imageService.updateImageMonuments(source, withNewImages)
            }

            Redirect(routes.ImageController.images(contestId))
          }
        )
    }

  def startImageCache(contestId: Long): EssentialAction =
    withAuth(contestPermission(User.ADMIN_ROLES, Some(contestId))) { _ => implicit request =>
      localImageCacheService.startDownload(contestId)
      Redirect(routes.ImageController.images(contestId))
    }

  def imageCacheStatus(contestId: Long): EssentialAction =
    withAuth(contestPermission(User.ADMIN_ROLES, Some(contestId))) { _ => _ =>
      Ok(Json.toJson(localImageCacheService.progress(contestId)))
    }

  def startRoundImageCache(id: Long, rid: Long): EssentialAction =
    withAuth(contestPermission(User.ADMIN_ROLES, Some(id))) { _ => implicit request =>
      localImageCacheService.startDownloadForRound(id, rid)
      Redirect(routes.RoundController.editRound(Some(rid), id, None))
    }

  def roundImageCacheStatus(id: Long, rid: Long): EssentialAction =
    withAuth(contestPermission(User.ADMIN_ROLES, Some(id))) { _ => _ =>
      Ok(Json.toJson(localImageCacheService.progressForRound(rid)))
    }

  val importImagesForm = Form(
    tuple(
      "source" -> text,
      "list" -> text,
      "action" -> text
    )
  )
}
