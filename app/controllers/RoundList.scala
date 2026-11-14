package controllers

import db.scalikejdbc.{Round, RoundCounts, User, VoteSql}
import db.scalikejdbc.rewrite.{ImageDbNew, RoundImageQuery}
import db.scalikejdbc.rewrite.ImageDbNew.{Limit, SelectionQuery}
import org.intracer.wmua.{BinaryOrder, ImageWithRating, Vote}
import org.intracer.wmua.ImageWithRating.{RankKey, Ranks}
import play.api.mvc.{Cookie, Flash, RequestHeader}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

/** Which of a round's files a list has, by the viewer's votes on them: all, the unrated, the
  * rated (a rated round's: any vote, stars or a ✗; the filter is a rated round's only), or those
  * of one rate (a binary round's selected or rejected, a rated round's stars).
  *
  * @param param its value of a list URL's `filter`
  */
sealed abstract class VoteFilter(val param: String) {

  /** As [[SelectionQuery]] has it. */
  def rate: Option[Int] = None
  def rated: Option[Boolean] = None

  /** Whether a file of this rate is in the list, as the query's `rate` and `rated` have it: rated
    * is a rated round's any vote (a ✗ included), a yes/no round's (`binary`) yes, as
    * `SelectionQuery.grades` reads them.
    */
  def matches(rate: Int, binary: Boolean): Boolean =
    this.rate.forall(_ == rate) && rated.forall(r =>
      if (!r) Vote.isUnrated(rate) else if (binary) Vote.isPositive(rate) else Vote.isVoted(rate))

  /** The filter in a round: itself if a round of its kind has it ([[VoteFilter.ofRound]]),
    * else all files. A binary round's selected (`Rate(1)`) is not a rated round's "rated 1",
    * nor the rated a binary round's selected: each goes by the round's kind, so a list
    * switched to a round of the other kind, or a URL of the other kind's filter, shows one of
    * the round's tabs.
    */
  def in(round: Round): VoteFilter = if (VoteFilter.ofRound(round).contains(this)) this else VoteFilter.All
}

object VoteFilter {

  case object All extends VoteFilter("all")

  case object Unrated extends VoteFilter("unrated") {
    override def rate: Option[Int] = Some(0)
  }

  case object Rated extends VoteFilter("rated") {
    override def rated: Option[Boolean] = Some(true)
  }

  final case class Rate(value: Int)
      extends VoteFilter(value match {
        case 1  => "selected"
        case -1 => "rejected"
        case n  => n.toString
      }) {
    override def rate: Option[Int] = Some(value)
  }

  def ofRate(rate: Int): VoteFilter = if (rate == 0) Unrated else Rate(rate)

  /** A round's filters, as its tabs have them: all files and the unrated, and the selected and
    * rejected (a binary round) or the rated: no link names one rate of a rated round.
    */
  def ofRound(round: Round): Seq[VoteFilter] =
    Seq(All, Unrated) ++ (if (round.isBinary) Seq(Rate(1), Rate(-1)) else Seq(Rated))

  def parse(param: String): Option[VoteFilter] = param match {
    case "all"      => Some(All)
    case "unrated"  => Some(Unrated)
    case "rated"    => Some(Rated)
    case "selected" => Some(Rate(1))
    case "rejected" => Some(Rate(-1))
    case n          => n.toIntOption.map(ofRate)
  }

  /** A request's `filter`, else the `rate` or `rated` of older URLs; all files if none. */
  def of(request: RequestHeader): VoteFilter =
    request.getQueryString("filter").flatMap(parse)
      .orElse(request.getQueryString("rate").flatMap(_.toIntOption).map(ofRate))
      .orElse(request.getQueryString("rated").flatMap(_.toBooleanOption).map(if (_) Rated else Unrated))
      .getOrElse(All)
}

/** The order of a list's files.
  *
  * @param param   its value of a list URL's `sort`
  * @param columns its ORDER BY columns and directions (1 ascending, -1 descending)
  */
sealed abstract class ListOrder(val param: String, val columns: Seq[(String, Int)]) {

  /** Its label in an order menu: `sort.<param>`, a binary order's own. */
  def messageKey: String = s"sort.$param"

  /** By when the juror last voted on the files ([[ListOrder.LatestVote]], [[ListOrder.OldestVote]]):
    * a juror's own list's; all the jurors' votes together are by rate instead.
    */
  def byVoteTime: Boolean = false
}

object ListOrder {

  /** By monument, then page id: a vote never moves a file. */
  case object FileOrder extends ListOrder("file", Seq("s.monument_id" -> 1, "s.page_id" -> 1))

  /** The highest rated first; files of a rate in file order. */
  case object ByRate extends ListOrder("rate", Seq("rate" -> -1, "s.monument_id" -> 1, "s.page_id" -> 1))

  /** A juror's unrated files after the rated (0, then 1), in either order by time. */
  val UnratedLast: String = s"(${VoteSql.unrated().value})"

  /** A vote's time (`vote.voted_at`, V97) as a number, exact to the millisecond and
    * independent of the session's time zone; 0 for a vote without one (rated before V97, or
    * unrated), before any real time: an untimed rated vote is older than every timed one. Never
    * NULL, as the large view's places in the order (`ImageDbNew.Keyset`) need.
    */
  val VoteTime: String = "COALESCE(UNIX_TIMESTAMP(s.voted_at), 0)"

  /** A juror's files by when they last changed their rate, the latest first; the untimed rated
    * after the timed, the unrated last; files of the same time in file order, so pages are stable.
    */
  case object LatestVote
      extends ListOrder("latest-vote", Seq(UnratedLast -> 1, VoteTime -> -1) ++ FileOrder.columns) {
    override def byVoteTime: Boolean = true
  }

  /** As [[LatestVote]], the oldest first: the untimed rated first, the unrated still last. */
  case object OldestVote
      extends ListOrder("oldest-vote", Seq(UnratedLast -> 1, VoteTime -> 1) ++ FileOrder.columns) {
    override def byVoteTime: Boolean = true
  }

