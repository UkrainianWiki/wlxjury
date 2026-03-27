package gatling.simulations

import gatling.setup.GatlingTestFixture
import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._
import scala.util.Random

class AggregatedRatingsSimulation extends Simulation {

  private val baseUrl = s"http://localhost:${GatlingTestFixture.port}"
  private val cfg     = gatling.setup.GatlingConfig

  private val rounds  = Seq(GatlingTestFixture.roundBinaryId, GatlingTestFixture.roundRatingId)
  private val (_, orgEmail, orgPassword) = GatlingTestFixture.organizer

  private val scn = scenario("Aggregated Ratings")
    .exec(http("login organizer")
      .post("/auth")
      .disableFollowRedirect
      .formParam("login", orgEmail)
      .formParam("password", orgPassword)
      .check(status.is(303)))
    .repeat(20) {
      exec { session =>
        session.set("roundId", rounds(Random.nextInt(rounds.length)))
      }
        .exec(http("round stats")
          .get("/roundstat/#{roundId}")
          .check(status.is(200)))
    }

  setUp(
    scn.inject(
      rampUsers(cfg.users).during(cfg.rampUpSeconds.seconds),
      constantUsersPerSec(cfg.users.toDouble / 10).during(cfg.durationSeconds.seconds)
    )
  ).protocols(http.baseUrl(baseUrl))
}
