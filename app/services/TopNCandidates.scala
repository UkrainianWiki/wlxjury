package services

import scala.collection.mutable

/** The cut picker's candidate cuts (regional winners plan §10.4, "SN"): pure, from a ranked list of
  * eligible files to the valid values of a top N and the candidate cuts, each with its reasons. The
  * list is ranked by the fill's own rank key (`DistributionSource.rank`): files of the same key tie,
  * and a cut never falls inside a tie, so the fill never has to cut one by page id.
  *
  *  - '''Valid values:''' N = k (1 ≤ k < m) where file k and file k + 1 differ in key, and N = m (all
  *    candidates); never N = 0. The round form's N list offers only these, and a save refuses others.
  *  - '''Candidate cuts''' in a window `[lo, hi]` ([[Window]]): C1 a significant drop, C2 close to the
  *    best (regions only), C3 all candidates, C4 around the target (when C1 finds none), C5 the
  *    boundaries of a tie at the window's edge; for yes/no sources, every level boundary in the window
  *    instead of C1, C2 and C4. At most [[MaxCandidates]], by N, one N with all its reasons. Nothing is
  *    preselected: the admin chooses.
  */
object TopNCandidates {

  /** A message with its arguments; an argument may be a message itself (a yes/no level's text). */
  final case class Msg(key: String, args: Seq[Any] = Nil)

  /** How scores compare. */
  sealed trait Scale

  /** Rated sources: numeric scores, the largest the rank key can give being `range` (from 0), for the
    * 5% tests of C1 and C2.
    */
  final case class Rated(range: Double) extends Scale

  /** Yes/no sources: each distinct key is a level, named by `describe` (of a file's key). */
  final case class Levels(describe: Seq[Double] => Msg) extends Scale

  /** The window of N the candidate cuts are looked for in: a main round's ([[Window.main]]) or a
    * region's ([[Window.region]]).
    */
  final case class Window(lo: Int, hi: Int, region: Boolean = false, target: Option[Int] = None) {
    /** What C4 and the level boundaries are nearest: the target, else the window's middle. */
    def centre: Double = target.fold((lo + hi) / 2.0)(_.toDouble)
  }

  object Window {

    /** A main round's: with a target T, `[⌈0.8 T⌉, ⌊1.25 T⌋]`; without, the first 100 places. */
    def main(target: Option[Int]): Window = target.filter(_ > 0) match {
      case Some(t) => Window(math.max(1, math.ceil(0.8 * t).toInt), math.max(1, math.floor(1.25 * t).toInt), target = Some(t))
      case None    => Window(1, MainWithoutTarget)
    }

    /** A region's of `m` candidates (the defaults of question 2): `lo = min(3, m)`, `hi = min(15,
      * max(lo, ⌈m / 3⌉))`.
      */
    def region(m: Int): Window = {
      val lo = math.min(3, m)
      Window(lo, math.min(15, math.max(lo, math.ceil(m / 3.0).toInt)), region = true)
    }
  }

  /** A main round's window without a target: places 1 to 100. */
  val MainWithoutTarget = 100

  /** The candidate cuts listed at most. */
  val MaxCandidates = 6

  /** A region's candidate cuts go no higher (C2, C3 and C5 may list one above its window's end). */
  val RegionMax = 25

  /** The gallery shows the window's end and this many files more. */
  val GalleryExtra = 5

  /** The share of the score range a drop (C1) or the distance from the best (C2) is measured in. */
  val RangeShare = 0.05

  /** A candidate cut: N and its reasons, in the order C1–C5. */
  final case class Candidate(n: Int, reasons: Seq[Msg])

  /** A tie: files `from` to `to` (1-based, `from < to`) of the same key. */
  final case class Tie(from: Int, to: Int) {
    /** The cuts around it: before it (none at 0) and after it. */
    def boundaries: Seq[Int] = Seq(from - 1, to).filter(_ > 0)
  }

