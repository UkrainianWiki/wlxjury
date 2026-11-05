package modules

import com.google.inject.{AbstractModule, Provides, Singleton}
import controllers.KOATUU
import db.{ImageRepo, RoundRepo}
import db.scalikejdbc.{ImageJdbc, Round}
import org.scalawiki.MwBot
import play.api.inject.ApplicationLifecycle
import play.api.{Configuration, Environment, Logging}
import services.imports.{AnonymousBots, CommonsFileCheck, CommonsLogin, CommonsSource, MonumentListSource, MwCommonsSource, MwMonumentListSource}
import services.jobs.JobScheduler

import scala.concurrent.Future

class AppModule(environment: Environment, configuration: Configuration)
    extends AbstractModule
    with Logging {

  override def configure(): Unit = {
    logger.info("Application has started")
    // before anything (scalawiki's own code included) makes a bot that logs in from the
    // environment: services.imports.AnonymousBots
    AnonymousBots.seed(configuration.get[String]("commons.host"))

    bind(classOf[RoundRepo]).toInstance(Round)
    bind(classOf[ImageRepo]).toInstance(ImageJdbc)
    bind(classOf[CommonsSource]).to(classOf[MwCommonsSource])
    bind(classOf[MonumentListSource]).to(classOf[MwMonumentListSource])
    // eager: started with the app, it takes over the jobs a previous run left
    bind(classOf[JobScheduler]).toProvider(classOf[JobSchedulerProvider]).asEagerSingleton()
    // eager: replaces the legacy password hashes left after their grace period (P8, V81)
    bind(classOf[LegacyPasswordRetirementTask]).asEagerSingleton()
    // eager: keeps round_image's counts in line with the votes in the background (P2a, V89)
    bind(classOf[RoundCountsTask]).asEagerSingleton()

    KOATUU.load()

    // slow and failed statements logged without their bound values
    db.scalikejdbc.SqlLogging.install(
      configuration.getOptional[Long](db.scalikejdbc.SqlLogging.SlowMillisKey)
        .getOrElse(db.scalikejdbc.SqlLogging.DefaultSlowMillis))

    // an invalid bcrypt.* setting fails the start, not the first sign-in
    services.PasswordHasher.Settings.from(configuration.underlying)
    services.PasswordHasher.default
  }

  /** The Commons bot. `MwBot.fromHost` keeps one per host, so the session [[CommonsLogin]]
    * logs in is the one every request uses, [[controllers.Global.commons]]'s included (both
    * `commons.wikimedia.org`). Created anonymous ([[AnonymousBots]]): scalawiki's default
    * would log it in at once as `SCALAWIKI_LOGIN` / `SCALAWIKI_PASSWORD` from the
    * environment, in tests too. The app's only account is `commons.user`, through
    * [[CommonsLogin]].
    */
  @Provides
  def bot: MwBot = AnonymousBots.bot(configuration.get[String]("commons.host"))

  /** One for the app: it keeps what the session is between import steps. No account in
    * Mode.Test unless `commons.allowTestLogin` ([[CommonsLogin.credentials]]).
    */
  @Provides @Singleton
  def commonsLogin(bot: MwBot): CommonsLogin =
    new CommonsLogin(bot, CommonsLogin.credentials(configuration, environment.mode))

  /** One for the app: it batches the checks of all requests and keeps their answers. */
  @Provides @Singleton
  def commonsFileCheck(source: CommonsSource, lifecycle: ApplicationLifecycle): CommonsFileCheck = {
    val check = new CommonsFileCheck(source)
    lifecycle.addStopHook(() => Future.successful(check.stop()))
    check
  }
}
