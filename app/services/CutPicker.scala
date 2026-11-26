package services

import db.scalikejdbc.Round
import org.intracer.wmua.{BinaryOrder, CategoryOperation}
import play.api.i18n.Messages
import play.api.libs.json._
import services.TopNCandidates.{Msg, Result, Scale}
import services.distribution.RoundDistributionHandler.TopNRanking

/** The round form's one cut control (regional winners plan §10.4, place 1; decided 2026-11-26): a top N
  * and "average file rating at least X" are the same cut, since files of the same score tie and a cut
  * never falls inside a tie. Each valid N ([[TopNCandidates.Result.isValid]]) is one score threshold
  * ([[threshold]]), so the control lists every valid cut as an "N / score" pair, the candidate cuts
  * ([[TopNCandidates]]) labelled with their reasons. A rated source's round keeps either the files
  * scoring at least the cut's score or the top N ([[CutPicker.Keep]]); a yes/no source's always keeps N.
  *
  * @param chosen  the N the form holds, valid or not
  * @param mixedScales the previous rounds' score ranges differ ("previous rounds on different scales")
  * @param changedSince the files ranked differ from those its stored N was chosen from (the previous
  *                     rounds' votes changed since): the form warns
  * @param keep    what a rated source's round keeps of the chosen cut
  * @param pick    a held value the form can't preselect (inside a tie, matching no file, a legacy round's
  *                two cuts): why, and the placeholder's posted value (the held N, so that a save of it is
  *                refused again rather than dropped; "" for a held threshold)
  * @param heldThreshold a legacy round's threshold the form holds: kept if it still takes the chosen
  *                cut's files ([[stored]]), so a re-apply that keeps the same cut doesn't rewrite it
  * @param links   each valid N's link to the previous round's rating view with the cut shown
  *                ([[CutPicker.viewable]]): empty where that view would not show the same cut
  */
