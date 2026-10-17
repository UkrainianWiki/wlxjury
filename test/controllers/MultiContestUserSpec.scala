package controllers

import db.scalikejdbc.{PlayTestDb, User, UserContest, UserContestJdbc}
import org.apache.pekko.stream.Materializer
import org.intracer.wmua.ContestJury
import org.specs2.mock.Mockito
import play.api.Application
import play.api.i18n.{Lang, MessagesImpl}
import play.api.mvc.{ControllerComponents, Result}
import play.api.test.CSRFTokenHelper._
import play.api.test._
import services.{SMTPOrWikiMail, UserService}

import scala.concurrent.Future

/** One account per email, in several contests with a role in each. */
class MultiContestUserSpec extends PlaySpecification with PlayTestDb with Mockito {

  sequential

  private def mailer = mock[SMTPOrWikiMail]

  private def userService(mail: SMTPOrWikiMail)(implicit app: Application) =
    new UserService(mail, app.configuration)

  private def controller(implicit app: Application) =
    new UserController(app.injector.instanceOf[ControllerComponents], userService(mailer))

  private def messages(implicit app: Application) =
    MessagesImpl(Lang("en"), Helpers.stubMessagesApi(Map("en" -> Map("password.existing" -> "EXISTING"))))

  private def member(email: String, memberships: (Long, String)*): User = {
    val (firstContest, firstRole) = memberships.head
    val created = User.create(
      User(email, email, None, Set(firstRole), Some("hash"), Some(firstContest))
    )
    memberships.tail.foreach { case (contest, role) => UserContestJdbc.createMembership(created.getId, contest, role) }
    created
  }

  private def save(as: User, contestId: Long, fields: (String, String)*)(implicit app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(
      controller.saveUser(),
      FakeRequest(POST, "/users/save")
        .withSession(Secured.UserName -> as.email, Secured.CurrentContestId -> contestId.toString)
        .withFormUrlEncodedBody(fields: _*)
        .withCSRFToken
    )
  }

  private def form(user: User, contestId: Long, role: String, password: Option[String] = None) =
    Seq(
      "id" -> user.getId.toString,
      "fullname" -> user.fullname,
      "email" -> user.email,
      "roles" -> role,
      "contest" -> contestId.toString
    ) ++ password.map("password" -> _)

  "createUser" should {
    "add an existing email to another contest, keeping its account and password" in {
      testDbApp { implicit app =>
        val Seq(c10, c20) = createContests(10, 20)
        val alice = member("alice@example.com", 10L -> "jury")
        val mail = mailer

        val added = userService(mail).createUser(
          alice, User("", " Alice@Example.com", roles = Set("organizer")), Some(c20)
        )(messages)

        added.id === alice.id
        added.contestId === Some(20L)
        User.findAll().map(_.getId) === Seq(alice.getId)
        User.findById(alice.getId).flatMap(_.password) === Some("hash")
        UserContestJdbc.findByUser(alice.getId) ===
          Seq(UserContest(alice.getId, 20, "organizer"), UserContest(alice.getId, 10, "jury"))
        there was one(mail).sendMail(any[User], any[User], any[String], contain("EXISTING"))
      }
    }

    "keep the role of an existing member and send nothing" in {
      testDbApp { implicit app =>
        val Seq(c10) = createContests(10)
        val bob = member("bob@example.com", 10L -> "admin")
        val mail = mailer

        userService(mail).createUser(bob, User("", "bob@example.com", roles = Set("jury")), Some(c10))(messages)

        UserContestJdbc.findByUser(bob.getId) === Seq(UserContest(bob.getId, 10, "admin"))
        there was no(mail).sendMail(any[User], any[User], any[String], any[String])
      }
    }
  }

  "saveUser" should {
    "let a contest admin add an existing email to the contest" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = member("admin@example.com", 20L -> "admin")
        val carol = member("carol@example.com", 10L -> "jury")

        val result = save(admin, 20, "id" -> "0", "fullname" -> "Carol", "email" -> "carol@example.com",
                          "roles" -> "jury", "contest" -> "20")

        status(result) === SEE_OTHER
        User.findAll().size === 2
        UserContestJdbc.findByUser(carol.getId).map(_.contestId) === Seq(20L, 10L)
      }
    }

    "let a contest admin change the role, only in their contest, of a user shared with another" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = member("admin@example.com", 10L -> "admin")
        val dave = member("dave@example.com", 10L -> "jury", 20L -> "admin")

        status(save(admin, 10, form(dave, 10, "organizer"): _*)) === SEE_OTHER

        UserContestJdbc.findByUser(dave.getId) ===
          Seq(UserContest(dave.getId, 20, "admin"), UserContest(dave.getId, 10, "organizer"))
      }
    }

    "not let a contest admin change the password of a user shared with another contest" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = member("admin@example.com", 10L -> "admin")
        val dave = member("dave@example.com", 10L -> "jury", 20L -> "admin")

        status(save(admin, 10, form(dave, 10, "jury", Some("taken-over")): _*)) === BAD_REQUEST

        User.findById(dave.getId).flatMap(_.password) === Some("hash")
      }
    }

    "let a contest admin change the password of a user only in their contest" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = member("admin@example.com", 10L -> "admin")
        val erin = member("erin@example.com", 10L -> "jury")

        status(save(admin, 10, form(erin, 10, "jury", Some("new")): _*)) === SEE_OTHER

        User.findById(erin.getId).flatMap(_.password) === Some(User.sha1("new"))
      }
    }

    "not let a contest admin edit a user of another contest by naming their own contest in the form" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = member("admin@example.com", 10L -> "admin")
        val frank = member("frank@example.com", 20L -> "admin")

        status(save(admin, 10, form(frank, 10, "jury", Some("taken-over")): _*)) === SEE_OTHER
        User.findById(frank.getId).flatMap(_.password) === Some("hash")
        UserContestJdbc.findByUser(frank.getId) === Seq(UserContest(frank.getId, 20, "admin"))
      }
    }
  }

  "switchContest" should {
    "switch only to one of the user's contests" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20, 30)
        val gina = member("gina@example.com", 10L -> "jury", 20L -> "organizer")
        def switch(to: Long) = call(
          controller.switchContest(to),
          FakeRequest(GET, s"/switch-contest/$to").withSession(Secured.UserName -> gina.email)
        )

        session(switch(10)).get(Secured.CurrentContestId) === Some("10")
        redirectLocation(switch(30)) must beSome(contain("/error"))
      }
    }
  }
}
