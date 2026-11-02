package controllers

import controllers.CsrfTestApp.{token, withCsrfApp}
import db.scalikejdbc._
import org.intracer.wmua.Image
import org.specs2.mock.Mockito
import play.api.Application
import play.api.mvc.{ControllerComponents, Result}
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import services.{SMTPOrWikiMail, UserService}

import scala.concurrent.Future

/** Conflicts of interest and access: the board, auditors and staff (roles without access)
  * get nothing, on any page; the files page's conflicts are for the contest's organizers
  * and admins, and taking them out of a round, by its form, for its contest's admins.
  */
class CoiAuthorizationSpec extends PlaySpecification with PlayTestDb with Mockito {

  sequential

  private val contestId = 10L

  private def image(id: Long, author: Option[String] = None) =
    Image(id, s"File:Image$id.jpg", Some(s"url$id"), None, 640, 480, Some(s"12-345-$id"), author = author)

  private def member(email: String, role: String, contest: Long = contestId, account: Option[String] = None): User =
    User.create(User(email, email, None, Set(role), Some(services.PasswordHasher.default.hash("secret")), Some(contest), wikiAccount = account))

  /** Contest 10 with a category of 3 files (2 by its board member), a round of them shown to
    * its jurors' organizer views too (`juryOrgView`), a juror, an organizer and an admin;
    * contest 20 with its own admin.
    */
  private def setUp(): Round = {
    createContests(contestId, 20)
    val images = Seq(image(1, Some("Board member")), image(2, Some("Board member")), image(3))
    imageDao.batchInsert(images)
    CategoryLinkJdbc.addToCategory(contestDao.findById(contestId).get.categoryId.get, images)
    val juror = member("juror@example.com", "jury")
    member("organizer@example.com", "organizer")
    member("admin@example.com", "admin")
    member("other-admin@example.com", "admin", contest = 20)
    User.NON_ACCESS_ROLES.foreach(r =>
      member(s"$r@example.com", r, account = Some(if (r == User.BOARD_ROLE) "Board member" else s"$r account")))
    val round = roundDao.create(Round(None, 1, Some("R"), contestId, active = true, juryOrgView = true))
    images.foreach(i => SelectionJdbc.create(i.pageId, 0, juror.getId, round.getId))
    round
  }

  /** A signed-in browser's page request, in contest 10. */
  private def page(app: Application, email: String, path: String, method: String = GET): Future[Result] =
    route(app, FakeRequest(method, path)
      .withSession(Secured.UserName -> email, Secured.CurrentContestId -> contestId.toString)
      .withCSRFToken).get

  private def pages(round: Round, userId: Long): Seq[String] = {
    val r = round.getId
    Seq(
      "/",
      s"/gallery/round/$r/user/$userId/page/1",
      s"/gallery/round/$r/user/0/page/1",
      s"/byrate/round/$r/user/0/page/1",
      s"/filelist/round/$r/user/0/page/1",
      s"/large/round/$r/user/$userId/pageid/1",
      s"/large/round/$r/user/0/pageid/1",
      "/gallery/page/1",
      s"/roundstat/$r",
      s"/roundstat?contestId=$contestId",
      s"/admin/rounds?contestId=$contestId",
      s"/admin/rounds/edit?contestId=$contestId&id=$r",
      s"/admin/users?contestId=$contestId",
      s"/admin/importusers?contestId=$contestId",
      s"/contest/$contestId/files",
      s"/contest/$contestId/files?coi=true",
      s"/contest/$contestId/files/1",
      s"/contest/$contestId/images",
      "/monuments"
    )
  }

