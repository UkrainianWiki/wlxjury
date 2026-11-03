package views

import controllers.{Pager, RateDistribution, RoundList}
import play.api.mvc.{Flash, RequestHeader}
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

/** unitegallery shows a tile's `data-description` with jQuery's `.html()`, so the attribute
  * holds HTML: the file's title in it is escaped once more, and `.html()` shows it as text.
  */
class GalleryDescriptionViewSpec extends PlaySpecification {

  private val juror = LargeViewRenders.juror

  implicit val request: RequestHeader = FakeRequest("GET", "/gallery/round/1/user/1/page/1").withCSRFToken
  implicit val flash: Flash = Flash()
  implicit val messages: play.api.i18n.Messages =
    LargeViewRenders.messages(request.asInstanceOf[play.api.mvc.Request[_]])

  "a gallery tile's description" should {
    "hold the title as escaped HTML, so markup in it is text" in {
      val files = LargeViewRenders.files().take(1).map { f =>
        f.copy(image = f.image.copy(title = "File:A & <i>b</i>.jpg"))
      }
      val html = contentAsString(views.html.gallery(juror, RoundList(LargeViewRenders.ratedRound, juror.getId),
        files, Pager(1, 1), Nil, None, new RateDistribution(Map.empty)))
      val description = org.jsoup.Jsoup.parse(html).select("[data-description]").first().attr("data-description")
      description === "<b>File:A &amp; &lt;i&gt;b&lt;/i&gt;.jpg</b>"
      org.jsoup.Jsoup.parse(description).select("i").size === 0
    }
  }

  "a gallery tile's caption" should {
    "hold the title as escaped HTML in its link, so markup in it is text" in {
      val files = LargeViewRenders.files().take(1).map { f =>
        f.copy(image = f.image.copy(title = "File:A & <i>b</i>.jpg"))
      }
      val html = contentAsString(views.html.gallery(juror, RoundList(LargeViewRenders.ratedRound, juror.getId),
        files, Pager(1, 1), Nil, None, new RateDistribution(Map.empty)))
      // what unitegallery inserts with `.html()`: the attribute as the browser decodes it
      val caption = org.jsoup.Jsoup.parse(html).select("#gallery img").first().attr("alt")
      val link = org.jsoup.Jsoup.parseBodyFragment(caption).select("a")
      (caption must contain(">File:A &amp; &lt;i&gt;b&lt;/i&gt;.jpg</a>")) and
        (link.size === 1) and (link.select("i").size === 0) and
        (link.text === "File:A & <i>b</i>.jpg") and
        (link.attr("href") === "https://commons.wikimedia.org/wiki/File%3AA_%26_%3Ci%3Eb%3C%2Fi%3E.jpg")
    }

    "keep a quote in the title inside the link's text, the href encoded" in {
      val files = LargeViewRenders.files().take(1).map { f =>
        f.copy(image = f.image.copy(title = "File:St Andrew's.jpg"))
      }
      val html = contentAsString(views.html.gallery(juror, RoundList(LargeViewRenders.ratedRound, juror.getId),
        files, Pager(1, 1), Nil, None, new RateDistribution(Map.empty)))
      val caption = org.jsoup.Jsoup.parse(html).select("#gallery img").first().attr("alt")
      val link = org.jsoup.Jsoup.parseBodyFragment(caption).select("a")
      // the thumbnail's alt is this text (unitegallery.js htmlToText; javascript-test/unitegallery-alt.test.js)
      (link.size === 1) and (link.text === "File:St Andrew's.jpg") and
        (link.attr("href") === "https://commons.wikimedia.org/wiki/File%3ASt_Andrew%27s.jpg") and
        (org.jsoup.Jsoup.parseBodyFragment(caption).body.text === "File:St Andrew's.jpg")
    }
  }
}
