package controllers

import controllers.CsrfTestApp.{token => csrfToken, withCsrfApp}
import db.scalikejdbc.{AccountTokenJdbc, PlayTestDb, User, UserContest, UserContestJdbc, UserIdentity, UserIdentityJdbc}
import org.apache.pekko.stream.Materializer
import play.api.{Application, Configuration}
import play.api.mvc.{ControllerComponents, Result}
import play.api.test.CSRFTokenHelper._
import play.api.test._
import scalikejdbc._
import services.oauth.OAuthSettings
import services.{AccountTokenService, ApiTokenService}

import scala.concurrent.Future

/** Shareable set-password links (the user, 2026-11-05): "Create a link to share" on an
  * invitation (the users page) or an account (its edit page, a reset) shows a single-use link
  * once, for the admin to pass on personally; the same kind of link as a mailed one, which it
  * replaces. Root for any account; a contest admin only for an account that is theirs alone.
  */
class ShareLinkSpec extends PlaySpecification with PlayTestDb with AccountTestSupport {

  sequential

  private def as[A](user: User, contestId: Option[Long] = None)(request: FakeRequest[A]): FakeRequest[A] =
    request.withSession(Seq(Secured.UserName -> user.email) ++ contestId.map(Secured.CurrentContestId -> _.toString): _*)

  private val LinkInPage = """/invitation/([A-Za-z0-9_-]{43})""".r

  /** The tokens of the links in a page (each once per occurrence). */
  private def linksIn(page: Future[Result])(implicit app: Application): Seq[String] = {
    implicit val mat: Materializer = app.materializer
    LinkInPage.findAllMatchIn(contentAsString(page)).map(_.group(1)).toSeq
  }

  private def shareInvitation(mail: RecordingMail, by: User, id: Long, contestId: Long, current: Option[Long] = None)(
      implicit app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(userController(mail).shareInvitation(id, contestId),
      as(by, current.orElse(Some(contestId)))(FakeRequest(POST, s"/users/$id/invitation/share?contestId=$contestId"))
        .withCSRFToken)
  }

  private def shareReset(mail: RecordingMail, by: User, id: Long, contestId: Option[Long])(implicit
      app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(userController(mail).sharePasswordLink(id, contestId),
      as(by, contestId.orElse(by.contestId))(FakeRequest(POST, s"/users/$id/password/share")).withCSRFToken)
  }

  private def usersPage(mail: RecordingMail, admin: User, contestId: Long)(implicit app: Application): String = {
    implicit val mat: Materializer = app.materializer
    contentAsString(call(userController(mail).users(Some(contestId)),
      as(admin, Some(contestId))(FakeRequest(GET, s"/admin/users?contestId=$contestId")).withCSRFToken))
  }

  private def editPage(mail: RecordingMail, by: User, id: Long, contestId: Option[Long])(implicit app: Application): String = {
    implicit val mat: Materializer = app.materializer
    contentAsString(call(userController(mail).editUser(id, contestId),
      as(by, contestId)(FakeRequest(GET, s"/users/edit/$id")).withCSRFToken))
  }

  private def add(mail: RecordingMail, admin: User, contestId: Long, email: String, role: String = "jury")(implicit
      app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(userController(mail).saveUser(),
      as(admin, Some(contestId))(FakeRequest(POST, "/users/save")).withFormUrlEncodedBody(
        "contest" -> contestId.toString, "id" -> "0", "fullname" -> "Person", "email" -> email, "roles" -> role)
        .withCSRFToken)
  }

  private def accept(token: String, password: String)(implicit app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(loginController(new RecordingMail).acceptInvitation(),
      FakeRequest(POST, "/invitation")
        .withFormUrlEncodedBody("token" -> token, "password" -> password, "repeat.password" -> password)
        .withCSRFToken)
  }

  private def invitationPage(token: String)(implicit app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(loginController(new RecordingMail).invitation(token), FakeRequest(GET, s"/invitation/$token").withCSRFToken)
  }

  private def roles(userId: Long): Seq[UserContest] = UserContestJdbc.findByUser(userId)

  private def reloaded(user: User): User = User.findById(user.getId).get

