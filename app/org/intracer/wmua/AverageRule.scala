package org.intracer.wmua

/** How a rated round scores a file from its jurors' rates (`round.average_rule`, V90): the
  * round's one option for it, used by every view of its results (the by-rate gallery, the large
  * view for all the jurors, the file list, the CSV, criteria results) and by a next round's top-N
  * and "min average rate" from it (docs/plans/2026-11-02-round-images-two-step.md §17). It
  * replaces the legacy `optionalRate` flag (2015, never on the round form), whose round-level
  * denominator it folds into [[AverageRule.Sum]] (the user, 2026-11-05):
  *  - [[AverageRule.Rated]]: the rate sum over the jurors who rated the file (rate above 0; in a
  *    criteria round a vote rated in every criterion, U5), so an unrated vote is left out;
  *  - [[AverageRule.Assigned]]: over the jurors the file is assigned to (its vote rows), an
  *    unrated vote counted as 0;
  *  - [[AverageRule.Sum]]: the rate sum itself, a file a juror skipped getting nothing for it.
  *    An `optionalRate` round divided the sum by the round's active jurors, the same for every
  *    file: its order was the sum's already, and V90 makes it a `sum` round.
  *
  * A stopped juror's votes count under every rule, as they did before V90 (their vote rows stay;
  * `round_user.active` only stops new votes). A yes/no round has no average: its lists count
  * approvals and rejections ([[BinaryOrder]]), whatever its rule.
  *
  * @param param its stored value and the round form's
  */
sealed abstract class AverageRule(val param: String) {

  def messageKey: String = s"round.average.rule.$param"

  /** The rank key of a file's average under the rule, as SQL over the sum and denominator
    * expressions `n` gives (the vote rows' aggregates, or `round_image`'s counts): the average
    * scaled by [[AverageRule.KeyScale]] in integer arithmetic, so ties are exact and a key read
    * back from SQL equals the one [[AverageRule.key]] computes in memory. None for
    * [[AverageRule.Sum]]: its files rank by the sum alone.
    */
  def keySql(n: AverageRule.Terms): Option[String] = this match {
    case AverageRule.Sum => None
    case _ =>
      val d = denominator(n)
      Some(s"IF($d = 0, 0, ${n.sum} * ${AverageRule.KeyScale} DIV $d)")
  }

  private def denominator(n: AverageRule.Terms): String =
    if (this == AverageRule.Rated) n.rated else n.assigned
}

object AverageRule {

  /** Over the jurors who rated the file: the default (the user, 2026-11-05). */
  case object Rated extends AverageRule("rated")

  /** Over the jurors the file is assigned to, unrated as 0: the grouped views' rule before V90. */
  case object Assigned extends AverageRule("assigned")

  /** The rate sum, not divided: an `optionalRate` round's order before V90, now its score. */
  case object Sum extends AverageRule("sum")

  val all: Seq[AverageRule] = Seq(Rated, Assigned, Sum)

  val Default: AverageRule = Rated

  def parse(param: String): Option[AverageRule] = all.find(_.param == param)

  /** A stored value (V90's check allows only these); the default for none. */
  def fromStored(value: Option[String]): AverageRule = value.flatMap(parse).getOrElse(Default)

  /** The SQL of a file's rate sum, its rated votes and its vote rows, over one source. */
  final case class Terms(sum: String, rated: String, assigned: String)

  /** Over a file's `vote` rows `s`, grouped by file. */
  val VoteTerms: Terms = Terms("SUM(s.rate)", "SUM(s.rate > 0)", "COUNT(*)")

  /** The scale of a rank key ([[AverageRule.keySql]]): averages that differ, with a denominator
    * below 30,000 jurors, differ by more than 1 / KeyScale.
    */
  val KeyScale = 1000000000L

  /** [[AverageRule.keySql]] in memory: SQL's `DIV` truncates as Long division does. */
  def key(sum: Long, n: Long): Long = if (n == 0) 0L else sum * KeyScale / n
}
