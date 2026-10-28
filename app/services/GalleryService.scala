package services

import controllers.RateDistribution
import db.scalikejdbc.{ImageJdbc, Round, User}

class GalleryService {

  /** The rounds a round's gallery lists beside it: the contest's the user may see as an
    * organizer of it, else the user's own active rounds.
    */
  def viewerRounds(user: User, round: Round): Seq[Round] =
    if (user.canViewOrgInfo(round)) Round.findByContest(round.contestId).filter(user.canViewOrgInfo)
    else Round.activeRounds(user)

  /** Whether `user` (in the round's contest if they are an active member of it) may view
    * the round's files and votes, as in its gallery and its large view.
    */
  def mayViewRound(user: User, round: Round): Boolean =
    !isNotAuthorized(user, Some(round), round.contestId, viewerRounds(user, round))

  /** Whether `user`, who may view the round ([[mayViewRound]]), may view it with
    * `asUserId`'s votes: 0 is all the round's jurors' votes together (the galleries'
    * "byrate"/"all" view, the large view's ratings of all jurors), any other id that
    * user's own votes.
    *
    * Their own votes, always. Anyone else's, or all together, only for those who may see
    * the round's organiser info ([[User.canViewOrgInfo]]: root, the contest's admins and
    * organizers, its jurors in a `juryOrgView` round), and only of a juror of the round
    * ([[User.isRoundJuror]]), as the galleries' juror menu lists them.
    */
  def viewAs(user: User, round: Round, asUserId: Long): GalleryService.ViewAs =
    if (user.id.contains(asUserId)) GalleryService.ViewAs.Allowed
    else if (!user.canViewOrgInfo(round)) GalleryService.ViewAs.OtherUsersVotes
    else if (asUserId == 0 || User.isRoundJuror(round.getId, asUserId)) GalleryService.ViewAs.Allowed
    else GalleryService.ViewAs.NotRoundJuror

  def isNotAuthorized(
      user: User,
      maybeRound: Option[Round],
      roundContest: Long,
      rounds: Seq[Round]
  ): Boolean = {
    val userContest = user.currentContest.getOrElse(0L)
    val isOrganizer = user.roles.intersect(Set("admin", "organizer", "root")).nonEmpty
    val notAuthorized = maybeRound.isEmpty ||
      // a round being created is hidden from its jurors until its distribution is complete
      (!isOrganizer && maybeRound.exists(_.distributing)) ||
      (!user.hasRole("root") && userContest != roundContest) ||
      (user.roles.intersect(Set("admin", "organizer", "root")).isEmpty
        && !rounds.exists(_.id == maybeRound.flatMap(_.id))
        && !maybeRound.exists(_.juryOrgView))
    notAuthorized
  }

  def getAsUser(asUserId: Long, user: User): User = {
    if (asUserId == 0) {
      null
    } else if (asUserId != user.getId) {
      User.findById(asUserId).get
    } else {
      user
    }
  }

  def rateDistribution(user: User, round: Round): RateDistribution = {
    val rateMap = ImageJdbc.rateDistribution(user.getId, round.getId)
    new RateDistribution(rateMap)
  }

}

object GalleryService {

  /** [[GalleryService.viewAs]]'s answer. */
  sealed trait ViewAs

  object ViewAs {
    case object Allowed extends ViewAs

    /** Another user's votes, or all jurors' together, for a user who may see only their own. */
    case object OtherUsersVotes extends ViewAs

    /** An organiser's view as a user who is not a juror of the round. */
    case object NotRoundJuror extends ViewAs
  }

  /** The denial reason of [[ViewAs.OtherUsersVotes]] ([[controllers.Secured.Denial]]). */
  val OtherUsersVotesReason = "other_users_votes"
}
