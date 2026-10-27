package controllers

import db.scalikejdbc.Round
import db.scalikejdbc.rewrite.ImageDbNew.{Limit, SelectionQuery}
import org.intracer.wmua.ImageWithRating
import play.api.mvc.RequestHeader

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

  /** The highest rated first; files of a rate by monument, then page id. */
  case object ByRate extends ListOrder("rate", Seq("rate" -> -1, "s.monument_id" -> 1, "s.page_id" -> 1))

  val all: Seq[ListOrder] = Seq(ByRate)

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

object RoundList {

  /** The list a request is for: the round, whose votes, the region from its path; the view
    * from its path or, for a large view, its `module`; the filter and order from its query.
    */
  def of(round: Round, asUserId: Long, region: String, view: GalleryView)(implicit
      request: RequestHeader
  ): RoundList =
    RoundList(
      round,
      asUserId,
      Some(region).filter(_ != "all"),
      VoteFilter.of(request),
      request.getQueryString("sort").flatMap(ListOrder.parse).getOrElse(ListOrder.ByRate),
      view
    )
}
