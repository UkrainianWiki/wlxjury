package graphql

import api.ApiAuth
import db.scalikejdbc.{ContestJuryJdbc, Round, RoundUser, User}
import org.intracer.wmua.ContestJury
import sangria.schema._
import services.GalleryService

/** The GraphQL schema (plan §6, slice 1): the caller (`me`), their contests and one contest with
  * its rounds. Its SDL is checked in as `conf/graphql/schema.graphql`, the UI's contract
  * (`graphql.GraphQLSchemaSpec` fails when the two differ). Who sees what:
  *
  *  - `contests`: root, every contest (retired ones too); anyone else, the contests where they have
  *    an access role ([[User.ACCESS_ROLES]]) in an active membership of a contest not retired, and
  *    (a session's) the contests their contest series grants reach, with the derived role, as on the
  *    web UI ([[GraphQLContext.asMemberOf]]: contest series plan §3.3, CA7, CA20);
  *  - `contest(id)`: one of those; any other, or a missing one, is null with `NOT_FOUND` (a denial
  *    logged on `security.authz` with the error's reference);
  *  - a contest's `rounds`: all of them for those who may see its organiser views (root, its admins
  *    and organizers, [[User.canViewOrgInfo]]); a juror, the rounds they are a juror of
  *    ([[User.roundsJuredIn]]) that are not being created or reviewed (`distributing`), also one
  *    where their own row is stopped (`myStatus` says so);
  *  - a round's `othersVotesVisible`: the galleries' rule ([[GalleryService.othersVotesVisibleIn]]),
  *    the conflict-of-interest rule included (a juror of an open round sees only their own votes);
  *  - `me.email`: the caller's own, always; another account's email (a later slice's `users`) only for
  *    root and the Admins of a contest the account is a member of ([[emailVisible]]).
  *
  * No vote, count or juror data in this slice.
  */
object GraphQLSchema {

  /** A contest as the caller sees it: `viewer` is the caller in that contest ([[GraphQLContext.asMemberOf]]). */
  final case class ContestView(contest: ContestJury, viewer: User)

  /** What a contest's rounds share, read once for all of them and only if a field asks for it: the
    * rounds the viewer is a juror of, those where they are stopped, and those where they see other
    * jurors' votes.
    */
  final class RoundsOf(ctx: GraphQLContext, viewer: User, rounds: Seq[Round]) {
    private def ids = rounds.flatMap(_.id)

    /** The rounds the viewer is a juror of ([[User.roundsJuredIn]]: votes in it, or a round_user row). */
    lazy val juredIn: Set[Long] = { ctx.checkDeadline(); User.roundsJuredIn(viewer.getId, ids) }

    /** Of [[juredIn]], those where the viewer's round_user row is stopped ([[RoundUser.stoppedIn]]). */
    lazy val stopped: Set[Long] = { ctx.checkDeadline(); RoundUser.stoppedIn(viewer.getId, juredIn.toSeq) }

    /** The rounds where the viewer sees other jurors' votes ([[GalleryService.othersVotesVisibleIn]]). */
    lazy val othersVisible: Set[Long] = { ctx.checkDeadline(); GalleryService.othersVotesVisibleIn(viewer, rounds) }
  }

  /** A round as the viewer sees it, with what its contest's rounds share ([[RoundsOf]]). */
  final case class RoundView(round: Round, of: RoundsOf)

  /** The viewer's status as a juror of a round (`myStatus`). */
  object JurorStatus {
    val Active = "active"
    val Stopped = "stopped"
  }

  /** A page of `contests`. */
  final case class ContestPage(totalCount: Int, offset: Int, limit: Int, items: Seq[ContestView])

  /** The largest page `contests` gives; a larger `limit` is clamped to it. */
  val MaxPageSize = 200

  /** How many rounds a contest's `rounds` counts as in the complexity budget (a contest has a few). */
  val RoundsEstimate = 20

  /** `limit` clamped to 1..[[MaxPageSize]]. */
  def pageSize(limit: Int): Int = math.max(1, math.min(limit, MaxPageSize))

