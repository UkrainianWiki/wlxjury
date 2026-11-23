package controllers

import db.scalikejdbc._
import org.intracer.wmua.Image
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._

import scala.concurrent.Future

/** The warnings of a round nobody can vote in (DG10: warned, never refused), through the router:
  * a juror's voting resumed in a stopped round, and an open round with no juror who can vote (its
  * last juror paused, or the round started so) as a flash after the action; the statistics page's
  * notice (what to do for its admins and root, the fact for its organizers) and the Rounds
  * table's icon for such a round, frozen while open too, never for one being created; none where
  * a juror can vote, nor for a juror or an organizer.
  */
class RoundJurorWarningsSpec extends PlaySpecification with PlayTestDb {

  sequential

  private implicit val autoSession: DBSession = AutoSession

  private val contestId = 10L
  private val Notice = "round-no-active-juror"
  private val Icon = "wlx-round-warning"
  // the admins' notice says what to do (round.warning.nojurors.act); the organizers' only the fact
  private val Act = "add a juror"
  private val Admin = "admin@example.com"

  private def member(email: String, role: String): User =
    User.create(User(email, email, None, Set(role), Some(services.PasswordHasher.default.hash("secret")), Some(contestId)))

  /** Contest 10: round 1, open, with two active jurors; round 2, stopped, with one juror paused;
    * a third juror in no round; its organizer and admin; root.
    */
  private def setUp(): (Round, Round, Map[String, User]) = {
    createContests(contestId)
    imageDao.batchInsert(Seq(1L, 2L).map(i => Image(i, s"File:Image$i.jpg", None, None, 640, 480, Some(s"12-345-$i"))))
    val users = Map(
      "juror" -> member("juror@example.com", "jury"),
      "juror2" -> member("juror2@example.com", "jury"),
      "juror3" -> member("juror3@example.com", "jury"),
      "organizer" -> member("organizer@example.com", "organizer"),
      "admin" -> member(Admin, "admin"),
      "root" -> User.create(User("root@example.com", "root@example.com", None, Set(User.ROOT_ROLE),
        Some(services.PasswordHasher.default.hash("secret"))))
    )
    val open = roundDao.create(Round(None, 1, Some("Open"), contestId, distribution = 0, active = true))
    open.addUsers(Seq("juror", "juror2").map(k => RoundUser(open.getId, users(k).getId, "jury", active = true)))
    val stopped = roundDao.create(Round(None, 2, Some("Stopped"), contestId, distribution = 0, active = false))
    stopped.addUsers(Seq(RoundUser(stopped.getId, users("juror").getId, "jury", active = false)))
    (open, stopped, users)
  }

  /** A third round: `tweak` sets its state in SQL; `jurors` its round_user rows, active or not. */
  private def round3(users: Map[String, User], jurors: Seq[(String, Boolean)], tweak: SQLSyntax): Round = {
    val r = roundDao.create(Round(None, 3, Some("Third"), contestId, distribution = 0, active = false))
    r.addUsers(jurors.map { case (k, a) => RoundUser(r.getId, users(k).getId, "jury", active = a) })
    sql"UPDATE round SET $tweak WHERE id = ${r.getId}".update()
    Round.findById(r.getId).get
  }

  private def request(method: String, email: String, path: String, body: Seq[(String, String)]) =
    FakeRequest(method, path).withFormUrlEncodedBody(body: _*)
      .withSession(Secured.UserName -> email, Secured.CurrentContestId -> contestId.toString).withCSRFToken

  private def post(app: Application, email: String, path: String, body: (String, String)*): Future[Result] =
    route(app, request(POST, email, path, body)).get

  private def get(app: Application, email: String, path: String): Future[Result] =
    route(app, request(GET, email, path, Nil)).get

  private def setJuror(app: Application, round: Round, juror: User, active: Boolean, by: String = Admin) =
    post(app, by, "/round/setrounduser",
      "currentId" -> juror.getId.toString, "parentId" -> round.getId.toString, "setActive" -> active.toString)

  private def setRound(app: Application, round: Round, active: Boolean, by: String = Admin, extra: Seq[(String, String)] = Nil) =
    post(app, by, "/admin/setround", Seq("currentId" -> round.getId.toString, "setActive" -> active.toString) ++ extra: _*)

  private def stat(app: Application, email: String, r: Round): Future[Result] = get(app, email, s"/roundstat/${r.getId}")

  private def roundsList(app: Application, email: String = Admin): Future[Result] =
    get(app, email, s"/admin/rounds?contestId=$contestId")

  private def iconOf(r: Round) = s"""class="$Icon text-warning-emphasis ms-1" data-round="${r.getId}""""

