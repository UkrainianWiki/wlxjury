package controllers

import controllers.CsrfTestApp.{token => csrfToken, withCsrfApp}
import db.scalikejdbc.{AccountTokenJdbc, ApiTokenJdbc, ContestJuryJdbc, PlayTestDb, User, UserContest, UserContestJdbc}
import org.apache.pekko.stream.Materializer
import play.api.{Application, Configuration}
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test._
import scalikejdbc._
import services.{AccountTokenService, ApiTokenService, LinkHost, UserService}

import scala.concurrent.Future

/** The fixes of S2's independent review (H1, H2, M1, M2, L1-L5, nits) and the user's later
  * decisions (old address notified; links on the request's host when it is a configured one).
  */
class AccountReviewFixesSpec extends PlaySpecification with PlayTestDb with AccountTestSupport {

  sequential

  private def as[A](user: User, contestId: Option[Long] = None)(request: FakeRequest[A]): FakeRequest[A] =
    request.withSession(Seq(Secured.UserName -> user.email) ++ contestId.map(Secured.CurrentContestId -> _.toString): _*)

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

  private def accept(token: String, password: String)(implicit app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(loginController(new RecordingMail).acceptInvitation(),
      FakeRequest(POST, "/invitation")
        .withFormUrlEncodedBody("token" -> token, "password" -> password, "repeat.password" -> password).withCSRFToken)
  }

  private def roles(userId: Long): Seq[UserContest] = UserContestJdbc.findByUser(userId)

