package gatling.setup

import com.dimafeng.testcontainers.MariaDBContainer
import org.testcontainers.utility.DockerImageName
import play.api.test.TestServer
import play.api.inject.guice.GuiceApplicationBuilder

import scalikejdbc._

import java.net.ServerSocket
import scala.util.Random

object GatlingTestFixture {

  private var localApplication: Option[_root_.play.api.Application] = None

  /** For controller benchmarks against the same disposable fixture and dependency graph. */
  def application: _root_.play.api.Application = {
    require(!GatlingConfig.external, "No application is exposed for an external target")
    val _ = data
    localApplication.get
  }

  private val data: GatlingFixtureData =
    if (GatlingConfig.external) GatlingExternalTarget.load() else init()

  /** The fixture mode: a MariaDB testcontainer with the generated fixture, and the app in
    * this JVM (TestServer). */
  private def init(): GatlingFixtureData = {
    val container = MariaDBContainer(
      dockerImageName = DockerImageName.parse(db.scalikejdbc.SharedTestDb.Image),
      dbName          = "wlxjury",
      dbUsername      = "WLXJURY_DB_USER",
      dbPassword      = "WLXJURY_DB_PASSWORD"
    )
    // The fixture's vote table and indexes (~370 MB) don't fit the default
    // 128 MB buffer pool, so the smoke queries would read them from disk on every
    // request. Server options rather than a my.cnf override: MariaDB ignores a
    // world-writable config file, as a file bind-mounted from Windows is.
    // max_allowed_packet: MariaDB's own default (16M), as on a production server;
    // testcontainers' config lowers it to 1M, and the driver doesn't split a batch
    // insert to fit it, so distributing a round of more than ~25k vote rows
    // (RoundDistributionSimulation) fails with a "Socket error".
    container.container.withCommand(
      "--innodb-buffer-pool-size=512M",
      "--innodb-log-file-size=128M",
      "--max-allowed-packet=16M",
      "--skip-name-resolve"
    )
    db.TestDocker.start("the Gatling fixture database", container)

    val port = freePort()
    val app  = new GuiceApplicationBuilder()
      .configure(Map[String, Any](
        "db.default.driver"   -> container.driverClassName,
        "db.default.username" -> container.username,
        "db.default.password" -> container.password,
        "db.default.url"      -> container.jdbcUrl,
        "play.filters.disabled"              -> Seq.empty[String],
        "play.http.session.secure"           -> false,
        // the test config runs jobs inline; the benchmark runs them as production does
        "jobs.mode"                              -> "background",
        "jobs.workers"                           -> GatlingConfig.Jobs.workers,
        "jobs.concurrency.round_distribution"    -> GatlingConfig.Jobs.distributionConcurrency,
        "jobs.roundDistribution.chunkRows"       -> GatlingConfig.Jobs.chunkRows,
        "jobs.pollInterval"                      -> "2 seconds",
        // region counts: the contest files page's cache, and the round galleries' counts
        "regions.countCache"                     -> GatlingConfig.regionCountCache
      ) ++ GatlingConfig.regionCounts.map("gallery.regionCounts" -> _) ++ timingConfig ++ csrfConfig ++ imagesConfig ++
        cspConfig ++ filtersConfig)
      .build()
    localApplication = Some(app)
    val server = TestServer(port, app)
    server.start()
    choosePool(app)

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

  /** ScalikeJDBC's default pool for the run (`-Dgatling.pool`). At start it is always Play's
    * HikariCP pool (db.default.hikaricp, 30 connections), which scalikejdbc.PlayDBApiAdapterModule
    * registers. "small" replaces it with a HikariCP pool of 8 connections (none kept idle, at
    * READ COMMITTED as Play's): the size of the commons-dbcp2 pool of ScalikeJDBC's defaults
    * that scalikejdbc.PlayFixtureModule's PlayInitializer registered until it was dropped from
    * play.modules.enabled (it raced the HikariCP pool, so earlier runs may have used either).
    * That option was "dbcp", until commons-dbcp2 left the build. Any other value ("hikari",
    * the default) keeps Play's pool.
    */
  private def choosePool(app: _root_.play.api.Application): Unit = {
    val started = scalikejdbc.ConnectionPool.get().dataSource.getClass.getName
    require(GatlingConfig.pool != "dbcp", "gatling.pool=dbcp is gone with commons-dbcp2: use small (HikariCP, 8 connections)")
    if (GatlingConfig.pool == "small") {
      val c = app.configuration
      scalikejdbc.ConnectionPool.singleton(_root_.db.scalikejdbc.HikariTestPool(c.get[String]("db.default.url"),
        c.get[String]("db.default.username"), c.get[String]("db.default.password"), name = "gatling-small",
        minimumIdle = 0, maximumPoolSize = 8))
    }
    println(s"[pool] at start: $started; for the run (gatling.pool=${GatlingConfig.pool}): " +
      scalikejdbc.ConnectionPool.get().dataSource.getClass.getName)
  }

  /** The CSRF filter skips POSTs too, as the simulations send none of its tokens; with
    * -Dui.csrf=on (the browser tests, browser-test/fixture-server.mjs) it checks them as in
    * production (Play's default whitelist: GET, HEAD, OPTIONS). Lazy, as cspMode. */
  private lazy val csrfConfig: Map[String, Any] =
    if (sys.props.get("ui.csrf").contains("on")) { println("[csrf] POSTs checked"); Map.empty }
    else Map("play.filters.csrf.method.whiteList" -> Seq("GET", "HEAD", "OPTIONS", "POST"))

  /** With -Dui.images=stub (the browser tests): the image proxy fetches its thumbnails from a
    * stub in this JVM, which answers every path with one placeholder PNG, instead of from
    * upload.wikimedia.org, and caches them under target/ui-shots/thumbs. The browser tests' Back
    * checks run without Playwright's routes (which turn the browser's cache off), so their
    * thumbnails reach this server's proxy; with the stub it never fetches from Commons. */
  private lazy val imagesConfig: Map[String, Any] =
    if (!sys.props.get("ui.images").contains("stub")) Map.empty
    else {
      val png = {
        val image = new java.awt.image.BufferedImage(640, 480, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setColor(new java.awt.Color(120, 140, 160))
        g.fillRect(0, 0, 640, 480)
        g.dispose()
        val out = new java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "png", out)
        out.toByteArray
      }
      val stub = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
      stub.createContext("/", exchange => {
        exchange.getResponseHeaders.set("Content-Type", "image/png")
        exchange.sendResponseHeaders(200, png.length.toLong)
        try exchange.getResponseBody.write(png) finally exchange.close()
      })
      stub.start()
      val base = s"http://127.0.0.1:${stub.getAddress.getPort}"
      println(s"[images] thumbnails from the stub at $base")
      Map(
        "wlxjury.thumbs.wiki-base-url" -> base,
        "wlxjury.thumbs.local-path"    -> new java.io.File("target/ui-shots/thumbs").getAbsolutePath
      )
    }

  /** With -Dui.csp=production (the screenshot harness's --csp), the production content security
    * policy (play.filters.csp from conf/application.conf, security.ProductionFilters), which the
    * tests' config doesn't include. With -Dui.csp=strict (--csp-strict), that policy with its
    * script-src replaced by this host's files only, without the inline scripts' hashes: the
    * browser then reports each inline script as a violation, which lists them. */
  // lazy: `data` (above) builds the app with filtersConfig, which reads it during initialisation
  private lazy val cspMode: Option[String] = sys.props.get("ui.csp").filter(Set("production", "strict"))

  private def cspConfig: Map[String, Any] =
    cspMode.fold(Map.empty[String, Any]) { mode =>
      val directives = security.ProductionFilters.cspDirectives.map { case (name, values) =>
        name -> (if (mode == "strict" && name == "script-src") "'self'" else values.mkString(" "))
      }
      println(s"[csp] " + directives.map { case (n, v) => s"$n $v" }.mkString("; "))
      Map("play.filters.csp.nonce.enabled" -> false) ++
        directives.map { case (name, value) => s"play.filters.csp.directives.$name" -> value }
    }

  /** The release's filters in its order (security.ProductionFilters), without the content
    * security policy's unless -Dui.csp is set, and with the timing log
    * (modules.RequestTimingFilter) after the sessions' limits if enabled. */
  private def filtersConfig: Map[String, Any] = {
    import scala.jdk.CollectionConverters._
    val release = security.ProductionFilters.release.getStringList("play.filters.enabled").asScala.toSeq
      .filter(f => cspMode.isDefined || f != "play.filters.csp.CSPFilter")
    Map("play.filters.enabled" -> release.flatMap { f =>
      if (f == "modules.SessionLifetimeFilter" && GatlingConfig.timing) Seq(f, "modules.RequestTimingFilter") else Seq(f)
    })
  }

  /** The timing log's file, if enabled. */
  private def timingConfig: Map[String, Any] =
    if (!GatlingConfig.timing) Map.empty
    else {
      println(s"[timing] logging requests to ${GatlingConfig.timingFile}")
      Map("timing.file" -> GatlingConfig.timingFile)
    }

  private def freePort(): Int = {
    val s = new ServerSocket(0)
    try s.getLocalPort finally s.close()
  }

  val port: Int           = data.port
  val baseUrl: String     = if (GatlingConfig.external) GatlingConfig.External.baseUrl else s"http://localhost:$port"
  val contestId: Long     = data.contestId
  val roundBinaryId: Long = data.roundBinaryId
  val roundRatingId: Long = data.roundRatingId
  val jurors: Seq[(Long, String, String)]       = data.jurors
  val organizer: (Long, String, String)         = data.organizer
  val imagePageIds: Seq[Long]                   = data.imagePageIds
  val regions: Seq[String]                      = data.regions
  val votingPairs: Seq[(Long, Long, Long, Int)] = data.votingPairs

  /** The fixture organizer first, then extra organizer accounts for concurrent sessions.
    * External target: the contest's own organizer accounts, repeated when the flows have
    * more sessions than the contest has organizers (each session logs in on its own). */
  val organizers: Seq[(Long, String, String)] =
    if (GatlingConfig.external) {
      val all = organizer +: data.extraOrganizers
      Iterator.continually(all).flatten.take(math.max(GatlingConfig.organizerAccounts, all.size)).toSeq
    } else
      organizer +: GatlingDbSetup.ensureExtraOrganizers(contestId, GatlingConfig.organizerAccounts - 1)

  /** (id, number, name, rates) of the binary and the rated round: saving a round with its
    * own values (RoundManagement) changes nothing. */
  val managedRounds: Seq[(Long, Int, String, Int)] = {
    implicit val s: DBSession = AutoSession
    val ids = Seq(roundBinaryId, roundRatingId)
    val byId = sql"SELECT id, number, name, rates FROM round WHERE id IN ($ids)"
      .map(rs => rs.long(1) -> ((rs.long(1), rs.int(2), rs.stringOpt(3).getOrElse(""), rs.int(4)))).list().toMap
    ids.map(byId)
  }

  /** The rated round's highest rate. */
  val ratedMaxRate: Int =
    managedRounds.find(_._1 == roundRatingId).map(_._4).getOrElse(GatlingConfig.maxRate)

  /** Per juror, the (page, round) pairs the juror has in the two rounds: a vote or a
    * gallery view is of the juror's own images, as a real juror's. */
  private val votesByJuror: Map[Long, IndexedSeq[(Long, Long)]] =
    votingPairs.groupBy(_._1).map { case (j, ps) => j -> ps.map(p => (p._2, p._3)).toIndexedSeq }

  /** The juror's rounds among the binary and the rated round. */
  def roundsOf(juryId: Long): Seq[Long] =
    votesByJuror.get(juryId).map(_.map(_._2).distinct.sorted).getOrElse(Nil)

  /** Jurors with images in `round` (in either round when None). */
  def jurorsIn(round: Option[Long]): Seq[(Long, String, String)] =
    jurors.filter(j => round.fold(roundsOf(j._1).nonEmpty)(roundsOf(j._1).contains))

  /** A random vote of the juror (in `round` if given): (pageId, roundId, rate). The rate is
    * random as the round allows: binary 1 or -1, rated 1 to its highest rate. */
  def nextVote(juryId: Long, round: Option[Long] = None): (Long, Long, Int) = {
    val own = votesByJuror.getOrElse(juryId, IndexedSeq.empty)
    val ps  = round.fold(own)(r => own.filter(_._2 == r))
    val (page, r) = ps(Random.nextInt(ps.size))
    val rate = if (r == roundRatingId) Random.nextInt(ratedMaxRate) + 1 else if (Random.nextBoolean()) 1 else -1
    (page, r, rate)
  }
}
