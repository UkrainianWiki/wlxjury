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
}