  /** A yes/no round's files by all the jurors' votes together, ties by page id: a list of
    * them all ([[RoundList.aggregateBinary]]); by rate in any other list.
    */
  final case class Binary(order: BinaryOrder) extends ListOrder(order.param, order.columns :+ ("s.page_id" -> 1)) {
    override def messageKey: String = order.messageKey
  }

  /** The comments report's files by their latest comment in its scope ([[CommentScope]]), the
    * latest first; then in file order. Its column needs the round's contest and the scope, so a
    * list gives it ([[RoundList.orderColumns]], `SelectionQuery.latestComment`); only the
    * comments report ([[GalleryView.comments]]) has it, another view's list is by rate instead.
    */
  case object LatestComment extends ListOrder("latest-comment", Nil)

  // lazy: LatestVote and OldestVote read UnratedLast and VoteTime, so either initialised first
  // initialises ListOrder, whose eager list would then hold it as null
  lazy val all: Seq[ListOrder] =
    Seq(FileOrder, ByRate, LatestVote, OldestVote, LatestComment) ++ BinaryOrder.all.map(Binary)

  def parse(param: String): Option[ListOrder] = all.find(_.param == param)
}

/** How a list is shown: as a gallery (a binary round's tiles to vote on, a rated round's
  * thumbnails with their stars), by rate, as a file list, as CSV, or as the organisers'
  * comments report (its page and its CSV).
  *
  * @param name its path's first segment (`/gallery/...`), and a large view URL's `module`
  */
sealed abstract class GalleryView(val name: String) {

  /** The comments report ([[GalleryView.Comments]], [[GalleryView.CommentsCsv]]): only the files
    * with comments in its scope ([[CommentScope]]), with them; for those who may view the
    * round's organiser info (`User.canViewOrgInfo`) only.
    */
  def comments: Boolean = false
}

object GalleryView {
  case object Gallery extends GalleryView("gallery")
  case object ByRate extends GalleryView("byrate")
  case object FileList extends GalleryView("filelist")
  case object Csv extends GalleryView("csv")

  /** The comments report: each file with comments, its thumbnail, result and comments. */
  case object Comments extends GalleryView("comments") {
    override def comments: Boolean = true
  }

  /** The comments report's comments as CSV, one row each ([[controllers.Csv.commentRows]]). */
  case object CommentsCsv extends GalleryView("comments-csv") {
    override def comments: Boolean = true
  }

  val all: Seq[GalleryView] = Seq(Gallery, ByRate, FileList, Csv, Comments, CommentsCsv)

  def parse(name: String): Option[GalleryView] = all.find(_.name == name)

  /** The view a large view goes back to: a request's `module`, the gallery if none. Only a
    * gallery, by rate or the comments report lists files one by one.
    */
  def of(request: RequestHeader): GalleryView =
    request.getQueryString("module").flatMap(parse).filter(Set(Gallery, ByRate, Comments)).getOrElse(Gallery)
}

/** Which comments the comments report ([[GalleryView.comments]]) lists its files by and shows:
  * those written in the round ([[CommentScope.ThisRound]], the default), or all the contest's on
  * the file, as its large view shows them ([[CommentScope.AllRounds]]). A file is in the report
  * if it has at least one comment in the scope.
  *
  * @param param its value of a list URL's `scope`
  */
sealed abstract class CommentScope(val param: String)

object CommentScope {
  case object ThisRound extends CommentScope("round")
  case object AllRounds extends CommentScope("contest")

  val all: Seq[CommentScope] = Seq(ThisRound, AllRounds)

  /** A request's `scope`, this round's if none or unknown. */
  def of(request: RequestHeader): CommentScope =
    request.getQueryString(RoundList.ScopeParam).flatMap(p => all.find(_.param == p)).getOrElse(ThisRound)
}

/** The list of a round's files a viewer is in: whose votes (a juror's, or all together with
  * `asUserId` 0), the region, the filter by votes, only the files with comments or all, only one
  * monument's files or all, the order, and the view. It has the list's
  * query and every URL into the list — its pages, a file's place in it, a file's large view
  * and a vote there — so a link keeps all of the list or none of it.
  *
  * Built after the viewer may see it ([[services.GalleryService.mayViewRound]],
  * [[services.GalleryService.viewAs]]): it doesn't decide who may.
  */
