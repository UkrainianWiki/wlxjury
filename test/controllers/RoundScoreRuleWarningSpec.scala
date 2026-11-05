package controllers

import db.scalikejdbc._
import org.intracer.wmua.{AverageRule, Image}
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import play.api.Application
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

/** The round form's warning beside the score rule ([[RoundController.ScoreRuleWarning]]): in
  * the page, without scripts, for a rated round with a rated vote, described by the select;
  * naming the later rounds that took a top-N or a minimum score from it; not on a new round's
  * form, nor on a round whose votes are all unrated, nor on a yes/no round.
  */
class RoundScoreRuleWarningSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val contestId = 10L

  private def member(email: String, role: String): User =
    User.create(User(email, email, None, Set(role), Some(services.PasswordHasher.default.hash("secret")), Some(contestId)))

  /** A round of the contest with one juror and one vote of `rate` on its file. */
  private def setUp(rate: Int, rates: Int = 5, rule: AverageRule = AverageRule.Rated): Round = {
    createContests(contestId)
    imageDao.batchInsert(Seq(Image(1, "File:Image1.jpg", None, None, 640, 480, Some("12-345-1"))))
    val juror = member("juror@example.com", "jury")
    member("admin@example.com", "admin")
    val round = roundDao.create(Round(None, 1, Some("R"), contestId, rates = Round.ratesById(rates), active = true,
      averageRule = rule))
    round.addUsers(Seq(RoundUser(round.getId, juror.getId, "jury", active = true)))
    VoteJdbc.create(1, rate, juror.getId, round.getId)
    round
  }

  private def page(app: Application, path: String): Document =
    Jsoup.parse(contentAsString(route(app, FakeRequest(GET, path)
      .withSession(Secured.UserName -> "admin@example.com", Secured.CurrentContestId -> contestId.toString)
      .withCSRFToken).get))

  private def editPage(app: Application, round: Round): Document =
    page(app, s"/admin/rounds/edit?contestId=$contestId&id=${round.getId}")

  "the score rule's warning" should {

    "be in the page for a rated round with a rated vote, described by the select, with the saved rule" in {
      testDbApp { app =>
        val round = setUp(rate = 4, rule = AverageRule.Assigned)
        val doc = editPage(app, round)
        val warning = doc.getElementById("averageRule_warning")
        (warning must not(beNull)) and
          (warning.text must contain("Changing the score rule re-scores and re-orders this round's results")) and
          (warning.text must contain("takes effect at once: nothing is recomputed in the background")) and
          (warning.text must contain("A distribution already queued or running keeps the rule it started with")) and
          (doc.getElementById("averageRule").attr("aria-describedby").split(" ").toSeq must
            contain("averageRule_help", "averageRule_warning")) and
          (doc.getElementById("averageRule_warning_row").attr("data-stored-rule") === "assigned") and
          // shown without scripts: no hidden attribute, no class hiding it
          (doc.getElementById("averageRule_warning_row").hasAttr("hidden") must beFalse) and
          (doc.getElementById("averageRule_warning_later") must beNull)
      }
    }

    "name the later rounds that took their top-N or minimum score from it" in {
      testDbApp { app =>
        val round = setUp(rate = 4)
        val prev = Some(round.getId.toString)
        roundDao.create(Round(None, 2, Some("Top"), contestId, rates = Round.ratesById(5), previous = prev,
          topImages = Some(10)))
        roundDao.create(Round(None, 3, None, contestId, rates = Round.ratesById(5), previous = prev,
          prevMinAvgRate = Some(BigDecimal(3))))
        // from it, but with neither: not named
        roundDao.create(Round(None, 4, Some("All"), contestId, rates = Round.ratesById(5), previous = prev))
        val later = editPage(app, round).getElementById("averageRule_warning_later")
        (later must not(beNull)) and
          (later.text === "Round(s) 2 (Top), 3 used this round's scores when they were created; their files won't " +
            "change. A later \"Distribute new files\" there uses the new rule.")
      }
    }

    "not be on a new round's form, a round whose votes are unrated, or a yes/no round" in {
      testDbApp { app =>
        val unrated = setUp(rate = 0)
        val newForm = page(app, s"/admin/rounds/edit?contestId=$contestId")
        val binary = roundDao.create(Round(None, 2, Some("B"), contestId, rates = Round.binaryRound, active = true))
        VoteJdbc.create(1, 1, User.findByEmail("juror@example.com").head.getId, binary.getId)
        val absent = (doc: Document) =>
          (doc.getElementById("averageRule") must not(beNull)) and
            (doc.getElementById("averageRule_warning_row") must beNull) and
            (doc.getElementById("averageRule").attr("aria-describedby").split(" ").toSeq must
              not(contain("averageRule_warning")))
        absent(newForm) and absent(editPage(app, unrated)) and absent(editPage(app, binary))
      }
    }
  }
}
