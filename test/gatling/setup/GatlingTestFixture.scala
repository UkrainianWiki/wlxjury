package gatling.setup

import com.dimafeng.testcontainers.MariaDBContainer
import org.testcontainers.utility.DockerImageName
import play.api.test.TestServer
import play.api.inject.guice.GuiceApplicationBuilder

import scalikejdbc._

import java.net.ServerSocket
import scala.util.Random

object GatlingTestFixture {

  private val data: GatlingFixtureData =
    if (GatlingConfig.external) GatlingExternalTarget.load() else init()

  /** The fixture mode: a MariaDB testcontainer with the generated fixture, and the app in
    * this JVM (TestServer). */
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
    db.TestDocker.start("the Gatling fixture database")(container.start())

    val port = freePort()
    val app  = new GuiceApplicationBuilder()
      .configure(Map[String, Any](
        "db.default.driver"   -> container.driverClassName,
        "db.default.username" -> container.username,
        "db.default.password" -> container.password,
        "db.default.url"      -> container.jdbcUrl,
        "play.filters.disabled"              -> Seq.empty[String],
        "play.filters.csrf.method.whiteList" -> Seq("GET", "HEAD", "OPTIONS", "POST"),
        "play.http.session.secure"           -> false,
        // the test config runs jobs inline; the benchmark runs them as production does
        "jobs.mode"                              -> "background",
        "jobs.workers"                           -> GatlingConfig.Jobs.workers,
        "jobs.concurrency.round_distribution"    -> GatlingConfig.Jobs.distributionConcurrency,
        "jobs.roundDistribution.chunkRows"       -> GatlingConfig.Jobs.chunkRows,
        "jobs.pollInterval"                      -> "2 seconds"
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
    val byId = sql"SELECT id, number, name, rates FROM rounds WHERE id IN ($ids)"
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
