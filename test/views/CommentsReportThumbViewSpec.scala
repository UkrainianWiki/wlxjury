package views

import controllers.{GalleryView, Pager, RateDistribution, RoundList}
import org.intracer.wmua.{Image, ImageWithRating, Vote}
import org.jsoup.Jsoup
import play.api.mvc.{Flash, RequestHeader}
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

/** The comments report's thumbnails: a video's is its still thumbnail with the ▶ mark, as in
  * the other lists, inside the row's thumbnail link (hidden from screen readers: the title's
  * link names the file), never a `<video>`. No database or Play app.
  */
class CommentsReportThumbViewSpec extends PlaySpecification {

  private val juror = LargeViewRenders.juror
  private val round = LargeViewRenders.binaryRound

  implicit val request: RequestHeader = FakeRequest("GET", "/comments/round/1/user/1/page/1").withCSRFToken
  implicit val flash: Flash = Flash()
  implicit val messages: play.api.i18n.Messages = LargeViewRenders.messages(request.asInstanceOf[play.api.mvc.Request[_]])

  private def file(pageId: Long, title: String, url: String) =
    ImageWithRating(Image(pageId = pageId, title = title, url = Some(url), width = 1920, height = 1080),
      votes = Seq(Vote(pageId, juror.getId, round.getId, 1)))

  "the comments report" should {
    "show a video as its still thumbnail with a play mark, and an image without one" in {
      val files = Seq(
        file(401L, "File:Image 401.jpg", "https://upload.wikimedia.org/wikipedia/commons/a/ab/Image_401.jpg"),
        file(402L, "File:Video 402.webm", "https://upload.wikimedia.org/wikipedia/commons/c/cd/Video_402.webm"))
      val doc = Jsoup.parse(contentAsString(views.html.commentsReport(juror,
        RoundList(round, juror.getId, view = GalleryView.Comments), files, Pager(1, files.size), Nil, None,
        new RateDistribution(Map.empty), Map.empty, Map.empty, commentTotal = 0)))
      val image = doc.selectFirst("article[id=401] a[aria-hidden=true]")
      val video = doc.selectFirst("article[id=402] a[aria-hidden=true]")
      def thumb(link: org.jsoup.nodes.Element) = {
        val imgs = link.select("img")
        (imgs.size must_== 1) and (imgs.first.hasAttr("alt") must beTrue) and (imgs.first.attr("alt") must_== "")
      }
      (doc.select("video").size must_== 0) and
        thumb(video) and thumb(image) and
        (video.selectFirst("img").attr("src") must
          beMatching(raw"/wikipedia/commons/thumb/c/cd/Video_402\.webm/\d+px--Video_402\.webm\.jpg")) and
        (video.select(".wlx-video-mark[aria-hidden=true]").size must_== 1) and
        (image.select(".wlx-video-mark").size must_== 0)
    }
  }
}
