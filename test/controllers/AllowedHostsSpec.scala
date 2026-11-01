package controllers

import db.scalikejdbc.{PlayTestDb, SharedTestDb}
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

/** The app with its Host check (play.filters.hosts.AllowedHostsFilter, modules.AllowedHostsModule)
  * and a deployed name in `wlxjury.allowedHosts`, as production.conf sets it from
  * WLXJURY_ALLOWED_HOSTS; the loopback names come from the tests' Play default list.
  * deployment.ProductionConfigSpec checks the production lists themselves.
  */
class AllowedHostsSpec extends PlaySpecification with PlayTestDb {
  sequential

  implicit val cfg: Map[String, String] = Map("wlxjury.allowedHosts" -> "jury.example.org")

  "a request's Host" should {
    "be refused with the 400 page when the app isn't served at it, without echoing it" in {
      testDbApp { implicit app =>
        SharedTestDb.truncateAll()
        val result = route(app, FakeRequest(GET, "/auth").withHeaders(HOST -> "attacker.example")).get
        status(result) === BAD_REQUEST
        contentAsString(result) must contain("The request could not be understood")
        contentAsString(result) must not(contain("attacker.example"))
      }
    }

    "be refused with the API's JSON under /api" in {
      testDbApp { implicit app =>
        val result = route(app, FakeRequest(GET, "/api/contests").withHeaders(HOST -> "attacker.example")).get
        status(result) === BAD_REQUEST
        contentAsJson(result) === play.api.libs.json.Json.obj("error" -> "bad_request")
      }
    }

    "pass for a configured name, with or without a port" in {
      testDbApp { implicit app =>
        // the sign-in page's form has the CSRF token, which the filter (off in tests) would add
        status(route(app, FakeRequest(GET, "/auth").withHeaders(HOST -> "jury.example.org").withCSRFToken).get) === OK
        status(route(app, FakeRequest(GET, "/auth").withHeaders(HOST -> "jury.example.org:443").withCSRFToken).get) === OK
      }
    }

    "pass for the deploy tools' readiness check (http://127.0.0.1:9000/health/ready)" in {
      testDbApp { implicit app =>
        val result = route(app, FakeRequest(GET, "/health/ready").withHeaders(HOST -> "127.0.0.1:9000")).get
        status(result) === OK
        (contentAsJson(result) \ "schemaVersion").asOpt[String] must beSome
      }
    }
  }
}
