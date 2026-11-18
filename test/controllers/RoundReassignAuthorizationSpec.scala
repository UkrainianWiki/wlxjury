package controllers

import controllers.CsrfTestApp.{token, withCsrfApp}
import db.scalikejdbc._
import org.intracer.wmua.Image
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._
import services.distribution.{RoundReassign, RoundRebalanceHandler}

import scala.concurrent.Future

/** Reassigning a juror's unrated files ([[RoundJurorsController.reassignPage]] and
  * [[RoundJurorsController.reassign]], round-editing plan §7 and §9), through the router (jobs run
  * inline): the round's contest's admins and root only, checked in the round's contest without
  * switching it, a juror not in the round 404, receivers validated with nothing queued, a round not
  * running refused, the CSRF token; the stat table's link and waiting count, the round form's tick.
  */
class RoundReassignAuthorizationSpec extends PlaySpecification with PlayTestDb {

  sequential

  private implicit val autoSession: DBSession = AutoSession

  private val contestId = 10L

  private def member(email: String, role: String, contest: Long = contestId): User =
    User.create(User(email, email, None, Set(role), Some(services.PasswordHasher.default.hash("secret")), Some(contest)))

  /** Contest 10: an open round of one juror per file: files 1-3 the juror's, 4-6 juror2's; an inactive
    * member in the round with no files; a contest juror outside it; its organizer and admin. Contest 20
    * with its admin, a juror and a round of theirs. Root.
    */
  private def setUp(): (Round, Round, Map[String, User]) = {
    createContests(contestId, 20)
    imageDao.batchInsert((1L to 6L).map(i => Image(i, s"File:Image$i.jpg", None, None, 640, 480, Some(s"12-345-$i"))))
    val users = Map(
      "juror" -> member("juror@example.com", "jury"),
      "juror2" -> member("juror2@example.com", "jury"),
      "newjuror" -> member("newjuror@example.com", "jury"),
      "inactive" -> member("inactive@example.com", User.NO_ROLE),
      "organizer" -> member("organizer@example.com", "organizer"),
      "admin" -> member("admin@example.com", "admin"),
      "other-admin" -> member("other-admin@example.com", "admin", contest = 20),
      "other-juror" -> member("other-juror@example.com", "jury", contest = 20),
      "root" -> User.create(User("root@example.com", "root@example.com", None, Set(User.ROOT_ROLE), Some("hash")))
    )
    val round = roundDao.create(Round(None, 1, Some("R"), contestId, distribution = 1, active = true))
    round.addUsers(Seq("juror", "juror2", "inactive").map(k => RoundUser(round.getId, users(k).getId, "jury", active = true)))
    Seq(1L, 2L, 3L).foreach(p => VoteJdbc.create(p, 0, users("juror").getId, round.getId))
    Seq(4L, 5L, 6L).foreach(p => VoteJdbc.create(p, 0, users("juror2").getId, round.getId))
    val other = roundDao.create(Round(None, 1, Some("O"), 20, distribution = 1, active = true))
    other.addUsers(Seq(RoundUser(other.getId, users("other-juror").getId, "jury", active = true)))
    VoteJdbc.create(1, 0, users("other-juror").getId, other.getId)
    (round, other, users)
  }

  private def request(method: String, email: Option[String], path: String, body: Seq[(String, String)],
      contest: Option[Long] = None) = {
    val r = FakeRequest(method, path).withFormUrlEncodedBody(body: _*)
    val c = contest.getOrElse(if (email.exists(_.startsWith("other-"))) 20L else contestId)
    email.fold(r)(e => r.withSession(Secured.UserName -> e, Secured.CurrentContestId -> c.toString)).withCSRFToken
  }

  private def post(app: Application, email: Option[String], path: String, body: (String, String)*): Future[Result] =
    route(app, request(POST, email, path, body)).get

  private def get(app: Application, email: Option[String], path: String): Future[Result] =
    route(app, request(GET, email, path, Nil)).get

  private def path(r: Long) = s"/admin/rounds/$r/reassign-juror"
  private def page(r: Long, juror: User) = s"${path(r)}?juror=${juror.getId}"

