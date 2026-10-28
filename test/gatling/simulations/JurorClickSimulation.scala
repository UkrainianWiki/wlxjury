package gatling.simulations

import gatling.setup.{GatlingConfig, GatlingTestFixture, PoolWatcher}
import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._
import scala.util.Random

/** A juror clicking through their files (docs/plans/2026-10-28-gallery-latency.md): a gallery
  * page, a file's large view, votes there (each a POST and its redirect to the next file's
  * large view), Esc back to the gallery (the `pageid` redirect, then the page), over again.
  *
  * Realistic by default: `gatling.clicks.jurors` sessions at once, each pausing
  * `pauseMinSeconds`–`pauseMaxSeconds` between clicks for `durationSeconds`. With
  * `gatling.clicks.stress = true` sessions arrive as [[JurorGallerySimulation]]'s (the
  * fixture's 20 users over 10 s, then 2 a second), one visit each, with no pauses.
  * `-Dgatling.timing=true` also logs the app's per-request breakdown and the DB pool and GC.
  */
class JurorClickSimulation extends Simulation {

  private val c = GatlingConfig.Clicks

  private val jurorFeeder = Iterator.continually(GatlingTestFixture.jurorsIn(None)).flatten.map {
    case (id, email, pass) => Map("userId" -> id.toString, "email" -> email, "password" -> pass)
  }

  private def get(name: String, url: String) = http(name).get(url).header("X-Timing-Label", name)

  private def think = if (c.stress) exec(session => session) else pause(c.pauseMin.seconds, c.pauseMax.seconds)

  private val Large = "\"(/large/round/[^\"]+)\"".r

  private val visit =
    exec { session =>
      val own = GatlingTestFixture.roundsOf(session("userId").as[String].toLong)
      session.set("roundId", own(Random.nextInt(own.length))).set("page", Random.nextInt(5) + 1)
    }
      .exec(get("gallery page", "/gallery/round/#{roundId}/user/#{userId}/page/#{page}")
        .check(status.is(200), regex(Large.regex).findAll.saveAs("largeUrls")))
      .exec(think)
      .exec { session =>
        val urls = session("largeUrls").as[Seq[String]]
        val url = urls(Random.nextInt(urls.size)).replace("&amp;", "&")
        session.set("largeUrl", url).set("pageId", "pageid/(\\d+)".r.findFirstMatchIn(url).get.group(1))
      }
      .exec(get("large view", "#{largeUrl}").check(status.is(200)))
      .repeat(c.votesPerVisit) {
        exec(think)
          .exec { session =>
            val rated = session("roundId").as[Long] == GatlingTestFixture.roundRatingId
            val rate = if (rated) Random.nextInt(GatlingTestFixture.ratedMaxRate) + 1 else if (Random.nextBoolean()) 1 else -1
            session.set("rate", rate)
          }
          .exec(http("vote").post("/large/round/#{roundId}/pageid/#{pageId}/select/#{rate}?sort=file")
            .header("X-Timing-Label", "vote")
            .check(status.is(303), header("Location").saveAs("next")))
          .exec(get("large view after vote", "#{next}").check(status.is(200)))
          .exec { session =>
            val next = session("next").as[String]
            "pageid/(\\d+)".r.findFirstMatchIn(next).fold(session)(m => session.set("pageId", m.group(1)))
          }
      }
      .exec(think)
      .exec(get("back to gallery (pageid)", "/gallery/round/#{roundId}/user/#{userId}/region/all/pageid/#{pageId}?sort=file")
        .check(status.is(303), header("Location").transform(_.takeWhile(_ != '#')).saveAs("back")))
      .exec(get("gallery page at file", "#{back}").check(status.is(200)))
      .exec(think)

  private val login = feed(jurorFeeder)
    .exec(http("login").post("/auth")
      .formParam("login", "#{email}")
      .formParam("password", "#{password}")
      .check(status.is(303)))
    .exitHereIfFailed

  private val scn =
    if (c.stress) scenario("Juror clicks (stress)").exec(login).exec(visit)
    else scenario("Juror clicks").exec(login).during(c.durationSeconds.seconds)(visit)

  before(if (GatlingConfig.timing) PoolWatcher.start())
  after(if (GatlingConfig.timing) PoolWatcher.stop(if (c.stress) "clicks-stress" else s"clicks-${c.jurors}"))

  setUp(
    if (c.stress)
      scn.inject(
        rampUsers(GatlingConfig.users).during(GatlingConfig.rampUpSeconds.seconds),
        constantUsersPerSec(GatlingConfig.users.toDouble / 10).during(GatlingConfig.durationSeconds.seconds)
      )
    else scn.inject(rampUsers(c.jurors).during(c.rampUpSeconds.seconds))
  ).protocols(http.baseUrl(GatlingTestFixture.baseUrl).disableFollowRedirect)
}
