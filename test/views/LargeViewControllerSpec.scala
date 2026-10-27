package views

import controllers.{GalleryView, RoundList, VoteFilter}
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

      val round = Round(id = Some(1), number = 1, contestId = 1, active = true, rates = Round.ratesById(5))
      val html = views.html.large.main_large(
        user = User("name", "email", id = Some(1), roles = Set("jury"), contestId = Some(1)),
        // the juror's own view, where the keys vote
        list = RoundList(round, asUserId = 1, filter = VoteFilter.Unrated, view = GalleryView.ByRate),
        score = 0.0,
        readOnly = false,
        files = files,
        index = 0)(Html("html"))

      val view = contentAsString(html)
      view must not contain "&amp"
      // votes are POSTs of the vote form, with its CSRF token
      view must contain("<form id=\"vote-form\" method=\"post\"")
      view must contain("name=\"csrfToken\"")
      view must contain("voteLarge('/large/round/1/pageid/1/select/' + Math.round(")
      view must contain("'?module=byrate&filter=unrated&sort=rate'")
      // the keys vote and move by paths, never by http:// URLs: behind HTTPS a form posted
      // to http:// is redirected to https as a GET, and the vote is lost
      view must contain("voteLarge(\"/large/round/1/pageid/1/select/1")
      view must contain("window.location = \"/large/")
      view must not(beMatching("(?s).*(voteLarge\\(|window\\.location = )\"https?://.*"))
    }
  }

}