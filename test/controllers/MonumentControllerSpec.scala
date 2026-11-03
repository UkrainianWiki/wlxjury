package controllers

import db.scalikejdbc.PlayTestDb
import org.specs2.mutable.Specification
import play.api.test.FakeRequest
import play.api.test.Helpers._

class MonumentControllerSpec extends Specification with PlayTestDb {

  sequential

  "the monument lists" should {
    "send a visitor who isn't signed in to the login page" in {
      testDbApp { implicit app =>
        Seq("/monuments", "/monuments/id/07-101-0001").map { path =>
          val result = route(app, FakeRequest("GET", path)).get
          (status(result), redirectLocation(result))
        } === Seq("/monuments", "/monuments/id/07-101-0001")
          .map(path => (SEE_OTHER, Some(routes.LoginController.login(Some(path)).url)))
      }
    }
  }
}
