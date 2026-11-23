package controllers

import db.scalikejdbc.{PlayTestDb, User}
import org.intracer.wmua.{ContestJury, Image}
import play.api.Application
import play.api.inject.bind
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import services.imports.{CommonsSource, FakeCommonsSource, FakeFile, FakeMonumentListSource, ImageImportHandler,
  MonumentListSource}
import services.jobs.JobJdbc

import scala.concurrent.Future

/** A source pasted with a wiki link's brackets (#111): the plain title is saved and imported; one that
  * is no title at all is a form error (400), never a 500.
  */
class WikiLinkSourceSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val file = FakeFile(Image(9001L, "File:Brazil 1.jpg", None, None, 640, 480, None))

  private def withCommons[T](body: Application => T): T =
    testDbAppWith(bind[CommonsSource].toInstance(new FakeCommonsSource(Seq(file))),
      bind[MonumentListSource].toInstance(new FakeMonumentListSource()))(body)

  private def fixture(): (ContestJury, User) = {
    val contest = contestDao.create(None, "Wiki Loves Earth", 2020, "Brazil")
    val admin = userDao.create(User("Admin", "admin@example.com", None, Set("admin"), contestId = contest.id))
    (contest, admin)
  }

  private def send(app: Application, contest: ContestJury, user: User, source: String): Future[Result] =
    route(app, FakeRequest(POST, s"/contest/${contest.getId}/importimages")
      .withSession(Secured.UserName -> user.email)
      .withFormUrlEncodedBody("source" -> source, "list" -> "", "action" -> "import.files")
      .withCSRFToken).get

  private def importSource(contest: ContestJury): Option[String] =
    JobJdbc.latestBySubjects(ImageImportHandler.Kind, ImageImportHandler.SubjectType, Seq(contest.getId))
      .get(contest.getId).map(ImageImportHandler.paramsOf(_).source)

  "an images source pasted as a wiki link (#111)" should {

    "import the plain category title" in {
      withCommons { implicit app =>
        val (contest, admin) = fixture()
        val source = "[[Category:Images from Wiki Loves Earth 2020 in Brazil - Best 115]]"
        status(send(app, contest, admin, source)) === SEE_OTHER
        importSource(contest) must beSome("Category:Images from Wiki Loves Earth 2020 in Brazil - Best 115")
      }
    }

    "import the plain title of a link with a colon and a sort key" in {
      withCommons { implicit app =>
        val (contest, admin) = fixture()
        status(send(app, contest, admin, " [[:Category:Foo_bar|sort key]] ")) === SEE_OTHER
        importSource(contest) must beSome("Category:Foo bar")
      }
    }

    "refuse a source that is no title with a form error, not a server error" in {
      withCommons { implicit app =>
        val (contest, admin) = fixture()
        val answer = send(app, contest, admin, "[[[Category:Foo#bar]]]")
        status(answer) === BAD_REQUEST
        contentAsString(answer) must contain("not a valid Commons page or category title")
        importSource(contest) must beNone
      }
    }

    "save the plain title with the files page's source form of the rounds page" in {
      withCommons { implicit app =>
        val (contest, admin) = fixture()
        val ok = route(app, FakeRequest(POST, s"/admin/setimages?contestId=${contest.getId}")
          .withSession(Secured.UserName -> admin.email)
          .withFormUrlEncodedBody("images" -> "[[Category:Foo]]").withCSRFToken).get
        status(ok) === SEE_OTHER
        contestDao.findById(contest.getId).flatMap(_.images) must beSome("Category:Foo")

        val bad = route(app, FakeRequest(POST, s"/admin/setimages?contestId=${contest.getId}")
          .withSession(Secured.UserName -> admin.email)
          .withFormUrlEncodedBody("images" -> "Category:A{B}").withCSRFToken).get
        status(bad) === SEE_OTHER
        contestDao.findById(contest.getId).flatMap(_.images) must beSome("Category:Foo")
      }
    }
  }
}
