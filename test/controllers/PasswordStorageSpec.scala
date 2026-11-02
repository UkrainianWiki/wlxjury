package controllers

import db.scalikejdbc.{PlayTestDb, User}
import org.apache.pekko.stream.Materializer
import org.specs2.mock.Mockito
import play.api.Application
import play.api.i18n.{Lang, MessagesImpl}
import play.api.mvc.{ControllerComponents, Result}
import play.api.test.CSRFTokenHelper._
import play.api.test._
import scalikejdbc._
import services.{PasswordHasher, SMTPOrWikiMail, UserService}

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import scala.concurrent.Future

/** Passwords are stored as bcrypt hashes (the tests' cost, 4); a legacy SHA-1 hash signs in
  * once and is replaced. See [[services.PasswordHasher]].
  */
class PasswordStorageSpec extends PlaySpecification with PlayTestDb with Mockito {

  sequential

  private val Bcrypt4 = """\$2b\$04\$[./A-Za-z0-9]{53}"""
  private val Legacy = "[0-9a-f]{40}"

  private def legacy(password: String): String =
    MessageDigest.getInstance("SHA-1").digest(password.getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString

  private def userService(implicit app: Application) = new UserService(mock[SMTPOrWikiMail], app.configuration)

  private def loginController(implicit app: Application) =
    new LoginController(userService, app.injector.instanceOf[ControllerComponents],
      app.injector.instanceOf[modules.SignInExecutionContext])

  private def userController(implicit app: Application) =
    new UserController(app.injector.instanceOf[ControllerComponents], userService,
      app.injector.instanceOf[modules.BlockingExecutionContext])

  private def signIn(login: String, password: String)(implicit app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(loginController.auth(),
      FakeRequest(POST, "/auth").withFormUrlEncodedBody("login" -> login, "password" -> password).withCSRFToken)
  }

  private def signedIn(result: Future[Result]): Boolean = session(result).get(Secured.UserName).isDefined

  private def stored(id: Long): Option[String] = User.findById(id).flatMap(_.password)

  private def as[A](user: User, contestId: Option[Long] = None)(request: FakeRequest[A]): FakeRequest[A] =
    request.withSession(Seq(Secured.UserName -> user.email) ++ contestId.map(Secured.CurrentContestId -> _.toString): _*)

  /** The own profile with a new password, and the current one (N2: needed for it). */
  private def saveProfile(user: User, password: String, current: String = "strong")(implicit app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(userController.saveUser(),
      as(user)(FakeRequest(POST, "/users/save")).withFormUrlEncodedBody(
        "id" -> user.getId.toString,
        // an invited account has no name yet; the form needs one
        "fullname" -> Some(user.fullname).filter(_.nonEmpty).getOrElse("Name"), "email" -> user.email, "password" -> password,
        "currentPassword" -> current
      ).withCSRFToken)
  }

  /** Accepts an invitation (or a password link: the same form) for the account with `password`. */
  private def acceptLink(user: User, password: String)(implicit app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    val (_, token) = services.AccountTokenService.create(user.getId, db.scalikejdbc.AccountTokenJdbc.Invitation, user.email)
    call(loginController.acceptInvitation(),
      FakeRequest(POST, "/invitation").withFormUrlEncodedBody(
        "token" -> token, "password" -> password, "repeat.password" -> password).withCSRFToken)
  }

  private def root(email: String = "root@example.com", hash: String = PasswordHasher.default.hash("strong")) =
    User.create(User("Root", email, password = Some(hash), roles = Set(User.ROOT_ROLE)))

  "signing in with a legacy SHA-1 hash" should {
    "fail with a wrong password and leave the hash as it is" in {
      testDbApp { implicit app =>
        val user = root(hash = legacy("strong"))
        val result = signIn(user.email, "wrong")
        status(result) === BAD_REQUEST
        signedIn(result) must beFalse
        stored(user.getId) === Some(legacy("strong"))
      }
    }

    "succeed once, rewriting the row with a bcrypt hash that the next sign-in uses" in {
      testDbApp { implicit app =>
        val user = root(hash = legacy("strong"))

        val first = signIn(user.email, "strong")
        status(first) === SEE_OTHER
        signedIn(first) must beTrue
        val rehashed = stored(user.getId)
        rehashed must beSome(beMatching(Bcrypt4))

        val second = signIn(user.email, " strong ")
        signedIn(second) must beTrue
        stored(user.getId) === rehashed // checked with bcrypt, not rewritten again

        status(signIn(user.email, "wrong")) === BAD_REQUEST
        stored(user.getId) === rehashed
      }
    }

    "not overwrite a password changed after the legacy hash was read" in {
      testDbApp { implicit app =>
        val user = root(hash = legacy("old"))
        User.setPassword(user.getId, "new")
        val changed = stored(user.getId)
        status(signIn(user.email, "old")) === BAD_REQUEST
        stored(user.getId) === changed
        signedIn(signIn(user.email, "new")) must beTrue
      }
    }
  }

  /** Sets the end of the legacy grace period (V81) `days` from now; back to V81's 3 months after `f`. */
  private def withGraceEnd[T](days: Int)(f: => T): T = {
    def set(interval: String) = DB.autoCommit { implicit s =>
      SQL(s"UPDATE legacy_password_retirement SET retire_at = NOW(3) + INTERVAL $interval WHERE id = 1").update.apply()
    }
    set(s"$days DAY")
    try f
    finally set("3 MONTH")
  }

  /** `f`'s result and the lines logged on security.password meanwhile. */
  private def withPasswordLog[T](f: => T): (T, Seq[String]) = {
    val logger = org.slf4j.LoggerFactory.getLogger("security.password").asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    val level = logger.getLevel
    logger.setLevel(ch.qos.logback.classic.Level.INFO)
    appender.start()
    logger.addAppender(appender)
    try {
      val result = f
      import scala.jdk.CollectionConverters._
      (result, appender.list.asScala.toSeq.map(_.getFormattedMessage))
    } finally {
      logger.detachAppender(appender)
      logger.setLevel(level)
    }
  }

  private def retireNow(): Int = new modules.LegacyPasswordRetirement().runOnce()

  "a legacy SHA-1 hash, during its grace period (P8)" should {
    "still sign in, and not be replaced by the retirement" in {
      testDbApp { implicit app =>
        val user = root(hash = legacy("strong"))
        db.scalikejdbc.LegacyPasswordRetirementJdbc.over() must beFalse
        retireNow() === 0
        stored(user.getId) === Some(legacy("strong"))
        signedIn(signIn(user.email, "strong")) must beTrue
      }
    }

    "never be retired without the grace period's row (V81 undone)" in {
      testDbApp { implicit app =>
        val user = root(hash = legacy("strong"))
        val saved = DB.autoCommit { implicit s =>
          val at = sql"SELECT retire_at FROM legacy_password_retirement WHERE id = 1".map(_.string(1)).single.apply()
          sql"DELETE FROM legacy_password_retirement".update.apply()
          at
        }
        try {
          retireNow() === 0
          stored(user.getId) === Some(legacy("strong"))
          signedIn(signIn(user.email, "strong")) must beTrue
        } finally DB.autoCommit { implicit s =>
          sql"INSERT INTO legacy_password_retirement (id, retire_at) VALUES (1, ${saved.get})".update.apply()
        }
      }
    }

    "never be retired without the grace period's table (V81's undo, DROP TABLE)" in {
      testDbApp { implicit app =>
        val user = root(hash = legacy("strong"))
        withGraceEnd(-1) {
          DB.autoCommit(implicit s => sql"RENAME TABLE legacy_password_retirement TO legacy_password_retirement_saved".update.apply())
          try {
            db.scalikejdbc.LegacyPasswordRetirementJdbc.over() must beFalse
            retireNow() === 0
            stored(user.getId) === Some(legacy("strong"))
            signedIn(signIn(user.email, "strong")) must beTrue
          } finally DB.autoCommit { implicit s =>
            sql"RENAME TABLE legacy_password_retirement_saved TO legacy_password_retirement".update.apply()
          }
        }
      }
    }
  }

  "a legacy SHA-1 hash, after its grace period (P8)" should {
    "not sign in even before it is replaced, the page pointing to a password link" in {
      testDbApp { implicit app =>
        val user = root(hash = legacy("strong"))
        withGraceEnd(-1) {
          val result = signIn(user.email, "strong")
          status(result) === BAD_REQUEST
          signedIn(result) must beFalse
          contentAsString(result) must contain("ask the contest&#x27;s organizers for a link to set a new one")
          stored(user.getId) === Some(legacy("strong")) // not rehashed
        }
      }
    }

    "be replaced by an unusable value, the account recorded, other passwords untouched, quietly" in {
      testDbApp { implicit app =>
        val user = root(hash = legacy("strong"))
        val upper = root("upper@example.com", legacy("other").toUpperCase)
        val current = root("bcrypt@example.com")
        val currentHash = stored(current.getId)
        val invited = User.create(User("", "invited@example.com", password = None, roles = Set(User.ROOT_ROLE)))
        withGraceEnd(-1) {
          val (replaced, log) = withPasswordLog(retireNow())
          replaced === 2
          log === Seq("legacy_passwords_retired count=2")
          stored(user.getId) === Some(PasswordHasher.RetiredLegacy)
          stored(upper.getId) === Some(PasswordHasher.RetiredLegacy)
          db.scalikejdbc.LegacyPasswordRetirementJdbc.retiredAccounts() === Seq(user.getId, upper.getId).sorted
          stored(current.getId) === currentHash
          stored(invited.getId) === None

          // nothing signs in with the replaced value, not even the value itself
          signedIn(signIn(user.email, "strong")) must beFalse
          signedIn(signIn(user.email, PasswordHasher.RetiredLegacy)) must beFalse
          signedIn(signIn(current.email, "strong")) must beTrue

          // again: nothing left to replace
          retireNow() === 0
          db.scalikejdbc.LegacyPasswordRetirementJdbc.retiredAccounts() must haveSize(2)
        }
      }
    }

    "not count as the current password for one's own password change, even before it is replaced" in {
      testDbApp { implicit app =>
        val user = root(hash = legacy("strong"))
        withGraceEnd(-1) {
          status(saveProfile(user, "new password", current = "strong")) === BAD_REQUEST
          stored(user.getId) === Some(legacy("strong"))
        }
      }
    }

    "be set again through a password link, as root's button sends" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        val user = root(hash = legacy("strong"))
        withGraceEnd(-1) {
          retireNow() === 1
          val (_, token) = services.AccountTokenService.create(user.getId, db.scalikejdbc.AccountTokenJdbc.PasswordReset, user.email)
          val accepted = call(loginController.acceptInvitation(),
            FakeRequest(POST, "/invitation").withFormUrlEncodedBody(
              "token" -> token, "password" -> "new password", "repeat.password" -> "new password").withCSRFToken)
          status(accepted) === SEE_OTHER
          stored(user.getId) must beSome(beMatching(Bcrypt4))
          signedIn(signIn(user.email, "new password")) must beTrue
        }
      }
    }
  }

  "signing in" should {
    "fail with a wrong password for a bcrypt hash" in {
      testDbApp { implicit app =>
        val user = root()
        val result = signIn(user.email, "Strong")
        status(result) === BAD_REQUEST
        signedIn(result) must beFalse
        contentAsString(result) must contain("Invalid user or password")
      }
    }

    "never match a stored value that is neither bcrypt nor SHA-1, nor a missing one" in {
      testDbApp { implicit app =>
        val garbage = root(hash = "hash")
        val none = User.create(User("No password", "none@example.com", isRoot = true))
        status(signIn(garbage.email, "hash")) === BAD_REQUEST
        stored(garbage.getId) === Some("hash")
        status(signIn(none.email, "")) === BAD_REQUEST
        status(signIn(none.email, "anything")) === BAD_REQUEST
        status(signIn("nobody@example.com", "strong")) === BAD_REQUEST
      }
    }

    "ask to try again when no turn to check the password comes in time" in {
      testDbApp { implicit app =>
        val user = root()
        withEveryTurnTaken {
          // a deadline already past: as a sign-in that waited its whole maxWait
          val request = FakeRequest(POST, "/auth")
            .withFormUrlEncodedBody("login" -> user.email, "password" -> "strong").withCSRFToken
          val result = Future.successful(loginController.signInResult(System.nanoTime() - 1)(request))
          status(result) === SERVICE_UNAVAILABLE
          contentAsString(result) must contain("Too many sign-ins at once")
          signedIn(result) must beFalse
        }
        signedIn(signIn(user.email, "strong")) must beTrue
      }
    }
  }

  /** `body` while a thread holds each of the hasher's turns. */
  private def withEveryTurnTaken[A](body: => A): A = {
    val hasher = PasswordHasher.default
    val permits = hasher.settings.maxConcurrent
    val holding = new java.util.concurrent.CountDownLatch(permits)
    val release = new java.util.concurrent.CountDownLatch(1)
    val holders = (1 to permits).map { _ =>
      val t = new Thread(() => hasher.withPermit(hasher.deadline())({ holding.countDown(); release.await() }))
      t.start(); t
    }
    try {
      holding.await()
      body
    } finally {
      release.countDown()
      holders.foreach(_.join())
    }
  }

  "with no turn to hash a password, or too many sign-ins in flight" should {
    "an invitation's acceptance get 503 and change nothing" in {
      testDbApp { implicit app =>
        val invitee = User.create(User("Late", "late@example.com"))
        val (_, token) = services.AccountTokenService.create(invitee.getId, db.scalikejdbc.AccountTokenJdbc.Invitation,
          invitee.email)
        withEveryTurnTaken {
          val request = FakeRequest(POST, "/invitation").withFormUrlEncodedBody(
            "token" -> token, "password" -> "late-password", "repeat.password" -> "late-password"
          ).withCSRFToken
          val result = Future.successful(loginController.acceptInvitationResult(System.nanoTime() - 1)(request))
          status(result) === SERVICE_UNAVAILABLE
          contentAsString(result) must contain("Too many sign-ins at once")
          signedIn(result) must beFalse
        }
        stored(invitee.getId) must beNone
        services.AccountTokenService.check(token, db.scalikejdbc.AccountTokenJdbc.SetsPassword) must beRight
      }
    }

    "a sign-in past the in-flight limit get 503 at once" in {
      testDbApp { implicit app =>
        val user = root()
        val hasher = PasswordHasher.default
        val limit = hasher.settings.maxQueued
        (1 to limit).map(_ => hasher.admit()).forall(identity) must beTrue
        try {
          val started = System.nanoTime()
          val refused = signIn(user.email, "strong")
          status(refused) === SERVICE_UNAVAILABLE
          header(RETRY_AFTER, refused) === Some("5")
          contentAsString(refused) must contain("Too many sign-ins at once")
          (System.nanoTime() - started) must be_<(1000000000L) // no wait for a turn
        } finally (1 to limit).foreach(_ => hasher.leave())
        signedIn(signIn(user.email, "strong")) must beTrue
      }
    }

    "the edit form change nothing and say so" in {
      testDbApp { implicit app =>
        val user = root()
        val before = stored(user.getId)
        val result = withEveryTurnTaken {
          implicit val mat: Materializer = app.materializer
          val r = call(userController.saveUser(),
            as(user)(FakeRequest(POST, "/users/save")).withFormUrlEncodedBody(
              "id" -> user.getId.toString, "fullname" -> "Renamed", "email" -> user.email, "password" -> "new-password",
              "currentPassword" -> "strong"
            ).withCSRFToken)
          status(r) // completes while the turns are taken
          r
        }
        status(result) === SERVICE_UNAVAILABLE
        contentAsString(result) must contain("The server is busy checking passwords")
        User.findById(user.getId).map(_.fullname) === Some("Root")
        stored(user.getId) === before
      }
    }

    "not hold up an import or root's reset, which hash nothing (S2: invitations and links)" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10)
        val admin = root()
        val juror = User.create(User("Juror", "juror@example.com", roles = Set("jury"),
          password = Some(PasswordHasher.default.hash("juror-password")), contestId = Some(10L)))
        val before = stored(juror.getId)
        val (imported, reset) = withEveryTurnTaken {
          val i = call(userController.importUsers(Some(10L)),
            as(admin, Some(10L))(FakeRequest(POST, "/admin/importusers?contestId=10"))
              .withFormUrlEncodedBody("userstoimport" -> "a1@example.com\na2@example.com\na3@example.com", "role" -> "jury")
              .withCSRFToken)
          status(i)
          val r = call(userController.resetPassword(juror.getId),
            as(admin)(FakeRequest(POST, s"/users/resetpasswd/${juror.getId}")).withCSRFToken)
          status(r)
          (i, r)
        }
        status(imported) === SEE_OTHER
        flash(imported).get("users-added") must beSome(contain("New accounts: 3."))
        Seq("a1@example.com", "a2@example.com", "a3@example.com").flatMap(User.findByEmail).map(_.password) ===
          Seq(None, None, None)
        status(reset) === SEE_OTHER
        stored(juror.getId) === before
      }
    }
  }

  "every way of setting a password" should {
    "store a bcrypt hash at the configured cost, never SHA-1" in {
      testDbApp { implicit app =>
        val messages = MessagesImpl(Lang("en"), Helpers.stubMessagesApi())
        val Seq(contest) = createContests(10)
        val first = root("first@example.com")

        // an organizer's new user: no password until the invitation is accepted, whatever the form said
        val givenUser = userService.createUser(first,
          User("Given", "given@example.com", roles = Set("jury"), password = Some("given-password")), Some(contest))(messages)
        stored(givenUser.getId) must beNone
        User.login("given@example.com", "given-password") must beNone
        status(acceptLink(givenUser, "accepted-password")) === SEE_OTHER
        stored(givenUser.getId) must beSome(beMatching(Bcrypt4))
        User.login("given@example.com", "accepted-password").flatMap(_.id) === givenUser.id

        // the own profile
        status(saveProfile(first, "changed-password")) === SEE_OTHER
        stored(first.getId) must beSome(beMatching(Bcrypt4))
        User.login("first@example.com", "changed-password").flatMap(_.id) === first.id
        User.login("first@example.com", "strong") must beNone

        // root's password link
        val before = stored(givenUser.getId)
        status(acceptLink(givenUser, "reset-password")) === SEE_OTHER
        stored(givenUser.getId) must beSome(beMatching(Bcrypt4))
        stored(givenUser.getId) !== before
        User.login("given@example.com", "accepted-password") must beNone

        // a legacy hash, rehashed by signing in
        val old = root("legacy@example.com", legacy("legacy-password"))
        signedIn(signIn(old.email, "legacy-password")) must beTrue

        val passwords = DB.readOnly { implicit s =>
          sql"SELECT email, password FROM users".map(rs => rs.string(1) -> rs.stringOpt(2)).list.apply()
        }
        passwords.size === 3 // first, given, legacy
        passwords.collect { case (email, Some(p)) if p.matches(Legacy) => email } must beEmpty
        passwords.collect { case (email, p) if !p.exists(_.matches(Bcrypt4)) => email } must beEmpty
      }
    }
  }

  "a new password" should {
    "be refused over 72 bytes, or blank, when an invitation is accepted" in {
      testDbApp { implicit app =>
        val invitee = User.create(User("Invitee", "invitee@example.com"))
        val result = acceptLink(invitee, "a" * 73)
        status(result) === BAD_REQUEST
        contentAsString(result) must contain("The password is too long: at most 72 bytes")
        stored(invitee.getId) must beNone

        status(acceptLink(invitee, "   ")) === BAD_REQUEST
        stored(invitee.getId) must beNone

        status(acceptLink(invitee, "a" * 72)) === SEE_OTHER
        User.login("invitee@example.com", "a" * 72) must beSome
      }
    }

    "be refused over 72 bytes, or blank, on the profile, keeping the old one" in {
      testDbApp { implicit app =>
        val user = root()
        val before = stored(user.getId)

        val long = saveProfile(user, "я" * 37) // 74 bytes
        status(long) === BAD_REQUEST
        contentAsString(long) must contain("The password is too long: at most 72 bytes")
        stored(user.getId) === before

        val blank = saveProfile(user, "   ")
        status(blank) === BAD_REQUEST
        contentAsString(blank) must contain("The password must not be blank.")
        stored(user.getId) === before

        status(saveProfile(user, "я" * 36)) === SEE_OTHER
        User.login(user.email, "я" * 36).flatMap(_.id) === user.id
      }
    }

    "still let a legacy password over 72 bytes sign in, rehashed so that only it matches" in {
      testDbApp { implicit app =>
        val long = "x" * 72 + " and more of a long legacy password"
        val user = root(hash = legacy(long))
        signedIn(signIn(user.email, long)) must beTrue
        stored(user.getId) must beSome(beMatching(Bcrypt4))
        signedIn(signIn(user.email, long)) must beTrue
        status(signIn(user.email, "x" * 72)) === BAD_REQUEST
      }
    }
  }
}
