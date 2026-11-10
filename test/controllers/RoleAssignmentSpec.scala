package controllers

import db.RoleAllowlistMigration
import db.scalikejdbc.{PlayTestDb, User, UserContestJdbc}
import org.apache.pekko.stream.Materializer
import org.jsoup.Jsoup
import org.specs2.mock.Mockito
import play.api.Application
import play.api.i18n.{Lang, MessagesImpl}
import play.api.mvc.{ControllerComponents, EssentialAction, Result}
import play.api.test.CSRFTokenHelper._
import play.api.test._
import scalikejdbc._
import services.{SMTPOrWikiMail, UserService}

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

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

  /** The edit page's Root field as a browser sends it: the checkbox's value if ticked, then the
    * hidden "false" after it.
    */
  private val rootTicked = Seq("root" -> "true", "root" -> "false")
  private val rootUnticked = Seq("root" -> "false")

  private def page(as: User, action: EssentialAction, path: String)(implicit app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(action, FakeRequest(GET, path)
      .withSession(Secured.UserName -> as.email, Secured.CurrentContestId -> "10").withCSRFToken)
  }

  /** Another root's change of the account, as committed meanwhile (root is written only through
    * User.setRootLocked in the app).
    */
  private def setRootRaw(id: Long, root: Boolean): Unit =
    DB.autoCommit(implicit s => sql"UPDATE users SET is_root = $root WHERE id = $id".update.apply())

  private def editPage(as: User, id: Long, contestId: Long)(implicit app: Application): String =
    contentAsString(page(as, controller.editUser(id, Some(contestId)), s"/users/edit/$id?contestId=$contestId"))

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

  "an admin's /users/save [A-08]" should {
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

  "an organizer's or a juror's /users/save [S-10]" should {
    "leave their own role as it is" in {
      testDbApp { implicit app =>
        createContests(10)
        val org = member("org@example.com", 10L -> "organizer")
        val jury = member("jury@example.com", 10L -> "jury")
        // their own profile is saved (303) with the role field ignored: only an admin's form
        // sets a role, so neither becomes admin
        (status(save(org, 10, form(org.getId, org.email, 10, "admin"): _*)),
         status(save(jury, 10, form(jury.getId, jury.email, 10, "admin"): _*))) === ((SEE_OTHER, SEE_OTHER)) and
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

  "root's /users/save [R-03]" should {
    "make a member root with the Root field (ticked), writing no contest role" in {
      testDbApp { implicit app =>
        createContests(10)
        val boss = root("boss@example.com")
        val bob = member("bob@example.com", 10L -> "jury")
        (status(save(boss, 10, form(bob.getId, bob.email, 10, "jury") ++ rootTicked: _*)) === SEE_OTHER) and
          (User.findById(bob.getId).map(_.isRoot) === Some(true)) and
          (UserContestJdbc.findByUser(bob.getId).map(_.role) === Seq("jury"))
      }
    }

    "keep a root account's root when changing its contest role (ticked, or no Root field)" in {
      testDbApp { implicit app =>
        createContests(10)
        val boss = root("boss@example.com")
        val chief = root("chief@example.com")
        UserContestJdbc.createMembership(chief.getId, 10, "jury")
        (status(save(boss, 10, form(chief.getId, chief.email, 10, "organizer") ++ rootTicked: _*)) === SEE_OTHER) and
          (User.findById(chief.getId).map(_.isRoot) === Some(true)) and
          (UserContestJdbc.find(chief.getId, 10).map(_.role) === Some("organizer")) and
          (status(save(boss, 10, form(chief.getId, chief.email, 10, "admin"): _*)) === SEE_OTHER) and
          (User.findById(chief.getId).map(_.isRoot) === Some(true)) and
          (UserContestJdbc.find(chief.getId, 10).map(_.role) === Some("admin"))
      }
    }

    "take root away with the Root field unticked, leaving the contest role" in {
      testDbApp { implicit app =>
        createContests(10)
        val boss = root("boss@example.com")
        val chief = root("chief@example.com")
        UserContestJdbc.createMembership(chief.getId, 10, "jury")
        (status(save(boss, 10, form(chief.getId, chief.email, 10, "jury") ++ rootUnticked: _*)) === SEE_OTHER) and
          (User.findById(chief.getId).map(_.isRoot) === Some(false)) and
          (UserContestJdbc.find(chief.getId, 10).map(_.role) === Some("jury")) and
          (User.findById(boss.getId).map(_.isRoot) === Some(true))
      }
    }

    "not take their own root away (a 400 that writes nothing)" in {
      testDbApp { implicit app =>
        createContests(10)
        val boss = root("boss@example.com")
        UserContestJdbc.createMembership(boss.getId, 10, "jury")
        val before = state()
        val result = save(boss, 10, form(boss.getId, boss.email, 10, "jury") ++ rootUnticked: _*)
        (status(result) === BAD_REQUEST) and (state() === before) and
          (Jsoup.parse(contentAsString(result)).select("#root_field .invalid-feedback").text ===
            "You can't change root for your own account. Another root can change it.")
      }
    }

    "make no new account root, root's add included: a 400 that creates nothing" in {
      testDbApp { implicit app =>
        createContests(10)
        val boss = root("boss@example.com")
        val withRole = Seq("id" -> "0", "fullname" -> "New", "email" -> "new@example.com", "contest" -> "10",
          "roles" -> "jury") ++ rootTicked
        val withoutRole = Seq("id" -> "0", "fullname" -> "New", "email" -> "new@example.com", "contest" -> "10",
          "root" -> "true")
        val before = state()
        (status(save(boss, 10, withRole: _*)) === BAD_REQUEST) and
          (status(save(boss, 10, withoutRole: _*)) === BAD_REQUEST) and
          (User.findByEmail("new@example.com") === Nil) and (state() === before)
      }
    }

    "make no existing account root from the add row: a 400 that adds no membership" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val boss = root("boss@example.com")
        val carol = member("carol@example.com", 20L -> "jury")
        val before = state()
        (status(save(boss, 10, form(0, carol.email, 10, "jury") ++ rootTicked: _*)) === BAD_REQUEST) and
          (state() === before) and (User.findById(carol.getId).map(_.isRoot) === Some(false))
      }
    }

    "not undo another root's change from a page loaded before it (only a toggle of rootWas is saved)" in {
      testDbApp { implicit app =>
        createContests(10)
        val boss = root("boss@example.com")
        val chief = root("chief@example.com")
        UserContestJdbc.createMembership(chief.getId, 10, "jury")
        val bob = member("bob@example.com", 10L -> "jury")
        // boss's page showed chief as root and bob as not; another root then changed both
        setRootRaw(chief.getId, root = false)
        setRootRaw(bob.getId, root = true)
        (status(save(boss, 10, form(chief.getId, chief.email, 10, "jury") ++ rootTicked :+ ("rootWas" -> "true"): _*)) ===
          SEE_OTHER) and
          (status(save(boss, 10, form(bob.getId, bob.email, 10, "jury") ++ rootUnticked :+ ("rootWas" -> "false"): _*)) ===
            SEE_OTHER) and
          (User.findById(chief.getId).map(_.isRoot) === Some(false)) and
          (User.findById(bob.getId).map(_.isRoot) === Some(true)) and
          // a real toggle of the shown state is saved
          (status(save(boss, 10, form(bob.getId, bob.email, 10, "jury") ++ rootUnticked :+ ("rootWas" -> "true"): _*)) ===
            SEE_OTHER) and
          (User.findById(bob.getId).map(_.isRoot) === Some(false))
      }
    }

    "keep the submitted rootWas on a page shown again, so that a resubmit doesn't undo another root's change" in {
      testDbApp { implicit app =>
        createContests(10)
        val boss = root("boss@example.com")
        val chief = root("chief@example.com")
        UserContestJdbc.createMembership(chief.getId, 10, "jury")
        // boss's page showed chief as root; another root then took it away; boss's save is refused
        setRootRaw(chief.getId, root = false)
        val result = save(boss, 10, form(chief.getId, chief.email, 10, "jury") ++
          Seq("wikiAccount" -> "Bad|Name", "root" -> "true", "root" -> "false", "rootWas" -> "true"): _*)
        val page = Jsoup.parse(contentAsString(result))
        (status(result) === BAD_REQUEST) and
          (page.select("input[type=hidden][name=rootWas]").attr("value") === "true") and
          (page.select("input#root[type=checkbox]").hasAttr("checked") must beTrue) and
          (User.findById(chief.getId).map(_.isRoot) === Some(false))
      }
    }

    "refuse root and an email change in one save, mailing nothing" in {
      testDbApp { implicit app =>
        createContests(10)
        val boss = root("boss@example.com")
        // an invitee who has not set a password: their new email would get an invitation
        val invitee = User.create(User("invitee@example.com", "invitee@example.com", None, Set.empty, None))
        UserContestJdbc.createMembership(invitee.getId, 10, "jury")
        val fields = Seq("id" -> invitee.getId.toString, "fullname" -> invitee.fullname, "email" -> "other@example.com",
          "roles" -> "jury", "contest" -> "10") ++ rootTicked
        val result = save(boss, 10, fields: _*)
        (status(result) === BAD_REQUEST) and
          (contentAsString(result) must contain("Change root and the email in separate saves.")) and
          (User.findById(invitee.getId).map(u => (u.isRoot, u.email)) === Some((false, "invitee@example.com"))) and
          (db.scalikejdbc.AccountTokenJdbc.findByUser(invitee.getId) must beEmpty)
      }
    }

    "refuse every value that is not a contest role, root included (root is no role)" in {
      testDbApp { implicit app =>
        createContests(10)
        val boss = root("boss@example.com")
        val bob = member("bob@example.com", 10L -> "jury")
        refused(boss, 10, form(bob.getId, bob.email, 10, _)) and
          (User.findById(bob.getId).map(_.isRoot) === Some(false))
      }
    }
  }

  "an admin's Root field [A-08]" should {
    "change no account's root: a denial (403) that writes nothing, a 400 for a new account" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10)
        val admin = member("admin@example.com", 10L -> "admin")
        val bob = member("bob@example.com", 10L -> "jury")
        val boss = root("boss@example.com")
        UserContestJdbc.createMembership(boss.getId, 10, "jury")
        val before = state()
        val denied = Seq(
          save(admin, 10, form(bob.getId, bob.email, 10, "jury") ++ rootTicked: _*),
          save(admin, 10, form(bob.getId, bob.email, 10, "jury") :+ ("root" -> "true"): _*),
          save(admin, 10, form(bob.getId, bob.email, 10, "jury") ++ rootTicked :+ ("rootWas" -> "false"): _*),
          save(admin, 10, form(boss.getId, boss.email, 10, "jury") ++ rootUnticked: _*),
          save(admin, 10, form(admin.getId, admin.email, 10, "admin") ++ rootTicked: _*),
          // the same gate whatever the body's encoding: the bound form's value
          call(controller.saveUser(), FakeRequest(POST, "/users/save")
            .withSession(Secured.UserName -> admin.email, Secured.CurrentContestId -> "10")
            .withJsonBody(play.api.libs.json.Json.toJson((form(bob.getId, bob.email, 10, "jury") :+ ("root" -> "true")).toMap))
            .withCSRFToken))
        val statuses = denied.map(status(_))
        (statuses.filterNot(_ == FORBIDDEN) === Nil) and
          (status(save(admin, 10, form(0, "new@example.com", 10, "jury") ++ rootTicked: _*)) === BAD_REQUEST) and
          (state() === before) and (User.findByEmail("new@example.com") === Nil)
      }
    }
  }

  "User.setRootLocked (root written under the rows' locks)" should {
    "leave a root when two roots take each other's root (the second one's is refused)" in {
      testDbApp { implicit app =>
        val boss = root("boss@example.com")
        val chief = root("chief@example.com")
        // both forms checked; chief's write commits first, then boss's finds boss no longer root
        (User.setRootLocked(chief.getId, boss.getId, root = false) === Right(true)) and
          (User.setRootLocked(boss.getId, chief.getId, root = false) === Left("root.changed.meanwhile")) and
          (User.findById(chief.getId).map(_.isRoot) === Some(true)) and
          (User.findById(boss.getId).map(_.isRoot) === Some(false))
      }
    }

    "refuse an actor no longer root, and root's own change" in {
      testDbApp { implicit app =>
        val boss = root("boss@example.com")
        val chief = root("chief@example.com")
        val third = root("third@example.com")
        setRootRaw(boss.getId, root = false)
        (User.setRootLocked(boss.getId, chief.getId, root = false) === Left("root.changed.meanwhile")) and
          (User.setRootLocked(chief.getId, chief.getId, root = false) === Left("root.changed.meanwhile")) and
          (User.findById(chief.getId).map(_.isRoot) === Some(true)) and
          (User.findById(third.getId).map(_.isRoot) === Some(true))
      }
    }

    "grant root only where L2 holds when written: not after a password set unverified the email" in {
      testDbApp { implicit app =>
        val boss = root("boss@example.com")
        val verified = member("verified@example.com")
        val invitee = User.create(User("invitee@example.com", "invitee@example.com", None, Set.empty, None))
        val unverified = member("set@example.com")
        // as an admin's set password does, after the form's check
        DB.autoCommit(implicit s => sql"UPDATE users SET email_verified_at = NULL WHERE id = ${unverified.getId}".update.apply())
        (User.setRootLocked(boss.getId, unverified.getId, root = true) === Left("root.needs.verified.email")) and
          (User.findById(unverified.getId).map(_.isRoot) === Some(false)) and
          (User.setRootLocked(boss.getId, verified.getId, root = true) === Right(true)) and
          (User.setRootLocked(boss.getId, invitee.getId, root = true) === Right(true)) and
          // already as asked: nothing written
          (User.setRootLocked(boss.getId, verified.getId, root = true) === Right(false))
      }
    }

    "grant no root to a deleted account" in {
      testDbApp { implicit app =>
        val boss = root("boss@example.com")
        val gone = member("gone@example.com")
        DB.autoCommit(implicit s => sql"UPDATE users SET deleted_at = NOW() WHERE id = ${gone.getId}".update.apply())
        (User.setRootLocked(boss.getId, gone.getId, root = true) === Left("root.changed.meanwhile")) and
          (state()._2.find(_._1 == gone.getId) === Some(gone.getId -> false))
      }
    }
  }

  "the edit and users pages" should {
    "show root's Root checkbox with the account, not as a role, and not to an admin" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = member("admin@example.com", 10L -> "admin")
        val boss = root("boss@example.com")
        val chief = root("chief@example.com")
        UserContestJdbc.createMembership(chief.getId, 10, "jury")
        val bob = member("bob@example.com", 10L -> "jury")
        val asRoot = Jsoup.parse(editPage(boss, chief.getId, 10))
        val box = asRoot.select("input#root[type=checkbox]")
        val ownPage = Jsoup.parse(editPage(boss, boss.getId, 10))
        val asAdmin = Jsoup.parse(editPage(admin, bob.getId, 10))
        (box.size === 1) and (box.attr("name") === "root") and (box.attr("value") === "true") and
          (box.hasAttr("checked") must beTrue) and
          (asRoot.select("label[for=root]").text === "Root (every contest)") and
          // the unticked box's value, after it (the first value counts: before it, every save
          // would take root away)
          (asRoot.select("form:has(#root) [name=root]").asScala.map(i => i.attr("type") -> i.attr("value")).toList ===
            List("checkbox" -> "true", "hidden" -> "false")) and
          // the state the page shows, for a toggle only
          (asRoot.select("input[type=hidden][name=rootWas]").attr("value") === "true") and
          (asRoot.select("select#roles option[value=root]").size === 0) and
          (asRoot.select("select#roles option[selected]").attr("value") === "jury") and
          // one's own: shown, not submitted
          (ownPage.select("input#root[type=checkbox]").hasAttr("disabled") must beTrue) and
          (ownPage.select("input[type=hidden][name=root]").size === 0) and
          (asAdmin.select("[name=root]").size === 0) and
          (asAdmin.select("select#roles option[value=root]").size === 0)
      }
    }

    "describe the role select with the conflicts of interest help" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = member("admin@example.com", 10L -> "admin")
        val bob = member("bob@example.com", 10L -> "jury")
        val edit = Jsoup.parse(editPage(admin, bob.getId, 10))
        val users = Jsoup.parse(contentAsString(page(admin, controller.users(Some(10L)), "/admin/users?contestId=10")))
        val imports = Jsoup.parse(contentAsString(page(admin, controller.showImportUsers(Some(10L)),
          "/admin/importusers?contestId=10")))
        def described(doc: org.jsoup.nodes.Document, select: String) = {
          val ids = doc.select(select).attr("aria-describedby").split("\\s+").filter(_.nonEmpty).toSeq
          ids.flatMap(id => Option(doc.getElementById(id))).map(_.text)
        }
        val help = "Board, Audit committee and Staff give no access"
        (described(edit, "select#roles").exists(_.contains(help)) must beTrue) and
          (described(users, "select#roles").exists(_.contains(help)) must beTrue) and
          (described(imports, "select#import-role").exists(_.contains(help)) must beTrue)
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

  "saveUser for a new user [A-07]" should {
    "not report another IllegalArgumentException as a refused role" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = member("admin@example.com", 10L -> "admin")
        val failing = new UserService(mailer, app.configuration) {
          override def createNewUser(user: User, formUser: User)(implicit messages: play.api.i18n.Messages,
                                                                linkHost: services.LinkHost) =
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
          val inSession = Secured.sessionUser(eve.email, Some("10"), None, None).get
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
