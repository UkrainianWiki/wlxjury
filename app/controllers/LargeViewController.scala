package controllers

import db.scalikejdbc.{MonumentJdbc, Round, RoundUser, VoteJdbc, User}
import modules.RequestTiming
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

  implicit val voteWrites: OWrites[Vote] = Json.writes[Vote]
  implicit val imageWrites: OWrites[Image] = Json.writes[Image]
  /** The votes keep their JSON name, "selection", from before the rename to `votes`. */
  implicit val iwrWrites: OWrites[ImageWithRating] =
    Json.writes[ImageWithRating].transform { (o: JsObject) =>
      JsObject(o.fields.map { case ("votes", v) => "selection" -> v; case f => f })
    }

  /** A round's file in the large view; never cached, as it shows votes ([[noStore]]). */
  def large(asUserId: Long, pageId: Long, region: String = "all", roundId: Long): EssentialAction =
    noStore(withAuth() { sessionUser => implicit request =>
      val maybeRound =
        if (roundId == 0) Round.activeRounds(sessionUser).headOption
        else Round.findById(roundId)
      // a round of another of the user's contests opens in that contest
      val user = maybeRound.fold(sessionUser)(r => Secured.inContest(sessionUser, r.contestId))
      Secured.switchingContest(sessionUser, user)(show(pageId, user, asUserId, region, roundId, maybeRound))
    })

  def largeCurrentUser(pageId: Long, region: String = "all"): EssentialAction =
    noStore(withAuth() { user => implicit request =>
      show(pageId, user, user.getId, region, 0, Round.activeRounds(user).headOption)
    })

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
    // a round open to its jurors, and the juror not stopped on it (P6): else as a stopped round
    Round.findById(roundId).filter(_.openToJurors).filterNot(_ => RoundUser.isStopped(roundId, user.getId)).fold(
      Redirect(routes.GalleryController.list(user.getId, 1, region, roundId))
    ) {
      // a rate the round doesn't take (the stars and keys give none), or a vote the round
      // doesn't take: a criteria round's votes are per criterion (a vote of the file's own
      // rate would overwrite the sum of its criteria), another round's are of the file: a page
      // saying so
      case round if !round.acceptsRate(select) || criteria.isDefined != round.hasCriteria =>
        BadRequest(views.html.errorPage(Some(user), "error.title", "vote.error.invalid.rate"))
      case round =>
        val list = RoundList.of(round, user, user.getId, region, GalleryView.of(request))
        // the file's place, and where the vote goes on to, before the vote changes the list
        val hood = list.around(pageId)

        // the juror's own selection of the file in the round, or of one of its criteria; a
        // file the juror has no selection for in the round, or a criterion their selection
        // hasn't, changes nothing and is a 404
        val voted = criteria match {
          case None =>
            VoteJdbc.rate(pageId = pageId, juryId = user.getId, roundId = round.getId, rate = select) > 0
          case Some(criterion) =>
            VoteJdbc
              .findBy(pageId, user.getId, round.getId)
              .exists(vote => CriteriaRate.updateRate(vote.getId, criterion, select))
        }

        if (!voted) NotFound(views.html.errorPage(Some(user), "error.title", "error.not.found"))
        else {
          // the file itself in a round with criteria, voted for one by one; the list's first
          // page if it has no other
          val next = hood.flatMap(h => if (round.hasCriteria) Some(h.file) else h.next)
          val moved = hood.filter(_ => criteria.isEmpty).flatMap(h => list.voteMoved(pageId, h.position, select))
          Redirect(next.fold(list.pageUrl(1))(file => list.largeUrl(file.pageId))).flashing(moved.map(_.flash).toSeq: _*)
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
            showRound(pageId, user, RoundList.of(round, user, asUserId, region, GalleryView.of(request)))
        }
    }

  /** The file in its list, with the files around it; a file not in the viewer's round (or
    * region), the list's first page.
    */
  private def showRound(pageId: Long, user: User, list: RoundList)(implicit request: Request[Any]): Result =
    list.around(pageId).fold(Redirect(list.pageUrl(1)))(showFile(_, user, list))

  private def showFile(hood: Neighbourhood, user: User, list: RoundList)(implicit request: Request[Any]): Result = {
    val round = list.round
    val asUserId = list.asUserId
    val pageId = hood.file.pageId

    val votes = if (user.canViewOrgInfo(round)) {
      VoteJdbc.byRoundAndImageWithJury(round.getId, pageId)
    } else Seq.empty

    // the round's, also when it is the current one (roundId 0); none without a selection
    val byCriteria = if (round.hasCriteria && asUserId != 0) {
      VoteJdbc
        .findBy(pageId, asUserId, round.getId)
        .fold(Seq.empty[CriteriaRate])(vote => CriteriaRate.getRates(vote.getId))
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

    val monument = hood.file.image.monumentId.flatMap(id => if (id.trim.nonEmpty) MonumentJdbc.find(id) else None)

    val comments = CommentJdbc.findBySubjectAndContest(pageId, round.contestId)

    render {
      case Accepts.Html() =>
        Ok(
          RequestTiming.render(
            views.html.large.large(
              user,
              list.withRound(round.copy(criteriaNames = criteriaNames)),
              hood,
              monument,
              comments,
              votes,
              byCriteria
            )
          )
        )
      case Accepts.Json() =>
        Ok(
          Json.toJson(
            hood.files.map(file => file.copy(votes = file.votes.map(_.copy(createdAt = None))))
          )
        )
    }
  }
}
