package gatling.simulations

import gatling.setup.GatlingTestFixture
import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._

class VotingSimulation extends Simulation {

  private val baseUrl = s"http://localhost:${GatlingTestFixture.port}"
  private val cfg     = gatling.setup.GatlingConfig

  private val voteFeeder = Iterator.continually(GatlingTestFixture.votingPairs).flatten.map {
    case (juryId, pageId, roundId, rate) =>
      Map(
        "juryId"  -> juryId.toString,
        "pageId"  -> pageId.toString,
        "roundId" -> roundId.toString,
        "rate"    -> rate.toString
      )
  }

  private val jurorFeeder = Iterator.continually(GatlingTestFixture.jurors).flatten.map {
    case (id, email, pass) =>
      Map("userId" -> id.toString, "email" -> email, "password" -> pass)
  }

  private val scn = scenario("Voting")
    .feed(jurorFeeder)
    .exec(http("login")
      .post("/auth")
      .formParam("login", "#{email}")
      .formParam("password", "#{password}")
      .check(status.in(200, 303)))
    .repeat(15) {
      feed(voteFeeder)
        .exec(http("cast vote")
          .post("/rate/round/#{roundId}/pageid/#{pageId}/select/#{rate}")
          .check(status.in(200, 303)))
    }

  setUp(
    scn.inject(
      rampUsers(cfg.users).during(cfg.rampUpSeconds.seconds),
      constantUsersPerSec(cfg.users.toDouble / 10).during(cfg.durationSeconds.seconds)
    )
  ).protocols(http.baseUrl(baseUrl))
}