  /** Whether `viewer` may see another account's (`target`'s) email (decision Q6), for a later
    * slice's `users`: root, or an Admin, in an active membership of a contest that is not retired,
    * of a contest `target` is a member of (`targetContests`, any role). Real memberships only: a
    * contest series grant's derived Admin role does not count here. The caller's own email
    * (`me.email`) is always shown (the maintainer's decision of 2026-11-24).
    */
  def emailVisible(viewer: User, targetContests: Seq[Long]): Boolean =
    viewer.hasRole(User.ROOT_ROLE) || {
      val administered = viewer.userContests.filter(m => m.opens && m.roles.contains(User.ADMIN_ROLE)).map(_.contestId).toSet
      targetContests.exists(administered)
    }

  /** The rounds of the contest `view` shows, as its viewer may see them (see the object's doc). */
  def visibleRounds(ctx: GraphQLContext, view: ContestView): Seq[RoundView] = {
    ctx.checkDeadline()
    val rounds = Round.findByContest(view.contest.getId).filter(_.deletedAt.isEmpty)
    val viewer = view.viewer
    val (staff, others) = rounds.partition(viewer.canViewOrgInfo)
    val juror =
      if (others.isEmpty || !viewer.hasAnyRole(User.VoterRoles)) Nil
      else {
        val open = others.filterNot(_.distributing)
        ctx.checkDeadline()
        val mine = User.roundsJuredIn(viewer.getId, open.flatMap(_.id))
        open.filter(r => mine.contains(r.getId))
      }
    val visible = (staff ++ juror).sortBy(_.getId)
    val of = new RoundsOf(ctx, viewer, visible)
    visible.map(RoundView(_, of))
  }

  /** The contests the caller may see, in the order the list gives them: the latest year first. */
  def visibleContests(ctx: GraphQLContext): Seq[ContestView] = {
    ctx.checkDeadline()
    val contests =
      if (ctx.callerIsRoot) ContestJuryJdbc.findAll()
      else {
        val real = ctx.user.userContests.filter(m => m.opens && m.roles.exists(User.ACCESS_ROLES)).map(_.contestId)
        controllers.Secured.contestsByIds((real ++ ctx.seriesContestIds).distinct).filterNot(_.retired)
      }
    contests
      .flatMap(c => ctx.asMemberOf(c.getId).map(ContestView(c, _)))
      .sortBy(v => (-v.contest.year, v.contest.name, v.contest.country, v.contest.getId))
  }

  /** One contest, if the caller may see it; else [[GraphQLError]] `NOT_FOUND`, the same for a
    * missing contest, an id that is no number, and one the caller may not see (logged as a denial).
    */
  def contestById(ctx: GraphQLContext, id: String): ContestView = {
    ctx.checkDeadline()
    id.toLongOption.flatMap(ContestJuryJdbc.findById) match {
      case None => throw GraphQLError.missing("contest")
      case Some(contest) =>
        ctx.asMemberOf(contest.getId)
          .filter(_ => ctx.callerIsRoot || !contest.retired)
          .map(ContestView(contest, _))
          .getOrElse(throw ctx.contestDenied(contest))
    }
  }

  // --- types

  val AuthMethodType: EnumType[String] = EnumType(
    "AuthMethod",
    Some("How the caller signed in to the API."),
    List(
      EnumValue("SESSION", Some("The browser's session (a page of the tool)."), "session"),
      EnumValue("TOKEN", Some("A personal API token: queries only."), "token")
    )
  )

  val ContestRoleType: EnumType[String] = EnumType(
    "ContestRole",
    Some("A role in a contest that gives access to it."),
    List(
      EnumValue("ADMIN", value = User.ADMIN_ROLE),
      EnumValue("ORGANIZER", value = "organizer"),
      EnumValue("JURY", value = User.JURY_ROLE)
    )
  )

  val RoundStatusType: EnumType[String] = EnumType(
    "RoundStatus",
    Some("A round's lifecycle state."),
    List(
      EnumValue("DRAFT", Some("Being created: its files are being distributed."), Round.Status.Draft),
      EnumValue("PAUSED", Some("Not open to its jurors: stopped, finished, or filled and in review."), Round.Status.Paused),
      EnumValue("ACTIVE", Some("Open to its jurors."), Round.Status.Active),
      EnumValue("FINALIZED", Some("Finalized."), Round.Status.Finalized),
      EnumValue("CANCELLED", Some("Its creation was cancelled."), Round.Status.Cancelled)
    )
  )

