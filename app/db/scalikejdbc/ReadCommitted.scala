package db.scalikejdbc

import scalikejdbc._

import java.sql.Connection

/** A transaction at READ COMMITTED, on a connection of the default pool given back at the
  * pool's level afterwards (the pool doesn't reset it, nor does ScalikeJDBC's own
  * `isolationLevel`). Each plain read then sees what was committed before it, not a snapshot
  * from the transaction's first read; locking reads lock the rows they read, without gaps.
  */
object ReadCommitted {

  def localTx[A](execution: DBSession => A): A =
    using(ConnectionPool.borrow()) { conn =>
      val previous = conn.getTransactionIsolation
      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED)
      try DB(conn).autoClose(false).localTx(execution)
      finally conn.setTransactionIsolation(previous)
    }
}
