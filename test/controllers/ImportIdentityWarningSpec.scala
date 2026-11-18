package controllers

import db.scalikejdbc.{PlayTestDb, User}
import org.intracer.wmua.{ContestJury, Image}
import play.api.Application
import play.api.inject.bind
import play.api.libs.json.{JsNull, JsString}
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import services.imports.{CommonsSource, FakeCommonsSource, FakeFile, FakeMonumentListSource, ImageImportHandler,
  MonumentListSource, NameProbe}
import services.jobs.JobJdbc

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.Future

/** The import form's identity warning (support diagnostics plan, DG10): the health page's H1 check
  * of the form's source against the contest's name, year and country, shown with the form and as
  * the admin types (the script's GET), before anything is queued; a warning only, never a refusal.
  * Over a stub Commons ([[FakeCommonsSource]]).
  */
class ImportIdentityWarningSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val nepalCategory = "Category:Images from Wiki Loves Monuments 2026 in Nepal"
  private val nepalName = "Wiki Loves Monuments 2026 in Nepal"
  private val warning = "This contest is Wiki Loves Earth 2020, but the source names Wiki Loves Monuments 2026 in Nepal."

  private val file = FakeFile(Image(9101L, "File:Nepal temple.jpg", None, None, 640, 480, None))

  private def withCommons[T](body: Application => T): T =
    testDbAppWith(
      bind[CommonsSource].toInstance(new FakeCommonsSource(Seq(file),
        names = Map(nepalName -> NameProbe(page = true, category = true, categoryFiles = 12, pageFiles = Some(3))))),
      bind[MonumentListSource].toInstance(new FakeMonumentListSource()))(body)

  /** An old international WLE 2020 contest (the Nepal case), with its admin. */
  private def fixture(source: Option[String] = None, email: String = "admin@example.com"): (ContestJury, User) = {
    val contest = contestDao.create(None, "Wiki Loves Earth", 2020, "", images = source)
    val admin = userDao.create(User("Admin", email, None, Set("admin"), contestId = contest.id))
    (contest, admin)
  }

  private def get(app: Application, path: String, user: User, script: Boolean = false): Future[Result] = {
    val request = FakeRequest(GET, path).withSession(Secured.UserName -> user.email)
    route(app, if (script) request.withHeaders("X-Requested-With" -> "XMLHttpRequest") else request).get
  }

  private def check(app: Application, contest: ContestJury, user: User, source: String): Future[Result] =
    get(app, s"/contest/${contest.getId}/importimages/source-identity?source=${URLEncoder.encode(source, UTF_8)}", user,
      script = true)

  private def importSource(contest: ContestJury): Option[String] =
    JobJdbc.latestBySubjects(ImageImportHandler.Kind, ImageImportHandler.SubjectType, Seq(contest.getId))
      .get(contest.getId).map(ImageImportHandler.paramsOf(_).source)

  "the import form's identity warning (DG10) [A-29]" should {

    "be shown with the form for the contest's source, and hidden for a matching one" in {
      withCommons { implicit app =>
        val (contest, admin) = fixture(Some(nepalCategory))
        val html = contentAsString(get(app, s"/contest/${contest.getId}/images", admin))
        html must contain(warning)
        html must beMatching("""(?s).*<div id="source-identity-warning" class="alert alert-warning py-2" role="status"\s+data-src="[^"]+"\s+>This contest.*""")
        html must contain(s"""data-src="/contest/${contest.getId}/importimages/source-identity"""")

        val (own, ownAdmin) = fixture(Some("Category:Images from Wiki Loves Earth 2020"), "own.admin@example.com")
        val ownHtml = contentAsString(get(app, s"/contest/${own.getId}/images", ownAdmin))
        ownHtml must beMatching("""(?s).*<div id="source-identity-warning"[^>]*\s+hidden></div>.*""")
      }
    }

    "answer the script's check of a typed source, without a Commons call, for the contest's admins only" in {
      withCommons { implicit app =>
        val (contest, admin) = fixture()
        val r = check(app, contest, admin, nepalCategory)
        status(r) === OK
        header(CACHE_CONTROL, r) must beSome("no-store")
        (contentAsJson(r) \ "warning").get === JsString(warning + " Imported files are added to the files the contest " +
          "already has. For a new contest or year, ask the tool administrators for a new contest. You can still import.")
        (contentAsJson(check(app, contest, admin, "Category:Images from Wiki Loves Earth 2020 in Nepal")) \ "warning")
          .get === JsNull
        (contentAsJson(check(app, contest, admin, "")) \ "warning").get === JsNull

        val organizer = userDao.create(User("Org", "org@example.com", None, Set("organizer"), contestId = contest.id))
        val juror = userDao.create(User("Juror", "juror@example.com", None, Set("jury"), contestId = contest.id))
        val other = contestDao.create(None, "Wiki Loves Earth", 2026, "")
        val otherAdmin = userDao.create(User("Other", "other@example.com", None, Set("admin"), contestId = other.id))
        val inactive = userDao.create(User("Inactive", "inactive@example.com", None, Set("no"), contestId = contest.id))
        for (user <- Seq(organizer, juror, inactive, otherAdmin)) {
          val denied = check(app, contest, user, nepalCategory)
          status(denied) === FORBIDDEN
          contentAsString(denied) must not(contain("Wiki Loves Monuments"))
        }
        status(route(app, FakeRequest(GET, s"/contest/${contest.getId}/importimages/source-identity?source=x")
          .withHeaders("X-Requested-With" -> "XMLHttpRequest")).get) === UNAUTHORIZED

        // root: no member, allowed
        val root = userDao.create(User("Root", "root@example.com", None, Set(User.ROOT_ROLE), isRoot = true))
        val asRoot = check(app, contest, root, nepalCategory)
        status(asRoot) === OK
        (contentAsJson(asRoot) \ "warning").as[String] must startWith(warning)
      }
    }

    "never switch the session's contest: a script's request (CLAUDE.md, Contest switching)" in {
      withCommons { implicit app =>
        val (contest, admin) = fixture()
        val (second, _) = fixture(None, "second.admin@example.com")
        db.scalikejdbc.UserContestJdbc.createMembership(admin.getId, second.getId, "admin")
        val r = route(app, FakeRequest(GET, s"/contest/${second.getId}/importimages/source-identity?source=x")
          .withSession(Secured.UserName -> admin.email, Secured.CurrentContestId -> contest.getId.toString)
          .withHeaders("X-Requested-With" -> "XMLHttpRequest")).get
        status(r) === OK
        header(SET_COOKIE, r) must beNone
        session(r).get(Secured.CurrentContestId) must beNone
      }
    }

    "never refuse: the import is queued, and the page then warns for the contest's new source" in {
      withCommons { implicit app =>
        val (contest, admin) = fixture()
        val sent = route(app, FakeRequest(POST, s"/contest/${contest.getId}/importimages")
          .withSession(Secured.UserName -> admin.email)
          .withFormUrlEncodedBody("source" -> nepalCategory, "list" -> "", "action" -> "import.files")
          .withCSRFToken).get
        status(sent) === SEE_OTHER
        importSource(contest) must beSome(nepalCategory)
        // said with the queued job (also for a send without the script), once: not again in the box
        val said = flash(sent).get(ImageController.SourceWarningFlash)
        said.getOrElse("") must startWith(warning)
        val html = contentAsString(route(app, FakeRequest(GET, s"/contest/${contest.getId}/images")
          .withSession(Secured.UserName -> admin.email).withFlash(ImageController.SourceWarningFlash -> said.get)).get)
        html must contain("""id="import-identity-flash"""")
        html.split(java.util.regex.Pattern.quote(warning), -1).length - 1 === 1
        html must beMatching("""(?s).*<div id="source-identity-warning"[^>]*\s+hidden></div>.*""")
        // later, without the flash: the box says it for the contest's source
        contentAsString(get(app, s"/contest/${contest.getId}/images", admin)) must contain(warning)
        // a matching source: no flash
        val (own, ownAdmin) = fixture(None, "own.admin@example.com")
        val ok = route(app, FakeRequest(POST, s"/contest/${own.getId}/importimages")
          .withSession(Secured.UserName -> ownAdmin.email)
          .withFormUrlEncodedBody("source" -> "Category:Images from Wiki Loves Earth 2020", "list" -> "",
            "action" -> "import.files")
          .withCSRFToken).get
        status(ok) === SEE_OTHER
        flash(ok).get(ImageController.SourceWarningFlash) must beNone
      }
    }

    "show the warning with the both-exist choice for the typed name, before anything is queued" in {
      withCommons { implicit app =>
        val (contest, admin) = fixture()
        val asked = route(app, FakeRequest(POST, s"/contest/${contest.getId}/importimages")
          .withSession(Secured.UserName -> admin.email)
          .withFormUrlEncodedBody("source" -> nepalName, "list" -> "", "action" -> "import.files")
          .withCSRFToken).get
        status(asked) === OK
        val html = contentAsString(asked)
        html must contain("""id="source-choice"""")
        html must contain(warning)
        importSource(contest) must beNone
      }
    }
  }
}
