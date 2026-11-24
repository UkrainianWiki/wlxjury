package api

import db.scalikejdbc.PlayTestDb
import play.api.test.PlaySpecification
import sttp.model.Method

/** Every API endpoint is named and classified for read-only, server-only tokens
  * ([[ApiAuth.Endpoints]]): a new endpoint fails here until it is listed, and only a GET endpoint
  * may be open to such tokens (support diagnostics plan §7; D-1b review L5). Being on the list
  * also means it only reads: a reviewer's check when one is added.
  */
class ApiEndpointScopeSpec extends PlaySpecification with PlayTestDb {

  "the API's endpoints" should {
    "each be named, once, and classified for read-only tokens; those open to them GET only" in {
      testDbApp { app =>
        val endpoints = app.injector.instanceOf[Api].apiEndpoints.map(_.endpoint)
        val names = endpoints.map(_.info.name)
        names.forall(_.isDefined) must beTrue
        names.flatten.distinct must haveSize(endpoints.size)
        names.flatten.toSet === ApiAuth.Endpoints.keySet
        val openToReadOnly = endpoints.filter(e => e.info.name.exists(ApiAuth.LocalReadEndpoints.contains))
        openToReadOnly must haveSize(ApiAuth.LocalReadEndpoints.size)
        openToReadOnly.filterNot(_.method.contains(Method.GET)).flatMap(_.info.name) must beEmpty
        ApiAuth.LocalReadEndpoints === Set("listContests", "getContest", "contestHealth", "recentActivity",
          "reuseReport", "reuseContest")
      }
    }
  }
}
