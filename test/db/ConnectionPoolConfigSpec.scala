package db

import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import com.zaxxer.hikari.HikariDataSource
import db.scalikejdbc.SharedTestDb
import org.specs2.mutable.Specification
import play.api.db.DBApi
import play.api.inject.guice.GuiceApplicationBuilder
import _root_.scalikejdbc.ConnectionPool

import java.io.File
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** The production config (conf/application.conf) gives ScalikeJDBC one pool: Play's
  * HikariCP pool with the configured size. The old BoneCP-style keys were silently
  * ignored, and scalikejdbc.PlayModule and then scalikejdbc.PlayFixtureModule started
  * PlayInitializer, which registered a commons-dbcp2 pool of 8 connections as "default"
  * too: whichever module started last won.
  */
class ConnectionPoolConfigSpec extends Specification {
  // the second example changes ScalikeJDBC's global default pool
  sequential

  private val prod = ConfigFactory
    .parseFile(new File("conf/application.conf"))
    .withFallback(ConfigFactory.defaultReference())
    .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))

  "conf/application.conf" should {

    "not enable the modules that start ScalikeJDBC's own pool (PlayInitializer)" in {
      val modules = prod.getStringList("play.modules.enabled").asScala
      (modules must contain("scalikejdbc.PlayDBApiAdapterModule")) and
        (modules must not(contain("scalikejdbc.PlayModule"))) and
        (modules must not(contain("scalikejdbc.PlayFixtureModule")))
    }

    "give Play's HikariCP pool, ScalikeJDBC's default pool, the configured size" in {
      SharedTestDb.init()
      val hikari = prod.getConfig("db.default.hikaricp")
      val app = new GuiceApplicationBuilder()
        .configure(Map[String, Any](
          "db.default.driver"   -> SharedTestDb.driverClassName,
          "db.default.username" -> SharedTestDb.username,
          "db.default.password" -> SharedTestDb.password,
          "db.default.url"      -> SharedTestDb.jdbcUrl,
          "play.modules.enabled" -> prod.getStringList("play.modules.enabled").asScala.toSeq
        ) ++ hikari.entrySet().asScala.map(e => s"db.default.hikaricp.${e.getKey}" -> e.getValue.unwrapped()))
        .build()
      try {
        // the pool ScalikeJDBC code uses (DB.readOnly, autoSession, ...), right after the
        // start: Play's HikariCP pool itself, as PlayDBApiAdapterModule registers it
        val default = ConnectionPool.get().dataSource
        val play    = app.injector.instanceOf[DBApi].database("default").dataSource
        (default must beTheSameAs(play)) and (default must beLike {
          case ds: HikariDataSource =>
            (ds.getMaximumPoolSize must_== 30) and (ds.getMinimumIdle must_== 10) and
              (ds.getConnectionTimeout must_== 30000L)
        })
      } finally {
        Await.result(app.stop(), 30.seconds)
        SharedTestDb.reregisterDefault()
      }
    }
  }
}
