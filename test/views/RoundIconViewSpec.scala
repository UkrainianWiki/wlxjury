package views

import db.scalikejdbc.Round
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.specs2.mutable.Specification
import play.api.i18n.Messages

import scala.jdk.CollectionConverters._

/** A round's type icons before its name (snippets/roundIcon, roundTypeIcon) and what they say for
  * screen readers after it (snippets/roundSpoken), the maintainer's decisions of 2026-11-19: a
  * yes/no round's ✓ ✗ pair, a rated round's star, a comments-only round's empty slot, all in one
  * slot hidden from screen readers; coloured if active (no "Active" badge), grey if not; the type
  * and the state in the slot's tooltip and, after the name, for screen readers. The statistics
  * pager leaves a comments-only round's empty slot out (roundStat.scala.html: RoundsFilterSpec).
  */
class RoundIconViewSpec extends Specification {

  implicit val messages: Messages = LargeViewRenders.realMessages

  private def round(rates: db.scalikejdbc.Rates, active: Boolean) =
    Round(Some(1), 1, Some("Final"), 1, rates = rates, active = active)

  private val rated = Round.rateRounds.head

  private def slot(r: Round): Element =
    Jsoup.parseBodyFragment(views.html.snippets.roundIcon(r).body).selectFirst("body > span")

  private def icons(e: Element): List[String] =
    e.select("i").asScala.toList.map(_.classNames.asScala.filter(_.startsWith("wlx-round-")).mkString)

  private def spoken(r: Round): String = views.html.snippets.roundSpoken(r).body

  /** The text a screen reader reads for a link of the name with its icons, as in the Rounds table's
    * cell and the round menus' items.
    */
  private def readAs(r: Round): String = {
    val doc = Jsoup.parseBodyFragment(s"<a>${views.html.snippets.roundIcon(r).body}${r.description}${spoken(r)}</a>")
    doc.select("[aria-hidden=true]").remove()
    doc.selectFirst("a").text
  }

  "a round's icon slot" should {

    "show a yes/no round's check and cross, coloured if active, grey if not" in {
      val on = slot(round(Round.binaryRound, active = true))
      val off = slot(round(Round.binaryRound, active = false))
      (on.classNames.asScala.toSet === Set("wlx-round-icon")) and
        (on.attr("data-type") === "binary") and
        (icons(on) === List("wlx-round-yes", "wlx-round-no")) and
        (on.select("i.bi.bi-check-lg + i.bi.bi-x-lg").size === 1) and
        (on.attr("title") === "Yes/No, Active") and
        (off.hasClass("wlx-round-icon-inactive") must beTrue) and
        (icons(off) === List("wlx-round-yes", "wlx-round-no")) and
        (off.attr("title") === "Yes/No, Inactive")
    }

    "show a rated round's star, coloured if active, grey if not" in {
      val on = slot(round(rated, active = true))
      val off = slot(round(rated, active = false))
      (on.attr("data-type") === "rated") and
        (on.hasClass("wlx-round-icon-inactive") must beFalse) and
        (on.select("i.bi.bi-star-fill.wlx-round-star").size === 1) and (on.select("i").size === 1) and
        (on.attr("title") === "Rated, Active") and
        (off.hasClass("wlx-round-icon-inactive") must beTrue) and
        (off.attr("title") === "Rated, Inactive")
    }

    "leave a comments-only round's slot empty, with no tooltip" in {
      val on = slot(round(Round.commentsOnlyRound, active = true))
      val off = slot(round(Round.commentsOnlyRound, active = false))
      (on.attr("data-type") === "none") and (on.children.size === 0) and (on.text === "") and
        (on.hasAttr("title") must beFalse) and
        (off.hasClass("wlx-round-icon-inactive") must beTrue) and (off.children.size === 0)
    }

    "be hidden from screen readers, the text after the name saying the type and Active" in {
      (slot(round(rated, active = true)).attr("aria-hidden") === "true") and
        (readAs(round(Round.binaryRound, active = true)) === "Final, Yes/No, Active") and
        (readAs(round(Round.binaryRound, active = false)) === "Final, Yes/No") and
        (readAs(round(rated, active = true)) === "Final, Rated, Active") and
        (readAs(round(rated, active = false)) === "Final, Rated") and
        (readAs(round(Round.commentsOnlyRound, active = true)) === "Final, Active") and
        (readAs(round(Round.commentsOnlyRound, active = false)) === "Final") and
        (spoken(round(Round.commentsOnlyRound, active = false)) === "")
    }

    "keep no white space around it, so the name follows the slot directly" in {
      val html = views.html.snippets.roundIcon(round(Round.binaryRound, active = true)).body
      (html must startWith("<span")) and (html must endWith("</span>")) and
        (spoken(round(rated, active = true)) must startWith("<span class=\"visually-hidden wlx-round-spoken\">, "))
    }
  }

  "the Type menu's legend" should {

    "show each type's icons coloured, All's slot empty" in {
      def legend(k: Option[controllers.RoundsFilter.Kind]) =
        Jsoup.parseBodyFragment(views.html.snippets.roundTypeIcon(k).body).selectFirst("body > span")
      val binary = legend(Some(controllers.RoundsFilter.Kind.Binary))
      val stars = legend(Some(controllers.RoundsFilter.Kind.Rated))
      val all = legend(None)
      (icons(binary) === List("wlx-round-yes", "wlx-round-no")) and
        (binary.hasClass("wlx-round-icon-inactive") must beFalse) and
        (icons(stars) === List("wlx-round-star")) and
        (all.children.size === 0) and
        (Seq(binary, stars, all).forall(e => e.attr("aria-hidden") == "true" && !e.hasAttr("title")) must beTrue)
    }
  }
}
