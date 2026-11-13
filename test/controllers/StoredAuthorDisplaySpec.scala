package controllers

import db.scalikejdbc._
import org.intracer.wmua.{Image, Vote}
import play.api.Application
import play.api.libs.json.JsValue
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import scala.concurrent.Future

/** A file author that an older import stored as wikitext (`[[User:X|Y]]`, a credit template)
  * is shown as its account (`StoredAuthors.shown`) wherever authors are shown: the round's
  * large view, its JSON and the file list's author column, to those who see authors only
  * (`canViewOrgInfo`); a juror still sees none.
  */
class StoredAuthorDisplaySpec extends PlaySpecification with PlayTestDb {

  sequential

  private case class Fixture(round: Round, organizer: User, juror: User)

  private def fixture(): Fixture = {
    SharedTestDb.truncateAll()
    val contest = contestDao.create(None, "WLM", 2026, "Ukraine")
    val round = roundDao.create(Round(None, 1, Some("R"), contestId = contest.getId, active = true))
    def user(name: String, role: String) =
      userDao.create(User(name, s"${name.toLowerCase}@example.com", None, Set(role), contestId = contest.id))
    val juror = user("Juror", "jury")
    val organizer = user("Org", "organizer")
    round.addUsers(Seq(RoundUser(round.getId, juror.getId, "jury", active = true)))
    val images = Seq(
      Image(1L, "File:F1.jpg", None, None, 640, 480, author = Some("[[User:Петро_Грушко|Петро]]")),
      Image(2L, "File:F2.jpg", None, None, 640, 480, author = Some("{{SergiyBobokPhotos}}"))
    )
    imageDao.batchInsert(images)
    selectionDao.batchInsert(images.map(i => Vote(i, juror, round, 0)))
    Fixture(round, organizer, juror)
  }

  private def get(app: Application, viewer: User, path: String, json: Boolean = false): Future[Result] =
    route(app, FakeRequest(GET, path)
      .withSession(Secured.UserName -> viewer.email, Secured.CurrentContestId -> viewer.contestId.fold("")(_.toString))
      .withHeaders("Accept" -> (if (json) "application/json" else "text/html"))
      .withCSRFToken).get

  private def authors(json: Future[Result]): Seq[Option[String]] =
    contentAsJson(json).as[Seq[JsValue]].map(f => (f \ "image" \ "author").asOpt[String])

  "an author stored as wikitext" should {

    "show the organizer its account in the large view, its JSON and the file list" in {
      testDbApp { app =>
        val f = fixture()
        val r = f.round.getId
        val large = contentAsString(get(app, f.organizer, s"/large/round/$r/user/0/pageid/1"))
        val json = get(app, f.organizer, s"/large/round/$r/user/0/pageid/1", json = true)
        val list = contentAsString(get(app, f.organizer, s"/filelist/round/$r/user/0/page/1"))
        (large must contain("Петро Грушко")) and (large must not(contain("[[User:"))) and
          (authors(json) === Seq(Some("Петро Грушко"), Some("SergiyBobokPhotos"))) and
          (list must contain("Петро Грушко")) and (list must contain("SergiyBobokPhotos")) and
          (list must not(contain("[[User:"))) and (list must not(contain("{{SergiyBobokPhotos}}")))
      }
    }

    "show a juror no author, as before" in {
      testDbApp { app =>
        val f = fixture()
        val r = f.round.getId
        val u = f.juror.getId
        val large = contentAsString(get(app, f.juror, s"/large/round/$r/user/$u/pageid/1"))
        val json = get(app, f.juror, s"/large/round/$r/user/$u/pageid/1", json = true)
        val list = contentAsString(get(app, f.juror, s"/filelist/round/$r/user/$u/page/1"))
        (large must not(contain("Грушко"))) and (authors(json) === Seq(None, None)) and
          (list must not(contain("Грушко"))) and (list must not(contain("SergiyBobok")))
      }
    }
  }
}
