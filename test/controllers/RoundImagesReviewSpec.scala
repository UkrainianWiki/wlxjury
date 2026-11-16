package controllers

import controllers.CsrfTestApp.{token, withCsrfApp}
import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image}
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._
import services.TestRoundServices

import scala.concurrent.Future

/** A round's review page and its Distribute (P3, D20; [[RoundReviewController]]): the pages for
  * the round's contest's admins, organizers (read only) and root; Distribute for its admins and
  * root, checked in the round's contest, a POST with the CSRF token, refused unless the round is in
  * review; another contest's admin refused, a missing round 404, signed out to the login page or a
  * JSON 401. Jurors never see a round in review: its gallery, large view and votes are refused, and
  * the round is in no menu of theirs. The new-round form makes one (always held in review).
  */
class RoundImagesReviewSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val contestId = 10L

  private def member(email: String, role: String, contest: Long = contestId): User =
    User.create(User(email, email, None, Set(role), Some(services.PasswordHasher.default.hash("secret")), Some(contest)))

  /** Contest 10: files 1-4 in its category, its juror, organizer and admin, a round in review
    * (its juror has one vote row, as a partly distributed review leaves it); contest 20 with its
    * own admin and an active round; root.
    */
  private def setUp(): (Round, Round, User) = {
    createContests(contestId, 20)
    val contest: ContestJury = contestDao.findById(contestId).get
    val imgs = (1L to 4L).map(i => Image(i, s"File:Image$i.jpg", None, None, 640, 480, Some(s"12-345-$i")))
    imageDao.batchInsert(imgs)
    CategoryLinkJdbc.addToCategory(contest.categoryId.get, imgs)
    val juror = member("juror@example.com", "jury")
    member("organizer@example.com", "organizer")
    member("admin@example.com", "admin")
    member("other-admin@example.com", "admin", contest = 20)
    User.create(User("root@example.com", "root@example.com", None, Set(User.ROOT_ROLE), Some("hash")))
    val service = TestRoundServices.roundService()
    val round = service.submit(Round(None, 0, Some("R"), contestId, distribution = 1, active = true), Seq(juror.getId),
      None, review = true).round
    DB.localTx { implicit s => RoundWrites.lock(Seq(round.getId)); VoteJdbc.create(1L, 0, juror.getId, round.getId) }
    val other = roundDao.create(Round(None, 1, Some("O"), 20, active = true))
    (roundDao.findById(round.getId).get, other, juror)
  }

  private def request(method: String, email: Option[String], path: String, body: Seq[(String, String)] = Nil) = {
    val r = FakeRequest(method, path).withFormUrlEncodedBody(body: _*)
    val contest = if (email.exists(_.startsWith("other-"))) 20L else contestId
    email.fold(r)(e => r.withSession(Secured.UserName -> e, Secured.CurrentContestId -> contest.toString)).withCSRFToken
  }

  private def get(app: Application, email: String, path: String): Future[Result] = route(app, request(GET, Some(email), path)).get
  private def post(app: Application, email: Option[String], path: String): Future[Result] = route(app, request(POST, email, path)).get

  private def review(r: Long) = s"/admin/rounds/$r/review"
  private def distribute(r: Long) = s"/admin/rounds/$r/distribute"

  private def votes(r: Long): Int = VoteJdbc.byRound(r).size
  private def jobs(r: Long): Int =
    DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM job WHERE subject_id = $r".map(_.int(1)).single().getOrElse(0))

  "the review page" should {

    "show the round's files to its admin with Distribute, and to its organizer without it" in {
      testDbApp { app =>
        val (round, _, _) = setUp()
        val r = round.getId
        val admin = get(app, "admin@example.com", review(r))
        val organizer = get(app, "organizer@example.com", review(r))
        (status(admin) === OK) and (contentAsString(admin) must contain("id=\"round-review-distribute\"")) and
          (contentAsString(admin) must contain("data-pageId=\"2\"")) and
          (contentAsString(admin) must contain(s"/admin/rounds/$r/review/file/3")) and
          // each tab's count after its name as the bar's (snippets/count), without brackets
          {
            val tabs = contentAsString(admin).split("id=\"round-review-tabs\"")(1).split("</ul>")(0)
            ("""<a [^>]*id="round-review-tab-[a-z]+"[^>]*>[^<(]+<span class="wlx-count"> \d+</span></a>""".r
              .findAllIn(tabs).size === RoundReviewController.Statuses.size) and (tabs must not(contain("(")))
          } and
          (status(organizer) === OK) and (contentAsString(organizer) must contain("data-pageId=\"4\"")) and
          (contentAsString(organizer) must not(contain(distribute(r)))) and
          (status(get(app, "organizer@example.com", s"/admin/rounds/$r/review/file/2")) === OK) and
          (status(get(app, "organizer@example.com", s"/admin/rounds/$r/review/regions")) === OK) and
          (status(get(app, "root@example.com", review(r))) === OK)
      }
    }

    "be refused to a juror and another contest's admin; 404 for a missing round; signed out to the login page" in {
      testDbApp { app =>
        val (round, other, _) = setUp()
        val r = round.getId
        val signedOut = route(app, FakeRequest(GET, review(r))).get
        val signedOutScript = route(app, FakeRequest(GET, s"/admin/rounds/$r/review/regions")
          .withHeaders("X-Requested-With" -> "XMLHttpRequest")).get
        (status(get(app, "juror@example.com", review(r))) === FORBIDDEN) and
          (status(get(app, "juror@example.com", s"/admin/rounds/$r/review/file/2")) === FORBIDDEN) and
          (status(get(app, "other-admin@example.com", review(r))) === FORBIDDEN) and
          // another contest's round under one's own contest
          (status(get(app, "admin@example.com", review(other.getId))) === FORBIDDEN) and
          (status(get(app, "admin@example.com", review(99999L))) === NOT_FOUND) and
          (status(get(app, "admin@example.com", review(r) + "?status=nope")) === NOT_FOUND) and
          (status(signedOut) === SEE_OTHER) and (redirectLocation(signedOut) must beSome.which(_.contains("returnTo"))) and
          (status(signedOutScript) === UNAUTHORIZED)
      }
    }

    "show its admin a failed job with Retry, and its organizer only that it failed (no job buttons, no poll)" in {
      testDbApp { app =>
        val (round, _, _) = setUp()
        val r = round.getId
        DB.autoCommit(implicit s => sql"""INSERT INTO job (kind, contest_id, subject_type, subject_id, dedupe_key, status, params, state)
          VALUES ('round_distribution', $contestId, 'round', $r, ${services.distribution.RoundDistributionHandler.dedupeKey(r)},
            'failed', '{"mode": "distribute"}', '{}')""".update())
        val admin = contentAsString(get(app, "admin@example.com", review(r)))
        val organizer = contentAsString(get(app, "organizer@example.com", review(r)))
        (admin must contain("class=\"job-status\"")) and
          (organizer must not(contain("job-status"))) and (organizer must not(contain("/jobs/"))) and
          (organizer must contain("id=\"round-review-job-failed\""))
      }
    }

    "refuse its regions and a file's large view to a juror and another contest's admin" in {
      testDbApp { app =>
        val (round, _, _) = setUp()
        val r = round.getId
        def script(email: String, path: String) =
          route(app, FakeRequest(GET, path).withHeaders("X-Requested-With" -> "XMLHttpRequest")
            .withSession(Secured.UserName -> email,
              Secured.CurrentContestId -> (if (email.startsWith("other-")) "20" else contestId.toString))).get
        (status(script("juror@example.com", s"/admin/rounds/$r/review/regions")) === FORBIDDEN) and
          (status(script("other-admin@example.com", s"/admin/rounds/$r/review/regions")) === FORBIDDEN) and
          (status(script("admin@example.com", "/admin/rounds/99999/review/regions")) === NOT_FOUND) and
          (status(get(app, "other-admin@example.com", s"/admin/rounds/$r/review/file/2")) === FORBIDDEN) and
          (status(get(app, "admin@example.com", "/admin/rounds/99999/review/file/2")) === NOT_FOUND) and
          (status(get(app, "admin@example.com", s"/admin/rounds/$r/review/regions")) === OK)
      }
    }

    "be linked from the rounds list with Distribute (its jurors form), the round form and the round's statistics" in {
      testDbApp { app =>
        val (round, _, _) = setUp()
        val r = round.getId
        val list = contentAsString(get(app, "admin@example.com", s"/admin/rounds?contestId=$contestId"))
        (list must contain(s"id=\"round-review-$r\"")) and (list must contain(s"/admin/rounds/$r/jurors")) and
          (list must not(contain(distribute(r)))) and
          (contentAsString(get(app, "admin@example.com", s"/admin/rounds/edit?contestId=$contestId&id=$r")) must
            contain("id=\"round-review-link\"")) and
          (contentAsString(get(app, "organizer@example.com", s"/roundstat/$r")) must contain(review(r)))
      }
    }
  }

  "Distribute" should {

    "be refused to its organizer, a juror, another contest's admin, and signed out; nothing queued" in {
      testDbApp { app =>
        val (round, _, _) = setUp()
        val r = round.getId
        val jobsBefore = jobs(r)
        val signedOut = route(app, FakeRequest(POST, distribute(r)).withHeaders("X-Requested-With" -> "XMLHttpRequest").withCSRFToken).get
        (status(post(app, Some("organizer@example.com"), distribute(r))) === FORBIDDEN) and
          (status(post(app, Some("juror@example.com"), distribute(r))) === FORBIDDEN) and
          (status(post(app, Some("other-admin@example.com"), distribute(r))) === FORBIDDEN) and
          (status(signedOut) === UNAUTHORIZED) and
          (jobs(r) === jobsBefore) and (votes(r) === 1) and (Round.findById(r).get.inReview must beTrue)
      }
    }

    "distribute for its admin, open the round, and then be refused as not in review" in {
      testDbApp { app =>
        val (round, _, _) = setUp()
        val r = round.getId
        // one after the other: two POSTs sent at once race for the round's lock, and either may be first
        val done = post(app, Some("admin@example.com"), distribute(r))
        val doneStatus = status(done)
        val again = post(app, Some("admin@example.com"), distribute(r))
        (doneStatus === SEE_OTHER) and (redirectLocation(done) === Some(review(r))) and
          (flash(done).get("success") must beSome) and
          (Round.findById(r).get.openToJurors must beTrue) and (votes(r) === 4) and
          (status(again) === SEE_OTHER) and (flash(again).get("error") must beSome)
      }
    }

    "be allowed to root, and refused for an active round with nothing queued" in {
      testDbApp { app =>
        val (round, other, _) = setUp()
        val otherJobs = jobs(other.getId)
        (status(post(app, Some("root@example.com"), distribute(round.getId))) === SEE_OTHER) and
          (Round.findById(round.getId).get.openToJurors must beTrue) and
          { post(app, Some("root@example.com"), distribute(other.getId)); jobs(other.getId) === otherJobs }
      }
    }

    "need the CSRF token" in {
      withCsrfApp { app =>
        val (round, _, _) = setUp()
        val r = round.getId
        val sessionToken = token(app)
        def send(body: (String, String)*) =
          route(app, FakeRequest(POST, distribute(r))
            .withHeaders(COOKIE -> "PLAY_SESSION=signed-in", ACCEPT -> "text/html")
            .withSession(Secured.UserName -> "admin@example.com", Secured.CurrentContestId -> contestId.toString,
              "csrfToken" -> sessionToken)
            .withFormUrlEncodedBody(body: _*)).get
        (status(send()) === FORBIDDEN) and (Round.findById(r).get.inReview must beTrue) and
          (status(send("csrfToken" -> "forged")) === FORBIDDEN) and (Round.findById(r).get.inReview must beTrue) and
          (status(send("csrfToken" -> sessionToken)) === SEE_OTHER) and (Round.findById(r).get.openToJurors must beTrue)
      }
    }
  }

  "a juror of a round in review" should {

    "not see it: its gallery, large view and votes refused, in no menu of theirs" in {
      testDbApp { app =>
        val (round, _, juror) = setUp()
        val r = round.getId
        val j = juror.getId
        val gallery = get(app, "juror@example.com", s"/gallery/round/$r/user/$j/page/1")
        val large = get(app, "juror@example.com", s"/large/round/$r/user/$j/pageid/1")
        val vote = route(app, request(POST, Some("juror@example.com"), s"/rate/round/$r/pageid/1/select/1?rate=0")).get
        val largeVote = route(app, request(POST, Some("juror@example.com"), s"/large/round/$r/pageid/1/select/1")).get
        (status(gallery) must beOneOf(FORBIDDEN, SEE_OTHER)) and
          (contentAsString(gallery) must not(contain("data-pageId=\"1\""))) and
          (status(large) must beOneOf(FORBIDDEN, SEE_OTHER)) and
          (status(vote) must beOneOf(CONFLICT, FORBIDDEN)) and
          (status(largeVote) must beOneOf(CONFLICT, FORBIDDEN, SEE_OTHER)) and
          (VoteJdbc.findBy(1L, j, r).map(_.rate) === Some(0)) and
          (Round.activeRounds(juror).map(_.getId) must not(contain(r))) and
          (Round.contestsWithOpenRound(j, Seq(contestId)) must beEmpty)
      }
    }
  }

  "\"Distribute new files\" on a cancelled creation (a Reinstate)" should {

    "be refused to its organizer, and done for its admin, recorded, held in review again" in {
      testDbApp { app =>
        val (round, _, juror) = setUp()
        val r = round.getId
        DB.autoCommit(implicit s => sql"UPDATE round SET status = 'cancelled' WHERE id = $r".update())
        def edit(email: String) = route(app, request(POST, Some(email), "/admin/rounds/save",
          Seq("id" -> r.toString, "number" -> round.number.toString, "name" -> "R", "contest" -> contestId.toString,
            "roles" -> "jury", "distribution" -> "1", "rates" -> "1", "minMpx" -> "No", "minSize" -> "No",
            "mediaType" -> "all", "jurors[0]" -> juror.getId.toString, "newImages" -> "true"))).get
        val refused = edit("organizer@example.com")
        val refusedStatus = (status(refused), Round.findById(r).flatMap(_.status))
        val done = edit("admin@example.com")
        val doneStatus = status(done) // waited for, before reading what it wrote
        val admin = User.findByEmail("admin@example.com").head.getId
        val by = DB.readOnly(implicit s => sql"SELECT status_changed_by FROM round WHERE id = $r".map(_.longOpt(1)).single().flatten)
        (refusedStatus === ((FORBIDDEN, Some("cancelled")))) and (doneStatus === SEE_OTHER) and
          (Round.findById(r).get.inReview must beTrue) and (by === Some(admin))
      }
    }
  }

  "the round form" should {

    "create a round for review, an older page's \"Distribute as soon as the images are ready\" ignored" in {
      testDbApp { app =>
        val (_, _, juror) = setUp()
        def create(name: String, extra: (String, String)*) = route(app, request(POST, Some("admin@example.com"),
          "/admin/rounds/save", Seq("number" -> "2", "name" -> name, "contest" -> contestId.toString, "roles" -> "jury",
            "distribution" -> "1", "rates" -> "1", "minMpx" -> "No", "minSize" -> "No", "mediaType" -> "all",
            "jurors[0]" -> juror.getId.toString) ++ extra)).get
        val form = contentAsString(get(app, "admin@example.com", s"/admin/rounds/edit?contestId=$contestId"))
        val reviewed = create("Reviewed")
        val atOnce = create("At once", "distributeAfter" -> "true")
        def named(n: String) = Round.findByContest(contestId).find(_.name.contains(n)).get
        (form must not(contain("name=\"distributeAfter\""))) and (form must contain("Review images first")) and
          (status(reviewed) === SEE_OTHER) and (named("Reviewed").inReview must beTrue) and
          (votes(named("Reviewed").getId) === 0) and
          (status(atOnce) === SEE_OTHER) and (named("At once").inReview must beTrue) and
          (votes(named("At once").getId) === 0)
      }
    }
  }
}
