package controllers

import controllers.CsrfTestApp.{token => csrfToken, withCsrfApp}
import db.scalikejdbc._
import org.apache.pekko.stream.Materializer
import org.intracer.wmua.{CommentJdbc, Image, Vote}
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test._
import services.UserService

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** The Edit user form's panel when a save is refused because other accounts have the wiki account
  * typed (`wiki.account.taken`; accounts plan A-2, section c): the other accounts as the users
  * page's search shows them (A1: every account, the address masked outside the editor's contests),
  * their votes, comments and rounds counted within the editor's contests only (root: all), "Add
  * that account to this contest instead" (A-1's add by id, both mails unticked) and "Keep them
  * separate". Signed-out users, jurors, organizers, inactive members and other contests' admins
  * get no panel.
  */
class WikiTakenPanelSpec extends PlaySpecification with PlayTestDb with AccountTestSupport {

  sequential

  private def session(user: User, contestId: Option[Long]): Seq[(String, String)] =
    Seq(Secured.UserName -> user.email) ++ contestId.map(Secured.CurrentContestId -> _.toString)

  private def person(name: String, email: String, contestId: Option[Long], role: String = "jury",
      wiki: Option[String] = None, verified: Boolean = true, root: Boolean = false): User =
    User.create(User(name, email, None, if (root) Set(User.ROOT_ROLE) else Set(role), Some(hash("secret")), contestId,
      wikiAccount = wiki, isRoot = root, emailVerifiedAt = if (verified) Some(java.time.ZonedDateTime.now) else None))

  /** The edit form of `user` in the contest as the page fills it, with `changes`. */
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

  /** The panel's item of `account`. */
  private def item(html: String, account: User): Element =
    Option(Jsoup.parse(html).selectFirst(s"#wiki-taken li[data-account-id=${account.getId}]"))
      .getOrElse(sys.error(s"no item for ${account.getId}"))

  /** What a browser sends with a panel's add form: its hidden fields, the selected role, and the
    * boxes as they are (none ticked unless the page ticks them).
    */
  private def submitted(add: Element): Seq[(String, String)] =
    add.select("input[type=hidden]").asScala.map(i => i.attr("name") -> i.attr("value")).toSeq ++
      add.select("select").asScala.map(s => s.attr("name") -> s.selectFirst("option[selected]").attr("value")) ++
      add.select("input[type=checkbox][checked]").asScala.map(i => i.attr("name") -> i.attr("value"))

  private var nextPage = 1000L

  /** A round of the contest, with `voter`'s votes of the rates (one file each) and a comment if asked. */
  private def worked(contestId: Long, voter: User, rates: Seq[Int], assigned: Boolean = false,
      comment: Boolean = false): Round = {
    val round = roundDao.create(Round(None, 1, Some("R"), contestId, rates = Round.binaryRound, active = true))
    val images = rates.map { _ => nextPage += 1; Image(nextPage, s"File:Image$nextPage.jpg", None, None, 640, 480, None) }
    imageDao.batchInsert(images)
    selectionDao.batchInsert(images.zip(rates).map { case (image, rate) => Vote(image.pageId, voter.getId, round.getId, rate) })
    if (assigned) round.addUsers(Seq(RoundUser(round.getId, voter.getId, "jury", active = true)))
    if (comment) CommentJdbc.create(voter.getId, voter.fullname, round.getId, Some(contestId), images.head.pageId, "a comment")
    round
  }

