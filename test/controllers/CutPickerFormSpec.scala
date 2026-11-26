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

/** The round form's top N, the cut picker (regional winners plan §10.4 place 1, §10.9 "Round form
  * (SN)"): no free number box; "Choose N" posts the form to the preview, which saves nothing and
  * offers an N list of valid values only, the candidate cuts and the gallery; a candidate or "Cut
  * here" sets N, a crafted value inside a tie does not; a save refuses an N inside a tie next to its
  * field; a valid N saves with how it was chosen in the round's settings log, and the round takes
  * the picker's first N files; an existing round's stored N shows read only. Only the contest's
  * admins reach the preview (an organizer, a juror and another contest's admin are refused).
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

    "offer only valid values, the candidate cuts and the gallery, nothing chosen, and save nothing" in {
      testDbApp { implicit app =>
        val (contest, prev) = setUp()
        val result = preview(app, "admin@example.com", newRound(contest, prev): _*)
        val page = doc(result)
        val cuts = page.select("#cut-gallery li.cut-line").asScala.toSeq.map(_.attr("data-cut"))
        val candidates = page.select("#top-n-candidates button[name=cutPick]").asScala.toSeq.map(_.attr("value"))
        (status(result) === OK) and (header("Cache-Control", result) must beSome("no-store")) and
          (options(page) === Seq("") ++ Valid) and (selected(page) === Seq("")) and
          (candidates must not(beEmpty)) and (candidates.forall(Valid.contains) must beTrue) and
          (page.select("#cut-gallery li.cut-file").size === 8) and
          // a line only at a cut position: never inside the ties 2–4 and 6–7
          (cuts === Seq("1", "4", "5", "7")) and
          (page.select("#cut-gallery .cut-tie-label").asScala.map(_.text).toSeq ===
            Seq("tied: places 2–4", "tied: places 6–7")) and
          (page.select("#top-n-preview").size === 0) and
          (Round.findByContest(contest.getId).size === 1)
      }
    }

    "set N from a candidate or \"Cut here\", with its preview; not from a crafted value inside a tie" in {
      testDbApp { implicit app =>
        val (contest, prev) = setUp()
        val picked = doc(preview(app, "admin@example.com", newRound(contest, prev, "cutPick" -> "4"): _*))
        val crafted = doc(preview(app, "admin@example.com", newRound(contest, prev, "cutPick" -> "3"): _*))
        (selected(picked) === Seq("4")) and
          (picked.select("#top-n-preview").text must contain("4 of 8 files, lowest included score 8")) and
          (selected(crafted) === Seq("")) and (crafted.select("#top-n-preview").size === 0)
      }
    }

    "look for candidates in the first 100 places, with no target (a posted one is ignored)" in {
      testDbApp { implicit app =>
        val (contest, prev) = setUp()
        val page = doc(preview(app, "admin@example.com", newRound(contest, prev, "topImagesTarget" -> "3"): _*))
        // no drop stands out (every gap 1): all candidates only; the list offers every valid value
        (page.select("#topImagesTarget").size === 0) and
          (page.select("#top-n-candidates button[name=cutPick]").asScala.map(_.attr("value")).toSeq === Seq("8")) and
          (options(page) === Seq("") ++ Valid)
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
          (options(page) === Seq("", "") ++ Valid) and (selected(page) === Seq("")) and
          (page.select("select#topImages[required]").size === 1) and
          (Round.findByContest(contest.getId).size === 1)
      }
    }

    "accept exactly the N values the preview offers, and refuse every other one (offered == allowed)" in {
      testDbApp { implicit app =>
        val (contest, prev) = setUp()
        val offered = options(doc(preview(app, "admin@example.com", newRound(contest, prev): _*))).filter(_.nonEmpty)
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
          (views.RoundChangeText.items(entry)(play.api.i18n.MessagesImpl(play.api.i18n.Lang("en"),
            app.injector.instanceOf[play.api.i18n.MessagesApi])).find(_.field == "topImages").flatMap(_.note) must
            beSome(contain("chosen from the list")))
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
