package controllers

import db.RoleAllowlistMigration
import db.scalikejdbc.{PlayTestDb, User, UserContestJdbc}
import org.apache.pekko.stream.Materializer
import org.specs2.mock.Mockito
import play.api.Application
import play.api.i18n.{Lang, MessagesImpl}
import play.api.mvc.{ControllerComponents, Result}
import play.api.test.CSRFTokenHelper._
import play.api.test._
import scalikejdbc._
import services.{SMTPOrWikiMail, UserService}

import scala.concurrent.Future

/** N1: a contest role is exactly one allowlisted value ([[User.ContestRoles]]); root comes only
  * from users.is_root, which only root sets. An admin's `/users/save` with anything else is a
  * 400 that writes nothing, for their own account, another member and a new or existing
  * account; a stored bad role grants nothing.
  */
class RoleAssignmentSpec extends PlaySpecification with PlayTestDb with Mockito {

  sequential

  private def mailer = mock[SMTPOrWikiMail]

  private def userService(implicit app: Application) = new UserService(mailer, app.configuration)

  private def controller(implicit app: Application) =
    new UserController(app.injector.instanceOf[ControllerComponents], userService,
      app.injector.instanceOf[modules.BlockingExecutionContext])

  private def messages = MessagesImpl(Lang("en"), Helpers.stubMessagesApi())

  /** Values an admin must not store: N1's root through a list, root variants, the marker,
    * unknown words and the legacy hand-disabled values.
    */
  private val badRoles = Seq(
    "root", "jury,root", "jury, root", "admin,root", " root", "root ", "ROOT", "Root",
    "USER_ID_1", "superuser", "jury1", "Jury", "jury ", "admin "
  )

  private def member(email: String, memberships: (Long, String)*): User = {
    // verified: a role is saved directly (an unverified account's goes through an invitation, S2)
    val created = User.create(User(email, email, None, Set.empty, Some("hash"),
      emailVerifiedAt = Some(java.time.ZonedDateTime.now)))
    memberships.foreach { case (contest, role) => UserContestJdbc.createMembership(created.getId, contest, role) }
    created
  }

  private def root(email: String): User = User.create(User(email, email, None, Set(User.ROOT_ROLE), Some("hash")))

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

  private def form(id: Long, email: String, contestId: Long, role: String) =
    Seq("id" -> id.toString, "fullname" -> email, "email" -> email, "roles" -> role, "contest" -> contestId.toString)

  /** Every membership and every account's root flag: what a refused save must not change. */
  private def state(): (List[(Long, Long, String)], List[(Long, Boolean)]) =
    DB.readOnly { implicit session =>
      (sql"SELECT user_id, contest_id, role FROM user_contest ORDER BY user_id, contest_id"
         .map(rs => (rs.long(1), rs.long(2), rs.string(3))).list(),
       sql"SELECT id, is_root FROM users ORDER BY id".map(rs => (rs.long(1), rs.boolean(2))).list())
    }

  /** Each bad role, saved by `as`: a 400 each time and nothing changed. */
  private def refused(as: User, contestId: Long, fields: String => Seq[(String, String)])(implicit app: Application) = {
    val before = state()
    val statuses = badRoles.map(role => role -> status(save(as, contestId, fields(role): _*)))
    (statuses.filterNot(_._2 == BAD_REQUEST) === Nil) and (state() === before)
  }