final case class CutPicker(
    result: Result,
    ranking: TopNRanking,
    chosen: Option[Int],
    mixedScales: Boolean,
    changedSince: Boolean = false,
    keep: CutPicker.Keep = CutPicker.Keep.Default,
    pick: Option[CutPicker.Pick] = None,
    heldThreshold: Option[BigDecimal] = None,
    links: Map[Int, String] = Map.empty
) {

  def m: Int = result.m

  /** Ranked from yes/no previous rounds: by levels; the round keeps N. */
  def levels: Boolean = ranking.order.isDefined

  /** The control's cuts: every valid N, from 1 to all candidates (offered == allowed: exactly the values
    * the save accepts, [[TopNCandidates.Result.refusal]]). Never a value inside a tie.
    */
  def options: Seq[Int] = result.validUpTo(m)

  /** The candidate cuts, by N. */
  def candidateOptions: Seq[Int] = result.candidates.map(_.n)

  /** The valid cuts that are not candidates. */
  def otherOptions: Seq[Int] = {
    val candidates = candidateOptions.toSet
    options.filterNot(candidates)
  }

  /** The N chosen, if a valid value. */
  def chosenValid: Option[Int] = chosen.filter(result.isValid)

  /** Whether the round keeps the files scoring at least the cut's score (a rated source's choice). */
  def keepsThreshold: Boolean = !levels && keep == CutPicker.Keep.Threshold

  /** The score threshold of a valid N of a rated source: the shortest decimal (at most 4 places) at or
    * below file N's score and above file N + 1's ([[CutPicker.threshold]]); None for a yes/no source, or
    * where 4 places don't separate the two scores (that cut is kept as its N).
    */
  def threshold(n: Int): Option[BigDecimal] =
    Option.when(!levels && result.isValid(n))(CutPicker.threshold(result.scores(n - 1),
      Option.when(n < m)(result.scores(n)))).flatten

  /** The files a threshold keeps now: those scoring at least `x`, as the fill compares them
    * (`SelectMinAvgRating`: the score as a decimal, at least `x`). 0 for a yes/no source.
    */
  def countAtLeast(x: BigDecimal): Int =
    if (levels) 0
    else result.scores.indexWhere(s => BigDecimal.decimal(s) < x) match {
      case -1 => m
      case i  => i
    }

  /** A valid N's cut as the control and the rating view name it: "top 88 · score ≥ 8.0", a yes/no
    * source's "top 88 · 3 yes, 0 no" (the lowest level kept), or "top 88" without a threshold.
    */
  def cut(n: Int): Msg =
    ranking.order match {
      case Some(o) => CutPicker.levelCut(n, CutPicker.describe(o)(result.keys(n - 1)))
      case None    => CutPicker.scoreCut(n, threshold(n))
    }

  /** An option's text: its cut, and a candidate's reasons. */
  def optionText(n: Int)(implicit messages: Messages): String =
    CutPicker.text(cut(n)) + result.candidate(n).fold("")(c => " · " + c.reasons.map(CutPicker.text).mkString("; "))

  /** What the round stores of a valid N (decided 2026-11-26: never both): the top N, or the threshold
    * ([[heldThreshold]], a legacy round's, if it still keeps exactly these N files; else the cut's own).
    * A cut without a threshold is kept as its N.
    */
  def stored(n: Int): (Option[Int], Option[BigDecimal]) =
    if (!keepsThreshold) (Some(n), None)
    else heldThreshold.filter(x => countAtLeast(x) == n).orElse(threshold(n))
      .fold[(Option[Int], Option[BigDecimal])]((Some(n), None))(x => (None, Some(x)))

  /** What the round's settings log keeps of the choice of `n` (`topImagesBasis`): the candidates listed,
    * how N was chosen (a candidate cut, with its reasons, or the list), the files ranked, the key at N,
    * and, kept as a threshold, that threshold (the files it took when saved being `chosen`).
    */
  def basis(n: Int, kept: Option[BigDecimal] = None): JsObject = Json.obj(
    "chosen" -> n,
    "candidates" -> result.candidates.map(_.n),
    "how" -> (if (result.candidate(n).isDefined) CutPicker.HowCandidate else CutPicker.HowList),
    "reasons" -> result.candidate(n).fold(Seq.empty[JsValue])(_.reasons.map(CutPicker.toJson)),
    "files" -> m,
    "key" -> result.keys.lift(n - 1)) ++
    kept.fold(Json.obj())(x => Json.obj("keep" -> CutPicker.Keep.Threshold.param, "threshold" -> x))

  /** Whether the files ranked now differ from those a stored choice (`basis`) was made from: another
    * number of candidates, or another rank key at the N chosen.
    */
  def differsFrom(basis: JsObject): Boolean = {
    val chosen = (basis \ "chosen").asOpt[Int]
    (basis \ "files").asOpt[Int].exists(_ != m) ||
      chosen.exists(n => (basis \ "key").asOpt[Seq[Double]].exists(k => !result.keys.lift(n - 1).contains(k)))
  }

  /** The picker with the form's held cut preselected: a valid held N (kept as `keepPosted`, else as N), or
    * the cut a held threshold keeps now (kept as a threshold). Anything else is explained and needs a pick
    * ([[pick]]): an N inside a tie or above the candidates (its refusal), a threshold no file reaches, and
    * a legacy round's two cuts (a round keeps one now). A yes/no source has no threshold: one held is
    * ignored, as its fill ignores it.
    */
  def holding(held: Option[Int], heldRate: Option[BigDecimal], keepPosted: Option[CutPicker.Keep]): CutPicker =
    copy(heldThreshold = heldRate.orElse(heldThreshold).filter(_ => !levels)).holdingCut(held, heldRate.filter(_ => !levels),
      keepPosted)

  private def holdingCut(held: Option[Int], heldRate: Option[BigDecimal], keepPosted: Option[CutPicker.Keep]): CutPicker =
    (held, heldRate) match {
      case (Some(n), Some(x)) =>
        copy(chosen = None, keep = keepPosted.getOrElse(CutPicker.Keep.Default),
          pick = Some(CutPicker.Pick(Msg(CutPicker.HeldBothKey, Seq(n, CutPicker.fmtThreshold(x),
            math.min(n, countAtLeast(x)))), "")))
      case (Some(n), None) =>
        val kept = copy(chosen = Some(n), keep = keepPosted.getOrElse(CutPicker.Keep.Top))
        result.refusal(n).fold(kept)(msg => kept.copy(pick = Some(CutPicker.Pick(msg, n.toString))))
      case (None, Some(x)) =>
        val n = countAtLeast(x)
        if (n >= 1) copy(chosen = Some(n), keep = CutPicker.Keep.Threshold)
        else copy(chosen = None, keep = CutPicker.Keep.Threshold,
          pick = Some(CutPicker.Pick(Msg(CutPicker.HeldThresholdNoneKey, Seq(CutPicker.fmtThreshold(x))), "")))
      case (None, None) => copy(chosen = None, keep = keepPosted.getOrElse(CutPicker.Keep.Default))
    }
}

