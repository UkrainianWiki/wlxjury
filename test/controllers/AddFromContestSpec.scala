package controllers

import controllers.CsrfTestApp.{token => csrfToken, withCsrfApp}
import db.scalikejdbc.{AccountChangeJdbc, AccountTokenJdbc, ContestJuryJdbc, PlayTestDb, User, UserContest, UserContestJdbc}
import org.apache.pekko.stream.Materializer
import play.api.Application
import play.api.mvc.Result
import play.api.test._
import scalikejdbc._
import services.{AccountNotices, AddFromContest, UserService}

import scala.concurrent.Future

/** "Add people from another of your contests" on the users page ([[AddFromContest]], asked for on
  * 2026-11-18): the contest's admins pick one of their other contests (root: any), see its members
  * as the search shows accounts, none ticked, and add some, each as the search adds an account by
  * id, recorded with the source contest and with a notice in the tool for its owner. Organizers,
  * jurors, other contests' admins and signed-out users get nothing; a source the admin doesn't
  * administer, or an id that is not one of its members, is a 404.
  */
class AddFromContestSpec extends PlaySpecification with PlayTestDb with AccountTestSupport {

  sequential

  private def session(user: User, contestId: Option[Long]): Seq[(String, String)] =
    Seq(Secured.UserName -> user.email) ++ contestId.map(Secured.CurrentContestId -> _.toString)

  private def person(name: String, email: String, contestId: Option[Long], role: String = "jury",
      verified: Boolean = true, password: Option[String] = Some("secret"), wiki: Option[String] = None,
      wikiVerified: Boolean = false): User =
    User.create(User(name, email, None, Set(role), password.map(hash), contestId, wikiAccount = wiki,
      emailVerifiedAt = if (verified) Some(java.time.ZonedDateTime.now) else None,
      wikiAccountVerifiedAt = if (wikiVerified) Some(java.time.ZonedDateTime.now) else None))

  private def join(user: User, contestId: Long, role: String): User = {
    UserContestJdbc.createMembership(user.getId, contestId, role)
    user
  }

  private def page(by: User, contestId: Long, query: String = "")(implicit app: Application): Future[Result] =
    route(app, FakeRequest(GET, s"/admin/users/add-from?contestId=$contestId$query")
      .withSession(session(by, Some(contestId)): _*)).get

  private def add(by: User, contestId: Long, from: String, picks: Seq[(String, String)], send: Boolean = false)(
      implicit app: Application): Future[Result] =
    route(app, FakeRequest(POST, s"/admin/users/add-from?contestId=$contestId")
      .withSession(session(by, Some(contestId)): _*)
      .withFormUrlEncodedBody(Seq("from" -> from) ++ picks.flatMap { case (id, role) =>
        Seq("accountId" -> id, s"role-$id" -> role) } ++ (if (send) Seq("sendInvitation" -> "true") else Nil): _*)).get

  private def delete(user: User): Unit =
    DB.autoCommit(implicit s => sql"UPDATE users SET deleted_at = NOW() WHERE id = ${user.getId}".update.apply())

  /** The rows of the member list: each one's id and whether its box is disabled. */
  private def rows(html: String): Seq[(Long, Boolean)] =
    """<input class="form-check-input add-from-pick" type="checkbox" name="accountId" value="(\d+)"\s+id="add-from-\d+"\s*(disabled)?""".r
      .findAllMatchIn(html).map(m => m.group(1).toLong -> (m.group(2) != null)).toSeq

  "the member list of another of the admin's contests [A-30]" should {

    "offer the admin's other contests, the newest first, and list a source's members, none ticked" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20, 30, 40)
        val admin = join(join(person("Admin", "admin@example.com", Some(10), "admin"), 20, "admin"), 40, "admin")
        // an organizer of 30, not its admin: not a source
        join(admin, 30, "organizer")
        val anna = person("Anna Juror", "anna@example.org", Some(20))
        val bohdan = person("Bohdan Org", "bohdan@example.org", Some(20), "organizer", wiki = Some("BohdanWiki"),
          wikiVerified = true)
        val member = join(person("Already Member", "member@example.org", Some(10)), 20, "jury")
        val wikiOnly = person("Wiki Only", "", Some(20), password = None, wiki = Some("WikiOnly"))
        val gone = person("Gone Person", "gone@example.org", Some(20))
        delete(gone)
        val rootAccount = join(account("root@example.com", None, "no", root = true, verified = true), 20, "jury")
        // a member of 20 who is also in a contest the admin doesn't administer: shown, address in full
        val elsewhere = join(person("Elsewhere", "elsewhere@example.org", Some(30)), 20, "jury")

