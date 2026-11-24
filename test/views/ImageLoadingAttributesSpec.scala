package views

import controllers.{GalleryView, Global, Pager, RateDistribution, RoundList}
import org.intracer.wmua.{Image, ImageWithRating, Vote}
import org.jsoup.Jsoup
import play.api.mvc.{Flash, RequestHeader}
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import scala.jdk.CollectionConverters._

/** Image loading (the Modern Web Guidance guide optimize-image-priority): the thumbnails of the
  * by-rate gallery (after its first row) and the large view's strip load lazily and decode off the main thread, each
  * with its displayed size as width and height; the large view's main photo loads first
  * (fetchpriority) and never lazily. The tiles of the juror gallery and the contest files are
  * made by Unite Gallery's script, so they have none of these in the markup. No database.
  */
class ImageLoadingAttributesSpec extends PlaySpecification {

  import LargeViewRenders._

  private val sizes = (0 until fileCount).map(_ -> (4000, 3000)).toMap

  private def thumbs(html: String) = Jsoup.parse(html).select("div.thumb_small img.cropped").asScala.toSeq

  private def lazyAndSized(imgs: Seq[org.jsoup.nodes.Element], w: Int, h: Int) =
    (imgs must not(beEmpty)) and forall(imgs) { i =>
      (i.attr("loading") must_== "lazy") and (i.attr("decoding") must_== "async") and
        (i.attr("width") must_== w.toString) and (i.attr("height") must_== h.toString) and
        (i.hasAttr("style") must beFalse)
    }

  private def main(html: String) = Jsoup.parse(html).getElementById("large_image")

  private def notLazyButFirst(img: org.jsoup.nodes.Element) =
    (img.attr("fetchpriority") must_== "high") and (img.hasAttr("loading") must beFalse) and
      (img.hasAttr("decoding") must beFalse)

  "the large view" should {
    "load its thumbnail strip lazily at its displayed size, and its main photo first" in {
      val html = large(ratedRound, middle, sizes = sizes)
      // a 4:3 file in the 150 x 120 box is 150 x 113
      lazyAndSized(thumbs(html), 150, 113) and notLazyButFirst(main(html))
    }

    "give a file of unknown size no width and height, but loading" in {
      val imgs = thumbs(large(ratedRound, middle))
      (imgs must not(beEmpty)) and forall(imgs)(i => (i.attr("loading") must_== "lazy") and (i.hasAttr("width") must beFalse))
    }

    "load a contest file's strip lazily and its main photo first" in {
      val html = contestFile(middle, sizes = sizes)
      lazyAndSized(thumbs(html), 150, 113) and notLazyButFirst(main(html))
    }

  }

  "the by-rate gallery" should {
    "load its thumbnails lazily at their displayed size" in {
      implicit val request: RequestHeader = FakeRequest("GET", "/byrate/round/1/user/1/page/1").withCSRFToken
      implicit val flash: Flash = Flash()
      implicit val messages: play.api.i18n.Messages = LargeViewRenders.messages(request.asInstanceOf[play.api.mvc.Request[_]])
      val files = (0 until 6).map { i =>
        ImageWithRating(
          Image(pageId = 201L + i, title = s"File:Image $i.jpg",
            url = Some(s"https://upload.wikimedia.org/wikipedia/commons/a/ab/Image_$i.jpg"), width = 4000, height = 3000),
          votes = Seq(Vote(201L + i, juror.getId, ratedRound.getId, i + 1)))
      }
      val html = contentAsString(views.html.galleryByRate(juror, RoundList(ratedRound, juror.getId, view = GalleryView.ByRate),
        files, Pager(1, files.size), Nil, None, new RateDistribution(Map.empty)))
      // a 4:3 file in the 300 x 250 box is 300 x 225
      // the first row (4) is the likely largest paint: eager, but sized and decoded off the main thread
      val imgs = Jsoup.parse(html).select("div.thumb img.cropped").asScala.toSeq
      (imgs.take(4).map(_.hasAttr("loading")) must_== Seq.fill(4)(false)) and
        forall(imgs.take(4))(i => (i.attr("decoding") must_== "async") and (i.attr("width") must_== "300") and (i.attr("height") must_== "225")) and
        lazyAndSized(imgs.drop(4), 300, 225)
    }
  }

  "Global.thumbBox" should {
    "fit the file into the box, and be empty without a size" in {
      (Global.thumbBox(Image(pageId = 1, title = "a", width = 4000, height = 3000), 150, 120) must beSome((150, 113))) and
        (Global.thumbBox(Image(pageId = 1, title = "a", width = 3000, height = 6000), 150, 120) must beSome((60, 120))) and
        (Global.thumbBox(Image(pageId = 1, title = "a", width = 100, height = 50), 150, 120) must beSome((100, 50))) and
        (Global.thumbBox(Image(pageId = 1, title = "a"), 150, 120) must beNone)
    }
  }
}