final case class RoundList(
    round: Round,
    asUserId: Long,
    region: Option[String] = None,
    filter: VoteFilter = VoteFilter.All,
    order: ListOrder = ListOrder.ByRate,
    view: GalleryView = GalleryView.Gallery,
    // only the files with a comment in the round's contest (the toggle "Only with comments", a
    // URL's `comments=1`): with any filter, as the file's large view shows its comments
    comments: Boolean = false,
    // the comments report's scope ([[CommentScope]]): only in that view ([[GalleryView.comments]])
    commentScope: CommentScope = CommentScope.ThisRound,
    // the comments report's filter by author ([[RoundList.CommenterParam]]): only the files with
    // a comment in the scope by this account (`comment.user_id`); only in that view ([[commentsBy]])
    commenter: Option[Long] = None,
    // only the files of this monument id exactly ([[RoundList.MonumentParam]]), for everyone who
    // may see the list: it only narrows it; a hard condition with the others, the region dropped
    // ([[withMonument]])
    monument: Option[String] = None,
    // `roundImage.read` (P2b): all the jurors' list from round_image's counts ([[countsQuery]])
    countsRead: Boolean = false,
    // which source this request's statements read, shared by the list's copies ([[CountsSession]])
    countsSession: CountsSession = new CountsSession
) {

  def roundId: Long = round.getId

  /** The region of a URL's path: "all" for all of them. */
  def regionParam: String = region.getOrElse("all")

  /** Only the files with comments: the toggle's ([[comments]]), or the comments report's,
    * whose files are those it shows the comments of.
    */
  def commentsOnly: Boolean = comments || view.comments

  /** The round whose comments alone the comments report has ([[CommentScope.ThisRound]]); None
    * for all the contest's, and outside the report, whose toggle is the contest's.
    */
  def commentsRound: Option[Long] = Option.when(view.comments && commentScope == CommentScope.ThisRound)(roundId)

  /** The account whose comments alone the comments report lists its files by ([[commenter]]),
    * counts and orders them by the latest of, and its CSV has; None outside the report.
    */
  def commentsBy: Option[Long] = commenter.filter(_ => view.comments)

  /** Files with their jurors' votes, one row each (file list, CSV). */
  private def withDetails: Boolean = view == GalleryView.FileList || view == GalleryView.Csv

  /** A juror's list whose filter fixes the rate (unrated, a yes/no round's selected or rejected,
    * a single rate): every file in it has the same vote, so by rate is file order (the rate
    * column is one value, then monument and page id) and the order menu leaves it out. The
    * list keeps its order for its links, so the juror's choice holds on the other tabs.
    */
  def oneRate: Boolean = asUserId != 0 && filter.rate.isDefined

  /** The orders a juror's own list offers (the navbar's order menu): file order, by their rating
    * and by when they rated, the latest or the oldest first; on a tab of one rate (a yes/no round's
    * selected or rejected) not by rating, which is file order there ([[oneRate]]); on the unrated
    * tab file order alone: no file in it has a rating or a vote time.
    */
  def orderChoices: Seq[ListOrder] =
    if (filter == VoteFilter.Unrated) Seq(ListOrder.FileOrder)
    else if (oneRate) Seq(ListOrder.FileOrder, ListOrder.LatestVote, ListOrder.OldestVote)
    else Seq(ListOrder.FileOrder, ListOrder.ByRate, ListOrder.LatestVote, ListOrder.OldestVote)

  /** The list's order as its order menu shows it: an order the menu hasn't is the file order it
    * gives there (by rate on a tab of one rate, any order on the unrated tab).
    */
  def shownOrder: ListOrder = if (orderChoices.contains(order)) order else ListOrder.FileOrder

  /** All the jurors' votes on a yes/no round's files together: ordered by a [[BinaryOrder]]. */
  def aggregateBinary: Boolean = asUserId == 0 && round.isBinary

  /** The list's [[BinaryOrder]]: its own, else the default. */
  def binaryOrder: BinaryOrder = order match {
    case ListOrder.Binary(b) => b
    case _                   => BinaryOrder.Default
  }

  /** The order and ranks of a file list or CSV, by rate whatever the list's order. */
  def rankKey: RankKey = RankKey.of(round, binaryOrder)

  /** The ORDER BY of the list's query: a file list or CSV by its [[rankKey]]; a binary order
    * only for all the jurors' votes on a yes/no round, by rate in a juror's list (a file of
    * theirs has one vote).
    */
  private def orderColumns: Seq[(String, Int)] =
    if (order == ListOrder.LatestComment && view.comments)
      (SelectionQuery.latestComment(round.contestId, commentsRound, commentsBy) -> -1) +: ListOrder.FileOrder.columns
    else if (withDetails) rankKey.columns ++ (if (round.isBinary) Seq("s.page_id" -> 1) else ListOrder.FileOrder.columns)
    else if (aggregateBinary && order != ListOrder.FileOrder) ListOrder.Binary(binaryOrder).columns
    // all the jurors' votes on a rated round by rate: by the average under the round's rule, as
    // its ranks and a file list are ([[rankKey]]), then in file order; by vote time is a
    // juror's own list's order, by rate for all the jurors' votes
    else if (asUserId == 0 && !round.isBinary && (order == ListOrder.ByRate || order.byVoteTime))
      rankKey.columns ++ ListOrder.FileOrder.columns
    else order match {
      // the latest comment first is the comments report's (a list built without it: by rate)
      case ListOrder.Binary(_) | ListOrder.LatestComment => ListOrder.ByRate.columns
      case o                                            => o.columns
    }

  /** Whether the list's files show their votes' times ([[org.intracer.wmua.Vote.votedAt]]): one
    * juror's votes (the viewer's own, or as the organisers view them; never all the jurors' votes
    * together) in an order by vote time. The large view shows the time of one juror's vote in any
    * order ([[showsVoteTime]]).
    */
  def showsVoteTimes: Boolean = asUserId != 0 && order.byVoteTime

  /** Whether a large view of the list shows its file's vote time: one juror's votes, in any order. */
  def showsVoteTime: Boolean = asUserId != 0

  /** Files to a page: a file list's are rows of a table; the comments report's files have all
    * their comments under them.
    */
  def pageSize: Int =
    if (view == GalleryView.FileList) RoundList.FileListPageSize
    else if (view.comments) RoundList.CommentsPageSize
    else Pager.pageSize

  def query: SelectionQuery =
    SelectionQuery(
      userId = Some(asUserId).filter(_ != 0),
      roundId = round.id,
      rate = filter.rate,
      rated = filter.rated,
      regions = region.toSet,
      order = orderColumns,
      grouped = asUserId == 0 && !withDetails,
      groupWithDetails = withDetails,
      // all the jurors' votes: the files with a vote the filter matches, counted and ordered
      // by all their votes; a juror's: their one vote on each file
      filesByVotes = asUserId == 0,
      // grouped rows count their approvals and rejections: a yes/no round's tally, a rated
      // round's rated votes (the denominator of AverageRule.Rated)
      tally = true,
      // a rated round's sums are of grades, a ✗ as 0, and its rated votes any vote
      grades = !round.isBinary,
      subRegions = round.specialNomination.contains("Віа Регіа"),
      commentsIn = Option.when(commentsOnly)(round.contestId),
      commentsRound = commentsRound,
      commentsBy = commentsBy,
      monumentId = monument
    )

  /** The list over `round_image`'s counts ([[RoundImageQuery]]), if it may be read from them:
    * all the jurors' votes with `roundImage.read` on, a filter and order the counts can tell,
    * and no statement of this request fallen back to the votes ([[CountsSession]]). Whether a
    * statement's counts are the votes' is proved per statement ([[fromCounts]]).
    */
  def countsQuery: Option[RoundImageQuery] =
    // a monument's list is read from the votes, as with the comments filter: it is small
    if (!countsRead || asUserId != 0 || commentsOnly || monument.isDefined || countsSession.fellBack(roundId)) None
    else RoundImageQuery.of(query, round.isBinary, rankKey.columns.map(_._1))

  /** Whether this request's statements so far were read from the counts, none fallen back. */
  def countsUsed: Boolean = countsSession.generation(roundId).isDefined && !countsSession.fellBack(roundId)

  /** One statement from the counts, if its snapshot is proved to have the votes' counts, else
    * `votes`. The proof: the round's generation was clean (`counts_gen_done >= counts_gen`) when
    * this request's first counts statement began, and is unchanged after this one ends. Every
    * bulk writer of the round's votes bumps `counts_gen` in its own transaction (P2a), and the
    * generation only grows, so an unchanged generation means no bulk write committed from the
    * first check to the end of this statement: the stored counts, with the marked files read
    * from their votes in the same statement ([[RoundImageQuery]]), are the votes'. A generation
    * that wasn't clean, or changed, sends this statement and the rest of the request to the
    * votes. The first statement costs two primary-key reads of `round`, each later one one.
    */
  private def fromCounts[A](counts: RoundImageQuery => A)(votes: => A): A =
    countsQuery.flatMap { q =>
      val session = countsSession
      session.generation(roundId).orElse(RoundCounts.cleanGeneration(roundId)) match {
        case None =>
          session.fallBack(roundId)
          None
        case Some(gen) =>
          session.beforeRead()
          val result = counts(q)
          if (RoundCounts.generation(roundId).contains(gen)) {
            session.read(roundId, gen)
            Some(result)
          } else {
            session.fallBack(roundId)
            None
          }
      }
    }.getOrElse(votes)

  def count(): Int = fromCounts(_.count())(query.count())

  /** The number of the list's files in each region, without its region filter (all the
    * jurors' votes; a juror's come from [[jurorCounts]]).
    */
  def regionCounts(): Map[String, Int] = {
    val all = copy(region = None)
    all.fromCounts(_.regionCounts())(all.query.regionCounts())
  }

  /** The list's files at `limit`, and the large view's file whatever the filter: from the
    * counts, else the votes. A file list's rows carry their votes, read by page id.
    */
  private def read(limit: Limit, withPageId: Option[Long] = None): Seq[ImageWithRating] = {
    def votes = query.copy(limit = Some(limit), withPageId = withPageId).list()
    if (withDetails)
      fromCounts(c => Option(c.copy(limit = Some(limit), withPageId = withPageId).pageIds()))(None)
        .fold(votes)(query.withVotes(_))
    else fromCounts(_.copy(limit = Some(limit), withPageId = withPageId).list())(votes)
  }

  /** The juror's files by region and rate, which give the list's total, its region nav's
    * counts and the juror's tabs in one query; None for all the jurors' votes together, or
    * sub-regions (the monument table's).
    */
  def jurorCounts(): Option[JurorCounts] =
    Option.when(asUserId != 0 && !query.subRegions)(JurorCounts(query.regionRateCounts(), round.isBinary))

  /** The juror's counts for their tabs: [[jurorCounts]]; under a monument filter in a round of
    * sub-regions too, from the same scan by top-level regions, whose rates don't depend on the
    * regions (its region keys, wrong for sub-regions, aren't used). None for all the jurors' votes,
    * and in a round of sub-regions without the filter: the tabs then count all the juror's files
    * (`GalleryService.rateDistribution`), which no filter narrows.
    */
  def tabCounts(): Option[JurorCounts] =
    jurorCounts().orElse(Option.when(asUserId != 0 && monument.isDefined)(
      JurorCounts(query.copy(subRegions = false).regionRateCounts(), round.isBinary)))

  /** The pager's page of files. */
  def files(pager: Pager): Seq[ImageWithRating] =
    read(Limit(Some(pager.pageSize), Some(pager.offset)))

  /** Whether a page of the list shows its files' ranks among all of them: a file list, or the
    * by-rate gallery in any order but file order or by vote time, which the ranks would not
    * follow.
    */
  def ranked: Boolean =
    view == GalleryView.FileList || (view == GalleryView.ByRate && order != ListOrder.FileOrder && !order.byVoteTime)

  /** The number of the list's files of each value of its [[rankKey]], in its order: what a
    * page's [[ranks]] are counted from, and the list's total (their sum, which [[count]] has
    * too), from one grouped query.
    */
  def rankCounts(): Seq[(Seq[Long], Int)] =
    fromCounts(_.keyCounts(rankKey.columns))(query.keyCounts(rankKey.columns))

  /** The ranks of a page of the list's files among all of them ([[rankKey]]): a file list's, a
    * by-rate gallery's, from the list's [[rankCounts]].
    */
  def ranks(files: Seq[ImageWithRating], counts: Seq[(Seq[Long], Int)]): Seq[String] = {
    val ranks = Ranks(counts)
    files.map(file => ranks(rankKey.values(file)))
  }

  def ranks(files: Seq[ImageWithRating]): Seq[String] = ranks(files, rankCounts())

  /** The file's index in the list, from 0. For a file of the viewer's round that the filter
    * leaves out (one just voted on), where it would be: the number of the list's files before
    * it. None for a file not in the viewer's round (or region).
    *
    * A juror's list: the file's row, then a count of the rows ordered before it
    * ([[SelectionQuery.countBefore]]); all the jurors' votes together: its rank among all the
    * round's files ([[rankPosition]]).
    */
  def position(pageId: Long): Option[Int] =
    if (query.keyed) query.orderKey(pageId).map(query.countBefore(_))
    else rankPosition(pageId)

  /** [[position]] by the file's rank (ROW_NUMBER) among all the list's files. */
  private[controllers] def rankPosition(pageId: Long): Option[Int] = {
    val rank = fromCounts(_.copy(withPageId = Some(pageId)).imageRank(pageId))(
      query.copy(withPageId = Some(pageId)).imageRank(pageId))
    Option.when(rank > 0)(rank - 1)
  }

  /** The file among the list's files around it, as its large view moves among them: up to two
    * before it, and five in all where the list has them; and the files after those that its
    * strip of following files shows (`ahead`). The file too if the filter now leaves it out, in
    * its place. None for a file not in the viewer's round (or region).
    *
    * A juror's list: the files before it, nearest first, and the file with those after it,
    * each read from its place in the order ([[ImageDbNew.Keyset]]), not at an offset.
    */
  def around(pageId: Long): Option[Neighbourhood] =
    if (!query.keyed) aroundAtOffset(pageId)
    else
      query.orderKey(pageId).flatMap { key =>
        val position = query.countBefore(key)
        val side = math.min(Neighbourhood.Side, position)
        val all = Neighbourhood.Window
        val before =
          if (side == 0) Nil
          else query.copy(keyset = Some(key.copy(before = true)), limit = Some(Limit(Some(side), Some(0)))).list().reverse
        // the file and those after it in the window, and on to the strip's last one
        val from = query
          .copy(withPageId = Some(pageId), keyset = Some(key.copy(before = false)),
            limit = Some(Limit(Some(math.max(all - side, 1 + Neighbourhood.Following)), Some(0))))
          .list()
        val files = before ++ from.take(all - side)
        Some(files.indexWhere(_.pageId == pageId)).filter(_ >= 0)
          .map(Neighbourhood(files, _, position, ahead = from.drop(all - side)))
      }

  /** [[around]] by the file's position and the list's files at an offset from it. */
  private[controllers] def aroundAtOffset(pageId: Long): Option[Neighbourhood] = {
    // one source for the position and the files around it, the same order (W10): the request's
    // (CountsSession), the votes for both once one statement has fallen back
    rankPosition(pageId).flatMap { position =>
      val offset = math.max(0, position - Neighbourhood.Side)
      val all = Neighbourhood.Window
      val files0 =
        read(Limit(Some(math.max(all, position - offset + 1 + Neighbourhood.Following)), Some(offset)), Some(pageId))
      val files = files0.take(all)
      Some(files.indexWhere(_.pageId == pageId)).filter(_ >= 0)
        .map(Neighbourhood(files, _, position, ahead = files0.drop(all)))
    }
  }

  /** How a vote for the file, at `before` in the list before the vote, changed its place:
    * moved by its new rate, or out of the list by the filter. Not out of the unrated files,
    * which a vote is meant to take out of them; never in file order with all the files.
    */
  def voteMoved(pageId: Long, before: Int, rate: Int): Option[MovedVote] =
    if (order == ListOrder.FileOrder && filter == VoteFilter.All) None
    else {
      // the file's place in the list after the vote; None if the filter leaves it out
      val now =
        if (query.keyed) query.orderKey(pageId).filter(_ => filter.matches(rate, round.isBinary)).map(query.countBefore(_))
        else Some(query.imageRank(pageId)).filter(_ > 0).map(_ - 1)
      now match {
        case None    => Option.when(filter != VoteFilter.Unrated)(MovedVote(pageId, out = true, rate))
        case Some(p) => Option.when(p != before)(MovedVote(pageId, out = false, rate))
      }
    }

  def withFilter(filter: VoteFilter): RoundList = copy(filter = filter)

  /** The list of only the files with comments, or of all of them. */
  def withComments(on: Boolean): RoundList = copy(comments = on)

  /** The number of files the list has with comments, with the toggle on or off: what the toggle
    * would list. A juror's from their counts with comments (one scan of their rows), all the
    * jurors' from a count of the list with comments.
    */
  def commentedCount(): Int = {
    val on = withComments(true)
    on.jurorCounts().fold(on.count())(_.total(filter, region))
  }

  def withOrder(order: ListOrder): RoundList = copy(order = order)

  /** The comments report's list in the scope. */
  def withScope(scope: CommentScope): RoundList = copy(commentScope = scope)

  /** The large view's line of its file's monument (its monument id, as stored, whether or not a
    * monument list has a row of it): the number of files this list has of that monument (its
    * viewer's, view, order, tab and comments kept), and the list with only them if it has a file
    * other than this one. The file itself may be outside the list (a vote just took it off the
    * tab): it counts only if it is in it. None while the list already has a monument filter, for
    * a file without a monument id, or when the list has none of the monument's files.
    *
    * One count of the monument's files ([[count]]); a second, with the file whatever the
    * filters, only when that count is 1, to tell whether the one file is this one.
    */
  def monumentLine(file: ImageWithRating): Option[MonumentLine] =
    if (monument.isDefined) None
    else
      file.image.monumentId.flatMap(RoundList.validMonument).flatMap { id =>
        val ofMonument = withMonument(Some(id))
        val n = ofMonument.count()
        // the file is in the list if counting it whatever the filters adds nothing
        def fileInList = ofMonument.query.copy(withPageId = Some(file.pageId)).count() == n
        val others = if (n == 1 && fileInList) 0 else n
        Option.when(n > 0)(MonumentLine(id, n, Option.when(others > 0)(ofMonument.pageUrl(1))))
      }

  /** The list of all its files, whatever their comments: without the toggle, a comments report
    * as the gallery ([[withView]]).
    */
  def allFiles: RoundList = withComments(false).withView(if (view.comments) GalleryView.Gallery else view)

  /** The list's own orders in the comments report: a juror's as their order menu has them; all
    * the jurors' votes on a yes/no round in a [[BinaryOrder]], on a rated round by rate, either
    * in file order too.
    */
  private def ownCommentOrders: Seq[ListOrder] =
    if (aggregateBinary) BinaryOrder.all.map(ListOrder.Binary) :+ ListOrder.FileOrder
    else if (asUserId == 0) Seq(ListOrder.ByRate, ListOrder.FileOrder)
    else orderChoices

  /** The orders the comments report offers: by the latest comment ([[ListOrder.LatestComment]]),
    * its default, then the list's own ([[ownCommentOrders]]).
    */
  def commentOrderChoices: Seq[ListOrder] = ListOrder.LatestComment +: ownCommentOrders

  /** The comments report's order as its menu shows it: one it hasn't is the first of the list's
    * own (as the list's query orders by it), not the latest comment.
    */
  def shownCommentOrder: ListOrder =
    if (commentOrderChoices.contains(order)) order else ownCommentOrders.head

  /** The comments on the list's files that the comments filter keeps them by, of all its
    * pages: the comments report's total ([[SelectionQuery.commentCount]]).
    */
  def commentCount(): Int = query.commentCount()

  /** The comments of the files of a page of the comments report, in its scope, each file's in
    * the order written: everyone's, also with the filter by author ([[commentsBy]]), whose
    * comments the report shows, the others' folded under them (commentsReport).
    */
  def commentsOf(files: Seq[ImageWithRating]): Map[Long, Seq[org.intracer.wmua.Comment]] =
    org.intracer.wmua.CommentJdbc.findByRooms(round.contestId, files.map(_.pageId), commentsRound).groupBy(_.room)

  /** The comments report of only the comments by the account, or (None) of everyone's. */
  def withCommenter(userId: Option[Long]): RoundList = copy(commenter = userId.filter(_ > 0))

  /** The list in a region, or all of them ("all"). */
  def withRegion(region: String): RoundList = copy(region = Some(region).filter(_ != "all"))

  /** The list of only the monument's files (its id exactly, [[RoundList.monumentParam]]'s rules:
    * trimmed, not blank, at most 255 characters, else none), in all regions: a monument is in one,
    * so a region menu would only empty the list; or (None) of every monument's, the region as it
    * is (all of them once a monument was chosen).
    */
  def withMonument(monumentId: Option[String]): RoundList =
    monumentId.flatMap(RoundList.validMonument).fold(copy(monument = None))(m => copy(monument = Some(m), region = None))

  def withViewer(asUserId: Long): RoundList = copy(asUserId = asUserId)

  /** The list in another view; into the comments report by the latest comment, its default
    * ([[RoundList.defaultOrder]]); out of it, by rate instead of by the latest comment, which
    * only it has. Between the report and its CSV the order stays.
    */
  def withView(view: GalleryView): RoundList =
    copy(view = view, order =
      if (view.comments && !this.view.comments) ListOrder.LatestComment
      else if (order == ListOrder.LatestComment && !view.comments) ListOrder.ByRate
      else order)

  /** The list read from `round_image`'s counts where it can be (`roundImage.read`, P2b). */
  def withCountsRead(on: Boolean): RoundList = copy(countsRead = on)

  /** The list in another round, its filter all files if that round's kind hasn't it. */
  def withRound(round: Round): RoundList = copy(round = round, filter = filter.in(round))

  /** The list in another round as the galleries' round menu opens it, for `viewerId`. One juror's
    * votes as the organisers view them (neither the viewer's own nor all together) stay that
    * juror's only in a round they are a juror of (`jurorOfRound`, [[User.roundsJuredIn]]):
    * elsewhere the juror's list is refused ([[services.GalleryService.viewAs]]), so it is all the
    * jurors' votes there, as the juror menu's "all", in that list's default order (by rate, a
    * yes/no round's in the binary order last chosen, [[RoundList.of]]; in the comments report
    * by the latest comment, its default): a juror's order by vote time isn't one of its, and a
    * named binary order would be remembered as chosen.
    */
  def inRound(round: Round, viewerId: Long, jurorOfRound: Boolean): RoundList =
    if (asUserId == 0 || asUserId == viewerId || jurorOfRound) withRound(round)
    else withRound(round).withViewer(0).withOrder(if (view.comments) ListOrder.LatestComment else ListOrder.ByRate)

  /** The list's query string: its view (`module`, for a large view's way back), filter, the
    * comments toggle, the comments report's scope and filter by author, the monument, and order.
    */
  private def params(module: Boolean): Seq[(String, String)] =
    Option.when(module && view != GalleryView.Gallery)("module" -> view.name).toSeq ++
      Option.when(filter != VoteFilter.All)("filter" -> filter.param).toSeq ++
      Option.when(comments)(RoundList.CommentsParam -> "1").toSeq ++
      Option.when(view.comments && commentScope != CommentScope.ThisRound)(RoundList.ScopeParam -> commentScope.param)
        .toSeq ++
      commentsBy.map(RoundList.CommenterParam -> _.toString).toSeq ++
      monument.map(RoundList.MonumentParam -> _).toSeq :+
      ("sort" -> order.param)

  private def withParams(path: String, params: Seq[(String, String)]): String =
    if (params.isEmpty) path
    else
      path + (if (path.contains('?')) "&" else "?") +
        params.map { case (k, v) => k + "=" + URLEncoder.encode(v, UTF_8) }.mkString("&")

  /** The list's page. */
  def pageUrl(page: Int): String = {
    val path = view match {
      case GalleryView.Gallery  => routes.GalleryController.list(asUserId, page, regionParam, roundId)
      case GalleryView.ByRate   => routes.GalleryController.byRate(asUserId, page, regionParam, roundId)
      case GalleryView.FileList => routes.GalleryController.fileList(asUserId, page, regionParam, roundId)
      case GalleryView.Csv      => routes.GalleryController.query(view.name, Some(asUserId), page, regionParam, roundId)
      case GalleryView.Comments => routes.GalleryController.comments(asUserId, page, regionParam, roundId)
      case GalleryView.CommentsCsv => routes.GalleryController.commentsCsv(asUserId, regionParam, roundId)
    }
    withParams(path.url, params(module = false))
  }

  /** The list's page with the file, scrolled to it. */
  def atUrl(pageId: Long): String = {
    val path = view match {
      case GalleryView.ByRate => routes.GalleryController.byRateAt(asUserId, pageId, regionParam, roundId)
      case GalleryView.Comments | GalleryView.CommentsCsv =>
        routes.GalleryController.commentsAt(asUserId, pageId, regionParam, roundId)
      case _                  => routes.GalleryController.listAtId(asUserId, pageId, regionParam, roundId)
    }
    withParams(path.url, params(module = false)) + "#" + pageId
  }

  /** The file's large view, in this list. */
  def largeUrl(pageId: Long): String =
    withParams(routes.LargeViewController.large(asUserId, pageId, regionParam, roundId).url, params(module = true))

  /** A vote for the file in its large view, going on in this list: `select` the rate. */
  def voteUrl(pageId: Long, select: Int, criterion: Option[Long] = None): String =
    votePrefix(pageId) + select + voteQuery(criterion)

  /** A large view vote's URL before the rate: the stars add theirs, then [[voteQuery]]. */
  def votePrefix(pageId: Long): String =
    routes.LargeViewController.rateByPageId(roundId, pageId, 0, regionParam).url.stripSuffix("0")

  /** A large view vote's query string: this list's, and the criterion voted for, if any. */
  def voteQuery(criterion: Option[Long] = None): String =
    withParams("", params(module = true) ++ criterion.map(c => "criteria" -> c.toString))

  /** The list's regions, for its region menu ([[GalleryController.regions]]): its viewer,
    * filter, order and view (`module`), the region it is in.
    */
  def regionsUrl: String =
    withParams(routes.GalleryController.regions(asUserId, regionParam, roundId).url,
      Option.when(view != GalleryView.Gallery)("module" -> view.name).toSeq ++ params(module = false))

  /** A comment on the file in its large view, going back to it in this list: whose votes
    * (`user`, as the large view's path has them) with the rest of its parameters.
    */
  def commentUrl(pageId: Long): String =
    withParams(routes.ImageDiscussionController.addComment(pageId, regionParam, Some(roundId), None, Some(asUserId)).url,
      params(module = true))
}

