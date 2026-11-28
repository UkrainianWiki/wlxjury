package controllers

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image}
import org.jsoup.Jsoup
import org.jsoup.nodes.{Document, Element}
import play.api.Application
import play.api.libs.json.Json
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import services.TestRoundServices
import services.distribution.RoundDistributionHandler

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** A round set up in numbered steps (UI-path plan §4 step 1, the maintainer's decision of
  * 2026-11-25): 1 "Which files" (the round form) → 2 "Check the matching files" (the review page)
  * → 3 "Jurors" (the jurors form) → open to jurors.
  *  - the new-round form is step 1, with one button, "Save and find matching files", and its help;
  *  - the save lands on step 2 saying "Round N is saved. Step 2 of 3: …", its step bar there, its
  *    heading "N files match (the contest has M)" by N's plural form, "Distribute to jurors" its
  *    main action;
  *  - while its files are being matched, that action is disabled with its reason, step 3 isn't
  *    linked, and Distribute is refused (offered == allowed);
  *  - step 3's heading names the round and the step; Edit round of a round being set up has the
  *    step bar and its current step; round statistics and the conflicts page the one-line state
  *    and a link to the current step (for an organizer the review page only, no action);
  *  - an open round has none of it, and its edit save is confirmed on the Rounds page;
  *  - a juror sees none of these pages; nothing calls the round "created" or "ready";
  *  - a round whose job opens it by itself when done, and a creation that failed, was cancelled or
  *    didn't finish, are not "being set up": no step, as the Rounds page shows them.
  */
class RoundSetupStepsSpec extends PlaySpecification with PlayTestDb {

  sequential

