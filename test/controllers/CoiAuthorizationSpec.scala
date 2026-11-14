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

import scalikejdbc._

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

  /** Contest 10 with a category of 3 files (2 by its board member), a round of them with the
    * legacy `jury_org_view` flag set (ignored), a juror, an organizer and an admin;
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
    val round = roundDao.create(Round(None, 1, Some("R"), contestId, active = true))
    LegacyJuryOrgView.set(round.getId)
    images.foreach(i => VoteJdbc.create(i.pageId, 0, juror.getId, round.getId))
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

  "a member whose only role is board, auditor or staff [S-01]" should {

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
        VoteJdbc.byRound(r).map(_.rate).toSet === Set(0)
      }
    }

    "not be in the contest: no current contest, nothing to switch to" in {
      testDbApp { app =>
        setUp()
        val board = Secured.sessionUser("board@example.com", Some(contestId.toString), None, None).get
        board.contestId must beNone
        Secured.inContest(board, contestId).contestId must beNone
        status(page(app, "board@example.com", s"/switch-contest/$contestId", POST)) === FORBIDDEN
      }
    }
  }

  "the contest files page's conflicts of interest [A-10]" should {

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

    "show the round's own count, and ask before disqualifying them: on a stopped round only" in {
      testDbApp { app =>
        val round = setUp()
        def html = contentAsString(page(app, "admin@example.com", s"/admin/rounds/edit?id=${round.getId}&contestId=$contestId"))
        // open: saving only sets the flag, nothing to confirm
        html must contain("2 of this round")
        html must contain("data-coi-round-files=\"0\"")
        Round.setActive(round.getId, active = false)
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
        VoteJdbc.byRound(round.getId).map(_.pageId).toSet === Set(1L, 2L, 3L)
        roundDao.findById(round.getId).get.excludeCoi must beFalse
      }
    }

    "only set the flag of an open round, saying to stop it first (W7)" in {
      testDbApp { app =>
        val round = setUp()
        val result = save(app, "admin@example.com", saveRound(round, excludeCoi = true))
        status(result) === SEE_OTHER
        flash(result).get("error") must beSome(contain("stop the round"))
        // a later save does nothing: the rounds list links the round's conflicts page
        flash(result).get("coiRound") must beSome(round.getId.toString)
        contentAsString(route(app, FakeRequest(GET, s"/admin/rounds?contestId=$contestId")
          .withSession(Secured.UserName -> "admin@example.com", Secured.CurrentContestId -> contestId.toString)
          .withFlash("error" -> "x", "coiRound" -> round.getId.toString).withCSRFToken).get) must
          contain(s"/admin/rounds/${round.getId}/conflicts")
        VoteJdbc.byRound(round.getId).map(_.pageId).toSet === Set(1L, 2L, 3L)
        RoundImageJdbc.disqualified(round.getId) must beEmpty
        roundDao.findById(round.getId).get.excludeCoi must beTrue
      }
    }

    "disqualify them, keeping their votes, when its contest's admin saves a stopped round ticked, and for root" in {
      testDbApp { app =>
        val round = setUp()
        Round.setActive(round.getId, active = false)
        val result = save(app, "admin@example.com", saveRound(round, excludeCoi = true))
        status(result) === SEE_OTHER
        flash(result).get("success") must beSome(contain("2"))
        VoteJdbc.byRound(round.getId).map(_.pageId).toSet === Set(3L)
        RoundImageJdbc.disqualified(round.getId).map(_.pageId) === Seq(1L, 2L)
        DB.readOnly(implicit s => sql"SELECT page_id FROM vote_aside WHERE round_id = ${round.getId}".map(_.long(1)).list())
          .toSet === Set(1L, 2L)
        roundDao.findById(round.getId).get.excludeCoi must beTrue

        val other = roundDao.create(Round(None, 2, Some("R2"), contestId, active = false))
        VoteJdbc.create(1L, 0, User.findByEmail("juror@example.com").head.getId, other.getId)
        User.create(User("root@example.com", "root@example.com", None, Set(User.ROOT_ROLE), Some("hash")))
        status(save(app, "root@example.com", saveRound(other, excludeCoi = true))) === SEE_OTHER
        VoteJdbc.byRound(other.getId) must beEmpty
        RoundImageJdbc.disqualified(other.getId).map(_.pageId) === Seq(1L)
      }
    }

    "not disqualify again on a later save, so a requalified file stays in (review finding 2)" in {
      testDbApp { app =>
        val round = setUp()
        val r = round.getId
        Round.setActive(r, active = false)
        status(save(app, "admin@example.com", saveRound(round, excludeCoi = true))) === SEE_OTHER
        RoundImageJdbc.disqualified(r).map(_.pageId) === Seq(1L, 2L)
        val admin = User.findByEmail("admin@example.com").head
        RoundConflicts.requalify(r, Seq(1L), RoundConflicts.RequalifyReason.MistakenMatch, admin,
          services.distribution.RoundDistributionHandler.dedupeKey(r)) === RoundConflicts.Outcome.Requalified(Seq(1L))
        // the form, the flag set: no confirm, and a save changes nothing
        contentAsString(page(app, "admin@example.com", s"/admin/rounds/edit?id=$r&contestId=$contestId")) must
          contain("data-coi-round-files=\"0\"")
        val again = save(app, "admin@example.com", saveRound(round, excludeCoi = true))
        status(again) === SEE_OTHER
        flash(again).get("success") must beNone
        RoundImageJdbc.disqualified(r).map(_.pageId) === Seq(2L)
        VoteJdbc.byRound(r).map(_.pageId).toSet === Set(1L, 3L)
      }
    }

    "say the round is being distributed, also when it has no conflicts, and not set the flag" in {
      testDbApp { app =>
        setUp()
        val juror = User.findByEmail("juror@example.com").head
        val clean = roundDao.create(Round(None, 2, Some("R2"), contestId, active = false))
        VoteJdbc.create(3L, 0, juror.getId, clean.getId)
        DB.autoCommit { implicit s =>
          services.jobs.JobJdbc.insert(services.jobs.NewJob(services.distribution.RoundDistributionHandler.Kind,
            Some(contestId), Some(services.distribution.RoundDistributionHandler.SubjectType), clean.id,
            Some(services.distribution.RoundDistributionHandler.dedupeKey(clean.getId))), owner = None)
        }
        val result = save(app, "admin@example.com", saveRound(clean, excludeCoi = true))
        (status(result) === SEE_OTHER) and (flash(result).get("error") must beSome(contain("distributed"))) and
          (roundDao.findById(clean.getId).get.excludeCoi must beFalse)
      }
    }

    "keep the files saved unticked, and stop leaving them out" in {
      testDbApp { app =>
        val round = setUp()
        Round.setExcludeCoi(round.getId, excludeCoi = true)
        val result = save(app, "admin@example.com", saveRound(round, excludeCoi = false))
        status(result) === SEE_OTHER
        flash(result).get("success") must beNone
        VoteJdbc.byRound(round.getId).map(_.pageId).toSet === Set(1L, 2L, 3L)
        roundDao.findById(round.getId).get.excludeCoi must beFalse
      }
    }

    "need the CSRF token" in {
      withCsrfApp { app =>
        val round = setUp()
        Round.setActive(round.getId, active = false)
        val sessionToken = token(app)
        def post(body: (String, String)*) =
          route(app, FakeRequest(POST, "/admin/rounds/save")
            .withHeaders(COOKIE -> "PLAY_SESSION=signed-in", ACCEPT -> "text/html")
            .withSession(Secured.UserName -> "admin@example.com", Secured.CurrentContestId -> contestId.toString,
              "csrfToken" -> sessionToken)
            .withFormUrlEncodedBody(body: _*)).get
        status(post(saveRound(round, excludeCoi = true): _*)) === FORBIDDEN
        VoteJdbc.byRound(round.getId).size === 3
        status(post(saveRound(round, excludeCoi = true) :+ ("csrfToken" -> sessionToken): _*)) === SEE_OTHER
        VoteJdbc.byRound(round.getId).map(_.pageId).toSet === Set(3L)
      }
    }
  }

  "importing users with a role [A-07]" should {

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
        // stored as MediaWiki names them (S2, N4): underscores as spaces
        board.flatMap(_.wikiAccount).toSet === Set("Board one", "Board two")
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