/** Which source one request's statements of all the jurors' list read (P2b), per round:
  * `round_image`'s counts at the clean generation its first counts statement found, or, once a
  * statement found the generation changed or not clean, the votes for the rest of the request
  * ([[RoundList.fromCounts]]). Shared by a list's copies (`copy` keeps it); a new
  * [[RoundList]] (a new request) gets its own. Equal to any other, so a list's equality is its
  * parameters'.
  */
final class CountsSession {

  private var generations = Map.empty[Long, Long]
  private var fallen = Set.empty[Long]

  /** For tests: runs after a statement's generation check, before the statement. */
  @volatile private[controllers] var beforeRead: () => Unit = () => ()

  def generation(roundId: Long): Option[Long] = synchronized(generations.get(roundId))

  def fellBack(roundId: Long): Boolean = synchronized(fallen.contains(roundId))

  private[controllers] def read(roundId: Long, gen: Long): Unit = synchronized(generations += roundId -> gen)

  private[controllers] def fallBack(roundId: Long): Unit = synchronized(fallen += roundId)

  override def equals(other: Any): Boolean = other.isInstanceOf[CountsSession]
  override def hashCode: Int = 0
  override def toString: String = "CountsSession"
}

/** A juror's files in a round by top-level region (None: without a monument id) and rate
  * ([[db.scalikejdbc.rewrite.ImageDbNew.SelectionQuery.regionRateCounts]]).
  */
