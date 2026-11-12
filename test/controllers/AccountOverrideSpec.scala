package controllers

import db.scalikejdbc.{AccountChange, AccountChangeJdbc, AccountTokenJdbc, PlayTestDb, User, UserContestJdbc, UserIdentity,
  UserIdentityJdbc}
import org.apache.pekko.stream.Materializer
import org.jsoup.Jsoup
import play.api.Application
import play.api.mvc.{ControllerComponents, Result}
import play.api.test.CSRFTokenHelper._
import play.api.test._
import scalikejdbc._
import services.AccountTokenService

import scala.concurrent.Future

/** The maintainer's decisions of 2026-11-12 on account management after 26.11:
  *  1. an admin who may edit an account ([[UserController.canEditAccount]]), or root, changes its
  *     email directly, password or not: unverified, the password kept, sessions ended;
  *  2. invitations are mailed on an add, an import and an edit only if "Send an invitation
  *     email" is ticked (unticked by default);
  *  3. a verified former address gets a short notice (the new address masked, who changed it);
  *  4. the account change log (V96): written with each change to another person's account,
  *     never for a refused one, and read only by the contest's admins and root.
  * One's own email change still goes through the link ([[OwnAccountSpec]]).
  */
class AccountOverrideSpec extends PlaySpecification with PlayTestDb with AccountTestSupport {

  sequential

  private def as[A](user: User, contestId: Option[Long])(request: FakeRequest[A]): FakeRequest[A] =
    request.withSession(Seq(Secured.UserName -> user.email) ++ contestId.map(Secured.CurrentContestId -> _.toString): _*)

  private def save(mail: RecordingMail, by: User, contestId: Option[Long], fields: (String, String)*)(implicit
      app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(userController(mail).saveUser(), as(by, contestId)(FakeRequest(POST, "/users/save"))
      .withFormUrlEncodedBody(fields: _*).withCSRFToken)
  }

  private def importUsers(mail: RecordingMail, by: User, contestId: Long, fields: (String, String)*)(implicit
      app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(userController(mail).importUsers(Some(contestId)),
      as(by, Some(contestId))(FakeRequest(POST, s"/admin/importusers?contestId=$contestId"))
        .withFormUrlEncodedBody(fields: _*).withCSRFToken)
  }

  /** Another member's edit form in the contest, as the page fills it, with `changes`. */
  private def form(user: User, contestId: Option[Long], role: String, changes: (String, String)*): Seq[(String, String)] =
    (Map("id" -> user.getId.toString, "fullname" -> user.fullname, "email" -> user.email,
      "wikiAccount" -> user.wikiAccount.getOrElse(""), "roles" -> role) ++
      contestId.map(c => "contest" -> c.toString) ++ changes).toSeq

  private def editPage(by: User, id: Long, contestId: Option[Long])(implicit app: Application): String = {
    implicit val mat: Materializer = app.materializer
    contentAsString(call(userController(new RecordingMail).editUser(id, contestId),
      as(by, contestId)(FakeRequest(GET, s"/users/edit/$id")).withCSRFToken))
  }

  private def changesPage(by: User, contestId: Long, session: Option[Long])(implicit app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(new AccountChangeController(app.injector.instanceOf[ControllerComponents]).changes(contestId),
      as(by, session)(FakeRequest(GET, s"/admin/account-changes?contestId=$contestId")).withCSRFToken)
  }

  private def usersPage(by: User, contestId: Long)(implicit app: Application): String = {
    implicit val mat: Materializer = app.materializer
    contentAsString(call(userController(new RecordingMail).users(Some(contestId)),
      as(by, Some(contestId))(FakeRequest(GET, s"/admin/users?contestId=$contestId")).withCSRFToken))
  }

  private def reloaded(user: User): User = User.findById(user.getId).get

  private def log(userId: Long): Seq[AccountChange] = AccountChangeJdbc.findByUser(userId, None, 100)

  private def authVersion(id: Long): Int =
    DB.readOnly(implicit s => sql"SELECT auth_version FROM users WHERE id = $id".map(_.int(1)).single().get)

