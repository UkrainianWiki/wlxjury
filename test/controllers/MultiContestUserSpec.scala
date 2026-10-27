package controllers

import db.scalikejdbc.{PlayTestDb, Round, RoundUser, User, UserContest, UserContestJdbc}
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
        val denied = call(
          controller.switchContest(30),
          FakeRequest(GET, "/switch-contest/30")
            .withHeaders("Accept-Language" -> "fr")
            .withSession(Secured.UserName -> gina.email)
        )
        status(denied) mustEqual FORBIDDEN
        val body = contentAsString(denied)
        body must contain("Accès refusé")
        body must contain("Vous êtes connecté avec le compte gina@example.com.")
        body must beMatching("""(?s).*Référence : [0-9a-f]{8}, \d{4}-\d\d-\d\d \d\d:\d\d UTC.*""")
      }
    }

    "not switch to, nor log in to, a contest where the user has the no role" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val hal = member("hal@example.com", 10L -> "jury", 20L -> User.NO_ROLE)

        Secured.inCurrentContest(hal, None).contestId === Some(10L)
        Secured.inCurrentContest(hal, Some("20")).contestId === Some(10L)
        status(call(
          controller.switchContest(20),
          FakeRequest(GET, "/switch-contest/20").withSession(Secured.UserName -> hal.email)
        )) mustEqual FORBIDDEN
      }
    }
  }

  "a link into another of the user's contests" should {
    "open that contest and make it the session's current one" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val ivan = member("ivan@example.com", 10L -> "jury", 20L -> "admin")

        val result = call(
          controller.users(Some(20)),
          FakeRequest(GET, "/admin/users?contestId=20")
            .withSession(Secured.UserName -> ivan.email, Secured.CurrentContestId -> "10")
            .withCSRFToken
        )

        status(result) === OK
        session(result).get(Secured.CurrentContestId) === Some("20")
      }
    }

    "open a round's gallery in the round's contest" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val jane = member("jane@example.com", 10L -> "jury", 20L -> "jury")
        val round = Round.create(Round(None, 1, contestId = 20, active = true))
        round.addUsers(Seq(RoundUser(round.getId, jane.getId, "jury", active = true)))

        val result = call(
          app.injector.instanceOf[GalleryController].list(jane.getId, 1, "all", round.getId, Some(0)),
          FakeRequest(GET, "/").withSession(Secured.UserName -> jane.email, Secured.CurrentContestId -> "10")
        )

        status(result) === OK
        session(result).get(Secured.CurrentContestId) === Some("20")
      }
    }

    "still be refused for a contest the user is not in" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20, 30)
        val kim = member("kim@example.com", 10L -> "admin", 20L -> "jury")

        val result = call(
          controller.users(Some(30)),
          FakeRequest(GET, "/admin/users?contestId=30")
            .withSession(Secured.UserName -> kim.email, Secured.CurrentContestId -> "10")
        )

        status(result) mustEqual FORBIDDEN
        // the session cookie is not rewritten: contest 10 stays current
        session(result).get(Secured.CurrentContestId) must beNone
      }
    }
  }

  "adding users" should {
    "not let a contest admin create a root user" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = member("admin@example.com", 10L -> "admin")

        status(save(admin, 10, "id" -> "0", "fullname" -> "Mallory", "email" -> "mallory@example.com",
                    "roles" -> User.ROOT_ROLE, "contest" -> "10")) === SEE_OTHER

        val mallory = User.findByEmail("mallory@example.com").head
        mallory.isRoot === false
        UserContestJdbc.findByUser(mallory.getId) === Seq(UserContest(mallory.getId, 10, "jury"))
      }
    }

    "tell the admin who got a new account, who joined with theirs and who was in already" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val admin = member("admin@example.com", 10L -> "admin")
        member("old@example.com", 20L -> "jury")
        member("in@example.com", 10L -> "jury")

        val result = call(
          controller.importUsers(Some(10)),
          FakeRequest(POST, "/admin/importusers?contestId=10")
            .withSession(Secured.UserName -> admin.email, Secured.CurrentContestId -> "10")
            .withFormUrlEncodedBody("userstoimport" ->
              "New One <new@example.com>\nOld One <old@example.com>\nIn One <in@example.com>")
            .withCSRFToken
        )

        status(result) === SEE_OTHER
        flash(result).get("users-added") must beSome(
          "New accounts: 1. Existing accounts added to the contest: 1. Already in the contest: 1.")
      }
    }
  }

  "editing users" should {
    "deny a juror another user's edit page without naming the page's own user as a role" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10)
        val juror = member("juror@example.com", 10L -> "jury")
        val other = member("other@example.com", 10L -> "jury")

        val page = call(
          controller.editUser(other.getId, Some(10)),
          FakeRequest(GET, s"/users/edit/${other.getId}?contestId=10").withSession(Secured.UserName -> juror.email)
        )

        status(page) mustEqual FORBIDDEN
        val html = contentAsString(page)
        html must contain("This page is for: Admin, Root.")
        html must not(contain("USER_ID_"))
      }
    }

    "let root edit the role in the contest the edit link names" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val root = User.create(User("Root", "root@example.com", roles = Set(User.ROOT_ROLE), password = Some("hash")))
        val leo = member("leo@example.com", 10L -> "jury", 20L -> "organizer")

        val page = call(
          controller.editUser(leo.getId, Some(10)),
          FakeRequest(GET, s"/users/edit/${leo.getId}?contestId=10").withSession(Secured.UserName -> root.email)
            .withCSRFToken
        )
        contentAsString(page) must contain("""name="contest" id="contest" value='10'""")

        status(save(root, 10, form(leo, 10, "admin"): _*)) === SEE_OTHER
        UserContestJdbc.findByUser(leo.getId) ===
          Seq(UserContest(leo.getId, 20, "organizer"), UserContest(leo.getId, 10, "admin"))
      }
    }

    "not let a contest admin change a root user's role in the contest" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = member("admin@example.com", 10L -> "admin")
        val root = User.create(User("Root", "root@example.com", roles = Set(User.ROOT_ROLE), password = Some("hash")))
        UserContestJdbc.createMembership(root.getId, 10, "jury")

        // an admin's role list has no root, so the browser sends its first option
        status(save(admin, 10, form(root, 10, User.NO_ROLE): _*)) === SEE_OTHER

        UserContestJdbc.findByUser(root.getId) === Seq(UserContest(root.getId, 10, "jury"))
        User.findById(root.getId).map(_.isRoot) === Some(true)
      }
    }

    "not let a contest admin change the password of an account with a past in another contest" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = member("admin@example.com", 10L -> "admin")
        // assigned to a round of contest 20, but no longer a member of it
        val mia = member("mia@example.com", 20L -> "jury")
        val round = Round.create(Round(None, 1, contestId = 20))
        round.addUsers(Seq(RoundUser(round.getId, mia.getId, "jury", active = true)))
        UserContestJdbc.delete(mia.getId, 20)

        status(save(admin, 10, "id" -> "0", "fullname" -> "Mia", "email" -> "mia@example.com",
                    "roles" -> "jury", "contest" -> "10")) === SEE_OTHER
        status(save(admin, 10, form(mia, 10, "jury", Some("taken-over")): _*)) === BAD_REQUEST

        User.findById(mia.getId).flatMap(_.password) === Some("hash")
      }
    }
  }
}
