package controllers

import db.scalikejdbc.{ListRepairJdbc, User}
import modules.BlockingExecutionContext
import play.api.i18n.I18nSupport
import play.api.mvc.{AnyContent, ControllerComponents, EssentialAction, Request, Result}
import services.FileListRepair

import javax.inject.Inject

/** Root's "Repair file list" pages (services.FileListRepair, V114): the overview of the contests
  * whose file list row is blank or shared (`/admin/file-lists`), with the rows no contest has any
  * more and the latest repairs; a contest's repair page (its rounds to count, the counts by
  * country with links into the files gallery, Confirm), Undo and the blank row's Rename. Root
  * only: everyone else is denied (403, logged on `security.authz`); signed out, the login page.
  * Every POST carries the CSRF token and answers with a redirect and a flash naming the outcome.
  */
class FileListRepairController @Inject() (cc: ControllerComponents, blocking: BlockingExecutionContext)
    extends Secured(cc)
    with I18nSupport {

  private val root = rolePermission(Set(User.ROOT_ROLE))

  private def form(request: Request[AnyContent]): Map[String, Seq[String]] =
    request.body.asFormUrlEncoded.getOrElse(Map.empty)

  private def rounds(values: Seq[String]): Seq[Long] =
    values.flatMap(v => v.toLongOption).distinct

  def overview(): EssentialAction = withAuthOn(blocking)(root) { user => implicit request =>
    val overview = FileListRepair.overview()
    val names = User.findByIds(overview.recent.flatMap(r => Seq(r.createdBy) ++ r.undoneBy).distinct)
      .map(u => u.getId -> u.fullname).toMap
    Ok(views.html.filelists.overview(user, overview, names)).withHeaders(CACHE_CONTROL -> "no-store")
  }

  /** The repair page: with `chosen`, the ticked `round`s are counted; else the default (every
    * round but the mixed ones).
    */
  def repairPage(contestId: Long, round: List[Long], chosen: Boolean): EssentialAction =
    withAuthOn(blocking)(root) { user => implicit request =>
      FileListRepair.plan(contestId, Option.when(chosen)(round)) match {
        case Left("error.contest.not.found") =>
          NotFound(views.html.errorPage(Some(user), "error.title", "error.contest.not.found"))
        case result =>
          val contest = db.scalikejdbc.ContestJuryJdbc.findById(contestId)
          contest.fold[Result](NotFound(views.html.errorPage(Some(user), "error.title", "error.contest.not.found"))) { c =>
            val repairs = ListRepairJdbc.findByContest(contestId)
            val others = result.toOption.toSeq.flatMap(p => p.others ++ p.rounds.flatMap(_.mixedWith)).distinct
            val contests = db.scalikejdbc.ContestJuryJdbc.findAll().filter(o => others.contains(o.getId)).map(o => o.getId -> o).toMap
            val names = User.findByIds(repairs.flatMap(r => Seq(r.createdBy) ++ r.undoneBy).distinct)
              .map(u => u.getId -> u.fullname).toMap
            Ok(views.html.filelists.repair(user, c, result, repairs, contests, names)).withHeaders(CACHE_CONTROL -> "no-store")
          }
      }
    }

  /** The repair page's form: Recount (the ticked rounds, shown again) or Confirm. Confirm takes the
    * ticked rounds, never the preview's alone: rounds ticked or unticked without Recount are
    * refused (`shown`, the rounds the preview counted), and so is a stale preview (its hash).
    */
  def confirm(contestId: Long): EssentialAction = withAuthOn(blocking)(root) { user => implicit request =>
    val counted = rounds(form(request).getOrElse("round", Nil))
    val shown = form(request).get("shown").flatMap(_.headOption).map(v => rounds(v.split(',').toSeq.map(_.trim)))
    val hash = form(request).get("hash").flatMap(_.headOption).getOrElse("")
    val back = routes.FileListRepairController.repairPage(contestId, counted.toList, chosen = true)
    val action = form(request).get("action").flatMap(_.headOption)
    if (action.contains("recount")) Redirect(back)
    // only the page's Confirm button confirms: a post without it is no repair
    else if (!action.contains("confirm")) BadRequest(views.html.errorPage(Some(user), "error.title", "error.bad.request"))
    else if (shown.exists(_.sorted != counted.sorted))
      Redirect(back).flashing(FileListRepairController.ErrorFlash -> "list.repair.recount.first")
    else
      FileListRepair.confirm(contestId, counted, hash, user.getId).fold(
        {
          case "error.contest.not.found" => NotFound(views.html.errorPage(Some(user), "error.title", "error.contest.not.found"))
          case key                       => Redirect(back).flashing(FileListRepairController.ErrorFlash -> key)
        },
        repair => Redirect(routes.FileListRepairController.repairPage(contestId))
          .flashing(FileListRepairController.DoneFlash -> "list.repair.done", FileListRepairController.RepairFlash -> repair.id.toString,
            FileListRepairController.AddedFlash -> repair.filesAdded.toString)
      )
  }

  def undo(repairId: Long): EssentialAction = withAuthOn(blocking)(root) { user => implicit request =>
    ListRepairJdbc.findById(repairId).fold[Result](
      NotFound(views.html.errorPage(Some(user), "error.title", "list.repair.not.found"))
    ) { found =>
      val back = routes.FileListRepairController.repairPage(found.contestId)
      FileListRepair.undo(repairId, user.getId).fold(
        key => Redirect(back).flashing(FileListRepairController.ErrorFlash -> key),
        repair => Redirect(back).flashing(FileListRepairController.DoneFlash -> "list.repair.undo.done",
          FileListRepairController.RepairFlash -> repair.id.toString)
      )
    }
  }

  def rename(categoryId: Long): EssentialAction = withAuthOn(blocking)(root) { user => implicit request =>
    FileListRepair.rename(categoryId, user.getId).fold(
      {
        case "list.repair.row.not.found" => NotFound(views.html.errorPage(Some(user), "error.title", "list.repair.row.not.found"))
        case key => Redirect(routes.FileListRepairController.overview()).flashing(FileListRepairController.ErrorFlash -> key)
      },
      _ => Redirect(routes.FileListRepairController.overview()).flashing(FileListRepairController.DoneFlash -> "list.repair.renamed",
        FileListRepairController.RepairFlash -> categoryId.toString)
    )
  }
}

object FileListRepairController {
  /** The flash keys: a done outcome's message key, a refusal's, and the repair (or row) it names. */
  val DoneFlash = "list-repair-done"
  val ErrorFlash = "list-repair-error"
  val RepairFlash = "list-repair-id"
  val AddedFlash = "list-repair-added"
}
