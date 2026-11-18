package services.distribution

import db.scalikejdbc.{Round, RoundImageCounts, WriteTx}
import play.api.libs.json.JsObject
import scalikejdbc._
import services.jobs.JobJdbc

/** Rounds left frozen with no job holding them (docs/plans/2026-11-16-round-editing.md, §5 "The
  * pause can't outlive the job"): a round's `freeze_count` counts the jobs that froze it, a
  * distribution (its previous rounds) or a rebalance (its own round), each listing it in its
  * state's `frozen` while it is queued or running, or failed and waiting for a Retry. A count
  * above those is left over, e.g. by a job given up after its attempts whose cleanup failed
  * (`JobScheduler.giveUpIfExhausted`). The rounds list and the repair job flag it; root's Release
  * ends the excess freezes.
  */
object RoundFreezes {

  /** The kinds whose jobs freeze rounds and list them in their state's `frozen`. */
  val FreezingKinds: Seq[String] = Seq(RoundDistributionHandler.Kind, RoundRebalanceHandler.Kind)

  /** How many active (queued or running) jobs hold each round frozen. A failed job's cleanup
    * releases what it froze, so a failed job still listing a round is one whose cleanup didn't
    * run: it doesn't count, and the round is flagged.
    */
  private def holders()(implicit session: DBSession): Map[Long, Int] =
    sql"""SELECT state FROM job WHERE kind IN ($FreezingKinds) AND status IN ('queued', 'running')"""
      .map(rs => frozenOf(rs.string(1)))
      .list()
      .flatMap(_.distinct)
      .groupMapReduce(identity)(_ => 1)(_ + _)

  private def frozenOf(state: String): Seq[Long] =
    scala.util.Try(play.api.libs.json.Json.parse(state).as[JsObject]).toOption
      .flatMap(st => (st \ "frozen").asOpt[Seq[Long]]).getOrElse(Nil)

  /** Of `rounds`, those frozen more times than jobs hold them, with the excess. */
  def leftFrozen(rounds: Seq[Round])(implicit session: DBSession = AutoSession): Map[Long, Int] = {
    val frozen = rounds.filter(_.freezeCount > 0)
    if (frozen.isEmpty) Map.empty
    else {
      val held = holders()
      frozen.flatMap(r => Some(r.freezeCount - held.getOrElse(r.getId, 0)).filter(_ > 0).map(r.getId -> _)).toMap
    }
  }

  /** Every round left frozen, with the excess (the repair job). */
  def allLeftFrozen()(implicit session: DBSession = AutoSession): Map[Long, Int] = {
    val counts = sql"SELECT id, freeze_count FROM round WHERE freeze_count > 0".map(rs => rs.long(1) -> rs.int(2)).list()
    if (counts.isEmpty) Map.empty
    else {
      val held = holders()
      counts.flatMap { case (id, n) => Some(n - held.getOrElse(id, 0)).filter(_ > 0).map(id -> _) }.toMap
    }
  }

  /** Ends the round's freezes that no active job holds, under its lock (a job freezes a round
    * only under it): the last restores its active flag ([[Round.release]]). A failed job that still
    * lists the round (its cleanup didn't run) has it taken off its state's `frozen`, so its Retry or
    * Cancel doesn't release it a second time: such jobs' rows are locked first, then the round, the
    * order every step takes.
    *
    * @return how many were ended (0: none was left over)
    */
  def release(roundId: Long): Int = {
    val released = WriteTx.write { implicit session =>
      val failed = sql"""SELECT id, state FROM job WHERE kind IN ($FreezingKinds) AND status = 'failed'"""
        .map(rs => rs.long(1) -> frozenOf(rs.string(2))).list().filter(_._2.contains(roundId)).map(_._1).sorted
      val locked = failed.flatMap(id => JobJdbc.lock(id)).filter(j => j.isFailed)
      val count = sql"SELECT freeze_count FROM round WHERE id = $roundId FOR UPDATE".map(_.int(1)).single().getOrElse(0)
      db.scalikejdbc.RoundWrites.recordRounds(Seq(roundId))
      val excess = count - holders().getOrElse(roundId, 0)
      (1 to excess).foreach(_ => Round.release(Seq(roundId), close = false))
      locked.filter(j => (j.state \ "frozen").asOpt[Seq[Long]].exists(_.contains(roundId))).foreach { j =>
        val frozen = (j.state \ "frozen").as[Seq[Long]].filterNot(_ == roundId)
        val state = j.state ++ play.api.libs.json.Json.obj("frozen" -> frozen)
        sql"UPDATE job SET state = ${play.api.libs.json.Json.stringify(state)}, updated_at = NOW(3) WHERE id = ${j.id}".update()
      }
      math.max(excess, 0)
    }
    if (released > 0) RoundImageCounts.invalidate(roundId)
    released
  }

  /** Whether an active distribution that holds the round frozen will close it at its end: one
    * that completes a creation (its round being created, `distributing`, or
    * `params.completingCreation`), which releases its previous rounds closed
    * ([[RoundDistributionHandler]]'s `complete`, `Round.release(…, close = true)`). A plain read.
    */
  def closedByHolder(roundId: Long)(implicit session: DBSession = AutoSession): Boolean =
    JobJdbc.activeOfKind(RoundDistributionHandler.Kind)
      .filter(j => (j.state \ "frozen").asOpt[Seq[Long]].exists(_.contains(roundId)))
      .exists { j =>
        (j.params \ "completingCreation").asOpt[Boolean].getOrElse(false) ||
          j.subjectId.flatMap(Round.findById(_)).exists(_.distributing)
      }

  /** The jobs that hold the round frozen now (for the log): plain reads. */
  def holdingJobs(roundId: Long)(implicit session: DBSession = AutoSession): Seq[Long] =
    FreezingKinds.flatMap(JobJdbc.activeOfKind(_)).filter(j => (j.state \ "frozen").asOpt[Seq[Long]].exists(_.contains(roundId)))
      .map(_.id)
}
