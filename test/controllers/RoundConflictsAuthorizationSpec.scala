package controllers

import controllers.CsrfTestApp.{token, withCsrfApp}
import db.scalikejdbc._
import org.intracer.wmua.Image
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._

import scala.concurrent.Future

/** A round's conflicts page and its forms (P1b, [[RoundConflictsController]]): the page for
  * its contest's admins, organizers (read only) and root; disqualify and requalify for its
  * contest's admins and root, checked in the round's contest, POSTs with the CSRF token,
  * refused while the round is open. Nothing is written when refused.
  */
class RoundConflictsAuthorizationSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val contestId = 10L

  private def member(email: String, role: String, contest: Long = contestId, account: Option[String] = None): User =
    User.create(User(email, email, None, Set(role), Some(services.PasswordHasher.default.hash("secret")), Some(contest),
      wikiAccount = account))

  /** Contest 10: files 1 and 2 by its board member, 3 by nobody, in a stopped round with its
    * juror's votes; its organizer and admin; contest 20 with its own admin and round; root.
    */
  private def setUp(): (Round, Round) = {
    createContests(contestId, 20)
    imageDao.batchInsert(Seq(1L, 2L, 3L).map(i => Image(i, s"File:Image$i.jpg", None, None, 640, 480, Some(s"12-345-$i"),
      author = Option.when(i < 3)("Board member"))))
    val juror = member("juror@example.com", "jury")
    member("organizer@example.com", "organizer")
    member("admin@example.com", "admin")
    member("board@example.com", User.BOARD_ROLE, account = Some("Board member"))
    member("other-admin@example.com", "admin", contest = 20)
    User.create(User("root@example.com", "root@example.com", None, Set(User.ROOT_ROLE), Some("hash")))
    val round = roundDao.create(Round(None, 1, Some("R"), contestId, active = false))
    val other = roundDao.create(Round(None, 1, Some("O"), 20, active = false))
    Seq(1L, 2L, 3L).foreach(p => VoteJdbc.create(p, p.toInt, juror.getId, round.getId))
    (round, other)
  }

  private def disqualified(round: Round): Seq[Long] = RoundImageJdbc.disqualified(round.getId).map(_.pageId)

  private def voted(round: Round): Set[Long] = VoteJdbc.byRound(round.getId).map(_.pageId).toSet

  private def request(method: String, email: Option[String], path: String, body: Seq[(String, String)]) = {
    val r = FakeRequest(method, path).withFormUrlEncodedBody(body: _*)
    // another contest's admin signs in to theirs
    val contest = if (email.exists(_.startsWith("other-"))) 20L else contestId
    email.fold(r)(e => r.withSession(Secured.UserName -> e, Secured.CurrentContestId -> contest.toString)).withCSRFToken
  }

  private def post(app: Application, email: Option[String], path: String, body: (String, String)*): Future[Result] =
    route(app, request(POST, email, path, body)).get

  private def get(app: Application, email: String, path: String): Future[Result] =
    route(app, request(GET, Some(email), path, Nil)).get

  private def page(r: Long) = s"/admin/rounds/$r/conflicts"
  private def disqualify(r: Long) = s"/admin/rounds/$r/conflicts/disqualify"
  private def requalify(r: Long) = s"/admin/rounds/$r/conflicts/requalify"

  private val both = Seq("pageId" -> "1", "pageId" -> "2")
  private val mistaken = "reason" -> RoundConflicts.RequalifyReason.MistakenMatch

  "a round's conflicts page [A-10]" should {

    "list its conflicts to its contest's admin with the forms, and to its organizer without them" in {
      testDbApp { app =>
        val (round, _) = setUp()
        val r = round.getId
        val admin = get(app, "admin@example.com", page(r))
        val organizer = get(app, "organizer@example.com", page(r))
        (status(admin) === OK) and (contentAsString(admin) must contain("data-coi-match=\"1\"")) and
          (contentAsString(admin) must contain("Board member — board")) and
          (contentAsString(admin) must contain(disqualify(r))) and
          (status(organizer) === OK) and (contentAsString(organizer) must contain("data-coi-match=\"2\"")) and
          (contentAsString(organizer) must not(contain(disqualify(r)))) and
          (contentAsString(organizer) must not(contain("name=\"pageId\"")))
      }
    }

    "be refused to a juror, the board and another contest's admin; 404 for a missing round" in {
      testDbApp { app =>
        val (round, _) = setUp()
        val r = round.getId
        (status(get(app, "juror@example.com", page(r))) === FORBIDDEN) and
          (status(get(app, "board@example.com", page(r))) === FORBIDDEN) and
          (status(get(app, "other-admin@example.com", page(r))) === FORBIDDEN) and
          (status(get(app, "admin@example.com", page(99999L))) === NOT_FOUND)
      }
    }

    "show root the page, and the round form its link" in {
      testDbApp { app =>
        val (round, _) = setUp()
        (status(get(app, "root@example.com", page(round.getId))) === OK) and
          (contentAsString(get(app, "admin@example.com", s"/admin/rounds/edit?contestId=$contestId&id=${round.getId}")) must
            contain(page(round.getId)))
      }
    }
  }

  "disqualifying and requalifying [A-10]" should {

    "work for its contest's admin and root, in the round's contest, with the reason shown" in {
      testDbApp { app =>
        val (round, _) = setUp()
        val r = round.getId
        val first = post(app, Some("admin@example.com"), disqualify(r), both: _*)
        (status(first) === SEE_OTHER) and (redirectLocation(first) must beSome(page(r))) and
          (flash(first).get("success") must beSome(contain("2"))) and
          (disqualified(round) === Seq(1L, 2L)) and (voted(round) === Set(3L)) and
          (contentAsString(get(app, "admin@example.com", page(r))) must contain("data-dq=\"1\"")) and {
            val back = post(app, Some("root@example.com"), requalify(r), "pageId" -> "1", mistaken)
            (status(back) === SEE_OTHER) and (disqualified(round) === Seq(2L)) and (voted(round) === Set(1L, 3L))
          } and
          (VoteJdbc.byRound(r).find(_.pageId == 1L).map(_.rate) === Some(1)) and
          (RoundImageInvariants.violations(r) must beEmpty)
      }
    }

    "be refused to a juror, an organizer, the board, another contest's admin (also for this contest's round); nothing written" in {
      testDbApp { app =>
        val (round, other) = setUp()
        val r = round.getId
        val refused = for {
          email <- Seq("juror@example.com", "organizer@example.com", "board@example.com", "other-admin@example.com")
          path <- Seq(disqualify(r), requalify(r))
        } yield (email, path, status(post(app, Some(email), path, both :+ mistaken: _*)))
        (refused.filter(_._3 != FORBIDDEN) must beEmpty) and
          (status(post(app, Some("admin@example.com"), disqualify(other.getId), both: _*)) === FORBIDDEN) and
          (status(post(app, Some("admin@example.com"), disqualify(99999L), both: _*)) === NOT_FOUND) and
          (disqualified(round) must beEmpty) and (voted(round) === Set(1L, 2L, 3L))
      }
    }

    "check a user of two contests in the round's contest, never switching the session on a POST" in {
      testDbApp { app =>
        val (round, other) = setUp()
        val r = round.getId
        // an admin of contest 10 whose session is in contest 20, where they are a juror
        val both = User.findByEmail("admin@example.com").head
        UserContestJdbc.createMembership(both.getId, 20L, "jury") must beTrue
        // a juror of contest 10 who is an admin of contest 20
        val juror = User.findByEmail("juror@example.com").head
        UserContestJdbc.createMembership(juror.getId, 20L, "admin") must beTrue
        def as(email: String, current: Long, method: String, path: String, body: (String, String)*) =
          route(app, FakeRequest(method, path).withFormUrlEncodedBody(body: _*)
            .withSession(Secured.UserName -> email, Secured.CurrentContestId -> current.toString).withCSRFToken).get
        val view = as("admin@example.com", 20L, GET, page(r))
        val posted = as("admin@example.com", 20L, POST, disqualify(r), "pageId" -> "1")
        (status(view) === OK) and (status(posted) === SEE_OTHER) and
          (session(posted).get(Secured.CurrentContestId) must not(beSome(contestId.toString))) and
          (disqualified(round) === Seq(1L)) and
          (status(as("juror@example.com", 20L, POST, disqualify(r), "pageId" -> "2")) === FORBIDDEN) and
          (status(as("juror@example.com", contestId, POST, requalify(r), "pageId" -> "1", mistaken)) === FORBIDDEN) and
          (status(as("juror@example.com", 20L, GET, page(r))) === FORBIDDEN) and
          (disqualified(round) === Seq(1L)) and (other.contestId === 20L)
      }
    }

    "be refused when signed out" in {
      testDbApp { app =>
        val (round, _) = setUp()
        // to the login page: never the conflicts page, nothing written
        val first = post(app, None, disqualify(round.getId), both: _*)
        val second = post(app, None, requalify(round.getId), both :+ mistaken: _*)
        (redirectLocation(first) must not(beSome(page(round.getId)))) and (status(first) must not(beEqualTo(OK))) and
          (redirectLocation(second) must not(beSome(page(round.getId)))) and
          (disqualified(round) must beEmpty) and (voted(round) === Set(1L, 2L, 3L))
      }
    }

    "be refused while the round is open to its jurors (W7), saying so, nothing written" in {
      testDbApp { app =>
        val (round, _) = setUp()
        val r = round.getId
        Round.setActive(r, active = true)
        val result = post(app, Some("admin@example.com"), disqualify(r), both: _*)
        (status(result) === SEE_OTHER) and (flash(result).get("error") must beSome(contain("stop it"))) and
          (disqualified(round) must beEmpty) and (voted(round) === Set(1L, 2L, 3L)) and
          (contentAsString(get(app, "admin@example.com", page(r))) must contain("coi-round-open"))
      }
    }

    "refuse a requalify without a known reason, and an empty selection" in {
      testDbApp { app =>
        val (round, _) = setUp()
        val r = round.getId
        status(post(app, Some("admin@example.com"), disqualify(r), both: _*)) === SEE_OTHER
        (flash(post(app, Some("admin@example.com"), requalify(r), "pageId" -> "1")).get("error") must beSome) and
          (flash(post(app, Some("admin@example.com"), requalify(r), "pageId" -> "1", "reason" -> "because")).get("error") must
            beSome) and
          (flash(post(app, Some("admin@example.com"), disqualify(r))).get("error") must beSome) and
          (disqualified(round) === Seq(1L, 2L))
      }
    }

    "show the round's history (V88) to its admin and organizer, also on an open round, never to a juror or another contest's admin" in {
      testDbApp { app =>
        val (round, _) = setUp()
        val r = round.getId
        status(post(app, Some("admin@example.com"), disqualify(r), both: _*)) === SEE_OTHER
        status(post(app, Some("root@example.com"), requalify(r), "pageId" -> "1", mistaken)) === SEE_OTHER
        val events = RoundImageEventJdbc.byRound(r)
        def shows(body: String) =
          (body must contain("id=\"coi-history\"")) and
            (body must contain("data-event-action=\"requalify\"")) and
            (body must contain("data-event-action=\"disqualify\"")) and
            events.map(e => body must contain(s"data-event=\"${e.id}\"")).reduce(_ and _) and
            (body must contain("A mistaken match")) and (body must contain("In the round → Disqualified"))
        val admin = contentAsString(get(app, "admin@example.com", page(r)))
        val organizer = contentAsString(get(app, "organizer@example.com", page(r)))
        Round.setActive(r, active = true)
        val open = get(app, "organizer@example.com", page(r))
        (events.map(e => (e.pageId, e.action)) === Seq(1L -> "requalify", 2L -> "disqualify", 1L -> "disqualify")) and
          shows(admin) and shows(organizer) and (status(open) === OK) and shows(contentAsString(open)) and
          Seq("juror@example.com", "board@example.com", "other-admin@example.com").map { email =>
            val refused = get(app, email, page(r))
            (status(refused) === FORBIDDEN) and (contentAsString(refused) must not(contain("coi-history")))
          }.reduce(_ and _)
      }
    }

    "say when the round has no history yet" in {
      testDbApp { app =>
        val (round, _) = setUp()
        contentAsString(get(app, "organizer@example.com", page(round.getId))) must contain("coi-history-none")
      }
    }
  }

  "\"Stop the round\" on the conflicts page" should {

    def stop(r: Long) = Seq("currentId" -> r.toString, "setActive" -> "false", "returnTo" -> "conflicts")

    "be offered to its admin while the round is open, never to an organizer or on a stopped round" in {
      testDbApp { app =>
        val (round, _) = setUp()
        val r = round.getId
        val stopped = contentAsString(get(app, "admin@example.com", page(r)))
        Round.setActive(r, active = true)
        val admin = contentAsString(get(app, "admin@example.com", page(r)))
        val organizer = contentAsString(get(app, "organizer@example.com", page(r)))
        (stopped must not(contain("coi-round-stop"))) and
          (admin must contain("id=\"coi-round-stop\"")) and (admin must contain("data-confirm=\"Stop round 1?")) and
          (admin must contain("javascripts/confirm-submit.js")) and
          (organizer must not(contain("coi-round-stop"))) and (organizer must not(contain("confirm-submit.js")))
      }
    }

    "stop the round for its admin, back to the conflicts page, and then allow disqualifying" in {
      testDbApp { app =>
        val (round, _) = setUp()
        val r = round.getId
        Round.setActive(r, active = true)
        val result = post(app, Some("admin@example.com"), "/admin/setround", stop(r): _*)
        (status(result) === SEE_OTHER) and (redirectLocation(result) must beSome(page(r))) and
          (flash(result).get("success") must beSome(contain("stopped"))) and
          (Round.findById(r).map(_.active) must beSome(false)) and
          (status(post(app, Some("admin@example.com"), disqualify(r), both: _*)) === SEE_OTHER) and
          (disqualified(round) === Seq(1L, 2L)) and
          // without returnTo: the rounds list, as before
          (redirectLocation(post(app, Some("admin@example.com"), "/admin/setround",
            "currentId" -> r.toString, "setActive" -> "false")) must beSome(s"/admin/rounds?contestId=$contestId"))
      }
    }

    "be refused to a juror, an organizer, the board and another contest's admin; the round stays open" in {
      testDbApp { app =>
        val (round, other) = setUp()
        val r = round.getId
        Round.setActive(r, active = true)
        val refused = Seq("juror@example.com", "organizer@example.com", "board@example.com", "other-admin@example.com")
          .map(email => email -> status(post(app, Some(email), "/admin/setround", stop(r): _*)))
        (refused.filter(_._2 != FORBIDDEN) must beEmpty) and
          // this contest's admin, for another contest's round
          (status(post(app, Some("admin@example.com"), "/admin/setround", stop(other.getId): _*)) === FORBIDDEN) and
          (Round.findById(r).map(_.active) must beSome(true)) and
          (redirectLocation(post(app, None, "/admin/setround", stop(r): _*)) must not(beSome(page(r)))) and
          (Round.findById(r).map(_.active) must beSome(true))
      }
    }

    "need the CSRF token" in {
      withCsrfApp { app =>
        val (round, _) = setUp()
        val r = round.getId
        Round.setActive(r, active = true)
        val sessionToken = token(app)
        def send(body: (String, String)*) =
          route(app, FakeRequest(POST, "/admin/setround")
            .withHeaders(COOKIE -> "PLAY_SESSION=signed-in", ACCEPT -> "text/html")
            .withSession(Secured.UserName -> "admin@example.com", Secured.CurrentContestId -> contestId.toString,
              "csrfToken" -> sessionToken)
            .withFormUrlEncodedBody(body: _*)).get
        (status(send(stop(r): _*)) === FORBIDDEN) and (Round.findById(r).map(_.active) must beSome(true)) and
          (status(send(stop(r) :+ ("csrfToken" -> sessionToken): _*)) === SEE_OTHER) and
          (Round.findById(r).map(_.active) must beSome(false))
      }
    }
  }

  "the conflicts page's forms" should {

    "need the CSRF token" in {
      withCsrfApp { app =>
        val (round, _) = setUp()
        val r = round.getId
        val sessionToken = token(app)
        def send(path: String, body: (String, String)*) =
          route(app, FakeRequest(POST, path)
            .withHeaders(COOKIE -> "PLAY_SESSION=signed-in", ACCEPT -> "text/html")
            .withSession(Secured.UserName -> "admin@example.com", Secured.CurrentContestId -> contestId.toString,
              "csrfToken" -> sessionToken)
            .withFormUrlEncodedBody(body: _*)).get
        (status(send(disqualify(r), both: _*)) === FORBIDDEN) and
          (status(send(disqualify(r), both :+ ("csrfToken" -> "forged"): _*)) === FORBIDDEN) and
          (disqualified(round) must beEmpty) and
          (status(send(disqualify(r), both :+ ("csrfToken" -> sessionToken): _*)) === SEE_OTHER) and
          (disqualified(round) === Seq(1L, 2L)) and
          (status(send(requalify(r), Seq("pageId" -> "1", mistaken): _*)) === FORBIDDEN) and
          (disqualified(round) === Seq(1L, 2L)) and
          (status(send(requalify(r), Seq("pageId" -> "1", mistaken, "csrfToken" -> sessionToken): _*)) === SEE_OTHER) and
          (disqualified(round) === Seq(2L))
      }
    }
  }
}
