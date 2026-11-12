package controllers

import db.UsersPasswordNullableMigration
import db.scalikejdbc.{PlayTestDb, User, UserContest, UserContestJdbc}
import org.apache.pekko.stream.Materializer
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test._
import scalikejdbc._

import scala.concurrent.Future

/** Production's users.password was NOT NULL (a 2015 Play evolution; fresh databases get V2's
  * nullable column), so from 26.11 a non-root admin's add of a person whose email no account
  * has ended in a server error ("Column 'password' cannot be null") and Import Users skipped
  * them. On that shape, after V98, the add form (mail off and on, a wiki account only) and an
  * import create the accounts without a password (NULL, the code's "none").
  */
class NewAccountProductionSchemaSpec extends PlaySpecification with PlayTestDb with AccountTestSupport {

  sequential

  import UsersPasswordNullableMigration.{productionShape, restore, run => v98}

  private def as[A](user: User, contestId: Long)(request: FakeRequest[A]): FakeRequest[A] =
    request.withSession(Secured.UserName -> user.email, Secured.CurrentContestId -> contestId.toString)

  /** The users page's add row, as the browser posts it ("Send an invitation email" unticked: absent). */
  private def add(mail: RecordingMail, admin: User, contestId: Long, email: String, wikiAccount: String = "",
                  sendInvitation: Boolean = false)(implicit app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    val fields = Seq("id" -> "0", "contest" -> contestId.toString, "fullname" -> "Сергій Тестовий",
      "wikiAccount" -> wikiAccount, "email" -> email, "roles" -> "jury") ++
      (if (sendInvitation) Seq("sendInvitation" -> "true") else Nil)
    call(userController(mail).saveUser(),
      as(admin, contestId)(FakeRequest(POST, "/users/save")).withFormUrlEncodedBody(fields: _*).withCSRFToken)
  }

  private def importUsers(mail: RecordingMail, admin: User, contestId: Long, lines: String,
                          sendInvitation: Boolean = false)(implicit app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    val fields = Seq("userstoimport" -> lines, "role" -> "jury") ++
      (if (sendInvitation) Seq("sendInvitation" -> "true") else Nil)
    call(userController(mail).importUsers(Some(contestId)),
      as(admin, contestId)(FakeRequest(POST, s"/admin/importusers?contestId=$contestId"))
        .withFormUrlEncodedBody(fields: _*).withCSRFToken)
  }

  /** The account's stored password is SQL NULL (not an empty string). */
  private def passwordIsNull(id: Long): Boolean =
    DB.readOnly(implicit s => sql"SELECT password IS NULL FROM users WHERE id = $id".map(_.boolean(1)).single())
      .contains(true)

  private def created(email: String): User = User.findByEmail(email).head

