package views

import controllers.{GalleryView, Pager, RateDistribution, RoundList}
import org.intracer.wmua.{Image, ImageWithRating, Region, Selection}
import org.jsoup.Jsoup
import play.api.mvc.{Flash, RequestHeader}
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import scala.jdk.CollectionConverters._

/** The by-rate galleries (galleryByRate, galleryByRateRegions) of a rated round: each file's
  * rank and rate in its own block inside its tile, every tile inside the gallery list, and no
  * stray markup text (a mangled tag once left `">` before each rank and closed the list after
  * the first tile).
  */
class GalleryByRateViewSpec extends PlaySpecification {

  private val juror = LargeViewRenders.juror
  private val round = LargeViewRenders.ratedRound

  private val files: Seq[ImageWithRating] = (0 until 4).map { i =>
    val pageId = 201L + i
    ImageWithRating(
      Image(pageId = pageId, title = s"File:Image $pageId.jpg",
        url = Some(s"https://upload.wikimedia.org/wikipedia/commons/a/ab/Image_$pageId.jpg"),
        monumentId = Some(s"07-101-000$i")),
      selection = Seq(Selection(pageId, juror.getId, round.getId, i + 1)),
      rank = Some(i + 1)
    )
  }

  implicit val request: RequestHeader = FakeRequest("GET", "/byrate/round/1/user/1/page/1").withCSRFToken
  implicit val flash: Flash = Flash()
  implicit val messages: play.api.i18n.Messages = LargeViewRenders.messages(request.asInstanceOf[play.api.mvc.Request[_]])

  private val list = RoundList(round, juror.getId, view = GalleryView.ByRate)

  private def checkTiles(html: String, count: Int) = {
    val doc = Jsoup.parse(html)
    val boxes = doc.select("li.gallerybox").asScala
    boxes must haveSize(count)
    forall(boxes) { box =>
      box.parent().tagName() === "ul"
      box.parent().hasClass("gallery") must beTrue
      box.select("div.rank-line").size() === 1
      box.select("div.rank-line b").text() must not(beEmpty)
    }
    // no markup shown as text, nor control characters
    doc.body().text() must not(contain("\">"))
    html must not(contain("\u0001"))
  }

  "the by-rate gallery" should {
    "keep each tile, with its rank, inside the gallery list" in {
      val html = contentAsString(views.html.galleryByRate(
        juror, list, files, Pager(1, files.size), Nil, None, new RateDistribution(Map.empty)))
      checkTiles(html, files.size)
    }
  }

  "the by-rate gallery by regions" should {
    "keep each tile, with its rank, inside its region's gallery list" in {
      val html = contentAsString(views.html.galleryByRateRegions(
        juror, list, files, Pager(1, files.size), Nil, None, Seq(Region("07", "Region 07", Some(files.size)))))
      checkTiles(html, files.size)
    }
  }
}
