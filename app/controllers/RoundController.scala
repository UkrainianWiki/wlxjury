package controllers

import controllers.EditRound.{editRoundForm, jurorsMapping}
import db.scalikejdbc._
import org.intracer.wmua.cmd.DistributeImages
import org.scalawiki.wlx.dto.SpecialNomination
import play.api.Logging
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.I18nSupport
import play.api.mvc.{ControllerComponents, EssentialAction}
import services.RoundService

import javax.inject.Inject

/** Controller for displaying pages related to contest rounds
  * @param contestsController
  */
class RoundController @Inject() (
    cc: ControllerComponents,
    val contestsController: ContestController,
    roundsService: RoundService,
    distributeImages: DistributeImages
) extends Secured(cc)
    with I18nSupport
    with Logging {

  /** Shows list of rounds in a contest
    * @param contestIdParam
    * @return
    */
  def rounds(contestIdParam: Option[Long] = None): EssentialAction =
    withAuth(contestPermission(User.ADMIN_ROLES, contestIdParam)) { user => implicit request =>
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
              ImageJdbc.roundsStat(contestId, rounds.size).toMap,
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
    withAuth(contestPermission(User.ADMIN_ROLES, Some(contestId))) { user => implicit request =>
      val rounds = Round.findByContest(contestId)

      val round: Round = roundId
        .flatMap(Round.findById)
        .getOrElse(
          new Round(id = None, contestId = contestId, number = rounds.size + 1)
        )

      val withTopImages = topImages.map(n => round.copy(topImages = Some(n))).getOrElse(round)

      val regions = contestsController.regions(contestId)
      val jurors = round.id.fold(User.loadJurors(contestId))(User.findByRoundSelection).sorted
      val editRound = EditRound(withTopImages, jurors.flatMap(_.id), None)
      val filledRound = editRoundForm.fill(editRound)
      val stat = round.id.map(id => roundsService.getRoundStat(id, round))
      val prevRound = round.previous.flatMap(Round.findById)
      val images = round.id
        .map(_ => distributeImages.imagesByRound(round, prevRound))
        .getOrElse(Nil)
      Ok(
        views.html.editRound(
          user,
          filledRound,
          round.id.isEmpty,
          rounds,
          Some(round.contestId),
          jurors,
          jurorsMapping,
          regions,
          stat,
          images,
          contestSpecialNominations(contestId)
        )
      )
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
    withAuth(rolePermission(User.ADMIN_ROLES)) { user => implicit request =>
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

            if (round.id.isEmpty) {
              roundsService.createNewRound(round, editForm.jurors)
            } else {
              for {
                roundId <- round.id
                currentRound <- Round.findById(roundId)
              } yield {
                Round.updateRound(roundId, round)
                if (editForm.newImages) {
                  val prevRound = round.previous.flatMap(Round.findById)
                  val jurors = User.findByRoundSelection(roundId)
                  distributeImages.distributeImages(currentRound, jurors, prevRound)
                }
              }
            }
            Redirect(routes.RoundController.rounds(Some(round.contestId)))
          }
        )
    }

  def setRound(): EssentialAction = withAuth(rolePermission(User.ADMIN_ROLES)) {
    user => implicit request =>
      val selectRound = selectRoundForm.bindFromRequest().get

      val id = selectRound.roundId.toLong
      val round = Round.findById(id)
      round.foreach { r =>
        roundsService.setCurrentRound(None, r.copy(active = selectRound.active))
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
    withAuth(rolePermission(Set(User.ADMIN_ROLE, "jury", "root") ++ User.ORG_COM_ROLES)) {
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