  "an admin's change of another account's email" should {
    "be made directly for an account with a password and a verified address, which keeps the password" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true).copy(fullname = "Anna Admin")
        User.updateUser(admin.getId, "Anna Admin", None, None)
        val juror = account("placeholder@example.com", Some(10), "jury", password = Some("pw-juror"), verified = true)
        UserIdentityJdbc.create(UserIdentity("google", "g-1", juror.getId, Some("placeholder@example.com"), None))
        val version = authVersion(juror.getId)
        val mail = new RecordingMail

        val result = save(mail, admin, Some(10), form(juror, Some(10), "jury", "email" -> "Juror@Example.org",
          "comment" -> "address from the WLE team list"): _*)
        status(result) === SEE_OTHER
        val notice = flash(result).get("users-added").getOrElse("")
        notice must contain("juror@example.org")
        notice must contain("current password")
        notice must contain("notice was emailed to the former address")

        val changed = reloaded(juror)
        changed.email === "juror@example.org"
        changed.emailVerified must beFalse
        // the same password, with the new address; the old address signs in no more
        User.login("juror@example.org", "pw-juror") must beSome
        User.login("placeholder@example.com", "pw-juror") must beNone
        authVersion(juror.getId) must not(beEqualTo(version))
        UserIdentityJdbc.findByUser(juror.getId) must beEmpty
        // its existing membership stays: it still votes in its rounds
        UserContestJdbc.findByUser(juror.getId).map(m => (m.contestId, m.role)) === Seq((10L, "jury"))

        // no invitation (unticked), only the short notice to the former, verified address
        mail.recipients === Seq("placeholder@example.com")
        val body = mail.sent.head._4
        body must contain("j***@example.org")
        body must contain("Anna Admin")
        body must not(contain("http"))
        body must not(contain("juror@example.org"))
        AccountTokenJdbc.findByUser(juror.getId) must beEmpty

