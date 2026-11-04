package controllers

import controllers.CsrfTestApp.{token => csrfToken, withCsrfApp}
import db.scalikejdbc.{AccountTokenJdbc, PlayTestDb, User, UserContest, UserContestJdbc}
import org.apache.pekko.stream.Materializer
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test._
import scalikejdbc._
import services.{AccountTokenService, ApiTokenService, PasswordHasher}

import scala.concurrent.Future

/** Security batch S2: no sign-up; accounts come from an organizer's invitation, whose link sets
  * the password and proves the email; an access role never goes to an account whose holder has
  * not proven its email (O2); root's "reset password" mails such a link (P7).
  */
class AccountInvitationSpec extends PlaySpecification with PlayTestDb with AccountTestSupport {

  sequential

  private def as[A](user: User, contestId: Option[Long] = None)(request: FakeRequest[A]): FakeRequest[A] =
    request.withSession(Seq(Secured.UserName -> user.email) ++ contestId.map(Secured.CurrentContestId -> _.toString): _*)

  /** The users page's add row (or an edit form, with `id`), posted by `admin` in the contest. */
  private def save(mail: RecordingMail, admin: User, contestId: Long, fields: (String, String)*)(implicit
      app: Application
  ): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(userController(mail).saveUser(),
      as(admin, Some(contestId))(FakeRequest(POST, "/users/save"))
        .withFormUrlEncodedBody((Seq("contest" -> contestId.toString) ++ fields): _*).withCSRFToken)
  }

  private def add(mail: RecordingMail, admin: User, contestId: Long, email: String, role: String = "jury")(implicit
      app: Application
  ): Future[Result] =
    save(mail, admin, contestId, "id" -> "0", "fullname" -> "Person", "email" -> email, "roles" -> role)

  private def importUsers(mail: RecordingMail, admin: User, contestId: Long, lines: String, role: String = "jury")(
      implicit app: Application
  ): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(userController(mail).importUsers(Some(contestId)),
      as(admin, Some(contestId))(FakeRequest(POST, s"/admin/importusers?contestId=$contestId"))
        .withFormUrlEncodedBody("userstoimport" -> lines, "role" -> role).withCSRFToken)
  }

  private def invitationPage(token: String)(implicit app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(loginController(new RecordingMail).invitation(token), FakeRequest(GET, s"/invitation/$token").withCSRFToken)
  }

  private def accept(token: String, password: String, repeat: Option[String] = None)(implicit
      app: Application
  ): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(loginController(new RecordingMail).acceptInvitation(),
      FakeRequest(POST, "/invitation")
        .withFormUrlEncodedBody("token" -> token, "password" -> password, "repeat.password" -> repeat.getOrElse(password))
        .withCSRFToken)
  }

  private def roles(userId: Long): Seq[UserContest] = UserContestJdbc.findByUser(userId)

  private def reloaded(user: User): User = User.findById(user.getId).get

  private def tokenRows: List[(String, String, Option[String])] =
    DB.readOnly { implicit s =>
      sql"SELECT token_hash, purpose, role FROM account_token ORDER BY id".map(rs =>
        (rs.string(1), rs.string(2), rs.stringOpt(3))).list()
    }

  "sign-up" should {
    "be gone: no route, and no link on the login page" in {
      testDbApp { implicit app =>
        status(route(app, FakeRequest(GET, "/signup")).get) === NOT_FOUND
        status(route(app, FakeRequest(POST, "/signup").withFormUrlEncodedBody(
          "login" -> "new@example.com", "password" -> "p", "repeat.password" -> "p")).get) === NOT_FOUND
        User.count() === 0L

        val login = route(app, FakeRequest(GET, "/auth").withCSRFToken).get
        status(login) === OK
        contentAsString(login) must not(contain("/signup"))
        contentAsString(login) must contain("invitation by email")
      }
    }
  }

  "an organizer's add of a new person [A-07]" should {
    "create the account without a password and mail an invitation whose token is stored hashed" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val mail = new RecordingMail

        status(add(mail, admin, 10, "new@example.com")) === SEE_OTHER

        val created = User.findByEmail("new@example.com").head
        created.password must beNone
        created.emailVerified must beFalse
        roles(created.getId) === Seq(UserContest(created.getId, 10, "jury"))
        mail.recipients === Seq("new@example.com")
        val token = mail.lastToken
        tokenRows === List((AccountTokenService.hash(token), AccountTokenJdbc.Invitation, None))
        DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM account_token WHERE token_hash = $token"
          .map(_.long(1)).single()) === Some(0L)
        User.login("new@example.com", "") must beNone
      }
    }

    "send no invitation for a role without access, nor without an email" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val mail = new RecordingMail

        status(add(mail, admin, 10, "board@example.com", "board")) === SEE_OTHER
        status(importUsers(mail, admin, 10, "User:Wiki_juror")) === SEE_OTHER

        mail.sent must beEmpty
        User.findByAccount("Wiki juror").map(_.password) === Seq(None) // normalised, no password
      }
    }
  }

  "accepting an invitation [J-01]" should {
    "show the form, set the password once, verify the email, sign in, and refuse the link again" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val mail = new RecordingMail
        status(add(mail, admin, 10, "new@example.com")) === SEE_OTHER
        status(add(mail, admin, 10, "new@example.com")) === SEE_OTHER // already a member: no new mail
        mail.sent.size === 1
        val token = mail.lastToken

        val page = invitationPage(token)
        status(page) === OK
        header("Referrer-Policy", page) === Some("no-referrer")
        header(CACHE_CONTROL, page) === Some("no-store")
        contentAsString(page) must contain(s"""name="token" value="$token"""")

        status(accept(token, "new-password", Some("other"))) === BAD_REQUEST
        status(accept(token, "a" * 73)) === BAD_REQUEST
        reloaded(User.findByEmail("new@example.com").head).password must beNone

        val accepted = accept(token, "new-password")
        status(accepted) === SEE_OTHER
        session(accepted).get(Secured.UserName) === Some("new@example.com")
        session(accepted).get(Secured.CurrentContestId) === Some("10")
        val user = User.findByEmail("new@example.com").head
        // the account's version after the acceptance, and its id (H2)
        session(accepted).get(Secured.AuthVersion) === Some(user.authVersion.toString)
        session(accepted).get(Secured.AccountId) === user.id.map(_.toString)
        user.emailVerified must beTrue
        User.login("new@example.com", "new-password").flatMap(_.id) === user.id

        val again = accept(token, "taken-over")
        status(again) === NOT_FOUND
        contentAsString(again) must contain("The link is invalid, has expired or has been used already.")
        status(invitationPage(token)) === NOT_FOUND
        User.login("new@example.com", "taken-over") must beNone
      }
    }

    "refuse an expired, revoked, unknown or malformed link, or one sent to an address the account no longer has, with the same page" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val mail = new RecordingMail
        Seq("a@example.com", "b@example.com", "c@example.com").foreach(e => status(add(mail, admin, 10, e)))
        val Seq(expired, revoked, moved) = mail.sent.toSeq.map(m => RecordingMail.Link.findFirstMatchIn(m._4).get.group(2))
        DB.autoCommit { implicit s =>
          sql"UPDATE account_token SET expires_at = NOW(3) - INTERVAL 1 SECOND WHERE token_hash = ${AccountTokenService.hash(expired)}".update.apply()
          sql"UPDATE account_token SET revoked_at = NOW(3) WHERE token_hash = ${AccountTokenService.hash(revoked)}".update.apply()
        }
        User.setEmail(User.findByEmail("c@example.com").head.getId, "c2@example.com", verified = false)

        val bodies = Seq(expired, revoked, moved, AccountTokenService.generate(), "short").map { t =>
          val result = accept(t, "new-password")
          status(result) === NOT_FOUND
          status(invitationPage(t)) === NOT_FOUND
          contentAsString(result)
        }
        bodies.distinct.size === 1
        User.findAll().flatMap(_.password).size === 1 // the admin's only
      }
    }

    "be refused without the CSRF token, through the router" in {
      withCsrfApp { app =>
        implicit val a: Application = app
        createContests(10)
        val invitee = User.create(User("Invitee", "invitee@example.com", None, Set("jury"), None, Some(10L)))
        val (_, token) = AccountTokenService.create(invitee.getId, AccountTokenJdbc.Invitation, invitee.email, Some(10L))
        val sessionToken = csrfToken(app)
        def post(fields: (String, String)*) =
          route(app, FakeRequest(POST, "/invitation")
            .withHeaders(COOKIE -> "PLAY_SESSION=x", ACCEPT -> "text/html")
            .withSession("csrfToken" -> sessionToken)
            .withFormUrlEncodedBody(fields: _*)).get
        val form = Seq("token" -> token, "password" -> "new-password", "repeat.password" -> "new-password")

        status(post(form: _*)) === FORBIDDEN
        reloaded(invitee).password must beNone
        status(post(form :+ ("csrfToken" -> sessionToken): _*)) === SEE_OTHER
        reloaded(invitee).password must beSome
      }
    }
  }

  "O2: an organizer's add of an email an unverified account with a password has [A-07]" should {
    "invite its owner instead of handing the role to the password's holder, who loses the account on acceptance" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        // registered (before S2's sign-up was closed) by someone who does not own the address
        val claimed = account("victim@example.com", None, "no", password = Some("claimer-password"))
        val claimersToken = ApiTokenService.create(claimed.getId, "tool", Some(30))
        val mail = new RecordingMail

        val added = add(mail, admin, 10, "victim@example.com")
        status(added) === SEE_OTHER
        flash(added).get("users-added") must beSome(contain("Invited (role given once they accept): 1."))
        roles(claimed.getId) must beEmpty
        User.login("victim@example.com", "claimer-password").map(Secured.inCurrentContest(_, None).contestId) ===
          Some(None)
        tokenRows.map(r => (r._2, r._3)) === List((AccountTokenJdbc.Invitation, Some("jury")))
        mail.recipients === Seq("victim@example.com")

        // the users page lists the pending invitation
        val page = call(userController(mail).users(Some(10)),
          as(admin, Some(10))(FakeRequest(GET, "/admin/users?contestId=10")).withCSRFToken)
        contentAsString(page) must contain("Invitations not accepted yet")
        contentAsString(page) must contain("victim@example.com")

        status(accept(mail.lastToken, "owner-password")) === SEE_OTHER
        roles(claimed.getId) === Seq(UserContest(claimed.getId, 10, "jury"))
        User.login("victim@example.com", "claimer-password") must beNone
        User.login("victim@example.com", "owner-password") must beSome
        // the claimer's session (version 0) and API token end
        Secured.sessionUser("victim@example.com", Some("10"), None, None) must beNone
        Secured.sessionUser("victim@example.com", Some("10"), Some("1"), None).map(_.contestRole) === Some(Some("jury"))
        ApiTokenService.authenticate(claimersToken) must beNone
        status(route(app, FakeRequest(GET, "/").withSession(Secured.UserName -> "victim@example.com")).get) === SEE_OTHER
      }
    }

    "join a verified account, or one without a password, directly, as before" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val verified = account("verified@example.com", None, "no", verified = true)
        val placeholder = account("placeholder@example.com", None, "no", password = None)
        val mail = new RecordingMail

        status(add(mail, admin, 10, "verified@example.com")) === SEE_OTHER
        status(add(mail, admin, 10, "placeholder@example.com", "organizer")) === SEE_OTHER

        roles(verified.getId) === Seq(UserContest(verified.getId, 10, "jury"))
        roles(placeholder.getId) === Seq(UserContest(placeholder.getId, 10, "organizer"))
        reloaded(verified).password === verified.password
        // the passwordless account joins at once, and gets an invitation to set its password
        tokenRows.map(r => (r._2, r._3)) === List((AccountTokenJdbc.Invitation, None))
        mail.sent.filter(_._4.contains("/invitation/")).map(_._2.email).toSeq === Seq("placeholder@example.com")
      }
    }

    "let a role without access join directly" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val unverified = account("board@example.com", None, "no")
        val mail = new RecordingMail

        status(add(mail, admin, 10, "board@example.com", "board")) === SEE_OTHER
        roles(unverified.getId) === Seq(UserContest(unverified.getId, 10, "board"))
        mail.sent must beEmpty
      }
    }

    "not join an account by an unverified wiki account: not added, said so" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        // set its own wiki account to someone else's before S2 made it read-only
        val claimer = account("claimer@example.com", None, "no", wikiAccount = Some("Victim wiki"))
        val mail = new RecordingMail

        val result = importUsers(mail, admin, 10, "User:Victim_wiki")
        status(result) === SEE_OTHER
        flash(result).get("users-added") must beSome(contain("Not added, as the wiki account belongs to an account"))
        roles(claimer.getId) must beEmpty
        mail.sent must beEmpty
        User.findAll().size === 2

        // a role without access: harmless, joins
        status(importUsers(mail, admin, 10, "User:Victim wiki", "board")) === SEE_OTHER
        roles(claimer.getId) === Seq(UserContest(claimer.getId, 10, "board"))
      }
    }
  }

  "an admin's role change for an unverified member with a password [A-08]" should {
    "become an invitation, the role given once accepted; a direct change revokes it" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val member = account("member@example.com", Some(10), "no")
        val mail = new RecordingMail
        def form(role: String) = Seq("id" -> member.getId.toString, "fullname" -> member.fullname,
          "email" -> member.email, "roles" -> role)

        val result = save(mail, admin, 10, form("jury"): _*)
        status(result) === SEE_OTHER
        flash(result).get("users-added") must beSome(contain("An invitation has been sent."))
        roles(member.getId).map(_.role) === Seq("no")
        val first = mail.lastToken

        // set directly to board (no access): the pending jury role is revoked
        status(save(mail, admin, 10, form("board"): _*)) === SEE_OTHER
        roles(member.getId).map(_.role) === Seq("board")
        status(accept(first, "member-password")) === NOT_FOUND

        status(save(mail, admin, 10, form("organizer"): _*)) === SEE_OTHER
        status(accept(mail.lastToken, "member-password")) === SEE_OTHER
        roles(member.getId).map(_.role) === Seq("organizer")
      }
    }
  }

  "re-sending an invitation [A-07]" should {
    "be for an admin of the contest, for an unverified member or pending invitee of it, never a root account" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val admin10 = account("admin10@example.com", Some(10), "admin", verified = true)
        val admin20 = account("admin20@example.com", Some(20), "admin", verified = true)
        val juror = account("juror@example.com", Some(10), "jury", verified = true)
        val member = account("member@example.com", Some(10), "jury")
        val root = account("root@example.com", None, "no", root = true)
        UserContestJdbc.createMembership(root.getId, 10, "jury")
        val outsider = account("outsider@example.com", Some(20), "jury")
        val mail = new RecordingMail
        def reinvite(as: User, id: Long, contestId: Long) =
          call(userController(mail).reinvite(id, contestId),
            this.as(as, Some(as.contestId.getOrElse(contestId)))(FakeRequest(POST, s"/users/$id/invite?contestId=$contestId"))
              .withCSRFToken)

        status(reinvite(admin20, member.getId, 10)) === FORBIDDEN
        status(reinvite(juror, member.getId, 10)) === FORBIDDEN
        status(reinvite(admin10, outsider.getId, 10)) === NOT_FOUND
        status(reinvite(admin10, root.getId, 10)) === BAD_REQUEST
        status(reinvite(admin10, juror.getId, 10)) === BAD_REQUEST // verified already
        mail.sent must beEmpty

        status(reinvite(admin10, member.getId, 10)) === SEE_OTHER
        mail.recipients === Seq("member@example.com")
        AccountTokenJdbc.findByUser(member.getId).map(t => (t.contestId, t.createdBy)) ===
          Seq((Some(10L), admin10.id))

        // a pending invitee (not a member yet) keeps the pending role in the new invitation
        val pendingAccount = account("pending@example.com", None, "no")
        status(add(mail, admin10, 10, "pending@example.com", "organizer")) === SEE_OTHER
        val firstLink = mail.lastToken
        status(reinvite(admin10, pendingAccount.getId, 10)) === SEE_OTHER
        status(accept(firstLink, "pending-password")) === NOT_FOUND
        status(accept(mail.lastToken, "pending-password")) === SEE_OTHER
        roles(pendingAccount.getId) === Seq(UserContest(pendingAccount.getId, 10, "organizer"))
      }
    }

    "reach a verified member whose legacy password was retired (P8), and an add of such an account invite it" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val admin10 = account("admin10@example.com", Some(10), "admin", verified = true)
        val retired = account("retired@example.com", Some(10), "jury", verified = true)
        val elsewhere = account("elsewhere@example.com", Some(20), "jury", verified = true)
        val withPassword = account("current@example.com", Some(10), "jury", verified = true)
        DB.autoCommit { implicit s =>
          sql"UPDATE users SET password = ${PasswordHasher.RetiredLegacy} WHERE id IN (${retired.getId}, ${elsewhere.getId})"
            .update.apply()
        }
        val mail = new RecordingMail
        def reinvite(id: Long) =
          call(userController(mail).reinvite(id, 10),
            as(admin10, Some(10L))(FakeRequest(POST, s"/users/$id/invite?contestId=10")).withCSRFToken)

        // a verified member with a usable password still needs none
        status(reinvite(withPassword.getId)) === BAD_REQUEST
        mail.sent must beEmpty

        status(reinvite(retired.getId)) === SEE_OTHER
        mail.recipients === Seq("retired@example.com")
        status(accept(mail.lastToken, "new-password")) === SEE_OTHER
        User.login("retired@example.com", "new-password").flatMap(_.id) === retired.id

        // added to another contest: an invitation, not the "use your password" greeting
        status(add(mail, admin10, 10, "elsewhere@example.com")) === SEE_OTHER
        mail.recipients.last === "elsewhere@example.com"
        AccountTokenJdbc.findByUser(elsewhere.getId).map(t => (t.purpose, t.contestId)) ===
          Seq((AccountTokenJdbc.Invitation, Some(10L)))
        status(accept(mail.lastToken, "elsewhere-password")) === SEE_OTHER
        User.login("elsewhere@example.com", "elsewhere-password").flatMap(_.id) === elsewhere.id
      }
    }

    "count the invitations that could not be mailed, and log account ids only" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val mail = new RecordingMail
        mail.failing = true

        val (result, lines) = accountLog {
          val r = add(mail, admin, 10, "new@example.com")
          status(r)
          r
        }
        flash(result).get("users-added") must beSome(contain("Invitations that could not be sent: 1."))
        tokenRows.size === 1
        lines must contain(beMatching("account_token_created purpose=invitation tokenId=\\d+ accountId=\\d+ .*"))
        lines must contain(beMatching("account_mail_failed purpose=invitation tokenId=\\d+ accountId=\\d+ error=.*"))
        lines.filter(_.contains("@")) must beEmpty
      }
    }
  }

  "root's reset password (P7) [A-08]" should {
    "mail a link, keep the password until it is used, set it once, and log no email or token" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10)
        val root = account("root@example.com", None, "no", root = true, verified = true)
        val juror = account("juror@example.com", Some(10), "jury", password = Some("old-password"), verified = true)
        val mail = new RecordingMail

        val (reset, lines) = accountLog {
          val r = call(userController(mail).resetPassword(juror.getId),
            as(root)(FakeRequest(POST, s"/users/resetpasswd/${juror.getId}")).withCSRFToken)
          status(r)
          r
        }
        status(reset) === SEE_OTHER
        flash(reset).get("password-reset") must beSome(contain("A link to set a new password has been sent"))
        mail.recipients === Seq("juror@example.com")
        val token = mail.lastToken
        lines.filter(l => l.contains("@") || l.contains(token)) must beEmpty
        User.login("juror@example.com", "old-password") must beSome

        status(invitationPage(token)) === OK
        status(accept(token, "new-password")) === SEE_OTHER
        User.login("juror@example.com", "old-password") must beNone
        User.login("juror@example.com", "new-password") must beSome
        status(accept(token, "again-password")) === NOT_FOUND
        User.login("juror@example.com", "again-password") must beNone
        roles(juror.getId) === Seq(UserContest(juror.getId, 10, "jury"))
      }
    }

    "be refused to anyone but root, and an expired link refused" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val juror = account("juror@example.com", Some(10), "jury", password = Some("old-password"), verified = true)
        val mail = new RecordingMail

        val refused = call(userController(mail).resetPassword(juror.getId),
          as(admin, Some(10))(FakeRequest(POST, s"/users/resetpasswd/${juror.getId}")).withCSRFToken)
        status(refused) === FORBIDDEN
        mail.sent must beEmpty

        val (_, token) = AccountTokenService.create(juror.getId, AccountTokenJdbc.PasswordReset, juror.email)
        DB.autoCommit { implicit s =>
          sql"UPDATE account_token SET expires_at = NOW(3) - INTERVAL 1 SECOND".update.apply()
        }
        status(accept(token, "new-password")) === NOT_FOUND
        User.login("juror@example.com", "old-password") must beSome
      }
    }

    "need the CSRF token, through the router" in {
      withCsrfApp { app =>
        implicit val a: Application = app
        val root = account("root@example.com", None, "no", root = true, verified = true)
        val juror = account("juror@example.com", None, "no", password = Some("old-password"), verified = true)
        val sessionToken = csrfToken(app)
        val refused = route(app, FakeRequest(POST, s"/users/resetpasswd/${juror.getId}")
          .withHeaders(COOKIE -> "PLAY_SESSION=x", ACCEPT -> "text/html")
          .withSession(Secured.UserName -> root.email, "csrfToken" -> sessionToken)).get
        status(refused) === FORBIDDEN
        AccountTokenJdbc.findByUser(juror.getId) must beEmpty
      }
    }
  }

  "the password hasher's limits" should {
    "apply to accepting an invitation: no turn, nothing changes" in {
      testDbApp { implicit app =>
        val invitee = User.create(User("Invitee", "invitee@example.com"))
        val (_, token) = AccountTokenService.create(invitee.getId, AccountTokenJdbc.Invitation, invitee.email)
        val request = FakeRequest(POST, "/invitation")
          .withFormUrlEncodedBody("token" -> token, "password" -> "p4ssword", "repeat.password" -> "p4ssword").withCSRFToken
        val hasher = PasswordHasher.default
        val limit = hasher.settings.maxQueued
        (1 to limit).map(_ => hasher.admit()).forall(identity) must beTrue
        try {
          implicit val mat: Materializer = app.materializer
          status(call(loginController(new RecordingMail).acceptInvitation(), request)) === SERVICE_UNAVAILABLE
        } finally (1 to limit).foreach(_ => hasher.leave())
        reloaded(invitee).password must beNone
        status(accept(token, "p4ssword")) === SEE_OTHER
      }
    }
  }
}
