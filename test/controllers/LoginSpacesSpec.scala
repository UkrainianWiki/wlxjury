package controllers

import db.scalikejdbc.{AccountTokenJdbc, PlayTestDb, User, UserContestJdbc}
import org.apache.pekko.stream.Materializer
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test._
import scalikejdbc._

import scala.concurrent.Future

/** Logins, emails and wiki accounts pasted from spreadsheets and messengers, with a space, a
  * tab or a non-breaking space around them ([[org.intracer.wmua.LoginText]]): they sign in and
  * are stored without it. Passwords keep their ASCII trim.
  */
class LoginSpacesSpec extends PlaySpecification with PlayTestDb with AccountTestSupport {

  sequential

  private val Nbsp = " "

  private def signIn(login: String, password: String)(implicit app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(loginController(new RecordingMail).auth(),
      FakeRequest(POST, "/auth").withFormUrlEncodedBody("login" -> login, "password" -> password).withCSRFToken)
  }

  private def sessionName(result: Future[Result]): Option[String] = session(result).get(Secured.UserName)

  private def as[A](user: User, contestId: Option[Long])(request: FakeRequest[A]): FakeRequest[A] =
    request.withSession(Seq(Secured.UserName -> user.email) ++ contestId.map(Secured.CurrentContestId -> _.toString): _*)

  private def save(mail: RecordingMail, as: User, contestId: Long, fields: (String, String)*)(implicit
      app: Application
  ): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(userController(mail).saveUser(),
      this.as(as, Some(contestId))(FakeRequest(POST, "/users/save")).withFormUrlEncodedBody(fields: _*).withCSRFToken)
  }

  private def importUsers(mail: RecordingMail, admin: User, contestId: Long, lines: String)(implicit
      app: Application
  ): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(userController(mail).importUsers(Some(contestId)),
      as(admin, Some(contestId))(FakeRequest(POST, s"/admin/importusers?contestId=$contestId"))
        .withFormUrlEncodedBody("userstoimport" -> lines, "role" -> "jury").withCSRFToken)
  }

  private def storedEmails: List[Option[String]] =
    DB.readOnly(implicit s => sql"SELECT email FROM users ORDER BY id".map(_.stringOpt(1)).list())

  "sign-in" should {
    "accept an email with a leading space, a trailing tab or non-breaking spaces, and store it clean" in {
      testDbApp { implicit app =>
        createContests(10)
        account("alice@example.com", Some(10), "jury")

        for (login <- Seq(" alice@example.com", "alice@example.com\t", s"${Nbsp}Alice@Example.com$Nbsp")) {
          sessionName(signIn(login, "secret")).map(_.toLowerCase) === Some("alice@example.com")
        }
        sessionName(signIn(s"${Nbsp}alice@example.com\t", "secret")) === Some("alice@example.com")
        status(signIn(s"${Nbsp}alice@example.com", "wrong")) === BAD_REQUEST
      }
    }

    "accept a wiki account name with spaces, a tab or non-breaking spaces around it" in {
      testDbApp { implicit app =>
        createContests(10)
        account("wiki@example.com", Some(10), "jury", wikiAccount = Some("Wiki juror"))

        for (login <- Seq(" Wiki juror", "Wiki_juror\t", s"${Nbsp}Wiki juror$Nbsp")) {
          sessionName(signIn(login, "secret")).map(_.replace('_', ' ')) === Some("Wiki juror")
        }
        sessionName(signIn(s"${Nbsp}Wiki juror\t", "secret")) === Some("Wiki juror")
      }
    }

    "keep the password's ASCII trim as it was: spaces around it are dropped, a non-breaking space is not" in {
      testDbApp { implicit app =>
        createContests(10)
        account("alice@example.com", Some(10), "jury")

        sessionName(signIn("alice@example.com", " secret\t")) === Some("alice@example.com")
        status(signIn("alice@example.com", s"${Nbsp}secret")) === BAD_REQUEST
        sessionName(signIn("alice@example.com", "secret")) === Some("alice@example.com")
      }
    }
  }

  "the users page's add row" should {
    "accept an email pasted with non-breaking spaces and store it clean" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val mail = new RecordingMail

        val result = save(mail, admin, 10, "id" -> "0", "fullname" -> "Pasted", "email" -> s"${Nbsp}Pasted@Example.com\t$Nbsp",
          "wikiAccount" -> s"${Nbsp}pasted_name ", "roles" -> "jury", "contest" -> "10")

        status(result) === SEE_OTHER
        val added = User.findByEmail("pasted@example.com")
        added.map(_.wikiAccount) === Seq(Some("Pasted name"))
        storedEmails === List(Some("admin@example.com"), Some("pasted@example.com"))
        UserContestJdbc.findByUser(added.head.getId).map(_.contestId) === Seq(10L)
      }
    }
  }

  "Edit user" should {
    "accept another account's email and wiki account pasted with non-breaking spaces, stored clean" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        // an invitee without a password: an admin may fix its address
        val invitee = account("old@example.com", Some(10), "jury", password = None)
        val mail = new RecordingMail

        val result = save(mail, admin, 10, "id" -> invitee.getId.toString, "fullname" -> "Invitee",
          "email" -> s"${Nbsp}New@Example.com$Nbsp", "wikiAccount" -> s"\tinvitee_wiki$Nbsp", "roles" -> "jury",
          "contest" -> "10")

        status(result) === SEE_OTHER
        val after = User.findById(invitee.getId).get
        after.email === "new@example.com"
        after.wikiAccount === Some("Invitee wiki")
      }
    }

    "keep the own email when it is sent back with a non-breaking space, and mail a clean new one" in {
      testDbApp { implicit app =>
        createContests(10)
        val juror = account("juror@example.com", Some(10), "jury", password = Some("pw-juror"), verified = true)
        val mail = new RecordingMail

        val same = save(mail, juror, 10, "id" -> juror.getId.toString, "fullname" -> "Renamed",
          "email" -> s"${Nbsp}juror@example.com\t")
        status(same) === SEE_OTHER
        User.findById(juror.getId).map(_.fullname) === Some("Renamed")
        mail.sent must beEmpty

        val changed = save(mail, juror, 10, "id" -> juror.getId.toString, "fullname" -> "Renamed",
          "email" -> s"${Nbsp}New@Example.com$Nbsp", "currentPassword" -> "pw-juror")
        status(changed) === SEE_OTHER
        mail.recipients === Seq("new@example.com")
        DB.readOnly(implicit s =>
          sql"SELECT email FROM account_token WHERE purpose = ${AccountTokenJdbc.EmailChange}".map(_.string(1)).list()) ===
          List("new@example.com")
      }
    }
  }

  "Import users" should {
    "take an email with non-breaking spaces as that email's account, not a wiki user named with it" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin", verified = true)
        val mail = new RecordingMail

        status(importUsers(mail, admin, 10, s"${Nbsp}imported@example.com$Nbsp\n\tUser:Imported_wiki$Nbsp")) === SEE_OTHER

        User.findByEmail("imported@example.com").map(_.wikiAccount) === Seq(None)
        User.findByAccount("Imported wiki").map(_.email) === Seq("")
        storedEmails === List(Some("admin@example.com"), Some("imported@example.com"), None)
        User.findAll().flatMap(_.wikiAccount) === Seq("Imported wiki")
      }
    }
  }
}
