package db.scalikejdbc

import com.dimafeng.testcontainers.MariaDBContainer
import org.testcontainers.utility.DockerImageName
import scalikejdbc.{ConnectionPool, DB}

object SharedTestDb {

  private val dataTables = Seq(
    "account_token", "api_token", "job", "legacy_password_retired", "criteria_rate", "criteria_rate_orphan", "round_image", "vote", "vote_aside", "image_renames", "category_members", "comment",
    "criteria", "round_criterion", "round_user", "user_contest", "user_identity", "images", "round", "users",
    "category", "monument", "contest_jury"
  )

  def truncateAll(): Unit = {
    require(initialized, "SharedTestDb must be initialized before truncateAll()")
    DB.autoCommit { implicit session =>
      import scalikejdbc.SQL
      SQL("SET FOREIGN_KEY_CHECKS=0").execute.apply()
      dataTables.foreach(t => SQL(s"TRUNCATE TABLE `$t`").execute.apply())
      // the catalogue keeps the criteria V83 ships, which no test adds
      SQL(s"DELETE FROM criterion WHERE id > ${org.intracer.wmua.Criterion.Shipped.size}").execute.apply()
      SQL("SET FOREIGN_KEY_CHECKS=1").execute.apply()
    }
    // TRUNCATE resets AUTO_INCREMENT, so round ids are reused
    RoundImageCounts.clear()
    RegionCountCache.clear()
  }

  // Exposed so that SharedPlayApp can reuse this container (one container per JVM)
  // and so PlayTestDb can re-register the pool after a per-test Play app stops.
  private var _url: String            = _
  private var _user: String           = _
  private var _password: String       = _
  private var _driverClassName: String = _

  def jdbcUrl: String        = { require(initialized); _url }
  def username: String       = { require(initialized); _user }
  def password: String       = { require(initialized); _password }
  def driverClassName: String = { require(initialized); _driverClassName }

  /** Lazily starts the container and wires the default ScalikeJDBC pool.
   *  Calling `init()` multiple times is safe — initialization happens once. A failure to
   *  start (Docker not answering, [[db.TestDocker]]) is kept: every spec fails at once. */
  lazy val initialized: Boolean = started.get

  private lazy val started: scala.util.Try[Boolean] = scala.util.Try(start())

  @volatile private var _started = false

  /** Whether the container has been started in this JVM, without starting it: a spec that runs
    * its own Play app (and so closes the default pool) re-registers the pool only then. */
  def isStarted: Boolean = _started

  /** The test server's settings, for the shared container and the specs that start a
    * MariaDB of their own (not the Gatling fixture's, which stays production-like): the
    * data directory on tmpfs (TRUNCATE drops and re-creates each table's file: 0.5-1 s per
    * `truncateAll` on disk, 30-40 ms on tmpfs; 98a8a79e:docs/plans/2026-11-01-open-followups.md,
    * "Database-heavy specs"), capped at 1 GB so a runaway spec fails ("table is full")
    * instead of filling the Docker VM's memory; and caches sized for the suite instead of
    * Testcontainers' my.cnf (a 16M buffer pool, a 5M redo log, 4 open tables). The pool
    * (128M) and the redo log (48M) are below the measured 256M / 128M to keep the
    * container's memory, tmpfs included, around 350 MB. O_DIRECT doesn't work on tmpfs:
    * fsync. Durability is moot on tmpfs. max_allowed_packet stays at Testcontainers' 1M:
    * DistributeImagesBatchSpec checks that a distribution's batch fits it.
    */
  private[db] def forTests(container: MariaDBContainer): MariaDBContainer = {
    container.container.withTmpFs(java.util.Map.of("/var/lib/mysql", "rw,size=1g"))
    container.container.withCommand(
      "--innodb-flush-method=fsync",
      "--innodb-buffer-pool-size=128M",
      "--innodb-log-file-size=48M",
      "--table-open-cache=2000",
      "--table-definition-cache=1000",
      "--sort-buffer-size=2M",
      "--innodb-flush-log-at-trx-commit=0",
      "--innodb-doublewrite=0",
      // A leaked lock fails fast, naming the lock, instead of hanging the suite: row locks after
      // 10 s (default 50), metadata locks after 60 s (default a year). The lock specs wait
      // deliberately but briefly; CriteriaRateSpec sets its own 10 s.
      "--innodb-lock-wait-timeout=10",
      "--lock-wait-timeout=60"
    )
    container
  }

