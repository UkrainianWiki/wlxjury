package org.intracer.wmua

import db.scalikejdbc.Round
import org.intracer.wmua.ImageWithRating.RankKey
import org.specs2.mutable.Specification
import play.api.i18n.Messages

/** What each score rule (V90, [[AverageRule]]) makes of one file in every view: the by-rate
  * gallery's and the file list's text ([[ImageWithRating.rateText]]), the CSV's
  * ([[ImageWithRating.rateString]]), the stars ([[ImageWithRating.starRate]]), a criteria round's
  * text, the rank key, and a next round's score of it ([[ImageWithRating.totalRate]], which top-N
  * and "min average rate" compare). The file has three jurors: 7 and 8, and one unrated vote,
  * read as vote rows (the file list, the CSV) and as a grouped row (the by-rate gallery, the large
  * view, a distribution's candidate): both give the same everywhere.
  */
class AverageRuleSpec extends Specification {

  implicit val messages: Messages = views.LargeViewRenders.realMessages

  private val image = Image(1, "File:Image1.jpg", None, None, 640, 480, Some("07-101-0001"))

  private def round(rule: AverageRule, rates: Int = 10, halfStar: Boolean = false, criteria: Int = 0): Round =
    Round(Some(1), 1, contestId = 1, rates = Round.ratesById(rates), averageRule = rule, halfStar = halfStar,
      hasCriteria = criteria > 0, criteriaCount = math.max(criteria, 1))

  private val rows = ImageWithRating(image, Seq(Vote(1, 11, 1, 7), Vote(1, 12, 1, 8), Vote(1, 13, 1, 0)))
  private val grouped = ImageWithRating(image, Seq(Vote(1, 0, 0, 15)), 3, approvalsFromDb = Some(2), rejectionsFromDb = Some(0))

  private def both[A](f: ImageWithRating => A) = Seq(f(rows), f(grouped)).distinct

  "the rated rule (the default)" should {
    val r = round(AverageRule.Rated)
    "average over the two jurors who rated the file" in {
      (both(_.totalRate(r)) === Seq(7.5)) and
        (both(_.rateText(r)) === Seq("7.50 (15 / 2 of 3 jurors)")) and
        (both(_.rateString(r)) === Seq("7.50 (15 / 2)")) and
        (both(_.starRate(r)) === Seq(7.5)) and
        (both(RankKey.of(r).values) === Seq(Seq(7500000000L, 15L)))
    }
    "say `of n` only when some assigned juror hasn't rated" in {
      val all = ImageWithRating(image, Seq(Vote(1, 11, 1, 7), Vote(1, 12, 1, 8)))
      all.rateText(r) === "7.50 (15 / 2 jurors)"
    }
    "be the default of a new round" in {
      Round(None, 1, contestId = 1).averageRule === AverageRule.Rated
    }
  }

  "the assigned rule" should {
    val r = round(AverageRule.Assigned)
    "average over the three assigned jurors, the unrated vote as 0" in {
      (both(_.totalRate(r)) === Seq(5.0)) and
        (both(_.rateText(r)) === Seq("5.00 (15 / 3 jurors)")) and
        (both(_.rateString(r)) === Seq("5.00 (15 / 3)")) and
        (both(_.starRate(r)) === Seq(5.0)) and
        (both(RankKey.of(r).values) === Seq(Seq(5000000000L, 15L)))
    }
  }

  "the sum rule (an optionalRate round's before V90)" should {
    val r = round(AverageRule.Sum)
    "score the file by its sum in every view, its stars by its rated jurors' average" in {
      (both(_.totalRate(r)) === Seq(15.0)) and
        (both(_.rateText(r)) === Seq("15 (sum of 3 jurors)")) and
        (both(_.rateString(r)) === Seq("15")) and
        (both(_.starRate(r)) === Seq(7.5)) and
        (both(RankKey.of(r).values) === Seq(Seq(15L))) and
        (RankKey.of(r).columns === Seq("sum(s.rate)" -> -1))
    }
  }

  "a criteria round (two criteria)" should {
    "draw its stars per criterion and follow its rule in the overall text" in {
      val rated = round(AverageRule.Rated, criteria = 2)
      val assigned = round(AverageRule.Assigned, criteria = 2)
      val sum = round(AverageRule.Sum, criteria = 2)
      (both(_.criteriaRateString(rated)) === Seq("3.75 (Σ 7.50: 15 / 2 of 3 jurors)")) and
        (both(_.criteriaRateString(assigned)) === Seq("2.50 (Σ 5.00: 15 / 3 jurors)")) and
        (both(_.criteriaRateString(sum)) === Seq("3.75 (Σ 15, sum of 3 jurors)"))
    }
  }

  "a half-star round" should {
    "keep stored units in the numbers and stars as half stars" in {
      val r = round(AverageRule.Rated, halfStar = true)
      (r.starUnits === 2) and (both(_.rateText(r)) === Seq("7.50 (15 / 2 of 3 jurors)")) and
        (both(f => r.perCriterion(f.starRate(r)) / r.starUnits) === Seq(3.75))
    }
  }

  "one juror's own vote and a file of one juror" should {
    "show the rate whatever the rule" in {
      val own = ImageWithRating(image, Seq(Vote(1, 11, 1, 7)))
      forall(AverageRule.all) { rule =>
        (own.totalRate(round(rule)) === 7.0) and (own.rateText(round(rule)) === "7")
      }
    }
  }

  "a yes/no round" should {
    "count approvals and rejections whatever its rule" in {
      val tally = ImageWithRating(image, Seq(Vote(1, 0, 0, 1)), 3, approvalsFromDb = Some(2), rejectionsFromDb = Some(1))
      val texts = AverageRule.all.map(rule => round(rule, rates = 1)).map(r =>
        (tally.rateText(r), tally.rateString(r), RankKey.of(r).values(tally), RankKey.of(r).columns))
      texts.distinct === Seq(("2", "2", Seq(2L, 1L), BinaryOrder.Default.columns))
    }
  }

  "a rank key" should {
    "tie equal averages exactly and tell close ones apart" in {
      (AverageRule.key(2, 6) === AverageRule.key(1, 3)) and
        (AverageRule.key(10000, 29999) must be_<(AverageRule.key(10000, 29998))) and
        (AverageRule.key(5, 0) === 0L)
    }
  }
}
