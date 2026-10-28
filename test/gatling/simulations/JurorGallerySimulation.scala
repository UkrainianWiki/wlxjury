package gatling.simulations

import gatling.setup.GatlingTestFixture
import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._
import scala.util.Random

class JurorGallerySimulation extends Simulation {

  private val baseUrl = GatlingTestFixture.baseUrl
  private val cfg     = gatling.setup.GatlingConfig
  // the gallery's order (sort=file or rate), to compare them; the juror's default if none
  private val sortQuery = sys.props.get("gatling.gallerySort").fold("")("?sort=" + _)


  private val jurorFeeder = Iterator.continually(GatlingTestFixture.jurorsIn(None)).flatten.map {
    case (id, email, pass) =>
      Map("userId" -> id.toString, "email" -> email, "password" -> pass)
  }

  private val scn = scenario("Juror Gallery")
    .feed(jurorFeeder)
    .exec(http("login")
      .post("/auth")
      .formParam("login", "#{email}")
      .formParam("password", "#{password}")
      .check(status.is(303)))
    .exitHereIfFailed // no unauthenticated requests "succeeding" on the login page
    .repeat(10) {
      exec { session =>
        val own   = GatlingTestFixture.roundsOf(session("userId").as[String].toLong)
        val round = own(Random.nextInt(own.length))
        val page  = Random.nextInt(5) + 1
        session
          .set("roundId", round)
          .set("page", page)
      }
        .exec(http("gallery page")
          .get("/gallery/round/#{roundId}/user/#{userId}/page/#{page}" + sortQuery)
          .header("X-Timing-Label", "gallery page")
          .check(status.is(200)))
    }

  // with -Dgatling.timing=true, the app's DB pool and the GC beside the timing log
  before(if (cfg.timing) gatling.setup.PoolWatcher.start())
  after(if (cfg.timing) gatling.setup.PoolWatcher.stop("gallery"))

  setUp(
    scn.inject(
      rampUsers(cfg.users).during(cfg.rampUpSeconds.seconds),
      constantUsersPerSec(cfg.users.toDouble / 10).during(cfg.durationSeconds.seconds)
    )
  ).protocols(http.baseUrl(baseUrl).disableFollowRedirect)
}
