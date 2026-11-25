package graphql

import api.ApiAuth
import controllers.{ListOrder, VoteFilter}
import db.scalikejdbc.{ContestJuryJdbc, Round, RoundUser, User}
import org.intracer.wmua.{ContestJury, Vote}
import sangria.schema._
import services.GalleryService

/** The GraphQL schema (plan §6, slices 1 to 3): the caller (`me`), their contests and one contest
  * with its rounds, one round, its statistics and its files. Its SDL is checked in as
  * `conf/graphql/schema.graphql`, the UI's contract (`graphql.GraphQLSchemaSpec` fails when the two
  * differ). Who sees what:
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
  *  - `round(id)`: one round of a contest's `rounds` as the caller sees them; `roundStat(roundId)`: the
  *    round statistics page's numbers, for those it shows them to ([[RoundQueries]], slice 2);
  *  - `roundImages` and `image(roundId, pageId)`: a juror's list of a round's files with their votes,
  *    through the web galleries' checks ([[RoundQueries.galleryAccess]], slice 3).
  */
object GraphQLSchema {

  /** A contest as the caller sees it: `viewer` is the caller in that contest ([[GraphQLContext.asMemberOf]]). */
  final case class ContestView(contest: ContestJury, viewer: User)

  /** What a contest's rounds share, read once for all of them and only if a field asks for it: the
    * rounds the viewer is a juror of, those where they are stopped, and those where they see other
    * jurors' votes. `jurorRounds`: those of `rounds` already known to be the viewer's as a juror
    * ([[visibleRounds]] read them); the rest are read if asked.
    */
  final class RoundsOf(ctx: GraphQLContext, viewer: User, rounds: Seq[Round], jurorRounds: Set[Long]) {

    /** The rounds the viewer is a juror of ([[User.roundsJuredIn]]: votes in it, or a round_user row). */
    lazy val juredIn: Set[Long] = {
      val unknown = rounds.flatMap(_.id).filterNot(jurorRounds)
      if (unknown.isEmpty) jurorRounds
      else { ctx.checkDeadline(); jurorRounds ++ User.roundsJuredIn(viewer.getId, unknown) }
    }

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

  /** What a `roundStat` counts as in the complexity budget: a read of every juror's votes in the round. */
  val RoundStatCost = 200

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
    roundViews(ctx, view.viewer, Round.findByContest(view.contest.getId).filter(_.deletedAt.isEmpty))
  }

