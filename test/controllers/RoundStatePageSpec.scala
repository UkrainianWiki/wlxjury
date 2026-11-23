package controllers

import db.scalikejdbc.{PlayTestDb, Round, RoundUser, User, VoteJdbc}
import org.intracer.wmua.Image
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import scala.concurrent.Future

/** A juror's gallery and large view of a round not open to them (support diagnostics plan, D-0b,
  * DG7): a 200 page with the round's state and the juror's open rounds, never a 403, and one INFO
  * line on `round.access` per refusal, no `security.authz` line. Anyone else is refused as before.
  * The vote's 409 `round_closed` is unchanged.
  */
class RoundStatePageSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val pageId = 4242L

  private case class Fixture(contestId: Long, juror: User, open: Round, round: Round)

  /** A contest with a juror, an open round of theirs (named "Open one") and `round`, of which
    * they are a juror too (a round_user row and a vote).
    */
  private def fixture(round: Long => Round): Fixture = {
    val contest = contestDao.create(None, "WLE", 2024, "Ukraine")
    val juror = userDao.create(User("Juror", "juror@example.com", None, Set("jury"), contestId = contest.id))
    imageDao.batchInsert(Seq(Image(pageId, s"File:Image$pageId.jpg", None, None, 640, 480, Some("UA-1"))))
    val open = roundDao.create(Round(None, 1, Some("Open one"), contestId = contest.getId, active = true))
    val tested = roundDao.create(round(contest.getId))
    Seq(open, tested).foreach { r =>
      r.addUsers(Seq(RoundUser(r.getId, juror.getId, "jury", active = true)))
      VoteJdbc.create(pageId, 0, juror.getId, r.getId)
    }
    Fixture(contest.getId, juror, open, tested)
  }

  private def get(app: Application, url: String, user: User, script: Boolean = false): Future[Result] = {
    val headers = if (script) Seq("X-Requested-With" -> "XMLHttpRequest") else Nil
    route(app, FakeRequest(GET, url).withSession(Secured.UserName -> user.email).withHeaders(headers: _*).withCSRFToken).get
  }

  private def comment(app: Application, f: Fixture, user: User): Future[Result] =
    route(app, FakeRequest(POST, s"/comment/region/all/pageid/$pageId?round=${f.round.getId}")
      .withSession(Secured.UserName -> user.email).withFormUrlEncodedBody("id" -> "0", "text" -> "late")
      .withCSRFToken).get

  /** An active round_distribution job that holds `round` frozen: one completing a creation, or
    * one that doesn't ("Distribute new files"); queued, never run here. */
  private def holdingJob(f: Fixture, round: Round, completingCreation: Boolean): Long = {
    import services.distribution.RoundDistributionHandler
    import services.jobs.{JobJdbc, NewJob}
    import play.api.libs.json.Json
    JobJdbc.insert(NewJob(RoundDistributionHandler.Kind, contestId = Some(f.contestId), subjectType = Some("round"),
      subjectId = f.open.id, params = Json.obj("completingCreation" -> completingCreation),
      state = Json.obj("frozen" -> Seq(round.getId))), owner = None)(scalikejdbc.AutoSession)
  }

  private def galleryUrl(round: Round, user: User) = s"/gallery/round/${round.getId}/user/${user.getId}/page/1"
  private def largeUrl(round: Round, user: User) = s"/large/round/${round.getId}/user/${user.getId}/pageid/$pageId"

  /** The page's and the logs' answer for the juror in the gallery and the large view. */
  private def statePages(app: Application, f: Fixture, reason: String, text: String) = {
    val ((gallery, large), logs) = LogCapture("round.access", "security.authz") {
      val g = get(app, galleryUrl(f.round, f.juror), f.juror)
      val l = get(app, largeUrl(f.round, f.juror), f.juror)
      ((status(g), contentAsString(g)), (status(l), contentAsString(l)))
    }
    val line = s"round_access_refused reason=$reason roundId=${f.round.getId} accountId=${f.juror.getId} contestId=${f.contestId}"
    forall(Seq(gallery, large)) { case (code, page) =>
      code === OK
      page must contain(s"""data-reason="$reason"""")
      page must contain(text)
      // no file of the round: only its state, and the juror's open rounds
      page must not(contain(s"Image$pageId"))
      page must contain(s"""href="/gallery/round/${f.open.getId}/user/${f.juror.getId}/page/1"""")
      page must contain("Open one")
    }
    logs("round.access") === Seq(line, line) // one per refusal
    logs("security.authz") must beEmpty      // a state, not a denial
  }

  "a juror's gallery and large view of a round not open to them (DG7) [J-10]" should {

    "show a stopped round's state page, not a 403" in {
      testDbApp { implicit app =>
        val f = fixture(c => Round(None, 2, Some("Stopped one"), contestId = c, active = false))
        statePages(app, f, "round_stopped", "Round “Stopped one” is stopped")
      }
    }

    "show a round being distributed's state page" in {
      testDbApp { implicit app =>
        val f = fixture(c => Round(None, 2, Some("New one"), contestId = c, active = true, distributing = true))
        statePages(app, f, "round_distributing", "Round “New one” is still being prepared")
      }
    }

    "show a juror stopped on an open round their state page" in {
      testDbApp { implicit app =>
        val f = fixture(c => Round(None, 2, Some("Busy one"), contestId = c, active = true))
        RoundUser.setActive(f.round.getId, f.juror.getId, active = false)
        statePages(app, f, "juror_stopped", "has stopped your voting in round “Busy one”")
      }
    }

    "show a round frozen while the next round is prepared from it: a state that passes, with Reload" in {
      testDbApp { implicit app =>
        // the previous round of a distribution: frozen while open, no rebalance job pausing it
        val f = fixture(c => Round(None, 2, Some("Previous one"), contestId = c, active = true))
        scalikejdbc.DB.localTx(implicit s => Round.freeze(Seq(f.round.getId)))
        statePages(app, f, "round_preparing", "Round “Previous one” is closed while a later round reads its votes")
        val page = contentAsString(get(app, galleryUrl(f.round, f.juror), f.juror))
        page must contain(s"""id="round-state-reload" href="${galleryUrl(f.round, f.juror)}"""")
        // a stopped round offers no Reload
        scalikejdbc.DB.localTx(implicit s => Round.release(Seq(f.round.getId), close = true))
        contentAsString(get(app, galleryUrl(f.round, f.juror), f.juror)) must not(contain("round-state-reload"))
      }
    }

    "tell a frozen round's closing by a creation from one given back by Distribute new files" in {
      testDbApp { implicit app =>
        val f = fixture(c => Round(None, 2, Some("Previous one"), contestId = c, active = true))
        scalikejdbc.DB.localTx(implicit s => Round.freeze(Seq(f.round.getId)))
        // "Distribute new files" into a later round: it gives the round back as it was
        val reopening = holdingJob(f, f.round, completingCreation = false)
        statePages(app, f, "round_preparing", "Round “Previous one” is closed while a later round reads its votes")
        // a creation: it closes the round at its end; no Reload, no promise
        scalikejdbc.DB.autoCommit { implicit s =>
          import scalikejdbc._
          sql"UPDATE job SET status = 'done' WHERE id = $reopening".update()
        }
        holdingJob(f, f.round, completingCreation = true)
        statePages(app, f, "round_closing", "Round “Previous one” is being closed while the next round is prepared")
        contentAsString(get(app, galleryUrl(f.round, f.juror), f.juror)) must not(contain("round-state-reload"))
      }
    }

    "show round_stopped for a frozen round root stopped meanwhile" in {
      testDbApp { implicit app =>
        val f = fixture(c => Round(None, 2, Some("Previous one"), contestId = c, active = true))
        scalikejdbc.DB.localTx(implicit s => Round.freeze(Seq(f.round.getId)))
        Round.setActiveGuarded(f.round.getId, active = false)
        statePages(app, f, "round_stopped", "Round “Previous one” is stopped")
      }
    }

    "answer a comment on a frozen round with the state, the comment not saved, Reload to its large view" in {
      testDbApp { implicit app =>
        val f = fixture(c => Round(None, 2, Some("Previous one"), contestId = c, active = true))
        scalikejdbc.DB.localTx(implicit s => Round.freeze(Seq(f.round.getId)))
        val (page, logs) = LogCapture("round.access", "security.authz") {
          val r = comment(app, f, f.juror)
          (status(r), contentAsString(r))
        }
        page._1 === OK
        page._2 must contain("data-reason=\"round_preparing\"")
        page._2 must contain("Your comment was not saved.")
        page._2 must beMatching(s"(?s).*id=\"round-state-reload\" href=\"/large/round/${f.round.getId}/user/${f.juror.getId}/[^\"]*pageid/$pageId.*")
        logs("round.access") must haveSize(1)
        logs("security.authz") must beEmpty
        org.intracer.wmua.CommentJdbc.findAll() must beEmpty
        // a gallery's state page has no such line
        contentAsString(get(app, galleryUrl(f.round, f.juror), f.juror)) must not(contain("round-state-comment-lost"))
      }
    }

    "say so to a script: the vote path's 409 round_closed with the reason, logged on round.access" in {
      testDbApp { implicit app =>
        val f = fixture(c => Round(None, 2, Some("Stopped one"), contestId = c, active = false))
        val (results, logs) = LogCapture("round.access", "security.authz") {
          Seq(
            get(app, galleryUrl(f.round, f.juror), f.juror, script = true),
            // the large view asked for JSON
            route(app, FakeRequest(GET, largeUrl(f.round, f.juror)).withSession(Secured.UserName -> f.juror.email)
              .withHeaders("Accept" -> "application/json").withCSRFToken).get,
            // the gallery's region menu
            get(app, s"/regions/round/${f.round.getId}/user/${f.juror.getId}/region/all", f.juror, script = true)
          ).map(r => (status(r), (contentAsJson(r) \ "error").as[String], (contentAsJson(r) \ "reason").as[String]))
        }
        forall(results)(_ === ((CONFLICT, "round_closed", "round_stopped")))
        logs("round.access") must haveSize(3)
        logs("security.authz") must beEmpty
      }
    }

    "refuse a juror's comment on a stopped round with the state page, logged on round.access" in {
      testDbApp { implicit app =>
        val f = fixture(c => Round(None, 2, Some("Stopped one"), contestId = c, active = false))
        val (result, logs) = LogCapture("round.access", "security.authz") {
          val r = route(app, FakeRequest(POST, s"/comment/region/all/pageid/$pageId?round=${f.round.getId}")
            .withSession(Secured.UserName -> f.juror.email).withFormUrlEncodedBody("id" -> "0", "text" -> "late")
            .withCSRFToken).get
          (status(r), contentAsString(r))
        }
        result._1 === OK
        result._2 must contain("""data-reason="round_stopped"""")
        logs("round.access") must haveSize(1)
        logs("security.authz") must beEmpty
        org.intracer.wmua.CommentJdbc.findAll() must beEmpty
      }
    }

    "leave the juror's open round and a stopped round's staff view as they are" in {
      testDbApp { implicit app =>
        val f = fixture(c => Round(None, 2, Some("Stopped one"), contestId = c, active = false))
        val admin = userDao.create(User("Admin", "admin@example.com", None, Set("admin"), contestId = Some(f.contestId)))
        val (pages, logs) = LogCapture("round.access") {
          Seq(get(app, galleryUrl(f.open, f.juror), f.juror), get(app, galleryUrl(f.round, admin), admin),
            get(app, galleryUrl(f.round, f.juror), admin)).map(r => (status(r), contentAsString(r)))
        }
        forall(pages) { case (code, page) =>
          code === OK
          page must not(contain("round-state"))
        }
        logs("round.access") must beEmpty
      }
    }

    "still refuse with a 403 a juror who is not one of the round, and a juror of another contest" in {
      testDbApp { implicit app =>
        val f = fixture(c => Round(None, 2, Some("Stopped one"), contestId = c, active = false))
        val stranger = userDao.create(User("Other", "other@example.com", None, Set("jury"), contestId = Some(f.contestId)))
        val elsewhere = contestDao.create(None, "WLM", 2024, "Poland")
        val foreign = userDao.create(User("Foreign", "foreign@example.com", None, Set("jury"), contestId = elsewhere.id))
        val (codes, logs) = LogCapture("round.access", "security.authz") {
          Seq(stranger, foreign).flatMap(u => Seq(get(app, galleryUrl(f.round, u), u), get(app, largeUrl(f.round, u), u),
            // the region menu and a comment, the paths the state reaches too
            get(app, s"/regions/round/${f.round.getId}/user/${u.getId}/region/all", u, script = true),
            comment(app, f, u)))
            .map(r => (status(r), contentAsString(r)))
        }
        forall(codes) { case (code, page) =>
          code === FORBIDDEN
          page must not(contain("Stopped one"))
          page must not(contain("round_closed"))
        }
        logs("round.access") must beEmpty
        logs("security.authz") must haveSize(8)
        org.intracer.wmua.CommentJdbc.findAll() must beEmpty
        // a juror of the contest who is not one of the round: the generic reason, as before
        logs("security.authz").head must contain(s"accountId=${stranger.getId}")
      }
    }

    "keep the vote's 409 round_closed on a stopped round and for a stopped juror [S-06]" in {
      testDbApp { implicit app =>
        val f = fixture(c => Round(None, 2, Some("Stopped one"), contestId = c, active = false))
        def vote(round: Round) = route(app, FakeRequest(POST, s"/rate/round/${round.getId}/pageid/$pageId/select/1")
          .withSession(Secured.UserName -> f.juror.email).withHeaders("X-Requested-With" -> "XMLHttpRequest")
          .withCSRFToken).get
        val stopped = vote(f.round)
        status(stopped) === CONFLICT
        (contentAsJson(stopped) \ "error").as[String] === "round_closed"

        RoundUser.setActive(f.open.getId, f.juror.getId, active = false)
        val jurorStopped = vote(f.open)
        status(jurorStopped) === CONFLICT
        (contentAsJson(jurorStopped) \ "error").as[String] === "round_closed"
        VoteJdbc.findBy(pageId, f.juror.getId, f.round.getId).map(_.rate) === Some(0)
      }
    }

    // an old round_user row with no recorded status (`active` NULL) is stopped since V108 (the
    // maintainer's decision of 2026-11-20; the 2026-11-19 review of #381 found the gallery saying
    // "juror stopped" while the vote path took a vote): the column is NOT NULL, the page and the
    // vote agree
    "refuse NULL in round_user.active, and show a stopped juror as stopped while refusing their vote" in {
      testDbApp { implicit app =>
        val f = fixture(c => Round(None, 2, Some("Old one"), contestId = c, active = true))
        scala.util.Try(scalikejdbc.DB.autoCommit(implicit s => scalikejdbc.SQL(
          "UPDATE round_user SET active = NULL WHERE round_id = ? AND user_id = ?")
          .bind(f.round.getId, f.juror.getId).update.apply())) must beAFailedTry
        RoundUser.setActive(f.round.getId, f.juror.getId, active = false)
        val gallery = get(app, galleryUrl(f.round, f.juror), f.juror)
        status(gallery) === OK
        contentAsString(gallery) must contain("data-reason=\"juror_stopped\"")
        val vote = route(app, FakeRequest(POST, s"/rate/round/${f.round.getId}/pageid/$pageId/select/1")
          .withSession(Secured.UserName -> f.juror.email).withHeaders("X-Requested-With" -> "XMLHttpRequest")
          .withCSRFToken).get
        status(vote) === CONFLICT
        (contentAsJson(vote) \ "error").as[String] === "round_closed"
        VoteJdbc.findBy(pageId, f.juror.getId, f.round.getId).map(_.rate) === Some(0)
      }
    }
  }
}
