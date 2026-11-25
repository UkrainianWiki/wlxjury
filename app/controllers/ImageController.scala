package controllers

import db.scalikejdbc.{ContestJuryJdbc, ImageJdbc, Round, User}
import modules.BlockingExecutionContext
import org.intracer.wmua.ContestJury
import play.api.Configuration
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.I18nSupport
import play.api.mvc.{AnyContent, ControllerComponents, EssentialAction, Request, RequestHeader, Result}
import play.api.libs.json.Json
import services.imports.{CommonsSource, FileTitles, ImportSource, SourceName}
import services.jobs.Job
import services.diagnostics.ContestHealth
import services.{ImageService, LocalImageCacheService}

import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.control.NonFatal

class ImageController @Inject() (
    commonsSource: CommonsSource,
    cc: ControllerComponents,
    imageService: ImageService,
    localImageCacheService: LocalImageCacheService,
    blocking: BlockingExecutionContext,
    config: Configuration
)(implicit ec: ExecutionContext)
    extends Secured(cc)
    with I18nSupport {

  /** The time both requests of a source name's check may take together (DG11). */
  private val sourceCheckTimeout: FiniteDuration =
    config.getOptional[FiniteDuration]("diagnostics.sourceCheckTimeout").getOrElse(5.seconds)

  /** Shows contest images view
    */
  def images(contestId: Long): EssentialAction =
    withAuth(contestPermission(User.ADMIN_ROLES, Some(contestId), derived = true)) { user => implicit request =>
      withContest(user, contestId) { contest =>
        val sourceImageNum = getNumberOfImages(contest)
        val dbImagesNum = ImageJdbc.countByContest(contest)

        val filledForm =
          importImagesForm.fill((contest.images.getOrElse(""), "", ""))
        Ok(
          views.html
            .contest_images(filledForm, contest, user, sourceImageNum, dbImagesNum, importJobs(contestId),
              identityWarning = ContestHealth.sourceWarning(contest, contest.images.getOrElse("")))
        )
      }
    }

  /** The typed import source's warning (support diagnostics plan, DG10), asked by the files page's
    * script as the admin types: the same check as the health page's H1 ([[ContestHealth.sourceWarning]]),
    * before anything is queued; a warning only, never a refusal. `{"warning": text}` or
    * `{"warning": null}`; no Commons call.
    */
  def sourceIdentity(contestId: Long, source: String): EssentialAction =
    withAuthJson(contestPermission(User.ADMIN_ROLES, Some(contestId), derived = true)) { _ => implicit request =>
      ContestJuryJdbc.findById(contestId).fold[Result](NotFound(Json.obj("error" -> "not_found"))) { contest =>
        val warning = ContestHealth.sourceWarning(contest, source.take(ImageController.MaxSourceLength))
          .map(check => ImageController.identityWarningText(contest, check)(messagesApi.preferred(request)))
        Ok(Json.obj("warning" -> warning)).withHeaders(CACHE_CONTROL -> "no-store")
      }
    }

  /** `f` of the contest, or the error page if there is none (root may ask for any id). */
  private def withContest(user: User, contestId: Long)(f: ContestJury => Result)(implicit
      request: RequestHeader
  ): Result =
    ContestJuryJdbc.findById(contestId).fold[Result](
      NotFound(views.html.errorPage(Some(user), "error.title", "error.contest.not.found"))
    )(f)

  /** The contest's latest import jobs, each with what it does. */
  private def importJobs(contestId: Long): Seq[(Job, String)] =
    imageService.latestJobs(contestId).map(j => j -> imageService.title(j))

  /** The number of files in the contest's source, if it is a category. */
  def getNumberOfImages(contest: ContestJury): Long =
    contest.images.flatMap(commonsSource.categoryFileCount).getOrElse(0L)

  /** Starts an import ("Import files") or the monuments' update ("Update monuments") from the
    * form's source, or "Import files" from its pasted list.
    *
    * A source name without a namespace prefix ([[SourceName]]) is checked on Commons first, for
    * both actions, before any job is queued (support diagnostics plan, DG11): the page or the
    * category `Category:<name>`, whichever exists; when both do, the form shows the choice with
    * both file counts, the category chosen; when neither does, it says so. When Commons can't be
    * reached within `diagnostics.sourceCheckTimeout` it says so too, keeping the name, and queues
    * nothing: the import needs Commons anyway (the maintainer's decision of 2026-11-18), so the
    * admin tries again later or adds `Category:` themselves. A prefixed source and a pasted list
    * are read as before, without a check. On the blocking pool: the check waits for Commons.
    */
  def importImages(contestId: Long): EssentialAction =
    withAuthOn(blocking)(contestPermission(User.ADMIN_ROLES, Some(contestId), derived = true)) { user => implicit request =>
      withContest(user, contestId) { contest =>
        // the typed source's identity warning (DG10): shown again with the form, never a refusal
        def page(form: Form[(String, String, String)], check: Option[ImageController.SourceCheck] = None) =
          views.html.contest_images(form, contest, user, 0, ImageJdbc.countByContest(contest), importJobs(contestId),
            check, form("source").value.flatMap(ContestHealth.sourceWarning(contest, _)))
        importImagesForm
          .bindFromRequest()
          .fold(
            formWithErrors => BadRequest(page(formWithErrors)),
            { case (source, list, action) =>
              // lines of the list that name no file are shown, not dropped
              val unreadable = if (action == "import.files") FileTitles.unreadable(list) else Nil
              val filled = importImagesForm.fill((source, list, action))
              if (unreadable.nonEmpty)
                BadRequest(page(
                  filled.withGlobalError("files.list.unreadable", unreadable.size, unreadable.take(5).mkString(" · "))))
              else
                checkedSource(contest, source, list, action) match {
                  case ImageController.Checked(checked) => startImport(contest, checked, list, action, user)
                  case ImageController.NoSuchName(name) =>
                    BadRequest(page(filled.withGlobalError("import.source.check.neither", name)))
                  // a question for the admin: which one, or send again (not a 503, which the
                  // proxy's maintenance page and monitoring read as the app's own outage)
                  case check: ImageController.SourceCheck => Ok(page(filled, Some(check)))
                }
            }
          )
      }
    }

  /** The source an import or update starts from ([[importImages]], DG11): the typed one as it
    * is, unless it is a name without a namespace prefix that the action reads (any update; an
    * import without a pasted list), which Commons is asked about on every send. The form's choice
    * (`sourceAs` for `sourceFor`, the same name) counts only when Commons still has both: a
    * forged or stale one never picks something that isn't there, and never skips the check.
    */
  private def checkedSource(contest: ContestJury, source: String, list: String, action: String)(implicit
      request: Request[AnyContent]): ImageController.SourceOutcome = {
    val name = source.trim
    val readsSource = action == "update.monuments" || (action == "import.files" && ImportSource.titles(list).isEmpty)
    if (!readsSource || !SourceName.prefixless(name)) ImageController.Checked(source)
    else {
      val (sourceAs, sourceFor) = sourceChoiceForm.bindFromRequest().value.getOrElse((None, None))
      val chosen = sourceAs.filter(_ => sourceFor.map(_.trim).contains(name))
      try {
        // a choice made: the counts aren't shown again, so the page's files aren't asked for
        val found = commonsSource.probeName(name, sourceCheckTimeout, countPageFiles = chosen.isEmpty)
        (found.page, found.category) match {
          case (true, false)  => ImageController.Checked(source)
          case (false, true)  => ImageController.Checked(SourceName.category(name))
          case (true, true) if chosen.contains(ImageController.AsPage) => ImageController.Checked(source)
          case (true, true) if chosen.contains(ImageController.AsCategory) =>
            ImageController.Checked(SourceName.category(name))
          case (true, true)   =>
            ImageController.BothExist(name, found.categoryFiles, found.pageFiles.getOrElse(0), found.pageFilesCapped)
          case (false, false) => ImageController.NoSuchName(name)
        }
      } catch {
        case NonFatal(e) =>
          // the name is the admin's, quoted; the exception's text is Commons', not logged
          ImageController.logger.warn(s"import_source_check_failed contestId=${contest.getId} " +
            s"source=${Secured.logValue(name)} error=${e.getClass.getSimpleName}")
          ImageController.Unreachable(name)
      }
    }
  }

  /** Queues the import or the update, as the form's action says, from `source`. */
  private def startImport(contest: ContestJury, source: String, list: String, action: String, user: User)(implicit
      request: RequestHeader): Result = {
    // the import runs in the background: the files page shows its progress
    val (jobs, listCopy) =
      if (action == "import.files") {
        val (job, copy) = imageService.importFilesWithList(contest, source, list, user.id)
        (Seq(job), copy)
      } else if (action == "update.monuments") (imageService.updateMonuments(contest, source, user.id), ImageService.ListCopy.Own)
      else (Nil, ImageService.ListCopy.Own)
    // the source's identity warning (DG10) with the queued job: also for a send without the script
    val warning = Option.when(list.trim.isEmpty || action == "update.monuments")(source)
      .flatMap(s => ContestHealth.sourceWarning(contest, s.take(ImageController.MaxSourceLength)))
      .map(ImageController.identityWarningText(contest, _)(messagesApi.preferred(request)))
    val back = Redirect(routes.ImageController.images(contest.getId))
    jobs.collect { case Left(running) => running } match {
      case Nil =>
        // the old list was blank-titled or another contest's too: not copied, as the import decided
        val notCopied = Option.when(listCopy == ImageService.ListCopy.NotCopied)(ImageController.ListNotCopiedFlash -> "1")
        val pairs = (warning.map(ImageController.SourceWarningFlash -> _) ++ notCopied).toSeq
        if (pairs.isEmpty) back else back.flashing(pairs: _*)
      case running =>
        back.flashing("error" ->
          running.map(j => messagesApi.preferred(request)("import.already.running", imageService.title(j))).mkString(" "))
    }
  }

  /** Starts downloading the contest's images to the local cache. The form posted by script
    * (async-form.js) gets 204 and the page polls the status in place; posted without script, back
    * to the files page at its cache section ([[ImageController.ImageCacheSection]]).
    */
  def startImageCache(contestId: Long): EssentialAction =
    withAuthForm(contestPermission(User.ADMIN_ROLES, Some(contestId), derived = true)) { _ => implicit request =>
      localImageCacheService.startDownload(contestId)
      if (Secured.isScriptRequest(request)) NoContent
      else Redirect(routes.ImageController.images(contestId).url + "#" + ImageController.ImageCacheSection)
    }

  def imageCacheStatus(contestId: Long): EssentialAction =
    withAuthJson(contestPermission(User.ADMIN_ROLES, Some(contestId), derived = true)) { _ => _ =>
      Ok(Json.toJson(localImageCacheService.progress(contestId)))
    }

  /** The permission is the contest's, so the round must be one of its: never another's. */
  private def inContest(contestId: Long, roundId: Long): Boolean =
    Round.findById(roundId).exists(_.contestId == contestId)

  /** [[startImageCache]] for a round's images, from its round form. */
  def startRoundImageCache(id: Long, rid: Long): EssentialAction =
    withAuthForm(contestPermission(User.ADMIN_ROLES, Some(id), derived = true)) { user => implicit request =>
      val script = Secured.isScriptRequest(request)
      if (!inContest(id, rid))
        if (script) NotFound(Json.obj("error" -> "round_not_found"))
        else NotFound(views.html.errorPage(Some(user), "error.title", "error.round.not.found"))
      else {
        localImageCacheService.startDownloadForRound(id, rid)
        if (script) NoContent
        else Redirect(routes.RoundController.editRound(Some(rid), id, None).url + "#" + ImageController.RoundImageCacheSection)
      }
    }

  def roundImageCacheStatus(id: Long, rid: Long): EssentialAction =
    withAuthJson(contestPermission(User.ADMIN_ROLES, Some(id), derived = true)) { _ => _ =>
      if (!inContest(id, rid)) NotFound(Json.obj("error" -> "round_not_found"))
      else Ok(Json.toJson(localImageCacheService.progressForRound(rid)))
    }

  val importImagesForm = Form(
    tuple(
      "source" -> services.imports.WikiTitle.mapping,
      "list" -> text,
      "action" -> text
    )
  )

  /** The answer to a source name's check (DG11): how to read the name (`sourceAs`: page or
    * category), and the name it was given for (`sourceFor`), so that an edited name is checked
    * again.
    */
  private val sourceChoiceForm = Form(
    tuple(
      "sourceAs" -> optional(text),
      "sourceFor" -> optional(text)
    )
  )
}