  "a member whose only role is board, auditor or staff" should {

    "be refused every page, as a non-member is" in {
      testDbApp { app =>
        val round = setUp()
        val results = for {
          role <- User.NON_ACCESS_ROLES.toSeq.sorted
          user = User.findByEmail(s"$role@example.com").head
          path <- pages(round, user.getId)
        } yield (role, path, status(page(app, s"$role@example.com", path)))
        results.filter(_._3 != FORBIDDEN) must beEmpty
      }
    }

    "be refused votes and the round's JSON" in {
      testDbApp { app =>
        val round = setUp()
        val r = round.getId
        val staff = "staff@example.com"
        status(page(app, staff, s"/rate/round/$r/pageid/1/select/1", POST)) === FORBIDDEN
        status(page(app, staff, s"/large/round/$r/pageid/1/select/1", POST)) === FORBIDDEN
        status(page(app, staff, s"/regions/round/$r/user/0/region/all")) === FORBIDDEN
        SelectionJdbc.byRound(r).map(_.rate).toSet === Set(0)
      }
    }

    "not be in the contest: no current contest, nothing to switch to" in {
      testDbApp { app =>
        setUp()
        val board = Secured.sessionUser("board@example.com", Some(contestId.toString)).get
        board.contestId must beNone
        Secured.inContest(board, contestId).contestId must beNone
        status(page(app, "board@example.com", s"/switch-contest/$contestId", POST)) === FORBIDDEN
      }
    }
  }

  "the contest files page's conflicts of interest" should {

    "list the files by members in conflict-of-interest roles to organizers and admins, with whose they are" in {
      testDbApp { app =>
        setUp()
        Seq("organizer@example.com", "admin@example.com").forall { email =>
          val result = page(app, email, s"/contest/$contestId/files?coi=true")
          status(result) === OK
          val html = contentAsString(result)
          html must contain("File:Image1.jpg")
          html must contain("File:Image2.jpg")
          html must not(contain("File:Image3.jpg"))
          html must contain("data-coi=\"Board member — board\"")
          true
        } must beTrue
      }
    }

    "be refused to a juror and to another contest's admin" in {
      testDbApp { app =>
        setUp()
        status(page(app, "juror@example.com", s"/contest/$contestId/files?coi=true")) === FORBIDDEN
        status(page(app, "other-admin@example.com", s"/contest/$contestId/files?coi=true")) === FORBIDDEN
      }
    }
  }

  /** The round form of an existing round as the browser posts it, "Leave out conflicts of
    * interest" ticked or not.
    */
  private def saveRound(round: Round, excludeCoi: Boolean): Seq[(String, String)] =
    Seq("id" -> round.getId.toString, "number" -> round.number.toString, "name" -> "R",
      "contest" -> round.contestId.toString, "roles" -> "jury", "distribution" -> "0", "rates" -> "1",
      "minMpx" -> "", "minSize" -> "", "mediaType" -> "all") ++
      User.findRoundJurors(round.getId).zipWithIndex.map { case (u, i) => s"jurors[$i]" -> u.getId.toString } ++
      (if (excludeCoi) Seq("excludeCoi" -> "true") else Nil)

  private def save(app: Application, email: String, body: Seq[(String, String)]): Future[Result] =
    route(app, FakeRequest(POST, "/admin/rounds/save")
      .withSession(Secured.UserName -> email, Secured.CurrentContestId -> contestId.toString)
      .withFormUrlEncodedBody(body: _*)
      .withCSRFToken).get

