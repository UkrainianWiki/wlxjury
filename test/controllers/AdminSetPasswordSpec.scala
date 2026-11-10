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

/** "Set password" on another account's edit page (the maintainer, 2026-11-10: "while we test and
  * learn new ways of login and reset password, let's keep good old user password edited by an
  * admin/root"): allowed exactly where a password link to share would be (root for anyone, an
  * admin for an active juror or organizer who is theirs alone), and with what using that link
  * does: the email and wiki account unverified, every session ended, API tokens revoked,
  * identities unlinked, unused links revoked.
  */
class AdminSetPasswordSpec extends PlaySpecification with PlayTestDb with AccountTestSupport {

  sequential

  private def as[A](user: User, contestId: Option[Long])(request: FakeRequest[A]): FakeRequest[A] =
    request.withSession(Seq(Secured.UserName -> user.email) ++ contestId.map(Secured.CurrentContestId -> _.toString): _*)

  private def save(by: User, contestId: Option[Long], fields: (String, String)*)(implicit
      app: Application): Future[Result] = saveWith(userController(new RecordingMail), by, contestId, fields: _*)

  private def saveWith(controller: UserController, by: User, contestId: Option[Long], fields: (String, String)*)(implicit
      app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(controller.saveUser(), as(by, contestId)(FakeRequest(POST, "/users/save")).withFormUrlEncodedBody(fields: _*)
      .withCSRFToken)
  }

  /** The edit form of `user` as the page fills it, in `contestId` with `role`, and `changes`. */
  private def form(user: User, contestId: Option[Long], role: String, changes: (String, String)*): Seq[(String, String)] =
    (Map("id" -> user.getId.toString, "fullname" -> user.fullname, "email" -> user.email,
      "wikiAccount" -> user.wikiAccount.getOrElse(""), "roles" -> role) ++
      contestId.map(c => "contest" -> c.toString) ++ changes).toSeq

  private def editPage(by: User, id: Long, contestId: Option[Long])(implicit app: Application): String = {
    implicit val mat: Materializer = app.materializer
    contentAsString(call(userController(new RecordingMail).editUser(id, contestId),
      as(by, contestId)(FakeRequest(GET, s"/users/edit/$id")).withCSRFToken))
  }

  private def reloaded(user: User): User = User.findById(user.getId).get

  /** `f`'s result and the denials ([[Secured.logDenial]]) logged on security.authz meanwhile. */
  private def authzLog[T](f: => T): (T, Seq[String]) = {
    val logger = org.slf4j.LoggerFactory.getLogger("security.authz").asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    appender.start()
    logger.addAppender(appender)
    try {
      val result = f
      import scala.jdk.CollectionConverters._
      (result, appender.list.asScala.toSeq.filter(_.getLevel == ch.qos.logback.classic.Level.WARN)
        .map(_.getFormattedMessage))
    } finally logger.detachAppender(appender)
  }

  private def roles(userId: Long): Seq[UserContest] = UserContestJdbc.findByUser(userId)

