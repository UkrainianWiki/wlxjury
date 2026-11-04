package modules

import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import services.counts.RoundCountsRecompute

import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

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

  private val timer: Option[ScheduledExecutorService] =
    Option.when(settings.background) {
      val executor = Executors.newSingleThreadScheduledExecutor { r =>
        val thread = new Thread(r, "round-counts")
        thread.setDaemon(true)
        thread
      }
      val every = settings.interval.toMillis
      executor.scheduleWithFixedDelay(() => { recompute.runOnce(); () }, every, every, TimeUnit.MILLISECONDS)
      executor
    }

  lifecycle.addStopHook(() => Future.successful(timer.foreach { t =>
    t.shutdown() // no new tick; the running one stops at its budget
    if (!t.awaitTermination(settings.budget.toSeconds + 5, TimeUnit.SECONDS)) t.shutdownNow()
    ()
  }))
}
