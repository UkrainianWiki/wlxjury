package gatling.simulations

import gatling.setup.GatlingTestFixture
import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._

class RoundManagementSimulation extends Simulation {

  private val baseUrl = s"http://localhost:${GatlingTestFixture.port}"
  private val cfg     = gatling.setup.GatlingConfig

  private val (_, orgEmail, orgPassword) = GatlingTestFixture.organizer

  private val scn = scenario("Round Management")
    .exec(http("login organizer")
      .post("/auth")
      .formParam("login", orgEmail)
      .formParam("password", orgPassword)
      .check(status.in(200, 303)))
    .repeat(20) {
      exec(http("rounds list")
        .get(s"/admin/rounds?contestId=${GatlingTestFixture.contestId}")
        .check(status.is(200)))
    }

  setUp(
    scn.inject(
      rampUsers(cfg.users).during(cfg.rampUpSeconds.seconds),
      constantUsersPerSec(cfg.users.toDouble / 10).during(cfg.durationSeconds.seconds)
    )
  ).protocols(http.baseUrl(baseUrl))
}
