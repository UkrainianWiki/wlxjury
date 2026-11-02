package org.intracer.wmua

import java.text.DecimalFormat
import java.util.Locale
import java.text.NumberFormat

import db.scalikejdbc.{Round, User}

case class ImageWithRating(
    image: Image,
    selection: Seq[Selection],
    countFromDb: Int = 0,
    approvalsFromDb: Option[Int] = None,
    rejectionsFromDb: Option[Int] = None
) extends Ordered[ImageWithRating] {

  val ownJuryRating = new OwnRating(
    selection.headOption.getOrElse(Selection(image.pageId, 0, 0, 0))
  )

  def unSelect(): Unit =
    ownJuryRating.unSelect()

  def select(): Unit =
    ownJuryRating.select()

  def isSelected: Boolean = ownJuryRating.isSelected

  def isRejected: Boolean = ownJuryRating.isRejected

  def isUnrated: Boolean = ownJuryRating.isUnrated

  def rate: Int = ownJuryRating.rate

  def rate_=(rate: Int): Unit = {
    ownJuryRating.rate = rate
  }

  /** The file's average rate: a rated round's [[rateSum]] over its jurors. A binary round's is
    * the legacy rule of a top-N from it (max(approvals − rejections, 0) over its assigned
    * jurors, `SelectTopByRating` without an order); its lists read [[approvals]] and
    * [[rejections]].
    */
  def totalRate(round: Round): Double =
    if (selection.size == 1 && selection.head.juryId != 0)
      selection.head.rate
    else if (ratedJurors(round) == 0)
      0.0
    else
      rateSum.toDouble / ratedJurors(round)

  /** A rated round's rate sum: its votes' rates, any below 0 as 0; of a grouped row (all the
    * jurors' votes together, one selection with their sum) that sum. Binary code counts
    * [[approvals]] and [[rejections]] instead: of a binary round's grouped row this is
    * max(approvals − rejections, 0), of its vote rows the approvals, or of the rows a
    * distribution's "selected by at least" reads (rate > 0, `SelectedAtLeast`) the approvals.
    */
  def rateSum: Int =
    selection.foldLeft(0)((acc, s) => acc + Math.max(s.rate, 0))

  /** A binary round's approvals of the file: its selections (rate 1). A grouped row (all the
    * jurors' votes together, one selection of juror 0 with their sum) has them only from a
    * query with `tally` (`SelectionQuery.tally`, a yes/no round's); one without fails here
    * rather than count its one selection. Vote rows are counted.
    */
  def approvals: Int = {
    require(approvalsFromDb.isDefined || !grouped, "grouped row without tally")
    approvalsFromDb.getOrElse(selection.count(_.rate > 0))
  }

  /** A binary round's rejections of the file (rate -1), from the query or counted as
    * [[approvals]] are.
    */
  def rejections: Int = {
    require(rejectionsFromDb.isDefined || !grouped, "grouped row without tally")
    rejectionsFromDb.getOrElse(selection.count(_.rate < 0))
  }

  /** A row of all the jurors' votes together: a selection of juror 0 holds their sum. */
  private def grouped: Boolean = selection.exists(_.juryId == 0)

  def jurors: Set[Long] = selection.map(s => s.juryId).toSet

  def jurorRate(juror: User): Option[Int] =
    selection.find(_.juryId == juror.getId).map(_.rate)

  def jurorRateStr(juror: User): String = jurorRate(juror).fold("")(_.toString)

  def ratedJurors(round: Round): Long =
    if (round.isBinary) {
      round.numberOfAssignedJurors
    } else if (
      selection.headOption.exists(_.juryId == 0) && !round.optionalRate
    )
      countFromDb
    else if (selection.size == 1 && selection.headOption.exists(_.juryId != 0))
      1
    else if (round.optionalRate)
      round.numberOfJurorsForAverageRate
    else selection.count(_.rate > 0)

  def rateString(round: Round): String = {
    if (round.isBinary) {
      approvals.toString
    } else {
      if (ratedJurors(round) < 2) rateSum.toString
      else
        s"${Formatter.fmt.format(totalRate(round))} ($rateSum / ${ratedJurors(round)})"
    }
  }

  def pageId: Long = image.pageId

  def title: String = image.title

  def compare(that: ImageWithRating): Int = (this.pageId - that.pageId).sign.toInt
}

object ImageWithRating {

  /** How a list of all of a round's votes (file list, CSV) orders and ranks its files: by
    * aggregates of each file's votes, as SQL over the file's selection rows `s` with their
    * directions (1 ascending, -1 descending), and their values of a file in memory. Files of
    * the same values tie, and share a rank range.
    */
  final case class RankKey(columns: Seq[(String, Int)], values: ImageWithRating => Seq[Int])

  object RankKey {

    /** A binary round's by `order`, a rated round's by the sum of its rates. */
    def of(round: Round, order: BinaryOrder = BinaryOrder.Default): RankKey =
      if (round.isBinary) RankKey(order.columns, file => order.values(file.approvals, file.rejections))
      else RankKey(Seq("sum(s.rate)" -> -1), file => Seq(file.rateSum))
  }

  /** The ranks of a list's files among all of them, from the number of its files of each key,
    * in the list's order: "4" for a file of its own key, "4-9" for six files that tie. A page
    * of the list is ranked without reading the rest.
    */
  final case class Ranks[K](counts: Seq[(K, Int)]) extends (K => String) {

    private val ranges: Map[K, (Int, Int)] =
      counts.zip(counts.scanLeft(0)(_ + _._2)).map { case ((key, n), before) => key -> (before + 1, n) }.toMap

    def apply(key: K): String = ranges.get(key).fold("") { case (start, n) =>
      if (n == 1) start.toString else start + "-" + (start + n - 1)
    }
  }

  object Ranks {

    /** The ranks of the keys of all of a list's files, in its order. */
    def of[K](orderedKeys: Seq[K]): Ranks[K] = {
      val sizes = orderedKeys.groupMapReduce(identity)(_ => 1)(_ + _)
      Ranks(orderedKeys.distinct.map(key => key -> sizes(key)))
    }
  }

  /** The ranks of all of a list's files, in its order by the round's [[RankKey]]. */
  def rankImages(
      orderedImages: Seq[ImageWithRating],
      round: Round,
      order: BinaryOrder = BinaryOrder.Default
  ): Seq[String] = {
    val keys = orderedImages.map(RankKey.of(round, order).values)
    keys.map(Ranks.of(keys))
  }

  def rank(orderedRates: Seq[Int]): Seq[String] = orderedRates.map(Ranks.of(orderedRates))
}

class Rating

class OneJuryRating(val selection: Selection) extends Rating {
  def isSelected: Boolean = selection.rate > 0

  def isRejected: Boolean = selection.rate < 0

  def isUnrated: Boolean = selection.rate == 0

  def rate: Int = selection.rate
}

class OwnRating(selection: Selection) extends OneJuryRating(selection) {

  def unSelect(): Unit = {
    selection.rate = -1
  }

  def select(): Unit = {
    selection.rate = 1
  }

  def rate_=(rate: Int): Unit = {
    selection.rate = rate
  }

}

class TotalRating extends Rating

object Formatter {

  def getFormatter: DecimalFormat = {
    val df =
      NumberFormat.getNumberInstance(Locale.US).asInstanceOf[DecimalFormat]
    df.applyPattern("0.00")
    df
  }

  val fmt = getFormatter
}
