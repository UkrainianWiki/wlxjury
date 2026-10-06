package controllers

import controllers.EditRound.{editRoundForm, jurorsMapping}
import db.scalikejdbc._
import org.intracer.wmua.cmd.DistributeImages
import org.scalawiki.wlx.dto.SpecialNomination
import play.api.Logging
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.I18nSupport
import play.api.libs.json.Json
import play.api.mvc.{ControllerComponents, EssentialAction, RequestHeader, Result}
import play.twirl.api.Html
import services.RoundService

import javax.inject.Inject
import modules.BlockingExecutionContext
import scala.util.control.NonFatal

/** Controller for displaying pages related to contest rounds
  * @param contestsController
  */
class RoundController @Inject() (
    cc: ControllerComponents,
    val contestsController: ContestController,
    roundsService: RoundService,
    distributeImages: DistributeImages,
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
      Ok(roundFormView(user, withTopImages, filledRound, Some(rounds), Some(jurors)))
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
      knownJurors: Option[Seq[User]] = None
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
      contestSpecialNominations(contestId)
    )
  }

  /** The number of files "Distribute new files" would add to the round, as JSON
    * `{"count": n}`: the full image filtering (which may query Commons), so it runs
    * only when the organizer asks for it, not on every view of the edit page.
    */
  def newFilesCount(id: Long): EssentialAction =
    withAuthOn(blocking)(rolePermission(User.ADMIN_ROLES)) { user => _ =>
      withAdminRound(user, id, NotFound(Json.obj("error" -> "round not found"))) { round =>
        val prevRounds = round.previousIds.flatMap(Round.findById)
        Ok(Json.obj("count" -> distributeImages.imagesByRound(round, prevRounds).size))
      }
    }

  /** The jurors' stat table of a round, as an HTML fragment for the edit page's
    * "jurors" panel, loaded when the panel is opened.
    */
  def roundStatTable(roundId: Long): EssentialAction =
    withAuthOn(blocking)(rolePermission(User.ADMIN_ROLES)) { user => implicit request =>
      withAdminRound(user, roundId, NotFound("")) { round =>
        Ok(views.html.roundStatTable(user, round, roundsService.getRoundStat(roundId, round)))
      }
    }

  /** Runs `f` with the round if `user` administers its contest: the round is loaded
    * once, for the permission check and the action.
    */
  private def withAdminRound(user: User, roundId: Long, notFound: => Result)(f: Round => Result): Result =
    Round.findById(roundId) match {
      case None                                                                         => notFound
      case Some(round) if contestPermission(User.ADMIN_ROLES, Some(round.contestId))(user) => f(round)
      case Some(_)                                                                      => onUnAuthorized(user)
    }

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
            val contestId: Option[Long] = formWithErrors.data.get("contest").map(_.toLong)
            BadRequest(
              views.html.editRound(
                user,
                formWithErrors,
                newRound = !formWithErrors.data.get("id").exists(_.nonEmpty),
                rounds = contestId.map(Round.findByContest).getOrElse(Nil),
                contestId = contestId,
                jurors = User.loadJurors(contestId.get),
                jurorsMapping = jurorsMapping
              )
            )
          },
          editForm => {
            val round = editForm.round.copy(active = true)
            val contestId = round.contestId
            val toRoundsList = Redirect(routes.RoundController.rounds(Some(contestId)))

            // Re-render the same create/edit form with the entered values kept and the
            // failure shown as a global form error, so the admin can fix and resubmit
            // instead of losing the page.
            def reRender(r: Round, message: String, args: Any*): Result =
              BadRequest(
                roundFormView(
                  user,
                  r,
                  editRoundForm
                    .fill(EditRound(r, editForm.jurors, editForm.returnTo, editForm.newImages))
                    .withGlobalError(message, args: _*)
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

            round.id match {
              case None =>
                try {
                  roundsService.createNewRound(round, editForm.jurors)
                  toRoundsList
                } catch {
                  // The same creation submitted again (the first one outlasted the
                  // browser's or the proxy's wait): show the rounds list, which has it.
                  case e: RoundService.DuplicateRound =>
                    logger.warn(s"Contest $contestId: ${e.getMessage}")
                    toRoundsList.flashing("error" -> e.getMessage)
                  // Round row exists but its images are incomplete / failed: send the
                  // admin to its edit page, where "Distribute new files" can retry.
                  case e: RoundService.RoundNotFullyDistributed =>
                    logger.error(e.getMessage)
                    Redirect(routes.RoundController.editRound(Some(e.roundId), contestId, None))
                      .flashing("error" -> e.getMessage)
                  case e: RoundService.RoundDistributionFailed =>
                    Redirect(routes.RoundController.editRound(Some(e.roundId), contestId, None))
                      .flashing("error" -> e.getMessage)
                  // Rejected before anything was created (bad input): keep the form.
                  case NonFatal(e) =>
                    logger.warn(s"Rejected round creation for contest $contestId: ${detail(e)}")
                    reRender(round, "round.creation.failed", detail(e))
                }

              case Some(roundId) =>
                Round.updateRound(roundId, round)
                if (!editForm.newImages) toRoundsList
                else
                  try {
                    roundsService.distributeNewImages(roundId)
                    toRoundsList
                  } catch {
                    case e: RoundService.RoundNotFullyDistributed =>
                      logger.error(e.getMessage)
                      reRender(Round.findById(roundId).getOrElse(round), e.getMessage)
                    case NonFatal(e) =>
                      logger.error(s"Failed to distribute new files for round $roundId", e)
                      reRender(
                        Round.findById(roundId).getOrElse(round),
                        "round.distribute.failed",
                        detail(e)
                      )
                  }
            }
          }
        )
    }

  def setRound(): EssentialAction = withAuth(rolePermission(User.ADMIN_ROLES)) {
    user => implicit request =>
      val selectRound = selectRoundForm.bindFromRequest().get

      val id = selectRound.roundId.toLong
      val round = Round.findById(id)
      round.foreach { r =>
        roundsService.setCurrentRound(Nil, r.copy(active = selectRound.active))
      }

      Redirect(routes.RoundController.rounds(round.map(_.contestId)))
  }

  def setRoundUser(): EssentialAction =
    withAuth(rolePermission(User.ADMIN_ROLES)) { user => implicit request =>
      val setRoundUser = setRoundUserForm.bindFromRequest().get
      RoundUser.setActive(
        setRoundUser.roundId.toLong,
        setRoundUser.userId.toLong,
        setRoundUser.active
      )
      Redirect(routes.RoundController.roundStat(setRoundUser.roundId.toLong))
    }

  def setImages(): EssentialAction =
    withAuth(rolePermission(User.ADMIN_ROLES)) { user => implicit request =>
      val imagesSource: Option[String] = imagesForm.bindFromRequest().get
      for (contest <- user.currentContest.flatMap(ContestJuryJdbc.findById)) {
        ContestJuryJdbc.setImagesSource(contest.getId, imagesSource)

        // val images: Seq[Page] = Await.result(Global.commons.categoryMembers(PageQuery.byTitle(imagesSource.get)), 1.minute)

        //          for (contestId <- contest.id;
        //               currentRoundId <- ContestJuryJdbc.currentRound(contestId);
        //               round <- RoundJdbc.find(currentRoundId)) {
        //            Tools.distributeImages(round, round.jurors, None)
        //          }
      }

      Redirect(routes.RoundController.rounds())

    }

  def currentRoundStat(contestId: Option[Long] = None): EssentialAction =
    withAuth(rolePermission(Set(User.ADMIN_ROLE, "jury", "root") ++ User.ORG_COM_ROLES)) {
      user => implicit request =>
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

        activeRound
          .map { round =>
            Redirect(routes.RoundController.roundStat(round.getId))
          }
          .getOrElse {
            Redirect(routes.LoginController.error("There is no active rounds in your contest"))
          }
    }

  def roundStat(roundId: Long): EssentialAction =
    withAuthOn(blocking)(rolePermission(Set(User.ADMIN_ROLE, "jury", "root") ++ User.ORG_COM_ROLES)) {
      user => implicit request =>
        Round
          .findById(roundId)
          .map { round =>
            if (!user.canViewOrgInfo(round)) {
              onUnAuthorized(user)
            } else {
              val stat = roundsService.getRoundStat(roundId, round)

              Ok(views.html.roundStat(user, round, stat))
            }
          }
          .getOrElse {
            Redirect(routes.LoginController.error("Round not found"))
          }
    }

  def mergeRounds(): EssentialAction =
    withAuth(rolePermission(User.ADMIN_ROLES)) { user => implicit request =>
      val mergeRounds = mergeRoundsForm.bindFromRequest().get
      roundsService.mergeRounds(
        user.contestId.get,
        mergeRounds.targetRoundId,
        mergeRounds.sourceRoundId
      )
      Redirect(routes.RoundController.rounds())
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

case class SelectRound(roundId: String, active: Boolean)

case class SetRoundUser(roundId: String, userId: String, active: Boolean)

case class RoundStat(
    jurors: Seq[User],
    round: Round,
    rounds: Seq[Round],
    byUserCount: Map[Long, Int],
    byUserRateCount: Map[Long, Map[Int, Int]],
    total: Int,
    totalByRate: Map[Int, Int]
)
