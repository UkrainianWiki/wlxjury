package gatling.simulations

import gatling.setup.{GatlingConfig, GatlingTestFixture}
import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._
import scala.util.Random

/** Organizers watching juror progress on /roundstat: a few organizer sessions, each
  * looking at a round's stats and then thinking for 15-45 s (x pauseScale).
  * `-Dgatling.aggRatings.stress=true` runs the old worst case instead: many concurrent
  * sessions of one organizer requesting /roundstat with no pauses.
  *
  * Redirects are not followed and the check needs the page's own content, so a session
  * that lost its login can't "succeed" on the login page.
  */
class AggregatedRatingsSimulation extends Simulation {

  private val baseUrl = s"http://localhost:${GatlingTestFixture.port}"
  private val cfg     = GatlingConfig.AggRatings

  private val rounds = Seq(GatlingTestFixture.roundBinaryId, GatlingTestFixture.roundRatingId)

  /** A human think time of `sec` seconds, scaled by pauseScale. */
  private def t(sec: Int): FiniteDuration = (sec * cfg.pauseScale * 1000).round.millis

  private val organizerFeeder = Iterator.continually(GatlingTestFixture.organizers).flatten.map {
    case (_, email, pass) => Map("orgEmail" -> email, "orgPassword" -> pass)
  }

  private val login =
    exec(http("login organizer").post("/auth")
      .formParam("login", "#{orgEmail}").formParam("password", "#{orgPassword}")
      .check(status.is(303)))
      .exitHereIfFailed

  private val roundStat =
    exec(session => session.set("roundId", rounds(Random.nextInt(rounds.length))))
      .exec(http("round stats")
        .get("/roundstat/#{roundId}")
        .check(status.is(200), substring("/roundstat/")))

  private val organizerScn = scenario("Aggregated Ratings")
    .feed(organizerFeeder)
    .exec(login)
    .during(cfg.durationSeconds.seconds) {
      exec(roundStat).pause(t(15), t(45))
    }

  private val (_, orgEmail, orgPassword) = GatlingTestFixture.organizer
  private val stressScn = scenario("Aggregated Ratings (stress)")
    .exec(session => session.set("orgEmail", orgEmail).set("orgPassword", orgPassword))
    .exec(login)
    .repeat(cfg.stressRepeat)(roundStat)

  private val setUpBuilder = setUp(
    if (cfg.stress)
      stressScn.inject(
        rampUsers(cfg.stressUsers).during(GatlingConfig.rampUpSeconds.seconds),
        constantUsersPerSec(cfg.stressUsers.toDouble / 10).during(GatlingConfig.durationSeconds.seconds))
    else
      organizerScn.inject(rampUsers(cfg.organizers).during(cfg.rampUpSeconds.seconds))
  ).protocols(http.baseUrl(baseUrl).disableFollowRedirect)

  // A safety net for the default flow only: the stress run drains its whole queue, as before.
  if (!cfg.stress) setUpBuilder.maxDuration((cfg.durationSeconds + cfg.rampUpSeconds + 180).seconds)
}
