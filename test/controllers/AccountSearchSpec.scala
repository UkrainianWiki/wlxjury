package controllers

import controllers.CsrfTestApp.{token => csrfToken, withCsrfApp}
import db.scalikejdbc.{AccountChangeJdbc, AccountTokenJdbc, ContestJuryJdbc, PlayTestDb, SharedTestDb, User, UserContest,
  UserContestJdbc}
import org.apache.pekko.stream.Materializer
import play.api.Application
import play.api.libs.json.{JsArray, JsValue}
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test._
import scalikejdbc._
import services.UserService

import java.util.concurrent.CountDownLatch
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** The users page's "Find an existing account" and "Add to this contest" (accounts plan A-1, A1
  * decided by the maintainer 2026-11-16): root and the contest's admins find every non-deleted
  * account, by a part of its name or wiki account or its whole address (a part only within their
  * contests), with masked addresses outside their contests, and add one by id with the email
  * path's rule. Signed-out users, organizers, jurors and inactive members get nothing.
  */
class AccountSearchSpec extends PlaySpecification with PlayTestDb with AccountTestSupport {

  sequential

  private def session(user: User, contestId: Option[Long]): Seq[(String, String)] =
    Seq(Secured.UserName -> user.email) ++ contestId.map(Secured.CurrentContestId -> _.toString)

  /** An account with the name, email and wiki account, a member of the contest if given. */
  private def person(name: String, email: String, contestId: Option[Long], role: String = "jury",
      verified: Boolean = true, password: Option[String] = Some("secret"), wiki: Option[String] = None): User =
    User.create(User(name, email, None, Set(role), password.map(hash), contestId, wikiAccount = wiki,
      emailVerifiedAt = if (verified) Some(java.time.ZonedDateTime.now) else None))

  private def searchRequest(by: Option[User], contestId: Long, text: String, current: Option[Long] = None) =
    FakeRequest(POST, s"/admin/users/search?contestId=$contestId")
      .withHeaders("X-Requested-With" -> "XMLHttpRequest")
      .withSession(by.toSeq.flatMap(u => session(u, current.orElse(Some(contestId)))): _*)
      .withFormUrlEncodedBody("q" -> text)

  private def search(by: User, contestId: Long, text: String, current: Option[Long] = None)(implicit
      app: Application): Future[Result] =
    route(app, searchRequest(Some(by), contestId, text, current)).get

  private def results(r: Future[Result])(implicit app: Application): Seq[JsValue] = {
    implicit val mat: Materializer = app.materializer
    status(r) === OK
    (contentAsJson(r) \ "results").as[JsArray].value.toSeq
  }

  private def byId(found: Seq[JsValue], user: User): JsValue =
    found.find(r => (r \ "id").as[Long] == user.getId).getOrElse(sys.error(s"${user.getId} not found in $found"))

  private def ids(found: Seq[JsValue]): Seq[Long] = found.map(r => (r \ "id").as[Long])

  private def add(by: User, contestId: Long, accountId: String, role: String = "jury", send: Boolean = false,
      tell: Boolean = false)(implicit app: Application): Future[Result] =
    route(app, FakeRequest(POST, s"/admin/users/add-existing?contestId=$contestId")
      .withSession(session(by, Some(contestId)): _*)
      .withFormUrlEncodedBody(Seq("accountId" -> accountId, "role" -> role) ++
        (if (send) Seq("sendInvitation" -> "true") else Nil) ++ (if (tell) Seq("tellOwner" -> "true") else Nil): _*)).get

  private def delete(user: User): Unit =
    DB.autoCommit(implicit s => sql"UPDATE users SET deleted_at = NOW() WHERE id = ${user.getId}".update.apply())

