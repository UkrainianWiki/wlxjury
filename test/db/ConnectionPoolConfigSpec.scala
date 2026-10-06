package db

import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import com.zaxxer.hikari.HikariDataSource
import db.scalikejdbc.SharedTestDb
import org.specs2.mutable.Specification
import play.api.db.DBApi
import play.api.inject.guice.GuiceApplicationBuilder

import java.io.File
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** The production config (conf/application.conf) gives ScalikeJDBC one pool: Play's
  * HikariCP pool with the configured size. The old BoneCP-style keys were silently
  * ignored, and a second module (scalikejdbc.PlayModule) built a pool only to have it
  * replaced.
  */
class ConnectionPoolConfigSpec extends Specification {

  private val prod = ConfigFactory
    .parseFile(new File("conf/application.conf"))
    .withFallback(ConfigFactory.defaultReference())
    .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))

  "conf/application.conf" should {

    "not enable ScalikeJDBC's own pool module" in {
      prod.getStringList("play.modules.enabled").asScala must contain("scalikejdbc.PlayDBApiAdapterModule")
      prod.getStringList("play.modules.enabled").asScala must not(contain("scalikejdbc.PlayModule"))
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
        // the pool PlayDBApiAdapterModule registers as ScalikeJDBC's default pool (read
        // through DBApi: other specs re-register the global default pool meanwhile)
        app.injector.instanceOf[DBApi].database("default").dataSource must beLike {
          case ds: HikariDataSource =>
            (ds.getMaximumPoolSize must_== 30) and (ds.getMinimumIdle must_== 10) and
              (ds.getConnectionTimeout must_== 30000L)
        }
      } finally {
        Await.result(app.stop(), 30.seconds)
        SharedTestDb.reregisterDefault()
      }
    }
  }
}
