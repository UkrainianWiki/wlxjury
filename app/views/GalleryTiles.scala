package views

import db.scalikejdbc.{Round, User}
import play.twirl.api.{Html, HtmlFormat}

/** The attributes of a page's #gallery that gallery-init.js starts the tiles with: whether the
  * viewer votes on them (their own files in an active round, as a juror), whether the round is
  * binary (thumbs up and down), and a rated round's tile stars ([[TileStars]]), as JSON.
  */
object GalleryTiles {

  def jury(user: User, asUserId: Long, round: Option[Round]): Boolean =
    user != null && user.getId == asUserId && round.exists(_.active) && user.roles.contains("jury")

  def attributes(user: User, asUserId: Long, round: Option[Round]): Html = {
    // with the worst mark (✗), a ✗ button beside the stars (tile-votes.js)
    val stars = TileStars.round(user, asUserId, round).map { r =>
      s"""{"number":${r.rates.id / r.starUnits},"half":${r.starUnits == 2},"worst":${r.takesWorstMark}}"""
    }
    Html(
      s"""data-jury="${jury(user, asUserId, round)}" data-binary="${round.exists(_.isBinary)}"""" +
        stars.fold("")(json => s""" data-stars="${HtmlFormat.escape(json)}"""")
    )
  }
}
