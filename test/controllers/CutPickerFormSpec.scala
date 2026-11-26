package controllers

import controllers.CsrfTestApp.{token => csrfToken, withCsrfApp}
import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image}
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import services.TestRoundServices

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** The round form's one cut control, the cut picker (regional winners plan §10.4 place 1, §10.9 "Round
  * form (SN)"; decided 2026-11-26): no free number box and no threshold field; "Choose N" posts the form
  * to the preview, which saves nothing and lists every valid cut ("N / score"), the candidates labelled,
  * "No top N" always, and for a rated source what the round keeps; a save refuses an N inside a tie next
  * to its field, and stores a valid cut as kept (a threshold or the top N, never both), with how it was
  * chosen in the round's settings log; the round takes the picker's first N files. Legacy rounds: a
  * started round's cut read only, a re-apply's held cut preselected or explained. The link to the rating
  * view only where it shows the same cut, and not to a juror of the open previous round; the rating
  * view's divider, its page, and its authorization unchanged. Only the contest's admins reach the
  * preview (an organizer, a juror and another contest's admin are refused).
  */
class CutPickerFormSpec extends PlaySpecification with PlayTestDb {

  sequential

  /** A contest with files 1–8, a stopped rated 1–10 round of three jurors whose averages are 9, 8,
    * 8, 8, 7, 6, 6, 5 (ties at places 2–4 and 6–7), an organizer, an admin, and another contest's
    * admin. The valid values are 1, 4, 5, 7 and 8.
    */
  private def setUp(): (ContestJury, Round) = {
    val contest = contestDao.create(None, "WLM", 2026, "Ukraine")
    TestDb.setSource(contest.getId, Some("Category:WLM images"))
    val other = contestDao.create(None, "WLE", 2026, "Ukraine")
    val imgs = (1L to 8L).map(i => Image(i, s"File:Image$i.jpg", None, None, 3000, 2000, Some(s"80-361-000$i")))
    imageDao.batchInsert(imgs)
    CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, imgs)
    def member(email: String, role: String, c: ContestJury = contest) =
      User.create(User(email, email, None, Set(role), Some(services.PasswordHasher.default.hash("secret")), c.id))
    val jurors = Seq("juror1", "juror2", "juror3").map(j => member(s"$j@example.com", "jury"))
    member("organizer@example.com", "organizer")
    member("admin@example.com", "admin")
    member("other-admin@example.com", "admin", other)
    val prev = roundDao.create(Round(None, 1, Some("Rated"), contest.getId, rates = Round.ratesById(10), active = false))
    prev.addUsers(jurors.map(u => RoundUser(prev.getId, u.getId, "jury", active = true)))
    for ((page, rate) <- Seq(1L -> 9, 2L -> 8, 3L -> 8, 4L -> 8, 5L -> 7, 6L -> 6, 7L -> 6, 8L -> 5); j <- jurors)
      VoteJdbc.create(page, rate, j.getId, prev.getId)
    (contestDao.findById(contest.getId).get, roundDao.findById(prev.getId).get)
  }

  private val Valid = Seq("1", "4", "5", "7", "8")

  private def request(method: String, email: String, path: String, body: Seq[(String, String)]) =
    FakeRequest(method, path).withFormUrlEncodedBody(body: _*).withSession(Secured.UserName -> email).withCSRFToken

  private def post(app: Application, email: String, path: String, body: (String, String)*): Future[Result] =
    route(app, request(POST, email, path, body)).get

  private def preview(app: Application, email: String, body: (String, String)*): Future[Result] =
    post(app, email, "/admin/rounds/cut-preview", body: _*)

  private def save(app: Application, email: String, body: (String, String)*): Future[Result] =
    post(app, email, "/admin/rounds/save", body: _*)

  private def newRound(contest: ContestJury, prev: Round, extra: (String, String)*): Seq[(String, String)] = {
    val base = Seq("number" -> "0", "name" -> "Final", "contest" -> contest.getId.toString, "roles" -> "jury",
      "rates" -> "1", "minMpx" -> "No", "minSize" -> "No", "mediaType" -> "all",
      "submitToken" -> java.util.UUID.randomUUID().toString, "previousRound[0]" -> prev.getId.toString)
    base.filterNot(f => extra.exists(_._1 == f._1)) ++ extra
  }

  private def doc(result: Future[Result]): Document = Jsoup.parse(contentAsString(result))

  private def options(page: Document): Seq[String] =
    page.select("select#topImages option").asScala.toSeq.map(_.attr("value"))

  private def selected(page: Document): Seq[String] =
    page.select("select#topImages option[selected]").asScala.toSeq.map(_.attr("value"))

  /** The control's cuts (its options with a value), by N. */
  private def cuts(page: Document): Seq[String] = options(page).filter(_.nonEmpty).sortBy(_.toInt)

  private def saved(contest: ContestJury, prev: Round): Round = Round.findByContest(contest.getId).find(_.id != prev.id).get

  "the new-round form" should {

    "have no free top-N box, but the stored N (none) and \"Choose N\" posting to the preview" in {
      testDbApp { implicit app =>
        val (contest, _) = setUp()
        val page = doc(route(app, request(GET, "admin@example.com", s"/admin/rounds/edit?contestId=${contest.getId}",
          Nil)).get)
        (page.select("input[name=topImages][type=number]").size === 0) and
          (page.select("select#topImages").size === 0) and
          (page.select("#topImages_field #topImages").text === "No top N: all matching files") and
          (page.select("#topImages-choose").attr("formaction") must contain("/admin/rounds/cut-preview")) and
          // no target (decided 2026-11-26): the admin picks from the list
          (page.select("#topImagesTarget").size === 0)
      }
    }
  }

  "\"Choose N\" (the preview)" should {

    "list every valid cut as N and score, the candidates labelled, no top N chosen, and save nothing" in {
      testDbApp { implicit app =>
        val (contest, prev) = setUp()
        val result = preview(app, "admin@example.com", newRound(contest, prev): _*)
        val page = doc(result)
        def text(n: String) = page.select(s"select#topImages option[value=$n]").text
        (status(result) === OK) and (header("Cache-Control", result) must beSome("no-store")) and
          (cuts(page) === Valid) and (options(page).head === "") and (selected(page) === Seq("")) and
          (page.select("select#topImages > option").text === "No top N: all matching files") and
          (text("4") === "top 4 · score ≥ 8.0") and (text("1") === "top 1 · score ≥ 9.0") and
          // no drop stands out (every gap 1): all candidates only, labelled
          (page.select("select#topImages optgroup[label=Candidate cuts] option").asScala.map(_.attr("value")).toSeq === Seq("8")) and
          (text("8") === "top 8 · score ≥ 5.0 · all candidates") and
          // a rated source: the round keeps the threshold by default
          (page.select("input[name=cutKeep][checked]").attr("value") === "threshold") and
          // the compact box: no gallery, no separate threshold field, no target
          (page.select("#cut-gallery, [name=cutPick], [name=cutShow], #topImagesTarget, input[name=minAvgRate]").size === 0) and
          (Round.findByContest(contest.getId).size === 1)
      }
    }

    "preselect a posted valid cut as kept; explain a posted N inside a tie and keep it for the save to refuse" in {
      testDbApp { implicit app =>
        val (contest, prev) = setUp()
        val picked = doc(preview(app, "admin@example.com", newRound(contest, prev, "topImages" -> "4", "cutKeep" -> "top"): _*))
        val crafted = doc(preview(app, "admin@example.com", newRound(contest, prev, "topImages" -> "3"): _*))
        (selected(picked) === Seq("4")) and (picked.select("input[name=cutKeep][checked]").attr("value") === "top") and
          (selected(crafted) === Seq("3")) and (options(crafted).take(2) === Seq("3", "")) and
          (crafted.select("#top-n-held").text === "N = 3 is inside a tie (places 2–4); choose 1 or 4.")
      }
    }

    "keep a posted N when no file matches the filters, with \"No top N\" to pick" in {
      testDbApp { implicit app =>
        val (contest, prev) = setUp()
        val body = newRound(contest, prev, "regions[0]" -> "99", "topImages" -> "4")
        val page = doc(preview(app, "admin@example.com", body: _*))
        val refused = save(app, "admin@example.com", body: _*)
        val refusedStatus = status(refused)
        val none = status(save(app, "admin@example.com", newRound(contest, prev, "regions[0]" -> "99", "topImages" -> ""): _*))
        (options(page) === Seq("4", "")) and (selected(page) === Seq("4")) and
          (page.select("#topImages_help").text must contain("no cut to choose")) and
          (refusedStatus === BAD_REQUEST) and
          (doc(refused).select("#topImages_field .invalid-feedback").text must contain("no cut to choose")) and
          (none === SEE_OTHER) and (saved(contest, prev).topImages must beNone) and
          (saved(contest, prev).prevMinAvgRate must beNone)
      }
    }

    "answer 403 for another contest's round and 404 for a missing one" in {
      testDbApp { implicit app =>
        val (contest, prev) = setUp()
        val third = contestDao.create(None, "WLM", 2027, "Ukraine")
        val elsewhere = roundDao.create(Round(None, 1, Some("Elsewhere"), third.getId, rates = Round.ratesById(10),
          previous = Some(prev.getId.toString)))
        val other = preview(app, "admin@example.com", newRound(contest, prev, "id" -> elsewhere.getId.toString): _*)
        val missing = preview(app, "admin@example.com", newRound(contest, prev, "id" -> "999999"): _*)
        (status(other) === FORBIDDEN) and (contentAsString(other) must not(contain("cut-gallery"))) and
          (status(missing) === NOT_FOUND)
      }
    }

    "be refused to an organizer, a juror and another contest's admin; signed out, the login" in {
      testDbApp { implicit app =>
        val (contest, prev) = setUp()
        val body = newRound(contest, prev)
        val signedOut = route(app, FakeRequest(POST, "/admin/rounds/cut-preview").withFormUrlEncodedBody(body: _*)
          .withCSRFToken).get
        val script = route(app, FakeRequest(POST, "/admin/rounds/cut-preview").withFormUrlEncodedBody(body: _*)
          .withHeaders("X-Requested-With" -> "XMLHttpRequest").withCSRFToken).get
        (status(preview(app, "organizer@example.com", body: _*)) === FORBIDDEN) and
          (status(preview(app, "juror1@example.com", body: _*)) === FORBIDDEN) and
          (status(preview(app, "other-admin@example.com", body: _*)) === FORBIDDEN) and
          (status(signedOut) === SEE_OTHER) and (status(script) === UNAUTHORIZED) and
          (contentAsString(preview(app, "organizer@example.com", body: _*)) must not(contain("cut-gallery")))
      }
    }
  }

  "the save" should {

    "refuse a crafted N inside a tie next to its field, with the valid values loaded, and save nothing" in {
      testDbApp { implicit app =>
        val (contest, prev) = setUp()
        val result = save(app, "admin@example.com", newRound(contest, prev, "topImages" -> "3"): _*)
        val page = doc(result)
        (status(result) === BAD_REQUEST) and
          (page.select("#topImages_field .invalid-feedback").text ===
            "N = 3 is inside a tie (places 2–4); choose 1 or 4.") and
          (page.select("#round-form-errors a[href=#topImages_field]").size === 1) and
          // the refused N kept in the placeholder (posted again, refused again: never dropped), the cuts loaded
          (options(page).take(2) === Seq("3", "")) and (cuts(page).filterNot(_ == "3") === Valid) and
          (selected(page) === Seq("3")) and (page.select("#top-n-held").size === 0) and
          (Round.findByContest(contest.getId).size === 1)
      }
    }

    "accept exactly the N values the preview offers, and refuse every other one (offered == allowed)" in {
      testDbApp { implicit app =>
        val (contest, prev) = setUp()
        val offered = cuts(doc(preview(app, "admin@example.com", newRound(contest, prev): _*)))
        // one save per N, awaited before the next: 0 to m + 1
        val accepted = (0 to 9).filter { n =>
          status(save(app, "admin@example.com", newRound(contest, prev, "topImages" -> n.toString): _*)) == SEE_OTHER
        }.map(_.toString)
        (offered === Valid) and (accepted === Valid)
      }
    }

    "save a valid N: the round takes the picker's first N, how it was chosen in its settings log" in {
      testDbApp { implicit app =>
        val (contest, prev) = setUp()
        val result = save(app, "admin@example.com", newRound(contest, prev, "topImages" -> "4"): _*)
        val saved = status(result)
        val created = Round.findByContest(contest.getId).find(_.id != prev.id).get
        val entry = ContestChangeJdbc.findByRound(contest.getId, created.getId, 10)
          .find(_.kind == ContestChangeJdbc.RoundCreated).get
        val basis = (entry.detail \ "topImagesBasis").as[play.api.libs.json.JsObject]
        val queued = services.jobs.JobJdbc.latestBySubjects(services.distribution.RoundDistributionHandler.Kind,
          services.distribution.RoundDistributionHandler.SubjectType, Seq(created.getId)).get(created.getId).get
        (saved === SEE_OTHER) and (created.topImages must beSome(4)) and
          // checked again by the job's Rank step, the votes frozen (the backstop)
          ((queued.params \ services.distribution.RoundDistributionHandler.TopNCheckedParam).asOpt[Boolean] must beSome(true)) and
          (RoundImageJdbc.countIn(created.getId) === 4L) and
          ((basis \ "chosen").as[Int] === 4) and ((basis \ "target").toOption must beNone) and
          ((basis \ "files").as[Int] === 8) and
          (created.prevMinAvgRate must beNone) and
          (views.RoundChangeText.items(entry)(play.api.i18n.MessagesImpl(play.api.i18n.Lang("en"),
            app.injector.instanceOf[play.api.i18n.MessagesApi])).find(_.field == "topImages").flatMap(_.note) must
            beSome(contain("chosen from the list")))
      }
    }

    "keep a valid cut as its threshold when asked, never both: the same files, no tie check in the job" in {
      testDbApp { implicit app =>
        val (contest, prev) = setUp()
        // a crafted threshold posted beside it is not kept too
        val result = status(save(app, "admin@example.com",
          newRound(contest, prev, "topImages" -> "4", "cutKeep" -> "threshold", "minAvgRate" -> "1"): _*))
        val created = saved(contest, prev)
        val entry = ContestChangeJdbc.findByRound(contest.getId, created.getId, 10)
          .find(_.kind == ContestChangeJdbc.RoundCreated).get
        val basis = (entry.detail \ "topImagesBasis").as[play.api.libs.json.JsObject]
        val queued = services.jobs.JobJdbc.latestBySubjects(services.distribution.RoundDistributionHandler.Kind,
          services.distribution.RoundDistributionHandler.SubjectType, Seq(created.getId)).get(created.getId).get
        val note = views.RoundChangeText.items(entry)(play.api.i18n.MessagesImpl(play.api.i18n.Lang("en"),
          app.injector.instanceOf[play.api.i18n.MessagesApi])).find(_.field == "prevMinAvgRate").flatMap(_.note)
        (result === SEE_OTHER) and (created.topImages must beNone) and
          (created.prevMinAvgRate must beSome(BigDecimal(8))) and (RoundImageJdbc.countIn(created.getId) === 4L) and
          ((queued.params \ services.distribution.RoundDistributionHandler.TopNCheckedParam).toOption must beNone) and
          ((basis \ "chosen").as[Int] === 4) and ((basis \ "threshold").as[BigDecimal] === BigDecimal(8)) and
          (note must beSome(contain("4 files when saved")))
      }
    }

    "keep any posted threshold alone (it can't split a tie)" in {
      testDbApp { implicit app =>
        val (contest, prev) = setUp()
        val result = status(save(app, "admin@example.com", newRound(contest, prev, "minAvgRate" -> "7.25"): _*))
        val created = saved(contest, prev)
        (result === SEE_OTHER) and (created.prevMinAvgRate must beSome(BigDecimal("7.25"))) and
          (created.topImages must beNone) and (RoundImageJdbc.countIn(created.getId) === 4L)
      }
    }

    "not store a threshold posted alone from a yes/no head previous round, nor without previous rounds" in {
      testDbApp { implicit app =>
        val (contest, prev) = setUp()
        val binary = roundDao.create(Round(None, 3, Some("Yes/no"), contest.getId, rates = Round.binaryRound, active = false))
        val fromBinary = status(save(app, "admin@example.com",
          newRound(contest, prev, "name" -> "From yes/no", "previousRound[0]" -> binary.getId.toString, "minAvgRate" -> "7.25"): _*))
        val withoutPrevious = status(save(app, "admin@example.com",
          newRound(contest, prev, "name" -> "No previous", "minAvgRate" -> "7.25").filterNot(_._1 == "previousRound[0]"): _*))
        def named(name: String) = Round.findByContest(contest.getId).find(_.name.contains(name)).get
        (fromBinary === SEE_OTHER) and (named("From yes/no").prevMinAvgRate must beNone) and
          (named("From yes/no").previousIds === Seq(binary.getId)) and
          (withoutPrevious === SEE_OTHER) and (named("No previous").prevMinAvgRate must beNone) and
          (named("No previous").previousIds must beEmpty)
      }
    }
  }

  "an existing round's top N" should {

    "show a stored N read only, even one inside a tie, while its filters aren't edited; the preview sends it back" in {
      testDbApp { implicit app =>
        val (contest, prev) = setUp()
        val stored = roundDao.create(Round(None, 2, Some("Old"), contest.getId, rates = Round.ratesById(10),
          active = true, previous = Some(prev.getId.toString), topImages = Some(3)))
        val page = doc(route(app, request(GET, "admin@example.com",
          s"/admin/rounds/edit?id=${stored.getId}&contestId=${contest.getId}", Nil)).get)
        val sent = preview(app, "admin@example.com", newRound(contest, prev, "id" -> stored.getId.toString,
          "number" -> "2"): _*)
        (page.select("#topImages_field input#topImages.wlx-locked[disabled]").attr("value") === "3") and (page.select("select#topImages").size === 0) and
          (page.select("#topImages-choose").size === 0) and
          (status(sent) === SEE_OTHER) and (redirectLocation(sent).get must contain(s"id=${stored.getId}"))
      }
    }

    "ignore a crafted N posted on a plain edit of a started round" in {
      testDbApp { implicit app =>
        val (contest, prev) = setUp()
        val stored = roundDao.create(Round(None, 2, Some("Old"), contest.getId, rates = Round.ratesById(10),
          active = true, previous = Some(prev.getId.toString), topImages = Some(3)))
        val edit = Seq("id" -> stored.getId.toString, "number" -> "2", "name" -> "Old renamed",
          "contest" -> contest.getId.toString, "roles" -> "jury", "distribution" -> "0", "rates" -> "10",
          "minMpx" -> "No", "minSize" -> "No", "mediaType" -> "all", "previousRound[0]" -> prev.getId.toString,
          "topImages" -> "4")
        val result = save(app, "admin@example.com", edit: _*)
        (status(result) === SEE_OTHER) and (roundDao.findById(stored.getId).get.topImages must beSome(3))
      }
    }

    "show a started round's stored threshold, or both, read only" in {
      testDbApp { implicit app =>
        val (contest, prev) = setUp()
        val both = roundDao.create(Round(None, 2, Some("Old"), contest.getId, rates = Round.ratesById(10),
          active = true, previous = Some(prev.getId.toString), topImages = Some(5), prevMinAvgRate = Some(BigDecimal("7.5"))))
        val page = doc(route(app, request(GET, "admin@example.com",
          s"/admin/rounds/edit?id=${both.getId}&contestId=${contest.getId}", Nil)).get)
        (page.select("input#topImages.wlx-locked[disabled]").attr("value") === "5") and
          (page.select("input#minAvgRate.wlx-locked[disabled]").attr("value") === "7.5") and
          (page.select("#topImages-choose").size === 0)
      }
    }

    "on a re-apply, preselect a held threshold's cut, explain a legacy round's two cuts and require one" in {
      testDbApp { implicit app =>
        val (contest, prev) = setUp()
        val held = TestRoundServices.roundService().submit(Round(None, 0, Some("Semi"), contest.getId, active = true,
          rates = Round.ratesById(10), previous = Some(prev.getId.toString), topImages = Some(4)), Nil, None,
          review = true).round
        // a legacy round's both
        scalikejdbc.DB.autoCommit { implicit s =>
          scalikejdbc.SQL("UPDATE round SET top_images = 5, prev_min_avg_rate = 7.5 WHERE id = ?").bind(held.getId).update()
        }
        val edit = Seq("id" -> held.getId.toString, "number" -> held.number.toString, "name" -> "Semi",
          "contest" -> contest.getId.toString, "roles" -> "jury", "distribution" -> "0", "rates" -> "10",
          "minMpx" -> "No", "minSize" -> "No", "mediaType" -> "all", "reapply" -> "true",
          "previousRound[0]" -> prev.getId.toString)
        val form = doc(route(app, request(GET, "admin@example.com",
          s"/admin/rounds/edit?id=${held.getId}&contestId=${contest.getId}", Nil)).get)
        val both = edit ++ Seq("topImages" -> "5", "minAvgRate" -> "7.5")
        val explained = doc(preview(app, "admin@example.com", both: _*))
        val refused = save(app, "admin@example.com", both: _*)
        val refusedStatus = status(refused)
        val threshold = doc(preview(app, "admin@example.com", (edit :+ ("minAvgRate" -> "7.5")): _*))
        // picked as a threshold: the held 7.5 kept (it takes the same 4 files)
        val reapplied = status(save(app, "admin@example.com",
          (edit ++ Seq("topImages" -> "4", "cutKeep" -> "threshold", "cutHeldRate" -> "7.5")): _*))
        val after = roundDao.findById(held.getId).get
        (form.select("#topImages_field input[type=hidden][name=topImages]").attr("value") === "5") and
          (form.select("#topImages_field input[type=hidden][name=minAvgRate]").attr("value") === "7.5") and
          (explained.select("#top-n-held").text ===
            "This round kept both the top 5 and the files scoring at least 7.5 (4 files now). A round keeps one cut now: choose it.") and
          (explained.select("select#topImages[required]").size === 1) and (selected(explained) === Seq("")) and
          (refusedStatus === BAD_REQUEST) and
          (doc(refused).select("#topImages_field .invalid-feedback").text must contain("A round keeps one cut now")) and
          (selected(threshold) === Seq("4")) and (threshold.select("input[name=cutKeep][checked]").attr("value") === "threshold") and
          (threshold.select("input[name=cutHeldRate]").attr("value") === "7.5") and
          (reapplied === SEE_OTHER) and (after.topImages must beNone) and
          (after.prevMinAvgRate must beSome(BigDecimal("7.5")))
      }
    }

    "refuse a re-apply with a stored N inside a tie, until a valid N is chosen" in {
      testDbApp { implicit app =>
        val (contest, prev) = setUp()
        // held with a valid N, then a stored N inside a tie (a round's N from before the picker: the job's
        // backstop refuses a fill with one)
        val held = TestRoundServices.roundService().submit(Round(None, 0, Some("Semi"), contest.getId, active = true,
          rates = Round.ratesById(10), previous = Some(prev.getId.toString), topImages = Some(4)), Nil, None,
          review = true).round
        scalikejdbc.DB.autoCommit { implicit s =>
          scalikejdbc.SQL("UPDATE round SET top_images = 3 WHERE id = ?").bind(held.getId).update()
        }
        val edit = Seq("id" -> held.getId.toString, "number" -> held.number.toString, "name" -> "Semi",
          "contest" -> contest.getId.toString, "roles" -> "jury", "distribution" -> "0", "rates" -> "10",
          "minMpx" -> "No", "minSize" -> "No", "mediaType" -> "all", "reapply" -> "true",
          "previousRound[0]" -> prev.getId.toString)
        val form = doc(route(app, request(GET, "admin@example.com",
          s"/admin/rounds/edit?id=${held.getId}&contestId=${contest.getId}", Nil)).get)
        val refused = save(app, "admin@example.com", (edit :+ ("topImages" -> "3")): _*)
        val reapplied = save(app, "admin@example.com", (edit :+ ("topImages" -> "4")): _*)
        (form.select("#topImages_field input[type=hidden][name=topImages]").attr("value") === "3") and
          (form.select("#topImages-choose").size === 1) and
          (status(refused) === BAD_REQUEST) and
          (doc(refused).select("#topImages_field .invalid-feedback").text must contain("inside a tie")) and
          (status(reapplied) === SEE_OTHER) and (roundDao.findById(held.getId).get.topImages must beSome(4))
      }
    }
  }

  "the link to the previous round's rating view" should {

    def link(page: Document): Seq[String] = page.select("a#top-n-cut-link").asScala.toSeq.map(_.attr("href"))
    def hrefs(page: Document): Seq[String] =
      page.select("select#topImages option[data-cut-href]").asScala.toSeq.map(_.attr("data-cut-href"))

    "follow every cut in the simple case: one previous round, no filter, a viewer who sees its votes" in {
      testDbApp { implicit app =>
        val (contest, prev) = setUp()
        val page = doc(preview(app, "admin@example.com", newRound(contest, prev, "topImages" -> "4", "cutKeep" -> "top"): _*))
        (link(page) === Seq(s"/byrate/round/${prev.getId}/user/0/page/1?sort=rate&cut=4#cut")) and
          (page.select("a#top-n-cut-link[target=_blank][rel=noopener]:not([hidden])").size === 1) and
          (hrefs(page).size === Valid.size)
      }
    }

    "be absent for several previous rounds and for each filter the rating view doesn't apply" in {
      testDbApp { implicit app =>
        val (contest, prev) = setUp()
        val second = roundDao.create(Round(None, 3, Some("Rated 2"), contest.getId, rates = Round.ratesById(10), active = false))
        val other = TestRoundServices.roundService().submit(Round(None, 0, Some("Best"), contest.getId, active = true,
          rates = Round.ratesById(10), previous = Some(prev.getId.toString), topImages = Some(1)), Nil, None,
          review = true).round
        // (a category filter reads Commons: CutPickerViewSpec covers it, with the settings half of the rule)
        val filters = Seq(Seq("previousRound[1]" -> second.getId.toString), Seq("regions[0]" -> "80"),
Seq("excludeRounds[0]" -> other.getId.toString),
          Seq("minMpx" -> "2"), Seq("minSize" -> "1"), Seq("mediaType" -> "image"),
          Seq("minJurors" -> "1"), Seq("rejectedMode" -> "exclude", "rejectedBy" -> "1"), Seq("monumentIds" -> "80-361-0001"))
        forall(filters) { extra =>
          val result = preview(app, "admin@example.com", newRound(contest, prev, extra: _*): _*)
          (extra -> status(result)) === (extra -> OK) and ((extra -> link(doc(result))) === (extra -> Nil))
        }
      }
    }

    "be there with conflicts of interest left out (the default) when none has a file there, and not when one has" in {
      testDbApp { implicit app =>
        val (contest, prev) = setUp()
        val body = newRound(contest, prev, "topImages" -> "4", "excludeCoi" -> "true")
        val clean = doc(preview(app, "admin@example.com", body: _*))
        scalikejdbc.DB.autoCommit { implicit s =>
          scalikejdbc.SQL("UPDATE images SET author = 'Coiauthor' WHERE page_id = 1").update()
        }
        User.setWikiAccount(User.findByEmail("juror1@example.com").head.getId, Some("Coiauthor"))
        val conflicted = doc(preview(app, "admin@example.com", body: _*))
        (link(clean).size === 1) and (link(conflicted) === Nil) and (cuts(conflicted).nonEmpty must beTrue)
      }
    }

    "be absent for a juror of the open previous round (others' votes hidden there), the cuts still listed" in {
      testDbApp { implicit app =>
        val (contest, prev) = setUp()
        val admin = User.findByEmail("admin@example.com").head
        prev.addUsers(Seq(RoundUser(prev.getId, admin.getId, "jury", active = true)))
        Round.setActive(prev.getId, active = true)
        val page = doc(preview(app, "admin@example.com", newRound(contest, prev, "topImages" -> "4"): _*))
        (link(page) === Nil) and (hrefs(page) === Nil) and (cuts(page) === Valid)
      }
    }
  }

  "the rating view's cut" should {

    def byRate(app: Application, email: String, round: Round, query: String, page: Int = 1): Future[Result] =
      route(app, request(GET, email, s"/byrate/round/${round.getId}/user/0/page/$page$query", Nil)).get

    def divider(page: Document): Option[(String, Int)] =
      Option(page.selectFirst("li.wlx-cut-divider")).map(d =>
        d.text -> d.parent.children.asScala.indexWhere(_ == d))

    "show the divider after file N, named as the control names it, only at a cut" in {
      testDbApp { implicit app =>
        val (_, prev) = setUp()
        val at4 = doc(byRate(app, "admin@example.com", prev, "?sort=rate&cut=4"))
        val inTie = doc(byRate(app, "admin@example.com", prev, "?sort=rate&cut=3"))
        val text = doc(byRate(app, "admin@example.com", prev, "?sort=rate&cut=zz%3Cscript%3E"))
        (divider(at4) === Some("Cut: top 4 · score ≥ 8.0" -> 4)) and
          (at4.select("li.wlx-cut-divider[data-file-anchor=cut]").size === 1) and
          (divider(inTie) must beNone) and (divider(text) must beNone) and
          (contentAsString(byRate(app, "admin@example.com", prev, "?sort=rate&cut=zz%3Cscript%3E")) must not(contain("zz<script>")))
      }
    }

    "show it on the page of file N only" in {
      testDbApp { implicit app =>
        val (contest, prev) = setUp()
        // 20 files more, each scoring less than the last: places 9 to 28
        val more = (9L to 28L).map(i => Image(i, s"File:More$i.jpg", None, None, 3000, 2000, Some(s"80-361-00$i")))
        imageDao.batchInsert(more)
        val jurors = User.findByContest(contest.getId).filter(_.roles.contains("jury"))
        for (img <- more; j <- jurors) VoteJdbc.create(img.pageId, if (img.pageId <= 18) 4 else 2, j.getId, prev.getId)
        // 8 + 10 files score at least 4: the cut 18 is on page 2, its 3rd file
        val url = services.CutPicker.viewUrl(prev.getId, None, 18)
        val page2 = doc(route(app, request(GET, "admin@example.com", url.stripSuffix("#cut"), Nil)).get)
        val page1 = doc(byRate(app, "admin@example.com", prev, "?sort=rate&cut=18"))
        (url must contain("/page/2?")) and (divider(page2).map(_._2) === Some(3)) and
          (divider(page2).map(_._1) === Some("Cut: top 18 · score ≥ 4.0")) and (divider(page1) must beNone)
      }
    }

    "leave the view's authorization as it is" in {
      testDbApp { implicit app =>
        val (_, prev) = setUp()
        forall(Seq("admin@example.com", "organizer@example.com", "juror1@example.com", "other-admin@example.com")) { email =>
          (email -> status(byRate(app, email, prev, "?sort=rate&cut=4"))) ===
            (email -> status(byRate(app, email, prev, "?sort=rate")))
        } and (doc(byRate(app, "juror1@example.com", prev, "?sort=rate&cut=4")).select("li.wlx-cut-divider, li.gallerybox").size === 0)
      }
    }
  }

  "the preview's CSRF check" should {
    "refuse a post without the form's token" in {
      withCsrfApp { app =>
        val refused = route(app, FakeRequest(POST, "/admin/rounds/cut-preview")
          .withHeaders(COOKIE -> "PLAY_SESSION=x", ACCEPT -> "text/html")
          .withSession(Secured.UserName -> "admin@example.com", "csrfToken" -> csrfToken(app))
          .withFormUrlEncodedBody("contest" -> "1")).get
        status(refused) === FORBIDDEN
      }
    }
  }
}