  "an admin's link to share for an invitation [A-19]" should {
    "show the link once, replace the mailed one, set the first password and show the link as used" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val mail = new RecordingMail
        status(add(mail, admin, 10, "new@example.com")) === SEE_OTHER
        val mailed = mail.lastToken
        val invitee = User.findByEmail("new@example.com").head

        // the users page offers it, in a form with the CSRF token
        val before = usersPage(mail, admin, 10)
        before must contain(routes.UserController.shareInvitation(invitee.getId, 10).url)
        before must contain("csrfToken")

        val (shared, lines) = accountLog {
          val r = shareInvitation(mail, admin, invitee.getId, 10)
          status(r)
          r
        }
        status(shared) === OK
        header(CACHE_CONTROL, shared) must beSome("no-store")
        header("Referrer-Policy", shared) must beSome("no-referrer")
        val html = contentAsString(shared)
        html must contain("Anyone with this link can set this account&#x27;s password")
        html must contain("data-copy-target=\"shared-link\"")
        html must contain("copy-field.js")
        html must contain("Expires ")
        val Seq(token) = linksIn(shared)
        mail.sent.size === 1 // nothing mailed
        // never in the session, the flash, a log line or the database
        session(shared).data.values.mkString must not(contain(token))
        flash(shared).data must beEmpty
        lines.filter(l => l.contains(token) || l.contains("@")) must beEmpty
        lines must contain(beMatching(s"account_link_shared purpose=invitation tokenId=\\d+ accountId=${invitee.getId} " +
          s"byAccountId=${admin.getId} contestId=10 linksRevoked=1"))
        DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM account_token WHERE token_hash = $token"
          .map(_.long(1)).single()) === Some(0L)

        // the mailed link stops working; the shared one is listed as active
        status(invitationPage(mailed)) === NOT_FOUND
        usersPage(mail, admin, 10) must contain("Link to share").and(contain("invitation-active"))

