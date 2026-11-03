package db.scalikejdbc

import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import scalikejdbc.{DB, SQL}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

/** JVM-wide singleton Play application for controller/service tests.
 *
 *  Reuses [[SharedTestDb]]'s MariaDB container — there is only one container
 *  per test JVM, so no pool-naming conflicts can arise.  Play's
 *  `PlayDBApiAdapterModule` registers to the "default" ScalikeJDBC pool with
 *  the same credentials that `SharedTestDb` uses, so the pool is effectively
 *  shared and immutable once set.
 *
 *  Tests that need a Play `Application` call `testDbApp { implicit app => … }`
 *  (provided by [[PlayTestDb]]).  `truncateAll()` is called before each test
 *  for data isolation.
 */
object SharedPlayApp {

  private var _app: Application = _

  lazy val initialized: Boolean = {
    // Ensure the shared DB container is up and the default pool is registered.
    SharedTestDb.init()

    _app = new GuiceApplicationBuilder()
      .configure(Map(
        "db.default.driver"   -> SharedTestDb.driverClassName,
        "db.default.username" -> SharedTestDb.username,
        "db.default.password" -> SharedTestDb.password,
        "db.default.url"      -> SharedTestDb.jdbcUrl
      ))
      .build()

    // Warm up the hasManyThrough actor ref used by Round.addUsers / usersRef.
    db.scalikejdbc.Round.usersRef

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      Try(Await.result(_app.stop(), 30.seconds))
    }))

    true
  }

  def init(): Unit = { val _ = initialized }

  def app: Application = {
    require(initialized, "SharedPlayApp must be initialized before accessing app")
    _app
  }

  private val dataTables = Seq(
    "criteria_rate", "selection", "category_members", "comment",
    "criteria", "round_user", "images", "rounds", "users",
    "category", "monument", "contest_jury"
  )

  /** Truncate all data tables so each spec/test starts with an empty database. */
  def truncateAll(): Unit = {
    require(initialized, "SharedPlayApp must be initialized before truncateAll()")
    DB.autoCommit { implicit session =>
      SQL("SET FOREIGN_KEY_CHECKS=0").execute.apply()
      dataTables.foreach(t => SQL(s"TRUNCATE TABLE `$t`").execute.apply())
      SQL("SET FOREIGN_KEY_CHECKS=1").execute.apply()
    }
  }
}
