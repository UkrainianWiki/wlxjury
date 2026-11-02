package controllers

import db.scalikejdbc.SharedTestDb
import org.specs2.specification.AfterAll
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.mvc.request.RemoteConnection
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import scala.concurrent.Await
import scala.concurrent.duration._

/** The app with its Host check (play.filters.hosts.AllowedHostsFilter, modules.AllowedHostsModule)
  * configured as production.conf does: no names in `play.filters.hosts.allowed`, a deployed name
  * in `wlxjury.allowedHosts` (WLXJURY_ALLOWED_HOSTS). Through the router, so that the readiness
  * route's `+ anyhost` counts. deployment.ProductionConfigSpec checks the production lists
  * themselves.
  */
class AllowedHostsSpec extends PlaySpecification with AfterAll {
  sequential

  private lazy val app: Application = {
    SharedTestDb.init()
    SharedTestDb.truncateAll()
    new GuiceApplicationBuilder()
      .configure(
        "db.default.driver"          -> SharedTestDb.driverClassName,
        "db.default.username"        -> SharedTestDb.username,
        "db.default.password"        -> SharedTestDb.password,
        "db.default.url"             -> SharedTestDb.jdbcUrl,
        "play.filters.hosts.allowed" -> Seq.empty[String],
        "wlxjury.allowedHosts"       -> "jury.example.org"
      )
      .build()
  }

  override def afterAll(): Unit =
    try Await.result(app.stop(), 30.seconds)
    finally SharedTestDb.reregisterDefault() // the app's pool was ScalikeJDBC's default

  private def get(path: String, host: String) =
    route(app, FakeRequest(GET, path).withHeaders(HOST -> host).withCSRFToken).get

  "a request's Host" should {
    "be refused with the 400 page when the app isn't served at it, without echoing it" in {
      val result = get("/auth", "attacker.example")
      status(result) === BAD_REQUEST
      contentAsString(result) must contain("The request could not be understood")
      contentAsString(result) must not(contain("attacker.example"))
    }

    "be refused for the loopback names too, which Apache's default vhost passes through" in {
      status(get("/auth", "127.0.0.1:9000")) === BAD_REQUEST
      status(get("/auth", "localhost")) === BAD_REQUEST
    }

    "be refused with the API's JSON under /api" in {
      val result = get("/api/contests", "attacker.example")
      status(result) === BAD_REQUEST
      contentAsJson(result) === Json.obj("error" -> "bad_request")
    }

    "pass for a configured name, with or without a port" in {
      // the sign-in page's form has the CSRF token, which the filter (off in tests) would add
      status(get("/auth", "jury.example.org")) === OK
      status(get("/auth", "jury.example.org:443")) === OK
    }
  }

  "the readiness check (+ anyhost)" should {
    "answer the deploy tools on the server at http://127.0.0.1:9000/health/ready, or any Host" in {
      forall(Seq("127.0.0.1:9000", "localhost:9000", "attacker.example")) { host =>
        val result = get("/health/ready", host)
        status(result) === OK
        (contentAsJson(result) \ "schemaVersion").asOpt[String] must beSome
      }
    }

    "reveal nothing to a client elsewhere, whatever the Host" in {
      forall(Seq("jury.example.org", "attacker.example", "127.0.0.1:9000")) { host =>
        val result = route(app, FakeRequest(GET, "/health/ready").withHeaders(HOST -> host)
          .withConnection(RemoteConnection("203.0.113.5", secure = false, clientCertificateChain = None))).get
        status(result) === NOT_FOUND
        contentAsString(result) must not(contain("schemaVersion"))
        contentAsString(result) must not(contain("build"))
      }
    }
  }
}
