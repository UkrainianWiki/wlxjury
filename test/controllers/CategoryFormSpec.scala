package controllers

import db.scalikejdbc.{Round, User}
import play.api.data.Form
import play.api.test.{Helpers, PlaySpecification}

class CategoryFormSpec extends PlaySpecification {
  implicit val messages: play.api.i18n.Messages = Helpers.stubMessages()
  private val base = Map(
    "number" -> "0", "name" -> "Categories", "contest" -> "1", "roles" -> "jury",
    "distribution" -> "0", "rates" -> "1", "minMpx" -> "", "minSize" -> "", "mediaType" -> "all",
    "jurors[0]" -> "1")
  private def bind(fields: (String, String)*) = EditRound.editRoundForm.bind(base ++ fields)
  private def categoryHtml(form: Form[EditRound]) = views.html.round.edit.imageFiltering(
    form, Nil, Map.empty, User("Admin", "admin@example.com", Some(1L)), noEdit = false).body
  private def selectedOperation(html: String): Option[String] = {
    val select = """(?s)<select[^>]*id=["']categoryClause["'][^>]*>(.*?)</select>""".r
      .findFirstMatchIn(html).map(_.group(1)).getOrElse("")
    """<option(?=[^>]*\bselected\b)[^>]*value=["']([^"']*)["'][^>]*>""".r
      .findFirstMatchIn(select).map(_.group(1))
  }

  "the category form" should {
    Seq("and", "or", "minus").foreach { clause =>
      s"bind both operands and operation $clause" in {
        val bound = bind("categoryClause" -> clause.toString, "source" -> "A", "excludeCategory" -> "B")
        bound.hasErrors must beFalse
        val round = bound.value.get.round
        (round.categoryClause === Some(clause)) and (round.category === Some("Category:A")) and
          (round.excludeCategory === Some("Category:B"))
      }

      s"require both operands for operation $clause" in {
        val withoutA = bind("categoryClause" -> clause.toString, "excludeCategory" -> "B")
        val withoutB = bind("categoryClause" -> clause.toString, "source" -> "A")
        (withoutA.hasErrors must beTrue) and (withoutB.hasErrors must beTrue)
      }
    }

    Seq("include", "exclude").foreach { clause =>
      s"bind unary operation $clause and clear an unused second operand" in {
        val bound = bind("categoryClause" -> clause.toString, "source" -> "A", "excludeCategory" -> "Unused")
        bound.hasErrors must beFalse
        val round = bound.value.get.round
        (round.categoryClause === Some(clause)) and (round.category === Some("Category:A")) and
          (round.excludeCategory must beNone)
      }

      s"require the first operand for unary operation $clause" in {
        bind("categoryClause" -> clause.toString).hasErrors must beTrue
      }
    }

    "reject whitespace-only operands and unsupported operations" in {
      (bind("categoryClause" -> "and", "source" -> "   ", "excludeCategory" -> "B").hasErrors must beTrue) and
        (bind("categoryClause" -> "999", "source" -> "A").hasErrors must beTrue) and
        (bind("categoryClause" -> "unexpected", "source" -> "A").hasErrors must beTrue)
    }

    "clear category settings when no filter is explicitly selected" in {
      val bound = bind("categoryClause" -> "none", "source" -> "A", "excludeCategory" -> "B")
      bound.hasErrors must beFalse
      val round = bound.value.get.round
      (round.category must beNone) and (round.excludeCategory must beNone) and (round.categoryClause must beNone)
    }

    "continue binding old requests that do not send a clause" in {
      val bound = bind("source" -> "A", "excludeCategory" -> "B")
      bound.hasErrors must beFalse
      val round = bound.value.get.round
      (round.category === Some("Category:A")) and (round.excludeCategory === Some("Category:B")) and
        (round.categoryClause must beNone)
    }

    "display the effective operation for legacy settings" in {
      val cases = Seq(
        (Some("Category:A"), None, Some("-1"), "exclude", Some("Category:A"), None),
        (None, Some("Category:B"), None, "exclude", Some("Category:B"), None),
        (Some("Category:A"), None, None, "include", Some("Category:A"), None),
        (Some("Category:A"), Some("Category:B"), None, "minus", Some("Category:A"), Some("Category:B"))
      )
      cases.map { case (first, second, clause, operation, shownFirst, shownSecond) =>
        val form = EditRound.editRoundForm.fill(EditRound(
          Round(Some(1), 1, Some("Old"), 1, category = first, excludeCategory = second, categoryClause = clause), Seq(1)))
        (form("categoryClause").value === Some(operation)) and
          (form("source").value === shownFirst) and (form("excludeCategory").value === shownSecond) and
          (selectedOperation(categoryHtml(form)) === Some(operation))
      }.reduce(_ and _)
    }

    "keep an implicit legacy operation when another field fails validation" in {
      val form = EditRound.editRoundForm.bind((base - "rates") ++ Map(
        "source" -> "Category:A", "excludeCategory" -> "Category:B"))
      form.hasErrors must beTrue
      selectedOperation(categoryHtml(form)) === Some("minus")
    }

    "show an old numeric operation correctly on a failed form" in {
      val form = EditRound.editRoundForm.bind((base - "rates") ++ Map(
        "categoryClause" -> "-1", "source" -> "Category:A"))
      form.hasErrors must beTrue
      selectedOperation(categoryHtml(form)) === Some("exclude")
    }

    "normalize legacy exclusion-only display without losing the existing form errors" in {
      val form = EditRound.editRoundForm.bind((base - "rates") ++ Map("excludeCategory" -> "Category:B"))
      form.hasErrors must beTrue
      val displayed = EditRound.categoryDisplay(form)
      (displayed.errors === form.errors) and
        (displayed("source").value === Some("Category:B")) and
        (displayed("excludeCategory").value must beNone) and
        (selectedOperation(categoryHtml(form)) === Some("exclude"))
    }
  }
}
