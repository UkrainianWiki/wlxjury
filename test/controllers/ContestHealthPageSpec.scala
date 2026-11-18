package controllers

import db.scalikejdbc.{PlayTestDb, User}
import play.api.Application
import play.api.mvc.Result
import play.api.test._
import services.diagnostics.HealthFixtures
import services.imports.ImportState

import scala.concurrent.Future

/** The contest health page (support diagnostics plan, D-1a, DG2): for root and the contest's
  * admins only; organizers, jurors, inactive members, board members and other contests' admins get
  * the 403 page (logged on `security.authz`), signed out the login page. The Nepal case's checks
  * and facts on the page, no other contest's data, and masked addresses. A page GET switches the
  * session's contest, as every admin page does.
  */
class ContestHealthPageSpec extends PlaySpecification with PlayTestDb {

  sequential

  private def page(by: Option[User], contestId: Long, current: Option[Long] = None)(implicit
      app: Application): Future[Result] = {
    val request = FakeRequest(GET, s"/admin/contests/$contestId/health")
    route(app, by.fold(request)(u => request.withSession(
      Seq(Secured.UserName -> u.email) ++ current.map(Secured.CurrentContestId -> _.toString): _*))).get
  }

  "the contest health page [A-29]" should {

    "show the Nepal case's checks and facts to the contest's admin, and to root" in {
      testDbApp { implicit app =>
        val n = HealthFixtures.nepal()
        val other = HealthFixtures.contestWithFiles("Hidden Contest", 2026, "", Some("Category:Hidden source"), Seq(900L))
        HealthFixtures.importJob(other, "Category:Hidden other import", ImportState(seen = 1, inserted = 1))
        val r = page(Some(n.admin), n.contest.getId, n.contest.id)
        status(r) === OK
        header(CACHE_CONTROL, r) must beSome("no-store")
        val html = contentAsString(r)
        html must contain("<h1 class=\"h3\">Contest health</h1>")
        // H1, H2, H4, H7, H8 with their hints
        html must contain("Import sources that name another contest type, year or country than this contest: 3")
        html must contain("12 of 32 files are in none of this contest&#x27;s country categories")
        html must contain(s"Import job ${n.winnersImport} left out 12 files as in no country category")
        html must contain("Round 2 has no files.")
        html must contain("Round 1 takes every file of the contest")
        // the facts: the sources with their differences, the countries, the empty round's source and filter
        html must contain(HealthFixtures.NepalCategory)
        html must contain("another contest type").and(contain("another year"))
        html must contain("Wiki Loves Monuments 2026 in Nepal")
        html must contain("<td>Armenia</td><td class=\"text-end\">8</td>")
        html must contain("Round 1: 32 files")
        html must contain("Selected by at least: 1")
        // every hint and label resolved: no raw key
        "health\\.[a-z0-9.]+".r.findAllIn(html.replaceAll("data-key=\"[^\"]*\"", "")).toSeq must beEmpty
        // nothing of the other contest
        html must not(contain("Hidden"))

        val asRoot = page(Some(User.create(User("Root", "root@example.org", None, Set(User.ROOT_ROLE), isRoot = true))),
          n.contest.getId)
        status(asRoot) === OK
        contentAsString(asRoot) must contain("Round 2 has no files.")
      }
    }

    "switch the session's contest on the page GET, as other admin pages do" in {
      testDbApp { implicit app =>
        val n = HealthFixtures.nepal()
        val second = HealthFixtures.contestWithFiles("Wiki Loves Earth", 2026, "", None, Seq(700L))
        db.scalikejdbc.UserContestJdbc.createMembership(n.admin.getId, second.getId, "admin")
        val r = page(Some(n.admin), second.getId, n.contest.id)
        status(r) === OK
        session(r).get(Secured.CurrentContestId) must beSome(second.getId.toString)
      }
    }

    "mask an address typed into a member's name" in {
      testDbApp { implicit app =>
        val n = HealthFixtures.nepal()
        val named = User.create(User("Ann ann.private@example.org", "ann@example.org", None, Set("jury"),
          contestId = n.contest.id))
        HealthFixtures.importJob(n.contest, HealthFixtures.NepalCategory, ImportState(), createdBy = named.id)
        val html = contentAsString(page(Some(n.admin), n.contest.getId, n.contest.id))
        html must contain("Ann a***@example.org")
        html must not(contain("ann.private@"))
      }
    }

    "refuse organizers, jurors, inactive and board members and other contests' admins; signed out, the login page" in {
      testDbApp { implicit app =>
        val n = HealthFixtures.nepal()
        val id = n.contest.getId
        val signedOut = page(None, id)
        status(signedOut) === SEE_OTHER
        redirectLocation(signedOut) must beSome(startWith("/auth"))

        def member(role: String) = User.create(User(role, s"$role@example.org", None, Set(role), contestId = n.contest.id))
        val (_, logs) = LogCapture("security.authz") {
          for (user <- Seq("organizer", "jury", "no", "board").map(member)) {
            val r = page(Some(user), id, n.contest.id)
            status(r) === FORBIDDEN
            contentAsString(r) must not(contain("Round 2 has no files")).and(not(contain(HealthFixtures.NepalCategory)))
          }
        }
        logs("security.authz").count(_.contains(s"/admin/contests/$id/health")) === 4

        // an admin of another contest, from its session's contest or this one's
        val otherContest = HealthFixtures.contestWithFiles("Wiki Loves Earth", 2026, "", None, Seq(800L))
        val otherAdmin = User.create(User("Other", "other.admin@example.org", None, Set("admin"),
          contestId = otherContest.id))
        for (current <- Seq(otherContest.getId, id)) {
          val r = page(Some(otherAdmin), id, Some(current))
          status(r) === FORBIDDEN
          contentAsString(r) must not(contain(HealthFixtures.NepalCategory))
        }
        // a contest that doesn't exist: root gets the 404 page
        val root = User.create(User("Root", "root@example.org", None, Set(User.ROOT_ROLE), isRoot = true))
        status(page(Some(root), 999999L)) === NOT_FOUND
      }
    }
  }
}
