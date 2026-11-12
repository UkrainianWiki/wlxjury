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

  /** The import form's text area's content, as the page has it (HTML-escaped). */
  private def textarea(page: String): String =
    """(?s)<textarea[^>]*id="userstoimport"[^>]*>(.*?)</textarea>""".r.findFirstMatchIn(page).map(_.group(1)).getOrElse("")

  "Import Users" should {
    "list a line that fails with an error, with the reference of its log line, and add the other lines" in {
      testDbApp { implicit app =>
        createContests(10, 11)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val existing = account("existing@example.com", Some(11), "jury", verified = true)
        val mail = new RecordingMail
        try {
          productionShape() // a new account fails, as on production before V98
          val lines = "existing@example.com\nСергій Тестовий <new+tag@example.com>, User:Bad#name\n\nplain-new@example.com"
          val (result, log) = accountLog {
            val r = importUsers(mail, admin, 10, lines, sendInvitation = true)
            (status(r), contentAsString(r))
          }
          val (code, page) = result
          val failedLines = log.filter(_.startsWith("user_import_line_failed"))
          val refs = failedLines.flatMap(l => "ref=([0-9a-f]{8})".r.findFirstMatchIn(l).map(_.group(1)))

          (code === OK) and
            (failedLines.size === 2) and (refs.size === 2) and
            (failedLines.head must contain("line=2")) and (failedLines(1) must contain("line=4")) and
            (failedLines.head must contain("SQLIntegrityConstraintViolationException")) and
            (page must contain(s"Line 2: new+tag@example.com: an error on our side (reference ${refs.head}).")) and
            (page must contain(s"Line 4: plain-new@example.com: an error on our side (reference ${refs(1)}).")) and
            (page must contain("Line 2: Bad#name: not an email address or a valid Wikimedia account name.")) and
            (page must contain("send its reference to the jury tool&#x27;s maintainers")) and
            // the form again with only the people not added, the name kept
            (textarea(page).linesIterator.toSeq.map(_.trim).filter(_.nonEmpty) must beLike {
              case Seq(named, "Bad#name", "plain-new@example.com") =>
                (named must contain("Сергій Тестовий")) and (named must endWith("&lt;new+tag@example.com&gt;"))
            }) and
            (page must contain("Existing accounts added to the contest: 1.")) and
            // the line that could be added is, and committed; no mail to anyone not added
            (UserContestJdbc.findByUser(existing.getId).map(_.contestId).sorted === Seq(10L, 11L)) and
            (User.findByEmail("new+tag@example.com") must beEmpty) and
            (User.findByEmail("plain-new@example.com") must beEmpty) and
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