object ImageController {

  /** The id of the files page's "Local image cache" heading: where the form posted without script
    * comes back to ([[ImageController.startImageCache]]). */
  val ImageCacheSection = "image-cache"

  /** The id of the round form's "Local image cache" heading ([[ImageController.startRoundImageCache]]). */
  val RoundImageCacheSection = "round-image-cache"

  /** The flash key of the identity warning shown with a queued import (DG10). */
  val SourceWarningFlash = "sourceWarning"

  /** The flash key of the notice that an import left the contest's previous list of files behind
    * ([[services.ImageService.ListCopy.NotCopied]]).
    */
  val ListNotCopiedFlash = "listNotCopied"

  /** The longest source the identity check reads (a Commons title is at most 255 bytes). */
  val MaxSourceLength = 512

  /** The form's identity warning (DG10): the contest and what the source names, both escaped where
    * shown (Twirl, the script's textContent).
    */
  def identityWarningText(contest: ContestJury, check: services.diagnostics.ContestIdentity.SourceCheck)(implicit
      messages: play.api.i18n.Messages): String =
    messages("import.source.identity.warn", services.diagnostics.ContestIdentity.describe(contest),
      check.identity.describe)

  /** A source name's failed check (WARN): the name quoted, never Commons' text. */
  private val logger = play.api.Logger("services.imports.SourceName")

