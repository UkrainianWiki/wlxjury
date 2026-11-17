package controllers

import controllers.CsrfTestApp.{token => csrfToken, withCsrfApp}
import db.scalikejdbc._
import org.apache.pekko.stream.Materializer
import org.intracer.wmua.{CommentJdbc, ContestJury, Image, Vote}
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import play.api.{Application, Configuration}
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test._
import scalikejdbc._
import services.AccountRetirement._
import services.distribution.{RoundRebalanceHandler, RoundRebalanceService}
import services.{AccountRetirement, TestRoundServices, UserService}

import java.util.concurrent.CountDownLatch
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** Retiring an empty duplicate account (docs/plans/2026-11-16-existing-accounts.md, "Retiring an
  * account", PR A-4; decision A3): the Edit user panel's unticked "Retire the account being
  * edited" with "Add that account to this contest instead", who may retire which account, what
  * makes an account not empty, the round job refusal (M4: a job, never a round held in review), the one transaction (memberships and
  * links gone, the email freed, sessions ended, sign-in refused), the change log and the
  * `security.account` lines, and the races with `round_user` inserts (round creation and adding a
  * juror skip or refuse a retired account; a retirement after an assignment is refused).
  */
class RetireAccountSpec extends PlaySpecification with PlayTestDb with AccountTestSupport {

  sequential

  private implicit val autoSession: DBSession = AutoSession

  private def session(user: User, contestId: Option[Long]): Seq[(String, String)] =
    Seq(Secured.UserName -> user.email) ++ contestId.map(Secured.CurrentContestId -> _.toString)

  private def person(name: String, email: String, contestId: Option[Long], role: String = "jury",
      wiki: Option[String] = None, root: Boolean = false): User =
    User.create(User(name, email, None, if (root) Set(User.ROOT_ROLE) else Set(role), Some(hash("secret")), contestId,
      wikiAccount = wiki, isRoot = root, emailVerifiedAt = Some(java.time.ZonedDateTime.now)))

  private def form(user: User, contestId: Long, role: String, changes: (String, String)*): Seq[(String, String)] =
    (Map("id" -> user.getId.toString, "fullname" -> user.fullname, "email" -> user.email,
      "emailWas" -> user.email, "wikiAccount" -> user.wikiAccount.getOrElse(""), "roles" -> role,
      "contest" -> contestId.toString, "lang" -> "en") ++ changes).toSeq

  private def save(mail: RecordingMail, by: User, contestId: Long, fields: Seq[(String, String)])(implicit
      app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(userController(mail).saveUser(), FakeRequest(POST, "/users/save").withSession(session(by, Some(contestId)): _*)
      .withFormUrlEncodedBody(fields: _*).withCSRFToken)
  }

  private def item(html: String, account: User): Element =
    Option(Jsoup.parse(html).selectFirst(s"#wiki-taken li[data-account-id=${account.getId}]"))
      .getOrElse(sys.error(s"no item for ${account.getId}"))

  private def submitted(add: Element): Seq[(String, String)] =
    add.select("input[type=hidden]").asScala.map(i => i.attr("name") -> i.attr("value")).toSeq ++
      add.select("select").asScala.map(s => s.attr("name") -> s.selectFirst("option[selected]").attr("value")) ++
      add.select("input[type=checkbox][checked]").asScala.map(i => i.attr("name") -> i.attr("value"))

  private def addExisting(by: User, contestId: Long, fields: Seq[(String, String)], mail: RecordingMail = new RecordingMail)(
      implicit app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(userController(mail).addExistingAccount(Some(contestId)), FakeRequest(POST, "/admin/users/add-existing")
      .withSession(session(by, Some(contestId)): _*).withFormUrlEncodedBody(fields: _*).withCSRFToken)
  }

  private def stored(u: User): User = User.findById(u.getId).get

  private def retired(u: User): Boolean = stored(u).deletedAt.nonEmpty

  /** An unused invitation link of the account into the contest. */
  private def invitation(u: User, contestId: Long, role: String = "jury"): Long =
    AccountTokenJdbc.create(u.getId, AccountTokenJdbc.Invitation, s"hash-${u.getId}-$contestId-$role", u.email,
      Some(contestId), Some(role), None, 3600)

  private def liveLinks(u: User): Long =
    sql"SELECT COUNT(*) FROM account_token WHERE user_id = ${u.getId} AND revoked_at IS NULL AND used_at IS NULL"
      .map(_.long(1)).single().getOrElse(0L)

  private var nextPage = 5000L

  private def roundOf(contestId: Long): Round =
    roundDao.create(Round(None, 1, Some("R"), contestId, rates = Round.binaryRound, active = true))

  private def image(): Image = {
    nextPage += 1
    val i = Image(nextPage, s"File:Retire$nextPage.jpg", None, None, 640, 480, None)
    imageDao.batchInsert(Seq(i))
    i
  }

  "who may retire which account [A-25]" should {

    "allow root and an admin of every contest the account is in, and refuse everyone and everything else" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val root = person("Root", "root@example.com", None, root = true)
        val otherRoot = person("Other Root", "root2@example.com", None, root = true)
        val admin10 = person("Admin Ten", "admin10@example.com", Some(10), "admin")
        val adminBoth = person("Admin Both", "adminboth@example.com", Some(10), "admin")
        UserContestJdbc.createMembership(adminBoth.getId, 20, "admin")
        val organizer = person("Org", "org@example.com", Some(10), "organizer")
        val juror = person("Juror", "juror@example.com", Some(10))
        val inTen = person("In Ten", "in.ten@example.org", Some(10))
        val inBoth = person("In Both", "in.both@example.org", Some(10))
        UserContestJdbc.createMembership(inBoth.getId, 20, "jury")
        val adminTarget = person("Admin Target", "admin.target@example.org", Some(10), "admin")
        val invitedAdmin = person("Invited Admin", "invited.admin@example.org", Some(10), "organizer")
        invitation(invitedAdmin, 10, "admin")

        def refusalOf(actor: User, account: User) = AccountRetirement.refusal(actor, stored(account), Some(10))

        // who may not
        refusalOf(admin10, inBoth) === Some(NotAllowed)
        refusalOf(organizer, inTen) === Some(NotAllowed)
        refusalOf(juror, inTen) === Some(NotAllowed)
        refusalOf(admin10, admin10) === Some(OwnAccount)
        refusalOf(root, root) === Some(OwnAccount)
        refusalOf(root, otherRoot) === Some(RootAccount)
        refusalOf(admin10, otherRoot) === Some(RootAccount)
        refusalOf(admin10, adminTarget) === Some(AdminAccount)
        refusalOf(admin10, invitedAdmin) === Some(AdminAccount)
        // refused: nothing written, logged by ids with the reason
        val (refused, lines) = accountLog(AccountRetirement.retire(admin10, adminTarget.getId, Some(10), None))
        refused === Left(AdminAccount)
        lines === Seq(s"account_retire_refused accountId=${adminTarget.getId} byAccountId=${admin10.getId} contestId=10 " +
          "reason=admin_account")
        AccountRetirement.retire(organizer, inTen.getId, Some(10), None) === Left(NotAllowed)
        AccountRetirement.retire(admin10, inBoth.getId, Some(10), None) === Left(NotAllowed)
        AccountRetirement.retire(root, otherRoot.getId, None, None) === Left(RootAccount)
        Seq(adminTarget, inTen, inBoth, otherRoot).map(retired) === Seq(false, false, false, false)
        UserContestJdbc.findByUser(adminTarget.getId).map(_.role) === Seq("admin")

        // who may: an admin of every contest the account is in, root (an admin account too)
        AccountRetirement.retire(admin10, inTen.getId, Some(10), None).map(_.memberships) === Right(1)
        AccountRetirement.retire(adminBoth, inBoth.getId, Some(10), None).map(_.memberships) === Right(2)
        AccountRetirement.retire(root, adminTarget.getId, Some(10), None).map(_.memberships) === Right(1)
        AccountRetirement.retire(root, invitedAdmin.getId, Some(10), None).map(_.links) === Right(1)
        Seq(inTen, inBoth, adminTarget, invitedAdmin).map(retired) === Seq(true, true, true, true)
        // once retired: gone
        AccountRetirement.retire(root, inTen.getId, Some(10), None) === Left(Gone)
        AccountRetirement.retire(root, 999999L, Some(10), None) === Left(Gone)

        // an account with no ties anywhere passes "every contest it is in" on an empty set: an
        // admin may retire only an account of the contest it is done from (review M1); root may
        val loose = person("Loose", "loose@example.org", None)
        val invitedOnly = person("Invited Only", "invited.only@example.org", None)
        invitation(invitedOnly, 10)
        refusalOf(admin10, loose) === Some(NotAllowed)
        AccountRetirement.retire(admin10, loose.getId, Some(10), None) === Left(NotAllowed)
        AccountRetirement.retire(admin10, loose.getId, None, None) === Left(NotAllowed)
        retired(loose) must beFalse
        // nor one of contest 10 retired "from" another contest
        val inTenToo = person("In Ten Too", "in.ten.too@example.org", Some(10))
        AccountRetirement.retire(adminBoth, inTenToo.getId, Some(20), None) === Left(NotAllowed)
        // a live invitation into the contest ties it
        AccountRetirement.retire(admin10, invitedOnly.getId, Some(10), None).map(_.links) === Right(1)
        AccountRetirement.retire(root, loose.getId, None, None).isRight must beTrue
      }
    }
  }

  "an account that is not empty [A-25]" should {

    "be refused for each of its votes, set-aside votes, orphans, rounds, comments, identities and API tokens" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = person("Admin Ten", "admin10@example.com", Some(10), "admin")
        def juror(n: Int) = person(s"Juror $n", s"juror$n@example.org", Some(10))
        val round = roundOf(10)
        val (voted, aside, orphan, assigned, commented, linked, tokened) =
          (juror(1), juror(2), juror(3), juror(4), juror(5), juror(6), juror(7))
        selectionDao.batchInsert(Seq(Vote(image().pageId, voted.getId, round.getId, 0)))
        sql"INSERT INTO vote_aside (page_id, jury_id, round_id, rate) VALUES (${image().pageId}, ${aside.getId}, ${round.getId}, 1)"
          .update()
        // vote_orphan is not among the tables the shared app truncates: removed again at the end
        sql"DELETE FROM vote_orphan WHERE page_id = 987654".update()
        sql"INSERT INTO vote_orphan (page_id, jury_id, round_id, rate) VALUES (987654, ${orphan.getId}, ${round.getId}, 1)"
          .update()
        round.addUsers(Seq(RoundUser(round.getId, assigned.getId, "jury", active = true)))
        CommentJdbc.create(commented.getId, commented.fullname, round.getId, Some(10), image().pageId, "a comment")
        sql"INSERT INTO user_identity (provider, subject, user_id) VALUES ('wikimedia', 'sub-6', ${linked.getId})".update()
        sql"""INSERT INTO api_token (user_id, name, token_hash, prefix)
              VALUES (${tokened.getId}, 'tool', ${"a" * 64}, 'wlx_aaaa')""".update()

        Seq(voted, aside, orphan, assigned, commented, linked, tokened)
          .map(u => AccountRetirement.retire(admin, u.getId, Some(10), None)) ===
          Seq(Left(HasVotes), Left(HasVotes), Left(HasVotes), Left(HasRounds), Left(HasComments), Left(HasIdentity),
            Left(HasApiToken))
        Seq(voted, aside, orphan, assigned, commented, linked, tokened).map(retired).distinct === Seq(false)
        UserContestJdbc.findByContest(10).size === 8

        // an identity unlinked and a revoked API token no longer count
        sql"UPDATE user_identity SET unlinked_at = NOW(3) WHERE user_id = ${linked.getId}".update()
        ApiTokenJdbc.revokeAll(tokened.getId)
        sql"DELETE FROM vote_orphan WHERE page_id = 987654".update()
        AccountRetirement.retire(admin, linked.getId, Some(10), None).isRight must beTrue
        AccountRetirement.retire(admin, tokened.getId, Some(10), None).isRight must beTrue
      }
    }

    "be refused while a round job is queued or running in its contests, not for a round held in review (M4)" in {
      testDbApp { implicit app =>
        createContests(10, 20)
        val root = person("Root", "root@example.com", None, root = true)
        val account = person("Empty", "empty@example.org", Some(10))
        def job(kind: String, contestId: Long, status: String) =
          sql"""INSERT INTO job (kind, contest_id, status, params, state) VALUES ($kind, $contestId, $status, '{}', '{}')"""
            .updateAndReturnGeneratedKey()
        // elsewhere, done, or another kind: no refusal
        job(RoundRebalanceHandler.Kind, 20, "running")
        job(services.distribution.RoundDistributionHandler.Kind, 10, "done")
        job(services.distribution.RoundDistributionHandler.Kind, 10, "failed")
        job("round_image_check", 10, "running")
        // a round of its contest held in review (distributing, paused, no job), another whose
        // creation failed (distributing, its job failed above): no refusal (the maintainer, 2026-11-17)
        val review = roundOf(10)
        sql"UPDATE round SET distributing = 1, active = 0, status = 'paused' WHERE id = ${review.getId}".update()
        Round.findById(review.getId).exists(_.inReview) must beTrue
        val failed = roundOf(10)
        sql"UPDATE round SET distributing = 1, active = 0, status = 'draft' WHERE id = ${failed.getId}".update()
        AccountRetirement.refusal(root, stored(account), Some(10)) must beNone

        // a job queued or running in its contest refuses
        val distribution = job(services.distribution.RoundDistributionHandler.Kind, 10, "queued")
        AccountRetirement.retire(root, account.getId, Some(10), None) === Left(RoundBusy)
        sql"UPDATE job SET status = 'running' WHERE id = $distribution".update()
        AccountRetirement.retire(root, account.getId, Some(10), None) === Left(RoundBusy)
        sql"UPDATE job SET status = 'cancelled' WHERE id = $distribution".update()
        val rebalance = job(RoundRebalanceHandler.Kind, 10, "running")
        AccountRetirement.retire(root, account.getId, Some(10), None) === Left(RoundBusy)
        sql"UPDATE job SET status = 'done' WHERE id = $rebalance".update()
        // an invitation's contest is the account's too: contest 20's job refuses now
        invitation(account, 20)
        AccountRetirement.retire(root, account.getId, Some(10), None) === Left(RoundBusy)
        sql"UPDATE job SET status = 'done' WHERE contest_id = 20".update()
        retired(account) must beFalse
        // with the round still held in review
        AccountRetirement.retire(root, account.getId, Some(10), None).isRight must beTrue
        Round.findById(review.getId).exists(_.inReview) must beTrue
      }
    }
  }

  "the Edit user panel's Retire [A-25]" should {

    "retire the edited account with the add: memberships, links and sessions gone, the address freed, logged, no mail" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val admin = person("Admin Ten", "admin@example.com", Some(10), "admin")
        val edited = person("Edited Person", "edited@example.org", Some(10), wiki = Some("Old Name"))
        val elsewhere = person("Shared Elsewhere", "else.where@example.org", Some(20), wiki = Some("Shared Wiki"))
        invitation(edited, 10, "organizer")
        val versionBefore = stored(edited).authVersion
        CoiMembers.forContest(10).keySet must contain(CoiMembers.normalize("Old Name"))
        val mail = new RecordingMail

        val html = contentAsString(save(mail, admin, 10, form(edited, 10, "jury", "wikiAccount" -> "Shared Wiki")))
        val add = item(html, elsewhere).selectFirst("form.wiki-taken-add")
        // unticked, with what it changes in the conflict-of-interest filter before the confirm
        val box = add.selectFirst("input[name=retireAccountId]")
        box.attr("type") === "checkbox"
        box.hasAttr("checked") must beFalse
        box.attr("value") === edited.getId.toString
        add.selectFirst(".wiki-taken-retire label").text === "Retire the account being edited (Edited Person)"
        add.selectFirst(".wiki-taken-retire-coi").text ===
          "The conflict-of-interest filter then no longer checks its wiki account Old Name, and checks Shared Wiki once that account is added."
        // as a browser sends it, unticked: the add only
        val unticked = submitted(add)
        unticked.map(_._1).toSet === Set("csrfToken", "accountId", "role")

        val (r, lines) = accountLog {
          val r = addExisting(admin, 10, unticked :+ ("retireAccountId" -> edited.getId.toString), mail)
          status(r)
          r
        }
        status(r) === SEE_OTHER
        flash(r).get("users-added") must beSome(contain("Account Edited Person was retired."))
        UserContestJdbc.find(elsewhere.getId, 10) must beSome(UserContest(elsewhere.getId, 10, "jury"))

        val gone = stored(edited)
        gone.deletedAt must beSome
        gone.email === ""
        gone.password must beNone
        gone.emailVerifiedAt must beNone
        gone.authVersion must not(beEqualTo(versionBefore))
        gone.fullname === "Edited Person"
        UserContestJdbc.findByUser(edited.getId) must beEmpty
        liveLinks(edited) === 0L
        // the change log: on the retired account (its address) and on the account added instead
        AccountChangeJdbc.findByUser(edited.getId, None, 10).map(c => (c.field, c.oldValue, c.comment, c.actorId)) ===
          Seq((AccountChangeJdbc.AccountRetired, Some("edited@example.org"), Some("#" + elsewhere.getId), admin.getId))
        AccountChangeJdbc.findByUser(elsewhere.getId, Some(10), 10).map(c => (c.field, c.comment)) must contain(
          (AccountChangeJdbc.AccountRetired, Some("#" + edited.getId)))
        lines must contain(s"account_retired accountId=${edited.getId} byAccountId=${admin.getId} contestId=10 " +
          s"keptAccountId=${elsewhere.getId} memberships=1 links=1")
        lines.mkString must not(contain("edited@example.org"))
        mail.sent must beEmpty

        // signed in nowhere: by its old address, its wiki account, or an old session
        User.login("edited@example.org", "secret") must beNone
        User.byUserName("Old Name") must beNone
        Secured.sessionUser("edited@example.org", Some("10"), Some(versionBefore.toString), Some(edited.getId.toString)) must
          beNone
        // the conflict-of-interest filter: its wiki account gone, the added one's in
        CoiMembers.forContest(10).keySet must not(contain(CoiMembers.normalize("Old Name")))
        CoiMembers.forContest(10).keySet must contain(CoiMembers.normalize("Shared Wiki"))
        // listed nowhere: the contest's users, root's all users, the search, the wiki account check
        User.findByContest(10).map(_.getId) must not(contain(edited.getId))
        User.findAllLive().map(_.getId) must not(contain(edited.getId))
        User.searchAccounts("Edited", None, 10).map(_._1.getId) must beEmpty
        User.otherAccountsWithWikiAccount(0L, "Old Name") must beEmpty

        // its address is free: an add by email creates a new account with it
        implicit val messages: play.api.i18n.Messages = messagesOf(app)
        val added = userService(mail).addToContest(admin, User("Edited Again", "edited@example.org", None, Set("jury"),
          None, Some(10)), ContestJuryJdbc.findById(10))
        added must beAnInstanceOf[UserService.NewAccount]
        added.user.getId must not(beEqualTo(edited.getId))
        User.findByEmail("edited@example.org").map(_.getId) === Seq(added.user.getId)
        // a stale copy of the retired account is never invited
        userService(mail).invite(admin, edited, ContestJuryJdbc.findById(10), Some("jury"), mail = false) must
          throwA[UserService.AccountNotFound]
        liveLinks(edited) === 0L
        mail.sent must beEmpty
      }
    }

    "say why it can't be retired, and offer nothing to whom may not retire it at all" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val root = person("Root", "root@example.com", None, root = true)
        val admin = person("Admin Ten", "admin@example.com", Some(10), "admin")
        val elsewhere = person("Shared Elsewhere", "else.where@example.org", Some(20), wiki = Some("Shared Wiki"))
        val voter = person("Voter", "voter@example.org", Some(10))
        selectionDao.batchInsert(Seq(Vote(image().pageId, voter.getId, roundOf(10).getId, 1)))
        val adminTarget = person("Other Admin", "other.admin@example.org", Some(10), "admin")
        val mail = new RecordingMail

        def addForm(by: User, edited: User, role: String) =
          item(contentAsString(save(mail, by, 10, form(edited, 10, role, "wikiAccount" -> "Shared Wiki"))), elsewhere)
            .selectFirst("form.wiki-taken-add")

        val notEmpty = addForm(admin, voter, "jury")
        notEmpty.select("input[name=retireAccountId]").size === 0
        notEmpty.selectFirst(".wiki-taken-retire-refused").text === "Voter can't be retired: it has votes."
        val anAdmin = addForm(admin, adminTarget, "admin")
        anAdmin.select("input[name=retireAccountId]").size === 0
        anAdmin.selectFirst(".wiki-taken-retire-refused").text ===
          "Other Admin can't be retired: it is an admin; only root can retire it."
        // root may retire an admin account; no conflict-of-interest note without a wiki account of its own
        val byRoot = addForm(root, adminTarget, "admin")
        byRoot.select("input[name=retireAccountId]").attr("value") === adminTarget.getId.toString
        byRoot.select(".wiki-taken-retire-coi").size === 0

        // a forged retire with the add: refused by the same rules, the add stays
        val r = addExisting(admin, 10, Seq("accountId" -> elsewhere.getId.toString, "role" -> "jury",
          "retireAccountId" -> adminTarget.getId.toString))
        status(r) === SEE_OTHER
        flash(r).get("users-added") must beSome(contain(
          "Other Admin was not retired: it is an admin; only root can retire it."))
        retired(adminTarget) must beFalse
        UserContestJdbc.find(elsewhere.getId, 10) must beSome
        // an account the admin may not change: named by its id only
        val inTwenty = person("Twenty Only", "twenty@example.org", Some(20))
        val forged = addExisting(admin, 10, Seq("accountId" -> elsewhere.getId.toString, "role" -> "jury",
          "retireAccountId" -> inTwenty.getId.toString))
        flash(forged).get("users-added") must beSome(contain(s"#${inTwenty.getId} was not retired: you may not change this account."))
        flash(forged).get("users-added").get must not(contain("Twenty Only"))
        retired(inTwenty) must beFalse
        mail.sent must beEmpty
      }
    }

    "retire nothing when the add gives nothing, and refuse a save of an account retired meanwhile" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val root = person("Root", "root@example.com", None, root = true)
        val admin = person("Admin Ten", "admin@example.com", Some(10), "admin")
        val edited = person("Edited Person", "edited@example.org", Some(10))
        // an account with a password and no address: an add by id gives it nothing (no invitation can reach it)
        val noEmail = User.create(User("No Email", "", None, Set("jury"), Some(hash("secret")), Some(20),
          wikiAccount = Some("Shared Wiki")))
        val r = addExisting(admin, 10, Seq("accountId" -> noEmail.getId.toString, "role" -> "jury",
          "retireAccountId" -> edited.getId.toString))
        status(r) === SEE_OTHER
        flash(r).get("users-added") must beSome(contain("was not retired: the other account was not added."))
        retired(edited) must beFalse

        // root's form of the account, saved after it was retired: nothing written
        AccountRetirement.retire(root, edited.getId, Some(10), None).isRight must beTrue
        val saved = save(new RecordingMail, root, 10, form(edited, 10, "jury", "fullname" -> "Renamed"))
        status(saved) === BAD_REQUEST
        contentAsString(saved) must contain("This account has been retired meanwhile; nothing was saved.")
        stored(edited).fullname === "Edited Person"
      }
    }

    "need the CSRF token, through the router" in {
      withCsrfApp { app =>
        implicit val a: Application = app
        createContests(30)
        val root = person("Root", "root@example.com", None, root = true)
        val edited = person("Edited Person", "edited@example.org", Some(30))
        val elsewhere = person("Shared Elsewhere", "else.where@example.org", None, wiki = Some("Shared Wiki"))
        val sessionToken = csrfToken(app)
        val refused = route(app, FakeRequest(POST, "/admin/users/add-existing?contestId=30")
          .withHeaders(COOKIE -> "PLAY_SESSION=x", ACCEPT -> "text/html")
          .withSession(Secured.UserName -> root.email, "csrfToken" -> sessionToken)
          .withFormUrlEncodedBody("accountId" -> elsewhere.getId.toString, "role" -> "jury",
            "retireAccountId" -> edited.getId.toString)).get
        status(refused) === FORBIDDEN
        retired(edited) must beFalse
        UserContestJdbc.findByUser(elsewhere.getId) must beEmpty

        val done = route(app, FakeRequest(POST, "/admin/users/add-existing?contestId=30")
          .withHeaders(COOKIE -> "PLAY_SESSION=x", ACCEPT -> "text/html")
          .withSession(Secured.UserName -> root.email, "csrfToken" -> sessionToken)
          .withFormUrlEncodedBody("accountId" -> elsewhere.getId.toString, "role" -> "jury",
            "retireAccountId" -> edited.getId.toString, "csrfToken" -> sessionToken)).get
        status(done) === SEE_OTHER
        retired(edited) must beTrue
      }
    }
  }

  "round_user inserts and a retirement" should {

    /** A contest of files in its category, a round of `jurors` (every file to every juror), and
      * `extra`, an empty juror of the contest outside the round.
      */
    def setUp(): (ContestJury, Round, Seq[User], User, User) = {
      implicit val contest: ContestJury = createContests(10).head
      val imgs = (1 to 4).map(i => Image(i.toLong, s"File:Image$i.jpg", None, None, 640, 480, monumentId = Some(s"12-345-$i")))
      imageDao.batchInsert(imgs)
      CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, imgs)
      val js = createUsers("jury", 1 to 2).sortBy(_.getId)
      val extra = createUsers("jury", 100).head
      val root = person("Root", "root@example.com", None, root = true)
      val round = TestRoundServices.roundService().createNewRound(
        Round(None, 0, Some("R"), contest.getId, distribution = 0, rates = Round.binaryRound, active = true),
        js.map(_.getId))
      (contest, Round.findById(round.getId).get, js, extra, root)
    }

    def roundUsers(roundId: Long): Seq[Long] =
      sql"SELECT user_id FROM round_user WHERE round_id = $roundId ORDER BY user_id".map(_.long(1)).list()

    /** Retires `account` in a transaction held open until `release`, on another thread. */
    def retireHeld(root: User, account: User, release: CountDownLatch, done: CountDownLatch): Future[Retired] =
      Future {
        DB.localTx { s =>
          val r = AccountRetirement.retireIn(root, account.getId, Some(10), None)(s)
          done.countDown()
          release.await()
          r
        }
      }

    "make a retirement wait for a round_user insert holding its lock, then refuse it" in {
      testDbApp { implicit app =>
        val (_, round, _, extra, root) = setUp()
        val (release, held) = (new CountDownLatch(1), new CountDownLatch(1))
        // as every round_user insert does: the share lock on the users row, then the row
        val inserting = Future {
          DB.localTx { s =>
            User.lockLive(Seq(extra.getId))(s) must contain(extra.getId)
            sql"INSERT INTO round_user (round_id, user_id, role, active) VALUES (${round.getId}, ${extra.getId}, 'jury', TRUE)"
              .update.apply()(s)
            held.countDown()
            release.await()
          }
        }
        held.await()
        val retiring = Future(AccountRetirement.retire(root, extra.getId, Some(10), None))
        Thread.sleep(500)
        retiring.isCompleted must beFalse
        release.countDown()
        Await.result(inserting, 30.seconds)
        Await.result(retiring, 30.seconds) === Left(HasRounds)
        retired(extra) must beFalse
      }
    }

    "make a retirement wait for a comment holding its lock, and never write a comment of a retired account" in {
      testDbApp { implicit app =>
        val (_, round, _, extra, root) = setUp()
        val pageId = sql"SELECT MIN(page_id) FROM images".map(_.long(1)).single().get
        val (release, held) = (new CountDownLatch(1), new CountDownLatch(1))
        val commenting = Future {
          DB.localTx { s =>
            User.lockLive(Seq(extra.getId))(s) must contain(extra.getId)
            CommentJdbc.create(extra.getId, extra.fullname, round.getId, Some(10), pageId, "a comment")(s)
            held.countDown()
            release.await()
          }
        }
        held.await()
        val retiring = Future(AccountRetirement.retire(root, extra.getId, Some(10), None))
        Thread.sleep(500)
        retiring.isCompleted must beFalse
        release.countDown()
        Await.result(commenting, 30.seconds)
        Await.result(retiring, 30.seconds) === Left(HasComments)

        // once retired: CommentJdbc.createLive writes nothing
        val other = createUsers("jury", 101)(contestDao.findById(10).get, autoSession).head
        AccountRetirement.retire(root, other.getId, Some(10), None).isRight must beTrue
        CommentJdbc.createLive(other.getId, other.fullname, round.getId, Some(10), pageId, "late") must beNone
        sql"SELECT COUNT(*) FROM comment WHERE user_id = ${other.getId}".map(_.long(1)).single() === Some(0L)
      }
    }

    "lock both accounts: of two crossed retirements, only one retires" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = person("Admin Ten", "admin10@example.com", Some(10), "admin")
        val (x, y) = (person("X", "x@example.org", Some(10)), person("Y", "y@example.org", Some(10)))
        AccountRetirement.retire(admin, x.getId, Some(10), Some(y.getId)).isRight must beTrue
        AccountRetirement.retire(admin, y.getId, Some(10), Some(x.getId)) === Left(KeptGone)
        retired(y) must beFalse
        // at once, from two threads: one retires, the other finds its kept account retired
        val (a, b) = (person("A", "a@example.org", Some(10)), person("B", "b@example.org", Some(10)))
        val both = Await.result(Future.sequence(Seq(
          Future(AccountRetirement.retire(admin, a.getId, Some(10), Some(b.getId))),
          Future(AccountRetirement.retire(admin, b.getId, Some(10), Some(a.getId))))), 30.seconds)
        both.count(_.isRight) === 1
        both.filter(_.isLeft) === Seq(Left(KeptGone))
        Seq(a, b).count(retired) === 1
      }
    }

    "refuse a retirement once a juror add committed first" in {
      testDbApp { implicit app =>
        val (_, round, js, extra, root) = setUp()
        val rebalance = new RoundRebalanceService(TestRoundServices.inlineScheduler(
          new RoundRebalanceHandler(Configuration("jobs.roundRebalance.chunkRows" -> 100))))
        val shown = rebalance.preview(round, 0L).signature
        rebalance.add(round, extra.getId, root, shown) must beAnInstanceOf[RoundRebalanceService.Queued]
        roundUsers(round.getId) must contain(extra.getId)
        AccountRetirement.retire(root, extra.getId, Some(10), None) must beOneOf(Left(HasRounds), Left(HasVotes))
        retired(extra) must beFalse
        js.size === 2
      }
    }

    "make a juror add wait for a retirement in progress, then refuse it" in {
      testDbApp { implicit app =>
        val (_, round, _, extra, root) = setUp()
        val rebalance = new RoundRebalanceService(TestRoundServices.inlineScheduler(
          new RoundRebalanceHandler(Configuration("jobs.roundRebalance.chunkRows" -> 100))))
        val shown = rebalance.preview(round, 0L).signature
        val (release, held) = (new CountDownLatch(1), new CountDownLatch(1))
        val retiring = retireHeld(root, extra, release, held)
        held.await()
        // the membership is still there as committed: only the users row's lock stops the add
        val adding = Future(rebalance.add(round, extra.getId, root, shown))
        Thread.sleep(500)
        adding.isCompleted must beFalse
        release.countDown()
        Await.result(retiring, 30.seconds).accountId === extra.getId
        Await.result(adding, 30.seconds) === RoundRebalanceService.Refused(
          services.jobs.JobError(RoundRebalanceHandler.NotJurorKey, Seq(extra.getId.toString)))
        roundUsers(round.getId) must not(contain(extra.getId))
        retired(extra) must beTrue
      }
    }

    "make a round creation wait for a retirement in progress, then leave the retired account out" in {
      testDbApp { implicit app =>
        val (contest, _, js, extra, root) = setUp()
        val (release, held) = (new CountDownLatch(1), new CountDownLatch(1))
        val retiring = retireHeld(root, extra, release, held)
        held.await()
        val creating = Future(TestRoundServices.roundService().createNewRound(
          Round(None, 0, Some("R2"), contest.getId, distribution = 0, rates = Round.binaryRound, active = true),
          (js :+ extra).map(_.getId)))
        Thread.sleep(500)
        creating.isCompleted must beFalse
        release.countDown()
        Await.result(retiring, 30.seconds)
        val created = Await.result(creating, 60.seconds)
        roundUsers(created.getId) === js.map(_.getId)
        sql"SELECT COUNT(*) FROM vote WHERE round_id = ${created.getId} AND jury_id = ${extra.getId}".map(_.long(1))
          .single() === Some(0L)
      }
    }
  }
}
