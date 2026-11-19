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

    "open the full resolution and the image's Commons page in a new tab, as the other Commons links" in {
      // the maintainer's browser check of 26.11.13: the full resolution loaded in the same tab
      val doc = Jsoup.parse(large(ratedRound, middle))
      val full = doc.selectFirst("#large-info a[href$=/Image_107.jpg]")
      val image = doc.selectFirst("#mainContent > a[href^=https://commons.wikimedia.org/wiki/]")
      (full.attr("target") must_== "_blank") and (full.attr("rel") must_== "noopener") and
        (image.attr("target") must_== "_blank") and (image.attr("rel") must_== "noopener")
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
        (org.intracer.wmua.Vote(101L, id.toLong, binaryRound.getId, rate),
          db.scalikejdbc.User(s"Juror $id", s"j$id@example.org", id = Some(id.toLong)))
      }
      val items = Jsoup.parse(views.html.large.juryStat(votes, list).body).select(".large-line li").asScala
      items.map(li => (li.select(".bi-check-lg").size, li.select(".bi-x-lg").size, li.select(".visually-hidden").text)) must_==
        Seq((1, 0, "selected"), (0, 1, "rejected"), (0, 0, "unrated"))
      items.last.select(".wlx-vote-unrated").text must_== "·"
    }

    "leave the rank numbers the room they need: three digits from a hundred jurors (main.css)" in {
      implicit val msgs: play.api.i18n.Messages = messages(request("/"))
      val list = controllers.RoundList(binaryRound, 0, view = controllers.GalleryView.ByRate)
      def votes(n: Int) = (1 to n).map { id =>
        (org.intracer.wmua.Vote(101L, id.toLong, binaryRound.getId, 1),
          db.scalikejdbc.User(s"Juror $id", s"j$id@example.org", id = Some(id.toLong)))
      }
      def ol(n: Int) = Jsoup.parse(views.html.large.juryStat(votes(n), list).body).selectFirst(".large-line > ol")
      (ol(99).hasClass("wlx-ranks-3") must beFalse) and (ol(100).hasClass("wlx-ranks-3") must beTrue)
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

    "open its Commons page and its full resolution in a new tab" in {
      val links = Jsoup.parse(contestFile(middle)).select("#large-info .file-title a").asScala.toSeq
      (links.size must_== 2) and
        (links.map(a => (a.attr("target"), a.attr("rel"))) must_== Seq(("_blank", "noopener"), ("_blank", "noopener")))
    }

    "have no strip at the list's last file" in {
      checkSections(Jsoup.parse(contestFile(fileCount - 1)), stripShown = false)
    }
  }

  "a video" should {
    // the file and the one after it are videos (stub messages: the key "video")
    val videos = Set(middle, middle + 1)
    val video = pageIdAt(middle + 1)

    /** The strip's first item (the video after the file): its link, as an image's, and its
      * still thumbnail. */
    def checkStripVideo(doc: Document) = {
      val link = doc.select("#large-next a[href]").first
      val img = link.selectFirst("img.cropped")
      (strip(doc).headOption must beSome(video)) and
        (doc.select("#large-next video").size must_== 0) and
        (img.attr("alt") must_== s"video: File:Video $video.webm") and
        // the image proxy's thumbnail, as the gallery's tiles have it
        (img.attr("src") must beMatching(raw"/wikipedia/commons/thumb/c/cd/Video_$video\.webm/\d+px--Video_$video\.webm\.jpg")) and
        (img.attr("srcset") must contain(" 2x")) and
        (link.select(".wlx-video-mark[aria-hidden=true] i.bi-play-fill").size must_== 1) and
        // the images after it have none
        (doc.select("#large-next .wlx-video-mark").size must_== 1)
    }

    "show as a still thumbnail with a play mark in a round's strip, and play as the file itself" in {
      val doc = Jsoup.parse(large(ratedRound, middle, videos = videos))
      checkStripVideo(doc) and
        (doc.select("#mainContent video source[src$=/Video_" + pageIdAt(middle) + ".webm]").size must_== 1)
    }

    "show no play mark in a contest file's strip once deleted on Commons, as it plays nowhere" in {
      val doc = Jsoup.parse(contestFile(middle, videos = Set(middle + 1), deleted = Set(middle + 1)))
      val box = doc.select("#large-next div.thumb_small").first
      (box.hasClass("wlx-deleted") must beTrue) and
        (box.select("img.cropped").attr("alt") must_== s"video: File:Video $video.webm") and
        (doc.select("#large-next .wlx-video-mark").size must_== 0)
    }

    "show as a still thumbnail with a play mark in a contest file's strip, and play as the file itself" in {
      val doc = Jsoup.parse(contestFile(middle, videos = videos))
      checkStripVideo(doc) and
        (doc.select("#mainContent video source[src$=/Video_" + pageIdAt(middle) + ".webm]").size must_== 1)
    }
  }

  "a criteria round's numbers above the stars" should {

    /** Each criterion's number above its stars, and the score its stars are drawn from. */
    def criteria(html: String): Seq[(String, String)] = {
      val doc = Jsoup.parse(html)
      (1 to 2).map { i =>
        // large-stars.js draws them from their data-score
        val score = doc.getElementById(s"rate-image$i").attr("data-score")
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

    "show the round's criteria in their order by name, one not rated yet with its stars at 0" in {
      val html = large(criteriaRound, middle, criteriaRates = Some(Map(2 -> 4)))
      val doc = Jsoup.parse(html)
      // the names: fallback texts, their keys having no messages
      doc.select(".large-side .large-rate").size === 2
      html.indexOf(">Quality<") must be_>(0)
      html.indexOf(">Quality<") must be_<(html.indexOf(">Composition<"))
      criteria(html) === Seq("–" -> "0.0", "4" -> "4.0")
      doc.getElementById("rate-image1").attr("data-vote-query") must contain("criteria=1")
      doc.getElementById("rate-image2").attr("data-vote-query") must contain("criteria=2")
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
