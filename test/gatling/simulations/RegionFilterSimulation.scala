package gatling.simulations

import gatling.setup.GatlingTestFixture
import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._
import scala.util.Random

class RegionFilterSimulation extends Simulation {

  private val baseUrl = GatlingTestFixture.baseUrl
  private val cfg     = gatling.setup.GatlingConfig

  private val regions = GatlingTestFixture.regions

  private val jurorFeeder = Iterator.continually(GatlingTestFixture.jurorsIn(None)).flatten.map {
    case (id, email, pass) =>
      Map("userId" -> id.toString, "email" -> email, "password" -> pass)
  }

  private val scn = scenario("Region Filter Gallery")
    .feed(jurorFeeder)
    .exec(http("login")
      .post("/auth")
      .formParam("login", "#{email}")
      .formParam("password", "#{password}")
      .check(status.is(303)))
    .exitHereIfFailed // no unauthenticated requests "succeeding" on the login page
    .repeat(10) {
      exec { session =>
        val own    = GatlingTestFixture.roundsOf(session("userId").as[String].toLong)
        val round  = own(Random.nextInt(own.length))
        val region = regions(Random.nextInt(regions.length))
        val page   = Random.nextInt(3) + 1
        session
          .set("roundId", round)
          .set("region", region)
          .set("page", page)
      }
        .exec(http("region gallery page")
          .get("/gallery/round/#{roundId}/user/#{userId}/region/#{region}/page/#{page}")
          .check(status.is(200)))
    }

  setUp(
    scn.inject(
      rampUsers(cfg.users).during(cfg.rampUpSeconds.seconds),
      constantUsersPerSec(cfg.users.toDouble / 10).during(cfg.durationSeconds.seconds)
    )
  ).protocols(http.baseUrl(baseUrl).disableFollowRedirect)
}
