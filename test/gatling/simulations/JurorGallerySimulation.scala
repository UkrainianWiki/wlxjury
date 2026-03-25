package gatling.simulations

import gatling.setup.GatlingTestFixture
import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._
import scala.util.Random

class JurorGallerySimulation extends Simulation {

  private val baseUrl = s"http://localhost:${GatlingTestFixture.port}"
  private val cfg     = gatling.setup.GatlingConfig

  private val rounds = Seq(GatlingTestFixture.roundBinaryId, GatlingTestFixture.roundRatingId)

  private val jurorFeeder = Iterator.continually(GatlingTestFixture.jurors).flatten.map {
    case (id, email, pass) =>
      Map("userId" -> id.toString, "email" -> email, "password" -> pass)
  }

  private val scn = scenario("Juror Gallery")
    .feed(jurorFeeder)
    .exec(http("login")
      .post("/auth")
      .formParam("login", "#{email}")
      .formParam("password", "#{password}")
      .check(status.in(200, 303)))
    .repeat(10) {
      exec { session =>
        val round = rounds(Random.nextInt(rounds.length))
        val page  = Random.nextInt(5) + 1
        session
          .set("roundId", round)
          .set("page", page)
      }
        .exec(http("gallery page")
          .get("/gallery/round/#{roundId}/user/#{userId}/page/#{page}")
          .check(status.is(200)))
    }

  setUp(
    scn.inject(
      rampUsers(cfg.users).during(cfg.rampUpSeconds.seconds),
      constantUsersPerSec(cfg.users.toDouble / 10).during(cfg.durationSeconds.seconds)
    )
  ).protocols(http.baseUrl(baseUrl))
}
