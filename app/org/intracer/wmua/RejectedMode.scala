package org.intracer.wmua

/** How a next round's "rejected by at least N jurors" picks files from its previous rounds' votes
  * (`round.prev_rejected_mode`, V93; N is `prev_rejected_by`): their rejections are a yes/no
  * round's "no" votes and a rated round's ✗ (`Round.rejects`). The user, 2026-11-05.
  *  - [[RejectedMode.Exclude]]: leaves out the files with N or more (`SUM(rejected) < N` pass), the
  *    filter's meaning before V93 and every existing round's;
  *  - [[RejectedMode.Only]]: only those files (`SUM(rejected) >= N`), to review the rejected files
  *    in a round of their own.
  *
  * @param param its stored value and the round form's
  */
sealed abstract class RejectedMode(val param: String) {
  def messageKey: String = s"rejected.by.mode.$param"

  /** Whether a file with `rejections` passes the filter of at least `n`. */
  def passes(rejections: Int, n: Int): Boolean = this match {
    case RejectedMode.Exclude => rejections < n
    case RejectedMode.Only    => rejections >= n
  }
}

object RejectedMode {

  case object Exclude extends RejectedMode("exclude")

  case object Only extends RejectedMode("only")

  val all: Seq[RejectedMode] = Seq(Exclude, Only)

  val Default: RejectedMode = Exclude

  def parse(param: String): Option[RejectedMode] = all.find(_.param == param)

  /** A stored value (V93's check allows only these), the default for none (a round or a job from
    * before V93).
    */
  def fromStored(value: Option[String]): RejectedMode = value.flatMap(parse).getOrElse(Default)
}
