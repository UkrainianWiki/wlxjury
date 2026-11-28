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

/** A round held in review without jurors (P3) and its filters re-applied (P3-5), through the pages:
  * the round form creates one (an older page's "Distribute as soon as the images are ready" ignored);
  * the jurors form picks the jurors
  * (its admins only, D20), refused with none; the round form of a round in review offers "Re-apply
  * filters" to its admins, whose POST re-applies them and the review page shows the result; an
  * organizer, a juror and another contest's admin are refused, and the CSRF token is needed.
  */
class RoundReviewJurorsReapplyPageSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val contestId = 10L

  private def member(email: String, role: String, contest: Long = contestId): User =
    User.create(User(email, email, None, Set(role), Some(services.PasswordHasher.default.hash("secret")), Some(contest)))

  /** Contest 10: files 1-4 small (0.8 Mpx), 5-8 large (6 Mpx) in its category, two jurors, an
    * organizer and an admin, and a round in review with no juror; contest 20 with its own admin.
    */
  private def setUp(): (Round, Seq[User]) = {
    createContests(contestId, 20)
    val contest: ContestJury = contestDao.findById(contestId).get
    val imgs = (1L to 8L).map(i =>
      if (i <= 4) Image(i, s"File:Image$i.jpg", None, None, 1000, 800, Some(s"12-345-$i"))
      else Image(i, s"File:Image$i.jpg", None, None, 3000, 2000, Some(s"12-345-$i")))
    imageDao.batchInsert(imgs)
    CategoryLinkJdbc.addToCategory(contest.categoryId.get, imgs)
    val jurors = Seq(member("juror1@example.com", "jury"), member("juror2@example.com", "jury"))
    member("organizer@example.com", "organizer")
    member("admin@example.com", "admin")
    member("other-admin@example.com", "admin", contest = 20)
    val round = TestRoundServices.roundService()
      .submit(Round(None, 0, Some("R"), contestId, active = true), Nil, None, review = true).round
    (roundDao.findById(round.getId).get, jurors)
  }

  private def request(method: String, email: String, path: String, body: Seq[(String, String)] = Nil) = {
    val contest = if (email.startsWith("other-")) 20L else contestId
    FakeRequest(method, path).withFormUrlEncodedBody(body: _*)
      .withSession(Secured.UserName -> email, Secured.CurrentContestId -> contest.toString).withCSRFToken
  }

  private def get(app: Application, email: String, path: String): Future[Result] = route(app, request(GET, email, path)).get
  private def post(app: Application, email: String, path: String, body: (String, String)*): Future[Result] =
    route(app, request(POST, email, path, body)).get

  private def review(r: Long) = s"/admin/rounds/$r/review"
  private def distribute(r: Long) = s"/admin/rounds/$r/distribute"
  private def roundUsers(r: Long): Seq[Long] = RoundUser.byRoundId(r).map(_.userId).sorted
  private def files(r: Long): Seq[Long] = RoundImageJdbc.byRound(r).filter(_.status == "in").map(_.pageId)

  /** The round form's fields for round `r` in review, with `extra`. */
  private def edit(r: Round, extra: (String, String)*): Seq[(String, String)] = editNamed(r, "R", extra: _*)

  private def editNamed(r: Round, name: String, extra: (String, String)*): Seq[(String, String)] =
    Seq("id" -> r.getId.toString, "number" -> r.number.toString, "name" -> name, "contest" -> contestId.toString,
      "roles" -> "jury", "distribution" -> "0", "rates" -> "1", "minSize" -> "No", "mediaType" -> "all") ++ extra

  "the round form" should {

    "create a round for review with no juror, an older page's \"Distribute as soon as ready\" ignored" in {
      testDbApp { app =>
        setUp()
        def create(name: String, extra: (String, String)*) = post(app, "admin@example.com", "/admin/rounds/save",
          // no juror chosen: the page's script disables the distribution select, which then sends nothing
          Seq("number" -> "2", "name" -> name, "contest" -> contestId.toString, "roles" -> "jury",
            "rates" -> "1", "minMpx" -> "No", "minSize" -> "No", "mediaType" -> "all") ++ extra: _*)
        val reviewed = create("No jury")
        val reviewedStatus = status(reviewed)
        val atOnce = create("At once", "distributeAfter" -> "true")
        def named(n: String) = Round.findByContest(contestId).find(_.name.contains(n))
        (reviewedStatus === SEE_OTHER) and (named("No jury").map(_.inReview) must beSome(true)) and
          (roundUsers(named("No jury").get.getId) must beEmpty) and
          (status(atOnce) === SEE_OTHER) and (named("At once").map(_.inReview) must beSome(true))
      }
    }
  }

  "the jurors form of a round without jurors" should {

    "offer its admin the jurors to pick (the contest's, chosen), and its organizer no form" in {
      testDbApp { app =>
        val (round, jurors) = setUp()
        val admin = contentAsString(get(app, "admin@example.com", s"/admin/rounds/${round.getId}/jurors"))
        val organizer = contentAsString(get(app, "organizer@example.com", review(round.getId)))
        (admin must contain("name=\"jurorsShown\"")) and (admin must contain("name=\"jury\" value=\"|\"")) and (admin must contain("id=\"jurors\"")) and
          (admin must contain(s"""<option value="${jurors.head.getId}" selected>""")) and
          (admin must contain("javascripts/round-form.js")) and
          (organizer must not(contain("id=\"round-review-distribute\""))) and (organizer must not(contain("id=\"jurors\"")))
      }
    }

    "choose the rating and file score on the jurors step and save them with distribution" in {
      testDbAppWith() { app =>
        val (round, jurors) = setUp()
        val edit = org.jsoup.Jsoup.parse(contentAsString(get(app, "admin@example.com", s"/admin/rounds/edit?contestId=$contestId")))
        val page = org.jsoup.Jsoup.parse(contentAsString(get(app, "admin@example.com", s"/admin/rounds/${round.getId}/jurors")))
        val result = post(app, "admin@example.com", distribute(round.getId), "jurorsShown" -> "true",
          "jurors[0]" -> jurors.head.getId.toString, "distribution" -> "0", "rates" -> "5", "averageRule" -> "sum")
        val resultStatus = status(result)
        val saved = Round.findById(round.getId).get
        (edit.select("select#rates, select#averageRule").size === 0) and
          (page.select("#round-jurors-form select#rates, #round-jurors-form select#averageRule").size === 2) and
          (resultStatus === SEE_OTHER) and (flash(result).get("success") must beSome) and
          (saved.rates.id === 5) and (saved.averageRule.param === "sum")
      }(Map("jobs.mode" -> "inline"))
    }

    "configure criteria with the rating on the jurors step" in {
      testDbAppWith() { app =>
        val (round, jurors) = setUp()
        val page = org.jsoup.Jsoup.parse(contentAsString(get(app, "admin@example.com", s"/admin/rounds/${round.getId}/jurors")))
        val result = post(app, "admin@example.com", distribute(round.getId), "jurorsShown" -> "true",
          "jurors[0]" -> jurors.head.getId.toString, "rates" -> "5", "averageRule" -> "rated",
          "criteriaShown" -> "true", "criteria[0]" -> "1", "worstMarkShown" -> "true")
        (page.select("#round-criteria select").size > 0 must beTrue) and
          (status(result) === SEE_OTHER) and (flash(result).get("success") must beSome) and
          (RoundCriterion.idsOf(round.getId) === Seq(1L))
      }(Map("jobs.mode" -> "inline"))
    }

    "clear criteria when the selected rating becomes yes or no" in {
      testDbAppWith() { app =>
        val (round, jurors) = setUp()
        DB.localTx { implicit session =>
          Round.updateRates(round.getId, Round.ratesById(5))
          RoundCriterion.updateIn(round.getId, Seq(1L))
        }
        val result = post(app, "admin@example.com", distribute(round.getId), "jurorsShown" -> "true",
          "jurors[0]" -> jurors.head.getId.toString, "rates" -> "1", "criteriaShown" -> "true")
        (status(result) === SEE_OTHER) and (flash(result).get("success") must beSome) and
          (Round.findById(round.getId).get.rates.id === 1) and (RoundCriterion.idsOf(round.getId) must beEmpty)
      }(Map("jobs.mode" -> "inline"))
    }

    "preserve legacy criteria when an unrated type stays unchanged" in {
      testDbAppWith() { app =>
        val (round, jurors) = setUp()
        DB.localTx { implicit session => RoundCriterion.updateIn(round.getId, Seq(1L)) }
        val result = post(app, "admin@example.com", distribute(round.getId), "jurorsShown" -> "true",
          "jurors[0]" -> jurors.head.getId.toString, "rates" -> "1", "criteriaShown" -> "true")
        (status(result) === SEE_OTHER) and (flash(result).get("success") must beSome) and
          (RoundCriterion.idsOf(round.getId) === Seq(1L))
      }(Map("jobs.mode" -> "inline"))
    }

    "allow a settings-free post for a legacy criteria round with an inert worst mark" in {
      testDbAppWith() { app =>
        val (round, jurors) = setUp()
        DB.localTx { implicit session =>
          Round.updateRates(round.getId, Round.ratesById(5))
          RoundCriterion.updateIn(round.getId, Seq(1L))
          Round.updateRound(round.getId, round.copy(worstMark = true))
        }
        val result = post(app, "admin@example.com", distribute(round.getId), "jurorsShown" -> "true",
          "jurors[0]" -> jurors.head.getId.toString)
        (status(result) === SEE_OTHER) and (flash(result).get("success") must beSome) and
          (RoundCriterion.idsOf(round.getId) === Seq(1L)) and (Round.findById(round.getId).get.worstMark must beTrue)
      }(Map("jobs.mode" -> "inline"))
    }

    "retain an inert worst mark when unchanged criteria are submitted" in {
      testDbAppWith() { app =>
        val (round, jurors) = setUp()
        DB.localTx { implicit session =>
          Round.updateRates(round.getId, Round.ratesById(5))
          RoundCriterion.updateIn(round.getId, Seq(1L))
          Round.updateRound(round.getId, round.copy(worstMark = true))
        }
        val result = post(app, "admin@example.com", distribute(round.getId), "jurorsShown" -> "true",
          "jurors[0]" -> jurors.head.getId.toString, "rates" -> "5", "criteriaShown" -> "true", "criteria[0]" -> "1")
        (status(result) === SEE_OTHER) and (flash(result).get("success") must beSome) and
          (Round.findById(round.getId).get.worstMark must beTrue)
      }(Map("jobs.mode" -> "inline"))
    }

    "clear a posted worst mark when switching to an unrated type" in {
      testDbAppWith() { app =>
        val (round, jurors) = setUp()
        DB.localTx { implicit session =>
          Round.updateRates(round.getId, Round.ratesById(5))
          Round.updateRound(round.getId, round.copy(worstMark = true))
        }
        val result = post(app, "admin@example.com", distribute(round.getId), "jurorsShown" -> "true",
          "jurors[0]" -> jurors.head.getId.toString, "rates" -> "1", "worstMarkShown" -> "true", "worstMark" -> "true")
        (status(result) === SEE_OTHER) and (flash(result).get("success") must beSome) and
          (Round.findById(round.getId).get.worstMark must beFalse)
      }(Map("jobs.mode" -> "inline"))
    }

    "warn about changing the score rule after a star vote" in {
      testDbAppWith() { app =>
        val (round, jurors) = setUp()
        DB.localTx { implicit session =>
          Round.updateRates(round.getId, Round.ratesById(5))
          VoteJdbc.create(1L, 3, jurors.head.getId, round.getId)
        }
        val page = org.jsoup.Jsoup.parse(contentAsString(get(app, "admin@example.com", s"/admin/rounds/${round.getId}/jurors")))
        (page.select("#averageRule_warning").size === 1) and
          (page.select("#averageRule").attr("aria-describedby") must contain("averageRule_warning"))
      }(Map("jobs.mode" -> "inline"))
    }

    "refuse a stale jurors page after rating settings changed" in {
      testDbAppWith() { app =>
        val (round, jurors) = setUp()
        val signature = services.RoundService.ratingSignature(round, RoundCriterion.idsOf(round.getId))
        DB.localTx { implicit session => Round.updateRates(round.getId, Round.ratesById(5)) }
        val result = post(app, "admin@example.com", distribute(round.getId), "jurorsShown" -> "true",
          "jurors[0]" -> jurors.head.getId.toString, "rates" -> "1", "ratingSignature" -> signature)
        (status(result) === SEE_OTHER) and (flash(result).get("error") must beSome) and
          (Round.findById(round.getId).get.rates.id === 5) and (roundUsers(round.getId) must beEmpty)
      }(Map("jobs.mode" -> "inline"))
    }

    "reject invalid rating settings and leave the round unchanged" in {
      testDbAppWith() { app =>
        val (round, jurors) = setUp()
        val result = post(app, "admin@example.com", distribute(round.getId), "jurorsShown" -> "true",
          "jurors[0]" -> jurors.head.getId.toString, "rates" -> "2", "averageRule" -> "sum")
        val resultStatus = status(result)
        val saved = Round.findById(round.getId).get
        (resultStatus === SEE_OTHER) and (flash(result).get("error") must beSome) and
          (saved.rates === round.rates) and (saved.averageRule === round.averageRule) and
          (roundUsers(round.getId) must beEmpty) and (saved.inReview must beTrue)
      }(Map("jobs.mode" -> "inline"))
    }

    "roll back the jury and rating when criteria or worst mark are invalid" in {
      testDbAppWith() { app =>
        val (round, jurors) = setUp()
        val body = Seq("jurorsShown" -> "true", "jurors[0]" -> jurors.head.getId.toString,
          "rates" -> "5", "criteriaShown" -> "true", "criteria[0]" -> "1", "criteria[1]" -> "1")
        val duplicate = post(app, "admin@example.com", distribute(round.getId), body: _*)
        val duplicateError = flash(duplicate).get("error")
        val conflict = post(app, "admin@example.com", distribute(round.getId),
          (body.filterNot(_._1 == "criteria[1]") ++ Seq("worstMarkShown" -> "true", "worstMark" -> "true")): _*)
        (duplicateError must beSome) and (flash(conflict).get("error") must beSome) and
          (Round.findById(round.getId).get.rates === round.rates) and
          (RoundCriterion.idsOf(round.getId) must beEmpty) and (roundUsers(round.getId) must beEmpty)
      }(Map("jobs.mode" -> "inline"))
    }

    "refuse Distribute with no juror picked, and distribute to those picked" in {
      testDbApp { app =>
        val (round, jurors) = setUp()
        val r = round.getId
        val none = post(app, "admin@example.com", distribute(r), "jurorsShown" -> "true", "distribution" -> "0")
        val noneFlash = (status(none), flash(none).get("error"))
        val rounds = post(app, "admin@example.com", distribute(r))
        val roundsFlash = flash(rounds).get("error")
        val stillInReview = Round.findById(r).get.inReview
        val done = post(app, "admin@example.com", distribute(r), "jurorsShown" -> "true",
          "jurors[0]" -> jurors(0).getId.toString, "jurors[1]" -> jurors(1).getId.toString, "distribution" -> "1")
        val doneStatus = status(done)
        (noneFlash._1 === SEE_OTHER) and (noneFlash._2 must beSome(contain("has no jurors"))) and
          (roundsFlash must beSome(contain("has no jurors"))) and (stillInReview must beTrue) and
          (doneStatus === SEE_OTHER) and (flash(done).get("success") must beSome) and
          (Round.findById(r).get.openToJurors must beTrue) and (Round.findById(r).get.distribution === 1) and
          (roundUsers(r) === jurors.map(_.getId).sorted) and (VoteJdbc.byRound(r).size === 8)
      }
    }

    "refuse Distribute with jurors to its organizer, a juror and another contest's admin; nothing written" in {
      testDbApp { app =>
        val (round, jurors) = setUp()
        val r = round.getId
        val body = Seq("jurorsShown" -> "true", "jurors[0]" -> jurors.head.getId.toString, "distribution" -> "0",
          "rates" -> "5", "averageRule" -> "sum")
        (status(post(app, "organizer@example.com", distribute(r), body: _*)) === FORBIDDEN) and
          (status(post(app, "juror1@example.com", distribute(r), body: _*)) === FORBIDDEN) and
          (status(post(app, "other-admin@example.com", distribute(r), body: _*)) === FORBIDDEN) and
          (roundUsers(r) must beEmpty) and (Round.findById(r).get.inReview must beTrue) and
          (Round.findById(r).get.rates === round.rates) and (Round.findById(r).get.averageRule === round.averageRule)
      }
    }
  }

  "\"Re-apply filters\"" should {

    "be the round form's button for its admin, re-apply the posted filters and show the result" in {
      testDbApp { app =>
        val (round, _) = setUp()
        val r = round.getId
        val form = contentAsString(get(app, "admin@example.com", s"/admin/rounds/edit?contestId=$contestId&id=$r"))
        val done = post(app, "admin@example.com", "/admin/rounds/save", edit(round, "reapply" -> "true", "minMpx" -> "1"): _*)
        val doneStatus = status(done)
        val page = contentAsString(get(app, "admin@example.com", review(r)))
        (form must contain("name=\"reapply\"")) and (form must contain("Re-apply filters")) and
          (form must not(contain("name=\"newImages\""))) and
          (doneStatus === SEE_OTHER) and (redirectLocation(done) === Some(review(r))) and
          (Round.findById(r).get.minMpx === Some(1)) and (files(r) === (5L to 8L)) and
          (Round.findById(r).get.inReview must beTrue) and
          (page must contain("id=\"round-review-reapplied\"")) and (page must contain("File:Image3.jpg"))
      }
    }

    "be refused to its organizer and another contest's admin, and with a message once the round has a vote" in {
      testDbApp { app =>
        val (round, jurors) = setUp()
        val r = round.getId
        val organizer = post(app, "organizer@example.com", "/admin/rounds/save", edit(round, "reapply" -> "true", "minMpx" -> "1"): _*)
        val other = post(app, "other-admin@example.com", "/admin/rounds/save", edit(round, "reapply" -> "true", "minMpx" -> "1"): _*)
        val refusedStatuses = (status(organizer), status(other))
        scalikejdbc.DB.localTx { implicit s => RoundWrites.lock(Seq(r)); VoteJdbc.create(5L, 0, jurors.head.getId, r) }
        val form = contentAsString(get(app, "admin@example.com", s"/admin/rounds/edit?contestId=$contestId&id=$r"))
        // a refused re-apply saves nothing of the form: its name neither
        val voted = post(app, "admin@example.com", "/admin/rounds/save",
          editNamed(round, "Renamed", "reapply" -> "true", "minMpx" -> "1"): _*)
        (refusedStatuses === ((FORBIDDEN, FORBIDDEN))) and
          (form must not(contain("name=\"reapply\""))) and
          (status(voted) === BAD_REQUEST) and (contentAsString(voted) must contain("has votes")) and
          (Round.findById(r).get.minMpx must beNone) and (files(r).size === 8) and
          (Round.findById(r).get.name === Some("R"))
      }
    }

    "disqualify the round's conflicts of interest when the box is newly ticked, a listed file's too" in {
      testDbApp { app =>
        val (round, jurors) = setUp()
        val r = round.getId
        scalikejdbc.DB.autoCommit { implicit s =>
          sql"UPDATE users SET wiki_account = 'Juror One' WHERE id = ${jurors.head.getId}".update()
          sql"UPDATE images SET author = 'Juror One' WHERE page_id IN (1, 6)".update()
          sql"UPDATE round_image SET added_how = 'list' WHERE round_id = $r AND page_id = 1".update()
        }
        val done = post(app, "admin@example.com", "/admin/rounds/save", edit(round, "reapply" -> "true", "minMpx" -> "No", "excludeCoi" -> "true"): _*)
        val doneStatus = status(done)
        def row(p: Long) = RoundImageJdbc.find(r, p).map(x => x.status)
        def code(p: Long) = scalikejdbc.DB.readOnly(implicit s =>
          sql"SELECT dq_code FROM round_image WHERE round_id = $r AND page_id = $p".map(_.stringOpt(1)).single().flatten)
        // a form shown again says why (its alert), for the failure's message
        val shownAgain = if (doneStatus == SEE_OTHER) "" else
          "(?s)alert-danger[^>]*>(.*?)<".r.findFirstMatchIn(contentAsString(done)).map(_.group(1).trim).getOrElse("?")
        (shownAgain === "") and (doneStatus === SEE_OTHER) and (flash(done).get("success") must beSome(contain("disqualified"))) and
          (row(1L) === Some("dq")) and (code(1L) === Some("coi")) and (row(6L) === Some("dq")) and (code(6L) === Some("coi")) and
          (Round.findById(r).get.excludeCoi must beTrue) and (files(r) === Seq(2L, 3L, 4L, 5L, 7L, 8L)) and
          (RoundImageInvariants.violations(r) must beEmpty)
      }
    }

    "need the CSRF token" in {
      withCsrfApp { app =>
        val (round, _) = setUp()
        val sessionToken = token(app)
        def send(body: (String, String)*) =
          route(app, FakeRequest(POST, "/admin/rounds/save")
            .withHeaders(COOKIE -> "PLAY_SESSION=signed-in", ACCEPT -> "text/html")
            .withSession(Secured.UserName -> "admin@example.com", Secured.CurrentContestId -> contestId.toString,
              "csrfToken" -> sessionToken)
            .withFormUrlEncodedBody(body: _*)).get
        (status(send(edit(round, "reapply" -> "true", "minMpx" -> "1"): _*)) === FORBIDDEN) and
          (Round.findById(round.getId).get.minMpx must beNone) and
          (status(send(edit(round, "reapply" -> "true", "minMpx" -> "1", "csrfToken" -> sessionToken): _*)) === SEE_OTHER) and
          (Round.findById(round.getId).get.minMpx === Some(1))
      }
    }
  }
}