  /** Of `rounds` (of one contest, none deleted), those `viewer` (the caller in that contest,
    * [[GraphQLContext.asMemberOf]]) may see, by id: all for those who may see its organiser views
    * ([[User.canViewOrgInfo]]); a juror, the rounds they are a juror of that are not being created or
    * reviewed. A contest's `rounds`, and `round(id)` of one round ([[RoundQueries.roundById]]).
    */
  def roundViews(ctx: GraphQLContext, viewer: User, rounds: Seq[Round]): Seq[RoundView] = {
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
    val of = new RoundsOf(ctx, viewer, visible, juror.flatMap(_.id).toSet)
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
      Field("contestId", IDType, Some("Its contest's id."), resolve = _.value.round.contestId.toString),
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

  val JurorStatType: ObjectType[GraphQLContext, RoundQueries.JurorStatView] = ObjectType(
    "JurorStat",
    "A juror's row of a round's statistics, as the round statistics page has it.",
    fields[GraphQLContext, RoundQueries.JurorStatView](
      Field("id", IDType, Some("The juror's account id."), resolve = _.value.juror.getId.toString),
      Field("fullname", StringType, resolve = _.value.juror.fullname),
      Field("email", OptionType(StringType),
        Some("The juror's email: for root and the contest's Admins only (decision Q6); null for anyone else."),
        resolve = c => Option.when(c.value.emailVisible)(c.value.juror.email).filter(e => e != null && e.trim.nonEmpty)),
      Field("status", JurorStatusType, Some("STOPPED when stopped on the round by its admins."),
        resolve = c => if (c.value.stopped) JurorStatus.Stopped else JurorStatus.Active),
      Field("files", IntType, Some("Their files in the round."), resolve = _.value.files),
      Field("unrated", IntType, Some("Their files without a vote yet."), resolve = _.value.unrated),
      Field("rated", OptionType(IntType),
        Some("A rated round's files they voted on (any vote: stars, or the cross that is the worst mark); null in a yes/no round."), resolve = _.value.rated),
      Field("selected", OptionType(IntType),
        Some("A yes/no round's yes votes; null in a rated round, and for another juror's row while the caller " +
          "sees only their own votes (the round's othersVotesVisible)."), resolve = _.value.selected),
      Field("rejected", OptionType(IntType),
        Some("A yes/no round's no votes; null as for selected."), resolve = _.value.rejected)
    )
  )

  val RoundStatType: ObjectType[GraphQLContext, RoundQueries.RoundStatView] = ObjectType(
    "RoundStat",
    "A round's statistics, as the round statistics page shows them.",
    fields[GraphQLContext, RoundQueries.RoundStatView](
      Field("round", RoundType, resolve = _.value.round),
      Field("totalFiles", IntType, Some("The round's files."), resolve = _.value.stat.total),
      Field("selectedFiles", OptionType(IntType),
        Some("A yes/no round's files with at least one yes vote; null in a rated round and while the caller sees " +
          "only their own votes."), resolve = _.value.totalByRate(org.intracer.wmua.Vote.Selected)),
      Field("unratedFiles", OptionType(IntType),
        Some("A yes/no round's files with at least one vote still to come; null as for selectedFiles."),
        resolve = _.value.totalByRate(org.intracer.wmua.Vote.Unrated)),
      Field("rejectedFiles", OptionType(IntType),
        Some("A yes/no round's files with at least one no vote; null as for selectedFiles."),
        resolve = _.value.totalByRate(org.intracer.wmua.Vote.Rejected)),
      Field("deletedFiles", IntType, Some("The round's files deleted on Commons."), resolve = _.value.stat.deletedFiles),
      Field("deletedFilesCounted", IntType, Some("Of those, the files still in its galleries and results."),
        resolve = _.value.stat.deletedCounted),
      Field("commentsInRound", IntType, Some("Comments written in this round on its files."),
        resolve = c => { c.ctx.checkDeadline(); c.value.comments.roundComments }),
      Field("commentsAllRounds", IntType, Some("All the contest's comments on the round's files."),
        resolve = c => { c.ctx.checkDeadline(); c.value.comments.allComments }),
      Field("noActiveJuror", BooleanType, Some("Open, but none of its jurors can vote in it (all stopped, or none)."),
        resolve = c => { c.ctx.checkDeadline(); c.value.noActiveJuror }),
      Field("jurors", ListType(JurorStatType), Some("Its jurors with votes in it, each with their counts."),
        resolve = _.value.jurors)
    )
  )

  // --- slice 3: a round's files, as its galleries list them

  val ImageFilterType: EnumType[VoteFilter] = EnumType(
    "ImageFilter",
    Some("Which of a round's files a list has, by its juror's votes: the round's tabs (RATED a rated round's, " +
      "SELECTED and REJECTED a yes/no round's; another round's is BAD_INPUT)."),
    List(
      EnumValue("ALL", Some("All the files."), VoteFilter.All),
      EnumValue("UNRATED", Some("The files without a vote yet."), VoteFilter.Unrated),
      EnumValue("RATED", Some("A rated round's files with a vote (stars, or the cross)."), VoteFilter.Rated),
      EnumValue("SELECTED", Some("A yes/no round's files voted yes."), VoteFilter.Rate(Vote.Selected): VoteFilter),
      EnumValue("REJECTED", Some("A yes/no round's files voted no."), VoteFilter.Rate(Vote.Rejected): VoteFilter)
    )
  )

  val ImageOrderType: EnumType[ListOrder] = EnumType(
    "ImageOrder",
    Some("The order of a list's files; files of the same place in it by page id, so that pages are stable."),
    List(
      EnumValue("FILE", Some("By monument id, then page id: a vote never moves a file."), ListOrder.FileOrder: ListOrder),
      EnumValue("RATE", Some("The highest rate first, then in file order."), ListOrder.ByRate: ListOrder),
      EnumValue("LATEST_VOTE", Some("The latest voted first, the unrated last, then in file order."),
        ListOrder.LatestVote: ListOrder),
      EnumValue("OLDEST_VOTE", Some("The oldest voted first, the unrated last, then in file order."),
        ListOrder.OldestVote: ListOrder)
    )
  )

  val ThumbnailSizeType: EnumType[String] = EnumType(
    "ThumbnailSize",
    Some("The boxes the web galleries fit thumbnails into."),
    List(
      EnumValue("GALLERY", Some("300 x 250: a rated round's gallery."), RoundQueries.Thumbs.Gallery),
      EnumValue("ROW", Some("250 tall at any width: a yes/no round's gallery."), RoundQueries.Thumbs.Row),
      EnumValue("STRIP", Some("150 x 120: the large view's strip of files."), RoundQueries.Thumbs.Strip)
    )
  )

  val ThumbnailSizeArg: Argument[Option[String]] = Argument("size", OptionInputType(ThumbnailSizeType),
    "The box; GALLERY if omitted.")

  val ThumbnailType: ObjectType[GraphQLContext, RoundQueries.ThumbnailView] = ObjectType(
    "Thumbnail",
    "A file's thumbnail as the web gallery's img has it: through this server's image proxy (a signed-in session's " +
      "cookie loads it), or the file itself where it is no wider.",
    fields[GraphQLContext, RoundQueries.ThumbnailView](
      Field("url", StringType, Some("The src, at 1x: a path on this server, or the file's URL."), resolve = _.value.url),
      Field("srcset", StringType, Some("The srcset: the 1.5x and 2x sizes."), resolve = _.value.srcset),
      Field("width", OptionType(IntType), Some("The width it is shown at; null without the file's stored size."),
        resolve = _.value.size.map(_._1)),
      Field("height", OptionType(IntType), Some("The height it is shown at; null as for width."),
        resolve = _.value.size.map(_._2))
    )
  )

  val LargeImageType: ObjectType[GraphQLContext, controllers.Global.LargeImage] = ObjectType(
    "LargeImage",
    "A file as the large view loads it: from Wikimedia at its sizes for a Commons file, with this server's " +
      "thumbnail as the fallback.",
    fields[GraphQLContext, controllers.Global.LargeImage](
      Field("src", StringType, resolve = _.value.src),
      Field("srcset", StringType, resolve = _.value.srcset),
      Field("sizes", OptionType(StringType), Some("The sizes attribute, where known."), resolve = _.value.sizes),
      Field("fallback", OptionType(StringType), Some("This server's thumbnail, to load if Wikimedia has not the file."),
        resolve = _.value.fallback),
      Field("width", OptionType(IntType), Some("The width it is shown at, where known."), resolve = _.value.width)
    )
  )

  val VoteType: ObjectType[GraphQLContext, Vote] = ObjectType(
    "Vote",
    "A juror's vote on a file in a round.",
    fields[GraphQLContext, Vote](
      Field("rate", IntType,
        Some("A yes/no round: 1 yes, -1 no, 0 not yet. A rated round: the stars in stored units (a half-star " +
          "round's are halves: halfStars), -1 the cross (the worst mark), 0 not yet. A comments-only round: 0."),
        resolve = _.value.rate),
      Field("voted", BooleanType, Some("Whether it is a vote (rate not 0)."), resolve = _.value.isVoted),
      Field("votedAt", OptionType(StringType),
        Some("When the rate was last changed (ISO-8601, UTC); null for one not voted yet, or voted before times were kept."),
        resolve = _.value.votedAt.map(_.toInstant.toString))
    )
  )

  val RoundImageType: ObjectType[GraphQLContext, RoundQueries.RoundImageView] = ObjectType(
    "RoundImage",
    "A file of a round's list, with the vote of the list's juror on it.",
    fields[GraphQLContext, RoundQueries.RoundImageView](
      Field("pageId", IDType, Some("The file's Commons page id."), resolve = _.value.image.pageId.toString),
      Field("title", StringType, Some("The file's title on Commons (File:...)."), resolve = _.value.image.title),
      Field("width", IntType, Some("Its stored width; 0 if not known."), resolve = _.value.image.width),
      Field("height", IntType, Some("Its stored height; 0 if not known."), resolve = _.value.image.height),
      Field("monumentId", OptionType(StringType), resolve = _.value.image.monumentId.filter(_.trim.nonEmpty)),
      Field("isVideo", BooleanType, resolve = _.value.image.isVideo),
      Field("thumbnail", ThumbnailType, Some("Its thumbnail in one of the web galleries' boxes."),
        arguments = ThumbnailSizeArg :: Nil,
        resolve = c => RoundQueries.thumbnail(c.value.image, c.arg(ThumbnailSizeArg).getOrElse(RoundQueries.Thumbs.Gallery))),
      Field("largeImage", LargeImageType, Some("The file as the large view loads it."),
        resolve = c => controllers.Global.largeImage(c.value.image)),
      Field("vote", OptionType(VoteType),
        Some("The vote of the list's juror on it: the caller's own, or the juror's whose list an organizer views."),
        resolve = _.value.vote)
    )
  )

  val PageInfoType: ObjectType[GraphQLContext, RoundQueries.ImagePage] = ObjectType(
    "PageInfo",
    "Where a page of a list ends.",
    fields[GraphQLContext, RoundQueries.ImagePage](
      Field("endCursor", OptionType(StringType),
        Some("The cursor after the page's last file, for the next page's after; null for an empty page."),
        resolve = _.value.endCursor),
      Field("hasNextPage", BooleanType, resolve = _.value.hasNextPage)
    )
  )

  val RoundImageConnectionType: ObjectType[GraphQLContext, RoundQueries.ImagePage] = ObjectType(
    "RoundImageConnection",
    "A page of a juror's list of a round's files.",
    fields[GraphQLContext, RoundQueries.ImagePage](
      Field("nodes", ListType(RoundImageType), resolve = _.value.nodes),
      Field("pageInfo", PageInfoType, resolve = _.value),
      Field("totalCount", IntType, Some("The list's files, all pages (one count, read only if asked)."),
        resolve = _.value.totalCount)
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
  val RoundIdArg: Argument[String] = Argument("roundId", IDType)
  val PageIdArg: Argument[String] = Argument("pageId", IDType)
  val UserIdArg: Argument[Option[String]] = Argument("userId", OptionInputType(IDType),
    "Whose list: omitted, the caller's own; a juror of the round's, for root and the contest's admins and organizers " +
      "who are not a juror of the open round.")
  val FilterArg: Argument[Option[VoteFilter]] = Argument("filter", OptionInputType(ImageFilterType), "The tab; ALL if omitted.")
  val OrderArg: Argument[Option[ListOrder]] = Argument("order", OptionInputType(ImageOrderType),
    "The order; FILE if omitted.")
  val FirstArg: Argument[Int] = Argument("first", OptionInputType(IntType), "The page size, at most 200.",
    RoundQueries.DefaultImagePage)
  val AfterArg: Argument[Option[String]] = Argument("after", OptionInputType(StringType),
    "The previous page's pageInfo.endCursor; omitted, the first page.")

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
        resolve = c => contestById(c.ctx, c.arg(IdArg))),
      Field("round", OptionType(RoundType),
        Some("One round the caller may see (as in its contest's rounds); null with a NOT_FOUND error for any other."),
        arguments = IdArg :: Nil,
        resolve = c => RoundQueries.roundById(c.ctx, c.arg(IdArg))),
      Field("roundStat", OptionType(RoundStatType),
        Some("A round's statistics, for root and its contest's admins and organizers: null with NOT_FOUND for a " +
          "round the caller doesn't see (as round(id)), FORBIDDEN for one they see but may not see the statistics of."),
        arguments = RoundIdArg :: Nil,
        // each a statistics read (every juror's votes counted): about what a page of files costs
        complexity = Some((_: GraphQLContext, _: Args, child: Double) => RoundStatCost + child),
        resolve = c => RoundQueries.roundStat(c.ctx, c.arg(RoundIdArg))),
      Field("roundImages", OptionType(RoundImageConnectionType),
        Some("A page of a juror's list of a round's files with their votes, as the round's gallery lists them: " +
          "the caller's own list (a juror sees only their own votes), or with userId a juror's, for those who may " +
          "see other jurors' votes there (othersVotesVisible). Keyset pages: pass pageInfo.endCursor as after. " +
          "Null with NOT_FOUND for a round the caller may not view, FORBIDDEN for another juror's list they may " +
          "not see, CONFLICT round_closed with extensions.reason for a juror's round not open to them now."),
        arguments = RoundIdArg :: UserIdArg :: FilterArg :: OrderArg :: FirstArg :: AfterArg :: Nil,
        complexity = Some((_: GraphQLContext, args: Args, child: Double) =>
          1 + child * RoundQueries.imagePageSize(args.arg(FirstArg))),
        resolve = c => RoundQueries.roundImages(c.ctx, c.arg(RoundIdArg), c.arg(UserIdArg),
          c.arg(FilterArg).getOrElse(VoteFilter.All), c.arg(OrderArg).getOrElse(ListOrder.FileOrder), c.arg(FirstArg),
          c.arg(AfterArg))),
      Field("image", OptionType(RoundImageType),
        Some("One file of the list roundImages gives with the same roundId and userId (whatever its vote), with " +
          "the vote; the same errors, and NOT_FOUND for a file the list has not."),
        arguments = RoundIdArg :: PageIdArg :: UserIdArg :: Nil,
        resolve = c => RoundQueries.image(c.ctx, c.arg(RoundIdArg), c.arg(PageIdArg), c.arg(UserIdArg)))
    )
  )

  /** Queries only: no mutation and no subscription type yet (plan §6, slices 4 and 6). */
  val schema: Schema[GraphQLContext, Unit] = Schema(QueryType)

  /** The SDL of [[schema]], as `conf/graphql/schema.graphql` holds it. */
  def sdl: String = schema.renderPretty.trim + "\n"

  /** The checked-in SDL's path, from the project's root. */
  val SdlPath = "conf/graphql/schema.graphql"
}
