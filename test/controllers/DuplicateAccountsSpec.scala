package controllers

import controllers.CsrfTestApp.{token => csrfToken, withCsrfApp}
import db.scalikejdbc.{AccountJoinRequestJdbc, PlayTestDb, Round, User, UserIdentity, UserIdentityJdbc}
import org.apache.pekko.stream.Materializer
import play.api.Application
import play.api.mvc.Result
import play.api.test._
import scalikejdbc._
import services.DuplicateAccounts

import scala.concurrent.Future

/** The duplicates report and "Keep separate" (the account join plan's J-1): groups of accounts
  * that share a normalised wiki account or a verified email, a contest's for its admins (masked
  * outside their contests, as the search, A1) and all of them for root; Keep separate per pair,
  * by root or an admin of every contest the accounts are in or worked in, hiding the pair for
  * good. Signed-out users, organizers, jurors, inactive members and other contests' admins get
  * nothing; the POST needs the CSRF token. Nothing is mailed (the controller has no mailer).
  */
class DuplicateAccountsSpec extends PlaySpecification with PlayTestDb with AccountTestSupport {

  sequential

  private def session(user: User, contestId: Option[Long]): Seq[(String, String)] =
    Seq(Secured.UserName -> user.email) ++ contestId.map(Secured.CurrentContestId -> _.toString)

  /** An account with the name, email and wiki account, a member of the contest if given. */
  private def person(name: String, email: String, contestId: Option[Long], role: String = "jury",
      verified: Boolean = true, wiki: Option[String] = None, root: Boolean = false): User =
    User.create(User(name, email, None, if (root) Set(User.ROOT_ROLE) else Set(role), Some(hash("secret")), contestId,
      wikiAccount = wiki, isRoot = root, emailVerifiedAt = if (verified) Some(java.time.ZonedDateTime.now) else None))

  private def page(by: User, contestId: Long)(implicit app: Application): Future[Result] =
    route(app, FakeRequest(GET, s"/admin/duplicates?contestId=$contestId").withSession(session(by, Some(contestId)): _*)).get

  private def keep(by: User, contestId: Option[Long], accounts: Seq[User], apartFrom: Option[User] = None)(implicit
      app: Application): Future[Result] =
    route(app, FakeRequest(POST, s"/admin/duplicates/keep-separate${contestId.fold("")(c => s"?contestId=$c")}")
      .withSession(session(by, contestId): _*)
      .withFormUrlEncodedBody(accounts.map(a => "account" -> a.getId.toString) ++
        apartFrom.map(a => "apartFrom" -> a.getId.toString): _*)).get

  private def keptRows(): List[(Long, Long, String, Long, Option[Long], Option[Long])] = DB.readOnly { implicit s =>
    sql"""SELECT first_account_id, second_account_id, state, requested_by, decided_by, contest_id
          FROM account_join_request ORDER BY first_account_id, second_account_id"""
      .map(rs => (rs.long(1), rs.long(2), rs.string(3), rs.long(4), rs.longOpt(5), rs.longOpt(6))).list()
  }

  private def idsOf(users: User*): Seq[Long] = users.map(_.getId).sorted

  /** The account with its memberships, as a signed-in request loads it ([[Secured]]). */
  private def loaded(user: User): User = User.withMemberships(Seq(user)).head

  private def groups(by: User, contestId: Option[Long]): Seq[Seq[Long]] =
    DuplicateAccounts.report(loaded(by), contestId).groups.map(_.found.ids)

  private def delete(user: User): Unit =
    DB.autoCommit(implicit s => sql"UPDATE users SET deleted_at = NOW() WHERE id = ${user.getId}".update.apply())