  "the round form's \"Leave out conflicts of interest\" of an existing round" should {

    "show the round's own count, and ask before taking them out" in {
      testDbApp { app =>
        val round = setUp()
        val html = contentAsString(page(app, "admin@example.com", s"/admin/rounds/edit?id=${round.getId}&contestId=$contestId"))
        html must contain("2 of this round")
        html must contain("data-coi-round-files=\"2\"")
        html must not(contain("remove-conflicts"))
      }
    }

    "be refused to a juror, an organizer, another contest's admin and the board" in {
      testDbApp { app =>
        val round = setUp()
        Seq("juror@example.com", "organizer@example.com", "other-admin@example.com", "board@example.com").map { email =>
          status(save(app, email, saveRound(round, excludeCoi = true)))
        } === Seq(FORBIDDEN, FORBIDDEN, FORBIDDEN, FORBIDDEN)
        SelectionJdbc.byRound(round.getId).map(_.pageId).toSet === Set(1L, 2L, 3L)
        roundDao.findById(round.getId).get.excludeCoi must beFalse
      }
    }

    "take them out when its contest's admin saves it ticked, and for root" in {
      testDbApp { app =>
        val round = setUp()
        val result = save(app, "admin@example.com", saveRound(round, excludeCoi = true))
        status(result) === SEE_OTHER
        flash(result).get("success") must beSome(contain("2"))
        SelectionJdbc.byRound(round.getId).map(_.pageId).toSet === Set(3L)
        roundDao.findById(round.getId).get.excludeCoi must beTrue

        val other = roundDao.create(Round(None, 2, Some("R2"), contestId, active = true))
        SelectionJdbc.create(1L, 0, User.findByEmail("juror@example.com").head.getId, other.getId)
        User.create(User("root@example.com", "root@example.com", None, Set(User.ROOT_ROLE), Some("hash")))
        status(save(app, "root@example.com", saveRound(other, excludeCoi = true))) === SEE_OTHER
        SelectionJdbc.byRound(other.getId) must beEmpty
      }
    }

    "keep the files saved unticked, and stop leaving them out" in {
      testDbApp { app =>
        val round = setUp()
        Round.setExcludeCoi(round.getId, excludeCoi = true)
        val result = save(app, "admin@example.com", saveRound(round, excludeCoi = false))
        status(result) === SEE_OTHER
        flash(result).get("success") must beNone
        SelectionJdbc.byRound(round.getId).map(_.pageId).toSet === Set(1L, 2L, 3L)
        roundDao.findById(round.getId).get.excludeCoi must beFalse
      }
    }

    "need the CSRF token" in {
      withCsrfApp { app =>
        val round = setUp()
        val sessionToken = token(app)
        def post(body: (String, String)*) =
          route(app, FakeRequest(POST, "/admin/rounds/save")
            .withHeaders(COOKIE -> "PLAY_SESSION=signed-in", ACCEPT -> "text/html")
            .withSession(Secured.UserName -> "admin@example.com", Secured.CurrentContestId -> contestId.toString,
              "csrfToken" -> sessionToken)
            .withFormUrlEncodedBody(body: _*)).get
        status(post(saveRound(round, excludeCoi = true): _*)) === FORBIDDEN
        SelectionJdbc.byRound(round.getId).size === 3
        status(post(saveRound(round, excludeCoi = true) :+ ("csrfToken" -> sessionToken): _*)) === SEE_OTHER
        SelectionJdbc.byRound(round.getId).map(_.pageId).toSet === Set(3L)
      }
    }
  }

  "importing users with a role" should {

    "add the board, auditors and staff by wiki account, two without emails, and greet none of them" in {
      testDbApp { implicit app =>
        implicit val mat: org.apache.pekko.stream.Materializer = app.materializer
        createContests(contestId)
        ContestJuryJdbc.updateGreeting(contestId, Greeting(Some("Hello {{RecipientName}}"), use = true))
        val admin = member("admin@example.com", "admin")
        val mail = mock[SMTPOrWikiMail]
        val controller = new UserController(app.injector.instanceOf[ControllerComponents],
          new UserService(mail, app.configuration), app.injector.instanceOf[modules.BlockingExecutionContext])

        val result = call(controller.importUsers(Some(contestId)),
          FakeRequest(POST, s"/admin/importusers?contestId=$contestId")
            .withSession(Secured.UserName -> admin.email, Secured.CurrentContestId -> contestId.toString)
            .withFormUrlEncodedBody("userstoimport" -> "User:Board one\nUser:Board_two", "role" -> "board")
            .withCSRFToken)

        status(result) === SEE_OTHER
        val board = User.findByContestAndRoles(contestId, Seq("board"))
        board.flatMap(_.wikiAccount).toSet === Set("Board one", "Board_two")
        board.map(_.email).toSet === Set("")
        there was no(mail).sendMail(any[User], any[User], any[String], any[String])
      }
    }

    "refuse a role that is not offered" in {
      testDbApp { implicit app =>
        implicit val mat: org.apache.pekko.stream.Materializer = app.materializer
        createContests(contestId)
        val admin = member("admin@example.com", "admin")
        val controller = new UserController(app.injector.instanceOf[ControllerComponents],
          new UserService(mock[SMTPOrWikiMail], app.configuration), app.injector.instanceOf[modules.BlockingExecutionContext])
        val result = call(controller.importUsers(Some(contestId)),
          FakeRequest(POST, s"/admin/importusers?contestId=$contestId")
            .withSession(Secured.UserName -> admin.email, Secured.CurrentContestId -> contestId.toString)
            .withFormUrlEncodedBody("userstoimport" -> "User:Someone", "role" -> "root")
            .withCSRFToken)
        status(result) === BAD_REQUEST
        User.findByAccount("Someone") must beEmpty
      }
    }
  }
}
