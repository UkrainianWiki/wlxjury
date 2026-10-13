package controllers

import db.scalikejdbc.{ImageJdbc, PlayTestDb, Round, RoundUser, SelectionJdbc, User}
import modules.BlockingExecutionContext
import org.apache.pekko.stream.Materializer
import play.api.Application
import play.api.mvc.ControllerComponents
import org.intracer.wmua.cmd.DistributeImages
import org.intracer.wmua.{CommentJdbc, Image}
import org.specs2.mock.Mockito.mock
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, Helpers, PlaySpecification}
import services.{GalleryService, RoundService}

class MutationAuthorizationSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val galleryService = new GalleryService

  private def galleryController(app: Application) =
    new GalleryController(galleryService, Helpers.stubControllerComponents(),
      new BlockingExecutionContext(app.actorSystem))

  private def largeViewController =
    new LargeViewController(Helpers.stubControllerComponents(), galleryService)

  private def imageDiscussionController =
    new ImageDiscussionController(Helpers.stubControllerComponents())

  // the app's components: stubControllerComponents' body parser drops the form
  private def roundController(app: Application) =
    new RoundController(
      app.injector.instanceOf[ControllerComponents],
      mock[ContestController],
      new RoundService(new DistributeImages(ImageJdbc), Round),
      new DistributeImages(ImageJdbc),
      new BlockingExecutionContext(app.actorSystem)
    )

  /** The round form as the edit page posts it. */
  private def saveRoundRequest(email: String, contestId: Long, roundId: Option[Long], name: String) =
    FakeRequest(POST, "/admin/rounds/save")
      .withFormUrlEncodedBody(
        Seq(
          "number" -> "1", "name" -> name, "contest" -> contestId.toString, "roles" -> "jury",
          "distribution" -> "0", "rates" -> "1", "minMpx" -> "", "minSize" -> "",
          "mediaType" -> "all", "jurors[0]" -> "1"
        ) ++ roundId.map(id => "id" -> id.toString): _*
      )
      .withSession(Secured.UserName -> email)
      .withCSRFToken

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

        val result = galleryController(app)
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

  "RoundController.saveRound" should {
    "save a round of the admin's own contest" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val homeContest = contestDao.create(None, "WLE", 2024, "Ukraine")
        val homeRound = roundDao.create(Round(None, 1, Some("Home"), contestId = homeContest.getId, active = true))
        val admin = userDao.create(
          User("Admin", "admin@example.com", None, Set(User.ADMIN_ROLE), contestId = homeContest.id)
        )

        val result = call(
          roundController(app).saveRound(),
          saveRoundRequest(admin.email, homeContest.getId, homeRound.id, "Renamed")
        )

        status(result) mustEqual SEE_OTHER
        redirectLocation(result) must beSome.which(_.startsWith("/admin/rounds"))
        roundDao.findById(homeRound.getId).flatMap(_.name) must beSome("Renamed")
      }
    }

    "reject creating a round in another contest" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val homeContest = contestDao.create(None, "WLE", 2024, "Ukraine")
        val foreignContest = contestDao.create(None, "WLM", 2024, "Poland")
        val admin = userDao.create(
          User("Admin", "admin@example.com", None, Set(User.ADMIN_ROLE), contestId = homeContest.id)
        )

        val result = call(
          roundController(app).saveRound(),
          saveRoundRequest(admin.email, foreignContest.getId, None, "Intruder")
        )

        expectUnauthorized(result)
        roundDao.findByContest(foreignContest.getId) must beEmpty
      }
    }

    "reject editing another contest's round" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val homeContest = contestDao.create(None, "WLE", 2024, "Ukraine")
        val foreignContest = contestDao.create(None, "WLM", 2024, "Poland")
        val foreignRound =
          roundDao.create(Round(None, 1, Some("Foreign"), contestId = foreignContest.getId, active = true))
        val admin = userDao.create(
          User("Admin", "admin@example.com", None, Set(User.ADMIN_ROLE), contestId = homeContest.id)
        )

        // the admin's own contest in the form, another contest's round id
        val result = call(
          roundController(app).saveRound(),
          saveRoundRequest(admin.email, homeContest.getId, foreignRound.id, "Renamed")
        )

        expectUnauthorized(result)
        roundDao.findById(foreignRound.getId).flatMap(_.name) must beSome("Foreign")
      }
    }
  }

  /** A POST of `form` to `url` with the user's session, as the round pages submit it. */
  private def adminPost(url: String, email: String, form: (String, String)*) =
    FakeRequest(POST, url)
      .withFormUrlEncodedBody(form: _*)
      .withSession(Secured.UserName -> email)
      .withCSRFToken

  /** An admin of a home contest, and a round of their own and of a foreign contest,
    * both active, each with the admin's juror active in it.
    */
  private def roundsOfTwoContests() = {
    val homeContest = contestDao.create(None, "WLE", 2024, "Ukraine")
    val foreignContest = contestDao.create(None, "WLM", 2024, "Poland")
    val homeRound = roundDao.create(Round(None, 1, Some("Home"), contestId = homeContest.getId, active = true))
    val foreignRound =
      roundDao.create(Round(None, 1, Some("Foreign"), contestId = foreignContest.getId, active = true))
    val admin = userDao.create(
      User("Admin", "admin@example.com", None, Set(User.ADMIN_ROLE), contestId = homeContest.id)
    )
    val juror = userDao.create(User("Juror", "juror@example.com", None, Set("jury"), contestId = homeContest.id))
    Seq(homeRound, foreignRound).foreach(r => r.addUsers(Seq(RoundUser(r.getId, juror.getId, "jury", active = true))))
    (admin, juror, homeRound, foreignRound)
  }

  private def roundUserActive(roundId: Long, userId: Long): Option[Boolean] =
    RoundUser.byRoundId(roundId).find(_.userId == userId).map(_.active)

  "RoundController.setRound" should {
    "stop a round of the admin's own contest" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val (admin, _, homeRound, _) = roundsOfTwoContests()

        val result = call(
          roundController(app).setRound(),
          adminPost("/admin/setround", admin.email, "currentId" -> homeRound.getId.toString, "setActive" -> "false")
        )

        status(result) mustEqual SEE_OTHER
        redirectLocation(result) must beSome.which(_.startsWith("/admin/rounds"))
        roundDao.findById(homeRound.getId).map(_.active) must beSome(false)
      }
    }

    "reject stopping another contest's round" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val (admin, _, _, foreignRound) = roundsOfTwoContests()

        val result = call(
          roundController(app).setRound(),
          adminPost("/admin/setround", admin.email, "currentId" -> foreignRound.getId.toString, "setActive" -> "false")
        )

        expectUnauthorized(result)
        roundDao.findById(foreignRound.getId).map(_.active) must beSome(true)
      }
    }
  }

  "RoundController.setRoundUser" should {
    "deactivate a juror in a round of the admin's own contest" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val (admin, juror, homeRound, _) = roundsOfTwoContests()

        val result = call(
          roundController(app).setRoundUser(),
          adminPost("/round/setrounduser", admin.email,
            "parentId" -> homeRound.getId.toString, "currentId" -> juror.getId.toString, "setActive" -> "false")
        )

        status(result) mustEqual SEE_OTHER
        roundUserActive(homeRound.getId, juror.getId) must beSome(false)
      }
    }

    "reject deactivating a juror in another contest's round" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val (admin, juror, _, foreignRound) = roundsOfTwoContests()

        val result = call(
          roundController(app).setRoundUser(),
          adminPost("/round/setrounduser", admin.email,
            "parentId" -> foreignRound.getId.toString, "currentId" -> juror.getId.toString, "setActive" -> "false")
        )

        expectUnauthorized(result)
        roundUserActive(foreignRound.getId, juror.getId) must beSome(true)
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
