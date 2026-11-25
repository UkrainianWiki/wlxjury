package controllers

import controllers.CsrfTestApp.{token, withCsrfApp}
import db.scalikejdbc._
import org.intracer.wmua.Image
import play.api.Application
import play.api.libs.json.Json
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._
import services.distribution.{RoundDistributionHandler, RoundRebalanceHandler}
import services.jobs.{JobJdbc, NewJob}

import scala.concurrent.Future

/** Adding a juror to a running or a stopped round ([[RoundJurorsController]]) and the pause its job holds,
  * through the router (jobs run inline): the page and its POST for the round's contest's admins
  * and root only, checked in the round's contest, with the CSRF token; refusals queue nothing;
  * the paused round's page and vote error for its jurors; Start/Stop refused while a job holds
  * the round; root's Release of a round left frozen.
  */
class RoundJurorsAuthorizationSpec extends PlaySpecification with PlayTestDb {

  sequential

  private implicit val autoSession: DBSession = AutoSession

  private val contestId = 10L

  private def member(email: String, role: String, contest: Long = contestId): User =
    User.create(User(email, email, None, Set(role), Some(services.PasswordHasher.default.hash("secret")), Some(contest)))

  /** Contest 10: 3 files in an open round of every file to every juror (two jurors), a contest
    * juror outside it (newjuror), an inactive member, its organizer and admin; contest 20 with
    * its admin, a juror and a round; root.
    */
  private def setUp(): (Round, Round, Map[String, User]) = {
    createContests(contestId, 20)
    imageDao.batchInsert(Seq(1L, 2L, 3L).map(i => Image(i, s"File:Image$i.jpg", None, None, 640, 480, Some(s"12-345-$i"))))
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
    val round = roundDao.create(Round(None, 1, Some("R"), contestId, distribution = 0, active = true))
    round.addUsers(Seq("juror", "juror2").map(k => RoundUser(round.getId, users(k).getId, "jury", active = true)))
    for (p <- Seq(1L, 2L, 3L); k <- Seq("juror", "juror2")) VoteJdbc.create(p, 0, users(k).getId, round.getId)
    val other = roundDao.create(Round(None, 1, Some("O"), 20, distribution = 0, active = true))
    other.addUsers(Seq(RoundUser(other.getId, users("other-juror").getId, "jury", active = true)))
    (round, other, users)
  }

  private def request(method: String, email: Option[String], path: String, body: Seq[(String, String)]) = {
    val r = FakeRequest(method, path).withFormUrlEncodedBody(body: _*)
    val contest = if (email.exists(_.startsWith("other-"))) 20L else contestId
    email.fold(r)(e => r.withSession(Secured.UserName -> e, Secured.CurrentContestId -> contest.toString)).withCSRFToken
  }

  private def post(app: Application, email: Option[String], path: String, body: (String, String)*): Future[Result] =
    route(app, request(POST, email, path, body)).get

  private def get(app: Application, email: Option[String], path: String): Future[Result] =
    route(app, request(GET, email, path, Nil)).get

  private def page(r: Long) = s"/admin/rounds/$r/add-juror"

  private def jury(r: Long): String = {
    val rows = RoundUser.byRoundId(r)
    rows.filter(_.active).map(_.userId).sorted.mkString(",") + "|" + rows.filterNot(_.active).map(_.userId).sorted.mkString(",")
  }

  /** The values of the add page's juror select, in order. */
  private def options(body: String): Seq[String] = {
    val start = body.indexOf("id=\"round-juror-add-select\"")
    if (start < 0) Nil
    else """<option value="(\d*)"""".r.findAllMatchIn(body.substring(start, body.indexOf("</select>", start))).map(_.group(1)).toSeq
  }

  private def rebalanceJobs(): Int =
    sql"SELECT COUNT(*) FROM job WHERE kind = ${RoundRebalanceHandler.Kind}".map(_.int(1)).single().getOrElse(0)

  private def files(r: Long, u: User): Int =
    sql"SELECT COUNT(*) FROM vote WHERE round_id = $r AND jury_id = ${u.getId}".map(_.int(1)).single().getOrElse(0)

