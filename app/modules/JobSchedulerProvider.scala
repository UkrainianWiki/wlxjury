package modules

import play.api.Configuration
import play.api.db.DBApi
import play.api.inject.ApplicationLifecycle
import services.distribution.RoundDistributionHandler
import services.imports.ImageImportHandler
import services.jobs.JobScheduler

import javax.inject.{Inject, Provider, Singleton}
import scala.concurrent.Future

/** The app's [[JobScheduler]], with a handler per job kind, started with the app (it
  * recovers the jobs an earlier run left) and stopped before the database: the DBApi is
  * injected so that its stop hook, registered before this one, runs after it.
  */
@Singleton
class JobSchedulerProvider @Inject() (
    config: Configuration,
    lifecycle: ApplicationLifecycle,
    dbApi: DBApi,
    roundDistribution: RoundDistributionHandler,
    imageImport: ImageImportHandler
) extends Provider[JobScheduler] {

  lazy val get: JobScheduler = {
    val scheduler = new JobScheduler(Seq(roundDistribution, imageImport), JobScheduler.Settings.from(config))
    scheduler.start()
    lifecycle.addStopHook(() => Future.successful(scheduler.stop()))
    scheduler
  }
}
