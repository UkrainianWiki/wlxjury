package controllers

import db.scalikejdbc.{PlayTestDb, User}
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import scala.concurrent.Future

/** A request for a contest or user that does not exist is answered with the error page
  * (404), never a server error: root passes every contest's permission check, so only
  * the lookup stands between a mistyped id and the page.
  */
class MissingResourceSpec extends PlaySpecification with PlayTestDb {

  sequential

  private def request(app: Application, method: String, path: String, root: User): Future[Result] =
    route(app, FakeRequest(method, path).withSession(Secured.UserName -> root.email).withCSRFToken).get

  "root" should {

    "find no contest's images page or import for a missing contest" in {
      testDbApp { app =>
        val root = User.create(User("Root", "root@example.com", roles = Set(User.ROOT_ROLE), password = Some("hash")))
        val images = request(app, GET, "/contest/999999/images", root)
        status(images) === NOT_FOUND
        contentAsString(images) must contain("There is no such contest.")

        val imported = request(app, POST, "/contest/999999/importimages", root)
        status(imported) === NOT_FOUND
        contentAsString(imported) must contain("There is no such contest.")
      }
    }

    "find no user to reset the password of for a missing user" in {
      testDbApp { app =>
        val root = User.create(User("Root", "root@example.com", roles = Set(User.ROOT_ROLE), password = Some("hash")))
        val reset = request(app, POST, "/users/resetpasswd/999999", root)
        status(reset) === NOT_FOUND
        contentAsString(reset) must contain("There is no such user.")
      }
    }
  }
}
