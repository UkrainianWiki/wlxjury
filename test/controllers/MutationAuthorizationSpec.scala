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
    new ImageDiscussionController(Helpers.stubControllerComponents(), galleryService)

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

  /** `f`'s result and the denials ([[Secured.logDenial]]) logged on security.authz meanwhile. */
  private def withAuthzLog[T](f: => T): (T, Seq[String]) = {
    val logger = org.slf4j.LoggerFactory.getLogger("security.authz").asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    appender.start()
    logger.addAppender(appender)
    try {
      val result = f
      import scala.jdk.CollectionConverters._
      (result, appender.list.asScala.toSeq.map(_.getFormattedMessage))
    } finally logger.detachAppender(appender)
  }

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
          .selectWS(foreignRound.getId, 1001L, select = 1)
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
            .selectWS(round.getId, 1004L, select = 1)
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

  // A vote's rate and file, the gallery's (selectWS, JSON) and the large view's (rateByPageId, a
  // page or a redirect): only a rate the round takes (Round.acceptsRate), stored in the
  // juror's own selection of the file; a file not assigned to the juror changes nothing (404).
  "a juror's vote, in the gallery and the large view" should {
    val pageId = 1010L

    /** A contest, its juror and a file. */
    def setUp(): User = {
      val contest = contestDao.create(None, "WLE", 2024, "Ukraine")
      imageDao.batchInsert(Seq(image(pageId, "UA-1010")))
      userDao.create(User("Juror", "juror@example.com", None, Set("jury"), contestId = contest.id))
    }

    /** A round of the juror's contest, of `rates` (half stars if `half`), the juror's selection
      * of the file there rated `rate`. */
    def roundOf(juror: User, rates: db.scalikejdbc.Rates, half: Boolean = false, active: Boolean = true,
        rate: Int = 0): Round = {
      val round = roundDao.create(
        Round(None, 1, contestId = juror.contestId.get, rates = rates, halfStar = half, active = active))
      round.addUsers(Seq(RoundUser(round.getId, juror.getId, "jury", active = true)))
      SelectionJdbc.create(pageId, rate, juror.getId, round.getId)
      round
    }

    def stored(round: Round, juror: User, file: Long = pageId) =
      SelectionJdbc.findBy(file, juror.getId, round.getId).map(_.rate)

    def gallery(app: Application, round: Round, juror: User, rate: Int, file: Long = pageId) = {
      implicit val materializer: Materializer = app.materializer
      galleryController(app).selectWS(round.getId, file, rate)
        .apply(sessionRequest(POST, s"/rate/round/${round.getId}/pageid/$file/select/$rate", juror.email)).run()
    }

    def large(app: Application, round: Round, juror: User, rate: Int, file: Long = pageId,
        criteria: Option[Int] = None) = {
      implicit val materializer: Materializer = app.materializer
      largeViewController.rateByPageId(round.getId, file, rate, criteria = criteria)
        .apply(sessionRequest(POST, s"/large/round/${round.getId}/pageid/$file/select/$rate", juror.email)).run()
    }

    /** The gallery's answer to `rate` and the stored rate then; the same for the large view;
      * the file unrated before each. */
    def both(app: Application, round: Round, juror: User, rate: Int) = {
      SelectionJdbc.rate(pageId, juror.getId, round.getId, 0)
      val inGallery = status(gallery(app, round, juror, rate))
      val afterGallery = stored(round, juror)
      SelectionJdbc.rate(pageId, juror.getId, round.getId, 0)
      val inLarge = status(large(app, round, juror, rate))
      (inGallery, afterGallery, inLarge, stored(round, juror))
    }

    "take -1, 0 and 1 in a binary round, and refuse 2" in {
      testDbApp { app =>
        val juror = setUp()
        val round = roundOf(juror, Round.binaryRound)
        both(app, round, juror, 1) === ((OK, Some(1), SEE_OTHER, Some(1)))
        both(app, round, juror, -1) === ((OK, Some(-1), SEE_OTHER, Some(-1)))
        both(app, round, juror, 0) === ((OK, Some(0), SEE_OTHER, Some(0)))
        both(app, round, juror, 2) === ((BAD_REQUEST, Some(0), BAD_REQUEST, Some(0)))
        (contentAsJson(gallery(app, round, juror, 2)) \ "error").as[String] === "invalid_rate"
        contentAsString(large(app, round, juror, 2)) must contain("vote.error.invalid.rate")
      }
    }

    "take 0 and 1..5 in a 1-5 round, and refuse 6 and negative rates" in {
      testDbApp { app =>
        val juror = setUp()
        val round = roundOf(juror, Round.ratesById(5))
        both(app, round, juror, 0) === ((OK, Some(0), SEE_OTHER, Some(0)))
        both(app, round, juror, 1) === ((OK, Some(1), SEE_OTHER, Some(1)))
        both(app, round, juror, 5) === ((OK, Some(5), SEE_OTHER, Some(5)))
        both(app, round, juror, 6) === ((BAD_REQUEST, Some(0), BAD_REQUEST, Some(0)))
        both(app, round, juror, -1) === ((BAD_REQUEST, Some(0), BAD_REQUEST, Some(0)))
        both(app, round, juror, 99) === ((BAD_REQUEST, Some(0), BAD_REQUEST, Some(0)))
      }
    }

    "take half stars in a half-star round, in its stored units, up to all its stars" in {
      testDbApp { app =>
        val juror = setUp()
        // 1-10 in half stars: five stars, a half star stored as 1, all five as 10
        val round = roundOf(juror, Round.ratesById(10), half = true)
        both(app, round, juror, 1) === ((OK, Some(1), SEE_OTHER, Some(1)))
        both(app, round, juror, 10) === ((OK, Some(10), SEE_OTHER, Some(10)))
        both(app, round, juror, 11) === ((BAD_REQUEST, Some(0), BAD_REQUEST, Some(0)))
        // 1-7 in half stars draws three stars: 6 at most
        val odd = roundOf(juror, Round.ratesById(7), half = true)
        both(app, odd, juror, 6) === ((OK, Some(6), SEE_OTHER, Some(6)))
        both(app, odd, juror, 7) === ((BAD_REQUEST, Some(0), BAD_REQUEST, Some(0)))
      }
    }

    "take no votes in a comments-only round" in {
      testDbApp { app =>
        val juror = setUp()
        val round = roundOf(juror, Round.commentsOnlyRound)
        both(app, round, juror, 0) === ((BAD_REQUEST, Some(0), BAD_REQUEST, Some(0)))
        both(app, round, juror, 1) === ((BAD_REQUEST, Some(0), BAD_REQUEST, Some(0)))
      }
    }

    /** [[roundOf]] with criteria 1 and 2, the juror's selection's rates of them 3 and 4. */
    def criteriaRoundOf(juror: User): Round = {
      val round = roundOf(juror, Round.ratesById(10), rate = 7)
      scalikejdbc.DB.autoCommit { implicit s =>
        import scalikejdbc._
        sql"UPDATE rounds SET has_criteria = 1 WHERE id = ${round.getId}".update()
        val id = SelectionJdbc.findBy(pageId, juror.getId, round.getId).get.getId
        sql"INSERT INTO criteria_rate (id, selection, criteria, rate) VALUES (${id * 10 + 1}, $id, 1, 3), (${id * 10 + 2}, $id, 2, 4)"
          .update()
      }
      roundDao.findById(round.getId).get
    }

    def criteriaOf(round: Round, juror: User, file: Long = pageId): Map[Long, Int] =
      SelectionJdbc.findBy(file, juror.getId, round.getId).toSeq
        .flatMap(s => org.intracer.wmua.CriteriaRate.getRates(s.getId)).map(c => c.criteria -> c.rate).toMap

    "check a criterion's rate as the file's" in {
      testDbApp { app =>
        val juror = setUp()
        val round = criteriaRoundOf(juror)
        status(large(app, round, juror, 11, criteria = Some(1))) === BAD_REQUEST
        criteriaOf(round, juror) === Map(1L -> 3, 2L -> 4)
        status(large(app, round, juror, 10, criteria = Some(1))) === SEE_OTHER
        criteriaOf(round, juror) === Map(1L -> 10, 2L -> 4)
        stored(round, juror) === Some(14)
      }
    }

    "take only criterion votes in a criteria round: 400 for the file's own rate, 404 for a criterion it hasn't" in {
      testDbApp { app =>
        val juror = setUp()
        val round = criteriaRoundOf(juror)
        // the file's own rate would overwrite the sum of its criteria: refused in both views
        val inGallery = gallery(app, round, juror, 5)
        status(inGallery) === BAD_REQUEST
        (contentAsJson(inGallery) \ "error").as[String] === "invalid_rate"
        val inLarge = large(app, round, juror, 5)
        status(inLarge) === BAD_REQUEST
        contentAsString(inLarge) must contain("vote.error.invalid.rate")
        // a criterion the juror's selection hasn't: not found, not a success
        val unknown = large(app, round, juror, 5, criteria = Some(3))
        status(unknown) === NOT_FOUND
        contentAsString(unknown) must contain("error.not.found")
        stored(round, juror) === Some(7)
        criteriaOf(round, juror) === Map(1L -> 3, 2L -> 4)
      }
    }

    "refuse a criterion in a round without criteria, changing nothing" in {
      testDbApp { app =>
        val juror = setUp()
        val round = roundOf(juror, Round.ratesById(10), rate = 2)
        val result = large(app, round, juror, 5, criteria = Some(1))
        status(result) === BAD_REQUEST
        contentAsString(result) must contain("vote.error.invalid.rate")
        stored(round, juror) === Some(2)
      }
    }

    "change only the juror's own criteria: 404 for another's file and for an organiser not a juror, 403 for another contest's juror" in {
      testDbApp { app =>
        val juror = setUp()
        val round = criteriaRoundOf(juror)
        val other = userDao.create(User("Other", "other@example.com", None, Set("jury"), contestId = juror.contestId))
        round.addUsers(Seq(RoundUser(round.getId, other.getId, "jury", active = true)))
        imageDao.batchInsert(Seq(image(1011L, "UA-1011")))
        val othersVote = SelectionJdbc.create(1011L, 5, other.getId, round.getId)
        scalikejdbc.DB.autoCommit { implicit s =>
          import scalikejdbc._
          sql"INSERT INTO criteria_rate (id, selection, criteria, rate) VALUES (${othersVote.getId * 10 + 1}, ${othersVote.getId}, 1, 5)"
            .update()
        }
        val organizer = userDao.create(User("Organizer", "organizer@example.com", None, Set("organizer"), contestId = juror.contestId))
        val foreign = contestDao.create(None, "WLM", 2024, "Poland")
        val outsider = userDao.create(User("Outsider", "outsider@example.com", None, Set("jury"), contestId = foreign.id))

        // another juror's file: the juror has no selection of it
        status(large(app, round, juror, 1, file = 1011L, criteria = Some(1))) === NOT_FOUND
        // the organiser has no selection of their own in the round
        status(large(app, round, organizer, 1, criteria = Some(1))) === NOT_FOUND
        status(large(app, round, organizer, 1, file = 1011L, criteria = Some(1))) === NOT_FOUND
        status(large(app, round, outsider, 1, criteria = Some(1))) === FORBIDDEN
        // signed out: to the login page
        implicit val materializer: Materializer = app.materializer
        val signedOut = largeViewController.rateByPageId(round.getId, pageId, 1, criteria = Some(1))
          .apply(FakeRequest(POST, s"/large/round/${round.getId}/pageid/$pageId/select/1?criteria=1").withCSRFToken).run()
        status(signedOut) === SEE_OTHER
        redirectLocation(signedOut) must beSome.which(_.startsWith(routes.LoginController.login(None).url))

        criteriaOf(round, juror) === Map(1L -> 3, 2L -> 4)
        criteriaOf(round, other, 1011L) === Map(1L -> 5)
        stored(round, juror) === Some(7)
        stored(round, other, 1011L) === Some(5)
      }
    }

    "change nothing in a stopped round: 409 in the gallery, back to the gallery from the large view" in {
      testDbApp { app =>
        val juror = setUp()
        val round = roundOf(juror, Round.ratesById(5), active = false, rate = 2)
        status(gallery(app, round, juror, 4)) === CONFLICT
        val fromLarge = large(app, round, juror, 4)
        status(fromLarge) === SEE_OTHER
        redirectLocation(fromLarge) must beSome.which(_.startsWith(s"/gallery/round/${round.getId}/"))
        stored(round, juror) === Some(2)
      }
    }

    "change nothing for a juror stopped on the round (P6): 409 in the gallery, back to the gallery from the large view" in {
      testDbApp { app =>
        val juror = setUp()
        val round = roundOf(juror, Round.ratesById(5), rate = 2)
        val other = userDao.create(User("Other", "other@example.com", None, Set("jury"), contestId = juror.contestId))
        round.addUsers(Seq(RoundUser(round.getId, other.getId, "jury", active = true)))
        SelectionJdbc.create(pageId, 3, other.getId, round.getId)
        RoundUser.setActive(round.getId, juror.getId, active = false)

        val inGallery = gallery(app, round, juror, 4)
        status(inGallery) === CONFLICT
        (contentAsJson(inGallery) \ "error").as[String] === "round_closed"
        val fromLarge = large(app, round, juror, 4)
        status(fromLarge) === SEE_OTHER
        redirectLocation(fromLarge) must beSome.which(_.startsWith(s"/gallery/round/${round.getId}/"))
        stored(round, juror) === Some(2)

        // only the stopped juror: another juror of the round still votes
        status(gallery(app, round, other, 5)) === OK
        stored(round, other) === Some(5)

        // active again: the juror votes
        RoundUser.setActive(round.getId, juror.getId, active = true)
        status(gallery(app, round, juror, 4)) === OK
        stored(round, juror) === Some(4)
      }
    }

    "be refused by the vote's statement itself for a stopped juror (a stop racing the vote, P6)" in {
      testDbApp { app =>
        val juror = setUp()
        val round = roundOf(juror, Round.ratesById(5), rate = 2)
        RoundUser.setActive(round.getId, juror.getId, active = false)
        SelectionJdbc.rate(pageId, juror.getId, round.getId, 4) === 0
        stored(round, juror) === Some(2)

      }
    }

    "be refused by the criterion vote's statement itself for a stopped juror (P6)" in {
      testDbApp { app =>
        val juror = setUp()
        val round = criteriaRoundOf(juror)
        RoundUser.setActive(round.getId, juror.getId, active = false)
        val selection = SelectionJdbc.findBy(pageId, juror.getId, round.getId).get
        org.intracer.wmua.CriteriaRate.updateRate(selection.getId, 1, 10) must beFalse
        criteriaOf(round, juror) === Map(1L -> 3, 2L -> 4)
        stored(round, juror) === Some(7)
      }
    }

    "change no criterion for a juror stopped on a criteria round (P6)" in {
      testDbApp { app =>
        val juror = setUp()
        val round = criteriaRoundOf(juror)
        RoundUser.setActive(round.getId, juror.getId, active = false)

        val result = large(app, round, juror, 10, criteria = Some(1))
        status(result) === SEE_OTHER
        redirectLocation(result) must beSome.which(_.startsWith(s"/gallery/round/${round.getId}/"))
        criteriaOf(round, juror) === Map(1L -> 3, 2L -> 4)
        stored(round, juror) === Some(7)
      }
    }

    "still take the votes of a juror with no round_user row (a legacy round's)" in {
      testDbApp { app =>
        val juror = setUp()
        val round = roundOf(juror, Round.ratesById(5), rate = 2)
        scalikejdbc.DB.autoCommit { implicit s =>
          import scalikejdbc._
          sql"DELETE FROM round_user WHERE round_id = ${round.getId}".update()
        }
        status(gallery(app, round, juror, 4)) === OK
        stored(round, juror) === Some(4)
        status(large(app, round, juror, 5)) === SEE_OTHER
        stored(round, juror) === Some(5)
      }
    }

    "change only the juror's own selection: a 404 for a file not assigned to them, another's kept" in {
      testDbApp { app =>
        val juror = setUp()
        val round = roundOf(juror, Round.ratesById(5), rate = 2)
        val other = userDao.create(User("Other", "other@example.com", None, Set("jury"), contestId = juror.contestId))
        round.addUsers(Seq(RoundUser(round.getId, other.getId, "jury", active = true)))
        imageDao.batchInsert(Seq(image(1011L, "UA-1011")))
        SelectionJdbc.create(1011L, 3, other.getId, round.getId)

        // the juror has no selection of the file: not found, and none created
        val inGallery = gallery(app, round, juror, 5, file = 1011L)
        status(inGallery) === NOT_FOUND
        (contentAsJson(inGallery) \ "error").as[String] === "not_found"
        val inLarge = large(app, round, juror, 4, file = 1011L)
        status(inLarge) === NOT_FOUND
        contentAsString(inLarge) must contain("error.not.found")
        // nor a file not in the round at all
        status(gallery(app, round, juror, 5, file = 9999L)) === NOT_FOUND
        status(large(app, round, juror, 4, file = 9999L)) === NOT_FOUND
        stored(round, juror, 1011L) must beNone
        stored(round, juror, 9999L) must beNone
        stored(round, other, 1011L) === Some(3)
        stored(round, juror) === Some(2)
        // the juror's own file, voted again with its stored rate: found, not a 404
        status(gallery(app, round, juror, 2)) === OK
        status(large(app, round, juror, 2)) === SEE_OTHER
      }
    }

    "refuse another contest's juror, changing nothing" in {
      testDbApp { app =>
        val juror = setUp()
        val round = roundOf(juror, Round.ratesById(5), rate = 2)
        val foreign = contestDao.create(None, "WLM", 2024, "Poland")
        val outsider = userDao.create(User("Outsider", "outsider@example.com", None, Set("jury"), contestId = foreign.id))
        status(gallery(app, round, outsider, 4)) === FORBIDDEN
        status(large(app, round, outsider, 4)) === FORBIDDEN
        stored(round, juror) === Some(2)
        stored(round, outsider) must beNone
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

        // another contest's job is answered as a missing one
        status(controller.status(job.id)
          .apply(sessionRequest(GET, s"/admin/jobs/${job.id}", admin.email)).run()) === NOT_FOUND
        status(controller.cancel(job.id)
          .apply(sessionRequest(POST, s"/admin/jobs/${job.id}/cancel", admin.email)).run()) === NOT_FOUND
        services.jobs.JobJdbc.findById(job.id).map(_.status) === Some(services.jobs.JobStatus.Running)

        val own = userDao.create(User("Own", "own@example.com", None, Set("admin"), contestId = foreign.id))
        val ok = controller.status(job.id).apply(sessionRequest(GET, s"/admin/jobs/${job.id}", own.email)).run()
        status(ok) === OK
        (contentAsJson(ok) \ "status").as[String] === "running"

        // a job that hasn't failed isn't retried; the admin is told why, in a message
        val retried = controller.retry(job.id).apply(sessionRequest(POST, s"/admin/jobs/${job.id}/retry", own.email)).run()
        status(retried) === SEE_OTHER
        flash(retried).get("error") must beSome("Can't retry: the job is running, not failed.")
      }
    }

    "answer another contest's job exactly as a missing one, logging the denial" in {
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
        val missing = job.id + 1000
        val controller = new JobController(
          app.injector.instanceOf[ControllerComponents], scheduler, new BlockingExecutionContext(app.actorSystem))

        val (answers, logged) = withAuthzLog {
          for (id <- Seq(job.id, missing)) yield {
            val polled = controller.status(id).apply(sessionRequest(GET, s"/admin/jobs/$id", admin.email)).run()
            val cancelled = controller.cancel(id).apply(sessionRequest(POST, s"/admin/jobs/$id/cancel", admin.email)).run()
            // the page names no id; only its forms' CSRF tokens differ between requests
            def body(result: scala.concurrent.Future[play.api.mvc.Result]) =
              contentAsString(result).replaceAll("name=\"csrfToken\" value=\"[^\"]*\"", "csrfToken")
            (status(polled), body(polled), status(cancelled), body(cancelled))
          }
        }
        val Seq(foreignJob, missingJob) = answers
        foreignJob === missingJob
        foreignJob._1 === NOT_FOUND
        foreignJob._3 === NOT_FOUND
        // the other contest's job's two requests are logged as denials, the missing one's not
        logged.size === 2
        forall(logged)(_ must contain(s"resource=\"job:${job.id}\""))
        forall(logged)(_ must contain(s"targetContestId=${foreign.getId}"))
        services.jobs.JobJdbc.findById(job.id).map(_.status) === Some(services.jobs.JobStatus.Running)
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
          // A's job is answered as a missing one
          val denied = jobs.status(job.id).apply(inContest(GET, s"/admin/jobs/${job.id}", admin.email, b.getId)).run()
          status(denied) === NOT_FOUND
          (contentAsJson(denied) \ "error").as[String] === "job not found"

          status(jobs.cancel(job.id)
            .apply(inContest(POST, s"/admin/jobs/${job.id}/cancel", admin.email, b.getId)).run()) === NOT_FOUND

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

  "a round's cache, started or read through a contest" should {
    "be refused for a round of another contest, by its admin there or not" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val a = contestDao.create(None, "WLE", 2024, "Ukraine")
        val b = contestDao.create(None, "WLM", 2024, "Poland")
        val aRound = roundDao.create(Round(None, 1, Some("A's round"), contestId = a.getId, active = true))
        val bRound = roundDao.create(Round(None, 1, Some("B's round"), contestId = b.getId, active = true))
        val admin = userDao.create(User("Admin", "admin@example.com", None, Set("admin"), contestId = a.id))
        val images = app.injector.instanceOf[ImageController]
        def statusOf(round: Round) = images.roundImageCacheStatus(a.getId, round.getId)
          .apply(sessionRequest(GET, s"/contest/${a.getId}/round/${round.getId}/images/cache/status", admin.email))
          .run()
        def start(round: Round) = call(images.startRoundImageCache(a.getId, round.getId),
          sessionRequest(POST, s"/contest/${a.getId}/round/${round.getId}/images/cache/start", admin.email))

        status(statusOf(bRound)) === NOT_FOUND
        status(start(bRound)) === NOT_FOUND

        status(statusOf(aRound)) === OK
        status(start(aRound)) === SEE_OTHER
      }
    }
  }

  "a round's thumbnail urls, listed through a contest" should {
    "be refused for a round of another contest" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val a = contestDao.create(None, "WLE", 2024, "Ukraine")
        val b = contestDao.create(None, "WLM", 2024, "Poland")
        val aRound = roundDao.create(Round(None, 1, Some("A's round"), contestId = a.getId, active = true))
        val bRound = roundDao.create(Round(None, 1, Some("B's round"), contestId = b.getId, active = true))
        val admin = userDao.create(User("Admin", "admin@example.com", None, Set("admin"), contestId = a.id))
        val gallery = app.injector.instanceOf[GalleryController]
        def urlsOf(round: Option[Round]) = call(gallery.thumbnailUrls(a.getId, round.map(_.getId)),
          sessionRequest(GET, s"/thumb_urls/${a.getId}", admin.email))

        status(urlsOf(Some(bRound))) === NOT_FOUND
        status(urlsOf(Some(aRound))) === OK
        status(urlsOf(None)) === OK
      }
    }
  }

  "a round's organizer views" should {
    // contests A and B, B's round with and without juryOrgView
    def setup() = {
      val a = contestDao.create(None, "WLE", 2024, "Ukraine")
      val b = contestDao.create(None, "WLM", 2024, "Poland")
      val open = roundDao.create(
        Round(None, 1, Some("B open round"), contestId = b.getId, active = true, juryOrgView = true))
      val closed = roundDao.create(Round(None, 2, Some("B closed round"), contestId = b.getId, active = true))
      (a, b, open, closed)
    }

    def roundStat(app: Application, round: Round, email: String) = {
      implicit val materializer: Materializer = app.materializer
      call(roundController(app).roundStat(round.getId),
        FakeRequest(GET, s"/roundstat/${round.getId}").withSession(Secured.UserName -> email).withCSRFToken)
    }

    "be refused to a juror of another contest, and to one deactivated in the round's" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val (a, b, open, _) = setup()
        val outsider = userDao.create(User("Outsider", "outsider@example.com", None, Set("jury"), contestId = a.id))
        val former = userDao.create(User("Former", "former@example.com", None, Set("jury"), contestId = a.id))
        UserContestJdbc.createMembership(former.getId, b.getId, User.NO_ROLE)

        Seq(outsider, former).foreach { juror =>
          status(roundStat(app, open, juror.email)) === FORBIDDEN
          Secured.inContest(Secured.inCurrentContest(juror, None), b.getId).canViewOrgInfo(open) === false

          val gallery = call(galleryController(app).list(0, 1, "all", open.getId),
            FakeRequest(GET, s"/gallery/round/${open.getId}").withSession(Secured.UserName -> juror.email))
          status(gallery) === FORBIDDEN
          contentAsString(gallery) must not(contain("B open round"))
        }
        ok
      }
    }

    "be open to a juror of the round's contest only if the round says so, and to its organizers and root" in {
      testDbApp { app =>
        val (_, b, open, closed) = setup()
        val juror = userDao.create(User("Juror", "juror@example.com", None, Set("jury"), contestId = b.id))
        val organizer = userDao.create(User("Org", "org@example.com", None, Set("organizer"), contestId = b.id))
        val root = User.create(User("Root", "root@example.com", roles = Set(User.ROOT_ROLE), password = Some("hash")))

        status(roundStat(app, open, juror.email)) === OK
        status(roundStat(app, closed, juror.email)) === FORBIDDEN
        Seq(organizer, root).foreach { user =>
          status(roundStat(app, open, user.email)) === OK
          status(roundStat(app, closed, user.email)) === OK
        }
        ok
      }
    }
  }

  "an admin of both contests, with the session in A" should {
    "be refused B's round's cache and thumbnail urls through A's contest" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val a = contestDao.create(None, "WLE", 2024, "Ukraine")
        val b = contestDao.create(None, "WLM", 2024, "Poland")
        val aRound = roundDao.create(Round(None, 1, Some("A's round"), contestId = a.getId, active = true))
        val bRound = roundDao.create(Round(None, 1, Some("B's round"), contestId = b.getId, active = true))
        val both = userDao.create(User("Both", "both@example.com", None, Set("admin"), contestId = a.id))
        UserContestJdbc.createMembership(both.getId, b.getId, "admin")
        val images = app.injector.instanceOf[ImageController]
        val gallery = app.injector.instanceOf[GalleryController]
        def inA(method: String, url: String) =
          FakeRequest(method, url)
            .withSession(Secured.UserName -> both.email, Secured.CurrentContestId -> a.getId.toString)
            .withCSRFToken
        def statusOf(round: Round) = call(images.roundImageCacheStatus(a.getId, round.getId),
          inA(GET, s"/contest/${a.getId}/round/${round.getId}/images/cache/status"))
        def start(round: Round) = call(images.startRoundImageCache(a.getId, round.getId),
          inA(POST, s"/contest/${a.getId}/round/${round.getId}/images/cache/start"))
        def urlsOf(round: Round) = call(gallery.thumbnailUrls(a.getId, Some(round.getId)),
          inA(GET, s"/thumb_urls/${a.getId}?roundId=${round.getId}"))

        status(statusOf(bRound)) === NOT_FOUND
        status(start(bRound)) === NOT_FOUND
        status(urlsOf(bRound)) === NOT_FOUND

        status(statusOf(aRound)) === OK
        status(urlsOf(aRound)) === OK
      }
    }
  }

  "a juror of A and B, with the session in A, and B's round open to its jurors" should {
    // B's round with juryOrgView, the juror not listed in it; a juror of A only
    def setup() = {
      val a = contestDao.create(None, "WLE", 2024, "Ukraine")
      val b = contestDao.create(None, "WLM", 2024, "Poland")
      val bRound = roundDao.create(
        Round(None, 1, Some("B open round"), contestId = b.getId, active = true, juryOrgView = true))
      val both = userDao.create(User("Both", "both@example.com", None, Set("jury"), contestId = a.id))
      UserContestJdbc.createMembership(both.getId, b.getId, "jury")
      val aOnly = userDao.create(User("A only", "aonly@example.com", None, Set("jury"), contestId = a.id))
      (a, b, bRound, both, aOnly)
    }

    def inA(url: String, email: String, a: Long) =
      FakeRequest(GET, url)
        .withSession(Secured.UserName -> email, Secured.CurrentContestId -> a.toString)
        .withCSRFToken

    "reach B's round stat, by B's id and by the round's" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val (a, b, bRound, both, _) = setup()

        val current = call(roundController(app).currentRoundStat(Some(b.getId)),
          inA(s"/roundstat?contestId=${b.getId}", both.email, a.getId))
        status(current) === SEE_OTHER
        redirectLocation(current) === Some(routes.RoundController.roundStat(bRound.getId).url)
        session(current).get(Secured.CurrentContestId) === Some(b.getId.toString)

        val stat = call(roundController(app).roundStat(bRound.getId),
          inA(s"/roundstat/${bRound.getId}", both.email, a.getId))
        status(stat) === OK
        contentAsString(stat) must contain("B open round")
      }
    }

    "still be refused to a juror of A only" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val (a, b, bRound, _, aOnly) = setup()

        val current = call(roundController(app).currentRoundStat(Some(b.getId)),
          inA(s"/roundstat?contestId=${b.getId}", aOnly.email, a.getId))
        // no round of theirs: a state, not an error, as on the juror's start page
        status(current) === OK
        redirectLocation(current) must beNone
        contentAsString(current) must contain("No round configured yet.")
        contentAsString(current) must not(contain("B open round"))

        status(call(roundController(app).roundStat(bRound.getId),
          inA(s"/roundstat/${bRound.getId}", aOnly.email, a.getId))) === FORBIDDEN
      }
    }
  }

  "a round's stat table, signed out" should {
    "be a 401 to a script, and the login page to a page" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val contest = contestDao.create(None, "WLE", 2024, "Ukraine")
        val round = roundDao.create(Round(None, 1, Some("Round"), contestId = contest.getId, active = true))
        val url = s"/roundstat/${round.getId}/table"
        def table(headers: (String, String)*) =
          call(roundController(app).roundStatTable(round.getId), FakeRequest(GET, url).withHeaders(headers: _*))

        val xhr = table("X-Requested-With" -> "XMLHttpRequest")
        status(xhr) === UNAUTHORIZED
        (contentAsJson(xhr) \ "error").as[String] === "unauthenticated"

        val page = table()
        status(page) === SEE_OTHER
        redirectLocation(page) === Some(routes.LoginController.login(Some(url)).url)
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
          .rateByPageId(foreignRound.getId, 1002L, select = 5)
          .apply(sessionRequest(POST, s"/large/round/${foreignRound.getId}/pageid/1002/select/5?rate=0", juror.email))
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
