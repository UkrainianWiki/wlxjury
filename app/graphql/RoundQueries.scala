package graphql

import controllers.{Global, ListOrder, RoundList, RoundStat, Secured, VoteFilter}
import db.scalikejdbc.rewrite.ImageDbNew.{Keyset, Limit}
import db.scalikejdbc.{ContestJuryJdbc, Round, RoundUser, User}
import org.intracer.wmua.{CommentJdbc, Image, ImageWithRating, Vote}
import play.api.libs.json.{JsArray, JsNull, JsNumber, JsObject, JsString, JsValue, Json}
import services.{GalleryService, RoundService}

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import scala.util.Try

/** The round queries (plan §6, slices 2 and 3): `round(id)`, `roundStat(roundId)`, `roundImages` and
  * `image(roundId, pageId)`. Each loads the round
  * first, then judges the caller in the round's contest ([[GraphQLContext.asMemberOf]]: an active
  * membership, a session's series grant counting, root in any contest); a contest the caller may not
  * see answers as a missing round, its denial logged on `security.authz`.
  */
object RoundQueries {

  /** A round and the caller judged in its contest. */
  final case class RoundAccess(round: Round, viewer: User)

  /** The round `id` and the caller in its contest, if they may see the contest: else `NOT_FOUND`,
    * the same for a missing round (a deleted one too), an id that is no number, and a round of a
    * contest the caller is not in (logged as a denial), or of a retired one (root sees those). Once a
    * request ([[GraphQLContext.memo]]).
    */
  def roundIn(ctx: GraphQLContext, id: String): RoundAccess = ctx.memo(("round", id)) {
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
    * organizers ([[User.canViewOrgInfo]]), a series grant's derived role counting. A round the caller
    * doesn't see ([[roundById]]'s rule: another contest's, or for a juror one they don't judge or one
    * being created) is `NOT_FOUND`, so that its existence isn't told; a round they see but may not see
    * the statistics of (a juror's own round) is `FORBIDDEN`, as the page's 403; both logged. The
    * conflict-of-interest rule as on the page ([[GalleryService.othersVotesVisible]]): a viewer who is a
    * juror of the open round sees each juror's progress, but other jurors' selected and rejected counts
    * and the round's files by vote only once it is closed. Emails: decision Q6
    * ([[GraphQLSchema.emailVisible]]), not the page's (which shows them to organizers too). Once a request.
    */
  def roundStat(ctx: GraphQLContext, roundId: String): RoundStatView = ctx.memo(("roundStat", roundId)) {
    val RoundAccess(round, viewer) = roundIn(ctx, roundId)
    val view = roundById(ctx, roundId)
    if (!viewer.canViewOrgInfo(round))
      throw ctx.denied(Secured.Denial.round(round, User.ADMIN_ROLES ++ User.ORG_COM_ROLES), GraphQLError.forbidden)
    ctx.checkDeadline()
    val stat = RoundService.roundStat(Round, round.getId, round, withDeletedFiles = true)
    RoundStatView(view, stat, viewer, GalleryService.othersVotesVisible(viewer, round),
      GraphQLSchema.emailVisible(ctx.user, Seq(round.contestId)))
  }

  // --- slice 3: a round's files as its galleries list them

  private val gallery = new GalleryService

  /** A round's gallery as the caller may view it: the round, the caller in its contest, and whose
    * list (`asUserId`: the caller's own, or a juror's whose list an organizer views).
    */
  final case class GalleryAccess(round: Round, viewer: User, asUserId: Long)

  /** The gallery checks, in the web gallery's order (`GalleryController.gallery`), on the caller in the
    * round's contest ([[roundIn]]): [[GalleryService.refusal]] with [[GalleryService.viewerRounds]] (what
    * [[GalleryService.mayViewRound]] answers, told apart: a denial is `NOT_FOUND`, logged; a juror's round
    * not open to them now, a state, is `CONFLICT` `round_closed` with its `reason`, logged on
    * `round.access` as the web's 409), [[GalleryService.pausedFor]] (`CONFLICT` `round_closed`, reason
    * `round_paused`: every `CONFLICT` carries a reason), then
    * [[GalleryService.viewAs]] for `userId` (omitted: the caller's own list): another juror's votes, or
    * all jurors' (0), only for those who may see the round's organiser views and are not a juror of the
    * open round (conflict of interest), else `FORBIDDEN`, logged with reason `other_users_votes`; and only
    * of a juror of the round, else `NOT_FOUND` `juror_not_found`. All the jurors' votes together (0) are
    * not listed by this slice: `BAD_INPUT` `all_jurors_not_supported` once allowed. Once a request per
    * round and `userId` ([[GraphQLContext.memo]]).
    */
  def galleryAccess(ctx: GraphQLContext, roundId: String, userId: Option[String]): GalleryAccess =
    ctx.memo(("gallery", roundId, userId)) {
      val RoundAccess(round, viewer) = roundIn(ctx, roundId)
      val asUserId =
        userId.fold(viewer.getId)(id => id.toLongOption.filter(_ >= 0).getOrElse(throw GraphQLError.badInput("bad_user_id")))
      ctx.checkDeadline()
      gallery.refusal(viewer, Some(round), round.contestId, gallery.viewerRounds(viewer, round)) match {
        case Some(GalleryService.Refusal.Denied) =>
          throw ctx.denied(Secured.Denial.round(round, Secured.ViewerRoles))
        case Some(state: GalleryService.RoundState) =>
          GalleryService.accessLogger.info(GalleryService.accessRefusedLine(state, round, viewer))
          throw GraphQLError.roundClosed(state.reason)
        case None => ()
      }
      if (gallery.pausedFor(viewer, round)) throw GraphQLError.roundClosed(RoundPaused)
      gallery.viewAs(viewer, round, asUserId) match {
        case GalleryService.ViewAs.Allowed => ()
        case GalleryService.ViewAs.OtherUsersVotes =>
          throw ctx.denied(Secured.Denial.round(round, User.ADMIN_ROLES ++ User.ORG_COM_ROLES)
            .copy(reason = Some(GalleryService.OtherUsersVotesReason)), GraphQLError.forbidden)
        case GalleryService.ViewAs.NotRoundJuror =>
          throw GraphQLError.missing("juror").copy(message = "juror_not_found")
      }
      if (asUserId == 0) throw GraphQLError.badInput("all_jurors_not_supported")
      GalleryAccess(round, viewer, asUserId)
    }

  /** `round_closed`'s reason while a job moves the round's files (the web's 409 `round_paused`). */
  val RoundPaused = "round_paused"

  /** The thumbnail boxes of the web galleries (`ThumbnailSize`). */
  object Thumbs {
    val Gallery = "gallery"
    val Row = "row"
    val Strip = "strip"
  }

  /** A thumbnail as the web gallery's `<img>` has it: `src`, `srcset` (1.5x and 2x) and its shown size. */
  final case class ThumbnailView(url: String, srcset: String, size: Option[(Int, Int)])

  /** The file's thumbnail in a box of the web galleries, through the image proxy as they load it
    * ([[Global.resizeTo]], [[Global.srcSet]]): the rated gallery's 300 x 250 (`galleryByRate`), the yes/no
    * gallery's 250 tall at any width (`gallery`), the large view's strip's 150 x 120. A size at or over the
    * file's width is the file itself (unless only thumbnails show it).
    */
  def thumbnail(image: Image, size: String): ThumbnailView = size match {
    case Thumbs.Row =>
      val y = Global.gallerySizeY
      ThumbnailView(Global.resizeTo(image, y),
        s"${Global.resizeTo(image, (y * 1.5).toInt)} 1.5x, ${Global.resizeTo(image, y * 2)} 2x",
        Global.largeImageBox(image, Some(image.resizeTo(y))))
    case Thumbs.Strip => box(image, Global.thumbSizeX, Global.thumbSizeY)
    case _            => box(image, Global.gallerySizeX, Global.gallerySizeY)
  }

  private def box(image: Image, x: Int, y: Int): ThumbnailView =
    ThumbnailView(Global.resizeTo(image, x, y), Global.srcSet(image, x, y), Global.thumbBox(image, x, y))

  /** The largest page `roundImages` gives; a larger `first` is clamped to it. */
  val MaxImagePage = 200

  /** `roundImages`' page size when `first` is not given. */
  val DefaultImagePage = 50

  /** `first` clamped to 1..[[MaxImagePage]]. */
  def imagePageSize(first: Int): Int = math.max(1, math.min(first, MaxImagePage))

  /** A file of a round's list, with the vote of the list's juror on it (`vote`, if the row has one). */
  final case class RoundImageView(file: ImageWithRating, round: Round) {
    def image: Image = file.image
    def vote: Option[Vote] = file.votes.headOption
  }

  /** A page of `roundImages`: its files, whether more follow, the cursor after its last file, and the
    * list's total (read only if asked).
    */
  final class ImagePage(val nodes: Seq[RoundImageView], val hasNextPage: Boolean, val endCursor: Option[String],
      total: => Int) {
    lazy val totalCount: Int = total
  }

  /** `roundImages`: a page of the list of the round's files of one juror (the caller's own, or the
    * juror's `userId` names, [[galleryAccess]]), with their vote on each, in `order`, filtered by
    * their votes (`filter`, one of the round's: [[VoteFilter.ofRound]]). Paged by keyset as #129's
    * gallery chunks are (own gallery tiles plan §3.6, "keyset continuation"): `after` is the cursor
    * of a page's last file, its values of the order's columns when that page was read
    * ([[ImageDbNew.SelectionQuery.listKeyed]]) and its page id; the next page is the files from that
    * place on, that file left out (`s.page_id <> after`). The order's columns end in the page id, so no
    * two files share a place: no file twice or skipped across pages, also with equal sort keys; a vote
    * after a page was read moves a file, never the place a cursor continues from (a file moved further
    * on is shown again there: a client drops a page id it shows, as the gallery's script does). The
    * cursor names its round, order, filter and juror: another list's is `BAD_INPUT` `bad_cursor`. Two
    * statements a page (the page with its places, and the count if `totalCount` is asked), after the
    * checks', whatever its size.
    */
  def roundImages(ctx: GraphQLContext, roundId: String, userId: Option[String], filter: VoteFilter, order: ListOrder,
      first: Int, after: Option[String]): ImagePage = {
    val GalleryAccess(round, _, asUserId) = galleryAccess(ctx, roundId, userId)
    if (!VoteFilter.ofRound(round).contains(filter)) throw GraphQLError.badInput("filter_not_in_round")
    val query = RoundList(round, asUserId, filter = filter, order = order).query
    val cursor = after.map(c => ImageCursor.decode(c, query.order, round.getId, order, filter, asUserId)
      .getOrElse(throw GraphQLError.badInput("bad_cursor")))
    val size = imagePageSize(first)
    ctx.checkDeadline()
    // n + 2 rows: the cursor's own file is left out wherever it is among them (the first row if its key
    // is unchanged; further on, or past them, if a vote moved it on: then a later page shows it again),
    // and the one after the page tells whether a page follows
    val rows = query
      .copy(keyset = cursor.map(c => Keyset(c.values, before = false)), limit = Some(Limit(Some(size + 2), Some(0))))
      .listKeyed()
      .filterNot { case (file, _) => cursor.exists(_.pageId == file.pageId) }
      .take(size + 1)
    val page = rows.take(size)
    new ImagePage(
      page.map { case (file, _) => RoundImageView(file, round) },
      hasNextPage = rows.sizeIs > size,
      endCursor = page.lastOption.map { case (file, key) =>
        ImageCursor.encode(round.getId, order, filter, asUserId, key.values, file.pageId)
      },
      total = { ctx.checkDeadline(); query.count() })
  }

  /** `image(roundId, pageId)`: one file of the list `roundImages` gives with the same arguments
    * (whatever its vote: no filter), with the vote of its juror; `NOT_FOUND` if the list hasn't it.
    */
  def image(ctx: GraphQLContext, roundId: String, pageId: String, userId: Option[String]): RoundImageView = {
    val GalleryAccess(round, _, asUserId) = galleryAccess(ctx, roundId, userId)
    val id = pageId.toLongOption.getOrElse(throw GraphQLError.missing("image"))
    ctx.checkDeadline()
    RoundList(round, asUserId).query.copy(pageIds = Some(Seq(id)), limit = Some(Limit(Some(1), Some(0)))).list()
      .headOption.map(RoundImageView(_, round))
      .getOrElse(throw GraphQLError.missing("image"))
  }
}

/** `roundImages`' cursors: base64url JSON of the list they continue (its round, order, filter and juror,
  * checked against the request's, so that a cursor of another list is refused) and the place of a
  * page's last file, its values of the order's columns ([[ListOrder.columns]]), the last its page id.
  * Each value is parsed by its column (a monument id a string or null, any other column a number)
  * and bound as a parameter, never read into SQL text; a tampered value selects another slice of the
  * same list only.
  */
object ImageCursor {