  "an admin's /users/save" should {
    "refuse a role that is not a contest role for their own account" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = member("admin@example.com", 10L -> "admin")
        refused(admin, 10, form(admin.getId, admin.email, 10, _))
      }
    }

    "refuse it for another member of the contest" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = member("admin@example.com", 10L -> "admin")
        val bob = member("bob@example.com", 10L -> "jury")
        refused(admin, 10, form(bob.getId, bob.email, 10, _))
      }
    }

    "refuse it for a new account (id = 0), creating nothing" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = member("admin@example.com", 10L -> "admin")
        refused(admin, 10, form(0, "new@example.com", 10, _)) and
          (User.findByEmail("new@example.com") === Nil)
      }
    }

    "refuse it for an existing account's email (id = 0), adding no membership" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = member("admin@example.com", 10L -> "admin")
        val carol = member("carol@example.com", 20L -> "jury")
        refused(admin, 10, form(0, carol.email, 10, _))
      }
    }

    "refuse root for a root user's form" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = member("admin@example.com", 10L -> "admin")
        val boss = root("boss@example.com")
        UserContestJdbc.createMembership(boss.getId, 10, "jury")
        val before = state()
        (status(save(admin, 10, form(boss.getId, boss.email, 10, User.ROOT_ROLE): _*)) === BAD_REQUEST) and
          (state() === before)
      }
    }

    "change nothing for an empty role (none given)" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = member("admin@example.com", 10L -> "admin")
        val bob = member("bob@example.com", 10L -> "jury")
        val before = state()
        status(save(admin, 10, form(bob.getId, bob.email, 10, ""): _*))
        status(save(admin, 10, form(admin.getId, admin.email, 10, ""): _*))
        state() === before
      }
    }

    "save each contest role for another member" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = member("admin@example.com", 10L -> "admin")
        val bob = member("bob@example.com", 10L -> "jury")
        User.FORM_ROLES.map { role =>
          (status(save(admin, 10, form(bob.getId, bob.email, 10, role): _*)) === SEE_OTHER) and
            (UserContestJdbc.find(bob.getId, 10).map(_.role) === Some(role))
        }.reduce(_ and _) and (User.findById(bob.getId).map(_.isRoot) === Some(false))
      }
    }

    "not change a role in another contest" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = member("admin@example.com", 10L -> "admin")
        val dave = member("dave@example.com", 20L -> "jury")
        status(save(admin, 10, form(dave.getId, dave.email, 20, "admin"): _*))
        UserContestJdbc.findByUser(dave.getId).map(m => (m.contestId, m.role)) === Seq((20L, "jury"))
      }
    }
  }

  "an organizer's or a juror's /users/save" should {
    "leave their own role as it is" in {
      testDbApp { implicit app =>
        createContests(10)
        val org = member("org@example.com", 10L -> "organizer")
        val jury = member("jury@example.com", 10L -> "jury")
        status(save(org, 10, form(org.getId, org.email, 10, "admin"): _*))
        status(save(jury, 10, form(jury.getId, jury.email, 10, "admin"): _*))
        (UserContestJdbc.find(org.getId, 10).map(_.role) === Some("organizer")) and
          (UserContestJdbc.find(jury.getId, 10).map(_.role) === Some("jury"))
      }
    }

    "refuse a role that is not a contest role" in {
      testDbApp { implicit app =>
        createContests(10)
        val jury = member("jury@example.com", 10L -> "jury")
        refused(jury, 10, form(jury.getId, jury.email, 10, _))
      }
    }
  }

  "root's /users/save" should {
    "make a member root through users.is_root, writing no contest role" in {
      testDbApp { implicit app =>
        createContests(10)
        val boss = root("boss@example.com")
        val bob = member("bob@example.com", 10L -> "jury")
        (status(save(boss, 10, form(bob.getId, bob.email, 10, User.ROOT_ROLE): _*)) === SEE_OTHER) and
          (User.findById(bob.getId).map(_.isRoot) === Some(true)) and
          (UserContestJdbc.findByUser(bob.getId).map(_.role) === Seq("jury"))
      }
    }

    "create a root account without a membership" in {
      testDbApp { implicit app =>
        createContests(10)
        val boss = root("boss@example.com")
        (status(save(boss, 10, form(0, "new@example.com", 10, User.ROOT_ROLE): _*)) === SEE_OTHER) and
          (User.findByEmail("new@example.com").map(_.isRoot) === Seq(true)) and
          (User.findByEmail("new@example.com").flatMap(u => UserContestJdbc.findByUser(u.getId)) === Nil)
      }
    }

    "refuse a role that is neither a contest role nor root" in {
      testDbApp { implicit app =>
        createContests(10)
        val boss = root("boss@example.com")
        val bob = member("bob@example.com", 10L -> "jury")
        val before = state()
        val statuses = badRoles.filterNot(_ == User.ROOT_ROLE)
          .map(role => role -> status(save(boss, 10, form(bob.getId, bob.email, 10, role): _*)))
        (statuses.filterNot(_._2 == BAD_REQUEST) === Nil) and (state() === before)
      }
    }
  }

  "UserService.addToContest" should {
    "refuse a role that is not a contest role, creating nothing" in {
      testDbApp { implicit app =>
        val Seq(c10, _) = createContests(10, 20)
        val admin = member("admin@example.com", 10L -> "admin").copy(contestId = Some(10), roles = Set("admin"))
        val carol = member("carol@example.com", 20L -> "jury")
        val before = state()
        val notRefused = for {
          role <- badRoles
          email <- Seq("new@example.com", carol.email)
          if scala.util.Try(userService.addToContest(admin, User("", email, roles = Set(role)), Some(c10))(messages))
            .failed.toOption.forall(!_.isInstanceOf[UserService.RoleNotAssignable])
        } yield (role, email)
        (notRefused === Nil) and (state() === before)
      }
    }
  }

  "saveUser for a new user" should {
    "not report another IllegalArgumentException as a refused role" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = member("admin@example.com", 10L -> "admin")
        val failing = new UserService(mailer, app.configuration) {
          override def createNewUser(user: User, formUser: User)(implicit messages: play.api.i18n.Messages) =
            throw new IllegalArgumentException("something else")
        }
        val controller = new UserController(app.injector.instanceOf[ControllerComponents], failing,
          app.injector.instanceOf[modules.BlockingExecutionContext])
        implicit val mat: Materializer = app.materializer
        val result = call(controller.saveUser(),
          FakeRequest(POST, "/users/save")
            .withSession(Secured.UserName -> admin.email, Secured.CurrentContestId -> "10")
            .withFormUrlEncodedBody(form(0, "new@example.com", 10, "jury"): _*)
            .withCSRFToken)
        status(result) must throwA[IllegalArgumentException](message = "something else")
      }
    }
  }

  "a stored membership role that is not a contest role" should {
    "grant no root and leave the membership inactive" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val eve = member("eve@example.com", 20L -> "jury")
        try {
          RoleAllowlistMigration.insertRaw(eve.getId, 10, "jury,root")
          val inSession = Secured.sessionUser(eve.email, Some("10")).get
          val in10 = User.findInContest(eve.getId, 10).get
          implicit val mat: Materializer = app.materializer
          val page = call(controller.users(Some(10L)),
            FakeRequest(GET, "/users/10").withSession(Secured.UserName -> eve.email, Secured.CurrentContestId -> "10"))
          (inSession.hasRole(User.ROOT_ROLE) must beFalse) and
            (inSession.contestId === Some(20L)) and
            (in10.hasRole(User.ROOT_ROLE) must beFalse) and
            (in10.contestRoles === Set.empty[String]) and
            (UserContestJdbc.find(eve.getId, 10).map(_.active) === Some(false)) and
            (status(page) === FORBIDDEN)
        } finally RoleAllowlistMigration.restore()
      }
    }
  }
}