  /** The form's `sourceAs` values, the both-exist choice's only: read the prefix-less name as
    * the page, or as its category. */
  val AsPage = "page"
  val AsCategory = "category"

  /** The outcome of a source name's check ([[ImageController.importImages]], DG11). */
  sealed trait SourceOutcome

  /** The source to import from. */
  final case class Checked(source: String) extends SourceOutcome

  /** Neither a page nor a category of that name on Commons: refused. */
  final case class NoSuchName(name: String) extends SourceOutcome

  /** What the files page shows with the form for a name it couldn't take as it is. */
  sealed trait SourceCheck extends SourceOutcome {
    def name: String
  }

  /** Both a page and a category of that name: the admin chooses, the category preselected
    * (the maintainer's refinement of DG11, 2026-11-18: a page may exist by coincidence, or the
    * admin may have pasted the wrong name). `pageFiles`: the files the page shows, at most
    * [[services.imports.NameProbe.PageFilesCap]] (`pageFilesCapped`: more).
    */
  final case class BothExist(name: String, categoryFiles: Long, pageFiles: Int, pageFilesCapped: Boolean)
      extends SourceCheck {
    def pageFilesText: String = if (pageFilesCapped) s"$pageFiles+" else pageFiles.toString
  }

  /** Commons couldn't be reached or timed out: nothing guessed, nothing queued; the admin tries
    * again later or adds `Category:` themselves.
    */
  final case class Unreachable(name: String) extends SourceCheck
}
