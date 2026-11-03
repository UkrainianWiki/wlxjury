package controllers

import db.scalikejdbc.{ContestJuryJdbc, ImageJdbc, User}
import org.intracer.wmua.ContestJury
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.I18nSupport
import play.api.mvc.{ControllerComponents, EssentialAction}
import play.api.libs.json.Json
import services.imports.{CommonsSource, FileTitles}
import services.jobs.Job
import services.{ImageService, LocalImageCacheService}

import javax.inject.Inject
import scala.concurrent.ExecutionContext

class ImageController @Inject() (
    commonsSource: CommonsSource,
    cc: ControllerComponents,
    imageService: ImageService,
    localImageCacheService: LocalImageCacheService
)(implicit ec: ExecutionContext)
    extends Secured(cc)
    with I18nSupport {

  /** Shows contest images view
    */
  def images(contestId: Long): EssentialAction =
    withAuth(_.canManageContestFiles(contestId)) { user => implicit request =>
      val contest = ContestJuryJdbc.findById(contestId).get

      val sourceImageNum = getNumberOfImages(contest)
      val dbImagesNum = ImageJdbc.countByContest(contest)

      val filledForm =
        importImagesForm.fill((contest.images.getOrElse(""), "", ""))
      Ok(
        views.html
          .contest_images(filledForm, contest, user, sourceImageNum, dbImagesNum, importJobs(contestId))
      )
    }

  /** The contest's latest import jobs, each with what it does. */
  private def importJobs(contestId: Long): Seq[(Job, String)] =
    imageService.latestJobs(contestId).map(j => j -> imageService.title(j))

  /** The number of files in the contest's source, if it is a category. */
  def getNumberOfImages(contest: ContestJury): Long =
    contest.images.flatMap(commonsSource.categoryFileCount).getOrElse(0L)

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
                .contest_images(formWithErrors, contest, user, 0, ImageJdbc.countByContest(contest), importJobs(contestId))
            ),
          { case (source, list, action) =>
            // lines of the list that name no file are shown, not dropped
            val unreadable = if (action == "import.files") FileTitles.unreadable(list) else Nil
            if (unreadable.nonEmpty)
              BadRequest(
                views.html.contest_images(
                  importImagesForm
                    .fill((source, list, action))
                    .withGlobalError("files.list.unreadable", unreadable.size, unreadable.take(5).mkString(" · ")),
                  contest, user, 0, ImageJdbc.countByContest(contest), importJobs(contestId)
                )
              )
            else {
              // the import runs in the background: the files page shows its progress
              val jobs =
                if (action == "import.files") Seq(imageService.importFiles(contest, source, list, user.id))
                else if (action == "update.monuments") imageService.updateMonuments(contest, source, user.id)
                else Nil
              val back = Redirect(routes.ImageController.images(contestId))
              jobs.collect { case Left(running) => running } match {
                case Nil => back
                case running =>
                  back.flashing("error" -> running.map(j => s"Already running: ${imageService.title(j)}.").mkString(" "))
              }
            }
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
