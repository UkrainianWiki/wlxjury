package controllers

import db.scalikejdbc.{PlayTestDb, User}
import org.intracer.wmua.{ContestJury, Image}
import play.api.Application
import play.api.inject.bind
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import services.imports.{CommonsSource, FakeCommonsSource, FakeFile, FakeMonumentListSource, ImageImportHandler,
  MonumentListImportHandler, MonumentListSource, NameProbe}
import services.jobs.JobJdbc

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** The files page's check of a source name without a namespace prefix (support diagnostics plan,
  * D-0b, DG11), over a stub Commons ([[FakeCommonsSource]]): the page or the category, whichever
  * exists; both: the choice with both counts, the category preselected; neither: refused; Commons
  * down: said so, and the next send reads the name as a page. A prefixed source and a pasted list
  * are read as before, unchecked. And an import that found no files says so on the page.
  */
class ImportSourceCheckSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val names = Map(
    "Only a page" -> NameProbe(page = true, category = false),
    "Only a category" -> NameProbe(page = false, category = true, categoryFiles = 12),
    "Wiki Loves Monuments 2026 in Nepal" ->
      NameProbe(page = true, category = true, categoryFiles = 1234, pageFiles = Some(500), pageFilesCapped = true)
  )

  private val file = FakeFile(Image(9001L, "File:Nepal 1.jpg", None, None, 640, 480, None))

  /** `body` with an app whose Commons is `commons` (the form's check and the import job's), and
    * no Wikipedia (the monument lists of "Update monuments"). */
  private def withCommons[T](commons: FakeCommonsSource)(body: Application => T): T =
    testDbAppWith(bind[CommonsSource].toInstance(commons),
      bind[MonumentListSource].toInstance(new FakeMonumentListSource()))(body)

  private def fixture(name: String = "Wiki Loves Earth", country: String = "Nepal"): (ContestJury, User) = {
    val contest = contestDao.create(None, name, 2026, country)
    val admin = userDao.create(User("Admin", "admin@example.com", None, Set("admin"), contestId = contest.id))
    (contest, admin)
  }

  private def send(app: Application, contest: ContestJury, user: User, fields: (String, String)*): Future[Result] = {
    val form = Map("list" -> "", "action" -> "import.files") ++ fields
    route(app, FakeRequest(POST, s"/contest/${contest.getId}/importimages")
      .withSession(Secured.UserName -> user.email)
      .withFormUrlEncodedBody(form.toSeq: _*)
      .withCSRFToken).get
  }

  /** The contest's jobs, of any kind. */
  private def jobs(contest: ContestJury): Int = {
    import scalikejdbc._
    DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM job WHERE contest_id = ${contest.getId}".map(_.int(1)).single())
      .getOrElse(0)
  }

  private def importSource(contest: ContestJury): Option[String] =
    JobJdbc.latestBySubjects(ImageImportHandler.Kind, ImageImportHandler.SubjectType, Seq(contest.getId))
      .get(contest.getId).map(ImageImportHandler.paramsOf(_).source)

  private def probed(commons: FakeCommonsSource) = commons.probed.asScala.toSeq

  "a source name without a namespace prefix (DG11) [A-09]" should {

    "be imported as the page when only the page exists, as the category when only the category does" in {
      val commons = new FakeCommonsSource(Seq(file), names = names)
      withCommons(commons) { implicit app =>
        val (contest, admin) = fixture()
        val page = send(app, contest, admin, "source" -> "Only a page")
        status(page) === SEE_OTHER
        importSource(contest) must beSome("Only a page")

        val category = send(app, contest, admin, "source" -> " Only a category ")
        status(category) === SEE_OTHER
        importSource(contest) must beSome("Category:Only a category")
        contestDao.findById(contest.getId).flatMap(_.images) must beSome("Category:Only a category")
        probed(commons) === Seq("Only a page", "Only a category")
      }
    }

    "show the choice with both counts when both exist, the category preselected, and queue the chosen one" in {
      val commons = new FakeCommonsSource(Seq(file), names = names)
      withCommons(commons) { implicit app =>
        val (contest, admin) = fixture()
        val name = "Wiki Loves Monuments 2026 in Nepal"
        val asked = send(app, contest, admin, "source" -> name)
        status(asked) === OK
        val page = contentAsString(asked)
        page must contain("""id="source-choice"""")
        page must contain(s"Commons has both a category and a page named “$name”")
        page must contain(s"Category:$name (1,234 files in the category)")
        page must contain(s"the page $name (500+ files shown on it)")
        page must beMatching("""(?s).*<input [^>]*name="sourceAs" id="source-as-category" value="category" checked>.*""")
        page must not(beMatching("""(?s).*id="source-as-page" value="page" checked.*"""))
        page must contain(s"""<input type="hidden" name="sourceFor" value="$name">""")
        page must contain(s"""value="$name"""") // the field keeps the name
        jobs(contest) === 0

        status(send(app, contest, admin, "source" -> name, "sourceAs" -> "category", "sourceFor" -> name)) === SEE_OTHER
        importSource(contest) must beSome(s"Category:$name")
        status(send(app, contest, admin, "source" -> name, "sourceAs" -> "page", "sourceFor" -> name)) === SEE_OTHER
        importSource(contest) must beSome(name)
        // every send asks Commons again: the choice counts only while both exist
        probed(commons) === Seq(name, name, name)
      }
    }

    "take a valid choice even when the page's files can't be counted (its second request fails)" in {
      val commons = new FakeCommonsSource(Seq(file), names = names)
      commons.pageCountFailure = Some(new java.util.concurrent.TimeoutException("slow count"))
      withCommons(commons) { implicit app =>
        val (contest, admin) = fixture()
        val name = "Wiki Loves Monuments 2026 in Nepal"
        // the choice made: the counts aren't asked for
        status(send(app, contest, admin, "source" -> name, "sourceAs" -> "category", "sourceFor" -> name)) === SEE_OTHER
        importSource(contest) must beSome(s"Category:$name")
        // without a choice the counts are needed: Commons couldn't answer, nothing queued
        val down = send(app, contest, admin, "source" -> name)
        status(down) === OK
        contentAsString(down) must contain("id=\"source-unreachable\"")
      }
    }

    "take a choice only for a name Commons still has as both, never instead of the check" in {
      val commons = new FakeCommonsSource(Seq(file), names = names)
      withCommons(commons) { implicit app =>
        val (contest, admin) = fixture()
        // a forged choice for a name that is only a category: the category, not the page
        status(send(app, contest, admin, "source" -> "Only a category", "sourceAs" -> "page",
          "sourceFor" -> "Only a category")) === SEE_OTHER
        importSource(contest) must beSome("Category:Only a category")
        // for a name of nothing: refused all the same
        status(send(app, contest, admin, "source" -> "Wiki Loves Mistakes", "sourceAs" -> "page",
          "sourceFor" -> "Wiki Loves Mistakes")) === BAD_REQUEST
        // a choice given for another name: the choice is asked again
        val name = "Wiki Loves Monuments 2026 in Nepal"
        val asked = send(app, contest, admin, "source" -> name, "sourceAs" -> "page", "sourceFor" -> "Other")
        status(asked) === OK
        contentAsString(asked) must contain("""id="source-choice"""")
        probed(commons) === Seq("Only a category", "Wiki Loves Mistakes", name)
      }
    }

    "refuse a name Commons has neither as a page nor as a category, queueing nothing" in {
      val commons = new FakeCommonsSource(Seq(file), names = names)
      withCommons(commons) { implicit app =>
        val (contest, admin) = fixture()
        val refused = send(app, contest, admin, "source" -> "Wiki Loves Mistakes")
        status(refused) === BAD_REQUEST
        contentAsString(refused) must contain(
          "There is no page or category named “Wiki Loves Mistakes” on Commons. Check the name, or add Category:")
        jobs(contest) === 0
        contestDao.findById(contest.getId).flatMap(_.images) must beNone
      }
    }

    "say when Commons can't be reached, keep the name, and queue nothing, on every send (no page fallback)" in {
      val commons = new FakeCommonsSource(Seq(file), names = names)
      commons.probeFailure = Some(new java.util.concurrent.TimeoutException("Commons is slow"))
      withCommons(commons) { implicit app =>
        val (contest, admin) = fixture()
        val name = "Only a category"
        val (down, logs) = LogCapture("services.imports.SourceName") {
          val r = send(app, contest, admin, "source" -> name)
          (status(r), contentAsString(r))
        }
        down._1 === OK
        down._2 must contain("""id="source-unreachable"""")
        down._2 must contain("Commons could not be reached to check this name, so nothing was started. Try again later")
        down._2 must contain(s"""value="$name"""") // the field keeps the name
        down._2 must not(contain("""name="sourceAs""""))
        down._2 must not(contain("Commons is slow"))
        jobs(contest) === 0
        logs("services.imports.SourceName") === Seq(
          s"""import_source_check_failed contestId=${contest.getId} source="$name" error=TimeoutException""")

        // a second send, with a choice or without: still checked, still nothing queued
        status(send(app, contest, admin, "source" -> name)) === OK
        status(send(app, contest, admin, "source" -> name, "sourceAs" -> "page", "sourceFor" -> name)) === OK
        jobs(contest) === 0
        // a prefixed source still works while Commons is down
        status(send(app, contest, admin, "source" -> "Category:Images from somewhere")) === SEE_OTHER
        importSource(contest) must beSome("Category:Images from somewhere")
        probed(commons) === Seq(name, name, name)
      }
    }

    "leave a prefixed source and a pasted file list as they are, unchecked" in {
      val commons = new FakeCommonsSource(Seq(file), names = names)
      withCommons(commons) { implicit app =>
        val (contest, admin) = fixture()
        status(send(app, contest, admin, "source" -> "Category:Images from Wiki Loves Earth 2026 in Nepal")) === SEE_OTHER
        importSource(contest) must beSome("Category:Images from Wiki Loves Earth 2026 in Nepal")
        status(send(app, contest, admin, "source" -> "Template:Monument Nepal")) === SEE_OTHER
        importSource(contest) must beSome("Template:Monument Nepal")
        status(send(app, contest, admin, "source" -> "Commons:Wiki Loves Monuments 2026/Winners")) === SEE_OTHER
        importSource(contest) must beSome("Commons:Wiki Loves Monuments 2026/Winners")
        // a list with a prefix-less source (the contest's own, pre-filled): the list's files, as before
        status(send(app, contest, admin, "source" -> "Wiki Loves Mistakes", "list" -> "File:Nepal 1.jpg")) === SEE_OTHER
        JobJdbc.latestBySubjects(ImageImportHandler.Kind, ImageImportHandler.SubjectType, Seq(contest.getId))
          .get(contest.getId).map(ImageImportHandler.paramsOf(_).titleCount) must beSome(1)
        probed(commons) must beEmpty
      }
    }

    "check the name for Update monuments too" in {
      val commons = new FakeCommonsSource(Seq(file), names = names)
      withCommons(commons) { implicit app =>
        // a contest with a monument id template: the action is offered
        val (contest, admin) = fixture("Wiki Loves Monuments", "Ukraine")
        val refused = send(app, contest, admin, "source" -> "Wiki Loves Mistakes", "action" -> "update.monuments")
        status(refused) === BAD_REQUEST
        jobs(contest) === 0
        status(send(app, contest, admin, "source" -> "Only a category", "action" -> "update.monuments")) === SEE_OTHER
        importSource(contest) must beSome("Category:Only a category")
        JobJdbc.latestBySubjects(MonumentListImportHandler.Kind, ImageImportHandler.SubjectType, Seq(contest.getId))
          .get(contest.getId) must beSome
        probed(commons) === Seq("Wiki Loves Mistakes", "Only a category")
      }
    }

    "be refused to anyone but the contest's admins, before any check or job [S-10]" in {
      val commons = new FakeCommonsSource(Seq(file), names = names)
      withCommons(commons) { implicit app =>
        val (contest, _) = fixture()
        val other = contestDao.create(None, "Wiki Loves Earth", 2026, "Armenia")
        val juror = userDao.create(User("Juror", "juror@example.com", None, Set("jury"), contestId = contest.id))
        val organizer = userDao.create(User("Org", "org@example.com", None, Set("organizer"), contestId = contest.id))
        val otherAdmin = userDao.create(User("Admin B", "adminb@example.com", None, Set("admin"), contestId = other.id))
        forall(Seq(juror, organizer, otherAdmin)) { user =>
          status(send(app, contest, user, "source" -> "Only a category")) === FORBIDDEN
        }
        val signedOut = route(app, FakeRequest(POST, s"/contest/${contest.getId}/importimages")
          .withFormUrlEncodedBody("source" -> "Only a category", "list" -> "", "action" -> "import.files")
          .withCSRFToken).get
        status(signedOut) === SEE_OTHER
        redirectLocation(signedOut) must beSome(startWith("/auth"))
        val script = route(app, FakeRequest(POST, s"/contest/${contest.getId}/importimages")
          .withHeaders("X-Requested-With" -> "XMLHttpRequest")
          .withFormUrlEncodedBody("source" -> "Only a category", "list" -> "", "action" -> "import.files")
          .withCSRFToken).get
        status(script) === UNAUTHORIZED
        probed(commons) must beEmpty
        jobs(contest) === 0
      }
    }
  }

  "the import form with the CSRF filter on (as in production)" should {
    "refuse a send without the session's token, before any check or job; take it with the token [S-02]" in {
      // an app of its own with the release's filters (CSRF on) and the stub Commons, so the check
      // can be seen not to run: the shared CSRF app (CsrfTestApp.withCsrfApp) has the real one
      val commons = new FakeCommonsSource(Seq(file), names = names)
      security.ProductionFilters.withAppWith(Map.empty, bind[CommonsSource].toInstance(commons),
          bind[MonumentListSource].toInstance(new FakeMonumentListSource())) { implicit app =>
        val (contest, admin) = fixture()
        val sessionToken = CsrfTestApp.token(app)
        def post(token: Seq[(String, String)]) = route(app, FakeRequest(POST, s"/contest/${contest.getId}/importimages")
          .withHeaders(COOKIE -> "PLAY_SESSION=x", ACCEPT -> "text/html")
          .withSession(Secured.UserName -> admin.email, "csrfToken" -> sessionToken)
          .withFormUrlEncodedBody(Seq("source" -> "Only a category", "list" -> "", "action" -> "import.files") ++ token: _*)).get
        forall(Seq(post(Nil), post(Seq("csrfToken" -> "forged")))) { refused =>
          status(refused) === FORBIDDEN
          contentAsString(refused) must contain("This page has expired or you were signed out.")
        }
        jobs(contest) === 0
        probed(commons) must beEmpty
        // the control: with the session's token it is checked and queued
        status(post(Seq("csrfToken" -> sessionToken))) === SEE_OTHER
        importSource(contest) must beSome("Category:Only a category")
        probed(commons) === Seq("Only a category")
      }
    }
  }

  "an import that found no files (D-0b) [A-09]" should {
    "say so on the files page; one that found files doesn't" in {
      val empty = new FakeCommonsSource(Nil, names = names)
      withCommons(empty) { implicit app =>
        val (contest, admin) = fixture()
        val (_, logs) = LogCapture("services.imports") {
          status(send(app, contest, admin, "source" -> "Category:Empty")) === SEE_OTHER
        }
        logs("services.imports").filter(_.startsWith("import_done ")) must contain(beMatching(
          s"""import_done job=\\d+ contestId=${contest.getId} mode=import source="Category:Empty" titles=0 seen=0 inserted=0 .*"""))
        val page = contentAsString(route(app, FakeRequest(GET, s"/contest/${contest.getId}/images")
          .withSession(Secured.UserName -> admin.email).withCSRFToken).get)
        page must contain("import-no-files")
        page must contain("This import found no files to add.")
      }
      val found = new FakeCommonsSource(Seq(file), names = names)
      withCommons(found) { implicit app =>
        val (contest, admin) = fixture()
        status(send(app, contest, admin, "source" -> "Category:Nepal")) === SEE_OTHER
        val page = contentAsString(route(app, FakeRequest(GET, s"/contest/${contest.getId}/images")
          .withSession(Secured.UserName -> admin.email).withCSRFToken).get)
        page must not(contain("import-no-files"))
      }
    }
  }
}
