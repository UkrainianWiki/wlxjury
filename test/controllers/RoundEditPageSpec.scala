package controllers

import db.scalikejdbc.{PlayTestDb, Round, SelectionJdbc, User}
import org.intracer.wmua.{ContestJury, Image}
import play.api.libs.json.Json
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

/** The round edit page and the two fragments it loads on demand. */
class RoundEditPageSpec extends PlaySpecification with PlayTestDb {

  sequential

  private def image(id: Long) = Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(s"12-345-$id"))

  /** A contest with an admin, two jurors, four images in the contest's category and a
    * binary round holding the first two of them.
    */
  private def fixture(): (ContestJury, User, Round) = {
    val contest = contestDao.create(None, "WLE", 2024, "Ukraine")
    contestDao.setImagesSource(contest.getId, Some("Category:WLE images"))
    val admin = userDao.create(User("Admin", "admin@example.com", None, Set("admin"), contestId = contest.id))
    val jurors = (1 to 2).map(i =>
      userDao.create(User(s"Juror $i", s"juror$i@example.com", None, Set("jury"), contestId = contest.id)))
    val images = (1L to 4L).map(image)
    imageDao.batchInsert(images)
    db.scalikejdbc.CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, images)
    val round = roundDao.create(
      Round(None, 1, Some("Round 1"), contest.getId, distribution = 0, active = true))
    for (img <- images.take(2); juror <- jurors)
      SelectionJdbc.create(img.pageId, rate = 0, juryId = juror.getId, roundId = round.getId)
    (contest, admin, round)
  }

  private def get(url: String, user: User) =
    FakeRequest(GET, url).withSession(Secured.UserName -> user.email).withCSRFToken

  "the round edit page" should {

    "show the round without its stat table or new files count" in {
      testDbApp { implicit app =>
        val (contest, admin, round) = fixture()
        val page = route(app, get(s"/admin/rounds/edit?id=${round.getId}&contestId=${contest.getId}", admin)).get
        status(page) must_== OK
        val html = contentAsString(page)
        html must contain(s"/roundstat/${round.getId}/table")
        html must contain(s"/admin/rounds/newfiles?id=${round.getId}")
        html must contain("value='Round 1'").or(contain("value=\"Round 1\""))
        html must not(contain("/thumb_urls/")) // the stat table isn't rendered
      }
    }

    "not show a round of another contest" in {
      testDbApp { implicit app =>
        val (_, _, round) = fixture()
        val other = contestDao.create(None, "WLM", 2024, "Poland")
        val otherAdmin =
          userDao.create(User("Other", "other@example.com", None, Set("admin"), contestId = other.id))
        val page = route(app, get(s"/admin/rounds/edit?id=${round.getId}&contestId=${other.getId}", otherAdmin)).get
        status(page) must_== OK
        val html = contentAsString(page)
        html must not(contain("Round 1"))
        html must not(contain(s"/roundstat/${round.getId}/table"))
      }
    }
  }

  "the round stat table fragment" should {
    "list the round's jurors" in {
      testDbApp { implicit app =>
        val (_, admin, round) = fixture()
        val table = route(app, get(s"/roundstat/${round.getId}/table", admin)).get
        status(table) must_== OK
        contentAsString(table) must contain("juror1@example.com")
        contentAsString(table) must contain("/thumb_urls/")
        contentAsString(table) must not(contain("<html"))
      }
    }
  }

  "the new files count" should {
    "count the contest images the round doesn't have yet" in {
      testDbApp { implicit app =>
        val (_, admin, round) = fixture()
        val count = route(app, get(s"/admin/rounds/newfiles?id=${round.getId}", admin)).get
        status(count) must_== OK
        contentAsJson(count) must_== Json.obj("count" -> 2)
      }
    }

    "refuse an admin of another contest" in {
      testDbApp { implicit app =>
        val (_, _, round) = fixture()
        val other = contestDao.create(None, "WLM", 2024, "Poland")
        val otherAdmin =
          userDao.create(User("Other", "other@example.com", None, Set("admin"), contestId = other.id))
        val count = route(app, get(s"/admin/rounds/newfiles?id=${round.getId}", otherAdmin)).get
        status(count) must_== SEE_OTHER
        redirectLocation(count) must beSome.which(_.startsWith("/error"))
      }
    }
  }
}
