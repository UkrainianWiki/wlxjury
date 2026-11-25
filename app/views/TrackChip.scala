package views

import db.scalikejdbc.{ContestJuryJdbc, Round, User}
import play.api.i18n.Messages
import services.RoundLineage
import services.RoundLineage.{Kind, Lineage, Track}

/** A round's track as the pages show it (round lineage slice A, §8.1): the Track chip after a
  * round's name on the Rounds page, the round statistics and the galleries' round menu, and the
  * Track menu's items. The chip's text carries the meaning; its colour is a stable hash of the
  * track's key onto a few `--wlx-track-*` tokens (theme.css, both themes), Main its own.
  */
object TrackChip {

  /** The chip's colours: `wlx-track-1` … `wlx-track-N`, and `wlx-track-main`. */
  val Colours = 6

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

  /** The chip's text: the name, if longer than [[MaxLength]] characters its start and its end with
    * "…" between (the whole in the chip's title), so a long name never shows only a beginning that
    * other tracks' names may share.
    */
  def short(track: Track, lineage: Lineage)(implicit messages: Messages): String = cut(name(track, lineage))

  /** `full` in [[MaxLength]] characters at most: up to its first third and the rest from its end,
    * "…" between, each cut at a word's edge where it has one.
    */
  private[views] def cut(full: String): String = {
    val length = full.codePointCount(0, full.length)
    if (length <= MaxLength) full
    else {
      val headEnd = full.offsetByCodePoints(0, (MaxLength - 1) / 3)
      val tailStart = full.offsetByCodePoints(0, length - (MaxLength - 1 - (MaxLength - 1) / 3))
      val head = full.substring(0, headEnd)
      val tail = full.substring(tailStart)
      val headWords = if (!full.charAt(headEnd).isWhitespace && head.trim.contains(' ')) head.substring(0, head.lastIndexOf(' ')) else head
      val tailWords = if (!full.charAt(tailStart - 1).isWhitespace && tail.trim.contains(' ')) tail.substring(tail.indexOf(' ')) else tail
      headWords.trim + "…" + tailWords.trim
    }
  }

  /** The round's track chip, if the pages show tracks (two tracks or more in the contest:
    * Lineage.chipsShown, the Track menu's rule), then for every round, a root named after itself
    * too; none in a contest with one track, where it would say nothing.
    */
  def shown(round: Round, lineage: Lineage): Option[Track] =
    if (lineage.chipsShown) lineage.track(round) else None

  /** The chip's colour class. */
  def colour(track: Track): String =
    if (track.isMain) "wlx-track-main" else s"wlx-track-${Math.floorMod(track.key.hashCode, Colours) + 1}"

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