  final case class Place(values: Seq[Option[Any]], pageId: Long)

  /** A cursor longer than this is refused unread (a real one is about 100 characters). */
  val MaxLength = 1024

  private val MonumentColumn = "s.monument_id"

  def encode(roundId: Long, order: ListOrder, filter: VoteFilter, asUserId: Long, values: Seq[Option[Any]],
      pageId: Long): String = {
    val json = Json.obj("r" -> roundId, "o" -> order.param, "f" -> filter.param, "u" -> asUserId, "k" -> values.map(toJson), "p" -> pageId)
    Base64.getUrlEncoder.withoutPadding.encodeToString(Json.stringify(json).getBytes(UTF_8))
  }

  private def toJson(value: Option[Any]): JsValue = value match {
    case None | Some(null)                => JsNull
    case Some(s: String)                  => JsString(s)
    case Some(b: java.lang.Boolean)       => JsNumber(if (b) 1 else 0)
    case Some(n: java.math.BigDecimal)    => JsNumber(BigDecimal(n))
    case Some(n: java.math.BigInteger)    => JsNumber(BigDecimal(n))
    case Some(n: java.lang.Number)        => JsNumber(BigDecimal(n.toString))
    case Some(other)                      => throw new IllegalStateException(s"cursor value of ${other.getClass.getName}")
  }

