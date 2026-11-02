package gatling.setup

import scalikejdbc._

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** Serves the Gatling fixture (GatlingTestFixture: a MariaDB testcontainer restored from
  * `data/cache/`, the app in a TestServer) for UI screenshots
  * (docs/plans/2026-10-31-theme-modes-and-design-system.md, "Screenshots") and for trying the
  * app locally ("Running the fixture locally" there).
  *
  * The fixture's images have titles but no url ([[GatlingDbSetup]] loads them from a CSV), so it
  * first sets each missing url to the file's on Commons ([[CommonsFileUrl]]), in this run's
  * container only, never in the cached dump the Gatling runs restore: the pages then show
  * thumbnails, through the image proxy or from upload.wikimedia.org (network needed).
  * `scripts/ui-shots.py` answers those requests with a placeholder, so its shots don't depend
  * on the network.
  *
  * It then writes `target/ui-shots/server.json`: the base URL, the contest and
  * round ids, a juror with images in both rounds (id, login, password), the organizer
  * (an admin of the contest), a root account (created here) and the juror's first page id in the binary and the rated round.
  * Then it serves until `target/ui-shots/server.stop` exists (or the process is killed) and exits,
  * stopping the server and the container.
  *
  * {{{
  * java -jar sbt-launch.jar 'set Test / unmanagedClasspath += Attributed.blank((Assets / packageBin).value)' \
  *     'Test/runMain gatling.setup.UiShotsServer'
  * }}}
  * `scripts/ui-shots.py shoot --start-server` runs it, waits for `server.json`, takes the
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
    // Play's Assets serves public/ from the classpath, which the test classpath lacks unless
    // the run adds the packaged assets (scripts/ui-shots.py does): pages without their CSS
    // and scripts are no use for screenshots
    require(getClass.getClassLoader.getResource("public/stylesheets/main.css") != null,
      "public/ assets aren't on the classpath: run with " +
        "'set Test / unmanagedClasspath += Attributed.blank((Assets / packageBin).value)'")

    val f = GatlingTestFixture
    implicit val session: DBSession = AutoSession

    val (jurorId, jurorEmail, jurorPassword) = f.jurorsIn(Some(f.roundBinaryId))
      .find(j => f.roundsOf(j._1).contains(f.roundRatingId))
      .orElse(f.jurorsIn(Some(f.roundBinaryId)).headOption)
      .getOrElse(sys.error("No juror with images in the binary round"))
    val pageId = sql"""SELECT MIN(page_id) FROM selection
                       WHERE jury_id = $jurorId AND round_id = ${f.roundBinaryId}"""
      .map(_.long(1)).single().getOrElse(sys.error("The juror has no images"))
    // the juror's first file in the rated round, for the large view's stars (0 without one)
    val ratedPageId = sql"""SELECT MIN(page_id) FROM selection
                            WHERE jury_id = $jurorId AND round_id = ${f.roundRatingId}"""
      .map(_.longOpt(1)).single().flatten.getOrElse(0L)
    val (_, orgEmail, orgPassword) = f.organizer
    // a root account, in this run's container only, for the root-only pages (the campaigns page)
    val (rootEmail, rootPassword) = ("root@ui-shots.test", "rootpass")
    if (db.scalikejdbc.User.findByEmail(rootEmail).isEmpty)
      db.scalikejdbc.User.create(fullname = "Root", email = rootEmail, passwordHash = services.PasswordHasher.default.hash(rootPassword),
        roles = Set(db.scalikejdbc.User.ROOT_ROLE), contestId = None)
    println(s"[ui-shots] set the Commons url of ${CommonsFileUrl.fillMissing()} images")

    val json =
      s"""{
         |  "baseUrl": ${q(f.baseUrl)},
         |  "contestId": ${f.contestId},
         |  "roundBinaryId": ${f.roundBinaryId},
         |  "roundRatingId": ${f.roundRatingId},
         |  "juror": {"id": $jurorId, "login": ${q(jurorEmail)}, "password": ${q(jurorPassword)}},
         |  "organizer": {"login": ${q(orgEmail)}, "password": ${q(orgPassword)}},
         |  "root": {"login": ${q(rootEmail)}, "password": ${q(rootPassword)}},
         |  "pageId": $pageId,
         |  "ratedPageId": $ratedPageId
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
