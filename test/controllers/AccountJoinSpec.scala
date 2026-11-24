package controllers

import controllers.CsrfTestApp.{token => csrfToken, withCsrfApp}
import db.scalikejdbc._
import org.intracer.wmua.{CommentJdbc, Image, Vote}
import org.jsoup.Jsoup
import play.api.Application
import play.api.i18n.MessagesApi
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test._
import scalikejdbc._
import services.distribution.{RoundRebalanceHandler, RoundRebalanceService}
import services.jobs.{Job, JobJdbc, JobScheduler, JobStatus}
import services.{AccountJoin, AccountJoinHandler, AccountJoinMail, TestRoundServices}

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** Joining two accounts (the account join plan's J-2, docs/plans/2026-11-03-account-join.md;
  * J1–J3): who may join which accounts (root, or an admin of every contest of both; forged ids,
  * another contest's admin, organizers and jurors refused; CSRF), the preview (memberships,
  * rounds, votes, collisions, the CoI change, the identities unlinked; the kept account chosen,
  * none preselected), any collision refusing the join, and the `account_join` job end to end over
  * several rounds (votes, votes set aside, criteria rates, round assignments, comments,
  * memberships; `round_image` invariants; the other account retired, sessions ended, tokens and
  * links revoked), Cancel only before the first round step, Retry, and the races: a vote of the
  * retiring account, adding either account to a round, two joins of one account.
  */
class AccountJoinSpec extends PlaySpecification with PlayTestDb with AccountTestSupport {

  sequential

  private implicit val autoSession: DBSession = AutoSession

  private def session(user: User, contestId: Option[Long]): Seq[(String, String)] =
    Seq(Secured.UserName -> user.email) ++ contestId.map(Secured.CurrentContestId -> _.toString)

  private def person(name: String, email: String, contestId: Option[Long], role: String = "jury",
      verified: Boolean = true, wiki: Option[String] = None, root: Boolean = false): User =
    User.create(User(name, email, None, if (root) Set(User.ROOT_ROLE) else Set(role), Some(hash("secret")), contestId,
      wikiAccount = wiki, isRoot = root, emailVerifiedAt = if (verified) Some(java.time.ZonedDateTime.now) else None))

  private def stored(u: User): User = User.findById(u.getId).get

  private var nextPage = 7000L

  private def image(): Image = {
    nextPage += 1
    val i = Image(nextPage, s"File:Join$nextPage.jpg", None, None, 640, 480, None)
    imageDao.batchInsert(Seq(i))
    i
  }

  private def roundOf(contestId: Long, name: String = "R", rates: Rates = Round.binaryRound): Round =
    roundDao.create(Round(None, 1, Some(name), contestId, rates = rates, active = true))

  private def assign(round: Round, users: User*): Unit =
    round.addUsers(users.map(u => RoundUser(round.getId, u.getId, "jury", active = true)))

  private def vote(round: Round, user: User, pageId: Long, rate: Int): Long = {
    selectionDao.batchInsert(Seq(Vote(pageId, user.getId, round.getId, rate)))
    VoteJdbc.findBy(pageId, user.getId, round.getId).get.getId
  }

  private def aside(round: Round, user: User, pageId: Long, rate: Int): Unit =
    DB.autoCommit { implicit autoSession =>
      sql"INSERT INTO vote_aside (page_id, jury_id, round_id, rate) VALUES ($pageId, ${user.getId}, ${round.getId}, $rate)".update()
    }

  private def rows(table: String, roundId: Long): Seq[(Long, Long, Int)] = {
    val t = SQLSyntax.createUnsafely(table)
    sql"SELECT page_id, jury_id, rate FROM $t WHERE round_id = $roundId ORDER BY page_id, jury_id"
      .map(rs => (rs.long(1), rs.long(2), rs.int(3))).list()
  }

  private def roundUsers(roundId: Long): Seq[(Long, Boolean)] =
    sql"SELECT user_id, active FROM round_user WHERE round_id = $roundId ORDER BY user_id"
      .map(rs => rs.long(1) -> rs.boolean(2)).list()

  private def joinJobs(): Seq[Job] =
    sql"SELECT id FROM job WHERE kind = ${AccountJoinJdbc.Kind} ORDER BY id".map(_.long(1)).list().flatMap(JobJdbc.findById(_))

  private def preview(by: User, contestId: Option[Long], accounts: Seq[User], kept: Option[User] = None)(implicit
      app: Application): Future[Result] = {
    val query = (contestId.map(c => s"contestId=$c") ++ accounts.map(a => s"account=${a.getId}") ++
      kept.map(k => s"kept=${k.getId}")).mkString("&")
    route(app, FakeRequest(GET, s"/admin/accounts/join?$query").withSession(session(by, contestId): _*)).get
  }

  private def signatureOf(html: String, joined: User): String =
    Option(Jsoup.parse(html).selectFirst(s"section[data-joined-id=${joined.getId}] input[name=signature]"))
      .map(_.attr("value")).getOrElse(sys.error(s"no form for ${joined.getId}"))

  private def start(by: User, contestId: Option[Long], kept: User, joined: User, signature: String,
      notify: Boolean = false)(implicit app: Application): Future[Result] =
    route(app, FakeRequest(POST, s"/admin/accounts/join${contestId.fold("")(c => s"?contestId=$c")}")
      .withSession(session(by, contestId): _*)
      .withFormUrlEncodedBody(Seq("kept" -> kept.getId.toString, "joined" -> joined.getId.toString,
        "signature" -> signature) ++ Option.when(notify)("notify" -> "true"): _*)).get

  /** A handler whose hooks run code in its steps, with a recording mailer. */
  private class TestJoin(mail: RecordingMail)(implicit app: Application)
      extends AccountJoinHandler(new AccountJoinMail(userService(mail), app.injector.instanceOf[MessagesApi])) {
    @volatile var atRound: Option[Long => Unit] = None
    @volatile var atFinish: Option[() => Unit] = None
    override protected def beforeRound(roundId: Long)(implicit session: DBSession): Unit = atRound.foreach(_(roundId))
    override protected def beforeFinish()(implicit session: DBSession): Unit = atFinish.foreach(_())
  }

  private def serviceStart(h: AccountJoinHandler, by: User, contestId: Option[Long], kept: User, joined: User,
      notify: Boolean = false): (AccountJoin.Outcome, JobScheduler) = {
    val jobs = TestRoundServices.inlineScheduler(h)
    val loadedBy = User.withMemberships(Seq(stored(by))).head
    val p = AccountJoin.preview(loadedBy, stored(kept), stored(joined), contestId)
    (AccountJoin.start(loadedBy, contestId, kept.getId, joined.getId, p.signature, notify, jobs), jobs)
  }