  private def active(round: Round, juror: User): Option[Boolean] =
    RoundUser.byRoundId(round.getId).find(_.userId == juror.getId).map(_.active)

  private def warning(result: Future[Result]): Option[String] = flash(result).get(RoundController.WarningFlash)

  "resuming a juror's voting in a stopped round" should {

    "resume it, with a warning that the round is stopped" in {
      testDbApp { app =>
        val (_, stopped, users) = setUp()
        val result = setJuror(app, stopped, users("juror"), active = true)
        (status(result) === SEE_OTHER) and (active(stopped, users("juror")) must beSome(true)) and
          (warning(result) must beSome(contain("2"))) and (flash(result).get("error") must beNone)
      }
    }

    "say nothing in an open round, nor in one frozen while open (a job reads it)" in {
      testDbApp { app =>
        val (open, _, users) = setUp()
        RoundUser.setActive(open.getId, users("juror").getId, active = false)
        val result = setJuror(app, open, users("juror"), active = true)
        val frozen = round3(users, Seq("juror" -> false),
          sqls"active = 0, freeze_count = 1, active_before_freeze = 1, distributing = 0")
        val inFrozen = setJuror(app, frozen, users("juror"), active = true)
        (status(result) === SEE_OTHER) and (active(open, users("juror")) must beSome(true)) and
          (warning(result) must beNone) and
          (status(inFrozen) === SEE_OTHER) and (active(frozen, users("juror")) must beSome(true)) and
          (warning(inFrozen) must beNone)
      }
    }
  }

