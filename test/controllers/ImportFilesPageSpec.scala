package controllers

import db.scalikejdbc.{PlayTestDb, User}
import org.intracer.wmua.ContestJury
import play.api.Configuration
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc.AutoSession
import services.imports.{FakeCommonsSource, ImageImportHandler, ImportParams}
import services.jobs.{JobJdbc, JobStatus}

/** The contest files page: its imports start background jobs and show their status. Over
  * Commons (a category that doesn't exist: nothing to import).
  */
class ImportFilesPageSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val source = "Category:Wlxjury test category that does not exist"

  private def fixture(): (ContestJury, User) = {
    val contest = contestDao.create(None, "WLE", 2024, "Ukraine")
    val admin = userDao.create(User("Admin", "admin@example.com", None, Set("admin"), contestId = contest.id))
    (contest, admin)
  }

  private def importFiles(contestId: Long, user: User) =
    FakeRequest(POST, s"/contest/$contestId/importimages")
      .withSession(Secured.UserName -> user.email)
      .withFormUrlEncodedBody("source" -> source, "list" -> "", "action" -> "import.files")
      .withCSRFToken

  private def filesPage(contestId: Long, user: User) =
    FakeRequest(GET, s"/contest/$contestId/images").withSession(Secured.UserName -> user.email).withCSRFToken

  "the files page" should {

    "start an import job and show it" in {
      testDbApp { implicit app =>
        val (contest, admin) = fixture()
        val result = route(app, importFiles(contest.getId, admin)).get
        status(result) must_== SEE_OTHER
        redirectLocation(result) must beSome(s"/contest/${contest.getId}/images")
        flash(result).get("error") must beNone

        val job = JobJdbc.latestBySubjects(ImageImportHandler.Kind, "contest", Seq(contest.getId)).get(contest.getId)
        job.map(_.status) must beSome(JobStatus.Done) // jobs run inline in tests
        contestDao.findById(contest.getId).get.images must beSome(source)

        val page = contentAsString(route(app, filesPage(contest.getId, admin)).get)
        page must contain(s"Importing files from $source")
        page must contain(s"/admin/jobs/${job.get.id}")
      }
    }

    "not start a second import while one is running" in {
      testDbApp { implicit app =>
        val (contest, admin) = fixture()
        val handler = new ImageImportHandler(new FakeCommonsSource(Nil), Configuration.empty)
        val running = JobJdbc.insert(
          handler.newJob(ImportParams(contest.getId, ImageImportHandler.Mode.Import, "Category:Other"), None),
          owner = None)(AutoSession)

        val result = route(app, importFiles(contest.getId, admin)).get
        status(result) must_== SEE_OTHER
        flash(result).get("error") must beSome(contain("Already running: Importing files from Category:Other"))
        JobJdbc.findById(running).map(_.status) must beSome(JobStatus.Queued)
        contestDao.findById(contest.getId).get.images must beNone // the source is unchanged
      }
    }
  }
}
