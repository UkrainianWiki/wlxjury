package controllers

import db.scalikejdbc.{Round, User}
import org.intracer.wmua.{CategoryBoxes, CategoryCombine, CategoryOperation, CategoryRule}
import play.api.data.Form
import play.api.test.{Helpers, PlaySpecification}

/** The round form's category filter: two boxes, "Only files in category" (`source`) and the second
  * box (`excludeCategory`), whose meaning the choice under "More category options"
  * (`categoryCombine`) gives. Every stored rule renders in them and is saved back as it was; older
  * clients posting an operation (`categoryClause`) or neither are bound as before.
  */
class CategoryFormSpec extends PlaySpecification {
  implicit val messages: play.api.i18n.Messages = Helpers.stubMessages()
  private val base = Map(
    "number" -> "0", "name" -> "Categories", "contest" -> "1", "roles" -> "jury",
    "distribution" -> "0", "rates" -> "1", "minMpx" -> "", "minSize" -> "", "mediaType" -> "all",
    "jurors[0]" -> "1")
  private val A = Some("Category:A")
  private val B = Some("Category:B")
  private def bind(fields: (String, String)*) = EditRound.editRoundForm.bind(base ++ fields)
  private def categoryHtml(form: Form[EditRound], noEdit: Boolean = false) = views.html.round.edit.imageFiltering(
    form, Nil, Map.empty, User("Admin", "admin@example.com", Some(1L)), noEdit = noEdit).body
  private def stored(round: Round) = (round.category, round.excludeCategory, round.categoryClause)
  private def filled(round: Round) = EditRound.editRoundForm.fill(EditRound(round, Seq(1)))
  private def oldRound(category: Option[String], second: Option[String], clause: Option[String]) =
    Round(Some(1), 1, Some("Old"), 1, category = category, excludeCategory = second, categoryClause = clause)

  private val CategoryFields = Set("source", "excludeCategory", "categoryCombine", "categoryClause")

  private def attr(tag: String, name: String): Option[String] =
    s"""\\b$name="([^"]*)"""".r.findFirstMatchIn(tag).map(_.group(1))
  private def flag(tag: String, name: String): Boolean = s"""\\s$name(?=[\\s>=])""".r.findFirstIn(tag).isDefined
  private def input(html: String, id: String): String =
    s"""<input\\b[^>]*\\bid="$id"[^>]*>""".r.findFirstIn(html).getOrElse(s"no input $id")

  /** What the rendered form submits of the category fields: the enabled inputs, a radio if checked. */
  private def submitted(html: String): Seq[(String, String)] =
    """<input\b[^>]*>""".r.findAllIn(html).toSeq.flatMap { tag =>
      attr(tag, "name").filter(CategoryFields).filterNot(_ => flag(tag, "disabled"))
        .filterNot(_ => attr(tag, "type").contains("radio") && !flag(tag, "checked"))
        .map(_ -> attr(tag, "value").getOrElse(""))
    }

  private def checkedChoice(html: String): Option[String] =
    """<input\b[^>]*name="categoryCombine"[^>]*>""".r.findAllIn(html).find(flag(_, "checked")).flatMap(attr(_, "value"))
  private def moreOpen(html: String): Boolean =
    """<details\b[^>]*id="category-more"[^>]*>""".r.findFirstIn(html).exists(flag(_, "open"))
  private def filteringOpen(html: String): Boolean =
    """<div id="collapseImageFiltering" class="collapse show"""".r.findFirstIn(html).isDefined
  private def secondLabel(html: String): Option[String] =
    """<label[^>]*for="excludeCategory"[^>]*>([^<]*)</label>""".r.findFirstMatchIn(html).map(_.group(1))

  "the category boxes" should {
    import CategoryCombine._
    import CategoryOperation._

    "give each combination its rule (the mapping table)" in {
      val table = Seq(
        (None, None, LeaveOut, CategoryRule(Unrestricted, None, None)),
        (A, None, LeaveOut, CategoryRule(Include, A, None)),
        (None, B, LeaveOut, CategoryRule(Exclude, B, None)),
        (A, B, LeaveOut, CategoryRule(Minus, A, B)),
        (A, B, InBoth, CategoryRule(And, A, B)),
        (A, B, InEither, CategoryRule(Union, A, B)),
        (None, None, InBoth, CategoryRule(Unrestricted, None, None)),
        (None, None, InEither, CategoryRule(Unrestricted, None, None)),
        (A, None, InBoth, CategoryRule(And, A, None)),
        (None, B, InEither, CategoryRule(Union, None, B))
      )
      table.map { case (first, second, combine, rule) => CategoryBoxes(first, second, combine).rule === rule }
        .reduce(_ and _)
    }

    "show every operation in the boxes it is saved from" in {
      Seq(
        CategoryRule(Unrestricted, None, None), CategoryRule(Include, A, None), CategoryRule(Exclude, A, None),
        CategoryRule(And, A, B), CategoryRule(Union, A, B), CategoryRule(Minus, A, B)
      ).map(rule => CategoryBoxes.ofRule(rule).rule === rule).reduce(_ and _)
    }
  }