        val picker = page(admin, 10)
        status(picker) === OK
        header(CACHE_CONTROL, picker) must beSome("no-store")
        val pickerHtml = contentAsString(picker)
        val options = """<option value="(\d+)"""".r.findAllMatchIn(pickerHtml).map(_.group(1).toLong).toSeq
        options === Seq(40L, 20L)
        pickerHtml must contain("contest40 2040 in")
        pickerHtml must not(contain("id=\"add-from-form\""))

        val (listed, listLines) = accountLog {
          val r = page(admin, 10, "&from=20")
          status(r)
          r
        }
        status(listed) === OK
        listLines === Seq(s"add_from_list byAccountId=${admin.getId} contestId=10 sourceContestId=20 rows=6")
        val html = contentAsString(listed)
        // a row whose role here would be Admin is bold
        html must beMatching(s"""(?s).*<tr class="add-from-row add-from-blocked fw-bold">\\s*<td>\\s*<input[^>]*value="${admin.getId}".*""")
        rows(html).toMap === Map(anna.getId -> false, bohdan.getId -> false, member.getId -> true, wikiOnly.getId -> false,
          elsewhere.getId -> false, admin.getId -> true)
        // nothing ticked: no checked box, "Send an invitation email" included
        val form = html.substring(html.indexOf("id=\"add-from-form\""), html.indexOf("</form>", html.indexOf("id=\"add-from-form\"")))
        form must not(contain("checked"))
        html must contain("""id="add-from-send"""")
        // each with the role they have there, as their select's default
        html must contain(s"""name="role-${bohdan.getId}"""")
        html must beMatching(s"""(?s).*id="add-from-role-${bohdan.getId}".*?<option value="organizer" selected>.*""")
        html must contain("Already in this contest (Juror).")
        // known only by a wiki account nobody verified: tickable (the maintainer, 2026-11-18)
        html must contain("WikiOnly")
        html must contain("anna@example.org").and(contain("elsewhere@example.org"))
        html must contain("BohdanWiki")
        // never a deleted account, nor a root account for an admin
        html must not(contain("Gone Person")).and(not(contain("root@example.com")))
        rows(html).map(_._1) must not(contain(rootAccount.getId))

        // the role filter
        val jurors = contentAsString(page(admin, 10, "&from=20&role=jury"))
        rows(jurors).map(_._1).toSet === Set(anna.getId, member.getId, wikiOnly.getId, elsewhere.getId)
        jurors must contain("aria-current=\"page\"")
        rows(contentAsString(page(admin, 10, "&from=20&role=organizer"))).map(_._1) === Seq(bohdan.getId)
        // an unknown filter is none
        rows(contentAsString(page(admin, 10, "&from=20&role=root"))).size === 6
      }
    }

    "let root pick any contest and see its root accounts; link from the users page only with a source" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val root = account("root@example.com", None, "no", root = true, verified = true)
        val other = join(account("root2@example.com", None, "no", root = true, verified = true), 20, "jury")
        val anna = person("Anna", "anna@example.org", Some(20))
        val html = contentAsString(page(root, 10, "&from=20"))
        rows(html).map(_._1).toSet === Set(other.getId, anna.getId)

