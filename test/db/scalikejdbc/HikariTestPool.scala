package db.scalikejdbc

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import scalikejdbc.{DataSourceCloser, DataSourceConnectionPool}

/** A HikariCP pool as ScalikeJDBC's pool, for the tests and tools that register their own pool
  * (SharedTestDb, the Gatling setups, WritePathStress): production's pool is Play's HikariCP
  * pool (db.default.hikaricp in conf/application.conf, ConnectionPoolConfigSpec), so the tests
  * run on the same one. ScalikeJDBC's own default pool (commons-dbcp2) is not on the classpath.
  *
  * The settings follow Play's (play-jdbc's reference.conf, db.default.hikaricp): autoCommit on,
  * a connection timeout of 30 seconds, idle timeout 10 minutes, max lifetime 30 minutes, and
  * no failure at start if the database is not up yet (initializationFailTimeout -1). The sizes
  * are each caller's; production's are 10 idle and 30 at most.
  *
  * Unlike a plain DataSourceConnectionPool, it closes its HikariDataSource on `close`
  * (ConnectionPool.closeAll, or ScalikeJDBC replacing it as the default pool) and reports its
  * active and idle connections, which ScalikeJDBC waits on before it closes a replaced pool.
  */
final class HikariTestPool private (val hikari: HikariDataSource)
    extends DataSourceConnectionPool(hikari, closer = new DataSourceCloser { def close(): Unit = hikari.close() }) {

  private def mx = Option(hikari.getHikariPoolMXBean)

  override def numActive: Int = mx.fold(0)(_.getActiveConnections)
  override def numIdle: Int   = mx.fold(0)(_.getIdleConnections)
  override def maxActive: Int = hikari.getMaximumPoolSize
  override def maxIdle: Int   = hikari.getMaximumPoolSize
  override def toString: String = s"HikariTestPool(${hikari.getPoolName}, max ${hikari.getMaximumPoolSize})"
}

object HikariTestPool {

  /** Production's isolation (db.default.hikaricp.transactionIsolation). */
  val ReadCommitted = "TRANSACTION_READ_COMMITTED"

  /** @param isolation a `java.sql.Connection` TRANSACTION_* name, or None for the server's
    *                  default (WritePathStress sets it per variant with SET GLOBAL)
    */
  def apply(url: String, user: String, password: String, name: String,
            minimumIdle: Int, maximumPoolSize: Int,
            isolation: Option[String] = Some(ReadCommitted)): HikariTestPool = {
    val c = new HikariConfig()
    c.setJdbcUrl(url)
    c.setUsername(user)
    c.setPassword(password)
    c.setPoolName(name)
    c.setMinimumIdle(minimumIdle)
    c.setMaximumPoolSize(maximumPoolSize)
    c.setAutoCommit(true)
    c.setConnectionTimeout(30000L)
    c.setIdleTimeout(600000L)
    c.setMaxLifetime(1800000L)
    c.setInitializationFailTimeout(-1L)
    isolation.foreach(c.setTransactionIsolation)
    new HikariTestPool(new HikariDataSource(c))
  }
}
