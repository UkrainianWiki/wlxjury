package modules

import javax.inject.{Inject, Singleton}
import play.api.inject.{Binding, Module}
import play.api.{Configuration, Environment}

/** Runs the database migrations when the app starts ([[db.Migrations.onStart]]): an eager
  * singleton (DatabaseMigrationsModule). Its place among the modules orders nothing: Guice
  * builds an eager provider binding (the job scheduler's) before an earlier module's eager
  * singleton, and in Prod's PRODUCTION stage Play builds the router and its controllers early.
  * What uses the database at the start takes this as a constructor dependency, so it is built
  * after the migrations have run: [[JobSchedulerProvider]], [[LegacyPasswordRetirementTask]]
  * (modules.MigrationOrderSpec). A new one must do the same.
  */
@Singleton
class DatabaseMigrations @Inject() (configuration: Configuration, environment: Environment) {
  db.Migrations.onStart(configuration, environment.mode)
}

class DatabaseMigrationsModule extends Module {
  override def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] =
    Seq(bind[DatabaseMigrations].toSelf.eagerly())
}
