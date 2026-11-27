package controllers

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

/** The round form's refused saves, its saves and its resubmits (UI-path plan §0.5, §4 step 1;
  * problem-monitoring plan M-1), and the next step of a round being set up (#186, #184):
  *  - a refused save comes back through the GET's view (same mode, regions, re-apply, the round's
  *    jurors), every error in the summary at the top linking to its field and at its field, the
  *    section holding it open; logged once as `form_refused` with field names and message keys,
  *    never the values;
  *  - a token that already saved a round, posted or opened again: that round's Edit page with
  *    "Round N is saved. You can change its settings here.", no second round, `resubmit_dropped`
  *    logged (same or changed); the form's pages are `no-store`;
  *  - the confirmation on the review page it lands on and after an edit save, never "created";
  *  - the next-step box (matching, ready, no files) and Distribute allowed only when offered;
  *  - the rating type editable while the round is being set up, locked once distributed;
  *  - an organizer, a juror and another contest's admin refused (403), nothing logged as a form.
  */
class RoundFormRefusalSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val Secret = "zq7-secret-value"

  /** A Ukrainian contest (its round form has the regions menu): files 1-4 small (0.8 Mpx), 5-8
    * large (6 Mpx) in its category, two jurors and a third outside every round, an organizer and an
    * admin; another contest with its own admin.
    */
  private def setUp(): ContestJury = {
    val contest = contestDao.create(None, "WLM", 2026, "Ukraine")
    TestDb.setSource(contest.getId, Some("Category:WLM images"))
    val other = contestDao.create(None, "WLE", 2026, "Ukraine")
    val imgs = (1L to 8L).map(i =>
      if (i <= 4) Image(i, s"File:Image$i.jpg", None, None, 1000, 800, Some(s"80-361-000$i"))
      else Image(i, s"File:Image$i.jpg", None, None, 3000, 2000, Some(s"80-361-000$i")))
    imageDao.batchInsert(imgs)
    CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, imgs)
    def member(email: String, role: String, c: ContestJury = contest) =
      User.create(User(email, email, None, Set(role), Some(services.PasswordHasher.default.hash("secret")), c.id))
    Seq("juror1", "juror2", "juror3").foreach(j => member(s"$j@example.com", "jury"))
    member("organizer@example.com", "organizer")
    member("admin@example.com", "admin")
    member("other-admin@example.com", "admin", other)
    contestDao.findById(contest.getId).get
  }

  private def admin = User.findByEmail("admin@example.com").head

  /** A round held in review (its files matched, no juror), with `filters`. */
  private def inReview(contest: ContestJury, round: Round = null): Round = {
    val r = Option(round).getOrElse(Round(None, 0, Some("Semi"), contest.getId, active = true))
    roundDao.findById(TestRoundServices.roundService().submit(r, Nil, None, review = true).round.getId).get
  }

  private def request(method: String, email: String, path: String, body: Seq[(String, String)]) =
    FakeRequest(method, path).withFormUrlEncodedBody(body: _*)
      .withSession(Secured.UserName -> email).withCSRFToken

  private def get(app: Application, email: String, path: String): Future[Result] =
    route(app, request(GET, email, path, Nil)).get

  private def save(app: Application, email: String, body: (String, String)*): Future[Result] =
    route(app, request(POST, email, "/admin/rounds/save", body)).get

  /** The new-round form's fields, with `extra` replacing or adding. */
  private def newRound(contest: ContestJury, token: String, extra: (String, String)*): Seq[(String, String)] = {
    val base = Seq("number" -> "0", "name" -> "Final", "contest" -> contest.getId.toString, "roles" -> "jury",
      "rates" -> "1", "minMpx" -> "No", "minSize" -> "No", "mediaType" -> "all", "submitToken" -> token)
    base.filterNot(f => extra.exists(_._1 == f._1)) ++ extra
  }

  /** An existing round's fields, with `extra` replacing or adding. */
  private def edit(r: Round, extra: (String, String)*): Seq[(String, String)] = {
    val base = Seq("id" -> r.getId.toString, "number" -> r.number.toString, "name" -> r.name.getOrElse(""),
      "contest" -> r.contestId.toString, "roles" -> "jury", "distribution" -> "0", "rates" -> r.rates.id.toString,
      "minMpx" -> "No", "minSize" -> "No", "mediaType" -> "all")
    base.filterNot(f => extra.exists(_._1 == f._1)) ++ extra
  }

  private def token() = java.util.UUID.randomUUID().toString

  private def doc(result: Future[Result]): Document = Jsoup.parse(contentAsString(result))

  /** The summary's lines: (the link's target, the line's text). */
  private def summary(page: Document): Seq[(String, String)] =
    page.select("#round-form-errors li").asScala.toSeq.map(li => li.select("a").attr("href") -> li.text)

  private def open(page: Document, collapse: String): Boolean = page.getElementById(collapse).hasClass("show")

  private def supportLines[T](f: => T): (T, Seq[String]) = {
    val (result, lines) = LogCapture("support.event")(f)
    (result, lines("support.event"))
  }

  "a refused new round's save" should {

    "come back as the GET's form: its token, the regions menu, every error at the top and at its field, Media filtering open" in {
      testDbApp { implicit app =>
        val contest = setUp()
        // a round of the contest: the form has the previous rounds' fields (the top-N among them)
        val previous = inReview(contest)
        val formUrl = s"/admin/rounds/edit?contestId=${contest.getId}"
        val form = doc(get(app, "admin@example.com", formUrl))
        val t = token()
        // a top-N below 1 and a category that is no title: two field errors inside Media filtering
        val refused = save(app, "admin@example.com", newRound(contest, t, "previousRound[0]" -> previous.getId.toString,
          "topImages" -> "0", "source" -> s"Category:<$Secret>"): _*)
        val page = doc(refused)
        val lines = summary(page)
        (status(refused) === BAD_REQUEST) and
          (header("Cache-Control", refused) must beSome("no-store")) and
          (form.select("#collapseImageFiltering.show").size === 0) and (open(page, "collapseImageFiltering") must beTrue) and
          // the same mode as the GET: a new round's, its regions menu, its token kept
          (page.select("h1").text === form.select("h1").text) and
          (page.select("select#regions").size === 1) and (form.select("select#regions").size === 1) and
          (page.select(s"""input[name=submitToken][value=$t]""").size === 1) and
          (page.getElementById("round-form").attr("data-resolve-url") must contain(s"submitToken=$t")) and
          // every error, linked to its field, and shown at the field
          (lines.map(_._1) must containTheSameElementsAs(Seq("#topImages_field", "#source_field"))) and
          (lines.map(_._2).mkString(" ") must contain("Only files in category: That is not a valid Commons")) and
          (page.select("#topImages_field .invalid-feedback").size === 1) and
          (page.select("#source_field .invalid-feedback").text must contain("not a valid Commons")) and
          (Round.findByContest(contest.getId).size === 1)
      }
    }

    "name a field the form doesn't show without a link to it" in {
      testDbApp { implicit app =>
        val contest = setUp()
        // no round yet: no previous rounds' fields, a crafted top-N's error listed by its label only
        val page = doc(save(app, "admin@example.com", newRound(contest, token(), "topImages" -> "0"): _*))
        (summary(page) === Seq("" -> page.select("#round-form-errors li").text)) and
          (page.select("#round-form-errors li").text must startWith("Number of top files"))
      }
    }

    "keep the initial cut in the history URL without turning a refused edit into its new default" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val previous = inReview(contest)
        val t = token()
        val opened = doc(get(app, "admin@example.com", s"/admin/rounds/edit?contestId=${contest.getId}&topImages=2&submitToken=$t"))
        val form = opened.select("#round-form")
        val action = form.attr("action")
        val body = newRound(contest, t, "previousRound[0]" -> previous.getId.toString, "topImages" -> "3",
          "source" -> s"Category:<$Secret>")
        val refused = route(app, request(POST, "admin@example.com", action, body)).get
        val refusedPage = doc(refused)
        val history = refusedPage.select("#round-form").attr("data-resolve-url")
        val reset = refusedPage.select("#round-form-reset")
        (form.attr("autocomplete") === "off") and
          (action must contain("topImages=2")) and (status(refused) === BAD_REQUEST) and
          (history must contain("topImages=2")) and (history must not(contain("topImages=3"))) and
          (opened.select("#round-form-reset").attr("data-reset-url") === form.attr("data-resolve-url")) and
          (reset.attr("type") === "button") and (reset.attr("data-reset-url") === history)
      }
    }

    "move a form-level error to its field: the second category box without the first" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val refused = save(app, "admin@example.com", newRound(contest, token(), "categoryCombine" -> "and",
          "excludeCategory" -> "Category:Rivers"): _*)
        val page = doc(refused)
        (status(refused) === BAD_REQUEST) and (open(page, "collapseImageFiltering") must beTrue) and
          (summary(page).map(_._1) === Seq("#source_field")) and
          (page.select("#source_field .invalid-feedback").text must contain("Enter a category in"))
      }
    }

    "open Add files for an unreadable list, its error at the field" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val refused = save(app, "admin@example.com", newRound(contest, token(),
          "fileList" -> s"File:Image1.jpg\nhttps://example.org/$Secret"): _*)
        val page = doc(refused)
        (status(refused) === BAD_REQUEST) and (open(page, "collapseAddFiles") must beTrue) and
          (summary(page).map(_._1) === Seq("#fileList_field")) and
          (page.select("#fileList_field .invalid-feedback").size === 1)
      }
    }

    "be logged once as form_refused, with field names and keys, never the values" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val (refused, lines) = supportLines {
          val r = save(app, "admin@example.com", newRound(contest, token(), "topImages" -> "0",
            "source" -> s"Category:<$Secret>"): _*)
          status(r)
          r
        }
        val (ruleRefused, ruleLines) = supportLines {
          val r = save(app, "admin@example.com", newRound(contest, token(), "categoryCombine" -> "and",
            "excludeCategory" -> s"Category:$Secret"): _*)
          status(r)
          r
        }
        val (unreadable, unreadableLines) = supportLines {
          val r = save(app, "admin@example.com", newRound(contest, token(),
            "fileList" -> s"https://example.org/$Secret"): _*)
          status(r)
          r
        }
        val refusedLine = lines.filter(_.startsWith("form_refused "))
        (status(refused) === BAD_REQUEST) and (refusedLine.size === 1) and
          (refusedLine.head must contain("form=round")) and (refusedLine.head must contain(s"contestId=${contest.getId}")) and
          (refusedLine.head must contain("roundId=new")) and (refusedLine.head must contain(s"accountId=${admin.getId}")) and
          (refusedLine.head must contain("status=400")) and
          (refusedLine.head must contain("fields=")) and (refusedLine.head must contain("topImages")) and
          (refusedLine.head must contain("source")) and
          (refusedLine.head must contain("error.wiki.title.invalid")) and (refusedLine.head must contain("error.min")) and
          (lines.mkString("\n") must not(contain(Secret))) and
          (status(unreadable) === BAD_REQUEST) and
          (unreadableLines.filter(_.startsWith("form_refused ")).map(_.contains("keys=files.list.unreadable")) === Seq(true)) and
          (unreadableLines.mkString("\n") must not(contain(Secret)))
      }
    }
  }

  "a refused edit" should {

    "keep a round in review in re-apply mode, its filters editable, the error in the summary; logged with its id" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val round = inReview(contest)
        val (refused, lines) = supportLines {
          // a rejected-by mode without its number: a form error of a field inside Media filtering
          val r = save(app, "admin@example.com", edit(round, "reapply" -> "true", "rejectedMode" -> "exclude",
            "monumentIds" -> Secret): _*)
          status(r)
          r
        }
        val page = doc(refused)
        (status(refused) === BAD_REQUEST) and
          (page.select("#round-form").attr("data-resolve-url") ===
            s"/admin/rounds/edit?id=${round.getId}&contestId=${contest.getId}") and
          (page.select("#round-form-reset").attr("data-reset-url") === page.select("#round-form").attr("data-resolve-url")) and
          (flash(refused).get(RoundFormDraft.Saved) must beNone) and
          (page.select("input[name=reapply]").size === 1) and
          (page.select("select#minMpx[disabled]").size === 0) and (page.select("input#monumentIds[readonly]").size === 0) and
          (open(page, "collapseImageFiltering") must beTrue) and
          (summary(page).map(_._1) === Seq("#rejectionFilter_field")) and
          (page.select("#rejectionFilter_field .invalid-feedback").size === 1) and
          (page.select("input[name=submitToken]").size === 0) and
          (lines.filter(_.startsWith("form_refused ")).map(_.contains(s"roundId=${round.getId}")) === Seq(true)) and
          (lines.mkString("\n") must not(contain(Secret)))
      }
    }

    "list the round's jurors, not the contest's" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val jurors = User.findByContest(contest.getId).filter(_.email.startsWith("juror")).sortBy(_.email)
        val round = roundDao.findById(TestRoundServices.roundService().createNewRound(
          Round(None, 0, Some("Open"), contest.getId, active = true), jurors.take(2).flatMap(_.id)).getId).get
        val refused = save(app, "admin@example.com", edit(round, "rates" -> "x"): _*)
        val options = doc(refused).select("select#jurors option").asScala.map(_.attr("value")).toSet
        (status(refused) === BAD_REQUEST) and (options === jurors.take(2).map(_.getId.toString).toSet) and
          (summary(doc(save(app, "admin@example.com", edit(round, "rates" -> "x"): _*))).map(_._1) === Seq("#rates_field"))
      }
    }
  }

  "a new-round form's token that saved a round" should {

    "answer a repost with the round's Edit page and its line: no second round, resubmit_dropped same or changed" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val t = token()
        val formPage = doc(get(app, "admin@example.com", s"/admin/rounds/edit?contestId=${contest.getId}&submitToken=$t"))
        val draftKey = formPage.select("#round-form").attr("data-round-draft-key")
        val first = save(app, "admin@example.com", newRound(contest, t, "monumentIds" -> "80-361-0001"): _*)
        val firstFlash = flash(first).get("success")
        val saved = Round.findByContest(contest.getId).head
        val edit = s"/admin/rounds/edit?id=${saved.getId}&contestId=${contest.getId}"
        val (again, sameLines) = supportLines {
          val r = save(app, "admin@example.com", newRound(contest, t, "monumentIds" -> "80-361-0001"): _*)
          status(r)
          r
        }
        // Back, an invalid change, Save: no 400 any more, the saved round's Edit page
        val (changed, changedLines) = supportLines {
          val r = save(app, "admin@example.com", newRound(contest, t, "monumentIds" -> "80-361-0002", "topImages" -> "0"): _*)
          status(r)
          r
        }
        val editPage = contentAsString(route(app, FakeRequest(GET, edit).withSession(Secured.UserName -> "admin@example.com")
          .withFlash(flash(again).data.toSeq: _*).withCSRFToken).get)
        def dropped(lines: Seq[String]) = lines.filter(_.startsWith("resubmit_dropped "))
        (firstFlash must beSome("Round 1 is saved. Step 2 of 3: its matching files are being found. Check them, then choose jurors.")) and
          (draftKey must not(beEmpty)) and (draftKey must not(contain(t))) and
          (flash(first).get(RoundFormDraft.Saved) must beSome(draftKey)) and
          (status(again) === SEE_OTHER) and (redirectLocation(again) must beSome(edit)) and
          (flash(again).get("success") must beSome("Round 1 is saved. You can change its settings here.")) and
          (status(changed) === SEE_OTHER) and (redirectLocation(changed) must beSome(edit)) and
          (flash(changed).get(RoundFormDraft.Saved) must beNone) and
          (Round.findByContest(contest.getId).size === 1) and (Round.findById(saved.getId).get.monuments === saved.monuments) and
          (dropped(sameLines).size === 1) and (dropped(sameLines).head must contain("outcome=same")) and
          (dropped(sameLines).head must contain(s"roundId=${saved.getId}")) and
          (dropped(changedLines).size === 1) and (dropped(changedLines).head must contain("outcome=changed")) and
          ((sameLines ++ changedLines).mkString must not(contain("Final"))) and
          (changedLines.filter(_.startsWith("form_refused ")) must beEmpty) and
          (Jsoup.parse(editPage).select("#flash-success").text === "Round 1 is saved. You can change its settings here.") and
          (Jsoup.parse(editPage).select("[data-round-draft-saved]").attr("data-round-draft-saved") === draftKey)
      }
    }

    "answer the form's URL with its token (Back) with the Edit page; an unused token gets the form, no-store" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val t = token()
        val unused = get(app, "admin@example.com", s"/admin/rounds/edit?contestId=${contest.getId}&submitToken=$t")
        val unusedPage = doc(unused)
        status(save(app, "admin@example.com", newRound(contest, t): _*)) must_== SEE_OTHER
        val saved = Round.findByContest(contest.getId).head
        val back = get(app, "admin@example.com", s"/admin/rounds/edit?contestId=${contest.getId}&submitToken=$t")
        (status(unused) === OK) and (header("Cache-Control", unused) must beSome("no-store")) and
          (unusedPage.select(s"input[name=submitToken][value=$t]").size === 1) and
          (status(back) === SEE_OTHER) and
          (redirectLocation(back) must beSome(s"/admin/rounds/edit?id=${saved.getId}&contestId=${contest.getId}")) and
          (flash(back).get("success") must beSome("Round 1 is saved. You can change its settings here."))
      }
    }

    "give another contest's admin, opening their own contest's form with this token, a fresh token" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val t = token()
        status(save(app, "admin@example.com", newRound(contest, t): _*)) must_== SEE_OTHER
        val otherContest = ContestJuryJdbc.findAll().map(_.getId).find(_ != contest.getId).get
        val page = get(app, "other-admin@example.com", s"/admin/rounds/edit?contestId=$otherContest&submitToken=$t")
        val shownToken = doc(page).select("input[name=submitToken]").attr("value")
        (status(page) === OK) and (shownToken must not(beEmpty)) and (shownToken !== t)
      }
    }

    "not lead to a deleted round: its token gets a fresh form, and its repost saves a new round" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val t = token()
        status(save(app, "admin@example.com", newRound(contest, t): _*)) must_== SEE_OTHER
        val deleted = Round.findByContest(contest.getId).head
        scalikejdbc.DB.autoCommit { implicit s =>
          scalikejdbc.SQL("UPDATE round SET deleted_at = NOW() WHERE id = ?").bind(deleted.getId).update()
        }
        val opened = get(app, "admin@example.com", s"/admin/rounds/edit?contestId=${contest.getId}&submitToken=$t")
        val shownToken = doc(opened).select("input[name=submitToken]").attr("value")
        // refused, the form comes back with a fresh token (its double-submit and Back protection kept)
        val refused = save(app, "admin@example.com", newRound(contest, t, "topImages" -> "0"): _*)
        val refusedToken = doc(refused).select("input[name=submitToken]").attr("value")
        val unreadable = save(app, "admin@example.com", newRound(contest, t, "fileList" -> "https://example.org/x"): _*)
        val unreadableToken = doc(unreadable).select("input[name=submitToken]").attr("value")
        val again = save(app, "admin@example.com", newRound(contest, t, "name" -> "Again"): _*)
        (status(opened) === OK) and (shownToken !== t) and
          (status(refused) === BAD_REQUEST) and (refusedToken must beMatching("[0-9a-f-]{36}")) and (refusedToken !== t) and
          (status(unreadable) === BAD_REQUEST) and (unreadableToken must beMatching("[0-9a-f-]{36}")) and (unreadableToken !== t) and
          (status(again) === SEE_OTHER) and (redirectLocation(again).get must beMatching("/admin/rounds/[0-9]+/review")) and
          (Round.findByContest(contest.getId).exists(r => r.name.contains("Again") && r.deletedAt.isEmpty) must beTrue)
      }
    }

    "keep the Rounds page's filter on the way to the saved round's Edit page" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val t = token()
        status(save(app, "admin@example.com", newRound(contest, t): _*)) must_== SEE_OTHER
        val saved = Round.findByContest(contest.getId).head
        val again = route(app, request(POST, "admin@example.com", "/admin/rounds/save?type=binary&order=newest",
          newRound(contest, t))).get
        (status(again) === SEE_OTHER) and
          (redirectLocation(again).get must contain(s"id=${saved.getId}")) and
          (redirectLocation(again).get must contain("type=binary")) and (redirectLocation(again).get must contain("order=newest"))
      }
    }

    "refuse an organizer, a juror and another contest's admin, posting or opening it; nothing logged as a form" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val t = token()
        status(save(app, "admin@example.com", newRound(contest, t): _*)) must_== SEE_OTHER
        val (statuses, lines) = supportLines {
          Seq("organizer@example.com", "juror1@example.com", "other-admin@example.com").flatMap(email => Seq(
            status(save(app, email, newRound(contest, t): _*)),
            status(save(app, email, newRound(contest, token(), "topImages" -> "0"): _*)),
            status(get(app, email, s"/admin/rounds/edit?contestId=${contest.getId}&submitToken=$t"))))
        }
        (statuses.forall(_ == FORBIDDEN) must beTrue) and (lines must beEmpty) and
          (Round.findByContest(contest.getId).size === 1)
      }
    }
  }

  "a saved round" should {

    "be confirmed on its review page, step 2 of its setup, never as created or ready" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val t = token()
        val form = doc(get(app, "admin@example.com", s"/admin/rounds/edit?contestId=${contest.getId}&submitToken=$t"))
        val draftKey = form.select("#round-form").attr("data-round-draft-key")
        val saved = save(app, "admin@example.com", newRound(contest, t): _*)
        status(saved) must_== SEE_OTHER
        val round = Round.findByContest(contest.getId).head
        val review = doc(route(app, FakeRequest(GET, redirectLocation(saved).get)
          .withSession(Secured.UserName -> "admin@example.com").withFlash(flash(saved).data.toSeq: _*).withCSRFToken).get)
        val onReview = review.select("#flash-success").text
        (redirectLocation(saved) must beSome(s"/admin/rounds/${round.getId}/review")) and
          (onReview === "Round 1 is saved. Step 2 of 3: its matching files are being found. Check them, then choose jurors.") and
          (onReview must not(contain("created"))) and (onReview must not(contain("ready"))) and
          (draftKey must not(beEmpty)) and
          (review.select("[data-round-draft-saved]").attr("data-round-draft-saved") === draftKey) and
          (review.select("script[src$=round-form-draft.js]").size === 1)
      }
    }

    "be confirmed after an edit save" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val round = inReview(contest)
        val formPage = doc(get(app, "admin@example.com", s"/admin/rounds/edit?id=${round.getId}&contestId=${contest.getId}"))
        val draftKey = formPage.select("#round-form").attr("data-round-draft-key")
        val saved = save(app, "admin@example.com", edit(round, "name" -> "Renamed"): _*)
        (status(saved) === SEE_OTHER) and (flash(saved).get("success") must beSome("Round 1 (Renamed) is saved.")) and
          (formPage.select("#round-form-reset").attr("data-reset-url") ===
            s"/admin/rounds/edit?id=${round.getId}&contestId=${contest.getId}") and
          (draftKey must not(beEmpty)) and (flash(saved).get(RoundFormDraft.Saved) must beSome(draftKey))
      }
    }
  }

  "a round being set up" should {

    "offer Distribute on Edit and review once its files matched, to its admin; its organizer the line only" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val round = inReview(contest)
        val editPage = doc(get(app, "admin@example.com", s"/admin/rounds/edit?id=${round.getId}&contestId=${contest.getId}"))
        val review = doc(get(app, "admin@example.com", s"/admin/rounds/${round.getId}/review"))
        val organizer = doc(get(app, "organizer@example.com", s"/admin/rounds/${round.getId}/review"))
        val jurors = s"/admin/rounds/${round.getId}/jurors"
        (editPage.select("#round-next-step").attr("data-state") === "ready") and
          (editPage.select("#round-next-step-text").text must contain("Jurors can’t see this round yet")) and
          (editPage.select("a#round-next-distribute").attr("href") === jurors) and
          (editPage.select("#round-next-files").attr("href") === s"/admin/rounds/${round.getId}/review") and
          (review.select("#round-next-step").attr("data-state") === "ready") and
          (review.select("a#round-review-distribute-link").attr("href") === jurors) and
          (review.select("#round-next-settings").attr("href") must contain(s"id=${round.getId}")) and
          (organizer.select("#round-next-step-text").size === 1) and (organizer.select(".round-next-distribute").size === 0)
      }
    }

    "show Distribute disabled when no file matches, and refuse it" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val round = inReview(contest, Round(None, 0, Some("None"), contest.getId, active = true, monuments = Some("99-999-9999")))
        val editPage = doc(get(app, "admin@example.com", s"/admin/rounds/edit?id=${round.getId}&contestId=${contest.getId}"))
        val jurorsForm = doc(get(app, "admin@example.com", s"/admin/rounds/${round.getId}/jurors"))
        val juror = User.findByEmail("juror1@example.com").head
        val refused = route(app, request(POST, "admin@example.com", s"/admin/rounds/${round.getId}/distribute",
          Seq("jurorsShown" -> "true", "jurors[0]" -> juror.getId.toString, "distribution" -> "0"))).get
        (editPage.select("#round-next-step").attr("data-state") === "no-files") and
          (editPage.select("button#round-next-distribute[disabled]").size === 1) and
          (editPage.select("#round-next-distribute-reason").text must contain("No file matches")) and
          (jurorsForm.select("#round-jurors-form button[type=submit][disabled]").size === 1) and
          (status(refused) === SEE_OTHER) and (flash(refused).get("error").getOrElse("") must contain("No file matches")) and
          (Round.findById(round.getId).get.inReview must beTrue)
      }
    }

    "say the matching runs, Distribute disabled, while its job is active" in {
      val round = Round(Some(1L), 1, Some("R"), 1L, distributing = true, status = Some(Round.Status.Paused))
      def job(status: String, mode: String, reapply: Boolean = false) =
        services.jobs.Job(1L, "round_distribution", Some(1L), Some("round"), Some(1L), None, status,
          play.api.libs.json.Json.obj("mode" -> mode, "reapply" -> reapply), play.api.libs.json.Json.obj(),
          None, None, None, None, 0, None, None, java.time.Instant.now(), None, None, None, None)
      val draft = round.copy(status = Some(Round.Status.Draft))
      (RoundNextStep.of(round, Some(job("running", "fill", reapply = true)), 5).map(_.state.id) must beSome(RoundNextStep.Matching.id)) and
        (RoundNextStep.of(draft, Some(job("running", "fill")), 0).map(_.state.id) must beSome(RoundNextStep.Matching.id)) and
        (RoundNextStep.of(round, Some(job("done", "fill")), 5).map(_.distributeAllowed) must beSome(true)) and
        (RoundNextStep.of(round, Some(job("done", "fill")), 0).map(_.state.id) must beSome(RoundNextStep.NoFiles.id)) and
        (RoundNextStep.of(round, Some(job("failed", "fill", reapply = true)), 5).map(_.state.id) must beSome(RoundNextStep.Unfinished.id)) and
        (RoundNextStep.of(round, Some(job("running", "distribute")), 5) must beNone) and
        (RoundNextStep.of(round.copy(distributing = false), None, 5) must beNone)
    }
  }

  /** A running job of `kind` on round `r`'s key, 1,200 of 1,800 files done. */
  private def runningJob(contest: ContestJury, r: Round, kind: String): Long =
    scalikejdbc.DB.autoCommit { implicit s =>
      services.jobs.JobJdbc.insert(services.jobs.NewJob(kind, contest.id,
        Some(services.distribution.RoundDistributionHandler.SubjectType), r.id,
        Some(services.distribution.RoundDistributionHandler.dedupeKey(r.getId)),
        progress = services.jobs.Progress(Some(1200L), Some(1800L), Some("files"))), owner = Some("spec"))
    }

  private def endJob(id: Long): Unit = scalikejdbc.DB.autoCommit { implicit s =>
    scalikejdbc.SQL("UPDATE job SET status = 'done', owner = NULL WHERE id = ?").bind(id).update()
  }

  private def jobStatus(app: Application, email: Option[String], id: Long): Future[Result] = {
    val req = FakeRequest(GET, s"/admin/jobs/$id").withHeaders("X-Requested-With" -> "XMLHttpRequest")
    route(app, email.fold(req)(e => req.withSession(Secured.UserName -> e))).get
  }

  "a save refused to wait for another round's job (Q7)" should {

    "show the distribution of a round to leave out, its progress and link, outside the form kept as entered" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val busy = roundDao.create(Round(None, 1, Some("Busy"), contest.getId, distributing = true))
        val jobId = runningJob(contest, busy, services.distribution.RoundDistributionHandler.Kind)
        val (refused, lines) = supportLines {
          val r = save(app, "admin@example.com", newRound(contest, token(), "name" -> "Kept as entered",
            "excludeRounds[0]" -> busy.getId.toString): _*)
          status(r)
          r
        }
        val page = doc(refused)
        val box = page.getElementById("round-form-wait")
        (status(refused) === BAD_REQUEST) and (box must not(beNull)) and
          (page.select("#round-form #round-form-wait").size === 0) and
          (box.attr("data-src") === s"/admin/jobs/$jobId") and (box.attr("data-status") === "running") and
          (page.select("#round-form-wait-running").text must contain("Round 1 is being distributed: 1,200 of 1,800 files")) and
          (page.select("#round-form-wait-running").hasAttr("hidden") must beFalse) and
          (page.select("#round-form-wait-link").attr("href") === s"/admin/rounds?contestId=${contest.getId}#round-${busy.getId}") and
          (page.select("#round-form-wait-done").text === "Round 1 has finished. You can save now.") and
          (page.select("#round-form-wait-done").hasAttr("hidden") must beTrue) and
          (page.select("input#name").attr("value") === "Kept as entered") and
          (page.select(s"select#excludeRounds option[value=${busy.getId}][selected]").size === 1) and
          (lines.filter(_.startsWith("form_refused ")).map(_.contains("keys=error.exclude.rounds.busy")) === Seq(true))
      }
    }

    "show a previous round's juror add, its own wording" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val previous = inReview(contest)
        TestRoundServices.roundService().distributeReviewed(previous.getId, admin.id,
          jurors = Some(User.findByEmail("juror1@example.com").flatMap(_.id)))
        val jobId = runningJob(contest, previous, services.distribution.RoundRebalanceHandler.Kind)
        val refused = save(app, "admin@example.com", newRound(contest, token(), "name" -> "Next",
          "previousRound[0]" -> previous.getId.toString): _*)
        val page = doc(refused)
        (status(refused) === BAD_REQUEST) and
          (page.select("#round-form-wait").attr("data-src") === s"/admin/jobs/$jobId") and
          (page.select("#round-form-wait-running").text must contain("Jurors’ files are being moved in round 1: 1,200 of 1,800 files")) and
          (page.select("#round-form-wait-done").text === "Moving jurors’ files in round 1 has finished. You can save now.") and
          (summary(page).map(_._2).mkString must contain("Jurors")) and
          (Round.findByContest(contest.getId).size === 1)
      }
    }

    "poll the job for its contest's admin only, say done when it ends, and then save" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val busy = roundDao.create(Round(None, 1, Some("Busy"), contest.getId, distributing = true))
        val jobId = runningJob(contest, busy, services.distribution.RoundDistributionHandler.Kind)
        val running = jobStatus(app, Some("admin@example.com"), jobId)
        val denied = Seq("organizer@example.com", "juror1@example.com", "other-admin@example.com")
          .map(e => status(jobStatus(app, Some(e), jobId)))
        val signedOut = status(jobStatus(app, None, jobId))
        endJob(jobId)
        scalikejdbc.DB.autoCommit { implicit s =>
          scalikejdbc.SQL("UPDATE round SET distributing = FALSE WHERE id = ?").bind(busy.getId).update()
        }
        val done = jobStatus(app, Some("admin@example.com"), jobId)
        val saved = save(app, "admin@example.com", newRound(contest, token(), "excludeRounds[0]" -> busy.getId.toString): _*)
        (status(running) === OK) and ((contentAsJson(running) \ "status").as[String] === "running") and
          ((contentAsJson(running) \ "progressDone").as[Long] === 1200L) and
          (denied === Seq(NOT_FOUND, NOT_FOUND, NOT_FOUND)) and (signedOut === UNAUTHORIZED) and
          ((contentAsJson(done) \ "status").as[String] === "done") and
          (status(saved) === SEE_OTHER)
      }
    }
  }

  "a saved edit whose new files' distribution is refused" should {

    "say the settings were saved, show the wait's job, and log distribution_refused, not form_refused" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val jurors = User.findByContest(contest.getId).filter(_.email.startsWith("juror")).flatMap(_.id)
        val open = roundDao.findById(TestRoundServices.roundService().createNewRound(
          Round(None, 0, Some("Open"), contest.getId, active = true), jurors).getId).get
        val busy = roundDao.create(Round(None, 2, Some("Busy"), contest.getId, distributing = true))
        val jobId = runningJob(contest, busy, services.distribution.RoundDistributionHandler.Kind)
        scalikejdbc.DB.autoCommit { implicit s =>
          scalikejdbc.SQL("UPDATE round SET exclude_rounds = ? WHERE id = ?").bind(busy.getId.toString, open.getId).update()
        }
        val (refused, lines) = supportLines {
          val r = save(app, "admin@example.com", edit(open, "name" -> "Renamed", "newImages" -> "true"): _*)
          status(r)
          r
        }
        val page = doc(refused)
        (status(refused) === BAD_REQUEST) and (Round.findById(open.getId).get.name must beSome("Renamed")) and
          (page.select("#round-form-errors h2").text === "Round 1 is saved. Its new files weren\u2019t distributed:") and
          (page.select("#round-form-errors li").text must contain("Could not distribute new files")) and
          (page.select("#round-form-wait").attr("data-src") === s"/admin/jobs/$jobId") and
          (lines.filter(_.startsWith("distribution_refused ")).map(_.contains(s"roundId=${open.getId}")) === Seq(true)) and
          (lines.filter(_.startsWith("distribution_refused ")).head must contain("error.exclude.rounds.busy")) and
          (lines.filter(_.startsWith("form_refused ")) must beEmpty)
      }
    }
  }

  "a series admin's wait box" should {

    "be shown, its job's JSON answering the series admin as the round form does" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val root = User.create(User("Root", "root@example.com", None, Set(User.ROOT_ROLE), isRoot = true))
        val series = services.series.ContestSeries.createSeriesTx("Ukraine WLM", Some("wlm"), Some("Ukraine WLM"), None,
          root.getId).toOption.get
        services.series.ContestSeries.setSeries(contest.getId, Some(series), root.getId, None) must beRight
        val sa = User.create(User("Series", "series@example.com", None, Set("organizer"), Some("hash"), contest.id))
        services.series.SeriesAdmins.grant(root, series, sa.getId, None, None) must beRight
        UserContestJdbc.delete(sa.getId, contest.getId)
        val busy = roundDao.create(Round(None, 1, Some("Busy"), contest.getId, distributing = true))
        val jobId = runningJob(contest, busy, services.distribution.RoundDistributionHandler.Kind)
        val session = Seq(Secured.UserName -> sa.email, Secured.CurrentContestId -> contest.getId.toString)
        val refused = route(app, FakeRequest(POST, "/admin/rounds/save").withSession(session: _*)
          .withFormUrlEncodedBody(newRound(contest, token(), "excludeRounds[0]" -> busy.getId.toString): _*).withCSRFToken).get
        val poll = route(app, FakeRequest(GET, s"/admin/jobs/$jobId").withSession(session: _*)
          .withHeaders("X-Requested-With" -> "XMLHttpRequest")).get
        (status(refused) === BAD_REQUEST) and (doc(refused).select("#round-form-wait-unknown").size === 1) and
          (doc(refused).select("#round-form-wait").attr("data-src") === s"/admin/jobs/$jobId") and
          (status(poll) === OK) and ((contentAsJson(poll) \ "status").as[String] === "running")
      }
    }
  }

  "the rating type (#184)" should {

    "be editable while the round is being set up, saved and re-applied, and locked once distributed" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val round = inReview(contest)
        val formUrl = s"/admin/rounds/edit?id=${round.getId}&contestId=${contest.getId}"
        val editable = doc(get(app, "admin@example.com", formUrl)).select("select#rates[disabled]").size
        val saved = save(app, "admin@example.com", edit(round, "rates" -> "5"): _*)
        val savedStatus = status(saved)
        val ratesAfterSave = Round.findById(round.getId).get.rates.id
        val reapplied = save(app, "admin@example.com", edit(Round.findById(round.getId).get, "rates" -> "0", "reapply" -> "true"): _*)
        val reappliedStatus = status(reapplied)
        val ratesAfterReapply = Round.findById(round.getId).get.rates.id
        // distributed: locked, a posted other rating ignored
        val juror = User.findByEmail("juror1@example.com").head
        TestRoundServices.roundService().distributeReviewed(round.getId, admin.id, jurors = Some(Seq(juror.getId)))
        val distributed = Round.findById(round.getId).get
        val locked = doc(get(app, "admin@example.com", formUrl)).select("select#rates[disabled]").size
        status(save(app, "admin@example.com", edit(distributed, "rates" -> "1"): _*)) must_== SEE_OTHER
        (editable === 0) and (savedStatus === SEE_OTHER) and (ratesAfterSave === 5) and
          (reappliedStatus === SEE_OTHER) and (ratesAfterReapply === 0) and
          (distributed.openToJurors must beTrue) and (locked === 1) and (Round.findById(round.getId).get.rates.id === 0)
      }
    }

    "clear the criteria when a criteria round being set up becomes yes/no, as the page posts it and as an older page does" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val Seq(a, b) = CriterionJdbc.all().take(2).map(_.id)
        def criteriaRound(name: String) = {
          val r = inReview(contest, Round(None, 0, Some(name), contest.getId, active = true, rates = Round.ratesById(5)))
          scalikejdbc.DB.localTx { implicit s => RoundCriterion.updateIn(r.getId, Seq(a, b)) must beRight }
          Round.findById(r.getId).get
        }
        val scripted = criteriaRound("Scripted")
        val older = criteriaRound("Older")
        val reapplied = criteriaRound("Reapplied")
        // the page's script hides the criteria selects: not sent, the hidden criteriaShown is
        val bySelects = save(app, "admin@example.com", edit(scripted, "rates" -> "1", "criteriaShown" -> "true"): _*)
        val bySelectsStatus = status(bySelects)
        // an older page or no script: the criteria posted unchanged
        val unchanged = save(app, "admin@example.com", edit(older, "rates" -> "1", "criteriaShown" -> "true",
          "criteria[0]" -> a.toString, "criteria[1]" -> b.toString): _*)
        val unchangedStatus = status(unchanged)
        val reapply = save(app, "admin@example.com", edit(reapplied, "rates" -> "1", "criteriaShown" -> "true",
          "reapply" -> "true"): _*)
        val reapplyStatus = status(reapply)
        (bySelectsStatus === SEE_OTHER) and (unchangedStatus === SEE_OTHER) and (reapplyStatus === SEE_OTHER) and
          forall(Seq(scripted, older, reapplied)) { r =>
            (Round.findById(r.getId).get.rates.id === 1) and (RoundCriterion.idsOf(r.getId) must beEmpty)
          }
      }
    }

    "be locked while a later round takes its files" in {
      testDbApp { implicit app =>
        val contest = setUp()
        val round = inReview(contest)
        roundDao.create(Round(None, 2, Some("Later"), contest.getId, previous = Some(round.getId.toString)))
        val formUrl = s"/admin/rounds/edit?id=${round.getId}&contestId=${contest.getId}"
        val locked = doc(get(app, "admin@example.com", formUrl)).select("select#rates[disabled]").size
        status(save(app, "admin@example.com", edit(round, "rates" -> "5"): _*)) must_== SEE_OTHER
        (locked === 1) and (Round.findById(round.getId).get.rates.id === round.rates.id)
      }
    }
  }
}