  /** The place `cursor` names in the list of `columns` (the query's order), if it is a cursor of this
    * list: the same order, filter and juror, a value per column of its type, the last the page id.
    */
  def decode(cursor: String, columns: Seq[(String, Int)], roundId: Long, order: ListOrder, filter: VoteFilter,
      asUserId: Long): Option[Place] =
    if (cursor.length > MaxLength) None
    else
      Try(Json.parse(new String(Base64.getUrlDecoder.decode(cursor), UTF_8))).toOption.collect { case o: JsObject => o }
        .filter(o => (o \ "r").asOpt[Long].contains(roundId) && (o \ "o").asOpt[String].contains(order.param) && (o \ "f").asOpt[String].contains(filter.param) &&
          (o \ "u").asOpt[Long].contains(asUserId))
        .flatMap { o =>
          for {
            pageId <- (o \ "p").asOpt[Long]
            raw    <- (o \ "k").asOpt[JsArray].map(_.value.toSeq)
            if raw.sizeIs == columns.size
            values = columns.map(_._1).zip(raw).map {
              case (MonumentColumn, JsNull)       => Some(None)
              case (MonumentColumn, JsString(s))  => Some(Some(s))
              case (column, JsNumber(n)) if column != MonumentColumn => Some(Some(bound(n)))
              case _                              => None
            }
            if values.forall(_.isDefined)
            place = values.flatten
            if place.lastOption.flatten.contains(java.lang.Long.valueOf(pageId))
          } yield Place(place, pageId)
        }

  /** A number as bound: a whole one as a long, else a decimal (a vote time, to the millisecond). */
  private def bound(n: BigDecimal): Any =
    if (n.isWhole && n.isValidLong) java.lang.Long.valueOf(n.toLong) else n.bigDecimal
}