  private def jury(r: Long): String = {
    val rows = RoundUser.byRoundId(r)
    rows.filter(_.active).map(_.userId).sorted.mkString(",") + "|" + rows.filterNot(_.active).map(_.userId).sorted.mkString(",")
  }

  private def rebalanceJobs(): Int =
    sql"SELECT COUNT(*) FROM job WHERE kind = ${RoundRebalanceHandler.Kind}".map(_.int(1)).single().getOrElse(0)

  private def files(r: Long, u: User): Int =
    sql"SELECT COUNT(*) FROM vote WHERE round_id = $r AND jury_id = ${u.getId}".map(_.int(1)).single().getOrElse(0)

  private def active(r: Long, u: User): Option[Boolean] = RoundUser.byRoundId(r).find(_.userId == u.getId).map(_.active)

  "the reassign page" should {

    "show its contest's admin and root the juror's files, the receivers ticked, the figures and the form" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        val juryBefore = jury(r)
        val admin = get(app, Some("admin@example.com"), page(r, users("juror")))
        val body = contentAsString(admin)
        val root = get(app, Some("root@example.com"), page(r, users("juror")))
        // a preview with other ticks (picked): still only a page
        val picked = contentAsString(get(app, Some("admin@example.com"),
          s"${page(r, users("juror"))}&picked=1&receiver=${users("inactive").getId}"))
        // the GETs queue nothing and change no juror's state or file
        (rebalanceJobs() === 0) and (jury(r) === juryBefore) and (files(r, users("juror")) === 3) and
          (files(r, users("juror2")) === 3) and (picked must contain("round-juror-reassign-refused")) and
          // the receivers' labels hold their state, the help is the fieldset's description, the inactive
          // member is marked; the confirm is guarded by preview-confirm.js
          (body must contain("""<fieldset aria-describedby="round-juror-reassign-receivers-help">""")) and
          (body must contain("round-juror-no-membership")) and (body must contain("preview-confirm.js")) and
          (body must contain("""data-preview-form="round-juror-reassign-choose"""")) and
          (status(admin) === OK) and (body must contain("round-juror-reassign-form")) and
          (body must contain("data-unrated=\"3\"")) and (body must contain("data-moved=\"3\"")) and
          (body must contain(s"""name="receiver" value="${users("juror2").getId}" checked""")) and
          (body must contain("name=\"receiver[]\"")) and
          (status(root) === OK) and (contentAsString(root) must contain("round-juror-reassign-form"))
      }
    }

    "be refused to its organizer, a juror, another contest's admin (also for another contest's round); 404s; login when signed out" in {
      testDbApp { app =>
        val (round, other, users) = setUp()
        val r = round.getId
        val signedOut = route(app, FakeRequest(GET, page(r, users("juror")))
          .withHeaders("X-Requested-With" -> "XMLHttpRequest")).get
        (status(get(app, Some("organizer@example.com"), page(r, users("juror")))) === FORBIDDEN) and
          (status(get(app, Some("juror@example.com"), page(r, users("juror")))) === FORBIDDEN) and
          (status(get(app, Some("other-admin@example.com"), page(r, users("juror")))) === FORBIDDEN) and
          (status(get(app, Some("admin@example.com"), page(other.getId, users("other-juror")))) === FORBIDDEN) and
          (status(get(app, Some("admin@example.com"), page(99999L, users("juror")))) === NOT_FOUND) and
          // a juror id not in the round
          (status(get(app, Some("admin@example.com"), page(r, users("newjuror")))) === NOT_FOUND) and
          (status(get(app, Some("admin@example.com"), page(r, users("other-juror")))) === NOT_FOUND) and
          (status(get(app, None, page(r, users("juror")))) === SEE_OTHER) and (status(signedOut) === UNAUTHORIZED)
      }
    }

    "say there is nothing to reassign, with no receivers and no form, for a juror with no unrated file" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        // juror2: files 4 and 5 rated, file 6 deleted on Commons (an unrated vote the Unrated column counts,
        // but a reassign doesn't move)
        sql"UPDATE vote SET rate = 1 WHERE round_id = $r AND page_id IN (4, 5)".update()
        sql"UPDATE images SET deleted_at = NOW() WHERE page_id = 6".update()
        val result = get(app, Some("admin@example.com"), page(r, users("juror2")))
        val body = contentAsString(result)
        (status(result) === OK) and (body must contain("round-juror-reassign-refused")) and
          (body must contain("nothing to reassign")) and (body must contain("data-unrated=\"0\"")) and
          (body must not(contain("round-juror-reassign-form"))) and (body must not(contain("round-juror-reassign-choose"))) and
          (rebalanceJobs() === 0)
      }
    }

    "say the round must be started first, with no form, when it isn't running" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        Round.setActive(round.getId, active = false)
        val result = get(app, Some("admin@example.com"), page(round.getId, users("juror")))
        (status(result) === OK) and (contentAsString(result) must contain("round-juror-reassign-refused")) and
          (contentAsString(result) must not(contain("round-juror-reassign-form")))
      }
    }
  }

  "reassigning (POST)" should {

    "move the juror's unrated files to the receivers and stop the juror, without switching the session's contest" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        val result = route(app, request(POST, Some("root@example.com"), path(r), Seq("juror" -> users("juror").getId.toString,
          "jury" -> jury(r), "receiver[]" -> users("juror2").getId.toString), contest = Some(20L))).get
        (status(result) === SEE_OTHER) and (redirectLocation(result) must beSome(s"/admin/rounds?contestId=$contestId")) and
          (flash(result).get("success") must beSome(contain("3"))) and
          (session(result).get(Secured.CurrentContestId) must not(beSome(contestId.toString))) and
          (files(r, users("juror")) === 0) and (files(r, users("juror2")) === 6) and
          (active(r, users("juror")) must beSome(false)) and
          (Round.findById(r).map(r => r.active -> r.freezeCount) must beSome(true -> 0)) and
          (RoundImageInvariants.violations(r) must beEmpty)
      }
    }

    "refuse a juror with no unrated file, queueing nothing and leaving the juror running" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        sql"UPDATE vote SET rate = 1 WHERE round_id = $r AND jury_id = ${users("juror2").getId}".update()
        val result = post(app, Some("admin@example.com"), path(r), "juror" -> users("juror2").getId.toString,
          "jury" -> jury(r), "receiver[]" -> users("juror").getId.toString)
        (status(result) === CONFLICT) and (contentAsString(result) must contain("nothing to reassign")) and
          (contentAsString(result) must not(contain("round-juror-reassign-form"))) and
          (rebalanceJobs() === 0) and (active(r, users("juror2")) must beSome(true)) and
          (files(r, users("juror2")) === 3) and (files(r, users("juror")) === 3) and
          (Round.findById(r).map(r => r.active -> r.freezeCount) must beSome(true -> 0))
      }
    }

    "be refused to its organizer, a juror, another contest's admin, with nothing queued; a juror not in the round 404" in {
      testDbApp { app =>
        val (round, other, users) = setUp()
        val r = round.getId
        val body = Seq("juror" -> users("juror").getId.toString, "jury" -> jury(r), "receiver[]" -> users("juror2").getId.toString)
        val denied = Seq("organizer@example.com", "juror@example.com", "other-admin@example.com").map { e =>
          status(post(app, Some(e), path(r), body: _*)) === FORBIDDEN
        }.reduce(_ and _)
        val otherRound = post(app, Some("admin@example.com"), path(other.getId), "juror" -> users("other-juror").getId.toString,
          "jury" -> jury(other.getId))
        val notInRound = post(app, Some("admin@example.com"), path(r), "juror" -> users("newjuror").getId.toString,
          "jury" -> jury(r), "receiver[]" -> users("juror2").getId.toString)
        denied and (status(otherRound) === FORBIDDEN) and (status(notInRound) === NOT_FOUND) and
          (rebalanceJobs() === 0) and (files(r, users("juror")) === 3)
      }
    }

    "refuse a receiver of another contest, a non-member, an inactive member, none ticked, a round not running, a changed jury" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        def send(receivers: String*) = post(app, Some("admin@example.com"), path(r),
          (Seq("juror" -> users("juror").getId.toString, "jury" -> jury(r)) ++ receivers.map("receiver[]" -> _)): _*)
        val refused = Seq(users("other-juror"), users("organizer"), users("inactive")).map { u =>
          val result = send(users("juror2").getId.toString, u.getId.toString)
          (status(result) === CONFLICT) and (contentAsString(result) must contain("round-juror-reassign-notice"))
        }.reduce(_ and _)
        val none = send()
        val stale = post(app, Some("admin@example.com"), path(r), "juror" -> users("juror").getId.toString, "jury" -> "1|",
          "receiver[]" -> users("juror2").getId.toString)
        Round.setActive(r, active = false)
        val stopped = send(users("juror2").getId.toString)
        // signed out, a script's POST (the CSRF filter is off in this app): JSON 401
        val signedOut = route(app, FakeRequest(POST, path(r)).withHeaders("X-Requested-With" -> "XMLHttpRequest")
          .withFormUrlEncodedBody("juror" -> users("juror").getId.toString, "jury" -> jury(r))).get
        refused and (status(none) === CONFLICT) and (status(stale) === CONFLICT) and (status(stopped) === CONFLICT) and
          (status(signedOut) === UNAUTHORIZED) and
          (rebalanceJobs() === 0) and (files(r, users("juror")) === 3) and (active(r, users("juror")) must beSome(true))
      }
    }

    "need the CSRF token; JSON 401 or the login page signed out" in {
      withCsrfApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        val sessionToken = token(app)
        def send(body: (String, String)*) =
          route(app, FakeRequest(POST, path(r))
            .withHeaders(COOKIE -> "PLAY_SESSION=signed-in", ACCEPT -> "text/html")
            .withSession(Secured.UserName -> "admin@example.com", Secured.CurrentContestId -> contestId.toString,
              "csrfToken" -> sessionToken)
            .withFormUrlEncodedBody(body: _*)).get
        val body = Seq("juror" -> users("juror").getId.toString, "jury" -> jury(r), "receiver[]" -> users("juror2").getId.toString)
        val signedOut = route(app, FakeRequest(POST, path(r)).withHeaders("X-Requested-With" -> "XMLHttpRequest")
          .withFormUrlEncodedBody(body: _*)).get
        (status(send(body: _*)) === FORBIDDEN) and (status(send(body :+ ("csrfToken" -> "forged"): _*)) === FORBIDDEN) and
          // with the CSRF filter on, a signed-out POST without a token is refused by it first: the CSRF JSON
          (status(signedOut) === FORBIDDEN) and (contentAsString(signedOut) must contain("\"csrf\"")) and
          (rebalanceJobs() === 0) and
          (status(send(body :+ ("csrfToken" -> sessionToken): _*)) === SEE_OTHER) and (files(r, users("juror2")) === 6)
      }
    }
  }

  "the reassign's flashes" should {

    "say what was removed in a round where every file goes to every juror" in {
      testDbApp { app =>
        val (_, _, users) = setUp()
        val all = roundDao.create(Round(None, 2, Some("All"), contestId, distribution = 0, active = true))
        all.addUsers(Seq("juror", "juror2").map(k => RoundUser(all.getId, users(k).getId, "jury", active = true)))
        Seq("juror", "juror2").foreach(k => Seq(1L, 2L).foreach(p => VoteJdbc.create(p, 0, users(k).getId, all.getId)))
        val result = post(app, Some("admin@example.com"), path(all.getId), "juror" -> users("juror").getId.toString,
          "jury" -> jury(all.getId))
        (status(result) === SEE_OTHER) and (flash(result).get("success") must beSome(contain("2 unrated files were removed"))) and
          (flash(result).get("success") must beSome(not(contain("waiting")))) and (files(all.getId, users("juror")) === 0) and
          (files(all.getId, users("juror2")) === 2)
      }
    }

    "on Cancel of a reassign stopped part-way, say to run it again for the rest" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        val st = services.distribution.ReassignState(phase = "move", stopped = true, movedAny = true,
          given = Map(users("juror2").getId.toString -> 2L))
        val id = services.jobs.JobJdbc.insert(services.jobs.NewJob(RoundRebalanceHandler.Kind, Some(contestId), Some("round"),
          Some(r), Some(services.distribution.RoundDistributionHandler.dedupeKey(r)),
          params = play.api.libs.json.Json.obj("roundId" -> r, "from" -> users("juror").getId,
            "receivers" -> Seq(users("juror2").getId), "by" -> users("admin").getId),
          state = play.api.libs.json.Json.toJsObject(st)), owner = None)
        services.jobs.JobJdbc.update(id, services.jobs.JobStatus.Failed, play.api.libs.json.Json.toJsObject(st),
          services.jobs.Progress(), Some(services.jobs.JobError("job.error.reference", reference = Some("ref")).encode))
        val result = post(app, Some("admin@example.com"), s"/admin/jobs/$id/cancel")
        (status(result) === SEE_OTHER) and
          (flash(result).get("success") must beSome(contain("Run “Reassign files…” again to move the rest"))) and
          (flash(result).get("success") must beSome(contain("after 2 of the juror's unrated files had been moved or left waiting"))) and
          (services.jobs.JobJdbc.findById(id).map(_.status) must beSome(services.jobs.JobStatus.Cancelled))
      }
    }
  }

  "the round's statistics and form" should {

    "link Reassign files… only for a juror with unrated files a reassign moves (in, not deleted; or set aside)" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        val stat = s"/roundstat/$r"
        def link(u: User) = s"""href="${page(r, u)}""""
        // juror2: files 4 and 5 rated, file 6 deleted on Commons (still in the Unrated column)
        sql"UPDATE vote SET rate = 1 WHERE round_id = $r AND page_id IN (4, 5)".update()
        sql"UPDATE images SET deleted_at = NOW() WHERE page_id = 6".update()
        val before = contentAsString(get(app, Some("admin@example.com"), stat))
        val jurorsBefore = RoundReassign.reassignableJurors(r)
        // an unrated row of juror2's set aside: a reassign hands it on too
        sql"INSERT INTO vote_aside (page_id, jury_id, round_id, rate) VALUES (6, ${users("juror2").getId}, $r, 0)".update()
        val aside = contentAsString(get(app, Some("admin@example.com"), stat))
        (before must contain(link(users("juror")))) and (before must not(contain(link(users("juror2"))))) and
          (before must contain(users("juror2").email)) and
          (jurorsBefore === Set(users("juror").getId)) and
          (aside must contain(link(users("juror2")))) and
          (RoundReassign.reassignableJurors(r) === Set(users("juror").getId, users("juror2").getId))
      }
    }

    "link Reassign files… for the admin only, while the round runs; show the waiting files to its organizer too" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        val stat = s"/roundstat/$r"
        val link = path(r)
        val admin = contentAsString(get(app, Some("admin@example.com"), stat))
        val organizer = contentAsString(get(app, Some("organizer@example.com"), stat))
        // a file waiting: juror2's row of file 6 removed
        sql"DELETE FROM vote WHERE round_id = $r AND page_id = 6".update()
        val waiting = contentAsString(get(app, Some("organizer@example.com"), stat))
        val form = contentAsString(get(app, Some("admin@example.com"), s"/admin/rounds/edit?id=$r&contestId=$contestId"))
        Round.setActive(r, active = false)
        val stopped = contentAsString(get(app, Some("admin@example.com"), stat))
        (stopped must not(contain("round-waiting"))) and
          (admin must contain(link)) and (organizer must not(contain(link))) and
          (admin must not(contain("round-waiting"))) and
          (waiting must contain("data-waiting=\"1\"")) and (RoundReassign.waitingCount(Round.findById(r).get) must beSome(1L)) and
          (form must contain("name=\"topUpWaiting\"")) and (form must not(contain("name=\"topUpWaiting\" checked"))) and
          (stopped must not(contain(link)))
      }
    }
  }
}
