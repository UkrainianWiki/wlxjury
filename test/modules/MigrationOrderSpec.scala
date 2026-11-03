package modules

import com.google.inject.matcher.Matchers
import com.google.inject.spi.ProvisionListener
import com.google.inject.{AbstractModule, Key}
import db.scalikejdbc.SharedTestDb
import org.specs2.mutable.Specification
import play.api.Mode
import play.api.inject.guice.GuiceApplicationBuilder
import services.jobs.JobScheduler

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** The migrations finish before anything that uses the database at the start begins: the job
  * scheduler (its provider starts it, and it takes over requeued jobs) and the legacy password
  * timer. Guice doesn't order eager singletons by module (an eager provider binding is built
  * before an earlier module's eager singleton, and in Prod's PRODUCTION stage Play builds the
  * router, its controllers and so the scheduler early): the constructor dependency on
  * [[DatabaseMigrations]] is what orders them. Checked in Prod mode (Guice's PRODUCTION stage)
  * with background jobs, as production runs, recording every provision's start and end, and
  * structurally: each constructor takes [[DatabaseMigrations]].
  */
class MigrationOrderSpec extends Specification {

  sequential

  private val events = new ConcurrentLinkedQueue[String]()

  private class Recording extends AbstractModule {
    override def configure(): Unit =
      bindListener(Matchers.any(), new ProvisionListener {
        def onProvision[T](provision: ProvisionListener.ProvisionInvocation[T]): Unit = {
          val name = provision.getBinding.getKey.getTypeLiteral.getRawType.getName
          events.add(s"start $name")
          provision.provision()
          events.add(s"end $name")
          ()
        }
      })
  }

  /** The DB users bound eagerly or started at construction, which must come after the migrations. */
  private val dbUsers = Seq(classOf[JobSchedulerProvider], classOf[JobScheduler], classOf[LegacyPasswordRetirementTask])

  "the app's start in Prod mode" should {
    "finish the migrations before the job scheduler and the password timer are built" in {
      SharedTestDb.init()
      val app = new GuiceApplicationBuilder()
        .in(Mode.Prod)
        .configure(Map[String, Any](
          "db.default.driver"   -> SharedTestDb.driverClassName,
          "db.default.username" -> SharedTestDb.username,
          "db.default.password" -> SharedTestDb.password,
          "db.default.url"      -> SharedTestDb.jdbcUrl,
          "db.default.migration.auto" -> true,
          "play.http.secret.key" -> "a-test-signing-key-of-at-least-thirty-two-bytes",
          "wlxjury.allowedHosts" -> "localhost",
          "jobs.mode" -> "background",
          // started, but its first poll and heartbeat come only after the spec: it can't claim
          // a job an earlier spec left in the shared database
          "jobs.pollInterval" -> "1 hour",
          "jobs.heartbeatInterval" -> "1 hour",
          "wlxjury.legacyPasswords.retire" -> true,
          "wlxjury.legacyPasswords.firstCheck" -> "1 hour"
        ))
        .bindings(new Recording)
        .build()
      try {
        val seen = events.asScala.toList
        val migrated = seen.indexOf(s"end ${classOf[DatabaseMigrations].getName}")
        migrated must be_>=(0)
        // A provision's "start" comes before its dependencies are provisioned (they nest in
        // it), its constructor body (and the provider's get, which starts the scheduler) runs
        // just before its "end": the migrations must have ended before that. Without the
        // constructor dependency the three were provisioned first (their starts at events 38,
        // 66 and 434, the migrations' end at 525).
        foreach(dbUsers) { c =>
          val built = seen.indexOf(s"end ${c.getName}")
          (built must be_>=(0)) and (built must be_>(migrated))
        }
      } finally {
        Await.result(app.stop(), 30.seconds)
        SharedTestDb.reregisterDefault()
      }
    }

    "have each of them depend on the migrations by its constructor" in {
      // the structural guarantee, independent of the order Guice happened to take
      foreach(Seq(classOf[JobSchedulerProvider], classOf[LegacyPasswordRetirementTask])) { c =>
        val deps = com.google.inject.spi.InjectionPoint.forConstructorOf(c).getDependencies.asScala.map(_.getKey)
        deps must contain(Key.get(classOf[DatabaseMigrations]))
      }
    }
  }
}
