package org.intracer.wmua

/** The order of a yes/no round's files by all of its jurors' votes together: the galleries,
  * file list and CSV of all the jurors' votes, and the top-N a next round takes from it
  * (docs/plans/2026-11-01-binary-aggregate-votes.md, decision 1). Files of the same values
  * tie, and ties go in page id order wherever the order is used.
  *
  * @param param its value of a list URL's `sort`, the order cookie's and `rounds.top_binary_order`'s
  */
sealed abstract class BinaryOrder(val param: String) {

  /** Its ORDER BY over a file's selection rows `s` (grouped by file), with directions (1
    * ascending, -1 descending), before the page id.
    */
  def columns: Seq[(String, Int)]

  /** The values of [[columns]] of a file with these approvals (rate 1) and rejections (-1). */
  def values(approvals: Int, rejections: Int): Seq[Int]

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

  /** The order stored with a round (`rounds.top_binary_order`): NULL, or a value no order has,
    * is the default.
    */
  def ofStored(value: Option[String]): BinaryOrder = value.flatMap(parse).getOrElse(Default)
}
