package controllers

import db.scalikejdbc._
import org.apache.pekko.stream.Materializer
import org.intracer.wmua._
import play.api.libs.json.Json
import play.api.mvc.RequestHeader
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, Helpers, PlaySpecification}
import scalikejdbc.{DB, scalikejdbcSQLInterpolationImplicitDef}
import services.GalleryService

class LargeImageSpec extends PlaySpecification with PlayTestDb {

  sequential

  private var contest: ContestJury = _
  private var round: Round = _
  private var user: User = _
  private val email = "email@1.com"
  private lazy val galleryService = new GalleryService
  private def controller = new LargeViewController(Helpers.stubControllerComponents(), galleryService)

  private def contestImage(id: Long): Image =
    Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(s"12-345-$id"))

  private def setUp(rates: Rates = Round.binaryRound): Unit = {
    contest = contestDao.create(None, "WLE", 2015, "Ukraine")
    round = roundDao.create(
      Round(None, 1, contestId = contest.getId, rates = rates, active = true)
    )
    user = userDao.create(
      User("fullname", email, None, Set("jury"), contestId = contest.id)
    )
    round.addUsers(Seq(RoundUser(round.getId, user.getId, "jury", active = true)))
  }

  private def createImages(number: Long, startId: Long = 0): Seq[Image] = {
    val images = (startId until number + startId).map(contestImage)
    imageDao.batchInsert(images)
    images
  }

  private def createSelection(images: Seq[Image],
                      rate: Int = 0,
                      user: User = user,
                      round: Round = round) = {
    val selections = images.zipWithIndex.map { case (image, i) =>
      Selection(image, user, round, rate)
    }
    selectionDao.batchInsert(selections)
    selections
  }

  private def request(url: String): RequestHeader = {
    FakeRequest(GET, url)
      .withSession(Secured.UserName -> email, Secured.CurrentContestId -> contest.getId.toString)
      .withHeaders("Accept" -> "application/json")
      .withCSRFToken
  }

  /** A vote as the large view's vote form posts it. */
  private def voteRequest(url: String): RequestHeader =
    FakeRequest(POST, url)
      .withSession(Secured.UserName -> email, Secured.CurrentContestId -> contest.getId.toString)
      .withCSRFToken

  private def imageJson(id: Int, rate: Int = 0) = {
    s"""{"image":{"pageId":$id,"title":"File:Image$id.jpg","width":640,"height":480,"monumentId":"12-345-$id"},
       |"selection":[{"pageId":$id,"juryId":1,"roundId":1,"rate":$rate,"id":${id + 1},"monumentId":"12-345-$id"}],
       |"countFromDb":0}""".stripMargin
  }

  private def mkJson(elems: String*) = Json.parse(elems.mkString("[", ",", "]"))

  "get 1 unrated image" in {
    testDbApp { app =>
      implicit val materializer: Materializer = app.materializer

      setUp(rates = Round.ratesById(5))

      val images = createImages(1)
      createSelection(images)

      val result = controller.large(user.id.get, images.head.pageId, roundId = round.id.get)
        .apply(request(s"/large/round/${round.id.get}/user/${user.id.get}/pageid/${images.head.pageId}?rate=0"))

      status(result) mustEqual OK
      contentAsJson(result) mustEqual mkJson(imageJson(0))
    }
  }

  "get 1 rated image" in {
    testDbApp { app =>
      implicit val materializer: Materializer = app.materializer

      setUp(rates = Round.ratesById(5))

      val images = createImages(1)
      createSelection(images, rate = 5)

      val result = controller.large(user.id.get, images.head.pageId, roundId = round.id.get)
        .apply(request(s"/large/round/${round.id.get}/user/${user.id.get}/pageid/${images.head.pageId}?module=byrate"))

      status(result) mustEqual OK
      contentAsJson(result) mustEqual mkJson(imageJson(0, rate = 5))
    }
  }

  "get 2 unrated images" in {
    testDbApp { app =>
      implicit val materializer: Materializer = app.materializer

      setUp(rates = Round.ratesById(5))

      val images = createImages(2)
      createSelection(images)

      val result = controller.large(user.id.get, images.head.pageId, roundId = round.id.get)
        .apply(request(s"/large/round/${round.id.get}/user/${user.id.get}/pageid/${images.head.pageId}?rate=0"))
        .run()

      status(result) mustEqual OK
      contentAsJson(result) mustEqual mkJson(imageJson(0), imageJson(1))
    }
  }

  "get 2 rated images" in {
    testDbApp { app =>
      implicit val materializer: Materializer = app.materializer

      setUp(rates = Round.ratesById(5))

      val images = createImages(2)
      createSelection(images.init, 3)
      createSelection(images.tail, 5)

      val result = controller.large(user.id.get, images.last.pageId, roundId = round.id.get)
        .apply(request(s"/large/round/${round.id.get}/user/${user.id.get}/pageid/${images.last.pageId}?module=byrate"))
        .run()

      status(result) mustEqual OK
      contentAsJson(result) mustEqual mkJson(imageJson(1, rate = 5), imageJson(0, rate = 3))
    }
  }

  "get first unrated image from 2" in {
    testDbApp { app =>
      implicit val materializer: Materializer = app.materializer

      setUp(rates = Round.ratesById(5))

      val images = createImages(2)
      createSelection(images.init, 0)
      createSelection(images.tail, 5)

      val result = controller.large(user.id.get, images.head.pageId, roundId = round.id.get)
        .apply(request(s"/large/round/${round.id.get}/user/${user.id.get}/pageid/${images.head.pageId}?rate=0"))
        .run()

      status(result) mustEqual OK
      contentAsJson(result) mustEqual mkJson(imageJson(0))
    }
  }

  "get last unrated image from 2" in {
    testDbApp { app =>
      implicit val materializer: Materializer = app.materializer

      setUp(rates = Round.ratesById(5))

      val images = createImages(2)
      createSelection(images.init, 5)
      createSelection(images.tail, 0)

      val result = controller.large(user.id.get, images.last.pageId, roundId = round.id.get)
        .apply(request(s"/large/round/${round.id.get}/user/${user.id.get}/pageid/${images.last.pageId}?rate=0"))
        .run()

      status(result) mustEqual OK
      contentAsJson(result) mustEqual mkJson(imageJson(1))
    }
  }

  "get first rated image from 2" in {
    testDbApp { app =>
      implicit val materializer: Materializer = app.materializer

      setUp(rates = Round.ratesById(5))

      val images = createImages(2)
      createSelection(images.init, 5)
      createSelection(images.tail, 0)

      val result = controller.large(user.id.get, images.head.pageId, roundId = round.id.get)
        .apply(request(s"/large/round/${round.id.get}/user/${user.id.get}/pageid/${images.head.pageId}?module=byrate"))
        .run()

      status(result) mustEqual OK
      contentAsJson(result) mustEqual mkJson(imageJson(0, rate = 5), imageJson(1, rate = 0))
    }
  }

  "get last rated image from 2" in {
    testDbApp { app =>
      implicit val materializer: Materializer = app.materializer

      setUp(rates = Round.ratesById(5))

      val images = createImages(2)
      createSelection(images.init, 0)
      createSelection(images.tail, 5)

      val result = controller.large(user.id.get, images.last.pageId, roundId = round.id.get)
        .apply(request(s"/large/round/${round.id.get}/user/${user.id.get}/pageid/${images.last.pageId}?module=byrate"))
        .run()

      status(result) mustEqual OK
      contentAsJson(result) mustEqual mkJson(imageJson(1, rate = 5), imageJson(0, rate = 0))
    }
  }

  "rate 1 unrated image" in {
    testDbApp { app =>
      implicit val materializer: Materializer = app.materializer

      setUp(rates = Round.ratesById(5))

      val images = createImages(1)
      createSelection(images)

      val result = controller.rateByPageId(round.id.get, images.head.pageId, select = 5)
        .apply(voteRequest(s"/large/round/${round.id.get}/pageid/${images.head.pageId}/select/5?rate=0"))
        .run()

      status(result) mustEqual SEE_OTHER
      redirectLocation(result) must beSome.which(_ === s"/gallery/round/${round.id.get}/user/${user.id.get}/page/1?filter=unrated&sort=rate")
    }
  }

  "unrate 1 rated image" in {
    testDbApp { app =>
      implicit val materializer: Materializer = app.materializer

      setUp(rates = Round.ratesById(5))

      val images = createImages(1)
      createSelection(images, rate = 5)

      val result = controller.rateByPageId(round.id.get, images.head.pageId, select = 0)
        .apply(voteRequest(s"/large/round/${round.id.get}/pageid/${images.head.pageId}/select/0"))
        .run()

      status(result) mustEqual SEE_OTHER
      redirectLocation(result) must beSome.which(_ === s"/gallery/round/${round.id.get}/user/${user.id.get}/page/1?sort=rate")
    }
  }

  "rerate 1 rated image" in {
    testDbApp { app =>
      implicit val materializer: Materializer = app.materializer

      setUp(rates = Round.ratesById(5))

      val images = createImages(1)
      createSelection(images, rate = 5)

      val result = controller.rateByPageId(round.id.get, images.head.pageId, select = 3)
        .apply(voteRequest(s"/large/round/${round.id.get}/pageid/${images.head.pageId}/select/0?module=byrate"))
        .run()

      status(result) mustEqual SEE_OTHER
      redirectLocation(result) must beSome.which(_ === s"/byrate/round/${round.id.get}/user/${user.id.get}/page/1?sort=rate")
    }
  }

  "rerate 1st rated image from two rated" in {
    testDbApp { app =>
      implicit val materializer: Materializer = app.materializer

      setUp(rates = Round.ratesById(5))

      val images = createImages(2)
      createSelection(images.init, rate = 5)
      createSelection(images.tail, rate = 3)

      val result = controller.rateByPageId(round.id.get, images.head.pageId, select = 2)
        .apply(voteRequest(s"/large/round/${round.id.get}/pageid/${images.head.pageId}/select/0?module=byrate"))
        .run()

      status(result) mustEqual SEE_OTHER
      redirectLocation(result) must beSome.which(_ === s"/large/round/${round.id.get}/user/${user.id.get}/pageid/${images.last.pageId}?module=byrate&sort=rate")
    }
  }

  "rerate 2nd rated image from two rated" in {
    testDbApp { app =>
      implicit val materializer: Materializer = app.materializer

      setUp(rates = Round.ratesById(5))

      val images = createImages(2)
      createSelection(images.init, rate = 3)
      createSelection(images.tail, rate = 2)

      val result = controller.rateByPageId(round.id.get, images.last.pageId, select = 4)
        .apply(voteRequest(s"/large/round/${round.id.get}/pageid/${images.last.pageId}/select/0?module=byrate"))
        .run()

      status(result) mustEqual SEE_OTHER
      redirectLocation(result) must beSome.which(_ === s"/large/round/${round.id.get}/user/${user.id.get}/pageid/${images.head.pageId}?module=byrate&sort=rate")
    }
  }

  "rate 1st unrated image from two" in {
    testDbApp { app =>
      implicit val materializer: Materializer = app.materializer

      setUp(rates = Round.ratesById(5))

      val images = createImages(2)
      createSelection(images.init, 0)
      createSelection(images.tail, 5)

      val result = controller.rateByPageId(round.id.get, images.head.pageId, select = 5)
        .apply(voteRequest(s"/large/round/${round.id.get}/pageid/${images.head.pageId}/select/5?rate=0"))
        .run()

      status(result) mustEqual SEE_OTHER
      redirectLocation(result) must beSome.which(_ === s"/gallery/round/${round.id.get}/user/${user.id.get}/page/1?filter=unrated&sort=rate")
    }
  }

  "rate 2nd unrated image from two" in {
    testDbApp { app =>
      implicit val materializer: Materializer = app.materializer

      setUp(rates = Round.ratesById(5))

      val images = createImages(2)
      createSelection(images.init, 5)
      createSelection(images.tail, 0)

      val result = controller.rateByPageId(round.id.get, images.last.pageId, select = 5)
        .apply(voteRequest(s"/large/round/${round.id.get}/pageid/${images.last.pageId}/select/5?rate=0"))
        .run()

      status(result) mustEqual SEE_OTHER
      redirectLocation(result) must beSome.which(_ === s"/gallery/round/${round.id.get}/user/${user.id.get}/page/1?filter=unrated&sort=rate")
    }
  }

  "rate 1st unrated image from two unrated" in {
    testDbApp { app =>
      implicit val materializer: Materializer = app.materializer

      setUp(rates = Round.ratesById(5))

      val images = createImages(2)
      createSelection(images)

      val result = controller.rateByPageId(round.id.get, images.head.pageId, select = 5)
        .apply(voteRequest(s"/large/round/${round.id.get}/pageid/${images.head.pageId}/select/5?rate=0"))
        .run()

      status(result) mustEqual SEE_OTHER
      redirectLocation(result) must beSome.which(_ === s"/large/round/${round.id.get}/user/${user.id.get}/pageid/${images.last.pageId}?filter=unrated&sort=rate")
    }
  }

  "rate 2nd unrated image from two unrated" in {
    testDbApp { app =>
      implicit val materializer: Materializer = app.materializer

      setUp(rates = Round.ratesById(5))

      val images = createImages(2)
      createSelection(images)

      val result = controller.rateByPageId(round.id.get, images.last.pageId, select = 5)
        .apply(voteRequest(s"/large/round/${round.id.get}/pageid/${images.last.pageId}/select/5?rate=0"))
        .run()

      status(result) mustEqual SEE_OTHER
      redirectLocation(result) must beSome.which(_ === s"/large/round/${round.id.get}/user/${user.id.get}/pageid/${images.head.pageId}?filter=unrated&sort=rate")
    }
  }

  "rate unrated image from the middle of many" in {
    testDbApp { app =>
      implicit val materializer: Materializer = app.materializer

      setUp(rates = Round.ratesById(5))

      val images = createImages(15)
      createSelection(images)

      val pageId = images(7).pageId
      val nextPageId = images(8).pageId

      val result = controller.rateByPageId(round.id.get, pageId, select = 5)
        .apply(voteRequest(s"/large/round/${round.id.get}/pageid/$pageId/select/5?rate=0"))
        .run()

      status(result) mustEqual SEE_OTHER
      redirectLocation(result) must beSome.which(_ === s"/large/round/${round.id.get}/user/${user.id.get}/pageid/$nextPageId?filter=unrated&sort=rate")
    }
  }

  "a round with criteria" should {
    // a rated round with criteria, the juror's current one; criteria 1 and 2 of the juror's
    // selection of file 0 rated 3 and 4. File 1 is only another juror's.
    def setUpCriteria(): (Image, Image, User) = {
      setUp(rates = Round.ratesById(10))
      DB.autoCommit { implicit s => sql"UPDATE rounds SET has_criteria = 1 WHERE id = ${round.getId}".update() }
      round = roundDao.findById(round.getId).get
      val Seq(mine, others) = createImages(2)
      createSelection(Seq(mine))
      val other = userDao.create(User("Other", "other@example.com", None, Set("jury"), contestId = contest.id))
      round.addUsers(Seq(RoundUser(round.getId, other.getId, "jury", active = true)))
      createSelection(Seq(others), user = other)
      val selection = SelectionJdbc.findBy(mine.pageId, user.getId, round.getId).get
      val othersSelection = SelectionJdbc.findBy(others.pageId, other.getId, round.getId).get
      DB.autoCommit { implicit s =>
        sql"""INSERT INTO criteria_rate (id, selection, criteria, rate) VALUES
              (1, ${selection.getId}, 1, 3), (2, ${selection.getId}, 2, 4), (3, ${othersSelection.getId}, 1, 5)"""
          .update()
      }
      (mine, others, other)
    }
    def criteriaRates(pageId: Long, juror: User): Map[Long, Int] =
      SelectionJdbc.findBy(pageId, juror.getId, round.getId).toSeq
        .flatMap(s => CriteriaRate.getRates(s.getId)).map(c => c.criteria -> c.rate).toMap
    def viewRequest(roundId: Long, pageId: Long) =
      FakeRequest(GET, s"/large/round/$roundId/user/${user.getId}/pageid/$pageId")
        .withSession(Secured.UserName -> email, Secured.CurrentContestId -> contest.getId.toString)
        .withHeaders("Accept" -> "text/html")
        .withCSRFToken

    "show the juror's criteria rates in the current round's view (round 0), which failed before" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val (mine, _, _) = setUpCriteria()
        val appController = new LargeViewController(app.injector.instanceOf[play.api.mvc.ControllerComponents], galleryService)

        forall(Seq(0L, round.getId)) { roundId =>
          val result = call(appController.large(user.getId, mine.pageId, "all", roundId),
            viewRequest(roundId, mine.pageId))
          status(result) === OK
          val page = contentAsString(result)
          page must contain("Науковість")
          page must contain("Підпис")
        }
      }
    }

    "show no criteria rates for a selection without them" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val (mine, _, _) = setUpCriteria()
        DB.autoCommit { implicit s => sql"DELETE FROM criteria_rate".update() }
        val appController = new LargeViewController(app.injector.instanceOf[play.api.mvc.ControllerComponents], galleryService)

        val result = call(appController.large(user.getId, mine.pageId, "all", 0L),
          viewRequest(0L, mine.pageId))
        status(result) === OK
        contentAsString(result) must not(contain("Науковість"))
      }
    }

    "rate a criterion of the juror's selection" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val (mine, _, _) = setUpCriteria()

        val result = controller.rateByPageId(round.getId, mine.pageId, select = 7, criteria = Some(2))
          .apply(voteRequest(s"/large/round/${round.getId}/pageid/${mine.pageId}/select/7?criteria=2"))
          .run()

        status(result) === SEE_OTHER
        criteriaRates(mine.pageId, user) === Map(1L -> 3, 2L -> 7)
      }
    }

    "change nothing for a file the juror has no selection for, which failed before" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val (_, others, other) = setUpCriteria()

        val result = controller.rateByPageId(round.getId, others.pageId, select = 1, criteria = Some(1))
          .apply(voteRequest(s"/large/round/${round.getId}/pageid/${others.pageId}/select/1?criteria=1"))
          .run()

        status(result) === SEE_OTHER
        criteriaRates(others.pageId, other) === Map(1L -> 5)
        SelectionJdbc.findBy(others.pageId, user.getId, round.getId) must beNone
      }
    }

    "refuse a vote in round 0, as the gallery's votes, changing nothing" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val (mine, _, _) = setUpCriteria()

        val result = controller.rateByPageId(0L, mine.pageId, select = 7, criteria = Some(1))
          .apply(voteRequest(s"/large/round/0/pageid/${mine.pageId}/select/7?criteria=1"))
          .run()

        status(result) === FORBIDDEN
        criteriaRates(mine.pageId, user) === Map(1L -> 3, 2L -> 4)
      }
    }
  }

  "the large view of a round" should {
    // contests A and B, each with a rated round; two jurors of A and one of B, each with a
    // vote on two files in their round; A's organizer and admin, a root, and a juror of B
    // whose membership of A is deactivated, still listed in A's round
    def setUpContests() = {
      val a = contestDao.create(None, "WLE", 2024, "Ukraine")
      val b = contestDao.create(None, "WLM", 2024, "Poland")
      val roundA = roundDao.create(Round(None, 1, Some("A round"), contestId = a.getId, rates = Round.ratesById(5), active = true))
      val roundB = roundDao.create(Round(None, 1, Some("B round"), contestId = b.getId, rates = Round.ratesById(5), active = true))
      val juror1 = userDao.create(User("Juror 1", "juror1@example.com", None, Set("jury"), contestId = a.id))
      val juror2 = userDao.create(User("Juror 2", "juror2@example.com", None, Set("jury"), contestId = a.id))
      val jurorB = userDao.create(User("Juror B", "jurorb@example.com", None, Set("jury"), contestId = b.id))
      val former = userDao.create(User("Former", "former@example.com", None, Set("jury"), contestId = b.id))
      UserContestJdbc.createMembership(former.getId, a.getId, User.NO_ROLE)
      val organizer = userDao.create(User("Org", "org@example.com", None, Set("organizer"), contestId = a.id))
      val admin = userDao.create(User("Admin", "admin@example.com", None, Set("admin"), contestId = a.id))
      val root = User.create(User("Root", "root@example.com", roles = Set(User.ROOT_ROLE), password = Some("hash")))
      val images = createImages(2)
      Seq(roundA -> Seq(juror1, juror2, former), roundB -> Seq(jurorB)).foreach { case (r, jurors) =>
        r.addUsers(jurors.map(j => RoundUser(r.getId, j.getId, "jury", active = true)))
        jurors.foreach(j => createSelection(images, rate = 3, user = j, round = r))
      }
      (roundA, roundB, juror1, juror2, jurorB, former, organizer, admin, root, images)
    }

    def large(app: play.api.Application, email: String, roundId: Long, asUserId: Long, pageId: Long, json: Boolean = false) = {
      implicit val materializer: Materializer = app.materializer
      val req = FakeRequest(GET, s"/large/round/$roundId/user/$asUserId/pageid/$pageId")
        .withSession(Secured.UserName -> email)
        .withHeaders("Accept" -> (if (json) "application/json" else "text/html"))
        .withCSRFToken
      call(appController(app).large(asUserId, pageId, "all", roundId), req)
    }

    // the app's components, for the pages' messages
    def appController(app: play.api.Application) =
      new LargeViewController(app.injector.instanceOf[play.api.mvc.ControllerComponents], galleryService)

    "be refused to a juror of another contest, as a page and as JSON" in {
      testDbApp { app =>
        val (_, roundB, juror1, _, jurorB, _, _, _, _, images) = setUpContests()

        Seq(jurorB.getId, juror1.getId).foreach { asUserId =>
          val page = large(app, juror1.email, roundB.getId, asUserId, images.head.pageId)
          status(page) === FORBIDDEN
          contentAsString(page) must not(contain("File:Image0.jpg"))

          val json = large(app, juror1.email, roundB.getId, asUserId, images.head.pageId, json = true)
          status(json) === FORBIDDEN
          (contentAsJson(json) \ "error").as[String] === "forbidden"
        }
        ok
      }
    }

    "be refused to a juror whose membership of the round's contest is deactivated" in {
      testDbApp { app =>
        val (roundA, _, _, _, _, former, _, _, _, images) = setUpContests()

        status(large(app, former.email, roundA.getId, former.getId, images.head.pageId)) === FORBIDDEN
        status(large(app, former.email, roundA.getId, former.getId, images.head.pageId, json = true)) === FORBIDDEN
      }
    }

    "be open to the round's juror, its contest's organizer and admin, and root" in {
      testDbApp { app =>
        val (roundA, _, juror1, _, _, _, organizer, admin, root, images) = setUpContests()

        Seq(juror1, organizer, admin, root).foreach { viewer =>
          val page = large(app, viewer.email, roundA.getId, juror1.getId, images.head.pageId)
          status(page) === OK
          contentAsString(page) must contain("File:Image0.jpg")
          status(large(app, viewer.email, roundA.getId, juror1.getId, images.head.pageId, json = true)) === OK
        }
        ok
      }
    }

    "refuse another juror of the round that juror's votes, as the round's gallery does" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val (roundA, _, juror1, juror2, _, _, _, _, _, images) = setUpContests()
        val gallery = new GalleryController(galleryService, Helpers.stubControllerComponents(),
          new modules.BlockingExecutionContext(app.actorSystem))

        val galleryStatus = status(call(gallery.list(juror1.getId, 1, "all", roundA.getId),
          FakeRequest(GET, s"/gallery/round/${roundA.getId}/user/${juror1.getId}/page/1")
            .withSession(Secured.UserName -> juror2.email).withCSRFToken))
        val largeStatus = status(large(app, juror2.email, roundA.getId, juror1.getId, images.head.pageId))

        galleryStatus === FORBIDDEN
        largeStatus === FORBIDDEN
        status(large(app, juror2.email, roundA.getId, juror1.getId, images.head.pageId, json = true)) === FORBIDDEN
      }
    }

    "be a page saying so for a missing round, not a server error" in {
      testDbApp { app =>
        val (_, _, juror1, _, _, _, _, _, _, images) = setUpContests()

        val missing = large(app, juror1.email, 999999L, juror1.getId, images.head.pageId)
        status(missing) === NOT_FOUND
        contentAsString(missing) must contain("There is no such round.")
        status(large(app, juror1.email, 999999L, juror1.getId, images.head.pageId, json = true)) === NOT_FOUND
      }
    }

    "be a page saying so without an active round, not a server error" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val contestC = contestDao.create(None, "WLE", 2024, "Ukraine")
        val idle = userDao.create(User("Idle", "idle@example.com", None, Set("jury"), contestId = contestC.id))
        val req = FakeRequest(GET, s"/large/user/${idle.getId}/pageid/1").withSession(Secured.UserName -> idle.email)

        val result = call(appController(app).large(idle.getId, 1L, "all", 0L), req)
        status(result) === OK
        contentAsString(result) must contain("No round configured yet.")
        status(call(appController(app).largeCurrentUser(1L, "all"),
          FakeRequest(GET, "/large/pageid/1").withSession(Secured.UserName -> idle.email))) === OK
      }
    }
  }

  private def withCsrfApp[T](body: play.api.Application => T): T = CsrfTestApp.withCsrfApp(body)

  "a vote in the large view, through the router and the filters" should {
    // a rated round with two unrated files; the session has a CSRF token, as a signed-in
    // browser's has, and sends a cookie, which makes the filter check the request
    var images: Seq[Image] = Nil
    var token = ""
    def setUpVote(app: play.api.Application): Unit = {
      setUp(rates = Round.ratesById(5))
      images = createImages(2)
      createSelection(images)
      token = app.injector.instanceOf[play.filters.csrf.CSRF.TokenProvider].generateToken
    }
    def send(app: play.api.Application, method: String, body: (String, String)*) =
      route(app, FakeRequest(method, s"/large/round/${round.getId}/pageid/${images.head.pageId}/select/5?rate=0")
        .withHeaders(COOKIE -> "PLAY_SESSION=signed-in")
        .withSession(Secured.UserName -> email, Secured.CurrentContestId -> contest.getId.toString,
          "csrfToken" -> token)
        .withFormUrlEncodedBody(body: _*)).get
    def rate() = SelectionJdbc.findBy(images.head.pageId, user.getId, round.getId).map(_.rate)

    "not be cast by a GET" in {
      withCsrfApp { app =>
        setUpVote(app)

        status(send(app, GET)) must beOneOf(NOT_FOUND, METHOD_NOT_ALLOWED)
        rate() === Some(0)
      }
    }

    "be refused without the CSRF token" in {
      withCsrfApp { app =>
        setUpVote(app)

        status(send(app, POST)) === FORBIDDEN
        status(send(app, POST, "csrfToken" -> "forged")) === FORBIDDEN
        rate() === Some(0)
      }
    }

    "be refused without the token with a page that says to reload it, and to sign in if signed out" in {
      withCsrfApp { app =>
        setUpVote(app)

        val refused = send(app, POST)
        status(refused) === FORBIDDEN
        val page = contentAsString(refused)
        page must contain("This page has expired or you were signed out. Go back, reload the page and try again.")
        page must beMatching("(?s).*Reference: [0-9a-f]{8}, .*")
        page must not(contain(routes.LoginController.login().url + "\""))

        // signed out: the session (and its token) is gone, the form is the old page's
        val signedOut = route(app, FakeRequest(POST, s"/large/round/${round.getId}/pageid/${images.head.pageId}/select/5?rate=0")
          .withHeaders(COOKIE -> "PLAY_SESSION=expired")
          .withFormUrlEncodedBody("csrfToken" -> token)).get
        status(signedOut) === FORBIDDEN
        contentAsString(signedOut) must contain("This page has expired or you were signed out.")
        contentAsString(signedOut) must contain("href=\"" + routes.LoginController.login().url + "\"")
        rate() === Some(0)
      }
    }

    "be cast by a POST with the token, going on to the next file" in {
      withCsrfApp { app =>
        setUpVote(app)

        val result = send(app, POST, "csrfToken" -> token)
        status(result) === SEE_OTHER
        redirectLocation(result) === Some(s"/large/round/${round.getId}/user/${user.getId}/pageid/${images.last.pageId}?filter=unrated&sort=rate")
        rate() === Some(5)
      }
    }
  }

  "a vote on a gallery tile, through the router and the filters" should {
    // a binary round with an unrated file; a session with a CSRF token, as a signed-in
    // browser's has, and a cookie, which makes the filter check the request
    var image: Image = null
    def setUpTile(): Unit = {
      setUp()
      image = createImages(1).head
      createSelection(Seq(image))
    }
    def vote(app: play.api.Application, sessionToken: String, headers: (String, String)*) =
      route(app, FakeRequest(POST, s"/rate/round/${round.getId}/pageid/${image.pageId}/select/1")
        .withHeaders(Seq(COOKIE -> "PLAY_SESSION=signed-in", "X-Requested-With" -> "XMLHttpRequest") ++ headers: _*)
        .withSession(Secured.UserName -> email, Secured.CurrentContestId -> contest.getId.toString,
          "csrfToken" -> sessionToken)).get
    def rate() = SelectionJdbc.findBy(image.pageId, user.getId, round.getId).map(_.rate)

    "be refused without the CSRF token, or with a forged one" in {
      withCsrfApp { app =>
        setUpTile()
        val token = app.injector.instanceOf[play.filters.csrf.CSRF.TokenProvider].generateToken

        status(vote(app, token)) === FORBIDDEN
        status(vote(app, token, "Csrf-Token" -> "forged")) === FORBIDDEN
        rate() === Some(0)
      }
    }

    "be refused without the token as JSON the gallery explains (vote-errors.js)" in {
      withCsrfApp { app =>
        setUpTile()
        val refused = vote(app, CsrfTestApp.token(app))
        status(refused) === FORBIDDEN
        (contentAsJson(refused) \ "error").as[String] === "csrf"
        (contentAsJson(refused) \ "reference").as[String] must beMatching("[0-9a-f]{8}")
        rate() === Some(0)
      }
    }

    "be answered as JSON when the round is closed, or the juror signed out" in {
      withCsrfApp { app =>
        setUpTile()
        val sessionToken = CsrfTestApp.token(app)
        roundDao.updateById(round.getId).withAttributes("active" -> false)
        val closed = vote(app, sessionToken, "Csrf-Token" -> sessionToken)
        status(closed) === CONFLICT
        (contentAsJson(closed) \ "error").as[String] === "round_closed"

        // the session has its token, but no user: signed out elsewhere
        val signedOut = route(app, FakeRequest(POST, s"/rate/round/${round.getId}/pageid/${image.pageId}/select/1")
          .withHeaders(COOKIE -> "PLAY_SESSION=signed-in", "X-Requested-With" -> "XMLHttpRequest",
            "Csrf-Token" -> sessionToken)
          .withSession("csrfToken" -> sessionToken)).get
        status(signedOut) === UNAUTHORIZED
        (contentAsJson(signedOut) \ "error").as[String] === "unauthenticated"
        rate() === Some(0)
      }
    }

    "be explained to the juror in the page's language: the gallery renders the texts" in {
      withCsrfApp { app =>
        setUpTile()
        val path = s"/gallery/round/${round.getId}/user/${user.getId}/page/1?rate=0"
        val page = route(app, FakeRequest(GET, path)
          .withHeaders(COOKIE -> "PLAY_SESSION=signed-in", ACCEPT -> "text/html", ACCEPT_LANGUAGE -> "fr")
          .withSession(Secured.UserName -> email, Secured.CurrentContestId -> contest.getId.toString)).get
        status(page) === OK
        val html = contentAsString(page)
        html must contain("data-vote-expired=\"Cette page a expiré. Rechargez-la et votez à nouveau.\"")
        html must contain("data-vote-closed=\"Ce tour n&#x27;est plus ouvert au vote.\"")
        html must contain("data-vote-reference=\"Référence : {0}\"")
        html must contain("data-vote-login-url=\"" + routes.LoginController.login(Some(path)).url + "\"")
        html must contain("javascripts/vote-errors.js")
      }
    }

    "be cast with the token the gallery page renders, in the Csrf-Token header" in {
      withCsrfApp { app =>
        setUpTile()
        // the juror's gallery, as the browser opens it: the filter gives the session a token
        val page = route(app, FakeRequest(GET, s"/gallery/round/${round.getId}/user/${user.getId}/page/1?rate=0")
          .withHeaders(COOKIE -> "PLAY_SESSION=signed-in", ACCEPT -> "text/html")
          .withSession(Secured.UserName -> email, Secured.CurrentContestId -> contest.getId.toString)).get
        status(page) === OK
        val rendered = """data-csrf-token="([^"]+)"""".r.findFirstMatchIn(contentAsString(page)).map(_.group(1))
        rendered must beSome
        val sessionToken = session(page).get("csrfToken")
        sessionToken must beSome

        val result = vote(app, sessionToken.get, "Csrf-Token" -> rendered.get)
        status(result) === OK
        contentAsString(result) === "success"
        rate() === Some(1)
      }
    }
  }

  "going back from the large view to the gallery" should {

    def gallery(app: play.api.Application) =
      new GalleryController(galleryService, Helpers.stubControllerComponents(),
        new modules.BlockingExecutionContext(app.actorSystem))

    def back(app: play.api.Application, pageId: Long, query: String = "") = {
      implicit val materializer: Materializer = app.materializer
      val path = s"/gallery/round/${round.getId}/user/${user.getId}/region/all/pageid/$pageId$query"
      call(gallery(app).listAtId(user.getId, pageId, "all", round.getId),
        FakeRequest(GET, path).withSession(Secured.UserName -> email, Secured.CurrentContestId -> contest.getId.toString))
    }

    "open the page with the file: the 15th file's is page 1, the 16th's page 2, which failed before" in {
      testDbApp { app =>
        setUp()
        createSelection(createImages(20))
        val order = RoundList(round, user.getId).query.list().map(_.pageId)

        redirectLocation(back(app, order(14))) === Some(s"/gallery/round/${round.getId}/user/${user.getId}/page/1?sort=rate#${order(14)}")
        redirectLocation(back(app, order(15))) === Some(s"/gallery/round/${round.getId}/user/${user.getId}/page/2?sort=rate#${order(15)}")
      }
    }

    "keep the list's filter, and open page 1 for a file not in the round" in {
      testDbApp { app =>
        setUp()
        createSelection(createImages(20))
        val order = RoundList(round, user.getId).query.list().map(_.pageId)

        redirectLocation(back(app, order(16), "?rate=0")) ===
          Some(s"/gallery/round/${round.getId}/user/${user.getId}/page/2?filter=unrated&sort=rate#${order(16)}")
        redirectLocation(back(app, 99999L)) === Some(s"/gallery/round/${round.getId}/user/${user.getId}/page/1?sort=rate")
      }
    }
  }

  "the large view of a file just voted out of its list" should {
    "show that file, not the list's first, which it went to before" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        setUp()
        val images = createImages(5)
        createSelection(images)
        val order = RoundList(round, user.getId).query.list().map(_.pageId)
        SelectionJdbc.rate(order(3), user.getId, round.getId, -1)

        val result = controller.large(user.getId, order(3), "all", round.getId)
          .apply(request(s"/large/round/${round.getId}/user/${user.getId}/pageid/${order(3)}?rate=0"))
          .run()
        status(result) mustEqual OK
        // after the unrated files, by rate: the file and the two before it
        contentAsJson(result).as[Seq[play.api.libs.json.JsValue]].map(f => (f \ "image" \ "pageId").as[Long]) ===
          Seq(order(2), order(4), order(3))
      }
    }
  }
}
