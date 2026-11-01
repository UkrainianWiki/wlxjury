package controllers

import db.scalikejdbc.{Round, User}
import org.jsoup.Jsoup
import play.api.test.{Helpers, PlaySpecification}

import scala.jdk.CollectionConverters._

/** The round form's "selected by at least X jurors" (minJurors, stored as prevSelectedBy): the
  * empty choice named as no filter and stored as None, then 0 named as what it picks (files with
  * an unrated vote) for every round, then 1-6; a help saying 0 is not "no filter"; the stored
  * value selected.
  */
class MinJurorsChoiceSpec extends PlaySpecification {
  implicit val messages: play.api.i18n.Messages = Helpers.stubMessages()

  private val base = Map(
    "number" -> "0", "name" -> "Final", "contest" -> "1", "roles" -> "jury",
    "distribution" -> "0", "rates" -> "1", "minMpx" -> "", "minSize" -> "", "mediaType" -> "all",
    "jurors[0]" -> "1")

  private def bound(minJurors: String): Option[Int] =
    EditRound.editRoundForm.bind(base + ("minJurors" -> minJurors)).get.round.prevSelectedBy

  /** The image filtering part of the form, for a round stored with `prevSelectedBy`. */
  private def page(prevSelectedBy: Option[Int]) = {
    val round = new Round(id = Some(2), number = 2, name = Some("Final"), contestId = 1, rates = Round.binaryRound,
      previous = Some("1"), prevSelectedBy = prevSelectedBy)
    val form = EditRound.editRoundForm.fill(EditRound(round, Seq(1L), None))
    val html = views.html.round.edit
      .imageFiltering(form, Seq(round.copy(id = Some(1), previous = None)), Map.empty, User("u", "e@e", Some(1L)),
        noEdit = false)
      .body
    Jsoup.parse(html)
  }

  /** The choices (value, text) and the selected value, for a round stored with `prevSelectedBy`. */
  private def choices(prevSelectedBy: Option[Int]): (Seq[(String, String)], Seq[String]) = {
    val options = page(prevSelectedBy).select("select#minJurors option").asScala.toSeq
    (options.map(o => o.attr("value") -> o.text), options.filter(_.hasAttr("selected")).map(_.attr("value")))
  }

  "the minimum jurors choices" should {
    "name the empty choice as no filter, then 0 as files with an unrated vote, for every round" in {
      val (options, selected) = choices(None)
      options.take(2) === Seq("" -> "selected.by.any.number", "0" -> "selected.by.unrated")
      options.map(_._1) === Seq("", "0", "1", "2", "3", "4", "5", "6")
      selected === Seq("")
    }

    "say in its help that 0 is not \"no filter\"" in {
      val doc = page(None)
      (doc.select("#minJurors_help").text === "selected.by.help") and
        (doc.select("select#minJurors").attr("aria-describedby") must contain("minJurors_help"))
    }

    "select a stored value" in {
      choices(Some(2))._2 === Seq("2")
    }

    "select a stored 0, with the same choices" in {
      val (options, selected) = choices(Some(0))
      options === choices(None)._1
      selected === Seq("0")
    }
  }

  "the minimum jurors field" should {
    "store the empty choice as no filter, a number as itself" in {
      (bound("") must beNone) and (bound("2") must beSome(2)) and (bound("0") must beSome(0))
    }
  }
}