final case class JurorCounts(byRegionRate: Map[(Option[String], Int), Int], binary: Boolean = false) {

  /** The files per rate: the juror's tabs ([[RateDistribution]]). */
  def rates: Map[Int, Int] = byRegionRate.groupMapReduce(_._1._2)(_._2)(_ + _)

  /** The files the filter lists per region, every region of the juror's files kept (0 if
    * none), without the files without a monument id: the region nav's counts.
    */
  def regions(filter: VoteFilter): Map[String, Int] =
    byRegionRate.toSeq.collect { case ((Some(region), rate), n) => region -> (if (filter.matches(rate, binary)) n else 0) }
      .groupMapReduce(_._1)(_._2)(_ + _)

  /** The files the filter lists, in the region or in all of them: the list's total. */
  def total(filter: VoteFilter, region: Option[String]): Int =
    byRegionRate.toSeq.collect {
      case ((r, rate), n) if region.forall(r.contains) && filter.matches(rate, binary) => n
    }.sum
}

/** A large view's file (`files(index)`, at `position` in its list) and the files around it,
  * which its keys and buttons move among; `ahead`, the list's files after those, which only
  * its strip of following files shows.
  */
final case class Neighbourhood(
    files: Seq[ImageWithRating],
    index: Int,
    position: Int,
    ahead: Seq[ImageWithRating] = Nil
) {

  def file: ImageWithRating = files(index)

  /** The files after it, as its strip shows them: up to [[Neighbourhood.Following]]. */
  def following: Seq[ImageWithRating] = (files.drop(index + 1) ++ ahead).take(Neighbourhood.Following)

  /** Where a vote for the file goes on to: the file after it, or before it at the list's end. */
  def next: Option[ImageWithRating] = files.lift(index + 1).orElse(files.lift(index - 1))
}

