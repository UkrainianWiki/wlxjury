package controllers

import controllers.EditRound.{editRoundForm, jurorsMapping}
import db.scalikejdbc._
import org.scalawiki.wlx.dto.SpecialNomination
import play.api.Logging
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.I18nSupport
import play.api.libs.json.Json
import play.api.mvc.{AnyContent, ControllerComponents, EssentialAction, Request, RequestHeader, Result}
import play.twirl.api.Html
import services.RoundService
import services.distribution.RoundDistributionHandler
import services.imports.{FileTitles, ImportSource}
import services.jobs.JobJdbc

import javax.inject.Inject
import modules.BlockingExecutionContext
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Controller for displaying pages related to contest rounds
  * @param contestsController
  */
class RoundController @Inject() (
    cc: ControllerComponents,
    val contestsController: ContestController,
    roundsService: RoundService,
    blocking: BlockingExecutionContext
) extends Secured(cc)
    with I18nSupport
    with Logging {

  /** Shows list of rounds in a contest
    * @param contestIdParam
    * @return
    */
  def rounds(contestIdParam: Option[Long] = None): EssentialAction =
    withAuthOn(blocking)(contestPermission(User.ADMIN_ROLES, contestIdParam)) { user => implicit request =>
      val roundsView =
        for (
          contestId <- contestIdParam.orElse(user.currentContest);
          contest <- ContestJuryJdbc.findById(contestId)
        ) yield {
          val rounds = Round.findByContest(contestId)
          Ok(
            views.html.rounds(
              user,
              rounds,
              RoundImageCounts.get(rounds.flatMap(_.id))(ImageJdbc.imageCountByRounds),
              JobJdbc.latestBySubjects(RoundDistributionHandler.Kind, RoundDistributionHandler.SubjectType,
                rounds.flatMap(_.id)),
              editRoundForm,
              imagesForm.fill(contest.images),
              selectRoundForm,
              rounds.find(_.id == contest.currentRound),
              contest
            )
          )
        }
      roundsView.getOrElse(Redirect(routes.LoginController.index)) // TODO message
    }

  /** Shows round editing page
    * @param roundId
    * @param contestId
    * @param topImages
    * @return
    */
  def editRound(roundId: Option[Long], contestId: Long, topImages: Option[Int]): EssentialAction =
    withAuthOn(blocking)(contestPermission(User.ADMIN_ROLES, Some(contestId))) { user => implicit request =>
      val rounds = Round.findByContest(contestId)

      // From the permission-checked contest's rounds: a round of another contest gets
      // the new-round form, not its settings.
      val round: Round = roundId
        .flatMap(id => rounds.find(_.id.contains(id)))
        .getOrElse(
          new Round(id = None, contestId = contestId, number = rounds.size + 1)
        )

      val withTopImages = topImages.map(n => round.copy(topImages = Some(n))).getOrElse(round)

      val jurors = withTopImages.id.fold(User.loadJurors(contestId))(User.findRoundJurors).sorted
      val filledRound = editRoundForm.fill(EditRound(withTopImages, jurors.flatMap(_.id), None))
      Ok(
        roundFormView(user, withTopImages, filledRound, Some(rounds), Some(jurors),
          submitToken = Option.when(withTopImages.id.isEmpty)(RoundController.newSubmitToken()))
      )
    }

  /** Renders the create/edit round page for `round` with the given (possibly
    * error-carrying) form. Shared by the GET handler and the failure paths of
    * [[saveRound]] so a failed create/redistribute comes back as the same form with
    * the entered values intact, not a redirect that drops them.
    *
    * The page does no expensive work: the jurors' stat table and the number of new
    * files are loaded on demand ([[roundStatTable]], [[newFilesCount]]).
    */
  private def roundFormView(
      user: User,
      round: Round,
      form: Form[EditRound],
      knownRounds: Option[Seq[Round]] = None,
      knownJurors: Option[Seq[User]] = None,
      submitToken: Option[String] = None
  )(implicit request: RequestHeader): Html = {
    val contestId = round.contestId
    views.html.editRound(
      user,
      form,
      round.id.isEmpty,
      knownRounds.getOrElse(Round.findByContest(contestId)),
      Some(contestId),
      knownJurors.getOrElse(round.id.fold(User.loadJurors(contestId))(User.findRoundJurors).sorted),
      jurorsMapping,
      contestsController.regions(contestId),
      contestSpecialNominations(contestId),
      submitToken,
      round.id.flatMap(roundsService.distributionJob)
    )
  }

  /** The number of files "Distribute new files" would add to the round, as JSON
    * `{"count": n}`: the distribution's own windows and filters, read only (which may
    * query Commons), so it runs only when the organizer asks for it. Off the request
    * threads: on one thread of its own ([[RoundController.countContext]]), so a few
    * counts at once queue there instead of holding the blocking dispatcher. Errors are
    * JSON too, and the request never switches the session's contest.
    */
  def newFilesCount(id: Long): EssentialAction =
    Action.async { request =>
      implicit val ec: ExecutionContext = blocking
      Future(userFromRequest(request)).flatMap {
        case None => Future.successful(Unauthorized(Json.obj("error" -> "unauthenticated")))
        case Some(user) =>
          Future(Round.findById(id)).flatMap {
            case None => Future.successful(NotFound(Json.obj("error" -> "round not found")))
            case Some(round) if !adminOfRound(user, round) =>
              Future.successful(onUnAuthorizedJson(user, request, Secured.Denial.round(round, User.ADMIN_ROLES)))
            case Some(round) =>
              Future(Ok(Json.obj("count" -> roundsService.newFilesCount(round.getId))))(RoundController.countContext)
          }
      }
    }

  /** The jurors' stat table of a round, as an HTML fragment for the edit page's
    * "jurors" panel, loaded when the panel is opened.
    */
  def roundStatTable(roundId: Long): EssentialAction =
    withAuthOn(blocking)(rolePermission(User.ADMIN_ROLES)) { user => implicit request =>
      withAdminRound(user, roundId, NotFound(""), request) { round =>
        Ok(views.html.roundStatTable(user, round, roundsService.getRoundStat(roundId, round)))
      }
    }

  /** Runs `f` with the round if `user` administers its contest: the round is loaded
    * once, for the permission check and the action. Checked in the round's contest, which
    * may be another of the user's than the session's; the session stays as it is.
    */
  private def withAdminRound(
      user: User,
      roundId: Long,
      notFound: => Result,
      request: RequestHeader
  )(f: Round => Result): Result =
    Round.findById(roundId) match {
      case None                                     => notFound
      case Some(round) if adminOfRound(user, round) => f(round)
      case Some(round)                              => onUnAuthorized(user, request, Secured.Denial.round(round, User.ADMIN_ROLES))
    }

  /** Whether `user` administers the round's contest, as an active member of it. */
  private def adminOfRound(user: User, round: Round): Boolean =
    contestPermission(User.ADMIN_ROLES, Some(round.contestId))(Secured.inContest(user, round.contestId))

  def contestSpecialNominations(contestId: Long): Seq[SpecialNomination] = {
    ContestJuryJdbc
      .findById(contestId)
      .map { contest =>
        if (contest.name == "Wiki Loves Monuments" && contest.country == "Ukraine")
          SpecialNomination.nominations
            .filter(_.years.contains(contest.year))
            .sortBy(_.name)
        else Nil
      }
      .getOrElse(Nil)

  }

  def saveRound(): EssentialAction =
    withAuthOn(blocking)(rolePermission(User.ADMIN_ROLES)) { user => implicit request =>
      editRoundForm
        .bindFromRequest()
        .fold(
          formWithErrors => {
            val contestId: Option[Long] = formWithErrors.data.get("contest").flatMap(_.toLongOption)
            // the form lists the contest's rounds and jurors: only for its own admins
            if (!contestPermission(User.ADMIN_ROLES, contestId)(user))
              onUnAuthorized(user, request, Secured.Denial.contest(contestId, User.ADMIN_ROLES))
            else
              BadRequest(
                views.html.editRound(
                  user,
                  formWithErrors,
                  newRound = !formWithErrors.data.get("id").exists(_.nonEmpty),
                  rounds = contestId.map(Round.findByContest).getOrElse(Nil),
                  contestId = contestId,
                  jurors = contestId.map(User.loadJurors).getOrElse(Nil),
                  jurorsMapping = jurorsMapping,
                  // keep the form's token: nothing was created from this submission
                  submitToken = RoundController.submitToken(request)
                )
              )
          },
          editForm => {
            val round = editForm.round.copy(active = true)
            val contestId = round.contestId
            val toRoundsList = Redirect(routes.RoundController.rounds(Some(contestId)))
            // "Add files": added by the round's distribution job, after the files its filters choose
            val titles = ImportSource.titles(editForm.fileList.getOrElse(""))

            // Re-render the same create/edit form with the entered values kept and the
            // failure shown as a global form error, so the admin can fix and resubmit
            // instead of losing the page.
            def reRender(r: Round, message: String, args: Any*): Result =
              BadRequest(
                roundFormView(
                  user,
                  r,
                  editRoundForm
                    .fill(EditRound(r, editForm.jurors, editForm.fileList, editForm.newImages))
                    .withGlobalError(message, args: _*),
                  // a creation rejected before anything was created keeps the form's token
                  submitToken = RoundController.submitToken(request).filter(_ => r.id.isEmpty)
                )
              )

            def detail(e: Throwable): String =
              Option(e.getMessage)
                .filter(_.nonEmpty)
                .getOrElse(e.toString)
                // keep MessageFormat (used by Messages(key, args)) from choking on the text
                .replace("'", "''")
                .replace("{", "(")
                .replace("}", ")")
                .take(500)

            // "Add files": lines that name no file are shown, not dropped
            val unreadable = FileTitles.unreadable(editForm.fileList.getOrElse(""))
            def unreadableLines: Result =
              reRender(round, "files.list.unreadable", unreadable.size, unreadable.take(5).mkString(" · "))

            // the submitted contest must be one the user administers; an edited round
            // must belong to it (checked below)
            if (!contestPermission(User.ADMIN_ROLES, Some(contestId))(user))
              onUnAuthorized(user, request, Secured.Denial.contest(Some(contestId), User.ADMIN_ROLES))
            else round.id match {
              case None if unreadable.nonEmpty => unreadableLines
              case None =>
                try {
                  roundsService.submit(round, editForm.jurors, RoundController.submitToken(request), user.id, titles) match {
                    // the distribution runs in the background: the rounds list shows its progress
                    case _: RoundService.Created => toRoundsList
                    // The same form submitted again (a double click, or Back and Submit):
                    // no second round; a failed distribution of the first was resumed.
                    case resubmitted: RoundService.Resubmitted =>
                      logger.warn(s"Contest $contestId: ${resubmitted.message}")
                      toRoundsList.flashing("error" -> resubmitted.message)
                  }
                } catch {
                  // Rejected before anything was created (bad input): keep the form.
                  case NonFatal(e) =>
                    logger.warn(s"Rejected round creation for contest $contestId: ${detail(e)}")
                    reRender(round, "round.creation.failed", detail(e))
                }

              case Some(roundId) =>
                withAdminRound(user, roundId, NotFound(s"Round $roundId not found"), request) {
                  case stored if stored.contestId != contestId =>
                    onUnAuthorized(user, request,
                      Secured.Denial.round(stored, User.ADMIN_ROLES).copy(reason = Some("round_in_other_contest")))
                  case _ if unreadable.nonEmpty => unreadableLines
                  case stored =>
                    val notStarted =
                      if (Round.updateRound(roundId, round)) Nil
                      else Seq("error" -> RoundController.stillDistributing(stored))
                    if (!editForm.newImages && titles.isEmpty) toRoundsList.flashing(notStarted: _*)
                    else
                      try {
                        roundsService.distributeNewFiles(roundId, user.id, titles, newFiles = editForm.newImages) match {
                          case RoundService.AlreadyRunning(_) =>
                            toRoundsList.flashing(
                              "error" -> (s"Round ${stored.number}: its images are already being distributed." +
                                (if (titles.nonEmpty) " The listed files were not added: add them again once it has finished."
                                 else "")))
                          case RoundService.Resumed(_) if titles.nonEmpty =>
                            toRoundsList.flashing(
                              "error" -> (s"Round ${stored.number}: its failed distribution was resumed instead of " +
                                "adding the listed files. Add them again once it has finished."))
                          case _ => toRoundsList.flashing(notStarted: _*)
                        }
                      } catch {
                        case NonFatal(e) =>
                          logger.warn(s"Round $roundId: could not start distributing new files: ${detail(e)}")
                          reRender(Round.findById(roundId).getOrElse(round), "round.distribute.failed", detail(e))
                      }
                }
            }
          }
        )
    }

  /** Starts or stops a round of a contest the user administers. */
  def setRound(): EssentialAction = withAuth(rolePermission(User.ADMIN_ROLES)) {
    user => implicit request =>
      selectRoundForm
        .bindFromRequest()
        .fold(
          _ => BadRequest,
          selectRound =>
            selectRound.roundId.toLongOption.fold[Result](BadRequest) { id =>
              withAdminRound(user, id, NotFound(s"Round $id not found"), request) { round =>
                val toList = Redirect(routes.RoundController.rounds(Some(round.contestId)))
                if (roundsService.setCurrentRound(Nil, round.copy(active = selectRound.active))) toList
                else toList.flashing("error" -> RoundController.stillDistributing(round))
              }
            }
        )
  }

  /** Activates or deactivates a juror in a round of a contest the user administers.
    * Only that round's round_user row changes.
    */
  def setRoundUser(): EssentialAction =
    withAuth(rolePermission(User.ADMIN_ROLES)) { user => implicit request =>
      setRoundUserForm
        .bindFromRequest()
        .fold(
          _ => BadRequest,
          setRoundUser =>
            (setRoundUser.roundId.toLongOption, setRoundUser.userId.toLongOption) match {
              case (Some(roundId), Some(userId)) =>
                withAdminRound(user, roundId, NotFound(s"Round $roundId not found"), request) { _ =>
                  RoundUser.setActive(roundId, userId, setRoundUser.active)
                  Redirect(routes.RoundController.roundStat(roundId))
                }
              case _ => BadRequest
            }
        )
    }

  /** Sets the images source of `contestIdParam`, else of the user's current contest. */
  def setImages(contestIdParam: Option[Long] = None): EssentialAction =
    withAuth(rolePermission(User.ADMIN_ROLES)) { user => implicit request =>
      contestIdParam.orElse(user.currentContest) match {
        case None => Redirect(routes.ContestController.list())
        case contestId if !contestPermission(User.ADMIN_ROLES, contestId)(user) =>
          onUnAuthorized(user, request, Secured.Denial.contest(contestId, User.ADMIN_ROLES))
        case contestId =>
          val imagesSource: Option[String] = imagesForm.bindFromRequest().get
          for (contest <- contestId.flatMap(ContestJuryJdbc.findById)) {
            // the source only: the contest's files stay those of its imports
            ContestJuryJdbc.setImagesSource(contest.getId, imagesSource, contest.categoryId)

            // val images: Seq[Page] = Await.result(Global.commons.categoryMembers(PageQuery.byTitle(imagesSource.get)), 1.minute)

            //          for (contestId <- contest.id;
            //               currentRoundId <- ContestJuryJdbc.currentRound(contestId);
            //               round <- RoundJdbc.find(currentRoundId)) {
            //            Tools.distributeImages(round, round.jurors, None)
            //          }
          }

          Redirect(routes.RoundController.rounds(contestId))
      }
    }

  def currentRoundStat(contestId: Option[Long] = None): EssentialAction =
    withAuth(rolePermission(Set(User.ADMIN_ROLE, "jury", "root") ++ User.ORG_COM_ROLES)) {
      sessionUser => implicit request =>
        // the asked-for contest's rounds are checked as a member of it, if an active one
        val user = contestId.fold(sessionUser)(Secured.inContest(sessionUser, _))
        val currentContestId = contestId.orElse(user.currentContest)
        val activeRound = Round
          .activeRounds(user)
          .headOption
          .orElse {
            currentContestId.flatMap { contestId =>
              Round
                .activeRounds(contestId)
                .filter(r => user.canViewOrgInfo(r))
                .lastOption
            }
          }
          .orElse {
            currentContestId.flatMap { contestId =>
              Round
                .findByContest(contestId)
                .filter(r => user.canViewOrgInfo(r))
                .lastOption
            }
          }

        Secured.switchingContest(sessionUser, user) {
          activeRound
            .map { round =>
              Redirect(routes.RoundController.roundStat(round.getId))
            }
            .getOrElse {
              NotFound(views.html.errorPage(Some(user), "error.title", "no.round.yet"))
            }
        }
    }

  def roundStat(roundId: Long): EssentialAction =
    withAuthOn(blocking)(rolePermission(Set(User.ADMIN_ROLE, "jury", "root") ++ User.ORG_COM_ROLES)) {
      sessionUser => implicit request =>
        Round
          .findById(roundId)
          .map { round =>
            // a round of another of the user's contests opens in that contest
            val user = Secured.inContest(sessionUser, round.contestId)
            if (!user.canViewOrgInfo(round)) {
              onUnAuthorized(user, request, Secured.Denial.round(round, User.ADMIN_ROLES ++ User.ORG_COM_ROLES))
            } else {
              val stat = roundsService.getRoundStat(roundId, round, withDeletedFiles = true)

              Secured.switchingContest(sessionUser, user)(Ok(views.html.roundStat(user, round, stat)))
            }
          }
          .getOrElse {
            NotFound(views.html.errorPage(Some(sessionUser), "error.title", "error.round.not.found"))
          }
    }

  /** Merges two rounds of the target round's contest (RoundService checks the source is
    * in it too).
    */
  def mergeRounds(): EssentialAction =
    withAuth(rolePermission(User.ADMIN_ROLES)) { user => implicit request =>
      val mergeRounds = mergeRoundsForm.bindFromRequest().get
      Round.findById(mergeRounds.targetRoundId) match {
        case Some(target) if contestPermission(User.ADMIN_ROLES, Some(target.contestId))(user) =>
          roundsService.mergeRounds(
            target.contestId,
            mergeRounds.targetRoundId,
            mergeRounds.sourceRoundId
          )
          Redirect(routes.RoundController.rounds(Some(target.contestId)))
        case Some(target) => onUnAuthorized(user, request, Secured.Denial.round(target, User.ADMIN_ROLES))
        case None =>
          onUnAuthorized(user, request,
            Secured.Denial(resource = Some(s"round:${mergeRounds.targetRoundId}"), requiredRoles = User.ADMIN_ROLES))
      }
    }

  //  def byRate(roundId: Int) = withAuth({
  //    user =>
  //      implicit request =>
  //        val round: Round = Round.find(roundId.toLong).get
  //        val rounds = Round.findByContest(user.contest)
  //
  //        val images = Image.byRoundMerged(round.id.toInt)
  //
  ////        val byUserCount = selection.groupBy(_.juryId).mapValues(_.size)
  ////        val byUserRateCount = selection.groupBy(_.juryId).mapValues(_.groupBy(_.rate).mapValues(_.size))
  ////
  ////        val totalCount = selection.map(_.pageId).toSet.size
  ////        val totalByRateCount = selection.groupBy(_.rate).mapValues(_.map(_.pageId).toSet.size)
  //
  //        val imagesByRate = images.sortBy(-_.totalRate)
  //
  ////        Ok(views.html.galleryByRate(user, round, imagesByRate))
  //  })

  val imagesForm = Form("images" -> optional(text))

  val selectRoundForm = Form(
    mapping(
      "currentId" -> text,
      "setActive" -> boolean
    )(SelectRound.apply)(SelectRound.unapply)
  )

  val setRoundUserForm = Form(
    mapping(
      "parentId" -> text,
      "currentId" -> text,
      "setActive" -> boolean
    )(SetRoundUser.apply)(SetRoundUser.unapply)
  )

  val mergeRoundsForm = Form(
    mapping(
      "targetRoundId" -> longNumber,
      "sourceRoundId" -> longNumber
    )(MergeRoundsForm.apply)(MergeRoundsForm.unapply)
  )
  case class MergeRoundsForm(targetRoundId: Long, sourceRoundId: Long)

}