  /** A contest with 8 files in its category (1-4 small, 5-8 large), two jurors, an organizer and
    * an admin.
    */
  private def setUp(): ContestJury = {
    val contest = contestDao.create(None, "WLM", 2026, "Ukraine")
    TestDb.setSource(contest.getId, Some("Category:WLM images"))
    val imgs = (1L to 8L).map(i =>
      if (i <= 4) Image(i, s"File:Image$i.jpg", None, None, 1000, 800, Some(s"80-361-000$i"))
      else Image(i, s"File:Image$i.jpg", None, None, 3000, 2000, Some(s"80-361-000$i")))
    imageDao.batchInsert(imgs)
    CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, imgs)
    def member(email: String, role: String) =
      User.create(User(email, email, None, Set(role), Some(services.PasswordHasher.default.hash("secret")), contest.id))
    Seq("juror1", "juror2").foreach(j => member(s"$j@example.com", "jury"))
    member("organizer@example.com", "organizer")
    member("admin@example.com", "admin")
    contestDao.findById(contest.getId).get
  }

  private def admin = User.findByEmail("admin@example.com").head
  private def jurorIds = Seq("juror1", "juror2").flatMap(j => User.findByEmail(s"$j@example.com").flatMap(_.id))

  /** A round held in review, its files matched (its fill runs inline), no juror. */
  private def inReview(contest: ContestJury, name: String = "Semi", monuments: Option[String] = None): Round = {
    val r = Round(None, 0, Some(name), contest.getId, active = true, monuments = monuments)
    roundDao.findById(TestRoundServices.roundService().submit(r, Nil, None, review = true).round.getId).get
  }

  /** A round open to its jurors: held in review, then distributed to both jurors. */
  private def open(contest: ContestJury): Round = {
    val r = inReview(contest, "Open")
    TestRoundServices.roundService().distributeReviewed(r.getId, admin.id, jurors = Some(jurorIds))
    roundDao.findById(r.getId).get
  }

  /** A re-apply of `r`'s filters running (its files being matched again), never run here. */
  private def matching(contest: ContestJury, r: Round): Long =
    scalikejdbc.DB.autoCommit { implicit s =>
      services.jobs.JobJdbc.insert(services.jobs.NewJob(RoundDistributionHandler.Kind, contest.id,
        Some(RoundDistributionHandler.SubjectType), r.id, Some(RoundDistributionHandler.dedupeKey(r.getId)),
        params = Json.obj("mode" -> RoundDistributionHandler.Mode.Fill, "fromReview" -> true, "reapply" -> true)),
        owner = Some("spec"))
    }

  /** A distribution job of `r` with `params`, ended with `status` (failed, cancelled), never run here. */
  private def endedJob(contest: ContestJury, r: Round, status: String, params: (String, Json.JsValueWrapper)*): Long =
    scalikejdbc.DB.autoCommit { implicit s =>
      val id = services.jobs.JobJdbc.insert(services.jobs.NewJob(RoundDistributionHandler.Kind, contest.id,
        Some(RoundDistributionHandler.SubjectType), r.id, Some(RoundDistributionHandler.dedupeKey(r.getId)),
        params = Json.obj(params: _*)), owner = None)
      scalikejdbc.SQL("UPDATE job SET status = ? WHERE id = ?").bind(status, id).update()
      id
    }

  private val Reapply: Seq[(String, Json.JsValueWrapper)] = Seq("mode" -> RoundDistributionHandler.Mode.Fill,
    "fromReview" -> true, "reapply" -> true)

  private def request(method: String, email: String, path: String, body: Seq[(String, String)] = Nil) =
    FakeRequest(method, path).withFormUrlEncodedBody(body: _*).withSession(Secured.UserName -> email).withCSRFToken

  private def get(app: Application, email: String, path: String): Future[Result] =
    route(app, request(GET, email, path)).get

  private def page(app: Application, email: String, path: String): Document =
    Jsoup.parse(contentAsString(get(app, email, path)))

  private def newRound(contest: ContestJury, extra: (String, String)*): Seq[(String, String)] = {
    val base = Seq("number" -> "0", "name" -> "Final", "contest" -> contest.getId.toString, "roles" -> "jury",
      "rates" -> "1", "minMpx" -> "No", "minSize" -> "No", "mediaType" -> "all",
      "submitToken" -> java.util.UUID.randomUUID().toString)
    base.filterNot(f => extra.exists(_._1 == f._1)) ++ extra
  }

  /** The step bar's items: (step, current, done, linked to). */
  private def steps(doc: Document): Seq[(String, Boolean, Boolean, Option[String])] =
    doc.select("#round-setup-steps li").asScala.toSeq.map { li =>
      (li.attr("data-step"), li.attr("aria-current") == "step", li.hasClass("wlx-setup-done"),
        Option(li.selectFirst("a")).map(_.attr("href")))
    }

  private def text(e: Element): String = Option(e).fold("")(_.text)

  /** "created" or "ready" as a word: a round being set up is neither until it opens to jurors. */
  private val CreatedOrReady = "(?i).*\\b(created|ready)\\b.*"

  "the new-round form" should {

    "be step 1, with one button, \"Save and find matching files\", and its help" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val form = page(app, "admin@example.com", s"/admin/rounds/edit?contestId=${contest.getId}")
        (form.select("#round-setup-steps").attr("data-page") === "1") and
          (steps(form) === Seq(("1", true, false, None), ("2", false, false, None), ("3", false, false, None),
            ("open", false, false, None))) and
          (form.select("#round-setup-steps li").asScala.map(_.text).toSeq === Seq("1 Which files",
            "2 Check the matching files", "3 Jurors", "Open to jurors")) and
          (form.select("#round-form button[type=submit]").asScala.map(_.text).toSeq === Seq("Save and find matching files")) and
          (text(form.selectFirst("#round-form-next-help")) === "The app picks the contest’s files that match these " +
            "settings, for you to check. This can take a few minutes.")
      }
    }
  }

  "a saved new round" should {

    "land on step 2, saying so, with its step bar, its heading and Distribute as its main action" in {
      testDbApp { implicit app =>
        val contest = setUp()
        // two monuments: 2 of the contest's 8 files
        val saved = route(app, request(POST, "admin@example.com", "/admin/rounds/save",
          newRound(contest, "monumentIds" -> "80-361-0001, 80-361-0002"))).get
        status(saved)
        val round = Round.findByContest(contest.getId).head
        val review = Jsoup.parse(contentAsString(route(app, FakeRequest(GET, redirectLocation(saved).getOrElse(""))
          .withSession(Secured.UserName -> "admin@example.com").withFlash(flash(saved).data.toSeq: _*).withCSRFToken).get))
        val r = round.getId
        val main = review.selectFirst("#round-next-step a.btn-primary")
        (status(saved) === SEE_OTHER) and (redirectLocation(saved) must beSome(s"/admin/rounds/$r/review")) and
          (text(review.selectFirst("#flash-success")) ===
            "Round 1 is saved. Step 2 of 3: its matching files are being found. Check them, then choose jurors.") and
          (review.select("#round-setup-steps").attr("data-page") === "2") and
          (steps(review) === Seq(("1", false, true, Some(s"/admin/rounds/edit?id=$r&contestId=${contest.getId}")),
            ("2", true, true, None), ("3", false, false, Some(s"/admin/rounds/$r/jurors")), ("open", false, false, None))) and
          (review.selectFirst("#round-review-title").ownText === "Round 1 (Final) – step 2 of 3: check the matching files") and
          (review.select("#round-review-title a[data-help-topic=review]").size === 1) and
          (review.select("#round-review-match").first().tagName === "h3") and
          (text(review.selectFirst("#round-review-match")) === "2 files match (the contest has 8)") and
          (text(review.selectFirst("#round-next-step-text")) ===
            "Jurors can’t see this round yet. When the files look right, choose jurors and distribute to them.") and
          (text(main) === "Distribute to jurors") and (main.attr("href") === s"/admin/rounds/$r/jurors") and
          (text(review.selectFirst("#round-review")) must not(beMatching(CreatedOrReady)))
      }
    }
  }

  "a round whose files are being matched" should {

    "show the step-3 action disabled with its reason, not link step 3, and refuse Distribute" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val round = inReview(contest)
        matching(contest, round)
        val r = round.getId
        val review = page(app, "admin@example.com", s"/admin/rounds/$r/review")
        val jurors = page(app, "admin@example.com", s"/admin/rounds/$r/jurors")
        val list = page(app, "admin@example.com", s"/admin/rounds?contestId=${contest.getId}")
        val refused = route(app, request(POST, "admin@example.com", s"/admin/rounds/$r/distribute",
          Seq("jurorsShown" -> "true", "jurors[0]" -> jurorIds.head.toString, "distribution" -> "0"))).get
        (review.select("#round-next-step").attr("data-state") === "matching") and
          (review.select("button#round-next-distribute[disabled]").size === 1) and
          (review.select("a.round-next-distribute").size === 0) and
          (text(review.selectFirst("#round-next-distribute-reason")) === "Its matching files are still being found.") and
          (steps(review).find(_._1 == "3").flatMap(_._4) must beNone) and
          (review.select("#round-setup-steps").attr("data-step") === "2") and
          (review.select("#round-review-match").size === 0) and
          (text(jurors.selectFirst("#round-jurors-title")) === "Round 1 (Semi) – step 3 of 3: jurors") and
          (jurors.select("#round-setup-steps").attr("data-page") === "3") and
          (steps(jurors).find(_._1 == "2").flatMap(_._4) must beSome(s"/admin/rounds/$r/review")) and
          (text(list.selectFirst(s"tr[data-round=$r] .wlx-round-start-state")) === "Setting up – step 2 of 3: check files") and
          (list.select(s"tr[data-round=$r] a.round-start-distribute").size === 0) and
          (status(refused) === SEE_OTHER) and (flash(refused).get("error") must beSome) and
          (Round.findById(r).get.inReview must beTrue) and (RoundUser.byRoundId(r) must beEmpty)
      }
    }
  }

  "a round being set up" should {

    "show its step bar and current step on Edit round, its state and step link on statistics and conflicts" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val round = inReview(contest)
        val r = round.getId
        val edit = page(app, "admin@example.com", s"/admin/rounds/edit?id=$r&contestId=${contest.getId}")
        val stat = page(app, "admin@example.com", s"/roundstat/$r")
        val conflicts = page(app, "admin@example.com", s"/admin/rounds/$r/conflicts")
        (edit.select("#round-setup-steps").attr("data-page") === "1") and
          (steps(edit).take(3) === Seq(("1", true, true, None), ("2", false, true, Some(s"/admin/rounds/$r/review")),
            ("3", false, false, Some(s"/admin/rounds/$r/jurors")))) and
          (text(edit.selectFirst("#round-setup-state")) === "Setting up – step 3 of 3: choose jurors") and
          (text(stat.selectFirst("#round-setup-state")) === "Setting up – step 3 of 3: choose jurors · Distribute to jurors") and
          (stat.select("#round-setup-state-link").attr("href") === s"/admin/rounds/$r/jurors") and
          (stat.select("#round-review-link").size === 0) and
          (conflicts.select("#round-setup-state-link").attr("href") === s"/admin/rounds/$r/jurors")
      }
    }

    "show its organizer the state and the review page, no action and no step links; refuse its jurors every page" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val round = inReview(contest)
        val r = round.getId
        val review = page(app, "organizer@example.com", s"/admin/rounds/$r/review")
        val stat = page(app, "organizer@example.com", s"/roundstat/$r")
        val conflicts = page(app, "organizer@example.com", s"/admin/rounds/$r/conflicts")
        (review.select("#round-setup-steps").size === 1) and (review.select("#round-setup-steps a").size === 0) and
          (review.select(".round-next-distribute").size === 0) and
          (text(stat.selectFirst("#round-setup-state")) === "Setting up – step 3 of 3: choose jurors · Check the files") and
          (stat.select("#round-setup-state-link").attr("href") === s"/admin/rounds/$r/review") and
          (stat.select(s"a[href='/admin/rounds/$r/jurors']").size === 0) and
          (conflicts.select("#round-setup-state-link").attr("href") === s"/admin/rounds/$r/review") and
          (status(get(app, "organizer@example.com", s"/admin/rounds/$r/jurors")) === FORBIDDEN) and
          (status(get(app, "organizer@example.com", s"/admin/rounds/edit?id=$r&contestId=${contest.getId}")) === FORBIDDEN) and
          foreach(Seq(s"/admin/rounds/$r/review", s"/admin/rounds/$r/jurors", s"/roundstat/$r",
            s"/admin/rounds/edit?id=$r&contestId=${contest.getId}", s"/admin/rounds?contestId=${contest.getId}")) { path =>
            status(get(app, "juror1@example.com", path)) aka path must_== FORBIDDEN
          }
      }
    }

    "with no file matched, stay at step 2 everywhere, Distribute disabled" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val round = inReview(contest, "None", monuments = Some("99-999-9999"))
        val r = round.getId
        val review = page(app, "admin@example.com", s"/admin/rounds/$r/review")
        val stat = page(app, "admin@example.com", s"/roundstat/$r")
        val list = page(app, "admin@example.com", s"/admin/rounds?contestId=${contest.getId}")
        (text(review.selectFirst("#round-review-match")) === "0 files match (the contest has 8)") and
          (review.select("button#round-next-distribute[disabled]").size === 1) and
          (text(stat.selectFirst("#round-setup-state")) === "Setting up – step 2 of 3: check files · Check the files") and
          (list.select(s"tr[data-round=$r] a.round-start-check-files").attr("href") === s"/admin/rounds/$r/review")
      }
    }
  }

  "a round not set up step by step" should {

    "have no setup step: a job that opens it when done, a creation failed, cancelled or unfinished" in {
      val round = Round(Some(1L), 1, Some("R"), 1L, distributing = true, status = Some(Round.Status.Draft))
      def job(status: String, params: (String, play.api.libs.json.Json.JsValueWrapper)*) =
        services.jobs.Job(1L, RoundDistributionHandler.Kind, Some(1L), Some("round"), Some(1L), None, status,
          Json.obj(params: _*), Json.obj(), None, None, None, None, 0, None, None, java.time.Instant.now(), None, None,
          None, None)
      val fill: (String, Json.JsValueWrapper) = "mode" -> RoundDistributionHandler.Mode.Fill
      // a creation's fill that queues the distribute ("Distribute new files" reinstating an old creation, its Retry)
      val opensItself = job("running", fill, "distributeAfter" -> true)
      // a one-pass job from before P3 (no mode)
      val onePass = job("running")
      val cancelled = round.copy(status = Some(Round.Status.Cancelled))
      (RoundNextStep.of(round, Some(opensItself), 5) must beNone) and
        (services.distribution.RoundStartBlock.of(round, Some(opensItself)) must
          beSome(services.distribution.RoundStartBlock.Distributing: services.distribution.RoundStartBlock)) and
        (RoundNextStep.of(round, Some(onePass), 5) must beNone) and
        // a held fill running: matching, as the Rounds page's "selecting"
        (RoundNextStep.of(round, Some(job("running", fill, "distributeAfter" -> false)), 0).map(_.state) must
          beSome(RoundNextStep.Matching: RoundNextStep.State)) and
        (services.distribution.RoundStartBlock.of(round, Some(job("running", fill, "distributeAfter" -> false))) must
          beSome(services.distribution.RoundStartBlock.Selecting: services.distribution.RoundStartBlock)) and
        // no job running: failed, cancelled, unfinished (no job at all)
        (RoundNextStep.of(round, Some(job("failed", fill)), 5) must beNone) and
        (RoundNextStep.of(cancelled, Some(job("cancelled", fill)), 5) must beNone) and
        (RoundNextStep.of(round, None, 5) must beNone)
    }

    "show no setup state on its statistics while a job distributes it, as the Rounds page" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val round = roundDao.create(Round(None, 1, Some("Legacy"), contest.getId, active = false,
          distributing = true, status = Some(Round.Status.Draft)))
        scalikejdbc.DB.autoCommit { implicit s =>
          services.jobs.JobJdbc.insert(services.jobs.NewJob(RoundDistributionHandler.Kind, contest.id,
            Some(RoundDistributionHandler.SubjectType), round.id, Some(RoundDistributionHandler.dedupeKey(round.getId)),
            params = Json.obj("mode" -> RoundDistributionHandler.Mode.Fill, "distributeAfter" -> true)), owner = Some("spec"))
        }
        val r = round.getId
        val stat = page(app, "admin@example.com", s"/roundstat/$r")
        val edit = page(app, "admin@example.com", s"/admin/rounds/edit?id=$r&contestId=${contest.getId}")
        val list = page(app, "admin@example.com", s"/admin/rounds?contestId=${contest.getId}")
        (stat.select("#round-setup-state").size === 0) and
          (edit.select("#round-setup-steps, #round-setup-state").size === 0) and
          (list.select(s"tr[data-round=$r] .wlx-round-start-state").attr("data-state") === "distributing")
      }
    }
  }

  "a round's state" should {

    "be the same on the Rounds page, Edit round, statistics and the conflicts page, with the same action" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val failedReapply = inReview(contest, "Failed re-apply")
        endedJob(contest, failedReapply, "failed", Reapply: _*)
        val cancelledReapply = inReview(contest, "Cancelled re-apply")
        endedJob(contest, cancelledReapply, "cancelled", Reapply: _*)
        val failedDistribute = inReview(contest, "Failed distribute")
        endedJob(contest, failedDistribute, "failed", "mode" -> RoundDistributionHandler.Mode.Distribute)
        val list = page(app, "admin@example.com", s"/admin/rounds?contestId=${contest.getId}")
        def listed(r: Round) = text(list.selectFirst(s"tr[data-round=${r.getId}] .wlx-round-start-state"))
        def onPage(path: String) = text(page(app, "admin@example.com", path).selectFirst("#round-setup-state"))
        val expected = Seq(
          failedReapply -> "Setting up – step 2 of 3: re-applying its filters failed",
          cancelledReapply -> "Setting up – step 2 of 3: re-applying its filters was cancelled",
          failedDistribute -> "Setting up – step 3 of 3: choose jurors")
        foreach(expected) { case (r, state) =>
          val id = r.getId
          val stat = page(app, "admin@example.com", s"/roundstat/$id")
          val review = page(app, "admin@example.com", s"/admin/rounds/$id/review")
          val reapply = r.getId != failedDistribute.getId
          (listed(r) === state) and
            (onPage(s"/admin/rounds/edit?id=$id&contestId=${contest.getId}") === state) and
            (text(stat.selectFirst("#round-setup-state")) must startWith(state)) and
            (onPage(s"/admin/rounds/$id/conflicts") must startWith(state)) and
            (stat.select("#round-setup-state-link").attr("href") must contain(
              if (reapply) s"/admin/rounds/edit?id=$id" else s"/admin/rounds/$id/jurors")) and
            // Distribute: disabled with its reason after a re-apply that didn't finish, offered after a failed distribute
            (review.select("button#round-next-distribute[disabled]").size === (if (reapply) 1 else 0)) and
            (review.select("a#round-review-distribute-link").size === (if (reapply) 0 else 1))
        } and {
          // offered == allowed: the failed distribute's Distribute distributes it again; a failed re-apply's is refused
          def post(r: Round) = route(app, request(POST, "admin@example.com", s"/admin/rounds/${r.getId}/distribute",
            Seq("jurorsShown" -> "true", "jurors[0]" -> jurorIds.head.toString, "distribution" -> "0"))).get
          val refused = post(failedReapply)
          val again = post(failedDistribute)
          (flash(refused).get("error") must beSome(contain("re-apply"))) and
            (redirectLocation(again) must beSome(s"/admin/rounds/${failedDistribute.getId}/review")) and
            (flash(again).get("error") must beNone)
        }
      }
    }

    "say a creation that failed, didn't finish or opens the round by itself on its review page and jurors form as the Rounds page" in {
      testDbApp { implicit app =>
        val contest = setUp()
        def draft(name: String) = roundDao.create(Round(None, 0, Some(name), contest.getId, active = false,
          distributing = true, status = Some(Round.Status.Draft)))
        val failed = draft("Failed")
        endedJob(contest, failed, "failed", "mode" -> RoundDistributionHandler.Mode.Fill, "distributeAfter" -> false)
        val unfinished = draft("Unfinished")
        val opensItself = draft("Opens itself")
        scalikejdbc.DB.autoCommit { implicit s =>
          services.jobs.JobJdbc.insert(services.jobs.NewJob(RoundDistributionHandler.Kind, contest.id,
            Some(RoundDistributionHandler.SubjectType), opensItself.id, Some(RoundDistributionHandler.dedupeKey(opensItself.getId)),
            params = Json.obj("mode" -> RoundDistributionHandler.Mode.Fill, "distributeAfter" -> true)), owner = Some("spec"))
        }
        val list = page(app, "admin@example.com", s"/admin/rounds?contestId=${contest.getId}")
        def listed(r: Round) = text(list.selectFirst(s"tr[data-round=${r.getId}] .wlx-round-start-state"))
        def review(r: Round) = text(page(app, "admin@example.com", s"/admin/rounds/${r.getId}/review").selectFirst("#round-review-state"))
        def jurors(r: Round) = page(app, "admin@example.com", s"/admin/rounds/${r.getId}/jurors")
        (review(failed) === listed(failed)) and (listed(failed) === "Not started – its distribution failed") and
          (review(unfinished) === listed(unfinished)) and
          (review(opensItself) ===
            "The images are being distributed to the jurors. The round opens to them when it has finished.") and
          (text(jurors(failed).selectFirst("#round-jurors-state")) === listed(failed)) and
          (text(jurors(unfinished).selectFirst("#round-jurors-state")) === listed(unfinished)) and
          (jurors(unfinished).select("#round-jurors-filling, meta[http-equiv=refresh]").size === 0) and
          (jurors(opensItself).select("#round-jurors-filling").size === 0) and
          (jurors(opensItself).select("#round-jurors-not-in-review").size === 1)
      }
    }
  }

  "the matching files' heading" should {

    "say N by its plural form, true also when N exceeds the contest's files" in {
      testDbApp { implicit app =>
        val api = app.injector.instanceOf[play.api.i18n.MessagesApi]
        def heading(lang: String, n: Int, m: Long) = {
          val messages = api.preferred(Seq(play.api.i18n.Lang(lang)))
          messages(s"round.review.match.${views.Plural.form(n, lang)}", n, m)
        }
        (heading("en", 1, 40) === "1 file matches (the contest has 40)") and
          (heading("en", 45, 40) === "45 files match (the contest has 40)") and
          (heading("ru", 1, 40) === "Подходит 1 файл (всего в конкурсе 40)") and
          (heading("ru", 22, 40) === "Подходят 22 файла (всего в конкурсе 40)") and
          (heading("ru", 45, 40) === "Подходят 45 файлов (всего в конкурсе 40)") and
          (heading("uk", 21, 40) === "Відповідає 21 файл (усього в конкурсі 40)") and
          (heading("fr", 1, 40) === "1 fichier correspond (le concours en a 40)")
      }
    }
  }

  "an open round" should {

    "show no setup step anywhere, keep Start/Stop, and have its edit save confirmed on the Rounds page" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val round = open(contest)
        val r = round.getId
        val edit = page(app, "admin@example.com", s"/admin/rounds/edit?id=$r&contestId=${contest.getId}")
        val stat = page(app, "admin@example.com", s"/roundstat/$r")
        val review = page(app, "admin@example.com", s"/admin/rounds/$r/review")
        val saved = route(app, request(POST, "admin@example.com", "/admin/rounds/save",
          Seq("id" -> r.toString, "number" -> round.number.toString, "name" -> "Renamed", "contest" -> contest.getId.toString,
            "roles" -> "jury", "distribution" -> "0", "rates" -> round.rates.id.toString, "minMpx" -> "No",
            "minSize" -> "No", "mediaType" -> "all"))).get
        val list = Jsoup.parse(contentAsString(route(app, FakeRequest(GET, redirectLocation(saved).getOrElse(""))
          .withSession(Secured.UserName -> "admin@example.com").withFlash(flash(saved).data.toSeq: _*).withCSRFToken).get))
        (round.active must beTrue) and (round.distributing must beFalse) and
          (edit.select("#round-setup-steps, #round-setup-state, #round-next-step").size === 0) and
          (stat.select("#round-setup-state").size === 0) and
          (review.select("#round-setup-steps").size === 0) and
          (review.selectFirst("#round-review-title").ownText === "Round 1 (Open): its images before distribution") and
          (review.select("#round-review-title a[data-help-topic=review]").size === 1) and
          (status(saved) === SEE_OTHER) and
          (text(list.selectFirst("#flash-success")) === "Round 1 (Renamed) is saved.") and
          (list.select(s"#set-active-form-$r").size === 1) and
          (list.select(s"tr[data-round=$r] .wlx-round-start-state").size === 0)
      }
    }
  }
}
