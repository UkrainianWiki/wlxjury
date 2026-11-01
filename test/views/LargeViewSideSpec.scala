package views

import org.jsoup.Jsoup
import org.jsoup.nodes.{Document, Element}
import org.specs2.mutable.Specification

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._

/** The large views' side column: under the buttons and stars, the file's details, which the
  * stars rate, open by default; then a strip of only the files after it, closed by default,
  * none at the list's end. Each section's heading is a button for Bootstrap's collapse, and
  * large-sections.js keeps its state (javascript-test/large-sections.test.js). No database or
  * Play app.
  */
class LargeViewSideSpec extends Specification {

  import LargeViewRenders._

  /** Where an element is in the document, to compare two elements' order. */
  private def at(doc: Document, e: Element): Int = doc.getAllElements.indexOf(e)

  /** The page ids the strip of following files links, in order; empty without the strip. */
  private def strip(doc: Document): Seq[Long] =
    doc.select("#large-next a[href]").asScala.toSeq.map(a => a.attr("href").replaceAll(".*/(\\d+)(\\?.*)?$", "$1").toLong)

  private def checkSections(doc: Document, stripShown: Boolean) = {
    val info = doc.getElementById("large-info")
    val infoToggle = doc.selectFirst("button[aria-controls=large-info]")
    val nextToggle = doc.selectFirst("button[aria-controls=large-next]")
    (info must not(beNull)) and
      (info.hasClass("collapse") && info.hasClass("show") must beTrue) and
      (infoToggle.attr("type") must_== "button") and
      (infoToggle.attr("data-bs-toggle") must_== "collapse") and
      (infoToggle.attr("aria-expanded") must_== "true") and
      (infoToggle.hasClass("collapsed") must beFalse) and
      (infoToggle.text must_== "large.file.info") and
      (if (!stripShown) (doc.getElementById("large-next") must beNull) and (nextToggle must beNull)
       else {
         val next = doc.getElementById("large-next")
         (next.hasClass("collapse") must beTrue) and (next.hasClass("show") must beFalse) and
           (nextToggle.attr("aria-expanded") must_== "false") and (nextToggle.hasClass("collapsed") must beTrue) and
           (nextToggle.attr("data-bs-target") must_== "#large-next") and
           (nextToggle.text must_== "large.next.files") and
           (at(doc, info) must be_<(at(doc, nextToggle)))
       }) and
      // the script that keeps their states, and its call after the column
      (doc.select("head script[src*=large-sections.js]").size must_== 1) and
      (doc.select(".large-side > script:not([src])").asScala.map(_.data().trim) must_==
        Seq("if (window.wlxLargeSections) wlxLargeSections.restore(document);"))
  }

  "a round's large view" should {

    "show the file's details right under the stars, before the files after it" in {
      val doc = Jsoup.parse(large(ratedRound, middle))
      val stars = doc.getElementById("rate-image")
      val info = doc.selectFirst("button[aria-controls=large-info]")
      (at(doc, stars) must be_<(at(doc, info))) and
        // nothing between the stars and the details
        (stars.nextElementSibling must_== info.parent) and
        (doc.select("#large-info .large-line ol").size must_== 1) and // the jurors' votes
        (doc.select("#large-info a[href$=/Image_107.jpg]").size must_== 1) and
        checkSections(doc, stripShown = true)
    }

    "show its details under the buttons in a binary round" in {
      val doc = Jsoup.parse(large(binaryRound, middle))
      (doc.selectFirst(".large-nav").nextElementSibling must_== doc.selectFirst("button[aria-controls=large-info]").parent) and
        checkSections(doc, stripShown = true)
    }

    "show the five files after the file in its strip, not the file or those before it" in {
      strip(Jsoup.parse(large(ratedRound, middle))) must_== (middle + 1 to middle + 5).map(pageIdAt)
    }

    "show five at the list's start and fewer near its end" in {
      (strip(Jsoup.parse(large(binaryRound, 0))) must_== (1 to 5).map(pageIdAt)) and
        (strip(Jsoup.parse(large(binaryRound, fileCount - 3))) must_== Seq(pageIdAt(fileCount - 2), pageIdAt(fileCount - 1)))
    }

    "have no strip at the list's last file" in {
      val doc = Jsoup.parse(large(ratedRound, fileCount - 1))
      (doc.select(".thumb_small").size must_== 0) and checkSections(doc, stripShown = false)
    }

    "mark no thumbnail current, as it shows none of the file" in {
      Jsoup.parse(large(binaryRound, middle)).select(".current").size must_== 0
    }
  }