  "an open round left with no juror who can vote" should {

    "be warned of when its last juror's voting is paused, which is done" in {
      testDbApp { app =>
        val (open, _, users) = setUp()
        val first = setJuror(app, open, users("juror"), active = false)
        val firstWarning = warning(first)
        val last = setJuror(app, open, users("juror2"), active = false)
        (firstWarning must beNone) and (warning(last) must beSome(contain("1"))) and
          (warning(last) must beSome(contain(Act))) and (active(open, users("juror2")) must beSome(false))
      }
    }

    "be warned of when it is started so, by its admin or root, which is done" in {
      testDbApp { app =>
        val (_, stopped, _) = setUp()
        val started = setRound(app, stopped, active = true)
        val startedWarning = warning(started)
        val nowActive = Round.findById(stopped.getId).map(_.active)
        status(setRound(app, stopped, active = false))
        val byRoot = setRound(app, stopped, active = true, by = "root@example.com")
        (startedWarning must beSome(contain("2"))) and (nowActive must beSome(true)) and
          (status(byRoot) === SEE_OTHER) and (warning(byRoot) must beSome(contain(Act)))
      }
    }

    "not be warned of when the round has a juror who can vote, nor when a round is stopped" in {
      testDbApp { app =>
        val (open, stopped, _) = setUp()
        val stoppedOpen = setRound(app, open, active = false)
        val stoppedOpenWarning = warning(stoppedOpen)
        val startedAgain = setRound(app, open, active = true)
        val startedAgainWarning = warning(startedAgain)
        // a round no juror can vote in, stopped: nothing to warn of
        status(setRound(app, stopped, active = true))
        val stoppedEmpty = setRound(app, stopped, active = false)
        (stoppedOpenWarning must beNone) and (startedAgainWarning must beNone) and
          (status(stoppedEmpty) === SEE_OTHER) and (warning(stoppedEmpty) must beNone) and
          (Round.findById(stopped.getId).map(_.active) must beSome(false))
      }
    }

    "say both the conflicts page's message and the warning when started from that page" in {
      testDbApp { app =>
        val (_, stopped, _) = setUp()
        val result = setRound(app, stopped, active = true, extra = Seq("returnTo" -> "conflicts"))
        val page = route(app, FakeRequest(GET, s"/admin/rounds/${stopped.getId}/conflicts")
          .withSession(Secured.UserName -> Admin, Secured.CurrentContestId -> contestId.toString)
          .withFlash(flash(result).data.toSeq: _*).withCSRFToken).get
        (redirectLocation(result) must beSome(s"/admin/rounds/${stopped.getId}/conflicts")) and
          (flash(result).get("success") must beSome) and (warning(result) must beSome(contain(Act))) and
          (status(page) === OK) and (contentAsString(page) must contain("flash-warning")) and
          (contentAsString(page) must contain("alert alert-success"))
      }
    }

    "show its notice on the statistics page and its icon in the Rounds table, to its staff only" in {
      testDbApp { app =>
        val (open, stopped, users) = setUp()
        // jurors can vote in round 1; round 2 is stopped: no notice, no icon
        val before = (contentAsString(stat(app, Admin, open)), contentAsString(stat(app, Admin, stopped)),
          contentAsString(roundsList(app)))
        RoundUser.setActive(open.getId, users("juror").getId, active = false)
        RoundUser.setActive(open.getId, users("juror2").getId, active = false)
        val asAdmin = contentAsString(stat(app, Admin, open))
        val asRoot = contentAsString(stat(app, "root@example.com", open))
        val asOrganizer = contentAsString(stat(app, "organizer@example.com", open))
        val asJuror = stat(app, "juror@example.com", open)
        val after = contentAsString(roundsList(app))
        (before._1 must not(contain(Notice))) and (before._2 must not(contain(Notice))) and
          (before._3 must not(contain(Icon))) and
          (asAdmin must contain(Notice)) and (asAdmin must contain(Act)) and
          (asRoot must contain(Notice)) and (asRoot must contain(Act)) and
          (asOrganizer must contain(Notice)) and (asOrganizer must not(contain(Act))) and
          (status(asJuror) === FORBIDDEN) and (contentAsString(asJuror) must not(contain(Notice))) and
          (after must contain(iconOf(open))) and (after must not(contain(iconOf(stopped))))
      }
    }

    "count only jurors who can vote: not a deleted account, nor one no longer a juror of the contest" in {
      testDbApp { app =>
        val (open, _, users) = setUp()
        sql"UPDATE users SET deleted_at = NOW() WHERE id = ${users("juror").getId}".update()
        sql"DELETE FROM user_contest WHERE user_id = ${users("juror2").getId}".update()
        val page = contentAsString(stat(app, Admin, open))
        (page must contain(Notice)) and (contentAsString(roundsList(app)) must contain(iconOf(open))) and
          (RoundUser.activeCounts(Seq(open.getId)) === Map.empty[Long, Int])
      }
    }

    "count no juror whose contest role is now `no`, though their round_user row is active" in {
      testDbApp { app =>
        val (open, _, users) = setUp()
        Seq("juror", "juror2").foreach(k =>
          sql"UPDATE user_contest SET role = ${User.NO_ROLE} WHERE user_id = ${users(k).getId}".update())
        (active(open, users("juror")) must beSome(true)) and
          (contentAsString(stat(app, Admin, open)) must contain(Notice)) and
          (contentAsString(roundsList(app)) must contain(iconOf(open)))
      }
    }

    "warn of a round frozen while open, never of one being created" in {
      testDbApp { app =>
        val (_, _, users) = setUp()
        val frozen = round3(users, Seq("juror3" -> false),
          sqls"active = 0, freeze_count = 1, active_before_freeze = 1, distributing = 0")
        val frozenPage = contentAsString(stat(app, Admin, frozen))
        val frozenList = contentAsString(roundsList(app))
        sql"UPDATE round SET freeze_count = 0, active_before_freeze = 0, distributing = 1 WHERE id = ${frozen.getId}".update()
        val creating = Round.findById(frozen.getId).get
        (frozenPage must contain(Notice)) and (frozenList must contain(iconOf(frozen))) and
          (contentAsString(stat(app, Admin, creating)) must not(contain(Notice))) and
          (contentAsString(roundsList(app)) must not(contain(iconOf(creating))))
      }
    }

    "show nothing to a juror, and give an organizer's pause no warning (refused)" in {
      testDbApp { app =>
        val (open, _, users) = setUp()
        RoundUser.setActive(open.getId, users("juror2").getId, active = false)
        val jurorList = roundsList(app, "juror@example.com")
        val byOrganizer = setJuror(app, open, users("juror"), active = false, by = "organizer@example.com")
        (status(jurorList) === FORBIDDEN) and (contentAsString(jurorList) must not(contain(Icon))) and
          (status(byOrganizer) === FORBIDDEN) and (warning(byOrganizer) must beNone) and
          (active(open, users("juror")) must beSome(true))
      }
    }

    "leave a juror's own gallery page without the notice or the icon" in {
      testDbApp { app =>
        val (open, _, users) = setUp()
        RoundUser.setActive(open.getId, users("juror2").getId, active = false)
        RoundUser.setActive(open.getId, users("juror").getId, active = false)
        val page = contentAsString(get(app, "juror@example.com",
          s"/gallery/round/${open.getId}/user/${users("juror").getId}/page/1"))
        (RoundUser.openWithoutActiveJuror(Seq(open)) === Set(open.getId)) and
          (page must not(contain(Notice))) and (page must not(contain(Icon))) and
          (page must not(contain("No juror can vote")))
      }
    }
  }
}
