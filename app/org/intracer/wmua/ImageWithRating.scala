package org.intracer.wmua

import java.text.DecimalFormat
import java.util.Locale
import java.text.NumberFormat

import db.scalikejdbc.{Round, User}

case class ImageWithRating(
    image: Image,
    selection: Seq[Selection],
    countFromDb: Int = 0,
    rank: Option[Int] = None,
    rank2: Option[Int] = None,
    approvalsFromDb: Option[Int] = None
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

  def rankStr: String = (
    for (r1 <- rank; r2 <- rank2)
      yield if (r1 != r2) s"$r1-$r2." else s"$r1."
  ).orElse(
    for (r1 <- rank)
      yield s"$r1."
  ).getOrElse("")

  def totalRate(round: Round): Double =
    if (selection.size == 1 && selection.head.juryId != 0)
      selection.head.rate
    else if (ratedJurors(round) == 0)
      0.0
    else
      rateSum.toDouble / ratedJurors(round)

  def rateSum: Int =
    selection.foldLeft(0)((acc, s) => acc + Math.max(s.rate, 0))

  /** A binary round's approvals of the file: its selections (rate 1), counted by the query
    * of a list of all the jurors' votes together, whose one selection per file has their sum.
    */
  def approvals: Int = approvalsFromDb.getOrElse(selection.count(_.rate > 0))

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

  def rankImages(
      orderedImages: Seq[ImageWithRating],
      round: Round
  ): Seq[String] = {
    rank(orderedImages.map(_.rateSum))
  }

  def rank(orderedRates: Seq[Int]): Seq[String] = {

    val sizeByRate = orderedRates.groupBy(identity).view.mapValues(_.size)
    val startByRate = sizeByRate.keys.map { rate =>
      rate -> (orderedRates.indexOf(rate) + 1)
    }.toMap

    orderedRates.map { rate =>
      val (start, size) = (startByRate(rate), sizeByRate(rate))
      if (size == 1)
        start.toString
      else
        start + "-" + (start + size - 1)
    }
  }
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
