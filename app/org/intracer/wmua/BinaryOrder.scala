package org.intracer.wmua

/** The order of a yes/no round's files by all of its jurors' votes together: the galleries,
  * file list and CSV of all the jurors' votes, and the top-N a next round takes from it
  * (docs/history/2026-11-01-binary-aggregate-votes.md, decision 1). Files of the same values
  * tie, and ties go in page id order wherever the order is used.
  *
  * @param param its value of a list URL's `sort`, the order cookie's and `round.top_binary_order`'s
  */
sealed abstract class BinaryOrder(val param: String) {

  /** Its ORDER BY over a file's vote rows `s` (grouped by file), with directions (1
    * ascending, -1 descending), before the page id.
    */
  def columns: Seq[(String, Int)]

  /** The values of [[columns]] of a file with these approvals (rate 1) and rejections (-1). */
  def values(approvals: Int, rejections: Int): Seq[Int]

  /** [[values]] to sort by ascending: the best first. */
  def sortKey(approvals: Int, rejections: Int): Seq[Int] =
    columns.map(_._2).zip(values(approvals, rejections)).map { case (dir, value) => dir * value }

  def messageKey: String = s"binary.order.$param"
}

object BinaryOrder {

  val Approvals = "sum(s.rate > 0)"
  val Rejections = "sum(s.rate < 0)"

  /** The most approvals first, then the fewest rejections: the default. */
  case object ApprovalsThenRejections extends BinaryOrder("approvals") {
    val columns: Seq[(String, Int)] = Seq(Approvals -> -1, Rejections -> 1)
    def values(approvals: Int, rejections: Int): Seq[Int] = Seq(approvals, rejections)
  }

  /** Approvals less rejections: the sum of the rates. */
  case object Difference extends BinaryOrder("difference") {
    val columns: Seq[(String, Int)] = Seq("sum(s.rate)" -> -1)
    def values(approvals: Int, rejections: Int): Seq[Int] = Seq(approvals - rejections)
  }

  /** The approvals alone. */
  case object ApprovalsOnly extends BinaryOrder("approvals-only") {
    val columns: Seq[(String, Int)] = Seq(Approvals -> -1)
    def values(approvals: Int, rejections: Int): Seq[Int] = Seq(approvals)
  }

  val Default: BinaryOrder = ApprovalsThenRejections

  val all: Seq[BinaryOrder] = Seq(ApprovalsThenRejections, Difference, ApprovalsOnly)

  def parse(param: String): Option[BinaryOrder] = all.find(_.param == param)

  /** The round form's and the job settings' value for a top-N with no order stored
    * (`round.top_binary_order` NULL: a round from before V72), picked by the rule of then: the
    * rate sum of each previous round, below 0 as 0, over its jurors, or the approvals with
    * "selected by at least" N >= 1 (`DistributionSource.ratedPage`; a stored 0 is no filter). Shown for a round that has it,
    * not offered for a new one.
    */
  val LegacyParam = "legacy"

  val LegacyMessageKey = s"binary.order.$LegacyParam"

  /** The round form's top-N order: an order's value, else the default ([[LegacyParam]] is none). */
  def ofForm(value: Option[String]): Option[BinaryOrder] =
    if (value.contains(LegacyParam)) None else Some(value.flatMap(parse).getOrElse(Default))
}
