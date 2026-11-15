package views

import org.jsoup.Jsoup
import org.jsoup.nodes.{Document, Element}
import org.specs2.mutable.Specification

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}

/** The large views' image has its box at its loaded height before the file arrives
  * (Global.largeImageBox), so a link to #comments, below it, stays where it scrolled: `width` and
  * `height` attributes, as wide as its `sizes` in the stored proportions, the height following the
  * width (main.css), fitted into the column by `mw-100 mh-100`; large-view.js removes them once the
  * file has loaded (javascript-test/large-view.test.js). None for a file of unknown size. The
  * browser test (voting.spec.js, J-25) checks the box's size. No database or Play app.
  */
class LargeViewImageBoxSpec extends Specification {

  import LargeViewRenders._

  private def img(doc: Document): Element = doc.getElementById("large_image")

  /** The image's width and height attributes, None without. */
  private def box(doc: Document): Option[(String, String)] =
    Option.when(img(doc).hasAttr("width") || img(doc).hasAttr("height"))(img(doc).attr("width") -> img(doc).attr("height"))

  private val landscape = Map(middle -> (4000, 3000)) // shown 1280 wide
  private val portrait  = Map(middle -> (3300, 4400)) // 825

  /** The image fitted into the column, no inline style (UI §2.1), main.css's rule for the box (its selector). */
  private def fitted(doc: Document) =
    (img(doc).classNames.contains("mw-100") must beTrue) and (img(doc).classNames.contains("mh-100") must beTrue) and
      (img(doc).hasAttr("style") must beFalse) and
      (new String(Files.readAllBytes(Paths.get("public/stylesheets/main.css")), UTF_8) must
        contain("#large_image[width][height] {"))

  "a round's large view" should {

    "give its image the box of its loaded size, from the stored width and height" in {
      val doc = Jsoup.parse(large(ratedRound, middle, sizes = landscape))
      (box(doc) must beSome(("1280", "960"))) and (img(doc).attr("sizes") must_== "1280px") and fitted(doc)
    }

    "fit a tall image's box into the box by its height" in {
      val doc = Jsoup.parse(large(binaryRound, middle, sizes = portrait))
      (box(doc) must beSome(("825", "1100"))) and (img(doc).attr("sizes") must_== "825px")
    }

    "give no box to a file of unknown size" in {
      (box(Jsoup.parse(large(ratedRound, middle))) must beNone) and
        (box(Jsoup.parse(large(ratedRound, middle, sizes = Map(middle -> (4000, 0))))) must beNone) and
        (box(Jsoup.parse(large(ratedRound, middle, sizes = Map(middle -> (0, 3000))))) must beNone)
    }

    "leave a video's box the column's" in {
      val video = Jsoup.parse(large(ratedRound, middle, videos = Set(middle))).selectFirst("#mainContent video")
      (video.attr("width") must_== "100%") and (video.attr("height") must_== "100%") and (video.hasAttr("style") must beFalse)
    }
  }

  "a contest file's large view" should {

    "give its image the same box" in {
      val doc = Jsoup.parse(contestFile(middle, sizes = landscape))
      (box(doc) must beSome(("1280", "960"))) and fitted(doc)
    }

    "give a deleted file's cached thumbnail the box of its width" in {
      // the cache's widest thumbnail of a 4000 × 3000 file: 500 px tall, 666 wide
      val doc = Jsoup.parse(contestFile(middle, sizes = landscape, deleted = Set(middle)))
      (img(doc).attr("src") must contain("/666px-")) and (box(doc) must beSome(("666", "500")))
    }

    "give no box to a file of unknown size" in {
      box(Jsoup.parse(contestFile(middle))) must beNone
    }
  }
}