  private def startedJob(outcome: AccountJoin.Outcome): Job = outcome match {
    case AccountJoin.Started(job) => JobJdbc.findById(job.id).get
    case other                    => sys.error(s"not started: $other")
  }

  private def refusals(outcome: AccountJoin.Outcome): Seq[String] = outcome match {
    case AccountJoin.Refused(p, changed) => (if (changed) Seq("changed") else Nil) ++ p.refusals.map(_.reason)
    case other                         => sys.error(s"not refused: $other")
  }

  // ── who may join which accounts ────────────────────────────────────────────

  "who may join two accounts [A-26]" should {

    "be root, or an admin of every contest of both; never another contest's admin, an organizer or a juror" in {
      testDbApp { implicit app =>
        createContests(10, 20, 30)
        val root = person("Root", "root@example.com", None, root = true)
        val adminBoth = person("Admin Both", "admin.both@example.com", Some(10), "admin")
        UserContestJdbc.createMembership(adminBoth.getId, 20, "admin")
        val admin10 = person("Admin Ten", "admin10@example.com", Some(10), "admin")
        val admin30 = person("Admin Thirty", "admin30@example.com", Some(30), "admin")
        val organizer = person("Org", "org@example.com", Some(10), "organizer")
        val juror = person("Juror", "juror@example.com", Some(10))
        val kept = person("Kept One", "kept@example.org", Some(10), wiki = Some("Same"))
        val joined = person("Joined One", "joined@example.org", Some(10), wiki = Some("same"))
        UserContestJdbc.createMembership(joined.getId, 20, "jury")

        status(preview(root, None, Seq(kept, joined))) === OK
        status(preview(root, Some(10), Seq(kept, joined))) === OK
        status(preview(adminBoth, Some(10), Seq(kept, joined))) === OK
        // an admin of only one of the two accounts' contests
        status(preview(admin10, Some(10), Seq(kept, joined))) === FORBIDDEN
        // another contest's admin, an organizer, a juror
        status(preview(admin30, Some(10), Seq(kept, joined))) === FORBIDDEN
        status(preview(admin30, Some(30), Seq(kept, joined))) === FORBIDDEN
        status(preview(organizer, Some(10), Seq(kept, joined))) === FORBIDDEN
        status(preview(juror, Some(10), Seq(kept, joined))) === FORBIDDEN
        // outside a contest: root only
        status(preview(adminBoth, None, Seq(kept, joined))) === FORBIDDEN
        // an account with no ties (in no contest, worked in none): root only, never on an empty set
        val loose = person("Loose One", "loose@example.org", None, wiki = Some("same"))
        status(preview(adminBoth, Some(10), Seq(kept, loose))) === FORBIDDEN
        status(preview(root, Some(10), Seq(kept, loose))) === OK
        // signed out: the login page
        val signedOut = route(app, FakeRequest(GET, s"/admin/accounts/join?contestId=10&account=${kept.getId}&account=${joined.getId}")).get
        status(signedOut) must beOneOf(SEE_OTHER, UNAUTHORIZED)
        // the POST alike: refused, nothing queued
        val sig = signatureOf(contentAsString(preview(root, Some(10), Seq(kept, joined), Some(kept))), joined)
        status(start(admin10, Some(10), kept, joined, sig)) === FORBIDDEN
        status(start(organizer, Some(10), kept, joined, sig)) === FORBIDDEN
        status(start(admin30, Some(30), kept, joined, sig)) === FORBIDDEN
        joinJobs() must beEmpty
      }
    }

    "refuse forged ids: a missing or retired account, a root account, the kept one not among them, one account twice" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        val root = person("Root", "root@example.com", None, root = true)
        val a = person("A", "a@example.org", Some(10))
        val b = person("B", "b@example.org", Some(10))
        val gone = person("Gone", "gone@example.org", Some(10))
        DB.autoCommit(implicit autoSession => sql"UPDATE users SET deleted_at = NOW(3) WHERE id = ${gone.getId}".update())
        def raw(query: String, by: User = admin) =
          route(app, FakeRequest(GET, s"/admin/accounts/join?contestId=10&$query").withSession(session(by, Some(10)): _*)).get
        status(raw(s"account=${a.getId}&account=999999")) === NOT_FOUND
        status(raw(s"account=${a.getId}&account=${gone.getId}")) === NOT_FOUND
        status(raw(s"account=${a.getId}&account=${root.getId}")) === FORBIDDEN
        status(raw(s"account=${a.getId}&account=${b.getId}&kept=${admin.getId}")) === BAD_REQUEST
        status(raw(s"account=${a.getId}&account=${a.getId}")) === BAD_REQUEST
        status(raw(s"account=${a.getId}&account=x")) === BAD_REQUEST
        // the POST: a joined account the page didn't show is read again (a root account: refused)
        val sig = signatureOf(contentAsString(preview(admin, Some(10), Seq(a, b), Some(a))), b)
        status(start(admin, Some(10), a, root, sig)) === FORBIDDEN
        status(start(admin, Some(10), a, gone, sig)) === NOT_FOUND
        status(start(admin, Some(10), a, a, sig)) === BAD_REQUEST
        joinJobs() must beEmpty
      }
    }

    "need the CSRF token on the start" in {
      withCsrfApp { app =>
        implicit val a: Application = app
        createContests(40)
        val root = person("Root", "root40@example.com", None, root = true)
        val k = person("K40", "k40@example.org", Some(40))
        val j = person("J40", "j40@example.org", Some(40))
        val refused = route(app, FakeRequest(POST, "/admin/accounts/join?contestId=40")
          .withHeaders(COOKIE -> "PLAY_SESSION=x", ACCEPT -> "text/html")
          .withSession(Secured.UserName -> root.email, "csrfToken" -> csrfToken(app))
          .withFormUrlEncodedBody("kept" -> k.getId.toString, "joined" -> j.getId.toString, "signature" -> "x")).get
        status(refused) === FORBIDDEN
        joinJobs() must beEmpty
        stored(j).deletedAt must beNone
      }
    }
  }

  // ── entry points ───────────────────────────────────────────────────────────

  "the entry points [A-26]" should {

    "be the duplicates report's Join on a group the admin may decide, and the wiki-taken panel's link" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        val a = person("Anna A", "anna.a@example.org", Some(10), wiki = Some("Anna"))
        val b = person("Anna B", "anna.b@example.org", Some(10), wiki = Some("anna"))
        // a group with an account in no contest: no Join for an admin (Keep separate stays)
        person("Cy E", "cy.e@example.org", Some(10), wiki = Some("Cy"))
        person("Cy F", "cy.f@example.org", None, wiki = Some("Cy"))
        // another group, with an account in contest 20 this admin doesn't administer: no Join
        val c = person("Bo C", "bo.c@example.org", Some(10), wiki = Some("Bo"))
        val d = person("Bo D", "bo.d@example.org", Some(20), wiki = Some("Bo"))
        val report = Jsoup.parse(contentAsString(route(app, FakeRequest(GET, "/admin/duplicates?contestId=10")
          .withSession(session(admin, Some(10)): _*)).get))
        report.select("a.duplicates-join").asScala.map(_.attr("href")) ===
          Seq(AccountJoinController.previewUrl(Some(10), Seq(a.getId, b.getId)))
        // the panel: the edited account typed the other's wiki account
        val edit = route(app, FakeRequest(POST, "/users/save").withSession(session(admin, Some(10)): _*)
          .withFormUrlEncodedBody("id" -> c.getId.toString, "fullname" -> c.fullname, "email" -> c.email,
            "emailWas" -> c.email, "wikiAccount" -> "Anna", "roles" -> "jury", "contest" -> "10", "lang" -> "en")).get
        status(edit) === BAD_REQUEST
        val links = Jsoup.parse(contentAsString(edit)).select("a.wiki-taken-join").asScala.map(_.attr("href"))
        links === Seq(a, b).map(o => AccountJoinController.previewUrl(Some(10), Seq(c.getId, o.getId)))
        d.getId must be_>(0L)
      }
    }
  }

  // ── the preview ────────────────────────────────────────────────────────────

  "the preview [A-26]" should {

    "list both accounts' memberships, rounds, votes, collisions, the CoI change and the identities unlinked" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val root = person("Root", "root@example.com", None, root = true)
        val kept = person("Kept Person", "kept@example.org", Some(10), "jury", wiki = Some("Kept Wiki"))
        val joined = person("Joined Person", "joined@example.org", Some(10), "organizer", wiki = Some("Old Wiki"))
        UserContestJdbc.createMembership(joined.getId, 20, "jury")
        UserIdentityJdbc.create(UserIdentity("google", "g-joined", joined.getId, Some("joined@gmail.example"), Some("Joined G")))
        val r1 = roundOf(10, "First")
        assign(r1, kept, joined)
        val (p1, p2, p3) = (image().pageId, image().pageId, image().pageId)
        vote(r1, kept, p1, 1)
        vote(r1, joined, p1, -1) // a collision
        vote(r1, kept, p2, 0)
        vote(r1, joined, p2, 0) // one assignment: merges
        vote(r1, joined, p3, 1)
        CommentJdbc.create(joined.getId, joined.fullname, r1.getId, Some(10), p3, "a comment")

        val html = contentAsString(preview(root, Some(10), Seq(kept, joined), Some(kept)))
        val doc = Jsoup.parse(html)
        val section = doc.selectFirst(s"section[data-joined-id=${joined.getId}]")
        // the kept account checked as chosen; without `kept` none is
        doc.select("input[name=kept][checked]").asScala.map(_.attr("value")) === Seq(kept.getId.toString)
        Jsoup.parse(contentAsString(preview(root, Some(10), Seq(kept, joined)))).select("input[name=kept][checked]").size === 0
        val memberships = section.select("table.account-join-memberships tbody tr").asScala.map(_.text)
        memberships must contain(beMatching(".*Juror Organizer Organizer.*"))
        memberships must contain(beMatching(".* – Juror Juror.*"))
        val roundRow = section.selectFirst("table.account-join-rounds tbody tr").text
        roundRow must contain("First")
        roundRow must contain("3 files, 2 voted")
        section.select("ul.account-join-collisions li").size === 1
        section.text must contain("Old Wiki is no longer checked")
        section.selectFirst("li.account-join-identities").text must contain("google: Joined G")
        section.text must contain("Comments moved: 1.")
        section.selectFirst("div.account-join-refusals").text must contain("1 files have a vote of both accounts")
        section.selectFirst("button[type=submit]").hasAttr("disabled") must beTrue
        // the mail unticked
        section.select("input[name=notify][checked]").size === 0
      }
    }

    "offer only the account with an owner-proven email as the kept one, where one has it" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        val proven = person("Proven", "proven@example.org", Some(10))
        val unproven = person("Unproven", "unproven@example.org", Some(10), verified = false)
        val doc = Jsoup.parse(contentAsString(preview(admin, Some(10), Seq(proven, unproven))))
        doc.selectFirst(s"input#kept-${unproven.getId}").hasAttr("disabled") must beTrue
        doc.selectFirst(s"input#kept-${proven.getId}").hasAttr("disabled") must beFalse
        // keeping the unproven one: no preview, nothing to start
        Jsoup.parse(contentAsString(preview(admin, Some(10), Seq(proven, unproven), Some(unproven))))
          .select("section.account-join-preview").size === 0
        val p = AccountJoin.preview(User.withMemberships(Seq(admin)).head, unproven, proven, Some(10))
        p.refusals.map(_.reason) must contain("kept_unverified")
      }
    }
  }

  // ── refusals ───────────────────────────────────────────────────────────────

  "a join [A-26]" should {

    "be refused on any collision (vote, vote set aside, across the two), nothing written" in {
      testDbApp { implicit app =>
        createContests(10)
        val root = person("Root", "root@example.com", None, root = true)
        val k = person("K", "k@example.org", Some(10))
        val j = person("J", "j@example.org", Some(10))
        val r = roundOf(10)
        assign(r, k, j)
        val (p1, p2) = (image().pageId, image().pageId)
        val other = person("Other", "other@example.org", Some(10))
        vote(r, other, p1, 1)
        vote(r, other, p2, 1)
        aside(r, k, p1, 1)
        aside(r, j, p1, -1) // two votes set aside, one of each
        val mail = new RecordingMail
        val h = new TestJoin(mail)
        refusals(serviceStart(h, root, Some(10), k, j)._1) === Seq("collisions")
        DB.autoCommit(implicit autoSession => sql"DELETE FROM vote_aside WHERE jury_id = ${j.getId}".update())
        // across the tables: one's vote, the other's set aside (refused whatever the rates)
        vote(r, j, p1, 0)
        refusals(serviceStart(h, root, Some(10), k, j)._1) === Seq("collisions")
        joinJobs() must beEmpty
        rows("vote", r.getId).count(_._2 == j.getId) === 1
        stored(j).deletedAt must beNone
      }
    }

    "run only the preview the performer saw, and refuse two joins of one account" in {
      testDbApp { implicit app =>
        createContests(10)
        val root = person("Root", "root@example.com", None, root = true)
        val k = person("K", "k@example.org", Some(10))
        val j = person("J", "j@example.org", Some(10))
        val x = person("X", "x@example.org", Some(10))
        val r = roundOf(10)
        assign(r, j)
        val h = new TestJoin(new RecordingMail)
        val jobs = TestRoundServices.inlineScheduler(h)
        val loaded = User.withMemberships(Seq(root)).head
        val p = AccountJoin.preview(loaded, k, j, Some(10))
        // a vote since the page was shown: shown again
        vote(r, j, image().pageId, 1)
        refusals(AccountJoin.start(loaded, Some(10), k.getId, j.getId, p.signature, notify = false, jobs)) must contain("changed")
        // a join that failed holds both accounts (a frozen round fails its step): no second one
        DB.autoCommit(implicit autoSession => sql"UPDATE round SET freeze_count = 1 WHERE id = ${r.getId}".update())
        val failed = startedJob(serviceStart(h, root, Some(10), k, j)._1)
        failed.status === JobStatus.Failed
        refusals(serviceStart(h, root, Some(10), x, j)._1) must contain("in_join")
        refusals(serviceStart(h, root, Some(10), k, x)._1) must contain("in_join")
        // the retiring account signs in no more
        val signIn = route(app, FakeRequest(POST, "/auth").withFormUrlEncodedBody("login" -> j.email, "password" -> "secret")
          .withCSRFToken).get
        status(signIn) === FORBIDDEN
        contentAsString(signIn) must contain("being joined into another account")
        // the kept one does (a juror with no open round: the page saying so, signed in)
        val keptSignIn = route(app, FakeRequest(POST, "/auth").withFormUrlEncodedBody("login" -> k.email, "password" -> "secret")
          .withCSRFToken).get
        status(keptSignIn) must not(beEqualTo(FORBIDDEN))
        play.api.test.Helpers.session(keptSignIn).get(Secured.UserName) must beSome
      }
    }

    "while it holds the accounts: round creation drops the retiring one, keeps the kept one; adding either is refused" in {
      testDbApp { implicit app =>
        val contest = createContests(10).head
        val root = person("Root", "root@example.com", None, root = true)
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        val k = person("K", "k@example.org", Some(10))
        val j = person("J", "j@example.org", Some(10))
        val y = person("Y", "y@example.org", Some(10))
        val r = roundOf(10)
        assign(r, j)
        DB.autoCommit(implicit autoSession => sql"UPDATE round SET freeze_count = 1 WHERE id = ${r.getId}".update())
        val h = new TestJoin(new RecordingMail)
        startedJob(serviceStart(h, root, Some(10), k, j)._1).status === JobStatus.Failed
        // round creation (users-first): the retiring account left out, the kept one in
        val imgs = (1 to 2).map(_ => image())
        CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, imgs)
        val created = TestRoundServices.roundService().createNewRound(
          Round(None, 0, Some("New"), contest.getId, distribution = 0, rates = Round.binaryRound, active = true),
          Seq(k.getId, j.getId, y.getId))
        roundUsers(created.getId).map(_._1) === Seq(k.getId, y.getId).sorted
        // adding either to a round (round-first): refused. k is in the round: stopped, then started
        // again by the restart page (an active juror would be refused as already in the round)
        RoundUser.setActive(created.getId, k.getId, active = false)
        val rebalance = new RoundRebalanceService(TestRoundServices.inlineScheduler(new RoundRebalanceHandler(play.api.Configuration())))
        val open = Round.findById(created.getId).get
        val shown = rebalance.preview(open, 0L).signature
        rebalance.add(open, k.getId, admin, shown, restart = true) === RoundRebalanceService.Refused(
          services.jobs.JobError(RoundRebalanceHandler.JoiningKey, Seq(k.getId.toString)))
        roundUsers(created.getId).map(_._1) === Seq(k.getId, y.getId).sorted
        // a round_rebalance adding an account refuses a join's start
        DB.autoCommit { implicit autoSession =>
          sql"""INSERT INTO job (kind, contest_id, status, params, state, attempts, created_at)
                VALUES (${RoundRebalanceHandler.Kind}, 10, 'queued', ${s"""{"add": ${y.getId}}"""}, '{}', 0, NOW(3))""".update()
        }
        refusals(serviceStart(h, root, Some(10), y, person("Z", "z@example.org", Some(10)))._1) must contain("rebalance")
      }
    }
  }

  // ── the job ────────────────────────────────────────────────────────────────

  "the account_join job [A-26]" should {

    "move votes, votes set aside, criteria rates, assignments, comments and memberships over several rounds, then retire the other" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        UserContestJdbc.createMembership(admin.getId, 20, "admin")
        val k = person("Kept", "kept@example.org", Some(10), "jury", wiki = Some("Shared Wiki"))
        val j = person("Joined", "joined@example.org", Some(10), "organizer", wiki = Some("shared_wiki"))
        UserContestJdbc.createMembership(j.getId, 20, "jury")
        val other = person("Other", "other@example.org", Some(10))
        DB.autoCommit(implicit autoSession => sql"UPDATE users SET wiki_account_verified_at = NOW(3) WHERE id = ${j.getId}".update())
        UserIdentityJdbc.create(UserIdentity("wikimedia", "w-j", j.getId, None, Some("Shared Wiki")))
        ApiTokenJdbc.create(k.getId, "k", "hash-k", "pk", None)
        ApiTokenJdbc.create(j.getId, "j", "hash-j", "pj", None)
        AccountTokenJdbc.create(k.getId, AccountTokenJdbc.Invitation, "inv-k", k.email, Some(20), Some("jury"), None, 3600)
        val versions = Seq(k, j).map(stored(_).authVersion)

        // round 1 (binary): both assigned, J stopped; a merge, J's rated and unrated votes
        val r1 = roundOf(10, "One")
        assign(r1, k, j, other)
        RoundUser.setActive(r1.getId, j.getId, active = false)
        val Seq(a, b, c, d) = (1 to 4).map(_ => image().pageId)
        vote(r1, k, a, 0); vote(r1, j, a, 0) // one assignment
        vote(r1, j, b, 1)
        vote(r1, j, c, 0)
        vote(r1, other, d, 1); aside(r1, j, d, -1)
        // round 2 (rated, criteria): only J
        val r2 = roundOf(20, "Two", Round.ratesById(10))
        DB.autoCommit(implicit autoSession => RoundCriterion.insert(r2.getId, Seq(1L, 2L)))
        assign(r2, j)
        val e = image().pageId
        val rated = vote(r2, j, e, 7)
        DB.autoCommit(implicit autoSession => sql"INSERT INTO criteria_rate (vote_id, criterion_id, rate) VALUES ($rated, 1, 3), ($rated, 2, 4)".update())
        CommentJdbc.create(j.getId, j.fullname, r2.getId, Some(20), e, "a comment")
        // vote_orphan is not among the tables the shared app truncates: removed again at the end
        sql"DELETE FROM vote_orphan WHERE page_id = 987655".update()
        sql"INSERT INTO vote_orphan (page_id, jury_id, round_id, rate) VALUES (987655, ${j.getId}, 999999, 1)".update()

        val mail = new RecordingMail
        val h = new TestJoin(mail)
        val (outcome, log) = accountLog(serviceStart(h, admin, Some(10), k, j)._1)
        val job = startedJob(outcome)
        job.status === JobStatus.Done
        val st = AccountJoinHandler.stateOf(job)

        rows("vote", r1.getId) === Seq((a, k.getId, 0), (b, k.getId, 1), (c, k.getId, 0), (d, other.getId, 1)).sorted
        rows("vote_aside", r1.getId) === Seq((d, k.getId, -1))
        roundUsers(r1.getId) === Seq(k.getId -> true, other.getId -> true).sortBy(_._1)
        rows("vote", r2.getId) === Seq((e, k.getId, 7))
        VoteJdbc.findBy(e, k.getId, r2.getId).map(_.getId) === Some(rated)
        sql"SELECT criterion_id, rate FROM criteria_rate WHERE vote_id = $rated ORDER BY criterion_id"
          .map(rs => rs.long(1) -> rs.int(2)).list() === Seq(1L -> 3, 2L -> 4)
        roundUsers(r2.getId) === Seq(k.getId -> true)
        sql"SELECT user_id FROM comment".map(_.long(1)).list() === Seq(k.getId)
        sql"SELECT jury_id FROM vote_orphan".map(_.long(1)).list() === Seq(j.getId)
        Seq(r1, r2).flatMap(r => RoundImageInvariants.violations(r.getId)) must beEmpty
        (st.votes, st.aside, st.mergedVotes, st.roundUsers) === ((3L, 1L, 1L, 2L))

        // memberships: the higher-access role wins, one row per contest
        UserContestJdbc.findByUser(k.getId).map(m => m.contestId -> m.role).sorted === Seq(10L -> "organizer", 20L -> "jury")
        UserContestJdbc.findByUser(j.getId) must beEmpty
        // the CoI: the same name, verified by the joined account, so verified on the kept one
        stored(k).wikiAccount === Some("Shared Wiki")
        stored(k).wikiAccountVerified must beTrue
        // retired: deleted, the email freed, sessions of both ended, tokens and links revoked
        val retired = stored(j)
        retired.deletedAt must beSome
        Option(retired.email).filter(_.nonEmpty) must beNone
        Seq(k, j).map(stored(_).authVersion).zip(versions).forall { case (now, before) => now != before } must beTrue
        sql"SELECT COUNT(*) FROM api_token WHERE revoked_at IS NULL".map(_.long(1)).single().get === 0L
        AccountJoinJdbc.liveLinks(k.getId) === 0L
        UserIdentityJdbc.findByUser(j.getId) must beEmpty
        UserIdentityJdbc.findByUser(k.getId) must beEmpty
        // the record and the logs
        AccountChangeJdbc.findByUser(k.getId, None, 50).map(_.field) must contain(AccountChangeJdbc.AccountJoined)
        AccountChangeJdbc.findByUser(j.getId, None, 50).map(_.field) must contain(AccountChangeJdbc.AccountJoined)
        sql"SELECT state FROM account_join_request".map(_.string(1)).list() === Seq(AccountJoinRequestJdbc.Done)
        log.exists(l => l.startsWith(s"account_joined keptAccountId=${k.getId} joinedAccountId=${j.getId}")) must beTrue
        log.mkString must not(contain("@example.org"))
        mail.sent must beEmpty
        // the duplicates report no longer pairs them
        services.DuplicateAccounts.report(User.withMemberships(Seq(admin)).head, Some(10)).groups must beEmpty
        sql"DELETE FROM vote_orphan WHERE page_id = 987655".update()
        ok
      }
    }

    "mail both addresses only when the performer ticked it" in {
      testDbApp { implicit app =>
        createContests(10)
        val root = person("Root", "root@example.com", None, root = true)
        val k = person("Kept", "kept@example.org", Some(10))
        val j = person("Joined", "joined@example.org", Some(10))
        val mail = new RecordingMail
        startedJob(serviceStart(new TestJoin(mail), root, Some(10), k, j, notify = true)._1).status === JobStatus.Done
        mail.recipients.sorted === Seq("joined@example.org", "kept@example.org")
        mail.sent.map(_._4).mkString must not(contain("http"))
        mail.sent.find(_._2.email == "kept@example.org").get._4 must contain("j***@example.org")
      }
    }

    "run from the page: the preview, the start, the join's page" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        val k = person("Kept", "kept@example.org", Some(10))
        val j = person("Joined", "joined@example.org", Some(10))
        val r = roundOf(10)
        assign(r, j)
        vote(r, j, image().pageId, 1)
        val sig = signatureOf(contentAsString(preview(admin, Some(10), Seq(k, j), Some(k))), j)
        val started = start(admin, Some(10), k, j, sig)
        status(started) === SEE_OTHER
        val jobPage = redirectLocation(started).get
        val page = route(app, FakeRequest(GET, jobPage).withSession(session(admin, Some(10)): _*)).get
        status(page) === OK
        contentAsString(page) must contain("was joined into")
        stored(j).deletedAt must beSome
        rows("vote", r.getId).map(_._2) === Seq(k.getId)
        // another contest's admin: nothing of it
        createContests(30)
        val admin30 = person("Admin30", "admin30@example.com", Some(30), "admin")
        status(route(app, FakeRequest(GET, jobPage).withSession(session(admin30, Some(30)): _*)).get) === NOT_FOUND
      }
    }

    "be cancelled only before its first round step, and retried after a failed step" in {
      testDbApp { implicit app =>
        createContests(10)
        val root = person("Root", "root@example.com", None, root = true)
        val k = person("K", "k@example.org", Some(10))
        val j = person("J", "j@example.org", Some(10))
        val (r1, r2) = (roundOf(10, "One"), roundOf(10, "Two"))
        assign(r1, j); assign(r2, j)
        vote(r1, j, image().pageId, 1)
        vote(r2, j, image().pageId, 1)
        val h = new TestJoin(new RecordingMail)
        // round 1 frozen: the first round step fails, nothing moved: Cancel frees both accounts
        DB.autoCommit(implicit autoSession => sql"UPDATE round SET freeze_count = 1 WHERE id = ${r1.getId}".update())
        val (first, jobs) = serviceStart(h, root, Some(10), k, j)
        val failed = startedJob(first)
        failed.status === JobStatus.Failed
        failed.failure.map(_.key) === Some(AccountJoinHandler.RoundBusyKey)
        jobs.cancelOrRefuse(failed.id).map(_.map(_.status)) === Right(Some(JobStatus.Cancelled))
        AccountJoinJdbc.joining(Seq(k.getId, j.getId)) must beEmpty
        // round 2 frozen instead: round 1 moves, round 2 fails; Cancel refused, Retry finishes
        DB.autoCommit { implicit autoSession =>
          sql"UPDATE round SET freeze_count = 0 WHERE id = ${r1.getId}".update()
          sql"UPDATE round SET freeze_count = 1 WHERE id = ${r2.getId}".update()
        }
        val (second, jobs2) = serviceStart(h, root, Some(10), k, j)
        val half = startedJob(second)
        half.status === JobStatus.Failed
        rows("vote", r1.getId).map(_._2) === Seq(k.getId)
        rows("vote", r2.getId).map(_._2) === Seq(j.getId)
        jobs2.cancelOrRefuse(half.id) === Left(JobScheduler.CancelRefused(AccountJoinHandler.CancelRefusedKey))
        JobJdbc.findById(half.id).get.status === JobStatus.Failed
        DB.autoCommit(implicit autoSession => sql"UPDATE round SET freeze_count = 0 WHERE id = ${r2.getId}".update())
        jobs2.retry(half.id).map(_.id) === Right(half.id)
        JobJdbc.findById(half.id).get.status === JobStatus.Done
        rows("vote", r2.getId).map(_._2) === Seq(k.getId)
        stored(j).deletedAt must beSome
      }
    }

    "move a vote of the retiring account cast during the join, and sweep one that lands after its round's step" in {
      testDbApp { implicit app =>
        createContests(10)
        val root = person("Root", "root@example.com", None, root = true)
        val k = person("K", "k@example.org", Some(10))
        val j = person("J", "j@example.org", Some(10))
        val (r1, r2) = (roundOf(10, "One"), roundOf(10, "Two"))
        assign(r1, j); assign(r2, j)
        val p1 = image().pageId
        val p2 = image().pageId
        val late = image().pageId
        vote(r1, j, p1, 0)
        vote(r2, j, p2, 0)
        val h = new TestJoin(new RecordingMail)
        // during round 1's step, J's vote in round 2 commits (another connection): round 2's step moves it
        h.atRound = Some(roundId => if (roundId == r1.getId) VoteJdbc.rate(p2, j.getId, r2.getId, 1)(AutoSession))
        // during the last step, a row of J lands in round 1 (moved already): the sweep moves it too
        h.atFinish = Some(() => DB.autoCommit { implicit autoSession =>
          sql"INSERT INTO vote (page_id, jury_id, round_id, rate) VALUES ($late, ${j.getId}, ${r1.getId}, 1)".update()
        })
        val job = startedJob(serviceStart(h, root, Some(10), k, j)._1)
        job.status === JobStatus.Done
        rows("vote", r2.getId) === Seq((p2, k.getId, 1))
        rows("vote", r1.getId).map(_._2).distinct === Seq(k.getId)
        rows("vote", r1.getId).map(_._1) must contain(late)
        stored(j).deletedAt must beSome
        // adding the kept account to a round while the join ran: refused (read in its step)
        AccountJoinJdbc.joining(Seq(k.getId)) must beEmpty
      }
    }

    "refuse adding either account to a round while its step runs" in {
      testDbApp { implicit app =>
        val contest = createContests(10).head
        val root = person("Root", "root@example.com", None, root = true)
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        val k = person("K", "k@example.org", Some(10))
        val j = person("J", "j@example.org", Some(10))
        val y = person("Y", "y@example.org", Some(10))
        val imgs = (1 to 2).map(_ => image())
        CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, imgs)
        val open = TestRoundServices.roundService().createNewRound(
          Round(None, 0, Some("Open"), contest.getId, distribution = 0, rates = Round.binaryRound, active = true), Seq(y.getId))
        val r = roundOf(10, "Joined's")
        assign(r, j)
        vote(r, j, image().pageId, 1)
        val rebalance = new RoundRebalanceService(TestRoundServices.inlineScheduler(new RoundRebalanceHandler(play.api.Configuration())))
        val h = new TestJoin(new RecordingMail)
        @volatile var outcomes = Seq.empty[RoundRebalanceService.Outcome]
        h.atRound = Some(_ => {
          val fresh = Round.findById(open.getId).get
          outcomes = Seq(k, j).map(u => rebalance.add(fresh, u.getId, admin, rebalance.preview(fresh, 0L).signature))
        })
        startedJob(serviceStart(h, root, Some(10), k, j)._1).status === JobStatus.Done
        outcomes.collect { case RoundRebalanceService.Refused(e) => e.key } ===
          Seq(RoundRebalanceHandler.JoiningKey, RoundRebalanceHandler.JoiningKey)
        roundUsers(open.getId).map(_._1) === Seq(y.getId)
      }
    }
  }

  // ── the review's fixes ─────────────────────────────────────────────────────

  /** A join of `placeholder` into a new account, held (failed at a frozen round). */
  private def heldJoinFor(by: User, placeholder: User)(implicit app: Application): Job = {
    val keeper = person("Keeper", s"keeper-${placeholder.getId}@example.org", Some(10))
    heldJoin(by, keeper, placeholder, 10)._1
  }

  /** A join held: started by `by` and failed at its first round (a frozen round), nothing moved. */
  private def heldJoin(by: User, k: User, j: User, contestId: Long)(implicit app: Application): (Job, JobScheduler, Round) = {
    val r = roundOf(contestId, "Held")
    assign(r, j)
    vote(r, j, image().pageId, 1)
    DB.autoCommit(implicit autoSession => sql"UPDATE round SET freeze_count = 1 WHERE id = ${r.getId}".update())
    val (outcome, jobs) = serviceStart(new TestJoin(new RecordingMail), by, Some(contestId), k, j)
    (startedJob(outcome), jobs, r)
  }

  "merges and collisions (J1, the review)" should {

    "merge a rated vote with an unrated assignment either way, keeping the vote; collide only on two votes" in {
      testDbApp { implicit app =>
        createContests(10)
        val root = person("Root", "root@example.com", None, root = true)
        val k = person("K", "k@example.org", Some(10))
        val j = person("J", "j@example.org", Some(10))
        val r = roundOf(10)
        assign(r, k, j)
        val Seq(a, b, c) = (1 to 3).map(_ => image().pageId)
        vote(r, k, a, 0); vote(r, j, a, 1)  // the joined account's vote moves, the kept's assignment goes
        vote(r, k, b, -1); vote(r, j, b, 0) // the kept account's vote stays, the joined's assignment goes
        vote(r, k, c, 0); vote(r, j, c, 0)  // one assignment
        val p = AccountJoin.preview(User.withMemberships(Seq(root)).head, k, j, Some(10))
        (p.collisionCount, p.merges, p.refusals) === ((0, 3, Nil))
        startedJob(serviceStart(new TestJoin(new RecordingMail), root, Some(10), k, j)._1).status === JobStatus.Done
        rows("vote", r.getId) === Seq((a, k.getId, 1), (b, k.getId, -1), (c, k.getId, 0))
        RoundImageInvariants.violations(r.getId) must beEmpty
      }
    }

    "fail a round whose merge pair the kept account rated mid-join, writing nothing there; root abandons it" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val root = person("Root", "root@example.com", None, root = true)
        val k = person("K", "k@example.org", Some(10))
        val j = person("J", "j@example.org", Some(10))
        // round 1, moved before the failure, is in contest 20, where only J is a member (a juror)
        UserContestJdbc.createMembership(j.getId, 20, "jury")
        val (r1, r2) = (roundOf(20, "One"), roundOf(10, "Two"))
        assign(r1, j); assign(r2, k, j)
        val p1 = image().pageId
        val p2 = image().pageId
        vote(r1, j, p1, 1)
        vote(r2, k, p2, 0); vote(r2, j, p2, 1)
        val h = new TestJoin(new RecordingMail)
        // during round 1's step the kept account rates its assignment in round 2: two votes now
        h.atRound = Some(roundId => if (roundId == r1.getId) VoteJdbc.rate(p2, k.getId, r2.getId, -1)(AutoSession))
        val failed = startedJob(serviceStart(h, root, Some(10), k, j)._1)
        failed.status === JobStatus.Failed
        failed.failure.map(_.key) === Some(AccountJoinHandler.CollisionKey)
        rows("vote", r1.getId) === Seq((p1, k.getId, 1))
        rows("vote", r2.getId) === Seq((p2, k.getId, -1), (p2, j.getId, 1)).sortBy(_._2)
        // after a round step: no Cancel; an admin can't abandon, root can
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        status(route(app, FakeRequest(POST, s"/admin/accounts/join/${failed.id}/abandon")
          .withSession(session(admin, Some(10)): _*)).get) === FORBIDDEN
        val (abandoned, log) = accountLog {
          val r = route(app, FakeRequest(POST, s"/admin/accounts/join/${failed.id}/abandon")
            .withSession(session(root, None): _*)).get
          status(r)
          r
        }
        status(abandoned) === SEE_OTHER
        JobJdbc.findById(failed.id).get.status === JobStatus.Cancelled
        AccountJoinJdbc.joining(Seq(k.getId, j.getId)) must beEmpty
        stored(j).deletedAt must beNone
        rows("vote", r1.getId) === Seq((p1, k.getId, 1))
        Seq(k, j).forall(u => AccountChangeJdbc.findByUser(u.getId, None, 10)
          .exists(c => c.field == AccountChangeJdbc.AccountJoined && c.comment.exists(_.startsWith("abandoned")))) must beTrue
        log.exists(l => l.startsWith(s"account_join_abandoned jobId=${failed.id}") && l.endsWith("contestsMerged=20")) must beTrue
        // the kept account votes what it now holds: J's membership of round 1's contest, logged
        UserContestJdbc.find(k.getId, 20).map(_.role) === Some("jury")
        UserContestJdbc.find(j.getId, 20).map(_.role) === Some("jury")
        AccountChangeJdbc.findByUser(k.getId, Some(20), 10).exists(c => c.field == AccountChangeJdbc.Role &&
          c.newValue.contains("jury") && c.comment.exists(_.contains("abandoned join"))) must beTrue
        // a second abandon: refused (not failed any more)
        AccountJoin.abandon(root, failed.id) === Left(AccountJoin.AbandonRefused("account.join.abandon.not.failed"))
      }
    }
  }

  "while a join holds the accounts (the review)" should {

    "refuse memberships, role and password changes, link acceptances and API tokens for either; sign the retiring one out" in {
      testDbApp { implicit app =>
        val contest20 = createContests(10, 20).last
        val root = person("Root", "root@example.com", None, root = true)
        val admin = person("Admin", "admin@example.com", Some(10), "admin")
        UserContestJdbc.createMembership(admin.getId, 20, "admin")
        val k = person("K", "k@example.org", Some(10))
        val j = person("J", "j@example.org", Some(10))
        val (_, token) = services.AccountTokenService.create(j.getId, AccountTokenJdbc.Invitation, j.email, Some(20L),
          Some("jury"), admin.id)
        val sessionBefore = stored(j)
        val held = heldJoin(root, k, j, 10)._1
        held.status === JobStatus.Failed
        val mail = new RecordingMail
        implicit val messages: play.api.i18n.Messages = messagesOf
        // a membership: neither account joins a contest
        Seq(k, j).map(u => userService(mail).addAccountToContest(admin, u.getId, contest20, "jury").added)
          .forall(_.isInstanceOf[services.UserService.InJoin]) must beTrue
        UserContestJdbc.find(k.getId, 20) must beNone
        // a role change on Edit user
        val edit = route(app, FakeRequest(POST, "/users/save").withSession(session(admin, Some(10)): _*)
          .withFormUrlEncodedBody("id" -> k.getId.toString, "fullname" -> k.fullname, "email" -> k.email,
            "emailWas" -> k.email, "wikiAccount" -> "", "roles" -> "organizer", "contest" -> "10", "lang" -> "en")).get
        contentAsString(edit) must contain("is being joined with another account")
        UserContestJdbc.find(k.getId, 10).map(_.role) === Some("jury")
        // an invitation's acceptance
        services.AccountTokenService.acceptPasswordLink(token, hash("new-pass")) ===
          Left(services.AccountTokenService.InJoin)
        UserContestJdbc.find(j.getId, 20) must beNone
        // a password set by root
        services.AccountTokenService.setPasswordByAdmin(root, k.getId, Some(10), hash("x"), wikimediaSignIn = false) ===
          Left(services.ShareLinkService.InJoin)
        // an API token
        services.ApiTokenService.createOnce(stored(k), "t", None, "nonce-1") ===
          services.ApiTokenService.CreateOutcome.SessionEnded
        // a Google or Wikimedia sign-in accepting a live link (an account without a password): refused,
        // with its own reason
        val placeholder = User.create(User("P", "p@example.org", None, Set("jury"), None, Some(10L)))
        services.AccountTokenService.create(placeholder.getId, AccountTokenJdbc.Invitation, placeholder.email, Some(20L),
          Some("jury"), admin.id)
        val (_, providerLog) = accountLog {
          val other = heldJoinFor(root, placeholder)
          services.AccountTokenService.acceptByProvider[String](placeholder.getId, placeholder.email)((_, _) => Right(())) ===
            Left(None)
          other
        }
        providerLog.exists(_ == s"account_link_by_provider_refused reason=account_in_join accountId=${placeholder.getId}") must beTrue
        UserContestJdbc.find(placeholder.getId, 20) must beNone
        // the retiring account's session, as it was before the join: signed out; the kept one's is not
        val retiringNow = stored(j)
        Secured.sessionUser(j.email, None, Some(retiringNow.authVersion.toString), Some(j.getId.toString)) must beNone
        sessionBefore.authVersion must not(beEqualTo(retiringNow.authVersion))
        val keptNow = stored(k)
        Secured.sessionUser(k.email, None, Some(keptNow.authVersion.toString), Some(k.getId.toString)) must beSome
      }
    }
  }

  "abandoning a join (the review)" should {

    "need the CSRF token" in {
      withCsrfApp { app =>
        implicit val a: Application = app
        createContests(41)
        val root = person("Root", "root41@example.com", None, root = true)
        val k = person("K41", "k41@example.org", Some(41))
        val j = person("J41", "j41@example.org", Some(41))
        val (r1, r2) = (roundOf(41, "One"), roundOf(41, "Two"))
        assign(r1, j); assign(r2, j)
        vote(r1, j, image().pageId, 1)
        vote(r2, j, image().pageId, 1)
        // round 2 frozen: round 1 moves, then the job fails (no Cancel any more)
        DB.autoCommit(implicit autoSession => sql"UPDATE round SET freeze_count = 1 WHERE id = ${r2.getId}".update())
        val failed = startedJob(serviceStart(new TestJoin(new RecordingMail), root, Some(41), k, j)._1)
        failed.status === JobStatus.Failed
        val refused = route(app, FakeRequest(POST, s"/admin/accounts/join/${failed.id}/abandon")
          .withHeaders(COOKIE -> "PLAY_SESSION=x", ACCEPT -> "text/html")
          .withSession(Secured.UserName -> root.email, "csrfToken" -> csrfToken(app))).get
        status(refused) === FORBIDDEN
        JobJdbc.findById(failed.id).get.status === JobStatus.Failed
        AccountJoinJdbc.joining(Seq(j.getId)) must contain(j.getId)
      }
    }
  }

  "a join's performer and its job's page (the review)" should {

    "be finished by an admin who retries it when the performer lost the right; refused for one who doesn't qualify" in {
      testDbApp { implicit app =>
        createContests(10)
        val adminA = person("Admin A", "admin.a@example.com", Some(10), "admin")
        val adminB = person("Admin B", "admin.b@example.com", Some(10), "admin")
        val k = person("K", "k@example.org", Some(10))
        val j = person("J", "j@example.org", Some(10))
        val (held, jobs, r) = heldJoin(adminA, k, j, 10)
        DB.autoCommit(implicit autoSession => sql"UPDATE round SET freeze_count = 0 WHERE id = ${r.getId}".update())
        // the performer is an admin no more
        DB.autoCommit(implicit autoSession =>
          sql"UPDATE user_contest SET role = 'jury' WHERE user_id = ${adminA.getId} AND contest_id = 10".update())
        // a retry with no qualifying user fails at the last step, after the round moved
        jobs.retry(held.id).isRight must beTrue
        val stuck = JobJdbc.findById(held.id).get
        stuck.status === JobStatus.Failed
        stuck.failure.map(_.key) === Some(AccountJoinHandler.PerformerKey)
        stored(j).deletedAt must beNone
        // Admin B retries through the job's page: finished for B
        val (retried, log) = accountLog {
          val r = route(app, FakeRequest(POST, s"/admin/jobs/${held.id}/retry")
            .withSession(session(adminB, Some(10)): _*)).get
          status(r)
          r
        }
        status(retried) === SEE_OTHER
        // the app's own scheduler queued it again: it runs inline there
        val done = JobJdbc.findById(held.id).get
        done.status === JobStatus.Done
        AccountJoinHandler.stateOf(done).finishedBy === adminB.id
        stored(j).deletedAt must beSome
        log.exists(l => l.startsWith("account_joined") && l.contains(s"byAccountId=${adminB.getId} startedBy=${adminA.getId}")) must beTrue
      }
    }

    "not show, cancel or retry a join for an admin of its contest who fails the performer rule" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val adminBoth = person("Admin Both", "admin.both@example.com", Some(10), "admin")
        UserContestJdbc.createMembership(adminBoth.getId, 20, "admin")
        val admin10 = person("Admin Ten", "admin10@example.com", Some(10), "admin")
        val k = person("K", "k@example.org", Some(10))
        val j = person("J", "j@example.org", Some(10))
        UserContestJdbc.createMembership(j.getId, 20, "jury")
        val (held, _, _) = heldJoin(adminBoth, k, j, 10)
        def as(u: User, method: String, path: String) =
          route(app, FakeRequest(method, path).withSession(session(u, Some(10)): _*)).get
        status(as(admin10, GET, s"/admin/jobs/${held.id}")) === NOT_FOUND
        status(as(admin10, POST, s"/admin/jobs/${held.id}/cancel")) === NOT_FOUND
        status(as(admin10, POST, s"/admin/jobs/${held.id}/retry")) === NOT_FOUND
        status(as(admin10, GET, s"/admin/accounts/join/${held.id}")) === NOT_FOUND
        JobJdbc.findById(held.id).get.status === JobStatus.Failed
        // the performer: its status, and Cancel (nothing moved yet) through the job's page
        status(as(adminBoth, GET, s"/admin/jobs/${held.id}")) === OK
        status(as(adminBoth, POST, s"/admin/jobs/${held.id}/cancel")) === SEE_OTHER
        JobJdbc.findById(held.id).get.status === JobStatus.Cancelled
      }
    }
  }
}