object RoundController {

  /** "Count new files" runs here, one at a time: a full read of the round's candidates. */
  private[controllers] lazy val countContext: ExecutionContext =
    ExecutionContext.fromExecutor(java.util.concurrent.Executors.newSingleThreadExecutor { (r: Runnable) =>
      val t = new Thread(r, "count-new-files")
      t.setDaemon(true)
      t
    })

  def stillDistributing(round: Round): String =
    s"Round ${round.number} (${round.description}) can't be started yet: its images are still being " +
      "distributed. It starts by itself once its distribution has finished."

  private val TokenPattern = "[0-9a-fA-F-]{36}".r

  /** A one-time token for a new-round form (see RoundService.submit). */
  def newSubmitToken(): String = java.util.UUID.randomUUID().toString

  /** The submission token the new-round form sent, if it is one. */
  def submitToken(request: Request[AnyContent]): Option[String] =
    request.body.asFormUrlEncoded
      .flatMap(_.get("submitToken"))
      .flatMap(_.headOption)
      .map(_.trim)
      .filter(TokenPattern.matches)
}

case class SelectRound(roundId: String, active: Boolean)

case class SetRoundUser(roundId: String, userId: String, active: Boolean)

case class RoundStat(
    jurors: Seq[User],
    round: Round,
    rounds: Seq[Round],
    byUserCount: Map[Long, Int],
    byUserRateCount: Map[Long, Map[Int, Int]],
    total: Int,
    totalByRate: Map[Int, Int],
    deletedFiles: Int = 0,
    deletedCounted: Int = 0
)
// deletedFiles: the round's files deleted on Commons (ImageJdbc.roundDeletedFiles);
// deletedCounted: those of them still in its galleries and results