  "the jurors' votes in a binary round" should {

    "show a check for a selection, a cross for a rejection and a dot for a vote yet to come" in {
      implicit val msgs: play.api.i18n.Messages = messages(request("/"))
      val list = controllers.RoundList(binaryRound, 0, view = controllers.GalleryView.ByRate)
      val votes = Seq(1 -> 1, 2 -> -1, 3 -> 0).map { case (id, rate) =>
        (org.intracer.wmua.Selection(101L, id.toLong, binaryRound.getId, rate),
          db.scalikejdbc.User(s"Juror $id", s"j$id@example.org", id = Some(id.toLong)))
      }
      val items = Jsoup.parse(views.html.large.juryStat(votes, list).body).select(".large-line li").asScala
      items.map(li => (li.select(".bi-check-lg").size, li.select(".bi-x-lg").size, li.select(".visually-hidden").text)) must_==
        Seq((1, 0, "selected"), (0, 1, "rejected"), (0, 0, "unrated"))
      items.last.select(".wlx-vote-unrated").text must_== "·"
    }
  }

  "a contest file's large view" should {

    "show the file's details under its buttons, then the files after it" in {
      val doc = Jsoup.parse(contestFile(middle))
      (doc.selectFirst(".large-nav").nextElementSibling must_== doc.selectFirst("button[aria-controls=large-info]").parent) and
        (doc.select("#large-info .file-title").size must_== 1) and
        checkSections(doc, stripShown = true) and
        (strip(doc) must_== (middle + 1 to middle + 5).map(pageIdAt))
    }

    "have no strip at the list's last file" in {
      checkSections(Jsoup.parse(contestFile(fileCount - 1)), stripShown = false)
    }
  }

  "a criteria round's numbers above the stars" should {

    /** Each criterion's number above its stars, and the score its stars are drawn from. */
    def criteria(html: String): Seq[(String, String)] = {
      val doc = Jsoup.parse(html)
      (1 to 2).map { i =>
        val score = raw"(?s)#rate-image$i'\)\.raty\(\{\s*score: ([0-9.]+),".r
          .findFirstMatchIn(html).fold("")(_.group(1))
        doc.getElementById(s"rate-number$i").text -> score
      }
    }

    "show a half star's rate in half stars: 7 as 3.5, 6 as 3" in {
      criteria(large(halfStarCriteriaRound, middle, criteriaRates = Some(Map(1 -> 7, 2 -> 6)))) ===
        Seq("3.5" -> "3.5", "3" -> "3.0")
    }

    "show a whole-star round's rates as stored" in {
      criteria(large(criteriaRound, middle)) === Seq("2" -> "2.0", "3" -> "3.0")
    }
  }

  "the side sections' headings" should {

    "be in every language" in {
      val keys = Seq("large.file.info", "large.next.files")
      for (lang <- Seq("en", "fr", "uk", "ru")) yield {
        val defined = new String(Files.readAllBytes(Paths.get(s"conf/messages.$lang")), UTF_8)
          .linesIterator.map(_.takeWhile(_ != '=').trim).toSet
        keys.filterNot(defined) aka s"keys missing in messages.$lang" must beEmpty
      }
    }
  }
}