  val RoundKindType: EnumType[String] = EnumType(
    "RoundKind",
    Some("How jurors vote in a round."),
    List(
      EnumValue("BINARY", Some("Yes or no."), "binary"),
      EnumValue("RATED", Some("Stars, 1 to maxStars."), "rated"),
      EnumValue("COMMENTS_ONLY", Some("No votes, comments only."), "comments")
    )
  )

  private def kindOf(round: Round): String =
    if (round.isBinary) "binary" else if (round.isRated) "rated" else "comments"

  private def statusOf(round: Round): String =
    Some(round.lifecycle).filter(Round.Status.All.contains)
      .getOrElse(Round.Status.ofFlags(distributing = round.distributing, open = round.active))

  val JurorStatusType: EnumType[String] = EnumType(
    "JurorStatus",
    Some("The caller's status as a juror of a round."),
    List(
      EnumValue("ACTIVE", Some("A juror of the round, not stopped."), JurorStatus.Active),
      EnumValue("STOPPED", Some("Stopped on the round by its admins: no new files, no votes; its gallery says so."),
        JurorStatus.Stopped)
    )
  )

  val RoundType: ObjectType[GraphQLContext, RoundView] = ObjectType(
    "Round",
    "A judging round of a contest.",
    fields[GraphQLContext, RoundView](
      Field("id", IDType, resolve = _.value.round.getId.toString),
      Field("number", IntType, Some("Its number in the contest."), resolve = _.value.round.number.toInt),
      Field("name", OptionType(StringType), Some("Its name, if it has one."),
        resolve = _.value.round.name.filter(_.trim.nonEmpty)),
      Field("title", StringType, Some("Its name, or its number if it has none."), resolve = _.value.round.description),
      Field("status", RoundStatusType, resolve = c => statusOf(c.value.round)),
      Field("inReview", BooleanType, Some("Filled and waiting for its admins' review before its jurors see it."),
        resolve = _.value.round.inReview),
      Field("kind", RoundKindType, resolve = c => kindOf(c.value.round)),
      Field("maxStars", OptionType(IntType),
        Some("A rated round's highest number of stars a juror can give (a half-star 1-7 round draws 3); null otherwise."),
        resolve = c => Option.when(c.value.round.isRated)(c.value.round.maxRate / c.value.round.starUnits)),
      Field("halfStars", BooleanType, Some("Whether a rated round takes half stars."),
        resolve = c => c.value.round.isRated && c.value.round.starUnits == 2),
      Field("hasCriteria", BooleanType, Some("Whether a rated round is voted on per criterion."),
        resolve = c => c.value.round.isRated && c.value.round.hasCriteria),
      Field("myStatus", OptionType(JurorStatusType),
        Some("The caller's status as a juror of this round: STOPPED when stopped on it (the round is still " +
          "listed); null when the caller is not one of its jurors."),
        resolve = c => {
          val id = c.value.round.getId
          Option.when(c.value.of.juredIn(id))(if (c.value.of.stopped(id)) JurorStatus.Stopped else JurorStatus.Active)
        }),
      Field("othersVotesVisible", BooleanType,
        Some("Whether the caller may see other jurors' votes in this round, as in its galleries: root and the " +
          "contest's admins and organizers, except while the round is open if they are one of its jurors " +
          "(conflict of interest: then only their own votes); never a juror."),
        resolve = c => c.value.of.othersVisible(c.value.round.getId))
    )
  )