  /** The candidate cuts of a ranked list, and its valid values.
    *
    * @param m          the eligible candidates
    * @param window     as given, before it is clipped to m
    * @param candidates the candidate cuts, by N
    */
  final case class Result(keys: IndexedSeq[Seq[Double]], scores: IndexedSeq[Double], window: Window,
      candidates: Seq[Candidate]) {

    def m: Int = keys.size

    /** Whether a top N of `n` is a valid value: a cut between two keys, or all candidates. */
    def isValid(n: Int): Boolean = n >= 1 && n <= m && (n == m || keys(n - 1) != keys(n))

    /** The valid values from 1 to `upTo` (and m, if not above it). */
    def validUpTo(upTo: Int): Seq[Int] = (1 to math.min(upTo, m)).filter(isValid)

    /** The tie N falls inside, if any: N files take only part of it. */
    def tieAt(n: Int): Option[Tie] =
      if (n < 1 || n >= m || keys(n - 1) != keys(n)) None
      else {
        var from = n
        while (from > 1 && keys(from - 2) == keys(n - 1)) from -= 1
        var to = n + 1
        while (to < m && keys(to) == keys(n - 1)) to += 1
        Some(Tie(from, to))
      }

    /** The tie file `i` (1-based) is in, if it ties with a neighbour. */
    def tieOf(i: Int): Option[Tie] =
      if (i > 1 && keys(i - 2) == keys(i - 1)) tieAt(i - 1)
      else if (i < m && keys(i - 1) == keys(i)) tieAt(i)
      else None

    /** The candidate of N, if listed. */
    def candidate(n: Int): Option[Candidate] = candidates.find(_.n == n)

    /** Why a posted N is refused, as a message: none for a valid value. */
    def refusal(n: Int): Option[Msg] =
      if (m == 0) Some(Msg(NoCandidatesKey))
      else if (n > m) Some(Msg(AboveAllKey, Seq(n, m)))
      else if (n < 1) Some(Msg("error.min", Seq(1)))
      else tieAt(n).map { tie =>
        if (tie.from - 1 > 0) Msg(InTieKey, Seq(n, tie.from, tie.to, tie.from - 1, tie.to))
        else Msg(InTieUpperKey, Seq(n, tie.from, tie.to, tie.to))
      }

    /** The gallery's default end: the window's end plus [[GalleryExtra]], at least the largest
      * candidate a region lists ("all candidates"), at most m.
      */
    def galleryEnd: Int = {
      val hi = math.min(window.hi, m)
      val regionAll = if (window.region) candidates.map(_.n).maxOption.getOrElse(0) else 0
      math.min(m, math.max(hi + GalleryExtra, regionAll))
    }
  }

  // message keys of the reasons (each without "N = n", which the page puts before them)
  val DropKey = "top.n.reason.drop"
  val LargestDropKey = "top.n.reason.drop.largest"
  val CloseToBestKey = "top.n.reason.close"
  val AllKey = "top.n.reason.all"
  val NoClearDropKey = "top.n.reason.no.drop"
  val TieIncludedKey = "top.n.reason.tie.includes"
  val TieBeforeKey = "top.n.reason.tie.before"
  val LevelKey = "top.n.reason.level"
  // refusals of a posted N
  val InTieKey = "top.n.in.tie"
  val InTieUpperKey = "top.n.in.tie.upper"
  val AboveAllKey = "top.n.above.all"
  val NoCandidatesKey = "top.n.no.candidates"

  /** A score as the reasons write it: at most two decimals, a point. */
  def fmt(score: Double): String =
    BigDecimal(score).setScale(2, BigDecimal.RoundingMode.HALF_UP).bigDecimal.stripTrailingZeros.toPlainString match {
      case "-0" => "0"
      case s    => s
    }