object CutPicker {

  /** What a rated source's round keeps of its cut: the files scoring at least its score (the default:
    * it can't fall inside a tie, so the job's tie check doesn't apply) or the top N.
    */
  sealed abstract class Keep(val param: String)

  object Keep {
    case object Threshold extends Keep("threshold")
    case object Top extends Keep("top")
    val Default: Keep = Threshold
    val all: Seq[Keep] = Seq(Threshold, Top)
    def parse(value: String): Option[Keep] = all.find(_.param == value)
  }

  /** A held value that needs a pick: why (shown above the control), and the placeholder's posted value. */
  final case class Pick(msg: Msg, value: String)

  /** The form's field of [[Keep]] (outside its mapping). */
  val KeepField = "cutKeep"

  /** The form's field of a held threshold kept while the control shows (a legacy round's: [[CutPicker.stored]]). */
  val HeldThresholdField = "cutHeldRate"

  /** The rating view's parameter of the cut shown (`controllers.RoundList.cutAt`). */
  val CutParam = "cut"

  /** The rating view's fragment of the cut's divider (file-anchor.js scrolls to it). */
  val CutFragment = "#cut"

  val HowCandidate = "candidate"
  val HowList = "list"

  val ScoreCutKey = "top.n.cut.score"
  val LevelCutKey = "top.n.cut.level"
  val TopCutKey = "top.n.cut.top"
  val HeldBothKey = "top.n.held.both"
  val HeldThresholdNoneKey = "top.n.held.threshold.none"

  /** The decimal places a threshold has at most: `round.prev_min_avg_rate`'s (DECIMAL(10,4), V116). */
  val ThresholdScale = 4

  /** The largest threshold stored: DECIMAL(10,4)'s. */
  val ThresholdMax: BigDecimal = BigDecimal("999999.9999")

  /** The threshold of a cut after a file scoring `at`, the next one scoring `next` (none after the
    * last): the shortest decimal (0 to [[ThresholdScale]] places) at most `at`, floored, and more than
    * `next`. None if there is none ([[ThresholdScale]] places don't separate the scores: averages of more
    * than about 100 votes per file) or it is above [[ThresholdMax]]. Scores compare as decimals of their
    * doubles, as the fill compares a file's score with the threshold.
    */
  def threshold(at: Double, next: Option[Double]): Option[BigDecimal] = {
    val score = BigDecimal.decimal(at)
    (0 to ThresholdScale).iterator
      .map(d => score.setScale(d, BigDecimal.RoundingMode.FLOOR))
      .find(x => next.forall(n => BigDecimal.decimal(n) < x))
      .filter(x => x >= 0 && x <= ThresholdMax)
  }

  /** A threshold as the control writes it: at least one decimal, a point. */
  def fmtThreshold(x: BigDecimal): String = {
    val plain = x.bigDecimal.stripTrailingZeros
    (if (plain.scale < 1) plain.setScale(1) else plain).toPlainString
  }

  /** A rated cut's name: "top n · score ≥ x", or "top n" without a threshold. */
  def scoreCut(n: Int, x: Option[BigDecimal]): Msg =
    x.fold(Msg(TopCutKey, Seq(n)))(t => Msg(ScoreCutKey, Seq(n, fmtThreshold(t))))

