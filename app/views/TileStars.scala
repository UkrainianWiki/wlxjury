package views

import db.scalikejdbc.{Round, User}

/** Where the viewer rates files with stars outside the large view (tile-votes.js): their own
  * gallery tiles and by-rate list of an active rated round, without criteria (which the large
  * view rates); not a binary round (thumbs up and down), nor another juror's or all the jurors'
  * votes (`asUserId` 0).
  */
object TileStars {

  def round(user: User, asUserId: Long, round: Option[Round]): Option[Round] =
    round.filter { r =>
      user != null && user.getId == asUserId && r.active && user.roles.contains("jury") &&
        !r.isBinary && !r.hasCriteria && r.acceptsRate(1)
    }
}
