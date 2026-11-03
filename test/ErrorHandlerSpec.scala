import db.scalikejdbc.PlayTestDb
import play.api.test.{FakeRequest, PlaySpecification}

/** Error pages show a message from conf/messages, never the error's own text. */
class ErrorHandlerSpec extends PlaySpecification with PlayTestDb {

  sequential

  "a server error" should {
    "show a reference, not the exception's message" in {
      testDbApp { implicit app =>
        val handler = app.injector.instanceOf[ErrorHandler]
        val result = handler.onServerError(FakeRequest(GET, "/rounds"), new RuntimeException("jdbc:mariadb://secret-host"))

        status(result) === INTERNAL_SERVER_ERROR
        val page = contentAsString(result)
        page must not(contain("secret-host"))
        page must beMatching("(?s).*tell the organizers this reference: [0-9a-f]{8}\\..*")
      }
    }
  }

  "a client error" should {
    "show the status's message, not the error's text" in {
      testDbApp { implicit app =>
        val handler = app.injector.instanceOf[ErrorHandler]
        val notFound = handler.onClientError(FakeRequest(GET, "/nowhere"), NOT_FOUND, "<b>raw</b>")
        status(notFound) === NOT_FOUND
        contentAsString(notFound) must contain("The page you asked for was not found.")
        contentAsString(notFound) must not(contain("<b>raw</b>"))

        val badRequest = handler.onClientError(FakeRequest(GET, "/x"), BAD_REQUEST, "For input string: \"abc\"")
        status(badRequest) === BAD_REQUEST
        contentAsString(badRequest) must not(contain("input string"))
      }
    }
  }
}
