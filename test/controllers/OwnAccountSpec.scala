package controllers

import db.scalikejdbc.{AccountTokenJdbc, PlayTestDb, User}
import org.apache.pekko.stream.Materializer
import play.api.Application
import play.api.mvc.{Flash, Result}
import play.api.test.CSRFTokenHelper._
import play.api.test._
import scalikejdbc._
import services.AccountTokenService

import scala.concurrent.Future

/** Security batch S2: one's own password or email changes only with the current password (N2),
  * a new email only once a link mailed to it is followed, without telling whether another
  * account has it (N7); one's own wiki account is read-only; nobody sets another account's
  * password; admins change another's email only for an invitee without a password; wiki
  * accounts are valid, normalised, unique on change, and encoded in links (N4); sessions of an
  * older auth version are refused; deleted accounts and ambiguous wiki names sign in no one
  * (P14, P18, N4).
  */
class OwnAccountSpec extends PlaySpecification with PlayTestDb with AccountTestSupport {

  sequential

  private def save(mail: RecordingMail, as: User, contestId: Option[Long], fields: (String, String)*)(implicit
      app: Application
  ): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(userController(mail).saveUser(),
      FakeRequest(POST, "/users/save")
        .withSession(Seq(Secured.UserName -> as.email) ++ contestId.map(Secured.CurrentContestId -> _.toString): _*)
        .withFormUrlEncodedBody(fields: _*).withCSRFToken)
  }

  /** The own profile's form of `user`, with `changes`. */
  private def own(user: User, changes: (String, String)*): Seq[(String, String)] =
    (Map("id" -> user.getId.toString, "fullname" -> user.fullname, "email" -> user.email,
      "wikiAccount" -> user.wikiAccount.getOrElse("")) ++ changes).toSeq

  /** Another member's form in the contest. */
  private def other(user: User, contestId: Long, role: String, changes: (String, String)*): Seq[(String, String)] =
    (Map("id" -> user.getId.toString, "fullname" -> user.fullname, "email" -> user.email,
      "wikiAccount" -> user.wikiAccount.getOrElse(""), "roles" -> role, "contest" -> contestId.toString) ++ changes).toSeq

  private def reloaded(user: User): User = User.findById(user.getId).get

  private def confirm(token: String)(implicit app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(loginController(new RecordingMail).emailConfirm(),
      FakeRequest(POST, "/email/confirm").withFormUrlEncodedBody("token" -> token).withCSRFToken)
  }

  "one's own password [J-32]" should {
    "change only with the right current password (N2)" in {
      testDbApp { implicit app =>
        createContests(10)
        val juror = account("juror@example.com", Some(10), "jury", password = Some("old-password"), verified = true)
        val mail = new RecordingMail

        val missing = save(mail, juror, Some(10), own(juror, "password" -> "new-password"): _*)
        status(missing) === BAD_REQUEST
        contentAsString(missing) must contain("The current password is not correct.")
        status(save(mail, juror, Some(10), own(juror, "password" -> "new-password", "currentPassword" -> "wrong"): _*)) ===
          BAD_REQUEST
        User.login(juror.email, "old-password") must beSome

        status(save(mail, juror, Some(10),
          own(juror, "password" -> "new-password", "currentPassword" -> "old-password"): _*)) === SEE_OTHER
        User.login(juror.email, "new-password") must beSome
        User.login(juror.email, "old-password") must beNone
      }
    }

    "not be needed for a change of name only" in {
      testDbApp { implicit app =>
        val user = account("user@example.com", None, "no", verified = true, root = true)
        status(save(new RecordingMail, user, None, own(user, "fullname" -> "Renamed"): _*)) === SEE_OTHER
        reloaded(user).fullname === "Renamed"
      }
    }
  }

  "one's own email [J-32]" should {
    "change only with the current password, and only once the link mailed to the new address is followed" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10)
        val juror = account("juror@example.com", Some(10), "jury", password = Some("pw-juror"), verified = true)
        val mail = new RecordingMail

        status(save(mail, juror, Some(10), own(juror, "email" -> "new@example.com"): _*)) === BAD_REQUEST
        status(save(mail, juror, Some(10), own(juror, "email" -> "new@example.com", "currentPassword" -> "x"): _*)) ===
          BAD_REQUEST
        mail.sent must beEmpty

        val requested = save(mail, juror, Some(10), own(juror, "email" -> "New@Example.com", "currentPassword" -> "pw-juror"): _*)
        status(requested) === SEE_OTHER
        redirectLocation(requested) === Some(routes.UserController.editUser(juror.getId).url)
        flash(requested).get("account-notice") must beSome(contain("A confirmation link has been sent"))
        reloaded(juror).email === "juror@example.com"
        mail.recipients === Seq("new@example.com")
        val token = mail.lastToken

        val page = call(loginController(mail).emailConfirmPage(token), FakeRequest(GET, s"/email/confirm/$token").withCSRFToken)
        status(page) === OK
        header("Referrer-Policy", page) === Some("no-referrer")
        reloaded(juror).email === "juror@example.com" // the page changes nothing

        val confirmed = confirm(token)
        status(confirmed) === OK
        contentAsString(confirmed) must contain("Your email address is now new@example.com.")
        val after = reloaded(juror)
        after.email === "new@example.com"
        after.emailVerified must beTrue
        after.authVersion === 1
        Secured.sessionUser("new@example.com", None, Some("0"), None) must beNone
        User.login("new@example.com", "pw-juror") must beSome

        status(confirm(token)) === NOT_FOUND
      }
    }

    "answer the same whether another account has the new address, sending nothing then (N7)" in {
      testDbApp { implicit app =>
        createContests(10)
        // two accounts: one change a minute per account (L5)
        val juror = account("juror@example.com", Some(10), "jury", password = Some("pw-juror"), verified = true)
        val other = account("other@example.com", Some(10), "jury", password = Some("pw-other"), verified = true)
        account("taken@example.com", Some(10), "jury")
        val mail = new RecordingMail

        val taken = save(mail, juror, Some(10), own(juror, "email" -> "taken@example.com", "currentPassword" -> "pw-juror"): _*)
        val free = save(mail, other, Some(10), own(other, "email" -> "free@example.com", "currentPassword" -> "pw-other"): _*)

        status(taken) === status(free)
        flash(taken) === flash(free)
        redirectLocation(taken).map(_.replaceAll("[0-9]+", "N")) === redirectLocation(free).map(_.replaceAll("[0-9]+", "N"))
        mail.recipients === Seq("free@example.com")
        reloaded(juror).email === "juror@example.com"
      }
    }

    "not be taken by a confirmation once another account has the address" in {
      testDbApp { implicit app =>
        val juror = account("juror@example.com", None, "no", verified = true)
        val (_, token) = AccountTokenService.create(juror.getId, AccountTokenJdbc.EmailChange, "later@example.com")
        account("later@example.com", None, "no")

        status(confirm(token)) === NOT_FOUND
        reloaded(juror).email === "juror@example.com"
      }
    }
  }

  "one's own wiki account [J-32]" should {
    "be read-only until the Commons login verifies it" in {
      testDbApp { implicit app =>
        createContests(10)
        val juror = account("juror@example.com", Some(10), "jury", verified = true, wikiAccount = Some("Juror"))
        val result = save(new RecordingMail, juror, Some(10), own(juror, "wikiAccount" -> "Someone else"): _*)
        status(result) === BAD_REQUEST
        reloaded(juror).wikiAccount === Some("Juror")
        status(save(new RecordingMail, juror, Some(10), own(juror, "fullname" -> "Renamed"): _*)) === SEE_OTHER
        reloaded(juror).wikiAccount === Some("Juror")
      }
    }
  }

  "another account [S-09]" should {
    "never get its password set, by an admin or root" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val root = account("root@example.com", None, "no", root = true, verified = true)
        val juror = account("juror@example.com", Some(10), "jury", password = Some("pw-juror"))
        val mail = new RecordingMail

        status(save(mail, admin, Some(10), other(juror, 10, "jury", "password" -> "taken-over"): _*)) === BAD_REQUEST
        status(save(mail, root, Some(10), other(juror, 10, "jury", "password" -> "taken-over"): _*)) === BAD_REQUEST
        User.login(juror.email, "pw-juror") must beSome
        User.login(juror.email, "taken-over") must beNone
      }
    }

    "have its email changed by an admin only while it is an invitee without a password, which gets a new invitation" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val juror = account("juror@example.com", Some(10), "jury", password = Some("pw-juror"))
        val mail = new RecordingMail

        val refused = save(mail, admin, Some(10), other(juror, 10, "jury", "email" -> "admins@example.com"): _*)
        status(refused) === BAD_REQUEST
        contentAsString(refused) must contain("can only be changed by its owner")
        reloaded(juror).email === "juror@example.com"

        // the add row with a typo; the invitation to it is replaced by one to the fixed address
        status(save(mail, admin, Some(10), "id" -> "0", "fullname" -> "Typo", "email" -> "tpyo@example.com",
          "roles" -> "jury", "contest" -> "10")) === SEE_OTHER
        val typoToken = mail.lastToken
        val invitee = User.findByEmail("tpyo@example.com").head
        val fixed = save(mail, admin, Some(10), other(invitee.copy(contestId = Some(10)), 10, "jury",
          "email" -> "typo@example.com"): _*)
        status(fixed) === SEE_OTHER
        reloaded(invitee).email === "typo@example.com"
        reloaded(invitee).emailVerified must beFalse
        mail.recipients.last === "typo@example.com"
        AccountTokenService.check(typoToken, AccountTokenJdbc.SetsPassword) must beLeft
        AccountTokenService.check(mail.lastToken, AccountTokenJdbc.SetsPassword) must beRight
      }
    }

    "have its email changed by root through a link mailed to the new address" in {
      testDbApp { implicit app =>
        createContests(10)
        val root = account("root@example.com", None, "no", root = true, verified = true)
        val juror = account("juror@example.com", Some(10), "jury", password = Some("pw-juror"), verified = true)
        val mail = new RecordingMail

        status(save(mail, root, None, other(juror, 10, "jury", "email" -> "moved@example.com"): _*)) === SEE_OTHER
        reloaded(juror).email === "juror@example.com"
        mail.recipients === Seq("moved@example.com")
        status(confirm(mail.lastToken)) === OK
        reloaded(juror).email === "moved@example.com"
      }
    }

    "have its wiki account set by an admin only valid, normalised, not another account's, and unverified (N4)" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val juror = account("juror@example.com", Some(10), "jury", verified = true)
        account("other@example.com", Some(10), "jury", wikiAccount = Some("Taken name"))
        val mail = new RecordingMail

        Seq("Bad#name", "a[b]", "x|y", "Name/sub", "a@b", "a:b", "x" * 86).foreach { name =>
          status(save(mail, admin, Some(10), other(juror, 10, "jury", "wikiAccount" -> name): _*)) === BAD_REQUEST
        }
        status(save(mail, admin, Some(10), other(juror, 10, "jury", "wikiAccount" -> "taken_name"): _*)) === BAD_REQUEST
        reloaded(juror).wikiAccount must beNone

        status(save(mail, admin, Some(10), other(juror, 10, "jury", "wikiAccount" -> "User:new_juror  name"): _*)) ===
          SEE_OTHER
        reloaded(juror).wikiAccount === Some("New juror name")
        reloaded(juror).wikiAccountVerified must beFalse
        User.normalizeWikiAccount("ärger & co?") === Right(Some("Ärger & co?"))
        User.normalizeWikiAccount("  ") === Right(None)
      }
    }
  }

  "the users page" should {
    "URL-encode a wiki account in its Commons links, and show unverified emails" in {
      testDbApp { implicit app =>
        val Seq(contest) = createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        // stored before names were validated
        val juror = account("juror@example.com", Some(10), "jury", wikiAccount = Some("A&preload=x?y#z"))
        implicit val request: play.api.mvc.RequestHeader = FakeRequest(GET, "/admin/users").withCSRFToken
        implicit val flash: Flash = Flash()
        implicit val messages: play.api.i18n.Messages = messagesOf
        val html = views.html.users(admin, Seq(juror.copy(hasWikiEmail = true)),
          userController(new RecordingMail).editUserForm, Some(contest)).body

        html must contain("User:A%26preload%3Dx%3Fy%23z")
        html must contain("Special:EmailUser/A%26preload%3Dx%3Fy%23z?")
        html must not(contain("User_talk:A&preload"))
        html must contain("email-unverified")
        html must contain(routes.UserController.reinvite(juror.getId, 10).url)
      }
    }
  }

  "sessions and sign-in" should {
    "refuse a session of another auth version" in {
      testDbApp { implicit app =>
        createContests(10)
        val juror = account("juror@example.com", Some(10), "jury", verified = true)
        Secured.sessionUser(juror.email, None, None, None) must beSome
        DB.autoCommit(implicit s => sql"UPDATE users SET auth_version = 2 WHERE id = ${juror.getId}".update.apply())
        Secured.sessionUser(juror.email, None, None, None) must beNone
        Secured.sessionUser(juror.email, None, Some("1"), None) must beNone
        Secured.sessionUser(juror.email, None, Some("garbage"), None) must beNone
        Secured.sessionUser(juror.email, None, Some("2"), None) must beSome
        status(route(app, FakeRequest(GET, "/").withSession(Secured.UserName -> juror.email)).get) === SEE_OTHER
      }
    }

    "ignore deleted accounts, trim the email, and refuse an ambiguous wiki account (P14, P18, N4)" in {
      testDbApp { implicit app =>
        val gone = account("gone@example.com", None, "no", password = Some("pw-gone"), verified = true, wikiAccount = Some("Gone"))
        DB.autoCommit(implicit s => sql"UPDATE users SET deleted_at = NOW() WHERE id = ${gone.getId}".update.apply())
        User.login("gone@example.com", "pw-gone") must beNone
        User.login("Gone", "pw-gone") must beNone

        val kept = account("kept@example.com", None, "no", password = Some("pw-kept"), verified = true, wikiAccount = Some("Twin"))
        User.login("  Kept@Example.com ", "pw-kept").flatMap(_.id) === kept.id
        User.login("Twin", "pw-kept").flatMap(_.id) === kept.id
        account("twin@example.com", None, "no", wikiAccount = Some("Twin"))
        User.login("Twin", "pw-kept") must beNone
        User.byUserName("twin") must beNone
      }
    }
  }
}