  "the users page's search for existing accounts [A-22]" should {

    "find every account by a part of its name or wiki account, masked outside the admin's contests" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val admin = person("Admin Ten", "admin@example.com", Some(10), "admin")
        val member = person("Anna Member", "member@example.org", Some(10), wiki = Some("MemberWiki"))
        val outside = person("Anna Kovalenko", "anna.k@example.org", Some(20), "organizer")
        val byWiki = person("Someone Else", "wiki.only@example.org", Some(20), wiki = Some("AnnaWikiName"))
        val noEmail = person("Anna Without", "", None, password = None, wiki = Some("Without"))
        person("Bohdan", "bohdan@example.org", Some(20))

        val (r, lines) = accountLog {
          val r = search(admin, 10, "  aNNa ")
          status(r)
          r
        }
        header(CACHE_CONTROL, r) must beSome("no-store")
        val found = results(r)
        ids(found) === Seq(member, outside, byWiki, noEmail).map(_.getId)
        (contentAsJson(r) \ "more").as[Boolean] must beFalse

        val m = byId(found, member)
        (m \ "email").as[String] === "member@example.org"
        (m \ "emailFull").as[Boolean] must beTrue
        (m \ "member").as[String] === "Juror"
        (m \ "editUrl").as[String] === routes.UserController.editUser(member.getId, Some(10)).url
        (m \ "contests").as[JsArray].value.map(c => ((c \ "name").as[String], (c \ "role").as[String])) ===
          Seq(("contest10 2010 in country10", "Juror"))

        val o = byId(found, outside)
        (o \ "name").as[String] === "Anna Kovalenko"
        (o \ "email").as[String] === "a***@example.org"
        (o \ "emailFull").as[Boolean] must beFalse
        (o \ "member").asOpt[String] must beNone
        (o \ "editUrl").asOpt[String] must beNone
        // never its other contests, not even a count
        (o \ "contests").as[JsArray].value must beEmpty

        (byId(found, byWiki) \ "wikiAccount").as[String] === "AnnaWikiName"
        (byId(found, byWiki) \ "wikiVerified").as[Boolean] must beFalse
        (byId(found, noEmail) \ "email").asOpt[String] must beNone

        val body = contentAsString(r)
        body must not(contain("anna.k@")).and(not(contain("wiki.only@"))).and(not(contain("contest20")))
        body must not(contain("Bohdan"))

        // logged by ids only, never the text
        lines === Seq(s"account_search byAccountId=${admin.getId} contestId=10 results=4 length=4")
      }
    }

    "match an address outside the admin's contests only by the whole of it, a part within them" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = person("Admin Ten", "admin@example.com", Some(10), "admin")
        val member = person("Mykola", "mykola.member@example.org", Some(10))
        val outside = person("Petro", "petro.outside@example.org", Some(20))
        // an invitee of contest 10 who hasn't accepted yet: an unverified account with a password
        val invitee = person("Ivan", "ivan.invitee@example.org", Some(20), verified = false)
        implicit val messages: play.api.i18n.Messages = messagesOf
        userService(new RecordingMail).invite(admin, invitee, ContestJuryJdbc.findById(10), Some("jury"), mail = false)
        AccountTokenJdbc.findByUser(invitee.getId).count(_.live) === 1

        ids(results(search(admin, 10, "petro.outside@exam"))) must beEmpty
        ids(results(search(admin, 10, "outside"))) must beEmpty
        ids(results(search(admin, 10, "@example.org"))) === Seq(member, invitee).map(_.getId)

        val whole = results(search(admin, 10, " PETRO.Outside@example.org "))
        ids(whole) === Seq(outside.getId)
        (whole.head \ "email").as[String] === "p***@example.org"

        val part = results(search(admin, 10, "mykola.mem"))
        (part.head \ "email").as[String] === "mykola.member@example.org"
        val invited = results(search(admin, 10, "ivan.inv"))
        ids(invited) === Seq(invitee.getId)
        (invited.head \ "email").as[String] === "ivan.invitee@example.org"
        (invited.head \ "emailFull").as[Boolean] must beTrue
      }
    }

    "let root find by a part of any address, in full, with every contest" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val root = account("root@example.com", None, "no", root = true, verified = true)
        val outside = person("Petro", "petro.outside@example.org", Some(20), "organizer")
        val found = results(search(root, 10, "outside@exa"))
        ids(found) === Seq(outside.getId)
        (found.head \ "email").as[String] === "petro.outside@example.org"
        (found.head \ "contests").as[JsArray].value.map(c => (c \ "name").as[String]) === Seq("contest20 2020 in country20")
        (found.head \ "editUrl").as[String] === routes.UserController.editUser(outside.getId, Some(10)).url
      }
    }

    "match %, _ and a backslash literally" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        val percent = person("Ann 100% Sure", "p1@example.org", None)
        person("Ann 1000 Sure", "p2@example.org", None)
        val underscore = person("Ann a_b", "u1@example.org", None)
        person("Ann axb", "u2@example.org", None)
        val backslash = person("Ann c\\d", "b1@example.org", None)
        person("Ann cd", "b2@example.org", None)

        ids(results(search(admin, 10, "0% S"))) === Seq(percent.getId)
        ids(results(search(admin, 10, "a_b"))) === Seq(underscore.getId)
        ids(results(search(admin, 10, "c\\d"))) === Seq(backslash.getId)
        ids(results(search(admin, 10, "%%%"))) must beEmpty
        // the escape character itself (L2: '!', not a backslash)
        val bang = person("Ann x!y", "e1@example.org", None)
        person("Ann xy", "e2@example.org", None)
        ids(results(search(admin, 10, "x!y"))) === Seq(bang.getId)

        // the same under NO_BACKSLASH_ESCAPES, on one connection, its mode restored after
        DB.localTx { implicit s =>
          val mode = sql"SELECT @@SESSION.sql_mode".map(_.string(1)).single.apply().getOrElse("")
          sql"SET SESSION sql_mode = CONCAT(@@SESSION.sql_mode, ',NO_BACKSLASH_ESCAPES')".execute.apply()
          try {
            sql"SELECT @@SESSION.sql_mode".map(_.string(1)).single.apply().get must contain("NO_BACKSLASH_ESCAPES")
            User.searchAccounts("c\\d", None, 20).map(_._1.getId) === Seq(backslash.getId)
            User.searchAccounts("0% S", None, 20).map(_._1.getId) === Seq(percent.getId)
            User.searchAccounts("a_b", None, 20).map(_._1.getId) === Seq(underscore.getId)
            User.searchAccounts("x!y", None, 20).map(_._1.getId) === Seq(bang.getId)
          } finally sql"SET SESSION sql_mode = $mode".execute.apply()
        }
      }
    }

    "never show a root account to an admin, nor an address typed into a name or wiki account outside their contests" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        val rootAccount = account("anna.root@example.org", None, "no", root = true, verified = true)
        val root = account("root2@example.com", None, "no", root = true, verified = true)
        // L1: only root finds root, also by its whole address
        ids(results(search(admin, 10, "anna.root@example.org"))) must beEmpty
        ids(results(search(root, 10, "anna.root@example.org"))) === Seq(rootAccount.getId)

        // L3: an address typed into the name or wiki account of an account outside the admin's
        // contests: never matched by a part (it could be rebuilt a character at a time), found only
        // by the account's whole email, and then masked like the email
        val outside = person("Anna anna.secret@example.org", "x@example.org", Some(20), wiki = Some("Wiki hidden@example.net"))
        val outsideWiki = person("Bohdan", "z@example.org", Some(20), wiki = Some("Anna hidden2@example.net"))
        val inside = person("Anna open.member@example.org", "y@example.org", Some(10), wiki = Some("Wiki shown@example.net"))
        ids(results(search(admin, 10, "anna"))) === Seq(inside.getId)
        ids(results(search(admin, 10, "secret"))) must beEmpty
        ids(results(search(admin, 10, "hidden"))) must beEmpty
        val whole = results(search(admin, 10, "x@example.org"))
        ids(whole) === Seq(outside.getId)
        (whole.head \ "name").as[String] === "Anna a***@example.org"
        (whole.head \ "wikiAccount").as[String] === "Wiki h***@example.net"
        (results(search(admin, 10, "z@example.org")).head \ "wikiAccount").as[String] === "Anna h***@example.net"
        val found = results(search(admin, 10, "open.member"))
        (byId(found, inside) \ "name").as[String] === "Anna open.member@example.org"
        (byId(found, inside) \ "wikiAccount").as[String] === "Wiki shown@example.net"
        // root finds them by a part
        ids(results(search(root, 10, "secret"))) === Seq(outside.getId)
        ids(results(search(root, 10, "hidden2"))) === Seq(outsideWiki.getId)
      }
    }

    "never find a deleted account; show at most 20 and say more matched; search nothing under 3 characters" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10)
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        val gone = person("Deleted Person", "gone@example.org", None)
        delete(gone)
        ids(results(search(admin, 10, "deleted"))) must beEmpty
        ids(results(search(admin, 10, "gone@example.org"))) must beEmpty

        val many = (1 to 21).map(i => person(s"Bulk $i", s"bulk$i@example.org", None))
        val r = search(admin, 10, "bulk")
        ids(results(r)) === many.take(20).map(_.getId)
        (contentAsJson(r) \ "more").as[Boolean] must beTrue

        val (short, lines) = accountLog {
          val s = search(admin, 10, " bu ")
          status(s)
          s
        }
        results(short) must beEmpty
        (contentAsJson(short) \ "unsearchable").as[Boolean] must beTrue
        lines === Seq(s"account_search byAccountId=${admin.getId} contestId=10 results=0 length=2")
      }
    }

    "give signed-out users, organizers, jurors, inactive members and other contests' admins nothing" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        person("Anna Target", "target@example.org", Some(20))
        val organizer = person("Org", "org@example.com", Some(10), "organizer")
        val juror = person("Juror", "juror@example.com", Some(10))
        val inactive = person("Inactive", "inactive@example.com", Some(10), "no")
        val board = person("Board", "board@example.com", Some(10), "board")
        val otherAdmin = person("Other Admin", "admin20@example.com", Some(20), "admin")

        val signedOut = route(app, searchRequest(None, 10, "anna")).get
        status(signedOut) === UNAUTHORIZED
        contentAsString(signedOut) must not(contain("Anna"))
        // a form's POST without the script's header: the JSON 401 too, never a redirect with the text
        val plain = route(app, FakeRequest(POST, "/admin/users/search?contestId=10").withFormUrlEncodedBody("q" -> "anna")).get
        status(plain) === UNAUTHORIZED

        for (user <- Seq(organizer, juror, inactive, board)) {
          val r = search(user, 10, "anna")
          status(r) === FORBIDDEN
          (contentAsJson(r) \ "error").as[String] === "forbidden"
          contentAsString(r) must not(contain("Anna"))
        }
        // an admin of another contest, for this one: from its session's contest or this one's
        status(search(otherAdmin, 10, "anna", current = Some(20))) === FORBIDDEN
        status(search(otherAdmin, 10, "anna", current = Some(10))) === FORBIDDEN
      }
    }

    "need the CSRF token, through the router" in {
      withCsrfApp { app =>
        implicit val a: Application = app
        implicit val mat: Materializer = app.materializer
        val root = account("root@example.com", None, "no", root = true, verified = true)
        val contest = createContests(30).head
        val target = person("Anna Target", "target@example.org", None)
        val sessionToken = csrfToken(app)
        def request(header: Option[String]) = FakeRequest(POST, s"/admin/users/search?contestId=${contest.getId}")
          .withHeaders(Seq(COOKIE -> "PLAY_SESSION=x", "X-Requested-With" -> "XMLHttpRequest") ++
            header.map("Csrf-Token" -> _): _*)
          .withSession(Secured.UserName -> root.email, "csrfToken" -> sessionToken)
          .withFormUrlEncodedBody("q" -> "anna")
        val refused = route(app, request(None)).get
        status(refused) === FORBIDDEN
        (contentAsJson(refused) \ "error").as[String] === "csrf"
        val accepted = route(app, request(Some(sessionToken))).get
        status(accepted) === OK
        (contentAsJson(accepted) \ "results" \ 0 \ "id").as[Long] === target.getId

        val addRefused = route(app, FakeRequest(POST, s"/admin/users/add-existing?contestId=${contest.getId}")
          .withHeaders(COOKIE -> "PLAY_SESSION=x", ACCEPT -> "text/html")
          .withSession(Secured.UserName -> root.email, "csrfToken" -> sessionToken)
          .withFormUrlEncodedBody("accountId" -> target.getId.toString, "role" -> "jury")).get
        status(addRefused) === FORBIDDEN
        UserContestJdbc.findByUser(target.getId) must beEmpty
      }
    }

    "show its box on a contest's users page only" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10)
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        val page = route(app, FakeRequest(GET, "/admin/users?contestId=10").withSession(session(admin, Some(10)): _*)).get
        status(page) === OK
        val html = contentAsString(page)
        html must contain("id=\"account-search\"")
        html must contain(s"""data-search-url="${routes.UserController.searchAccounts(Some(10)).url}"""")
        html must contain("account-search.js")
        html must contain("""<form method="post" action="/admin/users/search?contestId=10"""")
        html must contain("""action="/admin/users/add-existing?contestId=10"""")
      }
    }
  }

  "Add to this contest, from the search [A-22]" should {

    "add a verified account from another contest by id, logged with the comment from search" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        val other = person("Other", "other@example.org", Some(20))
        val r = add(admin, 10, other.getId.toString, "organizer")
        status(r) === SEE_OTHER
        redirectLocation(r) must beSome(routes.UserController.users(Some(10)).url)
        flash(r).get("users-added") must beSome(contain("Existing accounts added to the contest: 1"))
        UserContestJdbc.find(other.getId, 10) must beSome(UserContest(other.getId, 10, "organizer"))
        AccountChangeJdbc.findByUser(other.getId, Some(10), 10).map(c => (c.field, c.newValue, c.comment)) ===
          Seq((AccountChangeJdbc.Role, Some("organizer"), Some(UserService.SearchComment)))
        // its other contest and account stay as they were
        UserContestJdbc.find(other.getId, 20) must beSome(UserContest(other.getId, 20, "jury"))
      }
    }

    "invite an unverified account with a password, mailed from the tool's sender only if asked" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        val unverified = person("Unverified", "unverified@example.org", None, verified = false)
        val mail = new RecordingMail
        implicit val messages: play.api.i18n.Messages = messagesOf
        val contest = ContestJuryJdbc.findById(10).get

        userService(mail).addAccountToContest(admin, unverified.getId, contest, "jury") must beLike {
          case UserService.SearchAdded(UserService.Invited(_, UserService.Mail.NotAsked), UserService.SearchNotice.NotSent) => ok
        }
        UserContestJdbc.find(unverified.getId, 10) must beNone
        AccountTokenJdbc.findByUser(unverified.getId).filter(_.live).map(t => (t.contestId, t.role, t.unsent)) ===
          Seq((Some(10L), Some("jury"), true))
        mail.sent must beEmpty
        AccountChangeJdbc.findByUser(unverified.getId, Some(10), 10).map(c => (c.field, c.comment)) ===
          Seq((AccountChangeJdbc.Invitation, Some(UserService.SearchComment)))

        val service = userService(mail)
        service.addAccountToContest(admin, unverified.getId, contest, "jury", sendInvitation = true) must beLike {
          case UserService.SearchAdded(UserService.Invited(_, UserService.Mail.Sent), UserService.SearchNotice.NotSent) => ok
        }
        mail.recipients === Seq("unverified@example.org")
        mail.sent.head._1.email === service.systemSender.email
        mail.sent.head._1.email must not(beEqualTo(admin.email))
      }
    }

    "never mail the owner unless asked, and always leave them a notice in the tool [M1]" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = person("Admin Ten", "admin@example.com", Some(10), "admin")
        val verified = person("Verified Elsewhere", "verified@example.org", Some(20))
        val unverified = person("Unverified", "unverified@example.org", None, verified = false)
        val noRole = person("No Role", "norole@example.org", None)
        val placeholder = person("No Address", "", Some(20), password = None, wiki = Some("PlaceholderWiki"))
        val mail = new RecordingMail
        implicit val messages: play.api.i18n.Messages = messagesOf
        val service = userService(mail)
        val contest = ContestJuryJdbc.findById(10).get

        // unticked: no mail, the notice in the tool, logged
        val (joined, lines) = accountLog(service.addAccountToContest(admin, verified.getId, contest, "jury"))
        joined.added must beAnInstanceOf[UserService.JoinedContest]
        joined.notice === UserService.SearchNotice.NotSent
        mail.sent must beEmpty
        lines === Seq(s"account_added_notice accountId=${verified.getId} byAccountId=${admin.getId} contestId=10 " +
          "outcome=not_sent inApp=true")
        services.AccountNotices.pending(verified.getId, 0L).map(n => (n.contestName, n.role, n.byName, n.invited)) ===
          Seq(("contest10 2010 in country10", "jury", "Admin Ten", false))

        // ticked: from the tool's sender; an invitation's sentence
        service.addAccountToContest(admin, unverified.getId, contest, "organizer", tellOwner = true).notice ===
          UserService.SearchNotice.Sent
        mail.sent.map(m => (m._1.email, m._2.email)) === Seq((service.systemSender.email, "unverified@example.org"))
        mail.sent.head._3 === "You were added to contest10"
        mail.sent.head._4 must contain("with the role Organizer").and(contain("once you accept the invitation"))
          .and(contain("Added by Admin Ten, an admin of the contest."))
        services.AccountNotices.pending(unverified.getId, 0L).map(n => (n.role, n.invited)) === Seq(("organizer", true))

        // a role without access, by an admin without a name: their own sentence and the fallback
        mail.sent.clear()
        val unnamed = person("", "unnamed@example.com", Some(10), "admin")
        service.addAccountToContest(unnamed, noRole.getId, contest, User.NO_ROLE, tellOwner = true).notice ===
          UserService.SearchNotice.Sent
        mail.sent.head._4 must contain("with the role No role")
          .and(contain("This role doesn't open the contest's pages for you."))
          .and(contain("Added by an admin of the contest."))

        // ticked without an email: nothing mailed; a member already: nothing at all
        mail.sent.clear()
        service.addAccountToContest(admin, placeholder.getId, contest, "jury", tellOwner = true).notice ===
          UserService.SearchNotice.NoEmail
        service.addAccountToContest(admin, verified.getId, contest, "jury", tellOwner = true).notice ===
          UserService.SearchNotice.NotNeeded
        mail.sent must beEmpty

        // the page says what happened, and "by email" only when it was
        val other = person("Page", "page@example.org", Some(20))
        val inApp = flash(add(admin, 10, other.getId.toString)).get("users-added").get
        inApp must contain("Its owner will see a notice in the tool when they sign in.")
        inApp must not(contain("by email"))
        val told = person("Page Told", "told@example.org", Some(20))
        flash(add(admin, 10, told.getId.toString, tell = true)).get("users-added") must
          beSome(contain("and was told by email"))
      }
    }

    "show the owner the notice: a bar after sign-in, the list on their profile, dismissed in this browser [M1]" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val admin = person("Admin Ten", "admin@example.com", Some(10), "admin")
        val owner = person("Owner", "owner@example.org", Some(20))
        implicit val messages: play.api.i18n.Messages = messagesOf
        userService(new RecordingMail).addAccountToContest(admin, owner.getId, ContestJuryJdbc.findById(10).get, "jury")
        val changeId = services.AccountNotices.pending(owner.getId, 0L).head.changeId

        // sign-in sets the bar's flag
        val signIn = call(loginController(new RecordingMail).auth(), FakeRequest(POST, "/auth")
          .withFormUrlEncodedBody("login" -> owner.email, "password" -> "secret").withCSRFToken)
        // a juror without an open round lands on the "no round yet" page (200), signed in
        session(signIn).get(Secured.AccountId) must beSome(owner.getId.toString)
        session(signIn).get(services.AccountNotices.SessionKey) must beSome("1")
        val signedIn = session(signIn).data.toSeq

        // the bar on the next page (the juror's start page), leading to the list on the profile
        val start = contentAsString(route(app, FakeRequest(GET, "/").withSession(signedIn: _*)).get)
        start must contain("id=\"account-notices-bar\"")
        start must contain(s"/users/edit/${owner.getId}#account-notices")
        val profile = contentAsString(route(app, FakeRequest(GET, s"/users/edit/${owner.getId}").withSession(signedIn: _*)).get)
        profile must not(contain("account-notices-bar"))
        profile must contain("id=\"account-notices\"")
        profile must contain("You were added to contest10 2010 in country10 by Admin Ten, an admin of the contest, " +
          "with the role Juror.")
        profile must contain("You can open the contest the next time you sign in.")

        // dismissed: a cookie for this account, the flag gone, the list empty
        val dismiss = route(app, FakeRequest(POST, "/users/notices/dismiss").withSession(signedIn: _*)
          .withFormUrlEncodedBody("upTo" -> changeId.toString)).get
        status(dismiss) === SEE_OTHER
        cookies(dismiss).get(services.AccountNotices.CookieName).map(_.value) must beSome(s"${owner.getId}.$changeId")
        session(dismiss).get(services.AccountNotices.SessionKey) must beNone
        val seen = cookies(dismiss).get(services.AccountNotices.CookieName).get
        val after = contentAsString(route(app, FakeRequest(GET, s"/users/edit/${owner.getId}")
          .withSession(session(dismiss).data.toSeq: _*).withCookies(seen)).get)
        after must not(contain("account-notices"))
        // another account's cookie hides nothing; nor does a sign-in after the dismiss set the flag
        services.AccountNotices.seenUpTo(FakeRequest().withCookies(play.api.mvc.Cookie(services.AccountNotices.CookieName,
          s"${admin.getId}.$changeId")), owner.getId) === 0L
        val again = call(loginController(new RecordingMail).auth(), FakeRequest(POST, "/auth").withCookies(seen)
          .withFormUrlEncodedBody("login" -> owner.email, "password" -> "secret").withCSRFToken)
        session(again).get(services.AccountNotices.SessionKey) must beNone

        // nobody else sees them, and a page without the flag shows no bar
        contentAsString(route(app, FakeRequest(GET, s"/users/edit/${admin.getId}").withSession(session(admin, Some(10)): _*)).get) must
          not(contain("account-notices"))
        // not root either, on the owner's edit page (the list is the own profile's)
        val root = account("root@example.com", None, "no", root = true, verified = true)
        contentAsString(route(app, FakeRequest(GET, s"/users/edit/${owner.getId}?contestId=10")
          .withSession(session(root, None): _*)).get) must not(contain("account-notices"))
      }
    }

    "keep the notices' cookie Secure as the session's, drop a stale bar, and need the CSRF token [M1]" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val admin = person("Admin Ten", "admin@example.com", Some(10), "admin")
        val owner = person("Owner", "owner@example.org", Some(20))
        implicit val messages: play.api.i18n.Messages = messagesOf
        userService(new RecordingMail).addAccountToContest(admin, owner.getId, ContestJuryJdbc.findById(10).get, "jury")
        val flagged = session(owner, None) :+ (services.AccountNotices.SessionKey -> "1")
        def dismissWith(secure: Boolean) = call(
          new UserController(app.injector.instanceOf[play.api.mvc.ControllerComponents], userService(new RecordingMail),
            app.injector.instanceOf[modules.BlockingExecutionContext],
            new ThemeCookies(play.api.http.SessionConfiguration(secure = secure))).dismissNotices(),
          FakeRequest(POST, "/users/notices/dismiss").withSession(flagged: _*).withCSRFToken)
        for (secure <- Seq(true, false))
          cookies(dismissWith(secure)).get(services.AccountNotices.CookieName).map(_.secure) must beSome(secure)

        // the own profile with nothing left to see (dismissed in another tab) drops the bar's flag
        val seen = cookies(dismissWith(false)).get(services.AccountNotices.CookieName).get
        val stale = route(app, FakeRequest(GET, s"/users/edit/${owner.getId}").withSession(flagged: _*).withCookies(seen)).get
        status(stale) === OK
        contentAsString(stale) must not(contain("account-notices"))
        session(stale).get(services.AccountNotices.SessionKey) must beNone
        session(stale).get(Secured.UserName) must beSome(owner.email)
        // with notices left, it keeps it
        session(route(app, FakeRequest(GET, s"/users/edit/${owner.getId}").withSession(flagged: _*)).get)
          .get(services.AccountNotices.SessionKey) must beSome("1")
      }
    }

    "refuse a dismiss without the CSRF token, through the router [M1]" in {
      withCsrfApp { app =>
        implicit val a: Application = app
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val admin = person("Admin Ten", "admin@example.com", Some(10), "admin")
        val owner = person("Owner", "owner@example.org", Some(20))
        implicit val messages: play.api.i18n.Messages = messagesOf
        userService(new RecordingMail).addAccountToContest(admin, owner.getId, ContestJuryJdbc.findById(10).get, "jury")
        val refused = route(app, FakeRequest(POST, "/users/notices/dismiss")
          .withHeaders(COOKIE -> "PLAY_SESSION=x", ACCEPT -> "text/html")
          .withSession(Secured.UserName -> owner.email, "csrfToken" -> csrfToken(app),
            services.AccountNotices.SessionKey -> "1")
          .withFormUrlEncodedBody("upTo" -> "999999")).get
        status(refused) === FORBIDDEN
        cookies(refused).get(services.AccountNotices.CookieName) must beNone
        // the flag stays: the session is not changed (or, if written, still has it)
        val after = session(refused)
        (after.data.isEmpty || after.get(services.AccountNotices.SessionKey).contains("1")) must beTrue
      }
    }

    "make a notice only from the search's own marker, never from a comment typed in the edit form [M1]" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = person("Admin Ten", "admin@example.com", Some(10), "admin")
        val owner = person("Owner", "owner@example.org", Some(10))
        // the edit form refuses the marker, in any case and spacing; other comments pass
        for (typed <- Seq("from search", " From  SEARCH ", "FROM SEARCH"))
          UserController.editExtrasForm.bind(Map("comment" -> typed)).hasErrors must beTrue
        UserController.editExtrasForm.bind(Map("comment" -> "found it by search")).hasErrors must beFalse
        // a row with another spelling (an older comment, say) is no notice: the match is exact
        DB.autoCommit { implicit s =>
          sql"""INSERT INTO account_change (user_id, actor_id, contest_id, field, new_value, comment)
                VALUES (${owner.getId}, ${admin.getId}, 10, ${AccountChangeJdbc.Role}, 'jury', 'From search')""".update.apply()
        }
        services.AccountNotices.pending(owner.getId, 0L) must beEmpty
      }
    }

    "change nothing for a member, and refuse an unknown id, a deleted account and a role that isn't one" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        val member = person("Member", "member@example.org", Some(10))
        val already = add(admin, 10, member.getId.toString, "admin")
        status(already) === SEE_OTHER
        flash(already).get("users-added") must beSome(contain("Already in the contest: 1"))
        UserContestJdbc.find(member.getId, 10) must beSome(UserContest(member.getId, 10, "jury"))

        status(add(admin, 10, "999999")) === NOT_FOUND
        val gone = person("Gone", "gone@example.org", None)
        delete(gone)
        status(add(admin, 10, gone.getId.toString)) === NOT_FOUND
        UserContestJdbc.findByUser(gone.getId) must beEmpty

        // a password and no address: it may not take the role, and no invitation can reach it
        val noAddress = person("No Address", "", None, verified = false, wiki = Some("NoAddressWiki"))
        val refused = add(admin, 10, noAddress.getId.toString)
        status(refused) === SEE_OTHER
        flash(refused).get("users-added") must beSome(contain("the account has no email for an invitation"))
        UserContestJdbc.findByUser(noAddress.getId) must beEmpty
        AccountTokenJdbc.findByUser(noAddress.getId) must beEmpty

        val target = person("Target", "target@example.org", None)
        status(add(admin, 10, target.getId.toString, "root")) === BAD_REQUEST
        status(add(admin, 10, "not-a-number")) === BAD_REQUEST
        UserContestJdbc.findByUser(target.getId) must beEmpty
      }
    }

    "give signed-out users, organizers, jurors and inactive members nothing" in {
      testDbApp { implicit app =>
        createContests(10)
        val target = person("Target", "target@example.org", None)
        val signedOut = route(app, FakeRequest(POST, "/admin/users/add-existing?contestId=10")
          .withFormUrlEncodedBody("accountId" -> target.getId.toString, "role" -> "jury")).get
        status(signedOut) === SEE_OTHER
        redirectLocation(signedOut) must beSome(startWith("/auth"))
        for (role <- Seq("organizer", "jury", "no", "staff")) {
          val user = person(role, s"$role@example.com", Some(10), role)
          status(add(user, 10, target.getId.toString)) === FORBIDDEN
        }
        // L4: an admin of another contest, from its own session's contest or this one's
        createContests(20)
        val otherAdmin = person("Other Admin", "admin20@example.com", Some(20), "admin")
        for (current <- Seq(20L, 10L)) {
          val r = route(app, FakeRequest(POST, "/admin/users/add-existing?contestId=10")
            .withSession(session(otherAdmin, Some(current)): _*)
            .withFormUrlEncodedBody("accountId" -> target.getId.toString, "role" -> "jury")).get
          status(r) === FORBIDDEN
        }
        UserContestJdbc.findByUser(target.getId) must beEmpty

        // L1: a root account is not found by an admin's add either (404), and nothing is written
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        val rootAccount = account("root@example.com", None, "no", root = true, verified = true)
        status(add(admin, 10, rootAccount.getId.toString)) === NOT_FOUND
        UserContestJdbc.findByUser(rootAccount.getId) must beEmpty
      }
    }

    "refuse an account deleted while the add waited for its row lock, writing nothing" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        val target = person("Target", "target@example.org", None)
        implicit val messages: play.api.i18n.Messages = messagesOf
        implicit val ec: ExecutionContext = ExecutionContext.global
        val contest = ContestJuryJdbc.findById(10).get
        val locked = new CountDownLatch(1)
        val release = new CountDownLatch(1)
        // another transaction holds the row and deletes the account, uncommitted
        val holder = Future {
          DB.localTx { implicit s =>
            val _ = User.lockRow(target.getId)
            sql"UPDATE users SET deleted_at = NOW() WHERE id = ${target.getId}".update.apply()
            locked.countDown()
            release.await()
          }
        }
        locked.await()
        // the lookup before the lock sees the account (the delete isn't committed), then waits
        val adding = Future(userService(new RecordingMail).addAccountToContest(admin, target.getId, contest, "jury").added)
        try lockWait(adding) === "LOCK WAIT"
        finally release.countDown()
        Await.result(holder, 20.seconds)
        Await.ready(adding, 20.seconds).value.get must beLike {
          case scala.util.Failure(_: UserService.AccountNotFound) => ok
        }
        UserContestJdbc.findByUser(target.getId) must beEmpty
        AccountChangeJdbc.findByUser(target.getId, None, 10) must beEmpty
      }
    }
  }

  /** Waits, 20 seconds at most, until a transaction waits for a lock: "LOCK WAIT", or what happened
    * instead (as `CriteriaRateSpec`'s: INNODB_TRX read as the container's root).
    */
  private def lockWait(waiter: Future[_]): String = {
    val url = DB.readOnly(implicit s => s.connection.getMetaData.getURL).takeWhile(_ != '?')
    val root = java.sql.DriverManager.getConnection(url, "root", SharedTestDb.password)
    try {
      def transactions = {
        val rs = root.createStatement().executeQuery(
          "SELECT trx_mysql_thread_id, trx_state, trx_query FROM information_schema.INNODB_TRX")
        Iterator.continually(rs).takeWhile(_.next())
          .map(r => s"${r.getLong(1)} ${r.getString(2)} ${r.getString(3)}").toList
      }
      val deadline = 20.seconds.fromNow
      var outcome = Option.empty[String]
      while (outcome.isEmpty) {
        val now = transactions
        if (now.exists(_.contains(" LOCK WAIT "))) outcome = Some("LOCK WAIT")
        else if (waiter.isCompleted) outcome = Some(s"done without waiting: ${waiter.value}")
        else if (deadline.isOverdue()) outcome = Some(s"no lock wait in 20 s: $now")
        else Thread.sleep(200) // INNODB_TRX's cache is refreshed only if unread for 100 ms
      }
      outcome.get
    } finally root.close()
  }
}
