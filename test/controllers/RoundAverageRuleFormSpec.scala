package controllers

import db.scalikejdbc._
import org.intracer.wmua.{AverageRule, Image}
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import scala.concurrent.Future

/** The round form's score option (V90, [[AverageRule]]): offered to the contest's admins with its
  * three rules and their help, in every language; saved on an existing round at any time, open or
  * stopped; kept by a client without the select; refused, unchanged, to others and to an unknown
  * value; shown on the round's statistics page.
  */
class RoundAverageRuleFormSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val contestId = 10L

  private def member(email: String, role: String, contest: Long = contestId): User =
    User.create(User(email, email, None, Set(role), Some(services.PasswordHasher.default.hash("secret")), Some(contest)))

  private def setUp(): Round = {
    createContests(contestId, 20)
    imageDao.batchInsert(Seq(Image(1, "File:Image1.jpg", None, None, 640, 480, Some("12-345-1"))))
    val juror = member("juror@example.com", "jury")
    member("organizer@example.com", "organizer")
    member("admin@example.com", "admin")
    member("other-admin@example.com", "admin", contest = 20)
    val round = roundDao.create(Round(None, 1, Some("R"), contestId, rates = Round.ratesById(5), active = true))
    round.addUsers(Seq(RoundUser(round.getId, juror.getId, "jury", active = true)))
    VoteJdbc.create(1, 0, juror.getId, round.getId)
    round
  }

  private def form(round: Round, rule: Option[String]): Seq[(String, String)] =
    Seq("id" -> round.getId.toString, "number" -> round.number.toString, "name" -> "R",
      "contest" -> round.contestId.toString, "roles" -> "jury", "distribution" -> "0", "rates" -> "5",
      "minMpx" -> "", "minSize" -> "", "mediaType" -> "all") ++
      User.findRoundJurors(round.getId).zipWithIndex.map { case (u, i) => s"jurors[$i]" -> u.getId.toString } ++
      rule.map("averageRule" -> _).toSeq

  private def save(app: Application, email: String, body: Seq[(String, String)]): Future[Result] =
    route(app, FakeRequest(POST, "/admin/rounds/save")
      .withSession(Secured.UserName -> email, Secured.CurrentContestId -> contestId.toString)
      .withFormUrlEncodedBody(body: _*)
      .withCSRFToken).get

  private def page(app: Application, email: String, path: String, lang: String = "en"): Future[Result] =
    route(app, FakeRequest(GET, path)
      .withSession(Secured.UserName -> email, Secured.CurrentContestId -> contestId.toString)
      .withHeaders(ACCEPT_LANGUAGE -> lang)
      .withCSRFToken).get

  private def rule(round: Round): AverageRule = Round.findById(round.getId).get.averageRule

  "the round form's score option" should {

    "be offered to an admin with the three rules, rated chosen for a new round, in every language" in {
      testDbApp { app =>
        val round = setUp()
        val html = contentAsString(page(app, "admin@example.com", s"/admin/rounds/edit?contestId=$contestId"))
        val doc = org.jsoup.Jsoup.parse(html)
        val select = doc.getElementById("averageRule")
        (select must not(beNull)) and
          (select.select("option").eachAttr("value").toArray.toSeq === Seq("rated", "assigned", "sum")) and
          (select.select("option[selected]").attr("value") === "rated") and
          (html must contain("Total of the rates (a file a juror skipped adds nothing)")) and
          forall(Seq("uk" -> "Сума оцінок", "ru" -> "Сумма оценок", "fr" -> "Total des notes")) { case (lang, text) =>
            contentAsString(page(app, "admin@example.com", s"/admin/rounds/edit?contestId=$contestId&id=${round.getId}",
              lang)) must contain(text)
          }
      }
    }

    "save the rule on an open round, and on a stopped one, keeping it for a client without the select" in {
      testDbApp { app =>
        val round = setUp()
        (status(save(app, "admin@example.com", form(round, Some("sum")))) === SEE_OTHER) and
          (rule(round) === AverageRule.Sum) and {
            Round.setActive(round.getId, active = false)
            (status(save(app, "admin@example.com", form(round, Some("assigned")))) === SEE_OTHER) and
              (rule(round) === AverageRule.Assigned)
          } and
          (status(save(app, "admin@example.com", form(round, None))) === SEE_OTHER) and
          (rule(round) === AverageRule.Assigned) and
          // the edit form shows the stored rule
          (org.jsoup.Jsoup.parse(contentAsString(page(app, "admin@example.com",
            s"/admin/rounds/edit?contestId=$contestId&id=${round.getId}"))).select("#averageRule option[selected]")
            .attr("value") === "assigned")
      }
    }

    "refuse an unknown value, and a juror, an organizer and another contest's admin, the rule unchanged" in {
      testDbApp { app =>
        val round = setUp()
        (status(save(app, "admin@example.com", form(round, Some("active")))) === BAD_REQUEST) and
          (Seq("juror@example.com", "organizer@example.com", "other-admin@example.com").map(email =>
            status(save(app, email, form(round, Some("sum"))))) === Seq(FORBIDDEN, FORBIDDEN, FORBIDDEN)) and
          (rule(round) === AverageRule.Rated)
      }
    }

    "be shown on the round's statistics" in {
      testDbApp { app =>
        val round = setUp()
        Round.updateRound(round.getId, round.copy(averageRule = AverageRule.Sum))
        contentAsString(page(app, "admin@example.com", s"/roundstat/${round.getId}")) must
          contain("File score: Total of the rates")
      }
    }
  }
}