        val entries = log(juror.getId)
        entries.map(e => (e.field, e.oldValue, e.newValue, e.mailSent, e.contestId, e.actorId, e.comment)) ===
          Seq((AccountChangeJdbc.Email, Some("placeholder@example.com"), Some("juror@example.org"), Some(true), Some(10L),
            admin.getId, Some("address from the WLE team list")))
      }
    }

    "send no notice when the former address was unverified, and an invitation only if ticked" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val first = account("first@example.com", Some(10), "jury", password = Some("pw-1"))
        val second = account("second@example.com", Some(10), "jury", password = Some("pw-2"))
        val mail = new RecordingMail

        status(save(mail, admin, Some(10), form(first, Some(10), "jury", "email" -> "first.new@example.com"): _*)) ===
          SEE_OTHER
        mail.sent must beEmpty
        reloaded(first).email === "first.new@example.com"
        User.login("first.new@example.com", "pw-1") must beSome
        log(first.getId).map(e => (e.field, e.mailSent)) === Seq((AccountChangeJdbc.Email, Some(false)))

        val ticked = save(mail, admin, Some(10), form(second, Some(10), "jury", "email" -> "second.new@example.com",
          "sendInvitation" -> "true"): _*)
        status(ticked) === SEE_OTHER
        flash(ticked).get("users-added") must beSome(contain("An invitation has been sent"))
        mail.recipients === Seq("second.new@example.com")
        AccountTokenService.check(mail.lastToken, AccountTokenJdbc.SetsPassword) must beRight
        // until the invitation is used, the password still signs in
        User.login("second.new@example.com", "pw-2") must beSome
        log(second.getId).map(e => (e.field, e.mailSent)) ===
          Seq((AccountChangeJdbc.Invitation, Some(true)), (AccountChangeJdbc.Email, Some(false)))
      }
    }

    "be made directly by root, for an account in a contest root is not in" in {
      testDbApp { implicit app =>
        createContests(10)
        val root = account("root@example.com", None, "no", root = true, verified = true)
        val juror = account("juror@example.com", Some(10), "jury", password = Some("pw-juror"), verified = true)
        val mail = new RecordingMail

        status(save(mail, root, None, form(juror, Some(10), "jury", "email" -> "moved@example.com"): _*)) === SEE_OTHER
        reloaded(juror).email === "moved@example.com"
        User.login("moved@example.com", "pw-juror") must beSome
        mail.recipients === Seq("juror@example.com") // the notice only, no confirmation link
        mail.sent.head._4 must not(contain("http"))
        log(juror.getId).map(e => (e.field, e.actorId, e.contestId)) === Seq((AccountChangeJdbc.Email, root.getId, Some(10L)))
      }
    }

    "be refused, writing and logging nothing, for a taken address, for an account the admin may not edit, " +
      "with a password set, or with a comment over 500 characters" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val juror = account("juror@example.com", Some(10), "jury", password = Some("pw-juror"), verified = true)
        account("taken@example.com", Some(10), "jury")
        // also in another contest: only its role here may change
        val shared = account("shared@example.com", Some(10), "jury", password = Some("pw"), verified = true)
        UserContestJdbc.createMembership(shared.getId, 20, "jury")
        val mail = new RecordingMail

        val taken = save(mail, admin, Some(10), form(juror, Some(10), "jury", "email" -> "TAKEN@example.com"): _*)
        status(taken) === BAD_REQUEST
        contentAsString(taken) must contain("This email is already used by another account.")
        val other = save(mail, admin, Some(10), form(shared, Some(10), "jury", "email" -> "new@example.com"): _*)
        status(other) === BAD_REQUEST
        contentAsString(other) must contain("also in other contests")
        val withPassword = save(mail, admin, Some(10), form(juror, Some(10), "jury", "email" -> "new@example.com",
          "password" -> "chosen-password"): _*)
        status(withPassword) === BAD_REQUEST
        val longComment = save(mail, admin, Some(10), form(juror, Some(10), "jury", "email" -> "new@example.com",
          "comment" -> "x" * 501): _*)
        status(longComment) === BAD_REQUEST
        // the comment is shown again, with its error
        Jsoup.parse(contentAsString(longComment)).select("input[name=comment]").attr("value") === "x" * 501

        reloaded(juror).email === "juror@example.com"
        reloaded(shared).email === "shared@example.com"
        User.login("juror@example.com", "pw-juror") must beSome
        mail.sent must beEmpty
        DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM account_change".map(_.long(1)).single()) === Some(0L)
      }
    }
  }

  "one's own email change" should {
    "still go through the link mailed to the new address, and not be in the change log" in {
      testDbApp { implicit app =>
        createContests(10)
        val juror = account("juror@example.com", Some(10), "jury", password = Some("pw-juror"), verified = true)
        val mail = new RecordingMail
        status(save(mail, juror, Some(10), "id" -> juror.getId.toString, "fullname" -> juror.fullname,
          "email" -> "mine@example.com", "currentPassword" -> "pw-juror")) === SEE_OTHER
        reloaded(juror).email === "juror@example.com"
        mail.recipients === Seq("mine@example.com")
        val body = mail.sent.head._4
        // what it is about: the account's profile, the site
        body must contain("profile page in WLX Jury (jury.wle.org.ua)")
        body must contain("/email/confirm/")
        log(juror.getId) must beEmpty
      }
    }
  }

  "the invitation mail" should {
    "not be sent on an add or an import unless asked for, and say so" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val mail = new RecordingMail

        val added = save(mail, admin, Some(10), "id" -> "0", "fullname" -> "New", "email" -> "new@example.com",
          "roles" -> "jury", "contest" -> "10")
        status(added) === SEE_OTHER
        flash(added).get("users-added") must beSome(contain("Invitations not emailed, as chosen: 1"))
        val imported = importUsers(mail, admin, 10, "userstoimport" -> "imported@example.com", "role" -> "jury")
        status(imported) === SEE_OTHER
        flash(imported).get("users-added") must beSome(contain("Invitations not emailed, as chosen: 1"))
        mail.sent must beEmpty
        val created = User.findByEmail("new@example.com").head
        AccountTokenJdbc.findByUser(created.getId) must beEmpty
        log(created.getId).map(e => (e.field, e.newValue)) ===
          Seq((AccountChangeJdbc.Role, Some("jury")), (AccountChangeJdbc.AccountCreated, Some("new@example.com")))
        // the member's row offers the envelope and a link to share instead
        val page = Jsoup.parse(usersPage(admin, 10))
        page.select(s"form[action=/users/${created.getId}/invite?contestId=10]").size === 1
        page.select(s"form[action=/users/${created.getId}/invitation/share?contestId=10]").size === 1
        page.select("input#add-user-send-invitation[form=add-user]").hasAttr("checked") must beFalse

        status(save(mail, admin, Some(10), "id" -> "0", "fullname" -> "Mailed", "email" -> "mailed@example.com",
          "roles" -> "jury", "contest" -> "10", "sendInvitation" -> "true")) === SEE_OTHER
        status(importUsers(mail, admin, 10, "userstoimport" -> "imported2@example.com", "role" -> "jury",
          "sendInvitation" -> "true")) === SEE_OTHER
        mail.recipients === Seq("mailed@example.com", "imported2@example.com")
      }
    }

    "keep an invitation an existing account needs unsent, shown as not sent, until the admin sends it" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        // an unverified account with a password elsewhere: an access role waits for an invitation (O2)
        val known = account("known@example.com", Some(20), "jury", password = Some("pw"))
        val mail = new RecordingMail

        val added = save(mail, admin, Some(10), "id" -> "0", "fullname" -> "Known", "email" -> "known@example.com",
          "roles" -> "jury", "contest" -> "10")
        status(added) === SEE_OTHER
        mail.sent must beEmpty
        UserContestJdbc.findByUser(known.getId).map(_.contestId) === Seq(20L)
        val pending = AccountTokenJdbc.findByUser(known.getId)
        pending.map(t => (t.purpose, t.role, t.unsent, t.live)) === Seq((AccountTokenJdbc.Invitation, Some("jury"), true, true))
        val page = Jsoup.parse(usersPage(admin, 10))
        page.select("table.pending-invitations tbody tr").text must contain("Not sent")
        page.select("table.pending-invitations button.reinvite").text === "Send the invitation"
        log(known.getId).map(e => (e.field, e.newValue, e.mailSent)) ===
          Seq((AccountChangeJdbc.Invitation, Some("jury"), Some(false)))

        implicit val mat: Materializer = app.materializer
        status(call(userController(mail).reinvite(known.getId, 10),
          as(admin, Some(10))(FakeRequest(POST, s"/users/${known.getId}/invite")).withCSRFToken)) === SEE_OTHER
        mail.recipients === Seq("known@example.com")
        AccountTokenJdbc.findByUser(known.getId).filter(_.live).map(t => (t.role, t.unsent)) === Seq((Some("jury"), false))
      }
    }
  }

  "the account change log" should {
    "record a role change, a name and a password set, with the comment" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val juror = account("juror@example.com", Some(10), "jury", verified = true)
        val mail = new RecordingMail
        status(save(mail, admin, Some(10), form(juror, Some(10), "organizer", "fullname" -> "Juror Renamed",
          "comment" -> "moved to the organizers"): _*)) === SEE_OTHER
        status(save(mail, admin, Some(10), form(reloaded(juror), Some(10), "organizer", "password" -> "chosen-pw"): _*)) ===
          SEE_OTHER
        log(juror.getId).map(e => (e.field, e.oldValue, e.newValue, e.comment)).toSet === Set(
          (AccountChangeJdbc.Password, None, None, None),
          (AccountChangeJdbc.Role, Some("jury"), Some("organizer"), Some("moved to the organizers")),
          (AccountChangeJdbc.Name, Some("juror@example.com"), Some("Juror Renamed"), Some("moved to the organizers")))
      }
    }

    "be shown to the contest's admins and root only" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val organizer = account("organizer@example.com", Some(10), "organizer", verified = true)
        val juror = account("juror@example.com", Some(10), "jury", password = Some("pw"), verified = true)
        val otherAdmin = account("other.admin@example.com", Some(20), "admin", verified = true)
        // another juror: the changed one's sessions ended with its email
        val juror2 = account("juror2@example.com", Some(10), "jury", password = Some("pw"), verified = true)
        val root = account("root@example.com", None, "no", root = true, verified = true)
        status(save(new RecordingMail, admin, Some(10), form(juror, Some(10), "jury", "email" -> "j.new@example.com",
          "comment" -> "typo fixed"): _*)) === SEE_OTHER
        // root's change outside a contest: not in the contest's log
        AccountChangeJdbc.create(juror.getId, root.getId, None, AccountChangeJdbc.SessionsEnded)

        val forAdmin = changesPage(admin, 10, Some(10))
        status(forAdmin) === OK
        val page = Jsoup.parse(contentAsString(forAdmin))
        page.select("table.account-changes tbody tr").size === 1
        page.select("table.account-changes tbody tr").text must contain("j.new@example.com")
        page.select("table.account-changes tbody tr").text must contain("typo fixed")
        page.select("table.account-changes tbody tr").text must contain("notice to the former address queued")
        status(changesPage(root, 10, None)) === OK

        status(changesPage(organizer, 10, Some(10))) === FORBIDDEN
        status(changesPage(juror2, 10, Some(10))) === FORBIDDEN
        status(changesPage(otherAdmin, 10, Some(20))) === FORBIDDEN
        contentAsString(changesPage(otherAdmin, 10, Some(20))) must not(contain("j.new@example.com"))

        // the edit page: the admin, this contest's entries; root, all of them
        val adminEdit = Jsoup.parse(editPage(admin, juror.getId, Some(10)))
        adminEdit.select("#account-changes tbody tr").size === 1
        val rootEdit = Jsoup.parse(editPage(root, juror.getId, Some(10)))
        rootEdit.select("#account-changes tbody tr").size === 2
        // the users page links to it
        Jsoup.parse(usersPage(admin, 10)).select("a.account-changes-link").attr("href") ===
          "/admin/account-changes?contestId=10"
      }
    }
  }

  "the review of 2026-11-12" should {
    "not let an admin hand a co-admin's account to an address they set: no invitation, no re-send, no link" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val adminA = account("a@example.com", Some(10), "admin", verified = true)
        UserContestJdbc.createMembership(adminA.getId, 20, "admin")
        val adminB = account("b@example.com", Some(10), "admin", password = Some("pw-b"), verified = true)
        val mail = new RecordingMail

        // B's contests are all A's: A may change the address (canEditAccount), but not hand it a password
        val changed = save(mail, adminA, Some(10), form(adminB, Some(10), "admin", "email" -> "a.alias@example.com",
          "sendInvitation" -> "true"): _*)
        status(changed) === SEE_OTHER
        flash(changed).get("users-added") must beSome(contain("No invitation can be sent to this account."))
        reloaded(adminB).email === "a.alias@example.com"
        mail.recipients === Seq("b@example.com") // the notice to B's former address only

        val resent = call(userController(mail).reinvite(adminB.getId, 10),
          as(adminA, Some(10))(FakeRequest(POST, s"/users/${adminB.getId}/invite")).withCSRFToken)
        status(resent) === BAD_REQUEST
        status(call(userController(mail).shareInvitation(adminB.getId, 10),
          as(adminA, Some(10))(FakeRequest(POST, s"/users/${adminB.getId}/invitation/share")).withCSRFToken)) === FORBIDDEN
        // nor through an add of the new address to A's other contest (O2)
        val added = save(mail, adminA, Some(20), "id" -> "0", "fullname" -> "B", "email" -> "a.alias@example.com",
          "roles" -> "jury", "contest" -> "20", "sendInvitation" -> "true")
        status(added) === SEE_OTHER
        flash(added).get("users-added") must beSome(contain("Accounts no invitation can be sent to"))
        mail.recipients === Seq("b@example.com")
        AccountTokenJdbc.findByUser(adminB.getId).filter(_.live) must beEmpty
        User.login("a.alias@example.com", "pw-b") must beSome

        // root may: the address is the one root wants
        val root = account("root@example.com", None, "no", root = true, verified = true)
        status(call(userController(mail).reinvite(adminB.getId, 10),
          as(root, Some(10))(FakeRequest(POST, s"/users/${adminB.getId}/invite")).withCSRFToken)) === SEE_OTHER
        mail.recipients.last === "a.alias@example.com"
      }
    }

    "refuse an admin's change of a root account's email, and of one that worked in another contest" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val rootAccount = account("chief@example.com", None, "no", root = true, verified = true)
        UserContestJdbc.createMembership(rootAccount.getId, 10, "jury")
        val past = account("past@example.com", Some(10), "jury", password = Some("pw"), verified = true)
        DB.autoCommit { implicit s =>
          sql"INSERT INTO round (id, number, contest_id, rates, created_at) VALUES (900, 1, 20, 1, NOW())".update.apply()
          sql"INSERT INTO round_user (user_id, round_id, role, active) VALUES (${past.getId}, 900, 'jury', 0)".update.apply()
        }
        val mail = new RecordingMail

        val forRoot = save(mail, admin, Some(10), form(rootAccount, Some(10), "jury", "email" -> "x@example.com"): _*)
        status(forRoot) === BAD_REQUEST
        val forPast = save(mail, admin, Some(10), form(past, Some(10), "jury", "email" -> "y@example.com"): _*)
        status(forPast) === BAD_REQUEST
        contentAsString(forPast) must contain("also in other contests")
        reloaded(rootAccount).email === "chief@example.com"
        reloaded(past).email === "past@example.com"
        mail.sent must beEmpty
        DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM account_change".map(_.long(1)).single()) === Some(0L)
      }
    }

    "decide inside the transaction: a taken address, an address changed meanwhile, the unique key" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val juror = account("juror@example.com", Some(10), "jury", password = Some("pw"), verified = true)
        val other = account("other@example.com", Some(10), "jury")
        val service = userService(new RecordingMail)
        DB.localTx(implicit s => service.changeEmailByAdmin(admin, juror.getId, "OTHER@example.com", Some(10), None)) ===
          Left("error.email.already.exist")
        DB.localTx(implicit s => service.changeEmailByAdmin(admin, juror.getId, "new@example.com", Some(10), None,
          expectedOld = Some("stale@example.com"))) === Left("user.email.changed.meanwhile")
        reloaded(juror).email === "juror@example.com"
        log(juror.getId) must beEmpty

        // only users.email's duplicate key reads as a taken address, not any integrity violation
        val duplicate = scala.util.Try(DB.autoCommit(implicit s =>
          sql"UPDATE users SET email = ${other.email} WHERE id = ${juror.getId}".update.apply())).failed.get
        UserController.isDuplicateKey(duplicate) must beTrue
        val foreignKey = scala.util.Try(DB.autoCommit(implicit s =>
          sql"INSERT INTO account_change (user_id, actor_id, field) VALUES (999999, 1, 'email')".update.apply())).failed.get
        UserController.isDuplicateKey(foreignKey) must beFalse
      }
    }

    "log root's change in the account's contest only, never root's session contest" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val root = account("root@example.com", None, "no", root = true, verified = true)
        val admin20 = account("admin20@example.com", Some(20), "admin", verified = true)
        val loose = account("loose@example.com", None, "no", password = Some("pw"), verified = true)
        val mail = new RecordingMail
        status(save(mail, root, Some(20), "id" -> loose.getId.toString, "fullname" -> "Loose One",
          "email" -> "loose.new@example.com")) === SEE_OTHER
        reloaded(loose).email === "loose.new@example.com"
        log(loose.getId).map(e => (e.field, e.contestId)).toSet ===
          Set((AccountChangeJdbc.Email, None), (AccountChangeJdbc.Name, None))
        val page = Jsoup.parse(contentAsString(changesPage(admin20, 20, Some(20))))
        page.select("table.account-changes tbody tr").size === 0
        page.text must not(contain("loose"))
      }
    }

    "not revert an address changed meanwhile from a stale page, and refuse an edit of it" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val juror = account("juror@example.com", Some(10), "jury", password = Some("pw"), verified = true)
        val mail = new RecordingMail
        // the page showed juror@example.com; someone else changed it since
        DB.autoCommit(implicit s => sql"UPDATE users SET email = 'moved@example.com' WHERE id = ${juror.getId}".update.apply())
        status(save(mail, admin, Some(10), form(juror, Some(10), "jury", "fullname" -> "Renamed",
          "emailWas" -> "juror@example.com"): _*)) === SEE_OTHER
        reloaded(juror).email === "moved@example.com"
        reloaded(juror).fullname === "Renamed"
        val edited = save(mail, admin, Some(10), form(juror, Some(10), "jury", "email" -> "mine@example.com",
          "emailWas" -> "juror@example.com"): _*)
        status(edited) === BAD_REQUEST
        contentAsString(edited) must contain("changed by someone else meanwhile")
        reloaded(juror).email === "moved@example.com"
      }
    }
  }
}
