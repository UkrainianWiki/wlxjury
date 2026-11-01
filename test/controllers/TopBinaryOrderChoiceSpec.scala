package controllers

import db.scalikejdbc.{Rates, Round, User}
import org.intracer.wmua.BinaryOrder
import org.jsoup.Jsoup
import play.api.test.{Helpers, PlaySpecification}

import scala.jdk.CollectionConverters._

/** The round form's order of a top-N from a yes/no previous round (topBinaryOrder): offered with
  * a top-N, the stored order selected (the legacy rule, only for a round from before V72, without
  * one), and stored only with a top-N.
  */
class TopBinaryOrderChoiceSpec extends PlaySpecification {
  implicit val messages: play.api.i18n.Messages = Helpers.stubMessages()

  private val base = Map(
    "number" -> "0", "name" -> "Final", "contest" -> "1", "roles" -> "jury",
    "distribution" -> "0", "rates" -> "1", "minMpx" -> "", "minSize" -> "", "mediaType" -> "all",
    "jurors[0]" -> "1", "previousRound[0]" -> "1")

  private def bound(fields: (String, String)*): Option[BinaryOrder] =
    EditRound.editRoundForm.bind(base ++ fields).get.round.topBinaryOrder

  /** The choices (value, text) and the selected value, for a round stored with these. */
  private def choices(
      topImages: Option[Int],
      order: Option[BinaryOrder],
      previousRates: Rates = Round.binaryRound
  ): (Seq[(String, String)], Seq[String]) = {
    val round = new Round(id = Some(2), number = 2, name = Some("Final"), contestId = 1, rates = Round.binaryRound,
      previous = Some("1"), topImages = topImages, topBinaryOrder = order)
    val form = EditRound.editRoundForm.fill(EditRound(round, Seq(1L), None))
    val previous = round.copy(id = Some(1), previous = None, rates = previousRates)
    val html = views.html.round.edit
      .imageFiltering(form, Seq(previous), Map.empty, User("u", "e@e", Some(1L)), noEdit = true)
      .body
    val options = Jsoup.parse(html).select("#selectFilters select#topBinaryOrder option").asScala.toSeq
    (options.map(o => o.attr("value") -> o.text), options.filter(_.hasAttr("selected")).map(_.attr("value")))
  }

  "the top-N order choices" should {
    "offer the three orders with a top-N, among the yes/no round's filters, the default selected" in {
      val (options, selected) = choices(Some(10), Some(BinaryOrder.Default))
      options === Seq("approvals" -> "binary.order.approvals", "difference" -> "binary.order.difference",
        "approvals-only" -> "binary.order.approvals-only")
      selected === Seq("approvals")
    }

    "show the legacy rule, selected, for a round from before V72 (no order stored)" in {
      val (options, selected) = choices(Some(10), None)
      options.map(_._1) === Seq("approvals", "difference", "approvals-only", "legacy")
      options.last._2 === "binary.order.legacy"
      selected === Seq("legacy")
    }

    "show the order a round's top-N was picked by" in {
      choices(Some(10), Some(BinaryOrder.Difference))._2 === Seq("difference")
    }

    "not be offered without a top-N" in {
      choices(None, None)._1 must beEmpty
    }

    "not be shown for a top-N from a rated previous round, where no order picks the files" in {
      choices(Some(10), None, previousRates = Round.ratesById(5))._1 must beEmpty
    }
  }

  "the top-N order field" should {
    "store the chosen order with a top-N, none without one, the default for a missing or unknown one" in {
      (bound("topImages" -> "5", "topBinaryOrder" -> "difference") must beSome(BinaryOrder.Difference: BinaryOrder)) and
        (bound("topImages" -> "5") must beSome(BinaryOrder.Default)) and
        (bound("topBinaryOrder" -> "difference") must beNone) and
        (bound("topImages" -> "5", "topBinaryOrder" -> "nosuch") must beSome(BinaryOrder.Default))
    }

    "read the legacy rule as none, which a new round's submission from a yes/no round makes the default" in {
      val legacy = EditRound.editRoundForm.bind(base ++ Seq("topImages" -> "5", "topBinaryOrder" -> "legacy")).get.round
      (legacy.topBinaryOrder must beNone) and
        (legacy.withExplicitTopOrder(prevBinary = true).topBinaryOrder must beSome(BinaryOrder.Default)) and
        (legacy.copy(topImages = None).withExplicitTopOrder(prevBinary = true).topBinaryOrder must beNone) and
        (legacy.withExplicitTopOrder(prevBinary = false).topBinaryOrder must beNone)
    }
  }
}
