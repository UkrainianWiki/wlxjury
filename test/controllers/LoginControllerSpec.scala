package controllers

import db.scalikejdbc.{TestDb, User}
import org.apache.pekko.stream.Materializer
import org.specs2.mock.Mockito
import play.api.Application
import play.api.mvc._
import play.api.test.CSRFTokenHelper._
import play.api.test._
import services.{SMTPOrWikiMail, UserService}

class LoginControllerSpec extends PlaySpecification with Results with TestDb with Mockito {

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
               roles = Set(User.ROOT_ROLE))
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
}
