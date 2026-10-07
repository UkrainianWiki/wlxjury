package gatling.simulations

import gatling.setup.GatlingTestFixture
import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._

class VotingSimulation extends Simulation {

  private val baseUrl = GatlingTestFixture.baseUrl
  private val cfg     = gatling.setup.GatlingConfig

  private val jurorFeeder = Iterator.continually(GatlingTestFixture.jurorsIn(None)).flatten.map {
    case (id, email, pass) =>
      Map("userId" -> id.toString, "email" -> email, "password" -> pass)
  }

  // A vote on one of the juror's own images, in one of the juror's rounds
  private val nextVote = exec { session =>
    val (pageId, roundId, rate) = GatlingTestFixture.nextVote(session("userId").as[String].toLong)
    session.set("pageId", pageId).set("roundId", roundId).set("rate", rate)
  }

  private val scn = scenario("Voting")
    .feed(jurorFeeder)
    .exec(http("login")
      .post("/auth")
      .formParam("login", "#{email}")
      .formParam("password", "#{password}")
      .check(status.is(303)))
    .exitHereIfFailed // no unauthenticated requests "succeeding" on the login page
    .repeat(15) {
      exec(nextVote)
        .exec(http("cast vote")
          .post("/rate/round/#{roundId}/pageid/#{pageId}/select/#{rate}")
          .check(status.is(200), bodyString.is("success")))
    }

  setUp(
    scn.inject(
      rampUsers(cfg.users).during(cfg.rampUpSeconds.seconds),
      constantUsersPerSec(cfg.users.toDouble / 10).during(cfg.durationSeconds.seconds)
    )
  ).protocols(http.baseUrl(baseUrl).disableFollowRedirect)
}
