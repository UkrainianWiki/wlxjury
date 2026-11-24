package controllers

import controllers.CsrfTestApp.{token, withCsrfApp}
import db.scalikejdbc._
import org.intracer.wmua.ContestJury
import org.jsoup.Jsoup
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test._
import services.AccountTokenService
import services.series.{ContestSeries, SeriesAdmins}

import scala.concurrent.Future

/** Series admins' authorization (contest series plan §3.3, §6; CY-2). The series page, the grant
  * page and the grant: root and the series' admins only (signed out: the login page; a contest's
  * juror, organizer and admin, a series admin of another series: 403, nothing written; a revoked
  * grant: 403 on the next request); root's revoke and start-year change: root only. A series admin
  * with no real row: derived Admin from the start year on and Organizer below it, by opt-in only
  * (an action without `derived = true` refuses it); a Juror row wins; account reach (CA17) from the
  * start year on, never over protected accounts or members of other contests; an invitation issued
  * under a grant is accepted, and refused once the grant is revoked. The menu entry, the landing,
  * the contest switch, the users page's line and the contest's change log. CSRF on the grant.
  */
class SeriesAdminAuthorizationSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val thisYear = java.time.Year.now().getValue

  private def send(method: String, path: String, by: Option[User], current: Option[Long], form: (String, String)*)(
      implicit app: Application): Future[Result] = {
    val base = FakeRequest(method, path).withHeaders(ACCEPT -> "text/html")
    val withSession = by.fold(base)(u => base.withSession(Seq(Secured.UserName -> u.email) ++
      current.map(Secured.CurrentContestId -> _.toString): _*))
    route(app, withSession.withFormUrlEncodedBody(form: _*).withCSRFToken).get
  }

  private def rootUser = User.create(User("Root", s"root${System.nanoTime()}@example.org", None, Set(User.ROOT_ROLE), isRoot = true))

  private def member(name: String, contest: ContestJury, role: String): User =
    User.create(User(name, s"$name${System.nanoTime()}@example.org", None, Set(role), Some("hash"), contest.id))

  /** Series S (years 2019, last year, this year), series T (this year); `sa` a series admin of S
    * from last year (the default) with no real row left; `saT` of T; members of S's newest contest.
    */
  private case class Fixture(root: User, s: Long, cOld: ContestJury, cLast: ContestJury, cNow: ContestJury, t: Long,
      tNow: ContestJury, sa: User, saT: User, juror: User, organizer: User, admin: User, candidate: User)

  private def fixture(): Fixture = {
    SharedTestDb.truncateAll()
    val root = rootUser
    def series(name: String, years: Int*) = {
      val id = ContestSeries.createSeriesTx(name, Some("wlm"), Some(name), None, root.getId).toOption.get
      id -> years.map { y =>
        val c = ContestJuryJdbc.create(None, "Wiki Loves Monuments", y, name)
        ContestSeries.setSeries(c.getId, Some(id), root.getId, None)
        c
      }
    }
    val (s, Seq(cOld, cLast, cNow)) = series("Nepal", 2019, thisYear - 1, thisYear)
    val (t, Seq(tNow)) = series("Bhutan", thisYear)
    val sa = member("SeriesAdminS", cNow, "organizer")
    val saT = member("SeriesAdminT", tNow, "organizer")
    SeriesAdmins.grant(root, s, sa.getId, None, None) must beRight
    SeriesAdmins.grant(root, t, saT.getId, None, None) must beRight
    UserContestJdbc.delete(sa.getId, cNow.getId)
    Fixture(root, s, cOld, cLast, cNow, t, tNow, sa, saT, member("Juror", cNow, "jury"), member("Organizer", cNow, "organizer"),
      member("Admin", cNow, "admin"), member("Candidate", cLast, "jury"))
  }

  "the series page, the grant page and the grant" should {

    "be root's and the series' admins' only, writing nothing for anyone else" in {
      testDbApp { implicit app =>
        val f = fixture()
        val gets = Seq(s"/series/${f.s}", s"/series/${f.s}/admins/new?account=${f.candidate.getId}")
        def grant(by: Option[User]) = send(POST, s"/series/${f.s}/admins", by, Some(f.cNow.getId),
          "account" -> f.candidate.getId.toString)

        val signedOut = gets.map(p => send(GET, p, None, None))
        (signedOut.map(status(_)).distinct === Seq(SEE_OTHER)) and
          (signedOut.flatMap(redirectLocation(_)).forall(_.startsWith("/auth")) must beTrue) and
          (status(grant(None)) must be_!=(OK)) and
          forall(Seq(f.juror, f.organizer, f.admin, f.saT)) { u =>
            (gets.map(p => status(send(GET, p, Some(u), u.contestId))).distinct === Seq(FORBIDDEN)) and
              (status(grant(Some(u))) === FORBIDDEN)
          } and
          (SeriesAdminJdbc.liveGrant(f.candidate.getId, f.s) must beNone) and
          (gets.map(p => status(send(GET, p, Some(f.root), None))).distinct === Seq(OK)) and
          (gets.map(p => status(send(GET, p, Some(f.sa), None))).distinct === Seq(OK)) and
          (status(grant(Some(f.sa))) === SEE_OTHER) and
          (SeriesAdminJdbc.liveGrant(f.candidate.getId, f.s).map(_.grantedBy) must beSome(f.sa.getId)) and
          (status(send(GET, s"/series/999999", Some(f.root), None)) === NOT_FOUND) and
          // the grant page names no account outside the series to a series admin
          (status(send(GET, s"/series/${f.s}/admins/new?account=${f.saT.getId}", Some(f.sa), None)) === NOT_FOUND) and
          (status(send(GET, s"/series/${f.s}/admins/new?account=${f.root.getId}", Some(f.sa), None)) === NOT_FOUND) and
          (status(send(GET, s"/series/${f.s}/admins/new?account=${f.saT.getId}", Some(f.root), None)) === OK)
      }
    }

    "leave revoking and start years to root, and end a revoked series admin's access on the next request" in {
      testDbApp { implicit app =>
        val f = fixture()
        val g = SeriesAdminJdbc.liveGrant(f.sa.getId, f.s).get
        val gT = SeriesAdminJdbc.liveGrant(f.saT.getId, f.t).get
        (status(send(POST, s"/admin/series/grants/${gT.id}/revoke", Some(f.sa), None)) === FORBIDDEN) and
          (status(send(POST, s"/admin/series/grants/${g.id}/from-year", Some(f.sa), None, "fromYear" -> "2000")) === FORBIDDEN) and
          (status(send(POST, s"/admin/series/grants/${g.id}/revoke", Some(f.admin), Some(f.cNow.getId))) === FORBIDDEN) and
          (SeriesAdminJdbc.findById(g.id).map(_.adminFromYear) must beSome(thisYear - 1)) and
          (status(send(GET, s"/admin/rounds?contestId=${f.cNow.getId}", Some(f.sa), Some(f.cNow.getId))) === OK) and
          (status(send(POST, s"/admin/series/grants/${g.id}/from-year", Some(f.root), None, "fromYear" -> "2019")) === SEE_OTHER) and
          (SeriesAdminJdbc.findById(g.id).map(_.adminFromYear) must beSome(2019)) and
          (status(send(POST, s"/admin/series/grants/${g.id}/revoke", Some(f.root), None)) === SEE_OTHER) and
          (status(send(GET, s"/series/${f.s}", Some(f.sa), None)) === FORBIDDEN) and
          (status(send(GET, s"/admin/rounds?contestId=${f.cNow.getId}", Some(f.sa), Some(f.cNow.getId))) === FORBIDDEN) and
          (status(send(POST, s"/admin/series/grants/999999/revoke", Some(f.root), None)) === NOT_FOUND)
      }
    }

    "refuse a grant without the CSRF token" in {
      withCsrfApp { app =>
        val f = fixture()
        val sessionToken = token(app)
        def post(body: (String, String)*) =
          route(app, FakeRequest(POST, s"/series/${f.s}/admins")
            .withHeaders(COOKIE -> "PLAY_SESSION=signed-in", ACCEPT -> "text/html")
            .withSession(Secured.UserName -> f.sa.email, "csrfToken" -> sessionToken)
            .withFormUrlEncodedBody(body: _*)).get
        val account = "account" -> f.candidate.getId.toString
        (status(post(account)) === FORBIDDEN) and (status(post(account, "csrfToken" -> "forged")) === FORBIDDEN) and
          (SeriesAdminJdbc.liveGrant(f.candidate.getId, f.s) must beNone) and
          (status(post(account, "csrfToken" -> sessionToken)) === SEE_OTHER) and
          (SeriesAdminJdbc.liveGrant(f.candidate.getId, f.s) must beSome)
      }
    }
  }

  "a series admin's derived roles" should {

    "be Admin from the start year on and Organizer below it, by opt-in only; a Juror row wins; none outside the series" in {
      testDbApp { implicit app =>
        val f = fixture()
        def get(path: String, current: ContestJury) = status(send(GET, path, Some(f.sa), Some(current.getId)))
        (get(s"/admin/rounds?contestId=${f.cNow.getId}", f.cNow) === OK) and
          (get(s"/admin/rounds?contestId=${f.cLast.getId}", f.cLast) === OK) and
          // below the start year: Organizer, read-only
          (get(s"/admin/rounds?contestId=${f.cOld.getId}", f.cOld) === FORBIDDEN) and
          (get(s"/contest/${f.cOld.getId}/files", f.cOld) === OK) and
          // the account actions: a real Admin row, or a contest the grant reaches (CA17)
          (get(s"/admin/users?contestId=${f.cNow.getId}", f.cNow) === OK) and
          (get(s"/admin/users?contestId=${f.cOld.getId}", f.cOld) === FORBIDDEN) and
          (get(s"/admin/account-changes?contestId=${f.cOld.getId}", f.cOld) === FORBIDDEN) and
          // another series' contest
          (get(s"/admin/rounds?contestId=${f.tNow.getId}", f.tNow) === FORBIDDEN) and
          (get(s"/contest/${f.tNow.getId}/files", f.tNow) === FORBIDDEN) and {
            UserContestJdbc.createMembership(f.sa.getId, f.cLast.getId, User.JURY_ROLE)
            (get(s"/admin/rounds?contestId=${f.cLast.getId}", f.cLast) === FORBIDDEN) and
              (get(s"/admin/users?contestId=${f.cLast.getId}", f.cLast) === FORBIDDEN)
          }
      }
    }

    "be refused by a permission that doesn't opt in, and never reach an API token's or root's edit form's user" in {
      testDbApp { implicit app =>
        val f = fixture()
        val session = Secured.withSeries(f.sa, Some(f.cNow.getId.toString))
        val raw = Secured.inCurrentContest(User.findById(f.sa.getId).get, Some(f.cNow.getId.toString))
        (session.contestId must beSome(f.cNow.getId)) and
          (Secured.ContestPermission(User.ADMIN_ROLES, Some(f.cNow.getId))(session) must beFalse) and
          (Secured.ContestPermission(User.ADMIN_ROLES, Some(f.cNow.getId), derived = true)(session) must beTrue) and
          (Secured.ContestPermission(User.ADMIN_ROLES, Some(f.cOld.getId), derived = true)(Secured.inContest(session, f.cOld.getId)) must beFalse) and
          (Secured.ContestPermission(User.ORG_COM_ROLES, Some(f.cOld.getId), derived = true)(Secured.inContest(session, f.cOld.getId)) must beTrue) and
          (raw.seriesContests must beEmpty) and (raw.contestId must beNone) and
          (Secured.ContestPermission(User.ADMIN_ROLES, Some(f.cNow.getId), accountReach = true)(raw) must beFalse)
      }
    }
  }

  "a series admin's account reach" should {

    "cover members of the series' contests from the start year on, not protected accounts or members of other contests" in {
      testDbApp { implicit app =>
        val f = fixture()
        val session = Secured.inContest(Secured.withSeries(f.sa, Some(f.cNow.getId.toString)), f.cNow.getId)
        val elsewhere = member("Elsewhere", f.cNow, "jury")
        UserContestJdbc.createMembership(elsewhere.getId, f.tNow.getId, "jury")
        val old = member("Old", f.cOld, "jury")
        val otherRoot = rootUser
        UserContestJdbc.createMembership(otherRoot.getId, f.cNow.getId, "admin")
        UserContestJdbc.createMembership(f.saT.getId, f.cNow.getId, "jury")
        val c = new UserController(app.injector.instanceOf[play.api.mvc.ControllerComponents],
          app.injector.instanceOf[services.UserService], app.injector.instanceOf[modules.BlockingExecutionContext])
        (c.canEditAccount(session, f.juror) must beTrue) and
          (c.canEditAccount(session, f.candidate) must beTrue) and
          (c.canEditAccount(session, elsewhere) must beFalse) and
          (c.canEditAccount(session, old) must beFalse) and
          (c.canEditAccount(session, otherRoot) must beFalse) and
          (c.canEditAccount(session, f.saT) must beFalse) and
          // a contest's real admin never reaches the series admin (a protected target)
          (c.canEditAccount(Secured.inCurrentContest(f.admin, Some(f.cNow.getId.toString)), f.sa) must beFalse) and
          // search lists series admins (CA21) but offers no edit of them
          (services.AccountSearch.describe(Secured.inCurrentContest(f.admin, None), f.cNow.id, Seq(f.saT))
            .map(_.editable) === Seq(false))
      }
    }

    "accept an invitation issued under a grant, give no role once the grant gives nothing, and revoke it with the grant" in {
      testDbApp { implicit app =>
        val f = fixture()
        def invitee(name: String) = User.create(User(name, s"$name${System.nanoTime()}@example.org", None))
        val (first, second, third) = (invitee("First"), invitee("Second"), invitee("Third"))
        def invite(u: User) = AccountTokenService.create(u.getId, AccountTokenJdbc.Invitation, u.email, f.cNow.id,
          Some(User.JURY_ROLE), f.sa.id)._2
        val (t1, t2, t3) = (invite(first), invite(second), invite(third))
        val accepted = AccountTokenService.acceptPasswordLink(t1, services.PasswordHasher.default.hash("secret-1"))
        // the series retired: the grant gives nothing, so the inviter may no longer give the role (mayGrant)
        ContestSeries.retireSeries(f.s, f.root.getId, None) must beRight
        val noRole = AccountTokenService.acceptPasswordLink(t2, services.PasswordHasher.default.hash("secret-2"))
        // revoked: the links it made in the series' contests stop working (the security review of #140, L2)
        SeriesAdmins.revoke(f.root, SeriesAdminJdbc.findBySeries(f.s).find(_.userId == f.sa.getId).get.id,
          chain = false) must beRight
        val dead = AccountTokenService.acceptPasswordLink(t3, services.PasswordHasher.default.hash("secret-3"))
        (accepted must beRight) and (noRole must beRight) and (dead must beLeft) and
          (UserContestJdbc.find(first.getId, f.cNow.getId).map(_.role) must beSome(User.JURY_ROLE)) and
          (UserContestJdbc.find(second.getId, f.cNow.getId) must beNone) and
          (UserContestJdbc.find(third.getId, f.cNow.getId) must beNone)
      }
    }
  }

  "a grant back from the series' retirement" should {

    "give nothing until root confirms it, root only, with the CSRF form on root's page" in {
      testDbApp { implicit app =>
        val f = fixture()
        ContestSeries.retireSeries(f.s, f.root.getId, None) must beRight
        ContestSeries.unretireSeries(f.s, f.root.getId, None) must beRight
        val g = SeriesAdminJdbc.findBySeries(f.s).find(_.userId == f.sa.getId).get
        val rootPage = Jsoup.parse(contentAsString(send(GET, s"/admin/series/${f.s}", Some(f.root), None)))
        (g.toConfirm must beTrue) and
          (rootPage.select(s"form[action=/admin/series/grants/${g.id}/confirm] input[name=csrfToken]").size === 1) and
          (status(send(GET, s"/series/${f.s}", Some(f.sa), None)) === FORBIDDEN) and
          (status(send(GET, s"/admin/rounds?contestId=${f.cNow.getId}", Some(f.sa), Some(f.cNow.getId))) === FORBIDDEN) and
          (status(send(POST, s"/admin/series/grants/${g.id}/confirm", Some(f.sa), None)) === FORBIDDEN) and
          (status(send(POST, s"/admin/series/grants/${g.id}/confirm", Some(f.admin), Some(f.cNow.getId))) === FORBIDDEN) and
          (status(send(POST, s"/admin/series/grants/${g.id}/confirm", None, None)) must be_!=(OK)) and
          (SeriesAdminJdbc.findById(g.id).exists(_.toConfirm) must beTrue) and
          (status(send(POST, s"/admin/series/grants/${g.id}/confirm", Some(f.root), None)) === SEE_OTHER) and
          (status(send(GET, s"/admin/rounds?contestId=${f.cNow.getId}", Some(f.sa), Some(f.cNow.getId))) === OK) and
          (status(send(POST, s"/admin/series/grants/999999/confirm", Some(f.root), None)) === NOT_FOUND)
      }
    }
  }

  "the denial page and a move's confirmation" should {

    "name a series admin's series with the role the grant gives there, and list the series admins a move changes" in {
      testDbApp { implicit app =>
        val f = fixture()
        val denied = send(GET, s"/admin/rounds?contestId=${f.cOld.getId}", Some(f.sa), Some(f.cOld.getId))
        val own = Jsoup.parse(contentAsString(send(GET, s"/admin/contests/${f.cNow.getId}/series", Some(f.root), None)))
        val other = Jsoup.parse(contentAsString(send(GET, s"/admin/contests/${f.tNow.getId}/series", Some(f.root), None)))
        (status(denied) === FORBIDDEN) and
          (Jsoup.parse(contentAsString(denied)).select(".access-denied-series").text() must
            contain("As series admin of Nepal, your role here is: Organizer")) and
          (own.select(s".contest-series-moved[data-series=${f.s}][data-gain=false]").text() must contain("SeriesAdminS (Admin)")) and
          (own.select(s".contest-series-moved[data-series=${f.t}][data-gain=true]").text() must contain("SeriesAdminT")) and
          (other.select(s".contest-series-moved[data-series=${f.s}][data-gain=true]").text() must contain("SeriesAdminS (Admin)"))
      }
    }
  }

  "the role writes' series rule" should {

    "refuse a non-root actor making a series admin a Juror or lowering their Admin row in the series' contests, not elsewhere (plan §3.3)" in {
      testDbApp { implicit app =>
        val f = fixture()
        UserContestJdbc.createMembership(f.sa.getId, f.cLast.getId, "admin")
        UserContestJdbc.createMembership(f.sa.getId, f.tNow.getId, "organizer")
        val inLast = User.findInContest(f.sa.getId, f.cLast.getId).get
        val inT = User.findInContest(f.sa.getId, f.tNow.getId).get
        val writes = (UserController.seriesRoleWriteAllowed(f.admin, inLast, User.JURY_ROLE) must beFalse) and
          (UserController.seriesRoleWriteAllowed(f.admin, inLast, "organizer") must beFalse) and
          (UserController.seriesRoleWriteAllowed(f.admin, inLast, User.ADMIN_ROLE) must beTrue) and
          (UserController.seriesRoleWriteAllowed(f.saT, inT, User.JURY_ROLE) must beTrue) and
          (UserController.seriesRoleWriteAllowed(f.root, inLast, User.JURY_ROLE) must beTrue)
        // a grant waiting for root's confirmation protects too (M1): the series rule, and no email
        // change by a contest admin
        ContestSeries.retireSeries(f.s, f.root.getId, None) must beRight
        ContestSeries.unretireSeries(f.s, f.root.getId, None) must beRight
        val c = new UserController(app.injector.instanceOf[play.api.mvc.ControllerComponents],
          app.injector.instanceOf[services.UserService], app.injector.instanceOf[modules.BlockingExecutionContext])
        writes and (UserController.seriesRoleWriteAllowed(f.admin, inLast, User.JURY_ROLE) must beFalse) and
          (c.canEditAccount(Secured.inCurrentContest(User.findById(f.admin.getId).get, Some(f.cNow.getId.toString)), f.sa) must beFalse)
      }
    }
  }

  "a revoked series admin's links" should {

    "stop working: the password and share links they made in the series' contests are revoked (L2)" in {
      testDbApp { implicit app =>
        val f = fixture()
        val (linkId, _) = AccountTokenService.create(f.juror.getId, AccountTokenJdbc.PasswordReset, f.juror.email, f.cNow.id,
          None, f.sa.id, shared = true)
        val (elsewhereId, _) = AccountTokenService.create(f.juror.getId, AccountTokenJdbc.PasswordReset, f.juror.email,
          f.tNow.id, None, f.sa.id, shared = true)
        SeriesAdmins.revoke(f.root, SeriesAdminJdbc.liveGrant(f.sa.getId, f.s).get.id, chain = true) must beRight
        val live = AccountTokenJdbc.findByUser(f.juror.getId).map(t => t.id -> t.live).toMap
        (live.get(linkId) must beSome(false)) and (live.get(elsewhereId) must beSome(true))
      }
    }
  }

  "the menu, landing, switch, users page and change log" should {

    "show the series entry, land on the series page, switch to a series contest, list series admins and log grants" in {
      testDbApp { implicit app =>
        val f = fixture()
        val page = Jsoup.parse(contentAsString(send(GET, s"/series/${f.s}", Some(f.sa), None)))
        val landing = send(GET, "/", Some(f.sa), None)
        val switched = send(POST, s"/switch-contest/${f.cOld.getId}", Some(f.sa), None)
        val users = Jsoup.parse(contentAsString(send(GET, s"/admin/users?contestId=${f.cNow.getId}", Some(f.root), None)))
        val log = contentAsString(send(GET, s"/admin/account-changes?contestId=${f.cNow.getId}", Some(f.root), None))
        (page.select(s"a.contest-menu-series[href=/series/${f.s}]").size === 1) and
          (page.select(".series-home-contest").size === 3) and
          (redirectLocation(landing) must beSome(s"/series/${f.s}")) and
          (status(switched) === SEE_OTHER) and
          (session(switched).get(Secured.CurrentContestId) must beSome(f.cOld.getId.toString)) and
          (status(send(POST, s"/switch-contest/${f.cOld.getId}", Some(f.saT), None)) === FORBIDDEN) and
          (users.select("#users-series-admins").text() must contain("SeriesAdminS")) and
          (log must contain("Series admin granted"))
      }
    }
  }
}
