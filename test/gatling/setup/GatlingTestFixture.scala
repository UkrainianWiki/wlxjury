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
}