  /** The round paused by a rebalance as its "stop" step leaves it: frozen, the job holding its key. */
  private def pause(round: Round, by: User): Long = {
    DB.localTx { s => Round.freeze(Seq(round.getId))(s) }
    JobJdbc.insert(NewJob(RoundRebalanceHandler.Kind, Some(round.contestId), Some("round"), round.id,
      Some(RoundDistributionHandler.dedupeKey(round.getId)), params = Json.obj("roundId" -> round.getId, "add" -> by.getId),
      state = Json.obj("frozen" -> Seq(round.getId))), owner = None)(AutoSession)
  }

  "the add juror page" should {

    "show its contest's admin and root the contest's jurors outside the round, then the figures and the form" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        val admin = get(app, Some("admin@example.com"), page(r))
        val chosen = get(app, Some("admin@example.com"), page(r) + s"?juror=${users("newjuror").getId}")
        val root = get(app, Some("root@example.com"), page(r))
        (status(admin) === OK) and (contentAsString(admin) must contain("newjuror@example.com")) and
          (contentAsString(admin) must not(contain("other-juror@example.com"))) and
          (contentAsString(admin) must not(contain("round-juror-add-form"))) and
          (status(chosen) === OK) and (contentAsString(chosen) must contain("data-files=\"3\"")) and
          (contentAsString(chosen) must contain("round-juror-add-form")) and
          (status(root) === OK)
      }
    }

    "be refused to its organizer, a juror and another contest's admin; 404 for a missing round; login when signed out" in {
      testDbApp { app =>
        val (round, other, _) = setUp()
        val r = round.getId
        (status(get(app, Some("organizer@example.com"), page(r))) === FORBIDDEN) and
          (status(get(app, Some("juror@example.com"), page(r))) === FORBIDDEN) and
          (status(get(app, Some("other-admin@example.com"), page(r))) === FORBIDDEN) and
          // another contest's round under one's own contest
          (status(get(app, Some("admin@example.com"), page(other.getId))) === FORBIDDEN) and
          (status(get(app, Some("admin@example.com"), page(99999L))) === NOT_FOUND) and
          (status(get(app, None, page(r))) must beOneOf(SEE_OTHER, UNAUTHORIZED))
      }
    }

    "say why a juror of another contest, a non-member, an inactive member or an active juror of the round can't be added" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        val active = get(app, Some("admin@example.com"), page(r) + s"?juror=${users("juror").getId}")
        (status(active) === OK) and (contentAsString(active) must contain("round-juror-add-refused")) and
          (contentAsString(active) must contain("is already an active juror of round 1")) and
          (contentAsString(active) must not(contain("round-juror-add-form"))) and
        Seq("other-juror", "organizer", "inactive").map { k =>
          val result = get(app, Some("admin@example.com"), page(r) + s"?juror=${users(k).getId}")
          (status(result) === OK) and (contentAsString(result) must contain("round-juror-add-refused")) and
            (contentAsString(result) must not(contain("round-juror-add-form")))
        }.reduce(_ and _)
      }
    }

    "offer only the contest's jurors outside the round, never an active or a stopped juror of the round" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        RoundUser.setActive(r, users("juror2").getId, active = false)
        val body = contentAsString(get(app, Some("admin@example.com"), page(r)))
        // the round's jurors are still listed in the jury (donors), not offered
        (options(body) === Seq(users("newjuror").getId.toString)) and
          (body must contain(s"data-juror=\"${users("juror").getId}\"")) and
          (body must contain(s"data-juror=\"${users("juror2").getId}\""))
      }
    }

    "say who an active juror asked for is, with no one chosen in the select" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        val body = contentAsString(get(app, Some("admin@example.com"), page(round.getId) + s"?juror=${users("juror").getId}"))
        // the placeholder chosen, not the first juror outside the round
        (options(body) === Seq("", users("newjuror").getId.toString)) and
          (body must contain("<option value=\"\" selected disabled>")) and
          (body must not(contain(s"<option value=\"${users("newjuror").getId}\" selected"))) and
          (body must contain("juror@example.com is already an active juror of round 1"))
      }
    }

    "be linked under the round's statistics for its admin only" in {
      testDbApp { app =>
        val (round, _, _) = setUp()
        val stat = s"/roundstat/${round.getId}"
        (contentAsString(get(app, Some("admin@example.com"), stat)) must contain(page(round.getId))) and
          (contentAsString(get(app, Some("organizer@example.com"), stat)) must not(contain(page(round.getId))))
      }
    }
  }

  "adding a juror (POST)" should {

    "give its contest's admin's juror the round's files, and show the job in the rounds list" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        val y = users("newjuror")
        val result = post(app, Some("admin@example.com"), page(r), "juror" -> y.getId.toString, "jury" -> jury(r))
        (status(result) === SEE_OTHER) and (redirectLocation(result) must beSome(s"/admin/rounds?contestId=$contestId")) and
          (flash(result).get("success") must beSome(contain("3"))) and (files(r, y) === 3) and
          (Round.findById(r).map(r => r.active -> r.freezeCount) must beSome(true -> 0)) and
          (RoundImageInvariants.violations(r) must beEmpty) and
          (contentAsString(get(app, Some("admin@example.com"), s"/admin/rounds?contestId=$contestId")) must
            contain(s"/admin/jobs/"))
      }
    }

    "be refused to its organizer, a juror, another contest's admin, with nothing queued" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        val body = Seq("juror" -> users("newjuror").getId.toString, "jury" -> jury(r))
        Seq("organizer@example.com", "juror@example.com", "other-admin@example.com").map { e =>
          status(post(app, Some(e), page(r), body: _*)) === FORBIDDEN
        }.reduce(_ and _) and (rebalanceJobs() === 0) and (files(r, users("newjuror")) === 0)
      }
    }

    "refuse a juror of another contest, a non-member, an inactive member, a round being set up, a changed jury" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        val refused = Seq("other-juror", "organizer", "inactive").map { k =>
          val result = post(app, Some("admin@example.com"), page(r), "juror" -> users(k).getId.toString, "jury" -> jury(r))
          (status(result) === CONFLICT) and (contentAsString(result) must contain("round-juror-add-notice"))
        }.reduce(_ and _)
        val stale = post(app, Some("admin@example.com"), page(r), "juror" -> users("newjuror").getId.toString, "jury" -> "1|")
        sql"UPDATE round SET distributing = 1, active = 0, status = 'draft' WHERE id = $r".update()
        val settingUp = post(app, Some("admin@example.com"), page(r), "juror" -> users("newjuror").getId.toString, "jury" -> jury(r))
        refused and (status(stale) === CONFLICT) and (status(settingUp) === CONFLICT) and
          (contentAsString(settingUp) must contain("is still being set up")) and (rebalanceJobs() === 0) and
          (files(r, users("newjuror")) === 0)
      }
    }

    "refuse an active juror of the round with the message, writing nothing and queueing no job" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        val juror = users("juror")
        val shown = jury(r)
        val result = post(app, Some("admin@example.com"), page(r), "juror" -> juror.getId.toString, "jury" -> shown)
        (status(result) === CONFLICT) and (contentAsString(result) must contain("round-juror-add-notice")) and
          (contentAsString(result) must contain(s"${juror.fullname} is already an active juror of round 1")) and
          (rebalanceJobs() === 0) and (files(r, juror) === 3) and (jury(r) === shown) and
          (Round.findById(r).map(r => r.active -> r.freezeCount) must beSome(true -> 0))
      }
    }

    "need the CSRF token" in {
      withCsrfApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        val sessionToken = token(app)
        def send(body: (String, String)*) =
          route(app, FakeRequest(POST, page(r))
            .withHeaders(COOKIE -> "PLAY_SESSION=signed-in", ACCEPT -> "text/html")
            .withSession(Secured.UserName -> "admin@example.com", Secured.CurrentContestId -> contestId.toString,
              "csrfToken" -> sessionToken)
            .withFormUrlEncodedBody(body: _*)).get
        val body = Seq("juror" -> users("newjuror").getId.toString, "jury" -> jury(r))
        (status(send(body: _*)) === FORBIDDEN) and (status(send(body :+ ("csrfToken" -> "forged"): _*)) === FORBIDDEN) and
          (rebalanceJobs() === 0) and
          (status(send(body :+ ("csrfToken" -> sessionToken): _*)) === SEE_OTHER) and (files(r, users("newjuror")) === 3)
      }
    }
  }

  "a round paused by a rebalance" should {

    "show its juror the paused page and refuse a vote with round_paused; its organizer the gallery" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        val juror = users("juror")
        pause(round, users("newjuror"))
        val gallery = get(app, Some("juror@example.com"), s"/gallery/round/$r/user/${juror.getId}/page/1")
        val large = get(app, Some("juror@example.com"), s"/large/round/$r/user/${juror.getId}/pageid/1")
        val vote = route(app, FakeRequest(POST, s"/rate/round/$r/pageid/1/select/1")
          .withSession(Secured.UserName -> "juror@example.com", Secured.CurrentContestId -> contestId.toString)
          .withHeaders("X-Requested-With" -> "XMLHttpRequest").withCSRFToken).get
        val largeJson = route(app, FakeRequest(GET, s"/large/round/$r/user/${juror.getId}/pageid/1")
          .withSession(Secured.UserName -> "juror@example.com", Secured.CurrentContestId -> contestId.toString)
          .withHeaders(ACCEPT -> "application/json", "X-Requested-With" -> "XMLHttpRequest").withCSRFToken).get
        val organizer = get(app, Some("organizer@example.com"), s"/gallery/round/$r/user/0/page/1")
        (status(gallery) === OK) and (contentAsString(gallery) must contain("id=\"round-paused\"")) and
          (status(large) === OK) and (contentAsString(large) must contain("id=\"round-paused\"")) and
          (status(vote) === CONFLICT) and (contentAsString(vote) must contain("round_paused")) and
          (status(largeJson) === CONFLICT) and (contentAsString(largeJson) must contain("round_paused")) and
          (status(organizer) === OK) and (contentAsString(organizer) must not(contain("id=\"round-paused\""))) and
          (VoteJdbc.findBy(1, juror.getId, r).map(_.rate) must beSome(0))
      }
    }

    "keep the closed round's answers for a stopped juror" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        RoundUser.setActive(r, users("juror2").getId, active = false)
        pause(round, users("newjuror"))
        val vote = route(app, FakeRequest(POST, s"/rate/round/$r/pageid/1/select/1")
          .withSession(Secured.UserName -> "juror2@example.com", Secured.CurrentContestId -> contestId.toString)
          .withHeaders("X-Requested-With" -> "XMLHttpRequest").withCSRFToken).get
        val gallery = get(app, Some("juror2@example.com"), s"/gallery/round/$r/user/${users("juror2").getId}/page/1")
        // the gallery: the juror's state page (DG7 of the support diagnostics plan), not a 403
        (status(vote) === CONFLICT) and (contentAsString(vote) must contain("round_closed")) and
          (status(gallery) === OK) and (contentAsString(gallery) must contain("data-reason=\"juror_stopped\""))
      }
    }

    "refuse Start/Stop of its jurors while the job holds the round" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        pause(round, users("newjuror"))
        val result = post(app, Some("admin@example.com"), "/round/setrounduser",
          "currentId" -> users("juror").getId.toString, "parentId" -> r.toString, "setActive" -> "false")
        (status(result) === SEE_OTHER) and (flash(result).get("error") must beSome) and
          (RoundUser.byRoundId(r).find(_.userId == users("juror").getId).map(_.active) must beSome(true))
      }
    }
  }

  "a round left frozen with no job" should {

    "be flagged on the rounds list, and released by root only, back to active" in {
      testDbApp { app =>
        val (round, _, _) = setUp()
        val r = round.getId
        DB.localTx { s => Round.freeze(Seq(r))(s) }
        val list = contentAsString(get(app, Some("root@example.com"), s"/admin/rounds?contestId=$contestId"))
        val byAdmin = post(app, Some("admin@example.com"), s"/admin/rounds/$r/release-freeze")
        val stillFrozen = Round.findById(r).map(_.freezeCount)
        val byRoot = post(app, Some("root@example.com"), s"/admin/rounds/$r/release-freeze")
        (list must contain("round-left-frozen")) and (list must contain(s"/admin/rounds/$r/release-freeze")) and
          (status(byAdmin) === FORBIDDEN) and (stillFrozen must beSome(1)) and
          (status(byRoot) === SEE_OTHER) and (Round.findById(r).map(r => r.active -> r.freezeCount) must beSome(true -> 0))
      }
    }
  }

  /** A round of one juror per file (N = 1) in contest 10: files 1-3 the juror's, 4-6 juror2's
    * (the files of [[setUp]], three more).
    */
  private def roundOfOne(users: Map[String, User]): Round = {
    imageDao.batchInsert(Seq(4L, 5L, 6L).map(i => Image(i, s"File:Image$i.jpg", None, None, 640, 480, Some(s"12-345-$i"))))
    val round = roundDao.create(Round(None, 2, Some("N1"), contestId, distribution = 1, active = true))
    round.addUsers(Seq("juror", "juror2").map(k => RoundUser(round.getId, users(k).getId, "jury", active = true)))
    Seq(1L, 2L, 3L).foreach(p => VoteJdbc.create(p, 0, users("juror").getId, round.getId))
    Seq(4L, 5L, 6L).foreach(p => VoteJdbc.create(p, 0, users("juror2").getId, round.getId))
    round
  }

  "adding a juror to a round of N jurors per file" should {

    "show the donors ticked and the shares, and move the ticked donors' unrated files" in {
      testDbApp { app =>
        val (_, _, users) = setUp()
        val round = roundOfOne(users)
        val r = round.getId
        val y = users("newjuror")
        val shown = get(app, Some("admin@example.com"), page(r) + s"?juror=${y.getId}")
        val body = contentAsString(shown)
        val result = post(app, Some("admin@example.com"), page(r), "juror" -> y.getId.toString, "jury" -> jury(r),
          "donor[]" -> users("juror").getId.toString, "donor[]" -> users("juror2").getId.toString)
        (status(shown) === OK) and (body must contain("name=\"donor\"")) and (body must contain("data-files=\"2\"")) and
          (body must contain("name=\"donor[]\"")) and
          (status(result) === SEE_OTHER) and (files(r, y) === 2) and (files(r, users("juror")) === 2) and
          (files(r, users("juror2")) === 2) and (RoundImageInvariants.violations(r) must beEmpty)
      }
    }

    "refuse a ticked donor who isn't a juror of the round, with nothing queued" in {
      testDbApp { app =>
        val (_, _, users) = setUp()
        val round = roundOfOne(users)
        val r = round.getId
        val result = post(app, Some("admin@example.com"), page(r), "juror" -> users("newjuror").getId.toString,
          "jury" -> jury(r), "donor[]" -> users("organizer").getId.toString)
        (status(result) === CONFLICT) and (rebalanceJobs() === 0)
      }
    }

    "offer a stopped juror's Start with a fair share to the round's admin only" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        RoundUser.setActive(r, users("juror2").getId, active = false)
        val link = page(r) + s"?juror=${users("juror2").getId}"
        val stat = s"/roundstat/$r"
        (contentAsString(get(app, Some("admin@example.com"), stat)) must contain(link.replace("?", "?"))) and
          (contentAsString(get(app, Some("organizer@example.com"), stat)) must not(contain(link))) and
          (contentAsString(get(app, Some("admin@example.com"), link)) must contain("round-juror-add-restart"))
      }
    }

    "show the stopped juror of the statistics' link as the page's subject, and start them again with restart" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        val stopped = users("juror2")
        RoundUser.setActive(r, stopped.getId, active = false)
        // juror2 lacks file 3: the restart gives it
        sql"DELETE FROM vote WHERE round_id = $r AND jury_id = ${stopped.getId} AND page_id = 3".update()
        val body = contentAsString(get(app, Some("admin@example.com"), page(r) + s"?juror=${stopped.getId}"))
        val tampered = post(app, Some("admin@example.com"), page(r), "juror" -> stopped.getId.toString, "jury" -> jury(r))
        val tamperedBody = contentAsString(tampered)
        val jobsAfterTamper = rebalanceJobs()
        val restarted = post(app, Some("admin@example.com"), page(r), "juror" -> stopped.getId.toString, "jury" -> jury(r),
          "restart" -> "true")
        (body must contain(s"id=\"round-juror-add-subject\" data-juror=\"${stopped.getId}\"")) and
          (body must not(contain("id=\"round-juror-add-select\""))) and
          (body must contain("name=\"restart\" value=\"true\"")) and (body must contain("round-juror-add-form")) and
          (status(tampered) === CONFLICT) and (tamperedBody must contain("juror2@example.com is stopped in round 1")) and
          (jobsAfterTamper === 0) and
          (status(restarted) === SEE_OTHER) and (files(r, stopped) === 3) and
          (RoundUser.byRoundId(r).find(_.userId == stopped.getId).map(_.active) must beSome(true))
      }
    }
  }

  // ── offered == allowed (D3 revised 2026-11-25: running and stopped rounds) ───

  /** A state of the round the statistics are shown for, set on the fixture's round (`legacy`: another
    * round, with votes and no round_user rows), and who acts on it.
    */
  private case class Case(name: String, offered: Boolean, pageAllows: Boolean, actor: String = "admin@example.com",
      legacy: Boolean = false)(val set: (Round, Map[String, User]) => Unit)

  private def freeze(r: Long): Unit = DB.localTx { s => Round.freeze(Seq(r))(s) }

  private val cases = Seq(
    Case("running", offered = true, pageAllows = true)((_, _) => ()),
    Case("stopped", offered = true, pageAllows = true)((r, _) => Round.setActive(r.getId, active = false)),
    Case("being created", offered = false, pageAllows = false)((r, _) =>
      sql"UPDATE round SET distributing = 1, active = 0, status = 'draft' WHERE id = ${r.getId}".update()),
    // the plan's allowed exception: the page and the POST add the round_user row only; not linked (its
    // jurors are chosen on the jurors form)
    Case("held in review", offered = false, pageAllows = true)((r, _) =>
      sql"UPDATE round SET distributing = 1, active = 0, status = 'paused' WHERE id = ${r.getId}".update()),
    Case("running, frozen by another job", offered = false, pageAllows = false)((r, _) => freeze(r.getId)),
    Case("stopped, frozen by another job", offered = false, pageAllows = false)((r, _) => {
      Round.setActive(r.getId, active = false); freeze(r.getId)
    }),
    Case("holding a queued job's key", offered = false, pageAllows = false)((r, _) =>
      JobJdbc.insert(NewJob(RoundDistributionHandler.Kind, Some(r.contestId), Some("round"), r.id,
        Some(RoundDistributionHandler.dedupeKey(r.getId))), owner = None)(AutoSession)),
    Case("legacy", offered = false, pageAllows = false, legacy = true)((_, _) => ()),
    // root reaches a retired contest's round; its admins get the 403 page
    Case("a retired contest's, stopped", offered = false, pageAllows = false, actor = "root@example.com")((r, _) => {
      Round.setActive(r.getId, active = false)
      sql"UPDATE contest_jury SET retired_at = NOW(3) WHERE id = ${r.contestId}".update()
    })
  )

  "Add juror… and Start and give a fair share…" should {

    "be offered on the statistics exactly when the page and its POST take the juror (but the review exception), alike" in {
      testDbApp { app =>
        cases.map { c =>
          SharedPlayApp.truncateAll()
          val (fixture, _, users) = setUp()
          val round =
            if (!c.legacy) fixture
            else {
              val legacy = roundDao.create(Round(None, 2, Some("Legacy"), contestId, distribution = 0, active = true))
              VoteJdbc.create(1, 0, users("juror").getId, legacy.getId)
              legacy
            }
          val r = round.getId
          // a stopped juror of the round, whose row offers "Start and give a fair share…"
          if (!c.legacy) RoundUser.setActive(r, users("juror2").getId, active = false)
          c.set(round, users)
          val actor = Some(c.actor)
          val stat = get(app, actor, s"/roundstat/$r")
          val statBody = contentAsString(stat)
          val addPage = contentAsString(get(app, actor, page(r) + s"?juror=${users("newjuror").getId}"))
          val restartPage = contentAsString(get(app, actor, page(r) + s"?juror=${users("juror2").getId}"))
          val activeBefore = Round.findById(r).map(_.active)
          val result = post(app, actor, page(r), "juror" -> users("newjuror").getId.toString, "jury" -> jury(r))
          val allowedByPost = status(result) == SEE_OTHER
          def allowedByPage(body: String) = body.contains("round-juror-add-form") && !body.contains("round-juror-add-refused")
          (status(stat) aka s"${c.name}: statistics" must_== OK) and
            (statBody.contains("id=\"round-juror-add-link\"") aka s"${c.name}: Add juror… offered" must_== c.offered) and
            (statBody.contains("round-juror-share") aka s"${c.name}: Start and give a fair share… offered" must_==
              (c.offered && !c.legacy)) and
            (allowedByPage(addPage) aka s"${c.name}: the add page takes the juror" must_== c.pageAllows) and
            (allowedByPost aka s"${c.name}: the POST takes the juror (${status(result)})" must_== c.pageAllows) and
            (status(result) aka s"${c.name}: a refused POST" must beOneOf(SEE_OTHER, CONFLICT)) and
            // the stopped juror's own page: the same answer (none in a legacy round)
            ((c.legacy || allowedByPage(restartPage) == c.pageAllows) aka s"${c.name}: the restart page" must beTrue) and
            // the round's own state is left as it was: a stopped round stays stopped, a running one running
            (Round.findById(r).map(_.active) aka s"${c.name}: the round's active flag" must_== activeBefore) and
            (rebalanceJobs() aka s"${c.name}: jobs queued" must_== (if (allowedByPost && !c.name.contains("review")) 1 else 0))
        }.reduce(_ and _)
      }
    }

    "name the rounds created from it, linked, as information; none for a round with none or only a cancelled one" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        RoundUser.setActive(r, users("juror2").getId, active = false)
        def pages() = Seq(page(r), page(r) + s"?juror=${users("newjuror").getId}", page(r) + s"?juror=${users("juror2").getId}")
          .map(p => contentAsString(get(app, Some("admin@example.com"), p)))
        // a cancelled creation from it doesn't count
        val cancelled = roundDao.create(Round(None, 2, Some("Gone"), contestId, previous = Some(r.toString)))
        sql"UPDATE round SET status = ${Round.Status.Cancelled}, distributing = 1 WHERE id = ${cancelled.getId}".update()
        val none = pages()
        val semi = roundDao.create(Round(None, 3, Some("Semi-final"), contestId, previous = Some(r.toString)))
        val other = roundDao.create(Round(None, 4, None, contestId, previous = Some(s"${r},${semi.getId}")))
        val two = pages()
        val semiPages = Seq(contentAsString(get(app, Some("admin@example.com"), page(semi.getId))))
        none.map(b => b must not(contain("round-juror-add-next"))).reduce(_ and _) and
          two.map { b =>
            (b must contain("id=\"round-juror-add-next\"")) and (b must contain("Created from this round:")) and
              (b must contain(s"""href="/roundstat/${semi.getId}" data-round="${semi.getId}">Round 3 (Semi-final)</a>""")) and
              (b must contain(s"""href="/roundstat/${other.getId}" data-round="${other.getId}">Round 4</a>""")) and
              (b must not(contain(s"""/roundstat/${cancelled.getId}""""))) and
              (b must contain("images are distributed again")) and
              (b must contain("<p class=\"text-muted\" id=\"round-juror-add-next\">"))
          }.reduce(_ and _) and
          // round 3 has round 4 created from it too
          (semiPages.head must contain(s"""data-round="${other.getId}"""")) and
          (semiPages.head must not(contain(s"""data-round="${semi.getId}"""")))
      }
    }

    "keep a stopped round closed for its jurors while a juror is added: round_closed, never round_paused" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        val juror = users("juror")
        Round.setActive(r, active = false)
        pause(round, users("newjuror"))
        val vote = route(app, FakeRequest(POST, s"/rate/round/$r/pageid/1/select/1")
          .withSession(Secured.UserName -> "juror@example.com", Secured.CurrentContestId -> contestId.toString)
          .withHeaders("X-Requested-With" -> "XMLHttpRequest").withCSRFToken).get
        val largeJson = route(app, FakeRequest(GET, s"/large/round/$r/user/${juror.getId}/pageid/1")
          .withSession(Secured.UserName -> "juror@example.com", Secured.CurrentContestId -> contestId.toString)
          .withHeaders(ACCEPT -> "application/json", "X-Requested-With" -> "XMLHttpRequest").withCSRFToken).get
        val gallery = get(app, Some("juror@example.com"), s"/gallery/round/$r/user/${juror.getId}/page/1")
        (status(vote) === CONFLICT) and (contentAsString(vote) must contain("round_closed")) and
          (contentAsString(vote) must not(contain("round_paused"))) and
          (contentAsString(largeJson) must not(contain("round_paused"))) and
          (status(gallery) === OK) and (contentAsString(gallery) must contain("data-reason=\"round_stopped\"")) and
          (contentAsString(gallery) must not(contain("id=\"round-paused\""))) and
          (VoteJdbc.findBy(1, juror.getId, r).map(_.rate) must beSome(0))
      }
    }

    "add a juror to a stopped round through the POST, the round staying stopped, with no warning" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        val y = users("newjuror")
        Round.setActive(r, active = false)
        val shown = contentAsString(get(app, Some("admin@example.com"), page(r) + s"?juror=${y.getId}"))
        val result = post(app, Some("admin@example.com"), page(r), "juror" -> y.getId.toString, "jury" -> jury(r))
        (shown must contain("id=\"round-juror-add-stopped\"")) and (shown must not(contain("id=\"round-juror-add-pause\""))) and
          (status(result) === SEE_OTHER) and (flash(result).get("success") must beSome(contain("3"))) and
          (flash(result).get(RoundController.WarningFlash) must beNone) and (files(r, y) === 3) and
          (Round.findById(r).map(r => (r.active, r.freezeCount)) must beSome((false, 0))) and
          (RoundUser.byRoundId(r).find(_.userId == y.getId).map(_.active) must beSome(true)) and
          (RoundImageInvariants.violations(r) must beEmpty)
      }
    }

    "be refused on a stopped round to its organizer, a juror and another contest's admin, with nothing queued or linked" in {
      testDbApp { app =>
        val (round, _, users) = setUp()
        val r = round.getId
        Round.setActive(r, active = false)
        RoundUser.setActive(r, users("juror2").getId, active = false)
        val body = Seq("juror" -> users("newjuror").getId.toString, "jury" -> jury(r))
        Seq("organizer@example.com", "juror@example.com", "other-admin@example.com").map { e =>
          (status(get(app, Some(e), page(r))) aka s"$e: page" must_== FORBIDDEN) and
            (status(get(app, Some(e), page(r) + s"?juror=${users("juror2").getId}")) aka s"$e: restart page" must_== FORBIDDEN) and
            (status(post(app, Some(e), page(r), body: _*)) aka s"$e: POST" must_== FORBIDDEN)
        }.reduce(_ and _) and
          (contentAsString(get(app, Some("organizer@example.com"), s"/roundstat/$r")) must not(contain(page(r)))) and
          (rebalanceJobs() === 0) and (files(r, users("newjuror")) === 0) and
          (Round.findById(r).map(_.active) must beSome(false))
      }
    }
  }
}
