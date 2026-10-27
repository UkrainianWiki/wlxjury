package controllers

import db.scalikejdbc.rewrite.ImageDbNew.Limit
import db.scalikejdbc.{MonumentJdbc, Round, SelectionJdbc, User}
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

  def large(asUserId: Long, pageId: Long, region: String = "all", roundId: Long): EssentialAction =
    withAuth() { sessionUser => implicit request =>
      val maybeRound =
        if (roundId == 0) Round.activeRounds(sessionUser).headOption
        else Round.findById(roundId)
      // a round of another of the user's contests opens in that contest
      val user = maybeRound.fold(sessionUser)(r => Secured.inContest(sessionUser, r.contestId))
      Secured.switchingContest(sessionUser, user)(show(pageId, user, asUserId, region, roundId, maybeRound))
    }

  def largeCurrentUser(pageId: Long, region: String = "all"): EssentialAction =
    withAuth() { user => implicit request =>
      show(pageId, user, user.getId, region, 0, Round.activeRounds(user).headOption)
    }

  /** A vote in the large view, going on in its list ([[RoundList.voteUrl]]): to the file after
    * this one (before the vote), or the one before at the list's end; to the same file in a
    * round with criteria.
    */
  def rateByPageId(
      roundId: Long,
      pageId: Long,
      select: Int,
      region: String = "all",
      criteria: Option[Int] = None
  ): EssentialAction =
    withAuth(
      roundPermission(
        User.ADMIN_ROLES ++ User.JURY_ROLES ++ User.ORG_COM_ROLES,
        roundId
      )
    ) { user => implicit request =>
    Round.findById(roundId).filter(_.openToJurors).fold(
      Redirect(routes.GalleryController.list(user.getId, 1, region, roundId))
    ) { round =>
      val list = RoundList.of(round, user.getId, region, GalleryView.of(request))
      val result = afterVote(list, pageId)

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

  /** Where a vote for the file goes on to, found before the vote changes the list. */
  private def afterVote(list: RoundList, pageId: Long): Result = {
    val rank = list.query.imageRank(pageId)
    val offset = Math.max(0, rank - 3)
    val files = list.query.copy(limit = Some(Limit(Some(5), Some(offset)))).list()
    val index = files.indexWhere(_.pageId == pageId)

    val newIndex = if (!list.round.hasCriteria) {
      if (index >= files.size - 1)
        files.size - 2
      else index + 1
    } else {
      index
    }

    if (newIndex >= 0) Redirect(list.largeUrl(files(newIndex).pageId))
    else Redirect(list.pageUrl(1))
  }

  /** The large view of a round's file, by the round gallery's rules: the round must exist
    * ([[GalleryController.listGeneric]]'s pages if not), the user may view it
    * ([[GalleryService.mayViewRound]]) and with `asUserId`'s votes ([[GalleryService.viewAs]]).
    */
  def show(
      pageId: Long,
      user: User,
      asUserId: Long,
      region: String,
      roundId: Long,
      maybeRound: Option[Round]
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
            showRound(pageId, user, RoundList.of(round, asUserId, region, GalleryView.of(request)))
        }
    }

  private def showRound(pageId: Long, user: User, list: RoundList)(implicit request: Request[Any]): Result = {
    val round = list.round
    val asUserId = list.asUserId
    val query = list.query.copy(withPageId = Some(pageId).filter(_ => round.hasCriteria))
    val rank = query.imageRank(pageId)
    val offset = Math.max(0, rank - 3)
    val files = query.copy(limit = Some(Limit(Some(5), Some(offset)))).list()

    val index = files.indexWhere(_.pageId == pageId)
    if (index < 0) {
      return Redirect(files.headOption.fold(list.pageUrl(1))(file => list.largeUrl(file.pageId)))
    }

    val selection = if (user.canViewOrgInfo(round)) {
      SelectionJdbc.byRoundAndImageWithJury(round.getId, pageId)
    } else Seq.empty

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
      list.withRound(round.copy(criteriaNames = criteriaNames)),
      selection,
      byCriteria
    )
  }

  def show2(
      index: Int,
      files: Seq[ImageWithRating],
      user: User,
      list: RoundList,
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
      CommentJdbc.findBySubjectAndContest(files(index).pageId, list.round.contestId)

    render {
      case Accepts.Html() =>
        Ok(
          views.html.large.large(
            user,
            list,
            files,
            index,
            start,
            end,
            monument,
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
