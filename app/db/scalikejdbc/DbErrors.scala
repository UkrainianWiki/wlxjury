package db.scalikejdbc

import java.sql.{SQLException, SQLTransactionRollbackException}

/** MariaDB errors a transaction may be retried for, from an exception's cause chain. */
object DbErrors {

  /** ER_LOCK_DEADLOCK: the transaction was rolled back as a deadlock's victim. */
  val DeadlockErrorCode = 1213

  /** ER_LOCK_WAIT_TIMEOUT: a statement waited for a lock longer than `innodb_lock_wait_timeout`. */
  val LockWaitTimeoutErrorCode = 1205

  private def causes(e: Throwable): Iterator[Throwable] =
    Iterator.iterate(e)(_.getCause).takeWhile(_ != null).take(10)

  private def message(t: Throwable, text: String): Boolean = Option(t.getMessage).exists(_.contains(text))

  /** A deadlock: the whole transaction was rolled back, so it can be run again. */
  def isDeadlock(e: Throwable): Boolean =
    causes(e).exists {
      case sql: SQLException if sql.getErrorCode == LockWaitTimeoutErrorCode => false
      case sql: SQLException if sql.getErrorCode == DeadlockErrorCode || sql.getSQLState == "40001" => true
      case t => message(t, "Deadlock found")
    }

  /** A lock wait timeout: only the statement was rolled back (`innodb_rollback_on_timeout` off). */
  def isLockWaitTimeout(e: Throwable): Boolean =
    causes(e).exists {
      case sql: SQLException if sql.getErrorCode == LockWaitTimeoutErrorCode => true
      case t => message(t, "Lock wait timeout")
    }

  /** A database error worth retrying a transaction for: a deadlock, a transaction rollback, and
    * with `lockWaitTimeout` a lock wait timeout too.
    */
  def isTransient(e: Throwable, lockWaitTimeout: Boolean): Boolean =
    isDeadlock(e) || causes(e).exists(_.isInstanceOf[SQLTransactionRollbackException] && !isLockWaitTimeout(e)) ||
      (lockWaitTimeout && isLockWaitTimeout(e))

  /** `tx` again if the database rolled it back as a deadlock's victim ([[WriteTx.retry]]). */
  def retryOnDeadlock[T](tx: => T): T = WriteTx.retry(tx)
}
