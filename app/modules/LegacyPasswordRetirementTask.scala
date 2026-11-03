package modules

import db.scalikejdbc.LegacyPasswordRetirementJdbc
import db.scalikejdbc.LegacyPasswordRetirementJdbc.RetireFailed
import play.api.inject.ApplicationLifecycle
import play.api.{Configuration, Logger}

import java.util.concurrent.atomic.AtomicBoolean
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
  * @param retire    replaces the hashes if it is time; the number replaced
  * @param graceLeft the grace period's time left ([[LegacyPasswordRetirementJdbc.secondsLeft]]):
  *                  None without its table or row, said once (`legacy_password_grace_missing`):
  *                  nothing is ever retired then, which an operator should know
  */
class LegacyPasswordRetirement(
    retire: () => Int = () => LegacyPasswordRetirementJdbc.retire(),
    graceLeft: () => Option[Long] = () => LegacyPasswordRetirementJdbc.secondsLeft()
) {

  private val logger = Logger("security.password")
  private val missingSaid = new AtomicBoolean(false)

  /** One check: the number of hashes replaced; on a failure those replaced before it (a chunk's
    * [[RetireFailed]]), logged with the error's class only. WARN: production logs WARN and up.
    */
  def runOnce(): Int =
    try {
      if (graceLeft().isEmpty) {
        if (missingSaid.compareAndSet(false, true)) logger.warn("legacy_password_grace_missing")
        0
      } else {
        val replaced = retire()
        if (replaced > 0) logger.warn(s"legacy_passwords_retired count=$replaced")
        replaced
      }
    } catch {
      case e: RetireFailed =>
        logger.warn(s"legacy_passwords_retire_failed replaced=${e.replaced} error=${e.getCause.getClass.getSimpleName}")
        e.replaced
      case NonFatal(e) =>
        logger.warn(s"legacy_passwords_retire_failed replaced=0 error=${e.getClass.getSimpleName}")
        0
    }
}

/** [[LegacyPasswordRetirement]] on a timer of its own, started with the app (bound eagerly in
  * [[AppModule]]) and stopped with it; after the migrations (`migrations` is injected for that:
  * modules.MigrationOrderSpec).
  */
@Singleton
class LegacyPasswordRetirementTask @Inject() (
    migrations: DatabaseMigrations,
    config: Configuration,
    lifecycle: ApplicationLifecycle
) {

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
