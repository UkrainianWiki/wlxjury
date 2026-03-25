package gatling.setup

import com.typesafe.config.ConfigFactory

import java.io.File

object GatlingConfig {
  private val cfg =
    ConfigFactory.systemProperties()
      .withFallback(ConfigFactory.parseFile(new File("test/resources/gatling-perf.conf")))
      .resolve()

  val users: Int           = cfg.getInt("gatling.users")
  val rampUpSeconds: Int   = cfg.getInt("gatling.rampUpSeconds")
  val durationSeconds: Int = cfg.getInt("gatling.durationSeconds")
  val jurorFraction: Double= cfg.getDouble("gatling.jurorFraction")
  val maxRate: Int         = cfg.getInt("gatling.maxRate")
}
