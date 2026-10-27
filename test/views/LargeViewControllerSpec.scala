package views

import db.scalikejdbc.{Round, User}
import org.intracer.wmua._
import play.api.test.CSRFTokenHelper._
import play.api.test.{Helpers, PlaySpecification}
import play.twirl.api.Html

class LargeViewControllerSpec extends PlaySpecification {

  import play.api.i18n._
  implicit val lang: Lang = Lang("en-US")

  "large view" should {
    "have correct rating link" in {
      implicit val request = play.api.test.FakeRequest("GET", "/").withCSRFToken
      implicit val messages = Helpers.stubMessagesApi().preferred(request)

      val files = Seq(
        ImageWithRating(
          Image(pageId = 1, title = "File:1.jpg"),
          selection = Nil)
      )

      val html = views.html.large.main_large(
        title = "title",
        user = User("name", "email", id = Some(1), roles = Set("jury"), contestId = Some(1)),
        asUserId = 0,
        score = 0.0,
        readOnly = false,
        url = "url",
        files = files,
        index = 0,
        page = 0,
        rate = Some(0),
        region = "all",
        round = Round(id = Some(1), number = 1, contestId = 1, active = true, rates = Round.ratesById(5)),
        module = "byrate")(Html("html"))

      val view = contentAsString(html)
      view must not contain "&amp"
      // votes are POSTs of the vote form, with its CSRF token
      view must contain("<form id=\"vote-form\" method=\"post\"")
      view must contain("name=\"csrfToken\"")
      view must contain("voteLarge('url' + Math.round(")
    }
  }

}