  /** The import page after an import's redirect, with the flash it set. */
  private def importPage(mail: RecordingMail, admin: User, contestId: Long, after: Future[Result])(implicit
      app: Application
  ): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(userController(mail).showImportUsers(Some(contestId)),
      as(admin, contestId)(FakeRequest(GET, s"/admin/importusers?contestId=$contestId"))
        .withFlash(flash(after).data.toSeq: _*).withCSRFToken)
  }

  /** The account's invitation entries in the account change log: whether each was mailed. */
  private def invitationEntries(userId: Long): Seq[Option[Boolean]] =
    DB.readOnly(implicit s => sql"SELECT mail_sent FROM account_change WHERE user_id = $userId AND field = 'invitation' ORDER BY id"
      .map(_.booleanOpt(1)).list())

  /** The import form's text area's content, as the page has it (HTML-escaped). */
  private def textarea(page: String): String =
    """(?s)<textarea[^>]*id="userstoimport"[^>]*>(.*?)</textarea>""".r.findFirstMatchIn(page).map(_.group(1)).getOrElse("")

  "Import Users" should {
    "add the lines it can, and show each line not added after a redirect, with the reference of its log line" in {
      testDbApp { implicit app =>
        createContests(10, 11)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val otherAdmin = account("admin2@example.com", Some(10), "admin", verified = true)
        val existing = account("existing@example.com", Some(11), "jury", verified = true)
        // an account with a password and no verified email: invited into the contest (O2)
        val invitee = account("invitee@example.com", Some(11), "jury")
        val mail = new RecordingMail
        try {
          productionShape() // a new account fails, as on production before V98
          val lines = "existing@example.com\nСергій Тестовий <new+tag@example.com>, User:Bad#name\n\n" +
            "plain-new@example.com\ninvitee@example.com"
          val (posted, log) = accountLog {
            val r = importUsers(mail, admin, 10, lines, sendInvitation = true)
            status(r)
            r
          }
          val failedLines = log.filter(_.startsWith("user_import_line_failed"))
          val refs = failedLines.flatMap(l => "ref=([0-9a-f]{8})".r.findFirstMatchIn(l).map(_.group(1)))

          // post/redirect/get: the result is the import page's, once, and only for its admin
          val othersPage = contentAsString(importPage(mail, otherAdmin, 10, posted))
          val page = contentAsString(importPage(mail, admin, 10, posted))
          val again = contentAsString(importPage(mail, admin, 10, posted))

          (status(posted) === SEE_OTHER) and
            (redirectLocation(posted) must beSome("/admin/importusers?contestId=10")) and
            (flash(posted).data.values.mkString must not(contain("new+tag@example.com"))) and
            (failedLines.size === 2) and (refs.size === 2) and
            (failedLines.head must contain("line=2")) and (failedLines(1) must contain("line=4")) and
            (failedLines.head must contain("error=java.sql.SQLIntegrityConstraintViolationException")) and
            (page must contain(s"Line 2: new+tag@example.com: an error on our side (reference ${refs.head}).")) and
            (page must contain(s"Line 4: plain-new@example.com: an error on our side (reference ${refs(1)}).")) and
            (page must contain("Line 2: Bad#name: not an email address or a valid Wikimedia account name.")) and
            // both kinds of lines, both pieces of advice
            (page must contain("Correct the entries that are not valid")) and
            (page must contain("tell whoever runs the jury tool its reference")) and
            // the form again with only the people not added, the name kept
            (textarea(page).linesIterator.toSeq.map(_.trim).filter(_.nonEmpty) must beLike {
              case Seq(named, "Bad#name", "plain-new@example.com") =>
                (named must contain("Сергій Тестовий")) and (named must endWith("&lt;new+tag@example.com&gt;"))
            }) and
            (page must contain("Existing accounts added to the contest: 1.")) and
            (othersPage must not(contain("new+tag@example.com"))) and
            (again must not(contain("new+tag@example.com"))) and
            (again must contain("Lines not added: 3. Their list is no longer available")) and
            // the lines that could be added are, and committed; one invitation mailed, once; no mail
            // to anyone not added
            (UserContestJdbc.findByUser(existing.getId).map(_.contestId).sorted === Seq(10L, 11L)) and
            (User.findByEmail("new+tag@example.com") must beEmpty) and
            (User.findByEmail("plain-new@example.com") must beEmpty) and
            (mail.recipients.count(_ == invitee.email) === 1) and
            (mail.recipients must not(containAnyOf(Seq("new+tag@example.com", "plain-new@example.com"))))
        } finally restore()
      }
    }

    "go back to the users page when every line is added" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val result = importUsers(new RecordingMail, admin, 10, "first@example.com\nUser:Wiki_first")
        (status(result) === SEE_OTHER) and (redirectLocation(result) must beSome("/admin/users?contestId=10")) and
          (flash(result).get("users-added") must beSome(contain("New accounts: 2.")))
      }
    }
  }

  "the invitation's change log entry" should {
    "say whether the mail went out, written after it" in {
      testDbApp { implicit app =>
        createContests(10, 11)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        // with a password and no verified email: the role waits for an invitation, kept unsent
        // without "Send an invitation email" (a new account gets none then: nothing to log)
        val waiting = account("waiting@example.com", Some(11), "jury")
        val mail = new RecordingMail
        status(add(mail, admin, 10, "mailed@example.com", sendInvitation = true)) === SEE_OTHER
        status(add(mail, admin, 10, "waiting@example.com")) === SEE_OTHER
        (invitationEntries(created("mailed@example.com").getId) === Seq(Some(true))) and
          (invitationEntries(waiting.getId) === Seq(Some(false))) and
          (mail.recipients === Seq("mailed@example.com"))
      }
    }

    "stay, without the flag, if the flag can't be written once the mail is out: the add succeeds" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val mail = new RecordingMail
        val failingFlag = new services.UserService(mail, app.configuration) {
          override protected def mailInBackground(send: => Unit): Unit = send
          override protected def recordMailSent(changeId: Long, sent: Boolean): Unit =
            throw new java.sql.SQLException("flag of flagged@example.com", new java.io.IOException("connection reset"))
        }
        val controller = new UserController(app.injector.instanceOf[play.api.mvc.ControllerComponents], failingFlag,
          app.injector.instanceOf[modules.BlockingExecutionContext])
        val (result, entries) = accountLogWithThrowables {
          val r = call(controller.saveUser(), as(admin, 10)(FakeRequest(POST, "/users/save"))
            .withFormUrlEncodedBody("id" -> "0", "contest" -> "10", "fullname" -> "Flagged", "email" -> "flagged@example.com",
              "roles" -> "jury", "sendInvitation" -> "true").withCSRFToken)
          status(r)
          r
        }
        val flagged = User.findByEmail("flagged@example.com").headOption
        // the account's edit page lists the entry with its mail "not known"
        val editPage = contentAsString(call(controller.editUser(flagged.get.getId, Some(10L)),
          as(admin, 10)(FakeRequest(GET, s"/users/edit/${flagged.get.getId}?contestId=10")).withCSRFToken))
        val notRecorded = entries.filter(_._1.startsWith("account_change_mail_flag_not_recorded"))
        val logged = entries.flatMap { case (line, trace) => line +: trace.toSeq }.mkString("\n")
        (status(result) === SEE_OTHER) and
          (flagged must beSome) and
          (mail.recipients === Seq("flagged@example.com")) and
          (invitationEntries(flagged.get.getId) === Seq(None)) and
          ("""(?s)<td>Invitation</td>.*?<td>not known</td>""".r.findFirstIn(editPage) must beSome) and
          (notRecorded.size === 1) and
          (notRecorded.head._1 must contain("mailSent=true error=java.sql.SQLException causes=java.io.IOException")) and
          // the exception and its cause, classes and stacks only (R220-1)
          (notRecorded.head._2 must beSome(contain("Caused by: java.lang.Exception: java.io.IOException (message not logged)"))) and
          (logged must not(contain("flagged@example.com"))) and (logged must not(contain("connection reset")))
      }
    }
  }

  "a non-root admin's add of a new person on production's users table" should {
    "fail before V98, as on production since 26.11" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        try {
          productionShape()
          val formUser = User("Сергій Тестовий", "first+tag@example.com", roles = Set("jury"), contestId = Some(10))
          (userService(new RecordingMail).createNewUser(admin, formUser)(messagesOf) must
            throwA[java.sql.SQLIntegrityConstraintViolationException](message = "password")) and
            (User.findByEmail("first+tag@example.com") must beEmpty)
        } finally restore()
      }
    }

    "after V98, create each account without a password: mail off and on, a wiki account only, and an import" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val mail = new RecordingMail
        try {
          productionShape()
          v98()

          // each answered before the next is sent, as from a browser
          val plusOff = add(mail, admin, 10, "new+tag@example.com")
          val plusOffStatus = status(plusOff)
          val plainOnStatus = status(add(mail, admin, 10, "plain@example.com", sendInvitation = true))
          val wikiOnlyStatus = status(add(mail, admin, 10, "", wikiAccount = "Wiki person"))
          val importedStatus = status(importUsers(mail, admin, 10, "Сергій Тестовий <imported+tag@example.com>"))

          val emails = Seq("new+tag@example.com", "plain@example.com", "imported+tag@example.com")
          val wikiUser = User.findByAccount("Wiki person").headOption
          val accounts = emails.flatMap(User.findByEmail(_).headOption) ++ wikiUser

          (Seq(plusOffStatus, plainOnStatus, wikiOnlyStatus, importedStatus) === Seq.fill(4)(SEE_OTHER)) and
            (accounts.size === 4) and
            (redirectLocation(plusOff) must beSome("/admin/users?contestId=10")) and
            (accounts.map(_.password) === Seq.fill(4)(None)) and
            (accounts.forall(a => passwordIsNull(a.getId)) must beTrue) and
            (accounts.map(a => UserContestJdbc.findByUser(a.getId)) ===
              accounts.map(a => Seq(UserContest(a.getId, 10, "jury")))) and
            (created("new+tag@example.com").fullname === "Сергій Тестовий") and
            (mail.recipients === Seq("plain@example.com"))
        } finally restore()
      }
    }
  }
}
