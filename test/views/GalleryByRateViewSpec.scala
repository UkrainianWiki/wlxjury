package views

import controllers.{GalleryView, Pager, RateDistribution, RoundList}
import org.intracer.wmua.{Image, ImageWithRating, Selection}
import org.jsoup.Jsoup
import play.api.mvc.{Flash, RequestHeader}
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import scala.jdk.CollectionConverters._

/** The by-rate gallery of a rated round: each file's rank and rate in its own block inside its
  * tile, every tile inside the gallery list, and no stray markup text (a mangled tag once left
  * `">` before each rank and closed the list after the first tile); of a binary round, its
  * approvals or a juror's votes, never stars.
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

  "a binary round's by-rate gallery" should {
    val binary = LargeViewRenders.binaryRound

    def tile(file: ImageWithRating, asUserId: Long) = {
      val html = contentAsString(views.html.galleryByRate(juror, RoundList(binary, asUserId, view = GalleryView.ByRate),
        Seq(file), Pager(1, 1), Nil, None, new RateDistribution(Map.empty)))
      Jsoup.parse(html).selectFirst("li.gallerybox .rate-stars")
    }

    def file(rates: Int*) = ImageWithRating(Image(pageId = 301L, title = "File:Binary.jpg"),
      selection = rates.zipWithIndex.map { case (rate, i) => Selection(301L, i + 1L, binary.getId, rate) })

    "show all the jurors' approvals as a check and their number, without stars" in {
      // as the query of all the jurors' votes has them: one row per file, their rates' sum
      // (2 approvals and a rejection give 1) and the approvals counted apart
      val grouped = ImageWithRating(Image(pageId = 301L, title = "File:Binary.jpg"),
        selection = Seq(Selection(301L, 0, 0, 1)), countFromDb = 3, approvalsFromDb = Some(2))
      val rate = tile(grouped, asUserId = 0)
      (rate.select(".bi-check-lg[aria-hidden=true]").size must_== 1) and
        (rate.select("[class*=bi-star]").size must_== 0) and
        (rate.select("button").size must_== 0) and
        (rate.select(".visually-hidden").text must_== "approvals") and
        (rate.text must contain("2"))
    }

    "show a juror's vote: a check if selected, a cross if rejected, nothing if unrated" in {
      def marks(rate: Int) = {
        val r = tile(file(rate), asUserId = 1)
        (r.select(".bi-check-lg").size, r.select(".bi-x-lg").size, r.select("[class*=bi-star]").size,
          r.select(".visually-hidden").text)
      }
      (marks(1) must_== ((1, 0, 0, "selected"))) and
        (marks(-1) must_== ((0, 1, 0, "rejected"))) and
        (marks(0) must_== ((0, 0, 0, "")))
    }
  }
}
