package controllers

import db.scalikejdbc.{Round, User}
import org.jsoup.Jsoup
import play.api.test.{Helpers, PlaySpecification}

import scala.jdk.CollectionConverters._

/** The round form's "exclude files rejected by at least X jurors" (rejectedBy, stored as
  * prevRejectedBy), with the yes/no previous round filters: the empty choice named as no filter
  * and stored as None, then 1-6; "0" is no filter too; the stored value selected.
  */
class RejectedByChoiceSpec extends PlaySpecification {
  implicit val messages: play.api.i18n.Messages = Helpers.stubMessages()

  private val base = Map(
    "number" -> "0", "name" -> "Final", "contest" -> "1", "roles" -> "jury",
    "distribution" -> "0", "rates" -> "1", "minMpx" -> "", "minSize" -> "", "mediaType" -> "all",
    "jurors[0]" -> "1")

  private def bound(data: Map[String, String]): Option[Int] =
    EditRound.editRoundForm.bind(base ++ data).get.round.prevRejectedBy

  /** The choices (value, text) and the selected value, for a round stored with `prevRejectedBy`. */
  private def choices(prevRejectedBy: Option[Int]): (Seq[(String, String)], Seq[String]) = {
    val round = new Round(id = Some(2), number = 2, name = Some("Final"), contestId = 1, rates = Round.binaryRound,
      previous = Some("1"), prevRejectedBy = prevRejectedBy)
    val form = EditRound.editRoundForm.fill(EditRound(round, Seq(1L), None))
    val html = views.html.round.edit
      .imageFiltering(form, Seq(round.copy(id = Some(1), previous = None)), Map.empty, User("u", "e@e", Some(1L)),
        noEdit = false)
      .body
    val options = Jsoup.parse(html).select("#selectFilters select#rejectedBy option").asScala.toSeq
    (options.map(o => o.attr("value") -> o.text), options.filter(_.hasAttr("selected")).map(_.attr("value")))
  }

  "the rejected-by choices" should {
    "be with the yes/no filters: the empty choice as no filter, then 1-6" in {
      val (options, selected) = choices(None)
      (options.headOption must beSome("" -> "rejected.by.no.filter")) and
        (options.map(_._1) === Seq("", "1", "2", "3", "4", "5", "6")) and
        (selected === Seq(""))
    }

    "select a stored value" in {
      choices(Some(2))._2 === Seq("2")
    }
  }

  "the rejected-by field" should {
    "store the empty choice, 0 and none as no filter, a number as itself" in {
      (bound(Map("rejectedBy" -> "")) must beNone) and (bound(Map("rejectedBy" -> "0")) must beNone) and
        (bound(Map.empty) must beNone) and (bound(Map("rejectedBy" -> "2")) must beSome(2))
    }
  }
}
