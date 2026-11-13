package plans

import com.dimafeng.testcontainers.MariaDBContainer
import db.scalikejdbc.{HikariTestPool, Round, SharedTestDb}
import org.testcontainers.utility.DockerImageName
import scalikejdbc.ConnectionPool

import java.sql.{Connection, DriverManager, ResultSet}
import scala.util.Using

/** QueryPlanSpec's own MariaDB (decision 4): a container of its own in the spec's own forked JVM
  * (build.sbt, `Test / testGrouping`), never the shared test database, with production-like
  * settings where they matter to plans and the seed: the data directory on disk (not the shared
  * one's 1 GB tmpfs), production's 256M buffer pool, and `--innodb-stats-auto-recalc=0` from the
  * start, so no write queues a recalculation that overwrites the injected statistics. Durability
  * is moot (the container is thrown away): no doublewrite, no flush at each commit.
  */
final class PlanDb private (val container: MariaDBContainer, val startSeconds: Double, val migrateSeconds: Double) {

  val url: String = container.jdbcUrl
  val password: String = container.password

  /** A root connection, outside ScalikeJDBC (never captured). */
  def root(): Connection = DriverManager.getConnection(url, "root", password)

  def stop(): Unit = {
    scala.util.Try(ConnectionPool.closeAll())
    container.stop()
  }
}

object PlanDb {

  /** The server image: the suite's (`-Dwlxjury.test.mariadb-image`, SharedTestDb.Image). Reading
    * it starts nothing.
    */
  def Image: String = SharedTestDb.Image

  /** Set by build.sbt for the `query-plans` test group only: the spec's JVM is its own. */
  val OwnJvmProperty = "wlxjury.query-plans.own-jvm"

  /** The server versions the spec runs on (decision 9 = C: 10.6 only until the server move). */
  val SupportedServer = "10.6"

  /** `10.6` of `mariadb:10.6.22`; None for a tag without a version. */
  def imageServer(image: String = Image): Option[String] =
    "^[^:]+:(\\d+)\\.(\\d+)".r.findFirstMatchIn(image).map(m => s"${m.group(1)}.${m.group(2)}")

  /** Why the spec doesn't run here, if it doesn't: another server than 10.6, or a JVM it would
    * share (it makes its container the default connection pool and closes every pool at the end:
    * an IDE's or a non-forked run's JVM, or one whose shared test database is up).
    */
  def skipReason(image: String = Image): Option[String] =
    if (!sys.props.get(OwnJvmProperty).contains("true"))
      Some(s"QueryPlanSpec runs only in a forked JVM of its own (build.sbt's query-plans test group sets " +
        s"-D$OwnJvmProperty=true): it replaces the JVM's default connection pool")
    else if (SharedTestDb.isStarted)
      Some("QueryPlanSpec doesn't run in a JVM whose shared test database is started: it replaces the default pool")
    else serverSkipReason(image)

  /** Why not on this image: MariaDB 10.6 only until the server move (decision 9 = C). */
  def serverSkipReason(image: String = Image): Option[String] =
    Option.when(!imageServer(image).contains(SupportedServer))(
      s"QueryPlanSpec runs on MariaDB $SupportedServer only until the server move is scheduled " +
        s"(docs/plans/2026-11-13-query-plan-checks.md, decision 9 = C); this run's image is $image")

  def start(): PlanDb = {
    val container = MariaDBContainer(
      dockerImageName = DockerImageName.parse(Image),
      dbName = "wlxjury",
      dbUsername = "WLXJURY_DB_USER",
      dbPassword = "WLXJURY_DB_PASSWORD"
    )
    container.container.withCommand(
      "--innodb-buffer-pool-size=256M",
      "--innodb-log-file-size=128M",
      "--innodb-stats-auto-recalc=0",
      "--innodb-flush-log-at-trx-commit=0",
      "--innodb-doublewrite=0",
      "--table-open-cache=2000",
      "--innodb-lock-wait-timeout=10",
      "--lock-wait-timeout=60"
    )
    val t0 = System.nanoTime()
    db.TestDocker.start("the query plan database", container)
    val started = (System.nanoTime() - t0) / 1e9
    try {
      Class.forName(container.driverClassName)
      ConnectionPool.singleton(HikariTestPool(container.jdbcUrl, container.username, container.password,
        name = "query-plans", minimumIdle = 1, maximumPoolSize = 4))
      val t1 = System.nanoTime()
      db.Migrations.migrate(container.jdbcUrl, container.username, container.password)
      Round.usersRef
      new PlanDb(container, started, (System.nanoTime() - t1) / 1e9)
    } catch { case e: Throwable => scala.util.Try(container.stop()); throw e }
  }

  def execute(c: Connection, sql: String): Unit = Using.resource(c.createStatement())(_.execute(sql))

  /** How long [[purged]] waits. */
  val PurgeTimeoutSeconds = 120

  /** Waits until InnoDB's purge has caught up (`innodb_max_purge_lag_wait = 0` returns then),
    * failing after [[PurgeTimeoutSeconds]].
    */
  def purged(c: Connection): Unit = Using.resource(c.createStatement()) { st =>
    st.setQueryTimeout(PurgeTimeoutSeconds)
    st.execute("SET GLOBAL innodb_max_purge_lag_wait = 0")
  }

  def update(c: Connection, sql: String): Int = Using.resource(c.createStatement())(_.executeUpdate(sql))

  def rows[T](c: Connection, sql: String)(read: ResultSet => T): List[T] =
    Using.resource(c.createStatement()) { st =>
      Using.resource(st.executeQuery(sql))(rs => Iterator.continually(rs).takeWhile(_.next()).map(read).toList)
    }

  def long(c: Connection, sql: String): Long = rows(c, sql)(_.getLong(1)).head

  def string(c: Connection, sql: String): String = rows(c, sql)(_.getString(1)).head
}