  val ContestType: ObjectType[GraphQLContext, ContestView] = ObjectType(
    "Contest",
    "A photo contest.",
    fields[GraphQLContext, ContestView](
      Field("id", IDType, resolve = _.value.contest.getId.toString),
      Field("name", StringType, resolve = _.value.contest.name),
      Field("year", IntType, resolve = _.value.contest.year),
      Field("country", StringType, resolve = _.value.contest.country),
      Field("retired", BooleanType, Some("Retired: its members have no access (only root sees such a contest)."),
        resolve = _.value.contest.retired),
      Field("viewerRoles", ListType(ContestRoleType),
        Some("The caller's roles in this contest (none for root without a membership)."),
        resolve = c => (c.value.viewer.contestRoles & User.ACCESS_ROLES).toSeq.sorted),
      Field("rounds", ListType(RoundType),
        Some("Its rounds the caller may see: all for root and its admins and organizers; for a juror, " +
          "the rounds they judge, once distributed."),
        complexity = Some((_: GraphQLContext, _: Args, child: Double) => 1 + child * RoundsEstimate),
        resolve = c => visibleRounds(c.ctx, c.value))
    )
  )

  val ContestPageType: ObjectType[GraphQLContext, ContestPage] = ObjectType(
    "ContestPage",
    "A page of the caller's contests.",
    fields[GraphQLContext, ContestPage](
      Field("totalCount", IntType, Some("How many contests the caller may see."), resolve = _.value.totalCount),
      Field("offset", IntType, resolve = _.value.offset),
      Field("limit", IntType, Some("The page size applied (the limit asked, clamped to 1..200)."), resolve = _.value.limit),
      Field("items", ListType(ContestType), resolve = _.value.items)
    )
  )

  val MeType: ObjectType[GraphQLContext, ApiAuth.Caller] = ObjectType(
    "Me",
    "The caller.",
    fields[GraphQLContext, ApiAuth.Caller](
      Field("id", IDType, resolve = _.value.user.getId.toString),
      Field("fullname", StringType, resolve = _.value.user.fullname),
      Field("email", OptionType(StringType), Some("The caller's own email (null if the account has none)."),
        resolve = c => Option(c.value.user.email).filter(_.trim.nonEmpty)),
      Field("isRoot", BooleanType, resolve = _.value.user.hasRole(User.ROOT_ROLE)),
      Field("currentContestId", OptionType(IDType),
        Some("The session's current contest; for a token, the account's latest contest."),
        resolve = _.value.user.contestId.map(_.toString)),
      Field("authMethod", AuthMethodType, resolve = _.value.via match {
        case ApiAuth.BySession => "session"
        case _: ApiAuth.ByToken => "token"
      })
    )
  )

  val OffsetArg: Argument[Int] = Argument("offset", OptionInputType(IntType), "How many contests to skip.", 0)
  val LimitArg: Argument[Int] = Argument("limit", OptionInputType(IntType), "The page size, at most 200.", 100)
  val IdArg: Argument[String] = Argument("id", IDType)

  val QueryType: ObjectType[GraphQLContext, Unit] = ObjectType(
    "Query",
    fields[GraphQLContext, Unit](
      Field("me", MeType, Some("The caller."), resolve = _.ctx.caller),
      Field("contests", ContestPageType,
        Some("The contests the caller may see, the latest year first: root's all, anyone else's those " +
          "they have a role in."),
        arguments = OffsetArg :: LimitArg :: Nil,
        complexity = Some((_: GraphQLContext, args: Args, child: Double) => 1 + child * pageSize(args.arg(LimitArg))),
        resolve = c => {
          val all = visibleContests(c.ctx)
          val offset = math.max(0, c.arg(OffsetArg))
          val limit = pageSize(c.arg(LimitArg))
          ContestPage(all.size, offset, limit, all.slice(offset, offset + limit))
        }),
      Field("contest", OptionType(ContestType),
        Some("One contest the caller may see; null with a NOT_FOUND error for any other."),
        arguments = IdArg :: Nil,
        resolve = c => contestById(c.ctx, c.arg(IdArg)))
    )
  )

  /** Queries only: no mutation and no subscription type yet (plan §6, slices 4 and 6). */
  val schema: Schema[GraphQLContext, Unit] = Schema(QueryType)

  /** The SDL of [[schema]], as `conf/graphql/schema.graphql` holds it. */
  def sdl: String = schema.renderPretty.trim + "\n"

  /** The checked-in SDL's path, from the project's root. */
  val SdlPath = "conf/graphql/schema.graphql"
}
