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

  private def saveProfile(user: User, password: String)(implicit app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(userController.saveUser(),
      as(user)(FakeRequest(POST, "/users/save")).withFormUrlEncodedBody(
        "id" -> user.getId.toString,
        // a signed-up account has no name yet; the form needs one
        "fullname" -> Some(user.fullname).filter(_.nonEmpty).getOrElse("Name"), "email" -> user.email, "password" -> password
      ).withCSRFToken)
  }

  private def signUp(login: String, password: String)(implicit app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(loginController.signUp(),
      FakeRequest(POST, "/signup").withFormUrlEncodedBody(
        "login" -> login, "password" -> password, "repeat.password" -> password).withCSRFToken)
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
        val hasher = PasswordHasher.default
        // every turn taken until the request's deadline has passed
        val permits = hasher.settings.maxConcurrent
        val holding = new java.util.concurrent.CountDownLatch(permits)
        val release = new java.util.concurrent.CountDownLatch(1)
        val holders = (1 to permits).map { _ =>
          val t = new Thread(() => hasher.withPermit(hasher.deadline())({ holding.countDown(); release.await() }))
          t.start(); t
        }
        try {
          holding.await()
          // a deadline already past: as a sign-in that waited its whole maxWait
          val request = FakeRequest(POST, "/auth")
            .withFormUrlEncodedBody("login" -> user.email, "password" -> "strong").withCSRFToken
          val result = Future.successful(loginController.signInResult(System.nanoTime() - 1)(request))
          status(result) === SERVICE_UNAVAILABLE
          contentAsString(result) must contain("Too many sign-ins at once")
          signedIn(result) must beFalse
        } finally {
          release.countDown()
          holders.foreach(_.join())
        }
        signedIn(signIn(user.email, "strong")) must beTrue
      }
    }
  }

  "every way of setting a password" should {
    "store a bcrypt hash at the configured cost, never SHA-1" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        val messages = MessagesImpl(Lang("en"), Helpers.stubMessagesApi())
        val Seq(contest) = createContests(10)

        // sign-up: the first account, root
        val signedUp = signUp("first@example.com", "first-password")
        status(signedUp) === SEE_OTHER
        val first = User.findByEmail("first@example.com").head
        first.password must beSome(beMatching(Bcrypt4))
        User.login("first@example.com", "first-password").flatMap(_.id) === first.id

        // an organizer's new user: given and generated passwords
        val givenUser = userService.createUser(first,
          User("Given", "given@example.com", roles = Set("jury"), password = Some("given-password")), Some(contest))(messages)
        stored(givenUser.getId) must beSome(beMatching(Bcrypt4))
        User.login("given@example.com", "given-password").flatMap(_.id) === givenUser.id
        val generated = userService.createUser(first, User("Generated", "generated@example.com", roles = Set("jury")),
          Some(contest))(messages)
        stored(generated.getId) must beSome(beMatching(Bcrypt4))

        // the own profile
        status(saveProfile(first, "changed-password")) === SEE_OTHER
        stored(first.getId) must beSome(beMatching(Bcrypt4))
        User.login("first@example.com", "changed-password").flatMap(_.id) === first.id
        User.login("first@example.com", "first-password") must beNone

        // root's reset
        val before = stored(givenUser.getId)
        val reset = call(userController.resetPassword(givenUser.getId),
          as(first)(FakeRequest(POST, s"/users/resetpasswd/${givenUser.getId}")).withCSRFToken)
        status(reset) === SEE_OTHER
        stored(givenUser.getId) must beSome(beMatching(Bcrypt4))
        stored(givenUser.getId) !== before
        User.login("given@example.com", "given-password") must beNone

        // import
        val imported = call(userController.importUsers(Some(10L)),
          as(first, Some(10L))(FakeRequest(POST, "/admin/importusers?contestId=10"))
            .withFormUrlEncodedBody("userstoimport" -> "imported1@example.com\nimported2@example.com", "role" -> "jury")
            .withCSRFToken)
        status(imported) === SEE_OTHER
        Seq("imported1@example.com", "imported2@example.com")
          .map(e => User.findByEmail(e).headOption.flatMap(_.password)) must contain(beSome(beMatching(Bcrypt4))).forall

        // a legacy hash, rehashed by signing in
        val old = root("legacy@example.com", legacy("legacy-password"))
        signedIn(signIn(old.email, "legacy-password")) must beTrue

        val passwords = DB.readOnly { implicit s =>
          sql"SELECT email, password FROM users".map(rs => rs.string(1) -> rs.stringOpt(2)).list.apply()
        }
        passwords.size === 6 // first, given, generated, two imported, legacy
        passwords.collect { case (email, Some(p)) if p.matches(Legacy) => email } must beEmpty
        passwords.collect { case (email, p) if !p.exists(_.matches(Bcrypt4)) => email } must beEmpty
      }
    }
  }

  "a new password" should {
    "be refused over 72 bytes, or blank, on sign-up" in {
      testDbApp { implicit app =>
        val result = signUp("long@example.com", "a" * 73)
        status(result) === BAD_REQUEST
        contentAsString(result) must contain("The password is too long: at most 72 bytes")
        User.findByEmail("long@example.com") must beEmpty

        status(signUp("blank@example.com", "   ")) === BAD_REQUEST
        User.findByEmail("blank@example.com") must beEmpty

        status(signUp("max@example.com", "a" * 72)) === SEE_OTHER
        User.login("max@example.com", "a" * 72) must beSome
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
