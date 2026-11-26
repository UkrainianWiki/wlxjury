package views

import controllers.EditRound
import db.scalikejdbc.User
import org.jsoup.Jsoup
import org.jsoup.nodes.{Document, Element}
import org.specs2.mutable.Specification
import play.api.i18n.Messages
import play.api.test.Helpers

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

/** Every collapsible shows that it is one (the maintainer's report of 2026-11-26: a closed "Media
  * filtering" read as a plain heading): a chevron before its heading (main.css, .wlx-disclosure),
  * turned by aria-expanded for a Bootstrap collapse's toggle and by [open] for a <details>' summary.
  * The round form's cards (snippets/collapsible.scala.html) have the whole header as the toggle,
  * its aria-expanded as rendered open or closed; the summaries carry the same chevron in place of
  * the browser's triangle.
  */
class CollapsibleIndicatorViewSpec extends Specification {

  implicit val messages: Messages = Helpers.stubMessages()

  private def card(open: Boolean): Document = Jsoup.parseBodyFragment(
    views.html.snippets.collapsible("media.filtering", "headingX", "collapseX", open)(play.twirl.api.Html("<p>body</p>")).body)

  private val base = Map(
    "number" -> "0", "name" -> "Categories", "contest" -> "1", "roles" -> "jury",
    "distribution" -> "0", "rates" -> "1", "minMpx" -> "", "minSize" -> "", "mediaType" -> "all",
    "jurors[0]" -> "1")

  private def filtering(fields: (String, String)*): Document = Jsoup.parseBodyFragment(
    views.html.round.edit.imageFiltering(EditRound.editRoundForm.bind(base ++ fields), Nil, Map.empty,
      User("Admin", "admin@example.com", Some(1L)), noEdit = false).body)

  /** The chevron as the toggle's first child, hidden from screen readers. */
  private def chevron(toggle: Element): Boolean =
    Option(toggle.children.first).exists(i => i.tagName == "i" && i.hasClass("bi-chevron-right") &&
      i.hasClass("wlx-disclosure-icon") && i.attr("aria-hidden") == "true")

  "a round form card" should {
    "be a heading whose whole content is the toggle, with the chevron before the title" in {
      val doc = card(open = false)
      val header = doc.getElementById("headingX")
      val toggle = header.selectFirst("h2 > a[data-bs-toggle=collapse]")
      (header.hasClass("card-header") must beTrue) and (header.hasClass("wlx-card-toggle-header") must beTrue) and
        (toggle.attr("role") === "button") and (toggle.attr("href") === "#collapseX") and
        (toggle.attr("aria-controls") === "collapseX") and (toggle.hasClass("wlx-disclosure") must beTrue) and
        (toggle.hasClass("wlx-card-toggle") must beTrue) and (chevron(toggle) must beTrue) and
        (toggle.text === "media.filtering")
    }

    "say closed when rendered closed and open when rendered open" in {
      val closed = card(open = false).selectFirst("#headingX a")
      val open = card(open = true).selectFirst("#headingX a")
      (closed.attr("aria-expanded") === "false") and (closed.hasClass("collapsed") must beTrue) and
        (card(open = false).getElementById("collapseX").hasClass("show") must beFalse) and
        (open.attr("aria-expanded") === "true") and (open.hasClass("collapsed") must beFalse) and
        (card(open = true).getElementById("collapseX").hasClass("show") must beTrue)
    }
  }

  "the round form's Media filtering" should {
    "have the chevron, closed as before with no category" in {
      val toggle = filtering().selectFirst("#headingImageFiltering a[href=\"#collapseImageFiltering\"]")
      (chevron(toggle) must beTrue) and (toggle.attr("aria-expanded") === "false") and
        (toggle.hasClass("collapsed") must beTrue)
    }

    "open by itself, as before, with a category in its boxes" in {
      val doc = filtering("source" -> "Category:A")
      val toggle = doc.selectFirst("#headingImageFiltering a[href=\"#collapseImageFiltering\"]")
      (chevron(toggle) must beTrue) and (toggle.attr("aria-expanded") === "true") and
        (doc.getElementById("collapseImageFiltering").hasClass("show") must beTrue)
    }

    "give More category options' summary the same chevron" in {
      val summary = filtering().selectFirst("details#category-more > summary")
      (summary.hasClass("wlx-disclosure") must beTrue) and (chevron(summary) must beTrue) and
        (summary.text === "category.more")
    }
  }

  /** Each <summary> of a template, parsed as HTML, so a tag or its chevron written over several
    * lines counts as one; with the number of "<summary" in the source, so none is missed. */
  private def summaries(source: String): (Int, Seq[Element]) =
    ("""<summary\b""".r.findAllIn(source).size, Jsoup.parseBodyFragment(source).select("summary").asScala.toSeq)

  private def indicated(summary: Element): Boolean = summary.hasClass("wlx-disclosure") && chevron(summary)

  "every <summary> in the templates" should {
    "be found when it spans several lines" in {
      val (count, found) = summaries(
        """<details>
          |  <summary
          |      class="wlx-disclosure small">
          |    <i class="bi bi-chevron-right
          |       wlx-disclosure-icon" aria-hidden="true"></i>
          |    @Messages("x")
          |  </summary>
          |</details>
          |<details><summary>plain</summary></details>""".stripMargin)
      (count === 2) and (found.size === 2) and (found.map(indicated) === Seq(true, false))
    }

    "carry the disclosure class and the chevron" in {
      val templates = Files.walk(Paths.get("app/views")).iterator.asScala.filter(_.toString.endsWith(".scala.html")).toSeq
      val found = templates.map { p: Path => p -> summaries(new String(Files.readAllBytes(p), "UTF-8")) }
      (found.map(_._2._1).sum must be_>=(4)) and
        (found.filter { case (_, (count, els)) => count != els.size }.map(_._1) must beEmpty) and
        (found.flatMap { case (p, (_, els)) => els.filterNot(indicated).map(e => p -> e.outerHtml) } must beEmpty)
    }
  }
}
