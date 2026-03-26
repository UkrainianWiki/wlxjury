package db.scalikejdbc

import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder

import scala.concurrent.Await
import scala.concurrent.duration._

/** Mixin for tests that need a running Play application.
 *
 *  == Shared app (default) ==
 *  Call `testDbApp { implicit app => ... }` directly.  On first call the
 *  shared [[SharedPlayApp]] is initialised (reusing [[SharedTestDb]]'s
 *  container) and all tables are truncated for isolation.
 *
 *  == Per-spec lifecycle (e.g. RoundUsersSpec) ==
 *  Call `startPlayApp()` in `beforeAll` and `stopPlayApp()` in `afterAll`.
 *  `protected var app` is then the shared application.
 *
 *  == Per-test with custom config (e.g. ImageProxyControllerSpec) ==
 *  Place `implicit val cfg: Map[String, String] = Map(...)` in scope before
 *  calling `testDbApp { implicit app => ... }`.  A fresh Play app is started
 *  on top of the shared container and stopped after the call.  The shared
 *  ScalikeJDBC pool is restored via [[SharedTestDb.reregisterDefault()]]
 *  afterwards.
 */
trait PlayTestDb extends TestDb {

  // Used by startPlayApp / stopPlayApp pattern (e.g. RoundUsersSpec)
  protected var app: Application = _

  /** Run `body` with a Play application.
   *
   *  With an empty `additionalConfig` (the common case), uses [[SharedPlayApp]]
   *  and truncates all tables first.
   *
   *  With a non-empty `additionalConfig`, starts a fresh Play app for this
   *  single call (sharing [[SharedTestDb]]'s container but with extra config).
   */
  def testDbApp[T](
      body: Application => T
  )(implicit additionalConfig: Map[String, String] = Map.empty): T = {
    if (additionalConfig.isEmpty) {
      SharedPlayApp.init()
      SharedPlayApp.truncateAll()
      body(SharedPlayApp.app)
    } else {
      SharedTestDb.init()
      val perTestApp = new GuiceApplicationBuilder()
        .configure(Map(
          "db.default.driver"   -> SharedTestDb.driverClassName,
          "db.default.username" -> SharedTestDb.username,
          "db.default.password" -> SharedTestDb.password,
          "db.default.url"      -> SharedTestDb.jdbcUrl
        ) ++ additionalConfig)
        .build()
      try {
        body(perTestApp)
      } finally {
        Await.result(perTestApp.stop(), 30.seconds)
        // PlayDBApiAdapterModule closes ALL ScalikeJDBC pools on stop().
        // Restore the shared container's pool so other tests can still connect.
        SharedTestDb.reregisterDefault()
      }
    }
  }

  // -------------------------------------------------------------------------
  // Per-spec lifecycle (used by RoundUsersSpec)
  // -------------------------------------------------------------------------

  def startPlayApp(additionalConfig: Map[String, String] = Map.empty): Unit = {
    SharedPlayApp.init()
    SharedPlayApp.truncateAll()
    app = SharedPlayApp.app
    roundDao.usersRef
  }

  def stopPlayApp(): Unit = {
    // The shared app lives until JVM shutdown — nothing to stop here.
    // Truncate data so subsequent AutoRollback tests see an empty database.
    SharedPlayApp.truncateAll()
    SharedTestDb.reregisterDefault()
  }
}
