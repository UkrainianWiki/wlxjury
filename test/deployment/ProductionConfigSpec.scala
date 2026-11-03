package deployment

import com.typesafe.config.{Config, ConfigException, ConfigFactory, ConfigParseOptions, ConfigResolveOptions}
import controllers.{GalleryView, RoundList, Theme, ThemeCookies}
import db.scalikejdbc.{Round, User}
import modules.AllowedHosts
import org.specs2.mutable.Specification
import play.api.http.{HttpConfiguration, HttpErrorHandler}
import play.api.libs.crypto.CookieSignerProvider
import play.api.libs.streams.Accumulator
import play.api.mvc.{DefaultFlashCookieBaker, DefaultSessionCookieBaker, EssentialAction, Flash, RequestHeader, Result, Results, Session}
import org.apache.pekko.stream.testkit.NoMaterializer
import play.api.test.FakeRequest
import play.api.{Configuration, Environment, Mode}
import play.filters.hosts.AllowedHostsFilter

import java.io.File
import java.net.URLClassLoader
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.jdk.CollectionConverters._

class ProductionConfigSpec extends Specification {
  private def production = ConfigFactory.parseFile(new File("conf/wlxjury/production.conf"))
    .withFallback(ConfigFactory.defaultReference())
  private val variables = Map(
    "WLXJURY_DB_HOST" -> "localhost:3307", "WLXJURY_DB" -> "jury_copy",
    "WLXJURY_DB_USER" -> "jury", "WLXJURY_DB_PASSWORD" -> "space $ # ` \\ \" password",
    "PLAY_HTTP_SECRET_KEY" -> "an-existing-signing-key-at-least-32-bytes",
    "WLXJURY_ALLOWED_HOSTS" -> "jury.example.org, jury.example.net")
  private def resolved(vars: Map[String, String]) = ConfigFactory.parseMap(vars.asJava)
    .withFallback(production).resolve(ConfigResolveOptions.noSystem())

  /** production.conf over the release's own conf/application.conf: on the test classpath,
    * `classpath("application.conf")` is test/resources/application.conf, which has neither the
    * content security policy nor the release's filter settings. */
  private def releaseConfig(vars: Map[String, String] = variables): Config = {
    val conf = new URLClassLoader(Array(new File("conf").toURI.toURL), null)
    ConfigFactory.parseMap(vars.asJava)
      .withFallback(ConfigFactory.parseFile(new File("conf/wlxjury/production.conf"),
        ConfigParseOptions.defaults().setClassLoader(conf)))
      .withFallback(ConfigFactory.defaultReference())
      .resolve(ConfigResolveOptions.noSystem())
  }

  /** The status for a request with `host`, through the filter as the production app builds it
    * (modules.AllowedHostsModule), before an action that answers 200, with an error handler that
    * answers the status it's given. */
  private def hostStatus(config: Config, host: String, path: String = "/"): Int = {
    val errors = new HttpErrorHandler {
      def onClientError(request: RequestHeader, statusCode: Int, message: String): Future[Result] =
        Future.successful(Results.Status(statusCode))
      def onServerError(request: RequestHeader, exception: Throwable): Future[Result] =
        Future.successful(Results.InternalServerError)
    }
    val filter = AllowedHostsFilter(AllowedHosts.config(Configuration(config), Mode.Prod), errors)
    val ok     = EssentialAction(_ => Accumulator.done(Results.Ok))
    val result = filter(ok)(FakeRequest("GET", path).withHeaders("Host" -> host)).run()(NoMaterializer)
    Await.result(result, 10.seconds).header.status
  }

  "the external production configuration" should {
    "require the signing key even though the included app has a development default" in {
      resolved(variables - "PLAY_HTTP_SECRET_KEY") must throwA[ConfigException.UnresolvedSubstitution]
    }
    "require DB credentials and preserve special characters without shell expansion" in {
      resolved(variables).getString("db.default.password") mustEqual variables("WLXJURY_DB_PASSWORD")
      resolved(variables - "WLXJURY_DB_PASSWORD") must throwA[ConfigException.UnresolvedSubstitution]
    }
    "inherit release defaults, migrate automatically, validate history and prohibit baselining or cleaning" in {
      val config = resolved(variables)
      config.getInt("db.default.hikaricp.maximumPoolSize") mustEqual 30
      config.getBoolean("db.default.migration.auto") must beTrue
      config.getBoolean("db.default.migration.validateOnMigrate") must beTrue
      config.getBoolean("db.default.migration.initOnMigrate") must beFalse
      config.getBoolean("db.default.migration.cleanDisabled") must beTrue
      config.getBoolean("db.default.migration.cleanOnValidationError") must beFalse
      config.getStringList("db.default.migration.ignoreMigrationPatterns").asScala must beEmpty
    }
  }

