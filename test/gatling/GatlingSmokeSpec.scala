package gatling

import gatling.setup.GatlingTestFixture
import org.specs2.mutable.Specification

import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}
import java.net.{CookieManager, CookiePolicy, URI}

/** Single-request smoke test for each Gatling scenario.
 *  Verifies that one representative endpoint per simulation responds in < 1s
 *  against the full 38k-image fixture.
 *
 *  Run: sbt "testOnly gatling.GatlinSmokeSpec"
 *
 *  NOTE: First run triggers GatlingTestFixture initialisation (Docker + data load),
 *  which takes several minutes. Subsequent runs in the same JVM are instant.
 */
class GatlingSmokeSpec extends Specification {
  sequential

  private val MaxMs     = 1000L
  private lazy val base = s"http://localhost:${GatlingTestFixture.port}"
  private val f         = GatlingTestFixture

  // ── HTTP helpers ──────────────────────────────────────────────────────────

  private def newClient(): HttpClient =
    HttpClient.newBuilder()
      .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build()

  private def enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

  private def formBody(params: (String, String)*): String =
    params.map { case (k, v) => s"${enc(k)}=${enc(v)}" }.mkString("&")

  case class Resp(status: Int, ms: Long)

  private def doGet(cl: HttpClient, path: String): Resp = {
    val t0 = System.currentTimeMillis()
    val r  = cl.send(
      HttpRequest.newBuilder().uri(URI.create(s"$base$path")).GET().build(),
      BodyHandlers.discarding()
    )
    Resp(r.statusCode(), System.currentTimeMillis() - t0)
  }

  private def doPost(cl: HttpClient, path: String, body: String): Resp = {
    val t0 = System.currentTimeMillis()
    val r  = cl.send(
      HttpRequest.newBuilder()
        .uri(URI.create(s"$base$path"))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(BodyPublishers.ofString(body))
        .build(),
      BodyHandlers.discarding()
    )
    Resp(r.statusCode(), System.currentTimeMillis() - t0)
  }

  private def jurorClient(): (HttpClient, Long) = {
    val (jurorId, email, pass) = f.jurors.head
    val cl = newClient()
    doPost(cl, "/auth", formBody("login" -> email, "password" -> pass))
    (cl, jurorId)
  }

  private def organizerClient(): HttpClient = {
    val (_, email, pass) = f.organizer
    val cl = newClient()
    doPost(cl, "/auth", formBody("login" -> email, "password" -> pass))
    cl
  }

  // ── Scenario smoke checks ─────────────────────────────────────────────────

  "JurorGallery smoke" in {
    val (cl, jurorId) = jurorClient()
    val r = doGet(cl, s"/gallery/round/${f.roundBinaryId}/user/$jurorId/page/1")
    r.status must_== 200
    r.ms must be_<=(MaxMs)
  }

  "RegionFilter smoke" in {
    val (cl, jurorId) = jurorClient()
    val region = f.regions.head
    val r = doGet(cl, s"/gallery/round/${f.roundBinaryId}/user/$jurorId/region/$region/page/1")
    r.status must_== 200
    r.ms must be_<=(MaxMs)
  }

  "RegionStat smoke" in {
    // byRegionStat is called during gallery page render when contest.monumentIdTemplate.isDefined
    // (set in GatlingDbSetup.load). This test verifies both that the query stays < 1s and that
    // it actually returns region data (body contains a known region code).
    val (cl, jurorId) = jurorClient()
    val t0  = System.currentTimeMillis()
    val req = HttpRequest.newBuilder()
      .uri(URI.create(s"$base/gallery/round/${f.roundBinaryId}/user/$jurorId/page/1"))
      .GET().build()
    val resp = cl.send(req, BodyHandlers.ofString())
    val ms   = System.currentTimeMillis() - t0
    resp.statusCode() must_== 200
    ms must be_<=(MaxMs)
    resp.body() must contain(f.regions.head)
  }

  "AggregatedRatings smoke" in {
    val cl = organizerClient()
    val r  = doGet(cl, s"/roundstat/${f.roundRatingId}")
    r.status must_== 200
    r.ms must be_<=(MaxMs)
  }

  "RoundManagement smoke" in {
    val cl = organizerClient()
    val r  = doGet(cl, s"/admin/rounds?contestId=${f.contestId}")
    r.status must_== 200
    r.ms must be_<=(MaxMs)
  }

  "Voting smoke" in {
    val (cl, _) = jurorClient()
    val (_, pageId, roundId, rate) = f.votingPairs.head
    val r = doPost(cl, s"/rate/round/$roundId/pageid/$pageId/select/$rate", "")
    (r.status == 200 || r.status == 303) must beTrue
    r.ms must be_<=(MaxMs)
  }
}