  /** Production's MariaDB; `-Dwlxjury.test.mariadb-image=mariadb:10.11.14` runs the specs on
    * another server version (the Flyway upgrade was checked on 10.11 this way).
    */
  val Image: String = sys.props.getOrElse("wlxjury.test.mariadb-image", "mariadb:10.6.22")

  /** The test server's version, (major, minor), e.g. (10, 6). */
  lazy val serverVersion: (Int, Int) = {
    require(initialized)
    val version = DB.readOnly { implicit s =>
      scalikejdbc.SQL("SELECT VERSION()").map(_.string(1)).single.apply().getOrElse("")
    }
    version.split("[.-]").take(2).map(_.toInt) match { case Array(major, minor) => (major, minor) }
  }

  /** Whether the server stores DESC index keys (MariaDB 10.8+; before, DESC is parsed and
    * ignored): V85 swaps the juror gallery's index only there.
    */
  def descendingIndexKeys: Boolean = serverVersion._1 * 1000 + serverVersion._2 >= 10008

  /** vote's index on (jury_id, round_id, rate, monument_id, page_id): V82's name, or V85's with
    * rate descending.
    */
  def voteGalleryIndex: String =
    if (descendingIndexKeys) "vote_jury_round_ratedesc_mon_page_idx" else "vote_jury_round_rate_mon_page_idx"

  private def start(): Boolean = {
    val container = forTests(MariaDBContainer(
      dockerImageName = DockerImageName.parse(Image),
      dbName         = "wlxjury",
      dbUsername     = "WLXJURY_DB_USER",
      dbPassword     = "WLXJURY_DB_PASSWORD"
    ))
    db.TestDocker.start("the test database", container)
    // a failure after the start (Flyway, the pool) stops the container: `started` keeps the
    // failure, so no later spec starts another
    try configure(container)
    catch { case e: Throwable => scala.util.Try(container.stop()); throw e }
  }

  @volatile private var _container: MariaDBContainer = _

  /** The shared container, for a spec that runs a client in it (mariadb-dump, mariadb). */
  private[db] def container: MariaDBContainer = { require(initialized); _container }

  private def configure(container: MariaDBContainer): Boolean = {
    _container       = container
    _url             = container.jdbcUrl
    _user            = container.username
    _password        = container.password
    _driverClassName = container.driverClassName

    Class.forName(container.driverClassName)
    registerPools()

    db.Migrations.migrate(container.jdbcUrl, container.username, container.password)

    // Initialise the hasManyThrough association so that round.users is populated
    // when Round.findById / findAll is called without a Play application.
    Round.usersRef

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      // a Play DataSourceConnectionPool left as the default throws on close: stop the container anyway
      scala.util.Try(ConnectionPool.closeAll())
      container.stop()
    }))

    _started = true
    true
  }

  /** The pool's connections at READ COMMITTED, as production's (`db.default.hikaricp.
    * transactionIsolation`, W1 of docs/plans/2026-11-03-write-path-simplification.md): the
    * driver sets it on each new connection.
    */
  private[db] def readCommittedUrl(url: String): String =
    url + (if (url.contains("?")) "&" else "?") + "transactionIsolation=READ-COMMITTED"

  private def registerPools(): Unit = {
    ConnectionPool.singleton(readCommittedUrl(_url), _user, _password)
  }

  /** Re-registers the default ScalikeJDBC pool using the shared container's
   *  JDBC URL.  Call this after stopping a Play application: PlayDBApiAdapterModule
   *  made Play's HikariCP pool the default, and stopping the app closes it. */
  def reregisterDefault(): Unit = {
    require(initialized, "SharedTestDb must be initialized before reregisterDefault()")
    registerPools()
  }

  /** Call from spec `beforeAll()` to trigger lazy initialisation. */
  def init(): Unit = { val _ = initialized }
}
