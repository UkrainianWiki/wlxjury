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

  /** Organizer accounts the fixture provides: the most sessions any organizer flow uses. */
  def organizerAccounts: Int = math.max(RoundMgmt.organizers, AggRatings.organizers)

  object Distribution {
    private val c = cfg.getConfig("gatling.distribution")
    val jurorsPerImage: Int = c.getInt("jurorsPerImage")
    val fromRated: Boolean  = c.getBoolean("fromRated")
    val topImages: Int      = c.getInt("topImages")
    val topUp: Boolean      = c.getBoolean("topUp")
    val iterations: Int     = c.getInt("iterations")
    val timeoutMinutes: Int = c.getInt("timeoutMinutes")
  }
}
