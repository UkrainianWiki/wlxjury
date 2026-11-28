package controllers

import db.scalikejdbc.Round
import play.api.mvc.RequestHeader

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** The Status, Type, Track and Order menus of the Rounds page and the Round Statistics page (the
  * maintainer's decisions of 2026-11-18; the Track menu, round lineage slice A, 2026-11-25): which
  * of the rounds the viewer already sees are listed, and in what order, and whether the round tabs
  * are grouped by track (`group`, the Order menu's "Group by track"). Kept in the URL (`status`,
  * `type`, `track`, `order`, `group`), only where not the default, so a page with no params is as before (all
  * rounds, oldest first); links between the two pages carry them ([[addTo]]). An unknown value is
  * the default, never an error: a track the contest doesn't have too ([[withLineage]]).
  *
  * The track is a key of [[services.RoundLineage.Track]]; a round's track is the contest's lineage's
  * (`trackOf`, set by [[withLineage]]): without it, a filter by track keeps no round.
  *
  * It only narrows and orders a list it is given: the rounds a viewer may see are decided before
  * (the contest's rounds for its admins, those whose statistics they may view on the statistics
  * page), never here.
  */
final case class RoundsFilter(
    status: RoundsFilter.Status = RoundsFilter.Status.All,
    kind: RoundsFilter.Kind = RoundsFilter.Kind.All,
    order: RoundsFilter.Order = RoundsFilter.Order.Oldest,
    track: Option[String] = None,
    // the round tabs grouped by track (the Order menu's "Group by track"): None is the default, on
    // when the contest shows tracks ([[grouped]])
    group: Option[Boolean] = None,
    // each round's track key, from the contest's lineage ([[withLineage]]); not a choice of the viewer
    trackOf: Map[Long, String] = Map.empty
) {

  import RoundsFilter._

  def matches(round: Round): Boolean = status.matches(round) && kind.matches(round) && trackMatches(round)

  /** The round is on the chosen track (any, without one). */
  def trackMatches(round: Round): Boolean = track.forall(t => round.id.flatMap(trackOf.get).contains(t))

  /** The round is on that track. */
  def onTrack(round: Round, key: String): Boolean = round.id.flatMap(trackOf.get).contains(key)

  /** With the contest's tracks: each round's, and the chosen one only if the contest has it. */
  def withLineage(lineage: services.RoundLineage.Lineage): RoundsFilter =
    copy(track = track.filter(lineage.byKey(_).isDefined), trackOf = lineage.keys)

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
  def withTrack(t: Option[String]): RoundsFilter = copy(track = t)
  def withGroup(g: Option[Boolean]): RoundsFilter = copy(group = g)

  /** Whether the round tabs are grouped by track: as chosen, else when the contest shows tracks
    * (Lineage.showsTracks: two tracks or more) and one of them has two rounds or more (tracks of a
    * round each, named after it, group nothing); never without its lineage.
    */
  def grouped(lineage: Option[services.RoundLineage.Lineage]): Boolean =
    lineage.exists(l => group.getOrElse(l.showsTracks() && RoundsFilter.hasLongTrack(l)))

  def isDefault: Boolean = copy(trackOf = Map.empty) == RoundsFilter.Default

  /** The query params of the choices that aren't the default, in a fixed order. */
  def params: Seq[(String, String)] =
    Seq(
      Option.when(status != Status.All)(StatusParam -> status.param),
      Option.when(kind != Kind.All)(TypeParam -> kind.param),
      track.map(TrackParam -> _),
      Option.when(order != Order.Oldest)(OrderParam -> order.param),
      group.map(g => GroupParam -> (if (g) GroupTrack else GroupNone))
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
  val TrackParam = "track"
  val GroupParam = "group"
  val GroupTrack = "track"
  val GroupNone = "none"

  val Default: RoundsFilter = RoundsFilter()

  /** Whether one of the contest's tracks has two rounds or more, a track named after its rounds
    * (Lineage.selfNamedTrack) aside: grouping by track then groups something.
    */
  def hasLongTrack(lineage: services.RoundLineage.Lineage): Boolean =
    lineage.trackOf.values.filterNot(lineage.selfNamedTrack).groupMapReduce(identity)(_ => 1)(_ + _).values.exists(_ > 1)

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
      param(OrderParam).flatMap(Order.parse).getOrElse(Order.Oldest),
      param(TrackParam).map(_.trim).filter(_.nonEmpty),
      param(GroupParam).collect { case GroupTrack => true; case GroupNone => false }
    )

  /** From the request's query string. */
  def fromRequest(request: RequestHeader): RoundsFilter = of(request.getQueryString)

  /** From a posted form's fields (the Start/Stop forms, the round form), else the query string. */
  def fromForm(request: play.api.mvc.Request[play.api.mvc.AnyContent]): RoundsFilter = {
    val form = request.body.asFormUrlEncoded.getOrElse(Map.empty)
    of(name => form.get(name).flatMap(_.headOption).orElse(request.getQueryString(name)))
  }
}

/** The Status, Type, Track and Order menus as the navbar draws them (navbar.scala.html roundsMenus):
  * the current filter, the page each choice opens (`url`: the page with that filter), the rounds
  * the viewer sees on the page, for the items' counts, and the contest's lineage, for the Track menu
  * (none: no Track menu).
  */
final case class RoundsFilterBar(
    filter: RoundsFilter,
    url: RoundsFilter => String,
    rounds: Seq[Round],
    lineage: Option[services.RoundLineage.Lineage] = None,
    // the page shows round tabs (views.RoundTabs): its Order menu has "Group by track"
    tabs: Boolean = false,
    trackOnly: Boolean = false
) {

  /** The Order menu's "Group by track": on a page with round tabs, in a contest that shows tracks. */
  def groupShown: Boolean = tabs && lineage.exists(_.showsTracks())

  /** The round tabs grouped by track ([[RoundsFilter.grouped]]). */
  def grouped: Boolean = filter.grouped(lineage)

  /** How many of the rounds a Status choice keeps, within the current type and track. */
  def count(status: RoundsFilter.Status): Int =
    rounds.count(r => status.matches(r) && filter.kind.matches(r) && filter.trackMatches(r))

  /** How many of the rounds a Type choice keeps, within the current status and track. */
  def count(kind: RoundsFilter.Kind): Int =
    rounds.count(r => kind.matches(r) && filter.status.matches(r) && filter.trackMatches(r))

  /** How many of the rounds a Track choice keeps (its key; None: all), within the current status and type. */
  def count(track: Option[String]): Int =
    rounds.count(r => filter.status.matches(r) && filter.kind.matches(r) && track.forall(filter.onTrack(r, _)))

  /** The Track menu's tracks, in the lineage's order, those of the rounds listed that show chips: a
    * track named after its round that no other round continues (Lineage.selfNamedTrack) is left out,
    * as its chip is (the maintainer's decision for 26.11.17.2), its rounds still under All tracks;
    * unless it is the chosen one (an old link), which stays listed. The track shown as Main in a
    * contest without one (Lineage.shownAsMain) comes first, as Main does. Shown with two or more of
    * them, or a track chosen.
    */
  def tracks: Seq[services.RoundLineage.Track] = lineage.fold(Seq.empty[services.RoundLineage.Track]) { l =>
    val listed = l.tracksOf(rounds).filter(t => !l.selfNamedTrack(t) || filter.track.contains(t.key))
    val (main, rest) = listed.partition(l.shownAsMain.contains)
    main ++ rest
  }

  /** With two tracks or more among the rounds listed (the chips' rule, Lineage.showsTracks), or a track chosen. */
  def trackMenuShown: Boolean = lineage.exists(_.showsTracks(rounds)) || filter.track.isDefined
}
