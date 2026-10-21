package modules

import com.google.inject.{AbstractModule, Provides, Singleton}
import controllers.KOATUU
import db.{ImageRepo, RoundRepo}
import db.scalikejdbc.{ImageJdbc, Round}
import org.scalawiki.MwBot
import play.api.{Configuration, Environment, Logging}
import services.imports.{CommonsLogin, CommonsSource, MonumentListSource, MwCommonsSource, MwMonumentListSource}
import services.jobs.JobScheduler

class AppModule(environment: Environment, configuration: Configuration)
    extends AbstractModule
    with Logging {

  override def configure(): Unit = {
    logger.info("Application has started")

    bind(classOf[RoundRepo]).toInstance(Round)
    bind(classOf[ImageRepo]).toInstance(ImageJdbc)
    bind(classOf[CommonsSource]).to(classOf[MwCommonsSource])
    bind(classOf[MonumentListSource]).to(classOf[MwMonumentListSource])
    // eager: started with the app, it takes over the jobs a previous run left
    bind(classOf[JobScheduler]).toProvider(classOf[JobSchedulerProvider]).asEagerSingleton()

    KOATUU.load()
  }

  /** The Commons bot. `MwBot.fromHost` keeps one per host, so the session [[CommonsLogin]]
    * logs in is the one every request uses, [[controllers.Global.commons]]'s included (both
    * `commons.wikimedia.org`).
    */
  @Provides
  def bot: MwBot = MwBot.fromHost(configuration.get[String]("commons.host"))

  /** One for the app: it keeps what the session is between import steps. */
  @Provides @Singleton
  def commonsLogin(bot: MwBot): CommonsLogin = new CommonsLogin(bot, CommonsLogin.credentials(configuration))
}
