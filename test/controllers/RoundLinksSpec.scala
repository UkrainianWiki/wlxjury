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

  private def member(email: String, role: String, contest: Long = contestId): User =
    User.create(User(email, email, None, Set(role), Some(services.PasswordHasher.default.hash("secret")), Some(contest)))

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

  private def get(app: Application, email: String, path: String, contest: Long = contestId): Future[Result] =
    route(app, FakeRequest(GET, path)
      .withSession(Secured.UserName -> email, Secured.CurrentContestId -> contest.toString).withCSRFToken).get

  private def post(app: Application, email: String, path: String, body: (String, String)*): Future[Result] =
    route(app, FakeRequest(POST, path).withFormUrlEncodedBody(body: _*)
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

    "be refused to another contest's organizer and admin, and Edit round to the round's organizer, with no links" in {
      testDbApp { app =>
        val (Seq(_, second, _, _, _), _) = setUp()
        createContests(20)
        member("other-org@example.com", "organizer", contest = 20)
        member("other-admin@example.com", "admin", contest = 20)
        val refused = Seq(
          ("other-org@example.com", stat(second)), ("other-org@example.com", review(second)),
          ("other-admin@example.com", stat(second)), ("other-admin@example.com", review(second)),
          ("other-admin@example.com", edit(second)), ("organizer@example.com", edit(second)))
        refused.map { case (email, p) =>
          val result = get(app, email, p, contest = if (email.startsWith("other-")) 20L else contestId)
          (status(result) aka s"$email $p" must_== FORBIDDEN) and
            (contentAsString(result) aka s"$email $p" must not(contain("round-links")))
        }.reduce(_ and _)
      }
    }

    "never list a previous round of another contest" in {
      testDbApp { app =>
        setUp()
        createContests(20)
        val elsewhere = roundDao.create(Round(None, 1, Some("Elsewhere"), 20L, distribution = 0))
        val local = roundDao.create(Round(None, 6, Some("Local"), contestId, distribution = 0, previous = Some(elsewhere.getId.toString)))
        Seq(stat(local), review(local), edit(local)).map { p =>
          contentAsString(get(app, "admin@example.com", p)) aka p must not(contain(s"data-round=\"${elsewhere.getId}\""))
        }.reduce(_ and _)
      }
    }

    "show the links on Edit round shown again with an error, as on its page; none for a new round" in {
      testDbApp { app =>
        val (Seq(first, second, third, _, _), _) = setUp()
        // the id and the contest only: the form doesn't bind, and comes back with its errors
        val edited = post(app, "admin@example.com", "/admin/rounds/save", "id" -> second.getId.toString, "contest" -> contestId.toString)
        val created = post(app, "admin@example.com", "/admin/rounds/save", "id" -> "", "contest" -> contestId.toString)
        val links = block(contentAsString(edited))
        (status(edited) === BAD_REQUEST) and (links must contain(link(first, "Round 1 (First)"))) and
          (links must contain(link(third, "Round 3 (Third)"))) and
          (block(contentAsString(get(app, "admin@example.com", edit(second)))) === links) and
          (status(created) === BAD_REQUEST) and (contentAsString(created) must not(contain("id=\"round-links\"")))
      }
    }

    "show a cancelled round's own pages where it came from, and nothing created from it" in {
      testDbApp { app =>
        val (Seq(_, second, _, cancelled, _), _) = setUp()
        Seq(stat(cancelled), review(cancelled)).map { p =>
          val result = get(app, "admin@example.com", p)
          val links = block(contentAsString(result))
          (status(result) aka p must_== OK) and (links aka p must contain("Created from: " + link(second, "Round 2 (Second)"))) and
            (links aka p must not(contain("round-links-next")))
        }.reduce(_ and _)
      }
    }
  }
}
