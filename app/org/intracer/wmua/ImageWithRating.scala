package org.intracer.wmua

import java.text.DecimalFormat
import java.util.Locale
import java.text.NumberFormat

import db.scalikejdbc.{Round, User}

case class ImageWithRating(
    image: Image,
    votes: Seq[Vote],
    countFromDb: Int = 0,
    approvalsFromDb: Option[Int] = None,
    rejectionsFromDb: Option[Int] = None
) extends Ordered[ImageWithRating] {

  val ownJuryRating = new OwnRating(
    votes.headOption.getOrElse(Vote(image.pageId, 0, 0, 0))
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

  /** The file's score: a rated round's [[rateSum]] over [[ratedJurors]], its round's
    * [[AverageRule]] (the sum itself for [[AverageRule.Sum]]); one juror's own vote, its rate. A binary round's is the legacy rule of a
    * top-N from it (max(approvals − rejections, 0) over its assigned jurors,
    * `SelectTopByRating` without an order); its lists read [[approvals]] and [[rejections]].
    */
  def totalRate(round: Round): Double =
    if (ownVote)
      votes.head.rate
    else if (ratedJurors(round) == 0)
      0.0
    else
      rateSum.toDouble / ratedJurors(round)

  /** A rated round's rate sum: its votes' rates, any below 0 as 0; of a grouped row (all the
    * jurors' votes together, one vote with their sum) that sum. Binary code counts
    * [[approvals]] and [[rejections]] instead: of a binary round's grouped row this is
    * max(approvals − rejections, 0), of its vote rows the approvals, or of the rows a
    * distribution's "selected by at least" reads (rate > 0, `SelectedAtLeast`) the approvals.
    */
  def rateSum: Int =
    votes.foldLeft(0)((acc, s) => acc + Math.max(s.rate, 0))

  /** A binary round's approvals of the file: its selections (rate 1). A grouped row (all the
    * jurors' votes together, one vote of juror 0 with their sum) has them only from a
    * query with `tally` (`SelectionQuery.tally`, a yes/no round's); one without fails here
    * rather than count its one vote. Vote rows are counted.
    */
  def approvals: Int = {
    require(approvalsFromDb.isDefined || !grouped, "grouped row without tally")
    approvalsFromDb.getOrElse(votes.count(_.rate > 0))
  }

  /** A binary round's rejections of the file (rate -1), from the query or counted as
    * [[approvals]] are.
    */
  def rejections: Int = {
    require(rejectionsFromDb.isDefined || !grouped, "grouped row without tally")
    rejectionsFromDb.getOrElse(votes.count(_.rate < 0))
  }

  /** A row of all the jurors' votes together: a vote of juror 0 holds their sum. */
  private def grouped: Boolean = votes.exists(_.juryId == 0)

  def jurors: Set[Long] = votes.map(s => s.juryId).toSet

  def jurorRate(juror: User): Option[Int] =
    votes.find(_.juryId == juror.getId).map(_.rate)

  def jurorRateStr(juror: User): String = jurorRate(juror).fold("")(_.toString)

  /** One juror's vote of the file: a juror's own list (not a grouped row). */
  private def ownVote: Boolean = votes.size == 1 && votes.head.juryId != 0

  /** The jurors the file is assigned to: its vote rows, of a grouped row their count. */
  def assignedJurors: Long = if (grouped) countFromDb else votes.size

  /** The file's votes rated above 0 (in a criteria round, rated in every criterion: U5); of a
    * grouped row from its query's `tally` ([[approvals]]).
    */
  def ratedVotes: Long = if (grouped) approvals else votes.count(_.rate > 0)

  /** The denominator of the file's score ([[totalRate]]): a binary round's jurors; one
    * juror's own vote, 1; else as the round's [[AverageRule]] has it: the file's rated votes, its
    * assigned jurors, or 1 (the sum).
    */
  def ratedJurors(round: Round): Long =
    if (round.isBinary) round.numberOfAssignedJurors
    else if (ownVote) 1
    else
      round.averageRule match {
        case AverageRule.Rated    => ratedVotes
        case AverageRule.Assigned => assignedJurors
        case AverageRule.Sum      => 1
      }

  /** The file's rate as the CSV has it: a binary round's approvals; a rated round's average and
    * `(sum / jurors)`, the jurors its [[AverageRule]] divides by; the sum alone in an
    * [[AverageRule.Sum]] round, or for a file with fewer than two.
    */
  def rateString(round: Round): String = {
    if (round.isBinary) {
      approvals.toString
    } else {
      if (ratedJurors(round) < 2) rateSum.toString
      else
        s"${Formatter.fmt.format(totalRate(round))} ($rateSum / ${ratedJurors(round)})"
    }
  }

  /** The file's rate as a page shows it (the by-rate gallery, the file list): as [[rateString]],
    * with the denominator in words for a file of several jurors: `14.50 (29 / 2 of 3 jurors)`
    * over the rated jurors, `9.67 (29 / 3 jurors)` over the assigned, `29 (sum of 3 jurors)` in
    * an [[AverageRule.Sum]] round.
    */
  def rateText(round: Round)(implicit messages: play.api.i18n.Messages): String =
    if (round.isBinary || ownVote || assignedJurors < 2) rateString(round)
    else if (round.averageRule == AverageRule.Sum) messages("average.sum", rateSum.toString, assignedJurors.toString)
    else messages("average.rate", Formatter.fmt.format(totalRate(round)), denominator(round))

  /** The sum and the jurors it is averaged over, in words: `29 / 2 of 3 jurors` (the rated of the
    * assigned, or `29 / 3 jurors` when all rated), `29 / 3 jurors` (the assigned); in an
    * [[AverageRule.Sum]] round, `sum of 3 jurors`.
    */
  def denominator(round: Round)(implicit messages: play.api.i18n.Messages): String = {
    val n = ratedJurors(round)
    round.averageRule match {
      case AverageRule.Sum => messages("average.denominator.sum", assignedJurors.toString)
      case AverageRule.Rated if n != assignedJurors =>
        messages("average.denominator.rated", rateSum.toString, n.toString, assignedJurors.toString)
      case _ => messages("average.denominator.jurors", rateSum.toString, n.toString)
    }
  }

  /** The rate a file's stars are filled to: its [[totalRate]]; in an [[AverageRule.Sum]] round,
    * whose score (the sum) can exceed the stars, the average over its rated jurors (one vote's
    * scale).
    */
  def starRate(round: Round): Double =
    if (round.averageRule != AverageRule.Sum || round.isBinary || ownVote) totalRate(round)
    else if (ratedVotes == 0) 0.0
    else rateSum.toDouble / ratedVotes

  /** A criteria round's rate where its stars are drawn (the by-rate gallery, the large view for
    * all the jurors): per criterion in stars, as the stars ([[Round.perCriterion]]), then the
    * sum in stored units, the scale of the CSV, the ranks and "min average rate" (U7): one
    * vote's `3.50 (Σ 7)`; several jurors' `7.25 (Σ 14.50: 29 / 2 of 3 jurors)`, their average
    * sum and its [[denominator]] by the round's [[AverageRule]]; in an [[AverageRule.Sum]] round
    * `3.63 (Σ 29, sum of 3 jurors)`, the stars of the rated jurors' average ([[starRate]]).
    */
  def criteriaRateString(round: Round)(implicit messages: play.api.i18n.Messages): String = {
    val stars = CriteriaResults.format(BigDecimal(round.perCriterion(starRate(round)) / round.starUnits))
    if (ownVote || assignedJurors < 2) messages("criteria.rate.one", stars, rateSum.toString)
    else if (round.averageRule == AverageRule.Sum)
      messages("criteria.rate.sum", stars, rateSum.toString, assignedJurors.toString)
    else messages("criteria.rate.many", stars, CriteriaResults.format(BigDecimal(totalRate(round))), denominator(round))
  }

  def pageId: Long = image.pageId

  def title: String = image.title

  def compare(that: ImageWithRating): Int = (this.pageId - that.pageId).sign.toInt
}

object ImageWithRating {

  /** How a list of all of a round's votes (file list, CSV, the by-rate gallery for all the
    * jurors) orders and ranks its files: by aggregates of each file's votes, as SQL over the
    * file's vote rows `s` with their directions (1 ascending, -1 descending), and their values
    * of a file in memory. Files of the same values tie, and share a rank range.
    */
  final case class RankKey(columns: Seq[(String, Int)], values: ImageWithRating => Seq[Long])

  object RankKey {

    /** A binary round's by `order`. A rated round's by its average under its [[AverageRule]],
      * then the sum of its rates, as SQL has them: the average as an exact integer key
      * ([[AverageRule.keySql]], [[AverageRule.key]]); the sum with a rate below 0 counted as it
      * is, not as 0 as [[ImageWithRating.rateSum]] counts it (of a grouped row, its one vote's
      * sum; of a file's votes read one by one, their sum). An [[AverageRule.Sum]] round's by the
      * sum alone.
      */
    def of(round: Round, order: BinaryOrder = BinaryOrder.Default): RankKey =
      if (round.isBinary)
        RankKey(order.columns, file => order.values(file.approvals, file.rejections).map(_.toLong))
      else {
        val sum = "sum(s.rate)" -> -1
        def sumOf(file: ImageWithRating): Long = file.votes.map(_.rate.toLong).sum
        round.averageRule.keySql(AverageRule.VoteTerms) match {
          case None => RankKey(Seq(sum), file => Seq(sumOf(file)))
          case Some(average) =>
            val denominator: ImageWithRating => Long =
              if (round.averageRule == AverageRule.Rated) _.ratedVotes else _.assignedJurors
            RankKey(Seq(average -> -1, sum), file => Seq(AverageRule.key(sumOf(file), denominator(file)), sumOf(file)))
        }
      }
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

class OneJuryRating(val vote: Vote) extends Rating {
  def isSelected: Boolean = vote.rate > 0

  def isRejected: Boolean = vote.rate < 0

  def isUnrated: Boolean = vote.rate == 0

  def rate: Int = vote.rate
}

class OwnRating(vote: Vote) extends OneJuryRating(vote) {

  def unSelect(): Unit = {
    vote.rate = -1
  }

  def select(): Unit = {
    vote.rate = 1
  }

  def rate_=(rate: Int): Unit = {
    vote.rate = rate
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
