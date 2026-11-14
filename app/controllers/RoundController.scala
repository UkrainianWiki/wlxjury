package controllers

import controllers.EditRound.{editRoundForm, jurorsMapping}
import db.scalikejdbc._
import org.scalawiki.wlx.dto.SpecialNomination
import play.api.Logging
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.{I18nSupport, Messages}
import play.api.libs.json.Json
import play.api.mvc.{AnyContent, ControllerComponents, EssentialAction, Request, RequestHeader, Result}
import play.twirl.api.Html
import services.RoundService
import services.distribution.{RoundDistributionHandler, RoundImageCheckHandler}
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
              contest,
              // root's all-rounds check: not shown to a contest's admins
              Option.when(user.isRoot)(JobJdbc.latestByDedupeKey(RoundImageCheckHandler.AllDedupeKey)).flatten,
              // and root's author normalization
              Option.when(user.isRoot)(JobJdbc.latestByDedupeKey(services.imports.AuthorNormalizeHandler.DedupeKey)).flatten
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
          // a new round leaves out conflicts of interest unless unticked, and picks a top-N by
          // the default order unless another is chosen
          new Round(id = None, contestId = contestId, number = rounds.size + 1, excludeCoi = true,
            topBinaryOrder = Some(org.intracer.wmua.BinaryOrder.Default))
        )

      // a round's criteria; a new round's are those of the contest's latest round, if it has
      // some (U3), with its rating and half stars
      val withCriteria = round.id match {
        case Some(id) => round.copy(criterionIds = RoundCriterion.idsOf(id))
        case None =>
          rounds.lastOption.filter(_.hasCriteria).fold(round) { previous =>
            round.copy(criterionIds = RoundCriterion.idsOf(previous.getId), rates = previous.rates,
              halfStar = previous.halfStar)
          }
      }
      // a top-N of 0 (a legacy round's stored 0, or ?topImages=0) is none: the form's field takes
      // at least 1, so the round's edit form would not save with it
      val withTopImages = withCriteria.copy(topImages = topImages.orElse(withCriteria.topImages).filter(_ > 0))

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
    val rounds = knownRounds.getOrElse(Round.findByContest(contestId))
    views.html.editRound(
      user,
      form,
      round.id.isEmpty,
      rounds,
      Some(contestId),
      knownJurors.getOrElse(round.id.fold(User.loadJurors(contestId))(User.findRoundJurors).sorted),
      jurorsMapping,
      contestsController.regions(contestId),
      contestSpecialNominations(contestId),
      submitToken,
      round.id.flatMap(roundsService.distributionJob),
      Some(coiCounts(contestId, round.id)),
      CriterionJdbc.all(),
      round.id.flatMap(id => RoundController.ScoreRuleWarning.of(id, rounds)),
      // the check is queued by the contest's admins (and root): this page is theirs
      round.id.flatMap(id =>
        JobJdbc.latestBySubjects(RoundImageCheckHandler.Kind, RoundImageCheckHandler.SubjectType, Seq(id)).get(id))
    )
  }

  /** The round form's conflicts of interest: the contest's files that are, the round's too
    * for an existing round, and the contest's files without a stored author.
    */
  private def coiCounts(contestId: Long, roundId: Option[Long]): CoiCounts = {
    val members = CoiMembers.forContest(contestId)
    ContestJuryJdbc.findById(contestId).flatMap(_.categoryId).fold(CoiCounts(0, None, 0L)) { categoryId =>
      val round = roundId.flatMap(Round.findById)
      CoiCounts(CoiMembers.files(categoryId, members).size, roundId.map(CoiMembers.roundFiles(_, members).size),
        CoiMembers.filesWithoutAuthor(categoryId), excluded = round.exists(_.excludeCoi),
        open = round.exists(r => r.active || r.distributing || r.freezeCount > 0))
    }
  }

  /** The round form's "Leave out conflicts of interest" saved for an existing round. Ticked on
    * a round that didn't leave them out: disqualifies its conflict-of-interest files, keeping
    * their votes aside, and leaves them out of its later distributions
    * ([[RoundConflicts.disqualify]]: only while the round is not open to its jurors, W7, and no
    * distribution of it is queued or running; on an open or frozen round only the flag is set).
    * Ticked on a round that already leaves them out: nothing, so a file requalified on the
    * round's conflicts page stays (review finding 2); later matches are disqualified there.
    * Unticked: stops leaving them out; files disqualified stay so, later distributions may add
    * the others.
    *
    * @return the flash messages to show
    */
  private def leaveOutConflicts(stored: Round, exclude: Boolean, user: User)(implicit
      messages: Messages
  ): Seq[(String, String)] = {
    val roundId = stored.getId
    if (!exclude) {
      if (stored.excludeCoi) Round.setExcludeCoi(roundId, excludeCoi = false)
      Nil
    } else if (stored.excludeCoi) Nil
    else {
      val inRound = CoiMembers.roundFiles(roundId, CoiMembers.forContest(stored.contestId))
      // the flag is set under the round's lock, also when the round is open or frozen
      RoundConflicts.disqualify(roundId, user, RoundDistributionHandler.dedupeKey(roundId), excludeCoi = true) match {
        case RoundConflicts.Outcome.Disqualified(files) =>
          if (files.isEmpty) Nil else Seq("success" -> messages("coi.disqualified", files.size, stored.number))
        // refused before the flag was set: said whether or not the round has conflicts
        case RoundConflicts.Outcome.Distributing => Seq("error" -> messages("coi.remove.distributing", stored.number))
        case _ if inRound.isEmpty                => Nil
        // the flag is set, and a later save changes nothing: the round's conflicts page, linked
        // on the rounds list (`coiRound`), disqualifies them once the round is stopped
        case RoundConflicts.Outcome.Open =>
          Seq("error" -> messages("coi.disqualify.open", stored.number, inRound.size), "coiRound" -> roundId.toString)
        case RoundConflicts.Outcome.Frozen =>
          Seq("error" -> messages("coi.disqualify.frozen", stored.number), "coiRound" -> roundId.toString)
        case _ => Nil
      }
    }
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

  private def roundNotFound(user: User)(implicit request: Request[AnyContent]): Result =
    NotFound(views.html.errorPage(Some(user), "error.title", "error.round.not.found"))

  /** Whether `user` administers the round's contest, as an active member of it
    * ([[Secured.administersRound]], which the statistics page's pause button shows by too).
    */
  private def adminOfRound(user: User, round: Round): Boolean = Secured.administersRound(user, round)

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
                  submitToken = RoundController.submitToken(request),
                  criteria = CriterionJdbc.all()
                )
              )
          },
          editForm => {
            // a new round starts once distributed; an edit leaves it started or stopped
            // (setRound starts and stops it)
            val round = editForm.round
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
                    .fill(EditRound(r, editForm.jurors, editForm.fileList, editForm.newImages, editForm.criteriaShown))
                    .withGlobalError(message, args: _*),
                  // a creation rejected before anything was created keeps the form's token
                  submitToken = RoundController.submitToken(request).filter(_ => r.id.isEmpty)
                )
              )

            // Our own checks' refusal of the settings is shown as it is; any other failure,
            // whose text may be the database's, only as a logged reference.
            def failed(r: Round, key: String, referenceKey: String, e: Throwable, what: String): Result =
              e match {
                // the rounds to leave out refused (V95): their message, in the page's language
                case refused: RoundDistributionHandler.ExcludeRoundsRefused =>
                  logger.warn(s"$what: ${refused.text}")
                  reRender(r, key, messagesApi.preferred(request)(refused.refusal.key, refused.refusal.args: _*))
                case _ => failedOtherwise(r, key, referenceKey, e, what)
              }

            def failedOtherwise(r: Round, key: String, referenceKey: String, e: Throwable, what: String): Result =
              RoundController.refusal(e) match {
                case Some(text) =>
                  logger.warn(s"$what: $text")
                  reRender(r, key, text)
                case None =>
                  val reference = Secured.newReference()
                  logger.error(s"$what ref=$reference", e)
                  reRender(r, referenceKey, reference)
              }

            // "Add files": lines that name no file are shown, not dropped
            val unreadable = FileTitles.unreadable(editForm.fileList.getOrElse(""))
            def unreadableLines: Result =
              reRender(round, "files.list.unreadable", unreadable.size, unreadable.take(5).mkString(" · "))

            // the edit of an existing round, its criteria saved
            def saveEdit(stored: Round): Result = {
              val roundId = stored.getId
              // the score rule and the worst mark as posted; a client without the select (the
              // checkbox) keeps the stored one
              Round.updateRound(roundId, round.copy(
                averageRule = if (editForm.averageRuleShown) round.averageRule else stored.averageRule,
                worstMark = if (editForm.worstMarkShown) round.worstMark else stored.worstMark))
              // before "Distribute new files", whose job then reads the round's flag
              val coi = leaveOutConflicts(stored, round.excludeCoi, user)(request.messages)
              // one message per kind: an error of the distribution wins over the removal's
              def flashing(errors: (String, String)*) =
                toRoundsList.flashing((coi ++ errors).toMap.toSeq: _*)
              if (!editForm.newImages && titles.isEmpty) flashing()
              else
                try {
                  roundsService.distributeNewFiles(roundId, user.id, titles, newFiles = editForm.newImages) match {
                    case RoundService.AlreadyRunning(_) =>
                      flashing("error" -> messagesApi.preferred(request)(
                        if (titles.nonEmpty) "round.distribute.running.files" else "round.distribute.running",
                        stored.number))
                    case RoundService.Resumed(_) if titles.nonEmpty =>
                      flashing("error" -> messagesApi.preferred(request)("round.distribute.resumed.files", stored.number))
                    case givenUp: RoundService.GivenUp =>
                      flashing("error" -> messagesApi.preferred(request)("round.distribute.attempts", stored.number,
                        givenUp.reference))
                    case _ => flashing()
                  }
                } catch {
                  case NonFatal(e) =>
                    failed(Round.findById(roundId).getOrElse(round), "round.distribute.failed",
                      "round.distribute.failed.reference", e, s"Round $roundId: could not start distributing new files")
                }
            }

            // the submitted contest must be one the user administers; an edited round
            // must belong to it (checked below)
            if (!contestPermission(User.ADMIN_ROLES, Some(contestId))(user))
              onUnAuthorized(user, request, Secured.Denial.contest(Some(contestId), User.ADMIN_ROLES))
            else round.id match {
              case None if unreadable.nonEmpty => unreadableLines
              // "rejected by at least N" (V93) from no previous round whose votes may reject: it
              // would leave out nothing, or keep nothing; refused, with the form kept
              case None if !EditRound.rejectedByHasSource(round,
                  if (round.previousIds.isEmpty) Nil else Round.findByIds(contestId, round.previousIds)) =>
                reRender(round, EditRound.RejectedByNoSource)
              case None =>
                try {
                  roundsService.submit(round.copy(active = true), editForm.jurors, RoundController.submitToken(request), user.id, titles) match {
                    // the distribution runs in the background: the rounds list shows its progress
                    case _: RoundService.Created => toRoundsList
                    // The same form submitted again (a double click, or Back and Submit):
                    // no second round; a failed distribution of the first was resumed.
                    case resubmitted: RoundService.Resubmitted =>
                      logger.warn(s"Contest $contestId: ${resubmitted.message}")
                      toRoundsList.flashing(
                        "error" -> messagesApi.preferred(request)(
                          resubmitted.messageKey, resubmitted.round.number, resubmitted.round.description,
                          resubmitted.reference))
                  }
                } catch {
                  // Rejected before anything was created (bad input): keep the form.
                  case NonFatal(e) =>
                    failed(round, "round.creation.failed", "round.creation.failed.reference", e,
                      s"Rejected round creation for contest $contestId")
                }

              case Some(roundId) =>
                withAdminRound(user, roundId, roundNotFound(user), request) {
                  case stored if stored.contestId != contestId =>
                    onUnAuthorized(user, request,
                      Secured.Denial.round(stored, User.ADMIN_ROLES).copy(reason = Some("round_in_other_contest")))
                  case _ if unreadable.nonEmpty => unreadableLines
                  // the stored round's rating decides (the form's is not editable), for criteria
                  // changed: those posted as stored stay, whatever they are (legacy rounds)
                  case stored if editForm.criteriaShown && round.criterionIds.nonEmpty && !stored.isRated &&
                      round.criterionIds != RoundCriterion.idsOf(roundId) =>
                    reRender(round, "error.criteria.rated.only")
                  // the worst mark only in a rated round without criteria, by the stored rating and
                  // the criteria the round has after the edit (a client without the selects keeps them)
                  case stored if editForm.worstMarkShown && round.worstMark && (!stored.isRated ||
                      (if (editForm.criteriaShown) round.criterionIds else RoundCriterion.idsOf(roundId)).nonEmpty) =>
                    reRender(round, EditRound.WorstMarkRatedOnly)
                  case stored =>
                    // first: after the first vote criteria are only replaced in their places
                    // (U10), and a refused change saves nothing of the form
                    val criteria =
                      if (editForm.criteriaShown) RoundCriterion.update(roundId, round.criterionIds) else Right(false)
                    criteria.fold(refused => reRender(round, refused.messageKey), _ => saveEdit(stored))
                }
            }
          }
        )
    }

  /** Starts or stops a round of a contest the user administers: back to the rounds list, or,
    * with `returnTo=conflicts` (the round's conflicts page's "Stop the round"), to that page
    * with a message. `returnTo` is a fixed word, never a path.
    */
  def setRound(): EssentialAction = withAuth(rolePermission(User.ADMIN_ROLES)) {
    user => implicit request =>
      selectRoundForm
        .bindFromRequest()
        .fold(
          _ => BadRequest,
          selectRound =>
            selectRound.roundId.toLongOption.fold[Result](BadRequest) { id =>
              withAdminRound(user, id, roundNotFound(user), request) { round =>
                val messages = messagesApi.preferred(request)
                val toConflicts = request.body.asFormUrlEncoded.flatMap(_.get("returnTo")).exists(_.contains("conflicts"))
                val back =
                  if (toConflicts) Redirect(routes.RoundConflictsController.page(round.getId))
                  else Redirect(routes.RoundController.rounds(Some(round.contestId)))
                if (roundsService.setCurrentRound(Nil, round.copy(active = selectRound.active))) {
                  if (toConflicts)
                    back.flashing("success" ->
                      messages(if (selectRound.active) "coi.round.started" else "coi.round.stopped", round.number))
                  else back
                } else back.flashing("error" -> messages("round.still.distributing", round.number, round.description))
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
                withAdminRound(user, roundId, roundNotFound(user), request) { _ =>
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
          }

          Redirect(routes.RoundController.rounds(contestId))
      }
    }

  // the round statistics are organizer views (User.canViewOrgInfo): never a juror's, who gets
  // the 403 page naming the roles at once
  def currentRoundStat(contestId: Option[Long] = None): EssentialAction =
    withAuth(rolePermission(Set(User.ADMIN_ROLE, "root") ++ User.ORG_COM_ROLES)) {
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
              // a state, not an error, as for a juror ([[LoginController.index]]): no link back
              // to the start page, which would come here again
              JurorContests.noRoundPage(user)
            }
        }
    }

  def roundStat(roundId: Long): EssentialAction =
    withAuthOn(blocking)(rolePermission(Set(User.ADMIN_ROLE, "root") ++ User.ORG_COM_ROLES)) {
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
              // the link to the comments report, with its header's numbers (one statement, inline)
              val comments = org.intracer.wmua.CommentJdbc.roundCounts(round.contestId, round.getId)

              Secured.switchingContest(sessionUser, user)(Ok(views.html.roundStat(user, round, stat, comments)))
            }
          }
          .getOrElse {
            NotFound(views.html.errorPage(Some(sessionUser), "error.title", "error.round.not.found"))
          }
    }

  /** The counts of the round statistics page's links to the round's authors and its conflicts
    * of interest ([[RoundStatLinks.counts]]), as JSON for the page's script (roundstat-counts.js):
    * numbers only. Its JDBC (a grouped pass over the round's files, 0.1-0.6 s on 22-29k files)
    * runs on the blocking pool; sent by script, it never switches the session's contest. The
    * round is checked in its own contest ([[Secured.inContest]]), for each of the three links: a
    * refusal of any refuses the whole request (JSON 403, logged), never a partial answer. No
    * round: JSON 404.
    */
  def roundStatCounts(roundId: Long): EssentialAction =
    withAuthJsonOn(blocking)() { sessionUser => implicit request =>
      Round.findById(roundId).fold[Result](NotFound(Json.obj("error" -> "round_not_found"))) { round =>
        val user = Secured.inContest(sessionUser, round.contestId)
        if (!RoundStatLinks.mayView(user, round))
          onUnAuthorizedJson(user, request, Secured.Denial.round(round, User.ADMIN_ROLES ++ User.ORG_COM_ROLES))
        else {
          val counts = RoundStatLinks.counts(round)
          Ok(
            Json.obj(
              "authors" -> counts.authors,
              "withoutAuthor" -> counts.withoutAuthor,
              "conflicts" -> counts.conflicts,
              "disqualified" -> counts.disqualified
            ) ++ counts.history.fold(Json.obj())(n => Json.obj("history" -> n))
          )
        }
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


}

object RoundController {

  /** "Count new files" runs here, one at a time: a full read of the round's candidates. */
  private[controllers] lazy val countContext: ExecutionContext =
    ExecutionContext.fromExecutor(java.util.concurrent.Executors.newSingleThreadExecutor { (r: Runnable) =>
      val t = new Thread(r, "count-new-files")
      t.setDaemon(true)
      t
    })

  /** The text of a refusal by our own checks of a round's settings (`require`, in
    * [[RoundService]] and the distribution), without its prefix; None for any other failure.
    */
  def refusal(e: Throwable): Option[String] = e match {
    case e: IllegalArgumentException =>
      Option(e.getMessage).filter(_.startsWith(RequirementFailed)).map(_.stripPrefix(RequirementFailed).take(500))
    case _ => None
  }

  private val RequirementFailed = "requirement failed: "

  /** The round form's note beside the score rule of a rated round with a vote of stars: saving
    * another rule re-scores and re-orders its results on every page at once (reads compute the
    * score from the rule-independent votes and counts; nothing runs in the background), a
    * distribution already queued keeps the rule it was queued with, and `laterRounds` took files
    * by its scores (a top-N or a minimum score) when they were distributed.
    *
    * @param storedRule the round's saved rule, which the page's script compares the select with
    */
  final case class ScoreRuleWarning(storedRule: org.intracer.wmua.AverageRule, laterRounds: Seq[Round]) {

    /** The later rounds as the note names them: each one's number, and its name if it has one. */
    def laterRoundsText: String =
      laterRounds.map(r => r.number.toString + r.name.map(_.trim).filter(_.nonEmpty).fold("")(n => s" ($n)")).mkString(", ")
  }

  object ScoreRuleWarning {

    /** The warning for round `roundId` of the contest's `rounds`, if a rated round with a vote of
      * stars: with none (no vote, or worst marks only) every file scores 0 under every rule. */
    def of(roundId: Long, rounds: Seq[Round]): Option[ScoreRuleWarning] =
      rounds.find(_.id.contains(roundId)).filter(_.isRated).filter(_ => VoteJdbc.hasStarVote(roundId)).map { round =>
        ScoreRuleWarning(round.averageRule, laterRounds(roundId, rounds))
      }

    /** The rounds that took a top-N or a minimum score from round `roundId`, by number. */
    def laterRounds(roundId: Long, rounds: Seq[Round]): Seq[Round] =
      rounds
        .filter(r => r.deletedAt.isEmpty && r.previousIds.contains(roundId) &&
          (r.topImages.exists(_ > 0) || r.prevMinAvgRate.isDefined))
        .sortBy(_.number)
  }

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

/** The round form's conflict-of-interest counts: the contest's files that are conflicts of
  * interest, an existing round's (`inRound`), and the contest's files without a stored author.
  */
/** The round form's conflicts of interest ([[RoundController]]): the contest's, the round's,
  * those without an author; whether the round leaves them out already (saving it ticked then
  * changes nothing, so nothing is confirmed) and is open or frozen (saving only sets the flag).
  */
final case class CoiCounts(conflicts: Int, inRound: Option[Int], withoutAuthor: Long, excluded: Boolean = false,
    open: Boolean = false) {

  /** The files a save of the ticked box would disqualify, for the browser's confirm. */
  def toConfirm: Int = if (excluded || open) 0 else inRound.getOrElse(0)

  def describe(implicit messages: play.api.i18n.Messages): String =
    inRound.fold(messages("coi.count", conflicts))(n => messages("coi.count.round", n, conflicts) + " " +
      messages("coi.exclude.edit")) +
      (if (withoutAuthor > 0) " " + messages("coi.without.author", withoutAuthor) else "")
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
