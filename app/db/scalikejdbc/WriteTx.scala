package db.scalikejdbc

import scalikejdbc._

/** Write transactions that are run again when MariaDB rolls them back as a deadlock's victim
  * (docs/plans/2026-11-03-write-path-simplification.md, W3): the one wrapper for every
  * transaction owner of the vote tables' writes. Only error 1213 is retried: after it the
  * whole transaction is gone, so the body runs again from the start; a lock wait timeout (1205)
  * rolls back only the statement and is not retried here (job steps retry both,
  * [[services.jobs.JobScheduler]]).
  *
  * Rules for a body: it decides from what it reads inside the transaction (never from a read
  * made before it), and it has no effect outside the database before commit (cache
  * invalidations, log lines and job wake-ups come after the call returns). Never call it
  * inside a caller's transaction: there the caller owns the retry.
  */
object WriteTx {

  val Attempts = 3

  /** `DB.localTx(f)`, retried on a deadlock ([[retry]]). */
  def write[A](f: DBSession => A): A = retry(DB.localTx(f))

  /** `tx` again, up to [[Attempts]] times in all, while it fails as a deadlock's victim. */
  def retry[A](tx: => A): A = {
    var attempt = 1
    var result: Option[A] = None
    while (result.isEmpty)
      try result = Some(tx)
      catch {
        case e: Throwable if attempt < Attempts && DbErrors.isDeadlock(e) =>
          attempt += 1
          Thread.sleep(5L * attempt + scala.util.Random.nextInt(10))
      }
    result.get
  }
}
