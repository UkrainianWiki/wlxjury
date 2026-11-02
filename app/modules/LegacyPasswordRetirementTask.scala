package modules

import db.scalikejdbc.LegacyPasswordRetirementJdbc
import play.api.inject.ApplicationLifecycle
import play.api.{Configuration, Logger}

import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

/** Replaces the legacy SHA-1 password hashes left once their grace period is over (P8, V81,
  * [[LegacyPasswordRetirementJdbc.retire]]): checked `wlxjury.legacyPasswords.firstCheck` after
  * the start, then every `wlxjury.legacyPasswords.checkEvery`; off if
  * `wlxjury.legacyPasswords.retire` is false (the tests' config: specs call [[runOnce]]). A
  * legacy hash stops signing in at the end of the grace period whether this has run or not
  * ([[db.scalikejdbc.User.login]]). Logs the number replaced, never a hash or an account.
  *
  * @param retire replaces the hashes if it is time; the number replaced
  */
class LegacyPasswordRetirement(retire: () => Int = () => LegacyPasswordRetirementJdbc.retire()) {

  private val logger = Logger("security.password")

  /** One check: the number of hashes replaced (0 on a failure, logged by its class only). */
  def runOnce(): Int =
    try {
      val replaced = retire()
      // WARN: production logs WARN and up, and the replacement is to be seen there
      if (replaced > 0) logger.warn(s"legacy_passwords_retired count=$replaced")
      replaced
    } catch {
      case NonFatal(e) =>
        logger.warn(s"legacy_passwords_retire_failed error=${e.getClass.getSimpleName}")
        0
    }
}

/** [[LegacyPasswordRetirement]] on a timer of its own, started with the app (bound eagerly in
  * [[AppModule]]) and stopped with it.
  */
@Singleton
class LegacyPasswordRetirementTask @Inject() (config: Configuration, lifecycle: ApplicationLifecycle) {

  private val enabled = config.getOptional[Boolean]("wlxjury.legacyPasswords.retire").getOrElse(true)
  private val firstCheck = config.getOptional[FiniteDuration]("wlxjury.legacyPasswords.firstCheck").getOrElse(1.minute)
  private val checkEvery = config.getOptional[FiniteDuration]("wlxjury.legacyPasswords.checkEvery").getOrElse(6.hours)

  val retirement = new LegacyPasswordRetirement()

  private val timer: Option[ScheduledExecutorService] =
    Option.when(enabled) {
      val executor = Executors.newSingleThreadScheduledExecutor { r =>
        val thread = new Thread(r, "legacy-password-retirement")
        thread.setDaemon(true)
        thread
      }
      executor.scheduleWithFixedDelay(() => { retirement.runOnce(); () }, firstCheck.toMillis, checkEvery.toMillis,
        TimeUnit.MILLISECONDS)
      executor
    }

  lifecycle.addStopHook(() => Future.successful(timer.foreach(_.shutdownNow())))
}