  "an admin or root setting another account's password [S-09]" should {
    "set it for an admin's juror: they sign in with it, their sessions end, the email is unverified, logged by id" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val juror = account("juror@example.com", Some(10), "jury", password = Some("old-password"), verified = true,
          wikiAccount = Some("Juror wiki"))
        DB.autoCommit(implicit s =>
          sql"UPDATE users SET wiki_account_verified_at = NOW(3) WHERE id = ${juror.getId}".update.apply())
        val apiToken = ApiTokenService.create(juror.getId, "tool", Some(30))
        UserIdentityJdbc.create(UserIdentity("google", "juror-subject", juror.getId))
        AccountTokenService.create(juror.getId, AccountTokenJdbc.PasswordReset, juror.email)
        val oldVersion = Some(reloaded(juror).authVersion.toString)
        Secured.sessionUser(juror.email, Some("10"), oldVersion, juror.id.map(_.toString)) must beSome

        // the field, with its help, in the form that has the CSRF token
        val page = editPage(admin, juror.getId, Some(10))
        page must contain("id=\"set-password\"")
        page must contain("autocomplete=\"new-password\"")
        page must contain("Give it to them in person.")
        page must contain("csrfToken")

        val (result, lines) = accountLog {
          val r = save(admin, Some(10), form(juror, Some(10), "jury", "password" -> "given-password"): _*)
          status(r)
          r
        }
        status(result) === SEE_OTHER
        redirectLocation(result) === Some(routes.UserController.users(Some(10)).url)
        flash(result).get("users-added") must beSome(contain("Password set."))
        User.login(juror.email, "given-password").flatMap(_.id) === juror.id
        User.login(juror.email, "old-password") must beNone
        Secured.sessionUser(juror.email, Some("10"), oldVersion, juror.id.map(_.toString)) must beNone
        ApiTokenService.authenticate(apiToken) must beNone
        UserIdentityJdbc.findByUser(juror.getId) must beEmpty
        AccountTokenJdbc.findByUser(juror.getId).filter(_.live) must beEmpty
        val after = reloaded(juror)
        after.emailVerified must beFalse
        after.wikiAccountVerified must beFalse
        after.wikiAccount === Some("Juror wiki")
        roles(juror.getId) === Seq(UserContest(juror.getId, 10, "jury"))
        lines must contain(beMatching(s"password_set_by_admin accountId=${juror.getId} byAccountId=${admin.getId} " +
          "contestId=10 emailProven=false sessionsEnded=true apiTokensRevoked=1 identitiesUnlinked=1 linksRevoked=1 " +
          "memberships="))
        // ids only: never an email, the password or its hash
        lines.filter(l => l.contains("@") || l.contains("given-password") || l.contains("$2")) must beEmpty
      }
    }

    "let root set anyone's: an admin's, another root's" in {
      testDbApp { implicit app =>
        createContests(10)
        val root = account("root@example.com", None, "no", root = true, verified = true)
        val otherRoot = account("root2@example.com", None, "no", root = true, verified = true)
        val admin = account("admin@example.com", Some(10), "admin", password = Some("old-admin"), verified = true)
        editPage(root, otherRoot.getId, None) must contain("id=\"set-password\"")

        val (results, lines) = accountLog {
          val a = save(root, Some(10), form(admin, Some(10), "admin", "password" -> "admin-password"): _*)
          val r = save(root, None, form(otherRoot, None, "root", "password" -> "root-password"): _*)
          (status(a), status(r))
        }
        results === ((SEE_OTHER, SEE_OTHER))
        User.login(admin.email, "admin-password").flatMap(_.id) === admin.id
        User.login(otherRoot.email, "root-password").flatMap(_.id) === otherRoot.id
        reloaded(otherRoot).isRoot must beTrue
        reloaded(otherRoot).emailVerified must beFalse
        roles(admin.getId) === Seq(UserContest(admin.getId, 10, "admin"))
        lines must contain(beMatching(s"password_set_by_admin accountId=${admin.getId} byAccountId=${root.getId} contestId=10 .*"))
        lines must contain(beMatching(s"password_set_by_admin accountId=${otherRoot.getId} byAccountId=${root.getId} emailProven=false .*"))
      }
    }

    "refuse an admin for root, another admin, a member of another contest, someone outside their contest, a pending invitee or themselves" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin10 = account("admin10@example.com", Some(10), "admin", verified = true)
        val coAdmin = account("coadmin@example.com", Some(10), "admin", password = Some("pw-coadmin"), verified = true)
        val admin20 = account("admin20@example.com", Some(20), "admin", verified = true)
        val root = account("root@example.com", None, "no", password = Some("pw-root"), root = true, verified = true)
        UserContestJdbc.createMembership(root.getId, 10, "jury")
        val both = account("both@example.com", Some(10), "jury", password = Some("pw-both"), verified = true)
        UserContestJdbc.createMembership(both.getId, 20, "jury")
        val outsider = account("outsider@example.com", Some(20), "jury", password = Some("pw-outsider"), verified = true)
        val juror10 = account("juror10@example.com", Some(10), "jury", password = Some("pw-juror10"), verified = true)
        val pending = account("pending@example.com", None, "no", password = Some("pw-pending"))
        val mail = new RecordingMail
        implicit val mat: Materializer = app.materializer
        status(call(userController(mail).saveUser(), as(admin10, Some(10))(FakeRequest(POST, "/users/save"))
          .withFormUrlEncodedBody("contest" -> "10", "id" -> "0", "fullname" -> "Pending", "email" -> pending.email,
            "roles" -> "jury").withCSRFToken)) === SEE_OTHER // invited, the role pending (O2)
        roles(pending.getId) must beEmpty

        val targets = Seq(coAdmin -> "pw-coadmin", root -> "pw-root", both -> "pw-both", outsider -> "pw-outsider",
          juror10 -> "pw-juror10", pending -> "pw-pending")
        val versions = targets.map { case (u, _) => u.getId -> reloaded(u).authVersion }.toMap
        def set(by: User, contestId: Long, target: User, role: String) =
          status(save(by, Some(contestId), form(target, Some(contestId), role, "password" -> "taken-over"): _*))

        // 403 through the denial page: the shared link's rule
        set(admin10, 10, coAdmin, "admin") === FORBIDDEN
        set(admin10, 10, root, "jury") === FORBIDDEN
        set(admin10, 10, both, "jury") === FORBIDDEN
        // not in the admin's contest, or only invited to it: not an account they can edit
        set(admin10, 10, outsider, "jury") === SEE_OTHER
        set(admin20, 20, juror10, "jury") === SEE_OTHER
        set(admin10, 10, pending, "jury") === SEE_OTHER
        // themselves: their own profile's rule, the current password
        val own = save(admin10, Some(10), form(admin10, Some(10), "admin", "password" -> "taken-over"): _*)
        status(own) === BAD_REQUEST
        contentAsString(own) must contain("The current password is not correct.")

        targets.map { case (u, password) => User.login(u.email, password).flatMap(_.id) } === targets.map(_._1.id)
        targets.map { case (u, _) => reloaded(u).authVersion } === targets.map { case (u, _) => versions(u.getId) }
        User.login(admin10.email, "taken-over") must beNone
        // no field where it would be refused
        editPage(admin10, coAdmin.getId, Some(10)) must not(contain("id=\"set-password\""))
        editPage(admin10, both.getId, Some(10)) must not(contain("id=\"set-password\""))
        editPage(admin10, root.getId, Some(10)) must not(contain("id=\"set-password\""))
      }
    }

    "refuse an organizer or a juror posting another's password" in {
      testDbApp { implicit app =>
        createContests(10)
        val organizer = account("organizer@example.com", Some(10), "organizer", password = Some("pw-organizer"), verified = true)
        val juror = account("juror@example.com", Some(10), "jury", password = Some("pw-juror"), verified = true)

        status(save(organizer, Some(10), form(juror, Some(10), "jury", "password" -> "taken-over"): _*)) === SEE_OTHER
        status(save(juror, Some(10), form(organizer, Some(10), "organizer", "password" -> "taken-over"): _*)) === SEE_OTHER
        User.login(juror.email, "pw-juror").flatMap(_.id) === juror.id
        User.login(organizer.email, "pw-organizer").flatMap(_.id) === organizer.id
        User.login(juror.email, "taken-over") must beNone
        User.login(organizer.email, "taken-over") must beNone
      }
    }

    "change nothing for a blank password, and refuse one over 72 bytes or with an email change" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val juror = account("juror@example.com", Some(10), "jury", password = Some("old-password"), verified = true)
        val version = reloaded(juror).authVersion

        status(save(admin, Some(10), form(juror, Some(10), "jury", "password" -> "", "fullname" -> "Renamed"): _*)) ===
          SEE_OTHER
        reloaded(juror).fullname === "Renamed"
        reloaded(juror).authVersion === version
        reloaded(juror).emailVerified must beTrue

        val tooLong = save(admin, Some(10), form(juror, Some(10), "jury", "password" -> "x" * 73): _*)
        status(tooLong) === BAD_REQUEST
        contentAsString(tooLong) must contain("The password is too long")
        contentAsString(tooLong) must contain("id=\"set-password\"") // the field again, with its error

        val withEmail = save(admin, Some(10),
          form(juror, Some(10), "jury", "password" -> "new-password", "email" -> "other@example.com"): _*)
        status(withEmail) === BAD_REQUEST
        contentAsString(withEmail) must contain("in separate saves")

        User.login(juror.email, "old-password").flatMap(_.id) === juror.id
        reloaded(juror).email === juror.email
        reloaded(juror).authVersion === version
        reloaded(juror).emailVerified must beTrue
      }
    }

    "refuse root too for an account without an email where Wikimedia sign-in is offered, and say why" in {
      testDbApp { implicit app =>
        createContests(10)
        val root = account("root@example.com", None, "no", root = true, verified = true)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val wiki = User.create(User("Wiki juror", "", None, Set("jury"), None, Some(10), wikiAccount = Some("Wiki juror")))
        // Wikimedia sign-in offered: an account without an email signs in with it, no password
        val settings = new OAuthSettings(Configuration.from(Map(
          "oauth.wikimedia.clients" -> Seq(Map("host" -> "jury.example", "clientId" -> "wm", "clientSecret" -> "secret")))))
        val controller = new UserController(app.injector.instanceOf[ControllerComponents], userService(new RecordingMail),
          app.injector.instanceOf[modules.BlockingExecutionContext], ThemeCookies.default, settings)

        for (by <- Seq(root, admin)) {
          val refused = saveWith(controller, by, Some(10), form(wiki, Some(10), "jury", "password" -> "wiki-password"): _*)
          status(refused) === BAD_REQUEST
          contentAsString(refused) must contain("No password can be set for this account")
          contentAsString(refused) must not(contain("id=\"set-password\""))
        }
        reloaded(wiki).password must beNone
      }
    }

    "still refuse with password.others.not.allowed a deleted account, root's post included" in {
      testDbApp { implicit app =>
        createContests(10)
        val root = account("root@example.com", None, "no", root = true, verified = true)
        val juror = account("juror@example.com", Some(10), "jury", password = Some("old-password"), verified = true)
        DB.autoCommit(implicit s => sql"UPDATE users SET deleted_at = NOW(3) WHERE id = ${juror.getId}".update.apply())
        val before = reloaded(juror)

        val refused = save(root, None, form(juror, None, "no", "password" -> "taken-over"): _*)
        status(refused) === BAD_REQUEST
        contentAsString(refused) must contain("This account&#x27;s password can&#x27;t be set")
        reloaded(juror).password === before.password
        reloaded(juror).authVersion === before.authVersion
        reloaded(juror).emailVerified must beTrue
      }
    }

    "refuse a role change in the same save, so that nobody becomes an admin or organizer with a password the setter chose" in {
      testDbApp { implicit app =>
        createContests(10)
        val root = account("root@example.com", None, "no", root = true, verified = true)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val juror = account("juror@example.com", Some(10), "jury", password = Some("old-password"), verified = true)
        val version = reloaded(juror).authVersion

        for ((by, role) <- Seq(admin -> "admin", admin -> "organizer", root -> "admin", root -> "root")) {
          val refused = save(by, Some(10), form(juror, Some(10), role, "password" -> "chosen-password"): _*)
          status(refused) === BAD_REQUEST
          contentAsString(refused) must contain("Change the role and set the password in separate saves.")
        }
        roles(juror.getId) === Seq(UserContest(juror.getId, 10, "jury"))
        reloaded(juror).isRoot must beFalse
        User.login(juror.email, "old-password").flatMap(_.id) === juror.id
        User.login(juror.email, "chosen-password") must beNone
        reloaded(juror).authVersion === version
        reloaded(juror).emailVerified must beTrue
      }
    }

    "let root set the password of an account in no contest, the role list's first option posted with it" in {
      testDbApp { implicit app =>
        val root = account("root@example.com", None, "no", root = true, verified = true)
        val loner = account("loner@example.com", None, "no", password = Some("old-password"), verified = true)
        UserContestJdbc.findByUser(loner.getId) must beEmpty

        val result = save(root, None, form(loner, None, User.FORM_ROLES.head, "password" -> "loner-password"): _*)
        status(result) === SEE_OTHER
        User.login(loner.email, "loner-password").flatMap(_.id) === loner.id
        UserContestJdbc.findByUser(loner.getId) must beEmpty
        reloaded(loner).isRoot must beFalse
      }
    }

    "refuse under the account's lock once the account joined a contest the admin doesn't run" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val juror = account("juror@example.com", Some(10), "jury", password = Some("old-password"), verified = true)
        val actor = User.findInContest(admin.getId, 10).get
        // the controller's check passed on the page; the account joins contest 20 before the save
        UserContestJdbc.createMembership(juror.getId, 20, "jury")
        val (result, lines) = accountLog {
          AccountTokenService.setPasswordByAdmin(actor, juror.getId, Some(10), hash("late-password"),
            wikimediaSignIn = false)
        }
        result must beLeft(services.ShareLinkService.OtherContest: services.ShareLinkService.Refusal)
        lines must contain(s"password_set_by_admin_refused reason=share_link_other_contest accountId=${juror.getId} " +
          s"byAccountId=${admin.getId}")
        User.login(juror.email, "old-password").flatMap(_.id) === juror.id
        reloaded(juror).emailVerified must beTrue
      }
    }

    "log the 403 on security.authz, and refuse an inactive member" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val coAdmin = account("coadmin@example.com", Some(10), "admin", password = Some("pw-coadmin"), verified = true)
        val inactive = account("inactive@example.com", Some(10), "no", password = Some("pw-inactive"), verified = true)

        val (statuses, lines) = authzLog {
          Seq(status(save(admin, Some(10), form(coAdmin, Some(10), "admin", "password" -> "taken-over"): _*)),
            status(save(admin, Some(10), form(inactive, Some(10), "no", "password" -> "taken-over"): _*)))
        }
        statuses === Seq(FORBIDDEN, FORBIDDEN)
        lines must contain(beMatching(s"authorization_denied ref=\\w{8} accountId=${admin.getId} method=POST " +
          s"path=\"/users/save\" .*targetContestId=10 resource=\"account:${coAdmin.getId}\" .*" +
          "reason=share_link_target_role.*"))
        lines must contain(beMatching(s"authorization_denied .*resource=\"account:${inactive.getId}\" .*" +
          "reason=share_link_reset_not_active_member.*"))
        User.login(coAdmin.email, "pw-coadmin").flatMap(_.id) === coAdmin.id
        User.login(inactive.email, "pw-inactive").flatMap(_.id) === inactive.id
      }
    }

    "need the CSRF token, through the router" in {
      withCsrfApp { app =>
        implicit val a: Application = app
        val root = account("root@example.com", None, "no", root = true, verified = true)
        val juror = account("juror@example.com", None, "no", password = Some("old-password"), verified = true)
        val sessionToken = csrfToken(app)
        val refused = route(app, FakeRequest(POST, "/users/save")
          .withHeaders(COOKIE -> "PLAY_SESSION=x", ACCEPT -> "text/html")
          .withSession(Secured.UserName -> root.email, "csrfToken" -> sessionToken)
          .withFormUrlEncodedBody(form(juror, None, "no", "password" -> "taken-over"): _*)).get
        status(refused) === FORBIDDEN
        User.login(juror.email, "old-password").flatMap(_.id) === juror.id
        User.login(juror.email, "taken-over") must beNone
      }
    }
  }
}
