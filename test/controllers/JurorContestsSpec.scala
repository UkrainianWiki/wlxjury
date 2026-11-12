package controllers

import db.scalikejdbc.{PlayTestDb, Round, RoundUser, User, UserContestJdbc}
import org.jsoup.Jsoup
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test._

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** A juror who keeps a past year's membership ([[JurorContests]]): the contest switcher leaves
  * out a juror-only contest without a round open to them (the current one always shown), the
  * start page goes on to a contest of theirs with an open round, and the "no round yet" page has
  * no link back to the start page (which showed it again), but Sign out and the user's other
  * contests.
  */
class JurorContestsSpec extends PlaySpecification with PlayTestDb {

  sequential

  private def member(email: String, memberships: (Long, String)*): User = {
    val (firstContest, firstRole) = memberships.head
    val created = User.create(User(email, email, None, Set(firstRole), Some("hash"), Some(firstContest)))
    memberships.tail.foreach { case (contest, role) => UserContestJdbc.createMembership(created.getId, contest, role) }
    created
  }

  /** A round of the contest open to the juror (or with the juror stopped, `jurorActive` false). */
  private def openRound(contestId: Long, juror: User, active: Boolean = true, jurorActive: Boolean = true,
      distributing: Boolean = false): Round = {
    val round = Round.create(Round(None, 1, contestId = contestId, active = active))
    round.addUsers(Seq(RoundUser(round.getId, juror.getId, "jury", active = jurorActive)))
    if (distributing)
      scalikejdbc.DB.autoCommit(implicit s => scalikejdbc.SQL("UPDATE round SET distributing = TRUE WHERE id = ?")
        .bind(round.getId).update.apply())
    round
  }

  private def send(app: Application, method: String, path: String, user: User, contestId: Long): Future[Result] =
    route(app, FakeRequest(method, path)
      .withHeaders(ACCEPT -> "text/html")
      .withSession(Secured.UserName -> user.email, Secured.CurrentContestId -> contestId.toString).withCSRFToken).get

  /** The contests the navbar's switcher offers on the user's profile page (none: no switcher). */
  private def switcher(app: Application, user: User, contestId: Long): Seq[String] = {
    val html = contentAsString(send(app, GET, s"/users/edit/${user.getId}", user, contestId))
    Jsoup.parse(html).select("form.navbar-menu-form[action^=/switch-contest/]").asScala.map(_.attr("action")).toSeq
  }

  "the contests with an open round" should {
    "be those with an active, not distributing round where the juror is active" in {
      testDbApp { implicit app =>
        createContests(10, 20, 30, 40, 50)
        val juror = member("juror@example.com", 10L -> "jury")
        openRound(10, juror)
        openRound(20, juror, active = false)
        openRound(30, juror, jurorActive = false)
        openRound(40, juror, distributing = true)
        (Round.contestsWithOpenRound(juror.getId, Seq(10L, 20L, 30L, 40L, 50L)) === Set(10L)) and
          (Round.contestsWithOpenRound(juror.getId, Nil) === Set.empty[Long])
      }
    }
  }

  "a juror with last year's membership and this year's round" should {

    "see no switcher in this year's contest: last year's has no open round" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val juror = member("juror@example.com", 20L -> "jury", 10L -> "jury")
        openRound(20, juror)
        (switcher(app, juror, 20) must beEmpty) and
          // in last year's contest (switched by hand), both: where they are and where to go
          (switcher(app, juror, 10).sorted === Seq("/switch-contest/10", "/switch-contest/20"))
      }
    }

    "still switch to last year's contest by a POST, and be taken back by the start page" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val juror = member("juror@example.com", 20L -> "jury", 10L -> "jury")
        val round = openRound(20, juror)

        val switched = send(app, POST, "/switch-contest/10", juror, 20)
        val start = send(app, GET, "/", juror, 10)
        (status(switched) === SEE_OTHER) and
          (session(switched).get(Secured.CurrentContestId) must beSome("10")) and
          (status(start) === SEE_OTHER) and
          (redirectLocation(start) must beSome(contain(s"/round/${round.getId}/"))) and
          (session(start).get(Secured.CurrentContestId) must beSome("20"))
      }
    }

    "open this year's round from a gallery link without a round in last year's contest" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val juror = member("juror@example.com", 20L -> "jury", 10L -> "jury")
        openRound(20, juror)
        val gallery = send(app, GET, s"/gallery/user/${juror.getId}/page/1", juror, 10)
        (status(gallery) === OK) and
          (contentAsString(gallery) must not(contain("No round configured yet."))) and
          (session(gallery).get(Secured.CurrentContestId) must beSome("20"))
      }
    }
  }

  "a juror without an open round anywhere" should {
    "get the no-round page with Sign out and no link back to the start page" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val juror = member("juror@example.com", 20L -> "jury", 10L -> "jury")
        val start = send(app, GET, "/", juror, 20)
        val alert = Jsoup.parse(contentAsString(start)).select("main .alert, .alert").first()
        (status(start) === OK) and
          (alert.text must contain("No round configured yet.")) and
          (alert.select("form.no-round-logout[method=post][action=/logout]").size === 1) and
          (alert.select("a[href=/]").size === 0) and
          (alert.select("form.no-round-switch").size === 0) and
          (session(start).get(Secured.CurrentContestId) must beNone.or(beSome("20")))
      }
    }
  }

  "an organizer" should {
    "keep all their contests in the switcher" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val organizer = member("org@example.com", 20L -> "organizer", 10L -> "organizer")
        switcher(app, organizer, 20).sorted === Seq("/switch-contest/10", "/switch-contest/20")
      }
    }
  }

  "a juror in one contest and an organizer in another" should {
    "be offered the organizer's contest, not a juror one without an open round, also on the no-round page" in {
      testDbApp { implicit app =>
        createContests(10, 20, 30)
        val mixed = member("mixed@example.com", 30L -> "jury", 20L -> "organizer", 10L -> "jury")
        val start = send(app, GET, "/", mixed, 30)
        val switches = Jsoup.parse(contentAsString(start)).select("form.no-round-switch").asScala.map(_.attr("action")).toSeq
        (switcher(app, mixed, 30).sorted === Seq("/switch-contest/20", "/switch-contest/30")) and
          (status(start) === OK) and
          (switches === Seq("/switch-contest/20"))
      }
    }
  }
}
