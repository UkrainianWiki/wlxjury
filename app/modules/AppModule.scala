package modules

import com.google.inject.{AbstractModule, Provides}
import controllers.KOATUU
import db.{ImageRepo, RoundRepo}
import db.scalikejdbc.{ImageJdbc, Round}
import org.scalawiki.MwBot
import play.api.{Configuration, Environment, Logging}
import services.imports.{CommonsSource, MwCommonsSource}
import services.jobs.JobScheduler

class AppModule(environment: Environment, configuration: Configuration)
    extends AbstractModule
    with Logging {

  override def configure(): Unit = {
    logger.info("Application has started")

    bind(classOf[RoundRepo]).toInstance(Round)
    bind(classOf[ImageRepo]).toInstance(ImageJdbc)
    bind(classOf[CommonsSource]).to(classOf[MwCommonsSource])
    // eager: started with the app, it takes over the jobs a previous run left
    bind(classOf[JobScheduler]).toProvider(classOf[JobSchedulerProvider]).asEagerSingleton()

    KOATUU.load()
  }

  @Provides
  def bot: MwBot = {
    val host = configuration.get[String]("commons.host")
    MwBot.fromHost(host)
  }
}
