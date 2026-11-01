package views

import controllers.{Pager, RateDistribution, RoundList}
import db.scalikejdbc.{Round, User}
import play.api.mvc.{Flash, RequestHeader}
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

/** The stars on a rated round's gallery tiles (tile-votes.js): only in the juror's own gallery
  * of an active round without criteria, as #gallery's data-stars (the stars' number and half
  * stars, which gallery-init.js gives unitegallery as its `wlx_stars` option) with raty's
  * script; in any other gallery no data-stars and no raty.
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

  /** #gallery's data-stars, "null" without one. */
  private def starsOption(html: String): String =
    Option(org.jsoup.Jsoup.parse(html).getElementById("gallery")).fold("missing") { g =>
      if (g.hasAttr("data-stars")) g.attr("data-stars") else "null"
    }

  "a gallery's tile stars" should {
    "be on the juror's own tiles of an active rated round" in {
      val html = page(LargeViewRenders.ratedRound)
      starsOption(html) === """{"number":5,"half":false}"""
      html must contain("javascripts/jquery.raty.js")
      html must contain("javascripts/large-rate.js")
    }

    "be in half stars in a half-star round: five for 1-10, three for 1-7" in {
      starsOption(page(LargeViewRenders.halfStarRound)) === """{"number":5,"half":true}"""
      starsOption(page(LargeViewRenders.ratedRound.copy(rates = Round.ratesById(7), halfStar = true))) ===
        """{"number":3,"half":true}"""
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
      html must contain("data-jury=\"true\"")
      html must contain("data-binary=\"true\"")
    }
  }

  "the organisers' tiles of all the jurors' votes on a binary round" should {

    /** The tiles' data, as unitegallery reads the items (data-*), of the gallery of `files`. */
    def tiles(asUserId: Long, files: Seq[org.intracer.wmua.ImageWithRating]) =
      org.jsoup.Jsoup.parse(contentAsString(views.html.gallery(juror, RoundList(LargeViewRenders.binaryRound, asUserId),
        files, Pager(1, files.size), Nil, None, new RateDistribution(Map.empty)))).select("#gallery img")

    "carry each file's approvals and rejections and their text for its tile (tile-votes.js addTally)" in {
      // as the grouped query of all the jurors' votes has them
      val grouped = LargeViewRenders.files().take(2).zipWithIndex.map { case (f, i) =>
        f.copy(approvalsFromDb = Some(3 - i), rejectionsFromDb = Some(i + 1))
      }
      val imgs = tiles(0L, grouped)
      (imgs.size === 2) and
        (imgs.eachAttr("data-approvals") === java.util.List.of("3", "2")) and
        (imgs.eachAttr("data-rejections") === java.util.List.of("1", "2")) and
        // its words (stub messages: the key), which screen readers read
        (imgs.eachAttr("data-tally") === java.util.List.of("binary.tally", "binary.tally"))
    }

    "carry none on a juror's own tiles, which show their vote" in {
      tiles(juror.getId, LargeViewRenders.files().take(2)).eachAttr("data-tally").size === 0
    }
  }
}
