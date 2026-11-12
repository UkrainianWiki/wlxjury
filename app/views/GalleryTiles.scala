package views

import db.scalikejdbc.{Round, User}
import play.twirl.api.{Html, HtmlFormat}

/** The attributes of a page's #gallery that gallery-init.js starts the tiles with: whether the
  * viewer votes on them (their own files in an active round, as a juror), whether the round is
  * binary (thumbs up and down), a rated round's tile stars ([[TileStars]]), as JSON, and the
  * viewed juror's votes shown read-only ([[viewVotes]]).
  */
object GalleryTiles {

  def jury(user: User, asUserId: Long, round: Option[Round]): Boolean =
    user != null && user.getId == asUserId && round.exists(_.active) && user.roles.contains("jury")

  /** One juror's votes shown read-only on a binary round's tiles (tile-votes.js addVoteMark):
    * their votes as the organisers view them, or the juror's own where they don't vote (a round
    * not active); never all the jurors' votes together (asUserId 0), which have their tallies.
    * `{"binary":true}`, as JSON. (A rated round's gallery is its by-rate list, whose stars
    * already show the juror's rates.)
    */
  def viewVotes(user: User, asUserId: Long, round: Option[Round]): Option[String] =
    round.filter(r => user != null && asUserId != 0 && !jury(user, asUserId, Some(r)) && r.isBinary)
      .map(_ => """{"binary":true}""")

  def attributes(user: User, asUserId: Long, round: Option[Round]): Html = {
    // with the worst mark, its button (a bi-x-lg icon) beside the stars (tile-votes.js)
    val stars = TileStars.round(user, asUserId, round).map { r =>
      s"""{"number":${r.rates.id / r.starUnits},"half":${r.starUnits == 2},"worst":${r.takesWorstMark}}"""
    }
    Html(
      s"""data-jury="${jury(user, asUserId, round)}" data-binary="${round.exists(_.isBinary)}"""" +
        stars.fold("")(json => s""" data-stars="${HtmlFormat.escape(json)}"""") +
        viewVotes(user, asUserId, round).fold("")(json => s""" data-view-votes="${HtmlFormat.escape(json)}"""")
    )
  }
}
