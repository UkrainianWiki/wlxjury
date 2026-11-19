package controllers

import db.scalikejdbc.Round
import play.api.mvc.RequestHeader

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** The Status, Type and Order menus of the Rounds page and the Round Statistics page (the
  * maintainer's decisions of 2026-11-18): which of the rounds the viewer already sees are listed,
  * and in what order. Kept in the URL (`status`, `type`, `order`), only where not the default, so a
  * page with no params is as before (all rounds, oldest first); links between the two pages carry
  * them ([[addTo]]). An unknown value is the default, never an error.
  *
  * It only narrows and orders a list it is given: the rounds a viewer may see are decided before
  * (the contest's rounds for its admins, those whose statistics they may view on the statistics
  * page), never here.
  */
final case class RoundsFilter(
    status: RoundsFilter.Status = RoundsFilter.Status.All,
    kind: RoundsFilter.Kind = RoundsFilter.Kind.All,
    order: RoundsFilter.Order = RoundsFilter.Order.Oldest
) {

  import RoundsFilter._

  def matches(round: Round): Boolean = status.matches(round) && kind.matches(round)

  /** The rounds this filter keeps, in its order. */
  def apply(rounds: Seq[Round]): Seq[Round] = sort(rounds.filter(matches))

  /** The rounds in this filter's order: by creation (the round's id, as the rounds list has always
    * been ordered), the oldest or the newest first; or the active ones first, each group oldest
    * first.
    */
  def sort(rounds: Seq[Round]): Seq[Round] = order match {
    case Order.Oldest => rounds.sortBy(_.id.getOrElse(Long.MaxValue))
    case Order.Newest => rounds.sortBy(_.id.getOrElse(Long.MaxValue))(Ordering[Long].reverse)
    case Order.ActiveFirst => rounds.sortBy(r => (!r.active, r.id.getOrElse(Long.MaxValue)))
  }

  /** The round the statistics page opens with this filter (its Status, Type and Order menus' items,
    * the maintainer's decision of 2026-11-19, "switch to first in order"): `current` if this filter
    * keeps it; else the first of `visible` it keeps, in its order (the oldest, the newest or the
    * first active); else, no round matching, `current` (the page then says none matches). Only one
    * of `visible`, the rounds the viewer may see on the page, or `current` itself.
    */
  def statRound(current: Round, visible: Seq[Round]): Round =
    if (matches(current)) current else apply(visible).headOption.getOrElse(current)

  def withStatus(s: Status): RoundsFilter = copy(status = s)
  def withKind(k: Kind): RoundsFilter = copy(kind = k)
  def withOrder(o: Order): RoundsFilter = copy(order = o)

  def isDefault: Boolean = this == RoundsFilter.Default

  /** The query params of the choices that aren't the default, in a fixed order. */
  def params: Seq[(String, String)] =
    Seq(
      Option.when(status != Status.All)(StatusParam -> status.param),
      Option.when(kind != Kind.All)(TypeParam -> kind.param),
      Option.when(order != Order.Oldest)(OrderParam -> order.param)
    ).flatten

  /** `url` with this filter's params added (after its own, if it has a query); as it is for the
    * default filter.
    */
  def addTo(url: String): String =
    if (params.isEmpty) url
    else {
      val query = params.map { case (k, v) => s"$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}" }.mkString("&")
      url + (if (url.contains('?')) "&" else "?") + query
    }
}

object RoundsFilter {

  val StatusParam = "status"
  val TypeParam = "type"
  val OrderParam = "order"

  val Default: RoundsFilter = RoundsFilter()

  sealed abstract class Status(val param: String, val messageKey: String) {
    def matches(round: Round): Boolean
  }

  object Status {
    /** Active: open, as its Start/Stop button shows (`rounds.active`). */
    case object All extends Status("all", "rounds.filter.status.all") { def matches(r: Round) = true }
    case object Active extends Status("active", "rounds.filter.status.active") { def matches(r: Round) = r.active }
    case object Inactive extends Status("inactive", "rounds.filter.status.inactive") { def matches(r: Round) = !r.active }
    val values: Seq[Status] = Seq(All, Active, Inactive)
    def parse(s: String): Option[Status] = values.find(_.param == s)
  }

  /** A round's type: yes/no, or rated (any star rating, criteria rounds included: [[Round.isRated]]).
    * A comments-only round is neither: listed under All only.
    */
  sealed abstract class Kind(val param: String, val messageKey: String) {
    def matches(round: Round): Boolean
  }

  object Kind {
    case object All extends Kind("all", "rounds.filter.type.all") { def matches(r: Round) = true }
    case object Binary extends Kind("binary", "rounds.filter.type.binary") { def matches(r: Round) = r.isBinary }
    case object Rated extends Kind("rated", "rounds.filter.type.rated") { def matches(r: Round) = r.isRated }
    val values: Seq[Kind] = Seq(All, Binary, Rated)
    def parse(s: String): Option[Kind] = values.find(_.param == s)

    /** The round's type as its badge says it (the Type menu's own item), if yes/no or rated. */
    def of(round: Round): Option[Kind] = Seq(Binary, Rated).find(_.matches(round))
  }

  sealed abstract class Order(val param: String, val messageKey: String)

  object Order {
    case object Oldest extends Order("oldest", "rounds.order.oldest")
    case object Newest extends Order("newest", "rounds.order.newest")
    case object ActiveFirst extends Order("active", "rounds.order.active")
    val values: Seq[Order] = Seq(Oldest, Newest, ActiveFirst)
    def parse(s: String): Option[Order] = values.find(_.param == s)
  }

  /** From the values of the params (the first of each); any missing or unknown is the default. */
  def of(param: String => Option[String]): RoundsFilter =
    RoundsFilter(
      param(StatusParam).flatMap(Status.parse).getOrElse(Status.All),
      param(TypeParam).flatMap(Kind.parse).getOrElse(Kind.All),
      param(OrderParam).flatMap(Order.parse).getOrElse(Order.Oldest)
    )

  /** From the request's query string. */
  def fromRequest(request: RequestHeader): RoundsFilter = of(request.getQueryString)

  /** From a posted form's fields (the Start/Stop forms, the round form), else the query string. */
  def fromForm(request: play.api.mvc.Request[play.api.mvc.AnyContent]): RoundsFilter = {
    val form = request.body.asFormUrlEncoded.getOrElse(Map.empty)
    of(name => form.get(name).flatMap(_.headOption).orElse(request.getQueryString(name)))
  }
}

/** The Status, Type and Order menus as the navbar draws them (navbar.scala.html roundsMenus):
  * the current filter, the page each choice opens (`url`: the page with that filter), and the
  * rounds the viewer sees on the page, for the items' counts.
  */
final case class RoundsFilterBar(filter: RoundsFilter, url: RoundsFilter => String, rounds: Seq[Round]) {

  /** How many of the rounds a Status choice keeps, within the current type. */
  def count(status: RoundsFilter.Status): Int = rounds.count(r => status.matches(r) && filter.kind.matches(r))

  /** How many of the rounds a Type choice keeps, within the current status. */
  def count(kind: RoundsFilter.Kind): Int = rounds.count(r => kind.matches(r) && filter.status.matches(r))
}
