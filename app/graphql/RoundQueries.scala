package graphql

import controllers.{RoundStat, Secured}
import db.scalikejdbc.{ContestJuryJdbc, Round, RoundUser, User}
import org.intracer.wmua.{CommentJdbc, Vote}
import services.{GalleryService, RoundService}

/** The round queries (plan §6, slice 2): `round(id)` and `roundStat(roundId)`. Each loads the round
  * first, then judges the caller in the round's contest ([[GraphQLContext.asMemberOf]]: an active
  * membership, a session's series grant counting, root in any contest); a contest the caller may not
  * see answers as a missing round, its denial logged on `security.authz`.
  */
object RoundQueries {

  /** A round and the caller judged in its contest. */
  final case class RoundAccess(round: Round, viewer: User)

  /** The round `id` and the caller in its contest, if they may see the contest: else `NOT_FOUND`,
    * the same for a missing round (a deleted one too), an id that is no number, and a round of a
    * contest the caller is not in (logged as a denial), or of a retired one (root sees those).
    */
  def roundIn(ctx: GraphQLContext, id: String): RoundAccess = {
    ctx.checkDeadline()
    id.toLongOption.flatMap(Round.findById(_)).filter(_.deletedAt.isEmpty) match {
      case None => throw GraphQLError.missing("round")
      case Some(round) =>
        ContestJuryJdbc.findById(round.contestId)
          .filter(contest => ctx.callerIsRoot || !contest.retired)
          .flatMap(contest => ctx.asMemberOf(contest.getId))
          .map(RoundAccess(round, _))
          .getOrElse(throw ctx.denied(Secured.Denial.round(round, User.ACCESS_ROLES)))
    }
  }

  /** `round(id)`: the round if the caller sees it in its contest's `rounds` ([[GraphQLSchema.roundViews]]:
    * all of them for root and the contest's admins and organizers; a juror the rounds they judge, once
    * distributed); else `NOT_FOUND` (logged as a denial).
    */
  def roundById(ctx: GraphQLContext, id: String): GraphQLSchema.RoundView = {
    val RoundAccess(round, viewer) = roundIn(ctx, id)
    GraphQLSchema.roundViews(ctx, viewer, Seq(round)).headOption
      .getOrElse(throw ctx.denied(Secured.Denial.round(round, User.ACCESS_ROLES)))
  }

  /** One juror's row of a round's statistics: their files and votes by rate, as the statistics page's
    * table has them (`roundStatTable.scala.html`). `othersVisible`: whether the viewer sees other
    * jurors' votes in the round ([[GalleryService.othersVotesVisible]]); without it, another juror's
    * selected and rejected counts are hidden, their progress (files, rated, unrated) shown.
    */
  final case class JurorStatView(juror: User, files: Int, rates: Map[Int, Int], round: Round, viewer: User,
      othersVisible: Boolean, emailVisible: Boolean) {

    /** The viewer sees this juror's votes: their own row, or anyone's with [[othersVisible]]. */
    def votesVisible: Boolean = othersVisible || viewer.id == juror.id

    private def count(p: Int => Boolean): Int = rates.collect { case (rate, n) if p(rate) => n }.sum

    def unrated: Int = count(Vote.isUnrated)

    /** A binary round's yes votes, if visible; null for a rated round. */
    def selected: Option[Int] = Option.when(round.isBinary && votesVisible)(count(_ == Vote.Selected))

    /** A binary round's no votes, if visible; null for a rated round. */
    def rejected: Option[Int] = Option.when(round.isBinary && votesVisible)(count(_ == Vote.Rejected))

    /** A rated (or comments-only) round's files with any vote, a ✗ included (the juror's progress). */
    def rated: Option[Int] = Option.when(!round.isBinary)(count(Vote.isVoted))

    def stopped: Boolean = !juror.active.getOrElse(true)
  }

  /** `roundStat`: the round, its [[RoundStat]], and what the viewer sees of it. */
  final case class RoundStatView(round: GraphQLSchema.RoundView, stat: RoundStat, viewer: User, othersVisible: Boolean,
      emailVisible: Boolean) {

    def jurors: Seq[JurorStatView] = stat.jurors.map { juror =>
      JurorStatView(juror, stat.byUserCount.getOrElse(juror.getId, 0), stat.byUserRateCount.getOrElse(juror.getId, Map.empty),
        stat.round, viewer, othersVisible, emailVisible)
    }

    /** A binary round's files with at least one vote of `rate`, if the viewer sees others' votes. */
    def totalByRate(rate: Int): Option[Int] =
      Option.when(stat.round.isBinary && othersVisible)(stat.totalByRate.getOrElse(rate, 0))

    /** The comments report's totals for the round's files (one statement, read if asked). */
    lazy val comments: CommentJdbc.RoundCounts = CommentJdbc.roundCounts(stat.round.contestId, stat.round.getId)

    /** Open, but none of its jurors can vote in it (DG10): the statistics page's notice. */
    lazy val noActiveJuror: Boolean = RoundUser.openWithoutActiveJuror(Seq(stat.round)).nonEmpty
  }

  /** `roundStat(roundId)`: the web round statistics page's numbers (`RoundController.roundStat`, the
    * same [[RoundService.roundStat]]) for those it shows them to: root and the contest's admins and
    * organizers ([[User.canViewOrgInfo]]), a series grant's derived role counting. Anyone else in the
    * contest gets `FORBIDDEN` (logged as a denial, as the page's 403); a round the caller may not see
    * the contest of, `NOT_FOUND` ([[roundIn]]). The conflict-of-interest rule as on the page: a viewer
    * who is a juror of the open round sees each juror's progress, but other jurors' selected and
    * rejected counts and the round's files by rate only once it is closed. Emails: decision Q6
    * ([[GraphQLSchema.emailVisible]]), not the page's (which shows them to organizers too).
    */
  def roundStat(ctx: GraphQLContext, roundId: String): RoundStatView = {
    val RoundAccess(round, viewer) = roundIn(ctx, roundId)
    if (!viewer.canViewOrgInfo(round))
      throw ctx.denied(Secured.Denial.round(round, User.ADMIN_ROLES ++ User.ORG_COM_ROLES), GraphQLError.forbidden)
    ctx.checkDeadline()
    val stat = RoundService.roundStat(Round, round.getId, round, withDeletedFiles = true)
    val view = GraphQLSchema.roundViews(ctx, viewer, Seq(round)).head
    RoundStatView(view, stat, viewer, view.of.othersVisible(round.getId),
      GraphQLSchema.emailVisible(ctx.user, Seq(round.contestId)))
  }
}