  "the duplicates report [A-24]" should {

    "group accounts by their normalised wiki account, in a contest's report only with one of its accounts, all for root [R-06]" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val admin = person("Admin Ten", "admin@example.com", Some(10), "admin")
        val member = person("Anna Member", "anna@example.org", Some(10), wiki = Some("Anna K"))
        val other = person("Anna Other", "anna.other@example.org", Some(20), "organizer", wiki = Some("anna_k"))
        val loose = person("Anna Loose", "loose@example.org", None, wiki = Some(" User:Anna  K "))
        val rootAccount = person("Root Anna", "root@example.com", None, wiki = Some("Anna K"), root = true)
        val gone = person("Anna Gone", "gone@example.org", Some(10), wiki = Some("Anna K"))
        delete(gone)
        // a group with no account in contest 10: not in its report
        person("Bohdan", "bohdan@example.org", Some(20), wiki = Some("Bohdan"))
        person("Bohdan Two", "bohdan2@example.org", Some(20), wiki = Some("bohdan"))
        // one person with several accounts by name only, or alone with a wiki account: never paired
        person("Anna Member", "anna.same.name@example.org", Some(10))
        person("Solo", "solo@example.org", Some(10), wiki = Some("Solo"))

        val (r, lines) = accountLog {
          val r = page(admin, 10)
          status(r)
          r
        }
        status(r) === OK
        header(CACHE_CONTROL, r) must beSome("no-store")
        val html = contentAsString(r)
        html must contain("Same wiki account: Anna K")
        html must contain("Groups: 1. Accounts in them: 3.")
        // within the admin's contests in full, outside them masked; never the other contests
        html must contain("anna@example.org")
        html must contain("a***@example.org").and(contain("l***@example.org"))
        html must not(contain("anna.other@")).and(not(contain("loose@"))).and(not(contain("contest20")))
        html must not(contain("Root Anna")).and(not(contain("Anna Gone"))).and(not(contain("Bohdan")))
        html must not(contain("anna.same.name@")).and(not(contain("Solo")))
        // the member's edit page only
        html must contain(routes.UserController.editUser(member.getId, Some(10)).url)
        html must not(contain(routes.UserController.editUser(other.getId, Some(10)).url))
        // another contest's account: Keep separate is root's, or an admin of every contest's
        // whether an address is verified: only beside a full one (the member's), never a masked one
        "badge text-bg-success\">verified<".r.findAllIn(html).size === 1
        html must not(contain(">unverified<"))
        html must contain("Only root, or an admin of every contest these accounts are in or worked in")
        html must not(contain("/admin/duplicates/keep-separate"))
        lines === Seq(s"duplicates_report byAccountId=${admin.getId} contestId=10 groups=1 accounts=3")

        // root: every group, root accounts included
        val root = person("Root", "root2@example.com", None, root = true)
        groups(root, None) === Seq(idsOf(member, other, loose, rootAccount),
          idsOf(User.findByEmail("bohdan@example.org").head, User.findByEmail("bohdan2@example.org").head))
        groups(root, Some(10)) === Seq(idsOf(member, other, loose, rootAccount))
        val all = route(app, FakeRequest(GET, "/admin/duplicates/all").withSession(session(root, None): _*)).get
        status(all) === OK
        contentAsString(all) must contain("anna.other@example.org").and(contain("Root Anna")).and(contain("Bohdan"))
        // the verified-email clause of the explanation: root's only (an admin's report has no such groups)
        contentAsString(all) must contain("For root, also accounts that share a verified email address")
        html must not(contain("verified email address"))
      }
    }

    "take a contest the account worked in (a round) as its contest, for the scope and for Keep separate" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = person("Admin Ten", "admin@example.com", Some(10), "admin")
        val a = person("A", "a@example.org", None, wiki = Some("Shared"))
        val b = person("B", "b@example.org", None, wiki = Some("shared"))
        groups(admin, Some(10)) must beEmpty
        val round10 = roundDao.create(Round(None, 1, Some("R1"), 10)).getId
        DB.autoCommit(implicit s =>
          sql"INSERT INTO round_user (user_id, round_id, role, active) VALUES (${a.getId}, $round10, 'jury', FALSE)".update())
        groups(admin, Some(10)) === Seq(idsOf(a, b))
        DuplicateAccounts.report(loaded(admin), Some(10)).groups.head.mayKeepSeparate must beTrue
        // b worked in contest 20 too, which this admin doesn't administer
        val round20 = roundDao.create(Round(None, 1, Some("R1"), 20)).getId
        DB.autoCommit(implicit s =>
          sql"INSERT INTO round_user (user_id, round_id, role, active) VALUES (${b.getId}, $round20, 'jury', TRUE)".update())
        DuplicateAccounts.report(loaded(admin), Some(10)).groups.head.mayKeepSeparate must beFalse
        DuplicateAccounts.keepSeparate(loaded(admin), Some(10), idsOf(a, b), None) === DuplicateAccounts.NotAllowed
        keptRows() must beEmpty
      }
    }

    "group a verified address with another account's sign-in of that address, for root only" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = person("Admin Ten", "admin@example.com", Some(10), "admin")
        val proved = person("Proved", "same@example.org", Some(10))
        val signIn = person("Sign-in", "other@example.org", Some(10), verified = false)
        UserIdentityJdbc.create(UserIdentity("google", "g-1", signIn.getId, Some("same@example.org"), None))
        // unverified address, an unlinked sign-in, or the account's own: no group
        val unverified = person("Unverified", "unverified@example.org", Some(10), verified = false)
        val third = person("Third", "third@example.org", Some(10))
        UserIdentityJdbc.create(UserIdentity("google", "g-2", third.getId, Some("unverified@example.org"), None))
        val unlinked = person("Unlinked", "unlinked@example.org", Some(10))
        UserIdentityJdbc.create(UserIdentity("google", "g-3", unlinked.getId, Some("proved2@example.org"), None))
        person("Proved Two", "proved2@example.org", Some(10))
        DB.autoCommit(implicit s => sql"UPDATE user_identity SET unlinked_at = NOW(3) WHERE subject = 'g-3'".update())
        UserIdentityJdbc.create(UserIdentity("wikimedia", "w-1", proved.getId, Some("same@example.org"), None))
        unverified.getId must be_>(0L)

        // root's only, while that reading of the plan waits for the maintainer: no email group for
        // an admin, the address in full for root
        DuplicateAccounts.report(loaded(admin), Some(10)).groups must beEmpty
        val root = person("Root", "root@example.com", None, root = true)
        val report = DuplicateAccounts.report(root, None)
        report.groups.map(g => (g.found.criterion, g.shared, g.found.ids)) ===
          Seq((DuplicateAccounts.SameEmail, "same@example.org", idsOf(proved, signIn)))
        // kept apart from both holders of its address: the holders stay a group, the address in full
        val other = person("Other sign-in", "other2@example.org", Some(10), verified = false)
        UserIdentityJdbc.create(UserIdentity("google", "g-4", other.getId, Some("same@example.org"), None))
        DuplicateAccounts.keepSeparate(root, None, idsOf(proved, signIn, other), Some(proved.getId)) ===
          DuplicateAccounts.KeptApart(2)
        DuplicateAccounts.report(root, None).groups.map(g => (g.shared, g.found.ids)) ===
          Seq(("same@example.org", idsOf(signIn, other)))
      }
    }

    "keep one account apart from the others, then the rest, for good; a pair kept apart still linked by a third" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = person("Admin Ten", "admin@example.com", Some(10), "admin")
        val a = person("Portion A", "a@example.org", Some(10), wiki = Some("Screener"))
        val b = person("Portion B", "b@example.org", Some(10), wiki = Some("screener"))
        val c = person("Portion C", "c@example.org", Some(10), wiki = Some("Screener"))

        val html = contentAsString(page(admin, 10))
        html must contain("Keep separate from the others").and(contain("Keep all separate"))

        val (r, lines) = accountLog {
          val r = keep(admin, Some(10), Seq(a, b, c), Some(a))
          status(r)
          r
        }
        status(r) === SEE_OTHER
        redirectLocation(r) must beSome(routes.DuplicateAccountsController.report(10).url)
        flash(r).get("duplicates") must beSome("duplicates.kept.apart")
        keptRows() === List(
          (a.getId, b.getId, "kept_apart", admin.getId, Some(admin.getId), Some(10L)),
          (a.getId, c.getId, "kept_apart", admin.getId, Some(admin.getId), Some(10L)))
        lines === Seq(s"accounts_kept_apart byAccountId=${admin.getId} contestId=10 pairs=${a.getId}:${b.getId},${a.getId}:${c.getId}")
        groups(admin, Some(10)) === Seq(idsOf(b, c))

        // the same POST again: the group has changed, nothing recorded
        val again = keep(admin, Some(10), Seq(a, b, c), Some(a))
        flash(again).get("duplicates") must beSome("duplicates.changed")
        keptRows().size === 2

        status(keep(admin, Some(10), Seq(b, c))) === SEE_OTHER
        groups(admin, Some(10)) must beEmpty
        contentAsString(page(admin, 10)) must contain("No possible duplicates.")

        // a pair kept apart (d, e) stays in the group while a third (f) links them, and says so
        val d = person("D", "d@example.org", Some(10), wiki = Some("Linked"))
        val e = person("E", "e@example.org", Some(10), wiki = Some("Linked"))
        val f = person("F", "f@example.org", Some(10), wiki = Some("Linked"))
        DuplicateAccounts.keepSeparate(loaded(admin), Some(10), idsOf(d, e), None) === DuplicateAccounts.KeptApart(1)
        groups(admin, Some(10)) === Seq(idsOf(d, e, f))
        contentAsString(page(admin, 10)) must contain(
          s"Kept separate already, but linked through the others: D (#${d.getId}) and E (#${e.getId}).")
      }
    }

    "refuse Keep separate to an admin of only some of the accounts' contests, writing nothing" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = person("Admin Ten", "admin@example.com", Some(10), "admin")
        val a = person("A", "a@example.org", Some(10), wiki = Some("Twice"))
        val b = person("B", "b@example.org", Some(20), wiki = Some("Twice"))
        val r = keep(admin, Some(10), Seq(a, b))
        status(r) === FORBIDDEN
        keptRows() must beEmpty
        // nor accounts not in one group of the report (made up, or another contest's)
        val c = person("C", "c@example.org", Some(20), wiki = Some("Elsewhere"))
        val d = person("D", "d@example.org", Some(20), wiki = Some("Elsewhere"))
        flash(keep(admin, Some(10), Seq(c, d))).get("duplicates") must beSome("duplicates.changed")
        flash(keep(admin, Some(10), Seq(a, c))).get("duplicates") must beSome("duplicates.changed")
        status(route(app, FakeRequest(POST, "/admin/duplicates/keep-separate?contestId=10")
          .withSession(session(admin, Some(10)): _*).withFormUrlEncodedBody("account" -> "x")).get) === BAD_REQUEST
        keptRows() must beEmpty
      }
    }

    "lock the accounts' rows in ascending id, and drop a deleted account from the groups" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = person("Admin Ten", "admin@example.com", Some(10), "admin")
        val a = person("A", "a@example.org", Some(10), wiki = Some("Gone"))
        val b = person("B", "b@example.org", Some(10), wiki = Some("Gone"))
        DB.localTx { implicit tx =>
          AccountJoinRequestJdbc.lockAccounts(idsOf(b, a) :+ a.getId).map(_.getId) === idsOf(a, b)
        }
        delete(b)
        groups(admin, Some(10)) must beEmpty
        DuplicateAccounts.keepSeparate(loaded(admin), Some(10), idsOf(a, b), None) === DuplicateAccounts.Changed
        keptRows() must beEmpty
      }
    }

    "give signed-out users, organizers, jurors, inactive members and other contests' admins nothing" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val a = person("Anna A", "a@example.org", Some(10), wiki = Some("Anna"))
        val b = person("Anna B", "b@example.org", Some(10), wiki = Some("anna"))

        val signedOut = route(app, FakeRequest(GET, "/admin/duplicates?contestId=10")).get
        status(signedOut) === SEE_OTHER
        redirectLocation(signedOut) must beSome(startWith("/auth"))
        val signedOutPost = route(app, FakeRequest(POST, "/admin/duplicates/keep-separate?contestId=10")
          .withFormUrlEncodedBody("account" -> a.getId.toString, "account" -> b.getId.toString)).get
        status(signedOutPost) === SEE_OTHER
        redirectLocation(signedOutPost) must beSome(startWith("/auth"))

        val organizer = person("Org", "org@example.com", Some(10), "organizer")
        val juror = person("Juror", "juror@example.com", Some(10))
        val inactive = person("Inactive", "inactive@example.com", Some(10), "no")
        val board = person("Board", "board@example.com", Some(10), "board")
        for (user <- Seq(organizer, juror, inactive, board)) {
          val r = page(user, 10)
          status(r) === FORBIDDEN
          contentAsString(r) must not(contain("Anna"))
          status(keep(user, Some(10), Seq(a, b))) === FORBIDDEN
          status(route(app, FakeRequest(GET, "/admin/duplicates/all").withSession(session(user, Some(10)): _*)).get) ===
            FORBIDDEN
        }
        // an admin of another contest, from its session's contest or this one's
        val otherAdmin = person("Other Admin", "admin20@example.com", Some(20), "admin")
        for (current <- Seq(20L, 10L)) {
          val r = route(app, FakeRequest(GET, "/admin/duplicates?contestId=10")
            .withSession(session(otherAdmin, Some(current)): _*)).get
          status(r) === FORBIDDEN
          contentAsString(r) must not(contain("Anna"))
          val k = route(app, FakeRequest(POST, "/admin/duplicates/keep-separate?contestId=10")
            .withSession(session(otherAdmin, Some(current)): _*)
            .withFormUrlEncodedBody("account" -> a.getId.toString, "account" -> b.getId.toString)).get
          status(k) === FORBIDDEN
        }
        // an admin of contest 10 has no full report, nor its Keep separate (root's)
        val admin = person("Admin Ten", "admin@example.com", Some(10), "admin")
        status(route(app, FakeRequest(GET, "/admin/duplicates/all").withSession(session(admin, Some(10)): _*)).get) ===
          FORBIDDEN
        status(keep(admin, None, Seq(a, b))) === FORBIDDEN
        keptRows() must beEmpty
      }
    }

    "need the CSRF token for Keep separate, root's on the full report, through the router [R-06]" in {
      withCsrfApp { app =>
        implicit val a: Application = app
        val root = account("root@example.com", None, "no", root = true, verified = true)
        val x = person("X", "x@example.org", None, wiki = Some("Pair"))
        val y = person("Y", "y@example.org", None, wiki = Some("Pair"))
        val sessionToken = csrfToken(app)
        def request(token: Option[String]) = FakeRequest(POST, "/admin/duplicates/keep-separate")
          .withHeaders(COOKIE -> "PLAY_SESSION=x", ACCEPT -> "text/html")
          .withSession(Secured.UserName -> root.email, "csrfToken" -> sessionToken)
          .withFormUrlEncodedBody(Seq("account" -> x.getId.toString, "account" -> y.getId.toString) ++
            token.map("csrfToken" -> _): _*)
        status(route(app, request(None)).get) === FORBIDDEN
        keptRows() must beEmpty
        status(route(app, request(Some(sessionToken))).get) === SEE_OTHER
        keptRows().map(r => (r._1, r._2, r._3, r._6)) === List((x.getId, y.getId, "kept_apart", None))
      }
    }

    "read users once and account_join_request by its keys, on 2,800 accounts" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = person("Admin Ten", "admin@example.com", Some(10), "admin")
        // production's size (the copy of 2026-11-15: about 2,800 accounts, 278 in 86 wiki groups)
        DB.localTx { implicit tx =>
          (1 to 2800).grouped(500).foreach { batch =>
            val rows = batch.map(i => sqls"(${s"Person $i"}, ${s"p$i@example.org"}, ${if (i <= 280) Some(s"Wiki ${i % 86}") else None})")
            sql"INSERT INTO users (fullname, email, wiki_account) VALUES ${sqls.csv(rows: _*)}".update()
          }
          sql"""INSERT INTO user_contest (user_id, contest_id, role) SELECT id, 10, 'jury' FROM users WHERE email LIKE 'p%'""".update()
          // a few sign-ins (production has few: OAuth is off there)
          sql"""INSERT INTO user_identity (provider, subject, user_id, email)
                SELECT 'google', CONCAT('g-', id), id, CONCAT('x', id, '@example.net') FROM users WHERE id % 500 = 0""".update()
        }
        val started = System.nanoTime()
        val report = DuplicateAccounts.report(loaded(admin), Some(10))
        val ms = (System.nanoTime() - started) / 1000000
        report.groups.size === 86
        report.accounts === 280
        val ids = report.groups.flatMap(_.found.ids)
        DuplicateAccounts.keepSeparate(loaded(admin), Some(10), report.groups.head.found.ids, None) must beLike {
          case DuplicateAccounts.KeptApart(n) => n must be_>(0)
        }
        def plan(statement: SQLSyntax): List[(String, String, Option[String], Long)] = DB.readOnly { implicit s =>
          sql"EXPLAIN $statement".map(rs => (rs.string("table"), rs.string("type"), rs.stringOpt("key"),
            rs.longOpt("rows").getOrElse(0L))).list()
        }
        val wiki = plan(sqls"""SELECT id, wiki_account FROM users
          WHERE deleted_at IS NULL AND wiki_account IS NOT NULL AND TRIM(wiki_account) <> '' AND is_root = FALSE""")
        val email = plan(sqls"""SELECT a.email, a.id, b.id FROM users a
          JOIN user_identity i ON i.email = a.email AND i.unlinked_at IS NULL AND i.user_id <> a.id
          JOIN users b ON b.id = i.user_id
          WHERE a.deleted_at IS NULL AND a.email_verified_at IS NOT NULL AND a.email_set_by_admin_at IS NULL
            AND b.deleted_at IS NULL AND a.is_root = FALSE AND b.is_root = FALSE""")
        val kept = plan(sqls"""SELECT first_account_id, second_account_id FROM account_join_request
          WHERE state = 'kept_apart' AND first_account_id IN ($ids) AND second_account_id IN ($ids)""")
        println(s"[duplicates] report on 2,800 accounts: $ms ms; plans: wiki $wiki; email $email; kept $kept")
        // one read of users for the names (a range of its wiki account index, measured 280 rows,
        // or a scan of at most all accounts), the email path driven by user_identity (a handful of
        // rows) and users by their keys
        (wiki.map(_._1) === List("users")) and (wiki.head._4 must be_<=(3000L)) and
          (email.count(_._2 == "ALL") must be_<=(1)) and (email.size === 3)
      }
    }
  }
}
