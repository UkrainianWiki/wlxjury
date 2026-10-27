package controllers

import db.scalikejdbc.rewrite.ImageDbNew.Limit
import db.scalikejdbc.{ContestJuryJdbc, MonumentJdbc, Round, SelectionJdbc, User}
import org.intracer.wmua._
import play.api.i18n.I18nSupport
import play.api.mvc.{ControllerComponents, EssentialAction, Request, Result}
import services.GalleryService

import javax.inject.Inject

class LargeViewController @Inject() (
    cc: ControllerComponents,
    galleryService: GalleryService
) extends Secured(cc)
    with I18nSupport {

  import play.api.libs.json._

  implicit val selectionWrites: OWrites[Selection] = Json.writes[Selection]
  implicit val imageWrites: OWrites[Image] = Json.writes[Image]
  implicit val iwrWrites: OWrites[ImageWithRating] =
    Json.writes[ImageWithRating]

  def large(
      asUserId: Long,
      pageId: Long,
      region: String = "all",
      roundId: Long,
      rate: Option[Int],
      module: String
  ): EssentialAction = withAuth() { sessionUser => implicit request =>
    val maybeRound =
      if (roundId == 0) Round.activeRounds(sessionUser).headOption
      else Round.findById(roundId)
    // a round of another of the user's contests opens in that contest
    val user = maybeRound.fold(sessionUser)(r => Secured.inContest(sessionUser, r.contestId))
    Secured.switchingContest(sessionUser, user)(show(pageId, user, asUserId, rate, region, roundId, maybeRound, module))
  }

  def largeCurrentUser(
      pageId: Long,
      region: String = "all",
      rate: Option[Int],
      module: String
  ): EssentialAction = withAuth() { user => implicit request =>
    show(pageId, user, user.getId, rate, region, 0, Round.activeRounds(user).headOption, module)
  }

  def rateByPageId(
      roundId: Long,
      pageId: Long,
      select: Int,
      region: String = "all",
      rate: Option[Int] = None,
      module: String,
      criteria: Option[Int] = None
  ): EssentialAction =
    withAuth(
      roundPermission(
        User.ADMIN_ROLES ++ User.JURY_ROLES ++ User.ORG_COM_ROLES,
        roundId
      )
    ) { user => implicit request =>
    val roundOption = Round.findById(roundId).filter(_.openToJurors)

    roundOption.fold(
      Redirect(
        routes.GalleryController.list(user.getId, 1, region, roundId, rate)
      )
    ) { round =>
      val result =
        checkLargeIndex(user, rate, pageId, region, round, module)

      criteria match {
        case None =>
          SelectionJdbc.rate(
            pageId = pageId,
            juryId = user.getId,
            roundId = round.getId,
            rate = select
          )
        case Some(criterion) =>
          // as a vote above (and the gallery's), a file the juror has no selection for in the
          // round changes nothing
          SelectionJdbc
            .findBy(pageId, user.getId, round.getId)
            .foreach(selection => CriteriaRate.updateRate(selection.getId, criterion, select))
      }
      result
    }
  }

  def removeImage(
      pageId: Long,
      roundId: Long,
      region: String = "all",
      rate: Option[Int],
      module: String
  ): EssentialAction =
    withAuth(roundPermission(User.ADMIN_ROLES, roundId)) { user => implicit request =>
      // the permission found the round; a page saying so if it is gone since, not a server error
      Round.findById(roundId).fold(
        NotFound(views.html.errorPage(Some(user), "error.title", "error.round.not.found"))
      ) { round =>
        SelectionJdbc.removeImage(pageId, roundId)
        checkLargeIndex(user, rate, pageId, region, round, module)
      }
    }

  def checkLargeIndex(
      asUser: User,
      rate: Option[Int],
      pageId: Long,
      region: String,
      round: Round,
      module: String
  ): Result = {

    val subRegions = round.specialNomination.contains("Віа Регіа")
    val query = galleryService.getQuery(
      asUser.getId,
      rate,
      round.id,
      regions = Set(region).filter(_ != "all"),
      subRegions = subRegions
    )

    val rank = query.imageRank(pageId)

    val offset = Math.max(0, rank - 3)

    val files = query.copy(limit = Some(Limit(Some(5), Some(offset)))).list()

    val index = files.indexWhere(_.pageId == pageId)

    val newIndex = if (!round.hasCriteria) {
      if (index >= files.size - 1)
        files.size - 2
      else index + 1
    } else {
      index
    }

    val newPageId =
      if (newIndex < 0)
        files.lastOption.fold(-1L)(_.pageId)
      else files(newIndex).pageId

    val roundId = round.getId

    if (newIndex >= 0) {
      Redirect(
        routes.LargeViewController
          .large(asUser.getId, newPageId, region, roundId, rate, module)
      )
    } else {

      if (module == "gallery") {
        Redirect(
          routes.GalleryController.list(asUser.getId, 1, region, roundId, rate)
        )
      } else {
        Redirect(
          routes.GalleryController.byRate(asUser.getId, 1, region, roundId)
        )
      }
    }
  }

  /** The large view of a round's file, by the round gallery's rules: the round must exist
    * ([[GalleryController.listGeneric]]'s pages if not), the user may view it
    * ([[GalleryService.mayViewRound]]) and with `asUserId`'s votes ([[GalleryService.viewAs]]).
    */
  def show(
      pageId: Long,
      user: User,
      asUserId: Long,
      rate: Option[Int],
      region: String,
      roundId: Long,
      maybeRound: Option[Round],
      module: String
  )(implicit request: Request[Any]): Result =
    maybeRound match {
      case None =>
        // no such round, or no active one: a page saying so, not a server error
        render {
          case Accepts.Html() =>
            if (roundId == 0) Ok(views.html.errorPage(Some(user), "error.title", "no.round.yet"))
            else NotFound(views.html.errorPage(Some(user), "error.title", "error.round.not.found"))
          case Accepts.Json() =>
            NotFound(Json.obj("error" -> (if (roundId == 0) "no_round" else "round_not_found")))
        }
      case Some(round) if !galleryService.mayViewRound(user, round) =>
        val denial = Secured.Denial.round(round, Secured.ViewerRoles)
        render {
          case Accepts.Html() => onUnAuthorized(user, request, denial)
          case Accepts.Json() => onUnAuthorizedJson(user, request, denial)
        }
      case Some(round) =>
        galleryService.viewAs(user, round, asUserId) match {
          // a juror's votes are their own: another's, or all together, for the organisers
          case refusal if refusal != GalleryService.ViewAs.Allowed =>
            render {
              case Accepts.Html() => viewAsRefused(user, round, refusal, json = false)
              case Accepts.Json() => viewAsRefused(user, round, refusal, json = true)
            }
          case _ =>
            showRound(pageId, user, asUserId, rate, region, roundId, round, module)
        }
    }

  private def showRound(
      pageId: Long,
      user: User,
      asUserId: Long,
      rate: Option[Int],
      region: String,
      roundId: Long,
      round: Round,
      module: String
  )(implicit request: Request[Any]): Result = {
    val subRegions = round.specialNomination.contains("Віа Регіа")
    val query = galleryService.getQuery(
      asUserId,
      rate,
      round.id,
      regions = Set(region).filter(_ != "all"),
      subRegions = subRegions,
      withPageId = Some(pageId).filter(_ => round.hasCriteria)
    )
    val rank = query.imageRank(pageId)
    val offset = Math.max(0, rank - 3)
    val files = query.copy(limit = Some(Limit(Some(5), Some(offset)))).list()

    val index = files.indexWhere(_.pageId == pageId)
    if (index < 0) {
      return Redirect(if (files.nonEmpty) {
        routes.LargeViewController
          .large(asUserId, files.head.pageId, region, round.getId, rate, module)
      } else {
        routes.GalleryController.list(asUserId, 1, region, round.getId, rate)
      })
    }

    val selection = if (user.canViewOrgInfo(round)) {
      SelectionJdbc.byRoundAndImageWithJury(round.getId, pageId)
    } else Seq.empty

    val page = index / (Pager.pageSize + 1) + 1

    // the round's, also when it is the current one (roundId 0); none without a selection
    val byCriteria = if (round.hasCriteria && asUserId != 0) {
      SelectionJdbc
        .findBy(pageId, asUserId, round.getId)
        .fold(Seq.empty[CriteriaRate])(selection => CriteriaRate.getRates(selection.getId))
        .map(c => c.criteria.toInt -> c.rate)
        .toMap
    } else Map.empty[Int, Int]

    val criteriaNames = if (round.hasCriteria) {
      Map(
        1 -> "Науковість",
        2 -> "Підпис",
        3 -> "Корисність",
        4 -> "Естетика",
        5 -> "Унікальність",
        6 -> "Технічна якість",
        7 -> "Корисність для Вікіпедії",
        8 -> "Оригінальність фото",
        9 -> "Додаткові бали"
      ).collect { case (k, v) if byCriteria.keySet.contains(k) => v }.toSeq

    } else Nil

    show2(
      index,
      files,
      user,
      asUserId,
      rate,
      page,
      round.copy(criteriaNames = criteriaNames),
      region,
      module,
      selection,
      byCriteria
    )
  }

  def show2(
      index: Int,
      files: Seq[ImageWithRating],
      user: User,
      asUserId: Long,
      rate: Option[Int],
      page: Int,
      round: Round,
      region: String,
      module: String,
      selection: Seq[(Selection, User)],
      byCriteria: Map[Int, Int] = Map.empty
  )(implicit request: Request[Any]): Result = {
    val extraRight = if (index - 2 < 0) 2 - index else 0
    val extraLeft = if (files.size < index + 3) index + 3 - files.size else 0

    val left = Math.max(0, index - 2)
    val right = Math.min(index + 3, files.size)
    val start = Math.max(0, left - extraLeft)
    val end = Math.min(files.size, right + extraRight)
    val monument = files(index).image.monumentId.flatMap(id => if (id.trim.nonEmpty) MonumentJdbc.find(id) else None)

    val comments =
      CommentJdbc.findBySubjectAndContest(files(index).pageId, round.contestId)

    render {
      case Accepts.Html() =>
        Ok(
          views.html.large.large(
            user,
            asUserId,
            files,
            index,
            start,
            end,
            page,
            rate,
            region,
            round,
            monument,
            module,
            comments,
            selection,
            byCriteria
          )
        )
      case Accepts.Json() =>
        Ok(
          Json.toJson(
            files.map(file => file.copy(selection = file.selection.map(_.copy(createdAt = None))))
          )
        )
    }
  }
}