        val users = contentAsString(route(app, FakeRequest(GET, "/admin/users?contestId=10")
          .withSession(session(root, Some(10)): _*)).get)
        users must contain("""href="/admin/users/add-from?contestId=10"""")
        // an admin of this contest only: no link
        val lone = person("Lone Admin", "lone@example.com", Some(10), "admin")
        val loneUsers = contentAsString(route(app, FakeRequest(GET, "/admin/users?contestId=10")
          .withSession(session(lone, Some(10)): _*)).get)
        loneUsers must not(contain("/admin/users/add-from"))
        contentAsString(page(lone, 10)) must contain("You are an admin of no other contest.")
      }
    }
  }

  "adding people from another of the admin's contests [A-30]" should {

    "join a verified account and a wiki-only one, invite an unverified one, unmailed, logged with the source, each with a notice" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = join(person("Admin Ten", "admin@example.com", Some(10), "admin"), 20, "admin")
        val verified = person("Verified", "verified@example.org", Some(20), "organizer")
        val unverified = person("Unverified", "unverified@example.org", Some(20), verified = false)
        val member = join(person("Member", "member@example.org", Some(10)), 20, "jury")
        // known only by a wiki account nobody verified: no password, no email (it joins, and needs a
        // link to sign in), and with a password (no invitation can reach it: skipped)
        val wikiOnly = person("Wiki Only", "", Some(20), password = None, wiki = Some("WikiOnly"))
        val wikiPassword = person("Wiki Password", "", Some(20), verified = false, wiki = Some("WikiPassword"))

        val (r, lines) = accountLog {
          val r = add(admin, 10, "20", Seq(verified.getId -> "organizer", unverified.getId -> "jury",
            member.getId -> "admin", wikiOnly.getId -> "jury", wikiPassword.getId -> "jury")
            .map { case (id, role) => id.toString -> role })
          status(r)
          r
        }
        status(r) === SEE_OTHER
        redirectLocation(r) must beSome(routes.UserController.users(Some(10)).url)
        val message = flash(r).get("users-added").get
        message must contain("Joined the contest: 2. Invited (role given once they accept): 1. Skipped: 2.")
        message must contain("already in this contest: 1").and(contain("no email for an invitation"))
        message must contain("Joined without an email or a password: 1.")
        // the admin of both contests may create its link
        message must not(contain("a link from root"))
        message must contain("Invitations not emailed, as chosen: 1.")
        message must contain("Each person added will see a notice in the tool when they next sign in.")

        UserContestJdbc.find(verified.getId, 10) must beSome(UserContest(verified.getId, 10, "organizer"))
        UserContestJdbc.find(unverified.getId, 10) must beNone
        AccountTokenJdbc.findByUser(unverified.getId).filter(_.live).map(t => (t.contestId, t.role, t.unsent)) ===
          Seq((Some(10L), Some("jury"), true))
        // the member keeps their role; the wiki-only account joins, the one with a password doesn't
        UserContestJdbc.find(member.getId, 10) must beSome(UserContest(member.getId, 10, "jury"))
        UserContestJdbc.find(wikiOnly.getId, 10) must beSome(UserContest(wikiOnly.getId, 10, "jury"))
        UserContestJdbc.find(wikiPassword.getId, 10) must beNone
        AccountTokenJdbc.findByUser(wikiPassword.getId) must beEmpty

        // the change log, with the source contest
        AccountChangeJdbc.findByUser(verified.getId, Some(10), 10).map(c => (c.field, c.newValue, c.comment)) ===
          Seq((AccountChangeJdbc.Role, Some("organizer"), Some("from contest #20")))
        AccountChangeJdbc.findByUser(unverified.getId, Some(10), 10).map(c => (c.field, c.comment)) ===
          Seq((AccountChangeJdbc.Invitation, Some("from contest #20")))
        AccountChangeJdbc.findByUser(member.getId, Some(10), 10) must beEmpty
        AccountChangeJdbc.findByUser(wikiOnly.getId, Some(10), 10).map(c => (c.field, c.comment)) ===
          Seq((AccountChangeJdbc.Role, Some("from contest #20")))

        // the notice in the tool, as the search's add gives
        AccountNotices.pending(verified.getId, 0L).map(n => (n.contestName, n.role, n.byName, n.invited)) ===
          Seq(("contest10 2010 in Andorra", "organizer", "Admin Ten", false))
        AccountNotices.pending(unverified.getId, 0L).map(n => (n.role, n.invited)) === Seq(("jury", true))
        AccountNotices.pending(wikiOnly.getId, 0L).map(n => (n.role, n.invited)) === Seq(("jury", false))

        lines must contain(s"add_from_contest byAccountId=${admin.getId} contestId=10 sourceContestId=20 requested=5 " +
          "joined=2 invited=1 skipped=2 sendInvitation=false needLink=1 admins=0 already_member=1 no_email=1")
      }
    }

    "mail nothing unless asked, and mail the invitation from the tool's sender when ticked" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = join(person("Admin", "admin@example.com", Some(10), "admin"), 20, "admin")
        val first = person("First", "first@example.org", Some(20), verified = false)
        val second = person("Second", "second@example.org", Some(20), verified = false)
        val joined = person("Joined", "joined@example.org", Some(20))
        val mail = new RecordingMail
        val service = userService(mail)
        implicit val messages: play.api.i18n.Messages = messagesOf
        val contest = ContestJuryJdbc.findById(10).get
        val source = ContestJuryJdbc.findById(20).get

        val unmailed = AddFromContest.add(service, admin, source, contest, Seq(first.getId -> "jury", joined.getId -> "jury"),
          sendInvitation = false)
        (unmailed.joined, unmailed.invited, unmailed.notMailed) === ((1, 1, 1))
        mail.sent must beEmpty

        val mailed = AddFromContest.add(service, admin, source, contest, Seq(second.getId -> "jury"), sendInvitation = true)
        (mailed.invited, mailed.notMailed, mailed.mailFailed) === ((1, 0, 0))
        mail.recipients === Seq("second@example.org")
        mail.sent.head._1.email === service.systemSender.email
        // never a mail to the owner besides: their notice is in the tool
        AccountNotices.pending(second.getId, 0L).map(_.invited) === Seq(true)
      }
    }

    "never make a notice from a comment typed in the edit form that reads as the source's" in {
      testDbApp { implicit app =>
        for (typed <- Seq("from contest #20", " From  Contest #7 ", "FROM CONTEST #123"))
          UserController.editExtrasForm.bind(Map("comment" -> typed)).hasErrors must beTrue
        UserController.editExtrasForm.bind(Map("comment" -> "from contest 20, by hand")).hasErrors must beFalse
        createContests(10)
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        val owner = person("Owner", "owner@example.org", Some(10))
        DB.autoCommit { implicit s =>
          sql"""INSERT INTO account_change (user_id, actor_id, contest_id, field, new_value, comment)
                VALUES (${owner.getId}, ${admin.getId}, 10, ${AccountChangeJdbc.Role}, 'jury', 'From contest #20')""".update.apply()
          sql"""INSERT INTO account_change (user_id, actor_id, contest_id, field, new_value, comment)
                VALUES (${owner.getId}, ${admin.getId}, 10, ${AccountChangeJdbc.Role}, 'jury', 'from contest #20 extra')""".update.apply()
          val trailing = "from contest #20\n"
          sql"""INSERT INTO account_change (user_id, actor_id, contest_id, field, new_value, comment)
                VALUES (${owner.getId}, ${admin.getId}, 10, ${AccountChangeJdbc.Role}, 'jury', $trailing)""".update.apply()
        }
        AccountNotices.pending(owner.getId, 0L) must beEmpty
      }
    }

    "say when nobody was ticked, and refuse a role that isn't one or an id that isn't a number" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = join(person("Admin", "admin@example.com", Some(10), "admin"), 20, "admin")
        val anna = person("Anna", "anna@example.org", Some(20))
        val none = add(admin, 10, "20", Nil)
        status(none) === SEE_OTHER
        redirectLocation(none) must beSome(routes.AddFromContestController.page(Some(10), Some(20)).url)
        flash(none).get("add-from") must beSome("users.add.from.none.ticked")

        status(add(admin, 10, "20", Seq(anna.getId.toString -> "root"))) === BAD_REQUEST
        status(add(admin, 10, "20", Seq(anna.getId.toString -> ""))) === BAD_REQUEST
        status(add(admin, 10, "20", Seq("x" -> "jury"))) === BAD_REQUEST
        status(add(admin, 10, "", Seq(anna.getId.toString -> "jury"))) === BAD_REQUEST
        UserContestJdbc.find(anna.getId, 10) must beNone
      }
    }
  }

  "who may add people from another contest [A-30]" should {

    "give organizers, jurors, inactive members and other contests' admins nothing" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val target = person("Target", "target@example.org", Some(20))
        val users = Seq("organizer", "jury", "no", "staff").map { role =>
          join(person(role, s"$role@example.com", Some(10), role), 20, "admin")
        }
        for (user <- users) {
          status(page(user, 10, "&from=20")) === FORBIDDEN
          status(add(user, 10, "20", Seq(target.getId.toString -> "jury"))) === FORBIDDEN
        }
        // an admin of the source only, for this contest: from its own session's contest or this one's
        val otherAdmin = person("Other Admin", "admin20@example.com", Some(20), "admin")
        for (current <- Seq(20L, 10L)) {
          val r = route(app, FakeRequest(POST, "/admin/users/add-from?contestId=10")
            .withSession(session(otherAdmin, Some(current)): _*)
            .withFormUrlEncodedBody("from" -> "20", "accountId" -> target.getId.toString,
              s"role-${target.getId}" -> "jury")).get
          status(r) === FORBIDDEN
        }
        UserContestJdbc.find(target.getId, 10) must beNone
      }
    }

    "refuse a source the admin doesn't administer, this contest itself, and a forged id, adding nobody" in {
      testDbApp { implicit app =>
        createContests(10, 20, 30, 40)
        val admin = join(join(person("Admin", "admin@example.com", Some(10), "admin"), 20, "admin"), 30, "organizer")
        val inSource = person("In Source", "in20@example.org", Some(20))
        val in30 = person("In Thirty", "in30@example.org", Some(30))
        val in40 = person("In Forty", "in40@example.org", Some(40))

        for (from <- Seq("30", "40", "10", "999")) {
          status(page(admin, 10, s"&from=$from")) === NOT_FOUND
          status(add(admin, 10, from, Seq(in30.getId.toString -> "jury"))) === NOT_FOUND
        }
        // a member of another contest, with a member of the source: nobody is added
        val (forged, lines) = accountLog {
          val r = add(admin, 10, "20", Seq(inSource.getId.toString -> "jury", in40.getId.toString -> "jury"))
          status(r)
          r
        }
        status(forged) === NOT_FOUND
        lines === Seq(s"add_from_contest_refused byAccountId=${admin.getId} contestId=10 sourceContestId=20 " +
          "requested=2 reason=not_member")
        status(add(admin, 10, "20", Seq("999999" -> "jury"))) === NOT_FOUND
        // a deleted member of the source, and a root account (only root sees root): 404 too
        val gone = person("Gone", "gone@example.org", Some(20))
        delete(gone)
        status(add(admin, 10, "20", Seq(gone.getId.toString -> "jury"))) === NOT_FOUND
        val rootAccount = join(account("root@example.com", None, "no", root = true, verified = true), 20, "jury")
        status(add(admin, 10, "20", Seq(rootAccount.getId.toString -> "jury"))) === NOT_FOUND
        Seq(inSource, in30, in40, gone, rootAccount).flatMap(u => UserContestJdbc.find(u.getId, 10)) must beEmpty
        AccountTokenJdbc.findByUser(inSource.getId) must beEmpty

        // root may take people from any contest
        val root = account("root2@example.com", None, "no", root = true, verified = true)
        val byRoot = add(root, 10, "40", Seq(in40.getId.toString -> "jury"))
        status(byRoot) === SEE_OTHER
        UserContestJdbc.find(in40.getId, 10) must beSome(UserContest(in40.getId, 10, "jury"))
      }
    }

    "send signed-out users to sign in" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val target = person("Target", "target@example.org", Some(20))
        val get = route(app, FakeRequest(GET, "/admin/users/add-from?contestId=10&from=20")).get
        status(get) === SEE_OTHER
        redirectLocation(get) must beSome(startWith("/auth"))
        val post = route(app, FakeRequest(POST, "/admin/users/add-from?contestId=10")
          .withFormUrlEncodedBody("from" -> "20", "accountId" -> target.getId.toString,
            s"role-${target.getId}" -> "jury")).get
        status(post) === SEE_OTHER
        redirectLocation(post) must beSome(startWith("/auth"))
        UserContestJdbc.find(target.getId, 10) must beNone
      }
    }

    "need the CSRF token, through the router" in {
      withCsrfApp { app =>
        implicit val a: Application = app
        val contests = createContests(50, 60)
        val admin = join(person("Csrf Admin", "csrf-admin@example.com", Some(50), "admin"), 60, "admin")
        val target = person("Csrf Target", "csrf-target@example.org", Some(60))
        val sessionToken = csrfToken(app)
        def request(token: Option[String]) = FakeRequest(POST, "/admin/users/add-from?contestId=50")
          .withHeaders(COOKIE -> "PLAY_SESSION=x", ACCEPT -> "text/html")
          .withSession(Secured.UserName -> admin.email, Secured.CurrentContestId -> "50", "csrfToken" -> sessionToken)
          .withFormUrlEncodedBody(Seq("from" -> "60", "accountId" -> target.getId.toString,
            s"role-${target.getId}" -> "jury") ++ token.map("csrfToken" -> _): _*)
        status(route(app, request(None)).get) === FORBIDDEN
        UserContestJdbc.find(target.getId, 50) must beNone
        status(route(app, request(Some(sessionToken))).get) === SEE_OTHER
        UserContestJdbc.find(target.getId, 50) must beSome(UserContest(target.getId, 50, "jury"))
        contests must haveSize(2)
      }
    }
  }

  "the source contest in the change log, and the people the add leaves out [A-30]" should {

    "show the source contest by name with a link on Account changes, the edit page and the owner's notice" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = join(person("Admin", "admin@example.com", Some(10), "admin"), 20, "admin")
        val anna = person("Anna", "anna@example.org", Some(20))
        status(add(admin, 10, "20", Seq(anna.getId.toString -> "jury"))) === SEE_OTHER
        val shown = """<a href="/admin/users?contestId=20" class="account-change-from">from contest20 2020 in Andorra (#20)</a>"""
        val changes = route(app, FakeRequest(GET, "/admin/account-changes?contestId=10")
          .withSession(session(admin, Some(10)): _*)).get
        status(changes) === OK
        contentAsString(changes) must contain(shown)
        val edit = route(app, FakeRequest(GET, s"/users/edit/${anna.getId}?contestId=10")
          .withSession(session(admin, Some(10)): _*)).get
        status(edit) === OK
        contentAsString(edit) must contain(shown)
        AccountNotices.pending(anna.getId, 0L).map(_.fromContest) === Seq(Some("contest20 2020 in Andorra (#20)"))
        val own = route(app, FakeRequest(GET, s"/users/edit/${anna.getId}").withSession(session(anna, Some(20)): _*)).get
        contentAsString(own) must contain("You were added from contest20 2020 in Andorra (#20).")
        // the contest gone: the comment as written
        DB.autoCommit(implicit s => sql"UPDATE account_change SET comment = 'from contest #999' WHERE user_id = ${anna.getId}".update.apply())
        val after = contentAsString(route(app, FakeRequest(GET, "/admin/account-changes?contestId=10")
          .withSession(session(admin, Some(10)): _*)).get)
        after must contain("from contest #999").and(not(contain("/admin/users?contestId=999")))
      }
    }

    "mark a live invitee, skip them on a second add, and leave their invitation as it was" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = join(person("Admin", "admin@example.com", Some(10), "admin"), 20, "admin")
        val unverified = person("Unverified", "unverified@example.org", Some(20), verified = false)
        flash(add(admin, 10, "20", Seq(unverified.getId.toString -> "jury"))).get("users-added") must
          beSome(contain("Invited (role given once they accept): 1."))
        val token = AccountTokenJdbc.findByUser(unverified.getId).filter(_.live)
        token must haveSize(1)
        rows(contentAsString(page(admin, 10, "&from=20"))).toMap.get(unverified.getId) must beSome(true)
        contentAsString(page(admin, 10, "&from=20")) must contain("Invited already")
        val again = add(admin, 10, "20", Seq(unverified.getId.toString -> "organizer"))
        flash(again).get("users-added") must beSome(contain("Skipped: 1.").and(contain("invited already: 1")))
        AccountTokenJdbc.findByUser(unverified.getId).filter(_.live).map(t => (t.id, t.role)) ===
          token.map(t => (t.id, Some("jury")))
      }
    }

    "count the Admins given, and tell when only root can share a link" in {
      testDbApp { implicit app =>
        createContests(10, 20, 30)
        val admin = join(person("Admin", "admin@example.com", Some(10), "admin"), 20, "admin")
        val anna = person("Anna", "anna@example.org", Some(20))
        // no email, no password, and also in a contest this admin doesn't administer
        val wikiOnly = join(person("Wiki Only", "", Some(20), password = None, wiki = Some("WikiOnly")), 30, "jury")
        val r = add(admin, 10, "20", Seq(anna.getId.toString -> "admin", wikiOnly.getId.toString -> "jury"))
        val message = flash(r).get("users-added").get
        message must contain("Joined the contest: 2.").and(contain("Given the Admin role: 1."))
        message must contain("Joined without an email or a password: 1.")
        message must contain("Of those, you may not create a link for 1: they sign in with a link from root.")
        UserContestJdbc.find(anna.getId, 10) must beSome(UserContest(anna.getId, 10, "admin"))
      }
    }

    "mail the contest's greeting to those who join at once only when the box is ticked" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = join(person("Admin", "admin@example.com", Some(10), "admin"), 20, "admin")
        val first = person("First", "first@example.org", Some(20))
        val second = person("Second", "second@example.org", Some(20))
        val mail = new RecordingMail
        val service = userService(mail)
        implicit val messages: play.api.i18n.Messages = messagesOf
        val contest = ContestJuryJdbc.findById(10).get
        val source = ContestJuryJdbc.findById(20).get
        contest.greeting.use must beTrue
        AddFromContest.add(service, admin, source, contest, Seq(first.getId -> "jury"), sendInvitation = false).joined === 1
        mail.sent must beEmpty
        AddFromContest.add(service, admin, source, contest, Seq(second.getId -> "jury"), sendInvitation = true).joined === 1
        mail.recipients === Seq("second@example.org")
      }
    }

    "refuse more than the most people one add takes, and a source where the actor is no longer an admin" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = join(person("Admin", "admin@example.com", Some(10), "admin"), 20, "admin")
        val anna = person("Anna", "anna@example.org", Some(20))
        val many = (1L to (AddFromContest.MaxPeople + 1).toLong).map(id => id.toString -> "jury")
        status(add(admin, 10, "20", many)) === BAD_REQUEST
        // made a juror of the source: no longer a source
        DB.autoCommit(implicit s =>
          sql"UPDATE user_contest SET role = 'jury' WHERE user_id = ${admin.getId} AND contest_id = 20".update.apply())
        status(page(admin, 10, "&from=20")) === NOT_FOUND
        status(add(admin, 10, "20", Seq(anna.getId.toString -> "jury"))) === NOT_FOUND
        UserContestJdbc.find(anna.getId, 10) must beNone
      }
    }
  }

  /** A service whose add runs `before` (with the account's id) and then the real add, or hands back
    * what `after` makes of its result: a race, or a failure after the add, simulated.
    */
  private def serviceWith(mail: RecordingMail, before: Long => Unit = _ => (),
      after: UserService.SearchAdded => UserService.SearchAdded = identity)(implicit app: Application): UserService =
    new UserService(mail, app.configuration) {
      override protected def mailInBackground(send: => Unit): Unit = send
      override def addAccountToContest(creator: User, accountId: Long, contest: org.intracer.wmua.ContestJury,
          role: String, sendInvitation: Boolean, tellOwner: Boolean, comment: String, keepLiveInvitation: Boolean)(
          implicit messages: play.api.i18n.Messages, linkHost: services.LinkHost): UserService.SearchAdded = {
        before(accountId)
        after(super.addAccountToContest(creator, accountId, contest, role, sendInvitation, tellOwner, comment,
          keepLiveInvitation))
      }
    }

  "an add racing another add, or failing after it [A-30]" should {

    "keep a live invitation made after the check, under the account's row lock: untouched, no mail" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = join(person("Admin", "admin@example.com", Some(10), "admin"), 20, "admin")
        val unverified = person("Unverified", "unverified@example.org", Some(20), verified = false)
        val mail = new RecordingMail
        implicit val messages: play.api.i18n.Messages = messagesOf
        val contest = ContestJuryJdbc.findById(10).get
        val source = ContestJuryJdbc.findById(20).get
        // the other add's invitation, made between this add's check and its add
        val racing = serviceWith(mail, before = id =>
          { userService(new RecordingMail).invite(admin, User.findById(id).get, Some(contest), Some("jury"), mail = false); () })
        val outcome = AddFromContest.add(racing, admin, source, contest, Seq(unverified.getId -> "organizer"),
          sendInvitation = true)
        (outcome.joined, outcome.invited, outcome.skipped) === ((0, 0, Map(AddFromContest.Skip.Invited -> 1)))
        mail.sent must beEmpty
        val live = AccountTokenJdbc.findByUser(unverified.getId).filter(_.live)
        live.map(t => (t.role, t.unsent)) === Seq((Some("jury"), true))
        AccountChangeJdbc.findByUser(unverified.getId, Some(10), 10).map(_.comment) === Seq(None)

        // the service path with the flag: the same; without it (every other caller), as before
        val service = userService(mail)
        service.addAccountToContest(admin, unverified.getId, contest, "jury", sendInvitation = true,
          keepLiveInvitation = true) must beLike {
          case UserService.SearchAdded(UserService.AlreadyInvited(_), UserService.SearchNotice.NotNeeded) => ok
        }
        mail.sent must beEmpty
        AccountTokenJdbc.findByUser(unverified.getId).filter(_.live).map(_.id) === live.map(_.id)
        service.addAccountToContest(admin, unverified.getId, contest, "jury", sendInvitation = true) must beLike {
          case UserService.SearchAdded(UserService.Invited(_, UserService.Mail.Sent), _) => ok
        }
        mail.recipients === Seq("unverified@example.org")
        AccountTokenJdbc.findByUser(unverified.getId).filter(_.live).map(_.id) must not(beEqualTo(live.map(_.id)))
      }
    }

    "count a person the add joined when a read after it fails, log a reference, and still sum up" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = join(person("Admin", "admin@example.com", Some(10), "admin"), 20, "admin")
        val anna = person("Anna", "anna@example.org", Some(20))
        implicit val messages: play.api.i18n.Messages = messagesOf
        val contest = ContestJuryJdbc.findById(10).get
        val source = ContestJuryJdbc.findById(20).get
        // an account without an id: the read of its membership after the add fails
        val failing = serviceWith(new RecordingMail, after = r => r.copy(added = r.added match {
          case UserService.JoinedContest(u) => UserService.JoinedContest(u.copy(id = None))
          case other                        => other
        }))
        val (outcome, lines) = accountLog(AddFromContest.add(failing, admin, source, contest,
          Seq(anna.getId -> "jury"), sendInvitation = false))
        (outcome.joined, outcome.skippedCount, outcome.reference.nonEmpty) === ((1, 0, true))
        UserContestJdbc.find(anna.getId, 10) must beSome(UserContest(anna.getId, 10, "jury"))
        lines.exists(_.startsWith(s"add_from_contest_failed ref=${outcome.reference.get} accountId=${anna.getId} ")) must
          beTrue
        lines.exists(_.contains("step=summary")) must beTrue
        lines.exists(_.startsWith(s"add_from_contest byAccountId=${admin.getId} contestId=10 sourceContestId=20 " +
          "requested=1 joined=1 invited=0 skipped=0")) must beTrue
      }
    }
  }
}
