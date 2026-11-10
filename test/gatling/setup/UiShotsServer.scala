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
    val listedJurors = f.jurorsIn(Some(f.roundBinaryId)).filter(j => f.roundsOf(j._1).contains(f.roundRatingId)).take(8)
    val jurors = listedJurors.map { case (id, email, password) =>
        s"""{"id": $id, "login": ${q(email)}, "password": ${q(password)}, """ +
          s""""pageId": ${firstPage(id, f.roundBinaryId)}, "ratedPageId": ${firstPage(id, f.roundRatingId)}}"""
      }
    val (_, orgEmail, orgPassword) = f.organizer
    // a root account, in this run's container only, for the root-only pages (the contests page)
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

    // docs mode: the photos learn the fixture's files and which of them the guides' shots show at
    // the top of a page (docs-screenshots.spec.js), which never get a degraded photo
    f.docsPhotos.foreach { photos =>
      val files  = sql"SELECT page_id, title FROM images ORDER BY page_id".map(r => (r.long(1), r.string(2))).list()
      val titles = files.map(_._2)
      val shown  = shownFiles(f.roundBinaryId, f.roundRatingId, (jurorId +: listedJurors.map(_._1)).distinct)
      photos.setFixture(DocsPhotos.Fixture(titles, shown))
      println(s"[images] docs photos for ${titles.size} files (${photos.pool.good.size} photos, " +
        s"${photos.pool.bad.size} degraded); ${shown.size} files shown at the top of the shots get no degraded one")
      // each file keeps its stored width (the fixture's, its Commons original's, so the large
      // view's "Full resolution" stays plausible) and takes its photo's aspect ratio: the pages
      // size a thumbnail from the stored width and height, so a landscape photo standing in for a
      // portrait file came out narrow and short. A file without a width takes its photo's own
      // size. This run's container only, after the rounds were filled, so no round's size filter
      // sees the change
      val sizes = (photos.pool.good ++ photos.pool.bad).distinct.map(p => p -> DocsPhotos.dimensions(p)).toMap
      val byPhoto = files.groupBy { case (_, t) => photos.fileForTitle(t).getOrElse(sys.error(s"no docs photo for $t")) }
      val resized: Int = DB.localTx { tx =>
        byPhoto.toSeq.flatMap { case (photo, group) =>
          val (w, h) = sizes(photo)
          group.map(_._1).grouped(1000).map { ids =>
            sql"UPDATE images SET height = ROUND(width * $h / $w) WHERE page_id IN ($ids) AND width > 0".update.apply()(tx) +
              sql"UPDATE images SET width = $w, height = $h WHERE page_id IN ($ids) AND (width IS NULL OR width <= 0)".update.apply()(tx)
          }
        }.sum
      }
      println(s"[images] $resized files take their docs photo's aspect ratio")
    }
    // the stub's mode, which docs-screenshots.spec.js checks: "docs", "stub", or "commons" (none)
    val images = if (f.docsPhotos.isDefined) "docs" else sys.props.get("ui.images").filter(_ == "stub").getOrElse("commons")

    val json =
      s"""{
         |  "baseUrl": ${q(f.baseUrl)},
         |  "images": ${q(images)},
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

  /** The titles of the files the guides' shots (docs-screenshots.spec.js) show near the top of a
    * page: the first [[DocsPhotos.ShownPerList]] of each juror's gallery of both rounds (the
    * large views open a juror's first file, with its neighbours), and of all the jurors' by-rate
    * gallery and file list of both, each in the order its page has without a cookie. */
  private def shownFiles(binaryId: Long, ratedId: Long, jurorIds: Seq[Long]): Set[String] = {
    import controllers.{GalleryView, Pager, RoundList}
    implicit val request: play.api.mvc.RequestHeader = play.api.test.FakeRequest()
    val rounds = Seq(binaryId, ratedId).map(id => db.scalikejdbc.Round.findById(id).getOrElse(sys.error(s"no round $id")))
    def top(list: RoundList): Seq[String] =
      list.files(Pager(1, Int.MaxValue, DocsPhotos.ShownPerList)).map(_.image.title)
    val ofJurors = for {
      jurorId <- jurorIds
      juror    = db.scalikejdbc.User.findById(jurorId).getOrElse(sys.error(s"no juror $jurorId"))
      round   <- rounds
      title   <- top(RoundList.of(round, juror, jurorId, "all", GalleryView.Gallery))
    } yield title
    val organizer = db.scalikejdbc.User.findById(GatlingTestFixture.organizer._1).getOrElse(sys.error("no organizer"))
    val ofAll = for {
      round <- rounds
      view  <- Seq(GalleryView.ByRate, GalleryView.FileList)
      title <- top(RoundList.of(round, organizer, 0, "all", view))
    } yield title
    (ofJurors ++ ofAll).toSet
  }
}