  "H1: an add by wiki account only" should {
    "not join a verified-email account, nor a passwordless one, with an access role" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val verified = account("verified@example.com", None, "no", verified = true, wikiAccount = Some("Wiki one"))
        val passwordless = account("placeholder@example.com", None, "no", password = None, wikiAccount = Some("Wiki two"))
        val mail = new RecordingMail

        val result = importUsers(mail, admin, 10, "User:Wiki one\nUser:Wiki_two")
        status(result) === SEE_OTHER
        flash(result).get("users-added") must beSome(contain("Not added, as the wiki account belongs to an account"))
        roles(verified.getId) must beEmpty
        roles(passwordless.getId) must beEmpty
        mail.sent must beEmpty
      }
    }
  }

  "H2: a session" should {
    "from before S2 not resolve to an account created later for the same address" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val old = account("victim@example.com", None, "no", password = Some("claimer"))
        DB.autoCommit(implicit s => sql"DELETE FROM users WHERE id = ${old.getId}".update.apply())

        status(add(new RecordingMail, admin, 10, "victim@example.com")) === SEE_OTHER
        val created = User.findByEmail("victim@example.com").head
        created.authVersion !== 0
        // the claimer's cookie: a name only, no version, no account id
        Secured.sessionUser("victim@example.com", None, None, None) must beNone
        status(route(app, FakeRequest(GET, "/").withSession(Secured.UserName -> "victim@example.com")).get) === SEE_OTHER
      }
    }

    "signed in to one account not act as another that later has its address" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        val a = account("a@example.com", None, "no", password = Some("pw-a"), verified = true, root = true)
        val signedIn = call(loginController(new RecordingMail).auth(),
          FakeRequest(POST, "/auth").withFormUrlEncodedBody("login" -> "a@example.com", "password" -> "pw-a").withCSRFToken)
        val s = session(signedIn)
        s.get(Secured.AccountId) === a.id.map(_.toString)
        User.setEmail(a.getId, "a2@example.com", verified = true)
        val b = account("a@example.com", None, "no", root = true)
        DB.autoCommit(implicit sess =>
          sql"UPDATE users SET auth_version = ${s.get(Secured.AuthVersion).get.toInt} WHERE id = ${b.getId}".update.apply())
        Secured.sessionUser("a@example.com", None, s.get(Secured.AuthVersion), s.get(Secured.AccountId)) must beNone
        Secured.sessionUser("a@example.com", None, s.get(Secured.AuthVersion), None) must beSome // the id makes the difference
      }
    }

    "end when an admin changes the account's wiki account or email" in {
      testDbApp { implicit app =>
        val user = account("user@example.com", None, "no", wikiAccount = Some("Name"))
        User.setWikiAccount(user.getId, Some("Name")) // unchanged: nothing ends
        User.findById(user.getId).get.authVersion === 0
        User.setWikiAccount(user.getId, Some("Other name"))
        User.findById(user.getId).get.authVersion === 1
        User.setEmail(user.getId, "user2@example.com", verified = false)
        User.findById(user.getId).get.authVersion === 2
      }
    }
  }

  "M1: accepting an invitation" should {
    "kill a pending email-change link the former password holder asked for" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val claimed = account("victim@example.com", None, "no", password = Some("claimer"))
        val (_, emailLink) = AccountTokenService.create(claimed.getId, AccountTokenJdbc.EmailChange, "claimer@example.com")
        val mail = new RecordingMail

        status(add(mail, admin, 10, "victim@example.com")) === SEE_OTHER
        status(accept(mail.lastToken, "owner-password")) === SEE_OTHER
        AccountTokenService.check(emailLink, Seq(AccountTokenJdbc.EmailChange)) must beLeft
        AccountTokenService.confirmEmailChange(emailLink) must beLeft
        User.findById(claimed.getId).map(_.email) === Some("victim@example.com")
      }
    }
  }

  "M2: an invitation in the contest's greeting" should {
    "keep the link out of the admin's text, in the app's own paragraph after it" in {
      testDbApp { implicit app =>
        val Seq(contest) = createContests(10)
        ContestJuryJdbc.updateGreeting(10, Greeting(Some("Hi {{RecipientName}}! Password: {{Password}} <a href=\"{{Password}}\">x</a>"), use = true))
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val mail = new RecordingMail

        status(add(mail, admin, 10, "new@example.com")) === SEE_OTHER
        val body = mail.sent.last._4
        val token = mail.lastToken
        body.split(java.util.regex.Pattern.quote(token), -1).length === 2 // once
        val adminText = body.substring(0, body.indexOf("\n\n"))
        adminText must not(contain(token))
        adminText must not(contain("http"))
        adminText must contain("the one you choose with the link below")
      }
    }
  }

  "L1: an API token" should {
    "not be created for a session an accepted link has ended" in {
      testDbApp { implicit app =>
        val user = account("user@example.com", None, "no", verified = true)
        DB.autoCommit(implicit s => sql"UPDATE users SET auth_version = 5 WHERE id = ${user.getId}".update.apply())
        ApiTokenService.createOnce(user, "stale", Some(30), "nonce-1") === ApiTokenService.CreateOutcome.SessionEnded
        ApiTokenService.createOnce(user.copy(authVersion = 5), "fresh", Some(30), "nonce-2") must
          beAnInstanceOf[ApiTokenService.CreateOutcome.Created]
        ApiTokenJdbc.findByUser(user.getId).map(_.name) === Seq("fresh")
      }
    }
  }

  "L2: root's form" should {
    "not make root an account whose holder has not proven its email" in {
      testDbApp { implicit app =>
        createContests(10)
        val root = account("root@example.com", None, "no", root = true, verified = true)
        val unverified = account("member@example.com", Some(10), "jury")
        val fields = Seq("id" -> unverified.getId.toString, "fullname" -> unverified.fullname,
          "email" -> unverified.email, "roles" -> "jury", "root" -> "true", "root" -> "false")
        status(save(new RecordingMail, root, 10, fields: _*)) === BAD_REQUEST
        User.findById(unverified.getId).map(_.isRoot) === Some(false)
      }
    }

    "refuse an empty email for an account with a password, sending nothing" in {
      testDbApp { implicit app =>
        createContests(10)
        val root = account("root@example.com", None, "no", root = true, verified = true)
        val member = account("member@example.com", Some(10), "jury", verified = true)
        val mail = new RecordingMail
        status(save(mail, root, 10, "id" -> member.getId.toString, "fullname" -> member.fullname, "email" -> "",
          "wikiAccount" -> "Member", "roles" -> "jury")) === BAD_REQUEST
        mail.sent must beEmpty
        AccountTokenJdbc.findByUser(member.getId) must beEmpty
      }
    }
  }

  "L3: log lines" should {
    "mask the token of a link in a path" in {
      Secured.logPath("/invitation/abcDEF_123-xyz") === "\"/invitation/***\""
      Secured.logPath("/email/confirm/abc") === "\"/email/confirm/***\""
      Secured.logPath("/users/save") === "\"/users/save\""
    }
  }

  "L4: a pending invitation" should {
    "be cancelled by an admin of its contest only (CSRF, through the router), and give no role once cancelled" in {
      withCsrfApp { app =>
        implicit val a: Application = app
        createContests(10, 20)
        val admin10 = account("admin10@example.com", Some(10), "admin", verified = true)
        val admin20 = account("admin20@example.com", Some(20), "admin", verified = true)
        val invitee = account("invitee@example.com", None, "no", password = Some("pw"))
        val (_, token) = AccountTokenService.create(invitee.getId, AccountTokenJdbc.Invitation, invitee.email,
          Some(10L), Some("jury"), admin10.id)
        val sessionToken = csrfToken(app)
        def cancel(as: User, contestId: Long, withToken: Boolean) =
          route(app, FakeRequest(POST, s"/users/${invitee.getId}/invitation/cancel?contestId=10")
            .withHeaders(COOKIE -> "PLAY_SESSION=x", ACCEPT -> "text/html")
            .withSession(Secured.UserName -> as.email, Secured.CurrentContestId -> contestId.toString, "csrfToken" -> sessionToken)
            .withFormUrlEncodedBody((if (withToken) Seq("csrfToken" -> sessionToken) else Nil): _*)).get

        status(cancel(admin10, 10, withToken = false)) === FORBIDDEN
        status(cancel(admin20, 20, withToken = true)) === FORBIDDEN
        AccountTokenService.check(token, AccountTokenJdbc.SetsPassword) must beRight
        status(cancel(admin10, 10, withToken = true)) === SEE_OTHER
        AccountTokenService.check(token, AccountTokenJdbc.SetsPassword) must beLeft
      }
    }

    "give no role if whoever sent it is no longer an admin of the contest" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val invitee = account("invitee@example.com", None, "no", password = Some("pw"))
        val mail = new RecordingMail
        status(add(mail, admin, 10, "invitee@example.com", "organizer")) === SEE_OTHER
        UserContestJdbc.updateRole(admin.getId, 10, "jury")

        status(accept(mail.lastToken, "new-password")) === SEE_OTHER
        roles(invitee.getId) must beEmpty
        User.login("invitee@example.com", "new-password") must beSome
      }
    }
  }

  "L5: email-change requests" should {
    "send at most one link a minute per account" in {
      testDbApp { implicit app =>
        val user = account("user@example.com", None, "no", verified = true)
        val mail = new RecordingMail
        val service = userService(mail)
        val messages = messagesOf
        service.requestEmailChange(user, user, "one@example.com")(messages)
        service.requestEmailChange(user, user, "two@example.com")(messages)
        mail.recipients === Seq("one@example.com")
        AccountTokenJdbc.findByUser(user.getId).count(_.live) === 1
      }
    }
  }

  "an email change" should {
    "be told to the former address, without a link or the full new address" in {
      testDbApp { implicit app =>
        val user = account("user@example.com", None, "no", verified = true)
        val (_, token) = AccountTokenService.create(user.getId, AccountTokenJdbc.EmailChange, "newaddress@example.org")
        val mail = new RecordingMail
        implicit val mat: Materializer = app.materializer
        status(call(loginController(mail).emailConfirm(),
          FakeRequest(POST, "/email/confirm").withFormUrlEncodedBody("token" -> token).withCSRFToken)) === OK

        mail.recipients === Seq("user@example.com")
        // from the tool's sender, never the account (whose From would be the new address)
        mail.sent.last._1.email === "no-reply@jury.wle.org.ua"
        mail.sent.last._1.email must not(contain("newaddress"))
        val body = mail.sent.last._4
        body must contain("n***@example.org")
        body must not(contain("newaddress"))
        body must not(contain("http"))
      }
    }
  }

  "mailed links" should {
    "point at the request's host only if it is a configured one, else at wlxjury.host" in {
      testDbApp { implicit app =>
        val user = User.create(User("Invitee", "invitee@example.com"))
        val mail = new RecordingMail
        val config = Configuration("wlxjury.allowedHosts" -> "jury.wlm.org.ua, jury.wle.org.ua, .example.org")
          .withFallback(app.configuration)
        val service = userServiceWith(mail, config)
        val messages = messagesOf
        def link(host: LinkHost) = {
          service.sendPasswordLink(user, user)(messages, host)
          RecordingMail.Link.findFirstMatchIn(mail.sent.last._4).get.matched
        }
        link(LinkHost(Some("jury.wlm.org.ua"))) must startWith("https://jury.wlm.org.ua/invitation/")
        link(LinkHost(Some("JURY.WLM.ORG.UA"))) must startWith("https://jury.wlm.org.ua/invitation/")
        link(LinkHost(Some("evil.example"))) must startWith("https://jury.wle.org.ua/invitation/")
        link(LinkHost(Some("x.example.org"))) must startWith("https://jury.wle.org.ua/invitation/") // a suffix pattern
        link(LinkHost(Some("jury.wlm.org.ua:8443"))) must startWith("https://jury.wle.org.ua/invitation/")
        // without a request (a background job): the configured host
        service.sendPasswordLink(user, user)(messages)
        RecordingMail.Link.findFirstMatchIn(mail.sent.last._4).get.matched must startWith("https://jury.wle.org.ua/")
      }
    }

    "use the request's Host through the controller, and refuse a forged one" in {
      implicit val config: Map[String, String] = Map("wlxjury.allowedHosts" -> "jury.wlm.org.ua")
      testDbApp { implicit app =>
        SharedTestDbAccess.truncate()
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val mail = new RecordingMail
        implicit val mat: Materializer = app.materializer
        def addVia(host: String, email: String) =
          status(call(userController(mail).saveUser(),
            as(admin, Some(10))(FakeRequest(POST, "/users/save").withHeaders(HOST -> host))
              .withFormUrlEncodedBody("contest" -> "10", "id" -> "0", "fullname" -> "P", "email" -> email, "roles" -> "jury")
              .withCSRFToken))
        addVia("jury.wlm.org.ua", "wlm@example.com") === SEE_OTHER
        RecordingMail.Link.findFirstMatchIn(mail.sent.last._4).get.matched must startWith("https://jury.wlm.org.ua/")
        addVia("attacker.example", "forged@example.com") === SEE_OTHER
        RecordingMail.Link.findFirstMatchIn(mail.sent.last._4).get.matched must startWith("https://jury.wle.org.ua/")
      }
    }
  }

  "the follow-up review" should {
    "resolve a session by its exact wiki name: renaming a near-duplicate never moves an old cookie to another account" in {
      testDbApp { implicit app =>
        val a = account("a@example.com", None, "no", verified = true, wikiAccount = Some("Foo_bar"))
        val b = account("b@example.com", None, "no", verified = true, wikiAccount = Some("Foo bar"))
        Secured.sessionUser("Foo_bar", None, None, None).flatMap(_.id) === a.id
        User.setWikiAccount(a.getId, Some("Renamed"))
        Secured.sessionUser("Foo_bar", None, None, None) must beNone
        Secured.sessionUser("Foo bar", None, None, None).flatMap(_.id) === b.id
      }
    }

    "give the first root a non-zero auth version, and keep versions within an Int" in {
      testDbApp { implicit app =>
        User.createFirstRoot("first@example.com", hash("root-password")).map(_.authVersion) must beSome(be_>(0))
        (1 to 1000).map(_ => User.newAuthVersion()).forall(v => v > 0 && v <= (1 << 30)) must beTrue
        val user = account("user@example.com", None, "no")
        DB.autoCommit(implicit s => sql"UPDATE users SET auth_version = 2147483646 WHERE id = ${user.getId}".update.apply())
        User.setEmail(user.getId, "moved@example.com", verified = false)
        User.findById(user.getId).map(_.authVersion) === Some(1)
      }
    }

    "let an admin send the invitation again to a verified member without a password" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val member = account("member@example.com", Some(10), "jury", password = None, verified = true)
        val mail = new RecordingMail
        status(call(userController(mail).reinvite(member.getId, 10),
          as(admin, Some(10))(FakeRequest(POST, s"/users/${member.getId}/invite?contestId=10")).withCSRFToken)) === SEE_OTHER
        mail.recipients === Seq("member@example.com")
      }
    }
  }

  "nits" should {
    "sign in by a wiki account typed with underscores, as names are stored" in {
      testDbApp { implicit app =>
        val user = account("user@example.com", None, "no", password = Some("pw-user"), verified = true,
          wikiAccount = Some("Some name"))
        User.login("Some_name", "pw-user").flatMap(_.id) === user.id
      }
    }

    "invite a passwordless account joined by email instead of greeting it with its existing password" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val placeholder = account("placeholder@example.com", None, "no", password = None)
        val mail = new RecordingMail
        status(add(mail, admin, 10, "placeholder@example.com")) === SEE_OTHER
        roles(placeholder.getId) === Seq(UserContest(placeholder.getId, 10, "jury"))
        mail.recipients === Seq("placeholder@example.com")
        mail.sent.last._4 must contain("/invitation/")
      }
    }

    "list only pending invitations sent to the account's current address" in {
      testDbApp { implicit app =>
        createContests(10)
        val invitee = account("invitee@example.com", None, "no", password = Some("pw"))
        AccountTokenService.create(invitee.getId, AccountTokenJdbc.Invitation, invitee.email, Some(10L), Some("jury"))
        AccountTokenJdbc.pendingInvitations(10).size === 1
        User.setEmail(invitee.getId, "moved@example.com", verified = false)
        AccountTokenJdbc.pendingInvitations(10) must beEmpty
      }
    }
  }
}

/** The shared test database, truncated: a per-test app's (custom config) is not. */
object SharedTestDbAccess {
  def truncate(): Unit = db.scalikejdbc.SharedTestDb.truncateAll()
}
