package gatling.setup

import scalikejdbc._

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** Serves the Gatling fixture (GatlingTestFixture: a MariaDB testcontainer restored from
  * `data/cache/`, the app in a TestServer) for UI screenshots
  * (docs/plans/2026-10-31-theme-modes-and-design-system.md, "Screenshots").
  *
  * When the app is up it writes `target/ui-shots/server.json`: the base URL, the contest and
  * round ids, a juror with images in both rounds (id, login, password), the organizer
  * (an admin of the contest) and a page id of that juror's in the binary round. Then it
  * serves until `target/ui-shots/server.stop` exists (or the process is killed) and exits,
  * stopping the server and the container.
  *
  * {{{
  * java -jar sbt-launch.jar 'Test/runMain gatling.setup.UiShotsServer'
  * }}}
  * `scripts/ui-shots.py --start-server` runs it, waits for `server.json`, takes the
  * screenshots and stops it.
  */
object UiShotsServer {
  private val dir       = new File("target/ui-shots")
  private val readyFile = new File(dir, "server.json")
  private val stopFile  = new File(dir, "server.stop")

  def main(args: Array[String]): Unit = {
    val status = try { run(); 0 } catch {
      case error: Throwable => error.printStackTrace(); 1
    }
    sys.exit(status)
  }

  private def q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  private def run(): Unit = {
    require(!GatlingConfig.external, "Use the disposable fixture, never an external target")
    dir.mkdirs()
    readyFile.delete()
    stopFile.delete()

    val f = GatlingTestFixture
    implicit val session: DBSession = AutoSession

    val (jurorId, jurorEmail, jurorPassword) = f.jurorsIn(Some(f.roundBinaryId))
      .find(j => f.roundsOf(j._1).contains(f.roundRatingId))
      .orElse(f.jurorsIn(Some(f.roundBinaryId)).headOption)
      .getOrElse(sys.error("No juror with images in the binary round"))
    val pageId = sql"""SELECT MIN(page_id) FROM selection
                       WHERE jury_id = $jurorId AND round_id = ${f.roundBinaryId}"""
      .map(_.long(1)).single().getOrElse(sys.error("The juror has no images"))
    val (_, orgEmail, orgPassword) = f.organizer

    val json =
      s"""{
         |  "baseUrl": ${q(f.baseUrl)},
         |  "contestId": ${f.contestId},
         |  "roundBinaryId": ${f.roundBinaryId},
         |  "roundRatingId": ${f.roundRatingId},
         |  "juror": {"id": $jurorId, "login": ${q(jurorEmail)}, "password": ${q(jurorPassword)}},
         |  "organizer": {"login": ${q(orgEmail)}, "password": ${q(orgPassword)}},
         |  "pageId": $pageId
         |}
         |""".stripMargin
    Files.write(readyFile.toPath, json.getBytes(UTF_8))
    println(s"[ui-shots] serving ${f.baseUrl}; wrote ${readyFile.getPath}; create ${stopFile.getPath} to stop")

    while (!stopFile.exists()) Thread.sleep(500)
    stopFile.delete()
    readyFile.delete()
    println("[ui-shots] stopping")
  }
}
