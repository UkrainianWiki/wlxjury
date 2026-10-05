package gatling.simulations

import gatling.setup.{GatlingConfig, GatlingTestFixture}
import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._

/** Round management as organizers actually do it: a few organizer sessions browsing
  * list -> edit -> save -> list -> stats -> list with think time, at least an order of
  * magnitude below VotingSimulation. `-Dgatling.roundMgmt.stress=true` runs the old
  * worst case instead: many concurrent sessions of one organizer hammering the rounds
  * list with no pauses, now with strict checks.
  *
  * Redirects are not followed and every check needs the page's own content, so a
  * session that lost its login can't "succeed" on the login page.
  */
class RoundManagementSimulation extends Simulation {

  private val baseUrl   = s"http://localhost:${GatlingTestFixture.port}"
  private val cfg       = GatlingConfig.RoundMgmt
  private val contestId = GatlingTestFixture.contestId
  private val jurorIds  = GatlingTestFixture.jurors.map(_._1)

  /** A human think time of `sec` seconds, scaled by pauseScale. */
  private def t(sec: Int): FiniteDuration = (sec * cfg.pauseScale * 1000).round.millis

  // (id, number, name, ratesId): saving with the same values is idempotent
  private val roundFeeder = Iterator.continually(Seq(
    (GatlingTestFixture.roundBinaryId, 1, "Binary Round", 1),
    (GatlingTestFixture.roundRatingId, 2, "Rating Round", GatlingConfig.maxRate)
  )).flatten.map { case (id, n, name, rates) =>
    Map("roundId" -> id, "roundNumber" -> n, "roundName" -> name, "rates" -> rates)
  }

  private val organizerFeeder = Iterator.continually(GatlingTestFixture.organizers).flatten.map {
    case (_, email, pass) => Map("orgEmail" -> email, "orgPassword" -> pass)
  }

  private val login =
    exec(http("login organizer").post("/auth")
      .formParam("login", "#{orgEmail}").formParam("password", "#{orgPassword}")
      .check(status.is(303)))
      .exitHereIfFailed // no unauthenticated requests "succeeding" on the login page

  private val roundsList = http("rounds list")
    .get(s"/admin/rounds?contestId=$contestId")
    .check(status.is(200), substring("/admin/rounds/edit"))

  private val editRound = http("edit round")
    .get(s"/admin/rounds/edit?id=#{roundId}&contestId=$contestId")
    .check(status.is(200), substring("/admin/rounds/save"))

  // Field names as in controllers.EditRound.editRoundForm
  private val saveRound = http("save round")
    .post("/admin/rounds/save")
    .formParam("id", "#{roundId}").formParam("number", "#{roundNumber}")
    .formParam("name", "#{roundName}").formParam("contest", contestId.toString)
    .formParam("roles", "jury").formParam("distribution", "0").formParam("rates", "#{rates}")
    .formParam("minMpx", "").formParam("minSize", "").formParam("mediaType", "all")
    .formParam("jurors[0]", jurorIds.head.toString) // required by the form; ignored for an existing round
    .check(status.is(303))

  private val roundStat = http("round stats")
    .get("/roundstat/#{roundId}")
    .check(status.is(200), substring("/roundstat/"))

  // Optional, once per simulation (gatling.roundMgmt.createRound). The round draws its
  // images from the contest category, one juror per image. Each simulation restores the
  // DB dump, so the extra round doesn't leak into other simulations.
  private val createRound = http("create round")
    .post("/admin/rounds/save")
    .formParam("number", "0").formParam("name", "Gatling created round")
    .formParam("contest", contestId.toString).formParam("roles", "jury")
    .formParam("distribution", "1").formParam("rates", "1")
    .formParam("minMpx", "").formParam("minSize", "").formParam("mediaType", "all")
    .formParamSeq(jurorIds.zipWithIndex.map { case (id, i) => s"jurors[$i]" -> id.toString })
    .requestTimeout(GatlingConfig.Distribution.timeoutMinutes.minutes)
    .check(status.is(303))

  private val organizerScn = scenario("Round Management")
    .feed(organizerFeeder)
    .exec(login)
    .exec(roundsList).pause(t(3), t(10))
    .doIf(session => cfg.createRound && session.userId == 1L) {
      exec(createRound).pause(t(5), t(15))
    }
    .during(cfg.durationSeconds.seconds) {
      feed(roundFeeder)
        .exec(editRound).pause(t(10), t(30)) // read / change the form
        .exec(saveRound)
        .exec(roundsList).pause(t(5), t(15)) // the browser follows the save redirect
        .exec(roundStat).pause(t(15), t(45)) // look at juror progress
        .exec(roundsList).pause(t(5), t(15))
    }

  // Old worst case, kept as an explicit opt-in (single organizer, no think time).
  private val (_, orgEmail, orgPassword) = GatlingTestFixture.organizer
  private val stressScn = scenario("Round Management (stress)")
    .exec(session => session.set("orgEmail", orgEmail).set("orgPassword", orgPassword))
    .exec(login)
    .repeat(cfg.stressRepeat)(exec(roundsList))

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
