package controllers

import db.scalikejdbc._
import org.intracer.wmua.Image
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import scala.concurrent.Future

/** The round form's worst mark (✗, V92): offered with its help, off by default; saved on an
  * existing rated round, on and off; kept by a client without the checkbox; refused with the
  * form's error for a yes/no round and for a criteria round, unchanged; refused, unchanged, to a
  * juror, an organizer and another contest's admin.
  */
class RoundWorstMarkFormSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val contestId = 10L

  private def member(email: String, role: String, contest: Long = contestId): User =
    User.create(User(email, email, None, Set(role), Some(services.PasswordHasher.default.hash("secret")), Some(contest)))

  private def setUp(rates: Rates = Round.ratesById(5)): Round = {
    createContests(contestId, 20)
    imageDao.batchInsert(Seq(Image(1, "File:Image1.jpg", None, None, 640, 480, Some("12-345-1"))))
    val juror = member("juror@example.com", "jury")
    member("organizer@example.com", "organizer")
    member("admin@example.com", "admin")
    member("other-admin@example.com", "admin", contest = 20)
    val round = roundDao.create(Round(None, 1, Some("R"), contestId, rates = rates, active = true))
    round.addUsers(Seq(RoundUser(round.getId, juror.getId, "jury", active = true)))
    VoteJdbc.create(1, 0, juror.getId, round.getId)
    round
  }

  /** The form as the page posts it: `worst` the checkbox (None: unchecked), `shown` the hidden
    * field the page has with the checkbox.
    */
  private def form(round: Round, worst: Boolean, shown: Boolean = true, extra: Seq[(String, String)] = Nil) =
    Seq("id" -> round.getId.toString, "number" -> round.number.toString, "name" -> "R",
      "contest" -> round.contestId.toString, "roles" -> "jury", "distribution" -> "0",
      "rates" -> round.rates.id.toString, "minMpx" -> "", "minSize" -> "", "mediaType" -> "all") ++
      User.findRoundJurors(round.getId).zipWithIndex.map { case (u, i) => s"jurors[$i]" -> u.getId.toString } ++
      Option.when(worst)("worstMark" -> "true").toSeq ++ Option.when(shown)("worstMarkShown" -> "true").toSeq ++ extra

  private def save(app: Application, email: String, body: Seq[(String, String)]): Future[Result] =
    route(app, FakeRequest(POST, "/admin/rounds/save")
      .withSession(Secured.UserName -> email, Secured.CurrentContestId -> contestId.toString)
      .withFormUrlEncodedBody(body: _*)
      .withCSRFToken).get

  private def page(app: Application, email: String, path: String): Future[Result] =
    route(app, FakeRequest(GET, path)
      .withSession(Secured.UserName -> email, Secured.CurrentContestId -> contestId.toString)
      .withCSRFToken).get

  private def stored(round: Round): Boolean = Round.findById(round.getId).get.worstMark

  "the round form's worst mark" should {

    "be offered unchecked for a new round, with its help and the hidden field" in {
      testDbApp { app =>
        setUp()
        val doc = org.jsoup.Jsoup.parse(contentAsString(page(app, "admin@example.com",
          s"/admin/rounds/edit?contestId=$contestId")))
        val box = doc.getElementById("worstMark")
        (box must not(beNull)) and (box.hasAttr("checked") must beFalse) and
          (doc.select("#round-worst-mark input[type=hidden][name=worstMarkShown]").size === 1) and
          (doc.getElementById("round-worst-mark").text must contain("Worst mark")) and
          // words only: the ✗ glyph is in no font the page uses (the icon is bi-x-lg)
          (doc.getElementById("round-worst-mark").text must not(contain("✗")))
      }
    }

    "be saved on and off on a rated round, kept by a client without the checkbox, shown checked" in {
      testDbApp { app =>
        val round = setUp()
        (status(save(app, "admin@example.com", form(round, worst = true))) === SEE_OTHER) and
          (stored(round) must beTrue) and
          (org.jsoup.Jsoup.parse(contentAsString(page(app, "admin@example.com",
            s"/admin/rounds/edit?contestId=$contestId&id=${round.getId}"))).getElementById("worstMark")
            .hasAttr("checked") must beTrue) and
          (status(save(app, "admin@example.com", form(round, worst = false, shown = false))) === SEE_OTHER) and
          (stored(round) must beTrue) and
          (status(save(app, "admin@example.com", form(round, worst = false))) === SEE_OTHER) and
          (stored(round) must beFalse)
      }
    }

    "be refused for a yes/no round, with the form's error, the round unchanged" in {
      testDbApp { app =>
        val round = setUp(Round.binaryRound)
        val result = save(app, "admin@example.com", form(round, worst = true))
        (status(result) === BAD_REQUEST) and
          (contentAsString(result) must contain("The worst mark is only for a rated round without criteria.")) and
          (stored(round) must beFalse)
      }
    }

    "be refused for a criteria round, posted with its criteria or by a client without the selects, unchanged" in {
      testDbApp { app =>
        createContests(contestId, 20)
        member("admin@example.com", "admin")
        val juror = member("juror@example.com", "jury")
        val criteria = CriterionJdbc.all().take(2).map(_.id)
        val round = roundDao.create(Round(None, 1, Some("R"), contestId, rates = Round.ratesById(10), active = true,
          criterionIds = criteria))
        round.addUsers(Seq(RoundUser(round.getId, juror.getId, "jury", active = true)))
        val withSelects = save(app, "admin@example.com", form(round, worst = true,
          extra = Seq("criteriaShown" -> "true") ++ criteria.zipWithIndex.map { case (id, i) => s"criteria[$i]" -> id.toString }))
        val withoutSelects = save(app, "admin@example.com", form(round, worst = true))
        (criteria.size === 2) and (status(withSelects) === BAD_REQUEST) and (status(withoutSelects) === BAD_REQUEST) and
          (contentAsString(withoutSelects) must contain("only for a rated round without criteria")) and
          (stored(round) must beFalse)
      }
    }

    "refuse a new round's rejected-by without a previous round that may reject, \"only\" included, by its message" in {
      testDbApp { app =>
        val rated = setUp()
        val criteriaRound = roundDao.create(Round(None, 2, Some("C"), contestId, rates = Round.ratesById(10),
          criterionIds = CriterionJdbc.all().take(2).map(_.id)))
        val juror = User.findRoundJurors(rated.getId).head
        VoteJdbc.rate(1, juror.getId, rated.getId, -1) // the file's ✗, which "only" keeps
        def newRound(previous: Seq[Round], mode: String) =
          Seq("number" -> "3", "name" -> "Next", "contest" -> contestId.toString, "roles" -> "jury",
            "distribution" -> "0", "rates" -> "5", "minMpx" -> "", "minSize" -> "", "mediaType" -> "all",
            "jurors[0]" -> juror.getId.toString, "rejectedMode" -> mode, "rejectedBy" -> "1") ++
            previous.zipWithIndex.map { case (r, i) => s"previousRound[$i]" -> r.getId.toString }
        val fromCriteria = save(app, "admin@example.com", newRound(Seq(criteriaRound), "only"))
        val fromNone = save(app, "admin@example.com", newRound(Nil, "exclude"))
        val before = Round.findByContest(contestId).size
        (status(fromCriteria) === BAD_REQUEST) and
          (contentAsString(fromCriteria) must contain("needs a previous round with rejections")) and
          (status(fromNone) === BAD_REQUEST) and (Round.findByContest(contestId).size === before) and
          // a rated round without criteria may reject (its ✗): taken
          (status(save(app, "admin@example.com", newRound(Seq(rated), "only"))) === SEE_OTHER)
      }
    }

    "refuse an unknown rejected-by mode with 400 (V93)" in {
      testDbApp { app =>
        val round = setUp()
        status(save(app, "admin@example.com", form(round, worst = false,
          extra = Seq("rejectedBy" -> "1", "rejectedMode" -> "everything")))) === BAD_REQUEST
      }
    }

    "refuse a juror, an organizer and another contest's admin, the option unchanged" in {
      testDbApp { app =>
        val round = setUp()
        (Seq("juror@example.com", "organizer@example.com", "other-admin@example.com").map(email =>
          status(save(app, email, form(round, worst = true)))) === Seq(FORBIDDEN, FORBIDDEN, FORBIDDEN)) and
          (stored(round) must beFalse)
      }
    }
  }
}
