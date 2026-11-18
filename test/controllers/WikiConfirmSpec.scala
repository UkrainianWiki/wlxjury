package controllers

import controllers.CsrfTestApp.{token => csrfToken, withCsrfApp}
import db.scalikejdbc.{AccountChangeJdbc, AccountTokenJdbc, PlayTestDb, User, UserContest, UserContestJdbc}
import org.apache.pekko.stream.Materializer
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test._
import scalikejdbc._
import services.{AccountNotices, UserService}

import scala.concurrent.Future

/** Adding by an unverified wiki account (accounts plan A-3, decision A2: confirm any account): the
  * add row given a wiki account and no email that names accounts whose wiki account nobody
  * verified adds nothing; it shows those accounts by name only, none preselected, and the admin
  * picks one and ticks "This is the person I mean". The server requires both, re-checks that the
  * wiki account still names the account, then adds it with the search's add by id (the email
  * path's rule), logged on `security.account` and in the change log with the comment that makes
  * the owner's notice. A placeholder (no password, no email) takes the same confirmation and never
  * joins unconfirmed; an import only counts such rows. Signed-out users, organizers, jurors,
  * inactive members and other contests' admins get nothing; the POST needs the CSRF token.
  */
class WikiConfirmSpec extends PlaySpecification with PlayTestDb with AccountTestSupport {

  sequential

  private def session(user: User, contestId: Option[Long]): Seq[(String, String)] =
    Seq(Secured.UserName -> user.email) ++ contestId.map(Secured.CurrentContestId -> _.toString)

  /** An account with the name, email (empty: none) and wiki account, a member of the contest if given. */
  private def person(name: String, email: String, contestId: Option[Long], role: String = "jury",
      verified: Boolean = true, password: Option[String] = Some("secret"), wiki: Option[String] = None,
      root: Boolean = false): User =
    User.create(User(name, email, None, if (root) Set(User.ROOT_ROLE) else Set(role), password.map(hash), contestId,
      wikiAccount = wiki, isRoot = root, emailVerifiedAt = if (verified) Some(java.time.ZonedDateTime.now) else None))

  /** The add row: a wiki account, no email. */
  private def addRow(by: User, contestId: Long, wiki: String, role: String = "jury", send: Boolean = false,
      name: String = "Typed Name")(implicit app: Application): Future[Result] =
    route(app, FakeRequest(POST, "/users/save")
      .withSession(session(by, Some(contestId)): _*)
      .withFormUrlEncodedBody(Seq("id" -> "0", "contest" -> contestId.toString, "fullname" -> name,
        "wikiAccount" -> wiki, "email" -> "", "roles" -> role) ++
        (if (send) Seq("sendInvitation" -> "true") else Nil): _*).withCSRFToken).get

  private def confirm(by: User, contestId: Long, wiki: String, accountId: Option[Long], confirmed: Boolean = true,
      role: String = "jury", tell: Boolean = false, current: Option[Long] = None)(implicit app: Application): Future[Result] =
    route(app, FakeRequest(POST, s"/admin/users/add-wiki-confirmed?contestId=$contestId")
      .withSession(session(by, current.orElse(Some(contestId))): _*)
      .withFormUrlEncodedBody(Seq("wikiAccount" -> wiki, "role" -> role) ++
        accountId.map("accountId" -> _.toString) ++ (if (confirmed) Seq("confirmed" -> "true") else Nil) ++
        (if (tell) Seq("tellOwner" -> "true") else Nil): _*).withCSRFToken).get

  private def changes(account: User): Seq[(String, Option[String], Option[String])] =
    AccountChangeJdbc.findByUser(account.getId, None, 20).map(c => (c.field, c.newValue, c.comment))

