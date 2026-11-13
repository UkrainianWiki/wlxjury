package controllers

import db.scalikejdbc.{MonumentJdbc, Round, RoundCriterion, RoundUser, VoteJdbc, User}
import modules.RequestTiming
import org.intracer.wmua._
import play.api.Configuration
import play.api.i18n.I18nSupport
import play.api.mvc.{ControllerComponents, EssentialAction, Request, Result}
import services.GalleryService

import javax.inject.Inject

class LargeViewController @Inject() (
    cc: ControllerComponents,
    galleryService: GalleryService,
    config: Configuration = Configuration.empty
) extends Secured(cc)
    with I18nSupport {

  /** The large view for all the jurors from `round_image`'s counts (`roundImage.read`, P2b). */
  private val countsRead = config.getOptional[Boolean]("roundImage.read").getOrElse(false)

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
        if (roundId == 0) JurorContests.firstOpenRound(sessionUser)
        else Round.findById(roundId)
      // a round of another of the user's contests opens in that contest
      val user = maybeRound.fold(sessionUser)(r => Secured.inContest(sessionUser, r.contestId))
      Secured.switchingContest(sessionUser, user)(show(pageId, user, asUserId, region, roundId, maybeRound))
    })

  def largeCurrentUser(pageId: Long, region: String = "all"): EssentialAction =
    noStore(withAuth() { sessionUser => implicit request =>
      // as round 0 in [[large]]: the juror's open round, in another of their contests if need be
      val maybeRound = JurorContests.firstOpenRound(sessionUser)
      val user = maybeRound.fold(sessionUser)(r => Secured.inContest(sessionUser, r.contestId))
      Secured.switchingContest(sessionUser, user)(show(pageId, user, user.getId, region, 0, maybeRound))
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
      criteria: Option[Long] = None
  ): EssentialAction =
    // only a juror of the round's contest votes (Secured.VotePermission), by the buttons, the
    // keys or a criterion's rate: an organizer, admin or root without that role is a 403 page
    withAuth(Secured.VotePermission(roundId)) { user => implicit request =>
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

        // the juror's own vote of the file in the round, or of one of the round's criteria; a
        // file the juror has no vote for in the round, or a criterion the round hasn't,
        // changes nothing and is a 404
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
            if (roundId == 0) JurorContests.noRoundPage(user)
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
            showRound(pageId, user, RoundList.of(round, user, asUserId, region, GalleryView.of(request)).withCountsRead(countsRead))
        }
    }

  /** The file in its list, with the files around it; a file not in the viewer's round (or
    * region), the list's first page.
    */
  private def showRound(pageId: Long, user: User, list: RoundList)(implicit request: Request[Any]): Result =
    list.around(pageId).fold(Redirect(list.pageUrl(1)))(showFile(_, user, list))

  private def showFile(hood: Neighbourhood, user: User, viewed: RoundList)(implicit request: Request[Any]): Result = {
    val asUserId = viewed.asUserId
    val pageId = hood.file.pageId
    val roundCriteria = if (viewed.round.hasCriteria) RoundCriterion.ofRound(viewed.round.getId) else Nil
    // a criteria round's results are drawn per criterion (Round.perCriterion)
    val list = if (roundCriteria.isEmpty) viewed
      else viewed.withRound(viewed.round.copy(criteriaCount = roundCriteria.size))
    val round = list.round

    val votes = if (user.canViewOrgInfo(round)) {
      VoteJdbc.byRoundAndImageWithJury(round.getId, pageId)
    } else Seq.empty

    // the round's criteria in their order (also when it is the current round, roundId 0), in
    // stored units: each with the viewed juror's rate (0 without one: rates are written by
    // votes; none without a vote), or for all the jurors together (only organisers view them,
    // GalleryService.viewAs) each one's average over the votes rated in every criterion, 0
    // for none (C3)
    val criteria: Seq[(Criterion, Double)] =
      if (roundCriteria.isEmpty) Nil
      else if (asUserId == 0) {
        val averages = CriteriaRate.averages(round.getId, Seq(pageId)).getOrElse(pageId, Map.empty)
        roundCriteria.map(c => c -> averages.get(c.id).fold(0.0)(_.toDouble))
      } else VoteJdbc.findBy(pageId, asUserId, round.getId).fold(Seq.empty[(Criterion, Double)]) { vote =>
        val rates = CriteriaRate.getRates(vote.getId).map(c => c.criterionId -> c.rate).toMap
        roundCriteria.map(c => c -> rates.getOrElse(c.id, 0).toDouble)
      }

    // each juror's rates of the criteria, in the organisers' list of the file's votes
    val jurorCriteria = JurorCriteria(roundCriteria,
      if (roundCriteria.isEmpty || votes.isEmpty) Map.empty else CriteriaRate.ratesOfFile(round.getId, pageId))

    val monument = hood.file.image.monumentId.flatMap(id => if (id.trim.nonEmpty) MonumentJdbc.find(id) else None)

    val comments = CommentJdbc.findBySubjectAndContest(pageId, round.contestId)

    render {
      case Accepts.Html() =>
        Ok(
          RequestTiming.render(
            views.html.large.large(
              user,
              list,
              hood,
              monument,
              comments,
              votes,
              criteria,
              jurorCriteria
            )
          )
        )
      case Accepts.Json() =>
        // nothing in the app reads this JSON (no page script requests it); it follows the
        // page's rules: the votes as viewAs allows, authors (and the descriptions, which may
        // name them) only to those the page shows authors to (large.scala.html's authorInfo,
        // secure-coding.md §22), absent otherwise; the author as the page shows it, an account
        // (StoredAuthors.shown), never an older import's wikitext
        val showAuthor = user.canViewOrgInfo(round)
        Ok(
          Json.toJson(
            hood.files.map { file =>
              val image =
                if (showAuthor) file.image.copy(author = services.imports.StoredAuthors.shown(file.image.author))
                else file.image.copy(author = None, description = None)
              file.copy(image = image, votes = file.votes.map(_.copy(createdAt = None)))
            }
          )
        )
    }
  }
}