  "the category form" should {
    // the stored rules as the form saves them, then legacy ones (no operation, or a numeric one)
    val canonical = Seq(
      (None, None, None), (A, None, Some("include")), (A, None, Some("exclude")),
      (A, B, Some("and")), (A, B, Some("or")), (A, B, Some("minus")))
    val legacy = Seq(
      (A, None, None), (None, B, None), (A, B, None), (A, None, Some("-1")), (A, None, Some("1")), (A, None, Some("0")))

    for ((first, second, clause) <- canonical ++ legacy; noEdit <- Seq(false, true)) {
      val mode = if (noEdit) "read only" else "editable"
      s"render and save ${clause.getOrElse("no operation")} of ($first, $second) unchanged, $mode" in {
        val round = oldRound(first, second, clause)
        val rule = round.categoryRule
        val html = categoryHtml(filled(round), noEdit)
        val posted = submitted(html)
        val bound = bind(posted: _*)
        // what the operation select posted before: the effective operation and its operands
        val before = bind("categoryClause" -> rule.operation.formValue,
          "source" -> rule.first.getOrElse(""), "excludeCategory" -> rule.second.getOrElse(""))
        (posted.map(_._1) must not contain "categoryClause") and
          (posted.map(_._1).distinct.sorted === Seq("categoryCombine", "excludeCategory", "source")) and
          (bound.hasErrors must beFalse) and
          (stored(bound.value.get.round) === stored(before.value.get.round)) and
          (stored(bound.value.get.round.withCanonicalCategoryRule) === stored(round.withCanonicalCategoryRule)) and
          (filteringOpen(html) === (rule.operation != CategoryOperation.Unrestricted)) and
          (moreOpen(html) === Set[CategoryOperation](CategoryOperation.And, CategoryOperation.Union).contains(rule.operation))
      }
    }

    "show an exclusion's category in the second box, and the choice of an intersection or union" in {
      val exclude = categoryHtml(filled(oldRound(A, None, Some("exclude"))))
      val excludeOnly = categoryHtml(filled(oldRound(None, B, None)))
      val and = categoryHtml(filled(oldRound(A, B, Some("and"))))
      val or = categoryHtml(filled(oldRound(A, B, Some("or"))))
      (input(exclude, "source") must not contain "value=") and
        (input(exclude, "excludeCategory") must contain("value=\"Category:A\"")) and
        (input(excludeOnly, "excludeCategory") must contain("value=\"Category:B\"")) and
        (checkedChoice(exclude) === Some("minus")) and (secondLabel(exclude) === Some("category.leave.out")) and
        (checkedChoice(and) === Some("and")) and (secondLabel(and) === Some("category.also.in")) and
        (checkedChoice(or) === Some("or")) and (secondLabel(or) === Some("category.or.in"))
    }

    "label the boxes, describe the first by its hint, and keep the operation select out" in {
      val html = categoryHtml(EditRound.editRoundForm)
      ("""<label[^>]*for="source"[^>]*>category.only</label>""".r.findFirstIn(html) must beSome) and
        (secondLabel(html) === Some("category.leave.out")) and
        (input(html, "source") must contain("""aria-describedby="source_help"""")) and
        (html must contain("""id="source_help"""")) and
        (input(html, "source") must contain("""data-category-suggest="category"""")) and
        (input(html, "excludeCategory") must contain("""data-category-suggest="category"""")) and
        (html must not contain "categoryClause") and
        ("""<summary>category.more</summary>""".r.findFirstIn(html) must beSome) and
        ("""<legend[^>]*>category.combine</legend>""".r.findFirstIn(html) must beSome) and
        (checkedChoice(html) === Some("minus")) and (moreOpen(html) must beFalse) and
        (filteringOpen(html) must beFalse) and
        (CategoryCombine.values.map(c => input(html, s"categoryCombine-${c.value}"))
          .map(tag => (tag must contain("data-second-label=")) and (tag must not contain "disabled")).reduce(_ and _))
    }

    "keep the category read only on a round that can't be edited, the choice submitted by a hidden input" in {
      val html = categoryHtml(filled(oldRound(A, B, Some("and"))), noEdit = true)
      val radios = CategoryCombine.values.map(c => input(html, s"categoryCombine-${c.value}"))
      (input(html, "source") must contain("readonly")) and (input(html, "excludeCategory") must contain("readonly")) and
        radios.map(tag => tag must contain("disabled")).reduce(_ and _) and
        ("""<input type="hidden" name="categoryCombine" value="and">""".r.findFirstIn(html) must beSome)
    }

    "bind the boxes with each choice to the same stored rule as the operation select did" in {
      val cases = Seq(
        (Seq("categoryCombine" -> "minus"), "none"),
        (Seq("categoryCombine" -> "minus", "source" -> "A"), "include"),
        (Seq("categoryCombine" -> "minus", "excludeCategory" -> "A"), "exclude"),
        (Seq("categoryCombine" -> "minus", "source" -> "A", "excludeCategory" -> "B"), "minus"),
        (Seq("categoryCombine" -> "and", "source" -> "A", "excludeCategory" -> "B"), "and"),
        (Seq("categoryCombine" -> "or", "source" -> "A", "excludeCategory" -> "B"), "or"),
        (Seq("categoryCombine" -> "and"), "none"),
        (Seq("categoryCombine" -> "or"), "none")
      )
      cases.map { case (fields, operation) =>
        val boxes = fields.toMap
        val bound = bind(fields: _*)
        val operands = if (operation == "exclude") Seq("source" -> "A") else (boxes - "categoryCombine").toSeq
        val before = bind((("categoryClause" -> operation) +: operands): _*)
        (bound.hasErrors must beFalse) and (stored(bound.value.get.round) === stored(before.value.get.round))
      }.reduce(_ and _)
    }

    Seq("and", "or").foreach { choice =>
      s"ask for the box that $choice needs, in words of the boxes" in {
        val withoutSecond = bind("categoryCombine" -> choice, "source" -> "A")
        val withoutFirst = bind("categoryCombine" -> choice, "excludeCategory" -> "B")
        (withoutSecond.globalErrors.map(_.message) === Seq("error.category.second.required")) and
          (withoutFirst.globalErrors.map(_.message) === Seq("error.category.first.required"))
      }
    }

    "refuse a choice that is none of the three" in {
      bind("categoryCombine" -> "xor", "source" -> "A").errors("categoryCombine").map(_.message) ===
        Seq("error.category.operation")
    }

    "show an invalid choice's or an older client's operation's error with More open" in {
      Seq("categoryCombine" -> "xor", "categoryClause" -> "999").map { field =>
        val form = bind(field, "source" -> "A")
        val html = categoryHtml(form)
        val more = """(?s)<details\b[^>]*id="category-more".*?</details>""".r.findFirstIn(html).getOrElse("")
        (form.errors(field._1).map(_.message) === Seq("error.category.operation")) and
          (moreOpen(html) must beTrue) and (more must contain("error.category.operation"))
      }.reduce(_ and _)
    }

    "let an older client's operation win over a choice posted with it" in {
      val include = bind("categoryClause" -> "include", "categoryCombine" -> "or", "source" -> "A", "excludeCategory" -> "B")
      val minus = bind("categoryClause" -> "minus", "categoryCombine" -> "and", "source" -> "A", "excludeCategory" -> "B")
      (include.hasErrors must beFalse) and
        (stored(include.value.get.round) === (Some("Category:A"), None, Some("include"))) and
        (stored(minus.value.get.round) === (Some("Category:A"), Some("Category:B"), Some("minus")))
    }

    "show a refused form's boxes and choice as posted, Media filtering and More open" in {
      val form = EditRound.editRoundForm.bind((base - "rates") ++ Map(
        "categoryCombine" -> "or", "source" -> "Category:A"))
      val html = categoryHtml(form)
      (form.hasErrors must beTrue) and (checkedChoice(html) === Some("or")) and (moreOpen(html) must beTrue) and
        (filteringOpen(html) must beTrue) and (input(html, "source") must contain("value=\"Category:A\"")) and
        (secondLabel(html) === Some("category.or.in"))
    }

    "save the plain title of a pasted wiki link" in {
      val bound = bind("categoryCombine" -> "minus", "source" -> "[[:Category:A_b|key]]",
        "excludeCategory" -> " [[Category:B]] ")
      bound.hasErrors must beFalse
      val round = bound.value.get.round
      (round.category === Some("Category:A b")) and (round.excludeCategory === Some("Category:B")) and
        (round.categoryClause === Some("minus"))
    }

    "give a field error, never an exception, for a title that cannot be one" in {
      val bound = bind("categoryCombine" -> "minus", "source" -> "[[[Category:A#B]]]")
      (bound.hasErrors must beTrue) and
        (bound.errors("source").map(_.message) === Seq(services.imports.WikiTitle.InvalidKey)) and
        (categoryHtml(bound) must contain("[[[Category:A#B]]]"))
    }

    // older clients and scripts: an operation (`categoryClause`), or neither it nor a choice
    Seq("and", "or", "minus").foreach { clause =>
      s"bind both operands and an older client's operation $clause" in {
        val bound = bind("categoryClause" -> clause, "source" -> "A", "excludeCategory" -> "B")
        bound.hasErrors must beFalse
        val round = bound.value.get.round
        (round.categoryClause === Some(clause)) and (round.category === Some("Category:A")) and
          (round.excludeCategory === Some("Category:B"))
      }

      s"require both operands for an older client's operation $clause" in {
        val withoutA = bind("categoryClause" -> clause, "excludeCategory" -> "B")
        val withoutB = bind("categoryClause" -> clause, "source" -> "A")
        (withoutA.hasErrors must beTrue) and (withoutB.hasErrors must beTrue)
      }
    }

    Seq("include", "exclude").foreach { clause =>
      s"bind an older client's unary operation $clause and clear an unused second operand" in {
        val bound = bind("categoryClause" -> clause, "source" -> "A", "excludeCategory" -> "Unused")
        bound.hasErrors must beFalse
        val round = bound.value.get.round
        (round.categoryClause === Some(clause)) and (round.category === Some("Category:A")) and
          (round.excludeCategory must beNone)
      }

      s"require the first operand for an older client's unary operation $clause" in {
        bind("categoryClause" -> clause).hasErrors must beTrue
      }
    }

    "reject an older client's whitespace-only operands and unsupported operations" in {
      (bind("categoryClause" -> "and", "source" -> "   ", "excludeCategory" -> "B").hasErrors must beTrue) and
        (bind("categoryClause" -> "999", "source" -> "A").hasErrors must beTrue) and
        (bind("categoryClause" -> "unexpected", "source" -> "A").hasErrors must beTrue)
    }

    "clear category settings when an older client explicitly selects no filter" in {
      val bound = bind("categoryClause" -> "none", "source" -> "A", "excludeCategory" -> "B")
      bound.hasErrors must beFalse
      val round = bound.value.get.round
      (round.category must beNone) and (round.excludeCategory must beNone) and (round.categoryClause must beNone)
    }

    "continue binding old requests that send neither an operation nor a choice" in {
      val bound = bind("source" -> "A", "excludeCategory" -> "B")
      bound.hasErrors must beFalse
      val round = bound.value.get.round
      (round.category === Some("Category:A")) and (round.excludeCategory === Some("Category:B")) and
        (round.categoryClause must beNone)
    }

    "show an old request's implicit operation in the boxes when another field fails validation" in {
      val form = EditRound.editRoundForm.bind((base - "rates") ++ Map(
        "source" -> "Category:A", "excludeCategory" -> "Category:B"))
      val html = categoryHtml(form)
      (form.hasErrors must beTrue) and (checkedChoice(html) === Some("minus")) and
        (input(html, "source") must contain("value=\"Category:A\"")) and
        (input(html, "excludeCategory") must contain("value=\"Category:B\""))
    }

    "show an old numeric exclusion in the second box on a failed form" in {
      val form = EditRound.editRoundForm.bind((base - "rates") ++ Map(
        "categoryClause" -> "-1", "source" -> "Category:A"))
      val html = categoryHtml(form)
      (form.hasErrors must beTrue) and (input(html, "source") must not contain "value=") and
        (input(html, "excludeCategory") must contain("value=\"Category:A\"")) and (checkedChoice(html) === Some("minus"))
    }

    "show an old exclusion-only request without losing the form's errors" in {
      val form = EditRound.editRoundForm.bind((base - "rates") ++ Map("excludeCategory" -> "Category:B"))
      val view = EditRound.categoryBoxes(form)
      (form.hasErrors must beTrue) and (view.form.errors === form.errors) and
        (view.form("source").value must beNone) and (view.form("excludeCategory").value === Some("Category:B")) and
        (view.combine === CategoryCombine.LeaveOut) and (view.hasCategory must beTrue)
    }
  }
}
