package gatling.setup

import com.typesafe.config.ConfigFactory
import java.io.File

object GatlingConfig {
  private val cfg = ConfigFactory.systemProperties()
    .withFallback(ConfigFactory.parseFile(new File("test/resources/gatling-perf.conf")))
    .resolve()

  private val quick = cfg.getBoolean("gatling.quick")

  val users: Int            = if (quick) cfg.getInt("gatling.quickUsers")           else cfg.getInt("gatling.users")
  val rampUpSeconds: Int    = if (quick) cfg.getInt("gatling.quickRampUpSeconds")   else cfg.getInt("gatling.rampUpSeconds")
  val durationSeconds: Int  = if (quick) cfg.getInt("gatling.quickDurationSeconds") else cfg.getInt("gatling.durationSeconds")
  val jurorFraction: Double = cfg.getDouble("gatling.jurorFraction")
  val maxRate: Int          = cfg.getInt("gatling.maxRate")

  /** An organizer flow (round management, round stats): a few sessions with human think
    * time by default; `stress = true` runs the old worst case (many sessions, no pauses).
    */
  class OrganizerFlow(path: String) {
    private val c = cfg.getConfig(path)
    val stress: Boolean      = c.getBoolean("stress")
    val organizers: Int      = c.getInt("organizers")
    val rampUpSeconds: Int   = c.getInt("rampUpSeconds")
    val durationSeconds: Int = if (quick) c.getInt("quickDurationSeconds") else c.getInt("durationSeconds")
    val pauseScale: Double   = c.getDouble("pauseScale")
    val stressUsers: Int     = c.getInt("stressUsers")
    val stressRepeat: Int    = c.getInt("stressRepeat")
  }

  object RoundMgmt extends OrganizerFlow("gatling.roundMgmt") {
    val createRound: Boolean = cfg.getBoolean("gatling.roundMgmt.createRound")
  }

  object AggRatings extends OrganizerFlow("gatling.aggRatings") {
    val withVoting: Boolean = cfg.getBoolean("gatling.aggRatings.withVoting")
  }

  /** The app's `regions.countCache` (`-Dgatling.regionCountCache`), true by default. */
  val regionCountCache: Boolean =
    !cfg.hasPath("gatling.regionCountCache") || cfg.getBoolean("gatling.regionCountCache")

  /** The app's `gallery.regionCounts` (`-Dgatling.regionCounts=off|juror|all`); the app's default if not set. */
  val regionCounts: Option[String] =
    Option.when(cfg.hasPath("gatling.regionCounts"))(cfg.getString("gatling.regionCounts"))

  /** Organizer accounts the fixture provides: the most sessions any organizer flow uses. */
  def organizerAccounts: Int = Seq(RoundMgmt.organizers, AggRatings.organizers, Concurrent.admins).max

  /** The app's background job scheduler (jobs.* in application.conf). */
  object Jobs {
    private val c = cfg.getConfig("gatling.jobs")
    val workers: Int                 = c.getInt("workers")
    val distributionConcurrency: Int = c.getInt("distributionConcurrency")
    val chunkRows: Int               = c.getInt("chunkRows")
  }

  /** Many admins creating rounds at once (ConcurrentRoundCreationSimulation). */
  object Concurrent {
    private val c = cfg.getConfig("gatling.concurrent")
    val admins: Int            = c.getInt("admins")
    val jurorsPerImage: Int    = c.getInt("jurorsPerImage")
    val soloFirst: Boolean     = c.getBoolean("soloFirst")
    val withVoting: Boolean    = c.getBoolean("withVoting")
    val voterUsers: Int        = c.getInt("voterUsers")
    val durationSeconds: Int   = c.getInt("durationSeconds")
    val pollMillis: Int        = c.getInt("pollMillis")
    val timeoutMinutes: Int    = c.getInt("timeoutMinutes")
  }

  /** `gatling.target = external`: run against an app that is already running (see
    * [[GatlingExternalTarget]]) instead of a TestServer on a generated fixture. */
  val external: Boolean = cfg.getString("gatling.target") == "external"

  object External {
    private val c = cfg.getConfig("gatling.external")
    val baseUrl: String        = c.getString("baseUrl")
    val appPort: Int           = new java.net.URI(baseUrl).getPort
    val dbUrl: String          = c.getString("db.url")
    val dbUser: String         = c.getString("db.user")
    val dbPassword: String     = c.getString("db.password")
    val contestId: Long        = c.getLong("contestId")
    val binaryRoundId: Long    = c.getLong("binaryRoundId")
    val ratedRoundId: Long     = c.getLong("ratedRoundId")
    val passwordPrefix: String = c.getString("passwordPrefix")
  }

  object Distribution {
    private val c = cfg.getConfig("gatling.distribution")
    val jurorsPerImage: Int = c.getInt("jurorsPerImage")
    val fromRated: Boolean  = c.getBoolean("fromRated")
    val topImages: Int      = c.getInt("topImages")
    val topUp: Boolean      = c.getBoolean("topUp")
    val iterations: Int     = c.getInt("iterations")
    val timeoutMinutes: Int = c.getInt("timeoutMinutes")
    val pollMillis: Int     = c.getInt("pollMillis")
  }
}
