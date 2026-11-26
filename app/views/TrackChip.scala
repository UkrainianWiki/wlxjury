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

  /** The track's name: Main's in the page's language, as for the track shown as Main in a contest
    * without one (Lineage.shownAsMain); a nomination's or media type's translated if the messages
    * have it; a region track's regions by name; else as the rule or label gives it.
    */
  def name(track: Track, lineage: Lineage)(implicit messages: Messages): String =
    if (lineage.shownAsMain.contains(track)) messages("round.track.main") else ownName(track, lineage)

  /** The track's own name ([[name]] without Lineage.shownAsMain): what the round form's automatic
    * track says, so that an admin copying it into the label never makes a real Main of it.
    */
  private[views] def ownName(track: Track, lineage: Lineage)(implicit messages: Messages): String = track.kind match {
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
  def short(track: Track, lineage: Lineage)(implicit messages: Messages): String = {
    val full = name(track, lineage)
    val others = lineage.tracks.filter(_ != track).map(name(_, lineage)).filter(_ != full)
    distinct(full, others)
  }

  /** `full`'s chip text, told apart from `others`' (the contest's other tracks' names): [[cut]];
    * where another name would read the same, the start cut at [[MaxLength]] ("Round 1 : Pre
    * Selection / Cycle 1 : Pré-séléction" and "Round 1 : Pre-selection / …" both cut to "Round
    * 1 :…1 : Pré-séléction", WLE Canada 2025); where that is the same too, the part around the first
    * character they differ at, "…" on each cut side.
    */
  private[views] def distinct(full: String, others: Seq[String]): String = {
    def clashes(text: String, of: String => String) = others.exists(o => of(o) == text)
    val middle = cut(full)
    if (!clashes(middle, cut)) middle
    else {
      val start = prefix(full)
      if (!clashes(start, prefix)) start
      else {
        val differ = others.filter(o => prefix(o) == start).map(o => full.zip(o).takeWhile { case (a, b) => a == b }.size).min
        window(full, differ)
      }
    }
  }

  /** `full`'s first [[MaxLength]] - 1 characters and "…", or `full` if it fits. */
  private[views] def prefix(full: String): String =
    if (full.codePointCount(0, full.length) <= MaxLength) full
    else full.substring(0, full.offsetByCodePoints(0, MaxLength - 1)).trim + "…"

  /** About [[MaxLength]] characters of `full` around its character `at`, "…" where cut. */
  private def window(full: String, at: Int): String = {
    val length = full.codePointCount(0, full.length)
    val atCp = full.codePointCount(0, at.min(full.length))
    val from = (atCp - MaxLength / 3).max(0).min((length - (MaxLength - 2)).max(0))
    val to = (from + MaxLength - 2).min(length)
    (if (from > 0) "…" else "") + full.substring(full.offsetByCodePoints(0, from), full.offsetByCodePoints(0, to)).trim +
      (if (to < length) "…" else "")
  }

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

  /** The round's track chip, if the pages show tracks (two tracks or more in the contest that say
    * more than a round's own name: Lineage.chipsShown, the Track menu's rule), then for every
    * round, a first round named after itself too when later rounds are on its track; none for a
    * round alone on a track named after it (Lineage.selfNamed), which would only repeat its name.
    */
  def shown(round: Round, lineage: Lineage): Option[Track] =
    if (lineage.chipsShown && !lineage.selfNamed(round)) lineage.track(round) else None

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

  /** The round's automatic track (its track without its own label), by its own name ([[ownName]]):
    * the round form's track field's help. None for a round not among `rounds` (its contest's).
    */
  def automatic(roundId: Long, rounds: Seq[Round])(implicit messages: Messages): Option[String] =
    rounds.find(_.id.contains(roundId)).flatMap { round =>
      ContestJuryJdbc.findById(round.contestId).flatMap { contest =>
        val lineage = RoundLineage.of(rounds.map(r => if (r.id == round.id) r.copy(track = None) else r), contest)
        // its own name, never the one shown as Main: typed as a label, Main would split the track
        lineage.track(roundId).map(ownName(_, lineage))
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