        status(invitationPage(token)) === OK
        status(accept(token, "first-password")) === SEE_OTHER
        User.login("new@example.com", "first-password").flatMap(_.id) === invitee.id
        // a shared link proves no email; the membership stays
        reloaded(invitee).emailVerified must beFalse
        roles(invitee.getId) === Seq(UserContest(invitee.getId, 10, "jury"))
        status(accept(token, "again-password")) === NOT_FOUND
        usersPage(mail, admin, 10) must contain("invitation-used")
      }
    }

    "give a pending invitee's role, and stop working once the invitation is mailed again" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        // an unverified account with a password: an add invites it, the role pending (O2)
        val pending = account("pending@example.com", None, "no", password = Some("old-password"))
        val mail = new RecordingMail
        status(add(mail, admin, 10, "pending@example.com", "organizer")) === SEE_OTHER
        roles(pending.getId) must beEmpty

        val Seq(first) = linksIn(shareInvitation(mail, admin, pending.getId, 10))
        AccountTokenJdbc.findByUser(pending.getId).filter(_.live).map(t => (t.shared, t.role)) ===
          Seq((true, Some("organizer")))
        // "Send by email" stays, and replaces the shared link
        implicit val mat: Materializer = app.materializer
        status(call(userController(mail).reinvite(pending.getId, 10),
          as(admin, Some(10))(FakeRequest(POST, s"/users/${pending.getId}/invite?contestId=10")).withCSRFToken)) === SEE_OTHER
        status(accept(first, "new-password")) === NOT_FOUND

        val Seq(second) = linksIn(shareInvitation(mail, admin, pending.getId, 10))
        status(accept(second, "new-password")) === SEE_OTHER
        roles(pending.getId) === Seq(UserContest(pending.getId, 10, "organizer"))
        User.login("pending@example.com", "old-password") must beNone
      }
    }

    "work for an account without an email, which then signs in by its wiki account" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val wiki = User.create(User("Wiki juror", "", None, Set("jury"), None, Some(10), wikiAccount = Some("Wiki juror")))
        val mail = new RecordingMail
        usersPage(mail, admin, 10) must contain(routes.UserController.shareInvitation(wiki.getId, 10).url)

        val Seq(token) = linksIn(shareInvitation(mail, admin, wiki.getId, 10))
        contentAsString(invitationPage(token)) must contain("Your wiki account, Wiki juror, is your login.")
        val accepted = accept(token, "wiki-password")
        status(accepted) === SEE_OTHER
        session(accepted).get(Secured.UserName) must beSome("Wiki juror")
        User.login("Wiki juror", "wiki-password").flatMap(_.id) === wiki.id
      }
    }
  }

  "an admin's or root's link to share for a reset [A-19]" should {
    "keep the old password until used, then end the other sessions and API tokens, and show the links' status" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val juror = account("juror@example.com", Some(10), "jury", password = Some("old-password"), verified = true)
        val apiToken = ApiTokenService.create(juror.getId, "tool", Some(30))
        UserIdentityJdbc.create(UserIdentity("google", "juror-subject", juror.getId))
        val mail = new RecordingMail

        val page = editPage(mail, admin, juror.getId, Some(10))
        page must contain(routes.UserController.sharePasswordLink(juror.getId, Some(10)).url)
        page must contain("csrfToken")

        val Seq(first) = linksIn(shareReset(mail, admin, juror.getId, Some(10)))
        val Seq(second) = linksIn(shareReset(mail, admin, juror.getId, Some(10)))
        mail.sent must beEmpty
        User.login("juror@example.com", "old-password") must beSome
        // creating a link cuts nobody's sign-in: the identities go when it is used
        UserIdentityJdbc.findByUser(juror.getId).map(_.subject) === Seq("juror-subject")
        status(accept(first, "new-password")) === NOT_FOUND // replaced by the second
        editPage(mail, admin, juror.getId, Some(10)) must contain("link-revoked").and(contain("link-active"))

        status(accept(second, "new-password")) === SEE_OTHER
        User.login("juror@example.com", "old-password") must beNone
        User.login("juror@example.com", "new-password") must beSome
        Secured.sessionUser("juror@example.com", Some("10"), None, None) must beNone
        ApiTokenService.authenticate(apiToken) must beNone
        UserIdentityJdbc.findByUser(juror.getId) must beEmpty
        reloaded(juror).emailVerified must beFalse // nobody proved the address
        roles(juror.getId) === Seq(UserContest(juror.getId, 10, "jury"))
        editPage(mail, admin, juror.getId, Some(10)) must contain("link-used")
      }
    }

    "show an expired link as expired, and refuse it" in {
      testDbApp { implicit app =>
        createContests(10)
        val root = account("root@example.com", None, "no", root = true, verified = true)
        val juror = account("juror@example.com", Some(10), "jury", password = Some("old-password"), verified = true)
        val mail = new RecordingMail
        val Seq(token) = linksIn(shareReset(mail, root, juror.getId, None))
        DB.autoCommit { implicit s =>
          sql"UPDATE account_token SET expires_at = NOW(3) - INTERVAL 1 SECOND".update.apply()
        }
        status(accept(token, "new-password")) === NOT_FOUND
        editPage(mail, root, juror.getId, Some(10)) must contain("link-expired")
        User.login("juror@example.com", "old-password") must beSome
      }
    }

    "be refused when used after the account joined a contest its creator doesn't administer" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin10 = account("admin10@example.com", Some(10), "admin", verified = true)
        val admin20 = account("admin20@example.com", Some(20), "admin", verified = true)
        val mail = new RecordingMail
        status(add(mail, admin10, 10, "new@example.com")) === SEE_OTHER
        val invitee = User.findByEmail("new@example.com").head
        val Seq(token) = linksIn(shareInvitation(mail, admin10, invitee.getId, 10))
        status(add(mail, admin20, 20, "new@example.com")) === SEE_OTHER // no password: joins 20
        status(invitationPage(token)) === NOT_FOUND
        status(accept(token, "taken-password")) === NOT_FOUND
        reloaded(invitee).password must beNone
      }
    }
  }

  "who may create a link to share [A-19]" should {
    "be root for any account, and an admin only for an account that is theirs alone" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val root = account("root@example.com", None, "no", root = true, verified = true)
        val otherRoot = account("root2@example.com", None, "no", root = true, verified = true)
        val admin10 = account("admin10@example.com", Some(10), "admin", verified = true)
        val coAdmin = account("coadmin@example.com", Some(10), "admin", verified = true)
        val admin20 = account("admin20@example.com", Some(20), "admin", verified = true)
        val organizer = account("organizer@example.com", Some(10), "organizer", verified = true)
        val juror = account("juror@example.com", Some(10), "jury", verified = true)
        val both = account("both@example.com", Some(10), "jury", verified = true)
        UserContestJdbc.createMembership(both.getId, 20, "jury")
        val invited20 = account("invited20@example.com", Some(10), "jury")
        AccountTokenService.create(invited20.getId, AccountTokenJdbc.Invitation, invited20.email, Some(20), Some("jury"),
          admin20.id)
        val outsider = account("outsider@example.com", Some(20), "jury")
        UserContestJdbc.createMembership(otherRoot.getId, 10, "jury")
        val mail = new RecordingMail
        def links = DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM account_token WHERE shared".map(_.long(1)).single())

        // refused: 403 through the denial page
        status(shareReset(mail, admin10, coAdmin.getId, Some(10))) === FORBIDDEN    // another admin
        status(shareReset(mail, admin10, otherRoot.getId, Some(10))) === FORBIDDEN  // root
        status(shareInvitation(mail, admin10, otherRoot.getId, 10)) === FORBIDDEN   // root, never invited
        status(shareReset(mail, admin10, both.getId, Some(10))) === FORBIDDEN       // also in contest 20
        status(shareInvitation(mail, admin10, both.getId, 10)) === FORBIDDEN
        status(shareInvitation(mail, admin10, invited20.getId, 10)) === FORBIDDEN   // invited into 20
        status(shareReset(mail, admin10, admin10.getId, Some(10))) === FORBIDDEN    // own account
        status(shareReset(mail, admin20, juror.getId, Some(10))) === FORBIDDEN      // not an admin of 10
        status(shareInvitation(mail, admin20, juror.getId, 10, current = Some(20))) === FORBIDDEN
        status(shareReset(mail, organizer, juror.getId, Some(10))) === FORBIDDEN
        status(shareInvitation(mail, organizer, juror.getId, 10)) === FORBIDDEN
        status(shareReset(mail, juror, organizer.getId, Some(10))) === FORBIDDEN
        // not in the contest: not found; an invitation a verified account with a password doesn't need
        status(shareReset(mail, admin10, outsider.getId, Some(10))) === NOT_FOUND
        status(shareInvitation(mail, admin10, juror.getId, 10)) === BAD_REQUEST
        links === Some(0L)
        // no button where it would be refused
        val page = usersPage(mail, admin10, 10)
        page must not(contain(routes.UserController.shareInvitation(both.getId, 10).url))
        page must not(contain(routes.UserController.shareInvitation(invited20.getId, 10).url))
        page must not(contain(routes.UserController.shareInvitation(otherRoot.getId, 10).url))
        editPage(mail, admin10, coAdmin.getId, Some(10)) must not(contain("share-password-link"))
        editPage(mail, admin10, both.getId, Some(10)) must not(contain("share-password-link"))

        // allowed: an admin for a juror or an organizer of their contest only
        status(shareReset(mail, admin10, juror.getId, Some(10))) === OK
        status(shareReset(mail, admin10, organizer.getId, Some(10))) === OK
        // an admin of both contests may, for the account in 10 and invited into 20: from a session
        // in 20 it acts in 10, the session's contest unchanged
        UserContestJdbc.createMembership(admin20.getId, 10, "admin")
        val inOther = shareInvitation(mail, admin20, invited20.getId, 10, current = Some(20))
        status(inOther) === OK
        session(inOther).get(Secured.CurrentContestId) must beNone
        // root: any account, an admin and another root too
        status(shareReset(mail, root, coAdmin.getId, None)) === OK
        status(shareReset(mail, root, otherRoot.getId, None)) === OK
        status(shareReset(mail, root, both.getId, None)) === OK
        status(shareInvitation(mail, root, invited20.getId, 10)) === OK
        links === Some(7L)
      }
    }

    "send a signed-out request to the login page, or a script's to a JSON 401" in {
      testDbApp { implicit app =>
        createContests(10)
        val juror = account("juror@example.com", Some(10), "jury", verified = true)
        val page = route(app, FakeRequest(POST, s"/users/${juror.getId}/password/share")).get
        status(page) === SEE_OTHER
        redirectLocation(page) must beSome(startWith("/auth"))
        val script = route(app, FakeRequest(POST, s"/users/${juror.getId}/invitation/share?contestId=10")
          .withHeaders("X-Requested-With" -> "XMLHttpRequest")).get
        status(script) === UNAUTHORIZED
        AccountTokenJdbc.findByUser(juror.getId) must beEmpty
      }
    }

    "need the CSRF token, through the router" in {
      withCsrfApp { app =>
        implicit val a: Application = app
        val root = account("root@example.com", None, "no", root = true, verified = true)
        val juror = account("juror@example.com", None, "no", password = Some("old-password"), verified = true)
        val sessionToken = csrfToken(app)
        val refused = route(app, FakeRequest(POST, s"/users/${juror.getId}/password/share")
          .withHeaders(COOKIE -> "PLAY_SESSION=x", ACCEPT -> "text/html")
          .withSession(Secured.UserName -> root.email, "csrfToken" -> sessionToken)).get
        status(refused) === FORBIDDEN
        AccountTokenJdbc.findByUser(juror.getId) must beEmpty
      }
    }
  }

  private def importUsers(mail: RecordingMail, admin: User, contestId: Long, lines: String)(implicit
      app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(userController(mail).importUsers(Some(contestId)),
      as(admin, Some(contestId))(FakeRequest(POST, s"/admin/importusers?contestId=$contestId"))
        .withFormUrlEncodedBody("userstoimport" -> lines, "role" -> "jury").withCSRFToken)
  }

  "an account whose password a shared link set [A-19]" should {
    "be unverified, so that another contest's admin invites it by email or by wiki account not at all" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin10 = account("admin10@example.com", Some(10), "admin", verified = true)
        val admin20 = account("admin20@example.com", Some(20), "admin", verified = true)
        val juror = account("juror@example.com", Some(10), "jury", password = Some("old-password"), verified = true,
          wikiAccount = Some("Juror wiki"))
        DB.autoCommit(implicit s =>
          sql"UPDATE users SET wiki_account_verified_at = NOW(3) WHERE id = ${juror.getId}".update.apply())
        val mail = new RecordingMail

        val Seq(token) = linksIn(shareReset(mail, admin10, juror.getId, Some(10)))
        status(accept(token, "holder-password")) === SEE_OTHER
        reloaded(juror).emailVerified must beFalse
        reloaded(juror).wikiAccountVerified must beFalse

        // by wiki account: not added (H1)
        val byName = importUsers(mail, admin20, 20, "User:Juror_wiki")
        flash(byName).get("users-added") must beSome(contain("Not added, as the wiki account belongs"))
        // by email: an invitation, not a membership (O2)
        val byEmail = add(mail, admin20, 20, "juror@example.com")
        flash(byEmail).get("users-added") must beSome(contain("Invited (role given once they accept): 1."))
        roles(juror.getId) === Seq(UserContest(juror.getId, 10, "jury"))
        mail.recipients === Seq("juror@example.com")

        // the address's owner accepts it: the password is theirs again and the email verified
        status(accept(mail.lastToken, "owner-password")) === SEE_OTHER
        User.login("juror@example.com", "holder-password") must beNone
        reloaded(juror).emailVerified must beTrue
        roles(juror.getId).map(_.contestId).sorted === Seq(10L, 20L)
      }
    }
  }

  "the rule's other refusals [A-19]" should {
    "refuse an admin's reset for a pending invitee (the invitation's link only), and inactive or past ties elsewhere" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val pending = account("pending@example.com", None, "no", password = Some("old-password"))
        val board = account("board@example.com", Some(10), "jury", verified = true)
        UserContestJdbc.createMembership(board.getId, 20, "board")
        val removed = account("removed@example.com", Some(10), "jury", verified = true)
        UserContestJdbc.createMembership(removed.getId, 20, "no")
        val past = account("past@example.com", Some(10), "jury", verified = true)
        DB.autoCommit { implicit s =>
          sql"INSERT INTO round (id, number, contest_id, rates, created_at) VALUES (900, 1, 20, 1, NOW())".update.apply()
          sql"INSERT INTO round_user (user_id, round_id, role, active) VALUES (${past.getId}, 900, 'jury', 0)".update.apply()
        }
        val mail = new RecordingMail
        status(add(mail, admin, 10, "pending@example.com")) === SEE_OTHER // pending (O2)

        status(shareReset(mail, admin, pending.getId, Some(10))) === FORBIDDEN
        status(shareInvitation(mail, admin, pending.getId, 10)) === OK
        status(shareReset(mail, admin, board.getId, Some(10))) === FORBIDDEN   // board in 20
        status(shareReset(mail, admin, removed.getId, Some(10))) === FORBIDDEN // "no" in 20
        status(shareReset(mail, admin, past.getId, Some(10))) === FORBIDDEN    // worked in 20
        editPage(mail, admin, past.getId, Some(10)) must not(contain("share-password-link"))
      }
    }

    "refuse a link at use once its account became an admin, or its creator is no longer one" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val promoted = account("promoted@example.com", Some(10), "jury", password = Some("old-password"), verified = true)
        val other = account("other@example.com", Some(10), "jury", password = Some("old-password"), verified = true)
        val mail = new RecordingMail
        val Seq(toPromoted) = linksIn(shareReset(mail, admin, promoted.getId, Some(10)))
        val Seq(toOther) = linksIn(shareReset(mail, admin, other.getId, Some(10)))

        UserContestJdbc.updateRole(promoted.getId, 10, "admin")
        status(invitationPage(toPromoted)) === NOT_FOUND
        status(accept(toPromoted, "new-password")) === NOT_FOUND
        User.login("promoted@example.com", "old-password") must beSome

        UserContestJdbc.updateRole(admin.getId, 10, "jury")
        status(accept(toOther, "new-password")) === NOT_FOUND
        User.login("other@example.com", "old-password") must beSome
      }
    }

    "refuse a password link for an account without an email where Wikimedia sign-in is offered, and say why" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val wiki = User.create(User("Wiki juror", "", None, Set("jury"), None, Some(10), wikiAccount = Some("Wiki juror")))
        val settings = new OAuthSettings(Configuration.from(Map(
          "oauth.wikimedia.clients" -> Seq(Map("host" -> "jury.example", "clientId" -> "wm", "clientSecret" -> "secret")))))
        val mail = new RecordingMail
        val controller = new UserController(app.injector.instanceOf[ControllerComponents], userService(mail),
          app.injector.instanceOf[modules.BlockingExecutionContext], ThemeCookies.default, settings)

        val page = contentAsString(call(controller.users(Some(10)),
          as(admin, Some(10))(FakeRequest(GET, "/admin/users?contestId=10")).withCSRFToken))
        page must contain("Signs in with Wikimedia, as Wiki juror")
        page must not(contain(routes.UserController.shareInvitation(wiki.getId, 10).url))
        val refused = call(controller.shareInvitation(wiki.getId, 10),
          as(admin, Some(10))(FakeRequest(POST, s"/users/${wiki.getId}/invitation/share?contestId=10")).withCSRFToken)
        status(refused) === BAD_REQUEST
        contentAsString(refused) must contain("signs in with Wikimedia")
        AccountTokenJdbc.findByUser(wiki.getId) must beEmpty
      }
    }

    "refuse at use a link of an account without an email made before Wikimedia sign-in was offered" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val wiki = User.create(User("Wiki juror", "", None, Set("jury"), None, Some(10), wikiAccount = Some("Wiki juror")))
        val mail = new RecordingMail
        val Seq(token) = linksIn(shareInvitation(mail, admin, wiki.getId, 10)) // not offered yet
        val settings = new OAuthSettings(Configuration.from(Map(
          "oauth.wikimedia.clients" -> Seq(Map("host" -> "jury.example", "clientId" -> "wm", "clientSecret" -> "secret")))))
        val login = new LoginController(userService(mail), app.injector.instanceOf[ControllerComponents],
          app.injector.instanceOf[modules.SignInExecutionContext], ThemeCookies.default, settings)

        status(call(login.invitation(token), FakeRequest(GET, s"/invitation/$token").withCSRFToken)) === NOT_FOUND
        status(call(login.acceptInvitation(), FakeRequest(POST, "/invitation")
          .withFormUrlEncodedBody("token" -> token, "password" -> "wiki-password", "repeat.password" -> "wiki-password")
          .withCSRFToken)) === NOT_FOUND
        reloaded(wiki).password must beNone
        status(invitationPage(token)) === OK // still live: refused only while Wikimedia is offered
      }
    }
  }
}
