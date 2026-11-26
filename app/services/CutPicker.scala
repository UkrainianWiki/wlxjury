package services

import db.scalikejdbc.Round
import org.intracer.wmua.BinaryOrder
import play.api.i18n.Messages
import play.api.libs.json._
import services.TopNCandidates.{Msg, Result, Scale}
import services.distribution.DistributionSource.RankedFile
import services.distribution.RoundDistributionHandler.TopNRanking

/** The round form's cut picker (regional winners plan §10.4, place 1): the candidate cuts of a main
  * round's top N ([[TopNCandidates]]) over the files its distribution would rank now, the N list of
  * valid values only, and the cut gallery's files.
  *
  * @param chosen  the N the form holds, valid or not
  * @param shownTo the gallery's last place shown
  * @param mixedScales the previous rounds' score ranges differ ("previous rounds on different scales")
  * @param changedSince the files ranked differ from those its stored N was chosen from (the previous
  *                     rounds' votes changed since): the preview warns
  */
final case class CutPicker(
    result: Result,
    ranking: TopNRanking,
    chosen: Option[Int],
    shownTo: Int,
    mixedScales: Boolean,
    changedSince: Boolean = false
) {

  def m: Int = result.m

  /** Ranked from yes/no previous rounds: by levels. */
  def levels: Boolean = ranking.order.isDefined

  /** The gallery's files, from place 1 to [[shownTo]]. */
  def gallery: Seq[RankedFile] = ranking.files.take(shownTo)

  /** "Show more": the gallery's next end, if it has files past its end. */
  def showMore: Option[Int] = Option.when(shownTo < m && shownTo < CutPicker.MaxShown)(
    math.min(math.min(m, CutPicker.MaxShown), shownTo + CutPicker.ShowMoreStep))

  /** The N list: every valid value up to the gallery's end, the candidate cuts, all candidates (N = m)
    * and the N chosen, if valid. Never a value inside a tie.
    */
  def options: Seq[Int] =
    (result.validUpTo(shownTo) ++ result.candidates.map(_.n) ++ Seq(m).filter(_ > 0) ++
      chosen.filter(result.isValid)).distinct.sorted

  /** The N chosen, if a valid value. */
  def chosenValid: Option[Int] = chosen.filter(result.isValid)

  /** The previous round a gallery file ranks by (its row's). */
  def roundOf(file: RankedFile): Option[Round] = ranking.prevRounds.find(_.id.contains(file.row.roundId))

  /** The ties among the gallery's files, by place: runs of one key, the last one followed to its end
    * (one pass, however long a yes/no level is).
    */
  private lazy val galleryTies: Map[Int, TopNCandidates.Tie] = {
    val ties = Map.newBuilder[Int, TopNCandidates.Tie]
    var start = 1
    while (start <= shownTo) {
      var end = start
      while (end < m && result.keys(end) == result.keys(start - 1)) end += 1
      if (end > start) (start to math.min(end, shownTo)).foreach(i => ties += i -> TopNCandidates.Tie(start, end))
      start = end + 1
    }
    ties.result()
  }

  /** The tie a gallery file is in, if it ties with a neighbour. */
  def tieOf(place: Int): Option[TopNCandidates.Tie] = galleryTies.get(place)

  /** A gallery file's score in its round's terms: a yes/no level, a rated round's average with its
    * jurors ([[org.intracer.wmua.ImageWithRating.rateText]]), a criteria round's per criterion and sum.
    */
  def scoreText(place: Int, f: RankedFile)(implicit messages: Messages): String =
    ranking.order match {
      case Some(o) => CutPicker.text(CutPicker.describe(o)(result.keys(place - 1)))
      case None =>
        roundOf(f).fold(TopNCandidates.fmt(result.scores(place - 1))) { r =>
          if (r.hasCriteria && !r.singleCriterion) f.file.criteriaRateString(r) else f.file.rateText(r)
        }
    }

  /** The score at place `i` (1-based) in the reasons' terms: a number, or a yes/no level. */
  def scoreAt(i: Int): Any =
    ranking.order.fold[Any](TopNCandidates.fmt(result.scores(i - 1)))(o => CutPicker.describe(o)(result.keys(i - 1)))

  /** The preview of N: "N of m files, lowest included score s". */
  def preview(n: Int): Msg =
    if (levels) Msg(CutPicker.PreviewLevelKey, Seq(n, m, scoreAt(n)))
    else Msg(CutPicker.PreviewKey, Seq(n, m, scoreAt(n)))

  /** What the round's settings log keeps of the choice of `n` (`topImagesBasis`): the candidates listed, how N was chosen (a candidate cut, with its reasons, or the list) and the files
    * ranked.
    */
  def basis(n: Int): JsObject = Json.obj(
    "chosen" -> n,
    "candidates" -> result.candidates.map(_.n),
    "how" -> (if (result.candidate(n).isDefined) CutPicker.HowCandidate else CutPicker.HowList),
    "reasons" -> result.candidate(n).fold(Seq.empty[JsValue])(_.reasons.map(CutPicker.toJson)),
    "files" -> m,
    "key" -> result.keys.lift(n - 1))

  /** Whether the files ranked now differ from those a stored choice (`basis`) was made from: another
    * number of candidates, or another rank key at the N chosen.
    */
  def differsFrom(basis: JsObject): Boolean = {
    val chosen = (basis \ "chosen").asOpt[Int]
    (basis \ "files").asOpt[Int].exists(_ != m) ||
      chosen.exists(n => (basis \ "key").asOpt[Seq[Double]].exists(k => !result.keys.lift(n - 1).contains(k)))
  }
}

