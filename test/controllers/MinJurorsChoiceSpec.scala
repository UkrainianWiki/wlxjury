package controllers

import db.scalikejdbc.{Round, User}
import org.jsoup.Jsoup
import play.api.test.{Helpers, PlaySpecification}

import scala.jdk.CollectionConverters._

/** The round form's "selected by at least X jurors" (minJurors, stored as prevSelectedBy): no
  * plain 0, the empty choice named as no filter and stored as None, a stored 0 kept as a legacy
  * choice only for a round that has it, and the stored value selected.
  */
class MinJurorsChoiceSpec extends PlaySpecification {
  implicit val messages: play.api.i18n.Messages = Helpers.stubMessages()

  private val base = Map(
    "number" -> "0", "name" -> "Final", "contest" -> "1", "roles" -> "jury",
    "distribution" -> "0", "rates" -> "1", "minMpx" -> "", "minSize" -> "", "mediaType" -> "all",
    "jurors[0]" -> "1")

  private def bound(minJurors: String): Option[Int] =
    EditRound.editRoundForm.bind(base + ("minJurors" -> minJurors)).get.round.prevSelectedBy

  /** The choices (value, text) and the selected value, for a round stored with `prevSelectedBy`. */
  private def choices(prevSelectedBy: Option[Int]): (Seq[(String, String)], Seq[String]) = {
    val round = new Round(id = Some(2), number = 2, name = Some("Final"), contestId = 1, rates = Round.binaryRound,
      previous = Some("1"), prevSelectedBy = prevSelectedBy)
    val form = EditRound.editRoundForm.fill(EditRound(round, Seq(1L), None))
    val html = views.html.round.edit
      .imageFiltering(form, Seq(round.copy(id = Some(1), previous = None)), Map.empty, User("u", "e@e", Some(1L)),
        noEdit = false)
      .body
    val options = Jsoup.parse(html).select("select#minJurors option").asScala.toSeq
    (options.map(o => o.attr("value") -> o.text), options.filter(_.hasAttr("selected")).map(_.attr("value")))
  }

  "the minimum jurors choices" should {
    "name the empty choice as no filter, with no plain 0" in {
      val (options, selected) = choices(None)
      options.head === ("" -> "selected.by.any.number")
      options.map(_._1) === Seq("", "1", "2", "3", "4", "5", "6")
      selected === Seq("")
    }

    "select a stored value" in {
      choices(Some(2))._2 === Seq("2")
    }

    "keep a stored 0 as a legacy choice, selected, only for a round that has it" in {
      val (options, selected) = choices(Some(0))
      options.map(_._1) === Seq("", "0", "1", "2", "3", "4", "5", "6")
      options(1) === ("0" -> "selected.by.legacy.zero")
      selected === Seq("0")
    }
  }

  "the minimum jurors field" should {
    "store the empty choice as no filter, a number as itself" in {
      (bound("") must beNone) and (bound("2") must beSome(2)) and (bound("0") must beSome(0))
    }
  }
}