  /** The candidate cuts of the files ranked by `keys` (best first; equal keys tie), each file's numeric
    * score in `scores` (rated: the rank key's first value; yes/no: unused), in `window`.
    */
  def compute(keys: IndexedSeq[Seq[Double]], scores: IndexedSeq[Double], scale: Scale, window: Window): Result = {
    require(keys.size == scores.size, "a score for each key")
    val empty = Result(keys, scores, window, Nil)
    val m = keys.size
    if (m == 0) return empty

    val lo = math.min(window.lo, m)
    val hi = math.min(math.max(window.hi, lo), m)
    // 1-based: file i's key and score
    def key(i: Int): Seq[Double] = keys(i - 1)
    def score(i: Int): Double = scores(i - 1)
    // a cut after file n, n < m: between two keys
    def cut(n: Int): Boolean = n >= 1 && n < m && key(n) != key(n + 1)
    val inWindow = (lo to hi).filter(n => cut(n) || n == m)
    val cap = if (window.region) RegionMax else Int.MaxValue

    // (N, reason, priority: lower is kept first when more than MaxCandidates are found)
    val found = mutable.ArrayBuffer.empty[(Int, Int, Msg, Double)] // (n, order C1..C5, reason, priority)

    // C3, all candidates
    if ((window.region && m <= RegionMax) || (!window.region && m <= hi))
      found += ((m, 3, Msg(AllKey, Seq(m)), 0.0))

    // C5, the boundaries of a tie at lo or hi (also one over the whole window), even outside it
    val edgeTies = Seq(lo, hi).distinct.flatMap(n => empty.tieAt(n)).distinct
    for (tie <- edgeTies) {
      val label: Either[String, Msg] = scale match {
        case Rated(_)         => Left(fmt(score(tie.from)))
        case Levels(describe) => Right(describe(key(tie.from)))
      }
      val arg: Any = label.fold[Any](s => s, m => m)
      if (tie.from - 1 > 0) found += ((tie.from - 1, 5, Msg(TieBeforeKey, Seq(arg, tie.from, tie.to)), 1.0))
      found += ((tie.to, 5, Msg(TieIncludedKey, Seq(arg, tie.from, tie.to)), 1.0))
    }

    scale match {
      case Rated(range) =>
        val cuts = inWindow.filter(_ < m)
        val gaps = cuts.map(n => n -> (score(n) - score(n + 1)))
        // ties are no cut positions; a cut between equal scores (the sum decides) has no drop
        val positive = gaps.filter(_._2 > 0)
        val median = if (positive.sizeIs >= 3) {
          val sorted = positive.map(_._2).sorted
          val k = sorted.size
          Some(if (k % 2 == 1) sorted(k / 2) else (sorted(k / 2 - 1) + sorted(k / 2)) / 2)
        } else None
        val drops = positive.filter { case (_, g) => g >= RangeShare * range && median.forall(md => g >= 2 * md) }
        val largest = drops.maxByOption(_._2).map(_._1)
        for ((n, g) <- drops)
          found += ((n, 1, Msg(if (largest.contains(n)) LargestDropKey else DropKey, Seq(fmt(score(n)), fmt(score(n + 1)))),
            2.0 + 1.0 / (1.0 + g)))

        // C2 (regions): the files within 5% of the range of the best, moved to the end of a tie
        if (window.region) {
          val near = (1 to m).takeWhile(i => score(1) - score(i) <= RangeShare * range + 1e-9).size
          val n = empty.tieAt(near).fold(near)(_.to)
          if (n >= 2 && n <= RegionMax)
            found += ((n, 2, Msg(CloseToBestKey, Seq(n, fmt(RangeShare * range), fmt(score(1)))), 3.0))
        }

        // C4: no clear drop, the cuts nearest the target (or the middle), one on each side
        if (drops.isEmpty && cuts.nonEmpty) {
          val c = window.centre
          val below = cuts.filter(_ <= c).maxOption
          val above = cuts.filter(_ > c).minOption
          for (n <- (below.toSeq ++ above.toSeq).distinct)
            found += ((n, 4, Msg(NoClearDropKey, Seq(lo, hi, fmt(score(lo)), fmt(score(hi)))), 4.0))
        }

      case Levels(describe) =>
        // every level boundary in the window, those nearest the target (or the middle) first
        for (n <- inWindow if n < m) {
          val next = (n + 1 to m).takeWhile(i => key(i) == key(n + 1)).size
          found += ((n, 1, Msg(LevelKey, Seq(describe(key(n)), next, describe(key(n + 1)))),
            2.0 + math.abs(n - window.centre) / (m + 1.0)))
        }
    }

    val byN = found.filter { case (n, _, _, _) => n >= 1 && n <= cap }.groupBy(_._1).toSeq.map { case (n, rs) =>
      val reasons = rs.sortBy(_._2).map(_._3).distinct.toSeq
      (Candidate(n, reasons), rs.map(_._4).min)
    }
    val kept = byN.sortBy { case (c, priority) => (priority, c.n) }.take(MaxCandidates).map(_._1)
    Result(keys, scores, window, kept.sortBy(_.n))
  }
}
