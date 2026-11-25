package controllers

import db.scalikejdbc._
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._

import scala.concurrent.Future

/** A round's edges on its own pages (round statistics, review, Edit round; [[services.RoundLineage.links]]):
  * "Created from" (its previous rounds) and "Rounds created from it", linked, cancelled creations left out;
  * nothing for a round with neither; its admins and organizers see them (Edit round is the admins'), a
  * juror gets no organiser page.
  */
class RoundLinksSpec extends PlaySpecification with PlayTestDb {

  sequential

  private implicit val autoSession: DBSession = AutoSession

  private val contestId = 10L

  private def member(email: String, role: String): User =
    User.create(User(email, email, None, Set(role), Some(services.PasswordHasher.default.hash("secret")), Some(contestId)))

  /** Rounds 1 "First" → 2 "Second" → 3 "Third" (and 4, cancelled, from 2), 5 with no edge; its admin,
    * organizer and a juror of round 2.
    */
  private def setUp(): (Seq[Round], Map[String, User]) = {
    createContests(contestId)
    val users = Map("admin" -> member("admin@example.com", "admin"),
      "organizer" -> member("organizer@example.com", "organizer"), "juror" -> member("juror@example.com", "jury"))
    val first = roundDao.create(Round(None, 1, Some("First"), contestId, distribution = 0))
    val second = roundDao.create(Round(None, 2, Some("Second"), contestId, distribution = 0, previous = Some(first.getId.toString)))
    val third = roundDao.create(Round(None, 3, Some("Third"), contestId, distribution = 0, previous = Some(second.getId.toString)))
    val cancelled = roundDao.create(Round(None, 4, Some("Gone"), contestId, distribution = 0, previous = Some(second.getId.toString)))
    sql"UPDATE round SET status = ${Round.Status.Cancelled}, distributing = 1 WHERE id = ${cancelled.getId}".update()
    val lone = roundDao.create(Round(None, 5, None, contestId, distribution = 0))
    second.addUsers(Seq(RoundUser(second.getId, users("juror").getId, "jury", active = true)))
    (Seq(first, second, third, cancelled, lone), users)
  }

  private def get(app: Application, email: String, path: String): Future[Result] =
    route(app, FakeRequest(GET, path)
      .withSession(Secured.UserName -> email, Secured.CurrentContestId -> contestId.toString).withCSRFToken).get

  private def link(r: Round, label: String) =
    s"""<a href="/roundstat/${r.getId}" data-round="${r.getId}">$label</a>"""

  private def stat(r: Round) = s"/roundstat/${r.getId}"
  private def review(r: Round) = s"/admin/rounds/${r.getId}/review"
  private def edit(r: Round) = s"/admin/rounds/edit?id=${r.getId}&contestId=$contestId"

  /** The links block of a page: from its start to its end. */
  private def block(body: String): String = {
    val start = body.indexOf("id=\"round-links\"")
    if (start < 0) "" else body.substring(start, body.indexOf("</ul>", start))
  }

  "a round's own pages" should {

    "link the rounds it was created from and those created from it, leaving out a cancelled one" in {
      testDbApp { app =>
        val (Seq(first, second, third, cancelled, _), _) = setUp()
        Seq(("admin@example.com", Seq(stat(second), review(second), edit(second))),
          ("organizer@example.com", Seq(stat(second), review(second)))).flatMap { case (email, pages) =>
          pages.map { p =>
            val result = get(app, email, p)
            val links = block(contentAsString(result))
            (status(result) aka s"$email $p" must_== OK) and
              (links aka s"$email $p" must contain("id=\"round-links-from\"")) and
              (links must contain("Created from: " + link(first, "Round 1 (First)"))) and
              (links must contain("Rounds created from it: " + link(third, "Round 3 (Third)"))) and
              (links aka s"$email $p: the cancelled creation" must not(contain(s"data-round=\"${cancelled.getId}\"")))
          }
        }.reduce(_ and _)
      }
    }

    "show only the direction a round has, and nothing for a round with no edge" in {
      testDbApp { app =>
        val (Seq(first, second, third, _, lone), _) = setUp()
        val firstLinks = block(contentAsString(get(app, "admin@example.com", stat(first))))
        val thirdLinks = block(contentAsString(get(app, "admin@example.com", stat(third))))
        // round 2's only next round is its cancelled one: round 3 has none
        (firstLinks must not(contain("round-links-from"))) and (firstLinks must contain(link(second, "Round 2 (Second)"))) and
          (thirdLinks must contain(link(second, "Round 2 (Second)"))) and (thirdLinks must not(contain("round-links-next"))) and
          Seq(stat(lone), review(lone), edit(lone)).map { p =>
            contentAsString(get(app, "admin@example.com", p)) aka p must not(contain("id=\"round-links\""))
          }.reduce(_ and _)
      }
    }

    "show a juror none of them: no organiser page, and their gallery as before" in {
      testDbApp { app =>
        val (Seq(_, second, _, _, _), users) = setUp()
        val statPage = get(app, "juror@example.com", stat(second))
        val reviewPage = get(app, "juror@example.com", review(second))
        val gallery = get(app, "juror@example.com", s"/gallery/round/${second.getId}/user/${users("juror").getId}/page/1")
        (status(statPage) === FORBIDDEN) and (contentAsString(statPage) must not(contain("round-links"))) and
          (status(reviewPage) === FORBIDDEN) and (contentAsString(reviewPage) must not(contain("round-links"))) and
          (contentAsString(gallery) must not(contain("round-links")))
      }
    }
  }
}
