package controllers

import db.scalikejdbc.{Round, User}
import db.scalikejdbc.rewrite.ImageDbNew.{Limit, SelectionQuery}
import org.intracer.wmua.ImageWithRating
import play.api.mvc.{Cookie, RequestHeader}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

/** Which of a round's files a list has, by the viewer's votes on them: all, the unrated, the
  * rated (above 0: a binary round's selected), or those of one rate (a binary round's selected
  * or rejected, a rated round's stars).
  *
  * @param param its value of a list URL's `filter`
  */
sealed abstract class VoteFilter(val param: String) {

  /** As [[SelectionQuery]] has it. */
  def rate: Option[Int] = None
  def rated: Option[Boolean] = None
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
sealed abstract class ListOrder(val param: String, val columns: Seq[(String, Int)])

object ListOrder {

  /** By monument, then page id: a vote never moves a file. */
  case object FileOrder extends ListOrder("file", Seq("s.monument_id" -> 1, "s.page_id" -> 1))

  /** The highest rated first; files of a rate in file order. */
  case object ByRate extends ListOrder("rate", Seq("rate" -> -1, "s.monument_id" -> 1, "s.page_id" -> 1))

  val all: Seq[ListOrder] = Seq(FileOrder, ByRate)

  def parse(param: String): Option[ListOrder] = all.find(_.param == param)
}

/** How a list is shown: as a gallery (a binary round's tiles to vote on, a rated round's
  * thumbnails with their stars), by rate, as a file list, or as CSV.
  *
  * @param name its path's first segment (`/gallery/...`), and a large view URL's `module`
  */
sealed abstract class GalleryView(val name: String)

object GalleryView {
  case object Gallery extends GalleryView("gallery")
  case object ByRate extends GalleryView("byrate")
  case object FileList extends GalleryView("filelist")
  case object Csv extends GalleryView("csv")

  val all: Seq[GalleryView] = Seq(Gallery, ByRate, FileList, Csv)

  def parse(name: String): Option[GalleryView] = all.find(_.name == name)

  /** The view a large view goes back to: a request's `module`, the gallery if none. Only a
    * gallery or by rate lists files one by one.
    */
  def of(request: RequestHeader): GalleryView =
    request.getQueryString("module").flatMap(parse).filter(Set(Gallery, ByRate)).getOrElse(Gallery)

  /** A round gallery path's view: all the jurors' votes together (`user/0`) only by rate. */
  def ofPath(asUserId: Long): GalleryView = if (asUserId == 0) ByRate else Gallery
}

/** The list of a round's files a viewer is in: whose votes (a juror's, or all together with
  * `asUserId` 0), the region, the filter by votes, the order, and the view. It has the list's
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
    view: GalleryView = GalleryView.Gallery
) {

  def roundId: Long = round.getId

  /** The region of a URL's path: "all" for all of them. */
  def regionParam: String = region.getOrElse("all")

  /** Files with their jurors' votes, one row each (file list, CSV): all of them, not a page. */
  private def withDetails: Boolean = view == GalleryView.FileList || view == GalleryView.Csv

  def query: SelectionQuery =
    SelectionQuery(
      userId = Some(asUserId).filter(_ != 0),
      roundId = round.id,
      rate = filter.rate,
      rated = filter.rated,
      regions = region.toSet,
      order = order.columns,
      grouped = asUserId == 0 && !withDetails,
      groupWithDetails = withDetails,
      subRegions = round.specialNomination.contains("Віа Регіа")
    )

  def count(): Int = query.count()

  /** The pager's page of files; all of them for a file list or CSV. */
  def files(pager: Pager): Seq[ImageWithRating] =
    if (withDetails) query.list()
    else query.copy(limit = Some(Limit(Some(pager.pageSize), Some(pager.offset)))).list()

  /** The file's index in the list, from 0. For a file of the viewer's round that the filter
    * leaves out (one just voted on), where it would be: the number of the list's files before
    * it. None for a file not in the viewer's round (or region).
    */
  def position(pageId: Long): Option[Int] = {
    val rank = query.copy(withPageId = Some(pageId)).imageRank(pageId)
    Option.when(rank > 0)(rank - 1)
  }

  /** The file among the list's files around it, as its large view shows them: up to two
    * before it, and five in all where the list has them. The file too if the filter now leaves
    * it out, in its place. None for a file not in the viewer's round (or region).
    */
  def around(pageId: Long): Option[Neighbourhood] =
    position(pageId).flatMap { position =>
      val offset = math.max(0, position - Neighbourhood.Side)
      val files = query
        .copy(withPageId = Some(pageId), limit = Some(Limit(Some(2 * Neighbourhood.Side + 1), Some(offset))))
        .list()
      Some(files.indexWhere(_.pageId == pageId)).filter(_ >= 0).map(Neighbourhood(files, _))
    }

  def withFilter(filter: VoteFilter): RoundList = copy(filter = filter)

  def withOrder(order: ListOrder): RoundList = copy(order = order)

  /** The list in a region, or all of them ("all"). */
  def withRegion(region: String): RoundList = copy(region = Some(region).filter(_ != "all"))