  "the wiki account taken panel [A-23]" should {

    "show the other accounts as the admin may see them, counted within their contests only" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val admin = person("Admin Ten", "admin@example.com", Some(10), "admin")
        val edited = person("Edited Person", "edited@example.org", Some(10))
        val inTen = person("Shared In Ten", "in.ten@example.org", Some(10), wiki = Some("Shared Wiki"))
        val elsewhere = person("Shared Elsewhere", "else.where@example.org", Some(20), wiki = Some("shared wiki"))
        // in contest 10: a vote and an unrated file, assigned, a comment; in contest 20: a vote, a comment
        worked(10, inTen, Seq(1, 0), assigned = true, comment = true)
        worked(20, inTen, Seq(-1), comment = true)
        worked(20, elsewhere, Seq(1, 1), assigned = true)
        val mail = new RecordingMail

        val (r, lines) = accountLog {
          val r = save(mail, admin, 10, form(edited, 10, "organizer", "wikiAccount" -> " Shared Wiki ",
            "fullname" -> "Edited Renamed"))
          status(r)
          r
        }
        status(r) === BAD_REQUEST
        val html = contentAsString(r)
        html must contain("Another account already has this wiki account.")
        val panel = Jsoup.parse(html).selectFirst("#wiki-taken")
        panel.selectFirst("h2").text === "Other accounts with the wiki account Shared Wiki"
        panel.select("li.wiki-taken-account").asScala.map(_.attr("data-account-id").toLong) ===
          Seq(inTen.getId, elsewhere.getId)

        val ten = item(html, inTen)
        ten.text must contain("in.ten@example.org")
        ten.text must contain("Your contests: contest10 2010 in Andorra (Juror)")
        ten.selectFirst(".wiki-taken-activity").text === "In your contests: votes 1, comments 1, rounds 1."
        // a member of this contest already: no add, its role
        ten.select("form").size === 0
        ten.selectFirst(".wiki-taken-member").text === "Already in this contest: Juror."
        ten.select("a").asScala.map(_.attr("href")) === Seq(routes.UserController.editUser(inTen.getId, Some(10)).url)

        val other = item(html, elsewhere)
        other.text must contain("e***@example.org")
        other.text must contain("Your contests: none")
        other.selectFirst(".wiki-taken-activity").text === "No votes, comments or rounds in your contests."
        other.select("a").size === 0
        // no join link (J-2): neither account is only in contests this admin administers
        panel.select("a.wiki-taken-join").size === 0
        // A-1's add by id, with the form's role, both mails unticked
        val add = other.selectFirst("form.wiki-taken-add")
        add.attr("action") === routes.UserController.addExistingAccount(Some(10)).url
        add.select("input[name=accountId]").attr("value") === elsewhere.getId.toString
        add.selectFirst("select[name=role] option[selected]").attr("value") === "organizer"
        add.select("input[type=checkbox]").asScala.map(_.attr("name")) === Seq("sendInvitation", "tellOwner", "retireAccountId")
        add.select("input[type=checkbox][checked]").size === 0
        add.select("input[type=radio]").size === 0

        // never the other address in full, the other contest or its counts
        html must not(contain("else.where@")).and(not(contain("contest20")))
        panel.select("#wiki-taken-keep").attr("data-wiki-account") === ""
        panel.select("#wiki-taken-keep").attr("href") === routes.UserController.editUser(edited.getId, Some(10)).url
        panel.select(".wiki-taken-not-shown").size === 0
        html must contain("javascripts/wiki-taken.js")
        // the form as typed, nothing saved, nothing mailed or logged as a change
        Jsoup.parse(html).selectFirst("#fullname").attr("value") === "Edited Renamed"
        User.findById(edited.getId).map(u => (u.fullname, u.wikiAccount)) === Some(("Edited Person", None))
        AccountChangeJdbc.findByUser(edited.getId, None, 10) must beEmpty
        mail.sent must beEmpty
        lines === Seq(s"wiki_taken_panel byAccountId=${admin.getId} accountId=${edited.getId} contestId=10 shown=2 " +
          "notShown=false")
      }
    }

    "show root every address in full, every contest and every count" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val root = person("Root", "root@example.com", None, root = true)
        val edited = person("Edited Person", "edited@example.org", Some(10), wiki = Some("Own Name"))
        val inTen = person("Shared In Ten", "in.ten@example.org", Some(10), wiki = Some("Shared Wiki"))
        val elsewhere = person("Shared Elsewhere", "else.where@example.org", Some(20), wiki = Some("Shared Wiki"))
        worked(10, inTen, Seq(1, 0), assigned = true, comment = true)
        worked(20, inTen, Seq(-1), comment = true)
        worked(20, elsewhere, Seq(1, 1), assigned = true)

        val r = save(new RecordingMail, root, 10, form(edited, 10, "jury", "wikiAccount" -> "Shared Wiki"))
        status(r) === BAD_REQUEST
        val html = contentAsString(r)
        item(html, inTen).selectFirst(".wiki-taken-activity").text === "In all contests: votes 2, comments 2, rounds 2."
        val other = item(html, elsewhere)
        other.text must contain("else.where@example.org")
        other.text must contain("Contests: contest20 2020 in Andorra (Juror)")
        other.selectFirst(".wiki-taken-activity").text === "In all contests: votes 2, comments 0, rounds 1."
        // and the join's preview (J-2): root may join any two accounts
        other.select("a").asScala.map(_.attr("href")) === Seq(routes.UserController.editUser(elsewhere.getId, Some(10)).url,
          AccountJoinController.previewUrl(Some(10), Seq(edited.getId, elsewhere.getId)))
        other.selectFirst("form.wiki-taken-add").attr("action") === routes.UserController.addExistingAccount(Some(10)).url
        // Keep them separate puts the stored wiki account back
        Jsoup.parse(html).selectFirst("#wiki-taken-keep").attr("data-wiki-account") === "Own Name"
      }
    }

    "show at most 10 of a legacy group, never a root account to an admin, none preselected" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val admin = person("Admin Ten", "admin@example.com", Some(10), "admin")
        val edited = person("Edited Person", "edited@example.org", Some(10))
        val rootAccount = person("Hidden Root", "hidden.root@example.com", None, wiki = Some("Legacy Name"), root = true)
        val group = (1 to 11).map(i => person(s"Legacy $i", s"legacy$i@example.org", Some(20), wiki = Some("Legacy Name")))

        val r = save(new RecordingMail, admin, 10, form(edited, 10, "jury", "wikiAccount" -> "Legacy Name"))
        status(r) === BAD_REQUEST
        val html = contentAsString(r)
        val panel = Jsoup.parse(html).selectFirst("#wiki-taken")
        panel.select("li.wiki-taken-account").asScala.map(_.attr("data-account-id").toLong) === group.take(10).map(_.getId)
        panel.select("form.wiki-taken-add").size === 10
        panel.select("input[type=checkbox][checked]").size === 0
        panel.selectFirst(".wiki-taken-not-shown").text === "Other accounts with this wiki account are not shown."
        html must not(contain("Hidden Root")).and(not(contain("hidden.root")))
        html must not(contain(s"data-account-id=\"${rootAccount.getId}\""))

        // only the root account besides the shown ones: still said, never shown
        val few = person("Few Edited", "few@example.org", Some(10))
        val single = person("Single", "single@example.org", Some(20), wiki = Some("Root And One"))
        person("Root Two", "root2@example.com", None, wiki = Some("Root And One"), root = true)
        val second = contentAsString(save(new RecordingMail, admin, 10, form(few, 10, "jury", "wikiAccount" -> "Root And One")))
        Jsoup.parse(second).select("#wiki-taken li").asScala.map(_.attr("data-account-id").toLong) === Seq(single.getId)
        second must contain("Other accounts with this wiki account are not shown.")
        second must not(contain("Root Two"))
      }
    }

    "add that account instead through the panel's form: the account search's add by id, no mail unless ticked" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val admin = person("Admin Ten", "admin@example.com", Some(10), "admin")
        val edited = person("Edited Person", "edited@example.org", Some(10))
        val elsewhere = person("Shared Elsewhere", "else.where@example.org", Some(20), wiki = Some("Shared Wiki"))
        val mail = new RecordingMail

        val html = contentAsString(save(mail, admin, 10, form(edited, 10, "organizer", "wikiAccount" -> "Shared Wiki")))
        val fields = submitted(item(html, elsewhere).selectFirst("form.wiki-taken-add"))
        fields.map(_._1).toSet === Set("csrfToken", "accountId", "role")

        val added = call(userController(mail).addExistingAccount(Some(10)), FakeRequest(POST, "/admin/users/add-existing")
          .withSession(session(admin, Some(10)): _*).withFormUrlEncodedBody(fields: _*).withCSRFToken)
        status(added) === SEE_OTHER
        redirectLocation(added) must beSome(routes.UserController.users(Some(10)).url)
        flash(added).get("users-added") must beSome(contain("Its owner will see a notice in the tool when they sign in."))
        UserContestJdbc.find(elsewhere.getId, 10) must beSome(UserContest(elsewhere.getId, 10, "organizer"))
        AccountChangeJdbc.findByUser(elsewhere.getId, Some(10), 10).map(c => (c.field, c.newValue, c.comment)) ===
          Seq((AccountChangeJdbc.Role, Some("organizer"), Some(UserService.SearchComment)))
        services.AccountNotices.pending(elsewhere.getId, 0L).map(_.role) === Seq("organizer")
        mail.sent must beEmpty
        // the edited account as it was
        User.findById(edited.getId).flatMap(_.wikiAccount) must beNone
        UserContestJdbc.find(edited.getId, 10) must beSome(UserContest(edited.getId, 10, "jury"))

        // once a member, the panel offers no add for it
        val again = contentAsString(save(mail, admin, 10, form(edited, 10, "jury", "wikiAccount" -> "Shared Wiki")))
        item(again, elsewhere).select("form").size === 0
        item(again, elsewhere).selectFirst(".wiki-taken-member").text === "Already in this contest: Organizer."
        mail.sent must beEmpty
      }
    }

    "refuse an account in another contest before the panel: no panel, nothing logged" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val admin = person("Admin Ten", "admin@example.com", Some(10), "admin")
        val edited = person("Edited Person", "edited@example.org", Some(10))
        UserContestJdbc.createMembership(edited.getId, 20, "jury")
        person("Shared Elsewhere", "else.where@example.org", Some(20), wiki = Some("Shared Wiki"))
        val mail = new RecordingMail

        val (r, lines) = accountLog {
          val r = save(mail, admin, 10, form(edited, 10, "jury", "wikiAccount" -> "Shared Wiki"))
          status(r)
          r
        }
        status(r) === BAD_REQUEST
        val html = contentAsString(r)
        html must contain(messagesOf(app)("user.in.other.contests"))
        Option(Jsoup.parse(html).selectFirst("#wiki-taken")) must beNone
        html must not(contain("Shared Elsewhere")).and(not(contain("e***@example.org")))
        lines.filter(_.startsWith("wiki_taken_panel")) must beEmpty
        User.findById(edited.getId).flatMap(_.wikiAccount) must beNone
        mail.sent must beEmpty
      }
    }

    "give signed-out users, jurors, organizers, inactive members and other contests' admins no panel" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10, 20)
        val edited = person("Edited Person", "edited@example.org", Some(10))
        val elsewhere = person("Shared Elsewhere", "else.where@example.org", Some(20), wiki = Some("Shared Wiki"))
        val juror = person("Juror", "juror@example.com", Some(10))
        val organizer = person("Org", "org@example.com", Some(10), "organizer")
        val inactive = person("Inactive", "inactive@example.com", Some(10), "no")
        val otherAdmin = person("Other Admin", "admin20@example.com", Some(20), "admin")
        val fields = form(edited, 10, "jury", "wikiAccount" -> "Shared Wiki")

        def noPanel(r: Future[Result]) = {
          status(r) must not(beEqualTo(BAD_REQUEST))
          contentAsString(r) must not(contain("wiki-taken")).and(not(contain("Shared Elsewhere")))
            .and(not(contain("e***@example.org")))
        }
        noPanel(route(app, FakeRequest(POST, "/users/save").withFormUrlEncodedBody(fields: _*)).get)
        for (user <- Seq(juror, organizer, inactive)) noPanel(route(app, FakeRequest(POST, "/users/save")
          .withSession(session(user, Some(10)): _*).withFormUrlEncodedBody(fields: _*)).get)
        // an admin of another contest, from its session's contest or this one's
        for (current <- Seq(20L, 10L)) noPanel(route(app, FakeRequest(POST, "/users/save")
          .withSession(session(otherAdmin, Some(current)): _*).withFormUrlEncodedBody(fields: _*)).get)
        // nor its add, through the router
        for (user <- Seq(juror, organizer, inactive, otherAdmin)) {
          val r = route(app, FakeRequest(POST, "/admin/users/add-existing?contestId=10")
            .withSession(session(user, Some(10)): _*)
            .withFormUrlEncodedBody("accountId" -> elsewhere.getId.toString, "role" -> "jury")).get
          status(r) === FORBIDDEN
        }
        UserContestJdbc.find(elsewhere.getId, 10) must beNone
        User.findById(edited.getId).flatMap(_.wikiAccount) must beNone
      }
    }

    "need the CSRF token, through the router" in {
      withCsrfApp { app =>
        implicit val a: Application = app
        implicit val mat: Materializer = app.materializer
        val contest = createContests(30).head
        val root = person("Root", "root@example.com", None, root = true)
        val edited = person("Edited Person", "edited@example.org", contest.id)
        val elsewhere = person("Shared Elsewhere", "else.where@example.org", None, wiki = Some("Shared Wiki"))
        val sessionToken = csrfToken(app)
        def request(token: Option[String]) = FakeRequest(POST, "/users/save")
          .withHeaders(COOKIE -> "PLAY_SESSION=x", ACCEPT -> "text/html")
          .withSession(Secured.UserName -> root.email, Secured.CurrentContestId -> "30", "csrfToken" -> sessionToken)
          .withFormUrlEncodedBody(form(edited, 30, "jury", "wikiAccount" -> "Shared Wiki") ++
            token.map("csrfToken" -> _): _*)
        val refused = route(app, request(None)).get
        status(refused) === FORBIDDEN
        contentAsString(refused) must not(contain("Shared Elsewhere"))

        val shown = route(app, request(Some(sessionToken))).get
        status(shown) === BAD_REQUEST
        val add = item(contentAsString(shown), elsewhere).selectFirst("form.wiki-taken-add")
        add.select("input[name=csrfToken]").attr("value") must not(beEmpty)

        // the panel's add without the token: refused, nothing added
        val addRefused = route(app, FakeRequest(POST, "/admin/users/add-existing?contestId=30")
          .withHeaders(COOKIE -> "PLAY_SESSION=x", ACCEPT -> "text/html")
          .withSession(Secured.UserName -> root.email, "csrfToken" -> sessionToken)
          .withFormUrlEncodedBody("accountId" -> elsewhere.getId.toString, "role" -> "jury")).get
        status(addRefused) === FORBIDDEN
        UserContestJdbc.findByUser(elsewhere.getId) must beEmpty
      }
    }
  }
}
