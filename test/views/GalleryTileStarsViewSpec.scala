package views

import controllers.{Pager, RateDistribution, RoundList}
import db.scalikejdbc.{Round, User}
import play.api.mvc.{Flash, RequestHeader}
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

/** The stars on a rated round's gallery tiles (tile-votes.js): only in the juror's own gallery
  * of an active round without criteria, as unitegallery's `wlx_stars` option (the stars'
  * number and half stars) with raty's script; in any other gallery `null` and no raty.
  */
class GalleryTileStarsViewSpec extends PlaySpecification {

  private val juror = LargeViewRenders.juror

  implicit val request: RequestHeader = FakeRequest("GET", "/gallery/round/1/user/1/page/1").withCSRFToken
  implicit val flash: Flash = Flash()
  implicit val messages: play.api.i18n.Messages =
    LargeViewRenders.messages(request.asInstanceOf[play.api.mvc.Request[_]])

  /** The gallery page of `round` as `user` sees `asUserId`'s votes. */
  private def page(round: Round, user: User = juror, asUserId: Long = juror.getId): String =
    contentAsString(views.html.gallery(user, RoundList(round, asUserId), LargeViewRenders.files(), Pager(1, 13),
      Nil, None, new RateDistribution(Map.empty)))

  private def starsOption(html: String): String =
    "wlx_stars: ([^\\n]*)".r.findFirstMatchIn(html).map(_.group(1).trim).getOrElse("missing")

  "a gallery's tile stars" should {
    "be on the juror's own tiles of an active rated round" in {
      val html = page(LargeViewRenders.ratedRound)
      starsOption(html) === "{number: 5, half: false}"
      html must contain("javascripts/jquery.raty.js")
      html must contain("javascripts/large-rate.js")
    }

    "be in half stars in a half-star round: five for 1-10, three for 1-7" in {
      starsOption(page(LargeViewRenders.halfStarRound)) === "{number: 5, half: true}"
      starsOption(page(LargeViewRenders.ratedRound.copy(rates = Round.ratesById(7), halfStar = true))) ===
        "{number: 3, half: true}"
    }

    // an organizer's view of a juror's votes (it reads the round's jurors): OwnVotesSpec
    "be absent where the viewer doesn't vote on the tiles, or not with stars" in {
      val absent = Seq(
        page(LargeViewRenders.ratedRound, asUserId = LargeViewRenders.otherJurorId),
        page(LargeViewRenders.ratedRound.copy(active = false)), // stopped
        page(LargeViewRenders.binaryRound), // thumbs up and down instead
        page(LargeViewRenders.criteriaRound), // voted in the large view
        page(LargeViewRenders.ratedRound.copy(rates = Round.commentsOnlyRound))
      )
      forall(absent) { html =>
        starsOption(html) === "null"
        html must not(contain("javascripts/jquery.raty.js"))
      }
    }

    "leave the binary round's thumbs as they were" in {
      val html = page(LargeViewRenders.binaryRound)
      html must contain("jury: true")
      html must contain("binary: true")
    }
  }
}