  /** A yes/no cut's name: "top n · " and its lowest level kept. */
  def levelCut(n: Int, level: Msg): Msg = Msg(LevelCutKey, Seq(n, level))

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
    * the main round's window, the form's `chosen` N.
    */
  def build(ranking: TopNRanking, ranges: Seq[Double], chosen: Option[Int]): CutPicker = {
    val scale: Scale = ranking.order.fold[Scale](TopNCandidates.Rated(ranges.maxOption.getOrElse(1.0)))(o =>
      TopNCandidates.Levels(describe(o)))
    val result = TopNCandidates.compute(ranking.keys, ranking.scores, scale, TopNCandidates.Window.main)
    CutPicker(result, ranking, chosen, mixedScales = ranges.distinct.sizeIs > 1)
  }

  /** Whether the previous round's rating view (`/byrate/round/:round/user/0/…`: all the jurors' votes,
    * GalleryController.byRate) would show the picker's cuts between the same files, by the round's
    * settings and the picker's ranking alone. The caller checks the data
    * ([[services.distribution.RoundDistributionHandler.cutViewData]]: the picker left no file of the view
    * out, so no deleted file with votes there, no conflict of interest's file, no disqualified file of a
    * re-apply) and the viewer (GalleryService.othersVotesVisible):
    *  - one previous round, rated or yes/no: the view is of one round;
    *  - no filter the view doesn't apply: region, monuments, category, rounds left out, megapixels,
    *    size, media type, special nomination, "selected by" and "rejected by" (the view lists all the
    *    round's files with votes, whatever this round's filters). "Leave out conflicts of interest" is on
    *    by default for a new round: it counts only if it leaves a file out (the data half);
    *  - the same order and tie key. A rated round's view ranks by its average under its rule (Rated,
    *    Assigned: `AverageRule.keySql`, exact for fewer than 30,000 jurors per file; Sum: the grade sum
    *    alone), the picker by its score (`RoundDistributionHandler.rowScore`: the same grade sum over the
    *    same denominator, a criteria round's vote rate being its criteria's sum in both): equal scores
    *    are equal keys in both, so a cut between two scores falls between the same files. A yes/no
    *    round's view ranks by the `BinaryOrder` its link names (`sort`), on the picker's values for each
    *    order (approvals, rejections, and their difference: `sum(s.rate)` of rates 1, 0 and -1). The
    *    order inside a tie differs (the view: monument and page id; the fill: the sum and page id),
    *    which a cut never splits.
    *
    * The threshold doesn't count: the picker ranks without it (it is the cut itself).
    */
  def viewable(round: Round, ranking: TopNRanking): Boolean =
    round.previousIds.sizeIs == 1 && ranking.prevRounds.sizeIs == 1 &&
      ranking.prevRounds.forall(p => p.isBinary || p.isRated) &&
      ranking.prevRounds.forall(_.isBinary) == ranking.order.isDefined &&
      round.regionIds.isEmpty && round.monumentIds.isEmpty &&
      round.categoryRule.operation == CategoryOperation.Unrestricted &&
      round.excludeRounds.isEmpty && round.minMpx.isEmpty && round.minImageSize.isEmpty &&
      round.mediaType.isEmpty && round.specialNomination.isEmpty && round.selectedByFilter.isEmpty &&
      round.rejectedByFilter.isEmpty

  /** The rating view's page with cut `n` of previous round `prevId` (all the jurors' votes, in the
    * picker's order: `sort` the yes/no order, else by rate), scrolled to the cut's divider.
    */
  def viewUrl(prevId: Long, order: Option[BinaryOrder], n: Int): String =
    controllers.routes.GalleryController.byRate(0,
      controllers.Pager.pageOf(n - 1, controllers.RoundList.pageSizeOf(controllers.GalleryView.ByRate)), "all", prevId).url +
      s"?sort=${order.fold(controllers.ListOrder.ByRate.param)(_.param)}&$CutParam=$n$CutFragment"

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
