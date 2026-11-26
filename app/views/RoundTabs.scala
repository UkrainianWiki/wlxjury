package views

import db.scalikejdbc.Round
import services.RoundLineage.{Lineage, Track}

/** A page's round tabs (snippets/roundTabs): the one round bar of the round statistics and the
  * contest's authors pages (the maintainer's decision for 26.11.18). Compact: a tab is its type
  * icons and its name, never a Track chip; its track is in its tooltip. Grouped by track when
  * `grouped` (the Order menu's "Group by track", on by default where the contest shows tracks:
  * RoundsFilter.grouped): each track's name once, as a small label before its tabs, the tracks in
  * the lineage's order (Main, the tracks that feed it as they join, the others), the rounds in the
  * page's order within each.
  *
  * @param id      the bar's element id (`round-stat-rounds`, `authors-rounds`)
  * @param rounds  the rounds, in the page's order
  * @param current the round the page shows, if one
  * @param url     a round's tab's link
  * @param icons   its type icons before a round's name (snippets/roundIcon), where it has a type
  * @param outside a round shown although the page's filter leaves it out (the current one opened
  *                with such a filter): marked, with a tooltip
  */
final case class RoundTabs(
    id: String,
    rounds: Seq[Round],
    current: Option[Long],
    url: Round => String,
    lineage: Option[Lineage],
    grouped: Boolean,
    icons: Boolean = true,
    outside: Round => Boolean = _ => false
) {

  /** The tabs' groups: by track in the lineage's order when grouped, else one group without a
    * label. A round alone on a track named after it (Lineage.selfNamed), whose label would only
    * repeat its name, goes to a last group without a label, as a round without a track (none today).
    */
  def groups: Seq[(Option[Track], Seq[Round])] = lineage.filter(_ => grouped) match {
    case Some(l) =>
      def labelled(r: Round) = l.track(r).filterNot(_ => l.selfNamed(r))
      val byTrack = l.tracks.map(t => Some(t) -> rounds.filter(labelled(_).contains(t))).filter(_._2.nonEmpty)
      val rest = rounds.filter(labelled(_).isEmpty)
      byTrack ++ Option.when(rest.nonEmpty)(None -> rest)
    case None => Seq(None -> rounds)
  }

  def isCurrent(round: Round): Boolean = current.exists(round.id.contains)

  /** A round's track for its tooltip: where the contest shows tracks (Lineage.chipsShown), not
    * for a track of one round named after it, which would only repeat its name.
    */
  def track(round: Round): Option[Track] =
    lineage.filter(_.chipsShown).flatMap(l => l.track(round).filterNot(_ => l.selfNamed(round)))
}