/** The large view's line of its file's monument in the list ([[RoundList.monumentLine]]): the
  * list's files of the monument (`count`), and the list with only them (`url`) if it has a file
  * other than the large view's; None when that file is the only one.
  */
final case class MonumentLine(monumentId: String, count: Int, url: Option[String])

/** A vote that moved its file in the juror's list, or out of it ([[RoundList.voteMoved]]): the
  * next page, which the vote goes on to, says so and links the file. Carried in the flash.
  *
  * @param rate the file's new rate
  */
final case class MovedVote(pageId: Long, out: Boolean, rate: Int) {
  def flash: (String, String) = MovedVote.Key -> s"$pageId:${if (out) "out" else "moved"}:$rate"
}

object MovedVote {

  val Key = "movedVote"

  def of(flash: Flash): Option[MovedVote] =
    flash.get(Key).map(_.split(':')).flatMap {
      case Array(id, kind, rate) if kind == "out" || kind == "moved" =>
        for (pageId <- id.toLongOption; r <- rate.toIntOption) yield MovedVote(pageId, kind == "out", r)
      case _ => None
    }
}

object Neighbourhood {

  /** The files a large view moves among before its file; [[Window]] in all. */
  val Side = 2

  val Window: Int = 2 * Side + 1

  /** The files a large view's strip shows after its file. */
  val Following = 5
}

