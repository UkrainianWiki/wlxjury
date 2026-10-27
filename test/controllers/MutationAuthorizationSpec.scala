package controllers

import db.scalikejdbc.{ImageJdbc, PlayTestDb, Round, RoundUser, SelectionJdbc, User, UserContestJdbc}
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
      services.TestRoundServices.roundService(),
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
    status(result) mustEqual FORBIDDEN
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

  "GalleryController.selectWS on the juror's own round" should {
    "take votes only while the round is open to its jurors" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val contest = contestDao.create(None, "WLE", 2024, "Ukraine")
        val juror = userDao.create(User("Juror", "juror@example.com", None, Set("jury"), contestId = contest.id))
        imageDao.batchInsert(Seq(image(1004L, "UA-1004")))

        def vote(round: Round) = {
          SelectionJdbc.create(1004L, 0, juror.getId, round.getId)
          val result = galleryController(app)
            .selectWS(round.getId, 1004L, select = 1, module = "gallery", rate = Some(0), criteria = None)
            .apply(sessionRequest(POST, s"/rate/round/${round.getId}/pageid/1004/select/1?rate=0", juror.email))
            .run()
          (status(result), SelectionJdbc.findBy(1004L, juror.getId, round.getId).map(_.rate))
        }

        val open = roundDao.create(Round(None, 1, contestId = contest.getId, active = true))
        val stopped = roundDao.create(Round(None, 2, contestId = contest.getId, active = false))
        val distributing = roundDao.create(Round(None, 3, contestId = contest.getId, active = true, distributing = true))

        vote(open) === ((OK, Some(1)))
        vote(stopped) === ((CONFLICT, Some(0))) // e.g. a previous round frozen during a distribution
        vote(distributing) === ((CONFLICT, Some(0)))
      }
    }
  }

  "JobController" should {
    "let only the admins of the job's contest see, cancel or retry it" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val home = contestDao.create(None, "WLE", 2024, "Ukraine")
        val foreign = contestDao.create(None, "WLM", 2024, "Poland")
        val admin = userDao.create(User("Admin", "admin@example.com", None, Set("admin"), contestId = home.id))
        val scheduler = services.TestRoundServices.inlineScheduler(services.TestRoundServices.handler())
        val job = scalikejdbc.DB.localTx { implicit s =>
          scheduler.enqueue(services.jobs.NewJob(
            services.distribution.RoundDistributionHandler.Kind, contestId = foreign.id,
            subjectType = Some("round"), subjectId = Some(1L))).toOption.get
        }
        val controller = new JobController(
          app.injector.instanceOf[ControllerComponents], scheduler, new BlockingExecutionContext(app.actorSystem))

        expectUnauthorized(controller.status(job.id)
          .apply(sessionRequest(GET, s"/admin/jobs/${job.id}", admin.email)).run())
        expectUnauthorized(controller.cancel(job.id)
          .apply(sessionRequest(POST, s"/admin/jobs/${job.id}/cancel", admin.email)).run())
        services.jobs.JobJdbc.findById(job.id).map(_.status) === Some(services.jobs.JobStatus.Running)

        val own = userDao.create(User("Own", "own@example.com", None, Set("admin"), contestId = foreign.id))
        val ok = controller.status(job.id).apply(sessionRequest(GET, s"/admin/jobs/${job.id}", own.email)).run()
        status(ok) === OK
        (contentAsJson(ok) \ "status").as[String] === "running"
      }
    }

    "go back only to a local page after Cancel, else to the job's contest's rounds" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val contest = contestDao.create(None, "WLE", 2024, "Ukraine")
        val admin = userDao.create(User("Admin", "admin@example.com", None, Set("admin"), contestId = contest.id))
        val scheduler = services.TestRoundServices.inlineScheduler(services.TestRoundServices.handler())
        val job = scalikejdbc.DB.localTx { implicit s =>
          scheduler.enqueue(services.jobs.NewJob(
            services.distribution.RoundDistributionHandler.Kind, contestId = contest.id,
            subjectType = Some("round"), subjectId = Some(1L))).toOption.get
        }
        val controller = new JobController(
          app.injector.instanceOf[ControllerComponents], scheduler, new BlockingExecutionContext(app.actorSystem))
        def cancel(returnTo: String) = {
          val result = call(controller.cancel(job.id),
            adminPost(s"/admin/jobs/${job.id}/cancel", admin.email, "returnTo" -> returnTo))
          status(result) === SEE_OTHER
          redirectLocation(result)
        }
        val rounds = routes.RoundController.rounds(contest.id).url

        cancel("/admin/rounds?contestId=1") === Some("/admin/rounds?contestId=1")
        cancel("/\\evil.example") === Some(rounds)
        cancel("https://evil.example") === Some(rounds)
        cancel("//evil.example") === Some(rounds)
      }
    }
  }

  "an admin of two contests, with the session in the other one" should {
    // contest A's job and round, and three admins: of A and B, of B only, and of B with
    // A's membership deactivated
    def setup(app: Application) = {
      val a = contestDao.create(None, "WLE", 2024, "Ukraine")
      val b = contestDao.create(None, "WLM", 2024, "Poland")
      val both = userDao.create(User("Both", "both@example.com", None, Set("admin"), contestId = a.id))
      UserContestJdbc.createMembership(both.getId, b.getId, "admin")
      val bOnly = userDao.create(User("B only", "bonly@example.com", None, Set("admin"), contestId = b.id))
      val former = userDao.create(User("Former", "former@example.com", None, Set(User.NO_ROLE), contestId = a.id))
      UserContestJdbc.createMembership(former.getId, b.getId, "admin")
      val scheduler = services.TestRoundServices.inlineScheduler(services.TestRoundServices.handler())
      val job = scalikejdbc.DB.localTx { implicit s =>
        scheduler.enqueue(services.jobs.NewJob(
          services.distribution.RoundDistributionHandler.Kind, contestId = a.id,
          subjectType = Some("round"), subjectId = Some(1L))).toOption.get
      }
      val round = roundDao.create(Round(None, 1, Some("A's round"), contestId = a.getId, active = true))
      val juror = userDao.create(User("Juror", "juror@example.com", None, Set("jury"), contestId = a.id))
      round.addUsers(Seq(RoundUser(round.getId, juror.getId, "jury", active = true)))
      val jobs = new JobController(
        app.injector.instanceOf[ControllerComponents], scheduler, new BlockingExecutionContext(app.actorSystem))
      (b, both, bOnly, former, job, round, jobs)
    }

    def inContest(method: String, url: String, email: String, contestId: Long) =
      FakeRequest(method, url)
        .withSession(Secured.UserName -> email, Secured.CurrentContestId -> contestId.toString)
        .withHeaders("X-Requested-With" -> "XMLHttpRequest")
        .withCSRFToken

    "see A's job status and count A's round's new files, without switching the session" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val (b, both, _, _, job, round, jobs) = setup(app)

        val status1 = jobs.status(job.id).apply(inContest(GET, s"/admin/jobs/${job.id}", both.email, b.getId)).run()
        status(status1) === OK
        (contentAsJson(status1) \ "status").as[String] === "running"
        session(status1).get(Secured.CurrentContestId) must beNone

        val url = s"/admin/rounds/newfiles?id=${round.getId}"
        val count = roundController(app).newFilesCount(round.getId).apply(inContest(GET, url, both.email, b.getId)).run()
        status(count) === OK
        (contentAsJson(count) \ "count").as[Long] === 0L
        session(count).get(Secured.CurrentContestId) must beNone

        val table = roundController(app).roundStatTable(round.getId)
          .apply(inContest(GET, s"/admin/rounds/${round.getId}/stat-table", both.email, b.getId)).run()
        status(table) === OK
        session(table).get(Secured.CurrentContestId) must beNone
      }
    }

    "still be denied A's job and round as an admin of B only, or with A's membership deactivated" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val (b, _, bOnly, former, job, round, jobs) = setup(app)

        Seq(bOnly, former).foreach { admin =>
          val denied = jobs.status(job.id).apply(inContest(GET, s"/admin/jobs/${job.id}", admin.email, b.getId)).run()
          status(denied) === FORBIDDEN
          (contentAsJson(denied) \ "error").as[String] === "forbidden"
          (contentAsJson(denied) \ "reference").as[String] must beMatching("[0-9a-f]{8}")

          status(jobs.cancel(job.id)
            .apply(inContest(POST, s"/admin/jobs/${job.id}/cancel", admin.email, b.getId)).run()) === FORBIDDEN

          val count = roundController(app).newFilesCount(round.getId)
            .apply(inContest(GET, s"/admin/rounds/newfiles?id=${round.getId}", admin.email, b.getId)).run()
          status(count) === FORBIDDEN
          (contentAsJson(count) \ "error").as[String] === "forbidden"

          status(roundController(app).roundStatTable(round.getId)
            .apply(inContest(GET, s"/admin/rounds/${round.getId}/stat-table", admin.email, b.getId)).run()) ===
            FORBIDDEN
        }
        services.jobs.JobJdbc.findById(job.id).map(_.status) === Some(services.jobs.JobStatus.Running)
      }
    }
  }

  "a juror of contest C who is an admin of contest A, with the session in C" should {
    // the admin permission is found only in A, so a page request switches the session to A
    def setup() = {
      val a = contestDao.create(None, "WLE", 2024, "Ukraine")
      val c = contestDao.create(None, "WLM", 2024, "Poland")
      val user = userDao.create(User("Juror and admin", "mixed@example.com", None, Set("jury"), contestId = c.id))
      UserContestJdbc.createMembership(user.getId, a.getId, "admin")
      val round = roundDao.create(Round(None, 1, Some("A's round"), contestId = a.getId, active = true))
      (a, c, user, round)
    }

    def inC(method: String, url: String, email: String, c: Long, headers: (String, String)*) =
      FakeRequest(method, url)
        .withSession(Secured.UserName -> email, Secured.CurrentContestId -> c.toString)
        .withHeaders(headers: _*)
        .withCSRFToken

    "load A's stat table by script without switching the session, but switch it for a page" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val (a, c, user, round) = setup()
        def table(headers: (String, String)*) = roundController(app).roundStatTable(round.getId)
          .apply(inC(GET, s"/roundstat/${round.getId}/table", user.email, c.getId, headers: _*)).run()

        val xhr = table("X-Requested-With" -> "XMLHttpRequest")
        status(xhr) === OK
        session(xhr).get(Secured.CurrentContestId) must beNone

        val fetched = table("Sec-Fetch-Mode" -> "cors")
        status(fetched) === OK
        session(fetched).get(Secured.CurrentContestId) must beNone

        val page = table()
        status(page) === OK
        session(page).get(Secured.CurrentContestId) === Some(a.getId.toString)

        val navigated = table("Sec-Fetch-Mode" -> "navigate")
        status(navigated) === OK
        session(navigated).get(Secured.CurrentContestId) === Some(a.getId.toString)
      }
    }

    "stop A's round by a form POST without switching the session" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val (_, c, user, round) = setup()

        val result = call(
          roundController(app).setRound(),
          FakeRequest(POST, "/admin/setround")
            .withFormUrlEncodedBody("currentId" -> round.getId.toString, "setActive" -> "false")
            .withSession(Secured.UserName -> user.email, Secured.CurrentContestId -> c.getId.toString)
            .withCSRFToken
        )

        status(result) === SEE_OTHER
        roundDao.findById(round.getId).map(_.active) must beSome(false)
        session(result).get(Secured.CurrentContestId) must beNone
      }
    }
  }

  "the image cache status" should {
    "answer JSON: 401 signed out, 403 to a non-admin, the progress to an admin" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val a = contestDao.create(None, "WLE", 2024, "Ukraine")
        val round = roundDao.create(Round(None, 1, Some("A's round"), contestId = a.getId, active = true))
        val juror = userDao.create(User("Juror", "juror@example.com", None, Set("jury"), contestId = a.id))
        val admin = userDao.create(User("Admin", "admin@example.com", None, Set("admin"), contestId = a.id))
        val images = app.injector.instanceOf[ImageController]
        val contestUrl = s"/contest/${a.getId}/images/cache/status"
        val roundUrl = s"/contest/${a.getId}/round/${round.getId}/images/cache/status"
        def statuses(request: String => FakeRequest[play.api.mvc.AnyContentAsEmpty.type]) = Seq(
          images.imageCacheStatus(a.getId).apply(request(contestUrl)).run(),
          images.roundImageCacheStatus(a.getId, round.getId).apply(request(roundUrl)).run()
        )

        statuses(url => FakeRequest(GET, url)).foreach { signedOut =>
          status(signedOut) === UNAUTHORIZED
          (contentAsJson(signedOut) \ "error").as[String] === "unauthenticated"
        }
        statuses(url => FakeRequest(GET, url).withSession(Secured.UserName -> juror.email)).foreach { denied =>
          status(denied) === FORBIDDEN
          (contentAsJson(denied) \ "error").as[String] === "forbidden"
          (contentAsJson(denied) \ "reference").as[String] must beMatching("[0-9a-f]{8}")
        }
        statuses(url => FakeRequest(GET, url).withSession(Secured.UserName -> admin.email)).foreach { allowed =>
          status(allowed) === OK
          (contentAsJson(allowed) \ "running").as[Boolean] === false
        }
        ok
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
