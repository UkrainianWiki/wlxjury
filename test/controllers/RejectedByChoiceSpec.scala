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
    val options = Jsoup.parse(html).select("#rejectFilters select#rejectedBy option").asScala.toSeq
    (options.map(o => o.attr("value") -> o.text), options.filter(_.hasAttr("selected")).map(_.attr("value")))
  }

  /** The classes of the rejected-by filters for previous rounds `rounds` (round-form.js shows them
    * when one of them is chosen).
    */
  private def rejectClasses(rounds: Seq[Round]): String = {
    val form = EditRound.editRoundForm.fill(EditRound(rounds.head.copy(id = Some(9), number = 9), Seq(1L), None))
    Jsoup.parse(views.html.round.edit.imageFiltering(form, rounds, Map.empty, User("u", "e@e", Some(1L)), noEdit = false)
      .body).getElementById("rejectFilters").className
  }

  "the rejected-by filter" should {
    "be offered from a yes/no round and a rated round without criteria (its worst marks, the option on or off), not a criteria round" in {
      val rated = new Round(id = Some(2), number = 2, contestId = 1, rates = Round.ratesById(5))
      rejectClasses(Seq(
        new Round(id = Some(1), number = 1, contestId = 1, rates = Round.binaryRound),
        rated, rated.copy(id = Some(3), worstMark = true),
        rated.copy(id = Some(4), hasCriteria = true))) === "round1 round2 round3"
    }
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

  "the rejected-by mode (V93)" should {
    def round(data: Map[String, String]) = EditRound.editRoundForm.bind(base ++ data).get.round

    "exclude for a client without it (the meaning before V93), keep only with \"only\", none with \"none\"" in {
      (round(Map("rejectedBy" -> "2")).prevRejectedMode === org.intracer.wmua.RejectedMode.Exclude) and
        (round(Map("rejectedBy" -> "2", "rejectedMode" -> "exclude")).prevRejectedMode === org.intracer.wmua.RejectedMode.Exclude) and
        (round(Map("rejectedBy" -> "2", "rejectedMode" -> "only")).prevRejectedMode === org.intracer.wmua.RejectedMode.Only) and
        (round(Map("rejectedBy" -> "2", "rejectedMode" -> "only")).prevRejectedBy must beSome(2)) and
        (round(Map("rejectedMode" -> "none")).prevRejectedBy must beNone) and (round(Map.empty).prevRejectedBy must beNone)
    }

    "refuse a mode without X, and X with \"none\", by their messages" in {
      def errors(data: Map[String, String]) = EditRound.editRoundForm.bind(base ++ data).globalErrors.map(_.message)
      (errors(Map("rejectedMode" -> "exclude")) === Seq(EditRound.RejectedByCountMissing)) and
        (errors(Map("rejectedMode" -> "only", "rejectedBy" -> "")) === Seq(EditRound.RejectedByCountMissing)) and
        (errors(Map("rejectedMode" -> "none", "rejectedBy" -> "2")) === Seq(EditRound.RejectedByWithoutMode)) and
        (errors(Map("rejectedMode" -> "none")) must beEmpty)
    }

    "refuse an unknown mode" in {
      (EditRound.editRoundForm.bind(base ++ Map("rejectedBy" -> "2", "rejectedMode" -> "all")).hasErrors must beTrue) and
        (EditRound.editRoundForm.bind(base ++ Map("rejectedBy" -> "2", "rejectedMode" -> "ONLY")).hasErrors must beTrue)
    }

    "be shown as stored, \"none\" without a filter" in {
      def shown(r: Round) = {
        val form = EditRound.editRoundForm.fill(EditRound(r, Seq(1L), None))
        Jsoup.parse(views.html.round.edit.imageFiltering(form, Seq(r.copy(id = Some(1), previous = None)), Map.empty,
          User("u", "e@e", Some(1L)), noEdit = false).body)
          .select("#rejectFilters select#rejectedMode option[selected]").asScala.map(_.attr("value")).toSeq
      }
      val stored = new Round(id = Some(2), number = 2, contestId = 1, rates = Round.binaryRound, previous = Some("1"))
      (shown(stored.copy(prevRejectedBy = Some(2), prevRejectedMode = org.intracer.wmua.RejectedMode.Only)) === Seq("only")) and
        (shown(stored.copy(prevRejectedBy = Some(2))) === Seq("exclude")) and (shown(stored) === Seq("none"))
    }
  }
}