object RoundList {

  /** A file list's files to a page: all the jurors' votes on 38,366 files at once ran a 1 GB
    * server out of memory (docs/history/2026-11-01-binary-aggregate-votes.md, issue 4).
    */
  val FileListPageSize = 1000

  /** The cookie with the order a juror last chose for their own gallery, in this browser: kept
    * after signing out, unlike the session.
    */
  val SortCookie = "gallerySort"

  /** The region segment of the removed by-rate view grouped by region (46017ee3): its old URLs
    * show all regions, not an unknown region with no files.
    */
  val GroupedRegion = "grouped"

  /** The cookie with the order of a yes/no round's files by all the jurors' votes an
    * organiser last chose ([[BinaryOrder]]), in this browser.
    */
  val BinarySortCookie = "binarySort"

  /** A list URL's parameter for only the files with comments: `comments=1`. */
  val CommentsParam = "comments"

  /** A comments report URL's parameter of its scope ([[CommentScope]]): `scope=contest`. */
  val ScopeParam = "scope"

  /** A comments report URL's parameter of its filter by author ([[RoundList.commenter]]):
    * `commenter=<user id>`.
    */
  val CommenterParam = "commenter"

  /** The comments report's files to a page: a round's busiest had 1,542 comments on 321 files. */
  val CommentsPageSize = 20

