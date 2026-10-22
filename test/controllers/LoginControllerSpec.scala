package controllers

import db.scalikejdbc.{PlayTestDb, User}
import org.apache.pekko.stream.Materializer
import org.specs2.mock.Mockito
import play.api.Application
import play.api.mvc._
import play.api.test.CSRFTokenHelper._
import play.api.test._
import services.{SMTPOrWikiMail, UserService}

class LoginControllerSpec extends PlaySpecification with Results with PlayTestDb with Mockito {

  sequential

  def userService(implicit app: Application) =
    new UserService(mock[SMTPOrWikiMail], app.configuration)
  def login(implicit app: Application) =
    new LoginController(userService, Helpers.stubControllerComponents())

  "auth" should {
    "fail empty request" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        val result = login.auth().apply(FakeRequest().withCSRFToken)
        status(result) === BAD_REQUEST
      }
    }

    "fail when no users" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        val result = login
          .auth()
          .apply(
            FakeRequest()
              .withHeaders(
                "login" -> "qwerty",
                "password" -> "1234"
              )
              .withCSRFToken)

        status(result) === BAD_REQUEST
      }
    }

    "fail when wrong password" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        User.create(User("name", "qwerty@dot.com"))

        val result = login
          .auth()
          .apply(
            FakeRequest()
              .withHeaders(
                "login" -> "qwerty@dot.com",
                "password" -> "1234"
              )
              .withCSRFToken)

        status(result) === BAD_REQUEST
      }
    }

    "no rights" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        User.create(
          User("name", "qwerty@dot.com", password = Some(User.sha1("strong"))))
        val result = login
          .auth()
          .apply(
            FakeRequest()
              .withFormUrlEncodedBody(
                "login" -> "qwerty@dot.com",
                "password" -> "strong"
              )
              .withCSRFToken)

        status(result) === SEE_OTHER
        header(LOCATION, result) === Some(
          "/error?message=You+don%27t+have+permission+to+access+this+page")
      }
    }

    "root rights" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        User.create(
          User("name",
               "qwerty@dot.com",
               password = Some(User.sha1("strong")),
               isRoot = true)
        )
        val result = login
          .auth()
          .apply(
            FakeRequest()
              .withFormUrlEncodedBody(
                "login" -> "qwerty@dot.com",
                "password" -> "strong"
              )
              .withCSRFToken)

        status(result) === SEE_OTHER
        header(LOCATION, result) === Some("/contests")
      }
    }

  }

  "signUp" should {
    "log in as the new user, not as the user named by the password" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        User.create(
          User("root",
               "root@dot.com",
               password = Some(User.sha1("strong")),
               roles = Set(User.ROOT_ROLE))
        )
        val result = login
          .signUp()
          .apply(
            FakeRequest()
              .withFormUrlEncodedBody(
                "login" -> "new@dot.com",
                "password" -> "root@dot.com",
                "repeat.password" -> "root@dot.com"
              )
              .withCSRFToken)

        status(result) === SEE_OTHER
        session(result).get(Secured.UserName) === Some("new@dot.com")
      }
    }

    def signUp(loginName: String)(implicit app: Application) = {
      implicit val mat: Materializer = app.materializer
      login
        .signUp()
        .apply(
          FakeRequest()
            .withFormUrlEncodedBody(
              "login" -> loginName,
              "password" -> "anything",
              "repeat.password" -> "anything"
            )
            .withCSRFToken)
    }

    "not log in as an existing account with the email" in {
      testDbApp { implicit app =>
        val root = User.create(
          User("root", "root@dot.com", password = Some(User.sha1("strong")), roles = Set(User.ROOT_ROLE))
        )
        val result = signUp("Root@Dot.com")

        status(result) === BAD_REQUEST
        session(result).get(Secured.UserName) must beNone
        User.findAll().map(_.id) === Seq(root.id)
        User.findById(root.getId).flatMap(_.password) === Some(User.sha1("strong"))
      }
    }

    "not take a login that is not an email: it would be read as someone's wiki account" in {
      testDbApp { implicit app =>
        User.create(User("Wiki user", "wiki@dot.com", wikiAccount = Some("WikiUser"), password = Some("x")))
        val result = signUp("WikiUser")

        status(result) === BAD_REQUEST
        session(result).get(Secured.UserName) must beNone
        User.findAll().size === 1
      }
    }
  }
}
