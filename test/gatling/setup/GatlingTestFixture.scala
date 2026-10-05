package gatling.setup

import com.dimafeng.testcontainers.MariaDBContainer
import org.testcontainers.utility.DockerImageName
import play.api.test.TestServer
import play.api.inject.guice.GuiceApplicationBuilder

import java.net.ServerSocket

object GatlingTestFixture {

  private val data: GatlingFixtureData = init()

  private def init(): GatlingFixtureData = {
    val container = MariaDBContainer(
      dockerImageName = DockerImageName.parse("mariadb:10.6.22"),
      dbName          = "wlxjury",
      dbUsername      = "WLXJURY_DB_USER",
      dbPassword      = "WLXJURY_DB_PASSWORD"
    )
    // The fixture's selection table and indexes (~370 MB) don't fit the default
    // 128 MB buffer pool, so the smoke queries would read them from disk on every
    // request. Server options rather than a my.cnf override: MariaDB ignores a
    // world-writable config file, as a file bind-mounted from Windows is.
    // max_allowed_packet: MariaDB's own default (16M), as on a production server;
    // testcontainers' config lowers it to 1M, and the driver doesn't split a batch
    // insert to fit it, so distributing a round of more than ~25k selection rows
    // (RoundDistributionSimulation) fails with a "Socket error".
    container.container.withCommand(
      "--innodb-buffer-pool-size=512M",
      "--innodb-log-file-size=128M",
      "--max-allowed-packet=16M",
      "--skip-name-resolve"
    )
    container.start()

    val port = freePort()
    val app  = new GuiceApplicationBuilder()
      .configure(Map[String, Any](
        "db.default.driver"   -> container.driverClassName,
        "db.default.username" -> container.username,
        "db.default.password" -> container.password,
        "db.default.url"      -> container.jdbcUrl,
        "play.filters.disabled"              -> Seq.empty[String],
        "play.filters.csrf.method.whiteList" -> Seq("GET", "HEAD", "OPTIONS", "POST"),
        "play.http.session.secure"           -> false
      ))
      .build()
    val server = TestServer(port, app)
    server.start()

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      try server.stop() finally container.stop()
    }))

    val cacheKey = GatlingDbCache.cacheKey(GatlingConfig)

    if (GatlingDbCache.exists(cacheKey)) {
      GatlingDbCache.restore(container, cacheKey)
      GatlingDbSetup.loadFromDb(port)
    } else {
      val data = GatlingDbSetup.load(port, GatlingConfig)
      GatlingDbCache.save(container, cacheKey)
      data
    }
  }

  private def freePort(): Int = {
    val s = new ServerSocket(0)
    try s.getLocalPort finally s.close()
  }

  val port: Int           = data.port
  val contestId: Long     = data.contestId
  val roundBinaryId: Long = data.roundBinaryId
  val roundRatingId: Long = data.roundRatingId
  val jurors: Seq[(Long, String, String)]       = data.jurors
  val organizer: (Long, String, String)         = data.organizer
  val imagePageIds: Seq[Long]                   = data.imagePageIds
  val regions: Seq[String]                      = data.regions
  val votingPairs: Seq[(Long, Long, Long, Int)] = data.votingPairs

  /** The fixture organizer first, then extra organizer accounts for concurrent sessions. */
  val organizers: Seq[(Long, String, String)] =
    organizer +: GatlingDbSetup.ensureExtraOrganizers(contestId, GatlingConfig.organizerAccounts - 1)
}