  "the allowed hosts in production" should {
    "require the jury's host names" in {
      resolved(variables - "WLXJURY_ALLOWED_HOSTS") must throwA[ConfigException.UnresolvedSubstitution]
      releaseConfig(variables - "WLXJURY_ALLOWED_HOSTS") must throwA[ConfigException.UnresolvedSubstitution]
    }

    "turn the filter on, with the module that reads wlxjury.allowedHosts" in {
      val config = releaseConfig()
      config.getStringList("play.filters.enabled").asScala must contain("play.filters.hosts.AllowedHostsFilter")
      config.getStringList("play.filters.disabled").asScala must not(contain("play.filters.hosts.AllowedHostsFilter"))
      config.getStringList("play.modules.enabled").asScala must contain("modules.AllowedHostsModule")
      config.getStringList("play.modules.disabled").asScala must contain("play.filters.hosts.AllowedHostsModule")
    }

    "allow the configured names only (no loopback names, not Play's *.local)" in {
      AllowedHosts.config(Configuration(releaseConfig()), Mode.Prod).allowed must_==
        Seq("jury.example.org", "jury.example.net")
    }

    "refuse an unknown or loopback Host with a 400 and pass a configured one, on a route without anyhost" in {
      val config = releaseConfig()
      hostStatus(config, "jury.example.org") must_== 200
      hostStatus(config, "JURY.example.net:443") must_== 200
      // Apache's default vhost passes these through: only the readiness route (+ anyhost,
      // controllers.AllowedHostsSpec) answers them
      hostStatus(config, "127.0.0.1:9000") must_== 400
      hostStatus(config, "localhost") must_== 400
      hostStatus(config, "other.example") must_== 400
      hostStatus(config, "jury.example.org.evil.example") must_== 400
      hostStatus(config, "printer.local") must_== 400
      hostStatus(config, "") must_== 400
    }
  }

  "the cookies in production" should {
    lazy val http   = HttpConfiguration.fromConfiguration(Configuration(releaseConfig()), Environment.simple())
    lazy val signer = new CookieSignerProvider(http.secret).get

    "be Secure: the session, the flash and the language cookie" in {
      new DefaultSessionCookieBaker(http.session, http.secret, signer)
        .encodeAsCookie(Session(Map("username" -> "juror@example.org"))).secure must beTrue
      new DefaultFlashCookieBaker(http.flash, http.secret, signer)
        .encodeAsCookie(Flash(Map("message" -> "saved"))).secure must beTrue
      releaseConfig().getBoolean("play.i18n.langCookieSecure") must beTrue
    }

    "be Secure: the theme cookie, which follows the session cookie" in {
      new ThemeCookies(http.session).cookie(Theme.Dark).secure must beTrue
    }

    "be Secure: the galleries' sort cookies, which follow the session cookie" in {
      // GalleryController passes play.http.session.secure to RoundList.remembered
      val secure = releaseConfig().getBoolean("play.http.session.secure")
      val juror  = User("Juror", "juror@example.org", id = Some(3), roles = Set("jury"), contestId = Some(1))
      val rated  = Round(id = Some(8), number = 1, contestId = 1, active = true, rates = Round.ratesById(5))
      // the juror's own gallery, in an order the URL names (SortCookie)
      implicit val request: RequestHeader = FakeRequest("GET", "/gallery/round/8/user/3/page/1?sort=rate")
      val cookie = RoundList.remembered(RoundList.of(rated, juror, 3, "all", GalleryView.Gallery), juror, secure)
      cookie.map(_.name) must beSome(RoundList.SortCookie)
      cookie.map(_.secure) must beSome(true)
    }

    "trust X-Forwarded-Proto from Apache on loopback only" in {
      releaseConfig().getStringList("play.http.forwarded.trustedProxies").asScala must
        containTheSameElementsAs(Seq("127.0.0.1", "::1"))
    }
  }

  "modules.AllowedHosts" should {
    def config(entries: (String, Any)*) = Configuration(ConfigFactory.parseMap((Map[String, Any](
      "play.filters.hosts.allowed"                  -> List("127.0.0.1").asJava,
      "play.filters.hosts.routeModifiers.whiteList" -> List("anyhost").asJava,
      "play.filters.hosts.routeModifiers.blackList" -> List.empty[String].asJava
    ) ++ entries).asJava))

    "refuse to start in production without a host name" in {
      AllowedHosts.config(config(), Mode.Prod) must throwA[IllegalStateException](message = "WLXJURY_ALLOWED_HOSTS")
      AllowedHosts.config(config("wlxjury.allowedHosts" -> " , "), Mode.Prod) must throwA[IllegalStateException]
    }

    "take Play's list alone in development and tests" in {
      AllowedHosts.config(config(), Mode.Dev).allowed must_== Seq("127.0.0.1")
      AllowedHosts.config(config(), Mode.Test).allowed must_== Seq("127.0.0.1")
    }

    "split the names by commas and spaces" in {
      AllowedHosts.parse(" jury.example.org,jury.example.net  .wle.example\tjury.example.org:8443 ") must_==
        Seq("jury.example.org", "jury.example.net", ".wle.example", "jury.example.org:8443")
    }

    "refuse what isn't a host name, the match-all \".\" included" in {
      forall(Seq(".", "*", "jury.example.org/", "https://jury.example.org", "a..b", "-a.example", "a.example:x")) { bad =>
        AllowedHosts.parse("jury.example.org," + bad) must throwA[IllegalArgumentException]
      }
    }

    "refuse a suffix of one label, which would allow a whole top-level domain" in {
      AllowedHosts.parse(".wle.example") must_== Seq(".wle.example")
      forall(Seq(".org", ".ua", ".localhost")) { bad =>
        AllowedHosts.parse("jury.example.org," + bad) must throwA[IllegalArgumentException](message = "suffix of one label")
      }
    }

    "refuse a default port, which browsers leave out of Host, so the name would never match" in {
      AllowedHosts.parse("jury.example.org:8443") must_== Seq("jury.example.org:8443")
      forall(Seq("jury.example.org:443", "jury.example.org:80", ".example.org:443")) { bad =>
        AllowedHosts.parse(bad) must throwA[IllegalArgumentException](message = "default port")
      }
    }
  }
}
