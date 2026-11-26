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
import services.distribution.{RoundDistributionHandler, RoundFreezes, RoundImageCheckHandler,
  RoundRebalanceService}
import services.imports.{FileTitles, ImportSource, WikiTitle}
import services.distribution.RoundStartBlock
import services.jobs.{Job, JobJdbc}

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
    withAuthOn(blocking)(contestPermission(User.ADMIN_ROLES, contestIdParam, derived = true)) { user => implicit request =>
      val roundsView =
        for (
          contestId <- contestIdParam.orElse(user.currentContest);
          contest <- ContestJuryJdbc.findById(contestId)
        ) yield {
          val rounds = Round.findByContest(contestId)
          // the rounds' tracks (round lineage slice A): the Track chips, menu and hints
          val lineage = services.RoundLineage.of(rounds, contest)
          // the latest of each round's distribution and juror-add jobs (as a refused start reads them)
          val jobs = RoundStartBlock.latestJobs(rounds.flatMap(_.id))
          // each round's latest distribution job (`jobs` has juror adds too)
          val distributions = JobJdbc.latestBySubjects(RoundDistributionHandler.Kind,
            RoundDistributionHandler.SubjectType, rounds.flatMap(_.id))
          Ok(
            views.html.rounds(
              user,
              rounds,
              RoundImageCounts.get(rounds.flatMap(_.id))(ImageJdbc.imageCountByRounds),
              jobs,
              editRoundForm,
              imagesForm.fill(contest.images),
              selectRoundForm,
              contest,
              // root's all-rounds check: not shown to a contest's admins
              Option.when(user.isRoot)(JobJdbc.latestByDedupeKey(RoundImageCheckHandler.AllDedupeKey)).flatten,
              // and root's author normalization
              Option.when(user.isRoot)(JobJdbc.latestByDedupeKey(services.imports.AuthorNormalizeHandler.DedupeKey)).flatten,
              // rounds left frozen with no job holding them: flagged, root may release them
              RoundFreezes.leftFrozen(rounds),
              // the Status, Type, Track and Order menus' choices (unknown values: the default)
              RoundsFilter.fromRequest(request).withLineage(lineage),
              // open rounds nobody can vote in (no active juror): a warning icon (DG10)
              RoundUser.openWithoutActiveJuror(rounds),
              Some(lineage),
              // "Find the matching files again" (#194), from each round's latest distribution job (as the POST
              // reads it); the jurors read only for a cancelled or unfinished setup meant for review
              rounds.filter(r => RoundStartBlock.mayFindFilesAgain(r, distributions.get(r.getId),
                User.findRoundJurors(r.getId).nonEmpty)).flatMap(_.id).toSet,
              RoundController.setupSteps(rounds, distributions),
              // each job's title: its status's label (snippets/jobStatus)
              jobs.values.map(j => j.id -> roundsService.jobTitle(j)).toMap
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
  def editRound(roundId: Option[Long], contestId: Long, topImages: Option[Int],
      submitToken: Option[String] = None): EssentialAction =
    withAuthOn(blocking)(contestPermission(User.ADMIN_ROLES, Some(contestId), derived = true)) { user => implicit request =>
      // the new-round form's history entry (its URL with the form's token, round-form-token.js): once
      // the form saved a round, Back or a reload lands on that round's Edit page, never on the
      // stale form (UI-path plan §5 Q6); not saved yet, the form again with the same token
      val token = submitToken.map(_.trim).filter(t => roundId.isEmpty && RoundController.TokenPattern.matches(t))
      token.flatMap(Round.findBySubmitToken).filter(_.contestId == contestId) match {
        case Some(saved) => RoundController.toSavedRound(saved, messagesApi.preferred(request))
        // a token no round holds (a deleted one's or another contest's: a fresh one)
        case None => roundForm(user, roundId, contestId, topImages,
          token.filterNot(Round.submitTokenTaken).getOrElse(RoundController.newSubmitToken()))
      }
    }

  /** The round form's page of GET [[editRound]]: a round's, or a new round's with its token. */
  private def roundForm(user: User, roundId: Option[Long], contestId: Long, topImages: Option[Int], token: String)(
      implicit request: Request[AnyContent]): Result = {
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
      RoundController.formPage(Ok(
        roundFormView(user, withTopImages, filledRound, Some(rounds), Some(jurors),
          submitToken = Option.when(withTopImages.id.isEmpty)(token),
          storedCriteria = withTopImages.id.map(_ => withTopImages.criterionIds))
      ))
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
      submitToken: Option[String] = None,
      storedCriteria: Option[Seq[Long]] = None,
      summaryHeading: Option[String] = None,
      waitKey: Option[String] = None
  )(implicit request: RequestHeader): Html = {
    val contestId = round.contestId
    val rounds = knownRounds.getOrElse(Round.findByContest(contestId))
    // a round held in review with no vote and no job: its filters editable, "Re-apply filters" (P3-5)
    val stored = round.id.flatMap(id => rounds.find(_.id.contains(id)).orElse(Round.findById(id)))
    val reapply = stored.exists(roundsService.reapplyable)
    // #184: the rating type of a round being set up is editable (a re-apply's round, no later round
    // taking its files); then the form is a rated one's as for a new round
    val ratesEditable = stored.exists(s => reapply && !RoundController.takenFrom(s.getId, rounds))
    val job = round.id.flatMap(roundsService.distributionJob)
    // the new-round form's history entry: its URL with the token (round-form-token.js), so Back after
    // a save lands on the saved round's Edit page (editRound)
    val resolveUrl = submitToken.filter(_ => round.id.isEmpty).map(t =>
      RoundsFilter.fromRequest(request).addTo(routes.RoundController.editRound(None, contestId,
        form("topImages").value.flatMap(_.toIntOption).filter(_ > 0), Some(t)).url))
    views.html.editRound(
      user,
      RoundFormErrors.placed(form),
      round.id.isEmpty,
      rounds,
      Some(contestId),
      knownJurors.getOrElse(round.id.fold(User.loadJurors(contestId))(User.findRoundJurors).sorted),
      jurorsMapping,
      contestsController.regions(contestId),
      contestSpecialNominations(contestId),
      submitToken,
      job,
      Some(coiCounts(contestId, round.id)),
      CriterionJdbc.all(),
      round.id.flatMap(id => RoundController.ScoreRuleWarning.of(id, rounds)),
      // the check is queued by the contest's admins (and root): this page is theirs
      round.id.flatMap(id =>
        JobJdbc.latestBySubjects(RoundImageCheckHandler.Kind, RoundImageCheckHandler.SubjectType, Seq(id)).get(id)),
      RoundFormState(
        reapply = reapply,
        // an existing yes/no or comments-only round, by its stored rating (the form's is not
        // editable, unless the round is being set up): no criteria, no worst mark
        unratedRound = stored.exists(!_.isRated) && !ratesEditable,
        lockedCriteria = stored.flatMap(RoundController.lockedCriteria(_, storedCriteria)),
        // its files waiting for a juror (round-editing plan §7): "Also give out the N waiting files"
        waiting = stored.flatMap(r => services.distribution.RoundReassign.waitingCount(r)).filter(_ > 0),
        nextStep = stored.flatMap(s => RoundNextStep.of(s, job, RoundNextStep.inCount(s))),
        ratesEditable = ratesEditable,
        resolveUrl = resolveUrl,
        // refused to wait for another round's job (Q7): that job's live progress at the top
        waitFor = RoundFormWait.of(form, contestId, waitKey),
        summaryHeading = summaryHeading)
    )
  }

  /** A refused save's page: the round form again through [[roundFormView]] (its mode, menus and
    * jurors as on its GET), answered 400, logged as `form_refused` ([[FormRefusal]]).
    */
  private def refusedForm(user: User, round: Round, form: Form[EditRound], submitToken: Option[String])(implicit
      request: RequestHeader): Result = {
    val placed = RoundFormErrors.placed(form)
    RoundController.formPage(FormRefusal.badRequest(RoundController.FormId, user, Some(round.contestId), round.id,
      placed)(roundFormView(user, round, placed, submitToken = submitToken)))
  }

  /** The form after a saved edit whose "Distribute new files" (or list, or top-up) was refused: the
    * round form again, headed "Round N is saved. Its new files weren't distributed:", not "Nothing
    * was saved"; answered 400, logged as `distribution_refused` (not `form_refused`: the settings
    * were saved), the result marked as recorded. `waitKey`: a wait for another round's job, whose
    * progress the form shows (Q7).
    */
  private def distributionRefusedForm(user: User, round: Round, form: Form[EditRound], waitKey: Option[String])(implicit
      request: RequestHeader): Result = {
    val messages = messagesApi.preferred(request)
    RoundController.formPage(FormRefusal.refused(RoundController.FormId, user, Some(round.contestId), round.id,
      form.errors.map(FormRefusal.messageKey) ++ waitKey, event = FormRefusal.DistributionRefused)(
      BadRequest(roundFormView(user, round, form,
        summaryHeading = Some(messages("round.saved.not.distributed", round.number)), waitKey = waitKey))))
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
    * Its writes, in the edit's transaction under the round's lock, decided by the round's flag as
    * read there: the disqualify's outcome, if it ran ([[leaveOutConflictsDone]] after the commit).
    */
  private def leaveOutConflictsIn(roundId: Long, exclude: Boolean, user: User)(implicit
      session: scalikejdbc.DBSession
  ): Option[RoundConflicts.Outcome] = {
    val excluding = Round.findById(roundId).exists(_.excludeCoi)
    if (!exclude) {
      if (excluding) Round.writeExcludeCoi(roundId, excludeCoi = false)
      None
    } else if (excluding) None
    // the flag is set under the round's lock, also when the round is open or frozen
    else Some(RoundConflicts.disqualifyIn(roundId, user, RoundDistributionHandler.dedupeKey(roundId), excludeCoi = true))
  }

  /** After the commit of [[leaveOutConflictsIn]]: the disqualified files logged, and the flash
    * messages to show.
    */
  private def leaveOutConflictsDone(stored: Round, outcome: Option[RoundConflicts.Outcome], user: User)(implicit
      messages: Messages
  ): Seq[(String, String)] = {
    val roundId = stored.getId
    // the round's conflicts, read only where a message counts them (the files weren't disqualified)
    def inRound = CoiMembers.roundFiles(roundId, CoiMembers.forContest(stored.contestId))
    outcome match {
      case None => Nil
      case Some(RoundConflicts.Outcome.Disqualified(files)) =>
        RoundConflicts.logDisqualified(roundId, user, files)
        if (files.isEmpty) Nil else Seq("success" -> messages("coi.disqualified", files.size, stored.number))
      // refused before the flag was set: said whether or not the round has conflicts
      case Some(RoundConflicts.Outcome.Distributing) => Seq("error" -> messages("coi.remove.distributing", stored.number))
      // the flag is set, and a later save changes nothing: the round's conflicts page, linked
      // on the rounds list (`coiRound`), disqualifies them once the round is stopped
      case Some(RoundConflicts.Outcome.Open) =>
        val n = inRound.size
        if (n == 0) Nil
        else Seq("error" -> messages("coi.disqualify.open", stored.number, n), "coiRound" -> roundId.toString)
      case Some(RoundConflicts.Outcome.Frozen) =>
        if (inRound.isEmpty) Nil
        else Seq("error" -> messages("coi.disqualify.frozen", stored.number), "coiRound" -> roundId.toString)
      case _ => Nil
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
    withAuthOn(blocking)(rolePermission(User.ADMIN_ROLES, derived = true)) { user => implicit request =>
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

  /** The Main track's name in each of the app's languages: typed as a track label, it is "Main"
    * (round lineage decision 4), as the pages show it in the viewer's language.
    */
  private lazy val trackMainNames: Seq[String] =
    controllerComponents.langs.availables.map(lang => messagesApi("round.track.main")(lang)).distinct

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

  /** The round form's save. A new-round form's token that already saved a round is checked first,
    * before the form is validated (UI-path plan §5 Q6): its round's Edit page, never a second round
    * nor a refusal of the stale form ([[savedAgain]]). A refused save comes back as the same form
    * ([[refusedForm]]: its mode, menus and jurors as on its GET, every error listed at the top and
    * at its field, the sections with an error open), logged as `form_refused`.
    */
  def saveRound(): EssentialAction =
    withAuthOn(blocking)(rolePermission(User.ADMIN_ROLES, derived = true)) { user => implicit request =>
      RoundController.submitToken(request).flatMap(t => Round.findBySubmitToken(t).map(t -> _)) match {
        case Some((token, saved)) => savedAgain(user, token, saved)
        case None => bindAndSave(user)
      }
    }

  /** A new-round form posted again after it saved a round (Back and Save, a double click): no second
    * round, and the posted changes not applied; to that round's Edit page with "Round N is saved. You
    * can change its settings here." A failed distribution of it is resumed, as before. Logged as
    * `resubmit_dropped`, with whether the posted form was the one saved (`same`, a double click) or
    * not (`changed`: edits dropped), from [[RoundController.SavedForms]].
    */
  private def savedAgain(user: User, token: String, saved: Round)(implicit request: Request[AnyContent]): Result =
    if (!adminOfRound(user, saved)) onUnAuthorized(user, request, Secured.Denial.round(saved, User.ADMIN_ROLES))
    else {
      val messages = messagesApi.preferred(request)
      // a resumed distribution refused (a round leaving out its files is being filled): said on the page
      val again: Either[String, RoundService.Resubmitted] =
        try Right(roundsService.resubmit(saved))
        catch { case e: IllegalArgumentException if RoundController.refusal(e).isDefined => Left(RoundController.refusal(e).get) }
      val distribution = again.fold(_ => "refused", r =>
        if (r.resumed) "resumed" else if (r.givenUp) "given_up" else if (r.job.exists(_.isActive)) "running" else "none")
      val same = RoundController.SavedForms.same(token, RoundController.postedHash(request))
      FormRefusal.logger.warn(FormRefusal.resubmitLine(Secured.newReference(), RoundController.FormId, user,
        saved.contestId, saved.getId, if (same) "same" else "changed", distribution))
      val problem = again.fold(text => Some(text), r =>
        Option.when(r.givenUp)(messages("round.distribute.attempts", saved.number, r.reference)))
      RoundController.toSavedRound(saved, messages, problem.map("error" -> _).toSeq: _*)
    }

  private def bindAndSave(user: User)(implicit request: Request[AnyContent]): Result =
      editRoundForm
        .bindFromRequest()
        .fold(
          formWithErrors => {
            val contestId: Option[Long] = formWithErrors.data.get("contest").flatMap(_.toLongOption)
            val roundId = formWithErrors.data.get("id").flatMap(_.toLongOption)
            // the form lists the contest's rounds and jurors: only for its own admins
            if (!contestPermission(User.ADMIN_ROLES, contestId)(user))
              onUnAuthorized(user, request, Secured.Denial.contest(contestId, User.ADMIN_ROLES))
            else contestId match {
              // no contest posted: no form to show again
              case None =>
                FormRefusal.badRequest(RoundController.FormId, user, None, roundId, formWithErrors)(
                  views.html.errorPage(Some(user), "error.title", "error.invalid"))
              case Some(cid) => roundId match {
                // keep the form's token: nothing was saved from this submission
                case None =>
                  refusedForm(user, new Round(id = None, contestId = cid,
                    number = formWithErrors.data.get("number").flatMap(_.toLongOption).getOrElse(0L)),
                    formWithErrors, RoundController.formToken(RoundController.submitToken(request)))
                case Some(id) =>
                  withAdminRound(user, id, roundNotFound(user), request) {
                    case stored if stored.contestId != cid =>
                      onUnAuthorized(user, request,
                        Secured.Denial.round(stored, User.ADMIN_ROLES).copy(reason = Some("round_in_other_contest")))
                    case stored => refusedForm(user, stored, formWithErrors, None)
                  }
              }
            }
          },
          editForm => {
            // a new round starts once distributed; an edit leaves it started or stopped
            // (setRound starts and stops it)
            val round = editForm.round
            val contestId = round.contestId
            // back to the Rounds page as it was filtered (the form's hidden fields, from its link)
            val toRoundsList =
              Redirect(RoundsFilter.fromForm(request).addTo(routes.RoundController.rounds(Some(contestId)).url))
            // "Add files": added by the round's distribution job, after the files its filters choose
            val titles = ImportSource.titles(editForm.fileList.getOrElse(""))
            // the form's token, unless another (a deleted) round holds it: then no dedupe, as without one
            val liveToken = RoundController.submitToken(request).filterNot(Round.submitTokenTaken)

            // Re-render the same create/edit form with the entered values kept and the
            // failure shown as a global form error, so the admin can fix and resubmit
            // instead of losing the page.
            def reRender(r: Round, message: String, args: Any*): Result =
              refusedForm(user, r, filledWith(r, message, args),
                Option.when(r.id.isEmpty)(RoundController.formToken(liveToken)).flatten)

            def filledWith(r: Round, message: String, args: Seq[Any]): Form[EditRound] =
              editRoundForm
                .fill(EditRound(r, editForm.jurors, editForm.fileList, editForm.newImages, editForm.criteriaShown))
                .withGlobalError(message, args: _*)

            // the edit saved, "Distribute new files" (or the list, or the top-up) refused: the form again,
            // headed "Round N is saved. Its new files weren't distributed:", logged as
            // `distribution_refused`; `waitKey`, a wait for another round's job (Q7), shows its box
            def notDistributed(r: Round, waitKey: Option[String], message: String, args: Any*): Result =
              distributionRefusedForm(user, r, filledWith(r, message, args), waitKey)

            // Our own checks' refusal of the settings is shown as it is; any other failure,
            // whose text may be the database's, only as a logged reference.
            // `saved`: the edit was saved before this failed (Distribute new files): notDistributed
            def failed(r: Round, key: String, referenceKey: String, e: Throwable, what: String,
                saved: Boolean = false): Result = {
              def render(waitKey: Option[String], message: String, args: Any*): Result =
                if (saved) notDistributed(r, waitKey, message, args: _*) else reRender(r, message, args: _*)
              e match {
                // the rounds to leave out refused (V95): their message, in the page's language
                // a wait for another round's job (Q7): its own key, so the form shows that job's progress
                // (after a saved edit, inside the failure's wrapper, the wait named apart)
                case refused: RoundDistributionHandler.ExcludeRoundsRefused if RoundFormWait.Keys(refused.refusal.key) && !saved =>
                  logger.warn(s"$what: ${refused.text}")
                  reRender(r, refused.refusal.key, refused.refusal.args: _*)
                case refused: RoundDistributionHandler.ExcludeRoundsRefused =>
                  logger.warn(s"$what: ${refused.text}")
                  render(Some(refused.refusal.key).filter(RoundFormWait.Keys), key,
                    messagesApi.preferred(request)(refused.refusal.key, refused.refusal.args: _*))
                case _ =>
                  RoundController.refusal(e) match {
                    case Some(text) =>
                      logger.warn(s"$what: $text")
                      render(None, key, text)
                    case None =>
                      val reference = Secured.newReference()
                      logger.error(s"$what ref=$reference", e)
                      render(None, referenceKey, reference)
                  }
              }
            }

            // "Add files": lines that name no file are shown, not dropped
            val unreadable = FileTitles.unreadable(editForm.fileList.getOrElse(""))
            def unreadableLines: Result =
              reRender(round, "files.list.unreadable", unreadable.size, unreadable.take(5).mkString(" · "))

            // #184: the rating type posted, for a round being set up (a re-apply's round: in review, no
            // vote, no job; and no later round taking its files): `r` with it, a worst mark dropped if
            // it isn't a rated one; else `r` with the stored one (its select read only: as before, a
            // posted other one is ignored)
            def withRating(stored: Round, r: Round): Round =
              if (round.rates == stored.rates ||
                  !(roundsService.reapplyable(stored) && !RoundController.takenFrom(stored.getId, Round.findByContest(contestId))))
                r.copy(rates = stored.rates)
              else r.copy(rates = round.rates, worstMark = r.worstMark && round.isRated)

            // #184: the posted rating replaces the stored one (the round is being set up)
            def ratingChanged(stored: Round): Boolean = withRating(stored, stored).rates != stored.rates

            // the criteria the save writes, if any: none for a round set up as yes/no or comments only
            // (#184: its stored criteria cleared, whatever the form posted: a script hides their
            // selects, an older page may post them unchanged); else the posted ones, if the form had them
            def criteriaToWrite(stored: Round, edited: Round): Option[Seq[Long]] =
              if (edited.rates != stored.rates && !edited.isRated) Some(Nil)
              else Option.when(editForm.criteriaShown)(round.criterionIds)

            // the rating of `edited` written, if changed (#184), under the round's lock (held by the
            // caller, `s`): refused if a later round takes its files, read in the same transaction
            def writeRating(stored: Round, edited: Round)(implicit s: scalikejdbc.DBSession): Unit =
              if (edited.rates != stored.rates) {
                if (RoundController.takenFrom(stored.getId, Round.findByContestIn(contestId)))
                  throw RoundController.CriteriaRefused(RoundController.RatesLockedKey)
                Round.updateRates(stored.getId, edited.rates)
              }

            // "Re-apply filters" (P3-5): a round held in review, with no vote and no job, gets the
            // posted filters and a re-apply fill; to its review page, which shows the result. The form's
            // other settings (name, score rule, worst mark, criteria, "Leave out conflicts of interest":
            // newly ticked, the round's conflicts disqualified as on a stopped round) are written with
            // the filters, under the round's lock, only if the re-apply is queued: a refusal saves
            // nothing and shows the form again, its input (the pasted list included) kept
            def reapplyFilters(stored: Round): Result = {
              val roundId = stored.getId
              val toReview = Redirect(routes.RoundReviewController.review(roundId))
              val messages = messagesApi.preferred(request)
              val previous = if (round.previousIds.isEmpty) Nil else Round.findByIds(contestId, round.previousIds)
              val edited = withRating(stored, round.copy(
                averageRule = if (editForm.averageRuleShown) round.averageRule else stored.averageRule,
                worstMark = if (editForm.worstMarkShown) round.worstMark else stored.worstMark))
              // "rejected by at least N" needs a previous round whose votes may reject, as for a new round
              if (!EditRound.rejectedByHasSource(round.copy(id = None), previous)) reRender(round, EditRound.RejectedByNoSource)
              else
                try {
                  var disqualified = Seq.empty[(Long, String, CoiMembers.Member)]
                  roundsService.reapplyFilters(round, user.id, titles, alsoWrite = { s =>
                    // a started round's criteria are not changed (running or voted): a re-apply's round
                    // is neither, but a refusal rolls everything back
                    criteriaToWrite(stored, edited).foreach(ids =>
                      RoundCriterion.updateIn(roundId, ids)(s).left.foreach(r =>
                        throw RoundController.CriteriaRefused(r.messageKey)))
                    // #184: its rating type, checked again under the lock (the re-apply's checks: in
                    // review, no vote, no job)
                    writeRating(stored, edited)(s)
                    Round.updateRound(roundId, edited)(s)
                    if (editForm.trackShown) Round.setTrack(roundId, round.track, trackMainNames)(s)
                    // newly ticked: the conflicts disqualified (dq, code coi, their events) under the held
                    // lock, before the job; the fill leaves out later ones by the flag
                    if (round.excludeCoi && !stored.excludeCoi)
                      disqualified = RoundConflicts.disqualifyLocked(roundId, stored.contestId, user, excludeCoi = true)(s)
                    else Round.writeExcludeCoi(roundId, round.excludeCoi)(s)
                  }) match {
                    case RoundService.ReviewDistribution.Started(_) =>
                      RoundConflicts.logDisqualified(roundId, user, disqualified)
                      val coi = if (disqualified.isEmpty) "" else " " + messages("coi.disqualified", disqualified.size, stored.number)
                      toReview.flashing("success" -> (messages("round.reapply.started", stored.number) + coi))
                    case RoundService.ReviewDistribution.Running(_) =>
                      reRender(round, "round.distribute.running", stored.number)
                    case RoundService.ReviewDistribution.NotInReview =>
                      reRender(round, "round.reapply.not.in.review", stored.number)
                    case RoundService.ReviewDistribution.Refused(key, args) =>
                      reRender(round, key, args: _*)
                  }
                } catch {
                  case RoundController.CriteriaRefused(key) => reRender(round, key)
                  case NonFatal(e) =>
                    failed(round, "round.reapply.failed", "round.reapply.failed.reference", e,
                      s"Round $roundId: could not re-apply its filters")
                }
            }

            // the edit of an existing round: one transaction under the round's lock. First its
            // criteria: a started round's (running or voted) are not changed, and a refused change
            // saves nothing of the form (shown again, its criteria read only); a plain save, whose
            // filters aren't changed. Then its name, score rule and worst mark, and "Leave out
            // conflicts of interest" (before "Distribute new files", whose job then reads the
            // round's flag), with the entry of what changed in the round's settings log (RoundChanges)
            def saveEdit(stored: Round): Result = {
              val roundId = stored.getId
              // the score rule and the worst mark as posted; a client without the select (the
              // checkbox) keeps the stored one
              val edited = withRating(stored, round.copy(
                averageRule = if (editForm.averageRuleShown) round.averageRule else stored.averageRule,
                worstMark = if (editForm.worstMarkShown) round.worstMark else stored.worstMark))
              val saved =
                try Right(WriteTx.write { implicit s =>
                  Round.lock(Seq(roundId))
                  RoundChanges.logged(roundId, user.id, ContestChangeJdbc.RoundChanged, RoundChanges.Path.Edit) {
                    criteriaToWrite(stored, edited).foreach(ids =>
                      RoundCriterion.updateIn(roundId, ids).left.foreach(r =>
                        throw RoundController.CriteriaRefused(r.messageKey)))
                    // #184: its rating type, only while it is being set up, checked under the lock
                    if (edited.rates != stored.rates) {
                      val fresh = Round.findById(roundId).getOrElse(stored)
                      if (!(fresh.inReview && !RoundImageJdbc.hasVotes(roundId) &&
                          JobJdbc.activeByDedupeKey(RoundDistributionHandler.dedupeKey(roundId)).isEmpty))
                        throw RoundController.CriteriaRefused(RoundController.RatesLockedKey)
                    }
                    writeRating(stored, edited)
                    Round.updateRound(roundId, edited)
                    // the track label (round lineage decision 4), by its own setter; a client without
                    // the field keeps it
                    if (editForm.trackShown) Round.setTrack(roundId, round.track, trackMainNames)
                    leaveOutConflictsIn(roundId, round.excludeCoi, user)
                  }
                })
                catch { case RoundController.CriteriaRefused(key) => Left(key) }
              saved.fold(key => reRender(round, key),
                outcome => distributeAfterEdit(stored, leaveOutConflictsDone(stored, outcome, user)(request.messages)))
            }

            // the edit saved: "Distribute new files", "Add files" and "Also give out the waiting
            // files", if asked for; `coi`: the conflicts' messages
            def distributeAfterEdit(stored: Round, coi: Seq[(String, String)]): Result = {
              val roundId = stored.getId
              // one message per kind: an error of the distribution wins over the removal's; the
              // round saved said first ("Round N (description) is saved.")
              val savedLine = messagesApi.preferred(request)("round.saved", stored.number,
                stored.copy(name = round.name).description)
              def flashing(errors: (String, String)*) = {
                val all = (coi ++ errors).toMap
                toRoundsList.flashing((all ++ Map("success" -> (savedLine +: all.get("success").toSeq).mkString(" "))).toSeq: _*)
              }
              // "Also give out the waiting files" (round-editing plan §7) on its own: the top-up only, no new
              // files; only a distributed round of N > 0 jurors per file has such files (the form shows the tick)
              val topUp = editForm.topUpWaiting && stored.distribution > 0 && !stored.distributing
              if (!editForm.newImages && !topUp && titles.isEmpty) flashing()
              else
                try {
                  roundsService.distributeNewFiles(roundId, user.id, titles, newFiles = editForm.newImages,
                    topUpWaiting = topUp) match {
                    case RoundService.AlreadyRunning(_) =>
                      flashing("error" -> messagesApi.preferred(request)(
                        if (titles.nonEmpty) "round.distribute.running.files" else "round.distribute.running",
                        stored.number))
                    // a failed distribution resumed instead: the list and the tick weren't applied, said
                    case RoundService.Resumed(_) if titles.nonEmpty || topUp =>
                      val m = messagesApi.preferred(request)
                      flashing("error" -> Seq(
                        Option.when(titles.nonEmpty)(m("round.distribute.resumed.files", stored.number)),
                        Option.when(topUp)(m("round.waiting.topup.resumed", stored.number))).flatten.mkString(" "))
                    case givenUp: RoundService.GivenUp =>
                      flashing("error" -> messagesApi.preferred(request)("round.distribute.attempts", stored.number,
                        givenUp.reference))
                    case _ => flashing()
                  }
                } catch {
                  case NonFatal(e) =>
                    failed(Round.findById(roundId).getOrElse(round), "round.distribute.failed",
                      "round.distribute.failed.reference", e, s"Round $roundId: could not start distributing new files",
                      saved = true)
                }
            }

            // the submitted contest must be one the user administers; an edited round
            // must belong to it (checked below)
            if (!contestPermission(User.ADMIN_ROLES, Some(contestId))(user))
              onUnAuthorized(user, request, Secured.Denial.contest(Some(contestId), User.ADMIN_ROLES))
            else round.id match {
              case None if unreadable.nonEmpty => unreadableLines
              // no new round in a retired contest (contest series plan §3.1; root alone opens one)
              case None if ContestJuryJdbc.findById(contestId).exists(_.retired) => reRender(round, "round.create.retired")
              // "rejected by at least N" (V93) from no previous round whose votes may reject: it
              // would leave out nothing, or keep nothing; refused, with the form kept
              case None if !EditRound.rejectedByHasSource(round,
                  if (round.previousIds.isEmpty) Nil else Round.findByIds(contestId, round.previousIds)) =>
                reRender(round, EditRound.RejectedByNoSource)
              case None =>
                try {
                  // a new round's images are selected and it waits in review, without jurors (the
                  // maintainer's decision of 2026-11-16, superseding D4's "Distribute as soon as the
                  // images are ready"): its jurors are chosen on its jurors form, which distributes it
                  roundsService.submit(round.copy(active = true, track = Round.trackLabel(round.track, trackMainNames)),
                    Nil, liveToken, user.id, titles, review = true) match {
                    // "Save and find matching files" (step 1 of its setup, UI-path plan §4 step 1): its
                    // review page, step 2, which shows the matching's progress; saved, said there (never
                    // "created" or "ready": it has no jurors yet). A `next` posted by a form from before
                    // the one button lands there too
                    case created: RoundService.Created =>
                      liveToken.foreach(t => RoundController.SavedForms.remember(t, RoundController.postedHash(request)))
                      Redirect(routes.RoundReviewController.review(created.round.getId)).flashing(
                        "success" -> messagesApi.preferred(request)("round.saved.matching", created.round.number))
                    // the same token saved a round meanwhile (two submits at once; a later one is
                    // answered by savedAgain before the form is validated): that round's Edit page
                    case resubmitted: RoundService.Resubmitted =>
                      liveToken.fold(
                        RoundController.toSavedRound(resubmitted.round, messagesApi.preferred(request)))(t =>
                        savedAgain(user, t, resubmitted.round))
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
                  // the stored round's rating decides (the form's is not editable; #184: a round being
                  // set up, the posted one), for criteria changed, removed ones too: those posted as
                  // stored stay, whatever they are (legacy rounds). Before the re-apply case, which
                  // writes them too
                  // A rating changed to yes/no or comments only clears them instead (criteriaToWrite)
                  case stored if editForm.criteriaShown && !withRating(stored, stored).isRated && !ratingChanged(stored) &&
                      round.criterionIds != RoundCriterion.idsOf(roundId) =>
                    reRender(round, "error.criteria.rated.only")
                  // the worst mark only in a rated round without criteria, by the stored rating and
                  // the criteria the round has after the edit (a client without the selects keeps them);
                  // a rating changed to an unrated one drops it (withRating)
                  case stored if editForm.worstMarkShown && round.worstMark &&
                      ((!withRating(stored, stored).isRated && !ratingChanged(stored)) || (withRating(stored, stored).isRated &&
                        (if (editForm.criteriaShown) round.criterionIds else RoundCriterion.idsOf(roundId)).nonEmpty)) =>
                    reRender(round, EditRound.WorstMarkRatedOnly)
                  // the form in its re-apply state (posted `reapply`): checked under the round's lock,
                  // refused with a message if the round left review or got a vote or a job since; its
                  // criteria are written in the re-apply's transaction
                  case stored if RoundController.reapplyPosted(request) => reapplyFilters(stored)
                  // its criteria first, in the edit's transaction (saveEdit)
                  case stored => saveEdit(stored)
                }
            }
          }
        )

  /** Starts or stops a round of a contest the user administers: back to the rounds list, or,
    * with `returnTo=conflicts` (the round's conflicts page's "Stop the round"), to that page
    * with a message. `returnTo` is a fixed word, never a path.
    */
  def setRound(): EssentialAction = withAuth(rolePermission(User.ADMIN_ROLES, derived = true)) {
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
                  // back to the list as it was filtered (the form's hidden fields)
                  else Redirect(RoundsFilter.fromForm(request).addTo(routes.RoundController.rounds(Some(round.contestId)).url))
                def blockedKey(r: Round, block: RoundStartBlock) =
                  RoundStartBlock.refusedKey(block, User.findRoundJurors(r.getId).nonEmpty,
                    RoundStartBlock.reviewFirst(roundsService.distributionJob(r.getId)))
                // a round not yet distributed isn't started: its own reason, before any change (a crafted
                // POST too; the Rounds page shows no Start for it)
                def refusal(r: Round) =
                  if (ContestJuryJdbc.findById(r.contestId).exists(_.retired))
                    messages("round.start.refused.retired", r.number, r.description)
                  else
                    messages(RoundStartBlock.of(r).fold("round.start.refused.changed")(blockedKey(r, _)), r.number, r.description)
                val blocked = Option.when(selectRound.active)(RoundStartBlock.of(round)).flatten
                if (blocked.isDefined)
                  back.flashing("error" -> messages(blockedKey(round, blocked.get), round.number, round.description))
                else if (roundsService.setCurrentRound(Nil, round.copy(active = selectRound.active), user.id)) {
                  val done = Option.when(toConflicts)("success" ->
                    messages(if (selectRound.active) "coi.round.started" else "coi.round.stopped", round.number))
                  // started with no active juror (DG10: warned, never refused): nobody can vote in it
                  val noJuror = Option.when(selectRound.active && RoundUser.openWithoutActiveJuror(Seq(round)).nonEmpty)(
                    RoundController.WarningFlash -> messages("round.warning.nojurors.act", round.number))
                  val flashed = done.toSeq ++ noJuror
                  if (flashed.isEmpty) back else back.flashing(flashed: _*)
                } else // refused under the round's lock: its contest retired, or not distributed (meanwhile);
                  // neither any more (a race): to try again
                  back.flashing("error" -> refusal(Round.findById(round.getId).getOrElse(round)))
              }
            }
        )
  }

  /** "Find the matching files again" (issue #194): a round whose setup was cancelled or didn't finish,
    * meant to be held in review, with no jurors ([[RoundStartBlock.mayFindFilesAgain]], read again here:
    * the Rounds page offers it exactly then) is filled again by the reinstating path
    * ([[RoundService.distributeNewFiles]]: a fill held in review, which needs no jurors) and goes back to
    * review. The round's contest's admins and root; back to the Rounds page as it was filtered.
    */
  def findFilesAgain(id: Long): EssentialAction =
    withAuthOn(blocking)(rolePermission(User.ADMIN_ROLES, derived = true)) { user => implicit request =>
      withAdminRound(user, id, roundNotFound(user), request) { round =>
        val messages = messagesApi.preferred(request)
        // back to the round's row (its anchor) on the Rounds page as it was filtered
        val back = Redirect(RoundsFilter.fromForm(request).addTo(routes.RoundController.rounds(Some(round.contestId)).url) +
          s"#round-${round.getId}")
        def said(key: String, args: Any*) = messages(key, (Seq[Any](round.number, round.description) ++ args): _*)
        // a job of the round queued or running (a second press while its fill runs): said so, not refused
        if (JobJdbc.activeByDedupeKey(RoundDistributionHandler.dedupeKey(round.getId)).isDefined)
          back.flashing("error" -> said("round.find.again.running"))
        else if (!RoundStartBlock.mayFindFilesAgain(round)) back.flashing("error" -> said("round.find.again.refused"))
        else
          try
            roundsService.distributeNewFiles(round.getId, user.id) match {
              case RoundService.Started(_) | RoundService.Resumed(_) => back.flashing("success" -> said("round.find.again.started"))
              case RoundService.AlreadyRunning(_)                    => back.flashing("error" -> said("round.find.again.running"))
              // not reached: given up only a failed job, which isn't offered (its Retry is in its row)
              case givenUp: RoundService.GivenUp =>
                back.flashing("error" -> messages("round.distribute.attempts", round.number, givenUp.reference))
            }
          catch {
            // the rounds it leaves out, or its previous rounds, refused it: their message
            case refused: RoundDistributionHandler.ExcludeRoundsRefused =>
              logger.warn(s"Round ${round.getId}: matching files not found again: ${refused.text}")
              back.flashing("error" -> said("round.find.again.failed", messages(refused.refusal.key, refused.refusal.args: _*)))
            case NonFatal(e) =>
              RoundController.refusal(e) match {
                case Some(text) =>
                  logger.warn(s"Round ${round.getId}: matching files not found again: $text")
                  back.flashing("error" -> said("round.find.again.failed", text))
                case None =>
                  val reference = Secured.newReference()
                  logger.error(s"Round ${round.getId}: could not find its matching files again ref=$reference", e)
                  back.flashing("error" -> said("round.find.again.failed.reference", reference))
              }
          }
      }
    }

  /** Activates or deactivates a juror in a round of a contest the user administers.
    * Only that round's round_user row changes, under the round's lock; refused while a job holds
    * the round (a distribution, or a juror being added). Done, with a warning (DG10, never a
    * refusal): a juror started in a round that is stopped (they can't vote until it is started),
    * or the last active juror of an open round stopped (nobody can vote in it).
    */
  def setRoundUser(): EssentialAction =
    withAuth(rolePermission(User.ADMIN_ROLES, derived = true)) { user => implicit request =>
      setRoundUserForm
        .bindFromRequest()
        .fold(
          _ => BadRequest,
          setRoundUser =>
            (setRoundUser.roundId.toLongOption, setRoundUser.userId.toLongOption) match {
              case (Some(roundId), Some(userId)) =>
                withAdminRound(user, roundId, roundNotFound(user), request) { round =>
                  // under the round's lock, refused while a job holds the round (its jurors as read);
                  // back to the statistics as filtered (the form's hidden fields)
                  val back = Redirect(RoundsFilter.fromForm(request).addTo(routes.RoundController.roundStat(roundId).url))
                  if (RoundRebalanceService.setJurorActive(roundId, userId, setRoundUser.active)) {
                    val messages = messagesApi.preferred(request)
                    val warning =
                      if (setRoundUser.active)
                        // stopped: not open to jurors, nor only frozen while open (a job reads it)
                        Option.when(!round.openToJurors && !Round.isFrozenOpen(roundId))(
                          messages("round.warning.stopped.juror", round.number))
                      else
                        Option.when(RoundUser.openWithoutActiveJuror(Seq(round)).nonEmpty)(
                          messages("round.warning.nojurors.act", round.number))
                    warning.fold(back)(w => back.flashing(RoundController.WarningFlash -> w))
                  } else
                    back
                      .flashing("error" -> messagesApi.preferred(request)("round.juror.busy", round.number))
                }
              case _ => BadRequest
            }
        )
    }

  /** Sets the images source of `contestIdParam`, else of the user's current contest. */
  def setImages(contestIdParam: Option[Long] = None): EssentialAction =
    withAuth(rolePermission(User.ADMIN_ROLES, derived = true)) { user => implicit request =>
      contestIdParam.orElse(user.currentContest) match {
        case None => Redirect(routes.ContestController.list())
        case contestId if !contestPermission(User.ADMIN_ROLES, contestId)(user) =>
          onUnAuthorized(user, request, Secured.Denial.contest(contestId, User.ADMIN_ROLES))
        case contestId =>
          imagesForm.bindFromRequest().fold(
            // a source that is no title is refused, not saved (nor a 500)
            _ => Redirect(routes.RoundController.rounds(contestId))
              .flashing("error" -> messagesApi.preferred(request)(WikiTitle.InvalidKey)),
            imagesSource => {
              for (contest <- contestId.flatMap(ContestJuryJdbc.findById)) {
                // the source only: the contest's files stay those of its imports (its own category)
                ContestJuryJdbc.setImagesSource(contest.getId, imagesSource)
              }
              Redirect(routes.RoundController.rounds(contestId))
            }
          )
      }
    }

  // the round statistics are organizer views (User.canViewOrgInfo): never a juror's, who gets
  // the 403 page naming the roles at once
  def currentRoundStat(contestId: Option[Long] = None): EssentialAction =
    withAuth(rolePermission(Set(User.ADMIN_ROLE, "root") ++ User.ORG_COM_ROLES, derived = true)) {
      sessionUser => implicit request =>
        // the asked-for contest's rounds are checked as a member of it, if an active one
        val user = contestId.fold(sessionUser)(Secured.inContest(sessionUser, _))
        val currentContestId = contestId.orElse(user.currentContest)
        // the contest's statistics refused at once (the 403 page, logged) to whoever may not see
        // them there: staff of another contest, a juror of it, a deactivated member. The page
        // saying there is no round yet is only for those who may see them
        val refused = currentContestId.filterNot(user.canViewContestStats)
        if (refused.nonEmpty)
          onUnAuthorized(user, request,
            Secured.Denial.contest(refused, User.ADMIN_ROLES ++ User.ORG_COM_ROLES))
        else {
          // the contest's rounds the user may see the statistics of, read once (the last of them
          // the fallback round; the filter's choice among them), only if needed
          def visibleIn(c: Long) = Round.findByContest(c).filter(user.canViewOrgInfo)
          lazy val visible: Seq[Round] = currentContestId.fold(Seq.empty[Round])(visibleIn)
          // the user's own open round only if they may see its statistics (a juror there may not:
          // an admin elsewhere would be sent to a round that refuses them)
          val activeRound = Round
            .activeRounds(user)
            .filter(user.canViewOrgInfo)
            .headOption
            .orElse {
              currentContestId.flatMap { contestId =>
                Round
                  .activeRounds(contestId)
                  .filter(r => user.canViewOrgInfo(r))
                  .lastOption
              }
            }
            .orElse(visible.lastOption)

          Secured.switchingContest(sessionUser, user) {
            activeRound
              .map { round =>
                // the Rounds page's filter kept (the navbar's Statistics link), and the round it
                // opens chosen as the statistics page's own menus choose (RoundsFilter.statRound,
                // the maintainer's decision of 2026-11-19): this round if the filter keeps it,
                // else the first of the contest's rounds the user may see that it keeps, in order
                val requested = RoundsFilter.fromRequest(request)
                // a Track choice: the contest's tracks, from all its rounds (round lineage slice A); a
                // track the contest doesn't have is none, and left out of the redirect
                val filter = if (requested.track.isEmpty) requested
                  else ContestJuryJdbc.findById(round.contestId)
                    .fold(requested.withTrack(None))(c => requested.withLineage(services.RoundLineage.of(Round.findByContest(c.getId), c)))
                val target =
                  if (filter.isDefault) round
                  else filter.statRound(round, if (currentContestId.contains(round.contestId)) visible else visibleIn(round.contestId))
                Redirect(filter.addTo(routes.RoundController.roundStat(target.getId).url))
              }
              .getOrElse {
                // a state, not an error, as for a juror ([[LoginController.index]]): no link back
                // to the start page, which would come here again
                JurorContests.noRoundPage(user)
              }
          }
        }
    }

  def roundStat(roundId: Long): EssentialAction =
    withAuthOn(blocking)(rolePermission(Set(User.ADMIN_ROLE, "root") ++ User.ORG_COM_ROLES, derived = true)) {
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
              // open, but nobody can vote in it: a notice (DG10)
              val noActiveJuror = RoundUser.openWithoutActiveJuror(Seq(round)).nonEmpty
              // the round stopped, started again or closed by a juror of it (RoundJurorChanges), and
              // its settings saved (RoundChanges), with who: for those who see the organiser view,
              // not a juror of the open round. Two lists, each with its own limit: many settings
              // entries never push a juror's out
              val roundChanges =
                if (!services.GalleryService.othersVotesVisible(user, round)) Nil
                else {
                  val changes = Seq(ContestChangeJdbc.RoundJurorKinds, ContestChangeJdbc.RoundSettingKinds).flatMap(kinds =>
                    ContestChangeJdbc.findByRound(round.contestId, round.getId, RoundController.JurorChangesShown, kinds))
                  val names = User.findByIds(changes.map(_.actorId).distinct).map(u => u.getId -> u.fullname).toMap
                  changes.map(c => c -> names.getOrElse(c.actorId, s"#${c.actorId}"))
                }

              // the contest's tracks (round lineage slice A): the rounds' Track chips and the Track menu
              val lineage = ContestJuryJdbc.findById(round.contestId).map(services.RoundLineage.of(stat.rounds, _))
              val filter = lineage.fold(RoundsFilter.fromRequest(request))(RoundsFilter.fromRequest(request).withLineage(_))

              // being set up: its one-line state and a link to its current step (UI-path plan §0 row 7)
              val setupStep =
                if (!round.distributing) None
                else RoundNextStep.of(round, RoundService.latestJob(round), RoundNextStep.inCount(round))

              Secured.switchingContest(sessionUser, user)(
                Ok(views.html.roundStat(user, round, stat, comments, filter, noActiveJuror,
                  roundChanges, lineage, setupStep, Secured.administersRound(user, round))))
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
    withAuthJsonOn(blocking)(rolePermission(Secured.ViewerRoles, derived = true)) { sessionUser => implicit request =>
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

  val imagesForm = Form("images" -> services.imports.WikiTitle.optionalMapping)

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

  /** The statistics page's latest entries of each list: the round stopped, started again or closed
    * by a juror of it; its settings saved.
    */
  val JurorChangesShown = 20

  /** A rated round's stored criteria if it is started ([[RoundCriterion.started]]: running or
    * with a vote, criterion rates included, as the save checks), for its edit form: its
    * criteria can't be changed ([[RoundCriterion.update]]), so the form lists them, read
    * only, without selects. None for a round not started (stopped before its first vote: the
    * selects), and for a yes/no or comments-only round (no criteria in its form).
    *
    * @param storedIds the round's stored criteria, if already read (read here only if it is started)
    */
  def lockedCriteria(round: Round, storedIds: Option[Seq[Long]] = None): Option[Seq[Long]] =
    round.id.filter(id => round.isRated && RoundCriterion.started(id, criteriaRates = true))
      .map(id => storedIds.getOrElse(RoundCriterion.idsOf(id)))

  /** The flash key of a warning after a done action (DG10: warned, never refused): a juror
    * started in a stopped round, an open round left with no active juror. Shown on the rounds
    * list, the round statistics and the round's conflicts page.
    */
  val WarningFlash = "warning"

  /** "Count new files" runs here, one at a time: a full read of the round's candidates. */
  private[controllers] lazy val countContext: ExecutionContext =
    ExecutionContext.fromExecutor(java.util.concurrent.Executors.newSingleThreadExecutor { (r: Runnable) =>
      val t = new Thread(r, "count-new-files")
      t.setDaemon(true)
      t
    })

  /** A re-apply's criteria refused in its transaction (rolled back): the message key. */
  final case class CriteriaRefused(messageKey: String) extends RuntimeException(messageKey, null, false, false)

  /** The Rounds page's rounds being set up (UI-path plan §4 step 1, replacing #192's interim "Not
    * started – waiting for distribution"): each with its setup step from its latest distribution job,
    * derived from its [[RoundStartBlock]] as on its other pages ([[RoundNextStep.of]]), its matching files
    * read only for a round waiting in review. A round being distributed, or whose creation failed, was
    * cancelled or didn't finish, has none: its block's own state.
    *
    * @param distributions each round's latest distribution job
    */
  def setupSteps(rounds: Seq[Round], distributions: Map[Long, Job]): Map[Long, RoundNextStep] =
    rounds.flatMap(r => RoundNextStep.of(r, distributions.get(r.getId), RoundNextStep.inCount(r)).map(r.getId -> _)).toMap

  /** The round form was posted in its re-apply state (its hidden `reapply` field, P3-5). */
  def reapplyPosted(request: Request[AnyContent]): Boolean =
    request.body.asFormUrlEncoded.flatMap(_.get("reapply")).exists(_.contains("true"))

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

  private[controllers] val TokenPattern = "[0-9a-fA-F-]{36}".r

  /** The round form's id in `form_refused` and `resubmit_dropped` lines. */
  val FormId = "round"

  /** A rating type change refused: the round left its setup meanwhile (#184). */
  val RatesLockedKey = "round.rates.locked"

  /** A round form's page: never stored by the browser, so Back asks the server again (the
    * new-round form's history entry then resolves its token, [[RoundController.editRound]]).
    */
  def formPage(result: Result): Result = result.withHeaders("Cache-Control" -> "no-store")

  /** A round saved from a new-round form whose token was posted or opened again: its Edit page,
    * saying so ("Round N is saved. You can change its settings here."), with `more` flashed too.
    */
  def toSavedRound(saved: Round, messages: Messages, more: (String, String)*)(implicit request: RequestHeader): Result =
    play.api.mvc.Results.Redirect(RoundsFilter.fromRequest(request).addTo(routes.RoundController.editRound(saved.id, saved.contestId).url))
      .flashing((("success" -> messages("round.saved.edit.here", saved.number)) +: more): _*)

  /** Whether a round of `rounds` other than `roundId` takes files from it (a previous round of it). */
  def takenFrom(roundId: Long, rounds: Seq[Round]): Boolean =
    rounds.exists(r => !r.id.contains(roundId) && r.deletedAt.isEmpty && r.previousIds.contains(roundId))

  /** The posted settings of a round form, as a hash: its fields without the CSRF and submission
    * tokens and the button pressed, each field's values in order, the "Add files" list's lines
    * trimmed and sorted. Compared, never stored or logged ([[SavedForms]]).
    */
  def postedHash(request: Request[AnyContent]): String = {
    val data = request.body.asFormUrlEncoded.getOrElse(Map.empty) -- Seq("csrfToken", "submitToken", "next")
    val fields = data.toSeq.map { case (key, values) =>
      key -> (if (key == "fileList") values.flatMap(_.split("\r?\n")).map(_.trim).filter(_.nonEmpty).sorted
              else values.sorted)
    }.sortBy(_._1)
    val text = fields.map { case (key, values) => key + "\u0001" + values.mkString("\u0002") }.mkString("\u0000")
    // an in-memory equality check, not a secret: two MurmurHash3 seeds and the length (no
    // MessageDigest outside the password hasher and API tokens, RawSqlSpec)
    val h1 = scala.util.hashing.MurmurHash3.stringHash(text)
    val h2 = scala.util.hashing.MurmurHash3.stringHash(text, 0x5f3759df)
    f"$h1%08x$h2%08x-${text.length}"
  }

  /** The hash of the form each token saved a round from ([[postedHash]]), in memory only, for an
    * hour, at most [[MaxEntries]]: a repost of the token is a double click (`same`) or edits that
    * are not applied (`changed`; after a restart, always `changed`, the safe side). Never stored or
    * logged (problem-monitoring plan §2.3).
    */
  object SavedForms {
    val MaxAgeMillis: Long = 60L * 60 * 1000
    val MaxEntries = 10000
    private val hashes = new java.util.concurrent.ConcurrentHashMap[String, (String, Long)]()

    def remember(token: String, hash: String, now: Long = System.currentTimeMillis()): Unit = {
      hashes.entrySet().removeIf(e => now - e.getValue._2 > MaxAgeMillis)
      if (hashes.size >= MaxEntries) {
        import scala.jdk.CollectionConverters._
        hashes.entrySet().asScala.toSeq.sortBy(_.getValue._2).take(hashes.size - MaxEntries + 1)
          .foreach(e => hashes.remove(e.getKey))
      }
      hashes.put(token, (hash, now))
    }

    def same(token: String, hash: String, now: Long = System.currentTimeMillis()): Boolean =
      Option(hashes.get(token)).exists { case (saved, at) => saved == hash && now - at <= MaxAgeMillis }

    def size: Int = hashes.size
  }

  /** The token a refused new-round form comes back with: the posted one, unless a round (a deleted
    * one) holds it; then a fresh one, so the form keeps its double-submit and Back protection.
    */
  def formToken(posted: Option[String]): Option[String] =
    posted.filterNot(Round.submitTokenTaken).orElse(Some(newSubmitToken()))

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

/** The round form's state beyond its form and lists ([[RoundController]], editRound.scala.html).
  *
  * @param reapply        a round held in review with no vote and no job: its filters editable, "Re-apply filters"
  * @param unratedRound   an existing yes/no or comments-only round: no criteria, no worst mark
  * @param waiting        its files waiting for a juror: "Also give out the N waiting files"
  * @param lockedCriteria a started rated round's stored criteria, listed read only
  * @param nextStep       a round being set up: its next step (#186)
  * @param ratesEditable  a round being set up: its rating type can change (#184)
  * @param resolveUrl     a new round's form: its URL with its token, for the page's history entry
  * @param waitFor        a save refused to wait for another round's job: that job (Q7)
  * @param summaryHeading the error summary's heading, if not "Nothing was saved" (a saved edit's refused distribution)
  */
final case class RoundFormState(
    reapply: Boolean = false,
    unratedRound: Boolean = false,
    waiting: Option[Long] = None,
    lockedCriteria: Option[Seq[Long]] = None,
    nextStep: Option[RoundNextStep] = None,
    ratesEditable: Boolean = false,
    resolveUrl: Option[String] = None,
    waitFor: Option[RoundFormWait] = None,
    summaryHeading: Option[String] = None
)

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