object CutPicker {

  /** The gallery's furthest end ("Show more" stops there). */
  val MaxShown = 1000

  /** "Show more" adds this many places. */
  val ShowMoreStep = 50

  val PreviewKey = "top.n.preview"
  val PreviewLevelKey = "top.n.preview.level"
  val HowCandidate = "candidate"
  val HowList = "list"

  /** A yes/no level's text, by the order's values of a file's key. */
  def describe(order: BinaryOrder)(key: Seq[Double]): Msg = {
    val v = key.map(_.toInt)
    order match {
      case BinaryOrder.ApprovalsThenRejections => Msg("top.n.level.approvals", Seq(v.headOption.getOrElse(0), v.lift(1).getOrElse(0)))
      case BinaryOrder.Difference              => Msg("top.n.level.difference", Seq(v.headOption.getOrElse(0)))
      case BinaryOrder.ApprovalsOnly           => Msg("top.n.level.yes", Seq(v.headOption.getOrElse(0)))
    }
  }

  /** The largest score of a rated previous round's rank key, from 0 (regional winners plan §10.4,
    * "the score range"): its highest rate, × its criteria (a vote's rate is their sum), × its jurors
    * per file (`jurorsPerFile`) in a round scored by the sum.
    */
  def range(p: Round, jurorsPerFile: => Long): Double = {
    val vote = p.maxRate.toDouble * (if (p.hasCriteria) p.criteriaCount.max(1) else 1)
    if (p.averageRule == org.intracer.wmua.AverageRule.Sum) vote * math.max(1L, jurorsPerFile) else vote
  }

  /** The picker of a ranking: its scale (yes/no levels, or the largest of the rated rounds' ranges),
    * the main round's window, the gallery to `showTo` (at least the window's end plus 5).
    */
  def build(ranking: TopNRanking, ranges: Seq[Double], chosen: Option[Int], showTo: Option[Int]): CutPicker = {
    val scale: Scale = ranking.order.fold[Scale](TopNCandidates.Rated(ranges.maxOption.getOrElse(1.0)))(o =>
      TopNCandidates.Levels(describe(o)))
    val result = TopNCandidates.compute(ranking.keys, ranking.scores, scale, TopNCandidates.Window.main)
    val shown = math.min(ranking.files.size, math.max(result.galleryEnd, showTo.getOrElse(0)))
    CutPicker(result, ranking, chosen, shown, mixedScales = ranges.distinct.sizeIs > 1)
  }

  /** The files kept whole for a gallery shown to `showTo`. */
  def keep(showTo: Option[Int]): Int =
    math.min(MaxShown, math.max(TopNCandidates.Window.main.hi + TopNCandidates.GalleryExtra, showTo.getOrElse(0)))

  /** A message's text, its message arguments first. */
  def text(msg: Msg)(implicit messages: Messages): String =
    messages(msg.key, msg.args.map {
      case m: Msg => text(m)
      case other  => other
    }: _*)

  /** A message as the settings log keeps it. */
  def toJson(msg: Msg): JsValue = Json.obj("key" -> msg.key, "args" -> msg.args.map {
    case m: Msg    => toJson(m)
    case i: Int    => JsNumber(i)
    case l: Long   => JsNumber(l)
    case d: Double => JsNumber(d)
    case other     => JsString(other.toString)
  })

  /** A message from the settings log, if it reads as one. */
  def fromJson(v: JsValue): Option[Msg] = for {
    key <- (v \ "key").asOpt[String]
    args = (v \ "args").asOpt[Seq[JsValue]].getOrElse(Nil).map {
      case o: JsObject => fromJson(o).getOrElse("")
      case JsNumber(n) => if (n.isValidInt) n.toInt else n.toDouble
      case JsString(s) => s
      case other       => other.toString
    }
  } yield Msg(key, args)
}
