package gatling.setup

import scalikejdbc._

import java.io.{File, PrintWriter}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{CookieManager, URI, URLEncoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.util.Random

/** One juror's clicks, one request at a time, over HTTP to the fixture's TestServer
  * (docs/plans/2026-10-28-gallery-latency.md, Phase 1 step 1): gallery pages (1, 5, 50, the
  * last; all regions and the juror's largest; file and rate order), the large view (a file
  * early, in the middle, at the end of the list), the `pageid` redirect (Esc), the
  * organisers' all-jurors page, and votes → redirect → the next large view. Each request
  * carries `X-Timing-Label`, so the timing log (`-Dgatling.timing=true`, required) has its
  * breakdown per case; the client's own times go to `latency-client-<stamp>.csv`.
  *
  * {{{
  * sbt 'set Test / javaOptions ++= Seq("-Xmx512m", "-Dfile.encoding=UTF-8", "-Dgatling.timing=true")' \
  *     'Test/runMain gatling.setup.GalleryLatencyBenchmark'
  * }}}
  * `-Dbench.samples` (50) and `-Dbench.warmup` (10) per case.
  */
object GalleryLatencyBenchmark {
  private def prop(key: String, default: String) = sys.props.getOrElse(s"bench.$key", default)
  private val samples = prop("samples", "50").toInt
  private val warmup = prop("warmup", "10").toInt

  def main(args: Array[String]): Unit = {
    val status = try { run(); 0 } catch {
      case error: Throwable => error.printStackTrace(); 1
    }
    sys.exit(status)
  }

  private final class Client(base: String) {
    private val http = HttpClient.newBuilder()
      .cookieHandler(new CookieManager())
      .followRedirects(HttpClient.Redirect.NEVER)
      .build()

    def send(method: String, path: String, label: String, form: Map[String, String] = Map.empty)
        : (Int, String, Option[String], Double) = {
      val body = form.map { case (k, v) => k + "=" + URLEncoder.encode(v, UTF_8) }.mkString("&")
      val request = HttpRequest.newBuilder(URI.create(base + path))
        .header("X-Timing-Label", label)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .method(method, if (method == "GET") HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        .build()
      val start = System.nanoTime()
      val response = http.send(request, HttpResponse.BodyHandlers.ofString())
      val ms = (System.nanoTime() - start) / 1e6
      val location = response.headers().firstValue("Location")
      (response.statusCode(), response.body(), if (location.isPresent) Some(location.get) else None, ms)
    }
  }

  private def run(): Unit = {
    require(!GatlingConfig.external, "Use the disposable fixture, never an external target")
    require(GatlingConfig.timing, "Run with -Dgatling.timing=true: the breakdown is the timing log's")
    val f = GatlingTestFixture
    implicit val session: DBSession = AutoSession

    val stamp = LocalDateTime.now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val dir = new File("target/gatling")
    dir.mkdirs()
    val out = new PrintWriter(new File(dir, s"latency-client-$stamp.csv"), "UTF-8")
    out.println("label,sample,status,ms")
    println(s"[latency] client times: target/gatling/latency-client-$stamp.csv, timing log: ${GatlingConfig.timingFile}")

    // the juror with the most files in both rounds
    val (jurorId, email, password) = f.jurors.filter(j => f.roundsOf(j._1).size == 2).maxBy { j =>
      sql"SELECT COUNT(*) FROM selection WHERE jury_id = ${j._1}".map(_.int(1)).single().getOrElse(0)
    }
    val juror = new Client(f.baseUrl)
    val (loginStatus, _, _, _) = juror.send("POST", "/auth", "login", Map("login" -> email, "password" -> password))
    require(loginStatus == 303, s"login failed: $loginStatus")
    val organizer = new Client(f.baseUrl)
    val (orgStatus, _, _, _) =
      organizer.send("POST", "/auth", "login", Map("login" -> f.organizer._2, "password" -> f.organizer._3))
    require(orgStatus == 303, s"organizer login failed: $orgStatus")

    def measure(client: Client, label: String, n: Int = samples)(path: Int => String): Unit = {
      (0 until warmup).foreach(i => client.send("GET", path(i), "warmup"))
      for (i <- 0 until n) {
        val (status, _, _, ms) = client.send("GET", path(i), label)
        require(status == 200 || status == 303, s"$label: $status for ${path(i)}")
        out.println(f"$label,$i,$status,$ms%.2f")
      }
      out.flush()
      println(s"[latency] $label done")
    }

    val rounds = Seq("binary" -> f.roundBinaryId, "rated" -> f.roundRatingId)
    for ((kind, roundId) <- rounds) {
      val count = sql"SELECT COUNT(*) FROM selection WHERE jury_id = $jurorId AND round_id = $roundId"
        .map(_.int(1)).single().getOrElse(0)
      // the juror's largest region, as the region nav and the region filter have it
      val (region, regionCount) = sql"""SELECT NULLIF(LEFT(SUBSTRING_INDEX(monument_id, '-', 1), 3), '') r, COUNT(*) n
           FROM selection WHERE jury_id = $jurorId AND round_id = $roundId GROUP BY r ORDER BY n DESC"""
        .map(rs => rs.stringOpt(1) -> rs.int(2)).list().collectFirst { case (Some(r), n) => (r, n) }.get
      println(s"[latency] $kind round $roundId: juror $jurorId has $count files, region $region $regionCount")

      for (order <- Seq("file", "rate"); (reg, n) <- Seq("all" -> count, region -> regionCount)) {
        val last = (n + 14) / 15
        for (page <- Seq(1, 5, 50, last).distinct.filter(_ <= last)) {
          val p = if (page == last) "last" else page.toString
          val regionPath = if (reg == "all") "" else s"/region/$reg"
          measure(juror, s"gallery-$kind-$order-${if (reg == "all") "all" else "region"}-p$p")(_ =>
            s"/gallery/round/$roundId/user/$jurorId$regionPath/page/$page?sort=$order")
        }
      }

      // files at the start, the middle and the end of the list in file order
      def fileAt(position: Int): Long =
        sql"""SELECT page_id FROM selection WHERE jury_id = $jurorId AND round_id = $roundId
              ORDER BY monument_id, page_id LIMIT 1 OFFSET $position""".map(_.long(1)).single().get
      val files = Seq("early" -> fileAt(2), "middle" -> fileAt(count / 2), "end" -> fileAt(count - 1))
      for (order <- Seq("file", "rate"); (where, pageId) <- files) {
        measure(juror, s"large-$kind-$order-$where")(_ =>
          s"/large/round/$roundId/user/$jurorId/pageid/$pageId?sort=$order")
      }
      for (order <- Seq("file", "rate")) {
        measure(juror, s"atfile-$kind-$order")(_ =>
          s"/gallery/round/$roundId/user/$jurorId/region/all/pageid/${files(1)._2}?sort=$order")
      }
      measure(organizer, s"organizer-$kind-byrate-p1")(_ => s"/byrate/round/$roundId/user/0/page/1")
      measure(organizer, s"organizer-$kind-large-middle")(_ =>
        s"/large/round/$roundId/user/0/pageid/${files(1)._2}?sort=rate")
    }

    // votes last: they change the juror's lists. Each vote goes on to the next file's large view.
    val random = new Random(1)
    for ((kind, roundId) <- rounds; order <- Seq("file", "rate")) {
      val count = sql"SELECT COUNT(*) FROM selection WHERE jury_id = $jurorId AND round_id = $roundId"
        .map(_.int(1)).single().getOrElse(0)
      val start = sql"""SELECT page_id FROM selection WHERE jury_id = $jurorId AND round_id = $roundId
            ORDER BY monument_id, page_id LIMIT 1 OFFSET ${count / 3 + (if (order == "rate") 200 else 0)}"""
        .map(_.long(1)).single().get
      var pageId = start
      for (i <- 0 until warmup + samples) {
        val rate = if (kind == "rated") random.nextInt(f.ratedMaxRate) + 1 else if (random.nextBoolean()) 1 else -1
        val warm = i < warmup
        val (status, _, location, ms) =
          juror.send("POST", s"/large/round/$roundId/pageid/$pageId/select/$rate?sort=$order",
            if (warm) "warmup" else s"vote-$kind-$order")
        require(status == 303 && location.isDefined, s"vote: $status")
        val (nextStatus, _, _, nextMs) = juror.send("GET", location.get, if (warm) "warmup" else s"vote-next-$kind-$order")
        require(nextStatus == 200, s"after vote: $nextStatus for ${location.get}")
        if (!warm) {
          out.println(f"vote-$kind-$order,${i - warmup},$status,$ms%.2f")
          out.println(f"vote-next-$kind-$order,${i - warmup},$nextStatus,$nextMs%.2f")
        }
        pageId = "pageid/(\\d+)".r.findFirstMatchIn(location.get).map(_.group(1).toLong).getOrElse(pageId)
      }
      out.flush()
      println(s"[latency] votes $kind $order done")
    }
    out.close()
  }
}
