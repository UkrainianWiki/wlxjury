package views

import db.scalikejdbc.{ContestJuryJdbc, Round, User}
import play.api.i18n.Messages
import services.RoundLineage
import services.RoundLineage.{Kind, Lineage, Track}

/** A round's track as the pages show it (round lineage slice A, §8.1): the Track chip after a
  * round's name on the Rounds page, the round statistics and the galleries' round menu, and the
  * Track menu's items. The chip's text carries the meaning; its colour is one of a few
  * `--wlx-track-*` tokens (theme.css, both themes), Main its own, the others a stable hash of the
  * track's key, two tracks of one contest on the same token moved apart ([[colour]]).
  */
object TrackChip {

  /** The chip's colours: `wlx-track-1` … `wlx-track-N`, and `wlx-track-main`. */
  val Colours: Int = RoundLineage.ColourSlots

  /** A chip's longest text, in characters (labels cut by count, without font metrics: §2.3). */
  val MaxLength = 32

  /** The track's name: Main's in the page's language; a nomination's or media type's translated
    * if the messages have it; a region track's regions by name; else as the rule or label gives it.
    */
  def name(track: Track, lineage: Lineage)(implicit messages: Messages): String = track.kind match {
    case Kind.Main                    => messages("round.track.main")
    case Kind.Nomination | Kind.Media => if (messages.isDefinedAt(track.name)) messages(track.name) else track.name
    case Kind.Region =>
      track.name.split(",").toSeq.map(id => lineage.context.scheme.region(id, None).name).mkString(", ")
    case _ => track.name
  }

  /** The chip's text: the name cut at [[MaxLength]] characters, ending in "…". */
  def short(track: Track, lineage: Lineage)(implicit messages: Messages): String = {
    val full = name(track, lineage)
    if (full.codePointCount(0, full.length) <= MaxLength) full
    else full.substring(0, full.offsetByCodePoints(0, MaxLength - 1)).trim + "…"
  }

  /** The round's track, if its chip says something its name doesn't: none for a root whose track
    * is named after it (rule 7's last case, no category or media type), which would repeat its name.
    */
  def shown(round: Round, lineage: Lineage): Option[Track] =
    lineage.track(round).filterNot(t => t.kind == Kind.Name && t.name == round.description.trim)

  /** The chip's colour class: Main its own; any other track its slot in the contest
    * ([[RoundLineage.colourSlots]], computed once per lineage): the hash of its key, as before, unless
    * another track of the contest has that token, then the next free one. Every page builds the
    * lineage from all the contest's rounds, so a track has one colour on the Rounds page, the round
    * statistics, the galleries' round menu and the Track menu's swatch, and the same tracks the same
    * colours in another year. A track not in the lineage (none today): its hash.
    */
  def colour(track: Track, lineage: Lineage): String =
    if (track.isMain) "wlx-track-main"
    else s"wlx-track-${lineage.colours.getOrElse(track, RoundLineage.preferredSlot(track)) + 1}"

  /** The round's automatic track (its track without its own label), by name: the round form's
    * track field's help. None for a round not among `rounds` (its contest's).
    */
  def automatic(roundId: Long, rounds: Seq[Round])(implicit messages: Messages): Option[String] =
    rounds.find(_.id.contains(roundId)).flatMap { round =>
      ContestJuryJdbc.findById(round.contestId).flatMap { contest =>
        val lineage = RoundLineage.of(rounds.map(r => if (r.id == round.id) r.copy(track = None) else r), contest)
        lineage.track(roundId).map(name(_, lineage))
      }
    }

  /** The contest's lineage for the galleries' round menu: only for those who see its organiser
    * info (jurors' menus don't change, decision 16), from the menu's rounds, which for them are all
    * the contest's (GalleryService.viewerRounds); None for anyone else or another page.
    */
  def menuLineage(user: User, selected: Option[Round], rounds: Seq[Round]): Option[Lineage] =
    selected.filter(r => user != null && rounds.nonEmpty && user.canViewOrgInfo(r)).flatMap { r =>
      ContestJuryJdbc.findById(r.contestId).map(RoundLineage.of(rounds.filter(_.contestId == r.contestId), _))
    }
}
