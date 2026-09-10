package controllers

import db.scalikejdbc.{Round, User}
import play.api.test.{Helpers, PlaySpecification}

class PrevRoundHiddenInputSpec extends PlaySpecification {

  implicit val messages: play.api.i18n.Messages = Helpers.stubMessages()

  def round(id: Long) =
    new Round(id = Some(id), number = id, name = Some(s"R$id"), contestId = 619,
      rates = Round.binaryRound)

  private def previousRoundMarkup(noEdit: Boolean): List[String] = {
    val contestRounds = Seq(round(1915), round(1916), round(1920), round(1938))
    val r1938 = round(1938).copy(previous = Some("1916,1920"), prevSelectedBy = Some(1))
    val form = EditRound.editRoundForm.fill(EditRound(r1938, Seq(1L), None))
    views.html.round.edit
      .imageFiltering(form, contestRounds, Map.empty, User("u", "e@e", Some(1L)), noEdit = noEdit)
      .body
      .linesIterator
      .filter(_.contains("previousRound"))
      .toList
  }

  "the previous-round selector on an existing (read-only) round edit" should {
    "not submit any previousRound value (real value is read from the DB)" in {
      val lines = previousRoundMarkup(noEdit = true)
      lines.foreach(println)
      val hidden = lines.filter(l => l.contains("type=\"hidden\"") && l.contains("previousRound"))
      // no live hidden input carrying a bogus first-round id
      hidden.filterNot(_.contains("disabled")) must beEmpty
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
