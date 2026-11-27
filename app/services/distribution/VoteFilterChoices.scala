package services.distribution

import org.intracer.wmua.RejectedMode

import scala.collection.mutable

/** Counts before the cut, with every other filter held at its current value. */
final case class VoteFilterChoices(
    approvals: VoteFilterChoices.Thresholds,
    minimumRejections: VoteFilterChoices.Thresholds,
    maximumRejections: VoteFilterChoices.Thresholds
)

object VoteFilterChoices {
  /** Indexed by threshold, starting at zero (the number of files with no limit). */
  final case class Thresholds(atLeast: IndexedSeq[Int]) {
    def maximum: Int = atLeast.size - 1
    def count(n: Int): Int = atLeast.lift(n).getOrElse(0)
    def total: Int = count(0)
  }

  final case class Selection(approval: Option[Int], rejection: Option[Int], mode: RejectedMode) {
    def passesRejection(row: DistributionSource.RankRow): Boolean =
      rejection.forall(mode.passes(row.rejections, _))

    // The underlying yes/no ranking reads rows with an approval when that filter is set.
    def reads(row: DistributionSource.RankRow): Boolean =
      approval.forall(_ => row.approvals > 0) && passesRejection(row)
  }

  /** A file passes in any of its previous rounds. Keep the best approval count and both extremes
    * of the rejection count, using only rows that pass the other vote filter.
    */
  private[distribution] final class Counter(selection: Selection) {
    private val approvals = mutable.LongMap.empty[Int]
    private val rejections = mutable.LongMap.empty[(Int, Int)]

    def add(row: DistributionSource.RankRow): Boolean = {
      val approved = selection.approval.forall(_ <= row.approvals)
      val accepted = selection.passesRejection(row)
      if (accepted)
        approvals.update(row.pageId, approvals.get(row.pageId).fold(row.approvals)(_ max row.approvals))
      if (approved) {
        val n = row.rejections
        val limits = rejections.get(row.pageId).fold(n -> n) { case (min, max) => (min min n, max max n) }
        rejections.update(row.pageId, limits)
      }
      approved && accepted
    }

    def result(): VoteFilterChoices =
      VoteFilterChoices(thresholds(approvals.valuesIterator), thresholds(rejections.valuesIterator.map(_._1)),
        thresholds(rejections.valuesIterator.map(_._2)))
  }

  private def thresholds(values: Iterator[Int]): Thresholds = {
    val frequencies = mutable.ArrayBuffer(0)
    values.foreach { n =>
      while (frequencies.size <= n) frequencies += 0
      frequencies(n) += 1
    }
    for (n <- frequencies.size - 2 to 0 by -1)
      frequencies(n) += frequencies(n + 1)
    Thresholds(frequencies.toVector)
  }
}
