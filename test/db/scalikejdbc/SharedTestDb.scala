package db.scalikejdbc

import com.dimafeng.testcontainers.MariaDBContainer
import org.flywaydb.core.Flyway
import org.testcontainers.utility.DockerImageName
import scalikejdbc.{ConnectionPool, DB}

object SharedTestDb {

  private val dataTables = Seq(
    "criteria_rate", "selection", "category_members", "comment",
    "criteria", "round_user", "images", "rounds", "users",
    "category", "monument", "contest_jury"
  )

  def truncateAll(): Unit = {
    require(initialized, "SharedTestDb must be initialized before truncateAll()")
    DB.autoCommit { implicit session =>
      import scalikejdbc.SQL
      SQL("SET FOREIGN_KEY_CHECKS=0").execute.apply()
      dataTables.foreach(t => SQL(s"TRUNCATE TABLE `$t`").execute.apply())
      SQL("SET FOREIGN_KEY_CHECKS=1").execute.apply()
    }
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
   *  Calling `init()` multiple times is safe — initialization happens once. */
  lazy val initialized: Boolean = {
    val container = MariaDBContainer(
      dockerImageName = DockerImageName.parse("mariadb:10.6.22"),
      dbName         = "wlxjury",
      dbUsername     = "WLXJURY_DB_USER",
      dbPassword     = "WLXJURY_DB_PASSWORD"
    )
    container.start()

    _url             = container.jdbcUrl
    _user            = container.username
    _password        = container.password
    _driverClassName = container.driverClassName

    Class.forName(container.driverClassName)
    registerPools()

    Flyway
      .configure()
      .dataSource(container.jdbcUrl, container.username, container.password)
      .locations("classpath:db/migration/default")
      .load()
      .migrate()

    // Initialise the hasManyThrough association so that round.users is populated
    // when Round.findById / findAll is called without a Play application.
    Round.usersRef

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      ConnectionPool.closeAll()
      container.stop()
    }))

    true
  }

  private def registerPools(): Unit = {
    ConnectionPool.singleton(_url, _user, _password)
  }

  /** Re-registers the default ScalikeJDBC pool using the shared container's
   *  JDBC URL.  Call this after stopping a Play application that uses
   *  PlayDBApiAdapterModule, which closes all pools on shutdown. */
  def reregisterDefault(): Unit = {
    require(initialized, "SharedTestDb must be initialized before reregisterDefault()")
    registerPools()
  }

  /** Call from spec `beforeAll()` to trigger lazy initialisation. */
  def init(): Unit = { val _ = initialized }
}