  "the add row given an unverified wiki account [A-27]" should {

    "add nothing yet and show the matching accounts by name only, none preselected" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        val first = person("Olena First", "olena@example.org", Some(20), wiki = Some("Same Wiki"))
        val second = person("Olena Second", "second@example.org", None, verified = false, wiki = Some("Same Wiki"))

        // "Send an invitation email" ticked in the add row: chosen for another recipient, so unticked here
        val (page, lines) = accountLog {
          val r = addRow(admin, 10, "User:Same_wiki", send = true, name = "Olena <b>Typed</b>"); status(r); r
        }
        status(page) === OK
        val html = contentAsString(page)
        // the wiki account as MediaWiki writes it: the first letter upper case, spaces
        html must contain("Confirm the account with the wiki account Same wiki")
        html must contain("Olena First")
        html must contain("Olena Second")
        html must contain("This is the person I mean")
        html must contain("""action="/admin/users/add-wiki-confirmed?contestId=10"""")
        // by name only: no address, not even masked
        html must not(contain("olena@example.org"))
        html must not(contain("o***@example.org"))
        html must not(contain("s***@example.org"))
        // none preselected, nor the box ticked; no mail ticked
        """<input[^>]*type="radio"[^>]*checked""".r.findFirstIn(html) must beNone
        """id="wiki-confirm-check"[^>]*checked""".r.findFirstIn(html) must beNone
        """id="wiki-confirm-tell"[^>]*checked""".r.findFirstIn(html) must beNone
        """id="wiki-confirm-send"[^>]*checked""".r.findFirstIn(html) must beNone
        // the name typed in the add row, escaped
        html must contain("You typed: Olena &lt;b&gt;Typed&lt;/b&gt;")
        html must not(contain("<b>Typed</b>"))

        // nothing written
        UserContestJdbc.find(first.getId, 10) must beNone
        UserContestJdbc.findByUser(second.getId) must beEmpty
        AccountTokenJdbc.findByUser(second.getId) must beEmpty
        changes(first) must beEmpty
        changes(second) must beEmpty
        User.findAll().size === 3
        lines.exists(_.startsWith(s"add_refused reason=wiki_account_unverified accountId=${first.getId}")) must beTrue
        lines must contain(s"wiki_confirm_page byAccountId=${admin.getId} contestId=10 matches=2")
      }
    }

    "require the pick and the box on the server, then add the account with the email path's rule" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        val first = person("Olena First", "olena@example.org", Some(20), wiki = Some("Same Wiki"))
        val second = person("Olena Second", "second@example.org", None, verified = false, wiki = Some("Same Wiki"))

        // the box unticked, or no account picked: the page again, nothing added
        val (unticked, shownAgain) = accountLog { val r = confirm(admin, 10, "Same Wiki", first.id, confirmed = false); status(r); r }
        status(unticked) === BAD_REQUEST
        shownAgain must contain(s"wiki_confirm_page byAccountId=${admin.getId} contestId=10 matches=2")
        contentAsString(unticked) must contain("Nothing was added: pick an account and tick")
        // the account picked stays picked
        s"""value="${first.getId}" id="wiki-confirm-${first.getId}" required\\s+checked""".r
          .findFirstIn(contentAsString(unticked)) must beSome
        status(confirm(admin, 10, "Same Wiki", None)) === BAD_REQUEST
        UserContestJdbc.find(first.getId, 10) must beNone

        // confirmed: a verified account with a password joins, its owner told in the tool
        val (done, lines) = accountLog { val r = confirm(admin, 10, "User:Same Wiki", first.id); status(r); r }
        status(done) === SEE_OTHER
        redirectLocation(done) must beSome(routes.UserController.users(Some(10)).url)
        val text = flash(done).get("users-added").getOrElse("")
        text must contain("Existing accounts added to the contest: 1")
        // its address as the search shows it, read before the add: masked outside the admin's contests
        text must contain("The account you confirmed for the wiki account Same Wiki: Olena First, o***@example.org.")
        text must not(contain("olena@example.org"))
        text must contain("Its owner will see a notice in the tool when they sign in.")
        UserContestJdbc.find(first.getId, 10) must beSome(UserContest(first.getId, 10, "jury"))
        changes(first) === Seq((AccountChangeJdbc.Role, Some("jury"), Some(UserService.WikiConfirmedComment)))
        lines must contain(s"add_confirmed_unverified_wiki accountId=${first.getId} byAccountId=${admin.getId} " +
          "contestId=10 role=jury matches=2 placeholder=false outcome=added verified=false")
        // never the text typed, never an address
        lines.exists(l => l.contains("Same") || l.contains("@")) must beFalse
        AccountNotices.pending(first.getId, 0L).map(n => (n.contestId, n.role, n.invited)) === Seq((Some(10L), "jury", false))

