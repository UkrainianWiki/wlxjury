package controllers

import db.scalikejdbc.{PlayTestDb, Round, SelectionJdbc, User}
import org.apache.pekko.stream.Materializer
import org.intracer.wmua.{CommentJdbc, Image}
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, Helpers, PlaySpecification}
import services.GalleryService

class MutationAuthorizationSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val galleryService = new GalleryService

  private def galleryController =
    new GalleryController(galleryService, Helpers.stubControllerComponents())

  private def largeViewController =
    new LargeViewController(Helpers.stubControllerComponents(), galleryService)

  private def imageDiscussionController =
    new ImageDiscussionController(Helpers.stubControllerComponents())

  private def image(id: Long, monumentId: String): Image =
    Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(monumentId))

  private def sessionRequest(method: String, url: String, email: String) =
    FakeRequest(method, url)
      .withSession(Secured.UserName -> email)
      .withCSRFToken

  private def expectUnauthorized(result: scala.concurrent.Future[play.api.mvc.Result]) = {
    status(result) mustEqual SEE_OTHER
    redirectLocation(result) must beSome.which(_.startsWith("/error?message="))
  }

  "GalleryController.selectWS" should {
    "reject cross-contest selection mutations" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val homeContest = contestDao.create(None, "WLE", 2024, "Ukraine")
        val foreignContest = contestDao.create(None, "WLM", 2024, "Poland")
        val foreignRound = roundDao.create(
          Round(None, 1, contestId = foreignContest.getId, rates = Round.binaryRound, active = true)
        )
        val juror = userDao.create(
          User("Juror", "juror@example.com", None, Set("jury"), contestId = homeContest.id)
        )
        imageDao.batchInsert(Seq(image(1001L, "PL-1001")))

        val result = galleryController
          .selectWS(foreignRound.getId, 1001L, select = 1, module = "gallery", rate = Some(0), criteria = None)
          .apply(sessionRequest(POST, s"/rate/round/${foreignRound.getId}/pageid/1001/select/1?rate=0", juror.email))
          .run()

        expectUnauthorized(result)
        SelectionJdbc.findAll() must beEmpty
      }
    }
  }

  "LargeViewController.rateByPageId" should {
    "reject cross-contest rating mutations" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val homeContest = contestDao.create(None, "WLE", 2024, "Ukraine")
        val foreignContest = contestDao.create(None, "WLM", 2024, "Poland")
        val foreignRound = roundDao.create(
          Round(None, 1, contestId = foreignContest.getId, rates = Round.ratesById(5), active = true)
        )
        val juror = userDao.create(
          User("Juror", "juror@example.com", None, Set("jury"), contestId = homeContest.id)
        )
        imageDao.batchInsert(Seq(image(1002L, "PL-1002")))

        val result = largeViewController
          .rateByPageId(foreignRound.getId, 1002L, select = 5, module = "gallery", rate = Some(0))
          .apply(sessionRequest(GET, s"/large/round/${foreignRound.getId}/pageid/1002/select/5?rate=0", juror.email))
          .run()

        expectUnauthorized(result)
        SelectionJdbc.findAll() must beEmpty
      }
    }
  }

  "ImageDiscussionController.addComment" should {
    "reject cross-contest comment mutations" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val homeContest = contestDao.create(None, "WLE", 2024, "Ukraine")
        val foreignContest = contestDao.create(None, "WLM", 2024, "Poland")
        val foreignRound = roundDao.create(
          Round(None, 1, contestId = foreignContest.getId, rates = Round.commentsOnlyRound, active = true)
        )
        val juror = userDao.create(
          User("Juror", "juror@example.com", None, Set("jury"), contestId = homeContest.id)
        )
        imageDao.batchInsert(Seq(image(1003L, "PL-1003")))

        val request = FakeRequest(POST, s"/comment/region/all/pageid/1003")
          .withFormUrlEncodedBody("id" -> "0", "text" -> "should not be saved")
          .withSession(Secured.UserName -> juror.email)
          .withCSRFToken

        val result = imageDiscussionController
          .addComment(
            pageId = 1003L,
            region = "all",
            rate = Some(0),
            module = "gallery",
            round = Some(foreignRound.getId),
            contestId = Some(foreignContest.getId)
          )
          .apply(request)
          .run()

        expectUnauthorized(result)
        CommentJdbc.findAll() must beEmpty
      }
    }
  }
}
