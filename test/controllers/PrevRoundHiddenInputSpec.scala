package controllers

import db.scalikejdbc.{Round, User}
import play.api.test.{Helpers, PlaySpecification}

class PrevRoundHiddenInputSpec extends PlaySpecification {

  implicit val messages: play.api.i18n.Messages = Helpers.stubMessages()

  def round(id: Long) =
    new Round(id = Some(id), number = id, name = Some(s"R$id"), contestId = 619,
      rates = Round.binaryRound)

  /** The form's lines naming `field`, for round 1938 from 1916 and 1920, leaving out 1915's files. */
  private def markup(noEdit: Boolean, field: String): List[String] = {
    val contestRounds = Seq(round(1915), round(1916), round(1920), round(1938))
    val r1938 = round(1938).copy(previous = Some("1916,1920"), prevSelectedBy = Some(1), excludeRounds = Seq(1915L))
    val form = EditRound.editRoundForm.fill(EditRound(r1938, Seq(1L), None))
    views.html.round.edit
      .imageFiltering(form, contestRounds, Map.empty, User("u", "e@e", Some(1L)), noEdit = noEdit)
      .body
      .linesIterator
      .filter(_.contains(field))
      .toList
  }

  private def previousRoundMarkup(noEdit: Boolean): List[String] = markup(noEdit, "previousRound")

  "the previous-round selector on an existing (read-only) round edit" should {
    "not submit any previousRound value (real value is read from the DB)" in {
      val lines = previousRoundMarkup(noEdit = true)
      lines.foreach(println)
      val hidden = lines.filter(l => l.contains("type=\"hidden\"") && l.contains("previousRound"))
      // no live hidden input carrying a bogus first-round id
      hidden.filterNot(_.contains("disabled")) must beEmpty
    }
  }

  "the rounds-to-leave-out selector (V95) on an existing (read-only) round edit" should {
    "not submit any excludeRounds value: disabled, with no live hidden input (the stored value stays)" in {
      val lines = markup(noEdit = true, "excludeRounds")
      val hidden = lines.filter(l => l.contains("type=\"hidden\""))
      (hidden.filterNot(_.contains("disabled")) must beEmpty) and
        (lines.exists(l => l.contains("<select") && l.contains("excludeRounds[]") && l.contains("disabled")) must beTrue)
    }
  }

  "the rounds-to-leave-out selector on a new round" should {
    "be an editable multi-select" in {
      val lines = markup(noEdit = false, "excludeRounds")
      (lines.exists(l => l.contains("<select") && l.contains("excludeRounds[]")) must beTrue) and
        (lines.exists(l => l.contains("<select") && l.contains("disabled")) must beFalse)
    }
  }

  "the previous-round selector on a new round" should {
    "be an editable multi-select" in {
      val lines = previousRoundMarkup(noEdit = false)
      lines.exists(l => l.contains("<select") && l.contains("previousRound[]")) must beTrue
      lines.exists(l => l.contains("<select") && l.contains("disabled")) must beFalse
    }
  }
}