        // the other account: an unverified email with a password waits for an invitation
        val invited = confirm(admin, 10, "Same Wiki", second.id, role = "organizer")
        status(invited) === SEE_OTHER
        flash(invited).get("users-added") must beSome(contain("Invited (role given once they accept): 1."))
        UserContestJdbc.findByUser(second.getId) must beEmpty
        AccountTokenJdbc.findByUser(second.getId).map(t => (t.purpose, t.role)) ===
          Seq((AccountTokenJdbc.Invitation, Some("organizer")))
        AccountNotices.pending(second.getId, 0L).map(n => (n.role, n.invited)) === Seq(("organizer", true))
      }
    }

    "route a placeholder through the confirmation, never joining it unconfirmed" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        // no password, no email, in another contest: this admin may not share its link
        val placeholder = person("Placeholder Person", "", Some(20), "jury", verified = false, password = None,
          wiki = Some("Placeholder Wiki"))

        val page = addRow(admin, 10, "Placeholder Wiki")
        status(page) === OK
        contentAsString(page) must contain("Placeholder Person")
        UserContestJdbc.find(placeholder.getId, 10) must beNone

        // an import only counts it, and links the users page
        val imported = route(app, FakeRequest(POST, "/admin/importusers?contestId=10")
          .withSession(session(admin, Some(10)): _*)
          .withFormUrlEncodedBody("userstoimport" -> "User:Placeholder_Wiki\na#b", "role" -> "jury")
          .withCSRFToken).get
        status(imported) === SEE_OTHER
        flash(imported).get("users-added") must beSome(contain("Not added yet, as the wiki account is on an account"))
        flash(imported).get(UserController.WikiConfirmFlash) must beSome("1")
        UserContestJdbc.find(placeholder.getId, 10) must beNone
        val importPage = route(app, FakeRequest(GET, "/admin/importusers?contestId=10")
          .withSession(session(admin, Some(10)): _*)
          .withFlash(flash(imported).data.toSeq: _*)).get
        contentAsString(importPage) must contain("Confirm them on the users page")

        // confirmed: it joins (no password: nobody else can sign in to it), and the page says who can
        // give it a sign-in link
        val (done, lines) = accountLog { val r = confirm(admin, 10, "Placeholder Wiki", placeholder.id); status(r); r }
        status(done) === SEE_OTHER
        val text = flash(done).get("users-added").getOrElse("")
        text must contain("Existing accounts added to the contest: 1")
        text must contain("Placeholder Person, no email")
        text must contain("the person can sign in only with a link that root creates")
        UserContestJdbc.find(placeholder.getId, 10) must beSome(UserContest(placeholder.getId, 10, "jury"))
        lines must contain(s"add_confirmed_unverified_wiki accountId=${placeholder.getId} byAccountId=${admin.getId} " +
          "contestId=10 role=jury matches=1 placeholder=true outcome=added verified=false")
      }
    }

    "say nothing about root's link to root, and keep a role without access as before" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val root = person("Root", "root@example.com", None, root = true)
        val placeholder = person("Root Placeholder", "", Some(20), "jury", verified = false, password = None,
          wiki = Some("Root Placeholder Wiki"))
        val done = confirm(root, 10, "Root Placeholder Wiki", placeholder.id)
        status(done) === SEE_OTHER
        flash(done).get("users-added") must beSome(not(contain("root creates")))
        UserContestJdbc.find(placeholder.getId, 10) must beSome(UserContest(placeholder.getId, 10, "jury"))

        // board gives no access: it joins directly, no confirmation, as before A-3
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        val board = person("Board Member", "board@example.org", None, verified = false, wiki = Some("Board Wiki"))
        val r = addRow(admin, 10, "Board Wiki", "board")
        status(r) === SEE_OTHER
        UserContestJdbc.find(board.getId, 10) must beSome(UserContest(board.getId, 10, "board"))
      }
    }

    "add in the contest named by the URL, never switching the session's; without one, the session's" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        UserContestJdbc.createMembership(admin.getId, 20, "admin")
        val target = person("Target", "target@example.org", None, wiki = Some("Target Wiki"))
        val other = person("Other", "other@example.org", None, wiki = Some("Other Wiki"))

        // the session's contest is 20, the URL's 10: added to 10, the session's contest stays
        val r = confirm(admin, 10, "Target Wiki", target.id, current = Some(20))
        status(r) === SEE_OTHER
        UserContestJdbc.findByUser(target.getId).map(_.contestId) === Seq(10L)
        session(r).get(Secured.CurrentContestId).forall(_ == "20") must beTrue

        // no contest in the URL: the session's
        val bare = route(app, FakeRequest(POST, "/admin/users/add-wiki-confirmed")
          .withSession(session(admin, Some(20)): _*)
          .withFormUrlEncodedBody("wikiAccount" -> "Other Wiki", "accountId" -> other.getId.toString,
            "role" -> "jury", "confirmed" -> "true").withCSRFToken).get
        status(bare) === SEE_OTHER
        redirectLocation(bare) must beSome(routes.UserController.users(Some(20)).url)
        UserContestJdbc.findByUser(other.getId).map(_.contestId) === Seq(20L)
      }
    }

    "list only the accounts that aren't root, for an admin, where a root account shares the wiki account" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10)
        // the root account first by id: the add row's match, yet never shown to an admin
        val rootAccount = person("Root Holder", "root@example.com", None, root = true, wiki = Some("Shared Wiki"))
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        val plain = person("Plain Holder", "plain@example.org", None, wiki = Some("Shared Wiki"))
        val (page, lines) = accountLog { val r = addRow(admin, 10, "Shared Wiki"); status(r); r }
        status(page) === OK
        val html = contentAsString(page)
        html must contain("Plain Holder")
        html must not(contain("Root Holder"))
        html must not(contain(s"""value="${rootAccount.getId}""""))
        lines must contain(s"wiki_confirm_page byAccountId=${admin.getId} contestId=10 matches=1")
        status(confirm(admin, 10, "Shared Wiki", rootAccount.id)) === NOT_FOUND
        status(confirm(admin, 10, "Shared Wiki", plain.id)) === SEE_OTHER
        UserContestJdbc.findByUser(rootAccount.getId) must beEmpty
      }
    }

    "mail the invitation only with Send an invitation email ticked on the page" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10)
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        val quiet = person("Quiet", "quiet@example.org", None, verified = false, wiki = Some("Quiet Wiki"))
        val mailed = person("Mailed", "mailed@example.org", None, verified = false, wiki = Some("Mailed Wiki"))
        val mail = new RecordingMail
        def post(wiki: String, account: User, send: Boolean) =
          call(userController(mail).confirmWikiAccount(Some(10)),
            FakeRequest(POST, "/admin/users/add-wiki-confirmed?contestId=10")
              .withSession(session(admin, Some(10)): _*)
              .withFormUrlEncodedBody(Seq("wikiAccount" -> wiki, "accountId" -> account.getId.toString,
                "role" -> "jury", "confirmed" -> "true") ++ (if (send) Seq("sendInvitation" -> "true") else Nil): _*)
              .withCSRFToken)

        val (notMailed, lines) = accountLog { val r = post("Quiet Wiki", quiet, send = false); status(r); r }
        status(notMailed) === SEE_OTHER
        mail.sent must beEmpty
        AccountTokenJdbc.findByUser(quiet.getId).map(t => (t.purpose, t.role)) ===
          Seq((AccountTokenJdbc.Invitation, Some("jury")))
        lines must contain(s"add_confirmed_unverified_wiki accountId=${quiet.getId} byAccountId=${admin.getId} " +
          "contestId=10 role=jury matches=1 placeholder=false outcome=invited verified=false")

        status(post("Mailed Wiki", mailed, send = true)) === SEE_OTHER
        mail.recipients === Seq("mailed@example.org")
        mail.sent.exists(_._4.contains("/invitation/")) must beTrue
      }
    }

    "log an account with an email but no password, which joins, as added" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        val noPassword = person("No Password", "nopass@example.org", None, verified = false, password = None,
          wiki = Some("No Password Wiki"))
        val (r, lines) = accountLog { val r = confirm(admin, 10, "No Password Wiki", noPassword.id); status(r); r }
        status(r) === SEE_OTHER
        UserContestJdbc.find(noPassword.getId, 10) must beSome(UserContest(noPassword.getId, 10, "jury"))
        lines must contain(s"add_confirmed_unverified_wiki accountId=${noPassword.getId} byAccountId=${admin.getId} " +
          "contestId=10 role=jury matches=1 placeholder=false outcome=added verified=false")
        // no mail ticked: no invitation to set its password is made
        AccountTokenJdbc.findByUser(noPassword.getId) must beEmpty
      }
    }

    "confirm after a name longer than the confirmation keeps, typed in the add row" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10)
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        val target = person("Target", "target@example.org", None, wiki = Some("Long Name Wiki"))
        val page = addRow(admin, 10, "Long Name Wiki", name = "N" * 300)
        status(page) === OK
        val kept = """name="typedName" value="([^"]*)"""".r.findFirstMatchIn(contentAsString(page)).map(_.group(1))
        kept must beSome("N" * 255)
        val r = route(app, FakeRequest(POST, "/admin/users/add-wiki-confirmed?contestId=10")
          .withSession(session(admin, Some(10)): _*)
          .withFormUrlEncodedBody("wikiAccount" -> "Long Name Wiki", "accountId" -> target.getId.toString,
            "role" -> "jury", "typedName" -> kept.get, "confirmed" -> "true").withCSRFToken).get
        status(r) === SEE_OTHER
        UserContestJdbc.find(target.getId, 10) must beSome(UserContest(target.getId, 10, "jury"))
      }
    }

    "refuse a pick the wiki account doesn't name, a deleted or root account, and a role that isn't one" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        val named = person("Named", "named@example.org", None, wiki = Some("Named Wiki"))
        val other = person("Other", "other@example.org", None, wiki = Some("Other Wiki"))

        // a forged id: an account the wiki account doesn't name
        status(confirm(admin, 10, "Named Wiki", other.id)) === NOT_FOUND
        status(confirm(admin, 10, "Named Wiki", Some(999999L))) === NOT_FOUND
        UserContestJdbc.findByUser(other.getId) must beEmpty

        // its wiki account changed meanwhile: no longer one it names
        User.setWikiAccount(named.getId, Some("Renamed Wiki"))
        status(confirm(admin, 10, "Named Wiki", named.id)) === NOT_FOUND
        UserContestJdbc.findByUser(named.getId) must beEmpty

        // deleted (retired)
        val gone = person("Gone", "gone@example.org", None, wiki = Some("Gone Wiki"))
        DB.autoCommit(implicit s => sql"UPDATE users SET deleted_at = NOW() WHERE id = ${gone.getId}".update.apply())
        status(confirm(admin, 10, "Gone Wiki", gone.id)) === NOT_FOUND
        UserContestJdbc.findByUser(gone.getId) must beEmpty

        // a root account, for an admin (L1): never shown, never added; the add row says add by email
        val rootAccount = person("Root Holder", "root@example.com", None, root = true, wiki = Some("Root Wiki"))
        status(confirm(admin, 10, "Root Wiki", rootAccount.id)) === NOT_FOUND
        val row = addRow(admin, 10, "Root Wiki")
        status(row) === SEE_OTHER
        flash(row).get("users-added") must beSome(contain("Add them by email."))
        UserContestJdbc.findByUser(rootAccount.getId) must beEmpty

        // not a role, not a wiki account
        val target = person("Target", "target@example.org", None, wiki = Some("Target Wiki"))
        status(confirm(admin, 10, "Target Wiki", target.id, role = "root")) === BAD_REQUEST
        status(confirm(admin, 10, "a|b", target.id)) === BAD_REQUEST
        UserContestJdbc.findByUser(target.getId) must beEmpty
        changes(target) must beEmpty
      }
    }

    "give signed-out users, organizers, jurors, inactive members and other contests' admins nothing" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val target = person("Target", "target@example.org", None, wiki = Some("Target Wiki"))
        val signedOut = route(app, FakeRequest(POST, "/admin/users/add-wiki-confirmed?contestId=10")
          .withFormUrlEncodedBody("wikiAccount" -> "Target Wiki", "accountId" -> target.getId.toString,
            "role" -> "jury", "confirmed" -> "true")).get
        status(signedOut) === SEE_OTHER
        redirectLocation(signedOut) must beSome(startWith("/auth"))
        for (role <- Seq("organizer", "jury", "no", "staff")) {
          val user = person(role, s"$role@example.com", Some(10), role)
          status(confirm(user, 10, "Target Wiki", target.id)) === FORBIDDEN
          // nor the add row's page: an organizer's or a juror's save adds nobody
          status(addRow(user, 10, "Target Wiki")) !== OK
        }
        val otherAdmin = person("Other Admin", "admin20@example.com", Some(20), "admin")
        for (current <- Seq(20L, 10L))
          status(confirm(otherAdmin, 10, "Target Wiki", target.id, current = Some(current))) === FORBIDDEN
        UserContestJdbc.findByUser(target.getId) must beEmpty
        changes(target) must beEmpty
      }
    }

    "make the notice's comment one the edit form refuses" in {
      for (typed <- Seq(UserService.WikiConfirmedComment, " Wiki account unverified,  confirmed by the ADMIN "))
        UserController.editExtrasForm.bind(Map("comment" -> typed)).hasErrors must beTrue
      UserController.editExtrasForm.bind(Map("comment" -> "wiki account confirmed")).hasErrors must beFalse
    }

    "need the CSRF token, through the router" in {
      withCsrfApp { app =>
        implicit val a: Application = app
        val root = account("root@example.com", None, "no", root = true, verified = true)
        val contest = createContests(30).head
        val target = person("Target", "target@example.org", None, wiki = Some("Target Wiki"))
        val sessionToken = csrfToken(app)
        def request(token: Option[String]) = FakeRequest(POST, s"/admin/users/add-wiki-confirmed?contestId=${contest.getId}")
          .withHeaders(COOKIE -> "PLAY_SESSION=x", ACCEPT -> "text/html")
          .withSession(Secured.UserName -> root.email, "csrfToken" -> sessionToken)
          .withFormUrlEncodedBody(Seq("wikiAccount" -> "Target Wiki", "accountId" -> target.getId.toString,
            "role" -> "jury", "confirmed" -> "true") ++ token.map("csrfToken" -> _): _*)
        status(route(app, request(None)).get) === FORBIDDEN
        UserContestJdbc.findByUser(target.getId) must beEmpty
        status(route(app, request(Some(sessionToken))).get) === SEE_OTHER
        UserContestJdbc.findByUser(target.getId).map(_.contestId) === Seq(contest.getId)
      }
    }
  }
}