  /** A list URL's parameter of its monument filter ([[RoundList.monument]]): `monument=<id>`. */
  val MonumentParam = "monument"

  /** The longest monument id a list takes: `images.monument_id`'s and `vote.monument_id`'s length. */
  val MonumentMaxLength = 255

  /** A monument id as a list takes it: trimmed, not blank and at most [[MonumentMaxLength]]
    * characters; else none (the list of all monuments, never an error).
    */
  def validMonument(id: String): Option[String] =
    Some(id.trim).filter(m => m.nonEmpty && m.length <= MonumentMaxLength)

  /** A request's monument filter ([[MonumentParam]], [[validMonument]]). */
  def monumentParam(request: RequestHeader): Option[String] =
    request.getQueryString(MonumentParam).flatMap(validMonument)

  /** The list a request is for: the round, whose votes, the region from its path ("all" or
    * [[GroupedRegion]] for all of them; all of them with a monument filter); the view
    * from its path or, for a large view, its `module`; the filter, the comments toggle
    * ([[CommentsParam]]), the monument ([[MonumentParam]]) and order from its query,
    * the filter all files if the round's kind hasn't it, the order [[defaultOrder]] if it
    * names none. All the jurors' votes on a yes/no round by rate (or by vote time, a juror's
    * order) are in the binary order last chosen ([[BinarySortCookie]]), else the default; on a
    * rated round by vote time is by rate; any other list's binary order is by rate.
    */
  def of(round: Round, viewer: User, asUserId: Long, region: String, view: GalleryView)(implicit
      request: RequestHeader
  ): RoundList = {
    // the comments report is the organisers' (GalleryController refuses its pages to others): a
    // large view's `module` naming it is the gallery for anyone else
    val shown = if (view.comments && !viewer.canViewOrgInfo(round)) GalleryView.Gallery else view
    // the monument filter, for everyone: it only narrows what the viewer may see; it drops the
    // region (a monument is in one region, so a region of another would list nothing)
    val monument = monumentParam(request)
    val list = RoundList(
      round,
      asUserId,
      Some(region).filterNot(r => r == "all" || r == GroupedRegion || monument.isDefined),
      VoteFilter.of(request).in(round),
      // by the latest comment only in the comments report
      requestedOrder.filter(o => o != ListOrder.LatestComment || shown.comments)
        .getOrElse(defaultOrder(viewer, asUserId, shown)),
      shown,
      comments = request.getQueryString(CommentsParam).contains("1"),
      commentScope = CommentScope.of(request),
      // the report's filter by author: only in the report, so a juror's large view naming it with
      // module=comments (their gallery, above) ignores it
      commenter = Option.when(shown.comments)(
        request.getQueryString(CommenterParam).flatMap(_.toLongOption).filter(_ > 0)).flatten,
      monument = monument
    )
    list.order match {
      case ListOrder.ByRate if list.aggregateBinary => list.withOrder(ListOrder.Binary(rememberedBinary))
      // by vote time is a juror's list's: all the jurors' votes by rate
      case o if o.byVoteTime && asUserId == 0 =>
        list.withOrder(if (list.aggregateBinary) ListOrder.Binary(rememberedBinary) else ListOrder.ByRate)
      case ListOrder.Binary(_) if !list.aggregateBinary => list.withOrder(ListOrder.ByRate)
      case _ => list
    }
  }

  private def rememberedBinary(implicit request: RequestHeader): BinaryOrder =
    request.cookies.get(BinarySortCookie).flatMap(c => BinaryOrder.parse(c.value)).getOrElse(BinaryOrder.Default)

  /** Where a juror starts in a round: all their files, in their order. */
  def landing(round: Round, juror: User)(implicit request: RequestHeader): RoundList =
    RoundList(round, juror.getId, order = defaultOrder(juror, juror.getId, GalleryView.Gallery))

  /** A list's order when its URL names none: in the comments report (its page, CSV and large
    * views) the latest comment first; in a juror's own gallery the one they last chose
    * ([[SortCookie]]), else file order, which a vote doesn't change; by rate for the others,
    * the organisers' and the results.
    */
  def defaultOrder(viewer: User, asUserId: Long, view: GalleryView)(implicit request: RequestHeader): ListOrder =
    if (view.comments) ListOrder.LatestComment
    else if (ownGallery(viewer, asUserId, view))
      request.cookies.get(SortCookie).flatMap(c => ListOrder.parse(c.value)).getOrElse(ListOrder.FileOrder)
    else ListOrder.ByRate

  /** The cookie remembering the order of the viewer's own gallery, or of all the jurors'
    * votes on a yes/no round ([[BinarySortCookie]]), if the request names another than the
    * one remembered. `secure`: as the session cookie (`play.http.session.secure`), as
    * [[ThemeCookies]] does.
    */
  def remembered(list: RoundList, viewer: User, secure: Boolean)(implicit request: RequestHeader): Option[Cookie] = {
    val cookie =
      if (ownGallery(viewer, list.asUserId, list.view)) Some(SortCookie)
      else Option.when(list.aggregateBinary && list.order.isInstanceOf[ListOrder.Binary])(BinarySortCookie)
    cookie.filter(name =>
      requestedOrder.contains(list.order) && !request.cookies.get(name).exists(_.value == list.order.param)
    ).map(Cookie(_, list.order.param, maxAge = Some(SortCookieAge), secure = secure,
      sameSite = Some(Cookie.SameSite.Lax)))
  }

  private val SortCookieAge = 365 * 24 * 60 * 60

  private def requestedOrder(implicit request: RequestHeader): Option[ListOrder] =
    request.getQueryString("sort").flatMap(ListOrder.parse)

  private def ownGallery(viewer: User, asUserId: Long, view: GalleryView): Boolean =
    asUserId != 0 && viewer.id.contains(asUserId) && view == GalleryView.Gallery
}
