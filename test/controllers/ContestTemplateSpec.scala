package controllers

import db.scalikejdbc.{PlayTestDb, User}
import org.intracer.wmua.ContestJury
import play.api.Application
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import services.imports.ImageImportHandler
import services.jobs.JobJdbc

/** A contest's monument id template: the admin's, else its campaign config's, read when it
  * is used, never written for it.
  */
class ContestTemplateSpec extends PlaySpecification with PlayTestDb {

  sequential

  private def root(): User =
    User.create(User("Root", "root@example.com", roles = Set(User.ROOT_ROLE), password = Some("hash")))

  private def admin(contest: ContestJury): User =
    userDao.create(User("Admin", "admin@example.com", None, Set("admin"), contestId = contest.id))

  private def get(path: String, user: User)(implicit app: Application): String =
    contentAsString(route(app, FakeRequest(GET, path).withSession(Secured.UserName -> user.email).withCSRFToken).get)

  "a contest's monument id template" should {

    "stay empty when saved empty, and show as the campaign config's" in {
      testDbApp { implicit app =>
        val user = root()
        val saved = route(app, FakeRequest(POST, "/contests/save")
          .withSession(Secured.UserName -> user.email)
          .withFormUrlEncodedBody("name" -> "Wiki Loves Earth", "country" -> "Ukraine", "year" -> "2025",
            "monumentIdTemplate" -> "", "useGreeting" -> "true")
          .withCSRFToken).get
        status(saved) === SEE_OTHER

        val contest = contestDao.findAll().find(_.name == "Wiki Loves Earth").get
        contest.monumentIdTemplate must beNone

        val page = get("/contests", user)
        page must beMatching("""(?s).*UkrainianNaturalHeritageSite\s*<span class="text-muted"[^>]*>\(from the campaign config\)</span>.*""")
      }
    }

    "show an admin's own template as it is" in {
      testDbApp { implicit app =>
        val user = root()
        contestDao.create(None, "Wiki Loves Monuments", 2025, "Ukraine", monumentIdTemplate = Some("Custom template"))
        val page = get("/contests", user)
        page must contain("Custom template")
        page must not contain "from the campaign config"
      }
    }

    "offer Update monuments for a contest whose campaign has a template" in {
      testDbApp { implicit app =>
        val wle = contestDao.create(None, "Wiki Loves Earth", 2025, "Ukraine")
        get(s"/contest/${wle.getId}/images", admin(wle)) must contain("update-monuments-btn")
      }
    }

    "not offer Update monuments for a contest of no known campaign" in {
      testDbApp { implicit app =>
        val other = contestDao.create(None, "Wiki Loves Earth", 2025, "Ukraine Int")
        get(s"/contest/${other.getId}/images", admin(other)) must not contain "update-monuments-btn"
      }
    }

    "offer round regions for the campaign's country, including country codes" in {
      testDbApp { implicit app =>
        val controller = app.injector.instanceOf[ContestController]
        val coded = contestDao.create(None, "Wiki Loves Earth", 2025, "ua")
        val campaign = contestDao.create(None, "Custom", 2025, "Custom", campaign = Some("wle-UA"))
        val german = contestDao.create(None, "Wiki Loves Monuments", 2025, "Ukraine", campaign = Some("wlm-de"))
        controller.regions(coded.getId) must not(beEmpty)
        controller.regions(campaign.getId) === controller.regions(coded.getId)
        controller.regions(german.getId) must beEmpty
      }
    }

    "be the campaign config's in an import's params" in {
      testDbApp { implicit app =>
        val wlm = contestDao.create(None, "Wiki Loves Monuments", 2025, "Ukraine")
        val result = route(app, FakeRequest(POST, s"/contest/${wlm.getId}/importimages")
          .withSession(Secured.UserName -> admin(wlm).email)
          .withFormUrlEncodedBody("source" -> "Category:Wlxjury test category that does not exist", "list" -> "",
            "action" -> "import.files")
          .withCSRFToken).get
        status(result) === SEE_OTHER

        val job = JobJdbc.latestBySubjects(ImageImportHandler.Kind, "contest", Seq(wlm.getId)).get(wlm.getId)
        job.map(ImageImportHandler.paramsOf(_).monumentIdTemplate) must beSome(Some("Monument Ukraine"))
      }
    }
  }
}
