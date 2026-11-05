package modules

import play.api.{Configuration, Logger}
import play.api.inject.ApplicationLifecycle
import services.counts.RoundCountsRecompute

import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

/** [[RoundCountsRecompute]] on a timer of its own (one thread, one connection at a time), a tick
  * every `roundCounts.interval` after the previous one ended; started with the app (bound
  * eagerly in [[AppModule]]), after the migrations (`migrations` is injected for that:
  * modules.MigrationOrderSpec), and stopped with it, letting a running window commit. Off with
  * `roundCounts.mode = manual` (the tests' config: specs run [[RoundCountsRecompute.untilClean]]).
  */
@Singleton
class RoundCountsTask @Inject() (
    migrations: DatabaseMigrations,
    config: Configuration,
    lifecycle: ApplicationLifecycle
) {

  private val settings = RoundCountsRecompute.Settings.from(config)

  val recompute = new RoundCountsRecompute(settings)

  private val timer: Option[RoundCountsTimer] =
    Option.when(settings.background)(new RoundCountsTimer(settings.interval, () => { recompute.runOnce(); () }))

  lifecycle.addStopHook(() => Future.successful(timer.foreach(_.stop(settings.budget))))
}

/** Runs `tick` every `interval` after the previous one ended, on a daemon thread of its own. A
  * tick that throws is logged (WARN, the error's class only) and the next one still runs:
  * `scheduleWithFixedDelay` would otherwise cancel every later tick silently. Only a
  * [[VirtualMachineError]] (out of memory, a stack overflow) is rethrown, and ends the timer.
  */
final class RoundCountsTimer(interval: FiniteDuration, tick: () => Unit, name: String = "round-counts") {

  private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r =>
    val thread = new Thread(r, name)
    thread.setDaemon(true)
    thread
  }

  executor.scheduleWithFixedDelay(() => RoundCountsTimer.guarded(tick()), interval.toMillis, interval.toMillis,
    TimeUnit.MILLISECONDS)

  /** No new tick; the running one may finish (it stops at its budget), `grace` + 5 s at most. */
  def stop(grace: FiniteDuration): Unit = {
    executor.shutdown()
    if (!executor.awaitTermination(grace.toSeconds + 5, TimeUnit.SECONDS)) executor.shutdownNow()
    ()
  }
}

object RoundCountsTimer {

  private val logger = Logger("round.counts")

  /** `f`, with anything it throws logged and dropped but a [[VirtualMachineError]]. */
  private[modules] def guarded(f: => Unit): Unit =
    try f
    catch {
      case e: VirtualMachineError => throw e
      case e: Throwable =>
        // the class only: a driver's message may quote values (db practices §1.6)
        logger.warn(s"round_counts_tick_failed error=${e.getClass.getSimpleName}")
    }
}
