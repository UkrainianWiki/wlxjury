package gatling.setup

import scalikejdbc._

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** Serves the Gatling fixture (GatlingTestFixture: a MariaDB testcontainer restored from
  * `data/cache/`, the app in a TestServer) for UI screenshots
  * (docs/performance/practices.md §1.7; 98a8a79e:docs/plans/2026-10-31-theme-modes-and-design-system.md, "Screenshots") and for trying the
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
  * (an admin of the contest), a root account (created here) and the juror's first page id in the binary and the rated round;
  * for the browser tests (browser-test/), an account with the contest's organizer role (created here) and up to
  * eight jurors with files in both rounds, each with its first page id in each.
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
    // a juror's first file in a round (0 without one)
    def firstPage(juror: Long, round: Long): Long =
      sql"SELECT MIN(page_id) FROM vote WHERE jury_id = $juror AND round_id = $round"
        .map(_.longOpt(1)).single().flatten.getOrElse(0L)
    val pageId = Some(firstPage(jurorId, f.roundBinaryId)).filter(_ > 0)
      .getOrElse(sys.error("The juror has no images"))
    // the juror's first file in the rated round, for the large view's stars (0 without one)
    val ratedPageId = firstPage(jurorId, f.roundRatingId)
    // the user guides' screenshots (-Dui.images=docs): this juror's email is verified, as a
    // person's is who accepted an invitation, so that the profile shows no "unverified" badge
    if (sys.props.get("ui.images").contains("docs"))
      sql"UPDATE users SET email_verified_at = NOW() WHERE id = $jurorId".update()
    // the browser tests (browser-test/): jurors with files in both rounds, each engine voting
    // as its own juror and signing in and out as another, so engines may share one server
    val jurors = f.jurorsIn(Some(f.roundBinaryId)).filter(j => f.roundsOf(j._1).contains(f.roundRatingId))
      .take(8).map { case (id, email, password) =>
        s"""{"id": $id, "login": ${q(email)}, "password": ${q(password)}, """ +
          s""""pageId": ${firstPage(id, f.roundBinaryId)}, "ratedPageId": ${firstPage(id, f.roundRatingId)}}"""
      }
    val (_, orgEmail, orgPassword) = f.organizer
    // a root account, in this run's container only, for the root-only pages (the campaigns page)
    val (rootEmail, rootPassword) = ("root@ui-shots.test", "rootpass")
    if (db.scalikejdbc.User.findByEmail(rootEmail).isEmpty)
      db.scalikejdbc.User.create(fullname = "Root", email = rootEmail, passwordHash = services.PasswordHasher.default.hash(rootPassword),
        roles = Set(db.scalikejdbc.User.ROOT_ROLE), contestId = None)
    // an account with the contest's organizer role (the fixture's organizer is its admin), in
    // this run's container only
    val (orgRoleEmail, orgRolePassword) = ("organizer-role@ui-shots.test", "orgrolepass")
    if (db.scalikejdbc.User.findByEmail(orgRoleEmail).isEmpty)
      db.scalikejdbc.User.create(fullname = "Organizer role", email = orgRoleEmail,
        passwordHash = services.PasswordHasher.default.hash(orgRolePassword),
        roles = db.scalikejdbc.User.ORG_COM_ROLES, contestId = Some(f.contestId))
    // in one transaction: as autocommitted statements (a commit per row) it took about 2.5 minutes
    val filled = DB.localTx { tx => CommonsFileUrl.fillMissing()(tx) }
    println(s"[ui-shots] set the Commons url of $filled images")

    val json =
      s"""{
         |  "baseUrl": ${q(f.baseUrl)},
         |  "contestId": ${f.contestId},
         |  "roundBinaryId": ${f.roundBinaryId},
         |  "roundRatingId": ${f.roundRatingId},
         |  "juror": {"id": $jurorId, "login": ${q(jurorEmail)}, "password": ${q(jurorPassword)}},
         |  "organizer": {"login": ${q(orgEmail)}, "password": ${q(orgPassword)}},
         |  "root": {"login": ${q(rootEmail)}, "password": ${q(rootPassword)}},
         |  "organizerRole": {"login": ${q(orgRoleEmail)}, "password": ${q(orgRolePassword)}},
         |  "jurors": [${jurors.mkString(", ")}],
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
