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
    * organizers, never a juror) and are not a juror of the round themselves
    * ([[GalleryService.othersVotesVisible]], the conflict-of-interest rule), and only of a juror
    * of the round ([[User.isRoundJuror]]), as the galleries' juror menu lists them.
    */
  def viewAs(user: User, round: Round, asUserId: Long): GalleryService.ViewAs =
    if (user.id.contains(asUserId)) GalleryService.ViewAs.Allowed
    else if (!GalleryService.othersVotesVisible(user, round)) GalleryService.ViewAs.OtherUsersVotes
    else if (asUserId == 0 || User.isRoundJuror(round.getId, asUserId)) GalleryService.ViewAs.Allowed
    else GalleryService.ViewAs.NotRoundJuror

  /** Whether `user` may not view the round's files and votes, as in its gallery and its large
    * view: [[refusal]] has a reason.
    */
  def isNotAuthorized(
      user: User,
      maybeRound: Option[Round],
      roundContest: Long,
      rounds: Seq[Round]
  ): Boolean = refusal(user, maybeRound, roundContest, rounds).isDefined

  /** Why `user` may not view the round's files and votes, as in its gallery and its large view
    * (None: they may). `rounds`: the user's ([[viewerRounds]]); a juror views only one of them,
    * their own open rounds.
    *
    * The refusals are those of the one check before (support diagnostics D-0b, DG7), told apart:
    * a juror of the round whose round is stopped, still being distributed, or who is stopped on
    * it gets a [[GalleryService.RoundState]], which the gallery and the large view show as a 200
    * page (a state, not a denial); anyone else a [[GalleryService.Refusal.Denied]], a 403 as
    * before. Nobody is refused fewer or more rounds than before.
    */
  def refusal(
      user: User,
      maybeRound: Option[Round],
      roundContest: Long,
      rounds: Seq[Round]
  ): Option[GalleryService.Refusal] = {
    import GalleryService.{Refusal, RoundState}
    val userContest = user.currentContest.getOrElse(0L)
    val isOrganizer = user.roles.intersect(Set("admin", "organizer", "root")).nonEmpty
    maybeRound match {
      case None => Some(Refusal.Denied)
      // no role that sees galleries: e.g. only the board, auditors or staff (User.NON_ACCESS_ROLES),
      // whose membership is inactive anyway, so they are never in the round's contest
      case Some(_) if !user.hasAnyRole(User.ADMIN_ROLES ++ User.JURY_ROLES ++ User.ORG_COM_ROLES) => Some(Refusal.Denied)
      case Some(_) if !user.hasRole("root") && userContest != roundContest => Some(Refusal.Denied)
      case Some(_) if isOrganizer => None
      // anyone else (a juror): only a round of `rounds`, their own active rounds (viewerRounds),
      // and never one being created: it is hidden from its jurors until its distribution is complete
      case Some(round) if !round.distributing && rounds.exists(_.id == round.id) => None
      case Some(round) =>
        // only the round's own jurors learn its state: to anyone else it stays a denial
        if (!User.isRoundJuror(round.getId, user.getId)) Some(Refusal.Denied)
        else GalleryService.jurorRoundState(round, db.scalikejdbc.RoundUser.isStopped(round.getId, user.getId))
          .orElse(Some(Refusal.Denied))
    }
  }

  /** The state of the round for its juror who may not view it now ([[refusal]]): None if they
    * may view it, or are refused it as before (a 403).
    */
  def roundState(user: User, round: Round): Option[GalleryService.RoundState] =
    refusal(user, Some(round), round.contestId, viewerRounds(user, round)).collect {
      case s: GalleryService.RoundState => s
    }

  /** Whether `user`, who may view the round ([[mayViewRound]]) as its juror, gets the "paused"
    * page instead of its files: a job moves its files now ([[Round.pausedCond]]). Those who may see
    * its organiser info view it as a stopped round, its files as they are.
    */
  def pausedFor(user: User, round: Round): Boolean =
    round.mayBePaused && !user.canViewOrgInfo(round) && Round.isPaused(round.getId)

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

  /** Whether [[GalleryService.refusal]] may give `user` a [[RoundState]] of a round of their
    * current contest: a role that sees galleries, and not one that sees every round.
    */
  private def mayGetRoundState(user: User): Boolean =
    user.hasAnyRole(User.ADMIN_ROLES ++ User.JURY_ROLES ++ User.ORG_COM_ROLES) &&
      user.roles.intersect(Set("admin", "organizer", "root")).isEmpty

  /** The state of a round for its juror ([[GalleryService.refusal]] checked that) to whom it is
    * not one of their open rounds; None: the round tells no state (open to them). `jurorStopped`:
    * whether their round_user row is inactive, read only for an open (or paused) round.
    */
  private[services] def jurorRoundState(round: Round, jurorStopped: => Boolean): Option[RoundState] = {
    if (round.distributing) Some(RoundState.Distributing)
    // a round paused while a job moves its files is open to its jurors (Round.pausedCond); one
    // frozen while open by another job (the previous round of a distribution) opens again
    // when the job ends; otherwise inactive is stopped
    else if (!round.active && !(round.mayBePaused && Round.isPaused(round.getId)))
      Some(
        if (round.mayBePaused && Round.isFrozenOpen(round.getId))
          // a creation that holds it closes it at its end; anything else gives it back open
          if (services.distribution.RoundFreezes.closedByHolder(round.getId)) RoundState.Closing
          else RoundState.Preparing
        else RoundState.Stopped)
    else if (jurorStopped) Some(RoundState.JurorStopped)
    else None
  }

  /** The user's rounds of their current contest that are not open to them, each with the state
    * [[GalleryService.roundState]] gives it: their own rounds only (a round_user row of theirs),
    * never another juror's. One query ([[Round.jurorRoundsNotOpen]]); a round frozen by a job
    * (rare) reads its freeze as roundState does. None for a user who sees every round (an
    * organizer). The start page's "no round" page lists them (controllers.JurorContests).
    */
  def closedRounds(user: User): Seq[(Round, RoundState)] =
    if (!mayGetRoundState(user)) Nil
    else
      Round.jurorRoundsNotOpen(user).flatMap { case (round, jurorActive) =>
        jurorRoundState(round, !jurorActive).map(round -> _)
      }

  /** [[GalleryService.viewAs]]'s answer. */
  sealed trait ViewAs

  object ViewAs {
    case object Allowed extends ViewAs

    /** Another user's votes, or all jurors' together, for a user who may see only their own. */
    case object OtherUsersVotes extends ViewAs

    /** An organiser's view as a user who is not a juror of the round. */
    case object NotRoundJuror extends ViewAs
  }

  /** Why a user may not view a round's gallery or large view ([[GalleryService.refusal]]). */
  sealed trait Refusal

  object Refusal {

    /** Not a viewer of the round: another contest, no role, an inactive membership, a round the
      * juror is not a juror of: a 403 with its `security.authz` line, as before.
      */
    case object Denied extends Refusal
  }

  /** A juror's round not open to them now: a state, shown as a 200 page with the round's state
    * (DG7) and logged once at INFO on `round.access` ([[GalleryService.accessRefusedLine]]).
    * `reason`: the line's.
    */
  sealed abstract class RoundState(val reason: String) extends Refusal

  object RoundState {

    /** The round is stopped (not active, and not paused while a job moves its files). */
    case object Stopped extends RoundState("round_stopped")

    /** The round is open, but frozen for a while by a job that reads it (the previous round of a
      * distribution, e.g. "Distribute new files", `freeze_count > 0` and `active_before_freeze`),
      * without the rebalance job that pauses it ([[Round.pausedCond]]): the job gives it back as
      * it was when it ends, unless the round is stopped meanwhile.
      */
    case object Preparing extends RoundState("round_preparing")

    /** The round is frozen as the previous round of a round being created: that creation closes
      * it at its end ([[services.distribution.RoundFreezes.closedByHolder]]).
      */
    case object Closing extends RoundState("round_closing")

    /** The round is still being created: its distribution has not finished. */
    case object Distributing extends RoundState("round_distributing")

    /** The round is open, but the juror is stopped on it (`round_user.active` false). */
    case object JurorStopped extends RoundState("juror_stopped")

    val All: Seq[RoundState] = Seq(Stopped, Preparing, Closing, Distributing, JurorStopped)

    /** The states that pass by themselves shortly: the page offers to reload. */
    val Transient: Set[RoundState] = Set(Preparing)
  }

  /** The logger of a juror's refused gallery or large view (production: INFO). */
  val accessLogger: play.api.Logger = play.api.Logger("round.access")

  /** The `round.access` line of a juror refused a round's gallery or large view for its state:
    * ids only, read by the support tools (H17).
    */
  def accessRefusedLine(state: RoundState, round: Round, user: User): String =
    s"round_access_refused reason=${state.reason} roundId=${round.getId} accountId=${user.getId} " +
      s"contestId=${round.contestId}"

  /** The denial reason of [[ViewAs.OtherUsersVotes]] ([[controllers.Secured.Denial]]). */
  val OtherUsersVotesReason = "other_users_votes"

  /** Whether `user` may see other jurors' votes in `round` (one juror's, all together, their
    * ratings, rankings and per-juror counts of them): those who may see the round's organiser info
    * ([[User.canViewOrgInfo]]), unless they are a juror of the round themselves while it is open
    * (the maintainer's conflict-of-interest rule of 2026-11-24, open rounds only by the decision of
    * the same day): a juror of an open round sees only their own votes in it, whatever their
    * contest role (an admin or organizer, a series admin's derived role, root); once the round is
    * closed they see its results as any admin. Open: [[Round.openCond]], active, being created or
    * reviewed (distributing), or paused by a freeze while open; closed: anything else (stopped,
    * finalized, cancelled). A juror of the round: [[User.isRoundJuror]], a round_user row, active
    * or stopped, or votes of their own in it. The one check of every place that shows other
    * jurors' votes (a guard spec lists them).
    */
  def othersVotesVisible(user: User, round: Round): Boolean =
    user.canViewOrgInfo(round) && ownVotesOnlyRounds(user, Seq(round)).isEmpty

  /** Of `rounds`, those where `user` sees only their own votes ([[othersVotesVisible]]): the open
    * rounds they are a juror of. In two queries at most, for a menu of rounds (the galleries' round
    * menu opens these on the viewer's own votes).
    */
  def ownVotesOnlyRounds(user: User, rounds: Seq[Round]): Set[Long] =
    user.id.fold(Set.empty[Long]) { id =>
      val open = Round.openIds(rounds.flatMap(_.id))
      if (open.isEmpty) Set.empty else User.roundsJuredIn(id, open.toSeq)
    }
}