  def withViewer(asUserId: Long): RoundList = copy(asUserId = asUserId)

  def withView(view: GalleryView): RoundList = copy(view = view)

  def withRound(round: Round): RoundList = copy(round = round)

  /** The list's query string: its view (`module`, for a large view's way back), filter and
    * order.
    */
  private def params(module: Boolean): Seq[(String, String)] =
    Option.when(module && view != GalleryView.Gallery)("module" -> view.name).toSeq ++
      Option.when(filter != VoteFilter.All)("filter" -> filter.param).toSeq :+
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
    }
    withParams(path.url, params(module = false))
  }

  /** The list's page with the file, scrolled to it. */
  def atUrl(pageId: Long): String = {
    val path = view match {
      case GalleryView.ByRate => routes.GalleryController.byRateAt(asUserId, pageId, regionParam, roundId)
      case _                  => routes.GalleryController.listAtId(asUserId, pageId, regionParam, roundId)
    }
    withParams(path.url, params(module = false)) + "#" + pageId
  }

  /** The file's large view, in this list. */
  def largeUrl(pageId: Long): String =
    withParams(routes.LargeViewController.large(asUserId, pageId, regionParam, roundId).url, params(module = true))

  /** A vote for the file in its large view, going on in this list: `select` the rate. */
  def voteUrl(pageId: Long, select: Int, criterion: Option[Int] = None): String =
    votePrefix(pageId) + select + voteQuery(criterion)

  /** A large view vote's URL before the rate: the stars add theirs, then [[voteQuery]]. */
  def votePrefix(pageId: Long): String =
    routes.LargeViewController.rateByPageId(roundId, pageId, 0, regionParam).url.stripSuffix("0")

  /** A large view vote's query string: this list's, and the criterion voted for, if any. */
  def voteQuery(criterion: Option[Int] = None): String =
    withParams("", params(module = true) ++ criterion.map(c => "criteria" -> c.toString))

  /** A comment on the file in its large view, going back to it in this list. */
  def commentUrl(pageId: Long): String =
    withParams(routes.ImageDiscussionController.addComment(pageId, regionParam, Some(roundId)).url, params(module = true))
}

/** A large view's file (`files(index)`) and the files around it in its list. */
final case class Neighbourhood(files: Seq[ImageWithRating], index: Int) {

  def file: ImageWithRating = files(index)

  /** Where a vote for the file goes on to: the file after it, or before it at the list's end. */
  def next: Option[ImageWithRating] = files.lift(index + 1).orElse(files.lift(index - 1))
}

object Neighbourhood {

  /** The files a large view shows before its file; it shows `2 * Side + 1` in all. */
  val Side = 2
}

object RoundList {

  /** The cookie with the order a juror last chose for their own gallery, in this browser: kept
    * after signing out, unlike the session.
    */
  val SortCookie = "gallerySort"

  /** The list a request is for: the round, whose votes, the region from its path; the view
    * from its path or, for a large view, its `module`; the filter and order from its query,
    * the order [[defaultOrder]] if it names none.
    */
  def of(round: Round, viewer: User, asUserId: Long, region: String, view: GalleryView)(implicit
      request: RequestHeader
  ): RoundList =
    RoundList(
      round,
      asUserId,
      Some(region).filter(_ != "all"),
      VoteFilter.of(request),
      requestedOrder.getOrElse(defaultOrder(viewer, asUserId, view)),
      view
    )

  /** Where a juror starts in a round: all their files, in their order. */
  def landing(round: Round, juror: User)(implicit request: RequestHeader): RoundList =
    RoundList(round, juror.getId, order = defaultOrder(juror, juror.getId, GalleryView.Gallery))

  /** A list's order when its URL names none: in a juror's own gallery the one they last
    * chose ([[SortCookie]]), else file order, which a vote doesn't change; by rate for the
    * others, the organisers' and the results.
    */
  def defaultOrder(viewer: User, asUserId: Long, view: GalleryView)(implicit request: RequestHeader): ListOrder =
    if (ownGallery(viewer, asUserId, view))
      request.cookies.get(SortCookie).flatMap(c => ListOrder.parse(c.value)).getOrElse(ListOrder.FileOrder)
    else ListOrder.ByRate

  /** The cookie remembering the order of the viewer's own gallery, if the request names
    * another than the one remembered.
    */
  def remembered(list: RoundList, viewer: User)(implicit request: RequestHeader): Option[Cookie] =
    Option.when(
      ownGallery(viewer, list.asUserId, list.view) && requestedOrder.contains(list.order) &&
        !request.cookies.get(SortCookie).exists(_.value == list.order.param)
    )(Cookie(SortCookie, list.order.param, maxAge = Some(SortCookieAge), sameSite = Some(Cookie.SameSite.Lax)))

  private val SortCookieAge = 365 * 24 * 60 * 60

  private def requestedOrder(implicit request: RequestHeader): Option[ListOrder] =
    request.getQueryString("sort").flatMap(ListOrder.parse)

  private def ownGallery(viewer: User, asUserId: Long, view: GalleryView): Boolean =
    asUserId != 0 && viewer.id.contains(asUserId) && view == GalleryView.Gallery
}
