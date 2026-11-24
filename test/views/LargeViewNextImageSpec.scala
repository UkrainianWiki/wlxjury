package views

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.specs2.mutable.Specification

/** The large view's next file's photo, which large-next-image.js loads by script so the next
  * vote's page takes it from the browser cache (docs/performance/2026-11-24-large-view-timing.md):
  * #wlx-next-image carries the src, srcset and sizes the next file's own page gives its
  * #large_image, as the cache needs. Nothing for the last file, a video, a file of unknown size
  * (served through this server) or a deleted file. No database or Play app.
  */
class LargeViewNextImageSpec extends Specification {

  import LargeViewRenders._

  private val known = (0 until fileCount).map(i => i -> (4000, 3000)).toMap

  private def next(doc: Document) = Option(doc.getElementById("wlx-next-image"))
  private def own(doc: Document) = doc.getElementById("large_image")

  "a round's large view" should {

    "carry the next file's src, srcset and sizes as its own page renders them" in {
      val here = Jsoup.parse(large(ratedRound, middle, sizes = known))
      val there = own(Jsoup.parse(large(ratedRound, middle + 1, sizes = known)))
      val carried = next(here).get
      (carried.attr("data-src") must_== there.attr("src")) and
        (carried.attr("data-srcset") must_== there.attr("srcset")) and
        (carried.attr("data-sizes") must_== there.attr("sizes")) and
        (carried.attr("data-src") must contain("upload.wikimedia.org")) and
        (carried.hasAttr("hidden") must beTrue) and
        // this page's own photo is not the next one
        (carried.attr("data-src") must_!= own(here).attr("src"))
    }

    "carry nothing on the last file" in {
      next(Jsoup.parse(large(ratedRound, fileCount - 1, sizes = known))) must beNone
    }

    "carry nothing when the next file is a video or of unknown size" in {
      (next(Jsoup.parse(large(ratedRound, middle, videos = Set(middle + 1), sizes = known))) must beNone) and
        (next(Jsoup.parse(large(ratedRound, middle))) must beNone)
    }

    "carry the next file in a round of several criteria too: the same-file reloads find it cached" in {
      next(Jsoup.parse(large(criteriaRound, middle, sizes = known))) must beSome
    }

    "not preload by a server link" in {
      Jsoup.parse(large(ratedRound, middle, sizes = known)).select("link[rel=preload]").size must_== 0
    }

    "load the script as the other large-view scripts" in {
      Jsoup.parse(large(ratedRound, middle, sizes = known)).select("script[src*=large-next-image]").size must_== 1
    }
  }

  "a contest file's large view" should {

    "carry the next file's src, srcset and sizes as its own page renders them" in {
      val there = own(Jsoup.parse(contestFile(middle + 1, sizes = known)))
      val carried = next(Jsoup.parse(contestFile(middle, sizes = known))).get
      (carried.attr("data-src") must_== there.attr("src")) and
        (carried.attr("data-srcset") must_== there.attr("srcset")) and
        (carried.attr("data-sizes") must_== there.attr("sizes"))
    }

    "carry nothing for a deleted next file" in {
      next(Jsoup.parse(contestFile(middle, sizes = known, deleted = Set(middle + 1)))) must beNone
    }
  }
}
